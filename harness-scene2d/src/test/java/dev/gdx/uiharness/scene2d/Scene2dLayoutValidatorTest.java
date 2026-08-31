package dev.gdx.uiharness.scene2d;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.BitmapFont.BitmapFontData;
import com.badlogic.gdx.graphics.g2d.BitmapFont.Glyph;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFontCache;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Label.LabelStyle;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane.ScrollPaneStyle;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.BaseDrawable;
import com.badlogic.gdx.utils.Align;
import dev.gdx.uiharness.core.error.ErrorCode;
import dev.gdx.uiharness.core.error.HarnessException;
import dev.gdx.uiharness.core.layout.LayoutValidationCheck;
import dev.gdx.uiharness.core.layout.LayoutValidationConfig;
import dev.gdx.uiharness.core.layout.LayoutValidationReason;
import dev.gdx.uiharness.core.layout.LayoutValidationResult;
import dev.gdx.uiharness.core.layout.LayoutValidationSeverity;
import dev.gdx.uiharness.core.locator.Locator;
import dev.gdx.uiharness.core.locator.StrictResolution;
import dev.gdx.uiharness.core.model.Bounds;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import org.junit.jupiter.api.Test;

final class Scene2dLayoutValidatorTest {
    @Test void fullStageValidationCapturesOneAtomicObservation() {
        try (Fixture fixture = new Fixture()) {
            fixture.button("good", "Good", 100, 100);
            TextButton zero = fixture.button("zero", "Zero", 300, 100);
            zero.setBounds(300, 100, 0, 0);
            SemanticSnapshot snapshot =
                    fixture.session.snapshot(fixture.clock.revision(), fixture.clock.frame());

            LayoutValidationResult result = fixture.validate(snapshot, null);

            String zeroNodeId = snapshot.nodes().values().stream()
                    .filter(node -> "zero".equals(node.testId()))
                    .findFirst().orElseThrow().id();
            assertTrue(result.findings().stream()
                    .anyMatch(finding -> finding.reason() == LayoutValidationReason.ZERO_SIZE
                            && finding.nodeId().equals(zeroNodeId)));
        }
    }

    @Test void subtreeModeValidatesOnlyTheStrictlyResolvedSubtree() {
        try (Fixture fixture = new Fixture()) {
            TextButton outside = fixture.button("outside", "Outside", 100, 100);
            outside.setBounds(100, 100, 0, 0);
            fixture.button("inside", "Inside", 300, 100);
            SemanticSnapshot snapshot =
                    fixture.session.snapshot(fixture.clock.revision(), fixture.clock.frame());
            String outsideNodeId = snapshot.nodes().values().stream()
                    .filter(node -> "outside".equals(node.testId()))
                    .findFirst().orElseThrow().id();

            LayoutValidationResult full = fixture.validate(snapshot, null);
            assertTrue(full.findings().stream()
                    .anyMatch(finding -> finding.reason() == LayoutValidationReason.ZERO_SIZE
                            && finding.nodeId().equals(outsideNodeId)));

            LayoutValidationResult subtree = fixture.validate(
                    snapshot, Locator.testId("inside"));
            assertFalse(subtree.findings().stream()
                    .anyMatch(finding -> finding.reason() == LayoutValidationReason.ZERO_SIZE
                            && finding.nodeId().equals(outsideNodeId)),
                    "subtree validation must not report nodes outside the subtree");
        }
    }

    @Test void subtreeResolutionStaysStrictWithDistinctZeroAndMultipleErrors() {
        try (Fixture fixture = new Fixture()) {
            HarnessException missing = assertThrows(HarnessException.class,
                    () -> fixture.validate(
                            fixture.session.snapshot(
                                    fixture.clock.revision(), fixture.clock.frame()),
                            Locator.testId("absent")));
            assertEquals(ErrorCode.NOT_FOUND, missing.code());

            fixture.button("dup", "First", 100, 100);
            fixture.button("dup", "Second", 300, 100);
            HarnessException multiple = assertThrows(HarnessException.class,
                    () -> fixture.validate(
                            fixture.session.snapshot(
                                    fixture.clock.revision(), fixture.clock.frame()),
                            Locator.testId("dup")));
            assertEquals(ErrorCode.STRICTNESS_VIOLATION, multiple.code());
        }
    }

