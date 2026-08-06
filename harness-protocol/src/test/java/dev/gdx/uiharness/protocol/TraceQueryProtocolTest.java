package dev.gdx.uiharness.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gdx.uiharness.core.trace.StateTransition;
import dev.gdx.uiharness.core.trace.TransitionKind;
import dev.gdx.uiharness.core.trace.TransitionQueryResult;
import dev.gdx.uiharness.core.time.Deadline;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class TraceQueryProtocolTest {
    private final Command.TraceQuerySpec spec = new Command.TraceQuerySpec(
            "trace-1", null, List.of("appeared", "disabled"), List.of(),
            null, null, 128, 65_536, 2_000);

    @Test void traceQueryCommandRoundTripsThroughTheClosedSchema() throws Exception {
        Command.TraceQuery command = new Command.TraceQuery(spec);

        String json = ProtocolJson.mapper().writeValueAsString(command);

        assertEquals(command, ProtocolJson.mapper().readValue(
                json, Command.TraceQuery.class));
        assertTrue(json.contains("\"type\":\"trace-query\""));
        assertTrue(json.contains("\"kinds\":[\"appeared\",\"disabled\"]"));
    }

    @Test void traceQueryRejectsUnknownKindsAndBounds() {
        assertThrows(IllegalArgumentException.class, () -> new Command.TraceQuerySpec(
                "trace-1", null, List.of("teleported"), List.of(),
                null, null, 128, 65_536, 2_000));
        assertThrows(IllegalArgumentException.class, () -> new Command.TraceQuerySpec(
                "trace-1", null, List.of(), List.of(),
                10L, 5L, 128, 65_536, 2_000));
        assertThrows(IllegalArgumentException.class, () -> new Command.TraceQuerySpec(
                "trace-1", null, List.of(), List.of(),
                null, null, 0, 65_536, 2_000));
    }

    @Test void traceQueryRoutesThroughTheTraceController() {
        AtomicReference<dev.gdx.uiharness.core.trace.TransitionQuery> observed =
                new AtomicReference<>();
        HarnessProtocolService service = service(observed);

        HarnessResponse.Success success = assertInstanceOf(HarnessResponse.Success.class,
                service.execute(request(new Command.TraceQuery(spec)))
                        .toCompletableFuture().join());
        TransitionQueryResult result = assertInstanceOf(
                HarnessResponse.Result.TraceQuery.class, success.result()).result();
        assertEquals("trace-1", result.traceId());
        assertEquals(1, result.transitions().size());
        assertEquals(TransitionKind.DISABLED, result.transitions().getFirst().kind());
        assertEquals("trace-1", observed.get().traceId());
        assertEquals(2, observed.get().kinds().size());
    }

    private static HarnessRequest request(Command command) {
        return new HarnessRequest(ProtocolVersion.V1, "game", "request-trace-query", 500,
                command);
    }

    private static HarnessProtocolService service(
            AtomicReference<dev.gdx.uiharness.core.trace.TransitionQuery> observed) {
        var waits = new dev.gdx.uiharness.core.wait.WaitEngine(
                () -> null, new dev.gdx.uiharness.core.locator.StrictResolution(),
                System::nanoTime, listener -> () -> {});
        var capabilities = new CapabilitySet(List.of("ui_trace_query"));
        HarnessProtocolService.TraceController traces =
                new HarnessProtocolService.TraceController() {
                    @Override public CompletionStage<HarnessResponse.Result.TraceStarted> start(
                            Command.TraceStart command, Deadline deadline) {
                        throw new AssertionError("start is not expected");
                    }

                    @Override public CompletionStage<HarnessResponse.Result.TraceStopped> stop(
                            Deadline deadline) {
                        throw new AssertionError("stop is not expected");
                    }

                    @Override public CompletionStage<TransitionQueryResult> query(
                            dev.gdx.uiharness.core.trace.TransitionQuery query,
                            Deadline deadline) {
                        observed.set(query);
                        return CompletableFuture.completedFuture(new TransitionQueryResult(
                                query.traceId(),
                                List.of(new StateTransition(
                                        TransitionKind.DISABLED,
                                        1, 2, 1, 2, 1, 2, "test-id:save",
                                        List.of("enabled"),
                                        Map.of("enabled", "true"),
                                        Map.of("enabled", "false"),
                                        4L)),
                                false, 0, 0));
                    }
                };
        HarnessProtocolService.Session session = new HarnessProtocolService.Session(
                new NoopHarness(), new dev.gdx.uiharness.core.locator.StrictResolution(),
                waits, new NoopCapture(), capabilities, traces);
        return new HarnessProtocolService(
                Map.of("game", session), Map.of(), System::nanoTime, Runnable::run);
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
