package dev.gdx.uiharness.scene2d;

import dev.gdx.uiharness.core.layout.LayoutValidationConfig;
import dev.gdx.uiharness.core.layout.LayoutValidationEvidence;
import dev.gdx.uiharness.core.layout.LayoutValidationResult;
import dev.gdx.uiharness.core.layout.LayoutValidator;
import dev.gdx.uiharness.core.layout.TextLayoutEvidence;
import dev.gdx.uiharness.core.locator.Locator;
import dev.gdx.uiharness.core.locator.LocatorEngine;
import dev.gdx.uiharness.core.model.SemanticNode;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import dev.gdx.uiharness.core.navigation.NavigationResult;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Validates whole-stage or strictly resolved subtree layout invariants from one atomic
 * completed-frame semantic observation on the owning render thread.
 */
public final class Scene2dLayoutValidator {
    private final Scene2dSession session;
    private final LocatorEngine locators;
    private final LayoutValidator validator = new LayoutValidator();

    /**
     * Creates a validator over one session.
     *
     * @param session session owning the captured semantic observation
     * @param locators strict locator engine used for subtree resolution
     */
    public Scene2dLayoutValidator(Scene2dSession session, LocatorEngine locators) {
        this.session = Objects.requireNonNull(session, "session");
        this.locators = Objects.requireNonNull(locators, "locators");
    }

    /**
     * Validates one completed-frame observation.
     *
     * @param revision semantic revision of the observation
     * @param frame rendered frame of the observation
     * @param subtree strict subtree locator, or {@code null} for the full stage
     * @param config bounded validation configuration
     * @param navigation navigation evidence for keyboard reachability, when available
     * @return deterministic bounded validation result
     */
    public LayoutValidationResult validate(
            long revision,
            long frame,
            Locator subtree,
            LayoutValidationConfig config,
            NavigationResult navigation) {
        Objects.requireNonNull(config, "config");
        SemanticSnapshot snapshot = session.snapshot(revision, frame);
        LayoutValidationEvidence evidence = session.textLayoutEvidence(snapshot);
        if (subtree != null) {
            SemanticNode root = locators.resolveStrict(snapshot, subtree);
            snapshot = subtreeSnapshot(snapshot, root.id());
            evidence = subtreeEvidence(snapshot, evidence);
        }
        return validator.validate(snapshot, config, navigation, evidence);
    }

    private static SemanticSnapshot subtreeSnapshot(SemanticSnapshot source, String rootId) {
        var byId = new LinkedHashMap<String, SemanticNode>();
        var pending = new ArrayDeque<String>();
        pending.push(rootId);
        while (!pending.isEmpty()) {
            SemanticNode node = source.nodes().get(pending.pop());
            SemanticNode stored = node.id().equals(rootId) ? withoutParent(node) : node;
            byId.put(node.id(), stored);
            for (String childId : node.childIds()) {
                pending.push(childId);
            }
        }
        return new SemanticSnapshot(
                source.revision(), source.frame(), rootId, Map.copyOf(byId));
    }

    private static LayoutValidationEvidence subtreeEvidence(
            SemanticSnapshot snapshot, LayoutValidationEvidence source) {
        Map<String, TextLayoutEvidence> retained = new LinkedHashMap<>();
        for (String nodeId : snapshot.nodes().keySet()) {
            TextLayoutEvidence evidence = source.textByNodeId().get(nodeId);
            if (evidence != null) {
                retained.put(nodeId, evidence);
            }
        }
        return source.textGeometryAvailable()
                ? LayoutValidationEvidence.available(Map.copyOf(retained))
                : LayoutValidationEvidence.unavailable();
    }

    private static SemanticNode withoutParent(SemanticNode node) {
        return new SemanticNode(
                node.id(),
                null,
                node.childIds(),
                node.role(),
                node.accessibleName(),
                node.text(),
                node.label(),
                node.testId(),
                node.actorName(),
                node.actorType(),
                node.state(),
                node.localBounds(),
                node.stageBounds(),
                node.screenBounds(),
                node.zIndex(),
                node.properties());
    }
}