    @Test void repeatedValidationIsDeterministic() {
        try (Fixture fixture = new Fixture()) {
            fixture.button("missing", "No test id", 100, 100);
            SemanticSnapshot snapshot =
                    fixture.session.snapshot(fixture.clock.revision(), fixture.clock.frame());
            LayoutValidationResult first = fixture.validate(snapshot, null);
            LayoutValidationResult second = fixture.validate(snapshot, null);
            assertEquals(first.findings(), second.findings());
        }
    }

    @Test void realGlyphInkDetectsRagduelGameOverCollision() {
        try (Fixture fixture = new Fixture()) {
            fixture.viewport(960, 540);
            fixture.label("screen-value", "GAME_OVER", 466, 502, 82, 20);
            fixture.label("player-two-health-label", "P2 HEALTH:", 554, 502, 94, 20);
            LayoutValidationConfig config = LayoutValidationConfig.builder()
                    .enable(LayoutValidationCheck.CLIPPED_TEXT)
                    .enable(LayoutValidationCheck.TEXT_COLLISION)
                    .failOn(LayoutValidationSeverity.ERROR)
                    .build();

            LayoutValidationResult result = fixture.validator.validate(
                    fixture.clock.revision(), fixture.clock.frame(), null, config, null);

            assertEquals(LayoutValidationResult.Status.FAIL, result.status());
            assertTrue(result.findings().stream()
                    .anyMatch(finding ->
                            finding.reason() == LayoutValidationReason.CLIPPED_TEXT));
            assertTrue(result.findings().stream()
                    .anyMatch(finding ->
                            finding.reason() == LayoutValidationReason.TEXT_COLLISION));
        }
    }

    @Test void realGlyphInkDetectsLongTwoPlayerControlOverflow() {
        try (Fixture fixture = new Fixture()) {
            fixture.viewport(960, 540);
            fixture.label("player-one-controls",
                    "P1  A/D move | S crouch | F/G punch | H weapon | R block",
                    33, 463, 430, 24);
            fixture.label("player-two-controls",
                    "P2  Arrows move | Down crouch | K/L | Semicolon weapon | O block",
                    480, 463, 430, 24);
            LayoutValidationConfig config = LayoutValidationConfig.builder()
                    .enable(LayoutValidationCheck.CLIPPED_TEXT)
                    .enable(LayoutValidationCheck.TEXT_COLLISION)
                    .failOn(LayoutValidationSeverity.ERROR)
                    .build();

            LayoutValidationResult result = fixture.validator.validate(
                    fixture.clock.revision(), fixture.clock.frame(), null, config, null);

            assertEquals(LayoutValidationResult.Status.FAIL, result.status());
            assertTrue(result.findings().stream()
                    .anyMatch(finding ->
                            finding.reason() == LayoutValidationReason.CLIPPED_TEXT));
            assertTrue(result.findings().stream()
                    .anyMatch(finding ->
                            finding.reason() == LayoutValidationReason.TEXT_COLLISION));
        }
    }

    @Test void fittingRealLabelPassesIntrinsicTextValidation() {
        try (Fixture fixture = new Fixture()) {
            fixture.viewport(960, 540);
            fixture.label("fitting", "OK", 20, 20, 100, 32);
            LayoutValidationConfig config = only(
                    LayoutValidationCheck.CLIPPED_TEXT,
                    LayoutValidationCheck.TEXT_COLLISION);

            LayoutValidationResult result = fixture.validator.validate(
                    fixture.clock.revision(),
                    fixture.clock.frame(),
                    Locator.testId("fitting"),
                    config,
                    null);

            assertEquals(
                    LayoutValidationResult.Status.PASS,
                    result.status(),
                    result.findings().toString());
        }
    }

    @Test void validationNeverInvokesLabelOrBackgroundDraw() {
        try (Fixture fixture = new Fixture()) {
            DrawRejectingDrawable background = new DrawRejectingDrawable();
            DrawRejectingLabel label = new DrawRejectingLabel(
                    "AA", new LabelStyle(fixture.font, Color.WHITE));
            label.getStyle().background = background;
            label.setBounds(20, 20, 100, 30);
            fixture.stage.addActor(label);
            fixture.session.semantics().setTestId(label, "no-draw");

            fixture.validator.validate(
                    fixture.clock.revision(),
                    fixture.clock.frame(),
                    null,
                    only(LayoutValidationCheck.CLIPPED_TEXT),
                    null);

            assertFalse(label.drawInvoked);
            assertFalse(background.drawInvoked);
        }
    }

