package dev.gdx.uiharness.scene2d;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.BitmapFont.BitmapFontData;
import com.badlogic.gdx.graphics.g2d.BitmapFont.Glyph;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.GlyphLayout.GlyphRun;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.SnapshotArray;
import dev.gdx.uiharness.core.model.Bounds;
import dev.gdx.uiharness.core.model.SemanticNode;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import dev.gdx.uiharness.core.typography.AffineTransformObservation;
import dev.gdx.uiharness.core.typography.CoordinateBounds;
import dev.gdx.uiharness.core.typography.CoordinatePoint;
import dev.gdx.uiharness.core.typography.CoordinateSpace;
import dev.gdx.uiharness.core.typography.DisplayObservation;
import dev.gdx.uiharness.core.typography.EvidenceValue;
import dev.gdx.uiharness.core.typography.FontObservation;
import dev.gdx.uiharness.core.typography.GlyphRunObservation;
import dev.gdx.uiharness.core.typography.TransformChain;
import dev.gdx.uiharness.core.typography.TypographyGeometry;
import dev.gdx.uiharness.core.typography.TypographyObservation;
import dev.gdx.uiharness.core.typography.UnavailableReason;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;

/** Extracts immutable actor-attributed Label rendering evidence on the render thread. */
final class Scene2dTypographyExtractor {
    private final Stage stage;
    private final Semantics semantics;
    private final Scene2dSnapshotter snapshotter;

    Scene2dTypographyExtractor(
            Stage stage, Semantics semantics, Scene2dSnapshotter snapshotter) {
        this.stage = Objects.requireNonNull(stage, "stage");
        this.semantics = Objects.requireNonNull(semantics, "semantics");
        this.snapshotter = Objects.requireNonNull(snapshotter, "snapshotter");
    }

    List<TypographyObservation> extract(
            long revision, long frame, TypographyCaptureContext context) {
        Objects.requireNonNull(context, "context");
        requireGraphics();
        SemanticSnapshot snapshot = snapshotter.snapshot(stage, revision, frame);
        IdentityHashMap<Actor, String> actorIds = actorIds();
        CoordinateMapper coordinates = new CoordinateMapper(stage);
        int windowWidth = context.windowWidth();
        int windowHeight = context.windowHeight();
        double scaleX = context.framebufferWidth() / (double) windowWidth;
        double scaleY = context.framebufferHeight() / (double) windowHeight;
        DisplayObservation display = new DisplayObservation(
                context.applicationId(),
                context.viewportId(),
                windowWidth,
                windowHeight,
                Math.round(stage.getViewport().getWorldWidth()),
                Math.round(stage.getViewport().getWorldHeight()),
                context.framebufferWidth(),
                context.framebufferHeight(),
                scaleX,
                scaleY);
        List<TypographyObservation> result = new ArrayList<>();
        collect(
                stage.getRoot(),
                true,
                snapshot,
                actorIds,
                coordinates,
                context,
                display,
                scaleX,
                scaleY,
                revision,
                frame,
                result);
        return List.copyOf(result);
    }

    private void collect(
            Actor actor,
            boolean ancestorsVisible,
            SemanticSnapshot snapshot,
            IdentityHashMap<Actor, String> actorIds,
            CoordinateMapper coordinates,
            TypographyCaptureContext context,
            DisplayObservation display,
            double scaleX,
            double scaleY,
            long revision,
            long frame,
            List<TypographyObservation> result) {
        boolean visible = ancestorsVisible && actor.isVisible();
        if (visible && actor instanceof Label label) {
            String actorId = actorIds.get(actor);
            SemanticNode node = snapshot.nodes().get(actorId);
            if (node != null
                    && node.testId() != null
                    && context.rasterResiduals().containsKey(node.testId())) {
                result.add(observe(
                        label,
                        actorId,
                        node.testId(),
                        coordinates,
                        context,
                        display,
                        scaleX,
                        scaleY,
                        revision,
                        frame));
            }
        }
        if (actor instanceof Group group) {
            SnapshotArray<Actor> children = group.getChildren();
            for (int index = 0; index < children.size; index++) {
                collect(
                        children.get(index),
                        visible,
                        snapshot,
                        actorIds,
                        coordinates,
                        context,
                        display,
                        scaleX,
                        scaleY,
                        revision,
                        frame,
                        result);
            }
        }
    }

