package benchmark.palisade.eval;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Versioned, fail-closed structural usability evaluator independent of raster metrics. */
public final class StructuralUsability {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    private static final List<String> CONTROL_ROLES = List.of(
            "button", "checkbox", "slider", "select", "text", "number");
    private static final List<String> SIGNALS = List.of(
            "legibility",
            "affordance",
            "hierarchy",
            "clipping",
            "responsive",
            "scroll-stability");

    private StructuralUsability() {
    }

    /** Returns the SHA-256 of the loaded evaluator implementation class. */
    public static String implementationSha256() {
        try (InputStream input = StructuralUsability.class.getResourceAsStream(
                "StructuralUsability.class")) {
            if (input == null) {
                throw new IllegalStateException(
                        "Structural usability evaluator implementation is unavailable");
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] block = new byte[8192];
            int read;
            while ((read = input.read(block)) >= 0) {
                digest.update(block, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException(
                    "Could not identify structural usability evaluator", failure);
        }
    }

    /** Evaluates one observation and an optional matching comparison viewport. */
    public static Result evaluate(
            Policy policy, Evidence evidence, Evidence comparisonViewport) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(evidence, "evidence");
        Diagnostic identity = identityDiagnostic(policy, evidence);
        if (identity != null) {
            Status status = identity.code().contains("STALE")
                    || identity.code().contains("MISMATCH")
                    ? Status.STALE : Status.INCOMPLETE;
            return terminal(policy, status, evidence, identity);
        }
        if (evidence.controls().isEmpty()) {
            return terminal(
                    policy,
                    Status.INCOMPLETE,
                    evidence,
                    diagnostic(
                            "CONTROL_ATTRIBUTION_MISSING", null, "$.controls",
                            "at least one attributed control", "absent"));
        }

        List<Signal> signals = new ArrayList<>();
        signals.add(legibility(policy, evidence));
        signals.add(affordance(policy, evidence));
        signals.add(hierarchy(policy, evidence));
        signals.add(clipping(policy, evidence));
        signals.add(responsive(policy, evidence, comparisonViewport));
        signals.add(stability(evidence));
        Status status = Status.PASS;
        for (Signal signal : signals) {
            if (priority(signal.status()) > priority(status)) {
                status = signal.status();
            }
        }
        return new Result(
                "structural-usability/v1",
                status,
                policy.policyId(),
                policy.policyVersion(),
                policy,
                evidence,
                signals,
                List.of());
    }

    /** Returns a fail-closed result when a public observation cannot be decoded. */
    public static Result invalidObservation(
            Policy policy, Evidence evidence, String observed) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(evidence, "evidence");
        String bounded = Objects.requireNonNullElse(observed, "unavailable");
        if (bounded.isBlank()) {
            bounded = "unavailable";
        } else if (bounded.length() > 1024) {
            bounded = bounded.substring(0, 1024);
        }
        return terminal(
                policy,
                Status.INCOMPLETE,
                evidence,
                diagnostic(
                        "OBSERVATION_SCHEMA_INVALID",
                        null,
                        "$.structuralUsability",
                        "structural-observation/v1",
                        bounded));
    }

