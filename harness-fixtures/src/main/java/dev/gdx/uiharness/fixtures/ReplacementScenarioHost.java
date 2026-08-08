package dev.gdx.uiharness.fixtures;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.HdpiMode;
import com.badlogic.gdx.scenes.scene2d.Stage;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import dev.gdx.uiharness.core.scenario.ScenarioDefinition;
import dev.gdx.uiharness.core.scenario.ScenarioLifecycle;
import dev.gdx.uiharness.core.scenario.ScenarioRegistry;
import dev.gdx.uiharness.core.scenario.ScenarioRequest;
import dev.gdx.uiharness.scene2d.ControlledStageClock;
import dev.gdx.uiharness.scene2d.RenderThreadScheduler;
import dev.gdx.uiharness.scene2d.Scene2dScenarioRunner;
import dev.gdx.uiharness.scene2d.Scene2dSession;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Dedicated hidden LWJGL3 host that executes exactly one transferred registered scenario. */
public final class ReplacementScenarioHost extends ApplicationAdapter {
    private static final String APPLICATION_ID = "reference-ui-app";
    private static final Duration STEP = Duration.ofMillis(16);
    private final ScenarioRequest request;
    private final BufferedReader input;
    private final BufferedWriter output;
    private final String processId;
    private final String sessionId;
    private Stage stage;
    private ControlledStageClock clock;
    private RenderThreadScheduler scheduler;
    private Scene2dSession session;
    private Scene2dScenarioRunner runner;
    private ScheduledExecutorService deadlines;
    private CompletableFuture<?> scenario;
    private Lifecycle lifecycle;

    private ReplacementScenarioHost(ScenarioRequest request, BufferedReader input, BufferedWriter output) {
        this.request = request;
        this.input = input;
        this.output = output;
        String identity = Long.toUnsignedString(ProcessHandle.current().pid());
        processId = "replacement-process-" + identity;
        sessionId = "replacement-session-" + identity;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            throw new IllegalArgumentException("replacement host accepts no launch arguments");
        }
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        BufferedWriter output = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8));
        String line = input.readLine();
        if (line == null || line.length() > ReplacementWire.MAX_LINE_CHARS) {
            throw new IllegalArgumentException("missing or oversized replacement request");
        }
        ScenarioRequest request = ReplacementWire.request(line);
        Lwjgl3ApplicationConfiguration configuration = new Lwjgl3ApplicationConfiguration();
        configuration.setTitle("libGDX UI Harness Replacement");
        configuration.setWindowedMode(ReferenceUiApplication.WIDTH, ReferenceUiApplication.HEIGHT);
        configuration.setInitialVisible(false);
        configuration.setResizable(false);
        configuration.setHdpiMode(HdpiMode.Pixels);
        configuration.useVsync(false);
        configuration.setForegroundFPS(60);
        configuration.setIdleFPS(60);
        configuration.disableAudio(true);
        new Lwjgl3Application(new ReplacementScenarioHost(request, input, output), configuration);
    }

    @Override public void create() {
        stage = new Stage();
        clock = new ControlledStageClock(stage, STEP);
        scheduler = new RenderThreadScheduler(32);
        session = new Scene2dSession(stage);
        ScenarioRegistry registry = new ScenarioRegistry();
        Duration maximum = "never-ready".equals(request.scenarioId())
                ? Duration.ofMillis(100) : Duration.ofSeconds(5);
        lifecycle = new Lifecycle();
        registry.register(new ScenarioDefinition(
                ScenarioDefinition.SCHEMA_VERSION, request.scenarioId(), "1", APPLICATION_ID,
                List.of(request.profileId()), 1, maximum), lifecycle);
        deadlines = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("replacement-scenario-deadline").factory());
        runner = new Scene2dScenarioRunner(registry, scheduler, clock, (delay, signal) -> {
            var scheduled = deadlines.schedule(signal, delay.toNanos(), TimeUnit.NANOSECONDS);
            return () -> scheduled.cancel(false);
        });
        scenario = runner.start(request, APPLICATION_ID, processId, sessionId).toCompletableFuture();
        scenario.whenComplete((result, failure) -> Gdx.app.postRunnable(() -> {
            try {
                if (failure != null) {
                    throw new IllegalStateException("replacement scenario failed", failure);
                }
                dev.gdx.uiharness.core.scenario.ScenarioResult terminal =
                        (dev.gdx.uiharness.core.scenario.ScenarioResult) result;
                if (!terminal.cleanupCompleted()) {
                    // The deadline thread published the result before render-owned cleanup
                    // drained: run the render scheduler now so the deferred cleanup hook
                    // executes exactly once before this host exits. If the drain did not run
                    // it, fail the handoff instead of exiting without the cleanup.
                    scheduler.drain();
                    if (!lifecycle.cleanupRan()) {
                        throw new IllegalStateException(
                                "replacement scenario cleanup did not run before host exit");
                    }
                }
                output.write(ReplacementWire.result(terminal,
                        "replacement-reconnect-" + ProcessHandle.current().pid()));
                output.newLine();
                output.flush();
            } catch (Exception transportFailure) {
                transportFailure.printStackTrace(System.err);
            } finally {
                Gdx.app.exit();
            }
        }));
        Thread.ofVirtual().name("replacement-cancellation").start(() -> {
            try {
                if ("CANCEL".equals(input.readLine())) {
                    scenario.cancel(false);
                    Gdx.app.postRunnable(Gdx.app::exit);
                }
            } catch (Exception ignored) {
                // Parent closure terminates the process when no result remains possible.
            }
        });
    }

    @Override public void render() {
        scheduler.drain();
        clock.advance(STEP);
        stage.act(STEP.toNanos() / 1_000_000_000f);
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        stage.draw();
        if (!Boolean.parseBoolean(request.configuration().getOrDefault("withholdCompletedFrames", "false"))) {
            session.completedFrame(runner, clock.revision(), clock.frame());
        }
    }

    @Override public void dispose() {
        if (runner != null) {
            runner.close();
        }
        if (session != null) {
            session.close();
        }
        if (scheduler != null) {
            scheduler.close();
        }
        if (clock != null) {
            clock.close();
        }
        if (deadlines != null) {
            deadlines.close();
        }
        if (stage != null) {
            stage.dispose();
        }
    }

    private static final class Lifecycle implements ScenarioLifecycle {
        private final IdentityHashMap<ScenarioRequest, Integer> readiness = new IdentityHashMap<>();
        private final java.util.concurrent.atomic.AtomicBoolean cleanupRan =
                new java.util.concurrent.atomic.AtomicBoolean();

        /** Reports whether the cleanup hook executed; checked before the host writes its result. */
        boolean cleanupRan() {
            return cleanupRan.get();
        }

        @Override public void setup(ScenarioRequest request) { readiness.put(request, 0); }
        @Override public void reset(ScenarioRequest request) {}
        @Override public boolean ready(ScenarioRequest request) {
            int frames = readiness.compute(request, (ignored, current) -> current == null ? 1 : current + 1);
            return !"never-ready".equals(request.scenarioId()) && frames >= 2;
        }
        @Override public String startStateIdentity(ScenarioRequest request, SemanticSnapshot snapshot) {
            return request.scenarioId() + ":" + request.seed() + ":"
                    + request.configuration().getOrDefault("mode", "default");
        }
        @Override public void cleanup(ScenarioRequest request) {
            readiness.remove(request);
            cleanupRan.set(true);
        }
    }
}
