package benchmark.palisade;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Render-thread structural measurement owned by the immutable benchmark template. */
final class TrustedStructuralProbe {
    private static final int MAX_ACTORS = 256;
    private static final int MAX_CONTROLS = 64;
    private static final String PROBE_SHA256 = implementationSha256();

    private TrustedStructuralProbe() {
    }

    static void verifyLoaded() {
        if (!PROBE_SHA256.matches("[0-9a-f]{64}")) {
            throw new IllegalStateException("Trusted structural probe identity is invalid");
        }
    }

    static Map<String, Object> capture(
            Stage stage, CandidateState state, Pixmap framebuffer, long frame) {
        Map<String, Actor> actors = actors(stage);
        double width = framebuffer.getWidth();
        double height = framebuffer.getHeight();
        Rect viewport = new Rect(0, 0, width, height);
        Actor panelActor = actors.get("panel");
        Rect panel = panelActor == null ? viewport : bounds(panelActor, stage, width, height);
        Map<?, ?> contract = object(state.values().get("stateAction"));
        List<Map<String, Object>> controls = controls(
                contract, actors, stage, framebuffer, viewport);
        String semanticSha256 = sha256(String.valueOf(contract));
        String layoutSha256 = sha256(String.valueOf(controls));

        Map<String, Object> observation = new LinkedHashMap<>();
        observation.put("schemaVersion", "structural-observation/v1");
        observation.put("semanticRevision", stableRevision(semanticSha256));
        observation.put("layoutRevision", stableRevision(layoutSha256));
        observation.put("frameEdgeClipped", !panel.equals(panel.intersection(viewport)));
        observation.put("scrollY", measuredScrollY(controls));
        observation.put("semanticSha256", semanticSha256);
        observation.put("layoutSha256", layoutSha256);
        observation.put("regionSha256", pixelSha256(framebuffer, panel));
        observation.put("panelBounds", panel.map());
        observation.put("controls", controls);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", "trusted-structural-measurement/v1");
        result.put("probeSha256", PROBE_SHA256);
        result.put("observation", observation);
        return Map.copyOf(result);
    }

