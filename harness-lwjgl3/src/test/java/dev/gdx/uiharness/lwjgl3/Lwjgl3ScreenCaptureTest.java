package dev.gdx.uiharness.lwjgl3;

import static dev.gdx.uiharness.lwjgl3.Lwjgl3CaptureFixture.BLUE;
import static dev.gdx.uiharness.lwjgl3.Lwjgl3CaptureFixture.GREEN;
import static dev.gdx.uiharness.lwjgl3.Lwjgl3CaptureFixture.RED;
import static dev.gdx.uiharness.lwjgl3.Lwjgl3CaptureFixture.WINDOW_SIZE;
import static dev.gdx.uiharness.lwjgl3.Lwjgl3CaptureFixture.YELLOW;
import static dev.gdx.uiharness.lwjgl3.Lwjgl3CaptureFixture.await;
import static dev.gdx.uiharness.lwjgl3.Lwjgl3CaptureFixture.decode;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.graphics.Color;
import dev.gdx.uiharness.core.capture.CaptureRequest;
import dev.gdx.uiharness.core.capture.CapturedImage;
import dev.gdx.uiharness.core.error.ErrorCode;
import dev.gdx.uiharness.core.error.HarnessException;
import dev.gdx.uiharness.core.locator.Locator;
import dev.gdx.uiharness.core.time.Deadline;
import dev.gdx.uiharness.core.time.DeadlineScheduler;
import dev.gdx.uiharness.core.time.MonotonicClock;
import dev.gdx.uiharness.core.wait.FrameSignal;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.time.Duration;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class Lwjgl3ScreenCaptureTest {
    private Lwjgl3CaptureFixture fixture;

    @BeforeAll void startApplication() {
        fixture = new Lwjgl3CaptureFixture();
    }

    @AfterAll void stopApplicationAndNativeThread() {
        fixture.close();
        assertFalse(fixture.applicationThread().isAlive());
    }

    @Test void capturesFullBackBufferWithConventionalTopLeftOrientation() {
        fixture.configureQuadrants(WINDOW_SIZE);

        CapturedImage captured = fixture.captureFullWindow();
        BufferedImage decoded = decode(captured);

        assertEquals(WINDOW_SIZE, captured.width());
        assertEquals(WINDOW_SIZE, captured.height());
        assertEquals(WINDOW_SIZE, decoded.getWidth());
        assertEquals(WINDOW_SIZE, decoded.getHeight());
        assertEquals(RED, decoded.getRGB(8, 8));
        assertEquals(GREEN, decoded.getRGB(56, 8));
        assertEquals(BLUE, decoded.getRGB(8, 56));
        assertEquals(YELLOW, decoded.getRGB(56, 56));
        assertEquals(1.0, captured.scale().x());
        assertEquals(1.0, captured.scale().y());
    }

    @Test void recordsExactPngSha256AndDefensivelyOwnsBytes() {
        CapturedImage captured = fixture.captureFullWindow();
        byte[] firstRead = captured.pngBytes();
        String expected = sha256(firstRead);

        firstRead[0] ^= 0x7F;

        assertEquals(expected, captured.sha256());
        assertNotEquals(firstRead[0], captured.pngBytes()[0]);
        assertArrayEquals(new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47},
                java.util.Arrays.copyOf(captured.pngBytes(), 4));
    }

    @Test void rejectsFramebufferOverMaximumPixelsBeforeReadback() {
        CaptureRequest request = CaptureRequest.fullWindow().withLimits(
                new CaptureRequest.Limits(WINDOW_SIZE, WINDOW_SIZE, 100, 1_000_000));

        HarnessException failure = assertThrows(HarnessException.class,
                () -> await(fixture.capture().capture(request, fixture.deadline())));

        assertEquals(ErrorCode.LIMIT_EXCEEDED, failure.code());
        assertEquals("pixels", failure.evidence().details().get("dimension"));
    }

    @Test void boundsPngEncodingWhileStreaming() {
        CaptureRequest request = CaptureRequest.fullWindow().withLimits(
                new CaptureRequest.Limits(
                        WINDOW_SIZE, WINDOW_SIZE, WINDOW_SIZE * WINDOW_SIZE, 64));

        HarnessException failure = assertThrows(HarnessException.class,
                () -> await(fixture.capture().capture(request, fixture.deadline())));

        assertEquals(ErrorCode.LIMIT_EXCEEDED, failure.code());
        assertEquals("pngBytes", failure.evidence().details().get("dimension"));
    }

    @Test void expiredDeadlineNeverReachesCompletedFrameWork() {
        Deadline expired = Deadline.after(
                Lwjgl3CaptureFixture.CLOCK, Duration.ZERO);

        HarnessException failure = assertThrows(HarnessException.class,
                () -> await(fixture.capture().capture(
                        CaptureRequest.fullWindow(), expired)));

        assertEquals(ErrorCode.TIMEOUT, failure.code());
    }

    @Test void closingFenceReleasesQueuedCompletedFrameWork() {
        Lwjgl3FrameFence localFence = new Lwjgl3FrameFence(noopDeadlines(), 1);
        CompletionStage<String> pending = localFence.afterNextFrame(
                ignored -> "unreachable", fixture.deadline());

        localFence.close();
        HarnessException failure = assertThrows(HarnessException.class,
                () -> await(pending));

        assertEquals(ErrorCode.SESSION_CLOSED, failure.code());
    }

    @Test void legacyConstructorsOwnTheirDeadlineSchedulerAndCloseBounded() {
        Lwjgl3FrameFence defaultFence = new Lwjgl3FrameFence();
        CompletionStage<String> pending = defaultFence.afterNextFrame(
                frame -> "unreachable",
                Deadline.after(Lwjgl3CaptureFixture.CLOCK, Duration.ofHours(1)));
        ScheduledThreadPoolExecutor defaultOwned = ownedScheduler(defaultFence);
        assertNotNull(defaultOwned);
        assertFalse(defaultOwned.isShutdown());

        Lwjgl3FrameFence capacityFence = new Lwjgl3FrameFence(1);
        CompletionStage<String> first = capacityFence.afterNextFrame(
                frame -> "first",
                Deadline.after(Lwjgl3CaptureFixture.CLOCK, Duration.ofHours(1)));
        CompletionStage<String> overflow = capacityFence.afterNextFrame(
                frame -> "overflow",
                Deadline.after(Lwjgl3CaptureFixture.CLOCK, Duration.ofHours(1)));
        HarnessException overflowFailure = assertThrows(HarnessException.class,
                () -> await(overflow));
        assertEquals(ErrorCode.LIMIT_EXCEEDED, overflowFailure.code());
        ScheduledThreadPoolExecutor capacityOwned = ownedScheduler(capacityFence);
        assertNotNull(capacityOwned);
        assertFalse(capacityOwned.isShutdown());

        long closeStarted = System.nanoTime();
        defaultFence.close();
        capacityFence.close();
        long closeMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - closeStarted);

        assertTrue(defaultOwned.isShutdown(),
                "closing a legacy fence must shut down its owned scheduler");
        assertTrue(defaultOwned.isTerminated(),
                "the owned scheduler must terminate promptly");
        assertTrue(capacityOwned.isShutdown(),
                "closing a capacity-constructed legacy fence must shut its owned scheduler");
        assertTrue(capacityOwned.isTerminated());
        assertTrue(closeMillis < 5_000,
                "closing legacy fences with pending long deadlines must stay bounded");
        HarnessException failure = assertThrows(HarnessException.class, () -> await(pending));
        assertEquals(ErrorCode.SESSION_CLOSED, failure.code());
        HarnessException firstFailure = assertThrows(HarnessException.class, () -> await(first));
        assertEquals(ErrorCode.SESSION_CLOSED, firstFailure.code());
    }

    @Test void injectedDeadlineSchedulerRemainsExternallyOwnedAfterClose() {
        AtomicBoolean signalled = new AtomicBoolean();
        DeadlineScheduler external = (delay, signal) -> {
            signal.run();
            return () -> {};
        };
        Lwjgl3FrameFence localFence = new Lwjgl3FrameFence(external, 1);
        localFence.close();

        assertNull(ownedScheduler(localFence),
                "an injected scheduler must not be wrapped in an owned scheduler");
        external.schedule(Duration.ZERO, () -> signalled.set(true)).cancel();
        assertTrue(signalled.get(),
                "closing the fence must leave the injected scheduler usable by its owner");
    }

    @Test void ownedSchedulerShutdownNeverSurfacesFromAfterNextFrame() {
        Lwjgl3FrameFence localFence = new Lwjgl3FrameFence();
        ScheduledThreadPoolExecutor owned = ownedScheduler(localFence);
        assertNotNull(owned);
        owned.shutdownNow();

        CompletionStage<String> pending = localFence.afterNextFrame(
                frame -> "unreachable",
                Deadline.after(Lwjgl3CaptureFixture.CLOCK, Duration.ofHours(1)));

        HarnessException failure = assertThrows(HarnessException.class, () -> await(pending));
        assertEquals(ErrorCode.SESSION_CLOSED, failure.code(),
                "a legacy fence must report queued work as closed instead of throwing from "
                        + "its own shut-down scheduler");
        localFence.close();
    }

    @Test void concurrentSecondCloseWaitsForTheFirstCleanup() throws Exception {
        Lwjgl3FrameFence localFence = new Lwjgl3FrameFence(noopDeadlines(), 1);
        CountDownLatch listenerEntered = new CountDownLatch(1);
        CountDownLatch releaseListener = new CountDownLatch(1);
        localFence.subscribe(new FrameSignal.FrameListener() {
            @Override public void onFrame(FrameSignal.Frame frame) { }

            @Override public void onClosed() {
                listenerEntered.countDown();
                try {
                    releaseListener.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("listener interrupted", exception);
                }
            }
        });
        Thread firstCloser = new Thread(localFence::close, "first-fence-closer");
        firstCloser.start();
        assertTrue(listenerEntered.await(1, TimeUnit.SECONDS),
                "the first close must reach its blocking listener");
        AtomicBoolean secondCloseReturned = new AtomicBoolean();
        Thread secondCloser = new Thread(() -> {
            localFence.close();
            secondCloseReturned.set(true);
        }, "second-fence-closer");
        secondCloser.start();
        Thread.sleep(200);
        assertFalse(secondCloseReturned.get(),
                "a concurrent second close must not return before the first cleanup finishes");
        releaseListener.countDown();
        firstCloser.join(1_000);
        secondCloser.join(1_000);
        assertFalse(firstCloser.isAlive(), "the first close must finish after its listener");
        assertFalse(secondCloser.isAlive(), "the second close must return after the cleanup");
        assertTrue(secondCloseReturned.get(),
                "the second close must return once the first cleanup has finished");
    }

    @Test void reentrantCloseFromListenerDoesNotDeadlock() {
        Lwjgl3FrameFence localFence = new Lwjgl3FrameFence(noopDeadlines(), 1);
        AtomicBoolean reentrantCloseReturned = new AtomicBoolean();
        localFence.subscribe(new FrameSignal.FrameListener() {
            @Override public void onFrame(FrameSignal.Frame frame) { }

            @Override public void onClosed() {
                localFence.close();
                reentrantCloseReturned.set(true);
            }
        });
        localFence.close();

        assertTrue(reentrantCloseReturned.get(),
                "a reentrant close from the closing thread's own listener must return immediately");
        HarnessException failure = assertThrows(HarnessException.class,
                () -> await(localFence.afterNextFrame(
                        frame -> "unreachable", fixture.deadline())));
        assertEquals(ErrorCode.SESSION_CLOSED, failure.code());
    }

    @Test void closeAggregatesCleanupFailuresYetCompletesEverything() {
        DeadlineScheduler failingCancellations = (delay, signal) -> () -> {
            throw new IllegalStateException("cancel-step");
        };
        Lwjgl3FrameFence localFence = new Lwjgl3FrameFence(failingCancellations, 2);
        CompletionStage<String> first = localFence.afterNextFrame(
                frame -> "first", fixture.deadline());
        CompletionStage<String> second = localFence.afterNextFrame(
                frame -> "second", fixture.deadline());
        AtomicInteger closedNotifications = new AtomicInteger();
        AtomicBoolean laterListenerReached = new AtomicBoolean();
        localFence.subscribe(new FrameSignal.FrameListener() {
            @Override public void onFrame(FrameSignal.Frame frame) { }

            @Override public void onClosed() {
                closedNotifications.incrementAndGet();
                throw new IllegalStateException("listener-step");
            }
        });
        localFence.subscribe(new FrameSignal.FrameListener() {
            @Override public void onFrame(FrameSignal.Frame frame) { }

            @Override public void onClosed() {
                closedNotifications.incrementAndGet();
                laterListenerReached.set(true);
            }
        });

        IllegalStateException closeFailure = assertThrows(IllegalStateException.class,
                localFence::close);

        assertEquals("cancel-step", closeFailure.getMessage(),
                "the first cleanup failure must be the primary close failure");
        assertEquals(2, closeFailure.getSuppressed().length,
                "later cleanup failures must be suppressed on the primary failure");
        assertEquals(ErrorCode.SESSION_CLOSED,
                assertThrows(HarnessException.class, () -> await(first)).code());
        assertEquals(ErrorCode.SESSION_CLOSED,
                assertThrows(HarnessException.class, () -> await(second)).code());
        assertEquals(2, closedNotifications.get(),
                "every listener must be notified even when one throws");
        assertTrue(laterListenerReached.get(),
                "a throwing listener must not prevent later listeners from being notified");
        assertTrue(retainedListeners(localFence).isEmpty(),
                "close must clear listener retention even when cleanup steps fail");
    }

    @Test void ownedSchedulerShutsDownEvenWhenListenerCleanupFails() {
        Lwjgl3FrameFence localFence = new Lwjgl3FrameFence(1);
        CompletionStage<String> pending = localFence.afterNextFrame(
                frame -> "unreachable",
                Deadline.after(Lwjgl3CaptureFixture.CLOCK, Duration.ofHours(1)));
        ScheduledThreadPoolExecutor owned = ownedScheduler(localFence);
        assertNotNull(owned);
        localFence.subscribe(new FrameSignal.FrameListener() {
            @Override public void onFrame(FrameSignal.Frame frame) { }

            @Override public void onClosed() {
                throw new IllegalStateException("listener-step");
            }
        });

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                localFence::close);

        assertEquals("listener-step", failure.getMessage());
        assertTrue(owned.isShutdown(),
                "the owned scheduler must shut down even when a listener fails");
        assertTrue(owned.isTerminated(),
                "the owned scheduler must terminate even when a listener fails");
        assertEquals(ErrorCode.SESSION_CLOSED,
                assertThrows(HarnessException.class, () -> await(pending)).code());
        assertTrue(retainedListeners(localFence).isEmpty(),
                "close must clear listener retention even when a listener fails");
    }

    @Test void concurrentWaitersObserveTheSameCloseFailure() throws Exception {
        Lwjgl3FrameFence localFence = new Lwjgl3FrameFence(noopDeadlines(), 1);
        CountDownLatch listenerEntered = new CountDownLatch(1);
        CountDownLatch releaseListener = new CountDownLatch(1);
        localFence.subscribe(new FrameSignal.FrameListener() {
            @Override public void onFrame(FrameSignal.Frame frame) { }

            @Override public void onClosed() {
                listenerEntered.countDown();
                try {
                    releaseListener.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("listener interrupted", exception);
                }
                throw new IllegalStateException("cleanup-failure");
            }
        });
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();
        Thread firstCloser = new Thread(() -> {
            try {
                localFence.close();
            } catch (Throwable failure) {
                firstFailure.set(failure);
            }
        }, "first-fence-closer");
        firstCloser.start();
        assertTrue(listenerEntered.await(1, TimeUnit.SECONDS),
                "the first close must reach its failing listener");
        Thread secondCloser = new Thread(() -> {
            try {
                localFence.close();
            } catch (Throwable failure) {
                secondFailure.set(failure);
            }
        }, "second-fence-closer");
        secondCloser.start();
        long waitDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (secondCloser.getState() != Thread.State.WAITING
                && System.nanoTime() < waitDeadline) {
            Thread.yield();
        }
        assertEquals(Thread.State.WAITING, secondCloser.getState(),
                "the second closer must block until the first cleanup finishes");
        releaseListener.countDown();
        firstCloser.join(1_000);
        secondCloser.join(1_000);
        assertFalse(firstCloser.isAlive());
        assertFalse(secondCloser.isAlive());

        assertNotNull(firstFailure.get(), "the first closer must surface the cleanup failure");
        assertNotNull(secondFailure.get(), "a waiting closer must observe the same failure");
        assertSame(firstFailure.get(), secondFailure.get(),
                "the first close failure must propagate identically to concurrent waiters");
    }

    @Test void closingScreenCaptureImmediatelyFailsItsQueuedRequest() {
        Lwjgl3FrameFence localFence = new Lwjgl3FrameFence(noopDeadlines(), 1);
        Lwjgl3ScreenCapture localCapture = new Lwjgl3ScreenCapture(
                localFence, (revision, frame) -> {
                    throw new AssertionError("full-window capture must not resolve semantics");
                });
        CompletionStage<CapturedImage> pending = localCapture.capture(
                CaptureRequest.fullWindow(), fixture.deadline());

        localCapture.close();
        ExecutionException completion = assertThrows(ExecutionException.class,
                () -> pending.toCompletableFuture().get(1, TimeUnit.SECONDS));
        CompletionStage<String> independentWork = localFence.afterNextFrame(
                frame -> "fence-open", fixture.deadline());
        localFence.completedFrame(1, 1);
        localFence.close();

        HarnessException failure = (HarnessException) completion.getCause();
        assertEquals(ErrorCode.SESSION_CLOSED, failure.code());
        assertEquals("fence-open", await(independentWork));
    }

    @Test void cancellingQueuedFenceWorkImmediatelyReleasesCapacity() {
        Lwjgl3FrameFence localFence = new Lwjgl3FrameFence(noopDeadlines(), 1);
        AtomicBoolean cancelledWorkRan = new AtomicBoolean();
        CompletionStage<String> cancelled = localFence.afterNextFrame(frame -> {
            cancelledWorkRan.set(true);
            return "cancelled";
        }, fixture.deadline());

        assertTrue(cancelled.toCompletableFuture().cancel(false));
        CompletionStage<String> replacement = localFence.afterNextFrame(
                frame -> "replacement", fixture.deadline());
        localFence.completedFrame(1, 1);
        localFence.close();

        assertEquals("replacement", await(replacement));
        assertFalse(cancelledWorkRan.get());
    }

    @Test void cancellationLosesOnceFrameWorkIsAtomicallyClaimed() throws Exception {
        CompletableFuture<Lwjgl3FrameFence> ready = new CompletableFuture<>();
        CountDownLatch completeFrame = new CountDownLatch(1);
        CountDownLatch taskEntered = new CountDownLatch(1);
        CountDownLatch releaseTask = new CountDownLatch(1);
        Thread owner = new Thread(() -> {
            Lwjgl3FrameFence ownedFence = new Lwjgl3FrameFence(noopDeadlines(), 1);
            ready.complete(ownedFence);
            try {
                if (!completeFrame.await(1, TimeUnit.SECONDS)) {
                    throw new AssertionError("frame completion was not requested");
                }
                ownedFence.completedFrame(1, 1);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("fence owner interrupted", exception);
            }
        }, "claimed-frame-fence-owner");
        owner.start();
        Lwjgl3FrameFence ownedFence = await(ready);
        CompletionStage<String> claimed = ownedFence.afterNextFrame(frame -> {
            taskEntered.countDown();
            if (!releaseTask.await(1, TimeUnit.SECONDS)) {
                throw new AssertionError("claimed task was not released");
            }
            return "completed";
        }, fixture.deadline());

        completeFrame.countDown();
        assertTrue(taskEntered.await(1, TimeUnit.SECONDS));
        boolean cancellationAccepted;
        try {
            cancellationAccepted = claimed.toCompletableFuture().cancel(false);
        } finally {
            releaseTask.countDown();
        }
        owner.join(1_000);
        ownedFence.close();

        assertFalse(cancellationAccepted);
        assertEquals("completed", await(claimed));
        assertFalse(owner.isAlive());
    }

    @Test void captureSubmittedOffThreadRunsOnOwningGraphicsThread() {
        Thread graphicsThread = await(fixture.fence().afterNextFrame(
                ignored -> Thread.currentThread(), fixture.deadline()));

        CapturedImage captured = CompletableFuture.supplyAsync(() ->
                await(fixture.capture().capture(
                        CaptureRequest.fullWindow(), fixture.deadline()))).join();

        assertEquals(fixture.applicationThread(), graphicsThread);
        assertTrue(captured.frame() > 0);
        assertThrows(IllegalStateException.class,
                () -> fixture.fence().completedFrame(10_000, 10_000));
    }

    @Test void captureRequestedAfterActionWaitsForALaterCompletedRenderedFrame() {
        CompletionStage<FrameSignal.Frame> action =
                fixture.setTopLeftColorAfterAction(Color.MAGENTA);
        CompletionStage<CapturedImage> capture = action.thenCompose(frame ->
                fixture.capture().capture(
                        CaptureRequest.actor(Locator.testId("top-left")), fixture.deadline()));
        FrameSignal.Frame completedFrame = await(action);
        CapturedImage captured = await(capture);

        assertTrue(captured.frame() > completedFrame.frame());
        assertTrue(captured.revision() > completedFrame.revision());
        assertEquals(0xFFFF00FF, decode(captured).getRGB(8, 8));
    }

    @Test void queuedCaptureExpiresWithoutACompletedFrame() {
        ManualClock clock = new ManualClock();
        ManualDeadlineScheduler deadlines = new ManualDeadlineScheduler();
        Lwjgl3FrameFence localFence = new Lwjgl3FrameFence(deadlines, 1);
        CompletionStage<String> pending = localFence.afterNextFrame(
                frame -> "unreachable", Deadline.after(clock, Duration.ofSeconds(1)));

        clock.advance(Duration.ofSeconds(1));
        deadlines.expire();
        assertTrue(pending.toCompletableFuture().isDone(),
                "the deadline signal must fail the queued capture without a completed frame");
        HarnessException failure = assertThrows(HarnessException.class, () -> await(pending));
        assertEquals(ErrorCode.TIMEOUT, failure.code());
        assertTrue(failure.evidence().elapsed().toMillis() >= 1000,
                "the typed timeout must retain the elapsed monotonic time");
        localFence.close();
    }

    @Test void deadlineSignalRacingACompletedFrameCompletesExactlyOnce() {
        ManualClock clock = new ManualClock();
        ManualDeadlineScheduler deadlines = new ManualDeadlineScheduler();
        Lwjgl3FrameFence localFence = new Lwjgl3FrameFence(deadlines, 1);
        AtomicBoolean taskRan = new AtomicBoolean();
        CompletionStage<String> pending = localFence.afterNextFrame(frame -> {
            taskRan.set(true);
            return "ran";
        }, Deadline.after(clock, Duration.ofSeconds(1)));

        clock.advance(Duration.ofSeconds(1));
        deadlines.expire();
        assertTrue(pending.toCompletableFuture().isDone(),
                "the signal must claim the queued command before any frame is completed");
        localFence.completedFrame(1, 1);
        localFence.close();

        HarnessException failure = assertThrows(HarnessException.class, () -> await(pending));
        assertEquals(ErrorCode.TIMEOUT, failure.code());
        assertFalse(taskRan.get(), "a late completed frame must not execute claimed work");
    }

    @Test void completedFrameBeforeTheDeadlineSignalWins() {
        ManualClock clock = new ManualClock();
        ManualDeadlineScheduler deadlines = new ManualDeadlineScheduler();
        Lwjgl3FrameFence localFence = new Lwjgl3FrameFence(deadlines, 1);
        AtomicBoolean taskRan = new AtomicBoolean();
        CompletionStage<String> pending = localFence.afterNextFrame(frame -> {
            taskRan.set(true);
            return "ran";
        }, Deadline.after(clock, Duration.ofSeconds(5)));

        localFence.completedFrame(1, 1);
        clock.advance(Duration.ofSeconds(5));
        deadlines.expire();
        localFence.close();

        assertEquals("ran", await(pending));
        assertTrue(taskRan.get(), "the claimed frame work must run exactly once");
    }

    @Test void closingFenceCancelsArmedDeadlineSignals() {
        ManualClock clock = new ManualClock();
        ManualDeadlineScheduler deadlines = new ManualDeadlineScheduler();
        Lwjgl3FrameFence localFence = new Lwjgl3FrameFence(deadlines, 1);
        CompletionStage<String> pending = localFence.afterNextFrame(
                frame -> "unreachable", Deadline.after(clock, Duration.ofSeconds(1)));

        localFence.close();
        assertTrue(deadlines.cancelled,
                "closing the fence must cancel every armed deadline signal");
        HarnessException failure = assertThrows(HarnessException.class, () -> await(pending));
        assertEquals(ErrorCode.SESSION_CLOSED, failure.code());
        clock.advance(Duration.ofSeconds(1));
        deadlines.expire();
        HarnessException late = assertThrows(HarnessException.class, () -> await(pending));
        assertEquals(ErrorCode.SESSION_CLOSED, late.code(),
                "a late signal must not overwrite the terminal close outcome");
    }

    @Test void claimedFrameCancellationRunsOutsideTheLifecycleMonitor() {
        AtomicReference<Lwjgl3FrameFence> fenceRef = new AtomicReference<>();
        AtomicBoolean monitorHeld = new AtomicBoolean();
        DeadlineScheduler probing = (delay, signal) ->
                monitorProbe(fenceRef, fixture.deadline(), monitorHeld);
        Lwjgl3FrameFence localFence = new Lwjgl3FrameFence(probing, 1);
        fenceRef.set(localFence);
        localFence.afterNextFrame(frame -> "claimed", fixture.deadline());

        localFence.completedFrame(1, 1);

        assertFalse(monitorHeld.get(),
                "claiming frame work must cancel the deadline token only after leaving the lifecycle monitor");
        localFence.close();
    }

    @Test void closingCancellationRunsOutsideTheLifecycleMonitor() {
        AtomicReference<Lwjgl3FrameFence> fenceRef = new AtomicReference<>();
        AtomicBoolean monitorHeld = new AtomicBoolean();
        DeadlineScheduler probing = (delay, signal) ->
                monitorProbe(fenceRef, fixture.deadline(), monitorHeld);
        Lwjgl3FrameFence localFence = new Lwjgl3FrameFence(probing, 1);
        fenceRef.set(localFence);
        localFence.afterNextFrame(frame -> "unreachable", fixture.deadline());

        localFence.close();

        assertFalse(monitorHeld.get(),
                "closing the fence must cancel the deadline token only after leaving the lifecycle monitor");
    }

    @Test void publicCancellationRunsOutsideTheLifecycleMonitor() {
        AtomicReference<Lwjgl3FrameFence> fenceRef = new AtomicReference<>();
        AtomicBoolean monitorHeld = new AtomicBoolean();
        DeadlineScheduler probing = (delay, signal) ->
                monitorProbe(fenceRef, fixture.deadline(), monitorHeld);
        Lwjgl3FrameFence localFence = new Lwjgl3FrameFence(probing, 1);
        fenceRef.set(localFence);
        CompletionStage<String> pending = localFence.afterNextFrame(
                frame -> "unreachable", fixture.deadline());

        assertTrue(pending.toCompletableFuture().cancel(false));

        assertFalse(monitorHeld.get(),
                "public cancellation must cancel the deadline token only after leaving the lifecycle monitor");
        localFence.close();
    }

    @Test void registrationRaceCancellationRunsOutsideTheLifecycleMonitor() {
        AtomicReference<Lwjgl3FrameFence> fenceRef = new AtomicReference<>();
        AtomicBoolean claimedDuringRegistration = new AtomicBoolean();
        AtomicBoolean monitorHeld = new AtomicBoolean();
        DeadlineScheduler inlineClaiming = (delay, signal) -> {
            if (claimedDuringRegistration.compareAndSet(false, true)) {
                fenceRef.get().completedFrame(1, 1);
            }
            return monitorProbe(fenceRef, fixture.deadline(), monitorHeld);
        };
        Lwjgl3FrameFence localFence = new Lwjgl3FrameFence(inlineClaiming, 1);
        fenceRef.set(localFence);
        CompletionStage<String> pending = localFence.afterNextFrame(
                frame -> "claimed-during-registration", fixture.deadline());

        assertEquals("claimed-during-registration", await(pending));
        assertFalse(monitorHeld.get(),
                "a registration race must cancel the late token only after leaving the lifecycle monitor");
        localFence.close();
    }

    private static DeadlineScheduler noopDeadlines() {
        return (delay, signal) -> () -> {};
    }

    /**
     * Returns the executor behind the scheduler a legacy-constructed fence owns, or {@code null}
     * when the fence received an injected scheduler. Reflection is required because the owned
     * scheduler is an implementation detail; the legacy constructor contract is that the fence
     * creates and shuts down its own scheduler.
     */
    private static ScheduledThreadPoolExecutor ownedScheduler(Lwjgl3FrameFence fence) {
        try {
            Field field = Lwjgl3FrameFence.class.getDeclaredField("ownedScheduler");
            field.setAccessible(true);
            Object owned = field.get(fence);
            if (owned == null) {
                return null;
            }
            Field executor = owned.getClass().getDeclaredField("executor");
            executor.setAccessible(true);
            return (ScheduledThreadPoolExecutor) executor.get(owned);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(
                    "the owned scheduler must be observable for lifecycle assertions", exception);
        }
    }

    /**
     * Returns the fence's live listener registrations so retention can be asserted after close.
     */
    private static Collection<?> retainedListeners(Lwjgl3FrameFence fence) {
        try {
            Field field = Lwjgl3FrameFence.class.getDeclaredField("listeners");
            field.setAccessible(true);
            return (Collection<?>) field.get(fence);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(
                    "listeners must be observable for retention assertions", exception);
        }
    }

    /**
     * Returns a cancellation that verifies the fence's lifecycle monitor is not held while it
     * runs: a helper thread synchronously reenters the fence via {@link #afterNextFrame}, which
     * can only pass once the monitor is free. A bounded wait records {@code true} in
     * {@code monitorHeld} when the probe could not enter while the cancellation was running.
     */
    private static DeadlineScheduler.Cancellation monitorProbe(
            AtomicReference<Lwjgl3FrameFence> fenceRef,
            Deadline deadline,
            AtomicBoolean monitorHeld) {
        return () -> {
            CompletableFuture<Boolean> entered = CompletableFuture.supplyAsync(() -> {
                fenceRef.get().afterNextFrame(ignored -> "probe", deadline);
                return Boolean.TRUE;
            });
            try {
                entered.get(1, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("monitor probe interrupted", exception);
            } catch (ExecutionException | TimeoutException exception) {
                monitorHeld.set(true);
            }
        };
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("SHA-256 is required by the JDK", exception);
        }
    }

    /** Deterministic monotonic time source advanced explicitly by the test. */
    private static final class ManualClock implements MonotonicClock {
        private long nowNanos;

        void advance(Duration duration) {
            nowNanos = Math.addExact(nowNanos, duration.toNanos());
        }

        @Override public long nanoTime() {
            return nowNanos;
        }
    }

    /**
     * Records one armed deadline signal. {@link #expire()} fires the signal even when a
     * cancellation raced it, mirroring a real executor whose signal was already dispatched.
     */
    private static final class ManualDeadlineScheduler implements DeadlineScheduler {
        private Runnable signal;
        private boolean cancelled;

        @Override public Cancellation schedule(Duration delay, Runnable signal) {
            this.signal = signal;
            return () -> cancelled = true;
        }

        void expire() {
            if (signal != null) {
                signal.run();
            }
        }
    }
}
