package dev.gdx.uiharness.scene2d;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.Input.Keys;
import com.badlogic.gdx.InputAdapter;
import dev.gdx.uiharness.core.gesture.KeyboardGestureRequest;
import dev.gdx.uiharness.core.gesture.KeyboardGestureResult;
import dev.gdx.uiharness.core.gesture.KeyboardGestureResult.CleanupAttemptStatus;
import dev.gdx.uiharness.core.gesture.KeyboardGestureResult.CleanupStatus;
import dev.gdx.uiharness.core.gesture.KeyboardGestureResult.FailureCategory;
import dev.gdx.uiharness.core.gesture.KeyboardGestureResult.StepKind;
import dev.gdx.uiharness.core.gesture.KeyboardGestureResult.TerminalOutcome;
import dev.gdx.uiharness.core.time.Deadline;
import dev.gdx.uiharness.core.time.DeadlineScheduler;
import dev.gdx.uiharness.core.time.MonotonicClock;
import dev.gdx.uiharness.core.wait.FrameSignal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

final class Scene2dKeyboardGestureRunnerTest {
    @Test void holdsKeyAcrossExactlyThirtyDistinctLaterFrames() {
        Fixture fixture = new Fixture();
        CompletableFuture<KeyboardGestureResult> result = fixture.execute(List.of(
                new KeyboardGestureRequest.KeyDown(Keys.A),
                new KeyboardGestureRequest.WaitFrames(30),
                new KeyboardGestureRequest.KeyUp(Keys.A)));

        fixture.scheduler.drain();
        assertEquals(List.of("down:" + Keys.A), fixture.input.events);

        fixture.frames.publish(1, 1);
        fixture.frames.publish(2, 1);
        for (long frame = 2; frame <= 29; frame++) {
            fixture.frames.publish(frame + 1, frame);
        }
        assertEquals(List.of("down:" + Keys.A), fixture.input.events);
        assertFalse(result.isDone());

        fixture.frames.publish(31, 30);
        assertFalse(result.isDone(), "frame callback must not invoke input directly");
        fixture.scheduler.drain();

        KeyboardGestureResult completed = result.join();
        assertEquals(List.of("down:" + Keys.A, "up:" + Keys.A), fixture.input.events);
        assertEquals(TerminalOutcome.COMPLETED, completed.outcome());
        assertEquals(3, completed.completedSteps());
        assertEquals(30, completed.steps().get(1).count().orElseThrow());
        assertEquals(0, completed.steps().get(1).beforeFrame());
        assertEquals(30, completed.steps().get(1).afterFrame());
        assertTrue(completed.steps().stream().allMatch(step -> step.tick().isEmpty()));
    }

    @Test void keyChordsPreserveRequestedTransitionOrderOnOwnerThread() {
        Fixture fixture = new Fixture();
        CompletableFuture<KeyboardGestureResult> result = fixture.execute(List.of(
                new KeyboardGestureRequest.KeyDown(Keys.SHIFT_LEFT),
                new KeyboardGestureRequest.KeyDown(Keys.A),
                new KeyboardGestureRequest.KeyUp(Keys.A),
                new KeyboardGestureRequest.KeyUp(Keys.SHIFT_LEFT)));

        for (int index = 0; index < 4; index++) {
            fixture.scheduler.drain();
        }

        assertEquals(List.of(
                "down:" + Keys.SHIFT_LEFT,
                "down:" + Keys.A,
                "up:" + Keys.A,
                "up:" + Keys.SHIFT_LEFT), fixture.input.events);
        assertTrue(fixture.input.threads.stream()
                .allMatch(thread -> thread == fixture.ownerThread));
        assertEquals(List.of(
                StepKind.KEY_DOWN, StepKind.KEY_DOWN, StepKind.KEY_UP, StepKind.KEY_UP),
                result.join().steps().stream().map(KeyboardGestureResult.StepEvidence::kind)
                        .toList());
    }