    private TypographyObservation observe(
            Label label,
            String actorId,
            String controlId,
            CoordinateMapper coordinates,
            TypographyCaptureContext context,
            DisplayObservation display,
            double scaleX,
            double scaleY,
            long revision,
            long frame) {
        Double rasterResidual = context.rasterResiduals().get(controlId);
        if (rasterResidual == null) {
            throw new IllegalArgumentException(
                    "missing raster residual for control " + controlId);
        }
        label.validate();
        BitmapFont font = label.getStyle().font;
        TypographyMetadata metadata = semantics.metadata(label).typography();
        FontObservation fontObservation = fontObservation(label, font, metadata);
        TransformChain transforms = new TransformChain(
                coordinates.localToParentTransform(label),
                coordinates.parentToStageTransform(label),
                coordinates.stageToScreenTransform(),
                CoordinateMapper.screenToFramebufferTransform(scaleX, scaleY));
        TextPlacement placement = placement(label, font);
        TypographyGeometry geometry = geometry(
                label, coordinates, placement, transforms, scaleX, scaleY);
        String text = label.getText().toString();
        List<GlyphRunObservation> glyphRuns = List.of(new GlyphRunObservation(
                0,
                text.length(),
                text,
                new CoordinatePoint(
                        CoordinateSpace.LOCAL, placement.originX(), placement.originY()),
                new CoordinatePoint(
                        CoordinateSpace.LOCAL, placement.originX(), placement.baselineY()),
                new CoordinateBounds(
                        CoordinateSpace.LOCAL,
                        placement.inkBounds().x(),
                        placement.inkBounds().y(),
                        placement.inkBounds().width(),
                        placement.inkBounds().height())));
        List<String> mechanisms = mechanisms(fontObservation);
        List<String> hypotheses = fontObservation.bitmapScaleX() == 1
                        && fontObservation.bitmapScaleY() == 1
                ? List.of()
                : List.of("bitmap magnification may alter raster sharpness");
        return new TypographyObservation(
                "typography/v1",
                controlId,
                actorId,
                text,
                0,
                text.length(),
                glyphRuns,
                revision,
                frame,
                context.currentArtifactId(),
                context.captureSha256(),
                transformDigest(transforms),
                fontObservation,
                display,
                transforms,
                geometry,
                rasterResidual,
                mechanisms,
                hypotheses);
    }

    private static FontObservation fontObservation(
            Label label, BitmapFont font, TypographyMetadata metadata) {
        BitmapFontData data = font.getData();
        EvidenceValue<String> sourceId;
        List<String> atlasPageIds;
        EvidenceValue<Double> nominalSize;
        EvidenceValue<Double> generatedSize;
        EvidenceValue<Double> weight;
        EvidenceValue<Double> letterSpacing;
        EvidenceValue<String> distanceField;
        if (metadata != null) {
            sourceId = EvidenceValue.available(metadata.sourceId());
            atlasPageIds = metadata.atlasPageIds();
            nominalSize = EvidenceValue.available(metadata.nominalSize());
            generatedSize = EvidenceValue.available(metadata.generatedGlyphSize());
            weight = metadata.weight();
            letterSpacing = metadata.letterSpacing();
            distanceField = metadata.distanceField();
        } else {
            sourceId = data.fontFile == null
                    ? EvidenceValue.unavailable(
                            UnavailableReason.NOT_REGISTERED,
                            "font source identity was not registered")
                    : EvidenceValue.available(data.fontFile.path());
            atlasPageIds = data.imagePaths == null
                    ? List.of()
                    : List.of(data.imagePaths.clone());
            nominalSize = EvidenceValue.unavailable(
                    UnavailableReason.NOT_REGISTERED,
                    "nominal font size was not registered");
            generatedSize = EvidenceValue.unavailable(
                    UnavailableReason.NOT_REGISTERED,
                    "generated glyph size was not registered");
            weight = EvidenceValue.unavailable(
                    UnavailableReason.NOT_REGISTERED,
                    "font weight was not registered");
            letterSpacing = EvidenceValue.unavailable(
                    UnavailableReason.NOT_REGISTERED,
                    "letter spacing was not registered");
            distanceField = EvidenceValue.unavailable(
                    UnavailableReason.NOT_REGISTERED,
                    "distance-field metadata was not registered");
        }
        EvidenceValue<String> minFilter = filter(font, true);
        EvidenceValue<String> magFilter = filter(font, false);
        double scaleX = label.getFontScaleX();
        double scaleY = label.getFontScaleY();
        double baseLineHeight = font.getLineHeight() / Math.max(font.getScaleY(), 1e-12);
        return new FontObservation(
                sourceId,
                atlasPageIds,
                nominalSize,
                generatedSize,
                baseLineHeight * scaleX,
                baseLineHeight * scaleY,
                scaleX,
                scaleY,
                minFilter,
                magFilter,
                distanceField,
                weight,
                letterSpacing);
    }

