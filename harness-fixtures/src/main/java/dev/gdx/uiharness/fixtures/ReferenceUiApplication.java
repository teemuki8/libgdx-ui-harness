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
    private final String benchmarkScenario;
    private final int benchmarkDelayMillis;
    private final boolean markup;
    private FixtureScreen screen;
    private MarkupSigninScreen markupScreen;
    private FixtureControl control;
    /**
     * Readiness is a completed-frame barrier: {@code REFERENCE_UI_READY} is printed only after
     * the first frame finishes drawing. Scene2D defers widget layout until the first draw, so
     * declaring readiness from {@code create()} lets a client observe a stage that is still
     * mid-layout (bounds change between two immediate observations). See
     * {@link #render()} and {@link #readyPrinted}.
     */
    private boolean readyPrinted;

    private ReferenceUiApplication(
            Path processRoot, String benchmarkScenario, int benchmarkDelayMillis,
            boolean markup) {
        this.processRoot = processRoot;
        this.benchmarkScenario = benchmarkScenario;
        this.benchmarkDelayMillis = benchmarkDelayMillis;
        this.markup = markup;
    }

    /** Launches one hidden, non-networked fixture process. */
    public static void main(String[] args) {
        if (args.length != 1 && args.length != 2 && args.length != 3 && args.length != 4) {
            throw new IllegalArgumentException(
                    "Expected a process root, optional benchmark scenario/delay, and an "
                            + "optional final \"markup\" flag");
        }
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Process root must already exist");
        }
        boolean markup = (args.length == 2 || args.length == 4)
                && "markup".equals(args[args.length - 1]);
        if ((args.length == 2 || args.length == 4) && !markup) {
            throw new IllegalArgumentException("Final argument must be \"markup\"");
        }
        String benchmarkScenario = (args.length == 3 || args.length == 4) ? args[1] : null;
        int benchmarkDelayMillis = (args.length == 3 || args.length == 4)
                ? Integer.parseInt(args[2]) : 0;
        if (benchmarkScenario != null
                && (benchmarkScenario.isBlank() || benchmarkDelayMillis <= 0
                        || benchmarkDelayMillis % 16 != 0)) {
            throw new IllegalArgumentException(
                    "Benchmark delay must be a positive fixed-step multiple");
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
        new Lwjgl3Application(new ReferenceUiApplication(
                root, benchmarkScenario, benchmarkDelayMillis, markup), configuration);
    }

    @Override public void create() {
        if (markup) {
            markupScreen = new MarkupSigninScreen();
            screen = markupScreen;
            control = new FixtureControl(screen.stage(), processRoot,
                    screen.typographyControlIds(), screen.layoutControlIds());
            markupScreen.attachSemantics(control.semantics(), control.agentRuntime(),
                    FixtureControl.SESSION_ID);
        } else {
            ReferenceScreen referenceScreen =
                    new ReferenceScreen(benchmarkScenario, benchmarkDelayMillis);
            screen = referenceScreen;
            control = new FixtureControl(screen.stage(), processRoot,
                    screen.typographyControlIds(), screen.layoutControlIds());
            referenceScreen.attachAssertionFrameControl(control::withholdAssertionFrames);
            referenceScreen.attachSemantics(control.semantics());
        }
        Gdx.input.setInputProcessor(screen.stage());
        control.startMcp(System.in, System.out);
    }

    @Override public void render() {
        control.beforeDraw();
        Gdx.gl.glClearColor(0.09f, 0.125f, 0.2f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        screen.draw();
        control.afterDraw();
        if (!readyPrinted) {
            readyPrinted = true;
            System.err.println("REFERENCE_UI_READY");
        }
    }

    @Override public void resize(int width, int height) {
        if (screen != null) {
            screen.resize(width, height);
        }
    }

    @Override public void dispose() {
        RuntimeException failure = null;
        if (markupScreen != null) {
            // The markup runtime registrations must close before FixtureControl closes the
            // shared AgentRuntime, mirroring the preview's source-then-runtime order.
            try {
                markupScreen.closeRuntimeSource();
            } catch (RuntimeException closeFailure) {
                failure = closeFailure;
            }
        }
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