    private static Signal legibility(Policy policy, Evidence evidence) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (ControlEvidence control : evidence.controls()) {
            if (control.fontPixels() < policy.minimumFontPixels()) {
                diagnostics.add(diagnostic(
                        "TEXT_TOO_SMALL", control.controlId(), "fontPixels",
                        atLeast(policy.minimumFontPixels()), value(control.fontPixels())));
            }
            if (control.rasterResidual() > policy.maximumRasterResidual()) {
                diagnostics.add(diagnostic(
                        "TEXT_RASTER_SOFT", control.controlId(), "rasterResidual",
                        atMost(policy.maximumRasterResidual()), value(control.rasterResidual())));
            }
            if (control.contrastRatio() < policy.minimumContrastRatio()) {
                diagnostics.add(diagnostic(
                        "TEXT_CONTRAST_INSUFFICIENT", control.controlId(), "contrastRatio",
                        atLeast(policy.minimumContrastRatio()), value(control.contrastRatio())));
            }
            if (control.glyphClipped()) {
                diagnostics.add(diagnostic(
                        "GLYPH_BOUNDS_CLIPPED", control.controlId(), "glyphClipped",
                        "false", "true"));
            }
            if (control.labelControlId() == null
                    || !control.controlId().equals(control.labelledControlId())) {
                diagnostics.add(diagnostic(
                        "LABEL_ASSOCIATION_MISSING", control.controlId(), "labelControlId",
                        "reciprocal stable label/control IDs",
                        control.labelControlId() == null ? "absent" : "not reciprocal"));
            }
        }
        return signal("legibility", diagnostics);
    }

    private static Signal affordance(Policy policy, Evidence evidence) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (ControlEvidence control : evidence.controls()) {
            if (control.role() == null) {
                diagnostics.add(diagnostic(
                        "CONTROL_ROLE_MISSING", control.controlId(), "role",
                        "unambiguous semantic role", "absent"));
            } else if (!CONTROL_ROLES.contains(control.role())) {
                diagnostics.add(diagnostic(
                        "CONTROL_ROLE_AMBIGUOUS", control.controlId(), "role",
                        CONTROL_ROLES.toString(), control.role()));
            }
            if (control.labelControlId() == null
                    || !control.controlId().equals(control.labelledControlId())) {
                diagnostics.add(diagnostic(
                        "CONTROL_LABEL_ASSOCIATION_MISSING",
                        control.controlId(),
                        "labelControlId",
                        "reciprocal stable label/control IDs",
                        control.labelControlId() == null ? "absent" : "not reciprocal"));
            }
            if (!control.enabled()) {
                diagnostics.add(diagnostic(
                        "CONTROL_STATE_MISMATCH", control.controlId(), "enabled",
                        "true", "false"));
            }
            if (!control.focusable()) {
                diagnostics.add(diagnostic(
                        "CONTROL_NOT_FOCUSABLE", control.controlId(), "focusable",
                        "true", "false"));
            }
            if (control.hitBounds().width() < policy.minimumHitWidth()
                    || control.hitBounds().height() < policy.minimumHitHeight()) {
                diagnostics.add(diagnostic(
                        "HIT_TARGET_UNDERSIZED", control.controlId(), "hitBounds",
                        policy.minimumHitWidth() + "x" + policy.minimumHitHeight(),
                        control.hitBounds().width() + "x" + control.hitBounds().height()));
            }
            if (control.occluded()) {
                diagnostics.add(diagnostic(
                        "HIT_TARGET_OCCLUDED", control.controlId(), "occluded",
                        "false", "true"));
            }
        }
        return signal("affordance", diagnostics);
    }

    private static Signal hierarchy(Policy policy, Evidence evidence) {
        ControlEvidence control = control(evidence, policy.controlId());
        if (control == null) {
            return signal("hierarchy", List.of(diagnostic(
                    "EXPECTED_CONTROL_MISSING", policy.controlId(), "controlId",
                    policy.controlId(), "absent")));
        }
        List<Diagnostic> diagnostics = new ArrayList<>();
        compare(diagnostics, "HIERARCHY_ROLE_MISMATCH", control.controlId(),
                "hierarchyRole", policy.expectedHierarchyRole(), control.hierarchyRole());
        compare(diagnostics, "HIERARCHY_PARENT_MISMATCH", control.controlId(),
                "parentControlId", policy.expectedParentControlId(), control.parentControlId());
        if (!policy.expectedControlBounds().equals(control.visualBounds())) {
            diagnostics.add(diagnostic(
                    "ROW_GEOMETRY_MISMATCH", control.controlId(),
                    "visualBounds.framebuffer",
                    policy.expectedControlBounds().toString(),
                    control.visualBounds().toString()));
        }
        return signal("hierarchy", diagnostics);
    }

    private static Signal clipping(Policy policy, Evidence evidence) {
        ControlEvidence control = control(evidence, policy.controlId());
        if (control == null) {
            return signal("clipping", List.of(diagnostic(
                    "CLIP_CONTROL_MISSING", policy.controlId(), "controlId",
                    policy.controlId(), "absent")));
        }
        List<Diagnostic> diagnostics = new ArrayList<>();
        if (evidence.frameEdgeClipped()) {
            diagnostics.add(diagnostic(
                    "VIEWPORT_EDGE_CLIPPED", control.controlId(), "frameEdgeClipped",
                    "false", "true"));
        }
        compare(diagnostics, "SCROLL_OWNER_MISMATCH", control.controlId(),
                "scrollOwnerId", policy.expectedScrollOwnerId(), control.scrollOwnerId());
        compare(diagnostics, "CLIP_OWNER_MISMATCH", control.controlId(),
                "clipOwnerId", policy.expectedClipOwnerId(), control.clipOwnerId());
        if (!policy.expectedVisibleBounds().equals(control.visibleBounds())) {
            diagnostics.add(diagnostic(
                    "INTERNAL_CLIP_MISMATCH", control.controlId(), "visibleBounds.framebuffer",
                    policy.expectedVisibleBounds().toString(),
                    control.visibleBounds().toString()));
        }
        return signal("clipping", diagnostics);
    }

    private static Signal responsive(
            Policy policy, Evidence evidence, Evidence comparisonViewport) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        if (!policy.expectedPanelBounds().equals(evidence.panelBounds())) {
            diagnostics.add(diagnostic(
                    "CROSS_VIEWPORT_REFLOW_MISMATCH", null, "panelBounds.framebuffer",
                    policy.expectedPanelBounds().toString(),
                    evidence.panelBounds().toString()));
        }
        if (comparisonViewport != null
                && comparisonViewport.viewportId().equals(evidence.viewportId())) {
            diagnostics.add(diagnostic(
                    "COMPARISON_VIEWPORT_NOT_DISTINCT", null, "comparison.viewportId",
                    "different declared viewport", comparisonViewport.viewportId()));
        }
        return signal("responsive", diagnostics);
    }

    private static Signal stability(Evidence evidence) {
        List<FrameEvidence> frames = evidence.frames();
        if (frames.size() != 5) {
            return new Signal(
                    "scroll-stability",
                    Status.INCOMPLETE,
                    List.of(diagnostic(
                            "CAPTURE_FAMILY_INCOMPLETE", null, "frames",
                            "five post-settle frames", Integer.toString(frames.size()))));
        }
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (int index = 1; index < frames.size(); index++) {
            FrameEvidence previous = frames.get(index - 1);
            FrameEvidence current = frames.get(index);
            if (!current.stableAfter(previous)) {
                diagnostics.add(diagnostic(
                        "POST_SETTLE_CAPTURE_CHANGED", null, "frames[" + index + "]",
                        previous.identity(), current.identity()));
            }
        }
        return new Signal(
                "scroll-stability",
                diagnostics.isEmpty() ? Status.PASS : Status.UNSTABLE,
                diagnostics);
    }

    private static Diagnostic identityDiagnostic(Policy policy, Evidence evidence) {
        if (!"structural-usability/v1".equals(evidence.schemaVersion())) {
            return diagnostic(
                    "EVIDENCE_SCHEMA_UNSUPPORTED", null, "$.schemaVersion",
                    "structural-usability/v1", evidence.schemaVersion());
        }
        if (!policy.evaluatorId().equals(evidence.evaluatorId())
                || !policy.evaluatorSha256().equals(evidence.evaluatorSha256())) {
            return diagnostic(
                    "EVALUATOR_IDENTITY_MISMATCH", null, "$.evaluator",
                    policy.evaluatorId() + ":" + policy.evaluatorSha256(),
                    evidence.evaluatorId() + ":" + evidence.evaluatorSha256());
        }
        if (!policy.referenceSha256().equals(evidence.referenceSha256())) {
            return diagnostic(
                    "REFERENCE_IDENTITY_STALE", null, "$.referenceSha256",
                    policy.referenceSha256(), evidence.referenceSha256());
        }
        if (!policy.stateId().equals(evidence.stateId())
                || !policy.viewportId().equals(evidence.viewportId())
                || policy.width() != evidence.width()
                || policy.height() != evidence.height()
                || Double.compare(policy.deviceScale(), evidence.deviceScale()) != 0) {
            return diagnostic(
                    "OBSERVATION_IDENTITY_MISMATCH", null, "$.observation",
                    policy.stateId() + ":" + policy.viewportId() + ":"
                            + policy.width() + "x" + policy.height() + "@"
                            + policy.deviceScale(),
                    evidence.stateId() + ":" + evidence.viewportId() + ":"
                            + evidence.width() + "x" + evidence.height() + "@"
                            + evidence.deviceScale());
        }
        if (evidence.semanticRevision() < 0 || evidence.layoutRevision() < 0) {
            return diagnostic(
                    "REVISION_EVIDENCE_MISSING", null, "$.revision",
                    "non-negative semantic and layout revisions",
                    evidence.semanticRevision() + ":" + evidence.layoutRevision());
        }
        return null;
    }

    private static ControlEvidence control(Evidence evidence, String id) {
        return evidence.controls().stream()
                .filter(value -> value.controlId().equals(id))
                .findFirst()
                .orElse(null);
    }

    private static Result terminal(
            Policy policy, Status status, Evidence evidence, Diagnostic diagnostic) {
        List<Signal> signals = SIGNALS.stream()
                .map(name -> new Signal(name, status, List.of(diagnostic)))
                .toList();
        return new Result(
                "structural-usability/v1",
                status,
                policy.policyId(),
                policy.policyVersion(),
                policy,
                evidence,
                signals,
                List.of(diagnostic));
    }

    private static Signal signal(String name, List<Diagnostic> diagnostics) {
        return new Signal(
                name,
                diagnostics.isEmpty() ? Status.PASS : Status.FAIL,
                diagnostics);
    }

    private static void compare(
            List<Diagnostic> diagnostics,
            String code,
            String controlId,
            String path,
            String expected,
            String observed) {
        if (!Objects.equals(expected, observed)) {
            diagnostics.add(diagnostic(code, controlId, path, expected, observed));
        }
    }

    private static Diagnostic diagnostic(
            String code,
            String controlId,
            String path,
            String expected,
            String observed) {
        boolean geometry = path.contains("Bounds") || path.contains("bounds");
        String coordinateSpace = geometry ? "framebuffer-top-left" : "not-applicable";
        String units;
        if (geometry || path.contains("fontPixels")) {
            units = "pixels";
        } else if (path.contains("Ratio") || path.contains("Residual")) {
            units = "ratio";
        } else {
            units = "identity";
        }
        return new Diagnostic(
                code, controlId, path, coordinateSpace, units, expected, observed);
    }

    private static String atLeast(double value) {
        return ">=" + value;
    }

    private static String atMost(double value) {
        return "<=" + value;
    }

    private static String value(double value) {
        return Double.toString(value);
    }

    private static int priority(Status status) {
        return switch (status) {
            case PASS -> 0;
            case FAIL -> 1;
            case UNSTABLE -> 2;
            case STALE -> 3;
            case INCOMPLETE -> 4;
        };
    }

    /** Closed independent structural outcomes. */
    public enum Status {
        PASS,
        FAIL,
        INCOMPLETE,
        STALE,
        UNSTABLE
    }

    /** Immutable threshold and expected-structure identity. */
    public record Policy(
            String policyId,
            int policyVersion,
            String evaluatorId,
            String evaluatorSha256,
            String referenceSha256,
            String stateId,
            String viewportId,
            int width,
            int height,
            double deviceScale,
            double minimumFontPixels,
            double maximumRasterResidual,
            double minimumContrastRatio,
            double minimumHitWidth,
            double minimumHitHeight,
            String controlId,
            Rect expectedPanelBounds,
            Rect expectedControlBounds,
            String expectedHierarchyRole,
            String expectedParentControlId,
            String expectedScrollOwnerId,
            String expectedClipOwnerId,
            Rect expectedVisibleBounds) {
        /** Validates explicit identity, finite thresholds, and reference geometry. */
        public Policy {
            nonBlank(policyId, "policyId");
            if (policyVersion < 1) {
                throw new IllegalArgumentException("policyVersion must be positive");
            }
            nonBlank(evaluatorId, "evaluatorId");
            sha(evaluatorSha256, "evaluatorSha256");
            sha(referenceSha256, "referenceSha256");
            nonBlank(stateId, "stateId");
            nonBlank(viewportId, "viewportId");
            if (width < 1 || height < 1 || !Double.isFinite(deviceScale)
                    || deviceScale <= 0) {
                throw new IllegalArgumentException("policy display identity is invalid");
            }
            for (double value : new double[] {
                    minimumFontPixels,
                    maximumRasterResidual,
                    minimumContrastRatio,
                    minimumHitWidth,
                    minimumHitHeight}) {
                if (!Double.isFinite(value) || value < 0) {
                    throw new IllegalArgumentException(
                            "policy thresholds must be finite and non-negative");
                }
            }
            nonBlank(controlId, "controlId");
            Objects.requireNonNull(expectedPanelBounds, "expectedPanelBounds");
            Objects.requireNonNull(expectedControlBounds, "expectedControlBounds");
            nonBlank(expectedHierarchyRole, "expectedHierarchyRole");
            nonBlank(expectedParentControlId, "expectedParentControlId");
            nonBlank(expectedScrollOwnerId, "expectedScrollOwnerId");
            nonBlank(expectedClipOwnerId, "expectedClipOwnerId");
            Objects.requireNonNull(expectedVisibleBounds, "expectedVisibleBounds");
        }
    }

    /** Capture-bound evidence with no treatment or human-outcome identity. */
    public record Evidence(
            String schemaVersion,
            String evaluatorId,
            String evaluatorSha256,
            String referenceSha256,
            String captureSha256,
            String stateId,
            String viewportId,
            int width,
            int height,
            double deviceScale,
            long semanticRevision,
            long layoutRevision,
            boolean frameEdgeClipped,
            Rect panelBounds,
            List<ControlEvidence> controls,
            List<FrameEvidence> frames) {
        /** Copies and validates bounded evidence. */
        public Evidence {
            nonBlank(schemaVersion, "schemaVersion");
            nonBlank(evaluatorId, "evaluatorId");
            sha(evaluatorSha256, "evaluatorSha256");
            sha(referenceSha256, "referenceSha256");
            sha(captureSha256, "captureSha256");
            nonBlank(stateId, "stateId");
            nonBlank(viewportId, "viewportId");
            if (width < 1 || height < 1 || !Double.isFinite(deviceScale)
                    || deviceScale <= 0) {
                throw new IllegalArgumentException("evidence display identity is invalid");
            }
            Objects.requireNonNull(panelBounds, "panelBounds");
            controls = uniqueControls(controls);
            frames = List.copyOf(Objects.requireNonNull(frames, "frames"));
            if (frames.size() > 120) {
                throw new IllegalArgumentException("frames exceeds 120");
            }
        }

        public Evidence withViewport(String id, int newWidth, int newHeight) {
            return new Evidence(
                    schemaVersion, evaluatorId, evaluatorSha256, referenceSha256,
                    captureSha256, stateId, id, newWidth, newHeight, deviceScale,
                    semanticRevision, layoutRevision, frameEdgeClipped, panelBounds,
                    controls, frames);
        }

        public Evidence withPanelBounds(Rect value) {
            return new Evidence(
                    schemaVersion, evaluatorId, evaluatorSha256, referenceSha256,
                    captureSha256, stateId, viewportId, width, height, deviceScale,
                    semanticRevision, layoutRevision, frameEdgeClipped, value,
                    controls, frames);
        }

        public Evidence withFrames(List<FrameEvidence> value) {
            return new Evidence(
                    schemaVersion, evaluatorId, evaluatorSha256, referenceSha256,
                    captureSha256, stateId, viewportId, width, height, deviceScale,
                    semanticRevision, layoutRevision, frameEdgeClipped, panelBounds,
                    controls, value);
        }

        public Evidence withReferenceSha256(String value) {
            return new Evidence(
                    schemaVersion, evaluatorId, evaluatorSha256, value,
                    captureSha256, stateId, viewportId, width, height, deviceScale,
                    semanticRevision, layoutRevision, frameEdgeClipped, panelBounds,
                    controls, frames);
        }

        public Evidence withControls(List<ControlEvidence> value) {
            return new Evidence(
                    schemaVersion, evaluatorId, evaluatorSha256, referenceSha256,
                    captureSha256, stateId, viewportId, width, height, deviceScale,
                    semanticRevision, layoutRevision, frameEdgeClipped, panelBounds,
                    value, frames);
        }
    }

    /** Candidate-observed structure before evaluator-owned identities are bound. */
    public record Observation(
            String schemaVersion,
            long semanticRevision,
            long layoutRevision,
            boolean frameEdgeClipped,
            double scrollY,
            String semanticSha256,
            String layoutSha256,
            String regionSha256,
            Rect panelBounds,
            List<ControlEvidence> controls) {
        /** Validates bounded, hash-addressed observation data. */
        public Observation {
            if (!"structural-observation/v1".equals(schemaVersion)
                    || semanticRevision < 0
                    || layoutRevision < 0
                    || !Double.isFinite(scrollY)) {
                throw new IllegalArgumentException("structural observation identity is invalid");
            }
            sha(semanticSha256, "semanticSha256");
            sha(layoutSha256, "layoutSha256");
            sha(regionSha256, "regionSha256");
            Objects.requireNonNull(panelBounds, "panelBounds");
            controls = uniqueControls(controls);
        }
    }

    /** One stable attributed control's structural evidence. */
    public record ControlEvidence(
            String controlId,
            String role,
            String labelControlId,
            String labelledControlId,
            boolean enabled,
            boolean focusable,
            Rect hitBounds,
            Rect visualBounds,
            boolean occluded,
            double fontPixels,
            double rasterResidual,
            double contrastRatio,
            boolean glyphClipped,
            String hierarchyRole,
            String parentControlId,
            String scrollOwnerId,
            String clipOwnerId,
            Rect visibleBounds) {
        /** Validates bounded stable identity and finite measurements. */
        public ControlEvidence {
            nonBlank(controlId, "controlId");
            optional(role, "role");
            optional(labelControlId, "labelControlId");
            optional(labelledControlId, "labelledControlId");
            Objects.requireNonNull(hitBounds, "hitBounds");
            Objects.requireNonNull(visualBounds, "visualBounds");
            for (double value : new double[] {
                    fontPixels, rasterResidual, contrastRatio}) {
                if (!Double.isFinite(value) || value < 0) {
                    throw new IllegalArgumentException(
                            "control measurements must be finite and non-negative");
                }
            }
            nonBlank(hierarchyRole, "hierarchyRole");
            nonBlank(parentControlId, "parentControlId");
            optional(scrollOwnerId, "scrollOwnerId");
            optional(clipOwnerId, "clipOwnerId");
            Objects.requireNonNull(visibleBounds, "visibleBounds");
        }

        public ControlEvidence withFontPixels(double value) {
            return copy(role, labelControlId, enabled, focusable, hitBounds, occluded,
                    value, rasterResidual, contrastRatio, glyphClipped,
                    hierarchyRole, parentControlId, visibleBounds);
        }

        public ControlEvidence withRasterResidual(double value) {
            return copy(role, labelControlId, enabled, focusable, hitBounds, occluded,
                    fontPixels, value, contrastRatio, glyphClipped,
                    hierarchyRole, parentControlId, visibleBounds);
        }

        public ControlEvidence withContrastRatio(double value) {
            return copy(role, labelControlId, enabled, focusable, hitBounds, occluded,
                    fontPixels, rasterResidual, value, glyphClipped,
                    hierarchyRole, parentControlId, visibleBounds);
        }

        public ControlEvidence withGlyphClipped(boolean value) {
            return copy(role, labelControlId, enabled, focusable, hitBounds, occluded,
                    fontPixels, rasterResidual, contrastRatio, value,
                    hierarchyRole, parentControlId, visibleBounds);
        }

        public ControlEvidence withLabelControlId(String value) {
            return copy(role, value, enabled, focusable, hitBounds, occluded,
                    fontPixels, rasterResidual, contrastRatio, glyphClipped,
                    hierarchyRole, parentControlId, visibleBounds);
        }

        public ControlEvidence withRole(String value) {
            return copy(value, labelControlId, enabled, focusable, hitBounds, occluded,
                    fontPixels, rasterResidual, contrastRatio, glyphClipped,
                    hierarchyRole, parentControlId, visibleBounds);
        }

        public ControlEvidence withFocusable(boolean value) {
            return copy(role, labelControlId, enabled, value, hitBounds, occluded,
                    fontPixels, rasterResidual, contrastRatio, glyphClipped,
                    hierarchyRole, parentControlId, visibleBounds);
        }

        public ControlEvidence withEnabled(boolean value) {
            return copy(role, labelControlId, value, focusable, hitBounds, occluded,
                    fontPixels, rasterResidual, contrastRatio, glyphClipped,
                    hierarchyRole, parentControlId, visibleBounds);
        }

        public ControlEvidence withHitBounds(Rect value) {
            return copy(role, labelControlId, enabled, focusable, value, occluded,
                    fontPixels, rasterResidual, contrastRatio, glyphClipped,
                    hierarchyRole, parentControlId, visibleBounds);
        }

        public ControlEvidence withOccluded(boolean value) {
            return copy(role, labelControlId, enabled, focusable, hitBounds, value,
                    fontPixels, rasterResidual, contrastRatio, glyphClipped,
                    hierarchyRole, parentControlId, visibleBounds);
        }

        public ControlEvidence withHierarchy(String hierarchy, String parent) {
            return copy(role, labelControlId, enabled, focusable, hitBounds, occluded,
                    fontPixels, rasterResidual, contrastRatio, glyphClipped,
                    hierarchy, parent, visibleBounds);
        }

        public ControlEvidence withVisibleBounds(Rect value) {
            return copy(role, labelControlId, enabled, focusable, hitBounds, occluded,
                    fontPixels, rasterResidual, contrastRatio, glyphClipped,
                    hierarchyRole, parentControlId, value);
        }

        private ControlEvidence copy(
                String newRole,
                String newLabelControlId,
                boolean newEnabled,
                boolean newFocusable,
                Rect newHitBounds,
                boolean newOccluded,
                double newFontPixels,
                double newRasterResidual,
                double newContrastRatio,
                boolean newGlyphClipped,
                String newHierarchyRole,
                String newParentControlId,
                Rect newVisibleBounds) {
            return new ControlEvidence(
                    controlId, newRole, newLabelControlId, labelledControlId,
                    newEnabled, newFocusable, newHitBounds, visualBounds,
                    newOccluded, newFontPixels, newRasterResidual, newContrastRatio,
                    newGlyphClipped, newHierarchyRole, newParentControlId,
                    scrollOwnerId, clipOwnerId, newVisibleBounds);
        }
    }

    /** One post-settle frame identity; all signals must remain exactly stable. */
    public record FrameEvidence(
            long frame,
            long semanticRevision,
            long layoutRevision,
            double scrollY,
            String semanticSha256,
            String layoutSha256,
            String regionSha256,
            String framebufferSha256) {
        /** Validates finite ordered identities. */
        public FrameEvidence {
            if (frame < 0 || semanticRevision < 0 || layoutRevision < 0
                    || !Double.isFinite(scrollY)) {
                throw new IllegalArgumentException("frame identity is invalid");
            }
            sha(semanticSha256, "semanticSha256");
            sha(layoutSha256, "layoutSha256");
            sha(regionSha256, "regionSha256");
            sha(framebufferSha256, "framebufferSha256");
        }

        boolean stableAfter(FrameEvidence previous) {
            return frame == previous.frame + 1
                    && semanticRevision == previous.semanticRevision
                    && layoutRevision == previous.layoutRevision
                    && Double.compare(scrollY, previous.scrollY) == 0
                    && semanticSha256.equals(previous.semanticSha256)
                    && layoutSha256.equals(previous.layoutSha256)
                    && regionSha256.equals(previous.regionSha256)
                    && framebufferSha256.equals(previous.framebufferSha256);
        }

        String identity() {
            return frame + ":" + semanticRevision + ":" + layoutRevision + ":"
                    + scrollY + ":" + semanticSha256 + ":" + layoutSha256 + ":"
                    + regionSha256 + ":" + framebufferSha256;
        }
    }

    /** One framebuffer rectangle using top-left coordinates and pixels. */
    public record Rect(double x, double y, double width, double height) {
        /** Requires finite non-negative geometry. */
        public Rect {
            for (double value : new double[] {x, y, width, height}) {
                if (!Double.isFinite(value) || value < 0) {
                    throw new IllegalArgumentException(
                            "rectangle values must be finite and non-negative");
                }
            }
        }
    }

    /** One independent signal and its attributed diagnostics. */
    public record Signal(
            String name,
            int version,
            String direction,
            String scope,
            List<String> knownExclusions,
            Status status,
            List<Diagnostic> diagnostics) {
        /** Copies a known signal. */
        public Signal {
            if (!SIGNALS.contains(name)) {
                throw new IllegalArgumentException("unknown structural signal");
            }
            if (version != 1 || !"pass-required".equals(direction)) {
                throw new IllegalArgumentException("unsupported structural signal definition");
            }
            nonBlank(scope, "scope");
            knownExclusions = List.copyOf(
                    Objects.requireNonNull(knownExclusions, "knownExclusions"));
            Objects.requireNonNull(status, "status");
            diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
        }

        Signal(String name, Status status, List<Diagnostic> diagnostics) {
            this(
                    name,
                    1,
                    "pass-required",
                    scope(name),
                    exclusions(name),
                    status,
                    diagnostics);
        }

        private static String scope(String name) {
            return switch (name) {
                case "legibility" ->
                        "attributed glyph size, raster residual, contrast, clipping, labels";
                case "affordance" ->
                        "semantic role, state, focusability, hit target, occlusion";
                case "hierarchy" ->
                        "declared title, panel, form, row ownership and geometry";
                case "clipping" ->
                        "frame and internal clip-owner visible intersections";
                case "responsive" ->
                        "declared 1920x1080 and 1280x720 device-scale-1 layouts";
                case "scroll-stability" ->
                        "five one-frame-apart post-settle semantic/layout/region/PNG identities";
                default -> throw new IllegalArgumentException("unknown structural signal");
            };
        }

        private static List<String> exclusions(String name) {
            return switch (name) {
                case "legibility" -> List.of("language comprehension", "font aesthetics");
                case "affordance" -> List.of("learned product conventions");
                case "hierarchy" -> List.of("subjective visual preference");
                case "clipping" -> List.of("undeclared dynamic masks");
                case "responsive" -> List.of("all viewport sizes except 1920x1080 and 1280x720");
                case "scroll-stability" -> List.of("cross-platform PNG equivalence");
                default -> throw new IllegalArgumentException("unknown structural signal");
            };
        }
    }

    /** One stable expected-versus-observed predicate failure. */
    public record Diagnostic(
            String code,
            String controlId,
            String path,
            String coordinateSpace,
            String units,
            String expected,
            String observed) {
        /** Validates bounded diagnostic text. */
        public Diagnostic {
            nonBlank(code, "code");
            optional(controlId, "controlId");
            nonBlank(path, "path");
            nonBlank(coordinateSpace, "coordinateSpace");
            nonBlank(units, "units");
            nonBlank(expected, "expected");
            nonBlank(observed, "observed");
        }
    }

    /** Complete independent structural outcome. */
    public record Result(
            String schemaVersion,
            Status status,
            String policyId,
            int policyVersion,
            Policy policy,
            Evidence evidence,
            List<Signal> signals,
            List<Diagnostic> diagnostics) {
        /** Copies bounded output. */
        public Result {
            if (!"structural-usability/v1".equals(schemaVersion)) {
                throw new IllegalArgumentException("unsupported structural result");
            }
            Objects.requireNonNull(status, "status");
            nonBlank(policyId, "policyId");
            if (policyVersion < 1) {
                throw new IllegalArgumentException("policyVersion must be positive");
            }
            Objects.requireNonNull(policy, "policy");
            if (!policyId.equals(policy.policyId())
                    || policyVersion != policy.policyVersion()) {
                throw new IllegalArgumentException("result policy identity mismatch");
            }
            Objects.requireNonNull(evidence, "evidence");
            signals = List.copyOf(Objects.requireNonNull(signals, "signals"));
            diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
        }
    }

    private static List<ControlEvidence> uniqueControls(List<ControlEvidence> controls) {
        List<ControlEvidence> values =
                List.copyOf(Objects.requireNonNull(controls, "controls"));
        if (values.size() > 256) {
            throw new IllegalArgumentException("controls exceeds 256");
        }
        Map<String, ControlEvidence> unique = new LinkedHashMap<>();
        for (ControlEvidence control : values) {
            if (unique.putIfAbsent(control.controlId(), control) != null) {
                throw new IllegalArgumentException(
                        "duplicate structural control: " + control.controlId());
            }
        }
        return List.copyOf(unique.values());
    }

    private static void sha(String value, String name) {
        if (!SHA_256.matcher(Objects.requireNonNull(value, name)).matches()) {
            throw new IllegalArgumentException(name + " must be lowercase SHA-256");
        }
    }

    private static void optional(String value, String name) {
        if (value != null) {
            nonBlank(value, name);
        }
    }

    private static void nonBlank(String value, String name) {
        if (Objects.requireNonNull(value, name).isBlank() || value.length() > 16_384) {
            throw new IllegalArgumentException(name + " must be non-blank and bounded");
        }
    }
}
