package dev.gdx.uiharness.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gdx.uiharness.core.action.ActionResult;
import dev.gdx.uiharness.core.action.Harness;
import dev.gdx.uiharness.core.capture.CaptureRequest;
import dev.gdx.uiharness.core.capture.CapturedImage;
import dev.gdx.uiharness.core.capture.ScreenCapture;
import dev.gdx.uiharness.core.assertion.AssertionEvidence;
import dev.gdx.uiharness.core.assertion.AssertionResult;
import dev.gdx.uiharness.core.assertion.AssertionSnapshotSource;
import dev.gdx.uiharness.core.contract.ContractVersion;
import dev.gdx.uiharness.core.contract.StateActionContract;
import dev.gdx.uiharness.core.error.ErrorCode;
import dev.gdx.uiharness.core.error.ErrorEvidence;
import dev.gdx.uiharness.core.error.HarnessException;
import dev.gdx.uiharness.core.locator.Locator;
import dev.gdx.uiharness.core.locator.LocatorEngine;
import dev.gdx.uiharness.core.locator.StrictResolution;
import dev.gdx.uiharness.core.model.Bounds;
import dev.gdx.uiharness.core.model.Role;
import dev.gdx.uiharness.core.model.SemanticNode;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import dev.gdx.uiharness.core.model.SemanticState;
import dev.gdx.uiharness.core.scenario.ScenarioDefinition;
import dev.gdx.uiharness.core.scenario.ScenarioRegistry;
import dev.gdx.uiharness.core.scenario.ScenarioLifecycle;
import dev.gdx.uiharness.core.scenario.ScenarioRequest;
import dev.gdx.uiharness.core.scenario.ScenarioResult;
import dev.gdx.uiharness.core.time.Deadline;
import dev.gdx.uiharness.core.time.MonotonicClock;
import dev.gdx.uiharness.core.wait.FrameSignal;
import dev.gdx.uiharness.core.wait.WaitEngine;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
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
        HarnessResponse.Result.Assertion assertion = assertInstanceOf(
                HarnessResponse.Result.Assertion.class,
                success(service, new Command.Assert(1,
                        new Command.LocatorSpec.TestId("save"),
                        new Command.AssertionSpec.Enabled())).result());
        assertEquals("passed", assertion.outcome());
        assertEquals("enabled=true", assertion.lastObserved());
        assertEquals("true", assertion.expected());
        assertEquals(1, assertion.revision());
        assertEquals(1, assertion.frame());
        assertEquals(List.of(), assertion.candidates());
        assertFalse(assertion.truncated());
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

    @Test void mapsPreFrameStabilityDeadlineEvidenceAsFailedAssertion() {
        Command.Assert command = new Command.Assert(
                1,
                new Command.LocatorSpec.TestId("save"),
                new Command.AssertionSpec.StableForFrames(3, List.of("text")));
        AssertionResult core = new AssertionResult(
                AssertionResult.Status.FAILED,
                new AssertionEvidence("save-node", "3 completed frames", "0/3", 7, 9),
                Duration.ofMillis(10).toNanos());

        HarnessResponse.Result.Assertion assertion =
                HarnessResponse.Result.Assertion.fromCore(command, core);

        assertEquals("failed", assertion.outcome());
        assertEquals("retryable", assertion.actionability());
        assertEquals("save-node", assertion.nodeId());
        assertEquals("3 completed frames", assertion.expected());
        assertEquals("0/3", assertion.lastObserved());
        assertEquals(7, assertion.revision());
        assertEquals(9, assertion.frame());
        assertEquals(10, assertion.elapsedMillis());
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

    @Test void combinedSnapshotProviderKeepsSemanticAndContractEvidenceAtomic() {
        RecordingHarness harness = new RecordingHarness();
        harness.snapshotFailure = new IllegalStateException(
                "independent semantic snapshot must not run");
        StateActionContract contract = new StateActionContract(
                ContractVersion.V1, "atomic-state", 1, 1,
                List.of(), List.of(), null, List.of(), List.of(), null);
        HarnessProtocolService.ContractProvider contracts =
                new HarnessProtocolService.ContractProvider() {
                    @Override public CompletionStage<StateActionContract> snapshot(
                            Deadline deadline) {
                        return CompletableFuture.failedFuture(new IllegalStateException(
                                "independent contract snapshot must not run"));
                    }

                    @Override public CompletionStage<HarnessProtocolService.SnapshotEvidence>
                            snapshotWith(Harness ignored, Deadline deadline) {
                        return CompletableFuture.completedFuture(
                                new HarnessProtocolService.SnapshotEvidence(SNAPSHOT, contract));
                    }
                };
        HarnessProtocolService.Session session = session(
                harness, new RecordingCapture(),
                HarnessProtocolService.TraceController.unsupported());
        HarnessProtocolService service = new HarnessProtocolService(
                Map.of("game", session), Map.of("game", contracts), CLOCK, Runnable::run);

        HarnessResponse.Success success = assertInstanceOf(HarnessResponse.Success.class,
                await(service.execute(request(new Command.Snapshot()))));
        HarnessResponse.Result.Snapshot result = assertInstanceOf(
                HarnessResponse.Result.Snapshot.class, success.result());

        assertEquals("atomic-state", result.snapshot().contract().stateId());
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
                Optional.of("core-session"), Optional.of("file:///home/private/locator.txt"),
                Duration.ofMillis(12), OptionalLong.of(7),
                Optional.of("artifact://safe-trace-reference"),
                List.of(Map.of(
                        "unix", "file:///home/private/screen.java:12",
                        "windows", "file:///C:/Users/private/screen.java:12")),
                Map.of(
                        "failure", "at game.Actor.run(Actor.java:42) file:///tmp/crash.log",
                        "windows", "file://C:\\Users\\private\\crash.log",
                        "trace", "trace://safe-reference",
                        "artifact", "artifact://safe-reference"),
                List.of());
        RecordingHarness harness = new RecordingHarness();
        harness.snapshotFailure = new HarnessException(ErrorCode.NOT_FOUND,
                "not found under /home/private/project and file:///home/private/project",
                evidence);
        HarnessProtocolService service = service(harness, new RecordingCapture(),
                HarnessProtocolService.TraceController.unsupported());

        HarnessResponse.Failure failure = assertInstanceOf(HarnessResponse.Failure.class,
                await(service.execute(request(new Command.Snapshot()))));
        ProtocolError error = failure.error();
        String json = write(error);

        assertEquals(ProtocolError.Code.NOT_FOUND, error.code());
        assertEquals("request-1", error.requestId());
        assertEquals("[redacted]", error.locator());
        assertEquals(12, error.elapsedMillis());
        assertEquals(7L, error.lastSnapshotRevision());
        assertEquals("artifact://safe-trace-reference", error.traceReference());
        assertFalse(json.contains("/home/private"));
        assertFalse(json.contains("/tmp"));
        assertFalse(json.contains("C:/Users"));
        assertFalse(json.contains("C:\\Users"));
        assertFalse(json.contains("file:"));
        assertFalse(json.contains("Actor.java"));
        assertTrue(json.contains("[redacted]"));
        assertTrue(json.contains("trace://safe-reference"));
        assertTrue(json.contains("artifact://safe-reference"));
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

    @Test void oversizedCapturedPngMapsToTypedLimitFailure() {
        byte[] oversized = new byte[HarnessResponse.Result.Screenshot.MAX_PNG_BYTES + 1];
        RecordingCapture capture = new RecordingCapture();
        capture.image = new CapturedImage(oversized, "0".repeat(64),
                1, 1, 1, 1, new CapturedImage.Scale(1, 1));
        HarnessProtocolService service = service(new RecordingHarness(), capture,
                HarnessProtocolService.TraceController.unsupported());

        HarnessResponse.Failure failure = assertInstanceOf(HarnessResponse.Failure.class,
                await(service.execute(request(new Command.Screenshot(
                        null, 1, 1, 1, HarnessResponse.Result.Screenshot.MAX_PNG_BYTES)))));

        assertEquals(ProtocolError.Code.LIMIT_EXCEEDED, failure.error().code());
        assertEquals("response-byte-limit", failure.error().details().get("limit"));
        assertFalse(write(failure).contains("internal-error"));
    }

    @Test void executeWithAttachmentsCarriesTheSingleDefensiveSnapshotForScreenshot()
            throws Exception {
        byte[] payload = {1, 2, 3, 4, 5};
        RecordingCapture capture = new RecordingCapture();
        capture.image = new CapturedImage(payload, sha256Hex(payload), 1, 1, 5, 1,
                new CapturedImage.Scale(1, 1));
        HarnessProtocolService service = service(new RecordingHarness(), capture, traces());
        HarnessProtocolService.Execution execution = service
                .executeWithAttachments(new HarnessRequest(ProtocolVersion.V1, "game", "req-1", 10,
                        new Command.Screenshot(null, 8, 8, 64, 128)))
                .toCompletableFuture().join();

        HarnessResponse.Result.Screenshot screenshot = assertInstanceOf(
                HarnessResponse.Result.Screenshot.class,
                assertInstanceOf(HarnessResponse.Success.class, execution.response()).result());
        assertArrayEquals(payload, Base64.getDecoder().decode(screenshot.pngBase64()),
                "the public String wire representation must decode to the captured bytes");
        BinaryAttachment attachment =
                execution.captures().get(HarnessProtocolService.SCREENSHOT_CAPTURE);
        assertArrayEquals(payload, readAll(attachment.asByteBuffer()),
                "the internal capture attachment must equal the captured bytes exactly");
        assertEquals(sha256Hex(payload), attachment.sha256());
        assertEquals(payload.length, attachment.length());
    }

    @Test void executeKeepsItsExactPublicContractWithEmptyCaptures() {
        RecordingCapture capture = new RecordingCapture();
        HarnessProtocolService service = service(new RecordingHarness(), capture, traces());
        HarnessResponse response = service.execute(new HarnessRequest(
                ProtocolVersion.V1, "game", "req-1", 10,
                new Command.Screenshot(null, 8, 8, 64, 128)))
                .toCompletableFuture().join();
        HarnessResponse.Result.Screenshot screenshot = assertInstanceOf(
                HarnessResponse.Result.Screenshot.class,
                assertInstanceOf(HarnessResponse.Success.class, response).result());
        assertEquals("AQID", screenshot.pngBase64(), "public execute must keep its exact output");
    }

    @Test void executionBoundsAttachmentsAndDefensivelyOwnsTheMap() {
        Map<String, BinaryAttachment> supplied = new java.util.HashMap<>();
        supplied.put(HarnessProtocolService.SCREENSHOT_CAPTURE,
                BinaryAttachment.of(new byte[] {1, 2, 3}));
        HarnessProtocolService.Execution execution = new HarnessProtocolService.Execution(
                new HarnessResponse.Success(ProtocolVersion.V1, "r", "game",
                        new HarnessResponse.Result.Screenshot("AQID", "0".repeat(64),
                                1, 1, 3, 1, 1, 1)),
                supplied);
        supplied.put("extra", BinaryAttachment.of(new byte[] {9}));
        assertEquals(1, execution.captures().size(),
                "the Execution must own its attachment map defensively");

        Map<String, BinaryAttachment> tooMany = new java.util.HashMap<>();
        for (int index = 0; index < HarnessProtocolService.Execution.MAX_ATTACHMENTS + 1; index++) {
            tooMany.put("key-" + index, BinaryAttachment.of(new byte[] {(byte) index}));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new HarnessProtocolService.Execution(
                        new HarnessResponse.Success(ProtocolVersion.V1, "r", "game",
                                new HarnessResponse.Result.Screenshot("AQID", "0".repeat(64),
                                        1, 1, 3, 1, 1, 1)),
                        tooMany));
        // Per-attachment size is enforced by BinaryAttachment.of/takeCaptured (1..MAX_PNG_BYTES),
        // covered in BinaryAttachmentTest.ofRejectsEmptyAndOverLimitPayloadsAtTheFactory.
    }

    @Test void cancellingExecuteResponseCancelsRoutedActionAndCaptureStages() {
        RecordingHarness harness = new RecordingHarness();
        harness.actionResult = new CompletableFuture<>();
        RecordingCapture capture = new RecordingCapture();
        capture.result = new CompletableFuture<>();
        HarnessProtocolService service = service(harness, capture,
                HarnessProtocolService.TraceController.unsupported());

        CompletableFuture<HarnessResponse> actionResponse = service.execute(request(
                new Command.Action(roleLocator(),
                        new Command.ActionSpec.Click(0, 0, false)))).toCompletableFuture();
        CompletableFuture<HarnessResponse> captureResponse = service.execute(request(
                new Command.Screenshot(null, 10, 10, 100, 1024))).toCompletableFuture();

        assertTrue(actionResponse.cancel(false));
        assertTrue(captureResponse.cancel(false));
        assertTrue(harness.actionResult.isCancelled());
        assertTrue(capture.result.isCancelled());
    }

    @Test void cancellingExecuteResponsePreventsQueuedWaitTaskFromStarting() {
        AtomicInteger snapshotReads = new AtomicInteger();
        AtomicReference<Runnable> queuedTask = new AtomicReference<>();
        LocatorEngine locators = new StrictResolution();
        FrameSignal frames = listener -> () -> {};
        WaitEngine waits = new WaitEngine(() -> {
            snapshotReads.incrementAndGet();
            return SNAPSHOT;
        }, locators, CLOCK, frames);
        CapabilitySet capabilities = new CapabilitySet(List.of("wait"));
        HarnessProtocolService service = new HarnessProtocolService(Map.of("game",
                new HarnessProtocolService.Session(new RecordingHarness(), locators, waits,
                        new RecordingCapture(), capabilities,
                        HarnessProtocolService.TraceController.unsupported())),
                CLOCK, task -> assertTrue(queuedTask.compareAndSet(null, task)));

        CompletableFuture<HarnessResponse> response = service.execute(request(
                new Command.Wait(roleLocator(), Command.WaitCondition.PRESENT)))
                .toCompletableFuture();
        assertNotNull(queuedTask.get());

        assertTrue(response.cancel(false));
        queuedTask.get().run();
        assertEquals(0, snapshotReads.get());
    }

    @Test void scenarioOperationsAreExplicitlyUnavailableWithoutRegistration() {
        HarnessProtocolService service = service(
                new RecordingHarness(), new RecordingCapture(),
                HarnessProtocolService.TraceController.unsupported());

        HarnessResponse.Result.ScenarioList listed = assertInstanceOf(
                HarnessResponse.Result.ScenarioList.class,
                success(service, new Command.ScenarioList()).result());
        HarnessResponse.Result.ScenarioStart started = assertInstanceOf(
                HarnessResponse.Result.ScenarioStart.class,
                success(service, scenarioStart("known")).result());

        assertFalse(listed.available());
        assertTrue(listed.scenarios().isEmpty());
        assertInstanceOf(HarnessResponse.ScenarioStartOutcome.Unavailable.class,
                started.outcome());
    }

    @Test void registeredScenarioOperationsListRouteAndRejectUnknownIdsTerminally() {
        ScenarioRegistry registry = new ScenarioRegistry();
        registry.register(definition("known"), new NoOpScenarioLifecycle());
        AtomicBoolean invoked = new AtomicBoolean();
        HarnessProtocolService.ScenarioCoordinator coordinator = request -> {
            invoked.set(true);
            return CompletableFuture.completedFuture(
                    new HarnessResponse.ScenarioStartOutcome.Completed(scenarioResult()));
        };
        HarnessProtocolService.Session registered = session(
                new RecordingHarness(), new RecordingCapture(),
                HarnessProtocolService.TraceController.unsupported(), registry, coordinator);
        HarnessProtocolService service = new HarnessProtocolService(
                Map.of("game", registered), CLOCK, Runnable::run);

        HarnessResponse.Result.ScenarioList listed = assertInstanceOf(
                HarnessResponse.Result.ScenarioList.class,
                success(service, new Command.ScenarioList()).result());
        HarnessResponse.Result.ScenarioStart started = assertInstanceOf(
                HarnessResponse.Result.ScenarioStart.class,
                success(service, scenarioStart("known")).result());
        HarnessResponse.Result.ScenarioStart unknown = assertInstanceOf(
                HarnessResponse.Result.ScenarioStart.class,
                success(service, scenarioStart("missing")).result());

        assertTrue(listed.available());
        assertEquals(List.of("known"),
                listed.scenarios().stream().map(HarnessResponse.ScenarioDefinitionData::id).toList());
        assertInstanceOf(HarnessResponse.ScenarioStartOutcome.Completed.class, started.outcome());
        HarnessResponse.ScenarioStartOutcome.Rejected rejected = assertInstanceOf(
                HarnessResponse.ScenarioStartOutcome.Rejected.class, unknown.outcome());
        assertEquals("unknown-scenario", rejected.reason());
        assertTrue(invoked.get());
    }

    @Test void registryWithoutCoordinatorListsDefinitionsButStartRemainsUnavailable() {
        ScenarioRegistry registry = new ScenarioRegistry();
        registry.register(definition("known"), new NoOpScenarioLifecycle());
        HarnessProtocolService.Session basic = session(
                new RecordingHarness(), new RecordingCapture(),
                HarnessProtocolService.TraceController.unsupported());
        HarnessProtocolService.Session registered = new HarnessProtocolService.Session(
                basic.harness(), basic.locators(), basic.waits(), basic.capture(),
                basic.capabilities(), basic.traces(), Optional.of(registry), Optional.empty());
        HarnessProtocolService service = new HarnessProtocolService(
                Map.of("game", registered), CLOCK, Runnable::run);

        HarnessResponse.Result.ScenarioList listed = assertInstanceOf(
                HarnessResponse.Result.ScenarioList.class,
                success(service, new Command.ScenarioList()).result());
        HarnessResponse.Result.ScenarioStart started = assertInstanceOf(
                HarnessResponse.Result.ScenarioStart.class,
                success(service, scenarioStart("known")).result());

        assertTrue(listed.available());
        assertEquals(1, listed.scenarios().size());
        assertInstanceOf(HarnessResponse.ScenarioStartOutcome.Unavailable.class,
                started.outcome());
    }

    @Test void coordinatorIncompatibleRejectionRemainsAClosedTerminalOutcome() {
        ScenarioRegistry registry = new ScenarioRegistry();
        registry.register(definition("known"), new NoOpScenarioLifecycle());
        HarnessProtocolService.ScenarioCoordinator coordinator = request ->
                CompletableFuture.completedFuture(
                        new HarnessResponse.ScenarioStartOutcome.Rejected(
                                "incompatible-scenario"));
        HarnessProtocolService service = new HarnessProtocolService(
                Map.of("game", session(
                        new RecordingHarness(), new RecordingCapture(),
                        HarnessProtocolService.TraceController.unsupported(),
                        registry, coordinator)),
                CLOCK, Runnable::run);

        HarnessResponse.Result.ScenarioStart result = assertInstanceOf(
                HarnessResponse.Result.ScenarioStart.class,
                success(service, scenarioStart("known")).result());
        HarnessResponse.ScenarioStartOutcome.Rejected rejected = assertInstanceOf(
                HarnessResponse.ScenarioStartOutcome.Rejected.class, result.outcome());

        assertEquals("incompatible-scenario", rejected.reason());
    }

    private static HarnessProtocolService service(RecordingHarness harness,
            RecordingCapture capture, HarnessProtocolService.TraceController traces) {
        return new HarnessProtocolService(Map.of("game", session(harness, capture, traces)),
                CLOCK, Runnable::run);
    }

    private static HarnessProtocolService.Session session(
            RecordingHarness harness,
            RecordingCapture capture,
            HarnessProtocolService.TraceController traces) {
        LocatorEngine locators = new StrictResolution();
        FrameSignal frames = listener -> () -> {};
        AssertionSnapshotSource assertionSnapshots = new AssertionSnapshotSource() {
            @Override public SemanticSnapshot currentSnapshot() {
                return SNAPSHOT;
            }

            @Override public SemanticSnapshot snapshotFor(FrameSignal.Frame frame) {
                return new SemanticSnapshot(
                        frame.revision(), frame.frame(), SNAPSHOT.rootId(), SNAPSHOT.nodes());
            }
        };
        WaitEngine waits = new WaitEngine(
                () -> SNAPSHOT, assertionSnapshots, locators, CLOCK, frames,
                (delay, wakeup) -> () -> {});
        CapabilitySet capabilities = new CapabilitySet(List.of("action", "capabilities", "query",
                "screenshot", "snapshot", "trace", "ui_assert", "wait"));
        return new HarnessProtocolService.Session(
                harness, locators, waits, capture, capabilities, traces);
    }

    private static HarnessProtocolService.Session session(
            RecordingHarness harness,
            RecordingCapture capture,
            HarnessProtocolService.TraceController traces,
            ScenarioRegistry registry,
            HarnessProtocolService.ScenarioCoordinator coordinator) {
        HarnessProtocolService.Session basic = session(harness, capture, traces);
        return new HarnessProtocolService.Session(
                basic.harness(), basic.locators(), basic.waits(), basic.capture(),
                new CapabilitySet(List.of("action", "capabilities", "query", "scenario-list",
                        "scenario-start", "screenshot", "snapshot", "trace", "ui_assert", "wait")),
                basic.traces(), Optional.of(registry), Optional.of(coordinator));
    }

    private static HarnessResponse.Success success(
            HarnessProtocolService service, Command command) {
        return assertInstanceOf(
                HarnessResponse.Success.class, await(service.execute(request(command))));
    }

    private static Command.ScenarioStart scenarioStart(String id) {
        return new Command.ScenarioStart(id, 7, Map.of("locale", "en"), "desktop");
    }

    private static ScenarioDefinition definition(String id) {
        return new ScenarioDefinition(
                1, id, "v1", "game", List.of("desktop"), 2, Duration.ofMinutes(10));
    }

    private static ScenarioResult scenarioResult() {
        return new ScenarioResult(
                1, "known", "v1", "digest", 7, "game", "process-1", "game",
                1, 1, 2, 2, "desktop", "ready", Duration.ofMillis(10), 1, true,
                Optional.empty());
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

    private static HarnessProtocolService.TraceController traces() {
        return HarnessProtocolService.TraceController.unsupported();
    }

    private static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 unavailable", impossible);
        }
    }

    private static byte[] readAll(ByteBuffer view) {
        ByteBuffer local = view.duplicate();
        byte[] bytes = new byte[local.remaining()];
        local.get(bytes);
        return bytes;
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
        private CompletableFuture<ActionResult> actionResult;

        @Override public CompletionStage<ActionResult> perform(Locator locator,
                dev.gdx.uiharness.core.action.Action action, Deadline deadline) {
            actionCalls.incrementAndGet();
            lastDeadline.set(deadline);
            if (actionResult != null) {
                return actionResult;
            }
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

    private static final class NoOpScenarioLifecycle implements ScenarioLifecycle {
        @Override public void setup(ScenarioRequest request) {}
        @Override public void reset(ScenarioRequest request) {}
        @Override public boolean ready(ScenarioRequest request) {
            return true;
        }
        @Override public String startStateIdentity(
                ScenarioRequest request, SemanticSnapshot snapshot) {
            return "ready";
        }
        @Override public void cleanup(ScenarioRequest request) {}
    }

    private static final class RecordingCapture implements ScreenCapture {
        private final AtomicInteger calls = new AtomicInteger();
        private CapturedImage image = new CapturedImage(new byte[] {1, 2, 3},
                "0".repeat(64), 1, 1, 1, 1, new CapturedImage.Scale(1, 1));
        private CompletableFuture<CapturedImage> result;

        @Override public CompletionStage<CapturedImage> capture(CaptureRequest request,
                Deadline deadline) {
            calls.incrementAndGet();
            return result == null ? CompletableFuture.completedFuture(image) : result;
        }

        @Override public void close() {}
    }
}