    @Test void keyDownFailureWaitsForReverseOrderRenderThreadCleanup() {
        Fixture fixture = new Fixture();
        fixture.input.failDown.add(Keys.B);
        CompletableFuture<KeyboardGestureResult> result = fixture.execute(List.of(
                new KeyboardGestureRequest.KeyDown(Keys.A),
                new KeyboardGestureRequest.KeyDown(Keys.B),
                new KeyboardGestureRequest.WaitFrames(1),
                new KeyboardGestureRequest.KeyUp(Keys.B),
                new KeyboardGestureRequest.KeyUp(Keys.A)));

        fixture.scheduler.drain();
        fixture.scheduler.drain();
        assertFalse(result.isDone(), "input failure must not publish before cleanup");
        fixture.scheduler.drain();
        assertFalse(result.isDone(), "cleanup releases one held key per render turn");
        fixture.scheduler.drain();

        KeyboardGestureResult failed = result.join();
        assertEquals(List.of(
                "down:" + Keys.A,
                "down:" + Keys.B,
                "up:" + Keys.B,
                "up:" + Keys.A), fixture.input.events);
        assertEquals(TerminalOutcome.FAILED, failed.outcome());
        assertEquals(FailureCategory.KEY_DISPATCH_FAILURE, failed.failure().orElseThrow());
        assertEquals(1, failed.failureStep().orElseThrow());
        assertEquals(CleanupStatus.COMPLETED, failed.cleanupStatus());
        assertEquals(List.of(Keys.B, Keys.A), failed.cleanup().stream()
                .map(KeyboardGestureResult.CleanupAttempt::keycode).toList());
        assertTrue(failed.heldKeys().isEmpty());
    }

    @Test void cancellationSignalsCleanupAndReturnsStructuredTerminalResult() {
        Fixture fixture = new Fixture();
        CompletableFuture<KeyboardGestureResult> result = fixture.execute(List.of(
                new KeyboardGestureRequest.KeyDown(Keys.A),
                new KeyboardGestureRequest.WaitFrames(30),
                new KeyboardGestureRequest.KeyUp(Keys.A)));
        fixture.scheduler.drain();

        assertFalse(result.cancel(false), "cancellation must remain cleanup-transparent");
        assertFalse(result.isDone());
        fixture.scheduler.drain();

        KeyboardGestureResult cancelled = result.join();
        assertEquals(TerminalOutcome.CANCELLED, cancelled.outcome());
        assertEquals(FailureCategory.CANCELLED, cancelled.failure().orElseThrow());
        assertEquals(CleanupStatus.COMPLETED, cancelled.cleanupStatus());
        assertEquals(List.of("down:" + Keys.A, "up:" + Keys.A), fixture.input.events);
    }

    @Test void deadlineAndFrameClosureBothReleaseHeldKeys() {
        Fixture timeoutFixture = new Fixture();
        CompletableFuture<KeyboardGestureResult> timedOut = timeoutFixture.execute(List.of(
                new KeyboardGestureRequest.KeyDown(Keys.A),
                new KeyboardGestureRequest.WaitFrames(30),
                new KeyboardGestureRequest.KeyUp(Keys.A)));
        timeoutFixture.scheduler.drain();
        timeoutFixture.clock.advance(Duration.ofSeconds(10));
        timeoutFixture.deadlines.fireNext();
        assertFalse(timedOut.isDone());
        timeoutFixture.scheduler.drain();
        assertEquals(TerminalOutcome.TIMED_OUT, timedOut.join().outcome());

        Fixture closedFixture = new Fixture();
        CompletableFuture<KeyboardGestureResult> closed = closedFixture.execute(List.of(
                new KeyboardGestureRequest.KeyDown(Keys.B),
                new KeyboardGestureRequest.WaitFrames(30),
                new KeyboardGestureRequest.KeyUp(Keys.B)));
        closedFixture.scheduler.drain();
        closedFixture.frames.close();
        assertFalse(closed.isDone());
        closedFixture.scheduler.drain();
        KeyboardGestureResult failed = closed.join();
        assertEquals(FailureCategory.FRAME_SOURCE_CLOSED, failed.failure().orElseThrow());
        assertEquals(List.of("down:" + Keys.B, "up:" + Keys.B),
                closedFixture.input.events);
    }

    @Test void cleanupFailureRetainsPrimaryCancellationAndUnreleasedKey() {
        Fixture fixture = new Fixture();
        fixture.input.failUpAlways.add(Keys.A);
        CompletableFuture<KeyboardGestureResult> result = fixture.execute(List.of(
                new KeyboardGestureRequest.KeyDown(Keys.A),
                new KeyboardGestureRequest.WaitFrames(30),
                new KeyboardGestureRequest.KeyUp(Keys.A)));
        fixture.scheduler.drain();

        result.cancel(false);
        fixture.scheduler.drain();

        KeyboardGestureResult cancelled = result.join();
        assertEquals(FailureCategory.CANCELLED, cancelled.failure().orElseThrow());
        assertEquals(CleanupStatus.FAILED, cancelled.cleanupStatus());
        assertEquals(List.of(Keys.A), cancelled.heldKeys());
        assertEquals(CleanupAttemptStatus.DISPATCH_FAILED,
                cancelled.cleanup().getFirst().status());
    }

