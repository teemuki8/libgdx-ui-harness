package dev.gdx.uiharness.scene2d;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane.ScrollPaneStyle;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.utils.viewport.FitViewport;
import dev.gdx.uiharness.core.error.ErrorCode;
import dev.gdx.uiharness.core.error.HarnessException;
import dev.gdx.uiharness.core.limits.HarnessLimits;
import dev.gdx.uiharness.core.model.Bounds;
import dev.gdx.uiharness.core.model.Role;
import dev.gdx.uiharness.core.model.SemanticNode;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

final class Scene2dSnapshotterTest {
    @Test void extractsTransformedNestedActorIntoAllCoordinateSpaces() {
        Stage stage = stage();
        Group parent = new Group();
        parent.setPosition(20, 30);
        parent.setScale(2);
        TextButton save = new TextButton("Save", WidgetStyles.textButton());
        save.setName("saveActor");
        save.setBounds(5, 7, 80, 30);
        parent.addActor(save);
        stage.addActor(parent);

        SemanticNode node = nodeByAccessibleName(snapshot(stage), "Save");

        assertEquals(Role.BUTTON, node.role());
        assertEquals(new Bounds(0, 0, 80, 30), node.localBounds());
        assertEquals(new Bounds(30, 44, 160, 60), node.stageBounds());
        assertEquals(30, node.screenBounds().x(), 0.001);
        assertEquals(496, node.screenBounds().y(), 0.001);
        assertEquals(160, node.screenBounds().width(), 0.001);
        assertEquals(60, node.screenBounds().height(), 0.001);
    }

    @Test void traversesChildrenDeterministicallyAndPreservesSiblingZOrder() {
        Stage stage = stage();
        Actor first = actor("duplicate", 10, 10, 50, 50);
        Actor second = actor("duplicate", 10, 10, 50, 50);
        stage.addActor(first);
        stage.addActor(second);

        SemanticSnapshot firstSnapshot = snapshot(stage);
        SemanticSnapshot secondSnapshot = new Scene2dSnapshotter().snapshot(stage, 2, 2);
        List<SemanticNode> duplicates = firstSnapshot.nodes().values().stream()
                .filter(node -> "duplicate".equals(node.actorName()))
                .sorted(java.util.Comparator.comparingInt(SemanticNode::zIndex))
                .toList();

        assertEquals(List.of("n1", "n2"), firstSnapshot.nodes().get("n0").childIds());
        assertEquals(firstSnapshot.nodes().keySet(), secondSnapshot.nodes().keySet());
        assertEquals(0, duplicates.get(0).zIndex());
        assertEquals(1, duplicates.get(1).zIndex());
        assertFalse(duplicates.get(0).state().hitTarget());
        assertTrue(duplicates.get(1).state().hitTarget());
    }

    @Test void appliesParentVisibilityAlphaAndTouchability() {
        Stage stage = stage();
        Group parent = new Group();
        parent.setVisible(false);
        parent.getColor().a = 0.5f;
        parent.setTouchable(Touchable.disabled);
        TextButton child = new TextButton("Disabled", WidgetStyles.textButton());
        child.setBounds(0, 0, 80, 30);
        child.getColor().a = 0.5f;
        child.setDisabled(true);
        parent.addActor(child);
        stage.addActor(parent);

        SemanticNode node = nodeByAccessibleName(snapshot(stage), "Disabled");

        assertFalse(node.state().visible());
        assertFalse(node.state().touchable());
        assertEquals(false, node.state().enabled().orElseThrow());
        assertEquals(0.25, node.state().effectiveAlpha(), 0.0001);
    }

