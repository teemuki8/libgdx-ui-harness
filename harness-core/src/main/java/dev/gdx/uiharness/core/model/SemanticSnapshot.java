package dev.gdx.uiharness.core.model;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable semantic graph captured after a completed frame.
 *
 * @param revision monotonically increasing semantic revision
 * @param frame non-negative frame number
 * @param rootId identifier of the graph's sole root
 * @param nodes nodes keyed by their snapshot-local identifiers
 */
public record SemanticSnapshot(
        long revision, long frame, String rootId, Map<String, SemanticNode> nodes) {
    private static final int MAX_ID_LENGTH = 16_384;

    /** Defensively copies and validates the complete semantic graph. */
    public SemanticSnapshot {
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be non-negative");
        }
        if (frame < 0) {
            throw new IllegalArgumentException("frame must be non-negative");
        }
        validateId(rootId, "rootId");
        nodes = Map.copyOf(Objects.requireNonNull(nodes, "nodes"));

        SemanticNode root = nodes.get(rootId);
        if (root == null) {
            throw new IllegalArgumentException("rootId does not reference a node: " + rootId);
        }

        int roots = 0;
        for (Map.Entry<String, SemanticNode> entry : nodes.entrySet()) {
            String mapId = entry.getKey();
            SemanticNode node = entry.getValue();
            validateId(mapId, "node map key");
            if (!mapId.equals(node.id())) {
                throw new IllegalArgumentException(
                        "node map key " + mapId + " does not match node id " + node.id());
            }
            if (node.parentId() == null) {
                roots++;
                if (!node.id().equals(rootId)) {
                    throw new IllegalArgumentException(
                            "node without a parent is not the declared root: " + node.id());
                }
            }
        }
        if (roots != 1) {
            throw new IllegalArgumentException("semantic graph must contain exactly one root");
        }

        validateReferences(nodes);
        validateConnected(nodes, root);
    }

    private static void validateReferences(Map<String, SemanticNode> nodes) {
        for (SemanticNode node : nodes.values()) {
            if (node.parentId() != null) {
                SemanticNode parent = nodes.get(node.parentId());
                if (parent == null) {
                    throw new IllegalArgumentException(
                            "missing parent " + node.parentId() + " for node " + node.id());
                }
                if (!parent.childIds().contains(node.id())) {
                    throw new IllegalArgumentException(
                            "parent " + parent.id() + " does not reference child " + node.id());
                }
            }
            for (String childId : node.childIds()) {
                SemanticNode child = nodes.get(childId);
                if (child == null) {
                    throw new IllegalArgumentException(
                            "missing child " + childId + " for node " + node.id());
                }
                if (!node.id().equals(child.parentId())) {
                    throw new IllegalArgumentException(
                            "child " + childId + " does not reference parent " + node.id());
                }
            }
        }
    }

    private static void validateConnected(
            Map<String, SemanticNode> nodes, SemanticNode root) {
        var pending = new ArrayDeque<SemanticNode>();
        var visited = new HashSet<String>(nodes.size());
        pending.add(root);
        while (!pending.isEmpty()) {
            SemanticNode node = pending.removeFirst();
            if (!visited.add(node.id())) {
                throw new IllegalArgumentException("semantic graph contains a cycle at " + node.id());
            }
            for (String childId : node.childIds()) {
                pending.addLast(nodes.get(childId));
            }
        }
        if (visited.size() != nodes.size()) {
            throw new IllegalArgumentException("semantic graph contains nodes unreachable from the root");
        }
    }

    private static void validateId(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (value.length() > MAX_ID_LENGTH) {
            throw new IllegalArgumentException(name + " exceeds " + MAX_ID_LENGTH + " characters");
        }
    }
}
