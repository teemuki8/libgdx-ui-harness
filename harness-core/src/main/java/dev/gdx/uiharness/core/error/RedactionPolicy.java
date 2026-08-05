package dev.gdx.uiharness.core.error;

/**
 * Configurable redaction applied to semantic evidence before suggestions are ranked, rendered,
 * traced, or serialized. A policy identity is reported with diagnostics without exposing the
 * policy's secrets.
 */
public interface RedactionPolicy {
    /**
     * Returns the stable public identity of this policy.
     *
     * @return bounded policy identifier
     */
    String id();

    /**
     * Returns the value as it may be published for the supplied field.
     *
     * @param field semantic evidence field being published
     * @param value raw observed value, never {@code null}
     * @return bounded redacted value
     */
    String redact(RedactionField field, String value);
}
