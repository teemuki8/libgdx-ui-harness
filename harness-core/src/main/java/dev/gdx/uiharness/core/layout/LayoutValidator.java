package dev.gdx.uiharness.core.layout;

import dev.gdx.uiharness.core.model.Bounds;
import dev.gdx.uiharness.core.model.Role;
import dev.gdx.uiharness.core.model.SemanticNode;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import dev.gdx.uiharness.core.navigation.NavigationResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pure whole-stage layout invariant validator over one immutable semantic observation. Findings
 * are bounded, deterministically ordered, and classified against a configurable severity gate.
 * Execution never reads backend state and never dispatches input.
 */
public final class LayoutValidator {
    private static final Set<Role> TEXT_BEARING_ROLES = Set.of(
            Role.LABEL, Role.BUTTON, Role.TEXT_FIELD, Role.TEXT_AREA, Role.MENU_ITEM,
            Role.LIST_ITEM, Role.CHECKBOX, Role.RADIO_BUTTON, Role.SELECT, Role.SLIDER);
    private static final Set<Role> INTERACTIVE_ROLES = Set.of(
            Role.BUTTON, Role.CHECKBOX, Role.RADIO_BUTTON, Role.TEXT_FIELD, Role.TEXT_AREA,
            Role.SELECT, Role.SLIDER, Role.LIST_ITEM, Role.MENU_ITEM);
    private static final Set<Role> TARGET_SIZE_ROLES = Set.of(
            Role.BUTTON, Role.CHECKBOX, Role.TEXT_FIELD, Role.SELECT, Role.SLIDER);
    private static final double TEXT_EDGE_EPSILON = 0.5;

    /** Validates the supplied immutable observation without backend intrinsic evidence. */
    public LayoutValidationResult validate(
            SemanticSnapshot snapshot,
            LayoutValidationConfig config,
            NavigationResult navigation) {
        return validate(
                snapshot, config, navigation, LayoutValidationEvidence.unavailable());
    }

    /** Validates the supplied immutable observation and backend intrinsic evidence. */
    public LayoutValidationResult validate(
            SemanticSnapshot snapshot,
            LayoutValidationConfig config,
            NavigationResult navigation,
            LayoutValidationEvidence evidence) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(evidence, "evidence");
        List<SemanticNode> ordered = documentOrder(snapshot);
        boolean coverageLimited = ordered.size() > config.maxNodes();
        List<SemanticNode> examined = coverageLimited
                ? ordered.subList(0, config.maxNodes()) : ordered;

        Sink findings = new Sink(config.maxFindings());
        boolean truncated = coverageLimited;
        Map<LayoutValidationCheck, Boolean> availability = new LinkedHashMap<>();
        checkAvailability(availability, config, navigation, evidence);

        if (config.isEnabled(LayoutValidationCheck.OUTSIDE_VIEWPORT)) {
            checkOutsideViewport(examined, findings, config);
        }
        if (config.isEnabled(LayoutValidationCheck.CLIPPED_TEXT)
                && availability.get(LayoutValidationCheck.CLIPPED_TEXT) == Boolean.TRUE) {
            checkClippedText(snapshot, examined, evidence, findings);
        }
        if (config.isEnabled(LayoutValidationCheck.TEXT_COLLISION)
                && availability.get(LayoutValidationCheck.TEXT_COLLISION) == Boolean.TRUE) {
            checkTextCollision(snapshot, examined, evidence, findings);
        }
        if (config.isEnabled(LayoutValidationCheck.ZERO_SIZE)) {
            checkZeroSize(examined, findings, config);
        }
        if (config.isEnabled(LayoutValidationCheck.BELOW_TARGET_SIZE)) {
            checkBelowTargetSize(examined, findings, config);
        }
        if (config.isEnabled(LayoutValidationCheck.DUPLICATE_TEST_ID)) {
            checkDuplicateTestIds(examined, findings, config);
        }
        if (config.isEnabled(LayoutValidationCheck.MISSING_ACCESSIBLE_NAME)) {
            checkMissingAccessibleName(examined, findings, config);
        }
        if (config.isEnabled(LayoutValidationCheck.OBSCURED)) {
            checkObscured(snapshot, examined, findings, config);
        }
        if (config.isEnabled(LayoutValidationCheck.INTERACTIVE_OVERLAP)) {
            checkInteractiveOverlap(examined, findings, config);
        }
        if (config.isEnabled(LayoutValidationCheck.KEYBOARD_UNREACHABLE)
                && availability.get(LayoutValidationCheck.KEYBOARD_UNREACHABLE) == Boolean.TRUE) {
            checkKeyboardUnreachable(examined, findings, config, Objects.requireNonNull(navigation));
        }
        if (config.isEnabled(LayoutValidationCheck.INCONSISTENT_ALIGNMENT)
                || config.isEnabled(LayoutValidationCheck.INCONSISTENT_SPACING)) {
            LinkedHashMap<String, LinkedHashMap<GroupKey, List<SemanticNode>>> explicitGroups =
                    explicitSiblingGroups(examined);
            if (config.isEnabled(LayoutValidationCheck.INCONSISTENT_ALIGNMENT)) {
                availability.put(
                        LayoutValidationCheck.INCONSISTENT_ALIGNMENT,
                        checkConsistentAlignment(explicitGroups, findings, config));
            }
            if (config.isEnabled(LayoutValidationCheck.INCONSISTENT_SPACING)) {
                availability.put(
                        LayoutValidationCheck.INCONSISTENT_SPACING,
                        checkConsistentSpacing(explicitGroups, findings, config));
            }
        }
        for (LayoutValidationCheck check : availability.keySet()) {
            if (availability.get(check) == Boolean.FALSE) {
                findings.add(new LayoutFinding(
                        LayoutValidationReason.CHECK_UNAVAILABLE,
                        LayoutValidationSeverity.ERROR,
                        snapshot.rootId(),
                        null,
                        boundsOf(snapshot.nodes().get(snapshot.rootId())),
                        "check unavailable: "
                                + check.name().toLowerCase(java.util.Locale.ROOT)));
            }
        }

