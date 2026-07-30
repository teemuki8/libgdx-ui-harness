package dev.gdx.uiharness.core.typography;

import java.util.Objects;

/** A rendering fact that is either available or explicitly unavailable. */
public record EvidenceValue<T>(
        Availability availability,
        T value,
        UnavailableReason unavailableReason,
        String detail) {

    /** Enforces mutually exclusive available and unavailable shapes. */
    public EvidenceValue {
        availability = Objects.requireNonNull(availability, "availability");
        if (availability == Availability.AVAILABLE) {
            Objects.requireNonNull(value, "value");
            if (unavailableReason != null || detail != null) {
                throw new IllegalArgumentException(
                        "available evidence cannot contain an unavailable reason");
            }
        } else {
            if (value != null) {
                throw new IllegalArgumentException(
                        "unavailable evidence cannot contain a value");
            }
            unavailableReason =
                    Objects.requireNonNull(unavailableReason, "unavailableReason");
            if (Objects.requireNonNull(detail, "detail").isBlank()) {
                throw new IllegalArgumentException("unavailable detail must not be blank");
            }
        }
    }

    /** Creates available evidence. */
    public static <T> EvidenceValue<T> available(T value) {
        return new EvidenceValue<>(Availability.AVAILABLE,
                Objects.requireNonNull(value, "value"), null, null);
    }

    /** Creates unavailable evidence without manufacturing a default value. */
    public static <T> EvidenceValue<T> unavailable(
            UnavailableReason reason, String detail) {
        return new EvidenceValue<>(Availability.UNAVAILABLE, null, reason, detail);
    }

    /** Returns whether this evidence contains an observed value. */
    public boolean isAvailable() {
        return availability == Availability.AVAILABLE;
    }
}
