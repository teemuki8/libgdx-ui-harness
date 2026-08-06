package dev.gdx.uiharness.fixtures;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Real LWJGL3 navigation fixture: the reference process serves the production MCP server, and
 * {@code ui_navigation_inspect} traverses real keyboard focus through real input dispatch.
 */
final class NavigationProductionFixtureTest {
    private static final String SESSION_ID = "reference-ui";

    @Test
    @Timeout(120)
    void navigationInspectionRunsThroughProductionMcpWithRealInput() throws Exception {
        try (ReferenceProcess app = ReferenceProcess.launch()) {
            try (HarnessMcpClient client = HarnessMcpClient.connect(app)) {
                assertTrue(client.capabilities(SESSION_ID)
                        .contains("ui_navigation_inspect"));

                JsonNode result = client.navigate(SESSION_ID, List.of("tab", "tab"), 5_000);
                JsonNode path = result.path("result").path("path");

                assertFalse(path.path("defaultFocusIdentity").asText().isEmpty(),
                        "traversal must start from an observed default focus");
                assertTrue(path.path("steps").size() >= 1,
                        "real input must produce at least one correlated step");
                JsonNode first = path.path("steps").get(0);
                assertTrue(first.path("afterFrame").asLong() > first.path("beforeFrame").asLong(),
                        "steps must correlate strictly later completed frames");
                assertFalse(first.path("afterIdentity").asText().isEmpty(),
                        "steps must carry a stable semantic after-identity");
                assertFalse(path.path("reason").asText().equals("UNSUPPORTED_CONTROLLER_PATH"));
            }
        }
    }
}
