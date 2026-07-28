package dev.gdx.uiharness.fixtures;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.HdpiMode;
import java.nio.file.Files;
import java.nio.file.Path;

/** Hidden real LWJGL3 reference process whose only control transport is MCP over stdio. */
public final class ReferenceUiApplication extends ApplicationAdapter {
    /** Fixed framebuffer width. */
    public static final int WIDTH = 1280;
    /** Fixed framebuffer height. */
    public static final int HEIGHT = 720;

    private final Path processRoot;
    private ReferenceScreen screen;
    private FixtureControl control;

    private ReferenceUiApplication(Path processRoot) {
        this.processRoot = processRoot;
    }

    /** Launches one hidden, non-networked fixture process. */
    public static void main(String[] args) {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected one server-owned process root");
        }
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Process root must already exist");
        }

        Lwjgl3ApplicationConfiguration configuration = new Lwjgl3ApplicationConfiguration();
        configuration.setTitle("libGDX UI Harness Reference");
        configuration.setWindowedMode(WIDTH, HEIGHT);
        configuration.setWindowSizeLimits(WIDTH, HEIGHT, WIDTH, HEIGHT);
        configuration.setResizable(false);
        configuration.setInitialVisible(false);
        configuration.setHdpiMode(HdpiMode.Pixels);
        configuration.useVsync(false);
        configuration.setForegroundFPS(60);
        configuration.setIdleFPS(60);
        configuration.disableAudio(true);
        new Lwjgl3Application(new ReferenceUiApplication(root), configuration);
    }

    @Override public void create() {
        screen = new ReferenceScreen();
        control = new FixtureControl(screen.stage(), processRoot);
        screen.attachSemantics(control.semantics());
        Gdx.input.setInputProcessor(screen.stage());
        control.startMcp(System.in, System.out);
        System.err.println("REFERENCE_UI_READY");
    }

    @Override public void render() {
        control.beforeDraw();
        Gdx.gl.glClearColor(0.09f, 0.125f, 0.2f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        screen.draw();
        control.afterDraw();
    }

    @Override public void resize(int width, int height) {
        if (screen != null) {
            screen.resize(width, height);
        }
    }

    @Override public void dispose() {
        RuntimeException failure = null;
        if (control != null) {
            try {
                control.close();
            } catch (RuntimeException closeFailure) {
                failure = closeFailure;
            }
        }
        if (screen != null) {
            try {
                screen.close();
            } catch (RuntimeException closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else {
                    failure.addSuppressed(closeFailure);
                }
            }
        }
        if (failure != null) {
            System.err.println("REFERENCE_UI_CLOSE_FAILED: " + failure.getMessage());
            throw failure;
        }
        System.err.println("REFERENCE_UI_CLOSED");
    }
}
