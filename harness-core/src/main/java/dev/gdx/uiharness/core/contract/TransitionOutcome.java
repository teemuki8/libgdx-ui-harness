package dev.gdx.uiharness.core.contract;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Normalized result of one real-dispatch action after the resulting completed frame. */
public record TransitionOutcome(
        String actionId,
        boolean accepted,
        String rejectionReason,
        String resultingStateId,
        long resultingRevision,
        ValidationStatus validation,
        TransitionKind kind,
        String clipboardText,
        Map<String, ContractValue> acceptedPayload) {
    public TransitionOutcome {
        ContractSupport.text(actionId, "actionId");
        if (accepted && rejectionReason != null) {
            throw violation("$.transition.rejectionReason", "absent when accepted is true",
                    rejectionReason);
        }
        if (!accepted) {
            ContractSupport.text(rejectionReason, "rejectionReason");
        }
        ContractSupport.text(resultingStateId, "resultingStateId");
        if (resultingRevision < 0) {
            throw new IllegalArgumentException("resultingRevision must be non-negative");
        }
        Objects.requireNonNull(validation, "validation");
        Objects.requireNonNull(kind, "kind");
        if (clipboardText != null && clipboardText.length() > ContractSupport.MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("clipboardText exceeds contract string limit");
        }
        acceptedPayload = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(acceptedPayload, "acceptedPayload")));
        if (acceptedPayload.size() > 256) {
            throw new IllegalArgumentException("acceptedPayload exceeds 256 entries");
        }
        if (!accepted && !acceptedPayload.isEmpty()) {
            throw violation("$.transition.acceptedPayload",
                    "empty when accepted is false", acceptedPayload.toString());
        }
        acceptedPayload.forEach((key, value) -> {
            ContractSupport.text(key, "payload key");
            Objects.requireNonNull(value, "payload value");
        });
    }

    /** Creates a successful normalized outcome. */
    public static TransitionOutcome accepted(
            String actionId,
            String resultingStateId,
            long resultingRevision,
            ValidationStatus validation,
            TransitionKind kind,
            String clipboardText,
            Map<String, ContractValue> acceptedPayload) {
        return new TransitionOutcome(actionId, true, null, resultingStateId,
                resultingRevision, validation, kind, clipboardText, acceptedPayload);
    }

    private static ContractViolationException violation(
            String path, String expected, String observed) {
        return new ContractViolationException(path, expected, observed, ContractVersion.V1);
    }
}
