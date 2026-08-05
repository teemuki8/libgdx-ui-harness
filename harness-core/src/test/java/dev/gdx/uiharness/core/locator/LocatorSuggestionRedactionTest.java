package dev.gdx.uiharness.core.locator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gdx.uiharness.core.error.ErrorCode;
import dev.gdx.uiharness.core.error.HarnessException;
import dev.gdx.uiharness.core.error.RedactionField;
import dev.gdx.uiharness.core.error.RedactionPolicies;
import dev.gdx.uiharness.core.error.RedactionPolicy;
import dev.gdx.uiharness.core.limits.HarnessLimits;
import dev.gdx.uiharness.core.model.Bounds;
import dev.gdx.uiharness.core.model.Role;
import dev.gdx.uiharness.core.model.SemanticNode;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import dev.gdx.uiharness.core.model.SemanticState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class LocatorSuggestionRedactionTest {
    private static final String SECRET = "s3cr3t";
    private static final RedactionPolicy FULL_REDACTION = new RedactionPolicy() {
        @Override public String id() {
            return "full-redaction-test";
        }

        @Override public String redact(RedactionField field, String value) {
            return "[redacted]";
        }
    };
    private static final RedactionPolicy TEST_ID_ONLY = new RedactionPolicy() {
        @Override public String id() {
            return "test-id-redaction-test";
        }

        @Override public String redact(RedactionField field, String value) {
            return field == RedactionField.TEST_ID ? "[redacted]" : value;
        }
    };

    @Test
    void fullRedactionHidesSensitiveValuesFromEveryDiagnosticChannel() {
        StrictResolution redacting = new StrictResolution(HarnessLimits.defaults(),
                FULL_REDACTION);
        HarnessException error = assertThrows(HarnessException.class,
                () -> redacting.resolveStrict(secretButtons(), Locator.testId("missing")));

        assertEquals(ErrorCode.NOT_FOUND, error.code());
        assertEquals("full-redaction-test", error.evidence().details().get("redactionPolicyId"));

        String rendered = error.getMessage()
                + error.evidence().locator().orElse("")
                + String.join("", error.evidence().details().values());
        for (Map<String, String> candidate : error.evidence().candidates()) {
            rendered += String.join("", candidate.values());
        }
        for (LocatorSuggestion suggestion : error.evidence().suggestions()) {
            rendered += suggestion.locator().toString() + suggestion.rationale()
                    + String.join("", suggestion.distinctions().stream()
                            .map(DistinguishingProperty::value)
                            .toList());
        }
        assertFalse(rendered.contains(SECRET));
        assertTrue(error.evidence().candidates().stream()
                .allMatch(candidate -> !candidate.containsValue(SECRET)));
    }

    @Test
    void redactedTestIdsFallBackToTheNextRankedVariant() {
        StrictResolution redacting = new StrictResolution(HarnessLimits.defaults(),
                TEST_ID_ONLY);
        HarnessException error = assertThrows(HarnessException.class,
                () -> redacting.resolveStrict(secretButtons(), Locator.testId("missing")));

        List<LocatorSuggestion> suggestions = error.evidence().suggestions();
        assertEquals(2, suggestions.size());
        assertEquals("admin", suggestions.getFirst().candidateIdentity());
        assertEquals("role and accessible name", suggestions.getFirst().rationale());
        assertEquals("user", suggestions.getLast().candidateIdentity());
        assertFalse(suggestions.stream()
                .map(suggestion -> suggestion.locator().toString())
                .anyMatch(rendered -> rendered.contains(SECRET)));
    }

    @Test
    void identityPolicyKeepsRawValuesAndReportsNone() {
        StrictResolution plain = new StrictResolution(HarnessLimits.defaults());
        HarnessException error = assertThrows(HarnessException.class,
                () -> plain.resolveStrict(secretButtons(), Locator.testId("missing")));

        assertEquals("none", error.evidence().details().get("redactionPolicyId"));
        assertEquals("unique test identifier", error.evidence().suggestions().getFirst()
                .rationale());
        assertTrue(error.evidence().suggestions().getFirst().locator().toString()
                .contains("admin-" + SECRET));
    }

    private static SemanticSnapshot secretButtons() {
        SemanticNode root = node(
                "root", null, List.of("admin", "user"), Role.GROUP,
                "root", "", null, null, null, null);
        SemanticNode admin = node(
                "admin", "root", List.of(), Role.BUTTON,
                "Admin Panel", "Save changes", "admin-" + SECRET,
                "Admin Label", "adminButton", "TextButton");
        SemanticNode user = node(
                "user", "root", List.of(), Role.BUTTON,
                "User Panel", "Save changes", "user-visible",
                "user-label", "userButton", "TextButton");
        var byId = new LinkedHashMap<String, SemanticNode>();
        byId.put("user", user);
        byId.put("root", root);
        byId.put("admin", admin);
        return new SemanticSnapshot(21, 1, "root", byId);
    }

    private static SemanticNode node(
            String id,
            String parentId,
            List<String> children,
            Role role,
            String name,
            String text,
            String testId,
            String label,
            String actorName,
            String actorType) {
        Bounds bounds = new Bounds(0, 0, 10, 10);
        SemanticState state = new SemanticState(
                true,
                true,
                Optional.of(true),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                false,
                true,
                1.0,
                false,
                true,
                true);
        return new SemanticNode(
                id,
                parentId,
                children,
                role,
                name,
                text,
                label,
                testId,
                actorName,
                actorType,
                state,
                bounds,
                bounds,
                bounds,
                0,
                Map.of());
    }
}
