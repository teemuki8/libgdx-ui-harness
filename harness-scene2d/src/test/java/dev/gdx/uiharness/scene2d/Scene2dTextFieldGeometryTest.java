package dev.gdx.uiharness.scene2d;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.BitmapFontCache;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.ui.TextArea;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.BaseDrawable;
import com.badlogic.gdx.utils.Align;
import dev.gdx.uiharness.core.model.Bounds;
import java.lang.invoke.MethodHandles;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

final class Scene2dTextFieldGeometryTest {
    @Test void observedInkMatchesActualDrawAcrossFontAndScrollStates() {
        for (boolean flipped : new boolean[] {false, true}) {
            for (boolean integer : new boolean[] {false, true}) {
                try (Fixture fixture = new Fixture(flipped, integer)) {
                    for (int state = 0; state < 3; state++) {
                        fixture.stage.setKeyboardFocus(state == 0 ? null : fixture.field);
                        fixture.field.setDisabled(state == 2);
                        for (int alignment : List.of(Align.left, Align.center, Align.right)) {
                            fixture.field.setAlignment(alignment);
                            for (int cursor : List.of(0, 12, fixture.field.getText().length(), 3)) {
                                fixture.field.setCursorPosition(cursor);
                                fixture.compareObservationAndDraw("flipped=" + flipped
                                        + ", integer=" + integer + ", state=" + state
                                        + ", alignment=" + alignment + ", cursor=" + cursor);
                            }
                        }
                    }
                }
            }
        }
    }

    @Test void captureLeavesFontCacheCursorSelectionAndScrollUntouched() throws Exception {
        try (Fixture fixture = new Fixture(false, true)) {
            fixture.stage.setKeyboardFocus(fixture.field);
            fixture.field.setCursorPosition(fixture.field.getText().length());
            fixture.field.draw(fixture.stage.getBatch(), 1);
            fixture.field.setSelection(1, 5);
            fixture.font.getCache().setText("sentinel", 7, 19);
            float[] cacheBefore = Arrays.copyOf(fixture.font.getCache().getVertices(),
                    fixture.font.getCache().getVertexCount(0));
            Color colorBefore = new Color(fixture.font.getColor());
            List<Object> stateBefore = state(fixture.field);
            fixture.background.draws = 0;
            fixture.focusedBackground.draws = 0;

            for (int repeat = 0; repeat < 3; repeat++) {
                assertTrue(Scene2dTextFieldGeometry.placement(fixture.field).isPresent());
                assertEquals(stateBefore, state(fixture.field));
                assertArrayEquals(cacheBefore, Arrays.copyOf(fixture.font.getCache().getVertices(),
                        fixture.font.getCache().getVertexCount(0)));
                assertEquals(colorBefore, fixture.font.getColor());
                assertTrue(fixture.font.getData().markupEnabled);
                assertEquals(0, fixture.background.draws);
                assertEquals(0, fixture.focusedBackground.draws);
            }
        }
    }

    @Test void independentlyConfiguredCacheSnappingMatchesActualDraw() {
        try (Fixture fixture = new Fixture(false, false)) {
            fixture.font.getCache().setUseIntegerPositions(true);
            fixture.compareObservationAndDraw("cache snaps while text baseline does not");
        }
    }

    @Test void fractionalParentTranslationIsIncludedBeforeGlyphSnapping() {
        try (Fixture fixture = new Fixture(false, true)) {
            Group outer = new Group();
            outer.setTransform(false);
            outer.setPosition(33.25f, 11.5f);
            fixture.stage.addActor(outer);
            Group inner = new Group();
            inner.setTransform(false);
            inner.setPosition(17.5f, 9.25f);
            outer.addActor(inner);
            inner.addActor(fixture.field);
            fixture.compareObservationAndDraw("translated parents");
            fixture.field.setText("AB[RED]CD[]");
            fixture.field.setWidth(320);
            fixture.compareObservationAndDraw("literal markup in a fitted field");
        }
    }

    @Test void unknownFieldsMultilineAndOversizedTextRemainUnavailable() {
        try (Fixture fixture = new Fixture(false, false)) {
            assertTrue(Scene2dTextFieldGeometry.placement(new TextArea(
                    "AB", fixture.field.getStyle())).isEmpty());
            TextField custom = new TextField("AB", fixture.field.getStyle()) {
                @Override public void draw(Batch batch, float alpha) {
                    throw new AssertionError("capture must not draw a custom field");
                }
            };
            assertTrue(Scene2dTextFieldGeometry.placement(custom).isEmpty());
            fixture.field.setText("A".repeat(4097));
            assertTrue(Scene2dTextFieldGeometry.placement(fixture.field).isEmpty());
        }
    }

    @Test void invalidMetricsAndUnknownActorTransformsAreUnavailable() {
        try (Fixture fixture = new Fixture(false, false)) {
            fixture.background.setLeftWidth(Float.NaN);
            assertTrue(Scene2dTextFieldGeometry.placement(fixture.field).isEmpty());
            fixture.background.setLeftWidth(2.5f);
            fixture.font.getData().ascent = Float.POSITIVE_INFINITY;
            assertTrue(Scene2dTextFieldGeometry.placement(fixture.field).isEmpty());
            fixture.font.getData().ascent = 5;
            fixture.field.setRotation(20);
            assertTrue(Scene2dTextFieldGeometry.placement(fixture.field).isEmpty());
        }
    }

