package dev.gdx.uiharness.core.golden;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Canonical SHA-256 digest over the complete versioned semantic baseline. The encoding is
 * deterministic (no maps without ordering, no locale-sensitive formatting) so the resource
 * digest is stable across processes and JVMs.
 */
public final class BaselineDigest {
    /** Hex length of a SHA-256 digest. */
    public static final int HEX_LENGTH = 64;

    private BaselineDigest() {}

    /** Computes the canonical digest of one versioned baseline. */
    public static String canonical(SemanticBaseline baseline) {
        Objects.requireNonNull(baseline, "baseline");
        StringBuilder out = new StringBuilder();
        out.append("semantic-baseline/v1\n");
        out.append("major=").append(baseline.majorVersion()).append('\n');
        out.append("minor=").append(baseline.minorVersion()).append('\n');
        out.append("id=").append(baseline.id()).append('\n');
        out.append("strictNodes=").append(baseline.strictNodes()).append('\n');
        appendNode(out, baseline.root(), 0);
        return sha256(out.toString().getBytes(StandardCharsets.UTF_8));
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
        out.append(indent).append("  role=").append(node.role()).append('\n');
        appendText(out, indent + "  accessibleName", node.accessibleName());
        appendText(out, indent + "  text", node.text());
        appendText(out, indent + "  label", node.label());
        appendText(out, indent + "  testId", node.testId());
        appendText(out, indent + "  actorName", node.actorName());
        appendText(out, indent + "  actorType", node.actorType());
        out.append(indent).append("  visible=").append(node.visible()).append('\n');
        out.append(indent).append("  enabled=").append(node.enabled()).append('\n');
        out.append(indent).append("  checked=").append(node.checked()).append('\n');
        out.append(indent).append("  selected=").append(node.selected()).append('\n');
        out.append(indent).append("  expanded=").append(node.expanded()).append('\n');
        out.append(indent).append("  editable=").append(node.editable()).append('\n');
        out.append(indent).append("  focused=").append(node.focused()).append('\n');
        out.append(indent).append("  focusable=").append(node.focusable()).append('\n');
        out.append(indent).append("  stageBounds=").append(node.stageBounds()).append('\n');
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

    /**
     * Appends a possibly null, possibly multi-line string with an explicit length prefix so
     * the encoding is injective: embedded newlines, colons, or comma-space sequences inside a
     * value can never be confused with the next field boundary.
     */
    private static void appendText(StringBuilder out, String label, String value) {
        if (value == null) {
            out.append(label).append("=null\n");
            return;
        }
        out.append(label).append(".len=").append(value.length()).append(':')
                .append(value).append('\n');
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
