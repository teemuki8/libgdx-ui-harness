package dev.gdx.uiharness.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gdx.uiharness.core.runtime.RuntimeObservationResult;
import dev.gdx.uiharness.protocol.Command;
import dev.gdx.uiharness.protocol.HarnessResponse;
import dev.gdx.uiharness.protocol.ProtocolVersion;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class RuntimeObserveMcpTest {
    @Test void translatesExplicitObservationAndReturnsTypedCorrelatedResult() {
        AtomicReference<Command.RuntimeObserve> observed = new AtomicReference<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                HarnessToolHandler handler = new HarnessToolHandler(request -> {
                    observed.set(assertInstanceOf(Command.RuntimeObserve.class,
                            request.command()));
                    return CompletableFuture.completedFuture(new HarnessResponse.Success(
                            ProtocolVersion.V1, request.requestId(), request.sessionId(),
                            new HarnessResponse.Result.RuntimeObserve(
                                    new RuntimeObservationResult(
                                            RuntimeObservationResult.Status.AVAILABLE,
                                            "body-1", "angle", 41L, 17L,
                                            "1.25", "decimal"))));
                }, noArtifacts(), executor, 1024)) {
            McpSchema.CallToolResult result = handler.handle(
                    McpSchema.CallToolRequest.builder("ui_runtime_observe").arguments(Map.of(
                            "sessionId", "game",
                            "entityId", "body-1",
                            "propertyId", "angle",
                            "correlationToken", "render-frame",
                            "maxDurationMillis", 2_000)).build())
                    .block(Duration.ofSeconds(5));

            assertFalse(result.isError());
            assertEquals("render-frame", observed.get().correlationToken());
            @SuppressWarnings("unchecked")
            Map<String, Object> structured =
                    (Map<String, Object>) result.structuredContent();
            assertEquals("runtime-observation-result", structured.get("kind"));
            assertEquals("AVAILABLE", structured.get("status"));
            assertEquals(41, ((Number) structured.get("runtimeFrame")).longValue());
            assertTrue(McpJsonDefaults.getSchemaValidator().validate(
                    new HarnessToolCatalog().tool("ui_runtime_observe").outputSchema(),
                    structured).valid());
        }
    }

    @Test void unavailableProjectionOmitsEveryAvailableOnlyField() {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                HarnessToolHandler handler = new HarnessToolHandler(request ->
                        CompletableFuture.completedFuture(new HarnessResponse.Success(
                                ProtocolVersion.V1, request.requestId(), request.sessionId(),
                                new HarnessResponse.Result.RuntimeObserve(
                                        RuntimeObservationResult.unavailable(
                                                "body-1", "angle")))),
                        noArtifacts(), executor, 1024)) {
            McpSchema.CallToolResult result = handler.handle(
                    McpSchema.CallToolRequest.builder("ui_runtime_observe").arguments(Map.of(
                            "sessionId", "game",
                            "entityId", "body-1",
                            "propertyId", "angle",
                            "correlationToken", "render-frame",
                            "maxDurationMillis", 2_000)).build())
                    .block(Duration.ofSeconds(5));

            assertFalse(result.isError());
            @SuppressWarnings("unchecked")
            Map<String, Object> structured =
                    (Map<String, Object>) result.structuredContent();
            assertEquals("UNAVAILABLE", structured.get("status"));
            assertFalse(structured.containsKey("runtimeFrame"));
            assertFalse(structured.containsKey("runtimeRevision"));
            assertFalse(structured.containsKey("value"));
            assertFalse(structured.containsKey("valueFormatId"));
            assertTrue(McpJsonDefaults.getSchemaValidator().validate(
                    new HarnessToolCatalog().tool("ui_runtime_observe").outputSchema(),
                    structured).valid());
        }
    }

    private static ArtifactReference.Publisher noArtifacts() {
        return (mediaType, content) -> {
            throw new AssertionError("runtime observations must stay inline");
        };
    }
}
