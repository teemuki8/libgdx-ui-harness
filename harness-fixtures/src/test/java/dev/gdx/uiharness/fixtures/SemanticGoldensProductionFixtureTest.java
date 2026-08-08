package dev.gdx.uiharness.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Real LWJGL3 semantic-golden fixture: the reference process serves the production MCP server
 * with its committed baseline resource pre-registered; {@code ui_semantic_compare} compares
 * against that immutable baseline only. An unknown or misspelled baseline id returns a typed
 * {@code LOCATOR_NOT_FOUND} diagnostic and the fixture never learns a baseline from the current
 * UI.
 */
final class SemanticGoldensProductionFixtureTest {
    private static final String SESSION_ID = "reference-ui";
    private static final String BASELINE_ID = "reference-screen";

    private static boolean hasTextDrift(JsonNode comparison) {
        for (JsonNode difference : comparison.path("differences")) {
            if (!"CHANGED".equals(difference.path("kind").asText())) {
                continue;
            }
            for (JsonNode path : difference.path("propertyPaths")) {
                if ("text".equals(path.asText())) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    @Timeout(120)
    void registeredBaselineDetectsDriftThroughProductionMcp() throws Exception {
        try (ReferenceProcess app = ReferenceProcess.launch()) {
            try (HarnessMcpClient client = HarnessMcpClient.connect(app)) {
                JsonNode pristine = client.semanticCompare(SESSION_ID, BASELINE_ID, 5_000);
                assertTrue(pristine.path("comparedNodes").asInt() > 0);
                assertTrue(!hasTextDrift(pristine),
                        "the pristine screen must match its pre-registered resource baseline");

                client.fillByLabel(SESSION_ID, "Username", "Ada");

                JsonNode drifted = client.semanticCompare(SESSION_ID, BASELINE_ID, 5_000);
                assertTrue(drifted.path("comparedNodes").asInt() > 0);
                assertTrue(hasTextDrift(drifted),
                        "the fill must be detected as text drift against the resource baseline");
            }
        }
    }

    @Test
    @Timeout(120)
    void unknownBaselineReturnsTypedNotFoundAndNeverLearnsFromTheUi() throws Exception {
        try (ReferenceProcess app = ReferenceProcess.launch()) {
            try (HarnessMcpClient client = HarnessMcpClient.connect(app)) {
                client.fillByLabel(SESSION_ID, "Username", "Ada");

                for (String misspelled : List.of("reference-scren", "unknown-golden")) {
                    JsonNode error = client.semanticCompareFailure(SESSION_ID, misspelled, 5_000);
                    assertEquals("LOCATOR_NOT_FOUND", error.path("code").asText(), error.toString());
                    assertTrue(error.path("message").asText().contains(misspelled),
                            error.toString());
                }

                JsonNode known = client.semanticCompare(SESSION_ID, BASELINE_ID, 5_000);
                assertTrue(hasTextDrift(known),
                        "the filled UI must still drift against the pre-registered baseline");
            }
        }
    }
}
