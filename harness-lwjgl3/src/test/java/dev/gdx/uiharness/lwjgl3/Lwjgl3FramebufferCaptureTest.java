package dev.gdx.uiharness.lwjgl3;

import static dev.gdx.uiharness.lwjgl3.Lwjgl3CaptureFixture.RED;
import static dev.gdx.uiharness.lwjgl3.Lwjgl3CaptureFixture.WINDOW_SIZE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.gdx.uiharness.core.error.ErrorCode;
import dev.gdx.uiharness.core.error.HarnessException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** A tool without a harness session still gets the harness's bounded, already-oriented capture. */
final class Lwjgl3FramebufferCaptureTest {
    private static Lwjgl3CaptureFixture fixture;

    @BeforeAll
    static void startFixture() {
        fixture = new Lwjgl3CaptureFixture();
    }

    @AfterAll
    static void stopFixture() {
        fixture.close();
    }

    @Test
    void capturesTheWholeBackBufferTopLeftFirst() throws IOException {
        fixture.configureQuadrants(WINDOW_SIZE);
        Lwjgl3FramebufferCapture.Image image = fixture.onApplicationThread(() ->
                Lwjgl3FramebufferCapture.captureBackBuffer(Lwjgl3FramebufferCapture.Limits.defaults()));

        assertEquals(fixture.backBufferWidth(), image.width());
        assertEquals(fixture.backBufferHeight(), image.height());
        // The fixture's red actor owns the top-left quadrant: a flipped or offset readback cannot
        // produce red at (8, 8).
        assertEquals(RED, decode(image).getRGB(8, 8));
    }

    @Test
    void capturesOneBottomLeftOriginRegion() throws IOException {
        fixture.configureQuadrants(WINDOW_SIZE);
        int size = fixture.backBufferWidth();
        Lwjgl3FramebufferCapture.Image image = fixture.onApplicationThread(() ->
                Lwjgl3FramebufferCapture.captureRegion(
                        0, size / 2, size / 2, size / 2, Lwjgl3FramebufferCapture.Limits.defaults()));

        assertEquals(size / 2, image.width());
        assertEquals(size / 2, image.height());
        assertEquals(RED, decode(image).getRGB(4, 4));
    }

    @Test
    void hashesExactlyTheEncodedBytes() throws NoSuchAlgorithmException {
        Lwjgl3FramebufferCapture.Image image = fixture.onApplicationThread(() ->
                Lwjgl3FramebufferCapture.captureBackBuffer(Lwjgl3FramebufferCapture.Limits.defaults()));

        byte[] digest = MessageDigest.getInstance("SHA-256").digest(image.pngBytes());
        assertEquals(HexFormat.of().formatHex(digest), image.sha256());
    }

    @Test
    void refusesRegionsAboveThePixelCeiling() {
        Lwjgl3FramebufferCapture.Limits limits = new Lwjgl3FramebufferCapture.Limits(16, 4096);

        HarnessException failure = assertThrows(HarnessException.class, () -> fixture.onApplicationThread(
                () -> Lwjgl3FramebufferCapture.captureBackBuffer(limits)));

        assertEquals(ErrorCode.LIMIT_EXCEEDED, failure.code());
    }

    private static BufferedImage decode(Lwjgl3FramebufferCapture.Image image) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(image.pngBytes()));
    }
}
