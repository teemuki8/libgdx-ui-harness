package dev.gdx.uiharness.core.trace;

import dev.gdx.uiharness.core.locator.LocatorEngine;
import dev.gdx.uiharness.core.model.Bounds;
import dev.gdx.uiharness.core.model.SemanticNode;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pure projector of compact state transitions from retained bounded semantic observations.
 * Causality is attributed only when an observation carries a proven cause sequence; gaps and
 * unknown causes are reported explicitly, never invented.
 */
public final class TransitionProjector {
    /**
     * Projects transitions between adjacent retained observations.
     *
     * @param observations bounded observations in ascending sequence order
     * @param query bounded transition query
     * @param locators locator engine used to resolve the query locator against each observation
     * @return deterministic bounded projection result
     */
    public TransitionQueryResult query(
            List<SemanticObservation> observations,
            TransitionQuery query,
            LocatorEngine locators) {
        Objects.requireNonNull(observations, "observations");
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(locators, "locators");
        List<SemanticObservation> bounded = observations.stream()
                .filter(observation -> withinFrameRange(observation, query))
                .toList();
        var transitions = new ArrayList<StateTransition>();
        int gaps = 0;
        SemanticObservation previous = null;
        for (SemanticObservation current : bounded) {
            if (previous != null && current.frame() > previous.frame() + 1) {
                gaps++;
            }
            if (previous != null) {
                projectBetween(previous, current, query, locators, transitions);
            }
            previous = current;
        }
        boolean truncated = false;
        if (transitions.size() > query.maxTransitions()) {
            transitions = new ArrayList<>(transitions.subList(0, query.maxTransitions()));
            truncated = true;
        }
        transitions.sort(Comparator
                .comparingLong(StateTransition::afterSequence)
                .thenComparing(StateTransition::kind, Comparator.comparing(Enum::name))
                .thenComparing(StateTransition::actorIdentity));
        long unknownCauses = transitions.stream()
                .filter(transition -> transition.causeSequence() == null)
                .count();
        return new TransitionQueryResult(
                query.traceId(),
                List.copyOf(transitions),
                truncated,
                gaps,
                (int) unknownCauses);
    }

    private static boolean withinFrameRange(SemanticObservation observation,
            TransitionQuery query) {
        if (query.frameFrom() != null && observation.frame() < query.frameFrom()) {
            return false;
        }
        return query.frameTo() == null || observation.frame() <= query.frameTo();
    }

    private void projectBetween(
            SemanticObservation before,
            SemanticObservation after,
            TransitionQuery query,
            LocatorEngine locators,
            List<StateTransition> transitions) {
        Map<String, SemanticNode> beforeByKey = keyed(before.snapshot());
        Map<String, SemanticNode> afterByKey = keyed(after.snapshot());
        Set<String> selected = selectedIdentities(
                before.snapshot(), after.snapshot(), query, locators);
        for (Map.Entry<String, SemanticNode> entry : afterByKey.entrySet()) {
            if (!selected.contains(entry.getKey())) {
                continue;
            }
            SemanticNode afterNode = entry.getValue();
            SemanticNode beforeNode = beforeByKey.get(entry.getKey());
            if (beforeNode == null) {
                transitions.add(transition(TransitionKind.APPEARED, before, after,
                        entry.getKey(), List.of(), Map.of(), Map.of()));
                continue;
            }
            if (beforeNode.state().visible() != afterNode.state().visible()) {
                transitions.add(transition(TransitionKind.DISAPPEARED, before, after,
                        entry.getKey(), List.of("visible"),
                        Map.of("visible", Boolean.toString(beforeNode.state().visible())),
                        Map.of("visible", Boolean.toString(afterNode.state().visible()))));
            }
            if (beforeNode.state().visible() && afterNode.state().visible()) {
                enabledTransition(beforeNode, afterNode, before, after, entry.getKey(),
                        transitions);
                textTransition(beforeNode, afterNode, before, after, entry.getKey(), transitions);
                boundsTransition(beforeNode, afterNode, before, after, entry.getKey(),
                        transitions);
                focusTransition(beforeNode, afterNode, before, after, entry.getKey(), transitions);
                zOrderTransition(beforeNode, afterNode, before, after, entry.getKey(),
                        transitions);
            }
        }
        for (String key : beforeByKey.keySet()) {
            if (selected.contains(key) && !afterByKey.containsKey(key)) {
                transitions.add(transition(TransitionKind.DISAPPEARED, before, after,
                        key, List.of(), Map.of(), Map.of()));
            }
        }
    }

