package dev.gdx.uiharness.core.layout;

import dev.gdx.uiharness.core.typography.CoordinateBounds;
import dev.gdx.uiharness.core.typography.CoordinateSpace;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Evaluates capture-bound layout evidence without guessing missing ownership. */
public final class LayoutEvaluator {
    /** Evaluates selected controls and their declared cross-control relationships. */
    public List<LayoutReport> evaluate(
            List<LayoutObservation> observations,
            List<LayoutExpectation> expectations) {
        Objects.requireNonNull(observations, "observations");
        Objects.requireNonNull(expectations, "expectations");
        Map<String, LayoutObservation> observedById = indexObservations(observations);
        List<LayoutReport> reports = new ArrayList<>();
        for (LayoutExpectation expected : expectations) {
            LayoutObservation observed = observedById.get(expected.controlId());
            if (observed == null) {
                continue;
            }
            reports.add(evaluateOne(observed, expected, observedById));
        }
        return List.copyOf(reports);
    }

    /** Evaluates one actor when no relationship lookup is needed. */
    public LayoutReport evaluate(
            LayoutObservation observed, LayoutExpectation expected) {
        return evaluateOne(
                Objects.requireNonNull(observed, "observed"),
                Objects.requireNonNull(expected, "expected"),
                Map.of(observed.controlId(), observed));
    }

    private static LayoutReport evaluateOne(
            LayoutObservation observed,
            LayoutExpectation expected,
            Map<String, LayoutObservation> observations) {
        List<LayoutDiagnostic> diagnostics = new ArrayList<>();
        compareIdentity(diagnostics, observed, expected);
        if (observed.revision() != expected.revision()) {
            add(diagnostics, observed, expected, "revision",
                    expected.revision(), observed.revision(), "revision", null);
        }
        if (observed.frame() != expected.frame()) {
            add(diagnostics, observed, expected, "frame",
                    expected.frame(), observed.frame(), "frame", null);
        }
        if (!diagnostics.isEmpty()) {
            return report(LayoutStatus.STALE, observed, diagnostics);
        }
        if (observed.layoutRevision() != expected.layoutRevision()
                || !observed.layoutSha256().equals(expected.expectedLayoutSha256())) {
            if (observed.layoutRevision() != expected.layoutRevision()) {
                add(diagnostics, observed, expected, "layoutRevision",
                        expected.layoutRevision(), observed.layoutRevision(), "revision", null);
            }
            if (!observed.layoutSha256().equals(expected.expectedLayoutSha256())) {
                add(diagnostics, observed, expected, "layoutSha256",
                        expected.expectedLayoutSha256(), observed.layoutSha256(), "sha256", null);
            }
            return report(LayoutStatus.NOT_STABLE, observed, diagnostics);
        }
        if (!observed.transforms().invertible()) {
            add(diagnostics, observed, expected, "transforms.invertible",
                    true, "non-invertible", "boolean", null);
            return report(LayoutStatus.NOT_DIAGNOSABLE, observed, diagnostics);
        }
        compareOwnersAndRole(diagnostics, observed, expected);
        boolean missingClipOwner = expected.expectedClipOwnerId() != null
                && observed.observedClipOwnerId() == null;
        compareGeometry(diagnostics, observed, expected);
        compareRelationship(diagnostics, observed, expected, observations);
        if (missingClipOwner) {
            return report(LayoutStatus.INCOMPLETE, observed, diagnostics);
        }
        return report(
                diagnostics.isEmpty()
                        ? LayoutStatus.CONFORMANT : LayoutStatus.NON_CONFORMANT,
                observed,
                diagnostics);
    }

    private static void compareIdentity(
            List<LayoutDiagnostic> diagnostics,
            LayoutObservation observed,
            LayoutExpectation expected) {
        compare(diagnostics, observed, expected, "controlId",
                expected.controlId(), observed.controlId(), "id", null);
        compare(diagnostics, observed, expected, "applicationId",
                expected.applicationId(), observed.display().applicationId(), "id", null);
        compare(diagnostics, observed, expected, "viewportId",
                expected.viewportId(), observed.display().viewportId(), "id", null);
        compare(diagnostics, observed, expected, "currentArtifactId",
                expected.currentArtifactId(), observed.currentArtifactId(), "id", null);
    }

    private static void compareOwnersAndRole(
            List<LayoutDiagnostic> diagnostics,
            LayoutObservation observed,
            LayoutExpectation expected) {
        compare(diagnostics, observed, expected, "parentActorId",
                expected.expectedParentActorId(), observed.parentActorId(), "id", null);
        compare(diagnostics, observed, expected, "layoutOwnerId",
                expected.expectedLayoutOwnerId(), observed.layoutOwnerId(), "id", null);
        compare(diagnostics, observed, expected, "scrollOwnerId",
                expected.expectedScrollOwnerId(), observed.scrollOwnerId(), "id", null);
        compare(diagnostics, observed, expected, "clipOwnerId",
                expected.expectedClipOwnerId(), observed.observedClipOwnerId(), "id", null);
        compare(diagnostics, observed, expected, "layoutRole",
                expected.expectedLayoutRole(), observed.layoutRole(), "enum", null);
    }

