package dev.gdx.uiharness.core.locator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gdx.uiharness.core.error.ErrorCode;
import dev.gdx.uiharness.core.error.HarnessException;
import dev.gdx.uiharness.core.limits.HarnessLimits;
import dev.gdx.uiharness.core.model.Bounds;
import dev.gdx.uiharness.core.model.Role;
import dev.gdx.uiharness.core.model.SemanticNode;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import dev.gdx.uiharness.core.model.SemanticState;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class LocatorSuggestionEngineTest {
    private final StrictResolution engine = new StrictResolution(HarnessLimits.defaults());

    @Test
    void zeroMatchSuggestsUniqueTestIdsThatResolveAgainstTheSameSnapshot() {
        HarnessException error = assertThrows(HarnessException.class,
                () -> engine.resolveStrict(twoSaveButtons(), Locator.testId("missing")));

        assertEquals(ErrorCode.NOT_FOUND, error.code());
        List<LocatorSuggestion> suggestions = error.evidence().suggestions();
        assertEquals(2, suggestions.size());
        assertSuggestion(suggestions.getFirst(), "right-save", Stability.STABLE,
                "unique test identifier");
        assertSuggestion(suggestions.getLast(), "left-save", Stability.STABLE,
                "unique test identifier");
    }

    @Test
    void multipleMatchSuggestsPerCandidateUniqueSelectorsWithDistinctions() {
        HarnessException error = assertThrows(HarnessException.class,
                () -> engine.resolveStrict(twoSaveButtons(), Locator.text(TextMatch.exact("Save"))));

        assertEquals(ErrorCode.STRICTNESS_VIOLATION, error.code());
        List<LocatorSuggestion> suggestions = error.evidence().suggestions();
        assertEquals(2, suggestions.size());
        assertSuggestion(suggestions.getFirst(), "right-save", Stability.STABLE,
                "unique test identifier");
        assertSuggestion(suggestions.getLast(), "left-save", Stability.STABLE,
                "unique test identifier");
        assertTrue(suggestions.getFirst().distinctions().stream()
                .anyMatch(distinction -> "testId".equals(distinction.field())
                        && "right".equals(distinction.value())));
        assertTrue(suggestions.getLast().distinctions().stream()
                .anyMatch(distinction -> "testId".equals(distinction.field())
                        && "left".equals(distinction.value())));
    }

    @Test
    void rankingPrefersTestIdThenRoleAndNameThenLabel() {
        HarnessException error = assertThrows(HarnessException.class,
                () -> engine.resolveStrict(rankingButtons(), Locator.testId("missing")));

        List<LocatorSuggestion> suggestions = error.evidence().suggestions();
        assertEquals(3, suggestions.size());
        assertSuggestion(suggestions.get(0), "submit", Stability.STABLE,
                "unique test identifier");
        assertSuggestion(suggestions.get(1), "cancel", Stability.STABLE,
                "role and accessible name");
        assertSuggestion(suggestions.get(2), "delete", Stability.STABLE,
                "associated label");
    }

    @Test
    void duplicateTestIdsFallBackToRoleAndAccessibleName() {
        HarnessException error = assertThrows(HarnessException.class,
                () -> engine.resolveStrict(duplicateTestIdButtons(), Locator.role(Role.BUTTON)));

        List<LocatorSuggestion> suggestions = error.evidence().suggestions();
        assertEquals(2, suggestions.size());
        assertSuggestion(suggestions.get(0), "one", Stability.STABLE,
                "role and accessible name");
        assertSuggestion(suggestions.get(1), "two", Stability.STABLE,
                "role and accessible name");
        assertNotEquals(suggestions.get(0).locator(), suggestions.get(1).locator());
    }

    @Test
    void fragileActorAndPositionalFallbacksAreMarkedFragile() {
        HarnessException actorError = assertThrows(HarnessException.class,
                () -> engine.resolveStrict(actorOnlyButtons(), Locator.testId("missing")));
        List<LocatorSuggestion> actorSuggestions = actorError.evidence().suggestions();
        assertEquals(1, actorSuggestions.size());
        assertSuggestion(actorSuggestions.getFirst(), "mystery", Stability.FRAGILE,
                "backend actor name");

        HarnessException indexError = assertThrows(HarnessException.class,
                () -> engine.resolveStrict(indistinguishableButtons(), Locator.role(Role.BUTTON)));
        List<LocatorSuggestion> indexSuggestions = indexError.evidence().suggestions();
        assertEquals(2, indexSuggestions.size());
        assertSuggestion(indexSuggestions.get(0), "first", Stability.FRAGILE,
                "positional index");
        assertSuggestion(indexSuggestions.get(1), "second", Stability.FRAGILE,
                "positional index");
    }

    @Test
    void identicalSnapshotsProduceDeterministicSuggestions() {
        HarnessException first = assertThrows(HarnessException.class,
                () -> engine.resolveStrict(twoSaveButtons(), Locator.testId("missing")));
        HarnessException second = assertThrows(HarnessException.class,
                () -> engine.resolveStrict(twoSaveButtonsShuffled(), Locator.testId("missing")));

        assertEquals(suggestionKeys(first.evidence().suggestions()),
                suggestionKeys(second.evidence().suggestions()));
    }

    @Test
    void suggestionBurstIsBoundedAndTruncationIsReported() {
        HarnessException error = assertThrows(HarnessException.class,
                () -> engine.resolveStrict(twelveButtons(), Locator.testId("missing")));

        assertEquals(LocatorSuggestionEngine.MAX_SUGGESTIONS, error.evidence().suggestions().size());
        assertEquals("true", error.evidence().details().get("suggestionsTruncated"));
    }

    private static void assertSuggestion(
            LocatorSuggestion suggestion,
            String expectedIdentity,
            Stability expectedStability,
            String expectedRationale) {
        assertEquals(expectedIdentity, suggestion.candidateIdentity());
        assertEquals(expectedStability, suggestion.stability());
        assertEquals(expectedRationale, suggestion.rationale());
    }

    private static List<String> suggestionKeys(List<LocatorSuggestion> suggestions) {
        return suggestions.stream()
                .map(suggestion -> suggestion.locator().toString() + "|" + suggestion.stability()
                        + "|" + suggestion.rationale() + "|" + suggestion.candidateIdentity())
                .toList();
    }

    private SemanticSnapshot twoSaveButtons() {
        SemanticNode root = node(
                "root", null, List.of("right-pane", "left-pane"), Role.GROUP,
                "root", "", null, null, null);
        SemanticNode rightPane = node(
                "right-pane", "root", List.of("right-save"), Role.GROUP,
                "Right pane", "", "right-pane", null, null);
        SemanticNode rightSave = node(
                "right-save", "right-pane", List.of(), Role.BUTTON,
                "Save", "Save", "right", null, "TextButton");
        SemanticNode leftPane = node(
                "left-pane", "root", List.of("left-save"), Role.GROUP,
                "Left pane", "", "left-pane", null, null);
        SemanticNode leftSave = node(
                "left-save", "left-pane", List.of(), Role.BUTTON,
                "Save", "Save", "left", null, "TextButton");
        return snapshot(7, "root", leftSave, leftPane, rightSave, rightPane, root);
    }

    private SemanticSnapshot twoSaveButtonsShuffled() {
        SemanticNode root = node(
                "root", null, List.of("right-pane", "left-pane"), Role.GROUP,
                "root", "", null, null, null);
        SemanticNode leftPane = node(
                "left-pane", "root", List.of("left-save"), Role.GROUP,
                "Left pane", "", "left-pane", null, null);
        SemanticNode leftSave = node(
                "left-save", "left-pane", List.of(), Role.BUTTON,
                "Save", "Save", "left", null, "TextButton");
        SemanticNode rightPane = node(
                "right-pane", "root", List.of("right-save"), Role.GROUP,
                "Right pane", "", "right-pane", null, null);
        SemanticNode rightSave = node(
                "right-save", "right-pane", List.of(), Role.BUTTON,
                "Save", "Save", "right", null, "TextButton");
        return snapshot(8, "root", leftSave, leftPane, rightSave, rightPane, root);
    }

    private SemanticSnapshot rankingButtons() {
        SemanticNode root = node(
                "root", null, List.of("submit", "cancel", "delete"), Role.GROUP,
                "root", "", null, null, null);
        SemanticNode submit = node(
                "submit", "root", List.of(), Role.BUTTON,
                "Submit", "Submit now", "submit", "Form submit", "TextButton");
        SemanticNode cancel = node(
                "cancel", "root", List.of(), Role.BUTTON,
                "Cancel", "Cancel", null, "Close", "TextButton");
        SemanticNode delete = node(
                "delete", "root", List.of(), Role.BUTTON,
                null, "Delete", null, "Delete file", "TextButton");
        return snapshot(9, "root", delete, cancel, submit, root);
    }

    private SemanticSnapshot duplicateTestIdButtons() {
        SemanticNode root = node(
                "root", null, List.of("one", "two"), Role.GROUP,
                "root", "", null, null, null);
        SemanticNode one = node(
                "one", "root", List.of(), Role.BUTTON,
                "One", "One", "dup", null, "TextButton");
        SemanticNode two = node(
                "two", "root", List.of(), Role.BUTTON,
                "Two", "Two", "dup", null, "TextButton");
        return snapshot(10, "root", two, one, root);
    }

    private SemanticSnapshot actorOnlyButtons() {
        SemanticNode root = node(
                "root", null, List.of("mystery"), Role.GROUP,
                "root", "", null, null, null);
        SemanticNode mystery = node(
                "mystery", "root", List.of(), Role.BUTTON,
                null, null, null, null, "mysteryButton", "ImageButton");
        return snapshot(11, "root", mystery, root);
    }

    private SemanticSnapshot indistinguishableButtons() {
        SemanticNode root = node(
                "root", null, List.of("first", "second"), Role.GROUP,
                "root", "", null, null, null);
        SemanticNode first = node(
                "first", "root", List.of(), Role.BUTTON,
                null, null, null, "generic", "TextButton");
        SemanticNode second = node(
                "second", "root", List.of(), Role.BUTTON,
                null, null, null, "generic", "TextButton");
        return snapshot(12, "root", second, first, root);
    }

    private SemanticSnapshot twelveButtons() {
        var children = new LinkedHashMap<String, SemanticNode>();
        var childIds = new java.util.ArrayList<String>();
        for (int index = 1; index <= 12; index++) {
            String id = "button-" + index;
            childIds.add(id);
            children.put(id, node(
                    id, "root", List.of(), Role.BUTTON,
                    "Button " + index, "Button " + index, "tid" + index, null, "TextButton"));
        }
        SemanticNode root = node(
                "root", null, childIds, Role.GROUP,
                "root", "", null, null, null);
        children.put("root", root);
        return new SemanticSnapshot(13, 1, "root", children);
    }

    private static SemanticSnapshot snapshot(
            long revision, String rootId, SemanticNode... nodes) {
        var byId = new LinkedHashMap<String, SemanticNode>();
        for (SemanticNode node : nodes) {
            byId.put(node.id(), node);
        }
        return new SemanticSnapshot(revision, 1, rootId, byId);
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
            String actorType) {
        return node(id, parentId, children, role, name, text, testId, label, null, actorType);
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
