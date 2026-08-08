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
import java.time.Duration;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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

    private static DeadlineScheduler noopDeadlines() {
        return (delay, signal) -> () -> {};
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