    private static EvidenceValue<String> filter(BitmapFont font, boolean minification) {
        String observed = null;
        for (TextureRegion region : font.getRegions()) {
            Texture texture = region.getTexture();
            if (texture == null) {
                return EvidenceValue.unavailable(
                        UnavailableReason.NOT_EXPOSED,
                        "font atlas page has no observable texture");
            }
            String value = (minification
                    ? texture.getMinFilter()
                    : texture.getMagFilter()).name();
            if (observed != null && !observed.equals(value)) {
                return EvidenceValue.unavailable(
                        UnavailableReason.UNKNOWN,
                        "font atlas pages use different filters");
            }
            observed = value;
        }
        return observed == null
                ? EvidenceValue.unavailable(
                        UnavailableReason.MISSING, "font has no atlas pages")
                : EvidenceValue.available(observed);
    }

    private static TextPlacement placement(Label label, BitmapFont font) {
        GlyphLayout layout = label.getGlyphLayout();
        float width = label.getWidth();
        float height = label.getHeight();
        float x = 0;
        float y = 0;
        Drawable background = label.getStyle().background;
        if (background != null) {
            x = background.getLeftWidth();
            y = background.getBottomHeight();
            width -= background.getLeftWidth() + background.getRightWidth();
            height -= background.getBottomHeight() + background.getTopHeight();
        }
        boolean multipleLines = label.getWrap() || label.getText().indexOf("\n") != -1;
        float textWidth = multipleLines ? layout.width : width;
        float scaleY = label.getFontScaleY() / Math.max(font.getScaleY(), 1e-12f);
        float textHeight = multipleLines ? layout.height : font.getCapHeight() * scaleY;
        int alignment = label.getLabelAlign();
        if ((alignment & Align.left) == 0) {
            x += (alignment & Align.right) != 0
                    ? width - textWidth
                    : (width - textWidth) / 2;
        }
        if ((alignment & Align.top) != 0) {
            y += font.isFlipped() ? 0 : height - textHeight;
            y += font.getDescent() * scaleY;
        } else if ((alignment & Align.bottom) != 0) {
            y += font.isFlipped() ? height - textHeight : 0;
            y -= font.getDescent() * scaleY;
        } else {
            y += (height - textHeight) / 2;
        }
        if (!font.isFlipped()) {
            y += textHeight;
        }
        Bounds ink = inkBounds(layout, x, y, label.getFontScaleX(), label.getFontScaleY());
        Bounds layoutBounds = new Bounds(x, y - layout.height, layout.width, layout.height);
        double baseline = layout.runs.isEmpty() ? y : y + layout.runs.first().y;
        return new TextPlacement(x, y, baseline, layoutBounds, ink);
    }

