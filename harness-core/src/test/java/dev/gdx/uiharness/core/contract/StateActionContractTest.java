package dev.gdx.uiharness.core.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gdx.uiharness.core.model.Role;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class StateActionContractTest {
    @Test
    void preservesEveryDeclaredOrderAndDefensivelyCopiesCollections() {
        var controls = new java.util.ArrayList<>(List.of(
                control("victoryCondition", ControlKind.SELECT, ContractValue.text("conquest")),
                control("rivalTargetCount", ControlKind.NUMBER, ContractValue.integer(1))));
        var focusOrder = new java.util.ArrayList<>(
                List.of("victoryCondition", "rivalTargetCount"));
        var conditions = new java.util.ArrayList<>(List.of(new ConditionalRule(
                "victoryCondition", ContractValue.text("rival-target"),
                "rivalTargetCount", true, true, "victoryCondition")));
        var viewports = new java.util.ArrayList<>(List.of(new ViewportState(
                "configuration", 1280, 720, 0, 480, 0, 480,
                List.of("victoryCondition", "rivalTargetCount"))));

        StateActionContract contract = new StateActionContract(
                ContractVersion.V1, "state-a", 7, 11, controls, focusOrder,
                "rivalTargetCount", conditions, viewports, null);
        controls.clear();
        focusOrder.clear();
        conditions.clear();
        viewports.clear();

        assertEquals(List.of("victoryCondition", "rivalTargetCount"),
                contract.controls().stream().map(ControlState::id).toList());
        assertEquals(List.of("victoryCondition", "rivalTargetCount"), contract.focusOrder());
        assertEquals("victoryCondition", contract.conditions().getFirst().controllerId());
        assertEquals("configuration", contract.viewports().getFirst().id());
        assertThrows(UnsupportedOperationException.class,
                () -> contract.controls().add(control(
                        "other", ControlKind.BUTTON, ContractValue.nullValue())));
    }

    @Test
    void valuesAreTypedAndCopiesOfPayloadsAreImmutable() {
        Map<String, ContractValue> payload = new LinkedHashMap<>();
        payload.put("seed", ContractValue.integer(4_294_967_295L));
        payload.put("map", ContractValue.text("northern-realms-860"));
        TransitionOutcome transition = TransitionOutcome.accepted(
                "start-battle", "state-b", 8,
                new ValidationStatus(true, List.of()),
                TransitionKind.CONFIRMATION, null, payload);
        payload.clear();

        assertEquals(4_294_967_295L,
                ((ContractValue.IntegerValue) transition.acceptedPayload().get("seed")).value());
        assertEquals(List.of("seed", "map"),
                transition.acceptedPayload().keySet().stream().toList());
        assertThrows(UnsupportedOperationException.class,
                () -> transition.acceptedPayload().put("extra", ContractValue.bool(true)));
        assertNotSame(ContractValue.nullValue(), ContractValue.text("null"));
    }

    @Test
    void rejectsDuplicateAndAmbiguousControlIdentityWithAJsonPathDiagnostic() {
        ContractViolationException failure = assertThrows(
                ContractViolationException.class,
                () -> new StateActionContract(
                        ContractVersion.V1, "state-a", 1, 1,
                        List.of(
                                control("seed", ControlKind.TEXT, ContractValue.text("1")),
                                control("seed", ControlKind.TEXT, ContractValue.text("2"))),
                        List.of("seed"), "seed", List.of(), List.of(), null));

        assertEquals("$.controls[1].id", failure.path());
        assertEquals("unique stable control ID", failure.expected());
        assertEquals("seed", failure.observed());
        assertEquals("state-action/v1.0", failure.schemaVersion());
    }

    @Test
    void rejectsUnknownMajorAndBrokenReferences() {
        ContractViolationException versionFailure = assertThrows(
                ContractViolationException.class,
                () -> new StateActionContract(
                        new ContractVersion(2, 0), "state-a", 1, 1,
                        List.of(control("seed", ControlKind.TEXT, ContractValue.text("1"))),
                        List.of("seed"), "seed", List.of(), List.of(), null));
        assertEquals("$.schemaVersion", versionFailure.path());
        assertTrue(versionFailure.expected().contains("major 1"));

        ContractViolationException focusFailure = assertThrows(
                ContractViolationException.class,
                () -> new StateActionContract(
                        ContractVersion.V1, "state-a", 1, 1,
                        List.of(control("seed", ControlKind.TEXT, ContractValue.text("1"))),
                        List.of("missing"), null, List.of(), List.of(), null));
        assertEquals("$.focusOrder[0]", focusFailure.path());
    }

    @Test
    void rejectedTransitionCannotPublishAnAcceptedPayload() {
        ContractViolationException failure = assertThrows(
                ContractViolationException.class,
                () -> new TransitionOutcome(
                        "start-battle", false, "invalid", "state-a", 2,
                        new ValidationStatus(false, List.of("Seed must be an unsigned integer")),
                        TransitionKind.NONE, null,
                        Map.of("seed", ContractValue.text("-1"))));

        assertEquals("$.transition.acceptedPayload", failure.path());
        assertEquals("empty when accepted is false", failure.expected());
    }

    private static ControlState control(
            String id, ControlKind kind, ContractValue currentValue) {
        return new ControlState(
                id, Role.TEXT_FIELD, kind, id, List.of(),
                ContractValue.nullValue(), currentValue,
                true, true, true, true, false,
                new ValidationRule("text", null, null, null),
                new ValidationStatus(true, List.of()));
    }
}
