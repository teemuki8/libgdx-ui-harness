package dev.gdx.uiharness.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.gdx.uiharness.core.contract.ContractValue;
import dev.gdx.uiharness.core.contract.ContractVersion;
import dev.gdx.uiharness.core.contract.ControlKind;
import dev.gdx.uiharness.core.contract.ControlState;
import dev.gdx.uiharness.core.contract.StateActionContract;
import dev.gdx.uiharness.core.contract.ValidationRule;
import dev.gdx.uiharness.core.contract.ValidationStatus;
import dev.gdx.uiharness.core.model.Role;
import java.util.List;
import org.junit.jupiter.api.Test;

final class StateActionProtocolContractTest {
    @Test
    void roundTripsThePublicContractWithoutJavaTypeMetadata() throws Exception {
        HarnessResponse.ContractData source =
                HarnessResponse.ContractData.fromCore(contract());

        byte[] json = ProtocolJson.mapper().writeValueAsBytes(source);
        HarnessResponse.ContractData decoded = ProtocolJson.mapper().readValue(
                json, HarnessResponse.ContractData.class);

        assertEquals(source, decoded);
        String encoded = new String(json, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(encoded.contains("\"schemaVersion\":\"state-action/v1.0\""));
        assertTrue(encoded.contains("\"stateId\":\"state-1\""));
        assertTrue(!encoded.contains("dev.gdx") && !encoded.contains("@class"));
    }

    @Test
    void unknownMajorAndDuplicateControlIdsFailClosedDuringDecode() throws Exception {
        JsonNode valid = ProtocolJson.mapper().valueToTree(
                HarnessResponse.ContractData.fromCore(contract()));
        ObjectNode unknown = valid.deepCopy();
        unknown.put("schemaVersion", "state-action/v2.0");
        assertThrows(Exception.class, () -> ProtocolJson.mapper().treeToValue(
                unknown, HarnessResponse.ContractData.class));

        ObjectNode duplicate = valid.deepCopy();
        duplicate.withArray("controls").add(valid.path("controls").get(0).deepCopy());
        Exception failure = assertThrows(Exception.class,
                () -> ProtocolJson.mapper().treeToValue(
                        duplicate, HarnessResponse.ContractData.class));
        assertTrue(failure.getMessage().contains("$.controls[1].id"));
    }

    private static StateActionContract contract() {
        ControlState seed = new ControlState(
                "seed", Role.TEXT_FIELD, ControlKind.TEXT, "Seed", List.of(),
                ContractValue.text("generatedUint32"), ContractValue.text("0"),
                true, true, true, true, true,
                new ValidationRule(
                        "uint32-decimal", ContractValue.integer(0),
                        ContractValue.integer(4_294_967_295L), ContractValue.integer(1)),
                new ValidationStatus(true, List.of()));
        return new StateActionContract(
                ContractVersion.V1, "state-1", 1, 2, List.of(seed), List.of("seed"),
                "seed", List.of(), List.of(), null);
    }
}
