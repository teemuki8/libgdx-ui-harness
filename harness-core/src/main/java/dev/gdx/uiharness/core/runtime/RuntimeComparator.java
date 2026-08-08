package dev.gdx.uiharness.core.runtime;

import dev.gdx.uiharness.core.locator.Locator;
import dev.gdx.uiharness.core.locator.LocatorEngine;
import dev.gdx.uiharness.core.model.RuntimeBinding;
import dev.gdx.uiharness.core.model.SemanticNode;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure displayed/runtime comparator over one immutable semantic observation. Equality is
 * claimed only when typed values match on provably correlated frames; unavailable, stale,
 * uncorrelated, missing, and ambiguous states remain distinct.
 */
public final class RuntimeComparator {
    private final RuntimeObservationSource source;

    /** Creates a comparator without a runtime source (comparisons report unavailable). */
    public RuntimeComparator() {
        this(binding -> Optional.empty());
    }

    /** Creates a comparator over one optional runtime observation source. */
    public RuntimeComparator(RuntimeObservationSource source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    /**
     * Compares the displayed value of one strictly resolved bound node against its runtime
     * observation.
     *
     * @param snapshot immutable semantic observation
     * @param locator locator selecting exactly one bound node
     * @param locators locator engine used for strict resolution
     * @return bounded typed comparison
     */
    public DisplayedRuntimeComparison compare(
            SemanticSnapshot snapshot, Locator locator, LocatorEngine locators) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(locator, "locator");
        Objects.requireNonNull(locators, "locators");
        SemanticNode node = locators.resolveStrict(snapshot, locator);
        RuntimeBinding binding = node.binding();
        if (binding == null) {
            return new DisplayedRuntimeComparison(
                    DisplayedRuntimeComparison.Status.MISSING,
                    "", "", node.text(), null, null, null,
                    snapshot.frame(), null, false, Map.of(
                            "reason", "unbound", "actorId", node.id()));
        }
        if (binding.propertyId() == null) {
            return new DisplayedRuntimeComparison(
                    DisplayedRuntimeComparison.Status.MISSING,
                    binding.entityId(), "", node.text(), null,
                    binding.comparatorId(), binding.correlationId(),
                    snapshot.frame(), null, false, Map.of(
                            "reason", "entity-only-binding"));
        }
        String displayed = node.text();
        if (displayed == null) {
            displayed = "";
        }
        if (!source.available(binding)) {
            return new DisplayedRuntimeComparison(
                    DisplayedRuntimeComparison.Status.UNAVAILABLE,
                    binding.entityId(), binding.propertyId(), displayed, null,
                    binding.comparatorId(), binding.correlationId(),
                    snapshot.frame(), null, false, Map.of());
        }
        Optional<RuntimeObservation> observed = source.observe(binding);
        if (observed.isEmpty()) {
            return new DisplayedRuntimeComparison(
                    DisplayedRuntimeComparison.Status.UNAVAILABLE,
                    binding.entityId(), binding.propertyId(), displayed, null,
                    binding.comparatorId(), binding.correlationId(),
                    snapshot.frame(), null, false, Map.of());
        }
        RuntimeObservation runtime = observed.orElseThrow();
        if (!Objects.equals(runtime.entityId(), binding.entityId())
                || !Objects.equals(runtime.propertyId(), binding.propertyId())) {
            return new DisplayedRuntimeComparison(
                    DisplayedRuntimeComparison.Status.AMBIGUOUS,
                    binding.entityId(), binding.propertyId(), displayed, runtime.value(),
                    binding.comparatorId(), binding.correlationId(),
                    snapshot.frame(), runtime.frame(), false, Map.of(
                            "reason", "observation binding mismatch"));
        }
        boolean correlated = binding.correlationId() != null
                && runtime.frame() == snapshot.frame();
        if (!correlated && runtime.frame() < snapshot.frame()) {
            return new DisplayedRuntimeComparison(
                    DisplayedRuntimeComparison.Status.STALE,
                    binding.entityId(), binding.propertyId(), displayed, runtime.value(),
                    binding.comparatorId(), binding.correlationId(),
                    snapshot.frame(), runtime.frame(), false, Map.of());
        }
        if (!correlated) {
            return new DisplayedRuntimeComparison(
                    DisplayedRuntimeComparison.Status.UNCORRELATED,
                    binding.entityId(), binding.propertyId(), displayed, runtime.value(),
                    binding.comparatorId(), binding.correlationId(),
                    snapshot.frame(), runtime.frame(), false, Map.of());
        }
        String runtimeFormat = runtime.valueFormatId();
        if (binding.valueFormatId() != null && runtimeFormat != null
                && !binding.valueFormatId().equals(runtimeFormat)) {
            return new DisplayedRuntimeComparison(
                    DisplayedRuntimeComparison.Status.AMBIGUOUS,
                    binding.entityId(), binding.propertyId(), displayed, runtime.value(),
                    binding.comparatorId(), binding.correlationId(),
                    snapshot.frame(), runtime.frame(), false, Map.of(
                            "reason", "value-format-mismatch",
                            "declaredFormat", binding.valueFormatId(),
                            "runtimeFormat", runtimeFormat));
        }
        boolean equal = typedEqual(displayed, runtime.value(), binding);
        return new DisplayedRuntimeComparison(
                equal ? DisplayedRuntimeComparison.Status.EQUAL
                        : DisplayedRuntimeComparison.Status.MISMATCH,
                binding.entityId(), binding.propertyId(), displayed, runtime.value(),
                binding.comparatorId(), binding.correlationId(),
                snapshot.frame(), runtime.frame(), false, Map.of());
    }

    private static boolean typedEqual(
            String displayed, String runtime, RuntimeBinding binding) {
        if (binding.comparatorId() == null) {
            return Objects.equals(displayed, runtime);
        }
        return switch (binding.comparatorId()) {
            case "exact" -> Objects.equals(displayed, runtime);
            case "case-insensitive" ->
                    displayed.equalsIgnoreCase(runtime);
            default -> Objects.equals(displayed, runtime);
        };
    }
}
