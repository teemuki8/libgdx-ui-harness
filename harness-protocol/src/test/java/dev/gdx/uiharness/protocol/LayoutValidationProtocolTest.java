package dev.gdx.uiharness.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gdx.uiharness.core.layout.LayoutFinding;
import dev.gdx.uiharness.core.layout.LayoutValidationConfig;
import dev.gdx.uiharness.core.layout.LayoutValidationReason;
import dev.gdx.uiharness.core.layout.LayoutValidationResult;
import dev.gdx.uiharness.core.layout.LayoutValidationSeverity;
import dev.gdx.uiharness.core.model.Bounds;
import dev.gdx.uiharness.core.time.Deadline;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class LayoutValidationProtocolTest {
    private final Command.LayoutValidationSpec stageSpec = new Command.LayoutValidationSpec(
            "stage", null,
            List.of("outside-viewport", "clipped-text", "text-collision",
                    "interactive-overlap", "zero-size", "duplicate-test-id",
                    "missing-accessible-name", "keyboard-unreachable", "obscured"),
            64.0, 64.0, 1.0, 1.0, "error", 256, 10_000, 2_000);

    @Test void layoutValidationCommandRoundTripsThroughTheClosedSchema() throws Exception {
        Command.LayoutValidate command = new Command.LayoutValidate(stageSpec);

        String json = ProtocolJson.mapper().writeValueAsString(command);

        assertEquals(command, ProtocolJson.mapper().readValue(
                json, Command.LayoutValidate.class));
        assertTrue(json.contains("\"type\":\"layout-validate\""));
        assertTrue(json.contains("\"targetMode\":\"stage\""));
    }

    @Test void layoutValidationRejectsConflictingModesBoundsAndUnknownChecks() {
        assertThrows(IllegalArgumentException.class, () -> new Command.LayoutValidationSpec(
                "window", null, List.of(), 64, 64, 1, 1, "error", 256, 10_000, 2_000));
        assertThrows(IllegalArgumentException.class, () -> new Command.LayoutValidationSpec(
                "subtree", null, List.of(), 64, 64, 1, 1, "error", 256, 10_000, 2_000));
        assertThrows(IllegalArgumentException.class, () -> new Command.LayoutValidationSpec(
                "stage", null, List.of("teleport"), 64, 64, 1, 1, "error",
                256, 10_000, 2_000));
        assertThrows(IllegalArgumentException.class, () -> new Command.LayoutValidationSpec(
                "stage", null, List.of(), -1, 64, 1, 1, "error", 256, 10_000, 2_000));
        assertThrows(IllegalArgumentException.class, () -> new Command.LayoutValidationSpec(
                "stage", null, List.of(), 64, 64, 1, 1, "fatal", 256, 10_000, 2_000));
        assertThrows(IllegalArgumentException.class, () -> new Command.LayoutValidationSpec(
                "stage", null, List.of(), 64, 64, 1, 1, "error", 0, 10_000, 2_000));
    }

    @Test void layoutValidationRequiresCoordinatorAndRoutesResults() {
        RecordingCoordinator coordinator = new RecordingCoordinator();
        HarnessProtocolService service = service(coordinator);

        HarnessResponse.Failure unavailable = assertInstanceOf(HarnessResponse.Failure.class,
                service(null).execute(request(new Command.LayoutValidate(stageSpec)))
                        .toCompletableFuture().join());
        assertEquals(ProtocolError.Code.UNSUPPORTED_CAPABILITY, unavailable.error().code());

        HarnessResponse.Success success = assertInstanceOf(HarnessResponse.Success.class,
                service.execute(request(new Command.LayoutValidate(stageSpec)))
                        .toCompletableFuture().join());
        LayoutValidationResult result = assertInstanceOf(
                HarnessResponse.Result.LayoutValidation.class, success.result()).result();
        assertEquals(LayoutValidationResult.Status.FAIL, result.status());
        assertEquals(LayoutValidationReason.CHECK_UNAVAILABLE,
                result.findings().getFirst().reason());
        assertEquals(LayoutValidationSeverity.ERROR,
                result.findings().getFirst().severity());
        assertEquals(1, coordinator.calls.get());
    }

    @Test void layoutValidationResultRoundTripsWithFindings() throws Exception {
        LayoutValidationResult result = new LayoutValidationResult(
                LayoutValidationResult.Status.FAIL,
                List.of(new LayoutFinding(
                        LayoutValidationReason.TEXT_COLLISION,
                        LayoutValidationSeverity.ERROR,
                        "label-left",
                        "label-right",
                        new Bounds(-4, -2, 20, 10),
                        "visible text ink overlaps related actor")),
                42,
                false,
                LayoutValidationConfig.defaults());

        HarnessResponse.Result.LayoutValidation response =
                new HarnessResponse.Result.LayoutValidation(result);
        String json = ProtocolJson.mapper().writeValueAsString(response);
        HarnessResponse.Result.LayoutValidation decoded = ProtocolJson.mapper().readValue(
                json, HarnessResponse.Result.LayoutValidation.class);

        assertEquals(result.status(), decoded.result().status());
        assertEquals(1, decoded.result().findings().size());
        assertEquals("TEXT_COLLISION",
                ProtocolJson.mapper().readTree(json).at("/result/findings/0/reason").asText());
    }

    private static HarnessRequest request(Command command) {
        return new HarnessRequest(ProtocolVersion.V1, "game", "request-layout", 500, command);
    }

    private static HarnessProtocolService service(RecordingCoordinator coordinator) {
        var waits = new dev.gdx.uiharness.core.wait.WaitEngine(
                () -> null, new dev.gdx.uiharness.core.locator.StrictResolution(),
                System::nanoTime, listener -> () -> {});
        var capabilities = new CapabilitySet(List.of("ui_validate_layout"));
        HarnessProtocolService.Session session = new HarnessProtocolService.Session(
                new NoopHarness(), new dev.gdx.uiharness.core.locator.StrictResolution(),
                waits, new NoopCapture(), capabilities,
                HarnessProtocolService.TraceController.unsupported(),
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                coordinator == null ? java.util.Optional.empty()
                        : java.util.Optional.of(coordinator));
        return new HarnessProtocolService(
                Map.of("game", session), Map.of(), System::nanoTime, Runnable::run);
    }

    private static final class RecordingCoordinator
            implements HarnessProtocolService.LayoutValidationCoordinator {
        final AtomicReference<Command.LayoutValidationSpec> lastSpec = new AtomicReference<>();
        final java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();

        @Override public CompletionStage<LayoutValidationResult> validate(
                Command.LayoutValidationSpec spec, Deadline deadline) {
            calls.incrementAndGet();
            lastSpec.set(spec);
            return CompletableFuture.completedFuture(new LayoutValidationResult(
                    LayoutValidationResult.Status.FAIL,
                    List.of(new LayoutFinding(
                            LayoutValidationReason.CHECK_UNAVAILABLE,
                            LayoutValidationSeverity.ERROR,
                            "root", null, new Bounds(0, 0, 960, 540),
                            "check unavailable: clipped_text")),
                    1, false, LayoutValidationConfig.defaults()));
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
