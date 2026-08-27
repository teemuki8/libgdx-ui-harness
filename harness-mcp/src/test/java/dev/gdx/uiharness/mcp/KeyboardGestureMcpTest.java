package dev.gdx.uiharness.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gdx.uiharness.protocol.Command;
import dev.gdx.uiharness.protocol.HarnessProtocolService;
import dev.gdx.uiharness.protocol.HarnessResponse;
import dev.gdx.uiharness.protocol.ProtocolJson;
import dev.gdx.uiharness.protocol.ProtocolVersion;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class KeyboardGestureMcpTest {
    @Test void completedAndRejectedGesturesKeepStructuredTerminalEvidence() {
        AtomicInteger calls = new AtomicInteger();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                HarnessToolHandler handler = new HarnessToolHandler(
                        request -> CompletableFuture.completedFuture(new HarnessResponse.Success(
                                ProtocolVersion.V1, request.requestId(), request.sessionId(),
                                new HarnessResponse.Result.KeyboardGesture(
                                        calls.getAndIncrement() == 0
                                                ? completedGesture() : rejectedGesture()))),
                        noArtifacts(), executor, 1024)) {
            McpSchema.CallToolResult completed = handler.handle(call(
                    "game", "wait-frames")).block(Duration.ofSeconds(5));
            assertFalse(completed.isError());
            Map<String, Object> completedContent = structured(completed);
            assertEquals("keyboard-gesture-result", completedContent.get("kind"));
            assertEquals("completed", completedContent.get("outcome"));
            assertEquals(2, completedContent.get("completedSteps"));
            assertTrue(McpJsonDefaults.getSchemaValidator().validate(
                    new HarnessToolCatalog().tool("ui_keyboard_gesture").outputSchema(),
                    completedContent).valid());

            McpSchema.CallToolResult rejected = handler.handle(call(
                    "game", "wait-ticks")).block(Duration.ofSeconds(5));
            assertTrue(rejected.isError());
            Map<String, Object> rejectedContent = structured(rejected);
            assertEquals("keyboard-gesture-result", rejectedContent.get("kind"));
            assertEquals("rejected", rejectedContent.get("outcome"));
            assertEquals("invalid-runtime-state", rejectedContent.get("failure"));
            assertEquals("not-required", rejectedContent.get("cleanupStatus"));
            assertTrue(McpJsonDefaults.getSchemaValidator().validate(
                    new HarnessToolCatalog().tool("ui_keyboard_gesture").outputSchema(),
                    rejectedContent).valid());
        }
    }
    @Test void maximumV2ResultStaysBelowStructuredResultBound() throws Exception {
        HarnessResponse.KeyboardGestureData maximum = maximumGesture();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                HarnessToolHandler handler = new HarnessToolHandler(
                        request -> {
                            Command.KeyboardGesture gesture =
                                    (Command.KeyboardGesture) request.command();
                            assertEquals(2, gesture.schemaVersion());
                            assertEquals(256, gesture.steps().size());
                            return CompletableFuture.completedFuture(new HarnessResponse.Success(
                                    ProtocolVersion.V1, request.requestId(), request.sessionId(),
                                    new HarnessResponse.Result.KeyboardGesture(maximum)));
                        },
                        noArtifacts(), executor, 64 * 1_024)) {
            McpSchema.CallToolResult result = handler.handle(
                    longGestureCall()).block(Duration.ofSeconds(5));

            assertFalse(result.isError(), String.valueOf(result.structuredContent()));
            Map<String, Object> content = structured(result);
            assertEquals(256, ((List<?>) content.get("steps")).size());
            assertTrue(ProtocolJson.mapper().writeValueAsBytes(content).length
                    < ProtocolJson.MAX_RESPONSE_BYTES);
            assertTrue(McpJsonDefaults.getSchemaValidator().validate(
                    new HarnessToolCatalog().tool("ui_keyboard_gesture").outputSchema(),
                    content).valid());
        }
    }
    @Test void handlerRejectsUnknownGestureVersionAndTopLevelFieldBeforeProtocol() {
        AtomicInteger calls = new AtomicInteger();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                HarnessToolHandler handler = new HarnessToolHandler(
                        request -> {
                            calls.incrementAndGet();
                            return CompletableFuture.completedFuture(new HarnessResponse.Success(
                                    ProtocolVersion.V1, request.requestId(), request.sessionId(),
                                    new HarnessResponse.Result.KeyboardGesture(
                                            completedGesture())));
                        },
                        noArtifacts(), executor, 64 * 1_024)) {
            McpSchema.CallToolResult unknownVersion = handler.handle(
                    gestureCall(3, Map.of())).block(Duration.ofSeconds(5));
            McpSchema.CallToolResult unknownField = handler.handle(
                    gestureCall(1, Map.of("surprise", true))).block(Duration.ofSeconds(5));

            assertTrue(unknownVersion.isError());
            assertTrue(unknownField.isError());
            assertEquals(0, calls.get());
        }
    }



    @Test void cancellationSignalsGestureButRetainsMutationLaneUntilCleanupTerminal()
            throws Exception {
        CancellationTransparentSource gesture = new CancellationTransparentSource();
        CompletableFuture<Void> gestureStarted = new CompletableFuture<>();
        CompletableFuture<Void> sameSessionActionStarted = new CompletableFuture<>();
        CompletableFuture<Void> otherSessionActionStarted = new CompletableFuture<>();
        HarnessToolHandler.ExecutionSource source = request -> {
            if (request.command() instanceof Command.KeyboardGesture) {
                gestureStarted.complete(null);
                return gesture;
            }
            if ("game".equals(request.sessionId())) {
                sameSessionActionStarted.complete(null);
            } else {
                otherSessionActionStarted.complete(null);
            }
            return CompletableFuture.completedFuture(execution(request,
                    new HarnessResponse.Result.Action(1, 2, "clicked", Map.of())));
        };
        RequestAdmission admission = new RequestAdmission(8, 8, 4);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                HarnessToolHandler handler = new HarnessToolHandler(
                        source, noArtifacts(), executor, 1024, System::nanoTime, admission)) {
            CompletableFuture<McpSchema.CallToolResult> first = handler.handle(
                    call("game", "wait-frames")).toFuture();
            gestureStarted.get(5, TimeUnit.SECONDS);

            CompletableFuture<McpSchema.CallToolResult> queued = handler.handle(
                    action("game")).toFuture();
            CompletableFuture<McpSchema.CallToolResult> other = handler.handle(
                    action("other")).toFuture();
            otherSessionActionStarted.get(5, TimeUnit.SECONDS);
            assertFalse(sameSessionActionStarted.isDone());

            first.cancel(false);
            assertEquals(1, gesture.cancelCalls.get());
            assertFalse(sameSessionActionStarted.isDone(),
                    "transport cancellation must not release the mutation lane");

            gesture.complete(execution("game", "mcp-gesture",
                    new HarnessResponse.Result.KeyboardGesture(cancelledGesture())));
            sameSessionActionStarted.get(5, TimeUnit.SECONDS);
            assertFalse(queued.get(5, TimeUnit.SECONDS).isError());
            assertFalse(other.get(5, TimeUnit.SECONDS).isError());
        }
    }

    private static McpSchema.CallToolRequest call(String sessionId, String waitKind) {
        return McpSchema.CallToolRequest.builder("ui_keyboard_gesture").arguments(Map.of(
                "sessionId", sessionId,
                "schemaVersion", 1,
                "deadlineMillis", 30_000,
                "steps", List.of(
                        Map.of("kind", "key-down", "keycode", 29),
                        Map.of("kind", waitKind, "count", 2),
                        Map.of("kind", "key-up", "keycode", 29)))).build();
    }
    private static McpSchema.CallToolRequest gestureCall(
            int schemaVersion, Map<String, Object> additions) {
        Map<String, Object> arguments = new java.util.LinkedHashMap<>(Map.of(
                "sessionId", "game",
                "schemaVersion", schemaVersion,
                "deadlineMillis", 30_000,
                "steps", List.of(
                        Map.of("kind", "key-down", "keycode", 29),
                        Map.of("kind", "key-up", "keycode", 29))));
        arguments.putAll(additions);
        return McpSchema.CallToolRequest.builder("ui_keyboard_gesture")
                .arguments(arguments).build();
    }


    private static McpSchema.CallToolRequest longGestureCall() {
        ArrayList<Map<String, Object>> steps = new ArrayList<>(256);
        for (int index = 0; index < 128; index++) {
            steps.add(Map.of("kind", "key-down", "keycode", 29));
            steps.add(Map.of("kind", "key-up", "keycode", 29));
        }
        return McpSchema.CallToolRequest.builder("ui_keyboard_gesture").arguments(Map.of(
                "sessionId", "game",
                "schemaVersion", 2,
                "deadlineMillis", 30_000,
                "steps", steps)).build();
    }

    private static McpSchema.CallToolRequest action(String sessionId) {
        return McpSchema.CallToolRequest.builder("ui_action").arguments(Map.of(
                "sessionId", sessionId,
                "locator", Map.of("kind", "role", "role", "button"),
                "action", Map.of(
                        "kind", "click", "pointer", 0, "button", 0, "force", false)))
                .build();
    }

    private static HarnessResponse.KeyboardGestureData completedGesture() {
        return new HarnessResponse.KeyboardGestureData(
                1, "completed", 2, 2, 2,
                1, 1, 3, 1, 10,
                List.of(
                        new HarnessResponse.KeyboardGestureStepData(
                                0, "key-down", "completed", 29, null,
                                1, 1, 2, 1, List.of(29), null),
                        new HarnessResponse.KeyboardGestureStepData(
                                1, "key-up", "completed", 29, null,
                                2, 1, 3, 1, List.of(), null)),
                null, null, List.of(), "not-required", List.of(), null);
    }

    private static HarnessResponse.KeyboardGestureData maximumGesture() {
        ArrayList<HarnessResponse.KeyboardGestureStepData> steps = new ArrayList<>(256);
        List<Integer> held = List.of(
                0, 1, 2, 3, 4, 5, 6, 7,
                8, 9, 10, 11, 12, 13, 14, 15);
        for (int index = 0; index < 256; index++) {
            long identity = 1_000_000_000L + index;
            steps.add(new HarnessResponse.KeyboardGestureStepData(
                    index, "wait-ticks", "completed", null, 10_000,
                    identity, identity, identity + 1, identity + 1,
                    held, new HarnessResponse.KeyboardTickData(
                            10_000, 10_000, identity, identity + 10_000,
                            identity, identity, identity + 9_999,
                            identity, identity + 9_999, Long.MAX_VALUE)));
        }
        return new HarnessResponse.KeyboardGestureData(
                2, "completed", 256, 256, 256,
                1_000_000_000L, 1_000_000_000L,
                1_000_000_256L, 1_000_000_256L, Long.MAX_VALUE, steps,
                null, null, List.of(), "not-required", List.of(), "t".repeat(256));
    }

    private static HarnessResponse.KeyboardGestureData rejectedGesture() {
        return new HarnessResponse.KeyboardGestureData(
                1, "rejected", 3, 0, 0,
                1, 1, 1, 1, 10, List.of(), 1,
                "invalid-runtime-state", List.of(), "not-required", List.of(), null);
    }

    private static HarnessResponse.KeyboardGestureData cancelledGesture() {
        return new HarnessResponse.KeyboardGestureData(
                1, "cancelled", 3, 0, 0,
                1, 1, 1, 1, 10, List.of(), 0,
                "cancelled", List.of(), "not-required", List.of(), null);
    }

    private static HarnessProtocolService.Execution execution(
            dev.gdx.uiharness.protocol.HarnessRequest request,
            HarnessResponse.Result result) {
        return execution(request.sessionId(), request.requestId(), result);
    }

    private static HarnessProtocolService.Execution execution(
            String sessionId, String requestId, HarnessResponse.Result result) {
        return new HarnessProtocolService.Execution(new HarnessResponse.Success(
                ProtocolVersion.V1, requestId, sessionId, result), Map.of());
    }

    private static ArtifactReference.Publisher noArtifacts() {
        return (mediaType, content) -> {
            throw new AssertionError("keyboard gesture results must stay inline");
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(McpSchema.CallToolResult result) {
        return (Map<String, Object>) result.structuredContent();
    }

    private static final class CancellationTransparentSource
            extends CompletableFuture<HarnessProtocolService.Execution> {
        private final AtomicInteger cancelCalls = new AtomicInteger();

        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            cancelCalls.incrementAndGet();
            return false;
        }
    }
}
