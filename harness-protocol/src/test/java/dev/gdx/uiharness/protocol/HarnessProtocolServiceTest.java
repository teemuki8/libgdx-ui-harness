package dev.gdx.uiharness.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gdx.uiharness.core.action.ActionResult;
import dev.gdx.uiharness.core.action.Harness;
import dev.gdx.uiharness.core.capture.CaptureRequest;
import dev.gdx.uiharness.core.capture.CapturedImage;
import dev.gdx.uiharness.core.capture.ScreenCapture;
import dev.gdx.uiharness.core.error.ErrorCode;
import dev.gdx.uiharness.core.error.ErrorEvidence;
import dev.gdx.uiharness.core.error.HarnessException;
import dev.gdx.uiharness.core.locator.Locator;
import dev.gdx.uiharness.core.locator.LocatorEngine;
import dev.gdx.uiharness.core.locator.QueryResult;
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
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class HarnessProtocolServiceTest {
    private static final SemanticSnapshot SNAPSHOT = snapshot();
    private static final MonotonicClock CLOCK = () -> 10L;

    @Test void exactExecuteInterfaceRoutesEveryV1CommandAndEchoesContext() {
        RecordingHarness harness = new RecordingHarness();
        RecordingCapture capture = new RecordingCapture();
        AtomicInteger traceStarts = new AtomicInteger();
        AtomicInteger traceStops = new AtomicInteger();
        HarnessProtocolService.TraceController traces = new HarnessProtocolService.TraceController() {
            @Override public CompletionStage<HarnessResponse.Result.TraceStarted> start(
                    Command.TraceStart command, Deadline deadline) {
                traceStarts.incrementAndGet();
                return CompletableFuture.completedFuture(
                        new HarnessResponse.Result.TraceStarted("trace-1"));
            }

            @Override public CompletionStage<HarnessResponse.Result.TraceStopped> stop(
                    Deadline deadline) {
                traceStops.incrementAndGet();
                return CompletableFuture.completedFuture(new HarnessResponse.Result.TraceStopped(
                        "trace-1", "trace://trace-1", 1, 10));
            }
        };
        HarnessProtocolService service = service(harness, capture, traces);

        assertResult(service, new Command.Sessions(), HarnessResponse.Result.Sessions.class);
        assertResult(service, new Command.Capabilities(),
                HarnessResponse.Result.Capabilities.class);
        assertResult(service, new Command.Snapshot(), HarnessResponse.Result.Snapshot.class);
        assertResult(service, new Command.Query(roleLocator()),
                HarnessResponse.Result.Query.class);
        assertResult(service, new Command.Action(roleLocator(),
                new Command.ActionSpec.Click(0, 0, false)),
                HarnessResponse.Result.Action.class);
        assertResult(service, new Command.Wait(roleLocator(), Command.WaitCondition.PRESENT),
                HarnessResponse.Result.Wait.class);
        assertResult(service, new Command.Screenshot(null, 10, 10, 100, 1024),
                HarnessResponse.Result.Screenshot.class);
        assertResult(service, new Command.TraceStart(1000, 1024),
                HarnessResponse.Result.TraceStarted.class);
        assertResult(service, new Command.TraceStop(),
                HarnessResponse.Result.TraceStopped.class);

        assertEquals(2, harness.snapshotCalls.get());
        assertEquals(1, harness.actionCalls.get());
        assertEquals(1, capture.calls.get());
        assertEquals(1, traceStarts.get());
        assertEquals(1, traceStops.get());
        assertEquals(Duration.ofMillis(500), harness.lastDeadline.get().timeout());
    }

    @Test void rejectsUnknownSessionWithoutInvokingBackend() {
        RecordingHarness harness = new RecordingHarness();
        HarnessProtocolService service = service(harness, new RecordingCapture(),
                HarnessProtocolService.TraceController.unsupported());
        HarnessRequest request = new HarnessRequest(ProtocolVersion.V1, "missing", "request-17",
                500, new Command.Snapshot());

        HarnessResponse.Failure failure = assertInstanceOf(HarnessResponse.Failure.class,
                await(service.execute(request)));

        assertEquals(ProtocolError.Code.SESSION_NOT_FOUND, failure.error().code());
        assertEquals("request-17", failure.requestId());
        assertEquals("missing", failure.sessionId());
        assertEquals(0, harness.snapshotCalls.get());
    }

    @Test void rejectsVersionMismatchBeforeSessionSelection() {
        RecordingHarness harness = new RecordingHarness();
        HarnessProtocolService service = service(harness, new RecordingCapture(),
                HarnessProtocolService.TraceController.unsupported());
        HarnessRequest request = new HarnessRequest(new ProtocolVersion(2, 0), "missing", "r-v2",
                500, new Command.Snapshot());

        HarnessResponse.Failure failure = assertInstanceOf(HarnessResponse.Failure.class,
                await(service.execute(request)));

        assertEquals(ProtocolError.Code.PROTOCOL_VERSION_MISMATCH, failure.error().code());
        assertEquals("r-v2", failure.error().requestId());
        assertEquals("1.0", failure.error().details().get("supportedVersion"));
        assertEquals(0, harness.snapshotCalls.get());
    }

    @Test void translatesTypedEvidenceWhileRedactingPathsAndStackFragments() {
        ErrorEvidence evidence = new ErrorEvidence(Optional.of("core-request"),
                Optional.of("core-session"), Optional.of("role(button)"),
                Duration.ofMillis(12), OptionalLong.of(7),
                Optional.of("/home/private/traces/t.json"),
                List.of(Map.of("path", "/secret/game/screen.java:12")),
                Map.of("failure", "at game.Actor.run(Actor.java:42) /tmp/crash.log",
                        "artifact", "trace://safe-reference"));
        RecordingHarness harness = new RecordingHarness();
        harness.snapshotFailure = new HarnessException(ErrorCode.NOT_FOUND,
                "not found under /home/private/project", evidence);
        HarnessProtocolService service = service(harness, new RecordingCapture(),
                HarnessProtocolService.TraceController.unsupported());

        HarnessResponse.Failure failure = assertInstanceOf(HarnessResponse.Failure.class,
                await(service.execute(request(new Command.Snapshot()))));
        ProtocolError error = failure.error();
        String json = write(error);

        assertEquals(ProtocolError.Code.NOT_FOUND, error.code());
        assertEquals("request-1", error.requestId());
        assertEquals("game", error.sessionId());
        assertEquals("role(button)", error.locator());
        assertEquals(12, error.elapsedMillis());
        assertEquals(7L, error.lastSnapshotRevision());
        assertFalse(json.contains("/home/private"));
        assertFalse(json.contains("/secret"));
        assertFalse(json.contains("/tmp"));
        assertFalse(json.contains("Actor.java"));
        assertTrue(json.contains("[redacted]"));
        assertTrue(json.contains("trace://safe-reference"));
    }

    @Test void unexpectedExceptionBecomesStableRedactedInternalError() {
        RecordingHarness harness = new RecordingHarness();
        harness.snapshotFailure = new IllegalStateException(
                "password at /home/private/app: game.Main.run(Main.java:9)");
        HarnessProtocolService service = service(harness, new RecordingCapture(),
                HarnessProtocolService.TraceController.unsupported());

        HarnessResponse.Failure failure = assertInstanceOf(HarnessResponse.Failure.class,
                await(service.execute(request(new Command.Snapshot()))));
        ProtocolError error = failure.error();
        String json = write(error);

        assertEquals(ProtocolError.Code.INTERNAL_ERROR, error.code());
        assertEquals("Internal harness failure", error.message());
        assertNotNull(error.traceId());
        assertTrue(error.traceId().matches("internal-[0-9a-f]{16}"));
        assertFalse(json.contains("password"));
        assertFalse(json.contains("/home"));
        assertFalse(json.contains("Main.java"));
        assertFalse(json.contains("IllegalStateException"));
    }

    @Test void backendCancellationMapsDeterministicallyWithoutLeakingCause() {
        RecordingHarness harness = new RecordingHarness();
        harness.snapshotFailure = new CancellationException("cancel /secret/path");
        HarnessProtocolService service = service(harness, new RecordingCapture(),
                HarnessProtocolService.TraceController.unsupported());

        HarnessResponse.Failure failure = assertInstanceOf(HarnessResponse.Failure.class,
                await(service.execute(request(new Command.Snapshot()))));

        assertEquals(ProtocolError.Code.TIMEOUT, failure.error().code());
        assertEquals("Request was cancelled", failure.error().message());
        assertEquals("cancelled", failure.error().details().get("reason"));
        assertFalse(write(failure).contains("secret"));
    }

    private static HarnessProtocolService service(RecordingHarness harness,
            RecordingCapture capture, HarnessProtocolService.TraceController traces) {
        LocatorEngine locators = new StrictResolution();
        FrameSignal frames = listener -> () -> {};
        WaitEngine waits = new WaitEngine(() -> SNAPSHOT, locators, CLOCK, frames);
        CapabilitySet capabilities = new CapabilitySet(List.of("action", "capabilities", "query",
                "screenshot", "snapshot", "trace", "wait"));
        HarnessProtocolService.Session session = new HarnessProtocolService.Session(
                harness, locators, waits, capture, capabilities, traces);
        return new HarnessProtocolService(Map.of("game", session), CLOCK, Runnable::run);
    }

    private static void assertResult(HarnessProtocolService service, Command command,
            Class<? extends HarnessResponse.Result> resultType) {
        HarnessResponse.Success success = assertInstanceOf(HarnessResponse.Success.class,
                await(service.execute(request(command))));
        assertInstanceOf(resultType, success.result());
        assertEquals("request-1", success.requestId());
        assertEquals("game", success.sessionId());
        assertEquals(ProtocolVersion.V1, success.version());
    }

    private static HarnessRequest request(Command command) {
        return new HarnessRequest(ProtocolVersion.V1, "game", "request-1", 500, command);
    }

    private static Command.LocatorSpec roleLocator() {
        return new Command.LocatorSpec.Role("button");
    }

    private static HarnessResponse await(CompletionStage<HarnessResponse> stage) {
        return stage.toCompletableFuture().join();
    }

    private static String write(Object value) {
        try {
            return ProtocolJson.mapper().writeValueAsString(value);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
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
        private final AtomicInteger snapshotCalls = new AtomicInteger();
        private final AtomicInteger actionCalls = new AtomicInteger();
        private final AtomicReference<Deadline> lastDeadline = new AtomicReference<>();
        private RuntimeException snapshotFailure;

        @Override public CompletionStage<ActionResult> perform(Locator locator,
                dev.gdx.uiharness.core.action.Action action, Deadline deadline) {
            actionCalls.incrementAndGet();
            lastDeadline.set(deadline);
            return CompletableFuture.completedFuture(
                    new ActionResult(1, 2, "clicked", Map.of("target", "root")));
        }

        @Override public CompletionStage<SemanticSnapshot> snapshot(Deadline deadline) {
            snapshotCalls.incrementAndGet();
            lastDeadline.set(deadline);
            if (snapshotFailure != null) {
                return CompletableFuture.failedFuture(snapshotFailure);
            }
            return CompletableFuture.completedFuture(SNAPSHOT);
        }
    }

    private static final class RecordingCapture implements ScreenCapture {
        private final AtomicInteger calls = new AtomicInteger();

        @Override public CompletionStage<CapturedImage> capture(CaptureRequest request,
                Deadline deadline) {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(new CapturedImage(new byte[] {1, 2, 3},
                    "0".repeat(64), 1, 1, 1, 1, new CapturedImage.Scale(1, 1)));
        }

        @Override public void close() {}
    }
}
