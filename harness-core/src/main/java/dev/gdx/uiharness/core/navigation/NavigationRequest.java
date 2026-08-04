package dev.gdx.uiharness.core.navigation;

import dev.gdx.uiharness.core.limits.HarnessLimits;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Versioned bounded validation request containing observations, never execution behavior. */
public record NavigationRequest(
        int schemaVersion,
        List<NavigationStep> steps,
        List<String> knownFocusables,
        String modalBoundaryId,
        boolean controllerSupported,
        boolean deadlineExpired,
        int maxSteps,
        int maxActors,
        int maxResultBytes,
        int maxEvidenceBytes,
        Duration deadline) {
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_STEPS = 4_096;
    public static final int MAX_ACTORS = 10_000;
    public static final int MAX_BYTES = 1_048_576;

    public NavigationRequest {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported navigation schema version: " + schemaVersion);
        }
        steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
        knownFocusables = List.copyOf(Objects.requireNonNull(knownFocusables, "knownFocusables"));
        if (modalBoundaryId != null) {
            NavigationStep.requireIdentity(modalBoundaryId, "modalBoundaryId");
        }
        requireBound(maxSteps, MAX_STEPS, "maxSteps");
        requireBound(maxActors, MAX_ACTORS, "maxActors");
        requireBound(maxResultBytes, MAX_BYTES, "maxResultBytes");
        if (maxResultBytes < NavigationResult.minimumWireSizeUpperBound()) {
            throw new IllegalArgumentException(
                    "maxResultBytes cannot contain the minimum navigation result");
        }
        requireBound(maxEvidenceBytes, MAX_BYTES, "maxEvidenceBytes");
        Objects.requireNonNull(deadline, "deadline");
        if (deadline.isZero()) {
            throw new IllegalArgumentException("deadline must be positive");
        }
        HarnessLimits.defaults().validateDeadline(deadline);
        if (knownFocusables.size() > maxActors) {
            throw new IllegalArgumentException("known focusables exceed maxActors");
        }
        Set<String> identities = new HashSet<>();
        for (String identity : knownFocusables) {
            NavigationStep.requireIdentity(identity, "knownFocusable");
            if (!identities.add(identity)) {
                throw new IllegalArgumentException("duplicate focusable identity: " + identity);
            }
        }
        for (int index = 1; index < steps.size(); index++) {
            NavigationStep previous = steps.get(index - 1);
            NavigationStep next = steps.get(index);
            if (!Objects.equals(previous.afterIdentity(), next.beforeIdentity())
                    || previous.afterFrame() != next.beforeFrame()
                    || previous.afterRevision() != next.beforeRevision()) {
                throw new IllegalArgumentException(
                        "adjacent navigation steps must have continuous identity, frame, and revision");
            }
        }
        for (NavigationStep step : steps) {
            Objects.requireNonNull(step, "step");
            if (!identities.contains(step.beforeIdentity())
                    || step.afterIdentity() != null && !identities.contains(step.afterIdentity())) {
                throw new IllegalArgumentException("step references an unknown focusable identity");
            }
        }
    }

    private static void requireBound(int value, int hardMaximum, String name) {
        if (value < 1 || value > hardMaximum) {
            throw new IllegalArgumentException(name + " must be between 1 and " + hardMaximum);
        }
    }
}
