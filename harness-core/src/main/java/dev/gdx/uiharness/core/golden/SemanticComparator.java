package dev.gdx.uiharness.core.golden;

import dev.gdx.uiharness.core.model.SemanticNode;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Pure hierarchy-aware semantic comparator. Matching uses stable keys (unique test ID, then
 * role plus accessible name and parent context), never snapshot-local node IDs or Actor
 * identity. Ambiguity is reported, never resolved by heuristic pairing.
 */
public final class SemanticComparator {
    private static final Set<String> CONSTRAINED_FIELDS = Set.of(
            "role", "accessibleName", "text", "label", "testId", "actorName", "actorType",
            "visible", "enabled", "checked", "selected", "expanded", "editable", "focused",
            "focusable", "stageBounds", "placement");

    /** Compares one baseline against one snapshot using the supplied policy. */
    public SemanticCompareResult compare(
            SemanticBaseline baseline,
            SemanticSnapshot current,
            SemanticComparePolicy policy) {
        Objects.requireNonNull(baseline, "baseline");
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(policy, "policy");
        List<SemanticNode> snapshotNodes = documentOrder(current);
        List<BaselineNode> baselineNodes = flatten(baseline.root());
        Map<String, List<SemanticNode>> byKey = groupByKey(snapshotNodes, current);
        var differences = new ArrayList<SemanticDifference>();
        var matchedBaselineKeys = new java.util.HashSet<String>();
        for (BaselineNode expected : baselineNodes) {
            String key = key(expected);
            if (byKey.getOrDefault(key, List.of()).isEmpty()) {
                differences.add(new SemanticDifference(
                        SemanticDifference.Kind.REMOVED,
                        key, List.of(), Map.of(), Map.of(), List.of()));
            } else if (byKey.get(key).size() > 1) {
                List<String> identities = byKey.get(key).stream()
                        .map(node -> node.id()).toList();
                differences.add(new SemanticDifference(
                        SemanticDifference.Kind.AMBIGUOUS,
                        key, List.of(), Map.of(), Map.of(), identities));
            } else {
                matchedBaselineKeys.add(key);
                SemanticNode actual = byKey.get(key).getFirst();
                compareProperties(expected, actual, key, policy, differences);
            }
            if (differences.size() >= policy.maxDifferences()) {
                return result(differences, snapshotNodes.size(), true, policy);
            }
        }
        for (Map.Entry<String, List<SemanticNode>> entry : byKey.entrySet()) {
            if (!matchedBaselineKeys.contains(entry.getKey())) {
                for (SemanticNode node : entry.getValue()) {
                    differences.add(new SemanticDifference(
                            SemanticDifference.Kind.ADDED,
                            entry.getKey(), List.of(), Map.of(), Map.of(), List.of()));
                    if (differences.size() >= policy.maxDifferences()) {
                        return result(differences, snapshotNodes.size(), true, policy);
                    }
                }
            }
        }
        differences.sort(Comparator
                .comparing((SemanticDifference difference) -> difference.kind().name())
                .thenComparing(SemanticDifference::baselineKey));
        return result(differences, snapshotNodes.size(), false, policy);
    }

    private static SemanticCompareResult result(
            List<SemanticDifference> differences,
            int comparedNodes,
            boolean truncated,
            SemanticComparePolicy policy) {
        return new SemanticCompareResult(
                differences.isEmpty(),
                List.copyOf(differences),
                comparedNodes,
                truncated,
                policy.excludedProperties());
    }

