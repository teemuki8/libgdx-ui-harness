package dev.gdx.uiharness.scene2d;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.Input.Keys;
import com.badlogic.gdx.InputAdapter;
import dev.gdx.uiharness.core.gesture.ExactTickCoordinator;
import dev.gdx.uiharness.core.gesture.ExactTickCoordinator.TickAdvanceResult;
import dev.gdx.uiharness.core.gesture.ExactTickCoordinator.TickEvidence;
import dev.gdx.uiharness.core.gesture.ExactTickCoordinator.TickPreflight;
import dev.gdx.uiharness.core.gesture.InputGestureRequest;
import dev.gdx.uiharness.core.gesture.InputGestureResult;
import dev.gdx.uiharness.core.gesture.InputGestureResult.CleanupStatus;
import dev.gdx.uiharness.core.gesture.InputGestureResult.TerminalOutcome;
import dev.gdx.uiharness.core.time.Deadline;
import dev.gdx.uiharness.core.time.DeadlineScheduler;
import dev.gdx.uiharness.core.time.MonotonicClock;
import dev.gdx.uiharness.core.wait.FrameSignal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

final class Scene2dInputGestureRunnerTest {
    @Test void dispatchesCombinedInputThroughProductionCallbacksAndExactTicks() {
        ImmediateTicks ticks = new ImmediateTicks();
        Fixture fixture = new Fixture(ticks);
        var result = fixture.execute(List.of(
                new InputGestureRequest.KeyDown(Keys.W),
                new InputGestureRequest.MouseMove(20, -10),
                new InputGestureRequest.MouseDown(0),
                new InputGestureRequest.WaitTicks(30),
                new InputGestureRequest.MouseUp(0),
                new InputGestureRequest.KeyUp(Keys.W)));
        for (int i = 0; i < 8; i++) { fixture.scheduler.drain(); }
        assertEquals(TerminalOutcome.COMPLETED, result.join().outcome());
        assertEquals(30, ticks.tick);
        assertEquals(List.of("down:" + Keys.W, "move:120:90", "mouse-down:0",
                "mouse-up:0", "up:" + Keys.W), fixture.input.events);
    }
    @Test void preflightsUnsupportedTickAtEndBeforeAnyMouseOrKeyInput() {
        Fixture fixture = new Fixture();
        var result = fixture.execute(List.of(new InputGestureRequest.MouseMove(10, 0),
                new InputGestureRequest.WaitTicks(1)));
        fixture.scheduler.drain();
        assertEquals(TerminalOutcome.REJECTED, result.join().outcome());
        assertTrue(fixture.input.events.isEmpty());
    }
    @Test void failedMouseDownReleasesButtonAndKeyInReverseOwnershipOrder() {
        Fixture fixture = new Fixture();
        fixture.input.failDown.add(256);
        var result = fixture.execute(List.of(new InputGestureRequest.KeyDown(Keys.W),
                new InputGestureRequest.MouseDown(0), new InputGestureRequest.MouseUp(0),
                new InputGestureRequest.KeyUp(Keys.W)));
        for (int i = 0; i < 6; i++) { fixture.scheduler.drain(); }
        assertEquals(TerminalOutcome.FAILED, result.join().outcome());
        assertEquals(CleanupStatus.COMPLETED, result.join().cleanupStatus());
        assertEquals(List.of("down:" + Keys.W, "mouse-down:0", "mouse-up:0", "up:" + Keys.W),
                fixture.input.events);
        assertTrue(result.join().heldInputs().isEmpty());
    }
    @Test void cancellationDuringFrameWaitRetainsLeaseUntilMouseCleanup() {
        Fixture fixture = new Fixture();
        var result = fixture.execute(List.of(new InputGestureRequest.MouseDown(0),
                new InputGestureRequest.WaitFrames(30), new InputGestureRequest.MouseUp(0)));
        fixture.scheduler.drain();
        result.cancel(false);
        assertFalse(result.isDone());
        fixture.scheduler.drain();
        assertEquals(TerminalOutcome.CANCELLED, result.join().outcome());
        assertEquals(List.of("mouse-down:0", "mouse-up:0"), fixture.input.events);
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
        final Scene2dInputGestureRunner runner;

        Fixture() {
            this(ignored -> {});
        }

        Fixture(ExactTickCoordinator tickCoordinator) {
            this(Optional.of(tickCoordinator), ignored -> {});
        }

        Fixture(java.util.function.Consumer<dev.gdx.uiharness.core.trace.TraceEvent> trace) {
            this(Optional.empty(), trace);
        }

        Fixture(Optional<ExactTickCoordinator> tickCoordinator,
                java.util.function.Consumer<dev.gdx.uiharness.core.trace.TraceEvent> trace) {
            runner = new Scene2dInputGestureRunner(
                    "game", input, scheduler, frames, revision::get, frame::get, deadlines,
                    tickCoordinator, trace, new RelativePointerAdapter() {
                        private Position position = new Position(100, 100);
                        @Override public Position position() { return position; }
                        @Override public Position moveRelative(int dx, int dy) {
                            position = new Position(position.x() + dx, position.y() + dy);
                            return position;
                        }
                    });
        }

        CompletableFuture<InputGestureResult> execute(
                List<InputGestureRequest.Step> steps) {
            return execute(InputGestureRequest.SCHEMA_VERSION, steps);
        }

        CompletableFuture<InputGestureResult> execute(
                int schemaVersion, List<InputGestureRequest.Step> steps) {
            return runner.execute(
                    "request-1", new InputGestureRequest(schemaVersion, steps),
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
        final Set<Integer> blockUp = new java.util.HashSet<>();
        final CountDownLatch upStarted = new CountDownLatch(1);
        final CountDownLatch releaseUp = new CountDownLatch(1);

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
            if (blockUp.contains(keycode)) {
                upStarted.countDown();
                await(releaseUp);
            }
            if (failUpOnce.remove(keycode) || failUpAlways.contains(keycode)) {
                throw new IllegalStateException("keyUp failed");
            }
            return true;
        }

        @Override public boolean mouseMoved(int x, int y) {
            events.add("move:" + x + ":" + y);
            return true;
        }
        @Override public boolean touchDown(int x, int y, int pointer, int button) {
            events.add("mouse-down:" + button);
            if (failDown.contains(256 + button)) { throw new IllegalStateException("mouse down"); }
            return true;
        }
        @Override public boolean touchUp(int x, int y, int pointer, int button) {
            events.add("mouse-up:" + button);
            return true;
        }
        @Override public boolean keyTyped(char character) {
            throw new AssertionError("gesture must not synthesize keyTyped");
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while coordinating input callback", failure);
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

    private static final class ImmediateTicks implements ExactTickCoordinator {
        int preflightCalls;
        int advanceCalls;
        long tick;

        @Override public TickPreflight preflight(int ticks, Deadline deadline) {
            preflightCalls++;
            return new TickPreflight.Ready(10_000);
        }

        @Override public CompletionStage<TickAdvanceResult> advance(
                int ticks, Deadline deadline) {
            advanceCalls++;
            long start = tick;
            tick += ticks;
            return CompletableFuture.completedFuture(new TickAdvanceResult.Completed(
                    new TickEvidence(
                            ticks, ticks, start, tick, 1,
                            OptionalLong.of(start), OptionalLong.of(tick - 1),
                            OptionalLong.empty(), OptionalLong.empty(), 16_000_000)));
        }
    }

    private static final class FakeTicks implements ExactTickCoordinator {
        TickPreflight preflight = new TickPreflight.Ready(10_000);
        final CompletableFuture<TickAdvanceResult> advance = new CompletableFuture<>();
        int preflightCalls;

        @Override public TickPreflight preflight(int ticks, Deadline deadline) {
            preflightCalls++;
            return preflight;
        }

        @Override public CompletionStage<TickAdvanceResult> advance(
                int ticks, Deadline deadline) {
            return advance;
        }
    }
}
