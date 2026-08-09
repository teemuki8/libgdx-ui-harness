package dev.gdx.uiharness.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gdx.uiharness.core.action.Action;
import dev.gdx.uiharness.core.action.ActionResult;
import dev.gdx.uiharness.core.action.Harness;
import dev.gdx.uiharness.core.capture.CaptureRequest;
import dev.gdx.uiharness.core.capture.CapturedImage;
import dev.gdx.uiharness.core.capture.ScreenCapture;
import dev.gdx.uiharness.core.locator.Locator;
import dev.gdx.uiharness.core.model.Bounds;
import dev.gdx.uiharness.core.model.Role;
import dev.gdx.uiharness.core.model.SemanticNode;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import dev.gdx.uiharness.core.model.SemanticState;
import dev.gdx.uiharness.core.time.Deadline;
import dev.gdx.uiharness.core.time.MonotonicClock;
import dev.gdx.uiharness.core.trace.TraceReplay;
import dev.gdx.uiharness.core.trace.TraceReplayer;
import dev.gdx.uiharness.mcp.ArtifactReference;
import dev.gdx.uiharness.protocol.Command;
import dev.gdx.uiharness.protocol.HarnessResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FixtureTracingLifecycleTest {
    private static final long STEP_NANOS = Duration.ofMillis(16).toNanos();

    @Test void cancellingActionBeforeDelegateCompletionCancelsDelegateAndClosesTrace(
            @TempDir Path root) throws Exception {
        MutableClock clock = new MutableClock();
        ArchivePublisher publisher = new ArchivePublisher();
        try (FixtureControl.ReferenceTraceController traces = traces(root, publisher, clock)) {
            ControlledHarness delegate = new ControlledHarness(snapshot(1, 1));
            CompletableFuture<ActionResult> action = new CompletableFuture<>();
            delegate.action = action;
            FixtureControl.TracingHarness harness =
                    new FixtureControl.TracingHarness(delegate, traces);

            CompletableFuture<ActionResult> result = harness.perform(
                    Locator.testId("target"), Action.click(), deadline(clock))
                    .toCompletableFuture();

            assertTrue(result.cancel(false));
            assertTrue(action.isCancelled());
            clock.set(4 * STEP_NANOS);
            byte[] archive = stop(traces, publisher, clock);
            HarnessMcpClient.TraceEvidence evidence = HarnessMcpClient.traceEvidence(archive);
            assertEquals(List.of("COMMAND_STARTED", "COMMAND_FAILED"),
                    evidence.lifecycle("Click"));
            assertEquals(1, evidence.failedCausalChains("Click"));
            assertReplayable(root, archive);
        }
    }

    @Test void actionCompletionWinsOverCancellationDuringPostActionSnapshot(
            @TempDir Path root) throws Exception {
        MutableClock clock = new MutableClock();
        ArchivePublisher publisher = new ArchivePublisher();
        try (FixtureControl.ReferenceTraceController traces = traces(root, publisher, clock)) {
            ControlledHarness delegate = new ControlledHarness(snapshot(1, 1));
            CompletableFuture<SemanticSnapshot> after = new CompletableFuture<>();
            delegate.snapshots.add(after);
            CompletableFuture<ActionResult> action = new CompletableFuture<>();
            delegate.action = action;
            FixtureControl.TracingHarness harness =
                    new FixtureControl.TracingHarness(delegate, traces);
            ActionResult expected = new ActionResult(1, 2, "clicked", Map.of());
            CompletableFuture<ActionResult> result = harness.perform(
                    Locator.testId("target"), Action.click(), deadline(clock))
                    .toCompletableFuture();

            action.complete(expected);
            assertFalse(result.cancel(false));
            assertFalse(after.isCancelled());
            after.complete(snapshot(2, 2));
            assertSame(expected, result.join());

            clock.set(4 * STEP_NANOS);
            byte[] archive = stop(traces, publisher, clock);
            HarnessMcpClient.TraceEvidence evidence = HarnessMcpClient.traceEvidence(archive);
            assertEquals(List.of("COMMAND_STARTED", "COMMAND_COMPLETED"),
                    evidence.lifecycle("Click"));
            assertEquals(1, evidence.completedCausalChains("Click"));
            assertReplayable(root, archive);
        }
    }

    @Test void cancellingCaptureBeforeDelegateCompletionCancelsDelegateAndClosesTrace(
            @TempDir Path root) throws Exception {
        MutableClock clock = new MutableClock();
        ArchivePublisher publisher = new ArchivePublisher();
        try (FixtureControl.ReferenceTraceController traces = traces(root, publisher, clock)) {
            ControlledCapture delegate = new ControlledCapture();
            FixtureControl.TracingCapture capture =
                    new FixtureControl.TracingCapture(delegate, traces);

            CompletableFuture<CapturedImage> result = capture.capture(
                    CaptureRequest.fullWindow(), deadline(clock)).toCompletableFuture();

            assertTrue(result.cancel(false));
            assertTrue(delegate.result.isCancelled());
            clock.set(4 * STEP_NANOS);
            byte[] archive = stop(traces, publisher, clock);
            HarnessMcpClient.TraceEvidence evidence = HarnessMcpClient.traceEvidence(archive);
            assertEquals(List.of("COMMAND_STARTED", "COMMAND_FAILED"),
                    evidence.lifecycle("screenshot"));
            assertEquals(1, evidence.failedCausalChains("screenshot"));
            assertReplayable(root, archive);
        }
    }

    @Test void captureCompletionWinsAndRecordsOneTerminalEvent(@TempDir Path root)
            throws Exception {
        MutableClock clock = new MutableClock();
        ArchivePublisher publisher = new ArchivePublisher();
        try (FixtureControl.ReferenceTraceController traces = traces(root, publisher, clock)) {
            ControlledCapture delegate = new ControlledCapture();
            FixtureControl.TracingCapture capture =
                    new FixtureControl.TracingCapture(delegate, traces);
            CompletableFuture<CapturedImage> result = capture.capture(
                    CaptureRequest.fullWindow(), deadline(clock)).toCompletableFuture();
            CapturedImage expected = image();

            delegate.result.complete(expected);
            assertFalse(result.cancel(false));
            assertSame(expected, result.join());

            clock.set(4 * STEP_NANOS);
            byte[] archive = stop(traces, publisher, clock);
            HarnessMcpClient.TraceEvidence evidence = HarnessMcpClient.traceEvidence(archive);
            assertEquals(List.of("COMMAND_STARTED", "COMMAND_COMPLETED"),
                    evidence.lifecycle("screenshot"));
            assertEquals(1, evidence.completedCausalChains("screenshot"));
            assertReplayable(root, archive);
        }
    }

    @Test void stopReceiptCarriesVerifiedFinalizedArchiveDigest(@TempDir Path root)
            throws Exception {
        MutableClock clock = new MutableClock();
        ArchivePublisher publisher = new ArchivePublisher();
        try (FixtureControl.ReferenceTraceController traces = traces(root, publisher, clock)) {
            ControlledCapture delegate = new ControlledCapture();
            FixtureControl.TracingCapture capture =
                    new FixtureControl.TracingCapture(delegate, traces);
            CompletableFuture<CapturedImage> result = capture.capture(
                    CaptureRequest.fullWindow(), deadline(clock)).toCompletableFuture();
            CapturedImage expected = image();

            delegate.result.complete(expected);
            assertSame(expected, result.join());

            clock.set(4 * STEP_NANOS);
            HarnessResponse.Result.TraceStopped stopped = traces.stop(deadline(clock))
                    .toCompletableFuture().join();

            byte[] archive = publisher.archive.get();
            assertNotNull(archive);
            assertEquals(sha256(archive), stopped.archiveSha256());
            assertTrue(stopped.archiveSha256().matches("[0-9a-f]{64}"));
        }
    }

    @Test void stopRejectsPublisherReceiptThatDoesNotMatchVerifiedArchive(@TempDir Path root) {
        MutableClock clock = new MutableClock();
        ArtifactReference.Publisher mismatched = (mediaType, content) ->
                new ArtifactReference("artifact:mismatch", mediaType, content.length + 1L,
                        sha256(content));
        try (FixtureControl.ReferenceTraceController traces =
                new FixtureControl.ReferenceTraceController(root, mismatched)) {
            traces.start(new Command.TraceStart(30_000, 4L * 1_024 * 1_024), deadline(clock))
                    .toCompletableFuture().join();

            CompletionException failure = org.junit.jupiter.api.Assertions.assertThrows(
                    CompletionException.class,
                    () -> traces.stop(deadline(clock)).toCompletableFuture().join());

            assertTrue(failure.getCause().getMessage().contains("publisher receipt"));
        }
    }

    @Test void captureCompletionClaimCannotBeCancelledDuringTerminalRecording(
            @TempDir Path root) throws Exception {
        MutableClock clock = new MutableClock();
        ArchivePublisher publisher = new ArchivePublisher();
        CountDownLatch recording = new CountDownLatch(1);
        CountDownLatch releaseRecording = new CountDownLatch(1);
        FixtureControl.ReferenceTraceController traces =
                new FixtureControl.ReferenceTraceController(root, publisher) {
                    @Override void captureCompleted(CapturedImage image, TraceSpan span) {
                        recording.countDown();
                        await(releaseRecording);
                        super.captureCompleted(image, span);
                    }
                };
        try (traces; ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            traces.start(new Command.TraceStart(30_000, 4L * 1_024 * 1_024),
                    deadline(clock)).toCompletableFuture().join();
            ControlledCapture delegate = new ControlledCapture();
            FixtureControl.TracingCapture capture =
                    new FixtureControl.TracingCapture(delegate, traces);
            CompletableFuture<CapturedImage> result = capture.capture(
                    CaptureRequest.fullWindow(), deadline(clock)).toCompletableFuture();
            CapturedImage expected = image();
            CompletableFuture<Void> completion =
                    CompletableFuture.runAsync(() -> delegate.result.complete(expected), executor);
            assertTrue(recording.await(2, TimeUnit.SECONDS));
            CountDownLatch cancellationAttempted = new CountDownLatch(1);
            CompletableFuture<Boolean> cancelled = CompletableFuture.supplyAsync(() -> {
                cancellationAttempted.countDown();
                return result.cancel(false);
            }, executor);
            assertTrue(cancellationAttempted.await(2, TimeUnit.SECONDS));

            releaseRecording.countDown();

            completion.join();
            assertFalse(cancelled.join());
            assertSame(expected, result.join());
            clock.set(4 * STEP_NANOS);
            byte[] archive = stop(traces, publisher, clock);
            HarnessMcpClient.TraceEvidence evidence = HarnessMcpClient.traceEvidence(archive);
            assertEquals(List.of("COMMAND_STARTED", "COMMAND_COMPLETED"),
                    evidence.lifecycle("screenshot"));
            assertReplayable(root, archive);
        }
    }

    @Test void captureRecorderFailureIsSuppressedWithoutReplacingDelegateFailure(
            @TempDir Path root) {
        MutableClock clock = new MutableClock();
        ArchivePublisher publisher = new ArchivePublisher();
        RuntimeException recorderFailure = new RuntimeException("capture recorder failed");
        FixtureControl.ReferenceTraceController traces =
                new FixtureControl.ReferenceTraceController(root, publisher) {
                    @Override synchronized void captureFailed(Deadline deadline, TraceSpan span) {
                        throw recorderFailure;
                    }
                };
        try (traces) {
            traces.start(new Command.TraceStart(30_000, 4L * 1_024 * 1_024),
                    deadline(clock)).toCompletableFuture().join();
            ControlledCapture delegate = new ControlledCapture();
            FixtureControl.TracingCapture capture =
                    new FixtureControl.TracingCapture(delegate, traces);
            CompletableFuture<CapturedImage> result = capture.capture(
                    CaptureRequest.fullWindow(), deadline(clock)).toCompletableFuture();
            RuntimeException original = new RuntimeException("capture failed");

            delegate.result.completeExceptionally(original);

            CompletionException completion = org.junit.jupiter.api.Assertions.assertThrows(
                    CompletionException.class, result::join);
            assertSame(original, completion.getCause());
            assertTrue(Arrays.asList(original.getSuppressed()).contains(recorderFailure));
        }
    }

    @Test void recorderFailureIsSuppressedWithoutReplacingActionFailure(@TempDir Path root) {
        MutableClock clock = new MutableClock();
        ArchivePublisher publisher = new ArchivePublisher();
        RuntimeException recorderFailure = new RuntimeException("recorder failed");
        FixtureControl.ReferenceTraceController traces =
                new FixtureControl.ReferenceTraceController(root, publisher) {
                    @Override synchronized void commandFailed(String operation,
                            SemanticSnapshot before, Deadline deadline, TraceSpan span) {
                        throw recorderFailure;
                    }
                };
        try (traces) {
            traces.start(new Command.TraceStart(30_000, 4L * 1_024 * 1_024),
                    deadline(clock)).toCompletableFuture().join();
            ControlledHarness delegate = new ControlledHarness(snapshot(1, 1));
            CompletableFuture<ActionResult> action = new CompletableFuture<>();
            delegate.action = action;
            FixtureControl.TracingHarness harness =
                    new FixtureControl.TracingHarness(delegate, traces);
            CompletableFuture<ActionResult> result = harness.perform(
                    Locator.testId("target"), Action.click(), deadline(clock))
                    .toCompletableFuture();
            RuntimeException original = new RuntimeException("action failed");

            action.completeExceptionally(original);

            CompletionException completion = org.junit.jupiter.api.Assertions.assertThrows(
                    CompletionException.class, result::join);
            assertSame(original, completion.getCause());
            assertTrue(Arrays.asList(original.getSuppressed()).contains(recorderFailure));
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while coordinating trace race", failure);
        }
    }

    private static FixtureControl.ReferenceTraceController traces(
            Path root, ArchivePublisher publisher, MutableClock clock) {
        FixtureControl.ReferenceTraceController traces =
                new FixtureControl.ReferenceTraceController(root, publisher);
        traces.start(new Command.TraceStart(30_000, 4L * 1_024 * 1_024), deadline(clock))
                .toCompletableFuture().join();
        return traces;
    }

    private static byte[] stop(FixtureControl.ReferenceTraceController traces,
            ArchivePublisher publisher, MutableClock clock) {
        traces.stop(deadline(clock)).toCompletableFuture().join();
        return publisher.archive.get();
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 is unavailable", impossible);
        }
    }

    private static void assertReplayable(Path root, byte[] archive) throws Exception {
        Path file = root.resolve("replay.zip");
        Files.write(file, archive);
        TraceReplay replay = new TraceReplayer().load(file);
        assertTrue(replay.manifest().complete());
        assertTrue(replay.causality().isValid(), () -> replay.causality().errors().toString());
        assertFalse(replay.partial());
    }

    private static Deadline deadline(MonotonicClock clock) {
        return Deadline.after(clock, Duration.ofSeconds(30));
    }

    private static CapturedImage image() {
        return new CapturedImage(new byte[] {1}, "0".repeat(64), 2, 2, 1, 1,
                new CapturedImage.Scale(1, 1));
    }

    private static SemanticSnapshot snapshot(long revision, long frame) {
        Bounds bounds = new Bounds(0, 0, 10, 10);
        SemanticState state = new SemanticState(true, true, Optional.of(true),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                false, false, 1.0, false, true, true);
        SemanticNode root = new SemanticNode("root", null, List.of(), Role.BUTTON, "Target",
                "Target", null, "target", "button", "Button", state, bounds, bounds, bounds,
                0, Map.of());
        return new SemanticSnapshot(revision, frame, "root", Map.of("root", root));
    }

    private static final class MutableClock implements MonotonicClock {
        private final AtomicLong nanos = new AtomicLong();

        @Override public long nanoTime() {
            return nanos.get();
        }

        void set(long value) {
            nanos.set(value);
        }
    }

    private static final class ArchivePublisher implements ArtifactReference.Publisher {
        private final AtomicReference<byte[]> archive = new AtomicReference<>();

        @Override public ArtifactReference publish(String mediaType, byte[] content) {
            archive.set(content.clone());
            return new ArtifactReference("artifact:fixture-trace", mediaType, content.length,
                    sha256(content));
        }
    }

    private static final class ControlledHarness implements Harness {
        private final ArrayDeque<CompletableFuture<SemanticSnapshot>> snapshots =
                new ArrayDeque<>();
        private CompletableFuture<ActionResult> action;

        ControlledHarness(SemanticSnapshot before) {
            snapshots.add(CompletableFuture.completedFuture(before));
        }

        @Override public CompletionStage<ActionResult> perform(
                Locator locator, Action requested, Deadline deadline) {
            return action;
        }

        @Override public CompletionStage<SemanticSnapshot> snapshot(Deadline deadline) {
            return snapshots.removeFirst();
        }
    }

    private static final class ControlledCapture implements ScreenCapture {
        private final CompletableFuture<CapturedImage> result = new CompletableFuture<>();

        @Override public CompletionStage<CapturedImage> capture(
                CaptureRequest request, Deadline deadline) {
            return result;
        }

        @Override public void close() {}
    }
}