    private static void compareProperties(
            BaselineNode expected,
            SemanticNode actual,
            String key,
            SemanticComparePolicy policy,
            List<SemanticDifference> differences) {
        var paths = new ArrayList<String>();
        var before = new LinkedHashMap<String, String>();
        var after = new LinkedHashMap<String, String>();
        boolean changed = false;
        if (expected.role() != null && expected.role() != actual.role()) {
            changed = true;
            paths.add("role");
            before.put("role", expected.role().name());
            after.put("role", actual.role().name());
        }
        changed |= compareText("accessibleName", expected.accessibleName(),
                actual.accessibleName(), paths, before, after);
        changed |= compareText("text", expected.text(), actual.text(), paths, before, after);
        changed |= compareText("label", expected.label(), actual.label(), paths, before, after);
        changed |= compareText("testId", expected.testId(), actual.testId(), paths, before, after);
        changed |= compareBoolean("visible", expected.visible(), actual.state().visible(),
                paths, before, after);
        if (expected.enabled() != null && actual.state().enabled().isPresent()) {
            changed |= compareBoolean("enabled", expected.enabled(),
                    actual.state().enabled().get(), paths, before, after);
        }
        changed |= compareBoolean("checked", expected.checked(),
                actual.state().checked().orElse(false), paths, before, after);
        changed |= compareBoolean("selected", expected.selected(),
                actual.state().selected().orElse(false), paths, before, after);
        changed |= compareBoolean("expanded", expected.expanded(),
                actual.state().expanded().orElse(false), paths, before, after);
        changed |= compareBoolean("editable", expected.editable(),
                actual.state().editable().orElse(false), paths, before, after);
        changed |= compareBoolean("focused", expected.focused(), actual.state().focused(),
                paths, before, after);
        changed |= compareBoolean("focusable", expected.focusable(),
                actual.state().focusable(), paths, before, after);
        if (expected.stageBounds() != null
                && !withinTolerance(expected.stageBounds(), actual.stageBounds(), policy)) {
            changed = true;
            paths.add("stageBounds");
            before.put("stageBounds", expected.stageBounds().toString());
            after.put("stageBounds", actual.stageBounds().toString());
        }
        if (changed) {
            differences.add(new SemanticDifference(
                    SemanticDifference.Kind.CHANGED,
                    key,
                    List.copyOf(paths),
                    Map.copyOf(before),
                    Map.copyOf(after),
                    List.of()));
        }
    }

    private static boolean withinTolerance(
            dev.gdx.uiharness.core.model.Bounds expected,
            dev.gdx.uiharness.core.model.Bounds actual,
            SemanticComparePolicy policy) {
        if (policy.tolerances().isEmpty()) {
            return expected.equals(actual);
        }
        for (PositionalTolerance tolerance : policy.tolerances()) {
            if (Math.abs(expected.x() - actual.x()) <= tolerance.deltaX()
                    && Math.abs(expected.y() - actual.y()) <= tolerance.deltaY()
                    && Math.abs(expected.width() - actual.width()) <= tolerance.deltaWidth()
                    && Math.abs(expected.height() - actual.height()) <= tolerance.deltaHeight()) {
                return true;
            }
        }
        return false;
    }

    private static boolean compareText(
            String field,
            String expected,
            String actual,
            List<String> paths,
            Map<String, String> before,
            Map<String, String> after) {
        if (expected == null) {
            return false;
        }
        if (!Objects.equals(expected, actual)) {
            paths.add(field);
            before.put(field, expected);
            after.put(field, actual == null ? "" : actual);
            return true;
        }
        return false;
    }

    private static boolean compareBoolean(
            String field,
            Boolean expected,
            boolean actual,
            List<String> paths,
            Map<String, String> before,
            Map<String, String> after) {
        if (expected == null) {
            return false;
        }
        if (expected != actual) {
            paths.add(field);
            before.put(field, Boolean.toString(expected));
            after.put(field, Boolean.toString(actual));
            return true;
        }
        return false;
    }

    private static Map<String, List<SemanticNode>> groupByKey(
            List<SemanticNode> nodes, SemanticSnapshot snapshot) {
        Map<String, List<SemanticNode>> byKey = new HashMap<>();
        Map<String, String> parentKeys = new HashMap<>();
        for (SemanticNode node : nodes) {
            String parentKey = node.parentId() == null
                    ? "" : key(node.parentId(), snapshot, parentKeys);
            String nodeKey = key(node, parentKey);
            parentKeys.put(node.id(), nodeKey);
            byKey.computeIfAbsent(nodeKey, ignored -> new ArrayList<>()).add(node);
        }
        return byKey;
    }

    private static String key(String nodeId, SemanticSnapshot snapshot,
            Map<String, String> parentKeys) {
        return parentKeys.getOrDefault(nodeId, "");
    }

    private static String key(SemanticNode node, String parentKey) {
        if (node.testId() != null) {
            return "test-id:" + node.testId();
        }
        return "role:" + node.role().name() + "/name:" + node.accessibleName()
                + "@" + parentKey;
    }

    private static String key(BaselineNode node) {
        if (node.testId() != null) {
            return "test-id:" + node.testId();
        }
        return "role:" + (node.role() == null ? "" : node.role().name())
                + "/name:" + (node.accessibleName() == null ? "" : node.accessibleName())
                + "@";
    }

    private static List<BaselineNode> flatten(BaselineNode root) {
        var ordered = new ArrayList<BaselineNode>();
        var pending = new java.util.ArrayDeque<BaselineNode>();
        pending.push(root);
        while (!pending.isEmpty()) {
            BaselineNode node = pending.pop();
            ordered.add(node);
            List<BaselineNode> children = node.children();
            for (int index = children.size() - 1; index >= 0; index--) {
                pending.push(children.get(index));
            }
        }
        return ordered;
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
