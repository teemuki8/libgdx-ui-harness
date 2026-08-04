package dev.gdx.uiharness.core.assertion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gdx.uiharness.core.error.ErrorCode;
import dev.gdx.uiharness.core.error.HarnessException;
import dev.gdx.uiharness.core.locator.Locator;
import dev.gdx.uiharness.core.model.Bounds;
import dev.gdx.uiharness.core.model.Role;
import dev.gdx.uiharness.core.model.SemanticNode;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import dev.gdx.uiharness.core.model.SemanticState;
import dev.gdx.uiharness.core.time.Deadline;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class AssertionEvaluatorTest {
    private final AssertionEvaluator evaluator = new AssertionEvaluator();

    @Test void evaluatesEverySingleNodeBooleanAndTextVariant() {
        SemanticSnapshot snapshot = snapshot(node("target", true, Optional.of(true),
                Optional.of(true), true, "Ready to save", "Save", new Bounds(10, 10, 20, 20)));

        assertPassed(snapshot, new UiAssertion.Visible());
        assertFailed(snapshot, new UiAssertion.Hidden(), "visible");
        assertPassed(snapshot, new UiAssertion.Enabled());
        assertFailed(snapshot, new UiAssertion.Disabled(), "enabled");
        assertPassed(snapshot, new UiAssertion.Focused());
        assertPassed(snapshot, new UiAssertion.Checked());
        assertPassed(snapshot, new UiAssertion.TextEquals("Ready to save"));
        assertFailed(snapshot, new UiAssertion.TextEquals("Ready"), "Ready to save");
        assertPassed(snapshot, new UiAssertion.TextContains("to save"));
        assertFailed(snapshot, new UiAssertion.TextContains("cancel"), "Ready to save");
        assertPassed(snapshot, new UiAssertion.AccessibleNameExists());
    }

    @Test void hiddenRequiresAnExistingStrictMatch() {
        HarnessException missing = assertThrows(HarnessException.class,
                () -> evaluate(snapshot(), Locator.testId("missing"), new UiAssertion.Hidden()));
        assertEquals(ErrorCode.NOT_FOUND, missing.code());

        SemanticNode first = node("first", false, Optional.of(true), Optional.empty(), false,
                "", "First", new Bounds(0, 0, 1, 1));
        SemanticNode second = node("second", false, Optional.of(true), Optional.empty(), false,
                "", "Second", new Bounds(0, 0, 1, 1));
        HarnessException multiple = assertThrows(HarnessException.class,
                () -> evaluate(snapshot(first, second), Locator.role(Role.BUTTON),
                        new UiAssertion.Hidden()));
        assertEquals(ErrorCode.STRICTNESS_VIOLATION, multiple.code());
    }

    @Test void countEqualsUsesNonStrictCardinalityIncludingZeroAndMany() {
        SemanticSnapshot two = snapshot(
                node("first", true, Optional.of(true), Optional.empty(), false, "", "First", new Bounds(0, 0, 1, 1)),
                node("second", true, Optional.of(true), Optional.empty(), false, "", "Second", new Bounds(0, 0, 1, 1)));
        AssertionResult pass = evaluate(two, Locator.role(Role.BUTTON), new UiAssertion.CountEquals(2));
        assertEquals(AssertionResult.Status.PASSED, pass.status());
        assertEquals("2", pass.evidence().observed());
        assertEquals(AssertionResult.Status.FAILED,
                evaluate(two, Locator.role(Role.BUTTON), new UiAssertion.CountEquals(1)).status());
        assertEquals(AssertionResult.Status.PASSED,
                evaluate(two, Locator.testId("missing"), new UiAssertion.CountEquals(0)).status());
    }

    @Test void viewportContainmentIncludesSharedBoundaryAndRejectsCrossingIt() {
        Bounds viewport = new Bounds(0, 0, 100, 100);
        assertPassed(snapshot(node("target", true, Optional.of(true), Optional.empty(), false,
                "", "Target", new Bounds(80, 80, 20, 20))),
                new UiAssertion.BoundsInsideViewport(viewport));
        assertFailed(snapshot(node("target", true, Optional.of(true), Optional.empty(), false,
                "", "Target", new Bounds(80, 80, 20.01, 20))),
                new UiAssertion.BoundsInsideViewport(viewport), "80.0");
    }

    @Test void nonOverlapAllowsTouchingEdgesButRejectsPositiveAreaIntersection() {
        SemanticNode target = node("target", true, Optional.of(true), Optional.empty(), false,
                "", "Target", new Bounds(0, 0, 10, 10));
        SemanticNode touching = node("other", true, Optional.of(true), Optional.empty(), false,
                "", "Other", new Bounds(10, 0, 10, 10));
        assertPassed(snapshot(target, touching), new UiAssertion.DoesNotOverlap(Locator.testId("other")));

        SemanticNode overlapping = node("other", true, Optional.of(true), Optional.empty(), false,
                "", "Other", new Bounds(9.99, 0, 10, 10));
        assertFailed(snapshot(target, overlapping),
                new UiAssertion.DoesNotOverlap(Locator.testId("other")), "other");
    }

    @Test void checkedAndEnabledUnsupportedStatesFailRatherThanBecomingFalse() {
        SemanticSnapshot snapshot = snapshot(node("target", true, Optional.empty(), Optional.empty(), false,
                "", "Target", new Bounds(0, 0, 1, 1)));
        assertFailed(snapshot, new UiAssertion.Checked(), "unsupported");
        assertFailed(snapshot, new UiAssertion.Enabled(), "unsupported");
        assertFailed(snapshot, new UiAssertion.Disabled(), "unsupported");
    }

    @Test void accessibleNameMustContainNonWhitespaceText() {
        assertFailed(snapshot(node("target", true, Optional.of(true), Optional.empty(), false,
                "", "   ", new Bounds(0, 0, 1, 1))),
                new UiAssertion.AccessibleNameExists(), "blank");
    }

    @Test void publicModelsRejectInvalidVersionsBoundsAndUnboundedInputs() {
        assertThrows(IllegalArgumentException.class,
                () -> request(2, Locator.testId("target"), new UiAssertion.Visible()));
        assertThrows(IllegalArgumentException.class, () -> new UiAssertion.CountEquals(-1));
        assertThrows(IllegalArgumentException.class, () -> new UiAssertion.StableForFrames(0,
                java.util.Set.of(UiAssertion.StableProperty.BOUNDS)));
        assertThrows(IllegalArgumentException.class, () -> new UiAssertion.StableForFrames(2,
                java.util.Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new UiAssertion.TextEquals("x".repeat(16_385)));
    }

    private void assertPassed(SemanticSnapshot snapshot, UiAssertion assertion) {
        assertEquals(AssertionResult.Status.PASSED,
                evaluate(snapshot, Locator.testId("target"), assertion).status());
    }

    private void assertFailed(SemanticSnapshot snapshot, UiAssertion assertion, String observedPart) {
        AssertionResult result = evaluate(snapshot, Locator.testId("target"), assertion);
        assertEquals(AssertionResult.Status.FAILED, result.status());
        assertTrue(result.evidence().observed().contains(observedPart), result.evidence().observed());
    }

    private AssertionResult evaluate(SemanticSnapshot snapshot, Locator locator, UiAssertion assertion) {
        return evaluator.evaluate(snapshot, request(1, locator, assertion));
    }

    private static AssertionRequest request(int version, Locator locator, UiAssertion assertion) {
        return new AssertionRequest(version, locator, assertion,
                Deadline.after(() -> 20L, Duration.ofSeconds(1)));
    }

    private static SemanticSnapshot snapshot(SemanticNode... children) {
        List<String> childIds = java.util.Arrays.stream(children).map(SemanticNode::id).toList();
        Bounds rootBounds = new Bounds(0, 0, 100, 100);
        SemanticNode root = new SemanticNode("root", null, childIds, Role.GROUP, "Root", "", null,
                "root", null, "Group", state(true, Optional.of(true), Optional.empty(), false),
                rootBounds, rootBounds, rootBounds, 0, Map.of());
        Map<String, SemanticNode> nodes = new LinkedHashMap<>();
        nodes.put("root", root);
        for (SemanticNode child : children) nodes.put(child.id(), child);
        return new SemanticSnapshot(7, 11, "root", nodes);
    }

    private static SemanticNode node(String id, boolean visible, Optional<Boolean> enabled,
            Optional<Boolean> checked, boolean focused, String text, String name, Bounds bounds) {
        return new SemanticNode(id, "root", List.of(), Role.BUTTON, name, text, null, id, null,
                "TextButton", state(visible, enabled, checked, focused), bounds, bounds, bounds, 0,
                Map.of());
    }

    private static SemanticState state(boolean visible, Optional<Boolean> enabled,
            Optional<Boolean> checked, boolean focused) {
        return new SemanticState(visible, true, enabled, checked, Optional.empty(), Optional.empty(),
                Optional.empty(), focused, true, 1.0, false, true, true);
    }
}
