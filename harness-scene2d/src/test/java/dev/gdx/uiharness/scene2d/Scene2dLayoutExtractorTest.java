package dev.gdx.uiharness.scene2d;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane.ScrollPaneStyle;
import dev.gdx.uiharness.core.layout.LayoutObservation;
import dev.gdx.uiharness.core.typography.CoordinateSpace;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class Scene2dLayoutExtractorTest {
    @Test
    void attributesInternalClipAndOwnerChainToSelectedRow() {
        Stage stage = Scene2dTestSupport.stage();
        Group body = new Group();
        body.setSize(300, 500);
        Actor row = new Actor();
        row.setBounds(0, 390, 300, 44);
        body.addActor(row);
        ScrollPane pane = new ScrollPane(body, new ScrollPaneStyle());
        pane.setBounds(100, 80, 300, 200);
        pane.setScrollingDisabled(true, false);
        pane.setSmoothScrolling(false);
        stage.addActor(pane);
        pane.validate();

        try (Scene2dSession session = new Scene2dSession(stage)) {
            session.semantics().setTestId(body, "scrolling-form");
            session.semantics().setTestId(pane, "settings-scroll");
            session.semantics().setTestId(row, "major-rival-count");
            session.semantics().setLayout(
                    row, new LayoutMetadata("scrolling-row"));

            LayoutObservation observed = session.layout(
                    5,
                    9,
                    new LayoutCaptureContext(
                            "fixture-app",
                            "bottom-800x600",
                            "current-layout",
                            "a".repeat(64),
                            800,
                            600,
                            800,
                            600,
                            13,
                            Set.of("major-rival-count")))
                    .getFirst();

            assertEquals("major-rival-count", observed.controlId());
            assertEquals("scrolling-form", observed.parentActorId());
            assertEquals("scrolling-form", observed.layoutOwnerId());
            assertEquals("settings-scroll", observed.scrollOwnerId());
            assertEquals("settings-scroll", observed.observedClipOwnerId());
            assertEquals(List.of("settings-scroll"),
                    observed.clipChain().stream().map(value -> value.ownerId()).toList());
            assertEquals(CoordinateSpace.FRAMEBUFFER,
                    observed.visibleIntersection().space());
            assertEquals(800, observed.display().logicalViewportWidth());
            assertEquals(1, observed.display().deviceScaleX());
            assertFalse(observed.scroll().active());
            assertTrue(observed.transforms().invertible());
        }
    }

    @Test
    void absentSelectedControlFailsClosedByStableId() {
        Stage stage = Scene2dTestSupport.stage();
        try (Scene2dSession session = new Scene2dSession(stage)) {
            IllegalArgumentException failure =
                    org.junit.jupiter.api.Assertions.assertThrows(
                            IllegalArgumentException.class,
                            () -> session.layout(
                                    1,
                                    1,
                                    new LayoutCaptureContext(
                                            "fixture-app",
                                            "initial-800x600",
                                            "current-layout",
                                            "a".repeat(64),
                                            800,
                                            600,
                                            800,
                                            600,
                                            1,
                                            Set.of("missing-row"))));

            assertTrue(failure.getMessage().contains("missing-row"));
        }
    }
}
