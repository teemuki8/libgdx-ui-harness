package dev.gdx.uiharness.scene2d;

import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.utils.GdxNativesLoader;
import com.badlogic.gdx.utils.viewport.FitViewport;

/** Graphics-plumbing-only support for real Stage fixtures in other test packages. */
@SuppressWarnings("auxiliaryclass")
public final class Scene2dTestSupport {
    private Scene2dTestSupport() {}

    /** Creates an 800 by 600 Stage backed by a no-op batch. */
    public static Stage stage() {
        GdxNativesLoader.load();
        NoopBatch.installGraphics();
        FitViewport viewport = new FitViewport(800, 600);
        viewport.setScreenBounds(0, 0, 800, 600);
        viewport.getCamera().position.set(400, 300, 0);
        viewport.getCamera().update();
        return new Stage(viewport, new NoopBatch());
    }
}
