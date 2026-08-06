package dev.gdx.uiharness.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gdx.uiharness.core.runtime.DisplayedRuntimeComparison;
import dev.gdx.uiharness.core.time.Deadline;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class RuntimeCompareProtocolTest {
    @Test void runtimeCompareCommandRoundTripsThroughTheClosedSchema() throws Exception {
        Command.RuntimeCompare command = new Command.RuntimeCompare(
                new Command.LocatorSpec.TestId("health-bar"), 2_000);

        String json = ProtocolJson.mapper().writeValueAsString(command);

        assertEquals(command, ProtocolJson.mapper().readValue(
                json, Command.RuntimeCompare.class));
        assertTrue(json.contains("\"type\":\"runtime-compare\""));
        assertTrue(json.contains("\"testId\":\"health-bar\""));
    }

    @Test void runtimeCompareRejectsInvalidBounds() {
        assertThrows(IllegalArgumentException.class, () -> new Command.RuntimeCompare(
                new Command.LocatorSpec.TestId("health-bar"), 0));
    }

    @Test void runtimeCompareRequiresCoordinatorAndRoutesResults() {
        RecordingCoordinator coordinator = new RecordingCoordinator();
        HarnessProtocolService service = service(coordinator);

        HarnessResponse.Failure unavailable = assertInstanceOf(HarnessResponse.Failure.class,
                service(null).execute(request(new Command.RuntimeCompare(
                        new Command.LocatorSpec.TestId("health-bar"), 2_000)))
                        .toCompletableFuture().join());
        assertEquals(ProtocolError.Code.UNSUPPORTED_CAPABILITY, unavailable.error().code());

        HarnessResponse.Success success = assertInstanceOf(HarnessResponse.Success.class,
                service.execute(request(new Command.RuntimeCompare(
                        new Command.LocatorSpec.TestId("health-bar"), 2_000)))
                        .toCompletableFuture().join());
        DisplayedRuntimeComparison comparison = assertInstanceOf(
                HarnessResponse.Result.RuntimeCompare.class, success.result()).comparison();
        assertEquals(DisplayedRuntimeComparison.Status.EQUAL, comparison.status());
        assertEquals("enemy-1", comparison.entityId());
        assertEquals("health", comparison.propertyId());
        assertEquals("health-bar", coordinator.lastTestId.get());
    }

    private static HarnessRequest request(Command command) {
        return new HarnessRequest(ProtocolVersion.V1, "game", "request-runtime", 500, command);
    }

    private static HarnessProtocolService service(RecordingCoordinator coordinator) {
        var waits = new dev.gdx.uiharness.core.wait.WaitEngine(
                () -> null, new dev.gdx.uiharness.core.locator.StrictResolution(),
                System::nanoTime, listener -> () -> {});
        var capabilities = new CapabilitySet(List.of("ui_runtime_compare"));
        HarnessProtocolService.Session session = new HarnessProtocolService.Session(
                new NoopHarness(), new dev.gdx.uiharness.core.locator.StrictResolution(),
                waits, new NoopCapture(), capabilities,
                HarnessProtocolService.TraceController.unsupported(),
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                coordinator == null ? java.util.Optional.empty()
                        : java.util.Optional.of(coordinator));
        return new HarnessProtocolService(
                Map.of("game", session), Map.of(), System::nanoTime, Runnable::run);
    }

    private static final class RecordingCoordinator
            implements HarnessProtocolService.RuntimeCompareCoordinator {
        final AtomicReference<String> lastTestId = new AtomicReference<>();

        @Override public CompletionStage<DisplayedRuntimeComparison> compare(
                Command.LocatorSpec locator, Deadline deadline) {
            lastTestId.set(((Command.LocatorSpec.TestId) locator).testId());
            return CompletableFuture.completedFuture(new DisplayedRuntimeComparison(
                    DisplayedRuntimeComparison.Status.EQUAL,
                    "enemy-1", "health", "100", "100", "exact", "frame-1",
                    10, 10L, false, Map.of()));
        }
    }

    private static final class NoopHarness implements dev.gdx.uiharness.core.action.Harness {
        @Override public CompletionStage<dev.gdx.uiharness.core.action.ActionResult> perform(
                dev.gdx.uiharness.core.locator.Locator locator,
                dev.gdx.uiharness.core.action.Action action,
                dev.gdx.uiharness.core.time.Deadline deadline) {
            throw new AssertionError("actions are not expected");
        }

        @Override public CompletionStage<dev.gdx.uiharness.core.model.SemanticSnapshot> snapshot(
                dev.gdx.uiharness.core.time.Deadline deadline) {
            throw new AssertionError("snapshots are not expected");
        }
    }

    private static final class NoopCapture
            implements dev.gdx.uiharness.core.capture.ScreenCapture {
        @Override public CompletionStage<dev.gdx.uiharness.core.capture.CapturedImage> capture(
                dev.gdx.uiharness.core.capture.CaptureRequest request,
                dev.gdx.uiharness.core.time.Deadline deadline) {
            throw new AssertionError("capture is not expected");
        }

        @Override public void close() {}
    }
}
