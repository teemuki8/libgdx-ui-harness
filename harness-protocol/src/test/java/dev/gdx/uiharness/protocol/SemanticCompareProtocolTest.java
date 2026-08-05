package dev.gdx.uiharness.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gdx.uiharness.core.golden.SemanticCompareResult;
import dev.gdx.uiharness.core.time.Deadline;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class SemanticCompareProtocolTest {
    private final Command.SemanticCompareSpec spec = new Command.SemanticCompareSpec(
            "save-golden", false, List.of(), List.of(), 256, 2_000);

    @Test void semanticCompareCommandRoundTripsThroughTheClosedSchema() throws Exception {
        Command.SemanticCompare command = new Command.SemanticCompare(spec);

        String json = ProtocolJson.mapper().writeValueAsString(command);

        assertEquals(command, ProtocolJson.mapper().readValue(
                json, Command.SemanticCompare.class));
        assertTrue(json.contains("\"type\":\"semantic-compare\""));
        assertTrue(json.contains("\"baselineId\":\"save-golden\""));
    }

    @Test void semanticCompareRejectsIdentityExclusionsAndBounds() {
        assertThrows(IllegalArgumentException.class, () -> new Command.SemanticCompareSpec(
                "save-golden", false, List.of(), List.of("testId"), 256, 2_000));
        assertThrows(IllegalArgumentException.class, () -> new Command.SemanticCompareSpec(
                "save-golden", false, List.of(), List.of(), 0, 2_000));
        assertThrows(IllegalArgumentException.class, () -> new Command.SemanticCompareSpec(
                "save-golden", false, List.of(new Command.ToleranceSpec(
                        "t", "orbit", "pixels", 1, 1, 1, 1)),
                List.of(), 256, 2_000));
    }

    @Test void semanticCompareRequiresCoordinatorAndRoutesResults() {
        RecordingCoordinator coordinator = new RecordingCoordinator();
        HarnessProtocolService service = service(coordinator);

        HarnessResponse.Failure unavailable = assertInstanceOf(HarnessResponse.Failure.class,
                service(null).execute(request(new Command.SemanticCompare(spec)))
                        .toCompletableFuture().join());
        assertEquals(ProtocolError.Code.UNSUPPORTED_CAPABILITY, unavailable.error().code());

        HarnessResponse.Success success = assertInstanceOf(HarnessResponse.Success.class,
                service.execute(request(new Command.SemanticCompare(spec)))
                        .toCompletableFuture().join());
        SemanticCompareResult result = assertInstanceOf(
                HarnessResponse.Result.SemanticCompare.class, success.result()).result();
        assertTrue(result.matched());
        assertEquals("save-golden", coordinator.lastBaseline.get());
    }

    private static HarnessRequest request(Command command) {
        return new HarnessRequest(ProtocolVersion.V1, "game", "request-compare", 500, command);
    }

    private static HarnessProtocolService service(RecordingCoordinator coordinator) {
        var waits = new dev.gdx.uiharness.core.wait.WaitEngine(
                () -> null, new dev.gdx.uiharness.core.locator.StrictResolution(),
                System::nanoTime, listener -> () -> {});
        var capabilities = new CapabilitySet(List.of("ui_semantic_compare"));
        HarnessProtocolService.Session session = new HarnessProtocolService.Session(
                new NoopHarness(), new dev.gdx.uiharness.core.locator.StrictResolution(),
                waits, new NoopCapture(), capabilities,
                HarnessProtocolService.TraceController.unsupported(),
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
            implements HarnessProtocolService.SemanticCompareCoordinator {
        final AtomicReference<String> lastBaseline = new AtomicReference<>();

        @Override public CompletionStage<SemanticCompareResult> compare(
                Command.SemanticCompareSpec spec, Deadline deadline) {
            lastBaseline.set(spec.baselineId());
            return CompletableFuture.completedFuture(new SemanticCompareResult(
                    true, List.of(), 1, false, java.util.Set.of()));
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