    private static List<Map<String, Object>> controls(
            Map<?, ?> contract,
            Map<String, Actor> actors,
            Stage stage,
            Pixmap framebuffer,
            Rect viewport) {
        Object value = contract.get("controls");
        if (!(value instanceof List<?> declared)) {
            return List.of();
        }
        List<Map<String, Object>> measured = new ArrayList<>();
        for (Object element : declared) {
            if (measured.size() == MAX_CONTROLS || !(element instanceof Map<?, ?> control)) {
                break;
            }
            String id = text(control.get("id"));
            Actor actor = id == null ? null : actors.get(id);
            if (actor == null || !actor.isVisible()) {
                continue;
            }
            Rect visual = bounds(
                    actor, stage, framebuffer.getWidth(), framebuffer.getHeight());
            String role = text(control.get("role"));
            boolean selfLabelling = "button".equals(role) || "checkbox".equals(role);
            String labelId = !selfLabelling && actors.containsKey(id + "Label")
                    ? id + "Label" : null;
            Actor textActor = labelId == null ? actor : actors.get(labelId);
            Rect textVisual = bounds(
                    textActor, stage, framebuffer.getWidth(), framebuffer.getHeight());
            PixelMetrics pixels = pixels(framebuffer, textVisual);
            String owner = ancestor(actor, "scroll");
            Rect visible = visual.intersection(viewport);
            Actor scroll = owner == null ? null : actors.get(owner);
            String clipOwner = scroll instanceof Group group
                    && group.getCullingArea() != null ? owner : null;
            if (clipOwner != null) {
                visible = visible.intersection(bounds(
                        scroll, stage, framebuffer.getWidth(), framebuffer.getHeight()));
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("controlId", id);
            item.put("role", role);
            item.put("labelControlId", labelId);
            item.put("labelledControlId", labelId == null ? null : id);
            item.put("enabled", actor.getTouchable() != Touchable.disabled);
            item.put("focusable", actor.getTouchable() == Touchable.enabled);
            item.put("hitBounds", visual.map());
            item.put("visualBounds", visual.map());
            item.put("occluded", occluded(actor, stage, visual));
            item.put("fontPixels", fontPixels(
                    textActor, stage, framebuffer.getHeight()));
            item.put("rasterResidual", pixels.rasterResidual());
            item.put("contrastRatio", pixels.contrastRatio());
            item.put("glyphClipped", glyphClipped(textActor));
            item.put("hierarchyRole", "form".equals(parentName(actor))
                    ? "form-row" : actor.getClass().getSimpleName().toLowerCase());
            item.put("parentControlId", parentName(actor));
            item.put("scrollOwnerId", owner);
            item.put("clipOwnerId", clipOwner);
            item.put("visibleBounds", visible.map());
            measured.add(Collections.unmodifiableMap(new LinkedHashMap<>(item)));
        }
        return List.copyOf(measured);
    }

    private static Map<String, Actor> actors(Stage stage) {
        Map<String, Actor> indexed = new LinkedHashMap<>();
        int[] count = {0};
        for (Actor actor : stage.getActors()) {
            index(actor, indexed, count);
        }
        return Map.copyOf(indexed);
    }

    private static void index(
            Actor actor, Map<String, Actor> indexed, int[] count) {
        if (++count[0] > MAX_ACTORS) {
            throw new IllegalArgumentException("Stage has too many actors for structural evidence");
        }
        String name = actor.getName();
        if (name != null && !name.isBlank()) {
            if (indexed.putIfAbsent(name, actor) != null) {
                throw new IllegalArgumentException("Duplicate structural actor identity: " + name);
            }
        }
        if (actor instanceof Group group) {
            for (Actor child : group.getChildren()) {
                index(child, indexed, count);
            }
        }
    }

    private static Rect bounds(
            Actor actor, Stage stage, double framebufferWidth, double framebufferHeight) {
        Vector2 first = actor.localToStageCoordinates(new Vector2(0, 0));
        Vector2 second = actor.localToStageCoordinates(
                new Vector2(actor.getWidth(), actor.getHeight()));
        double scaleX = framebufferWidth / stage.getViewport().getWorldWidth();
        double scaleY = framebufferHeight / stage.getViewport().getWorldHeight();
        double x = Math.min(first.x, second.x) * scaleX;
        double bottom = Math.min(first.y, second.y) * scaleY;
        double width = Math.abs(second.x - first.x) * scaleX;
        double height = Math.abs(second.y - first.y) * scaleY;
        return new Rect(x, framebufferHeight - bottom - height, width, height);
    }

    private static boolean occluded(Actor actor, Stage stage, Rect bounds) {
        Vector2 stagePoint = stage.getViewport().unproject(new Vector2(
                (float) (bounds.x() + bounds.width() / 2),
                (float) (bounds.y() + bounds.height() / 2)));
        Actor hit = stage.hit(stagePoint.x, stagePoint.y, true);
        return hit != null && hit != actor && !hit.isDescendantOf(actor);
    }

    private static double fontPixels(Actor actor, Stage stage, double framebufferHeight) {
        double scale = framebufferHeight / stage.getViewport().getWorldHeight();
        double lineHeight;
        if (actor instanceof Label label) {
            lineHeight = label.getStyle().font.getLineHeight();
        } else if (actor instanceof TextButton button) {
            lineHeight = button.getLabel().getStyle().font.getLineHeight();
        } else if (actor instanceof TextField field) {
            lineHeight = field.getStyle().font.getLineHeight();
        } else if (actor instanceof SelectBox<?> select) {
            lineHeight = select.getStyle().font.getLineHeight();
        } else {
            return 0;
        }
        return lineHeight * Math.abs(actor.getScaleY()) * scale;
    }

    private static boolean glyphClipped(Actor actor) {
        if (actor instanceof Label label) {
            label.validate();
            return label.getPrefWidth() > actor.getWidth() + 0.5
                    || label.getPrefHeight() > actor.getHeight() + 0.5;
        }
        if (actor instanceof TextButton button) {
            return glyphClipped(button.getLabel());
        }
        GlyphLayout layout;
        if (actor instanceof TextField field) {
            layout = new GlyphLayout(field.getStyle().font, field.getText());
        } else if (actor instanceof SelectBox<?> select) {
            layout = new GlyphLayout(
                    select.getStyle().font, String.valueOf(select.getSelected()));
        } else {
            return false;
        }
        return layout.width > actor.getWidth() + 0.5
                || layout.height > actor.getHeight() + 0.5;
    }

    private static PixelMetrics pixels(Pixmap framebuffer, Rect source) {
        int left = clamp((int) Math.floor(source.x()), 0, framebuffer.getWidth());
        int top = clamp((int) Math.floor(source.y()), 0, framebuffer.getHeight());
        int right = clamp((int) Math.ceil(source.x() + source.width()), 0,
                framebuffer.getWidth());
        int bottom = clamp((int) Math.ceil(source.y() + source.height()), 0,
                framebuffer.getHeight());
        if (right <= left || bottom <= top) {
            return new PixelMetrics(1, 1);
        }
        double minimum = 1;
        double maximum = 0;
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {
                double value = luminance(framebuffer.getPixel(x, y));
                minimum = Math.min(minimum, value);
                maximum = Math.max(maximum, value);
            }
        }
        double range = maximum - minimum;
        double residual = 0;
        long count = 0;
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {
                double value = luminance(framebuffer.getPixel(x, y));
                if (range > 0) {
                    residual += Math.min(value - minimum, maximum - value) / range;
                }
                count++;
            }
        }
        double contrast = (maximum + 0.05) / (minimum + 0.05);
        return new PixelMetrics(residual / count, contrast);
    }

    private static double luminance(int rgba) {
        double red = ((rgba >>> 24) & 0xff) / 255.0;
        double green = ((rgba >>> 16) & 0xff) / 255.0;
        double blue = ((rgba >>> 8) & 0xff) / 255.0;
        return 0.2126 * channel(red) + 0.7152 * channel(green) + 0.0722 * channel(blue);
    }

    private static double channel(double value) {
        return value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }

    private static String ancestor(Actor actor, String name) {
        Group parent = actor.getParent();
        while (parent != null) {
            if (name.equals(parent.getName())) {
                return name;
            }
            parent = parent.getParent();
        }
        return null;
    }

    private static String parentName(Actor actor) {
        Group parent = actor.getParent();
        return parent == null || parent.getName() == null ? "stage" : parent.getName();
    }

    private static Map<?, ?> object(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private static String text(Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private static double measuredScrollY(List<Map<String, Object>> controls) {
        for (Map<String, Object> control : controls) {
            if ("costlyCavalry".equals(control.get("controlId"))
                    && control.get("visualBounds") instanceof Map<?, ?> bounds
                    && bounds.get("y") instanceof Number y
                    && y.doubleValue() < 200) {
                return 1;
            }
        }
        return 0;
    }

    private static long stableRevision(String sha256) {
        return Long.parseUnsignedLong(sha256.substring(0, 15), 16);
    }

    private static String pixelSha256(Pixmap framebuffer, Rect source) {
        MessageDigest digest = digest();
        int left = clamp((int) Math.floor(source.x()), 0, framebuffer.getWidth());
        int top = clamp((int) Math.floor(source.y()), 0, framebuffer.getHeight());
        int right = clamp((int) Math.ceil(source.x() + source.width()), 0,
                framebuffer.getWidth());
        int bottom = clamp((int) Math.ceil(source.y() + source.height()), 0,
                framebuffer.getHeight());
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {
                int pixel = framebuffer.getPixel(x, y);
                digest.update((byte) (pixel >>> 24));
                digest.update((byte) (pixel >>> 16));
                digest.update((byte) (pixel >>> 8));
                digest.update((byte) pixel);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256(String value) {
        return HexFormat.of().formatHex(digest().digest(
                value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static String implementationSha256() {
        try (InputStream input = TrustedStructuralProbe.class.getResourceAsStream(
                "TrustedStructuralProbe.class")) {
            if (input == null) {
                throw new IllegalStateException("Trusted structural probe is unavailable");
            }
            MessageDigest digest = digest();
            input.transferTo(new java.security.DigestOutputStream(
                    java.io.OutputStream.nullOutputStream(), digest));
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException failure) {
            throw new IllegalStateException("Could not identify trusted structural probe", failure);
        }
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 unavailable", impossible);
        }
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private record PixelMetrics(double rasterResidual, double contrastRatio) {
    }

    private record Rect(double x, double y, double width, double height) {
        private Rect intersection(Rect other) {
            double left = Math.max(x, other.x);
            double top = Math.max(y, other.y);
            double right = Math.min(x + width, other.x + other.width);
            double bottom = Math.min(y + height, other.y + other.height);
            return new Rect(left, top, Math.max(0, right - left), Math.max(0, bottom - top));
        }

        private Map<String, Object> map() {
            return Map.of("x", x, "y", y, "width", width, "height", height);
        }
    }
}