    @Test void fittingOneLineWrapWithAmbiguousBlockPlacementIsHardUnavailable() {
        try (Fixture fixture = new Fixture()) {
            Label label = fixture.label("ambiguous-wrap", "AA", 100, 40, 50, 20);
            label.setWrap(true);
            label.setAlignment(Align.center, Align.left);

            LayoutValidationResult result = fixture.validator.validate(
                    fixture.clock.revision(),
                    fixture.clock.frame(),
                    null,
                    only(LayoutValidationCheck.CLIPPED_TEXT),
                    null);

            assertEquals(LayoutValidationResult.Status.FAIL, result.status());
            assertTrue(result.findings().stream().anyMatch(finding ->
                    finding.reason() == LayoutValidationReason.CHECK_UNAVAILABLE
                            && finding.severity() == LayoutValidationSeverity.ERROR));
        }
    }

    @Test void exactPlacementPreservesHalfUnitOrigin() {
        try (Fixture fixture = new Fixture()) {
            BaseDrawable inset = drawable(0, 0, 0.5f, 0, 0, 0);
            Label label = fixture.label("half-unit", "AA", 10, 20, 100, 20);
            label.getStyle().background = inset;

            SemanticSnapshot snapshot =
                    fixture.session.snapshot(fixture.clock.revision(), fixture.clock.frame());
            var evidence = fixture.session.textLayoutEvidence(snapshot);
            var node = snapshot.nodes().values().stream()
                    .filter(value -> "half-unit".equals(value.testId()))
                    .findFirst().orElseThrow();

            assertTrue(evidence.textGeometryAvailable());
            assertEquals(
                    10.5,
                    evidence.textByNodeId().get(node.id()).layoutStageBounds().x(),
                    1e-6);
        }
    }

    @Test void exactPlacementPlacesZeroGlyphAtConceptualOrigin() {
        try (Fixture fixture = new Fixture()) {
            fixture.label("zero-glyph", "\u0001", 200, 30, 100, 20);

            SemanticSnapshot snapshot =
                    fixture.session.snapshot(fixture.clock.revision(), fixture.clock.frame());
            var evidence = fixture.session.textLayoutEvidence(snapshot);
            var node = snapshot.nodes().values().stream()
                    .filter(value -> "zero-glyph".equals(value.testId()))
                    .findFirst().orElseThrow();
            Bounds ink = evidence.textByNodeId().get(node.id()).inkStageBounds();

            assertTrue(evidence.textGeometryAvailable());
            assertEquals(200, ink.x(), 1e-6);
            assertEquals(45, ink.y(), 1e-6);
            assertEquals(0, ink.width(), 1e-6);
            assertEquals(0, ink.height(), 1e-6);
        }
    }

    @Test void subtreeModeExcludesExternalIntrinsicTextEvidence() {
        try (Fixture fixture = new Fixture()) {
            fixture.viewport(960, 540);
            fixture.label("outside-label", "OUT", 20, 20, 100, 30);
            fixture.label("inside-label", "IN", 20, 20, 100, 30);
            LayoutValidationConfig config = only(LayoutValidationCheck.TEXT_COLLISION);
            LayoutValidationResult full = fixture.validator.validate(
                    fixture.clock.revision(), fixture.clock.frame(), null, config, null);
            LayoutValidationResult subtree = fixture.validator.validate(
                    fixture.clock.revision(),
                    fixture.clock.frame(),
                    Locator.testId("inside-label"),
                    config,
                    null);

            assertEquals(LayoutValidationResult.Status.FAIL, full.status());
            assertEquals(
                    LayoutValidationResult.Status.PASS,
                    subtree.status(),
                    subtree.findings().toString());
        }
    }

