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
import dev.gdx.uiharness.core.wait.FrameSignal;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
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
        Lwjgl3FrameFence localFence = new Lwjgl3FrameFence(1);
        CompletionStage<String> pending = localFence.afterNextFrame(
                ignored -> "unreachable", fixture.deadline());

        localFence.close();
        HarnessException failure = assertThrows(HarnessException.class,
                () -> await(pending));

        assertEquals(ErrorCode.SESSION_CLOSED, failure.code());
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

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("SHA-256 is required by the JDK", exception);
        }
    }
}