    @Test void failedExplicitKeyUpRemainsEligibleForSuccessfulCleanupRetry() {
        Fixture fixture = new Fixture();
        fixture.input.failUpOnce.add(Keys.A);
        CompletableFuture<KeyboardGestureResult> result = fixture.execute(List.of(
                new KeyboardGestureRequest.KeyDown(Keys.A),
                new KeyboardGestureRequest.KeyUp(Keys.A)));

        fixture.scheduler.drain();
        fixture.scheduler.drain();
        assertFalse(result.isDone());
        fixture.scheduler.drain();

        KeyboardGestureResult failed = result.join();
        assertEquals(FailureCategory.KEY_DISPATCH_FAILURE, failed.failure().orElseThrow());
        assertEquals(1, failed.failureStep().orElseThrow());
        assertEquals(CleanupStatus.COMPLETED, failed.cleanupStatus());
        assertEquals(List.of(
                "down:" + Keys.A, "up:" + Keys.A, "up:" + Keys.A),
                fixture.input.events);
    }

    @Test void cleanupDeadlineAndSchedulerRejectionRetainUnreleasedKeys() {
        Fixture deadlineFixture = new Fixture();
        CompletableFuture<KeyboardGestureResult> deadlineResult = deadlineFixture.execute(List.of(
                new KeyboardGestureRequest.KeyDown(Keys.A),
                new KeyboardGestureRequest.WaitFrames(30),
                new KeyboardGestureRequest.KeyUp(Keys.A)));
        deadlineFixture.scheduler.drain();
        deadlineResult.cancel(false);
        deadlineFixture.deadlines.fireNext();
        KeyboardGestureResult deadlineFailure = deadlineResult.join();
        assertEquals(List.of(Keys.A), deadlineFailure.heldKeys());
        assertEquals(CleanupAttemptStatus.DEADLINE_EXCEEDED,
                deadlineFailure.cleanup().getFirst().status());

        Fixture rejectedFixture = new Fixture();
        CompletableFuture<KeyboardGestureResult> rejected = rejectedFixture.execute(List.of(
                new KeyboardGestureRequest.KeyDown(Keys.B),
                new KeyboardGestureRequest.WaitFrames(30),
                new KeyboardGestureRequest.KeyUp(Keys.B)));
        rejectedFixture.scheduler.drain();
        rejectedFixture.scheduler.close();
        rejected.cancel(false);
        KeyboardGestureResult schedulerFailure = rejected.join();
        assertEquals(List.of(Keys.B), schedulerFailure.heldKeys());
        assertEquals(CleanupAttemptStatus.SCHEDULER_REJECTED,
                schedulerFailure.cleanup().getFirst().status());
    }

    @Test void directLeaseAndStopRemainOwnedUntilCleanupTerminal() {
        Fixture fixture = new Fixture();
        CompletableFuture<KeyboardGestureResult> owner = fixture.execute(List.of(
                new KeyboardGestureRequest.KeyDown(Keys.A),
                new KeyboardGestureRequest.WaitFrames(30),
                new KeyboardGestureRequest.KeyUp(Keys.A)));
        fixture.scheduler.drain();

        KeyboardGestureResult busy = fixture.execute(List.of(
                new KeyboardGestureRequest.KeyDown(Keys.B),
                new KeyboardGestureRequest.KeyUp(Keys.B))).join();
        assertEquals(FailureCategory.SESSION_BUSY, busy.failure().orElseThrow());
        assertEquals(List.of("down:" + Keys.A), fixture.input.events);

        CompletableFuture<Void> stopped = fixture.runner.stop().toCompletableFuture();
        assertFalse(stopped.isDone());
        assertFalse(owner.isDone());
        KeyboardGestureResult stillBusy = fixture.execute(List.of(
                new KeyboardGestureRequest.KeyDown(Keys.C),
                new KeyboardGestureRequest.KeyUp(Keys.C))).join();
        assertEquals(FailureCategory.SESSION_CLOSED, stillBusy.failure().orElseThrow());
        fixture.scheduler.drain();

        assertEquals(TerminalOutcome.SESSION_CLOSED, owner.join().outcome());
        assertTrue(stopped.isDone());
    }

