package dev.gdx.uiharness.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * End-to-end proof that a screen built entirely from libgdx-ui-markup markup is drivable
 * through the production harness MCP: role/name locators resolve to markup-declared test
 * identifiers (semantics by construction), real input changes widget state, the runtime value
 * compares EQUAL through {@code ui_runtime_compare}, and a PNG is captured.
 */
final class MarkupFixtureEndToEndTest {
    private static final String SESSION_ID = "reference-ui";

    @Test
    @Timeout(120)
    void markupDeclaredScreenIsDrivableThroughTheProductionMcp() throws Exception {
        try (ReferenceProcess app = ReferenceProcess.launch("markup")) {
            try (HarnessMcpClient client = HarnessMcpClient.connect(app)) {
                // Semantics by construction: the markup id became the harness test identifier.
                JsonNode save = client.queryByRoleAndName(SESSION_ID, "button", "Save");
                assertEquals("save", save.path("testId").asText(),
                        "the markup id became the harness test identifier");
                assertEquals("button", save.path("role").asText());

                // Real input path: clicking the markup checkbox flips its checked state.
                client.clickByRoleAndName(SESSION_ID, "checkbox", "Remember me");
                client.waitForState(SESSION_ID, Map.of("kind", "test-id", "testId", "remember"),
                        "checked", true, 5_000);

                // Runtime hop: authoritative model and initial markup state compare EQUAL.
                JsonNode comparison = client.runtimeCompare(SESSION_ID, 5_000);
                assertEquals("EQUAL", comparison.path("status").asText(),
                        "the markup runtime entity compares on the proven frame");
                assertEquals("user", comparison.path("entityId").asText());
                assertEquals("value", comparison.path("propertyId").asText());
                assertEquals("Ada", comparison.path("displayedValue").asText());
                assertEquals("Ada", comparison.path("runtimeValue").asText());

                client.fillByLabel(SESSION_ID, "Username", "Grace");
                JsonNode filled = client.runtimeCompare(SESSION_ID, 5_000);
                assertEquals("EQUAL", filled.path("status").asText());
                assertEquals("Grace", filled.path("displayedValue").asText());
                assertEquals("Grace", filled.path("runtimeValue").asText());

                // Authority proof: a fixture-only real-input action changes only domain state.
                // Widget-mirror registration would incorrectly keep reporting EQUAL here.
                client.clickByRoleAndName(SESSION_ID, "button", "Diverge model");
                JsonNode mismatch = client.runtimeCompare(SESSION_ID, 5_000);
                assertEquals("MISMATCH", mismatch.path("status").asText());
                assertEquals("Grace", mismatch.path("displayedValue").asText());
                assertEquals("Carol", mismatch.path("runtimeValue").asText());

                HarnessMcpClient.Screenshot screenshot = client.screenshot(SESSION_ID);
                assertEquals(1280, screenshot.width());
                assertEquals(720, screenshot.height());
                assertTrue(screenshot.artifact().byteLength() > 100,
                        "the markup screen renders a non-trivial screenshot");
            }
            app.awaitCleanExit();
        }
    }
}
