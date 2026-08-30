package dev.gdx.uiharness.core.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gdx.uiharness.core.model.Bounds;
import dev.gdx.uiharness.core.model.Role;
import dev.gdx.uiharness.core.model.SemanticNode;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import dev.gdx.uiharness.core.model.SemanticState;
import dev.gdx.uiharness.core.navigation.NavigationPath;
import dev.gdx.uiharness.core.navigation.NavigationReason;
import dev.gdx.uiharness.core.navigation.NavigationResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class LayoutValidatorTest {
    private final LayoutValidator validator = new LayoutValidator();

    @Test void outsideViewportClippedTextAndZeroSizeAreReported() {
        SemanticSnapshot snapshot = snapshot(
                node("out", Role.BUTTON, "Out", bounds(2000, 0, 100, 100), "btn-out",
                        visible(true, false, false)),
                node("clip", Role.LABEL, "Clipped label", bounds(10, 10, 100, 20), "btn-clip",
                        visible(true, true, true)),
                node("zero", Role.IMAGE, null, bounds(50, 50, 0, 0), null,
                        visible(true, false, true)));

        LayoutValidationResult result = validator.validate(
                snapshot,
                only(
                        LayoutValidationCheck.OUTSIDE_VIEWPORT,
                        LayoutValidationCheck.CLIPPED_TEXT,
                        LayoutValidationCheck.ZERO_SIZE),
                null,
                LayoutValidationEvidence.available(Map.of(
                        "clip", text("clip", bounds(10, 10, 101, 20)))));

        assertEquals(LayoutValidationResult.Status.FAIL, result.status());
        assertReason(result, "out", LayoutValidationReason.OUTSIDE_VIEWPORT);
        assertReason(result, "clip", LayoutValidationReason.CLIPPED_TEXT);
        assertReason(result, "zero", LayoutValidationReason.ZERO_SIZE);
    }

    @Test void overlappingInteractiveControlsAndObscurationAreDistinct() {
        SemanticSnapshot snapshot = snapshot(
                node("first", Role.BUTTON, "First", bounds(10, 10, 100, 50), "first",
                        visible(true, false, true), 0),
                node("second", Role.BUTTON, "Second", bounds(30, 20, 100, 50), "second",
                        visible(true, false, true), 1));

        LayoutValidationResult result = validator.validate(
                snapshot,
                only(
                        LayoutValidationCheck.INTERACTIVE_OVERLAP,
                        LayoutValidationCheck.OBSCURED),
                null);

        assertTrue(result.findings().stream()
                .anyMatch(finding -> finding.reason() == LayoutValidationReason.INTERACTIVE_OVERLAP
                        && "first".equals(finding.nodeId())));
        assertTrue(result.findings().stream()
                .anyMatch(finding -> finding.reason() == LayoutValidationReason.OBSCURED
                        && "first".equals(finding.nodeId())));
    }

    @Test void duplicateTestIdsAndMissingAccessibleNamesAreDistinct() {
        SemanticSnapshot snapshot = snapshot(
                node("a", Role.BUTTON, "First", bounds(10, 10, 100, 50), "dup",
                        visible(true, false, true)),
                node("b", Role.BUTTON, "Second", bounds(120, 10, 100, 50), "dup",
                        visible(true, false, true)),
                node("c", Role.BUTTON, null, bounds(230, 10, 100, 50), null,
                        visible(true, false, true)));

        LayoutValidationResult result = validator.validate(
                snapshot,
                only(
                        LayoutValidationCheck.DUPLICATE_TEST_ID,
                        LayoutValidationCheck.MISSING_ACCESSIBLE_NAME),
                null);

        assertReason(result, "a", LayoutValidationReason.DUPLICATE_TEST_ID);
        assertReason(result, "b", LayoutValidationReason.DUPLICATE_TEST_ID);
        assertReason(result, "c", LayoutValidationReason.MISSING_ACCESSIBLE_NAME);
    }

    @Test void keyboardReachabilityConsumesNavigationEvidenceAndReportsUnavailableWithoutIt() {
        SemanticSnapshot snapshot = snapshot(
                node("reachable", Role.BUTTON, "Reachable", bounds(10, 10, 100, 50), "r",
                        visible(true, false, true)),
                node("unreachable", Role.BUTTON, "Unreachable", bounds(120, 10, 100, 50), "u",
                        visible(true, false, true)));

        LayoutValidationResult unavailable = validator.validate(
                snapshot, only(LayoutValidationCheck.KEYBOARD_UNREACHABLE), null);
        assertTrue(unavailable.findings().stream()
                .anyMatch(finding -> finding.reason()
                        == LayoutValidationReason.CHECK_UNAVAILABLE));

        NavigationResult navigation = new NavigationResult(1,
                new NavigationPath(1, "test-id:r", List.of(), NavigationReason.COMPLETE),
                List.of("test-id:r", "test-id:u"), List.of("test-id:u"), false);
        LayoutValidationResult withNavigation = validator.validate(
                snapshot,
                only(LayoutValidationCheck.KEYBOARD_UNREACHABLE),
                navigation);
        assertReason(withNavigation, "unreachable",
                LayoutValidationReason.KEYBOARD_UNREACHABLE);
    }

    @Test void optInChecksOnlyRunWhenEnabledAndReportAppliedThresholds() {
        LayoutValidationConfig config = only(LayoutValidationCheck.BELOW_TARGET_SIZE);
        SemanticSnapshot snapshot = snapshot(
                node("small", Role.BUTTON, "Small", bounds(10, 10, 40, 20), "s",
                        visible(true, false, true)));

        LayoutValidationResult result = validator.validate(snapshot, config, null);

        assertReason(result, "small", LayoutValidationReason.BELOW_TARGET_SIZE);
        assertTrue(result.appliedConfig().enabledChecks().contains(
                LayoutValidationCheck.BELOW_TARGET_SIZE));
        LayoutValidationResult defaultResult = validator.validate(snapshot, only(), null);
        assertFalse(defaultResult.findings().stream()
                .anyMatch(finding -> finding.reason()
                        == LayoutValidationReason.BELOW_TARGET_SIZE));
    }

    @Test void findingsAreDeterministicallyOrderedAndBounded() {
        List<SemanticNode> nodes = new java.util.ArrayList<>();
        for (int index = 0; index < 20; index++) {
            nodes.add(node("zero-" + index, Role.IMAGE, null,
                    bounds(50, 50, 0, 0), null, visible(true, false, true)));
        }
        SemanticSnapshot snapshot = snapshot(nodes.toArray(SemanticNode[]::new));
        LayoutValidationConfig bounded = LayoutValidationConfig.builder()
                .disable(LayoutValidationCheck.OUTSIDE_VIEWPORT)
                .disable(LayoutValidationCheck.CLIPPED_TEXT)
                .disable(LayoutValidationCheck.TEXT_COLLISION)
                .disable(LayoutValidationCheck.INTERACTIVE_OVERLAP)
                .disable(LayoutValidationCheck.DUPLICATE_TEST_ID)
                .disable(LayoutValidationCheck.MISSING_ACCESSIBLE_NAME)
                .disable(LayoutValidationCheck.KEYBOARD_UNREACHABLE)
                .disable(LayoutValidationCheck.OBSCURED)
                .enable(LayoutValidationCheck.ZERO_SIZE)
                .maxFindings(5)
                .build();

        LayoutValidationResult first = validator.validate(snapshot, bounded, null);
        LayoutValidationResult second = validator.validate(snapshot, bounded, null);

        assertEquals(5, first.findings().size());
        assertTrue(first.truncated());
        assertEquals(first.findings(), second.findings());
    }

    @Test void severityGateDrivesTheCiStatus() {
        SemanticSnapshot snapshot = snapshot(
                node("missing", Role.BUTTON, null, bounds(10, 10, 100, 50), null,
                        visible(true, false, true)));

        LayoutValidationResult warningsOnly = validator.validate(
                snapshot,
                onlyAt(
                        LayoutValidationSeverity.ERROR,
                        LayoutValidationCheck.MISSING_ACCESSIBLE_NAME),
                null);
        assertEquals(LayoutValidationResult.Status.PASS, warningsOnly.status(),
                "missing accessible name is a warning below the error gate");
        assertEquals(LayoutValidationResult.Status.FAIL,
                validator.validate(
                        snapshot,
                        onlyAt(
                                LayoutValidationSeverity.WARNING,
                                LayoutValidationCheck.MISSING_ACCESSIBLE_NAME),
                        null).status());
    }
    @Test void intrinsicTextOverflowAndCollisionAreHardFailures() {
        SemanticSnapshot snapshot = snapshot(
                node("state", Role.LABEL, "GAME_OVER", bounds(466, 502, 82, 20),
                        "screen-value", visible(false, false, true)),
                node("p2", Role.LABEL, "P2 HEALTH:", bounds(554, 502, 94, 20),
                        "player-two-health-label", visible(false, false, true)));
        LayoutValidationEvidence evidence = LayoutValidationEvidence.available(Map.of(
                "state", text("state", bounds(467, 502, 97, 13)),
                "p2", text("p2", bounds(554, 502, 91, 13))));
        LayoutValidationConfig config = only(
                LayoutValidationCheck.CLIPPED_TEXT,
                LayoutValidationCheck.TEXT_COLLISION);

        LayoutValidationResult result = validator.validate(snapshot, config, null, evidence);

        assertEquals(LayoutValidationResult.Status.FAIL, result.status());
        LayoutFinding clipped = finding(
                result, "state", LayoutValidationReason.CLIPPED_TEXT);
        assertEquals(LayoutValidationSeverity.ERROR, clipped.severity());
        assertEquals(
                "text ink exceeds actor/clip/viewport bounds: "
                        + "left=0.0, right=16.0, bottom=0.0, top=0.0",
                clipped.evidence());
        LayoutFinding collision = finding(
                result, "state", LayoutValidationReason.TEXT_COLLISION);
        assertEquals(LayoutValidationSeverity.ERROR, collision.severity());
        assertEquals(LayoutValidationCheck.TEXT_COLLISION, collision.reason().check());
        assertEquals("p2", collision.relatedActorId());
    }

    @Test void missingRequestedTextGeometryIsAnErrorNotAPass() {
        SemanticSnapshot snapshot = snapshot(node(
                "label", Role.LABEL, "Label", bounds(10, 10, 100, 20), "label",
                visible(false, false, true)));
        LayoutValidationConfig config = only(LayoutValidationCheck.CLIPPED_TEXT);

        LayoutValidationResult result = validator.validate(
                snapshot, config, null, LayoutValidationEvidence.unavailable());

        assertEquals(LayoutValidationResult.Status.FAIL, result.status());
        LayoutFinding unavailable = finding(
                result, "root", LayoutValidationReason.CHECK_UNAVAILABLE);
        assertEquals(LayoutValidationSeverity.ERROR, unavailable.severity());
        assertEquals("check unavailable: clipped_text", unavailable.evidence());
    }

    @Test void textEvidenceIsDefensivelyCopiedAndBounded() {
        List<Bounds> clips = new java.util.ArrayList<>();
        clips.add(bounds(0, 0, 100, 100));
        TextLayoutEvidence text = new TextLayoutEvidence(
                "label", bounds(10, 10, 90, 20), bounds(10, 10, 90, 20), clips);
        clips.clear();
        Map<String, TextLayoutEvidence> byNode = new LinkedHashMap<>();
        byNode.put("label", text);
        LayoutValidationEvidence evidence = LayoutValidationEvidence.available(byNode);
        byNode.clear();

        assertEquals(1, text.clipChainStageBounds().size());
        assertEquals(Map.of("label", text), evidence.textByNodeId());
        assertThrows(UnsupportedOperationException.class,
                () -> text.clipChainStageBounds().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> evidence.textByNodeId().clear());
        assertThrows(IllegalArgumentException.class,
                () -> new TextLayoutEvidence(
                        "label", bounds(0, 0, 1, 1), bounds(0, 0, 1, 1),
                        java.util.Collections.nCopies(129, bounds(0, 0, 1, 1))));
        assertThrows(IllegalArgumentException.class,
                () -> LayoutValidationEvidence.available(Map.of(
                        "other", text)));
        assertThrows(IllegalArgumentException.class,
                () -> new LayoutValidationEvidence(false, Map.of("label", text)));
    }

    @Test void compatibilityOverloadReportsUnavailableIntrinsicChecks() {
        SemanticSnapshot snapshot = snapshot(node(
                "label", Role.LABEL, "Label", bounds(10, 10, 100, 20), "label",
                visible(false, true, true)));

        LayoutValidationResult result = validator.validate(
                snapshot, only(LayoutValidationCheck.CLIPPED_TEXT), null);

        assertEquals(LayoutValidationResult.Status.FAIL, result.status());
        assertReason(result, "root", LayoutValidationReason.CHECK_UNAVAILABLE);
        assertFalse(result.findings().stream()
                .anyMatch(finding -> finding.reason() == LayoutValidationReason.CLIPPED_TEXT));
    }


    private static void assertReason(
            LayoutValidationResult result, String nodeId, LayoutValidationReason reason) {
        assertTrue(result.findings().stream()
                .anyMatch(finding -> finding.nodeId().equals(nodeId)
                        && finding.reason() == reason),
                "expected " + reason + " for " + nodeId + " in " + result.findings());
    }
    private static LayoutFinding finding(
            LayoutValidationResult result, String nodeId, LayoutValidationReason reason) {
        return result.findings().stream()
                .filter(finding -> finding.nodeId().equals(nodeId)
                        && finding.reason() == reason)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "expected " + reason + " for " + nodeId + " in " + result.findings()));
    }

    private static LayoutValidationConfig only(LayoutValidationCheck... checks) {
        return onlyAt(LayoutValidationSeverity.ERROR, checks);
    }

    private static LayoutValidationConfig onlyAt(
            LayoutValidationSeverity failOn, LayoutValidationCheck... checks) {
        LayoutValidationConfig.Builder builder = LayoutValidationConfig.builder();
        LayoutValidationConfig.DEFAULT_CHECKS.forEach(builder::disable);
        for (LayoutValidationCheck check : checks) {
            builder.enable(check);
        }
        return builder.failOn(failOn).build();
    }


    private static SemanticNode node(
            String id, Role role, String name, Bounds stage, String testId,
            SemanticState state) {
        return node(id, role, name, stage, testId, state, 0);
    }

    private static SemanticNode node(
            String id, Role role, String name, Bounds stage, String testId,
            SemanticState state, int zIndex) {
        Bounds local = new Bounds(0, 0, stage.width(), stage.height());
        return new SemanticNode(
                id, "root", List.of(), role, name, name, null, testId, null, null,
                state, local, stage, stage, zIndex, Map.of());
    }

    private static SemanticState visible(boolean touchable, boolean clipped,
            boolean viewportIntersecting) {
        return new SemanticState(
                true, touchable, Optional.of(true), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), false, true, 1.0, clipped,
                viewportIntersecting, true);
    }

    private static Bounds bounds(double x, double y, double width, double height) {
        return new Bounds(x, y, width, height);
    }
    private static TextLayoutEvidence text(String nodeId, Bounds ink) {
        return new TextLayoutEvidence(nodeId, ink, ink, List.of());
    }


    private static SemanticSnapshot snapshot(SemanticNode... nodes) {
        SemanticNode root = new SemanticNode("root", null,
                java.util.stream.Stream.of(nodes).map(SemanticNode::id).toList(),
                Role.GROUP, "root", "", null, null, null, null,
                visible(false, false, true), new Bounds(0, 0, 1280, 720),
                new Bounds(0, 0, 1280, 720), new Bounds(0, 0, 1280, 720), 0, Map.of());
        var byId = new LinkedHashMap<String, SemanticNode>();
        byId.put("root", root);
        for (SemanticNode node : nodes) {
            byId.put(node.id(), node);
        }
        return new SemanticSnapshot(1, 1, "root", byId);
    }
}
