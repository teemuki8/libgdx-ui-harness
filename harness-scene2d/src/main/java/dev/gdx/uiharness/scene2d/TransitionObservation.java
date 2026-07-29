package dev.gdx.uiharness.scene2d;

import dev.gdx.uiharness.core.contract.ContractValue;
import dev.gdx.uiharness.core.contract.TransitionKind;
import dev.gdx.uiharness.core.contract.ValidationStatus;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Application-domain transition facts observed after real input dispatch. */
public record TransitionObservation(
        String actionId,
        boolean accepted,
        String rejectionReason,
        ValidationStatus validation,
        TransitionKind kind,
        String clipboardText,
        Map<String, ContractValue> acceptedPayload) {
    public TransitionObservation {
        if (actionId == null || actionId.isBlank()) {
            throw new IllegalArgumentException("actionId must not be blank");
        }
        if (accepted && rejectionReason != null) {
            throw new IllegalArgumentException(
                    "accepted transition must not have a rejection reason");
        }
        if (!accepted && (rejectionReason == null || rejectionReason.isBlank())) {
            throw new IllegalArgumentException(
                    "rejected transition requires a rejection reason");
        }
        Objects.requireNonNull(validation, "validation");
        Objects.requireNonNull(kind, "kind");
        acceptedPayload = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(acceptedPayload, "acceptedPayload")));
        if (!accepted && !acceptedPayload.isEmpty()) {
            throw new IllegalArgumentException(
                    "rejected transition must not have an accepted payload");
        }
    }
}
