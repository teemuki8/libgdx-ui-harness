package dev.gdx.uiharness.core.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.gdx.uiharness.core.model.RuntimeBinding;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class RuntimeObserverTest {
    @Test void observesOneExplicitCorrelatedRuntimeValue() {
        RuntimeObserver observer = new RuntimeObserver(binding -> Optional.of(
                new RuntimeObservation(binding.entityId(), binding.propertyId(),
                        41, 17, "1.25", "decimal")));

        RuntimeObservationResult result = observer.observe(
                "body-1", "angle", "render-frame");

        assertEquals(RuntimeObservationResult.Status.AVAILABLE, result.status());
        assertEquals("body-1", result.entityId());
        assertEquals("angle", result.propertyId());
        assertEquals(41L, result.runtimeFrame());
        assertEquals(17L, result.runtimeRevision());
        assertEquals("1.25", result.value());
        assertEquals("decimal", result.valueFormatId());
    }

    @Test void missingSourceValueAndMismatchedIdentityAreUnavailable() {
        RuntimeObserver missingSource = new RuntimeObserver();
        RuntimeObserver missingValue = new RuntimeObserver(binding -> Optional.empty());
        RuntimeObserver mismatched = new RuntimeObserver(binding -> Optional.of(
                new RuntimeObservation("other", binding.propertyId(), 1, 1, "x", "string")));

        assertEquals(RuntimeObservationResult.Status.UNAVAILABLE,
                missingSource.observe("body-1", "angle", "frame").status());
        assertEquals(RuntimeObservationResult.Status.UNAVAILABLE,
                missingValue.observe("body-1", "angle", "frame").status());
        assertEquals(RuntimeObservationResult.Status.UNAVAILABLE,
                mismatched.observe("body-1", "angle", "frame").status());
    }

    @Test void explicitIdentifiersAndReturnedValuesRemainBounded() {
        RuntimeObserver observer = new RuntimeObserver(binding -> Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> observer.observe("x".repeat(257), "angle", "frame"));
        assertThrows(IllegalArgumentException.class,
                () -> observer.observe("body", "angle", "x".repeat(257)));
        assertThrows(IllegalArgumentException.class, () -> new RuntimeObservationResult(
                RuntimeObservationResult.Status.AVAILABLE, "body", "angle", 1L, 1L,
                "x".repeat(16_385), "string"));
    }

    @Test void sourceAvailabilityIsRespected() {
        RuntimeObservationSource source = new RuntimeObservationSource() {
            @Override public Optional<RuntimeObservation> observe(RuntimeBinding binding) {
                throw new AssertionError("unavailable sources must not be observed");
            }

            @Override public boolean available(RuntimeBinding binding) {
                return false;
            }
        };

        assertEquals(RuntimeObservationResult.Status.UNAVAILABLE,
                new RuntimeObserver(source).observe("body", "angle", "frame").status());
    }
}