    @Test void intrinsicEvidenceHandlesAlignmentWrapEllipsisAndScrollClips() {
        try (Fixture fixture = new Fixture()) {
            fixture.viewport(960, 540);
            Label aligned = fixture.label("aligned", "AA", 10, 40, 100, 20);
            aligned.setAlignment(Align.right);
            Label wrapped = fixture.label("wrapped", "AAAA", 150, 40, 25, 40);
            wrapped.setWrap(true);
            Label ellipsized = fixture.label("ellipsized", "AAAA", 200, 40, 25, 20);
            ellipsized.setEllipsis("...");
            Label scrolled = fixture.label("scrolled", "AAAA", 0, 0, 100, 20);
            ScrollPane pane = new ScrollPane(scrolled, new ScrollPaneStyle());
            pane.setBounds(300, 40, 30, 20);
            stageAddAndValidate(fixture, pane);

            SemanticSnapshot snapshot =
                    fixture.session.snapshot(fixture.clock.revision(), fixture.clock.frame());
            var evidence = fixture.session.textLayoutEvidence(snapshot);
            var byTestId = snapshot.nodes().values().stream()
                    .filter(node -> node.testId() != null)
                    .collect(java.util.stream.Collectors.toMap(
                            node -> node.testId(),
                            node -> evidence.textByNodeId().get(node.id())));

            assertEquals(89, byTestId.get("aligned").inkStageBounds().x(), 1e-6);
            assertTrue(byTestId.get("wrapped").layoutStageBounds().height() > 10);
            assertTrue(byTestId.get("ellipsized").inkStageBounds().width() < 43);
            assertEquals(1, byTestId.get("scrolled").clipChainStageBounds().size());
            assertEquals(
                    30,
                    byTestId.get("scrolled").clipChainStageBounds().getFirst().width(),
                    1e-6);
            evidence.textByNodeId().forEach(
                    (nodeId, text) -> assertEquals(nodeId, text.nodeId()));
        }
    }

    @Test void scrollClipMatchesInsetActorAreaWithVisibleScrollbarTracks() {
        try (Fixture fixture = new Fixture()) {
            fixture.viewport(960, 540);
            Label scrolled = fixture.label(
                    "scrolled-actor-area", "AAAA", 0, 0, 100, 60);
            Group content = new Group();
            content.setSize(100, 60);
            content.addActor(scrolled);
            ScrollPaneStyle style = new ScrollPaneStyle();
            style.background = drawable(0, 0, 3, 5, 11, 7);
            style.hScroll = drawable(0, 6, 0, 0, 0, 0);
            style.hScrollKnob = drawable(4, 4, 0, 0, 0, 0);
            style.vScroll = drawable(9, 0, 0, 0, 0, 0);
            style.vScrollKnob = drawable(7, 4, 0, 0, 0, 0);
            ScrollPane pane = new ScrollPane(content, style);
            pane.setFadeScrollBars(false);
            pane.setScrollbarsOnTop(false);
            pane.setBounds(300, 40, 50, 40);
            stageAddAndValidate(fixture, pane);

            SemanticSnapshot snapshot =
                    fixture.session.snapshot(fixture.clock.revision(), fixture.clock.frame());
            var evidence = fixture.session.textLayoutEvidence(snapshot);
            var node = snapshot.nodes().values().stream()
                    .filter(value -> "scrolled-actor-area".equals(value.testId()))
                    .findFirst()
                    .orElseThrow();

            assertEquals(
                    new dev.gdx.uiharness.core.model.Bounds(303, 53, 33, 16),
                    evidence.textByNodeId()
                            .get(node.id())
                            .clipChainStageBounds()
                            .getFirst());
        }
    }

