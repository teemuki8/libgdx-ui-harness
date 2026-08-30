package dev.gdx.uiharness.scene2d;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.BitmapFont.BitmapFontData;
import com.badlogic.gdx.graphics.g2d.BitmapFont.Glyph;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Label.LabelStyle;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import dev.gdx.uiharness.core.layout.LayoutValidationEvidence;
import dev.gdx.uiharness.core.typography.Availability;
import dev.gdx.uiharness.core.typography.CoordinateSpace;
import dev.gdx.uiharness.core.typography.EvidenceValue;
import dev.gdx.uiharness.core.typography.TypographyObservation;
import dev.gdx.uiharness.core.typography.UnavailableReason;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class Scene2dTypographyExtractorTest {
    @Test
    void attributesBitmapFontAndFullTransformChainToStableControl() {
        Stage stage = Scene2dTestSupport.stage();
        Group parent = new Group();
        parent.setPosition(10, 20);
        parent.setScale(2, 3);
        Label title = label("AAA");
        title.setBounds(5.25f, 7.5f, 200, 60);
        title.setFontScale(2.8f);
        parent.addActor(title);
        stage.addActor(parent);
        try (Scene2dSession session = new Scene2dSession(stage)) {
            session.semantics().setTestId(title, "title");
            session.semantics().setTypography(
                    title,
                    new TypographyMetadata(
                            "classpath:reference-ui/lsans-15.fnt",
                            List.of("classpath:reference-ui/lsans-15.png"),
                            15,
                            15,
                            EvidenceValue.unavailable(
                                    UnavailableReason.UNSUPPORTED,
                                    "BitmapFont does not expose weight"),
                            EvidenceValue.unavailable(
                                    UnavailableReason.UNSUPPORTED,
                                    "BitmapFont does not expose letter spacing"),
                            EvidenceValue.unavailable(
                                    UnavailableReason.UNSUPPORTED,
                                    "bitmap font has no distance-field smoothing")));

            List<TypographyObservation> observations = session.typography(
                    4,
                    9,
                    new TypographyCaptureContext(
                            "fixture-app",
                            "initial-800x600",
                            "current-title",
                            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                            800,
                            600,
                            800,
                            600,
                            Map.of("title", 0.25)));
            LayoutValidationEvidence layout = session.textLayoutEvidence(
                    session.snapshot(4, 9));

            TypographyObservation observed = observations.getFirst();
            assertEquals("title", observed.controlId());
            assertEquals("AAA", observed.text());
            assertEquals(2.8, observed.font().bitmapScaleX(), 1e-6);
            assertEquals(42, observed.font().effectiveSizeX(), 1e-5);
            assertEquals(Availability.UNAVAILABLE, observed.font().weight().availability());
            assertEquals(CoordinateSpace.LOCAL,
                    observed.transforms().localToParent().source());
            assertEquals(CoordinateSpace.FRAMEBUFFER,
                    observed.transforms().screenToFramebuffer().target());
            assertEquals(2, observed.transforms().parentToStage().effectiveScaleX(), 1e-6);
            assertEquals(3, observed.transforms().parentToStage().effectiveScaleY(), 1e-6);
            assertEquals(0.5,
                    observed.transforms().localToParent().fractionalTranslationY(), 1e-6);
            assertEquals(0.25, observed.rasterResidual(), 1e-9);
            assertFalse(observed.geometry()
                    .inkBounds(CoordinateSpace.FRAMEBUFFER)
                    .width() == 0);
            assertTrue(observed.transforms().invertible());
            var textLayout = layout.textByNodeId().get(observed.actorId());
            assertEquals(observed.actorId(), textLayout.nodeId());
            var typographyLayout =
                    observed.geometry().layoutBounds(CoordinateSpace.STAGE);
            assertEquals(typographyLayout.x(), textLayout.layoutStageBounds().x(), 1e-6);
            assertEquals(typographyLayout.y(), textLayout.layoutStageBounds().y(), 1e-6);
            assertEquals(
                    typographyLayout.width(), textLayout.layoutStageBounds().width(), 1e-6);
            assertEquals(
                    typographyLayout.height(), textLayout.layoutStageBounds().height(), 1e-6);
            var typographyInk = observed.geometry().inkBounds(CoordinateSpace.STAGE);
            assertEquals(typographyInk.x(), textLayout.inkStageBounds().x(), 1e-6);
            assertEquals(typographyInk.y(), textLayout.inkStageBounds().y(), 1e-6);
            assertEquals(typographyInk.width(), textLayout.inkStageBounds().width(), 1e-6);
            assertEquals(typographyInk.height(), textLayout.inkStageBounds().height(), 1e-6);
        }
    }

    @Test
    void missingRegisteredProvenanceRemainsExplicitlyUnavailable() {
        Stage stage = Scene2dTestSupport.stage();
        Label title = label("AAA");
        title.setBounds(10, 20, 200, 60);
        stage.addActor(title);
        try (Scene2dSession session = new Scene2dSession(stage)) {
            session.semantics().setTestId(title, "title");

            TypographyObservation observed = session.typography(
                    1,
                    2,
                    new TypographyCaptureContext(
                            "fixture-app",
                            "initial-800x600",
                            "current-title",
                            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                            800,
                            600,
                            800,
                            600,
                            Map.of("title", 0.0)))
                    .getFirst();

            assertEquals(Availability.UNAVAILABLE, observed.font().sourceId().availability());
            assertEquals(
                    UnavailableReason.NOT_REGISTERED,
                    observed.font().sourceId().unavailableReason());
        }
    }

    @Test
    void captureContextSelectsTheBoundedControlSet() {
        Stage stage = Scene2dTestSupport.stage();
        Label selected = label("AAA");
        Label unrelated = label("AAA");
        unrelated.setPosition(0, 40);
        stage.addActor(selected);
        stage.addActor(unrelated);
        try (Scene2dSession session = new Scene2dSession(stage)) {
            session.semantics().setTestId(selected, "selected-title");
            session.semantics().setTestId(unrelated, "benchmark-status");

            List<TypographyObservation> observed = session.typography(
                    1,
                    2,
                    new TypographyCaptureContext(
                            "fixture-app",
                            "initial-800x600",
                            "current-title",
                            "a".repeat(64),
                            800,
                            600,
                            800,
                            600,
                            Map.of("selected-title", 0.0)));

            assertEquals(List.of("selected-title"),
                    observed.stream().map(TypographyObservation::controlId).toList());
        }
    }

    @Test
    void aLikeTitleRetainsIdentityAndMappingsAcrossFiveFramesAtBothViewports() {
        Scene2dTestSupport.stage().dispose();
        ScreenViewport screenViewport = new ScreenViewport();
        screenViewport.update(800, 600, true);
        Stage stage = new Stage(screenViewport, new NoopBatch());
        Label title = label("SKIRMISH");
        title.setBounds(40, 500, 480, 90);
        title.setFontScale(2.8f);
        stage.addActor(title);
        try (Scene2dSession session = new Scene2dSession(stage)) {
            session.semantics().setTestId(title, "a-like-title");
            session.semantics().setTypography(title, metadata());

            for (int[] viewport : List.of(
                    new int[] {1920, 1080}, new int[] {1280, 720})) {
                stage.getViewport().update(viewport[0], viewport[1], true);
                String viewportId = "initial-" + viewport[0] + "x" + viewport[1];
                String actorId = null;
                String transform = null;
                List<?> origins = null;
                for (int repeat = 0; repeat < 5; repeat++) {
                    TypographyObservation observed = session.typography(
                            7 + repeat,
                            11 + repeat,
                            new TypographyCaptureContext(
                                    "fixture-app",
                                    viewportId,
                                    "current-" + repeat,
                                    "a".repeat(64),
                                    viewport[0],
                                    viewport[1],
                                    viewport[0],
                                    viewport[1],
                                    Map.of("a-like-title", 0.0)))
                            .getFirst();

                    assertEquals(viewportId, observed.display().viewportId());
                    assertEquals(viewport[0], observed.display().logicalViewportWidth());
                    assertEquals(viewport[1], observed.display().logicalViewportHeight());
                    assertEquals(1, observed.display().deviceScaleX());
                    assertEquals(1, observed.display().deviceScaleY());
                    assertEquals(2.8, observed.font().bitmapScaleX(), 1e-6);
                    if (actorId == null) {
                        actorId = observed.actorId();
                        transform = observed.transformSha256();
                        origins = observed.geometry().origins();
                    } else {
                        assertEquals(actorId, observed.actorId());
                        assertEquals(transform, observed.transformSha256());
                        assertEquals(origins, observed.geometry().origins());
                    }
                }
            }
        }
    }

    private static Label label(String text) {
        BitmapFontData data = new BitmapFontData();
        data.name = "fixture-font";
        data.lineHeight = 15;
        data.capHeight = 10;
        data.xHeight = 7;
        data.ascent = 0;
        data.descent = -3;
        data.down = -15;
        data.spaceXadvance = 4;
        Glyph glyph = new Glyph();
        glyph.id = 'A';
        glyph.width = 8;
        glyph.height = 10;
        glyph.xadvance = 9;
        data.setGlyph('A', glyph);
        Texture texture = new Texture(64, 64, Pixmap.Format.RGBA8888);
        texture.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        BitmapFont font = new BitmapFont(data, new TextureRegion(texture), false);
        font.getData().setScale(1);
        Label label = new Label(text, new LabelStyle(font, Color.WHITE));
        label.setAlignment(com.badlogic.gdx.utils.Align.left);
        return label;
    }

    private static TypographyMetadata metadata() {
        return new TypographyMetadata(
                "classpath:reference-ui/lsans-15.fnt",
                List.of("classpath:reference-ui/lsans-15.png"),
                15,
                15,
                EvidenceValue.unavailable(
                        UnavailableReason.UNSUPPORTED,
                        "BitmapFont does not expose weight"),
                EvidenceValue.unavailable(
                        UnavailableReason.UNSUPPORTED,
                        "BitmapFont does not expose letter spacing"),
                EvidenceValue.unavailable(
                        UnavailableReason.UNSUPPORTED,
                        "bitmap font has no distance-field smoothing"));
    }
}
