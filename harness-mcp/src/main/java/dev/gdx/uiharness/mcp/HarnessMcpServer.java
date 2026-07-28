package dev.gdx.uiharness.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransport;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import dev.gdx.uiharness.protocol.HarnessProtocolService;
import dev.gdx.uiharness.protocol.HarnessRequest;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
        private final McpJsonMapper mapper = McpJsonDefaults.getMapper();
        private final InputStream input;
        private final OutputStream output;
        private final ExecutorService connectionExecutor =
                Executors.newVirtualThreadPerTaskExecutor();
        private final AtomicBoolean closing = new AtomicBoolean();
        private final CompletableFuture<Void> terminated = new CompletableFuture<>();
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
                McpServerSession current = session;
                if (current != null) {
                    current.close();
                }
                connectionExecutor.shutdownNow();
                terminated.complete(null);
            }
        }

        private CompletableFuture<Void> termination() {
            return terminated;
        }

        private void readLoop() {
            try {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(input, StandardCharsets.UTF_8));
                String line;
                while (!closing.get() && (line = reader.readLine()) != null) {
                    McpSchema.JSONRPCMessage message =
                            McpSchema.deserializeJsonRpcMessage(mapper, line);
                    McpServerSession current = session;
                    if (current == null) {
                        throw new IllegalStateException("Stdio session was not initialized");
                    }
                    current.handle(message).block();
                }
            } catch (IOException | RuntimeException failure) {
                if (!closing.get()) {
                    terminated.completeExceptionally(failure);
                }
            } finally {
                closing.set(true);
                McpServerSession current = session;
                if (current != null) {
                    current.close();
                }
                terminated.complete(null);
                connectionExecutor.shutdown();
            }
        }

        private final class VirtualStdioTransport implements McpServerTransport {
            @Override public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
                return Mono.fromRunnable(() -> {
                    if (closing.get()) {
                        return;
                    }
                    try {
                        String json = mapper.writeValueAsString(message)
                                .replace("\r\n", "\\n")
                                .replace("\n", "\\n")
                                .replace("\r", "\\n");
                        synchronized (output) {
                            output.write(json.getBytes(StandardCharsets.UTF_8));
                            output.write('\n');
                            output.flush();
                        }
                    } catch (IOException failure) {
                        throw new IllegalStateException("Failed to write stdio MCP message", failure);
                    }
                });
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
}
