package benchmark.palisade.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class StructuralUsabilityTest {
    private static final String SHA_A =
            "98c092bfd976171cb17745b425e8d0ae357e93f085ed8eae9e618ee56c0f5cb3";
    private static final String SHA_B = "b".repeat(64);
    private static final String SHA_C = "c".repeat(64);
    private static final String SHA_D = "d".repeat(64);

    @Test
    void evaluatorImplementationHasStableMachineReadableIdentity() {
        String first = StructuralUsability.implementationSha256();

        assertEquals(64, first.length());
        assertTrue(first.matches("[0-9a-f]{64}"));
        assertEquals(first, StructuralUsability.implementationSha256());
    }

    @Test
    void offViewportCoordinatesAreValidButNegativeExtentsAreNot() {
        StructuralUsability.Rect offViewport =
                new StructuralUsability.Rect(-20, -40, 100, 50);

        assertEquals(-20, offViewport.x());
        assertEquals(-40, offViewport.y());
        assertThrows(
                IllegalArgumentException.class,
                () -> new StructuralUsability.Rect(0, 0, -1, 10));
        assertThrows(
                IllegalArgumentException.class,
                () -> new StructuralUsability.Rect(0, 0, 10, -1));
    }

    @Test
    void referenceLikeEvidencePassesEveryIndependentSignal() {
        StructuralUsability.Result result =
                StructuralUsability.evaluate(policy(), evidence(control()), null);

        assertEquals(StructuralUsability.Status.PASS, result.status());
        assertEquals(6, result.signals().size());
        assertTrue(result.signals().stream()
                .allMatch(signal -> signal.status() == StructuralUsability.Status.PASS));
    }

    @Test
    void intrinsicLabelsAreValidOnlyForSelfLabellingRoles() {
        for (String role : List.of("button", "checkbox")) {
            StructuralUsability.Result result = StructuralUsability.evaluate(
                    policy(), evidence(control(role, null, null)), null);

            assertEquals(StructuralUsability.Status.PASS, result.status());
        }
        for (String role : List.of("slider", "combobox", "textbox", "spinbutton")) {
            StructuralUsability.Result result = StructuralUsability.evaluate(
                    policy(), evidence(control(role, null, null)), null);

            assertEquals(StructuralUsability.Status.FAIL, result.status());
            assertEquals(
                    "LABEL_ASSOCIATION_MISSING",
                    signal(result, "legibility").diagnostics().getFirst().code());
        }
    }

    @Test
    void accessibleRoleVocabularyMatchesTheStateActionContract() {
        for (String role : List.of(
                "button", "checkbox", "slider", "combobox", "textbox", "spinbutton")) {
            StructuralUsability.Result result = StructuralUsability.evaluate(
                    policy(),
                    evidence(control(role, "major-rival-label", "major-rival-count")),
                    null);

            assertEquals(
                    StructuralUsability.Status.PASS,
                    signal(result, "affordance").status(),
                    role);
        }
        for (String role : List.of("select", "text", "number")) {
            StructuralUsability.Result result = StructuralUsability.evaluate(
                    policy(),
                    evidence(control(role, "major-rival-label", "major-rival-count")),
                    null);

            assertEquals(
                    "CONTROL_ROLE_AMBIGUOUS",
                    signal(result, "affordance").diagnostics().getFirst().code(),
                    role);
        }
    }

    @Test
    void externalLabelAssociationsMustBeCompleteAndReciprocal() {
        for (StructuralUsability.ControlEvidence mutation : List.of(
                control("slider", null, "major-rival-count"),
                control("slider", "major-rival-label", null),
                control("slider", "major-rival-label", "another-control"))) {
            StructuralUsability.Result result =
                    StructuralUsability.evaluate(policy(), evidence(mutation), null);

            assertEquals(StructuralUsability.Status.FAIL, result.status());
            assertEquals(
                    "LABEL_ASSOCIATION_MISSING",
                    signal(result, "legibility").diagnostics().getFirst().code());
        }
    }

    @Test
    void allThreeFrozenReferenceIdentitiesPassEveryApplicableGate() {
        for (StructuralUsability.Policy reference : List.of(
                referencePolicy(
                        "98c092bfd976171cb17745b425e8d0ae357e93f085ed8eae9e618ee56c0f5cb3",
                        "initial", "desktop-1920x1080", 1920, 1080),
                referencePolicy(
                        "92b4dd35574d3b614bd1f4a05c172dd9df9c41ef96396a7fab45902e7f4f2fb6",
                        "bottom", "desktop-1920x1080", 1920, 1080),
                referencePolicy(
                        "9de83761bb4135d618c48830afc280b85c36ff413f7d3b7248d0fb168b8d5ad0",
                        "initial", "desktop-1280x720", 1280, 720))) {
            StructuralUsability.Result result = StructuralUsability.evaluate(
                    reference, referenceEvidence(reference), null);

            assertEquals(StructuralUsability.Status.PASS, result.status());
        }
    }

    @Test
    void legibilityMutationsFailIndependentlyWithControlAttribution() {
        for (StructuralUsability.ControlEvidence mutation : List.of(
                control().withFontPixels(11),
                control().withRasterResidual(0.8),
                control().withContrastRatio(3.5),
                control().withGlyphClipped(true),
                control().withLabelControlId(null),
                new StructuralUsability.ControlEvidence(
                        "major-rival-count", "slider", "major-rival-label",
                        "another-control", true, true,
                        rect(100, 100, 420, 44), rect(100, 100, 420, 44),
                        false, 15, 0.1, 7, false,
                        "form-row", "form", "scroll", "scroll",
                        rect(100, 100, 420, 44)))) {
            StructuralUsability.Result result =
                    StructuralUsability.evaluate(policy(), evidence(mutation), null);

            assertEquals(StructuralUsability.Status.FAIL, result.status());
            assertTrue(result.signals().stream()
                    .filter(signal -> signal.name().equals("legibility"))
                    .flatMap(signal -> signal.diagnostics().stream())
                    .allMatch(diagnostic ->
                            diagnostic.controlId().equals("major-rival-count")));
        }
    }

    @Test
    void affordanceMutationsFailWithoutChangingRasterChannel() {
        for (StructuralUsability.ControlEvidence mutation : List.of(
                control().withRole(null),
                control().withRole("generic"),
                control().withLabelControlId(null),
                control().withFocusable(false),
                control().withEnabled(false),
                control().withHitBounds(rect(10, 10, 20, 20)),
                control().withOccluded(true))) {
            StructuralUsability.Result result =
                    StructuralUsability.evaluate(policy(), evidence(mutation), null);

            assertEquals(StructuralUsability.Status.FAIL, result.status());
            assertEquals(StructuralUsability.Status.FAIL, signal(result, "affordance").status());
        }
    }

    @Test
    void hierarchyAndInternalOnePixelCutFailWhenFramebufferEdgeIsClear() {
        StructuralUsability.ControlEvidence scrollingTitle =
                control().withHierarchy("scrolling-title", "scroll-body");
        StructuralUsability.Result hierarchy =
                StructuralUsability.evaluate(policy(), evidence(scrollingTitle), null);
        StructuralUsability.ControlEvidence cut = control().withVisibleBounds(
                rect(100, 101, 420, 43));
        StructuralUsability.Result clipping =
                StructuralUsability.evaluate(policy(), evidence(cut), null);

        assertEquals(StructuralUsability.Status.FAIL, signal(hierarchy, "hierarchy").status());
        assertEquals(StructuralUsability.Status.FAIL, signal(clipping, "clipping").status());
        assertFalse(clipping.evidence().frameEdgeClipped());
        assertEquals(
                "INTERNAL_CLIP_MISMATCH",
                signal(clipping, "clipping").diagnostics().getFirst().code());
        assertEquals(
                "framebuffer-top-left",
                signal(clipping, "clipping").diagnostics().getFirst().coordinateSpace());
        assertEquals(
                "pixels",
                signal(clipping, "clipping").diagnostics().getFirst().units());
    }

    @Test
    void wrongFixedCompositionFailsOnlyAtDeclared1280Viewport() {
        StructuralUsability.Evidence at1280 = evidence(control())
                .withViewport("desktop-1280x720", 1280, 720)
                .withPanelBounds(rect(100, 20, 1080, 680));
        StructuralUsability.Result result =
                StructuralUsability.evaluate(policy1280(), at1280, evidence(control()));

        assertEquals(StructuralUsability.Status.FAIL, signal(result, "responsive").status());
        assertEquals("CROSS_VIEWPORT_REFLOW_MISMATCH",
                signal(result, "responsive").diagnostics().getFirst().code());
    }

    @Test
    void anyPostSettleChangeFailsInsteadOfBeingAveraged() {
        List<StructuralUsability.FrameEvidence> frames =
                new ArrayList<>(evidence(control()).frames());
        frames.set(2, new StructuralUsability.FrameEvidence(
                12, 7, 5, 300, SHA_A, SHA_A, SHA_A, SHA_D));

        StructuralUsability.Result result = StructuralUsability.evaluate(
                policy(), evidence(control()).withFrames(frames), null);

        assertEquals(StructuralUsability.Status.UNSTABLE, result.status());
        assertEquals(StructuralUsability.Status.UNSTABLE,
                signal(result, "scroll-stability").status());
    }

    @Test
    void staleOrMissingIdentityIsIncompleteAndCannotPass() {
        StructuralUsability.Evidence stale = evidence(control()).withReferenceSha256(SHA_D);
        StructuralUsability.Result staleResult =
                StructuralUsability.evaluate(policy(), stale, null);
        StructuralUsability.Evidence missingControl =
                evidence(control()).withControls(List.of());
        StructuralUsability.Result missingResult =
                StructuralUsability.evaluate(policy(), missingControl, null);

        assertEquals(StructuralUsability.Status.STALE, staleResult.status());
        assertEquals(StructuralUsability.Status.INCOMPLETE, missingResult.status());
        assertEquals(policy().policyId(), missingResult.policyId());
    }

    @Test
    void invalidPublicObservationIsAnIndependentIncompleteResult() {
        StructuralUsability.Result result = StructuralUsability.invalidObservation(
                policy(), evidence(control()), "schemaVersion: null");

        assertEquals(StructuralUsability.Status.INCOMPLETE, result.status());
        assertEquals(6, result.signals().size());
        assertTrue(result.signals().stream()
                .allMatch(signal ->
                        signal.status() == StructuralUsability.Status.INCOMPLETE));
        assertEquals("OBSERVATION_SCHEMA_INVALID",
                result.diagnostics().getFirst().code());
        assertEquals("$.structuralUsability",
                result.diagnostics().getFirst().path());
        assertEquals("schemaVersion: null",
                result.diagnostics().getFirst().observed());
    }

    @Test
    void everyBoundObservationIdentityFailsClosedWhenChangedOrMissing() {
        StructuralUsability.Evidence base = evidence(control());
        for (StructuralUsability.Evidence mutation : List.of(
                identity(base, "bottom", base.viewportId(), base.width(),
                        base.height(), base.deviceScale(), 7, 5),
                identity(base, base.stateId(), "desktop-1280x720", base.width(),
                        base.height(), base.deviceScale(), 7, 5),
                identity(base, base.stateId(), base.viewportId(), 1919,
                        base.height(), base.deviceScale(), 7, 5),
                identity(base, base.stateId(), base.viewportId(), base.width(),
                        base.height(), 2, 7, 5),
                identity(base, base.stateId(), base.viewportId(), base.width(),
                        base.height(), base.deviceScale(), -1, 5))) {
            assertTrue(StructuralUsability.evaluate(policy(), mutation, null).status()
                    != StructuralUsability.Status.PASS);
        }
    }

    @Test
    void viewportEdgeClippingIsIndependentFromInternalClipOwnerGeometry() {
        StructuralUsability.Evidence base = evidence(control());
        StructuralUsability.Evidence clipped = new StructuralUsability.Evidence(
                base.schemaVersion(), base.evaluatorId(), base.evaluatorSha256(),
                base.referenceSha256(), base.captureSha256(), base.stateId(),
                base.viewportId(), base.width(), base.height(), base.deviceScale(),
                base.semanticRevision(), base.layoutRevision(), true,
                base.panelBounds(), base.controls(), base.frames());

        StructuralUsability.Signal result = signal(
                StructuralUsability.evaluate(policy(), clipped, null), "clipping");

        assertEquals("VIEWPORT_EDGE_CLIPPED", result.diagnostics().getFirst().code());
        assertEquals("not-applicable",
                result.diagnostics().getFirst().coordinateSpace());
    }

    private static StructuralUsability.Signal signal(
            StructuralUsability.Result result, String name) {
        return result.signals().stream()
                .filter(value -> value.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static StructuralUsability.Policy policy() {
        return new StructuralUsability.Policy(
                "palisade-structural-v1", 1, "evaluator-v1", SHA_D,
                SHA_A, "initial", "desktop-1920x1080", 1920, 1080, 1,
                12, 0.5, 4.5, 44, 44,
                "major-rival-count",
                rect(80, 40, 1760, 1000),
                rect(100, 100, 420, 44),
                "form-row", "form", "scroll", "scroll",
                rect(100, 100, 420, 44));
    }

    private static StructuralUsability.Policy policy1280() {
        StructuralUsability.Policy value = policy();
        return new StructuralUsability.Policy(
                value.policyId(), value.policyVersion(),
                value.evaluatorId(), value.evaluatorSha256(),
                value.referenceSha256(), value.stateId(), "desktop-1280x720",
                1280, 720, value.deviceScale(),
                value.minimumFontPixels(), value.maximumRasterResidual(),
                value.minimumContrastRatio(), value.minimumHitWidth(),
                value.minimumHitHeight(), value.controlId(),
                rect(80, 40, 1120, 640),
                value.expectedControlBounds(), value.expectedHierarchyRole(),
                value.expectedParentControlId(), value.expectedScrollOwnerId(),
                value.expectedClipOwnerId(), value.expectedVisibleBounds());
    }

    private static StructuralUsability.Evidence evidence(
            StructuralUsability.ControlEvidence control) {
        return new StructuralUsability.Evidence(
                "structural-usability/v1", "evaluator-v1", SHA_D,
                SHA_A, SHA_B, "initial", "desktop-1920x1080",
                1920, 1080, 1, 7, 5, false,
                rect(80, 40, 1760, 1000),
                List.of(control),
                frames());
    }

    private static StructuralUsability.Policy referencePolicy(
            String referenceSha,
            String state,
            String viewport,
            int width,
            int height) {
        StructuralUsability.Policy base = policy();
        return new StructuralUsability.Policy(
                base.policyId(), base.policyVersion(), base.evaluatorId(),
                base.evaluatorSha256(), referenceSha, state, viewport, width,
                height, base.deviceScale(), base.minimumFontPixels(),
                base.maximumRasterResidual(), base.minimumContrastRatio(),
                base.minimumHitWidth(), base.minimumHitHeight(), base.controlId(),
                base.expectedPanelBounds(), base.expectedControlBounds(),
                base.expectedHierarchyRole(), base.expectedParentControlId(),
                base.expectedScrollOwnerId(), base.expectedClipOwnerId(),
                base.expectedVisibleBounds());
    }

    private static StructuralUsability.Evidence referenceEvidence(
            StructuralUsability.Policy policy) {
        StructuralUsability.Evidence base = evidence(control());
        return new StructuralUsability.Evidence(
                base.schemaVersion(), base.evaluatorId(), base.evaluatorSha256(),
                policy.referenceSha256(), base.captureSha256(), policy.stateId(),
                policy.viewportId(), policy.width(), policy.height(),
                policy.deviceScale(), base.semanticRevision(),
                base.layoutRevision(), false, policy.expectedPanelBounds(),
                base.controls(), base.frames());
    }

    private static StructuralUsability.ControlEvidence control() {
        return control("slider", "major-rival-label", "major-rival-count");
    }

    private static StructuralUsability.ControlEvidence control(
            String role, String labelControlId, String labelledControlId) {
        return new StructuralUsability.ControlEvidence(
                "major-rival-count", role, labelControlId,
                labelledControlId, true, true,
                rect(100, 100, 420, 44), rect(100, 100, 420, 44),
                false, 15, 0.1, 7, false,
                "form-row", "form", "scroll", "scroll",
                rect(100, 100, 420, 44));
    }

    private static List<StructuralUsability.FrameEvidence> frames() {
        return List.of(
                new StructuralUsability.FrameEvidence(
                        10, 7, 5, 300, SHA_A, SHA_A, SHA_A, SHA_C),
                new StructuralUsability.FrameEvidence(
                        11, 7, 5, 300, SHA_A, SHA_A, SHA_A, SHA_C),
                new StructuralUsability.FrameEvidence(
                        12, 7, 5, 300, SHA_A, SHA_A, SHA_A, SHA_C),
                new StructuralUsability.FrameEvidence(
                        13, 7, 5, 300, SHA_A, SHA_A, SHA_A, SHA_C),
                new StructuralUsability.FrameEvidence(
                        14, 7, 5, 300, SHA_A, SHA_A, SHA_A, SHA_C));
    }

    private static StructuralUsability.Rect rect(
            double x, double y, double width, double height) {
        return new StructuralUsability.Rect(x, y, width, height);
    }

    private static StructuralUsability.Evidence identity(
            StructuralUsability.Evidence base,
            String state,
            String viewport,
            int width,
            int height,
            double scale,
            long semanticRevision,
            long layoutRevision) {
        return new StructuralUsability.Evidence(
                base.schemaVersion(), base.evaluatorId(), base.evaluatorSha256(),
                base.referenceSha256(), base.captureSha256(), state, viewport,
                width, height, scale, semanticRevision, layoutRevision,
                base.frameEdgeClipped(), base.panelBounds(), base.controls(),
                base.frames());
    }
}