    private static List<Object> state(TextField field) throws Exception {
        var lookup = MethodHandles.privateLookupIn(TextField.class, MethodHandles.lookup());
        return List.of(field.getCursorPosition(), field.getSelectionStart(), field.getSelection(),
                lookup.findVarHandle(TextField.class, "renderOffset", float.class).get(field),
                lookup.findVarHandle(TextField.class, "textOffset", float.class).get(field),
                lookup.findVarHandle(TextField.class, "visibleTextStart", int.class).get(field),
                lookup.findVarHandle(TextField.class, "visibleTextEnd", int.class).get(field),
                lookup.findVarHandle(TextField.class, "focused", boolean.class).get(field),
                lookup.findVarHandle(TextField.class, "cursorOn", boolean.class).get(field));
    }

    private static Bounds cachedInk(BitmapFontCache cache) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (int page = 0; page < cache.getPageCount(); page++) {
            float[] vertices = cache.getVertices(page);
            for (int index = 0; index < cache.getVertexCount(page); index += 5) {
                minX = Math.min(minX, vertices[index]);
                minY = Math.min(minY, vertices[index + 1]);
                maxX = Math.max(maxX, vertices[index]);
                maxY = Math.max(maxY, vertices[index + 1]);
            }
        }
        return new Bounds(minX, minY, maxX - minX, maxY - minY);
    }

    private static final class Fixture implements AutoCloseable {
        private final Stage stage = Scene2dTestSupport.stage();
        private final Texture texture = new Texture(64, 64, Pixmap.Format.RGBA8888);
        private final BitmapFont font;
        private final Scene2dSession session = new Scene2dSession(stage);
        private final TextField field;
        private final CountingDrawable background = new CountingDrawable(2.5f, 4.25f, 3.5f, 1.25f);
        private final CountingDrawable focusedBackground = new CountingDrawable(7.75f, 8.5f, 1, 5);

        Fixture(boolean flipped, boolean integer) {
            var data = new BitmapFont.BitmapFontData();
            data.capHeight = 10;
            data.lineHeight = 15;
            data.ascent = flipped ? -4 : 4;
            data.descent = -3;
            data.down = flipped ? 15 : -15;
            data.flipped = flipped;
            data.spaceXadvance = 8;
            data.markupEnabled = true;
            for (char character = 32; character <= 126; character++) {
                var glyph = new BitmapFont.Glyph();
                glyph.id = character;
                glyph.width = character == ' ' ? 0 : 7;
                glyph.height = character == ' ' ? 0 : 10;
                glyph.xadvance = 8;
                glyph.xoffset = 1;
                glyph.yoffset = flipped ? 0 : -10;
                data.setGlyph(character, glyph);
            }
            font = new BitmapFont(data, new TextureRegion(texture), false);
            font.getData().setScale(1.15f, 1.25f);
            font.setUseIntegerPositions(integer);
            var style = new TextField.TextFieldStyle(font, Color.WHITE, null, null, background);
            style.focusedBackground = focusedBackground;
            style.disabledBackground = new CountingDrawable(10.5f, 3, 5, 2);
            field = new TextField("AB[RED]CD[]abcd 1234567890", style);
            field.setBounds(20.25f, 40.125f, 88.25f, 46.5f);
            stage.addActor(field);
            session.semantics().setTestId(field, "field");
        }

        void compareObservationAndDraw(String scenario) {
            var snapshot = session.snapshot(1, 1);
            String id = snapshot.nodes().values().stream()
                    .filter(node -> "field".equals(node.testId())).findFirst().orElseThrow().id();
            var observation = session.textLayoutEvidence(snapshot).textByNodeId().get(id);
            assertFalse(observation == null, scenario + ": missing geometry");
            stage.draw();
            Bounds actual = cachedInk(font.getCache());
            Bounds observed = observation.inkStageBounds();
            assertEquals(actual.x(), observed.x(), 0.0001, scenario + ": x");
            assertEquals(actual.y(), observed.y(), 0.0001, scenario + ": y");
            assertEquals(actual.width(), observed.width(), 0.0001, scenario + ": width");
            assertEquals(actual.height(), observed.height(), 0.0001, scenario + ": height");
        }

        @Override public void close() {
            stage.setKeyboardFocus(null);
            field.draw(stage.getBatch(), 1);
            session.close();
            stage.dispose();
            font.dispose();
            texture.dispose();
        }
    }

    private static final class CountingDrawable extends BaseDrawable {
        private int draws;

        CountingDrawable(float left, float right, float top, float bottom) {
            setLeftWidth(left);
            setRightWidth(right);
            setTopHeight(top);
            setBottomHeight(bottom);
        }

        @Override public void draw(Batch batch, float x, float y, float width, float height) {
            draws++;
        }
    }
}