        List<LayoutFinding> orderedFindings = findings.list().stream()
                .sorted(Comparator
                        .comparingInt((LayoutFinding finding) -> finding.reason().ordinal())
                        .thenComparing(LayoutFinding::nodeId)
                        .thenComparing(finding -> finding.relatedActorId() == null
                                ? "" : finding.relatedActorId())
                        .thenComparing(LayoutFinding::evidence))
                .toList();
        truncated = truncated || findings.overflow();
        boolean gateHit = findings.reaches(config.failOn());
        LayoutValidationResult.Status status = truncated && orderedFindings.isEmpty()
                ? LayoutValidationResult.Status.INCOMPLETE
                : gateHit ? LayoutValidationResult.Status.FAIL
                        : LayoutValidationResult.Status.PASS;
        return new LayoutValidationResult(
                status, orderedFindings, examined.size(), truncated, config);
    }

    private static void checkAvailability(
            Map<LayoutValidationCheck, Boolean> availability,
            LayoutValidationConfig config,
            NavigationResult navigation,
            LayoutValidationEvidence evidence) {
        if (config.isEnabled(LayoutValidationCheck.CLIPPED_TEXT)) {
            availability.put(
                    LayoutValidationCheck.CLIPPED_TEXT, evidence.textGeometryAvailable());
        }
        if (config.isEnabled(LayoutValidationCheck.TEXT_COLLISION)) {
            availability.put(
                    LayoutValidationCheck.TEXT_COLLISION, evidence.textGeometryAvailable());
        }
        if (config.isEnabled(LayoutValidationCheck.KEYBOARD_UNREACHABLE)) {
            availability.put(LayoutValidationCheck.KEYBOARD_UNREACHABLE, navigation != null);
        }
        if (config.isEnabled(LayoutValidationCheck.INVALID_CLIP_SCROLL)) {
            // The semantic snapshot carries clipped state but not clip-chain geometry;
            // without it the check cannot be evaluated.
            availability.put(LayoutValidationCheck.INVALID_CLIP_SCROLL, false);
        }
    }

    private static void checkOutsideViewport(
            List<SemanticNode> nodes, Sink findings,
            LayoutValidationConfig config) {
        for (SemanticNode node : nodes) {
            if (node.state().visible() && !node.state().viewportIntersecting()) {
                findings.add(new LayoutFinding(
                        LayoutValidationReason.OUTSIDE_VIEWPORT,
                        LayoutValidationSeverity.ERROR,
                        node.id(), null, node.stageBounds(),
                        "visible actor does not intersect the viewport"));
            }
        }
    }

    private static void checkClippedText(
            SemanticSnapshot snapshot,
            List<SemanticNode> nodes,
            LayoutValidationEvidence evidence,
            Sink findings) {
        Bounds viewport = evidence.stageViewportBounds();
        for (SemanticNode node : nodes) {
            TextLayoutEvidence text = evidence.textByNodeId().get(node.id());
            if (!node.state().visible() || !textBearing(node) || text == null) {
                continue;
            }
            EdgeOverflow overflow = new EdgeOverflow();
            overflow.include(node.stageBounds(), text.layoutStageBounds());
            overflow.include(node.stageBounds(), text.inkStageBounds());
            overflow.include(viewport, text.layoutStageBounds());
            overflow.include(viewport, text.inkStageBounds());
            for (Bounds clip : text.clipChainStageBounds()) {
                overflow.include(clip, text.layoutStageBounds());
                overflow.include(clip, text.inkStageBounds());
            }
            if (overflow.exceeds(TEXT_EDGE_EPSILON)) {
                findings.add(new LayoutFinding(
                        LayoutValidationReason.CLIPPED_TEXT,
                        LayoutValidationSeverity.ERROR,
                        node.id(), null, node.stageBounds(),
                        "text ink exceeds actor/clip/viewport bounds: "
                                + "left=" + overflow.left(TEXT_EDGE_EPSILON)
                                + ", right=" + overflow.right(TEXT_EDGE_EPSILON)
                                + ", bottom=" + overflow.bottom(TEXT_EDGE_EPSILON)
                                + ", top=" + overflow.top(TEXT_EDGE_EPSILON)));
            }
        }
    }

    private static void checkTextCollision(
            SemanticSnapshot snapshot,
            List<SemanticNode> nodes,
            LayoutValidationEvidence evidence,
            Sink findings) {
        for (int index = 0; index < nodes.size(); index++) {
            SemanticNode earlier = nodes.get(index);
            TextLayoutEvidence earlierText = evidence.textByNodeId().get(earlier.id());
            if (!earlier.state().visible() || !textBearing(earlier) || earlierText == null) {
                continue;
            }
            for (int other = index + 1; other < nodes.size(); other++) {
                SemanticNode later = nodes.get(other);
                TextLayoutEvidence laterText = evidence.textByNodeId().get(later.id());
                if (!later.state().visible() || !textBearing(later) || laterText == null
                        || ancestorOf(snapshot, earlier, later)
                        || ancestorOf(snapshot, later, earlier)) {
                    continue;
                }
                if (overlaps(earlierText.inkStageBounds(), laterText.inkStageBounds())) {
                    findings.add(new LayoutFinding(
                            LayoutValidationReason.TEXT_COLLISION,
                            LayoutValidationSeverity.ERROR,
                            earlier.id(), later.id(), earlier.stageBounds(),
                            "visible text ink overlaps related actor"));
                }
            }
        }
    }

    private static void checkZeroSize(
            List<SemanticNode> nodes, Sink findings,
            LayoutValidationConfig config) {
        for (SemanticNode node : nodes) {
            if (node.stageBounds().width() == 0.0 || node.stageBounds().height() == 0.0) {
                findings.add(new LayoutFinding(
                        LayoutValidationReason.ZERO_SIZE,
                        LayoutValidationSeverity.ERROR,
                        node.id(), null, node.stageBounds(),
                        "actor has zero width or height"));
            }
        }
    }

    private static void checkBelowTargetSize(
            List<SemanticNode> nodes, Sink findings,
            LayoutValidationConfig config) {
        for (SemanticNode node : nodes) {
            if (!TARGET_SIZE_ROLES.contains(node.role())) {
                continue;
            }
            Bounds bounds = node.stageBounds();
            if (bounds.width() < config.minTargetWidth()
                    || bounds.height() < config.minTargetHeight()) {
                findings.add(new LayoutFinding(
                        LayoutValidationReason.BELOW_TARGET_SIZE,
                        LayoutValidationSeverity.WARNING,
                        node.id(), null, bounds,
                        "actor below target size " + config.minTargetWidth() + "x"
                                + config.minTargetHeight()));
            }
        }
    }

    private static void checkDuplicateTestIds(
            List<SemanticNode> nodes, Sink findings,
            LayoutValidationConfig config) {
        Map<String, List<SemanticNode>> byTestId = new HashMap<>();
        for (SemanticNode node : nodes) {
            if (node.testId() != null) {
                byTestId.computeIfAbsent(node.testId(), ignored -> new ArrayList<>()).add(node);
            }
        }
        for (Map.Entry<String, List<SemanticNode>> entry : byTestId.entrySet()) {
            if (entry.getValue().size() > 1) {
                for (SemanticNode node : entry.getValue()) {
                    findings.add(new LayoutFinding(
                            LayoutValidationReason.DUPLICATE_TEST_ID,
                            LayoutValidationSeverity.ERROR,
                            node.id(), null, node.stageBounds(),
                            "test identifier " + entry.getKey() + " is not unique"));
                }
            }
        }
    }

    private static void checkMissingAccessibleName(
            List<SemanticNode> nodes, Sink findings,
            LayoutValidationConfig config) {
        for (SemanticNode node : nodes) {
            boolean interactive = node.state().touchable()
                    || node.state().focusable()
                    || INTERACTIVE_ROLES.contains(node.role());
            if (interactive && (node.accessibleName() == null
                    || node.accessibleName().isBlank())) {
                findings.add(new LayoutFinding(
                        LayoutValidationReason.MISSING_ACCESSIBLE_NAME,
                        LayoutValidationSeverity.WARNING,
                        node.id(), null, node.stageBounds(),
                        "interactive actor has no accessible name"));
            }
        }
    }

    private static void checkObscured(
            SemanticSnapshot snapshot, List<SemanticNode> nodes, Sink findings,
            LayoutValidationConfig config) {
        for (int index = 0; index < nodes.size(); index++) {
            SemanticNode lower = nodes.get(index);
            if (!lower.state().visible()) {
                continue;
            }
            for (int other = 0; other < nodes.size(); other++) {
                if (other == index) {
                    continue;
                }
                SemanticNode higher = nodes.get(other);
                if (!higher.state().visible() || higher.zIndex() <= lower.zIndex()
                        || ancestorOf(snapshot, lower, higher)
                        || ancestorOf(snapshot, higher, lower)) {
                    continue;
                }
                if (overlaps(lower.stageBounds(), higher.stageBounds())) {
                    findings.add(new LayoutFinding(
                            LayoutValidationReason.OBSCURED,
                            LayoutValidationSeverity.WARNING,
                            lower.id(), higher.id(), lower.stageBounds(),
                            "actor is overlapped by a higher-z actor"));
                    break;
                }
            }
        }
    }

    private static void checkInteractiveOverlap(
            List<SemanticNode> nodes, Sink findings,
            LayoutValidationConfig config) {
        for (int index = 0; index < nodes.size(); index++) {
            SemanticNode first = nodes.get(index);
            if (!interactive(first)) {
                continue;
            }
            for (int other = index + 1; other < nodes.size(); other++) {
                SemanticNode second = nodes.get(other);
                if (!interactive(second)) {
                    continue;
                }
                if (overlaps(first.stageBounds(), second.stageBounds())) {
                    findings.add(new LayoutFinding(
                            LayoutValidationReason.INTERACTIVE_OVERLAP,
                            LayoutValidationSeverity.ERROR,
                            first.id(), second.id(), first.stageBounds(),
                            "interactive actors overlap"));
                    findings.add(new LayoutFinding(
                            LayoutValidationReason.INTERACTIVE_OVERLAP,
                            LayoutValidationSeverity.ERROR,
                            second.id(), first.id(), second.stageBounds(),
                            "interactive actors overlap"));
                }
            }
        }
    }

    private static void checkKeyboardUnreachable(
            List<SemanticNode> nodes, Sink findings,
            LayoutValidationConfig config, NavigationResult navigation) {
        Set<String> reachable = new HashSet<>();
        if (navigation.path().defaultFocusIdentity() != null) {
            reachable.add(navigation.path().defaultFocusIdentity());
        }
        for (var step : navigation.path().steps()) {
            reachable.add(step.afterIdentity());
        }
        for (SemanticNode node : nodes) {
            if (node.state().focusable() && node.state().visible()
                    && !reachable.contains(identity(node))) {
                findings.add(new LayoutFinding(
                        LayoutValidationReason.KEYBOARD_UNREACHABLE,
                        LayoutValidationSeverity.WARNING,
                        node.id(), null, node.stageBounds(),
                        "focusable actor is unreachable by keyboard navigation"));
            }
        }
    }

    private static boolean checkConsistentAlignment(
            LinkedHashMap<String, LinkedHashMap<GroupKey, List<SemanticNode>>> explicitGroups,
            Sink findings,
            LayoutValidationConfig config) {
        boolean available = false;
        for (LinkedHashMap<GroupKey, List<SemanticNode>> siblingGroups
                : explicitGroups.values()) {
            for (Map.Entry<GroupKey, List<SemanticNode>> group : siblingGroups.entrySet()) {
                List<SemanticNode> siblings = group.getValue();
                if (siblings.size() < 2) {
                    continue;
                }
                available = true;
                double reference = perpendicularCenter(
                        siblings.getFirst().stageBounds(), group.getKey().axis());
                for (SemanticNode sibling : siblings) {
                    double center = perpendicularCenter(
                            sibling.stageBounds(), group.getKey().axis());
                    if (Math.abs(center - reference) > config.maxAlignmentDelta()) {
                        findings.add(new LayoutFinding(
                                LayoutValidationReason.INCONSISTENT_ALIGNMENT,
                                LayoutValidationSeverity.WARNING,
                                sibling.id(), siblings.getFirst().id(), sibling.stageBounds(),
                                "layout-group " + group.getKey().id()
                                        + " perpendicular center deviates from alignment"));
                    }
                }
            }
        }
        return available;
    }

    private static boolean checkConsistentSpacing(
            LinkedHashMap<String, LinkedHashMap<GroupKey, List<SemanticNode>>> explicitGroups,
            Sink findings,
            LayoutValidationConfig config) {
        boolean available = false;
        for (LinkedHashMap<GroupKey, List<SemanticNode>> siblingGroups
                : explicitGroups.values()) {
            for (Map.Entry<GroupKey, List<SemanticNode>> group : siblingGroups.entrySet()) {
                List<SemanticNode> siblings = new ArrayList<>(group.getValue());
                if (siblings.size() < 3) {
                    continue;
                }
                available = true;
                Axis axis = group.getKey().axis();
                siblings.sort(Comparator.comparingDouble(node -> axialStart(
                        node.stageBounds(), axis)));
                Double referenceGap = null;
                for (int index = 1; index < siblings.size(); index++) {
                    SemanticNode previous = siblings.get(index - 1);
                    SemanticNode current = siblings.get(index);
                    double gap = axialStart(current.stageBounds(), axis)
                            - axialEnd(previous.stageBounds(), axis);
                    if (referenceGap == null) {
                        referenceGap = gap;
                    } else if (Math.abs(gap - referenceGap) > config.minSpacing()) {
                        findings.add(new LayoutFinding(
                                LayoutValidationReason.INCONSISTENT_SPACING,
                                LayoutValidationSeverity.WARNING,
                                current.id(), previous.id(), current.stageBounds(),
                                "layout-group " + group.getKey().id()
                                        + " axial gap deviates from spacing"));
                    }
                }
            }
        }
        return available;
    }

    private static LinkedHashMap<String, LinkedHashMap<GroupKey, List<SemanticNode>>>
            explicitSiblingGroups(List<SemanticNode> nodes) {
        LinkedHashMap<String, LinkedHashMap<GroupKey, List<SemanticNode>>> byParent =
                new LinkedHashMap<>();
        for (SemanticNode node : nodes) {
            if (!node.state().visible()) {
                continue;
            }
            String id = node.properties().get("layout-group");
            Axis axis = Axis.from(node.properties().get("layout-axis"));
            if (id == null || id.isBlank() || axis == null) {
                continue;
            }
            LinkedHashMap<GroupKey, List<SemanticNode>> siblingGroups =
                    byParent.computeIfAbsent(node.parentId(), ignored -> new LinkedHashMap<>());
            siblingGroups.computeIfAbsent(
                    new GroupKey(id, axis), ignored -> new ArrayList<>()).add(node);
        }
        return byParent;
    }

    private static double perpendicularCenter(Bounds bounds, Axis axis) {
        return axis == Axis.HORIZONTAL
                ? bounds.y() + bounds.height() / 2.0
                : bounds.x() + bounds.width() / 2.0;
    }

    private static double axialStart(Bounds bounds, Axis axis) {
        return axis == Axis.HORIZONTAL ? bounds.x() : bounds.y();
    }

    private static double axialEnd(Bounds bounds, Axis axis) {
        return axialStart(bounds, axis)
                + (axis == Axis.HORIZONTAL ? bounds.width() : bounds.height());
    }

    private record GroupKey(String id, Axis axis) {}

    private enum Axis {
        HORIZONTAL,
        VERTICAL;

        private static Axis from(String value) {
            return switch (value) {
                case "horizontal" -> HORIZONTAL;
                case "vertical" -> VERTICAL;
                case null, default -> null;
            };
        }
    }

    private static boolean textBearing(SemanticNode node) {
        return node.text() != null && !node.text().isEmpty()
                || TEXT_BEARING_ROLES.contains(node.role());
    }

    private static boolean interactive(SemanticNode node) {
        return node.state().touchable() || INTERACTIVE_ROLES.contains(node.role());
    }

    private static boolean overlaps(Bounds first, Bounds second) {
        return first.width() > 0
                && first.height() > 0
                && second.width() > 0
                && second.height() > 0
                && first.x() < second.x() + second.width()
                && second.x() < first.x() + first.width()
                && first.y() < second.y() + second.height()
                && second.y() < first.y() + first.height();
    }
    private static boolean contains(Bounds outer, Bounds inner, double epsilon) {
        return inner.x() >= outer.x() - epsilon
                && inner.y() >= outer.y() - epsilon
                && inner.x() + inner.width() <= outer.x() + outer.width() + epsilon
                && inner.y() + inner.height() <= outer.y() + outer.height() + epsilon;
    }

    private static boolean ancestorOf(
            SemanticSnapshot snapshot, SemanticNode candidate, SemanticNode node) {
        String parentId = node.parentId();
        while (parentId != null) {
            if (candidate.id().equals(parentId)) {
                return true;
            }
            SemanticNode parent = snapshot.nodes().get(parentId);
            parentId = parent == null ? null : parent.parentId();
        }
        return false;
    }


    private static String identity(SemanticNode node) {
        if (node.testId() != null) {
            return "test-id:" + node.testId();
        }
        String name = node.accessibleName();
        if (name == null) {
            name = node.actorName();
        }
        if (name == null) {
            name = node.text();
        }
        return "role:" + node.role().name().toLowerCase(java.util.Locale.ROOT)
                + "/name:" + (name == null ? "unnamed" : name);
    }

    private static Bounds boundsOf(SemanticNode node) {
        return node == null ? new Bounds(0, 0, 0, 0) : node.stageBounds();
    }

    private static final class EdgeOverflow {
        private double left;
        private double right;
        private double bottom;
        private double top;

        void include(Bounds outer, Bounds inner) {
            if (contains(outer, inner, TEXT_EDGE_EPSILON)) {
                return;
            }
            left = Math.max(left, outer.x() - inner.x());
            right = Math.max(
                    right,
                    inner.x() + inner.width() - outer.x() - outer.width());
            bottom = Math.max(bottom, outer.y() - inner.y());
            top = Math.max(
                    top,
                    inner.y() + inner.height() - outer.y() - outer.height());
        }

        boolean exceeds(double epsilon) {
            return left > epsilon || right > epsilon || bottom > epsilon || top > epsilon;
        }

        double left(double epsilon) {
            return left > epsilon ? left : 0.0;
        }

        double right(double epsilon) {
            return right > epsilon ? right : 0.0;
        }

        double bottom(double epsilon) {
            return bottom > epsilon ? bottom : 0.0;
        }

        double top(double epsilon) {
            return top > epsilon ? top : 0.0;
        }
    }

    /** Bounded finding collector that records overflow without unbounded growth. */
    private static final class Sink {
        private final List<LayoutFinding> findings;
        private final int maximum;
        private boolean overflow;
        private LayoutValidationSeverity highestSeverity;

        Sink(int maximum) {
            findings = new ArrayList<>(Math.min(maximum, 64));
            this.maximum = maximum;
        }

        void add(LayoutFinding finding) {
            if (highestSeverity == null
                    || finding.severity().ordinal() > highestSeverity.ordinal()) {
                highestSeverity = finding.severity();
            }
            if (findings.size() < maximum) {
                findings.add(finding);
            } else {
                overflow = true;
            }
        }

        List<LayoutFinding> list() {
            return List.copyOf(findings);
        }

        boolean reaches(LayoutValidationSeverity threshold) {
            return highestSeverity != null
                    && highestSeverity.ordinal() >= threshold.ordinal();
        }

        boolean overflow() {
            return overflow;
        }
    }

    private static List<SemanticNode> documentOrder(SemanticSnapshot snapshot) {
        var ordered = new ArrayList<SemanticNode>(snapshot.nodes().size());
        var pending = new java.util.ArrayDeque<String>();
        pending.push(snapshot.rootId());
        while (!pending.isEmpty()) {
            SemanticNode node = snapshot.nodes().get(pending.pop());
            ordered.add(node);
            List<String> children = node.childIds();
            for (int index = children.size() - 1; index >= 0; index--) {
                pending.push(children.get(index));
            }
        }
        return ordered;
    }
}
