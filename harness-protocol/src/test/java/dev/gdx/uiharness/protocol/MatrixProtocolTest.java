package dev.gdx.uiharness.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gdx.uiharness.core.matrix.MatrixCaseResult;
import dev.gdx.uiharness.core.matrix.MatrixCaseStatus;
import dev.gdx.uiharness.core.matrix.MatrixHiDpi;
import dev.gdx.uiharness.core.matrix.MatrixReport;
import dev.gdx.uiharness.core.matrix.MatrixWindow;
import java.util.List;
import java.util.Map;
import dev.gdx.uiharness.core.time.Deadline;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class MatrixProtocolTest {
    private final Command.MatrixRunSpec spec = new Command.MatrixRunSpec(
            "matrix",
            List.of(new Command.MatrixWindowSpec(1280, 720), new Command.MatrixWindowSpec(1920, 1080)),
            List.of(1.0),
            List.of(1.0),
            List.of("LOGICAL"),
            List.of("en"),
            List.of(),
            List.of(),
            100,
            5_000);

    @Test void matrixCommandsRoundTripThroughTheClosedSchema() throws Exception {
        Command.MatrixRun run = new Command.MatrixRun(spec);
        Command.MatrixResults results = new Command.MatrixResults("matrix-run-1");

        String runJson = ProtocolJson.mapper().writeValueAsString(run);
        String resultsJson = ProtocolJson.mapper().writeValueAsString(results);

        assertEquals(run, ProtocolJson.mapper().readValue(runJson, Command.MatrixRun.class));
        assertEquals(results, ProtocolJson.mapper().readValue(
                resultsJson, Command.MatrixResults.class));
        assertTrue(runJson.contains("\"type\":\"matrix-run\""));
        assertTrue(runJson.contains("\"windows\":[{\"width\":1280,\"height\":720}"));
        assertTrue(resultsJson.contains("\"type\":\"matrix-results\""));
    }

    @Test void matrixSpecRejectsDuplicateDimensionsUnknownModesAndBounds() {
        assertThrows(IllegalArgumentException.class, () -> new Command.MatrixRunSpec(
                "matrix",
                List.of(new Command.MatrixWindowSpec(1280, 720),
                        new Command.MatrixWindowSpec(1280, 720)),
                List.of(1.0), List.of(1.0), List.of("LOGICAL"), List.of("en"),
                List.of(), List.of(), 100, 5_000));
        assertThrows(IllegalArgumentException.class, () -> new Command.MatrixRunSpec(
                "matrix",
                List.of(new Command.MatrixWindowSpec(1280, 720)),
                List.of(1.0), List.of(1.0), List.of("RETINA"), List.of("en"),
                List.of(), List.of(), 100, 5_000));
        assertThrows(IllegalArgumentException.class, () -> new Command.MatrixRunSpec(
                "matrix",
                List.of(new Command.MatrixWindowSpec(1280, 720)),
                List.of(1.0), List.of(1.0), List.of("LOGICAL"), List.of("en"),
                List.of(), List.of(), 0, 5_000));
        assertThrows(IllegalArgumentException.class, () -> new Command.MatrixRunSpec(
                "matrix",
                List.of(new Command.MatrixWindowSpec(1280, 720)),
                List.of(0.0), List.of(1.0), List.of("LOGICAL"), List.of("en"),
                List.of(), List.of(), 100, 5_000));
    }

    @Test void matrixRequiresCoordinatorAndRoutesRunAndResults() {
        RecordingCoordinator coordinator = new RecordingCoordinator();
        HarnessProtocolService service = service(coordinator);

        HarnessResponse.Failure unavailable = assertInstanceOf(HarnessResponse.Failure.class,
                service(null).execute(request(new Command.MatrixRun(spec)))
                        .toCompletableFuture().join());
        assertEquals(ProtocolError.Code.UNSUPPORTED_CAPABILITY, unavailable.error().code());

        HarnessResponse.Success started = assertInstanceOf(HarnessResponse.Success.class,
                service.execute(request(new Command.MatrixRun(spec)))
                        .toCompletableFuture().join());
        assertEquals("matrix-run-1", assertInstanceOf(
                HarnessResponse.Result.MatrixRunStarted.class, started.result()).runId());

        HarnessResponse.Success report = assertInstanceOf(HarnessResponse.Success.class,
                service.execute(request(new Command.MatrixResults("matrix-run-1")))
                        .toCompletableFuture().join());
        MatrixReport data = assertInstanceOf(
                HarnessResponse.Result.MatrixReportData.class, report.result()).report();
        assertEquals("matrix-run-1", data.runId());
        assertEquals(1, data.results().size());
        assertEquals(MatrixCaseStatus.PASSED, data.results().getFirst().status());
    }

    private static HarnessRequest request(Command command) {
        return new HarnessRequest(ProtocolVersion.V1, "game", "request-matrix", 500, command);
    }

    private static HarnessProtocolService service(RecordingCoordinator coordinator) {
        var waits = new dev.gdx.uiharness.core.wait.WaitEngine(
                () -> null, new dev.gdx.uiharness.core.locator.StrictResolution(),
                System::nanoTime, listener -> () -> {});
        var capabilities = new CapabilitySet(List.of("ui_matrix_run", "ui_matrix_results"));
        HarnessProtocolService.Session session = new HarnessProtocolService.Session(
                new NoopHarness(), new dev.gdx.uiharness.core.locator.StrictResolution(),
                waits, new NoopCapture(), capabilities,
                HarnessProtocolService.TraceController.unsupported(),
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
            implements HarnessProtocolService.MatrixCoordinator {
        final AtomicReference<Command.MatrixRunSpec> lastSpec = new AtomicReference<>();

        @Override public CompletionStage<String> run(
                Command.MatrixRunSpec spec, Deadline deadline) {
            lastSpec.set(spec);
            return CompletableFuture.completedFuture("matrix-run-1");
        }

        @Override public CompletionStage<MatrixReport> results(String runId) {
            return CompletableFuture.completedFuture(new MatrixReport(
                    runId,
                    "matrix",
                    List.of(new MatrixCaseResult(
                            new dev.gdx.uiharness.core.matrix.MatrixCaseSummary(
                                    0, new MatrixWindow(1280, 720), 1.0, 1.0,
                                    MatrixHiDpi.LOGICAL, "en", "", 16.0 / 9.0),
                            MatrixCaseStatus.PASSED,
                            new MatrixWindow(1280, 720),
                            1.0, 1.0,
                            MatrixHiDpi.LOGICAL,
                            List.of(0), List.of(), List.of(), "")),
                    false));
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