    private static Bounds inkBounds(
            GlyphLayout layout, float originX, float originY, float scaleX, float scaleY) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (GlyphRun run : layout.runs) {
            float glyphX = originX + run.x;
            float glyphY = originY + run.y;
            for (int index = 0; index < run.glyphs.size; index++) {
                glyphX += run.xAdvances.get(index);
                Glyph glyph = run.glyphs.get(index);
                double left = glyphX + glyph.xoffset * scaleX;
                double bottom = glyphY + glyph.yoffset * scaleY;
                double right = left + glyph.width * scaleX;
                double top = bottom + glyph.height * scaleY;
                minX = Math.min(minX, left);
                minY = Math.min(minY, bottom);
                maxX = Math.max(maxX, right);
                maxY = Math.max(maxY, top);
            }
        }
        if (!Double.isFinite(minX)) {
            return new Bounds(originX, originY, 0, 0);
        }
        return new Bounds(minX, minY, maxX - minX, maxY - minY);
    }

    private static TypographyGeometry geometry(
            Label label,
            CoordinateMapper coordinates,
            TextPlacement placement,
            TransformChain transforms,
            double scaleX,
            double scaleY) {
        List<CoordinatePoint> origins = coordinates.typographyPoint(
                label, placement.originX(), placement.originY(), scaleX, scaleY);
        List<CoordinatePoint> baselines = coordinates.typographyPoint(
                label, placement.originX(), placement.baselineY(), scaleX, scaleY);
        List<CoordinateBounds> layoutBounds = coordinates.typographyBounds(
                label, placement.layoutBounds(), scaleX, scaleY);
        List<CoordinateBounds> inkBounds = coordinates.typographyBounds(
                label, placement.inkBounds(), scaleX, scaleY);
        CoordinatePoint framebufferOrigin = origins.stream()
                .filter(value -> value.space() == CoordinateSpace.FRAMEBUFFER)
                .findFirst()
                .orElseThrow();
        return new TypographyGeometry(
                origins,
                baselines,
                layoutBounds,
                inkBounds,
                fractional(framebufferOrigin.x()),
                fractional(framebufferOrigin.y()));
    }

    private static List<String> mechanisms(FontObservation font) {
        List<String> values = new ArrayList<>();
        if (font.sourceId().isAvailable()) {
            values.add("font-source=" + font.sourceId().value());
        }
        values.add("bitmap-scale=" + concise(font.bitmapScaleX())
                + "x" + concise(font.bitmapScaleY()));
        if (font.magnificationFilter().isAvailable()) {
            values.add("magnification-filter=" + font.magnificationFilter().value());
        }
        return List.copyOf(values);
    }

    private static String transformDigest(TransformChain transforms) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (AffineTransformObservation value : transforms.mappings()) {
                update(digest, value.source().name());
                update(digest, value.target().name());
                update(digest, Double.toHexString(value.m00()));
                update(digest, Double.toHexString(value.m01()));
                update(digest, Double.toHexString(value.translateX()));
                update(digest, Double.toHexString(value.m10()));
                update(digest, Double.toHexString(value.m11()));
                update(digest, Double.toHexString(value.translateY()));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", failure);
        }
    }

    private IdentityHashMap<Actor, String> actorIds() {
        IdentityHashMap<Actor, String> result = new IdentityHashMap<>();
        collectIds(stage.getRoot(), result);
        return result;
    }

    private static void collectIds(Actor actor, IdentityHashMap<Actor, String> result) {
        result.put(actor, "n" + result.size());
        if (actor instanceof Group group) {
            SnapshotArray<Actor> children = group.getChildren();
            for (int index = 0; index < children.size; index++) {
                collectIds(children.get(index), result);
            }
        }
    }

    private static void requireGraphics() {
        if (Gdx.graphics == null
                || Gdx.graphics.getWidth() <= 0
                || Gdx.graphics.getHeight() <= 0) {
            throw new IllegalStateException("logical window geometry is unavailable");
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static double fractional(double value) {
        return Math.abs(value - Math.rint(value));
    }

    private static String concise(double value) {
        if (value == Math.rint(value)) {
            return Long.toString(Math.round(value));
        }
        return Double.toString(value);
    }

    private record TextPlacement(
            double originX,
            double originY,
            double baselineY,
            Bounds layoutBounds,
            Bounds inkBounds) {}
}
