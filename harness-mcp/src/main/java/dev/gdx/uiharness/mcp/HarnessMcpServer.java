package dev.gdx.uiharness.mcp;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransport;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.gdx.uiharness.protocol.HarnessProtocolService;
import dev.gdx.uiharness.protocol.HarnessRequest;
import dev.gdx.uiharness.protocol.ProtocolJson;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.publisher.Mono;

/** MCP SDK 2.0 server exposing only the fixed harness tool catalog over stdio. */
public final class HarnessMcpServer implements AutoCloseable {
    /**
     * Fixed bounded allowance translating an accepted scenario deadline into the SDK's outer
     * request timeout, covering protocol translation and dispatch before the scenario's own
     * validated deadline starts governing.
     */
    private static final Duration OUTER_TRANSLATION_ALLOWANCE = Duration.ofSeconds(30);

    /**
     * SDK outer request timeout: the full published scenario maximum plus the bounded
     * translation allowance, so a {@code ui_scenario_start} with {@code maxDurationMillis}
     * above the default request deadline is never aborted by the outer SDK timeout.
     */
    static final Duration OUTER_REQUEST_TIMEOUT = Duration.ofMillis(
            HarnessRequest.MAX_SCENARIO_DEADLINE_MILLIS + OUTER_TRANSLATION_ALLOWANCE.toMillis());

    /**
     * Transport-level admission cap aligned with the handler's global admission limit: at most
     * this many messages may be dispatched onto virtual threads at once. Each admitted task
     * blocks through {@code handle(message).block()}, which completes only after the response
     * send finishes, so the output queue stays bounded by the same cap.
     */
    static final int TRANSPORT_ADMISSION_LIMIT = RequestAdmission.DEFAULT_GLOBAL_LIMIT;

    /**
     * JSON-RPC error code for a request rejected at the transport boundary because the bounded
     * admission slots are full. Uses the JSON-RPC implementation-defined server-error range
     * (-32000..-32099), distinct from every code the MCP SDK emits itself.
     */
    static final int TRANSPORT_BUSY_ERROR_CODE = -32000;

    static final String TRANSPORT_BUSY_ERROR_MESSAGE =
            "Admission limit exceeded (limit=" + TRANSPORT_ADMISSION_LIMIT + ")";

    private final VirtualStdioProvider transport;
    private final HarnessToolHandler handler;
    private final McpAsyncServer server;
    private final AtomicBoolean closed = new AtomicBoolean();

