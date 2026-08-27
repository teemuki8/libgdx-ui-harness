package dev.gdx.uiharness.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gdx.uiharness.core.runtime.RuntimeObservationResult;
import dev.gdx.uiharness.core.time.Deadline;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class RuntimeObserveProtocolTest {
    @Test void runtimeObserveCommandRoundTripsThroughTheClosedSchema() throws Exception {
        Command.RuntimeObserve command = new Command.RuntimeObserve(
                "body-1", "angle", "render-frame", 2_000);

        String json = ProtocolJson.mapper().writeValueAsString(command);

        assertEquals(command, ProtocolJson.mapper().readValue(
                json, Command.RuntimeObserve.class));
        assertTrue(json.contains("\"type\":\"runtime-observe\""));
        assertThrows(Exception.class, () -> ProtocolJson.mapper().readValue(
                "{\"type\":\"runtime-observe\",\"entityId\":\"body-1\","
                        + "\"propertyId\":\"angle\",\"correlationToken\":\"frame\","
                        + "\"maxDurationMillis\":2000,\"locator\":{}}",
                Command.RuntimeObserve.class));
    }

    @Test void runtimeObserveRejectsInvalidBounds() {
        assertThrows(IllegalArgumentException.class, () -> new Command.RuntimeObserve(
                "body", "angle", "frame", 0));
        assertThrows(IllegalArgumentException.class, () -> new Command.RuntimeObserve(
                "x".repeat(257), "angle", "frame", 2_000));
    }

    @Test void runtimeObserveRequiresCoordinatorAndRoutesOneExplicitBinding() {
        RecordingCoordinator coordinator = new RecordingCoordinator();

        HarnessResponse.Failure unavailable = assertInstanceOf(HarnessResponse.Failure.class,
                service(null).execute(request()).toCompletableFuture().join());
        assertEquals(ProtocolError.Code.UNSUPPORTED_CAPABILITY, unavailable.error().code());

        HarnessResponse.Success success = assertInstanceOf(HarnessResponse.Success.class,
                service(coordinator).execute(request()).toCompletableFuture().join());
        RuntimeObservationResult result = assertInstanceOf(
                HarnessResponse.Result.RuntimeObserve.class, success.result()).observation();
        assertEquals(RuntimeObservationResult.Status.AVAILABLE, result.status());
        assertEquals("body-1/angle/render-frame", coordinator.binding.get());
    }

    @Test void priorSessionConstructorRemainsSourceCompatible() {
        HarnessProtocolService.Session session = new HarnessProtocolService.Session(
                new NoopHarness(), new dev.gdx.uiharness.core.locator.StrictResolution(),
                waits(), new NoopCapture(), new CapabilitySet(List.of()),
                HarnessProtocolService.TraceController.unsupported(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        assertTrue(session.runtimeObservationCoordinator().isEmpty());
    }

    private static HarnessRequest request() {
        return new HarnessRequest(ProtocolVersion.V1, "game", "request-observe", 500,
                new Command.RuntimeObserve("body-1", "angle", "render-frame", 2_000));
    }

    private static HarnessProtocolService service(RecordingCoordinator coordinator) {
        CapabilitySet capabilities = new CapabilitySet(
                coordinator == null ? List.of() : List.of("ui_runtime_observe"));
        HarnessProtocolService.Session session = new HarnessProtocolService.Session(
                new NoopHarness(), new dev.gdx.uiharness.core.locator.StrictResolution(),
                waits(), new NoopCapture(), capabilities,
                HarnessProtocolService.TraceController.unsupported(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                coordinator == null ? Optional.empty() : Optional.of(coordinator),
                Optional.empty());
        return new HarnessProtocolService(
                Map.of("game", session), Map.of(), System::nanoTime, Runnable::run);
    }

    private static dev.gdx.uiharness.core.wait.WaitEngine waits() {
        return new dev.gdx.uiharness.core.wait.WaitEngine(
                () -> null, new dev.gdx.uiharness.core.locator.StrictResolution(),
                System::nanoTime, listener -> () -> {});
    }

    private static final class RecordingCoordinator
            implements HarnessProtocolService.RuntimeObservationCoordinator {
        final AtomicReference<String> binding = new AtomicReference<>();

        @Override public CompletionStage<RuntimeObservationResult> observe(
                String entityId, String propertyId, String correlationToken, Deadline deadline) {
            binding.set(entityId + "/" + propertyId + "/" + correlationToken);
            return CompletableFuture.completedFuture(new RuntimeObservationResult(
                    RuntimeObservationResult.Status.AVAILABLE,
                    entityId, propertyId, 5L, 3L, "1.25", "decimal"));
        }
    }

    private static final class NoopHarness implements dev.gdx.uiharness.core.action.Harness {
        @Override public CompletionStage<dev.gdx.uiharness.core.action.ActionResult> perform(
                dev.gdx.uiharness.core.locator.Locator locator,
                dev.gdx.uiharness.core.action.Action action,
                Deadline deadline) {
            throw new UnsupportedOperationException();
        }

        @Override public CompletionStage<dev.gdx.uiharness.core.model.SemanticSnapshot> snapshot(
                Deadline deadline) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class NoopCapture
            implements dev.gdx.uiharness.core.capture.ScreenCapture {
        @Override public CompletionStage<dev.gdx.uiharness.core.capture.CapturedImage> capture(
                dev.gdx.uiharness.core.capture.CaptureRequest request,
                Deadline deadline) {
            throw new UnsupportedOperationException();
        }

        @Override public void close() {}
    }
}
