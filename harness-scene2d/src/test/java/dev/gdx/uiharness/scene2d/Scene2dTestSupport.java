package dev.gdx.uiharness.scene2d;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
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
        installApplication();
        installInput();
        FitViewport viewport = new FitViewport(800, 600);
        viewport.setScreenBounds(0, 0, 800, 600);
        viewport.getCamera().position.set(400, 300, 0);
        viewport.getCamera().update();
        return new Stage(viewport, new NoopBatch());
    }

    private static void installApplication() {
        Gdx.app = (Application) java.lang.reflect.Proxy.newProxyInstance(
                Application.class.getClassLoader(),
                new Class<?>[] {Application.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("getType")) {
                        return Application.ApplicationType.Desktop;
                    }
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) {
                        return false;
                    }
                    if (type == int.class) {
                        return 0;
                    }
                    if (type == long.class) {
                        return 0L;
                    }
                    if (type == float.class) {
                        return 0f;
                    }
                    return null;
                });
    }

    private static void installInput() {
        Gdx.input = (Input) java.lang.reflect.Proxy.newProxyInstance(
                Input.class.getClassLoader(),
                new Class<?>[] {Input.class},
                (proxy, method, arguments) -> {
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) {
                        return false;
                    }
                    if (type == int.class) {
                        return 0;
                    }
                    if (type == long.class) {
                        return 0L;
                    }
                    if (type == float.class) {
                        return 0f;
                    }
                    return null;
                });
    }
}