    @Test void wrapWithEllipsisUsesSingleLineCenteredAndRightPlacement() {
        try (Fixture fixture = new Fixture()) {
            fixture.viewport(960, 540);
            Label centered = fixture.label(
                    "centered-wrap-ellipsis", "AAAAAA", 100, 40, 50, 20);
            centered.setWrap(true);
            centered.setEllipsis("...");
            centered.setAlignment(Align.center);
            Label right = fixture.label(
                    "right-wrap-ellipsis", "AAAAAA", 200, 40, 50, 20);
            right.setWrap(true);
            right.setEllipsis("...");
            right.setAlignment(Align.right);

            SemanticSnapshot snapshot =
                    fixture.session.snapshot(fixture.clock.revision(), fixture.clock.frame());
            var evidence = fixture.session.textLayoutEvidence(snapshot);
            var byTestId = snapshot.nodes().values().stream()
                    .filter(node -> node.testId() != null)
                    .collect(java.util.stream.Collectors.toMap(
                            node -> node.testId(),
                            node -> evidence.textByNodeId().get(node.id())));
            assertEquals(
                    104,
                    byTestId.get("centered-wrap-ellipsis").inkStageBounds().x(),
                    1e-6);
            assertEquals(
                    208,
                    byTestId.get("right-wrap-ellipsis").inkStageBounds().x(),
                    1e-6);
        }
    }
    @Test void wrapEllipsisWithIndependentLineAlignmentIsUnavailable() {
        try (Fixture fixture = new Fixture()) {
            fixture.viewport(960, 540);
            Label truncated = fixture.label(
                    "truncated", "AAAAAA", 100, 40, 50, 20);
            truncated.setWrap(true);
            truncated.setEllipsis("...");
            truncated.setAlignment(Align.center, Align.left);
            Label fitting = fixture.label(
                    "fitting", "AA", 300, 40, 50, 20);
            fitting.setWrap(true);
            fitting.setEllipsis("...");
            fitting.setAlignment(Align.right, Align.left);

            SemanticSnapshot snapshot =
                    fixture.session.snapshot(fixture.clock.revision(), fixture.clock.frame());
            var evidence = fixture.session.textLayoutEvidence(snapshot);
            LayoutValidationResult result = fixture.validator.validate(
                    fixture.clock.revision(),
                    fixture.clock.frame(),
                    null,
                    only(LayoutValidationCheck.CLIPPED_TEXT),
                    null);

            assertFalse(evidence.textGeometryAvailable());
            assertTrue(evidence.textByNodeId().isEmpty());
            assertEquals(LayoutValidationResult.Status.FAIL, result.status());
            assertTrue(result.findings().stream().anyMatch(finding ->
                    finding.reason() == LayoutValidationReason.CHECK_UNAVAILABLE
                            && finding.severity() == LayoutValidationSeverity.ERROR));
        }
    }

    @Test void fullWidthWrappedLinesUseRenderedMultilinePlacement() {
        try (Fixture fixture = new Fixture()) {
            fixture.viewport(960, 540);
            ObservedLabel centered = fixture.label(
                    "centered-full-width-wrap", "AAAA AAAA", 100, 100, 43, 59);
            centered.setWrap(true);
            centered.setAlignment(Align.center, Align.center);
            ObservedLabel right = fixture.label(
                    "right-full-width-wrap", "AAAA AAAA", 200, 100, 43, 59);
            right.setWrap(true);
            right.setAlignment(Align.right, Align.right);

            SemanticSnapshot snapshot =
                    fixture.session.snapshot(fixture.clock.revision(), fixture.clock.frame());
            var evidence = fixture.session.textLayoutEvidence(snapshot);
            var byTestId = snapshot.nodes().values().stream()
                    .filter(node -> node.testId() != null)
                    .collect(java.util.stream.Collectors.toMap(
                            node -> node.testId(),
                            node -> evidence.textByNodeId().get(node.id())));

            assertEquals(2, centered.getGlyphLayout().runs.size);
            assertEquals(43, centered.getGlyphLayout().width, 1e-6);
            assertEquals(0, centered.getGlyphLayout().runs.get(0).x, 1e-6);
            assertEquals(0, centered.getGlyphLayout().runs.get(1).x, 1e-6);
            assertEquals(0, right.getGlyphLayout().runs.get(0).x, 1e-6);
            assertEquals(0, right.getGlyphLayout().runs.get(1).x, 1e-6);
            assertEquals(
                    centered.cachedInkStageBounds(),
                    byTestId.get("centered-full-width-wrap").inkStageBounds());
            assertEquals(
                    117,
                    byTestId.get("centered-full-width-wrap").layoutStageBounds().y(),
                    1e-6);
            assertEquals(
                    right.cachedInkStageBounds(),
                    byTestId.get("right-full-width-wrap").inkStageBounds());
            assertEquals(
                    117,
                    byTestId.get("right-full-width-wrap").layoutStageBounds().y(),
                    1e-6);
        }
    }


    private static LayoutValidationConfig only(LayoutValidationCheck... checks) {
        LayoutValidationConfig.Builder builder = LayoutValidationConfig.builder();
        for (LayoutValidationCheck check : LayoutValidationCheck.values()) {
            builder.disable(check);
        }
        for (LayoutValidationCheck check : checks) {
            builder.enable(check);
        }
        return builder.failOn(LayoutValidationSeverity.ERROR).build();
    }

    private static void stageAddAndValidate(Fixture fixture, ScrollPane pane) {
        fixture.stage.addActor(pane);
        pane.validate();
    }

