package dev.gdx.uiharness.core.error;

/** Standard {@link RedactionPolicy} instances. */
public final class RedactionPolicies {
    private RedactionPolicies() {
        // Utility holder.
    }

    /**
     * Returns the default identity policy that publishes raw values unchanged.
     *
     * @return identity redaction policy with id {@code none}
     */
    public static RedactionPolicy none() {
        return NoneRedaction.INSTANCE;
    }

    private enum NoneRedaction implements RedactionPolicy {
        INSTANCE;

        @Override public String id() {
            return "none";
        }

        @Override public String redact(RedactionField field, String value) {
            return value;
        }
    }
}
