package dev.gdx.uiharness.fixtures;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Real LWJGL3 semantic-golden fixture: the reference process serves the production MCP server;
 * {@code ui_semantic_compare} registers a baseline from the pristine screen, then a fill action
 * is detected as a text drift against that retained baseline. The reference screen intentionally
 * carries duplicate hidden test ids, so the fixture asserts drift detection rather than a blanket
 * {@code matched} flag.
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
    void baselineRegistersAndDetectsDriftThroughProductionMcp() throws Exception {
        try (ReferenceProcess app = ReferenceProcess.launch()) {
            try (HarnessMcpClient client = HarnessMcpClient.connect(app)) {
                JsonNode pristine = client.semanticCompare(SESSION_ID, BASELINE_ID, 5_000);
                assertTrue(pristine.path("comparedNodes").asInt() > 0);
                assertTrue(!hasTextDrift(pristine),
                        "the pristine screen must not drift from its own baseline");

                client.fillByLabel(SESSION_ID, "Username", "Ada");

                JsonNode drifted = client.semanticCompare(SESSION_ID, BASELINE_ID, 5_000);
                assertTrue(drifted.path("comparedNodes").asInt() > 0);
                assertTrue(hasTextDrift(drifted),
                        "the fill must be detected as text drift against the retained baseline");
            }
        }
    }
}
