package dev.gdx.uiharness.agentruntime;

import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValue;
import java.math.BigDecimal;
import java.util.List;

/**
 * Deterministic bounded canonical rendering of agent-runtime values.
 *
 * <p>Nesting deeper than 8 levels, sequences with more than 32 entries, and output
 * longer than 256 characters are all cut short so the rendered string always ends
 * with an explicit {@code "..."} marker.
 */
public final class RuntimeValueRenderer {
    private static final int MAX_DEPTH = 8;
    private static final int MAX_ITEMS = 32;
    private static final int MAX_LENGTH = 256;
    private static final String TRUNCATION = "...";

    private RuntimeValueRenderer() {}

    /** Renders one value to its canonical display-comparable string. */
    public static String render(RuntimeValue value) {
        Renderer renderer = new Renderer();
        renderer.renderValue(value, 0);
        return renderer.result();
    }

    private static final class Renderer {
        /** Characters of real content before the truncation marker takes over. */
        private static final int CONTENT_LIMIT = MAX_LENGTH - TRUNCATION.length();

        private final StringBuilder out = new StringBuilder();
        private boolean truncated;

        String result() {
            return truncated ? out + TRUNCATION : out.toString();
        }

        void renderValue(RuntimeValue value, int depth) {
            if (truncated) {
                return;
            }
            if (value == null || depth > MAX_DEPTH) {
                truncated = true;
                append(TRUNCATION);
                return;
            }
            switch (value) {
                case RuntimeValue.NullValue _ -> {
                    // Renders as the empty string.
                }
                case RuntimeValue.BooleanValue b -> append(Boolean.toString(b.value()));
                case RuntimeValue.IntegerValue i -> append(Long.toString(i.value()));
                case RuntimeValue.DecimalValue d -> append(decimal(d.value()));
                case RuntimeValue.StringValue s -> append(text(s.value()));
                case RuntimeValue.EnumValue e -> append(text(e.value()));
                case RuntimeValue.Vector2Value v -> {
                    append("(");
                    append(component(v.x()));
                    append(", ");
                    append(component(v.y()));
                    append(")");
                }
                case RuntimeValue.ListValue l -> renderSequence(l.values(), '[', ']', depth);
                case RuntimeValue.ObjectValue o -> renderObject(o, depth);
            }
        }

        private void renderSequence(List<RuntimeValue> values, char open, char close, int depth) {
            append(String.valueOf(open));
            if (values != null) {
                int shown = 0;
                for (RuntimeValue item : values) {
                    if (truncated) {
                        break;
                    }
                    if (shown >= MAX_ITEMS) {
                        truncated = true;
                        append(TRUNCATION);
                        break;
                    }
                    if (shown > 0) {
                        append(", ");
                    }
                    renderValue(item, depth + 1);
                    shown++;
                }
            }
            if (!truncated) {
                append(String.valueOf(close));
            }
        }

        private void renderObject(RuntimeValue.ObjectValue object, int depth) {
            append("{");
            List<RuntimeValue.Field> fields = object.fields();
            if (fields != null) {
                int shown = 0;
                for (RuntimeValue.Field field : fields) {
                    if (truncated) {
                        break;
                    }
                    if (shown >= MAX_ITEMS) {
                        truncated = true;
                        append(TRUNCATION);
                        break;
                    }
                    if (shown > 0) {
                        append(", ");
                    }
                    append(text(field.name()));
                    append("=");
                    renderValue(field.value(), depth + 1);
                    shown++;
                }
            }
            if (!truncated) {
                append("}");
            }
        }

        private void append(String text) {
            if (truncated) {
                return;
            }
            int remaining = CONTENT_LIMIT - out.length();
            if (remaining <= 0) {
                truncated = true;
            } else if (text.length() <= remaining) {
                out.append(text);
            } else {
                out.append(text, 0, remaining);
                truncated = true;
            }
        }
    }

    private static String decimal(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    /**
     * Renders a vector component the way {@code Double.toString} would: the component is
     * stored scale-canonicalized, so converting back to a double restores the canonical
     * decimal form (for example {@code -2} renders as {@code -2.0}).
     */
    private static String component(RuntimeValue.DecimalValue component) {
        BigDecimal value = component == null ? null : component.value();
        return value == null ? "" : Double.toString(value.doubleValue());
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
