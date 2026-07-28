package dev.gdx.uiharness.core.locator;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.regex.PatternSyntaxException;
import org.junit.jupiter.api.Test;

final class LocatorEngineTest {
    private final LocatorEngine engine = new StrictResolution(HarnessLimits.defaults());

    @Test void roleAndNameResolveAfterActorReplacement() {
        Locator locator = Locator.role(Role.BUTTON).withName(TextMatch.exact("Save"));

        assertEquals("old", engine.resolveStrict(snapshotWithButton("old"), locator).id());
        assertEquals("new", engine.resolveStrict(snapshotWithButton("new"), locator).id());
    }

    @Test void labelTestIdAndActorFallbackLocatorsUseTheirOwnFields() {
        SemanticSnapshot snapshot = snapshot(
                node("root", null, List.of("field", "named", "typed"), Role.GROUP),
                node("field", "root", List.of(), Role.TEXT_FIELD, "", "", "User name",
                        "username", "login-field", "TextField", defaultState()),
                node("named", "root", List.of(), Role.GENERIC, "", "", null,
                        null, "inventory-grid", "Table", defaultState()),
                node("typed", "root", List.of(), Role.GENERIC, "", "", null,
                        null, "confirm", "Dialog", defaultState()));

        assertEquals(List.of("field"), ids(engine.query(snapshot,
                Locator.label(TextMatch.exact("User name")))));
        assertEquals(List.of("field"), ids(engine.query(snapshot, Locator.testId("username"))));
        assertEquals(List.of("named"), ids(engine.query(snapshot,
                Locator.actorName(TextMatch.substring("inventory")))));
        assertEquals(List.of("typed"), ids(engine.query(snapshot,
                Locator.actorType(TextMatch.exact("Dialog")))));
    }

    @Test void textModesNormalizeUnicodeWhitespaceBeforeMatching() {
        SemanticSnapshot snapshot = snapshot(
                node("root", null, List.of("exact", "case", "substring", "regex"), Role.GROUP),
                textNode("exact", "root", "  Save\u00a0\tgame  "),
                textNode("case", "root", "STRASSE"),
                textNode("substring", "root", "Welcome,\u2003Ada!"),
                textNode("regex", "root", "Score:\n  42 points"));

        assertEquals(List.of("exact"), ids(engine.query(snapshot,
                Locator.text(TextMatch.exact("Save game")))));
        assertEquals(List.of("case"), ids(engine.query(snapshot,
                Locator.text(TextMatch.caseInsensitiveExact("strasse")))));
        assertEquals(List.of("substring"), ids(engine.query(snapshot,
                Locator.text(TextMatch.substring("come, Ada")))));
        assertEquals(List.of("regex"), ids(engine.query(snapshot,
                Locator.text(TextMatch.regex("\\d+ points")))));
    }

    @Test void invalidOrOversizedTextPatternsFailAtConstruction() {
        assertThrows(PatternSyntaxException.class, () -> TextMatch.regex("["));
        assertThrows(IllegalArgumentException.class,
                () -> TextMatch.substring("x".repeat(16_385)));
        assertThrows(IllegalArgumentException.class,
                () -> Locator.testId("x".repeat(16_385)));
    }

    @Test void childDescendantParentAndSiblingRelationsReturnRelatedTargets() {
        SemanticSnapshot snapshot = relationSnapshot();
        Locator primary = Locator.role(Role.GROUP).withName(TextMatch.exact("Primary"));

        assertEquals(List.of("save-a"), ids(engine.query(snapshot,
                primary.child(Locator.role(Role.BUTTON)))));
        assertEquals(List.of("deep"), ids(engine.query(snapshot,
                primary.descendant(Locator.testId("deep")))));
        assertEquals(List.of("panel-a"), ids(engine.query(snapshot,
                Locator.testId("save-a").parent(Locator.role(Role.GROUP)))));
        assertEquals(List.of("label-a"), ids(engine.query(snapshot,
                Locator.testId("save-a").sibling(Locator.role(Role.LABEL)))));
    }

