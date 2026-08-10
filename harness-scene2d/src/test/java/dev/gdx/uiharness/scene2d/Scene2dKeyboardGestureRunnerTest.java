package dev.gdx.uiharness.scene2d;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.Input.Keys;
import com.badlogic.gdx.InputAdapter;
import dev.gdx.uiharness.core.gesture.KeyboardGestureRequest;
import dev.gdx.uiharness.core.gesture.KeyboardGestureResult;
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

    private static final class Fixture {
        final Thread ownerThread = Thread.currentThread();
        final ManualClock clock = new ManualClock();
        final AtomicLong revision = new AtomicLong();
        final AtomicLong frame = new AtomicLong();
        final ManualFrames frames = new ManualFrames(revision, frame);
        final ManualDeadlines deadlines = new ManualDeadlines();
        final RenderThreadScheduler scheduler = new RenderThreadScheduler(16);
        final RecordingInput input = new RecordingInput();
        final Scene2dKeyboardGestureRunner runner = new Scene2dKeyboardGestureRunner(
                "game", input, scheduler, frames, revision::get, frame::get, deadlines,
                Optional.empty(), ignored -> {});

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

        @Override public boolean keyDown(int keycode) {
            threads.add(Thread.currentThread());
            events.add("down:" + keycode);
            return true;
        }

        @Override public boolean keyUp(int keycode) {
            threads.add(Thread.currentThread());
            events.add("up:" + keycode);
            return true;
        }

        @Override public boolean keyTyped(char character) {
            throw new AssertionError("gesture must not synthesize keyTyped");
        }
    }

    private static final class ManualClock implements MonotonicClock {
        private long nanos;

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
    }

    private static final class ManualDeadlines implements DeadlineScheduler {
        @Override public Cancellation schedule(Duration delay, Runnable signal) {
            return () -> {};
        }
    }
}