    @Test void reportsKeyboardAndScrollFocusIndependently() {
        Stage stage = stage();
        TextField field = new TextField("query", WidgetStyles.textField());
        field.setName("field");
        field.setBounds(10, 10, 100, 20);
        Actor content = actor("content", 0, 0, 200, 200);
        ScrollPane pane = new ScrollPane(content, new ScrollPaneStyle());
        pane.setName("pane");
        pane.setBounds(150, 10, 100, 100);
        stage.addActor(field);
        stage.addActor(pane);
        stage.setKeyboardFocus(field);
        stage.setScrollFocus(pane);

        SemanticSnapshot snapshot = snapshot(stage);
        SemanticNode fieldNode = nodeByActorName(snapshot, "field");
        SemanticNode paneNode = nodeByActorName(snapshot, "pane");

        assertTrue(fieldNode.state().focused());
        assertTrue(fieldNode.state().focusable());
        assertTrue(paneNode.state().focused());
        assertTrue(paneNode.state().focusable());
    }

    @Test void propagatesNestedScrollPaneClippingAndViewportIntersection() {
        Stage stage = stage();
        Actor leaf = actor("outside", 140, 140, 20, 20);
        Group innerContent = new Group();
        innerContent.setSize(200, 200);
        innerContent.addActor(leaf);
        ScrollPane inner = new ScrollPane(innerContent, new ScrollPaneStyle());
        inner.setBounds(0, 0, 120, 120);
        Group outerContent = new Group();
        outerContent.setSize(180, 180);
        outerContent.addActor(inner);
        ScrollPane outer = new ScrollPane(outerContent, new ScrollPaneStyle());
        outer.setBounds(10, 10, 100, 100);
        stage.addActor(outer);

        SemanticNode node = nodeByActorName(snapshot(stage), "outside");

        assertTrue(node.state().clipped());
        assertFalse(node.state().viewportIntersecting());
        assertFalse(node.state().hitTarget());
    }

    @Test void explicitMetadataOverridesInferenceAndKeepsEqualActorsDistinct() {
        Stage stage = stage();
        EqualActor first = new EqualActor();
        first.setName("first");
        first.setBounds(0, 0, 10, 10);
        EqualActor second = new EqualActor();
        second.setName("second");
        second.setBounds(20, 0, 10, 10);
        stage.addActor(first);
        stage.addActor(second);
        try (Scene2dSession session = new Scene2dSession(stage)) {
            session.adapters().register(EqualActor.class, (actor, target) -> target
                    .role(Role.BUTTON)
                    .accessibleName("inferred")
                    .property("source", "adapter"));
            session.semantics().setRole(first, Role.LABEL);
            session.semantics().setAccessibleName(first, "First explicit");
            session.semantics().setTestId(first, "first-id");
            session.semantics().setLabel(first, "First label");
            session.semantics().setProperty(first, "source", "metadata");
            session.semantics().setAccessibleName(second, "Second explicit");

            SemanticSnapshot snapshot = session.snapshot(1, 1);
            SemanticNode firstNode = nodeByActorName(snapshot, "first");
            SemanticNode secondNode = nodeByActorName(snapshot, "second");

            assertEquals(Role.LABEL, firstNode.role());
            assertEquals("First explicit", firstNode.accessibleName());
            assertEquals("first-id", firstNode.testId());
            assertEquals("First label", firstNode.label());
            assertEquals("metadata", firstNode.properties().get("source"));
            assertEquals("Second explicit", secondNode.accessibleName());
            assertNotEquals(firstNode.accessibleName(), secondNode.accessibleName());
        }
    }

    @Test void rejectsCustomAdapterOutputBeyondConfiguredLimitsBeforePublishing() {
        Stage stage = stage();
        EqualActor actor = new EqualActor();
        actor.setBounds(0, 0, 10, 10);
        stage.addActor(actor);
        HarnessLimits limits = new HarnessLimits(10, 10, 10, 5, 10_000, Duration.ofSeconds(1));
        Scene2dSnapshotter snapshotter = new Scene2dSnapshotter(limits);
        snapshotter.adapters().register(
                EqualActor.class, (value, target) -> target.accessibleName("too-long"));

        HarnessException exception = assertThrows(
                HarnessException.class, () -> snapshotter.snapshot(stage, 1, 1));

        assertEquals(ErrorCode.LIMIT_EXCEEDED, exception.code());
    }

