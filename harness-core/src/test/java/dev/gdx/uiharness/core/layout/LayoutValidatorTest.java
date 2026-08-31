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
                LayoutValidationEvidence.available(
                        bounds(0, 0, 1280, 720),
                        Map.of("clip", text("clip", bounds(10, 10, 101, 20)))));

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

    @Test void ancestorChildCompositionIsNotObscuration() {
        SemanticNode label = node(
                "label", "button", List.of(), Role.LABEL, "Play",
                bounds(30, 30, 80, 20), null, visible(false, false, true), 3, Map.of());
        SemanticNode button = node(
                "button", "table", List.of("label"), Role.BUTTON, "Play",
                bounds(20, 20, 100, 40), "play", visible(true, false, true), 2, Map.of());
        SemanticNode table = node(
                "table", "root", List.of("button"), Role.GROUP, null,
                bounds(10, 10, 200, 100), null, visible(false, false, true), 1, Map.of());

        LayoutValidationResult composition = validator.validate(
                snapshot(List.of(table, button, label)),
                only(LayoutValidationCheck.OBSCURED),
                null);

        assertFalse(composition.findings().stream()
                .anyMatch(finding -> finding.reason() == LayoutValidationReason.OBSCURED),
                composition.findings().toString());

        SemanticNode overlay = node(
                "overlay", "table", List.of(), Role.IMAGE, null,
                bounds(20, 20, 100, 40), null, visible(false, false, true), 3, Map.of());
        SemanticNode tableWithOverlay = node(
                "table", "root", List.of("button", "overlay"), Role.GROUP, null,
                bounds(10, 10, 200, 100), null, visible(false, false, true), 1, Map.of());

        LayoutValidationResult obscured = validator.validate(
                snapshot(List.of(tableWithOverlay, button, label, overlay)),
                only(LayoutValidationCheck.OBSCURED),
                null);

        assertEquals(List.of("button->overlay"), obscured.findings().stream()
                .filter(finding -> finding.reason() == LayoutValidationReason.OBSCURED)
                .map(finding -> finding.nodeId() + "->" + finding.relatedActorId())
                .toList());
    }

    @Test void targetSizeIgnoresLabelsAndChecksInteractiveControls() {
        SemanticSnapshot snapshot = snapshot(
                node("decorative", Role.LABEL, "Status", bounds(10, 10, 100, 20), null,
                        visible(false, false, true)),
                node("control", Role.BUTTON, "Play", bounds(10, 40, 100, 20), "play",
                        visible(true, false, true)));

        LayoutValidationResult result = validator.validate(
                snapshot, only(LayoutValidationCheck.BELOW_TARGET_SIZE), null);

        assertFalse(result.findings().stream()
                .anyMatch(finding -> "decorative".equals(finding.nodeId())
                        && finding.reason() == LayoutValidationReason.BELOW_TARGET_SIZE));
        assertReason(result, "control", LayoutValidationReason.BELOW_TARGET_SIZE);
    }

    @Test void alignmentAndSpacingRequireExplicitHomogeneousGroup() {
        SemanticSnapshot ungrouped = snapshot(
                node("title", Role.LABEL, "Title", bounds(10, 10, 80, 20), null,
                        visible(false, false, true)),
                node("value", Role.LABEL, "Value", bounds(25, 40, 80, 20), null,
                        visible(false, false, true)),
                node("action", Role.BUTTON, "Action", bounds(10, 75, 80, 20), "action",
                        visible(true, false, true)));

        LayoutValidationResult unavailable = validator.validate(
                ungrouped,
                only(
                        LayoutValidationCheck.INCONSISTENT_ALIGNMENT,
                        LayoutValidationCheck.INCONSISTENT_SPACING),
                null);

        assertEquals(LayoutValidationResult.Status.FAIL, unavailable.status());
        assertEquals(2, unavailable.findings().stream()
                .filter(finding -> finding.reason() == LayoutValidationReason.CHECK_UNAVAILABLE
                        && finding.severity() == LayoutValidationSeverity.ERROR)
                .count());

        Map<String, String> vertical =
                Map.of("layout-group", "hud-values", "layout-axis", "vertical");
        Map<String, String> horizontal =
                Map.of("layout-group", "control-row", "layout-axis", "horizontal");
        SemanticSnapshot grouped = snapshot(
                node("v1", "root", List.of(), Role.LABEL, "One",
                        bounds(10, 10, 20, 20), null, visible(false, false, true), 0, vertical),
                node("v2", "root", List.of(), Role.LABEL, "Two",
                        bounds(10, 40, 20, 20), null, visible(false, false, true), 0, vertical),
                node("v3", "root", List.of(), Role.LABEL, "Three",
                        bounds(14, 75, 20, 20), null, visible(false, false, true), 0, vertical),
                node("h1", "root", List.of(), Role.BUTTON, "One",
                        bounds(100, 10, 20, 20), "h1", visible(true, false, true), 0, horizontal),
                node("h2", "root", List.of(), Role.BUTTON, "Two",
                        bounds(130, 10, 20, 20), "h2", visible(true, false, true), 0, horizontal),
                node("h3", "root", List.of(), Role.BUTTON, "Three",
                        bounds(165, 14, 20, 20), "h3", visible(true, false, true), 0, horizontal));

        LayoutValidationResult findings = validator.validate(
                grouped,
                only(
                        LayoutValidationCheck.INCONSISTENT_ALIGNMENT,
                        LayoutValidationCheck.INCONSISTENT_SPACING),
                null);

        assertReason(findings, "v3", LayoutValidationReason.INCONSISTENT_ALIGNMENT);
        assertReason(findings, "v3", LayoutValidationReason.INCONSISTENT_SPACING);
        assertReason(findings, "h3", LayoutValidationReason.INCONSISTENT_ALIGNMENT);
        assertReason(findings, "h3", LayoutValidationReason.INCONSISTENT_SPACING);
        assertFalse(findings.findings().stream()
                .anyMatch(finding -> finding.reason() == LayoutValidationReason.CHECK_UNAVAILABLE));
        assertEquals(findings.findings(), validator.validate(
                grouped,
                only(
                        LayoutValidationCheck.INCONSISTENT_ALIGNMENT,
                        LayoutValidationCheck.INCONSISTENT_SPACING),
                null).findings());

        findings.findings().stream()
                .filter(finding -> finding.reason()
                        == LayoutValidationReason.INCONSISTENT_ALIGNMENT
                        || finding.reason() == LayoutValidationReason.INCONSISTENT_SPACING)
                .forEach(finding -> {
                    assertTrue(grouped.nodes().containsKey(finding.relatedActorId()));
                    assertTrue(finding.evidence().contains("layout-group"));
                    assertTrue(finding.evidence().contains(
                            finding.nodeId().startsWith("v") ? "hud-values" : "control-row"));
                });
    }

    @Test void longUnicodeLayoutGroupEvidenceIsBoundedForAlignmentAndSpacing() {
        String groupId = "group-" + "\uD83D\uDE00".repeat(8_189);
        assertEquals(16_384, groupId.length());
        Map<String, String> properties =
                Map.of("layout-group", groupId, "layout-axis", "vertical");
        SemanticSnapshot snapshot = snapshot(
                node("first", "root", List.of(), Role.LABEL, "First",
                        bounds(10, 10, 20, 20), null,
                        visible(false, false, true), 0, properties),
                node("second", "root", List.of(), Role.LABEL, "Second",
                        bounds(10, 40, 20, 20), null,
                        visible(false, false, true), 0, properties),
                node("third", "root", List.of(), Role.LABEL, "Third",
                        bounds(14, 75, 20, 20), null,
                        visible(false, false, true), 0, properties));

        LayoutValidationResult result = validator.validate(
                snapshot,
                only(
                        LayoutValidationCheck.INCONSISTENT_ALIGNMENT,
                        LayoutValidationCheck.INCONSISTENT_SPACING),
                null);

        LayoutFinding alignment =
                finding(result, "third", LayoutValidationReason.INCONSISTENT_ALIGNMENT);
        LayoutFinding spacing =
                finding(result, "third", LayoutValidationReason.INCONSISTENT_SPACING);
        assertBoundedGroupEvidence(alignment, "first", "alignment");
        assertBoundedGroupEvidence(spacing, "second", "spacing");
        assertEquals(result.findings(), validator.validate(
                snapshot,
                only(
                        LayoutValidationCheck.INCONSISTENT_ALIGNMENT,
                        LayoutValidationCheck.INCONSISTENT_SPACING),
                null).findings());
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
        LayoutValidationEvidence evidence = LayoutValidationEvidence.available(
                bounds(0, 0, 1280, 720),
                Map.of(
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

    @Test void zeroAreaTextInkDoesNotCollide() {
        SemanticSnapshot snapshot = snapshot(
                node("label", Role.LABEL, "Label", bounds(10, 10, 100, 20),
                        "label", visible(false, false, true)),
                node("empty", Role.LABEL, "", bounds(20, 15, 10, 10),
                        "empty", visible(false, false, true)));
        LayoutValidationEvidence evidence = LayoutValidationEvidence.available(
                bounds(0, 0, 1280, 720),
                Map.of(
                        "label", text("label", bounds(10, 10, 100, 20)),
                        "empty", text("empty", bounds(20, 15, 0, 10))));

        LayoutValidationResult result = validator.validate(
                snapshot, only(LayoutValidationCheck.TEXT_COLLISION), null, evidence);

        assertEquals(LayoutValidationResult.Status.PASS, result.status());
        assertFalse(result.findings().stream()
                .anyMatch(finding ->
                        finding.reason() == LayoutValidationReason.TEXT_COLLISION));
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

    @Test void unavailableCheckFailsEvenWhenFindingCapacityIsAlreadyFull() {
        SemanticSnapshot snapshot = snapshot(node(
                "small", Role.BUTTON, "Small", bounds(10, 10, 10, 10), "small",
                visible(true, false, true)));
        LayoutValidationConfig.Builder builder = LayoutValidationConfig.builder();
        LayoutValidationConfig.DEFAULT_CHECKS.forEach(builder::disable);
        LayoutValidationConfig config = builder
                .enable(LayoutValidationCheck.BELOW_TARGET_SIZE)
                .enable(LayoutValidationCheck.CLIPPED_TEXT)
                .failOn(LayoutValidationSeverity.ERROR)
                .maxFindings(1)
                .build();

        LayoutValidationResult result = validator.validate(
                snapshot, config, null, LayoutValidationEvidence.unavailable());

        assertEquals(LayoutValidationResult.Status.FAIL, result.status());
        assertEquals(1, result.findings().size());
        assertEquals(
                LayoutValidationReason.BELOW_TARGET_SIZE,
                result.findings().getFirst().reason());
        assertTrue(result.truncated());
    }

    @Test void textEvidenceIsDefensivelyCopiedAndBounded() {
        List<Bounds> clips = new java.util.ArrayList<>();
        clips.add(bounds(0, 0, 100, 100));
        TextLayoutEvidence text = new TextLayoutEvidence(
                "label", bounds(10, 10, 90, 20), bounds(10, 10, 90, 20), clips);
        clips.clear();
        Map<String, TextLayoutEvidence> byNode = new LinkedHashMap<>();
        byNode.put("label", text);
        Bounds viewport = bounds(-10, -20, 1280, 720);
        LayoutValidationEvidence evidence =
                LayoutValidationEvidence.available(viewport, byNode);
        byNode.clear();

        assertEquals(1, text.clipChainStageBounds().size());
        assertEquals(Map.of("label", text), evidence.textByNodeId());
        assertEquals(viewport, evidence.stageViewportBounds());
        assertThrows(UnsupportedOperationException.class,
                () -> text.clipChainStageBounds().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> evidence.textByNodeId().clear());
        assertThrows(IllegalArgumentException.class,
                () -> new TextLayoutEvidence(
                        "label", bounds(0, 0, 1, 1), bounds(0, 0, 1, 1),
                        java.util.Collections.nCopies(129, bounds(0, 0, 1, 1))));
        assertThrows(IllegalArgumentException.class,
                () -> LayoutValidationEvidence.available(
                        viewport, Map.of("other", text)));
        assertThrows(NullPointerException.class,
                () -> LayoutValidationEvidence.available(null, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new LayoutValidationEvidence(false, viewport, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new LayoutValidationEvidence(
                        false, null, Map.of("label", text)));
        LayoutValidationEvidence unavailable = LayoutValidationEvidence.unavailable();
        assertEquals(null, unavailable.stageViewportBounds());
        assertTrue(unavailable.textByNodeId().isEmpty());
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

    private static void assertBoundedGroupEvidence(
            LayoutFinding finding, String relatedActorId, String checkName) {
        assertEquals(relatedActorId, finding.relatedActorId());
        assertTrue(finding.evidence().length() <= LayoutFinding.MAX_EVIDENCE_LENGTH);
        assertTrue(finding.evidence().startsWith("layout-group group-"));
        assertTrue(finding.evidence().endsWith(" deviates from " + checkName));
        for (int index = 0; index < finding.evidence().length(); index++) {
            char current = finding.evidence().charAt(index);
            if (Character.isHighSurrogate(current)) {
                assertTrue(index + 1 < finding.evidence().length()
                        && Character.isLowSurrogate(finding.evidence().charAt(index + 1)));
            } else if (Character.isLowSurrogate(current)) {
                assertTrue(index > 0
                        && Character.isHighSurrogate(finding.evidence().charAt(index - 1)));
            }
        }
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

    private static SemanticNode node(
            String id, String parentId, List<String> childIds, Role role, String name,
            Bounds stage, String testId, SemanticState state, int zIndex,
            Map<String, String> properties) {
        Bounds local = new Bounds(0, 0, stage.width(), stage.height());
        return new SemanticNode(
                id, parentId, childIds, role, name, name, null, testId, null, null,
                state, local, stage, stage, zIndex, properties);
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

    private static SemanticSnapshot snapshot(List<SemanticNode> nodes) {
        List<String> rootChildren = nodes.stream()
                .filter(node -> "root".equals(node.parentId()))
                .map(SemanticNode::id)
                .toList();
        SemanticNode root = new SemanticNode("root", null, rootChildren,
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