    private HarnessMcpServer(HarnessProtocolService protocol,
            ArtifactReference.Publisher artifacts, InputStream input, OutputStream output) {
        if (OUTER_REQUEST_TIMEOUT.toMillis() <= HarnessRequest.MAX_SCENARIO_DEADLINE_MILLIS) {
            throw new IllegalStateException(
                    "the outer request timeout must exceed the maximum accepted scenario deadline");
        }
        transport = new VirtualStdioProvider(input, output);
        // The server owns one admission and wires it into the handler so every tool call is
        // bounded before protocol dispatch.
        handler = new HarnessToolHandler(
                protocol, artifacts, RequestAdmission.serverDefaults());
        HarnessToolCatalog catalog = new HarnessToolCatalog();
        McpServer.AsyncSpecification<?> specification = McpServer.async(transport)
                .serverInfo("libgdx-ui-harness", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .validateToolInputs(false)
                .requestTimeout(OUTER_REQUEST_TIMEOUT);
        for (McpSchema.Tool tool : catalog.tools()) {
            specification.toolCall(tool, (exchange, request) -> handler.handle(request));
        }
        server = specification.build();
    }

    /** Opens the default stdio server; no network listener is created. */
    public static HarnessMcpServer open(HarnessProtocolService protocol,
            ArtifactReference.Publisher artifacts, InputStream input, OutputStream output) {
        return new HarnessMcpServer(
                Objects.requireNonNull(protocol, "protocol"),
                Objects.requireNonNull(artifacts, "artifacts"),
                Objects.requireNonNull(input, "input"),
                Objects.requireNonNull(output, "output"));
    }

    /** Waits until stdin closes or the stdio connection terminates. */
    public void awaitTermination() {
        transport.termination().join();
    }

    /** Closes the SDK server, transport, and owned virtual-thread dispatch. */
    @Override public void close() {
        if (closed.compareAndSet(false, true)) {
            server.close();
            handler.close();
            transport.close();
        }
    }

    /** Minimal SDK transport whose single stdio connection runs on a Java virtual thread. */
    private static final class VirtualStdioProvider implements McpServerTransportProvider {
        private static final String CANCELLED_NOTIFICATION = "notifications/cancelled";
        private final McpJsonMapper mapper = hardenedMapper();
        private final InputStream input;
        private final OutputStream output;
        private final ExecutorService connectionExecutor =
                Executors.newVirtualThreadPerTaskExecutor();
        private final ExecutorService outputExecutor = Executors.newSingleThreadExecutor(
                Thread.ofVirtual().name("mcp-stdio-output").factory());
        private final AtomicBoolean closing = new AtomicBoolean();
        private final CompletableFuture<Void> terminated = new CompletableFuture<>();
        private final Set<MessageTask> inFlight = ConcurrentHashMap.newKeySet();
        private final Map<Object, MessageTask> requests = new ConcurrentHashMap<>();
        private final AtomicInteger dispatched = new AtomicInteger();
        private volatile McpServerSession session;

        private VirtualStdioProvider(InputStream input, OutputStream output) {
            this.input = input;
            this.output = output;
        }

        @Override public void setSessionFactory(McpServerSession.Factory sessionFactory) {
            VirtualStdioTransport sessionTransport = new VirtualStdioTransport();
            session = sessionFactory.create(sessionTransport);
            connectionExecutor.submit(this::readLoop);
        }

        @Override public Mono<Void> notifyClients(String method, Object params) {
            McpServerSession current = session;
            return current == null ? Mono.error(new IllegalStateException("No stdio session"))
                    : current.sendNotification(method, params);
        }

        @Override public Mono<Void> notifyClient(String sessionId, String method, Object params) {
            McpServerSession current = session;
            if (current == null || !current.getId().equals(sessionId)) {
                return Mono.error(new IllegalStateException("Unknown stdio session"));
            }
            return current.sendNotification(method, params);
        }

        @Override public Mono<Void> closeGracefully() {
            return Mono.fromRunnable(this::close);
        }

        @Override public void close() {
            if (closing.compareAndSet(false, true)) {
                inFlight.forEach(task -> task.cancel(true));
                McpServerSession current = session;
                if (current != null) {
                    current.close();
                }
                connectionExecutor.shutdownNow();
                outputExecutor.shutdownNow();
                terminated.complete(null);
            }
        }

        private CompletableFuture<Void> termination() {
            return terminated;
        }

        private void readLoop() {
            try {
                BoundedJsonRpcFramer framer = new BoundedJsonRpcFramer(
                        input, ProtocolJson.MAX_REQUEST_BYTES);
                while (!closing.get()) {
                    BoundedJsonRpcFramer.Frame frame = framer.read();
                    if (frame instanceof BoundedJsonRpcFramer.Frame.EndOfInput) {
                        break;
                    }
                    if (frame instanceof BoundedJsonRpcFramer.Frame.Rejected) {
                        writeParseError();
                        continue;
                    }
                    McpSchema.JSONRPCMessage message;
                    try {
                        message = McpSchema.deserializeJsonRpcMessage(
                                mapper, ((BoundedJsonRpcFramer.Frame.Message) frame).json());
                    } catch (IOException | IllegalArgumentException failure) {
                        writeParseError();
                        continue;
                    }
                    if (!cancelRequest(message)) {
                        dispatch(message);
                    }
                }
                drainInFlight();
                finishNaturally();
            } catch (IOException failure) {
                if (!closing.get()) {
                    terminated.completeExceptionally(failure);
                    close();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                close();
            }
        }

        /**
         * Writes one bounded JSON-RPC parse error synchronously on the read-loop thread;
         * rejected frames are never echoed. A failed write propagates to the read loop's
         * failure path, so an output failure deterministically terminates the transport
         * instead of being lost in a detached task or swallowed by a racing EOF close.
         */
        private void writeParseError() throws IOException {
            writeLine(mapper.writeValueAsString(parseErrorBody()));
        }

        private static Map<String, Object> parseErrorBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("jsonrpc", McpSchema.JSONRPC_VERSION);
            body.put("id", null);
            body.put("error", new McpSchema.JSONRPCResponse.JSONRPCError(
                    McpSchema.ErrorCodes.PARSE_ERROR, "Parse error"));
            return body;
        }

        private void writeLine(String json) throws IOException {
            // The read loop writes parse errors synchronously while response writes run
            // on outputExecutor; the output monitor serializes both, matching the MCP
            // SDK's own stdio transport.
            synchronized (output) {
                output.write(json.getBytes(StandardCharsets.UTF_8));
                output.write('\n');
                output.flush();
            }
        }

        /**
         * Dispatches one message onto a virtual thread only while a bounded admission slot is
         * free. Excess {@link McpSchema.JSONRPCRequest}s receive an immediate typed JSON-RPC
         * limit response written synchronously under the output monitor; excess notifications
         * and other messages are dropped. The slot is released in {@link MessageTask#finish()}
         * only after {@code handle(message).block()} returns, which completes only once the
         * response send finishes, so the output queue is bounded by the same admission cap.
         */
        private void dispatch(McpSchema.JSONRPCMessage message) throws IOException {
            if (dispatched.get() >= TRANSPORT_ADMISSION_LIMIT) {
                if (message instanceof McpSchema.JSONRPCRequest request) {
                    writeOverloadResponse(request.id());
                }
                return;
            }
            MessageTask task = new MessageTask(message);
            inFlight.add(task);
            if (task.requestId != null) {
                requests.put(task.requestId, task);
            }
            dispatched.incrementAndGet();
            try {
                connectionExecutor.execute(task);
            } catch (java.util.concurrent.RejectedExecutionException closing) {
                // The transport is closing: the task never runs, so release its slot at once
                // instead of leaking the admission permit.
                task.finish();
            }
        }

        /**
         * Writes one bounded typed JSON-RPC limit response synchronously on the read-loop
         * thread for a request rejected by the transport admission gate. Like a parse error it
         * is written directly under the output monitor and never queued on the output executor,
         * so an excess flood cannot recursively grow the output queue.
         */
        private void writeOverloadResponse(Object requestId) throws IOException {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("jsonrpc", McpSchema.JSONRPC_VERSION);
            body.put("id", requestId);
            body.put("error", new McpSchema.JSONRPCResponse.JSONRPCError(
                    TRANSPORT_BUSY_ERROR_CODE, TRANSPORT_BUSY_ERROR_MESSAGE));
            writeLine(mapper.writeValueAsString(body));
        }

        private boolean cancelRequest(McpSchema.JSONRPCMessage message) {
            if (!(message instanceof McpSchema.JSONRPCNotification notification)
                    || !CANCELLED_NOTIFICATION.equals(notification.method())
                    || !(notification.params() instanceof Map<?, ?> params)) {
                return false;
            }
            MessageTask task = requests.remove(params.get("requestId"));
            if (task != null) {
                task.cancel(true);
            }
            return true;
        }

        private void drainInFlight() throws InterruptedException {
            while (!inFlight.isEmpty()) {
                MessageTask[] tasks = inFlight.toArray(MessageTask[]::new);
                for (MessageTask task : tasks) {
                    task.awaitFinished();
                }
            }
        }

        private void finishNaturally() {
            if (closing.compareAndSet(false, true)) {
                McpServerSession current = session;
                if (current != null) {
                    current.close();
                }
                connectionExecutor.shutdown();
                outputExecutor.shutdown();
                terminated.complete(null);
            }
        }

        private final class MessageTask extends FutureTask<Void> {
            private final Object requestId;
            private final AtomicBoolean started = new AtomicBoolean();
            private final AtomicBoolean finished = new AtomicBoolean();
            private final CountDownLatch finishedSignal = new CountDownLatch(1);

            private MessageTask(McpSchema.JSONRPCMessage message) {
                super(() -> {
                    McpServerSession current = session;
                    if (current == null) {
                        throw new IllegalStateException("Stdio session was not initialized");
                    }
                    current.handle(message).block();
                    return null;
                });
                requestId = message instanceof McpSchema.JSONRPCRequest request
                        ? request.id() : null;
            }

            @Override public void run() {
                started.set(true);
                try {
                    super.run();
                } finally {
                    finish();
                }
            }

            @Override protected void done() {
                if (!started.get()) {
                    finish();
                }
            }

            private void finish() {
                if (finished.compareAndSet(false, true)) {
                    inFlight.remove(this);
                    if (requestId != null) {
                        requests.remove(requestId, this);
                    }
                    dispatched.decrementAndGet();
                    finishedSignal.countDown();
                }
            }

            private void awaitFinished() throws InterruptedException {
                finishedSignal.await();
            }
        }

        private final class VirtualStdioTransport implements McpServerTransport {
            @Override public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
                return Mono.fromFuture(CompletableFuture.runAsync(() -> {
                    if (closing.get()) {
                        return;
                    }
                    try {
                        String json = mapper.writeValueAsString(message)
                                .replace("\r\n", "\\n")
                                .replace("\n", "\\n")
                                .replace("\r", "\\n");
                        writeLine(json);
                    } catch (IOException failure) {
                        throw new IllegalStateException(
                                "Failed to write stdio MCP message", failure);
                    }
                }, outputExecutor));
            }

            @Override public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
                return mapper.convertValue(data, typeRef);
            }

            @Override public Mono<Void> closeGracefully() {
                return Mono.fromRunnable(VirtualStdioProvider.this::close);
            }

            @Override public void close() {
                VirtualStdioProvider.this.close();
            }
        }
    }

    /**
     * Builds the hardened MCP mapper used for every stdio message: the Jackson factory
     * enforces the same request constraints as {@link ProtocolJson} (nesting depth,
     * string length, and number length), while the frame byte cap is enforced earlier by
     * {@link BoundedJsonRpcFramer}.
     */
    private static McpJsonMapper hardenedMapper() {
        StreamReadConstraints constraints = StreamReadConstraints.builder()
                .maxNestingDepth(ProtocolJson.MAX_NESTING_DEPTH)
                .maxStringLength(ProtocolJson.MAX_STRING_LENGTH)
                .maxNumberLength(ProtocolJson.MAX_NUMBER_LENGTH)
                .build();
        JsonFactory factory = JsonFactory.builder()
                .streamReadConstraints(constraints)
                .build();
        return new HardenedMcpJsonMapper(JsonMapper.builder(factory).build());
    }

    /** {@link McpJsonMapper} adapter over the hardened Jackson 2 mapper. */
    private static final class HardenedMcpJsonMapper implements McpJsonMapper {
        private final ObjectMapper mapper;

        private HardenedMcpJsonMapper(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override public <T> T readValue(String json, Class<T> type) throws IOException {
            return mapper.readValue(json, type);
        }

        @Override public <T> T readValue(byte[] json, Class<T> type) throws IOException {
            return mapper.readValue(json, type);
        }

        @Override public <T> T readValue(String json, TypeRef<T> typeRef) throws IOException {
            return mapper.readValue(json, mapper.getTypeFactory().constructType(
                    typeRef.getType()));
        }

        @Override public <T> T readValue(byte[] json, TypeRef<T> typeRef) throws IOException {
            return mapper.readValue(json, mapper.getTypeFactory().constructType(
                    typeRef.getType()));
        }

        @Override public <T> T convertValue(Object from, Class<T> type) {
            return mapper.convertValue(from, type);
        }

        @Override public <T> T convertValue(Object from, TypeRef<T> typeRef) {
            return mapper.convertValue(from, mapper.getTypeFactory().constructType(
                    typeRef.getType()));
        }

        @Override public String writeValueAsString(Object value) throws IOException {
            return mapper.writeValueAsString(value);
        }

        @Override public byte[] writeValueAsBytes(Object value) throws IOException {
            return mapper.writeValueAsBytes(value);
        }
    }
}
