package dev.gdx.uiharness.core.contract;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable evaluator-complete state and optional last-transition contract. */
public record StateActionContract(
        ContractVersion schemaVersion,
        String stateId,
        long revision,
        long frame,
        List<ControlState> controls,
        List<String> focusOrder,
        String focusedControlId,
        List<ConditionalRule> conditions,
        List<ViewportState> viewports,
        TransitionOutcome transition) {
    public StateActionContract {
        Objects.requireNonNull(schemaVersion, "schemaVersion");
        if (schemaVersion.major() != ContractVersion.V1.major()) {
            throw violation("$.schemaVersion", "supported major 1",
                    schemaVersion.wireName(), schemaVersion);
        }
        ContractSupport.text(stateId, "stateId");
        if (revision < 0 || frame < 0) {
            throw new IllegalArgumentException("revision and frame must be non-negative");
        }
        controls = List.copyOf(Objects.requireNonNull(controls, "controls"));
        if (controls.size() > 256) {
            throw new IllegalArgumentException("controls exceeds 256 entries");
        }
        Set<String> ids = new HashSet<>(controls.size());
        for (int index = 0; index < controls.size(); index++) {
            String id = controls.get(index).id();
            if (!ids.add(id)) {
                throw violation("$.controls[" + index + "].id",
                        "unique stable control ID", id, schemaVersion);
            }
        }

        focusOrder = List.copyOf(Objects.requireNonNull(focusOrder, "focusOrder"));
        Set<String> focusedIds = new HashSet<>(focusOrder.size());
        for (int index = 0; index < focusOrder.size(); index++) {
            String id = focusOrder.get(index);
            if (!ids.contains(id)) {
                throw violation("$.focusOrder[" + index + "]",
                        "ID of a declared control", id, schemaVersion);
            }
            if (!focusedIds.add(id)) {
                throw violation("$.focusOrder[" + index + "]",
                        "unique focus control ID", id, schemaVersion);
            }
        }
        if (focusedControlId != null && !ids.contains(focusedControlId)) {
            throw violation("$.focusedControlId", "ID of a declared control",
                    focusedControlId, schemaVersion);
        }

        conditions = List.copyOf(Objects.requireNonNull(conditions, "conditions"));
        for (int index = 0; index < conditions.size(); index++) {
            ConditionalRule condition = conditions.get(index);
            requireReference(ids, condition.controllerId(),
                    "$.conditions[" + index + "].controllerId", schemaVersion);
            requireReference(ids, condition.dependentId(),
                    "$.conditions[" + index + "].dependentId", schemaVersion);
            if (condition.restoreFocusTo() != null) {
                requireReference(ids, condition.restoreFocusTo(),
                        "$.conditions[" + index + "].restoreFocusTo", schemaVersion);
            }
        }

        viewports = List.copyOf(Objects.requireNonNull(viewports, "viewports"));
        Set<String> viewportIds = new HashSet<>(viewports.size());
        for (int viewportIndex = 0; viewportIndex < viewports.size(); viewportIndex++) {
            ViewportState viewport = viewports.get(viewportIndex);
            if (!viewportIds.add(viewport.id())) {
                throw violation("$.viewports[" + viewportIndex + "].id",
                        "unique viewport ID", viewport.id(), schemaVersion);
            }
            for (int controlIndex = 0;
                    controlIndex < viewport.visibleControlIds().size(); controlIndex++) {
                requireReference(ids, viewport.visibleControlIds().get(controlIndex),
                        "$.viewports[" + viewportIndex + "].visibleControlIds["
                                + controlIndex + "]",
                        schemaVersion);
            }
        }
        if (transition != null
                && (transition.resultingRevision() != revision
                || !transition.resultingStateId().equals(stateId))) {
            throw violation("$.transition.resultingStateId",
                    "identity of the enclosing resulting state",
                    transition.resultingStateId(), schemaVersion);
        }
    }

    private static void requireReference(
            Set<String> ids, String id, String path, ContractVersion version) {
        if (!ids.contains(id)) {
            throw violation(path, "ID of a declared control", id, version);
        }
    }

    private static ContractViolationException violation(
            String path, String expected, String observed, ContractVersion version) {
        return new ContractViolationException(path, expected, observed, version);
    }
}
