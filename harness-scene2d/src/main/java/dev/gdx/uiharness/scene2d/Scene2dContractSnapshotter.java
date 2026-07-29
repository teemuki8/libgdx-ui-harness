package dev.gdx.uiharness.scene2d;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.utils.SnapshotArray;
import dev.gdx.uiharness.core.contract.ContractValue;
import dev.gdx.uiharness.core.contract.ContractVersion;
import dev.gdx.uiharness.core.contract.ControlState;
import dev.gdx.uiharness.core.contract.StateActionContract;
import dev.gdx.uiharness.core.contract.TransitionOutcome;
import dev.gdx.uiharness.core.contract.ViewportState;
import dev.gdx.uiharness.core.model.SemanticNode;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Builds the typed public contract from one completed Scene2D frame. */
final class Scene2dContractSnapshotter {
    private final Stage stage;
    private final Semantics semantics;
    private final ActorAdapterRegistry adapters;
    private final Scene2dSnapshotter snapshotter;

    Scene2dContractSnapshotter(
            Stage stage,
            Semantics semantics,
            ActorAdapterRegistry adapters,
            Scene2dSnapshotter snapshotter) {
        this.stage = Objects.requireNonNull(stage, "stage");
        this.semantics = Objects.requireNonNull(semantics, "semantics");
        this.adapters = Objects.requireNonNull(adapters, "adapters");
        this.snapshotter = Objects.requireNonNull(snapshotter, "snapshotter");
    }

    StateActionContract snapshot(long revision, long frame) {
        SemanticSnapshot semantic = snapshotter.snapshot(stage, revision, frame);
        List<Actor> actors = new ArrayList<>(semantic.nodes().size());
        collect(stage.getRoot(), actors);
        if (actors.size() != semantic.nodes().size()) {
            throw new IllegalStateException("Scene2D traversal changed during contract snapshot");
        }

        List<ControlActor> controls = new ArrayList<>();
        List<ViewportActor> viewports = new ArrayList<>();
        for (int index = 0; index < actors.size(); index++) {
            Actor actor = actors.get(index);
            ActorMetadata metadata = semantics.metadata(actor);
            SemanticNode node = semantic.nodes().get("n" + index);
            if (metadata.control() != null) {
                SemanticNodeBuilder inferred = new SemanticNodeBuilder();
                adapters.contribute(actor, inferred);
                inferred.apply(metadata);
                ContractValue current = inferred.currentValue;
                if (current == null) {
                    throw new IllegalStateException(
                            "Missing current value for control " + metadata.control().id());
                }
                boolean enabled = node.state().enabled().orElse(true);
                boolean actionable = node.state().visible() && enabled
                        && node.state().viewportIntersecting();
                ControlState state = new ControlState(
                        metadata.control().id(), node.role(), metadata.control().kind(),
                        accessibleName(node), metadata.control().options(),
                        metadata.control().defaultValue(), current,
                        node.state().visible(), enabled, actionable,
                        node.state().focusable(), node.state().focused(),
                        metadata.control().validationRule(),
                        metadata.control().validationStatus());
                controls.add(new ControlActor(metadata.control(), actor, state));
            }
            if (metadata.viewportId() != null) {
                viewports.add(new ViewportActor(metadata.viewportId(), actor, node));
            }
        }
        controls.sort(Comparator.comparingInt(item -> item.metadata().order()));
        List<ControlState> orderedControls =
                controls.stream().map(ControlActor::state).toList();
        List<String> focusOrder = controls.stream()
                .sorted(Comparator.comparingInt(item -> item.metadata().focusOrder()))
                .filter(item -> item.state().focusable() && item.state().visible())
                .map(item -> item.state().id())
                .toList();
        String focused = controls.stream()
                .filter(item -> item.state().focused())
                .map(item -> item.state().id())
                .findFirst().orElse(null);
        List<ViewportState> viewportStates = viewports.stream()
                .map(viewport -> viewportState(viewport, controls))
                .toList();
        String stateId = stateId(
                orderedControls, focusOrder, focused, semantics.conditions(), viewportStates);
        TransitionObservation observation = semantics.transition();
        TransitionOutcome transition = observation == null ? null : new TransitionOutcome(
                observation.actionId(), observation.accepted(), observation.rejectionReason(),
                stateId, revision, observation.validation(), observation.kind(),
                observation.clipboardText(), observation.acceptedPayload());
        return new StateActionContract(
                ContractVersion.V1, stateId, revision, frame, orderedControls, focusOrder,
                focused, semantics.conditions(), viewportStates, transition);
    }

    private static String accessibleName(SemanticNode node) {
        if (node.accessibleName() != null && !node.accessibleName().isBlank()) {
            return node.accessibleName();
        }
        if (node.label() != null && !node.label().isBlank()) {
            return node.label();
        }
        throw new IllegalStateException("Missing accessible name for control " + node.id());
    }

    private static ViewportState viewportState(
            ViewportActor viewport, List<ControlActor> controls) {
        double scrollX = 0;
        double scrollY = 0;
        double maxX = 0;
        double maxY = 0;
        if (viewport.actor() instanceof ScrollPane pane) {
            scrollX = pane.getScrollX();
            scrollY = pane.getScrollY();
            maxX = pane.getMaxX();
            maxY = pane.getMaxY();
        }
        List<String> visible = controls.stream()
                .filter(control -> descendantOf(control.actor(), viewport.actor()))
                .filter(control -> control.state().visible())
                .map(control -> control.state().id())
                .toList();
        return new ViewportState(
                viewport.id(), viewport.node().stageBounds().width(),
                viewport.node().stageBounds().height(), scrollX, scrollY, maxX, maxY, visible);
    }

    private static boolean descendantOf(Actor actor, Actor ancestor) {
        for (Actor current = actor; current != null; current = current.getParent()) {
            if (current == ancestor) {
                return true;
            }
        }
        return false;
    }

    private static void collect(Actor actor, List<Actor> destination) {
        destination.add(actor);
        if (actor instanceof Group group) {
            SnapshotArray<Actor> children = group.getChildren();
            for (int index = 0; index < children.size; index++) {
                collect(children.get(index), destination);
            }
        }
    }

    private static String stateId(
            List<ControlState> controls,
            List<String> focusOrder,
            String focused,
            List<dev.gdx.uiharness.core.contract.ConditionalRule> conditions,
            List<ViewportState> viewports) {
        String canonical = controls + "\n" + focusOrder + "\n" + focused
                + "\n" + conditions + "\n" + viewports;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 unavailable", impossible);
        }
    }

    private record ControlActor(ControlMetadata metadata, Actor actor, ControlState state) {}

    private record ViewportActor(String id, Actor actor, SemanticNode node) {}
}