    @Test void reportsPropertyCountOverflowAsTypedLimitFailure() {
        Stage stage = stage();
        EqualActor actor = new EqualActor();
        actor.setBounds(0, 0, 10, 10);
        stage.addActor(actor);
        Scene2dSnapshotter snapshotter = new Scene2dSnapshotter();
        boolean[] continuedAfterOverflow = {false};
        snapshotter.adapters().register(EqualActor.class, (value, target) -> {
            for (int index = 0; index <= 256; index++) {
                target.property("property-" + index, "value");
            }
            continuedAfterOverflow[0] = true;
        });

        HarnessException exception = assertThrows(
                HarnessException.class, () -> snapshotter.snapshot(stage, 1, 1));

        assertEquals(ErrorCode.LIMIT_EXCEEDED, exception.code());
        assertEquals("properties", exception.evidence().details().get("dimension"));
        assertFalse(continuedAfterOverflow[0]);
    }

    @Test void permitsReplacingAPropertyAtTheCountBound() {
        Stage stage = stage();
        EqualActor actor = new EqualActor();
        actor.setBounds(0, 0, 10, 10);
        stage.addActor(actor);
        Scene2dSnapshotter snapshotter = new Scene2dSnapshotter();
        snapshotter.adapters().register(EqualActor.class, (value, target) -> {
            for (int index = 0; index < 256; index++) {
                target.property("property-" + index, "original");
            }
            target.property("property-0", "replacement");
        });

        SemanticNode node = snapshotter.snapshot(stage, 1, 1).nodes().values().stream()
                .filter(candidate -> candidate.actorType().equals("EqualActor"))
                .findFirst()
                .orElseThrow();

        assertEquals(256, node.properties().size());
        assertEquals("replacement", node.properties().get("property-0"));
    }


    @Test void closedSessionRejectsSnapshotsAndMetadataChanges() {
        Stage stage = stage();
        Actor actor = actor("actor", 0, 0, 10, 10);
        stage.addActor(actor);
        Scene2dSession session = new Scene2dSession(stage);
        session.close();

        HarnessException snapshotFailure = assertThrows(
                HarnessException.class, () -> session.snapshot(1, 1));
        HarnessException metadataFailure = assertThrows(
                HarnessException.class, () -> session.semantics().setTestId(actor, "closed"));

        assertEquals(ErrorCode.SESSION_CLOSED, snapshotFailure.code());
        assertEquals(ErrorCode.SESSION_CLOSED, metadataFailure.code());
        assertFalse(session.isOpen());
    }

    private static SemanticSnapshot snapshot(Stage stage) {
        return new Scene2dSnapshotter().snapshot(stage, 1, 1);
    }

    private static Stage stage() {
        com.badlogic.gdx.utils.GdxNativesLoader.load();
        NoopBatch.installGraphics();
        FitViewport viewport = new FitViewport(800, 600);
        viewport.setScreenBounds(0, 0, 800, 600);
        viewport.getCamera().position.set(400, 300, 0);
        viewport.getCamera().update();
        return new Stage(viewport, new NoopBatch());
    }

    private static Actor actor(String name, float x, float y, float width, float height) {
        Actor actor = new Actor();
        actor.setName(name);
        actor.setBounds(x, y, width, height);
        return actor;
    }

    private static SemanticNode nodeByAccessibleName(SemanticSnapshot snapshot, String name) {
        return snapshot.nodes().values().stream()
                .filter(node -> name.equals(node.accessibleName()))
                .findFirst()
                .orElseThrow();
    }

    private static SemanticNode nodeByActorName(SemanticSnapshot snapshot, String name) {
        return snapshot.nodes().values().stream()
                .filter(node -> name.equals(node.actorName()))
                .findFirst()
                .orElseThrow();
    }

    private static final class EqualActor extends Actor {
        @Override public boolean equals(Object other) {
            return other instanceof EqualActor;
        }

        @Override public int hashCode() {
            return 1;
        }
    }
}
