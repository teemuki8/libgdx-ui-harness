package dev.gdx.uiharness.lwjgl3;

import static dev.gdx.uiharness.lwjgl3.Lwjgl3CaptureFixture.RED;
import static dev.gdx.uiharness.lwjgl3.Lwjgl3CaptureFixture.WINDOW_SIZE;
import static dev.gdx.uiharness.lwjgl3.Lwjgl3CaptureFixture.await;
import static dev.gdx.uiharness.lwjgl3.Lwjgl3CaptureFixture.decode;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.gdx.uiharness.core.capture.CaptureRequest;
import dev.gdx.uiharness.core.capture.CapturedImage;
import dev.gdx.uiharness.core.error.ErrorCode;
import dev.gdx.uiharness.core.error.HarnessException;
import dev.gdx.uiharness.core.locator.Locator;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class ActorCropTest {
    private Lwjgl3CaptureFixture fixture;

    @BeforeAll void startApplication() {
        fixture = new Lwjgl3CaptureFixture();
    }

    @AfterAll void stopApplication() {
        fixture.close();
    }

    @Test void capturesTopLeftOrientedActorCropAfterRenderedFrame() {
        fixture.configureQuadrants(WINDOW_SIZE);

        CapturedImage image = fixture.captureActor("top-left");

        assertEquals(32, image.width());
        assertEquals(32, image.height());
        assertEquals(RED, decode(image).getRGB(0, 0));
    }

    @Test void scalesSemanticScreenBoundsThroughViewportIntoFramebufferPixels() {
        fixture.configureQuadrants(32);

        CapturedImage image = fixture.captureActor("top-left");
        BufferedImage decoded = decode(image);

        assertEquals(32, image.width());
        assertEquals(32, image.height());
        assertEquals(RED, decoded.getRGB(16, 16));
    }

    @Test void resolvesFreshActorBoundsAtTheCapturedFrame() {
        fixture.configureQuadrants(WINDOW_SIZE);
        fixture.setTopLeftBounds(8, 40, 16, 16);

        CapturedImage image = fixture.captureActor("top-left");

        assertEquals(16, image.width());
        assertEquals(16, image.height());
        assertEquals(RED, decode(image).getRGB(8, 8));
    }

    @Test void clipsPartiallyOffscreenBoundsBeforeAllocating() {
        fixture.configureQuadrants(WINDOW_SIZE);
        fixture.setTopLeftBounds(-8, 32, 32, 32);

        CapturedImage image = fixture.captureActor("top-left");

        assertEquals(24, image.width());
        assertEquals(32, image.height());
        assertEquals(RED, decode(image).getRGB(12, 16));
    }

    @Test void rejectsEmptyActorCropWithTypedGeometryEvidence() {
        fixture.configureQuadrants(WINDOW_SIZE);
        fixture.setTopLeftBounds(0, 32, 0, 32);

        HarnessException failure = assertThrows(HarnessException.class,
                () -> fixture.captureActor("top-left"));

        assertEquals(ErrorCode.CAPTURE_FAILURE, failure.code());
        assertEquals("empty", failure.evidence().details().get("geometry"));
    }

    @Test void rejectsFullyOffscreenActorCropWithTypedGeometryEvidence() {
        fixture.configureQuadrants(WINDOW_SIZE);
        fixture.setTopLeftBounds(-40, 32, 32, 32);

        HarnessException failure = assertThrows(HarnessException.class,
                () -> fixture.captureActor("top-left"));

        assertEquals(ErrorCode.CAPTURE_FAILURE, failure.code());
        assertEquals("offscreen", failure.evidence().details().get("geometry"));
    }

    @Test void rejectsCropDimensionsAndPixelsBeforeReadbackAllocation() {
        fixture.configureQuadrants(WINDOW_SIZE);
        CaptureRequest request = CaptureRequest.actor(Locator.testId("top-left"))
                .withLimits(new CaptureRequest.Limits(16, 64, 128, 1_000_000));

        HarnessException failure = assertThrows(HarnessException.class,
                () -> await(fixture.capture().capture(request, fixture.deadline())));

        assertEquals(ErrorCode.LIMIT_EXCEEDED, failure.code());
        assertEquals("width", failure.evidence().details().get("dimension"));
    }
}
