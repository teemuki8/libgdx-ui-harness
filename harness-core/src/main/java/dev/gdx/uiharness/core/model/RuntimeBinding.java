package dev.gdx.uiharness.core.model;


/**
 * Explicit typed UI-to-runtime binding attached by application metadata. Bindings are never
 * inferred from labels, actor names, reflection, or object identity.
 *
 * @param entityId bounded opaque runtime entity identifier
 * @param propertyId bounded property identifier or path
 * @param valueFormatId bounded value-format identity, when declared
 * @param comparatorId bounded comparator identity, when declared
 * @param correlationId bounded shared-frame correlation identity, when declared
 */
public record RuntimeBinding(
        String entityId,
        String propertyId,
        String valueFormatId,
        String comparatorId,
        String correlationId) {
    private static final int MAX_IDENTIFIER = 256;

    /** Validates the bounded binding fields. */
    public RuntimeBinding {
        entityId = requireBounded(entityId, "entityId");
        propertyId = requireBounded(propertyId, "propertyId");
        valueFormatId = optionalBounded(valueFormatId, "valueFormatId");
        comparatorId = optionalBounded(comparatorId, "comparatorId");
        correlationId = optionalBounded(correlationId, "correlationId");
    }

    private static String requireBounded(String value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (value.length() > MAX_IDENTIFIER) {
            throw new IllegalArgumentException(name + " exceeds 256 characters");
        }
        return value;
    }

    private static String optionalBounded(String value, String name) {
        if (value == null) {
            return null;
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (value.length() > MAX_IDENTIFIER) {
            throw new IllegalArgumentException(name + " exceeds 256 characters");
        }
        return value;
    }
}
