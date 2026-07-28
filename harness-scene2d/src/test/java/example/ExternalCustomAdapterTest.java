package example;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import dev.gdx.uiharness.core.model.Role;
import dev.gdx.uiharness.core.model.SemanticNode;
import dev.gdx.uiharness.scene2d.Scene2dSnapshotter;
import dev.gdx.uiharness.scene2d.Scene2dTestSupport;
import org.junit.jupiter.api.Test;

final class ExternalCustomAdapterTest {
    @Test void applicationPackageCanRegisterAndPublishCustomSemantics() {
        Stage stage = Scene2dTestSupport.stage();
        ExternalActor actor = new ExternalActor();
        actor.setBounds(10, 10, 20, 20);
        stage.addActor(actor);
        Scene2dSnapshotter snapshotter = new Scene2dSnapshotter();
        snapshotter.adapters().register(ExternalActor.class, (value, target) -> target
                .role(Role.BUTTON)
                .accessibleName("External")
                .property("origin", "application"));

        SemanticNode node = snapshotter.snapshot(stage, 1, 1).nodes().values().stream()
                .filter(candidate -> candidate.role() == Role.BUTTON)
                .findFirst()
                .orElseThrow();

        assertEquals("External", node.accessibleName());
        assertEquals("application", node.properties().get("origin"));
        stage.dispose();
    }

    private static final class ExternalActor extends Actor {}
}
