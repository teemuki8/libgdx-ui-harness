package dev.gdx.uiharness.core.runtime;

import dev.gdx.uiharness.core.model.RuntimeBinding;
import java.util.Objects;
import java.util.Optional;

/** Read-only observer for one explicit registered runtime entity property and correlation token. */
public final class RuntimeObserver {
    private static final int MAX_IDENTIFIER = 256;
    private final RuntimeObservationSource source;

    /** Creates an observer without an installed source; every request is unavailable. */
    public RuntimeObserver() {
        source = null;
    }

    /** Creates an observer over one application-supplied read-only source. */
    public RuntimeObserver(RuntimeObservationSource source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    /** Observes only the requested entity property on its explicitly correlated completed frame. */
    public RuntimeObservationResult observe(
            String entityId, String propertyId, String correlationToken) {
        RuntimeBinding binding =
                new RuntimeBinding(entityId, propertyId, null, null, correlationToken);
        if (source == null || !source.available(binding)) {
            return RuntimeObservationResult.unavailable(entityId, propertyId);
        }
        Optional<RuntimeObservation> observed = source.observe(binding);
        if (observed.isEmpty()) {
            return RuntimeObservationResult.unavailable(entityId, propertyId);
        }
        RuntimeObservation value = observed.orElseThrow();
        if (!entityId.equals(value.entityId())
                || !propertyId.equals(value.propertyId())
                || !validFormat(value.valueFormatId())) {
            return RuntimeObservationResult.unavailable(entityId, propertyId);
        }
        return new RuntimeObservationResult(
                RuntimeObservationResult.Status.AVAILABLE,
                entityId,
                propertyId,
                value.frame(),
                value.revision(),
                value.value(),
                value.valueFormatId());
    }

    private static boolean validFormat(String valueFormatId) {
        return valueFormatId != null
                && !valueFormatId.isBlank()
                && valueFormatId.length() <= MAX_IDENTIFIER;
    }
}
