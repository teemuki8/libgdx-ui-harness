package dev.gdx.uiharness.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gdx.uiharness.core.action.ActionResult;
import dev.gdx.uiharness.core.action.Harness;
import dev.gdx.uiharness.core.capture.CaptureRequest;
import dev.gdx.uiharness.core.capture.CapturedImage;
import dev.gdx.uiharness.core.capture.ScreenCapture;
import dev.gdx.uiharness.core.locator.Locator;
import dev.gdx.uiharness.core.locator.LocatorEngine;
import dev.gdx.uiharness.core.locator.StrictResolution;
import dev.gdx.uiharness.core.model.Bounds;
import dev.gdx.uiharness.core.model.Role;
import dev.gdx.uiharness.core.model.SemanticNode;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import dev.gdx.uiharness.core.model.SemanticState;
import dev.gdx.uiharness.core.time.Deadline;
import dev.gdx.uiharness.core.time.MonotonicClock;
import dev.gdx.uiharness.core.wait.FrameSignal;
import dev.gdx.uiharness.core.wait.WaitEngine;
import dev.gdx.uiharness.protocol.CapabilitySet;
import dev.gdx.uiharness.protocol.Command;
import dev.gdx.uiharness.protocol.HarnessProtocolService;
import dev.gdx.uiharness.protocol.HarnessRequest;
import dev.gdx.uiharness.protocol.HarnessResponse;
import dev.gdx.uiharness.protocol.ProtocolJson;
import dev.gdx.uiharness.protocol.ProtocolVersion;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

final class HarnessMcpServerContractTest {
    private static final MonotonicClock CLOCK = System::nanoTime;
    private static final SemanticSnapshot SNAPSHOT = snapshot();

    @Test void clickToolReturnsStructuredActionResultFromOneProtocolRequestOnVirtualThread() {
        RecordingHarness harness = new RecordingHarness();
        RecordingArtifacts artifacts = new RecordingArtifacts();
        try (HarnessToolHandler handler = new HarnessToolHandler(service(harness), artifacts)) {
            McpSchema.CallToolResult result = handler.handle(call("ui_action", Map.of(
                    "sessionId", "game",
                    "locator", Map.of(
                            "kind", "filter",
                            "locator", Map.of("kind", "role", "role", "button"),
                            "filter", Map.of("kind", "name", "match",
                                    Map.of("mode", "exact", "source", "Save"))),
                    "action", Map.of("kind", "click", "pointer", 0, "button", 0,
                            "force", false)))).block(Duration.ofSeconds(2));

            assertNotNull(result);
            assertFalse(result.isError());
            assertEquals("action-result", structured(result).get("kind"));
            assertEquals(1, harness.actionCalls.get());
            assertTrue(harness.actionThreadWasVirtual);
            assertEquals("Save", new StrictResolution()
                    .resolveStrict(SNAPSHOT, harness.lastLocator).accessibleName());
        }
    }

    @Test void capabilityAndSnapshotResultsAreCompact() {
        try (HarnessToolHandler handler = new HarnessToolHandler(
                service(new RecordingHarness()), new RecordingArtifacts())) {
            McpSchema.CallToolResult capabilities = handler.handle(call("ui_capabilities",
                    Map.of("sessionId", "game"))).block(Duration.ofSeconds(2));
            assertEquals("capabilities-result", structured(capabilities).get("kind"));
            assertTrue(((List<?>) structured(capabilities).get("capabilities")).contains("action"));

            McpSchema.CallToolResult snapshot = handler.handle(call("ui_snapshot",
                    Map.of("sessionId", "game"))).block(Duration.ofSeconds(2));
            Map<String, Object> content = structured(snapshot);
            assertEquals("snapshot-summary", content.get("kind"));
            assertEquals(1, ((Number) content.get("nodeCount")).intValue());
            assertFalse(content.containsKey("nodes"));
        }
    }

