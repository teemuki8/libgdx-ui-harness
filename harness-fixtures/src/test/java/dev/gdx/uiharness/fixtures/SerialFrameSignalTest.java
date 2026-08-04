package dev.gdx.uiharness.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gdx.uiharness.core.wait.FrameSignal;
import dev.gdx.uiharness.lwjgl3.Lwjgl3FrameFence;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

final class SerialFrameSignalTest {
    @Test
    @Timeout(5)
    void forwardsCompletedRenderedFramesOffOwnerThreadInSourceOrder() throws Exception {
        Thread renderThread = Thread.currentThread();
        try (Lwjgl3FrameFence fence = new Lwjgl3FrameFence(4);
                SerialFrameSignal frames = new SerialFrameSignal(fence, () -> true)) {
            List<FrameSignal.Frame> observed = new ArrayList<>();
            List<Thread> callbackThreads = new ArrayList<>();
            CountDownLatch firstEntered = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            CountDownLatch bothObserved = new CountDownLatch(2);
            frames.subscribe(frame -> {
                if (frame.frame() == 41) {
                    firstEntered.countDown();
                    await(releaseFirst);
                }
                observed.add(frame);
                callbackThreads.add(Thread.currentThread());
                bothObserved.countDown();
            });

            fence.completedFrame(7, 41);
            assertTrue(firstEntered.await(1, TimeUnit.SECONDS));
            fence.completedFrame(8, 42);
            assertEquals(List.of(), observed,
                    "the second completed frame must not overtake blocked snapshot work");
            releaseFirst.countDown();
            assertTrue(bothObserved.await(1, TimeUnit.SECONDS));

            assertEquals(List.of(new FrameSignal.Frame(7, 41), new FrameSignal.Frame(8, 42)),
                    observed);
            assertEquals(2, callbackThreads.size());
            assertNotEquals(renderThread, callbackThreads.get(0));
            assertEquals(callbackThreads.get(0), callbackThreads.get(1));
        }
    }

    @Test
    void cancellationAndClosureBoundNotificationLifecycle() throws Exception {
        Lwjgl3FrameFence fence = new Lwjgl3FrameFence(4);
        SerialFrameSignal frames = new SerialFrameSignal(fence, () -> true);
        List<FrameSignal.Frame> observed = new ArrayList<>();
        CountDownLatch closed = new CountDownLatch(1);
        FrameSignal.Subscription subscription = frames.subscribe(observed::add);

        subscription.close();
        fence.completedFrame(1, 1);
        subscription.close();
        assertTrue(observed.isEmpty());

        frames.subscribe(new FrameSignal.FrameListener() {
            @Override public void onFrame(FrameSignal.Frame frame) {}

            @Override public void onClosed() {
                closed.countDown();
            }
        });
        frames.close();

        assertTrue(closed.await(1, TimeUnit.SECONDS));
        assertThrows(IllegalStateException.class, () -> frames.subscribe(observed::add));
        fence.close();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(1, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for test release");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