    @Test void hasAndHasTextKeepTheOuterLocator() {
        SemanticSnapshot snapshot = relationSnapshot();
        Locator primary = Locator.role(Role.GROUP).withName(TextMatch.exact("Primary"));

        assertEquals(List.of("panel-a"), ids(engine.query(snapshot,
                primary.has(Locator.testId("deep")))));
        assertEquals(List.of("panel-a"), ids(engine.query(snapshot,
                primary.hasText(TextMatch.substring("Nested value")))));
    }

    @Test void stateFiltersMatchSupportedValuesAndDoNotTreatUnsupportedAsFalse() {
        SemanticState state = new SemanticState(
                false,
                true,
                Optional.of(false),
                Optional.of(true),
                Optional.of(false),
                Optional.of(true),
                Optional.of(false),
                true,
                false,
                0.5,
                true,
                false,
                true);
        SemanticSnapshot snapshot = snapshot(
                node("root", null, List.of("target", "unsupported"), Role.GROUP),
                node("target", "root", List.of(), Role.CHECKBOX, "target", "", null,
                        null, null, null, state),
                node("unsupported", "root", List.of(), Role.CHECKBOX, "unsupported", "", null,
                        null, null, null, unsupportedEnabledState()));
        Map<LocatorFilter.State, Boolean> expected = new LinkedHashMap<>();
        expected.put(LocatorFilter.State.VISIBLE, false);
        expected.put(LocatorFilter.State.TOUCHABLE, true);
        expected.put(LocatorFilter.State.ENABLED, false);
        expected.put(LocatorFilter.State.CHECKED, true);
        expected.put(LocatorFilter.State.SELECTED, false);
        expected.put(LocatorFilter.State.EXPANDED, true);
        expected.put(LocatorFilter.State.EDITABLE, false);
        expected.put(LocatorFilter.State.FOCUSED, true);
        expected.put(LocatorFilter.State.FOCUSABLE, false);
        expected.put(LocatorFilter.State.CLIPPED, true);
        expected.put(LocatorFilter.State.VIEWPORT_INTERSECTING, false);
        expected.put(LocatorFilter.State.HIT_TARGET, true);

        for (Map.Entry<LocatorFilter.State, Boolean> entry : expected.entrySet()) {
            Locator locator = Locator.testId("missing").filter(
                    LocatorFilter.state(entry.getKey(), entry.getValue()));
            assertEquals(List.of(), ids(engine.query(snapshot, locator)));
            locator = Locator.role(Role.CHECKBOX).withName(TextMatch.exact("target")).filter(
                    LocatorFilter.state(entry.getKey(), entry.getValue()));
            assertEquals(List.of("target"), ids(engine.query(snapshot, locator)),
                    entry.getKey().name());
        }
        assertEquals(List.of(), ids(engine.query(snapshot,
                Locator.role(Role.CHECKBOX).withName(TextMatch.exact("unsupported")).filter(
                        LocatorFilter.state(LocatorFilter.State.ENABLED, false)))));
    }

    @Test void indexIsZeroBasedAndPublishesFragilityEvidence() {
        QueryResult result = engine.query(relationSnapshot(),
                Locator.role(Role.BUTTON).atIndex(1));

        assertEquals(List.of("save-a"), ids(result));
        assertTrue(result.fragileIndex());
        assertEquals("1", result.evidence().getFirst().get("index"));
    }

    @Test void traversalUsesChildOrderRatherThanNodeMapOrder() {
        SemanticSnapshot snapshot = relationSnapshot();

        assertEquals(List.of("save-b", "save-a"), ids(engine.query(snapshot,
                Locator.role(Role.BUTTON))));
    }

    @Test void queryStopsAtOnePastTheConfiguredResultLimit() {
        HarnessLimits limits = new HarnessLimits(
                100, 20, 1, 16_384, 1_048_576, Duration.ofSeconds(1));
        LocatorEngine bounded = new StrictResolution(limits);

        HarnessException error = assertThrows(HarnessException.class,
                () -> bounded.query(relationSnapshot(), Locator.role(Role.BUTTON)));

        assertEquals(ErrorCode.LIMIT_EXCEEDED, error.code());
        assertEquals("2", error.evidence().details().get("actual"));
        assertEquals("matches", error.evidence().details().get("dimension"));
    }