    private static void enabledTransition(
            SemanticNode before, SemanticNode after,
            SemanticObservation beforeObs, SemanticObservation afterObs,
            String key, List<StateTransition> transitions) {
        if (before.state().enabled().isPresent() && after.state().enabled().isPresent()
                && before.state().enabled().get() != after.state().enabled().get()) {
            boolean nowEnabled = after.state().enabled().get();
            transitions.add(transition(nowEnabled ? TransitionKind.ENABLED
                    : TransitionKind.DISABLED, beforeObs, afterObs, key, List.of("enabled"),
                    Map.of("enabled", Boolean.toString(before.state().enabled().get())),
                    Map.of("enabled", Boolean.toString(nowEnabled))));
        }
    }

    private static void textTransition(
            SemanticNode before, SemanticNode after,
            SemanticObservation beforeObs, SemanticObservation afterObs,
            String key, List<StateTransition> transitions) {
        if (!Objects.equals(before.text(), after.text())) {
            transitions.add(transition(TransitionKind.TEXT_CHANGED, beforeObs, afterObs, key,
                    List.of("text"),
                    Map.of("text", before.text() == null ? "" : before.text()),
                    Map.of("text", after.text() == null ? "" : after.text())));
        }
    }

    private static void boundsTransition(
            SemanticNode before, SemanticNode after,
            SemanticObservation beforeObs, SemanticObservation afterObs,
            String key, List<StateTransition> transitions) {
        if (!Objects.equals(before.stageBounds(), after.stageBounds())) {
            transitions.add(transition(TransitionKind.BOUNDS_CHANGED, beforeObs, afterObs, key,
                    List.of("stageBounds"),
                    Map.of("stageBounds", bounds(before.stageBounds())),
                    Map.of("stageBounds", bounds(after.stageBounds()))));
        }
    }

    private static void focusTransition(
            SemanticNode before, SemanticNode after,
            SemanticObservation beforeObs, SemanticObservation afterObs,
            String key, List<StateTransition> transitions) {
        if (before.state().focused() != after.state().focused()) {
            transitions.add(transition(TransitionKind.FOCUS_CHANGED, beforeObs, afterObs, key,
                    List.of("focused"),
                    Map.of("focused", Boolean.toString(before.state().focused())),
                    Map.of("focused", Boolean.toString(after.state().focused()))));
        }
    }

    private static void zOrderTransition(
            SemanticNode before, SemanticNode after,
            SemanticObservation beforeObs, SemanticObservation afterObs,
            String key, List<StateTransition> transitions) {
        if (before.zIndex() != after.zIndex()) {
            transitions.add(transition(TransitionKind.Z_ORDER_CHANGED, beforeObs, afterObs, key,
                    List.of("zIndex"),
                    Map.of("zIndex", Integer.toString(before.zIndex())),
                    Map.of("zIndex", Integer.toString(after.zIndex()))));
        }
    }

    private static StateTransition transition(
            TransitionKind kind,
            SemanticObservation before,
            SemanticObservation after,
            String key,
            List<String> paths,
            Map<String, String> beforeValues,
            Map<String, String> afterValues) {
        return new StateTransition(
                kind,
                before.sequence(),
                after.sequence(),
                before.frame(),
                after.frame(),
                before.revision(),
                after.revision(),
                key,
                paths,
                beforeValues,
                afterValues,
                after.causeSequence());
    }

    private static Set<String> selectedIdentities(
            SemanticSnapshot before,
            SemanticSnapshot after,
            TransitionQuery query,
            LocatorEngine locators) {
        var selected = new java.util.HashSet<String>();
        if (query.locator() == null) {
            selected.addAll(keyed(before).keySet());
            selected.addAll(keyed(after).keySet());
            return selected;
        }
        for (var match : locators.query(after, query.locator()).matches()) {
            selected.add(key(match));
        }
        return selected;
    }

    private static Map<String, SemanticNode> keyed(SemanticSnapshot snapshot) {
        Map<String, SemanticNode> byKey = new HashMap<>();
        Map<String, String> parentKeys = new HashMap<>();
        for (SemanticNode node : snapshot.nodes().values()) {
            String parentKey = node.parentId() == null
                    ? "" : parentKeys.getOrDefault(node.parentId(), "");
            String nodeKey = key(node, parentKey);
            parentKeys.put(node.id(), nodeKey);
            byKey.put(nodeKey, node);
        }
        return byKey;
    }

    private static String key(SemanticNode node) {
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

    private static String key(SemanticNode node, String parentKey) {
        if (node.testId() != null) {
            return "test-id:" + node.testId();
        }
        return key(node) + "@" + parentKey;
    }

    private static String bounds(Bounds bounds) {
        return bounds.x() + "," + bounds.y() + "," + bounds.width() + "," + bounds.height();
    }
}