    @Test void screenshotAndLargeResultsUseInjectedOpaqueArtifactReferences() {
        RecordingArtifacts artifacts = new RecordingArtifacts();
        try (HarnessToolHandler handler = new HarnessToolHandler(service(new RecordingHarness()), artifacts)) {
            McpSchema.CallToolResult screenshot = handler.handle(call("ui_screenshot", Map.of(
                    "sessionId", "game", "maxWidth", 10, "maxHeight", 10,
                    "maxPixels", 100, "maxPngBytes", 1024))).block(Duration.ofSeconds(2));
            Map<String, Object> screenshotContent = structured(screenshot);
            assertEquals("screenshot-result", screenshotContent.get("kind"));
            assertFalse(screenshotContent.containsKey("pngBase64"));
            assertEquals("artifact:1", artifact(screenshotContent).get("reference"));
            assertEquals(List.of((byte) 1, (byte) 2, (byte) 3), boxed(artifacts.lastBytes));
        }

        CompletableFuture<HarnessResponse> completed = CompletableFuture.completedFuture(
                new HarnessResponse.Success(ProtocolVersion.V1, "mcp-1", "game",
                        new HarnessResponse.Result.Action(1, 2, "clicked", largeEvidence())));
        RecordingArtifacts largeArtifacts = new RecordingArtifacts();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                HarnessToolHandler handler = new HarnessToolHandler(
                        ignored -> completed, largeArtifacts, executor, 1024)) {
            McpSchema.CallToolResult result = handler.handle(call("ui_action", Map.of(
                    "sessionId", "game",
                    "locator", Map.of("kind", "role", "role", "button"),
                    "action", Map.of("kind", "click", "pointer", 0, "button", 0,
                            "force", false)))).block(Duration.ofSeconds(2));
            assertEquals("action-result", structured(result).get("kind"));
            assertEquals("artifact:1", artifact(structured(result)).get("reference"));
            assertNotNull(largeArtifacts.lastBytes);
        }
    }

    @Test void malformedCallsReturnStructuredErrorsWithoutInvokingProtocol() {
        AtomicInteger calls = new AtomicInteger();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                HarnessToolHandler handler = new HarnessToolHandler(request -> {
                    calls.incrementAndGet();
                    return CompletableFuture.failedFuture(new AssertionError());
                }, new RecordingArtifacts(), executor, 1024)) {
            McpSchema.CallToolResult result = handler.handle(call("ui_action", Map.of(
                    "sessionId", "game", "path", "/tmp/attack")))
                    .block(Duration.ofSeconds(2));
            assertTrue(result.isError());
            assertEquals("invalid-arguments", structured(result).get("code"));
            assertEquals(0, calls.get());
        }
    }

    @Test void cancellingToolCallCancelsTheProtocolStage() throws Exception {
        AtomicReference<HarnessRequest> observed = new AtomicReference<>();
        CompletableFuture<HarnessResponse> pending = new CompletableFuture<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                HarnessToolHandler handler = new HarnessToolHandler(request -> {
                    observed.set(request);
                    return pending;
                }, new RecordingArtifacts(), executor, 1024)) {
            var subscription = handler.handle(call("ui_capabilities", Map.of("sessionId", "game")))
                    .subscribe();
            for (int attempt = 0; attempt < 100 && observed.get() == null; attempt++) {
                Thread.sleep(5);
            }
            assertNotNull(observed.get());
            subscription.dispose();
            for (int attempt = 0; attempt < 100 && !pending.isCancelled(); attempt++) {
                Thread.sleep(5);
            }
            assertTrue(pending.isCancelled());
        }
    }

    @Test void artifactReferencesRejectFilesystemPaths() {
        assertThrows(IllegalArgumentException.class, () -> new ArtifactReference(
                "C:/tmp/capture.png", "image/png", 1, "0".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> new ArtifactReference(
                "../capture.png", "image/png", 1, "0".repeat(64)));
    }

    @Test void traceStopRejectsFilesystemLookingProtocolReferences() {
        CompletableFuture<HarnessResponse> response = CompletableFuture.completedFuture(
                new HarnessResponse.Success(ProtocolVersion.V1, "mcp-1", "game",
                        new HarnessResponse.Result.TraceStopped(
                                "trace-1", "/tmp/trace.zip", 1, 32)));
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                HarnessToolHandler handler = new HarnessToolHandler(
                        ignored -> response, new RecordingArtifacts(), executor, 1024)) {
            McpSchema.CallToolResult result = handler.handle(call(
                    "ui_trace_stop", Map.of("sessionId", "game")))
                    .block(Duration.ofSeconds(2));
            assertTrue(result.isError());
            assertEquals("invalid-artifact-reference", structured(result).get("code"));
        }
    }

    @Test void deeplyNestedAndOversizedLocatorGraphsAreRejectedBeforeProtocolDispatch() {
        AtomicInteger calls = new AtomicInteger();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                HarnessToolHandler handler = new HarnessToolHandler(request -> {
                    calls.incrementAndGet();
                    return CompletableFuture.failedFuture(new AssertionError());
                }, new RecordingArtifacts(), executor, 1024)) {
            assertInvalidLocator(handler, deepLocator(HarnessToolHandler.MAX_LOCATOR_DEPTH + 1));
            assertInvalidLocator(handler, wideLocator(12));
            assertEquals(0, calls.get());
        }
    }

    @Test
    @Timeout(10)
    void stdioContinuesReadingAndDrainsInFlightCallsAfterEof() throws Exception {
        RecordingHarness harness = new RecordingHarness();
        CompletableFuture<ActionResult> pendingAction = new CompletableFuture<>();
        harness.actionResult = pendingAction;
        try (PipedInputStream serverInput = new PipedInputStream();
                PipedOutputStream clientOutput = new PipedOutputStream(serverInput);
                PipedInputStream clientInput = new PipedInputStream();
                PipedOutputStream serverOutput = new PipedOutputStream(clientInput);
                HarnessMcpServer server = HarnessMcpServer.open(
                        service(harness), new RecordingArtifacts(), serverInput, serverOutput);
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                        clientOutput, StandardCharsets.UTF_8));
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        clientInput, StandardCharsets.UTF_8));
                ExecutorService waiter = Executors.newVirtualThreadPerTaskExecutor()) {
            initialize(writer, reader);
            send(writer, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
            send(writer, actionCallJson(3));
            send(writer, "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"ping\",\"params\":{}}");
            assertEquals(4, read(reader).path("id").asInt());

            closeStdin(clientOutput);
            CompletableFuture<Void> termination = CompletableFuture.runAsync(
                    server::awaitTermination, waiter);
            Thread.sleep(50);
            assertFalse(termination.isDone());

            pendingAction.complete(new ActionResult(
                    1, 2, "clicked", Map.of("target", "root")));
            JsonNode action = read(reader);
            assertEquals(3, action.path("id").asInt());
            assertEquals("action-result",
                    action.at("/result/structuredContent/kind").asText());
            termination.get(2, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    @Test
    @Timeout(10)
    void stdioCancellationReachesAnInFlightProtocolCall() throws Exception {
        RecordingHarness harness = new RecordingHarness();
        CompletableFuture<ActionResult> pendingAction = new CompletableFuture<>();
        harness.actionResult = pendingAction;
        try (PipedInputStream serverInput = new PipedInputStream();
                PipedOutputStream clientOutput = new PipedOutputStream(serverInput);
                PipedInputStream clientInput = new PipedInputStream();
                PipedOutputStream serverOutput = new PipedOutputStream(clientInput);
                HarnessMcpServer server = HarnessMcpServer.open(
                        service(harness), new RecordingArtifacts(), serverInput, serverOutput);
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                        clientOutput, StandardCharsets.UTF_8));
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        clientInput, StandardCharsets.UTF_8))) {
            initialize(writer, reader);
            send(writer, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
            send(writer, actionCallJson(3));
            for (int attempt = 0; attempt < 100 && harness.actionCalls.get() == 0; attempt++) {
                Thread.sleep(5);
            }
            assertEquals(1, harness.actionCalls.get());

            send(writer, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\","
                    + "\"params\":{\"requestId\":3,\"reason\":\"contract cancellation\"}}");
            send(writer, "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"ping\",\"params\":{}}");
            assertEquals(4, read(reader).path("id").asInt());
            closeStdin(clientOutput);
            server.awaitTermination();
            assertTrue(pendingAction.isCancelled());
        }
    }

    @Test
    @Timeout(10)
    void stdioInitializeListAndCallRoundTripThenCleanlyCloses() throws Exception {
        RecordingHarness harness = new RecordingHarness();
        try (PipedInputStream serverInput = new PipedInputStream();
                PipedOutputStream clientOutput = new PipedOutputStream(serverInput);
                PipedInputStream clientInput = new PipedInputStream();
                PipedOutputStream serverOutput = new PipedOutputStream(clientInput);
                HarnessMcpServer server = HarnessMcpServer.open(
                        service(harness), new RecordingArtifacts(), serverInput, serverOutput);
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                        clientOutput, StandardCharsets.UTF_8));
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        clientInput, StandardCharsets.UTF_8))) {
            assertNotNull(server);
            send(writer, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                    + "\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
                    + "\"clientInfo\":{\"name\":\"contract\",\"version\":\"1.0\"}}}");
            JsonNode initialize = read(reader);
            assertEquals(1, initialize.path("id").asInt());
            assertEquals("libgdx-ui-harness", initialize.at("/result/serverInfo/name").asText());
            assertTrue(initialize.at("/result/capabilities/tools").isObject());

            send(writer, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
            send(writer, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}");
            JsonNode listed = read(reader);
            assertEquals(9, listed.at("/result/tools").size());

            send(writer, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
                    + "\"params\":{\"name\":\"ui_action\",\"arguments\":{"
                    + "\"sessionId\":\"game\",\"locator\":{\"kind\":\"role\",\"role\":\"button\"},"
                    + "\"action\":{\"kind\":\"click\",\"pointer\":0,\"button\":0,\"force\":false}}}}");
            JsonNode called = read(reader);
            assertFalse(called.at("/result/isError").asBoolean());
            assertEquals("action-result", called.at("/result/structuredContent/kind").asText());
            assertEquals(1, harness.actionCalls.get());
            closeStdin(clientOutput);
        }
    }

    private static void assertInvalidLocator(
            HarnessToolHandler handler, Map<String, Object> locator) {
        McpSchema.CallToolResult result = handler.handle(call(
                "ui_query", Map.of("sessionId", "game", "locator", locator)))
                .block(Duration.ofSeconds(2));
        assertTrue(result.isError());
        assertEquals("invalid-arguments", structured(result).get("code"));
    }

    private static Map<String, Object> deepLocator(int depth) {
        Map<String, Object> locator = Map.of("kind", "role", "role", "button");
        for (int index = 0; index < depth; index++) {
            locator = Map.of("kind", "index", "index", 0, "locator", locator);
        }
        return locator;
    }

    private static Map<String, Object> wideLocator(int depth) {
        if (depth == 0) {
            return Map.of("kind", "role", "role", "button");
        }
        return Map.of("kind", "relation", "relation", "sibling",
                "anchor", wideLocator(depth - 1), "target", wideLocator(depth - 1));
    }

    private static void initialize(BufferedWriter writer, BufferedReader reader)
            throws Exception {
        send(writer, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                + "\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"contract\",\"version\":\"1.0\"}}}");
        assertEquals(1, read(reader).path("id").asInt());
    }

    private static String actionCallJson(int id) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"ui_action\",\"arguments\":{"
                + "\"sessionId\":\"game\",\"locator\":{\"kind\":\"role\",\"role\":\"button\"},"
                + "\"action\":{\"kind\":\"click\",\"pointer\":0,\"button\":0,\"force\":false}}}}";
    }

    private static McpSchema.CallToolRequest call(String name, Map<String, Object> arguments) {
        return McpSchema.CallToolRequest.builder(name).arguments(arguments).build();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(McpSchema.CallToolResult result) {
        assertNotNull(result);
        return (Map<String, Object>) result.structuredContent();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> artifact(Map<String, Object> content) {
        return (Map<String, Object>) content.get("artifact");
    }

    private static List<Byte> boxed(byte[] bytes) {
        Byte[] boxed = new Byte[bytes.length];
        for (int index = 0; index < bytes.length; index++) {
            boxed[index] = bytes[index];
        }
        return List.of(boxed);
    }

    private static Map<String, String> largeEvidence() {
        LinkedHashMap<String, String> evidence = new LinkedHashMap<>();
        for (int index = 0; index < 16; index++) {
            evidence.put("entry-" + index, "x".repeat(1024));
        }
        return evidence;
    }

    private static void send(BufferedWriter writer, String json) throws Exception {
        writer.write(json);
        writer.newLine();
        writer.flush();
    }

    private static void closeStdin(PipedOutputStream clientOutput) throws Exception {
        clientOutput.close();
    }

    private static JsonNode read(BufferedReader reader) throws Exception {
        return ProtocolJson.mapper().readTree(reader.readLine());
    }

    private static HarnessProtocolService service(RecordingHarness harness) {
        LocatorEngine locators = new StrictResolution();
        FrameSignal frames = listener -> () -> {};
        WaitEngine waits = new WaitEngine(() -> SNAPSHOT, locators, CLOCK, frames);
        CapabilitySet capabilities = new CapabilitySet(List.of(
                "action", "capabilities", "query", "screenshot", "snapshot", "trace", "wait"));
        ScreenCapture capture = new ScreenCapture() {
            @Override public CompletionStage<CapturedImage> capture(
                    CaptureRequest request, Deadline deadline) {
                return CompletableFuture.completedFuture(new CapturedImage(new byte[] {1, 2, 3},
                        "0".repeat(64), 1, 1, 1, 1, new CapturedImage.Scale(1, 1)));
            }

            @Override public void close() {}
        };
        HarnessProtocolService.TraceController traces = new HarnessProtocolService.TraceController() {
            @Override public CompletionStage<HarnessResponse.Result.TraceStarted> start(
                    Command.TraceStart command, Deadline deadline) {
                return CompletableFuture.completedFuture(
                        new HarnessResponse.Result.TraceStarted("trace-1"));
            }

            @Override public CompletionStage<HarnessResponse.Result.TraceStopped> stop(Deadline deadline) {
                return CompletableFuture.completedFuture(new HarnessResponse.Result.TraceStopped(
                        "trace-1", "artifact:trace-1", 2, 128));
            }
        };
        return new HarnessProtocolService(Map.of("game", new HarnessProtocolService.Session(
                harness, locators, waits, capture, capabilities, traces)), CLOCK, Runnable::run);
    }

    private static SemanticSnapshot snapshot() {
        Bounds bounds = new Bounds(0, 0, 10, 10);
        SemanticState state = new SemanticState(true, true, Optional.of(true),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                false, false, 1.0, false, true, true);
        SemanticNode root = new SemanticNode("root", null, List.of(), Role.BUTTON, "Save",
                "Save", null, "save", "button", "Button", state, bounds, bounds, bounds,
                0, Map.of());
        return new SemanticSnapshot(1, 1, "root", Map.of("root", root));
    }

    private static final class RecordingHarness implements Harness {
        private final AtomicInteger actionCalls = new AtomicInteger();
        private volatile boolean actionThreadWasVirtual;
        private volatile Locator lastLocator;
        private CompletionStage<ActionResult> actionResult = CompletableFuture.completedFuture(
                new ActionResult(1, 2, "clicked", Map.of("target", "root")));

        @Override public CompletionStage<ActionResult> perform(Locator locator,
                dev.gdx.uiharness.core.action.Action action, Deadline deadline) {
            actionCalls.incrementAndGet();
            actionThreadWasVirtual = Thread.currentThread().isVirtual();
            lastLocator = locator;
            return actionResult;
        }

        @Override public CompletionStage<SemanticSnapshot> snapshot(Deadline deadline) {
            return CompletableFuture.completedFuture(SNAPSHOT);
        }
    }

    private static final class RecordingArtifacts implements ArtifactReference.Publisher {
        private byte[] lastBytes;
        private int count;

        @Override public ArtifactReference publish(String mediaType, byte[] content) {
            lastBytes = content.clone();
            count++;
            return new ArtifactReference("artifact:" + count, mediaType, content.length,
                    "0".repeat(64));
        }
    }
}
