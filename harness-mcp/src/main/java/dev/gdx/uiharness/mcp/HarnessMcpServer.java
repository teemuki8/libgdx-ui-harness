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
import reactor.core.publisher.Mono;

/** MCP SDK 2.0 server exposing only the fixed harness tool catalog over stdio. */
public final class HarnessMcpServer implements AutoCloseable {
    private final VirtualStdioProvider transport;
    private final HarnessToolHandler handler;
    private final McpAsyncServer server;
    private final AtomicBoolean closed = new AtomicBoolean();

    private HarnessMcpServer(HarnessProtocolService protocol,
            ArtifactReference.Publisher artifacts, InputStream input, OutputStream output) {
        transport = new VirtualStdioProvider(input, output);
        handler = new HarnessToolHandler(protocol, artifacts);
        HarnessToolCatalog catalog = new HarnessToolCatalog();
        McpServer.AsyncSpecification<?> specification = McpServer.async(transport)
                .serverInfo("libgdx-ui-harness", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .validateToolInputs(false)
                .requestTimeout(Duration.ofMillis(HarnessRequest.MAX_DEADLINE_MILLIS));
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

        /** Writes one bounded JSON-RPC parse error; rejected frames are never echoed. */
        private void writeParseError() {
            CompletableFuture.runAsync(() -> {
                if (closing.get()) {
                    return;
                }
                try {
                    writeLine(mapper.writeValueAsString(parseErrorBody()));
                } catch (IOException failure) {
                    // A failed output write means the client is gone; terminate the
                    // transport exactly like a failed read instead of hanging forever
                    // on an unobserved future.
                    if (!closing.get()) {
                        terminated.completeExceptionally(failure);
                        close();
                    }
                }
            }, outputExecutor);
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
            output.write(json.getBytes(StandardCharsets.UTF_8));
            output.write('\n');
            output.flush();
        }

        private void dispatch(McpSchema.JSONRPCMessage message) {
            MessageTask task = new MessageTask(message);
            inFlight.add(task);
            if (task.requestId != null) {
                requests.put(task.requestId, task);
            }
            connectionExecutor.execute(task);
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