    @Test void traversalEnforcesConfiguredNodeAndDepthLimits() {
        HarnessLimits nodeLimits = new HarnessLimits(
                3, 20, 10, 16_384, 1_048_576, Duration.ofSeconds(1));
        HarnessLimits depthLimits = new HarnessLimits(
                100, 1, 10, 16_384, 1_048_576, Duration.ofSeconds(1));

        HarnessException nodeError = assertThrows(HarnessException.class,
                () -> new StrictResolution(nodeLimits).query(
                        relationSnapshot(), Locator.role(Role.BUTTON)));
        HarnessException depthError = assertThrows(HarnessException.class,
                () -> new StrictResolution(depthLimits).query(
                        relationSnapshot(), Locator.role(Role.BUTTON)));

        assertEquals("nodes", nodeError.evidence().details().get("dimension"));
        assertEquals("depth", depthError.evidence().details().get("dimension"));
    }

    private static SemanticSnapshot snapshotWithButton(String id) {
        return snapshot(
                node("root", null, List.of(id), Role.GROUP),
                node(id, "root", List.of(), Role.BUTTON, "Save", "Save", null,
                        null, id, "TextButton", defaultState()));
    }

    private static SemanticSnapshot relationSnapshot() {
        SemanticNode root = node("root", null, List.of("panel-b", "panel-a"), Role.GROUP);
        SemanticNode panelB = node("panel-b", "root", List.of("save-b", "label-b"),
                Role.GROUP, "Secondary", "", null, null, null, "Table", defaultState());
        SemanticNode saveB = node("save-b", "panel-b", List.of(), Role.BUTTON,
                "Save", "Save", null, "save-b", null, "TextButton", defaultState());
        SemanticNode labelB = node("label-b", "panel-b", List.of(), Role.LABEL,
                "", "Secondary label", null, null, null, "Label", defaultState());
        SemanticNode panelA = node("panel-a", "root", List.of("label-a", "save-a", "nested"),
                Role.GROUP, "Primary", "", null, null, null, "Table", defaultState());
        SemanticNode labelA = node("label-a", "panel-a", List.of(), Role.LABEL,
                "", "Primary label", null, null, null, "Label", defaultState());
        SemanticNode saveA = node("save-a", "panel-a", List.of(), Role.BUTTON,
                "Save", "Save", null, "save-a", null, "TextButton", defaultState());
        SemanticNode nested = node("nested", "panel-a", List.of("deep"), Role.GROUP,
                "Nested", "", null, null, null, "Table", defaultState());
        SemanticNode deep = node("deep", "nested", List.of(), Role.LABEL,
                "", "Nested value", null, "deep", null, "Label", defaultState());
        return snapshot(root, panelB, saveB, labelB, panelA, labelA, saveA, nested, deep);
    }

    private static SemanticNode textNode(String id, String parentId, String text) {
        return node(id, parentId, List.of(), Role.LABEL, "", text, null,
                null, null, "Label", defaultState());
    }

    private static SemanticNode node(
            String id, String parentId, List<String> children, Role role) {
        return node(id, parentId, children, role, id, "", null,
                null, null, null, defaultState());
    }

    private static SemanticNode node(
            String id,
            String parentId,
            List<String> children,
            Role role,
            String name,
            String text,
            String label,
            String testId,
            String actorName,
            String actorType,
            SemanticState state) {
        Bounds bounds = new Bounds(0, 0, 10, 10);
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

    private static SemanticState defaultState() {
        return new SemanticState(
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
    }

    private static SemanticState unsupportedEnabledState() {
        return new SemanticState(
                true,
                true,
                Optional.empty(),
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
    }

    private static SemanticSnapshot snapshot(SemanticNode... nodes) {
        Map<String, SemanticNode> byId = new LinkedHashMap<>();
        for (int index = nodes.length - 1; index >= 0; index--) {
            byId.put(nodes[index].id(), nodes[index]);
        }
        return new SemanticSnapshot(7, 11, "root", byId);
    }

    private static List<String> ids(QueryResult result) {
        return result.matches().stream().map(SemanticNode::id).toList();
    }
}
