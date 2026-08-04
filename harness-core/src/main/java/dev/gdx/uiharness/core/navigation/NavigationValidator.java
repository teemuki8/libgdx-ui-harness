package dev.gdx.uiharness.core.navigation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Pure validator for adapter-supplied navigation observations and known focusables. */
public final class NavigationValidator {
    /** Validates supplied observations without dispatching input or consulting application state. */
    public NavigationResult validate(NavigationRequest request) {
        Objects.requireNonNull(request, "request");
        List<String> orderedKnown = request.knownFocusables().stream().sorted().toList();
        List<String> boundedKnown = boundedIdentities(orderedKnown, request.maxEvidenceBytes());
        boolean truncated = boundedKnown.size() != orderedKnown.size();

        List<NavigationStep> accepted = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<FocusState> seen = new HashSet<>();
        String defaultFocus = request.steps().isEmpty()
                ? null
                : request.steps().get(0).beforeIdentity();
        if (defaultFocus != null) {
            visited.add(defaultFocus);
            seen.add(new FocusState(defaultFocus, request.steps().get(0).modalBoundaryId()));
        }

        NavigationReason reason = request.deadlineExpired() ? NavigationReason.DEADLINE : null;
        int observedSteps = 0;
        for (NavigationStep step : request.steps()) {
            if (reason != null) {
                break;
            }
            if (observedSteps == request.maxSteps()) {
                reason = NavigationReason.TRUNCATED;
                truncated = true;
                break;
            }
            accepted.add(step);
            observedSteps++;
            if (step.input().isController() && !request.controllerSupported()) {
                reason = NavigationReason.UNSUPPORTED_CONTROLLER_PATH;
                break;
            }
            if (step.afterIdentity() == null) {
                reason = NavigationReason.FOCUS_LOST;
                break;
            }
            visited.add(step.afterIdentity());
            if (request.modalBoundaryId() != null
                    && !request.modalBoundaryId().equals(step.modalBoundaryId())) {
                reason = NavigationReason.MODAL_ESCAPE;
                break;
            }
            if (step.beforeIdentity().equals(step.afterIdentity())) {
                reason = NavigationReason.DEAD_END;
                break;
            }
            FocusState state = new FocusState(step.afterIdentity(), step.modalBoundaryId());
            if (!seen.add(state)) {
                reason = NavigationReason.CYCLE;
                break;
            }
        }

        List<String> unreachable = orderedKnown.stream()
                .filter(identity -> !visited.contains(identity))
                .toList();
        List<String> boundedUnreachable = boundedIdentities(
                unreachable, Math.max(0, request.maxEvidenceBytes() - encodedSize(boundedKnown)));
        truncated |= boundedUnreachable.size() != unreachable.size();
        if (reason == null) {
            reason = unreachable.isEmpty()
                    ? NavigationReason.COMPLETE
                    : NavigationReason.UNREACHABLE_CONTROL;
        }

        NavigationPath path = new NavigationPath(
                NavigationPath.SCHEMA_VERSION, defaultFocus, accepted, reason);
        NavigationResult result = new NavigationResult(
                NavigationResult.SCHEMA_VERSION, path, boundedKnown, boundedUnreachable, truncated);
        return boundResult(result, request.maxResultBytes());
    }

    private static NavigationResult boundResult(NavigationResult result, int byteBudget) {
        if (result.wireSizeUpperBound() <= byteBudget) {
            return result;
        }

        String defaultFocus = result.path().defaultFocusIdentity();
        List<NavigationStep> steps = new ArrayList<>(result.path().steps());
        List<String> known = new ArrayList<>(result.knownFocusables());
        List<String> unreachable = new ArrayList<>(result.unreachableFocusables());
        NavigationResult bounded;
        do {
            if (!unreachable.isEmpty()) {
                unreachable.remove(unreachable.size() - 1);
            } else if (!known.isEmpty()) {
                known.remove(known.size() - 1);
            } else if (!steps.isEmpty()) {
                steps.remove(steps.size() - 1);
            } else if (defaultFocus != null) {
                defaultFocus = null;
            } else {
                throw new IllegalArgumentException(
                        "maxResultBytes cannot contain the minimum navigation result");
            }
            NavigationPath path = new NavigationPath(
                    NavigationPath.SCHEMA_VERSION,
                    defaultFocus,
                    steps,
                    result.path().reason());
            bounded = new NavigationResult(
                    NavigationResult.SCHEMA_VERSION, path, known, unreachable, true);
        } while (bounded.wireSizeUpperBound() > byteBudget);
        return bounded;
    }

    private static List<String> boundedIdentities(List<String> identities, int byteBudget) {
        List<String> result = new ArrayList<>();
        int used = 0;
        for (String identity : identities) {
            int size = encodedSize(identity);
            if (used + size > byteBudget) {
                break;
            }
            result.add(identity);
            used += size;
        }
        return List.copyOf(result);
    }


    private static int encodedSize(List<String> values) {
        int size = 0;
        for (String value : values) {
            size += encodedSize(value);
        }
        return size;
    }

    private static int encodedSize(String value) {
        return value == null ? 0 : Math.multiplyExact(value.length(), 6);
    }

    private record FocusState(String identity, String modalBoundaryId) {}
}
