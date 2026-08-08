package dev.gdx.uiharness.scene2d;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.badlogic.gdx.scenes.scene2d.Stage;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import dev.gdx.uiharness.core.scenario.ScenarioDefinition;
import dev.gdx.uiharness.core.scenario.ScenarioFailure;
import dev.gdx.uiharness.core.scenario.ScenarioLifecycle;
import dev.gdx.uiharness.core.scenario.ScenarioRegistry;
import dev.gdx.uiharness.core.scenario.ScenarioRequest;
import dev.gdx.uiharness.core.scenario.ScenarioResult;
import dev.gdx.uiharness.core.time.Deadline;
import dev.gdx.uiharness.core.time.DeadlineScheduler;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class Scene2dScenarioRunnerTest {
    @Test void lifecycleAndStageWorkRunOnRenderThreadAndReadinessNeedsCompletedFrame() {
        try (Fixture fixture = new Fixture()) {
            Thread renderThread = Thread.currentThread();
            List<Thread> hookThreads = new ArrayList<>();
            fixture.register(new RecordingLifecycle(hookThreads, true, "ready"));

            CompletionStage<ScenarioResult> started;
            try (ExecutorService caller = Executors.newVirtualThreadPerTaskExecutor()) {
                started = java.util.concurrent.CompletableFuture
                        .supplyAsync(() -> fixture.start(Duration.ofSeconds(1)), caller)
                        .join();
            }
            fixture.scheduler.drain();

            assertFalse(started.toCompletableFuture().isDone());
            assertEquals(List.of(renderThread, renderThread), hookThreads);

            fixture.completedFrame();

            ScenarioResult result = started.toCompletableFuture().join();
            assertEquals(List.of(renderThread, renderThread, renderThread, renderThread, renderThread), hookThreads);
            assertEquals(1, result.startFrame());
            assertEquals(1, result.readyFrame());
            assertEquals(1, result.startRevision());
            assertEquals(1, result.readyRevision());
            assertEquals("ready", result.startStateIdentity());
            assertTrue(result.cleanupCompleted());
            assertTrue(result.failure().isEmpty());
        }
    }

    @Test void completedStageFramesCannotBeReadOffTheRenderThread() {
        try (Fixture fixture = new Fixture();
                ExecutorService caller = Executors.newVirtualThreadPerTaskExecutor()) {
            var failure = assertThrows(
                    java.util.concurrent.CompletionException.class,
                    () -> java.util.concurrent.CompletableFuture
                            .runAsync(
                                    () -> fixture.session.completedFrame(fixture.runner, 1, 1),
                                    caller)
                            .join());

            assertInstanceOf(IllegalStateException.class, failure.getCause());
        }
    }

    @Test void monotonicReadinessDeadlineIsDistinctFromSetupRejection() {
        try (Fixture deadline = new Fixture()) {
            deadline.register(new RecordingLifecycle(new ArrayList<>(), false, "never"));
            CompletionStage<ScenarioResult> started = deadline.start(Duration.ofMillis(10));
            deadline.scheduler.drain();
            deadline.completedFrame();
            assertEquals(
                    ScenarioFailure.READINESS_DEADLINE,
                    started.toCompletableFuture().join().failure().orElseThrow());
        }

        try (Fixture rejected = new Fixture()) {
            rejected.register(new ScenarioLifecycle() {
                @Override public void setup(ScenarioRequest request) {
                    throw new IllegalStateException("rejected");
                }
                @Override public void reset(ScenarioRequest request) {}
                @Override public boolean ready(ScenarioRequest request) { return true; }
                @Override public String startStateIdentity(
                        ScenarioRequest request, SemanticSnapshot snapshot) { return "unused"; }
                @Override public void cleanup(ScenarioRequest request) {}
            });
            CompletionStage<ScenarioResult> started = rejected.start(Duration.ofSeconds(1));
            rejected.scheduler.drain();
            assertEquals(
                    ScenarioFailure.SETUP_REJECTED,
                    started.toCompletableFuture().join().failure().orElseThrow());
        }
    }

    @Test void readinessExceptionIsDistinctFromResetRejection() {
        try (Fixture fixture = new Fixture()) {
            fixture.register(new RecordingLifecycle(new ArrayList<>(), true, "unused") {
                @Override public boolean ready(ScenarioRequest request) {
                    throw new IllegalStateException("readiness rejected");
                }
            });
            CompletionStage<ScenarioResult> started = fixture.start(Duration.ofSeconds(1));
            fixture.scheduler.drain();
            fixture.completedFrame();

            assertEquals(
                    ScenarioFailure.READINESS_REJECTED,
                    started.toCompletableFuture().join().failure().orElseThrow());
        }
    }

    @Test void cancellationSchedulesRenderThreadCleanup() {
        try (Fixture fixture = new Fixture()) {
            AtomicReference<Thread> cleanupThread = new AtomicReference<>();
            fixture.register(new RecordingLifecycle(new ArrayList<>(), false, "never") {
                @Override public void cleanup(ScenarioRequest request) {
                    cleanupThread.set(Thread.currentThread());
                }
            });
            CompletionStage<ScenarioResult> started = fixture.start(Duration.ofSeconds(1));
            fixture.scheduler.drain();

            assertTrue(started.toCompletableFuture().cancel(false));
            assertFalse(started.toCompletableFuture().isDone());
            fixture.scheduler.drain();

            ScenarioResult result = started.toCompletableFuture().join();
            assertEquals(ScenarioFailure.CANCELLED, result.failure().orElseThrow());
            assertEquals(Thread.currentThread(), cleanupThread.get());
            assertTrue(result.cleanupCompleted());
        }
    }
    @Test void rejectedInitialSubmissionTerminalizesWithoutCleanup() {
        try (Fixture fixture = new Fixture(1)) {
            AtomicReference<Thread> cleanupThread = new AtomicReference<>();
            fixture.register(new RecordingLifecycle(new ArrayList<>(), false, "never") {
                @Override public void cleanup(ScenarioRequest request) {
                    cleanupThread.set(Thread.currentThread());
                }
            });
            fixture.scheduler.close();

            ScenarioResult result =
                    fixture.start(Duration.ofSeconds(1)).toCompletableFuture().join();

            assertEquals(ScenarioFailure.DISPATCH_FAILED, result.failure().orElseThrow());
            assertFalse(result.cleanupCompleted());
            assertEquals(null, cleanupThread.get());
        }
    }

    @Test void racedDeadlineCancellationRunsOutsideTheRunMonitor() {
        AtomicBoolean monitorHeld = new AtomicBoolean();
        // A scheduler whose cancellation synchronously reenters the runner: the deadline
        // signal is bound to the racing Run, so running it from a helper thread can only
        // proceed once the arming thread leaves the run monitor. A bounded wait records the
        // stall as monitor ownership.
        DeadlineScheduler probing = (delay, signal) -> () -> {
            CompletableFuture<Boolean> entered = CompletableFuture.supplyAsync(() -> {
                signal.run();
                return Boolean.TRUE;
            });
            try {
                entered.get(1, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("deadline probe interrupted", exception);
            } catch (ExecutionException | TimeoutException exception) {
                monitorHeld.set(true);
            }
        };
        try (Fixture fixture = new Fixture(16, probing)) {
            fixture.register(new RecordingLifecycle(new ArrayList<>(), false, "never"));
            // The initial submission fails before the deadline arm runs, so the run is already
            // terminal when armDeadline reconciles its fresh token with the terminal state.
            fixture.scheduler.close();
            CompletionStage<ScenarioResult> started = fixture.start(Duration.ofSeconds(1));

            assertFalse(monitorHeld.get(),
                    "armDeadline must cancel the raced token only after leaving the run monitor");
            ScenarioResult result = started.toCompletableFuture().join();
            assertEquals(ScenarioFailure.DISPATCH_FAILED, result.failure().orElseThrow());
            assertFalse(result.cleanupCompleted());
        }
    }

    @Test void rejectedFrameSubmissionTerminalizesWithoutCleanup() {
        try (Fixture fixture = new Fixture(1)) {
            fixture.register(new RecordingLifecycle(new ArrayList<>(), false, "never"));
            CompletionStage<ScenarioResult> started = fixture.start(Duration.ofSeconds(1));
            fixture.scheduler.drain();
            fixture.scheduler.close();

            fixture.session.completedFrame(fixture.runner, 1, 1);

            ScenarioResult result = started.toCompletableFuture().join();
            assertEquals(ScenarioFailure.DISPATCH_FAILED, result.failure().orElseThrow());
            assertFalse(result.cleanupCompleted());
        }
    }

    @Test void rejectedCancellationSubmissionTerminalizesInsteadOfRemainingCancelling() {
        try (Fixture fixture = new Fixture(1)) {
            fixture.register(new RecordingLifecycle(new ArrayList<>(), false, "never"));
            CompletionStage<ScenarioResult> started = fixture.start(Duration.ofSeconds(1));
            fixture.scheduler.drain();
            fixture.scheduler.close();

            assertTrue(started.toCompletableFuture().cancel(false));

            ScenarioResult result = started.toCompletableFuture().join();
            assertEquals(ScenarioFailure.DISPATCH_FAILED, result.failure().orElseThrow());
            assertFalse(result.cleanupCompleted());
        }
    }

    @Test void deadlineExpiryPublishesTerminalResultWithoutAnotherFrame() throws Exception {
        try (Fixture fixture = new Fixture()) {
            AtomicReference<Thread> cleanupThread = new AtomicReference<>();
            fixture.register(new RecordingLifecycle(new ArrayList<>(), false, "never") {
                @Override public void cleanup(ScenarioRequest request) {
                    cleanupThread.set(Thread.currentThread());
                }
            });
            CompletionStage<ScenarioResult> started = fixture.start(Duration.ofMillis(10));
            fixture.scheduler.drain();

            fixture.clock.advance(Duration.ofMillis(10));
            fixture.deadlines.expire();
            // The monotonic deadline publishes the terminal result on its own thread without
            // waiting for a render drain; hook cleanup is deferred to the render thread.
            ScenarioResult result = started.toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(ScenarioFailure.READINESS_DEADLINE, result.failure().orElseThrow());
            assertFalse(result.cleanupCompleted(),
                    "the deadline-published result reports cleanup before the render thread drains");
            assertEquals(null, cleanupThread.get(),
                    "hook cleanup must not run before the render thread drains");

            fixture.scheduler.drain();
            assertEquals(Thread.currentThread(), cleanupThread.get(),
                    "deferred cleanup runs exactly once on the render thread");
        }
    }

    @Test void noFrameDeadlineFailsAcquisitionWithoutRenderDrain() throws Exception {
        try (Fixture fixture = new Fixture()) {
            AtomicReference<Thread> cleanupThread = new AtomicReference<>();
            fixture.register(new RecordingLifecycle(new ArrayList<>(), false, "never") {
                @Override public void cleanup(ScenarioRequest request) {
                    cleanupThread.set(Thread.currentThread());
                }
            });
            CompletionStage<Scene2dScenarioRunner.Lease> acquired =
                    fixture.acquire(Duration.ofMillis(10));
            fixture.scheduler.drain();

            fixture.clock.advance(Duration.ofMillis(10));
            fixture.deadlines.expire();

            try {
                acquired.toCompletableFuture().get(5, TimeUnit.SECONDS);
                throw new AssertionError("acquisition unexpectedly succeeded");
            } catch (ExecutionException failure) {
                Scene2dScenarioRunner.AcquisitionException rejection = assertInstanceOf(
                        Scene2dScenarioRunner.AcquisitionException.class, failure.getCause());
                assertEquals(ScenarioFailure.READINESS_DEADLINE,
                        rejection.result().failure().orElseThrow());
                assertFalse(rejection.result().cleanupCompleted());
            }
            assertEquals(null, cleanupThread.get(),
                    "hook cleanup must not run before the render thread drains");

            fixture.scheduler.drain();
            assertEquals(Thread.currentThread(), cleanupThread.get(),
                    "deferred cleanup runs exactly once on the render thread");
        }
    }

    @Test void newAcquisitionStaysBusyBeforeDeferredCleanupThenSucceedsAfterDrain()
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            java.util.concurrent.atomic.AtomicInteger cleanups =
                    new java.util.concurrent.atomic.AtomicInteger();
            fixture.register(new RecordingLifecycle(new ArrayList<>(), true, "ready") {
                @Override public void cleanup(ScenarioRequest request) {
                    cleanups.incrementAndGet();
                }
            });
            CompletionStage<ScenarioResult> started = fixture.start(Duration.ofMillis(10));
            fixture.scheduler.drain();

            fixture.clock.advance(Duration.ofMillis(10));
            fixture.deadlines.expire();

            ScenarioResult first = started.toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(ScenarioFailure.READINESS_DEADLINE, first.failure().orElseThrow());
            assertFalse(first.cleanupCompleted());
            assertEquals(0, cleanups.get());

            // While the deferred cleanup still owns the active slot, a competing acquisition
            // is rejected as busy instead of starting a second run.
            CompletionStage<ScenarioResult> competing = fixture.start(Duration.ofSeconds(1));
            ScenarioResult busy = competing.toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(ScenarioFailure.SESSION_BUSY, busy.failure().orElseThrow());
            assertEquals(0, cleanups.get(),
                    "cleanup must not run before the render thread drains");

            fixture.scheduler.drain();
            assertEquals(1, cleanups.get(),
                    "deferred cleanup runs exactly once on the render thread");

            // After the owner cleanup drains, a new acquisition is admitted and succeeds.
            CompletionStage<ScenarioResult> next = fixture.start(Duration.ofSeconds(1));
            fixture.scheduler.drain();
            fixture.completedFrame();
            ScenarioResult nextResult = next.toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertTrue(nextResult.failure().isEmpty(),
                    "a new acquisition succeeds after the owner cleanup drains");
            assertTrue(nextResult.cleanupCompleted());
        }
    }

    @Test void terminalCompletionInvalidatesScheduledDeadline() {
        try (Fixture fixture = new Fixture()) {
            fixture.register(new RecordingLifecycle(new ArrayList<>(), true, "ready"));

            fixture.complete(fixture.start(Duration.ofSeconds(1)));

            assertTrue(fixture.deadlines.cancelled);
        }
    }

    @Test void acquiredScenarioRemainsReadyUntilExplicitAsyncRelease() {
        try (Fixture fixture = new Fixture()) {
            java.util.concurrent.atomic.AtomicInteger cleanups = new java.util.concurrent.atomic.AtomicInteger();
            fixture.register(new RecordingLifecycle(new ArrayList<>(), true, "ready") {
                @Override public void cleanup(ScenarioRequest request) { cleanups.incrementAndGet(); }
            });

            CompletionStage<Scene2dScenarioRunner.Lease> acquired = fixture.acquire(Duration.ofSeconds(1));
            fixture.scheduler.drain();
            fixture.completedFrame();

            Scene2dScenarioRunner.Lease lease = acquired.toCompletableFuture().join();
            assertEquals(0, cleanups.get(), "READY transfers ownership without cleaning the UI");
            CompletionStage<ScenarioResult> released = lease.release();
            assertFalse(released.toCompletableFuture().isDone());
            fixture.scheduler.drain();
            assertTrue(released.toCompletableFuture().join().cleanupCompleted());
            assertEquals(1, cleanups.get());
            assertEquals(released.toCompletableFuture().join(), lease.release().toCompletableFuture().join());
            assertEquals(1, cleanups.get(), "release races must clean exactly once");
        }
    }

    @Test void closeAndDeadlineReleaseReadyLeaseExactlyOnce() {
        Fixture closed = new Fixture();
        java.util.concurrent.atomic.AtomicInteger closeCleanups = new java.util.concurrent.atomic.AtomicInteger();
        closed.register(new RecordingLifecycle(new ArrayList<>(), true, "ready") {
            @Override public void cleanup(ScenarioRequest request) { closeCleanups.incrementAndGet(); }
        });
        CompletionStage<Scene2dScenarioRunner.Lease> closeAcquisition =
                closed.acquire(Duration.ofSeconds(1));
        closed.scheduler.drain();
        closed.completedFrame();
        Scene2dScenarioRunner.Lease closeLease = closeAcquisition.toCompletableFuture().join();
        closed.runner.close();
        closed.scheduler.drain();
        assertEquals(ScenarioFailure.CANCELLED,
                closeLease.completion().toCompletableFuture().join().failure().orElseThrow());
        assertEquals(1, closeCleanups.get());
        closed.close();

        try (Fixture expired = new Fixture()) {
            java.util.concurrent.atomic.AtomicInteger deadlineCleanups = new java.util.concurrent.atomic.AtomicInteger();
            expired.register(new RecordingLifecycle(new ArrayList<>(), true, "ready") {
                @Override public void cleanup(ScenarioRequest request) { deadlineCleanups.incrementAndGet(); }
            });
            CompletionStage<Scene2dScenarioRunner.Lease> acquisition = expired.acquire(Duration.ofMillis(20));
            expired.scheduler.drain();
            expired.completedFrame();
            Scene2dScenarioRunner.Lease lease = acquisition.toCompletableFuture().join();
            expired.clock.advance(Duration.ofMillis(10));
            expired.deadlines.expire();
            expired.scheduler.drain();
            assertEquals(ScenarioFailure.READINESS_DEADLINE,
                    lease.completion().toCompletableFuture().join().failure().orElseThrow());
            lease.release();
            expired.scheduler.drain();
            assertEquals(1, deadlineCleanups.get());
        }
    }


    @Test void concurrentAcquisitionsHaveExactlyOneWinnerAndRejectTheLoserWithSessionBusy() {
        try (Fixture fixture = new Fixture()) {
            java.util.concurrent.atomic.AtomicInteger setups =
                    new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicInteger cleanups =
                    new java.util.concurrent.atomic.AtomicInteger();
            fixture.register(new RecordingLifecycle(new ArrayList<>(), true, "ready") {
                @Override public void setup(ScenarioRequest request) {
                    setups.incrementAndGet();
                }
                @Override public void cleanup(ScenarioRequest request) {
                    cleanups.incrementAndGet();
                }
            });

            java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(2);
            List<java.util.concurrent.CompletableFuture<CompletionStage<Scene2dScenarioRunner.Lease>>> submitted =
                    new ArrayList<>();
            try (ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int index = 0; index < 2; index++) {
                    submitted.add(java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                        await(barrier);
                        return fixture.acquire(Duration.ofSeconds(1));
                    }, callers));
                }
            }
            List<CompletionStage<Scene2dScenarioRunner.Lease>> acquisitions =
                    submitted.stream()
                            .map(java.util.concurrent.CompletableFuture::join)
                            .toList();

            fixture.scheduler.drain();
            fixture.completedFrame();

            Scene2dScenarioRunner.Lease winner = null;
            int busy = 0;
            for (CompletionStage<Scene2dScenarioRunner.Lease> acquisition : acquisitions) {
                try {
                    winner = acquisition.toCompletableFuture().join();
                } catch (java.util.concurrent.CompletionException failure) {
                    Scene2dScenarioRunner.AcquisitionException rejection = assertInstanceOf(
                            Scene2dScenarioRunner.AcquisitionException.class, failure.getCause());
                    assertEquals(ScenarioFailure.SESSION_BUSY,
                            rejection.result().failure().orElseThrow());
                    assertEquals(0, rejection.result().setupAttempts(),
                            "the rejected acquisition must not attempt setup");
                    assertFalse(rejection.result().cleanupCompleted(),
                            "the rejected acquisition must not run cleanup");
                    busy++;
                }
            }
            assertEquals(1, busy, "exactly one competing acquisition must be rejected as busy");
            assertFalse(winner == null, "exactly one acquisition must own the session");
            assertEquals(1, setups.get(), "only the owner's hooks may execute");
            assertEquals(0, cleanups.get(), "no cleanup before the owner releases");

            winner.release();
            fixture.scheduler.drain();
            assertEquals(1, cleanups.get(), "the owner cleans exactly once");
        }
    }

    @Test void everyTerminalPathReleasesTheLeaseBeforeTheNextAcquisition() throws Exception {
        for (TerminalPath path : TerminalPath.values()) {
            releaseOnTerminalPath(path);
        }
    }

    @Test void staleReleaseOfAnEarlierLeaseCannotClearItsSuccessor() {
        try (Fixture fixture = new Fixture()) {
            java.util.concurrent.atomic.AtomicInteger cleanups =
                    new java.util.concurrent.atomic.AtomicInteger();
            fixture.register(new RecordingLifecycle(new ArrayList<>(), true, "ready") {
                @Override public void cleanup(ScenarioRequest request) {
                    cleanups.incrementAndGet();
                }
            });

            Scene2dScenarioRunner.Lease first = fixture.acquireReady(Duration.ofSeconds(1));
            first.release();
            fixture.scheduler.drain();
            assertEquals(1, cleanups.get(), "the first lease cleans once");

            Scene2dScenarioRunner.Lease second = fixture.acquireReady(Duration.ofSeconds(1));
            assertFalse(second.completion().toCompletableFuture().isDone(),
                    "the successor lease is active");

            first.release();
            fixture.scheduler.drain();
            assertFalse(second.completion().toCompletableFuture().isDone(),
                    "a stale release must not clear the successor lease");
            assertEquals(1, cleanups.get(), "the stale release must not clean again");

            second.release();
            fixture.scheduler.drain();
            assertEquals(2, cleanups.get(), "releasing the successor cleans exactly once more");
            assertTrue(second.completion().toCompletableFuture().join().cleanupCompleted());
        }
    }

    private enum TerminalPath {
        SUCCESS,
        CALLER_CANCELLATION,
        DEADLINE,
        SETUP_REJECTED,
        RESET_REJECTED,
        READINESS_REJECTED,
        CLOSE
    }

    private static void releaseOnTerminalPath(TerminalPath path) throws Exception {
        switch (path) {
            case SUCCESS -> successReleasesThenReacquires();
            case CALLER_CANCELLATION -> callerCancellationReleasesThenReacquires();
            case DEADLINE -> deadlineReleasesThenReacquires();
            case SETUP_REJECTED, RESET_REJECTED, READINESS_REJECTED ->
                    rejectionReleasesThenReacquires(path);
            case CLOSE -> closeReleasesTheHeldLease();
        }
    }

    private static void successReleasesThenReacquires() {
        try (Fixture fixture = new Fixture()) {
            java.util.concurrent.atomic.AtomicInteger cleanups =
                    new java.util.concurrent.atomic.AtomicInteger();
            fixture.register(new RecordingLifecycle(new ArrayList<>(), true, "ready") {
                @Override public void cleanup(ScenarioRequest request) {
                    cleanups.incrementAndGet();
                }
            });
            Scene2dScenarioRunner.Lease lease = fixture.acquireReady(Duration.ofSeconds(1));
            lease.release();
            fixture.scheduler.drain();
            assertEquals(1, cleanups.get(), "explicit release cleans once");
            assertSubsequentAcquisitionSucceeds(fixture, "success");
        }
    }

    private static void callerCancellationReleasesThenReacquires() {
        try (Fixture fixture = new Fixture()) {
            fixture.register(new RecordingLifecycle(new ArrayList<>(), true, "ready"));
            Scene2dScenarioRunner.Lease lease = fixture.acquireReady(Duration.ofSeconds(1));
            assertTrue(lease.completion().toCompletableFuture().cancel(false),
                    "caller cancellation must not bypass cleanup");
            fixture.scheduler.drain();
            assertEquals(ScenarioFailure.CANCELLED,
                    lease.completion().toCompletableFuture().join().failure().orElseThrow());
            assertTrue(lease.completion().toCompletableFuture().join().cleanupCompleted());
            assertSubsequentAcquisitionSucceeds(fixture, "caller cancellation");
        }
    }

    private static void deadlineReleasesThenReacquires() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.register(new RecordingLifecycle(new ArrayList<>(), true, "ready"));
            Scene2dScenarioRunner.Lease lease = fixture.acquireReady(Duration.ofMillis(20));
            fixture.clock.advance(Duration.ofMillis(10));
            fixture.deadlines.expire();
            ScenarioResult result = lease.completion().toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            assertEquals(ScenarioFailure.READINESS_DEADLINE, result.failure().orElseThrow());
            assertFalse(result.cleanupCompleted(),
                    "the deadline-published result cannot claim cleanup before the drain");
            fixture.scheduler.drain();
            assertSubsequentAcquisitionSucceeds(fixture, "deadline");
        }
    }

    private static void rejectionReleasesThenReacquires(TerminalPath path) {
        try (Fixture fixture = new Fixture()) {
            java.util.concurrent.atomic.AtomicBoolean failOnce =
                    new java.util.concurrent.atomic.AtomicBoolean(true);
            fixture.register(new RecordingLifecycle(new ArrayList<>(), true, "ready") {
                @Override public void setup(ScenarioRequest request) {
                    if (path == TerminalPath.SETUP_REJECTED && failOnce.getAndSet(false)) {
                        throw new IllegalStateException("setup rejected");
                    }
                }
                @Override public void reset(ScenarioRequest request) {
                    if (path == TerminalPath.RESET_REJECTED && failOnce.getAndSet(false)) {
                        throw new IllegalStateException("reset rejected");
                    }
                }
                @Override public boolean ready(ScenarioRequest request) {
                    if (path == TerminalPath.READINESS_REJECTED && failOnce.getAndSet(false)) {
                        throw new IllegalStateException("readiness rejected");
                    }
                    return true;
                }
            });
            ScenarioFailure expected = switch (path) {
                case SETUP_REJECTED -> ScenarioFailure.SETUP_REJECTED;
                case RESET_REJECTED -> ScenarioFailure.RESET_REJECTED;
                case READINESS_REJECTED -> ScenarioFailure.READINESS_REJECTED;
                default -> throw new AssertionError(path);
            };
            CompletionStage<Scene2dScenarioRunner.Lease> rejected =
                    fixture.acquire(Duration.ofSeconds(1));
            fixture.scheduler.drain();
            if (path == TerminalPath.READINESS_REJECTED) {
                fixture.completedFrame();
            }
            assertEquals(expected, failureOf(rejected), path.name());
            assertSubsequentAcquisitionSucceeds(fixture, path.name());
        }
    }

    private static void closeReleasesTheHeldLease() {
        Fixture fixture = new Fixture();
        try {
            java.util.concurrent.atomic.AtomicInteger cleanups =
                    new java.util.concurrent.atomic.AtomicInteger();
            fixture.register(new RecordingLifecycle(new ArrayList<>(), true, "ready") {
                @Override public void cleanup(ScenarioRequest request) {
                    cleanups.incrementAndGet();
                }
            });
            Scene2dScenarioRunner.Lease lease = fixture.acquireReady(Duration.ofSeconds(1));
            fixture.runner.close();
            fixture.scheduler.drain();
            assertEquals(ScenarioFailure.CANCELLED,
                    lease.completion().toCompletableFuture().join().failure().orElseThrow());
            assertTrue(lease.completion().toCompletableFuture().join().cleanupCompleted());
            assertEquals(1, cleanups.get(), "close releases the held lease exactly once");
            assertThrows(IllegalStateException.class,
                    () -> fixture.acquire(Duration.ofSeconds(1)),
                    "a closed runner rejects new acquisitions");
        } finally {
            fixture.close();
        }
    }

    private static void assertSubsequentAcquisitionSucceeds(Fixture fixture, String label) {
        Scene2dScenarioRunner.Lease next = fixture.acquireReady(Duration.ofSeconds(1));
        assertFalse(next.completion().toCompletableFuture().isDone(), label);
        next.release();
        fixture.scheduler.drain();
        assertTrue(next.completion().toCompletableFuture().join().cleanupCompleted(), label);
    }

    private static ScenarioFailure failureOf(
            CompletionStage<Scene2dScenarioRunner.Lease> acquisition) {
        try {
            acquisition.toCompletableFuture().join();
            throw new AssertionError("acquisition unexpectedly succeeded");
        } catch (java.util.concurrent.CompletionException failure) {
            return assertInstanceOf(Scene2dScenarioRunner.AcquisitionException.class,
                    failure.getCause()).result().failure().orElseThrow();
        }
    }

    private static void await(java.util.concurrent.CyclicBarrier barrier) {
        try {
            barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new IllegalStateException("acquisition barrier did not open", failure);
        }
    }

    @Test void repeatedInputsRetainIdentityOrReportNondeterminism() {
        try (Fixture fixture = new Fixture()) {
            AtomicReference<String> identity = new AtomicReference<>("stable");
            fixture.register(new RecordingLifecycle(new ArrayList<>(), true, "unused") {
                @Override public String startStateIdentity(
                        ScenarioRequest request, SemanticSnapshot snapshot) {
                    return identity.get();
                }
            });

            ScenarioResult first = fixture.complete(fixture.start(Duration.ofSeconds(1)));
            ScenarioResult repeated = fixture.complete(fixture.start(Duration.ofSeconds(1)));
            assertEquals(first.startStateIdentity(), repeated.startStateIdentity());
            assertTrue(repeated.failure().isEmpty());

            identity.set("changed");
            ScenarioResult changed = fixture.complete(fixture.start(Duration.ofSeconds(1)));
            assertEquals(
                    ScenarioFailure.NONDETERMINISTIC_INITIAL_STATE,
                    changed.failure().orElseThrow());
        }
    }

    @Test void failedDeadlineCleanupSubmissionStillReleasesTheActiveOwner() throws Exception {
        try (Fixture fixture = new Fixture()) {
            java.util.concurrent.atomic.AtomicInteger cleanups =
                    new java.util.concurrent.atomic.AtomicInteger();
            fixture.register(new RecordingLifecycle(new ArrayList<>(), true, "ready") {
                @Override public void cleanup(ScenarioRequest request) {
                    cleanups.incrementAndGet();
                }
            });
            CompletionStage<ScenarioResult> started = fixture.start(Duration.ofMillis(10));
            fixture.scheduler.drain();

            fixture.clock.advance(Duration.ofMillis(10));
            fixture.deadlines.expire();

            ScenarioResult first = started.toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(ScenarioFailure.READINESS_DEADLINE, first.failure().orElseThrow());
            assertFalse(first.cleanupCompleted());
            assertEquals(0, cleanups.get());

            // The render thread is torn down before the deferred cleanup drains: the cleanup
            // submission is rejected, and the deadline-published run must still release its
            // active owner slot instead of leaking it.
            fixture.scheduler.close();
            fixture.scheduler.drain();

            CompletionStage<ScenarioResult> next = fixture.start(Duration.ofSeconds(1));
            ScenarioResult nextResult = next.toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertNotEquals(ScenarioFailure.SESSION_BUSY, nextResult.failure().orElseThrow(),
                    "the released deadline owner must not block the next acquisition");
            assertEquals(ScenarioFailure.DISPATCH_FAILED, nextResult.failure().orElseThrow(),
                    "the next acquisition is admitted and only fails because the render "
                            + "thread scheduler is closed");
            assertEquals(0, cleanups.get(),
                    "the rejected cleanup submission never runs the cleanup hook");
        }
    }

    @Test void unrelatedRejectedSubmissionCannotReleaseOwnerBeforeDeferredCleanupDrains()
            throws Exception {
        try (Fixture fixture = new Fixture(1)) {
            java.util.concurrent.atomic.AtomicInteger cleanups =
                    new java.util.concurrent.atomic.AtomicInteger();
            fixture.register(new RecordingLifecycle(new ArrayList<>(), true, "ready") {
                @Override public void cleanup(ScenarioRequest request) {
                    cleanups.incrementAndGet();
                }
            });
            CompletionStage<ScenarioResult> started = fixture.start(Duration.ofMillis(10));
            fixture.scheduler.drain();

            fixture.clock.advance(Duration.ofMillis(10));
            fixture.deadlines.expire();

            ScenarioResult first = started.toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(ScenarioFailure.READINESS_DEADLINE, first.failure().orElseThrow());
            assertFalse(first.cleanupCompleted());
            assertEquals(0, cleanups.get());

            // The deferred cleanup fills the capacity-one scheduler queue; an unrelated
            // completed-frame submission is therefore rejected. That unrelated rejection must
            // not release the deadline-published owner while the accepted cleanup is queued.
            fixture.session.completedFrame(fixture.runner, 1, 1);

            CompletionStage<ScenarioResult> competing = fixture.start(Duration.ofSeconds(1));
            ScenarioResult busy = competing.toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(ScenarioFailure.SESSION_BUSY, busy.failure().orElseThrow(),
                    "the owner must stay held until the deferred cleanup drains");
            assertEquals(0, cleanups.get(),
                    "the deferred cleanup has not run yet");

            // After the deferred cleanup drains, the owner is released and a new acquisition
            // is admitted and succeeds.
            fixture.scheduler.drain();
            assertEquals(1, cleanups.get(),
                    "the deferred cleanup runs exactly once on the render thread");
            CompletionStage<ScenarioResult> next = fixture.start(Duration.ofSeconds(1));
            fixture.scheduler.drain();
            fixture.completedFrame();
            ScenarioResult nextResult = next.toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertTrue(nextResult.failure().isEmpty(),
                    "a new acquisition succeeds after the owner cleanup drains");
        }
    }

    private static class RecordingLifecycle implements ScenarioLifecycle {
        private final List<Thread> hookThreads;
        private final boolean ready;
        private final String identity;

        RecordingLifecycle(List<Thread> hookThreads, boolean ready, String identity) {
            this.hookThreads = hookThreads;
            this.ready = ready;
            this.identity = identity;
        }

        @Override public void setup(ScenarioRequest request) {
            hookThreads.add(Thread.currentThread());
        }

        @Override public void reset(ScenarioRequest request) {
            hookThreads.add(Thread.currentThread());
        }

        @Override public boolean ready(ScenarioRequest request) {
            hookThreads.add(Thread.currentThread());
            return ready;
        }

        @Override public String startStateIdentity(
                ScenarioRequest request, SemanticSnapshot snapshot) {
            hookThreads.add(Thread.currentThread());
            return identity;
        }

        @Override public void cleanup(ScenarioRequest request) {
            hookThreads.add(Thread.currentThread());
        }
    }
    private static final class ManualDeadlineScheduler
            implements DeadlineScheduler {
        private Runnable task;
        private boolean cancelled;

        @Override public Cancellation schedule(Duration delay, Runnable task) {
            this.task = task;
            return () -> cancelled = true;
        }

        void expire() {
            if (!cancelled) {
                task.run();
            }
        }
    }


    private static final class Fixture implements AutoCloseable {
        private static final Duration STEP = Duration.ofMillis(10);
        final Stage stage = Scene2dTestSupport.stage();
        final ControlledStageClock clock = new ControlledStageClock(stage, STEP);
        final RenderThreadScheduler scheduler;
        final ManualDeadlineScheduler deadlines = new ManualDeadlineScheduler();
        final Scene2dSession session = new Scene2dSession(stage);
        final ScenarioRegistry registry = new ScenarioRegistry();
        final Scene2dScenarioRunner runner;

        Fixture() {
            this(16);
        }

        Fixture(int schedulerCapacity) {
            scheduler = new RenderThreadScheduler(schedulerCapacity);
            runner = new Scene2dScenarioRunner(registry, scheduler, clock, deadlines);
        }

        Fixture(int schedulerCapacity, DeadlineScheduler deadlineScheduler) {
            scheduler = new RenderThreadScheduler(schedulerCapacity);
            runner = new Scene2dScenarioRunner(registry, scheduler, clock, deadlineScheduler);
        }

        void register(ScenarioLifecycle lifecycle) {
            registry.register(
                    new ScenarioDefinition(
                            ScenarioDefinition.SCHEMA_VERSION,
                            "login-ready",
                            "1.0.0",
                            "test-app",
                            List.of("desktop"),
                            1,
                            Duration.ofSeconds(2)),
                    lifecycle);
        }

        CompletionStage<ScenarioResult> start(Duration timeout) {
            return runner.start(
                    new ScenarioRequest(
                            ScenarioDefinition.SCHEMA_VERSION,
                            "login-ready",
                            42L,
                            Map.of("locale", "en", "account", "agent"),
                            "desktop",
                            Deadline.after(clock, timeout)),
                    "test-app",
                    "process-1",
                    "session-1");
        }

        Scene2dScenarioRunner.Lease acquireReady(Duration timeout) {
            CompletionStage<Scene2dScenarioRunner.Lease> acquired = acquire(timeout);
            scheduler.drain();
            completedFrame();
            return acquired.toCompletableFuture().join();
        }

        CompletionStage<Scene2dScenarioRunner.Lease> acquire(Duration timeout) {
            return runner.acquire(
                    new ScenarioRequest(
                            ScenarioDefinition.SCHEMA_VERSION,
                            "login-ready",
                            42L,
                            Map.of("locale", "en", "account", "agent"),
                            "desktop",
                            Deadline.after(clock, timeout)),
                    "test-app",
                    "process-1",
                    "session-1");
        }

        void completedFrame() {
            clock.advance(STEP);
            session.completedFrame(runner, clock.revision(), clock.frame());
            scheduler.drain();
        }

        ScenarioResult complete(CompletionStage<ScenarioResult> started) {
            scheduler.drain();
            completedFrame();
            return started.toCompletableFuture().join();
        }

        @Override public void close() {
            runner.close();
            session.close();
            scheduler.close();
            clock.close();
            stage.dispose();
        }
    }
}