    @Test void traceSinkFailureCannotReplaceSuccessfulInput() {
        Fixture fixture = new Fixture(ignored -> {
            throw new IllegalStateException("trace sink unavailable");
        });
        CompletableFuture<KeyboardGestureResult> result = fixture.execute(List.of(
                new KeyboardGestureRequest.KeyDown(Keys.A),
                new KeyboardGestureRequest.KeyUp(Keys.A)));

        fixture.scheduler.drain();
        fixture.scheduler.drain();

        assertEquals(TerminalOutcome.COMPLETED, result.join().outcome());
    }

    private static final class Fixture {
        final Thread ownerThread = Thread.currentThread();
        final ManualClock clock = new ManualClock();
        final AtomicLong revision = new AtomicLong();
        final AtomicLong frame = new AtomicLong();
        final ManualFrames frames = new ManualFrames(revision, frame);
        final ManualDeadlines deadlines = new ManualDeadlines();
        final RenderThreadScheduler scheduler = new RenderThreadScheduler(16);
        final RecordingInput input = new RecordingInput();
        final Scene2dKeyboardGestureRunner runner;

        Fixture() {
            this(ignored -> {});
        }

        Fixture(java.util.function.Consumer<dev.gdx.uiharness.core.trace.TraceEvent> trace) {
            runner = new Scene2dKeyboardGestureRunner(
                    "game", input, scheduler, frames, revision::get, frame::get, deadlines,
                    Optional.empty(), trace);
        }

        CompletableFuture<KeyboardGestureResult> execute(
                List<KeyboardGestureRequest.Step> steps) {
            return runner.execute(
                    "request-1", new KeyboardGestureRequest(1, steps),
                    Deadline.after(clock, Duration.ofSeconds(10)))
                    .toCompletableFuture();
        }
    }

    private static final class RecordingInput extends InputAdapter {
        final List<String> events = new ArrayList<>();
        final List<Thread> threads = new ArrayList<>();
        final Set<Integer> failDown = new java.util.HashSet<>();
        final Set<Integer> failUpOnce = new java.util.HashSet<>();
        final Set<Integer> failUpAlways = new java.util.HashSet<>();

        @Override public boolean keyDown(int keycode) {
            threads.add(Thread.currentThread());
            events.add("down:" + keycode);
            if (failDown.contains(keycode)) {
                throw new IllegalStateException("keyDown failed");
            }
            return true;
        }

        @Override public boolean keyUp(int keycode) {
            threads.add(Thread.currentThread());
            events.add("up:" + keycode);
            if (failUpOnce.remove(keycode) || failUpAlways.contains(keycode)) {
                throw new IllegalStateException("keyUp failed");
            }
            return true;
        }

        @Override public boolean keyTyped(char character) {
            throw new AssertionError("gesture must not synthesize keyTyped");
        }
    }

    private static final class ManualClock implements MonotonicClock {
        private long nanos;

        void advance(Duration duration) {
            nanos = Math.addExact(nanos, duration.toNanos());
        }

        @Override public long nanoTime() {
            return nanos;
        }
    }

    private static final class ManualFrames implements FrameSignal {
        private final AtomicLong revision;
        private final AtomicLong frame;
        private final CopyOnWriteArrayList<FrameListener> listeners =
                new CopyOnWriteArrayList<>();

        ManualFrames(AtomicLong revision, AtomicLong frame) {
            this.revision = revision;
            this.frame = frame;
        }

        @Override public Subscription subscribe(FrameListener listener) {
            listeners.add(listener);
            return () -> listeners.remove(listener);
        }

        void publish(long newRevision, long newFrame) {
            revision.set(newRevision);
            frame.set(newFrame);
            Frame value = new Frame(newRevision, newFrame);
            listeners.forEach(listener -> listener.onFrame(value));
        }

        void close() {
            listeners.forEach(FrameListener::onClosed);
            listeners.clear();
        }
    }

    private static final class ManualDeadlines implements DeadlineScheduler {
        private final List<ScheduledSignal> signals = new ArrayList<>();

        @Override public Cancellation schedule(Duration delay, Runnable signal) {
            ScheduledSignal scheduled = new ScheduledSignal(signal);
            signals.add(scheduled);
            return () -> scheduled.cancelled = true;
        }

        void fireNext() {
            ScheduledSignal next = signals.stream()
                    .filter(signal -> !signal.cancelled && !signal.fired)
                    .findFirst().orElseThrow();
            next.fired = true;
            next.signal.run();
        }

        private static final class ScheduledSignal {
            private final Runnable signal;
            private boolean cancelled;
            private boolean fired;

            ScheduledSignal(Runnable signal) {
                this.signal = signal;
            }
        }
    }
}
