package dev.gdx.uiharness.core.golden;

import dev.gdx.uiharness.core.model.Bounds;
import dev.gdx.uiharness.core.model.Role;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Canonical SHA-256 digest over the complete versioned semantic baseline. The encoding is
 * deterministic and injective over accepted baselines:
 *
 * <ul>
 *   <li>version components and {@code strictNodes} are fixed decimal and boolean literals;</li>
 *   <li>every user-controlled string ({@code id}, text fields, property keys and values) is
 *       emitted with an explicit UTF-16 code-unit length prefix;</li>
 *   <li>{@code role} is encoded as {@link Role#name()};</li>
 *   <li>nullable booleans are the fixed literals {@code true}, {@code false}, {@code null};</li>
 *   <li>{@code stageBounds} encodes presence plus each component as fixed-width 16-hex
 *       characters from {@link Double#doubleToLongBits(double)}: stable IEEE-754 bits that
 *       distinguish signed zero and preserve exponents; NaN payloads normalize to the
 *       canonical NaN bit pattern (consistent with {@link Double#equals(Object)}), which is
 *       unreachable in practice because {@link Bounds} rejects non-finite components;</li>
 *   <li>the byte sink is UTF-16 big-endian code units, so unpaired surrogates hash losslessly
 *       instead of being replaced by the UTF-8 encoder.</li>
 * </ul>
 *
 * <p>No maps are hashed without ordering and no locale-sensitive formatting is used, so the
 * digest is stable across processes and JVMs.
 */
public final class BaselineDigest {
    /** Hex length of a SHA-256 digest. */
    public static final int HEX_LENGTH = 64;

    private static final HexFormat HEX = HexFormat.of();

    private BaselineDigest() {}

    /** Computes the canonical digest of one versioned baseline. */
    public static String canonical(SemanticBaseline baseline) {
        Objects.requireNonNull(baseline, "baseline");
        StringBuilder out = new StringBuilder();
        out.append("semantic-baseline/v1\n");
        out.append("major=").append(baseline.majorVersion()).append('\n');
        out.append("minor=").append(baseline.minorVersion()).append('\n');
        appendText(out, "id", baseline.id());
        appendBoolean(out, "strictNodes", baseline.strictNodes());
        appendNode(out, baseline.root(), 0);
        return sha256(toUtf16BigEndianBytes(out));
    }

    /** Validates the bounded lowercase-hex digest format. */
    public static boolean isValidFormat(String digest) {
        if (digest == null || digest.length() != HEX_LENGTH) {
            return false;
        }
        for (int index = 0; index < digest.length(); index++) {
            char c = digest.charAt(index);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    private static void appendNode(StringBuilder out, BaselineNode node, int depth) {
        String indent = "  ".repeat(depth);
        out.append(indent).append("node\n");
        out.append(indent).append("  role=").append(node.role().name()).append('\n');
        appendText(out, indent + "  accessibleName", node.accessibleName());
        appendText(out, indent + "  text", node.text());
        appendText(out, indent + "  label", node.label());
        appendText(out, indent + "  testId", node.testId());
        appendText(out, indent + "  actorName", node.actorName());
        appendText(out, indent + "  actorType", node.actorType());
        appendBoolean(out, indent + "  visible", node.visible());
        appendBoolean(out, indent + "  enabled", node.enabled());
        appendBoolean(out, indent + "  checked", node.checked());
        appendBoolean(out, indent + "  selected", node.selected());
        appendBoolean(out, indent + "  expanded", node.expanded());
        appendBoolean(out, indent + "  editable", node.editable());
        appendBoolean(out, indent + "  focused", node.focused());
        appendBoolean(out, indent + "  focusable", node.focusable());
        appendBounds(out, indent + "  stageBounds", node.stageBounds());
        appendText(out, indent + "  placement", node.placement());
        out.append(indent).append("  properties.count=")
                .append(node.properties().size()).append('\n');
        for (Map.Entry<String, String> property
                : new TreeMap<>(node.properties()).entrySet()) {
            appendText(out, indent + "    propertyKey", property.getKey());
            appendText(out, indent + "    propertyValue", property.getValue());
        }
        for (BaselineNode child : node.children()) {
            appendNode(out, child, depth + 1);
        }
    }

    private static void appendBoolean(StringBuilder out, String label, Boolean value) {
        out.append(label).append('=');
        if (value == null) {
            out.append("null");
        } else {
            out.append(value.booleanValue());
        }
        out.append('\n');
    }

    private static void appendBounds(StringBuilder out, String label, Bounds bounds) {
        if (bounds == null) {
            out.append(label).append("=null\n");
            return;
        }
        out.append(label).append("=present\n");
        out.append(label).append(".x=0x")
                .append(HEX.toHexDigits(Double.doubleToLongBits(bounds.x()))).append('\n');
        out.append(label).append(".y=0x")
                .append(HEX.toHexDigits(Double.doubleToLongBits(bounds.y()))).append('\n');
        out.append(label).append(".width=0x")
                .append(HEX.toHexDigits(Double.doubleToLongBits(bounds.width()))).append('\n');
        out.append(label).append(".height=0x")
                .append(HEX.toHexDigits(Double.doubleToLongBits(bounds.height()))).append('\n');
    }

    /**
     * Appends a possibly null, possibly multi-line string with an explicit UTF-16 code-unit
     * length prefix so the encoding is injective: embedded newlines, colons, comma-space
     * sequences, or unpaired surrogates inside a value can never be confused with the next
     * field boundary.
     */
    private static void appendText(StringBuilder out, String label, String value) {
        if (value == null) {
            out.append(label).append("=null\n");
            return;
        }
        out.append(label).append(".len=").append(value.length()).append(':')
                .append(value).append('\n');
    }

    /**
     * Encodes each UTF-16 code unit as two big-endian bytes. This is lossless for every
     * accepted string, including unpaired surrogates that the UTF-8 encoder would replace.
     */
    private static byte[] toUtf16BigEndianBytes(CharSequence text) {
        byte[] bytes = new byte[text.length() * 2];
        for (int index = 0; index < text.length(); index++) {
            char unit = text.charAt(index);
            bytes[index * 2] = (byte) (unit >>> 8);
            bytes[index * 2 + 1] = (byte) unit;
        }
        return bytes;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK lacks SHA-256", impossible);
        }
    }
}