    private static BaseDrawable drawable(
            float minWidth,
            float minHeight,
            float left,
            float right,
            float top,
            float bottom) {
        BaseDrawable drawable = new BaseDrawable();
        drawable.setMinWidth(minWidth);
        drawable.setMinHeight(minHeight);
        drawable.setLeftWidth(left);
        drawable.setRightWidth(right);
        drawable.setTopHeight(top);
        drawable.setBottomHeight(bottom);
        return drawable;
    }

    private static final class Fixture implements AutoCloseable {
        final Stage stage = Scene2dTestSupport.stage();
        final Texture fontTexture = new Texture(64, 64, Pixmap.Format.RGBA8888);
        final BitmapFont font = printableAsciiFont(fontTexture);
        final ControlledStageClock clock = new ControlledStageClock(stage,
                java.time.Duration.ofMillis(16));
        final Scene2dSession session = new Scene2dSession(stage);
        final Scene2dLayoutValidator validator =
                new Scene2dLayoutValidator(session, new StrictResolution());

        TextButton button(String testId, String label, float x, float y) {
            TextButton button = new TextButton(label, WidgetStyles.textButton());
            button.setBounds(x, y, 160, 40);
            stage.addActor(button);
            if (testId != null) {
                session.semantics().setTestId(button, testId);
            }
            return button;
        }

        ObservedLabel label(
                String testId, String text, float x, float y, float width, float height) {
            ObservedLabel label =
                    new ObservedLabel(text, new LabelStyle(font, Color.WHITE));
            label.setAlignment(Align.left);
            label.setBounds(x, y, width, height);
            stage.addActor(label);
            session.semantics().setTestId(label, testId);
            return label;
        }

        void viewport(int width, int height) {
            stage.getViewport().setWorldSize(width, height);
            stage.getViewport().setScreenBounds(0, 0, width, height);
            stage.getCamera().position.set(width / 2f, height / 2f, 0);
            stage.getCamera().update();
        }

        LayoutValidationResult validate(SemanticSnapshot snapshot, Locator subtree) {
            return validator.validate(
                    snapshot.revision(), snapshot.frame(), subtree,
                    LayoutValidationConfig.defaults(), null);
        }


        private static BitmapFont printableAsciiFont(Texture texture) {
            BitmapFontData data = new BitmapFontData();
            data.name = "printable-ascii-fixture";
            data.lineHeight = 15;
            data.capHeight = 10;
            data.xHeight = 7;
            data.ascent = 0;
            data.descent = -3;
            data.down = -15;
            data.spaceXadvance = 11;
            for (char value = 32; value <= 126; value++) {
                Glyph glyph = new Glyph();
                glyph.id = value;
                glyph.width = 10;
                glyph.height = 10;
                glyph.xadvance = 11;
                data.setGlyph(value, glyph);
            }
            texture.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
            return new BitmapFont(data, new TextureRegion(texture), false);
        }
        @Override public void close() {
            session.close();
            clock.close();
            stage.dispose();
            font.dispose();
            fontTexture.dispose();
        }
    }
    private static final class DrawRejectingLabel extends Label {
        private boolean drawInvoked;

        DrawRejectingLabel(CharSequence text, LabelStyle style) {
            super(text, style);
        }

        @Override public void draw(Batch batch, float parentAlpha) {
            drawInvoked = true;
            super.draw(batch, parentAlpha);
        }
    }

    private static final class DrawRejectingDrawable extends BaseDrawable {
        private boolean drawInvoked;

        @Override public void draw(
                Batch batch, float x, float y, float width, float height) {
            drawInvoked = true;
        }
    }

    private static final class ObservedLabel extends Label {
        ObservedLabel(CharSequence text, LabelStyle style) {
            super(text, style);
        }

        Bounds cachedInkStageBounds() {
            validate();
            BitmapFontCache cache = getBitmapFontCache();
            double minX = Double.POSITIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY;
            double maxY = Double.NEGATIVE_INFINITY;
            for (int page = 0; page < cache.getPageCount(); page++) {
                float[] vertices = cache.getVertices(page);
                for (int index = 0; index < cache.getVertexCount(page); index += 5) {
                    double x = getX() - cache.getX() + vertices[index];
                    double y = getY() - cache.getY() + vertices[index + 1];
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
            return new Bounds(minX, minY, maxX - minX, maxY - minY);
        }
    }

}