    private static void compareGeometry(
            List<LayoutDiagnostic> diagnostics,
            LayoutObservation observed,
            LayoutExpectation expected) {
        for (CoordinateBounds expectedBounds : expected.expectedBounds()) {
            CoordinateBounds actual = observed.bounds(expectedBounds.space());
            if (maxEdgeDifference(expectedBounds, actual) > expected.boundsTolerance()) {
                add(diagnostics, observed, expected,
                        "bounds." + expectedBounds.space().name().toLowerCase(),
                        expectedBounds, actual, "px", expectedBounds.space().name());
            }
        }
        comparePadding(diagnostics, observed, expected);
        if (maxEdgeDifference(
                        expected.expectedVisibleIntersection(),
                        observed.visibleIntersection())
                > expected.boundsTolerance()) {
            add(diagnostics, observed, expected, "internalClip.intersection",
                    expected.expectedVisibleIntersection(), observed.visibleIntersection(),
                    "px", CoordinateSpace.FRAMEBUFFER.name());
        }
    }

    private static void comparePadding(
            List<LayoutDiagnostic> diagnostics,
            LayoutObservation observed,
            LayoutExpectation expected) {
        LayoutPadding actual = observed.padding();
        LayoutPadding wanted = expected.expectedPadding();
        double difference = Math.max(
                Math.max(Math.abs(actual.top() - wanted.top()),
                        Math.abs(actual.right() - wanted.right())),
                Math.max(Math.abs(actual.bottom() - wanted.bottom()),
                        Math.abs(actual.left() - wanted.left())));
        if (difference > expected.paddingTolerance()) {
            add(diagnostics, observed, expected, "padding",
                    wanted, actual, "logical-px", CoordinateSpace.LOCAL.name());
        }
    }

    private static void compareRelationship(
            List<LayoutDiagnostic> diagnostics,
            LayoutObservation observed,
            LayoutExpectation expected,
            Map<String, LayoutObservation> observations) {
        LayoutRelationship relationship = expected.relationship();
        if (relationship == null) {
            return;
        }
        LayoutObservation related = observations.get(relationship.relatedControlId());
        if (related == null) {
            add(diagnostics, observed, expected, "relationship.actor",
                    relationship.relatedControlId(), "missing", "id", null);
            return;
        }
        CoordinateBounds actual = observed.bounds(CoordinateSpace.FRAMEBUFFER);
        CoordinateBounds other = related.bounds(CoordinateSpace.FRAMEBUFFER);
        double deltaX = actual.x() - other.x();
        double deltaY = actual.y() - other.y();
        if (Math.abs(deltaX - relationship.expectedDeltaX()) > relationship.tolerance()
                || Math.abs(deltaY - relationship.expectedDeltaY())
                        > relationship.tolerance()) {
            add(diagnostics, observed, expected, "relationship.framebufferOrigin",
                    relationship.expectedDeltaX() + "," + relationship.expectedDeltaY(),
                    deltaX + "," + deltaY,
                    "px",
                    CoordinateSpace.FRAMEBUFFER.name());
        }
    }

    private static Map<String, LayoutObservation> indexObservations(
            List<LayoutObservation> values) {
        LinkedHashMap<String, LayoutObservation> result = new LinkedHashMap<>();
        for (LayoutObservation value : values) {
            if (result.putIfAbsent(value.controlId(), value) != null) {
                throw new IllegalArgumentException(
                        "duplicate layout observation: " + value.controlId());
            }
        }
        return Map.copyOf(result);
    }

    private static double maxEdgeDifference(
            CoordinateBounds first, CoordinateBounds second) {
        return Math.max(
                Math.max(Math.abs(first.x() - second.x()),
                        Math.abs(first.y() - second.y())),
                Math.max(
                        Math.abs(first.x() + first.width()
                                - second.x() - second.width()),
                        Math.abs(first.y() + first.height()
                                - second.y() - second.height())));
    }

    private static void compare(
            List<LayoutDiagnostic> diagnostics,
            LayoutObservation observed,
            LayoutExpectation expected,
            String path,
            Object wanted,
            Object actual,
            String units,
            String coordinateSpace) {
        if (!Objects.equals(wanted, actual)) {
            add(diagnostics, observed, expected, path,
                    wanted, actual, units, coordinateSpace);
        }
    }

    private static void add(
            List<LayoutDiagnostic> diagnostics,
            LayoutObservation observed,
            LayoutExpectation expected,
            String path,
            Object wanted,
            Object actual,
            String units,
            String coordinateSpace) {
        diagnostics.add(new LayoutDiagnostic(
                observed.controlId(),
                path,
                wanted == null ? "not-applicable" : String.valueOf(wanted),
                actual == null ? "missing" : String.valueOf(actual),
                units,
                coordinateSpace,
                expected.referenceArtifactId(),
                observed.currentArtifactId()));
    }

    private static LayoutReport report(
            LayoutStatus status,
            LayoutObservation observed,
            List<LayoutDiagnostic> diagnostics) {
        return new LayoutReport("layout/v1", status, observed, diagnostics);
    }
}
