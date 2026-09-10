package dev.gdx.uiharness.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import dev.gdx.uiharness.protocol.Command;
import dev.gdx.uiharness.protocol.HarnessResponse;
import dev.gdx.uiharness.protocol.ProtocolVersion;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class InputGestureMcpTest {
    @Test void handlerProjectsTypedInputEvidenceAndRejectsUnknownFieldsBeforeProtocol() {
        AtomicInteger calls = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
                var handler = new HarnessToolHandler(request -> {
                    calls.incrementAndGet();
                    Command.InputGesture command = (Command.InputGesture) request.command();
                    assertEquals(new Command.InputGestureStep.MouseMove(20, -10), command.steps().getFirst());
                    return CompletableFuture.completedFuture(new HarnessResponse.Success(
                            ProtocolVersion.V1, request.requestId(), request.sessionId(),
                            new HarnessResponse.Result.InputGesture(new HarnessResponse.InputGestureData(
                                    1, "completed", 1, 1, 1, 0, 0, 0, 0, 0,
                                    List.of(new HarnessResponse.InputGestureStepData(0, command.steps().getFirst(),
                                            "completed", 0, 0, 0, 0, List.of(), null)),
                                    null, null, List.of(), "not-required", List.of(), null))));
                }, (mediaType, bytes) -> { throw new AssertionError("no artifact"); }, executor, 65536)) {
            var result = handler.handle(call(Map.of("kind", "mouse-move", "deltaX", 20, "deltaY", -10)))
                    .block(Duration.ofSeconds(5));
            assertFalse(result.isError());
            assertTrue(McpJsonDefaults.getSchemaValidator().validate(
                    new HarnessToolCatalog().tool("ui_input_gesture").outputSchema(), result.structuredContent()).valid());
            var invalid = handler.handle(call(Map.of("kind", "mouse-move", "deltaX", 20,
                    "deltaY", -10, "extra", 1))).block(Duration.ofSeconds(5));
            assertTrue(invalid.isError());
            assertEquals(1, calls.get());
        }
    }

    private static McpSchema.CallToolRequest call(Map<String, Object> step) {
        return McpSchema.CallToolRequest.builder("ui_input_gesture").arguments(Map.of("sessionId", "game",
                "schemaVersion", 1, "deadlineMillis", 5000, "steps", List.of(step))).build();
    }
}
