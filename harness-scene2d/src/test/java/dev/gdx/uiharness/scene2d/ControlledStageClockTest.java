package dev.gdx.uiharness.scene2d;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import dev.gdx.uiharness.core.error.ErrorCode;
import dev.gdx.uiharness.core.error.HarnessException;
import dev.gdx.uiharness.core.wait.FrameSignal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

final class ControlledStageClockTest {
    @Test void advanceUsesOnlyFixedDeltaStepsAndEmitsOneSignalPerStep() {
        Stage stage = Scene2dTestSupport.stage();
        try {
            List<Float> deltas = new ArrayList<>();
            Actor actor = new Actor() {
                @Override public void act(float delta) {
                    deltas.add(delta);
                    super.act(delta);
                }
            };
            stage.addActor(actor);
            ControlledStageClock clock = new ControlledStageClock(
                    stage, Duration.ofMillis(16));
            List<FrameSignal.Frame> frames = new ArrayList<>();
            clock.subscribe(frames::add);

            clock.advance(Duration.ofMillis(48));

            assertEquals(List.of(0.016f, 0.016f, 0.016f), deltas);
            assertEquals(Duration.ofMillis(48).toNanos(), clock.nanoTime());
            assertEquals(3L, clock.frame());
            assertEquals(3L, clock.revision());
            assertEquals(
                    List.of(
                            new FrameSignal.Frame(1, 1),
                            new FrameSignal.Frame(2, 2),
                            new FrameSignal.Frame(3, 3)),
                    frames);
        } finally {
            stage.dispose();
        }
    }

    @Test void partialStepIsRejectedWithoutAdvancingStageOrClock() {
        Stage stage = Scene2dTestSupport.stage();
        try {
            List<Float> deltas = new ArrayList<>();
            Actor actor = new Actor() {
                @Override public void act(float delta) {
                    deltas.add(delta);
                }
            };
            stage.addActor(actor);
            ControlledStageClock clock = new ControlledStageClock(
                    stage, Duration.ofMillis(16));

            assertThrows(IllegalArgumentException.class,
                    () -> clock.advance(Duration.ofMillis(17)));
            assertEquals(List.of(), deltas);
            assertEquals(0L, clock.nanoTime());
            assertEquals(0L, clock.frame());
        } finally {
            stage.dispose();
        }
    }

    @Test void cancelledSubscriptionReceivesNoLaterFrames() {
        Stage stage = Scene2dTestSupport.stage();
        try {
            ControlledStageClock clock = new ControlledStageClock(
                    stage, Duration.ofMillis(10));
            List<FrameSignal.Frame> frames = new ArrayList<>();
            FrameSignal.Subscription subscription = clock.subscribe(frames::add);
            clock.advance(Duration.ofMillis(10));

            subscription.close();
            clock.advance(Duration.ofMillis(10));

            assertEquals(List.of(new FrameSignal.Frame(1, 1)), frames);
        } finally {
            stage.dispose();
        }
    }

    @Test void onlyOwningRenderThreadMayAdvanceStage() {
        Stage stage = Scene2dTestSupport.stage();
        try {
            ControlledStageClock clock = new ControlledStageClock(
                    stage, Duration.ofMillis(10));
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                CompletableFuture<Void> attemptedAdvance = CompletableFuture.runAsync(
                        () -> assertThrows(IllegalStateException.class,
                                () -> clock.advance(Duration.ofMillis(10))),
                        executor);
                attemptedAdvance.join();
            }
            assertEquals(0L, clock.frame());
        } finally {
            stage.dispose();
        }
    }

    @Test void closeNotifiesExistingListenersAndRejectsLaterUse() {
        Stage stage = Scene2dTestSupport.stage();
        try {
            ControlledStageClock clock = new ControlledStageClock(
                    stage, Duration.ofMillis(10));
            List<String> lifecycle = new ArrayList<>();
            clock.subscribe(new FrameSignal.FrameListener() {
                @Override public void onFrame(FrameSignal.Frame frame) {}

                @Override public void onClosed() {
                    lifecycle.add("closed");
                }
            });
            clock.close();
            assertEquals(List.of("closed"), lifecycle);

            HarnessException advanceError = assertThrows(HarnessException.class,
                    () -> clock.advance(Duration.ofMillis(10)));
            assertEquals(ErrorCode.SESSION_CLOSED, advanceError.code());
            HarnessException subscribeError = assertThrows(HarnessException.class,
                    () -> clock.subscribe(frame -> {}));
            assertEquals(ErrorCode.SESSION_CLOSED, subscribeError.code());
        } finally {
            stage.dispose();
        }
    }
}
