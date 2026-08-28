package dev.gdx.uiharness.fixtures;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import dev.gdx.uiharness.agentruntime.AgentRuntimeObservationSource;
import dev.gdx.uiharness.agentruntime.AgentRuntimeTickCoordinator;
import dev.gdx.uiharness.core.action.Action;
import dev.gdx.uiharness.core.action.ActionResult;
import dev.gdx.uiharness.core.action.Harness;
import dev.gdx.uiharness.core.assertion.DeadlineWakeup;
import dev.gdx.uiharness.core.capture.CaptureRequest;
import dev.gdx.uiharness.core.capture.CapturedImage;
import dev.gdx.uiharness.core.capture.ScreenCapture;
import dev.gdx.uiharness.core.error.ErrorCode;
import dev.gdx.uiharness.core.error.ErrorEvidence;
import dev.gdx.uiharness.core.error.HarnessException;
import dev.gdx.uiharness.core.layout.LayoutControlReference;
import dev.gdx.uiharness.core.layout.LayoutEvidence;
import dev.gdx.uiharness.core.layout.LayoutObservation;
import dev.gdx.uiharness.core.layout.LayoutQuiescenceEvaluator;
import dev.gdx.uiharness.core.layout.LayoutQuiescencePolicy;
import dev.gdx.uiharness.core.layout.LayoutReference;
import dev.gdx.uiharness.core.layout.LayoutStabilitySample;
import dev.gdx.uiharness.core.visual.VisualPolicy;
import dev.gdx.uiharness.core.assertion.AssertionSnapshotSource;
import dev.gdx.uiharness.core.visual.VisualReference;
import dev.gdx.uiharness.core.typography.CoordinateSpace;
import dev.gdx.uiharness.core.typography.TypographyControlReference;
import dev.gdx.uiharness.core.typography.TypographyObservation;
import dev.gdx.uiharness.core.typography.TypographyReference;
import dev.gdx.uiharness.core.layout.LayoutValidationCheck;
import dev.gdx.uiharness.core.layout.LayoutValidationConfig;
import dev.gdx.uiharness.core.layout.LayoutValidationSeverity;
import dev.gdx.uiharness.core.locator.Locator;
import dev.gdx.uiharness.core.locator.LocatorEngine;
import dev.gdx.uiharness.core.locator.StrictResolution;
import dev.gdx.uiharness.core.golden.BaselineNode;
import dev.gdx.uiharness.core.golden.PositionalTolerance;
import dev.gdx.uiharness.core.golden.SemanticBaseline;
import dev.gdx.uiharness.core.golden.SemanticBaselineCatalog;
import dev.gdx.uiharness.core.golden.SemanticComparePolicy;
import dev.gdx.uiharness.core.golden.SemanticComparator;
import dev.gdx.uiharness.core.model.SemanticNode;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import dev.gdx.uiharness.core.runtime.RuntimeComparator;
import dev.gdx.uiharness.core.runtime.RuntimeObservationSource;
import dev.gdx.uiharness.core.runtime.RuntimeObserver;
import dev.gdx.uiharness.core.scenario.ScenarioDefinition;
import dev.gdx.uiharness.core.scenario.ScenarioLifecycle;
import dev.gdx.uiharness.core.scenario.ScenarioRegistry;
import dev.gdx.uiharness.core.scenario.ScenarioRequest;
import dev.gdx.uiharness.core.time.Deadline;
import dev.gdx.uiharness.core.time.DeadlineScheduler;
import dev.gdx.uiharness.core.trace.SemanticObservation;
import dev.gdx.uiharness.core.trace.SemanticObservationStore;
import dev.gdx.uiharness.core.trace.TraceEvent;
import dev.gdx.uiharness.core.trace.TraceManifest;
import dev.gdx.uiharness.core.trace.TraceRecorder;
import dev.gdx.uiharness.core.trace.TransitionProjector;
import dev.gdx.uiharness.core.trace.TransitionQuery;
import dev.gdx.uiharness.core.trace.TransitionQueryResult;
import dev.gdx.uiharness.core.wait.FrameSignal;
import dev.gdx.uiharness.core.wait.WaitEngine;
import dev.gdx.uiharness.core.matrix.MatrixDefinition;
import dev.gdx.uiharness.core.matrix.MatrixHiDpi;
import dev.gdx.uiharness.core.matrix.MatrixLimits;
import dev.gdx.uiharness.core.matrix.MatrixReport;
import dev.gdx.uiharness.core.matrix.MatrixWindow;
import dev.gdx.uiharness.lwjgl3.Lwjgl3FrameFence;
import dev.gdx.uiharness.lwjgl3.Lwjgl3MatrixRunner;
import dev.gdx.uiharness.lwjgl3.LaunchProfile;
import dev.gdx.uiharness.lwjgl3.Lwjgl3ScreenCapture;
import dev.gdx.uiharness.lwjgl3.RegisteredLaunchCoordinator;
import dev.gdx.uiharness.lwjgl3.Lwjgl3VisualComparator;
import dev.gdx.uiharness.lwjgl3.Lwjgl3TypographyRasterComparator;
import dev.gdx.uiharness.mcp.ArtifactReference;
import dev.gdx.uiharness.mcp.HarnessMcpServer;
import dev.gdx.uiharness.protocol.ArtifactId;
import dev.gdx.uiharness.protocol.ArtifactMediaType;
import dev.gdx.uiharness.protocol.ArtifactStore;
import dev.gdx.uiharness.protocol.CapabilitySet;
import dev.gdx.uiharness.protocol.Command;
import dev.gdx.uiharness.protocol.FileArtifactStore;
import dev.gdx.uiharness.protocol.HarnessProtocolService;
import dev.gdx.uiharness.protocol.HarnessResponse;
import dev.gdx.uiharness.protocol.InspectCaptureCompareService;
import dev.gdx.uiharness.protocol.LayoutDiagnosticService;
import dev.gdx.uiharness.protocol.TypographyDiagnosticService;
import dev.gdx.uiharness.scene2d.ControlledStageClock;
import dev.gdx.uiharness.scene2d.LayoutCaptureContext;
import dev.gdx.uiharness.scene2d.RenderThreadScheduler;
import dev.gdx.uiharness.scene2d.Scene2dHarness;
import dev.gdx.uiharness.scene2d.Scene2dNavigationRunner;
import dev.gdx.uiharness.scene2d.Scene2dScenarioRunner;
import dev.gdx.uiharness.scene2d.Scene2dInputDispatcher;
import dev.gdx.uiharness.scene2d.Scene2dKeyboardGestureRunner;
import dev.gdx.uiharness.scene2d.Scene2dSession;
import dev.gdx.uiharness.scene2d.TypographyCaptureContext;
import io.github.teemuki8.libgdx.agent.runtime.core.SimulationControllerSpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Owns deterministic render hooks and every cross-module resource for one fixture process. */
public final class FixtureControl implements AutoCloseable {
    /** Stable protocol session selected by all reference workflows. */
    public static final String SESSION_ID = "reference-ui";

    /** Frame-correlation token recorded each rendered frame; runtime bindings must carry it. */
    public static final String CORRELATION_TOKEN = "reference-ui-frame";
    private static final String APPLICATION_ID = "reference-ui-app";
    private static final String VIEWPORT_ID = "main";
    private static final String REFERENCE_ID = "reference-screen";
    private static final String TYPOGRAPHY_REFERENCE_ID = "reference-typography";
    private static final String LAYOUT_REFERENCE_ID = "reference-layout";
    private static final String PROCESS_ID = "reference-ui-process";
    private static final LaunchProfile RESTART_PROFILE =
            new LaunchProfile(LaunchProfile.SCHEMA_VERSION,
                    "desktop-restart-1280x720", APPLICATION_ID);

    private static final Duration FIXED_STEP = Duration.ofMillis(16);
    private static final Duration ARTIFACT_LIFETIME = Duration.ofHours(1);
    private static final List<String> CAPABILITIES = List.of(
            "action", "compare", "query", "scenario-list", "scenario-start",
            "screenshot", "snapshot", "layout", "trace", "typography", "ui_assert", "wait",
            "ui_matrix_run", "ui_matrix_results", "ui_semantic_compare",
            "ui_navigation_inspect", "ui_navigation_validate", "ui_runtime_compare",
            "ui_runtime_observe", "ui_trace_query", "ui_validate_layout",
            "ui_keyboard_gesture", "ui_keyboard_gesture_ticks");

    private final Path processRoot;
    private final Path artifactRoot;
    private final Path traceRoot;
    private final Path proofRoot;
    private final Stage stage;
    private final ControlledStageClock clock;
    private final RenderThreadScheduler scheduler;
    private final Scene2dSession sceneSession;
    private final Scene2dHarness sceneHarness;
    private final ScenarioRegistry scenarios;
    private final ReplacementProcessCoordinator replacementCoordinator;
    private final Lwjgl3FrameFence fence;
    private final Lwjgl3ScreenCapture capture;
    private final WaitEngine waits;
    private final FileArtifactStore artifactStore;
    private final StorePublisher publisher;
    private final ReferenceTraceController traces;
    private final Harness tracingHarness;
    private final ExecutorService protocolExecutor;
    private final ExecutorService terminationExecutor;
    private final ScheduledExecutorService scenarioDeadlines;
    private final ExecutorService replacementExecutor;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean withholdAssertionFrames = new AtomicBoolean();
    private final AtomicBoolean withholdScenarioFrames = new AtomicBoolean();
    private final RegisteredLaunchCoordinator launchCoordinator;
    private final Scene2dScenarioRunner scenarioRunner;
    private final Scene2dNavigationRunner navigationRunner;
    private final dev.gdx.uiharness.scene2d.Scene2dLayoutValidator layoutValidator;
    private final Lwjgl3MatrixRunner matrixRunner;
    private final SemanticBaselineCatalog baselineCatalog = new SemanticBaselineCatalog();
    private final io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime agentRuntime;
    private final AgentRuntimeTickCoordinator tickCoordinator;
    private final Scene2dKeyboardGestureRunner gestureRunner;
    private final AtomicBoolean gestureKeyHeld = new AtomicBoolean();
    private String gestureMarkerPreviousText;
    private final ReferenceUiModel uiModel = new ReferenceUiModel("Ada", "");
    private HarnessMcpServer server;
    private Future<?> terminationTask;
    private final java.util.Set<String> typographyControlIds;
    private final java.util.Set<String> layoutControlIds;

    /**
     * Attaches one named production protocol session to the supplied real Stage.
     *
     * @param typographyControlIds test identifiers of the screen's typography-marked actors;
     *     the typography reference is built from exactly these controls
     * @param layoutControlIds test identifiers of the screen's layout-marked actors; the layout
     *     reference is built from exactly these controls
     */
    public FixtureControl(Stage stage, Path newProcessRoot,
            java.util.Set<String> typographyControlIds,
            java.util.Set<String> layoutControlIds) {
        Objects.requireNonNull(stage, "stage");
        this.stage = stage;
        this.typographyControlIds = java.util.Set.copyOf(
                Objects.requireNonNull(typographyControlIds, "typographyControlIds"));
        this.layoutControlIds = java.util.Set.copyOf(
                Objects.requireNonNull(layoutControlIds, "layoutControlIds"));
        processRoot = Objects.requireNonNull(newProcessRoot, "processRoot")
                .toAbsolutePath().normalize();
        artifactRoot = processRoot.resolve("artifacts");
        traceRoot = processRoot.resolve("traces");
        proofRoot = processRoot.resolve("proofs");
        createOwnedDirectories();

        clock = new ControlledStageClock(stage, FIXED_STEP);
        scheduler = new RenderThreadScheduler(128);
        sceneSession = new Scene2dSession(stage);
        scenarioDeadlines = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("reference-scenario-deadline").factory());
        DeadlineScheduler deadlineScheduler = (delay, signal) -> {
            java.util.concurrent.ScheduledFuture<?> scheduled =
                    scenarioDeadlines.schedule(signal, delay.toNanos(),
                            java.util.concurrent.TimeUnit.NANOSECONDS);
            return () -> scheduled.cancel(false);
        };
        sceneHarness = new Scene2dHarness(stage, stage, sceneSession, scheduler, clock,
                clock::revision, clock::frame, deadlineScheduler);
        scenarios = new ScenarioRegistry();
        ReferenceScenarioLifecycle lifecycle =
                new ReferenceScenarioLifecycle(stage, uiModel, withholdScenarioFrames);
        scenarios.register(scenario("reference-reset", APPLICATION_ID), lifecycle);
        scenarios.register(scenario(
                "never-ready", APPLICATION_ID, Duration.ofMillis(100)), lifecycle);
        scenarios.register(scenario("incompatible-reference", "another-application"), lifecycle);
        scenarios.register(scenario("navigation", APPLICATION_ID), lifecycle);
        scenarioRunner = Scene2dScenarioRunner.withDeadlineScheduler(
                scenarios, scheduler, clock, deadlineScheduler);
        navigationRunner = Scene2dNavigationRunner.withDeadlineScheduler(
                scenarioRunner, sceneSession,
                new Scene2dInputDispatcher(stage, stage), scheduler, clock,
                deadlineScheduler, clock::revision, clock::frame,
                new Scene2dNavigationRunner.Scenario(
                        "navigation", 7, Map.of(), RESTART_PROFILE.id(), APPLICATION_ID, PROCESS_ID,
                        SESSION_ID),
                8);
        layoutValidator = new dev.gdx.uiharness.scene2d.Scene2dLayoutValidator(
                sceneSession, new StrictResolution());
        replacementExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("reference-replacement-launch-", 0).factory());
        replacementCoordinator = new ReplacementProcessCoordinator(
                RESTART_PROFILE.id(), replacementExecutor, ReplacementProcess::launch);
        launchCoordinator = replacementCoordinator;
        fence = new Lwjgl3FrameFence(deadlineScheduler, 64);
        capture = new Lwjgl3ScreenCapture(fence, sceneSession::snapshot);
        LocatorEngine locators = new StrictResolution();
        artifactStore = new FileArtifactStore(artifactRoot,
                new ArtifactStore.Limits(32L * 1_024 * 1_024, 64), Clock.systemUTC());
        publisher = new StorePublisher(artifactStore, proofRoot);
        traces = new ReferenceTraceController(traceRoot, publisher, locators);
        tracingHarness = new TracingHarness(sceneHarness, traces);
        protocolExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("reference-protocol-", 0).factory());
        FrameSignal assertionFrames = listener -> fence.subscribe(new FrameSignal.FrameListener() {
            @Override public void onFrame(FrameSignal.Frame frame) {
                if (!withholdAssertionFrames.get()) {
                    listener.onFrame(frame);
                }
            }

            @Override public void onClosed() {
                listener.onClosed();
            }
        });
        AssertionSnapshotSource assertionSnapshots = new AssertionSnapshotSource() {
            @Override public SemanticSnapshot currentSnapshot() {
                return snapshotForWait();
            }

            @Override public SemanticSnapshot snapshotFor(FrameSignal.Frame frame) {
                if (!scheduler.isOwnerThread()) {
                    throw new IllegalStateException(
                            "completed-frame assertion snapshot must be captured on render thread");
                }
                SemanticSnapshot snapshot =
                        sceneSession.snapshot(frame.revision(), frame.frame());
                traces.snapshot(snapshot, "assert");
                return snapshot;
            }
        };
        waits = new WaitEngine(this::snapshotForWait, assertionSnapshots, locators, clock,
                assertionFrames, DeadlineWakeup.scheduledBy(scenarioDeadlines));
        matrixRunner = new Lwjgl3MatrixRunner(
                scenarioRunner, waits,
                new ReferenceCaseApplicator(scheduler, clock, RESTART_PROFILE.id()),
                new Lwjgl3MatrixRunner.Scenario(
                        "navigation", 7, Map.of(), RESTART_PROFILE.id(), APPLICATION_ID,
                        PROCESS_ID, SESSION_ID));
        loadReferenceBaselines();
        terminationExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("reference-mcp-termination-", 0).factory());
        agentRuntime = io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime.builder()
                .sessionId(new io.github.teemuki8.libgdx.agent.runtime.core.SessionId(SESSION_ID))
                .captureThread(Thread.currentThread())
                .commandDispatcher(Gdx.app::postRunnable)
                .build();
        agentRuntime.start();
        stage.getRoot().addCaptureListener(new InputListener() {
            @Override public boolean keyDown(InputEvent event, int keycode) {
                if (keycode == Input.Keys.A) {
                    gestureKeyHeld.set(true);
                    TextField marker = (TextField) stage.getRoot().findActor("password");
                    gestureMarkerPreviousText = marker.getText();
                    marker.setText("gesture-key-held");
                }
                return false;
            }

            @Override public boolean keyUp(InputEvent event, int keycode) {
                if (keycode == Input.Keys.A) {
                    gestureKeyHeld.set(false);
                    TextField marker = (TextField) stage.getRoot().findActor("password");
                    marker.setText(gestureMarkerPreviousText == null
                            ? "" : gestureMarkerPreviousText);
                    gestureMarkerPreviousText = null;
                }
                return false;
            }
        });
        agentRuntime.controls().register(SimulationControllerSpec.builder()
                .pause(() -> {})
                .resume(() -> {})
                .tick(deltaNanos -> {
                    if (deltaNanos != FIXED_STEP.toNanos()) {
                        throw new IllegalStateException("unexpected controlled tick delta");
                    }
                    if (!gestureKeyHeld.get()) {
                        throw new IllegalStateException(
                                "controlled tick ran without callback-owned held key");
                    }
                })
                .build());
        agentRuntime.controls().control(true, "fixture-pause", Duration.ofSeconds(5));
        tickCoordinator = new AgentRuntimeTickCoordinator(
                agentRuntime, SESSION_ID, FIXED_STEP.toNanos(), fence, deadlineScheduler);
        gestureRunner = new Scene2dKeyboardGestureRunner(
                SESSION_ID, stage, scheduler, fence, clock::revision, clock::frame,
                deadlineScheduler, Optional.of(tickCoordinator), traces::gesture);
        agentRuntime.entities().register(
                io.github.teemuki8.libgdx.agent.runtime.core.EntityId.of("reference-ui-user"),
                io.github.teemuki8.libgdx.agent.runtime.core.EntityType.of("user"),
                () -> "Reference UI user",
                inspector -> inspector.property("value", () ->
                        io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValues.string(
                                uiModel.username())));
        agentRuntime.entities().register(
                io.github.teemuki8.libgdx.agent.runtime.core.EntityId.of(
                        "reference-simulation"),
                io.github.teemuki8.libgdx.agent.runtime.core.EntityType.of("simulation"),
                () -> "Reference simulation",
                inspector -> inspector.property("angle", () ->
                        io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValues.decimal(
                                "1.25")));
        wireModelToUsernameField();
    }

    /** Returns semantic metadata for actor tagging after session construction. */
    public dev.gdx.uiharness.scene2d.Semantics semantics() {
        return sceneSession.semantics();
    }

    private void wireModelToUsernameField() {
        var usernameField = stage.getRoot().findActor("username");
        if (usernameField instanceof com.badlogic.gdx.scenes.scene2d.ui.TextField textField) {
            textField.addListener(new com.badlogic.gdx.scenes.scene2d.utils.ChangeListener() {
                @Override public void changed(ChangeEvent event, com.badlogic.gdx.scenes.scene2d.Actor actor) {
                    uiModel.setUsername(textField.getText());
                }
            });
        }
    }

    /** Returns the agent runtime shared with the active screen for value registration. */
    public io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime agentRuntime() {
        return agentRuntime;
    }

    /** Returns the fixture-owned domain model used by authoritative markup registration. */
    public ReferenceUiModel uiModel() {
        return uiModel;
    }
    /** Stops assertion frame notifications while the deterministic clock keeps advancing. */
    public void withholdAssertionFrames() {
        withholdAssertionFrames.set(true);
    }

    /** Captures the pristine semantic baseline from the current stage for the dump mode. */
    public SemanticBaseline pristineBaseline() {
        SemanticSnapshot current = sceneSession.snapshot(clock.revision(), clock.frame());
        return SemanticBaseline.registered(
                1, 0, REFERENCE_ID, toBaselineNode(current.nodes(), current.rootId()), false);
    }

    /**
     * Preloads the committed canonical reference baseline resource so comparisons never learn
     * from a live snapshot. The resource is a bounded protocol JSON document decoded through
     * {@link ReferenceBaselineCodec}, which validates the canonical digest before registration.
     */
    private void loadReferenceBaselines() {
        try (InputStream input = FixtureControl.class.getResourceAsStream(
                "/reference-ui/reference-baseline.json")) {
            if (input == null) {
                throw new IllegalStateException("Reference semantic baseline resource is missing");
            }
            baselineCatalog.register(ReferenceBaselineCodec.read(input));
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to read reference semantic baseline", failure);
        }
    }

    /** Starts the production MCP server over this process's stdio streams. */
    public void startMcp(InputStream input, OutputStream output) {
        if (server != null) {
            throw new IllegalStateException("MCP server is already started");
        }
        CapabilitySet capabilities = new CapabilitySet(CAPABILITIES);
        ScreenCapture tracingCapture = new TracingCapture(capture, traces);
        HarnessProtocolService.NavigationCoordinator navigationCoordinator =
                new HarnessProtocolService.NavigationCoordinator() {
                    @Override public CompletionStage<
                            dev.gdx.uiharness.core.navigation.NavigationResult> inspect(
                            Command.NavigationSpec spec, Deadline deadline) {
                        return navigationRunner.inspect(toCoreNavigationRequest(spec, deadline));
                    }

                    @Override public CompletionStage<
                            dev.gdx.uiharness.core.navigation.NavigationResult> validate(
                            Command.NavigationSpec spec, Deadline deadline) {
                        return navigationRunner.validate(toCoreNavigationRequest(spec, deadline));
                    }
                };
        HarnessProtocolService.LayoutValidationCoordinator layoutCoordinator =
                (spec, deadline) -> scheduler.submit(
                        () -> layoutValidator.validate(
                                clock.revision(),
                                clock.frame(),
                                spec.locator() == null ? null : spec.locator().toCore(),
                                toCoreLayoutConfig(spec),
                                null),
                        deadline);
        RuntimeObservationSource runtimeSource =
                new AgentRuntimeObservationSource(agentRuntime, SESSION_ID);
        RuntimeComparator runtimeComparator = new RuntimeComparator(runtimeSource);
        HarnessProtocolService.RuntimeCompareCoordinator runtimeCoordinator =
                (locator, deadline) -> scheduler.submit(
                        () -> runtimeComparator.compare(
                                sceneSession.snapshot(clock.revision(), clock.frame()),
                                locator.toCore(), new StrictResolution()),
                        deadline);
        RuntimeObserver runtimeObserver = new RuntimeObserver(runtimeSource);
        HarnessProtocolService.RuntimeObservationCoordinator observationCoordinator =
                (entityId, propertyId, correlationToken, deadline) -> scheduler.submit(
                        () -> runtimeObserver.observe(
                                entityId, propertyId, correlationToken),
                        deadline);
        SemanticComparator semanticComparator = new SemanticComparator();
        HarnessProtocolService.SemanticCompareCoordinator semanticCoordinator =
                (spec, deadline) -> scheduler.submit(() -> {
                    SemanticSnapshot current = sceneSession.snapshot(
                            clock.revision(), clock.frame());
                    SemanticBaseline baseline;
                    try {
                        baseline = baselineCatalog.require(spec.baselineId());
                    } catch (IllegalArgumentException missing) {
                        throw new HarnessException(ErrorCode.NOT_FOUND,
                                "unknown semantic baseline: " + spec.baselineId(),
                                ErrorEvidence.empty());
                    }
                    return semanticComparator.compare(baseline, current, toCorePolicy(spec));
                }, deadline);
        HarnessProtocolService.MatrixCoordinator matrixCoordinator =
                new HarnessProtocolService.MatrixCoordinator() {
                    @Override public CompletionStage<String> run(
                            Command.MatrixRunSpec spec, Deadline deadline) {
                        return matrixRunner.run(
                                toCoreMatrixDefinition(spec, deadline),
                                new MatrixLimits(spec.maxCases()), deadline);
                    }

                    @Override public CompletionStage<MatrixReport> results(String runId) {
                        return matrixRunner.results(runId)
                                .map(CompletableFuture::completedFuture)
                                .orElseGet(() -> CompletableFuture.failedFuture(
                                        new HarnessException(ErrorCode.NOT_FOUND,
                                                "Matrix run not retained: " + runId,
                                                ErrorEvidence.empty())));
                    }
                };
        HarnessProtocolService.Session session = new HarnessProtocolService.Session(
                tracingHarness, new StrictResolution(), waits, tracingCapture,
                capabilities, traces, Optional.of(scenarios),
                Optional.of(this::startScenario),
                Optional.of(navigationCoordinator),
                Optional.of(layoutCoordinator),
                Optional.of(matrixCoordinator), Optional.of(semanticCoordinator),
                Optional.of(runtimeCoordinator), Optional.of(observationCoordinator),
                Optional.of(gestureRunner::execute));
        VisualReference reference = reference();
        VisualPolicy policy = new VisualPolicy(
                "reference-smoke", 1, 1280L * 720, 0.125, true, true);
        InspectCaptureCompareService comparison = new InspectCaptureCompareService(
                SESSION_ID, APPLICATION_ID, VIEWPORT_ID, tracingHarness, tracingCapture,
                null, id -> REFERENCE_ID.equals(id)
                        ? java.util.Optional.of(reference) : java.util.Optional.empty(),
                List.of(policy), new Lwjgl3VisualComparator(), clock, InstantSource.system());
        TypographyReference typographyReference = typographyReference(reference.pngBytes());
        TypographyDiagnosticService typography = new TypographyDiagnosticService(
                APPLICATION_ID,
                VIEWPORT_ID,
                tracingCapture,
                id -> TYPOGRAPHY_REFERENCE_ID.equals(id)
                        ? java.util.Optional.of(typographyReference)
                        : java.util.Optional.empty(),
                this::typographyEvidence,
                clock);
        LayoutReference layoutReference = layoutReference(reference.pngBytes());
        LayoutDiagnosticService layout = new LayoutDiagnosticService(
                APPLICATION_ID,
                VIEWPORT_ID,
                tracingCapture,
                id -> LAYOUT_REFERENCE_ID.equals(id)
                        ? java.util.Optional.of(layoutReference)
                        : java.util.Optional.empty(),
                (registered, current, deadline) ->
                        layoutEvidence(registered, current, deadline, tracingCapture),
                clock);
        HarnessProtocolService protocol = new HarnessProtocolService(
                Map.of(SESSION_ID, session), Map.of(), Map.of(SESSION_ID, comparison),
                Map.of(SESSION_ID, typography), Map.of(SESSION_ID, layout),
                clock, protocolExecutor);
        server = HarnessMcpServer.open(protocol, publisher, input, output);
        terminationTask = terminationExecutor.submit(() -> {
            server.awaitTermination();
            if (!closed.get()) {
                Gdx.app.postRunnable(Gdx.app::exit);
            }
        });
    }

    /** Executes exactly one deterministic Stage step and drains render-thread commands. */
    public void beforeDraw() {
        scheduler.drain();
        clock.advance(FIXED_STEP);
    }

    /** Publishes identity for the framebuffer that was just rendered. */
    public void afterDraw() {
        // Replacement JVM owns and advances its own LWJGL3 frame loop.
        fence.completedFrame(clock.revision(), clock.frame());
        sceneSession.completedFrame(
                scenarioRunner, navigationRunner, clock.revision(), clock.frame());
        agentRuntime.beginFrame(FIXED_STEP.toNanos());
        agentRuntime.endFrame();
        var completedFrame = agentRuntime.latestFrame().orElseThrow().frameId();
        agentRuntime.uiCorrelations().recordFrame(
                new io.github.teemuki8.libgdx.agent.runtime.core.UiFrameCorrelation(
                        agentRuntime.currentEpoch(), completedFrame, SESSION_ID,
                        java.util.Optional.of(Long.toString(clock.frame())),
                        java.util.Optional.of(CORRELATION_TOKEN)));
    }

    private static BaselineNode toBaselineNode(
            Map<String, SemanticNode> nodes, String rootId) {
        return toBaselineNode(nodes.get(rootId), nodes);
    }

    private static BaselineNode toBaselineNode(
            SemanticNode node, Map<String, SemanticNode> nodes) {
        var children = new java.util.ArrayList<BaselineNode>();
        for (String childId : node.childIds()) {
            SemanticNode child = nodes.get(childId);
            if (child != null) {
                children.add(toBaselineNode(child, nodes));
            }
        }
        var state = node.state();
        return new BaselineNode(
                node.role(),
                node.accessibleName(),
                node.text(),
                node.label(),
                node.testId(),
                node.actorName(),
                node.actorType(),
                state.visible(),
                state.enabled().orElse(null),
                state.checked().orElse(null),
                state.selected().orElse(null),
                state.expanded().orElse(null),
                state.editable().orElse(null),
                state.focused(),
                state.focusable(),
                node.stageBounds(),
                null,
                node.properties(),
                children);
    }

    private static SemanticComparePolicy toCorePolicy(Command.SemanticCompareSpec spec) {
        List<PositionalTolerance> tolerances = spec.tolerances().stream()
                .map(tolerance -> new PositionalTolerance(
                        tolerance.id(),
                        dev.gdx.uiharness.core.typography.CoordinateSpace.valueOf(
                                tolerance.space().toUpperCase(java.util.Locale.ROOT)),
                        tolerance.units(),
                        tolerance.deltaX(),
                        tolerance.deltaY(),
                        tolerance.deltaWidth(),
                        tolerance.deltaHeight()))
                .toList();
        return new SemanticComparePolicy(
                tolerances,
                Set.copyOf(spec.excludedProperties()),
                spec.maxDifferences(),
                16_384);
    }

    private static MatrixDefinition toCoreMatrixDefinition(
            Command.MatrixRunSpec spec, Deadline deadline) {
        List<MatrixWindow> windows = spec.windows().stream()
                .map(window -> new MatrixWindow(window.width(), window.height()))
                .toList();
        List<MatrixHiDpi> hiDpiModes = spec.hiDpiModes().stream()
                .map(mode -> MatrixHiDpi.valueOf(
                        mode.toUpperCase(java.util.Locale.ROOT).replace('-', '_')))
                .toList();
        List<dev.gdx.uiharness.core.assertion.AssertionRequest> assertions =
                spec.assertions().stream()
                        .map(assertion -> new dev.gdx.uiharness.core.assertion.AssertionRequest(
                                dev.gdx.uiharness.core.assertion.AssertionRequest.SCHEMA_VERSION,
                                assertion.locator().toCore(),
                                assertion.assertion().toCore(),
                                deadline))
                        .toList();
        return new MatrixDefinition(
                MatrixDefinition.SCHEMA_VERSION,
                spec.scenarioId(),
                windows,
                spec.uiScales(),
                spec.devicePixelRatios(),
                hiDpiModes,
                spec.locales(),
                spec.fontSetIds(),
                assertions);
    }

    private static LayoutValidationConfig toCoreLayoutConfig(
            Command.LayoutValidationSpec spec) {
        java.util.EnumSet<LayoutValidationCheck> checks = java.util.EnumSet.noneOf(
                LayoutValidationCheck.class);
        for (String check : spec.enabledChecks()) {
            checks.add(LayoutValidationCheck.valueOf(
                    check.toUpperCase(java.util.Locale.ROOT).replace('-', '_')));
        }
        return new LayoutValidationConfig(
                checks,
                spec.minTargetWidth(),
                spec.minTargetHeight(),
                spec.maxAlignmentDelta(),
                spec.minSpacing(),
                LayoutValidationSeverity.valueOf(
                        spec.failOn().toUpperCase(java.util.Locale.ROOT)),
                spec.maxFindings(),
                spec.maxNodes());
    }

    private static dev.gdx.uiharness.core.navigation.NavigationRequest toCoreNavigationRequest(
            Command.NavigationSpec spec, Deadline deadline) {
        List<dev.gdx.uiharness.core.navigation.NavigationStep> steps = new ArrayList<>();
        for (int index = 0; index < spec.inputs().size(); index++) {
            dev.gdx.uiharness.core.navigation.NavigationInput input =
                    dev.gdx.uiharness.core.navigation.NavigationInput.valueOf(
                            spec.inputs().get(index).toUpperCase(java.util.Locale.ROOT)
                                    .replace('-', '_'));
            steps.add(new dev.gdx.uiharness.core.navigation.NavigationStep(
                    input, index + 1L, index + 1L, index + 2L, index + 2L,
                    "state:no-focus", "state:no-focus", null));
        }
        return new dev.gdx.uiharness.core.navigation.NavigationRequest(
                1, steps, List.of(), spec.startFocus(), null, spec.controllerSupported(),
                false, spec.maxSteps(), spec.maxActors(), spec.maxResultBytes(),
                spec.maxEvidenceBytes(), deadline.timeout());
    }

    /** Closes every resource in dependency order and removes its server-owned directories. */
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        RuntimeException failure = null;
        CompletionStage<Void> gestureStop = gestureRunner.stop();
        for (int attempt = 0; attempt <= 16
                && !gestureStop.toCompletableFuture().isDone(); attempt++) {
            scheduler.drain();
        }
        if (!gestureStop.toCompletableFuture().isDone()) {
            failure = append(failure,
                    new IllegalStateException("keyboard gesture cleanup did not terminate"));
        } else {
            try {
                gestureStop.toCompletableFuture().join();
            } catch (CompletionException stopFailure) {
                failure = append(failure,
                        new IllegalStateException("keyboard gesture cleanup failed",
                                stopFailure.getCause()));
            }
        }
        if (gestureKeyHeld.get()) {
            failure = append(failure,
                    new IllegalStateException("keyboard gesture key remained held at shutdown"));
        }
        failure = closeResource(server, failure);
        failure = closeResource(waits, failure);
        failure = closeResource(capture, failure);
        failure = closeResource(gestureRunner, failure);
        failure = closeResource(tickCoordinator, failure);
        failure = closeResource(fence, failure);
        failure = closeResource(replacementCoordinator, failure);
        failure = closeResource(agentRuntime, failure);
        failure = closeResource(sceneHarness, failure);
        failure = closeResource(sceneSession, failure);
        failure = closeResource(scheduler, failure);
        failure = closeResource(clock, failure);
        failure = closeResource(traces, failure);
        failure = closeResource(publisher, failure);
        failure = closeResource(artifactStore, failure);
        failure = closeResource(protocolExecutor, failure);
        failure = closeResource(scenarioDeadlines, failure);
        failure = closeResource(replacementExecutor, failure);
        failure = closeResource(terminationExecutor, failure);
        failure = deleteOwnedDirectories(failure);
        if (terminationTask != null && !terminationTask.isDone()) {
            failure = append(failure,
                    new IllegalStateException("MCP termination virtual thread did not stop"));
        }
        if (!protocolExecutor.isTerminated() || !terminationExecutor.isTerminated()
                || !scenarioDeadlines.isTerminated() || !replacementExecutor.isTerminated()) {
            failure = append(failure,
                    new IllegalStateException("fixture executors did not terminate"));
        }
        if (failure != null) {
            throw failure;
        }
    }

    private CompletionStage<HarnessResponse.ScenarioStartOutcome> startScenario(
            ScenarioRequest request) {
        if (!scenarios.require(request.scenarioId()).definition()
                .applicationId().equals(APPLICATION_ID)) {
            return CompletableFuture.completedFuture(
                    new HarnessResponse.ScenarioStartOutcome.Rejected(
                            "incompatible-scenario"));
        }
        CompletableFuture<RegisteredLaunchCoordinator.HandoffOutcome> source =
                launchCoordinator.restart(request).toCompletableFuture();
        CompletableFuture<HarnessResponse.ScenarioStartOutcome> mapped =
                new CompletableFuture<>() {
                    @Override public boolean cancel(boolean mayInterruptIfRunning) {
                        return source.cancel(mayInterruptIfRunning)
                                && super.cancel(mayInterruptIfRunning);
                    }
                };
        source.whenComplete((outcome, failure) -> {
            if (failure != null) {
                mapped.completeExceptionally(failure);
            } else if (outcome instanceof RegisteredLaunchCoordinator.HandoffResult handoff) {
                mapped.complete(new HarnessResponse.ScenarioStartOutcome.Completed(
                        handoff.scenario(), handoff.reconnectIdentity()));
            } else if (outcome instanceof RegisteredLaunchCoordinator.HandoffFailure handoffFailure) {
                mapped.complete(mapHandoffFailure(handoffFailure));
            }
        });
        return mapped;
    }

    static HarnessResponse.ScenarioStartOutcome mapHandoffFailure(
            RegisteredLaunchCoordinator.HandoffFailure failure) {
        return switch (failure) {
            case UNKNOWN_PROFILE ->
                    new HarnessResponse.ScenarioStartOutcome.Rejected("unsupported-profile");
            case INCOMPATIBLE_APPLICATION ->
                    new HarnessResponse.ScenarioStartOutcome.Rejected("incompatible-scenario");
            case DEADLINE -> new HarnessResponse.ScenarioStartOutcome.Failed("deadline");
            case CANCELLED -> new HarnessResponse.ScenarioStartOutcome.Failed("cancelled");
        };
    }


    private static ScenarioDefinition scenario(String id, String applicationId) {
        return scenario(id, applicationId, Duration.ofSeconds(5));
    }

    private static ScenarioDefinition scenario(
            String id, String applicationId, Duration maxDuration) {
        return new ScenarioDefinition(
                ScenarioDefinition.SCHEMA_VERSION,
                id,
                "1",
                applicationId,
                List.of(RESTART_PROFILE.id()),
                1,
                maxDuration);
    }


    private static final class ReferenceScenarioLifecycle implements ScenarioLifecycle {
        private final Stage stage;
        private final ReferenceUiModel uiModel;
        private final AtomicBoolean withholdScenarioFrames;
        private final IdentityHashMap<ScenarioRequest, Integer> readiness = new IdentityHashMap<>();

        ReferenceScenarioLifecycle(Stage stage, ReferenceUiModel uiModel,
                AtomicBoolean withholdScenarioFrames) {
            this.stage = stage;
            this.uiModel = uiModel;
            this.withholdScenarioFrames = withholdScenarioFrames;
        }

        @Override public void setup(ScenarioRequest request) {
            readiness.put(request, 0);
            withholdScenarioFrames.set(Boolean.parseBoolean(
                    request.configuration().getOrDefault("withholdCompletedFrames", "false")));
        }

        @Override public void reset(ScenarioRequest request) {
            textField("username").setText("");
            uiModel.setUsername("");
            textField("password").setText("");
            stage.unfocusAll();
            if ("navigation".equals(request.scenarioId())) {
                com.badlogic.gdx.scenes.scene2d.Actor focus = firstFocusable();
                if (focus != null) {
                    stage.setKeyboardFocus(focus);
                }
            }
        }

        private com.badlogic.gdx.scenes.scene2d.Actor firstFocusable() {
            return firstFocusable(stage.getActors());
        }

        private com.badlogic.gdx.scenes.scene2d.Actor firstFocusable(
                Iterable<com.badlogic.gdx.scenes.scene2d.Actor> actors) {
            for (com.badlogic.gdx.scenes.scene2d.Actor actor : actors) {
                if (actor instanceof com.badlogic.gdx.scenes.scene2d.ui.Button
                        || actor instanceof com.badlogic.gdx.scenes.scene2d.ui.TextField) {
                    return actor;
                }
                if (actor instanceof com.badlogic.gdx.scenes.scene2d.Group group) {
                    com.badlogic.gdx.scenes.scene2d.Actor nested = firstFocusable(group.getChildren());
                    if (nested != null) {
                        return nested;
                    }
                }
            }
            return null;
        }

        @Override public boolean ready(ScenarioRequest request) {
            int completedFrames = readiness.compute(
                    request, (ignored, current) -> current == null ? 1 : current + 1);
            return !"never-ready".equals(request.scenarioId()) && completedFrames >= 2;
        }

        @Override public String startStateIdentity(
                ScenarioRequest request, SemanticSnapshot snapshot) {
            return request.scenarioId() + ":" + request.seed() + ":"
                    + request.configuration().getOrDefault("mode", "default");
        }

        @Override public void cleanup(ScenarioRequest request) {
            readiness.remove(request);
            withholdScenarioFrames.set(false);
            textField("password").setText(request.scenarioId() + ":cleaned");
        }

        private TextField textField(String name) {
            return (TextField) Objects.requireNonNull(
                    stage.getRoot().findActor(name), "fixture actor " + name);
        }
    }

    private void createOwnedDirectories() {
        try {
            createOwnerOnlyDirectory(processRoot);
            createOwnerOnlyDirectory(artifactRoot);
            createOwnerOnlyDirectory(traceRoot);
            createOwnerOnlyDirectory(proofRoot);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Unable to create fixture directories", failure);
        }
    }

    /**
     * Creates (or verifies) one fixture-owned directory with exact owner-only
     * permissions so recorder roots satisfy the owner-only storage contract.
     */
    private static void createOwnerOnlyDirectory(Path directory) throws IOException {
        Files.createDirectories(directory);
        if (java.nio.file.FileSystems.getDefault()
                .supportedFileAttributeViews().contains("posix")) {
            Set<java.nio.file.attribute.PosixFilePermission> ownerOnly = Set.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                    java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(directory, ownerOnly);
            if (!Files.getPosixFilePermissions(directory).equals(ownerOnly)) {
                throw new IOException("fixture directory is not owner-only: " + directory);
            }
        } else if (java.nio.file.FileSystems.getDefault()
                .supportedFileAttributeViews().contains("acl")) {
            java.nio.file.attribute.AclFileAttributeView view = Files.getFileAttributeView(
                    directory, java.nio.file.attribute.AclFileAttributeView.class);
            if (view == null) {
                throw new IOException("acl view unavailable for fixture directory: "
                        + directory);
            }
            java.nio.file.attribute.UserPrincipal owner = view.getOwner();
            view.setAcl(java.util.List.of(java.nio.file.attribute.AclEntry.newBuilder()
                    .setType(java.nio.file.attribute.AclEntryType.ALLOW)
                    .setPrincipal(owner)
                    .setPermissions(java.util.EnumSet.allOf(
                            java.nio.file.attribute.AclEntryPermission.class))
                    .build()));
            java.util.List<java.nio.file.attribute.AclEntry> acl = view.getAcl();
            if (acl.size() != 1 || !acl.get(0).principal().equals(owner)) {
                throw new IOException("fixture directory ACL is not owner-only: "
                        + directory);
            }
        }
    }



    private SemanticSnapshot snapshotForWait() {
        SemanticSnapshot snapshot;
        if (scheduler.isOwnerThread()) {
            snapshot = sceneSession.snapshot(clock.revision(), clock.frame());
        } else {
            snapshot = scheduler.submit(
                    () -> sceneSession.snapshot(clock.revision(), clock.frame()),
                    Deadline.after(clock, Duration.ofSeconds(30)))
                    .toCompletableFuture().join();
        }
        traces.snapshot(snapshot, "wait");
        return snapshot;
    }

    private VisualReference reference() {
        byte[] png;
        try (InputStream input = FixtureControl.class.getResourceAsStream(
                "/reference-ui/reference-screen.png")) {
            if (input == null) {
                throw new IllegalStateException("Reference screenshot resource is missing");
            }
            png = input.readAllBytes();
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to read reference screenshot", failure);
        }
        return new VisualReference(
                REFERENCE_ID, APPLICATION_ID, SESSION_ID, VIEWPORT_ID,
                png, sha256(png), 1280, 720, new CapturedImage.Scale(1, 1),
                Instant.EPOCH, null, null);
    }

    private TypographyReference typographyReference(byte[] png) {
        String hash = sha256(png);
        Map<String, Double> residuals = new java.util.LinkedHashMap<>();
        for (String controlId : typographyControlIds) {
            residuals.put(controlId, 0.0);
        }
        List<TypographyObservation> observations = sceneSession.typography(
                0,
                0,
                new TypographyCaptureContext(
                        APPLICATION_ID,
                        VIEWPORT_ID,
                        "reference:" + TYPOGRAPHY_REFERENCE_ID,
                        hash,
                        1280,
                        720,
                        1280,
                        720,
                        residuals));
        List<TypographyControlReference> controls = observations.stream()
                .map(observation -> new TypographyControlReference(
                        observation.controlId(),
                        observation.font().sourceId().value(),
                        observation.font().nominalSize().value(),
                        observation.font().generatedGlyphSize().value(),
                        observation.font().bitmapScaleX(),
                        observation.font().bitmapScaleY(),
                        observation.font().minificationFilter().value(),
                        observation.font().magnificationFilter().value(),
                        observation.display().deviceScaleX(),
                        observation.display().deviceScaleY(),
                        observation.font().weight(),
                        observation.font().letterSpacing(),
                        observation.geometry().inkBounds(CoordinateSpace.FRAMEBUFFER),
                        observation.geometry().baseline(CoordinateSpace.FRAMEBUFFER).y(),
                        1,
                        0.5,
                        0.5001,
                        0.75,
                        observation.transformSha256()))
                .toList();
        return new TypographyReference(
                TYPOGRAPHY_REFERENCE_ID,
                APPLICATION_ID,
                VIEWPORT_ID,
                "reference:" + TYPOGRAPHY_REFERENCE_ID,
                png,
                hash,
                1280,
                720,
                new CapturedImage.Scale(1, 1),
                controls);
    }

    private CompletionStage<List<TypographyObservation>> typographyEvidence(
            TypographyReference reference,
            CapturedImage current,
            Deadline deadline) {
        String artifactId = "capture:" + current.sha256();
        Map<String, Double> zeroResiduals = reference.controls().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        TypographyControlReference::controlId, ignored -> 0.0));
        return scheduler.submit(
                        () -> sceneSession.typography(
                                current.revision(),
                                current.frame(),
                                new TypographyCaptureContext(
                                        APPLICATION_ID,
                                        VIEWPORT_ID,
                                        artifactId,
                                        current.sha256(),
                                        Math.toIntExact(Math.round(
                                                current.width() / current.scale().x())),
                                        Math.toIntExact(Math.round(
                                                current.height() / current.scale().y())),
                                        current.width(),
                                        current.height(),
                                        zeroResiduals)),
                        deadline)
                .thenCompose(preliminary -> {
                    Map<String, TypographyControlReference> expected =
                            reference.controlsById();
                    Lwjgl3TypographyRasterComparator comparator =
                            new Lwjgl3TypographyRasterComparator();
                    Map<String, Double> residuals = preliminary.stream()
                            .collect(java.util.stream.Collectors.toUnmodifiableMap(
                                    TypographyObservation::controlId,
                                    observation -> comparator.meanAbsoluteError(
                                            reference.pngBytes(),
                                            current.pngBytes(),
                                            current.width(),
                                            current.height(),
                                            expected.get(observation.controlId())
                                                    .expectedInkBounds(),
                                            observation.geometry().inkBounds(
                                                    CoordinateSpace.FRAMEBUFFER))));
                    return scheduler.submit(
                            () -> sceneSession.typography(
                                    current.revision(),
                                    current.frame(),
                                    new TypographyCaptureContext(
                                            APPLICATION_ID,
                                            VIEWPORT_ID,
                                        artifactId,
                                        current.sha256(),
                                        Math.toIntExact(Math.round(
                                                current.width() / current.scale().x())),
                                        Math.toIntExact(Math.round(
                                                current.height() / current.scale().y())),
                                        current.width(),
                                            current.height(),
                                            residuals)),
                            deadline);
                });
    }

    private LayoutReference layoutReference(byte[] png) {
        String hash = sha256(png);
        List<LayoutObservation> observations = sceneSession.layout(
                0,
                0,
                new LayoutCaptureContext(
                        APPLICATION_ID,
                        VIEWPORT_ID,
                        "reference:" + LAYOUT_REFERENCE_ID,
                        hash,
                        1280,
                        720,
                        1280,
                        720,
                        0,
                        layoutControlIds));
        List<LayoutControlReference> controls = observations.stream()
                .map(observation -> new LayoutControlReference(
                        observation.controlId(),
                        observation.parentActorId(),
                        observation.layoutOwnerId(),
                        observation.scrollOwnerId(),
                        observation.observedClipOwnerId(),
                        observation.layoutRole(),
                        observation.bounds(),
                        observation.visibleIntersection(),
                        observation.padding(),
                        0,
                        0,
                        null,
                        observation.layoutSha256()))
                .toList();
        return new LayoutReference(
                LAYOUT_REFERENCE_ID,
                APPLICATION_ID,
                VIEWPORT_ID,
                "reference:" + LAYOUT_REFERENCE_ID,
                controls);
    }

    private CompletionStage<LayoutEvidence> layoutEvidence(
            LayoutReference reference,
            CapturedImage current,
            Deadline deadline,
            ScreenCapture diagnosticCapture) {
        Set<String> controlIds = reference.controlsById().keySet();
        CompletionStage<List<LayoutObservation>> observations = scheduler.submit(
                () -> layoutObservations(current, controlIds),
                deadline);
        return observations.thenCompose(currentObservations -> {
            List<LayoutStabilitySample> samples = new ArrayList<>();
            CompletionStage<Void> chain = CompletableFuture.completedFuture(null);
            for (int index = 0; index < 8; index++) {
                chain = chain.thenCompose(ignored ->
                        diagnosticCapture.capture(CaptureRequest.fullWindow(), deadline)
                                .thenCompose(image -> scheduler.submit(
                                        () -> {
                                            List<LayoutObservation> observed =
                                                    layoutObservations(image, controlIds);
                                            samples.add(layoutSample(image, observed));
                                            return null;
                                        },
                                        deadline)));
            }
            return chain.thenApply(ignored -> {
                LayoutQuiescenceEvaluator evaluator = new LayoutQuiescenceEvaluator();
                LayoutQuiescencePolicy policy = LayoutQuiescencePolicy.issueFour();
                return new LayoutEvidence(
                        currentObservations,
                        evaluator.evaluate(
                                samples.subList(0, 3), deadline.elapsed(), policy),
                        evaluator.verifyCaptures(
                                samples.subList(3, 8), deadline.elapsed(), policy));
            });
        });
    }

    private List<LayoutObservation> layoutObservations(
            CapturedImage image, Set<String> controlIds) {
        return sceneSession.layout(
                image.revision(),
                image.frame(),
                new LayoutCaptureContext(
                        APPLICATION_ID,
                        VIEWPORT_ID,
                        "capture:" + image.sha256(),
                        image.sha256(),
                        Math.toIntExact(Math.round(image.width() / image.scale().x())),
                        Math.toIntExact(Math.round(image.height() / image.scale().y())),
                        image.width(),
                        image.height(),
                        0,
                        controlIds));
    }

    private static LayoutStabilitySample layoutSample(
            CapturedImage image, List<LayoutObservation> observations) {
        LayoutObservation scrolling = observations.stream()
                .filter(value -> value.scrollOwnerId() != null)
                .findFirst()
                .orElse(observations.getFirst());
        return new LayoutStabilitySample(
                image.frame(),
                image.revision(),
                scrolling.layoutRevision(),
                scrolling.scroll().x(),
                scrolling.scroll().y(),
                scrolling.scroll().maxX(),
                scrolling.scroll().maxY(),
                sha256(scrolling.scroll().viewportBounds().toString()
                        .getBytes(StandardCharsets.UTF_8)),
                sha256(scrolling.scroll().contentBounds().toString()
                        .getBytes(StandardCharsets.UTF_8)),
                sha256(scrolling.clipChain().toString()
                        .getBytes(StandardCharsets.UTF_8)),
                sha256(observations.stream()
                        .map(LayoutObservation::layoutSha256)
                        .collect(java.util.stream.Collectors.joining("|"))
                        .getBytes(StandardCharsets.UTF_8)),
                image.sha256(),
                scrolling.scroll().active());
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK lacks SHA-256", impossible);
        }
    }
    private RuntimeException deleteOwnedDirectories(RuntimeException failure) {
        try {
            Files.deleteIfExists(traceRoot);
            Files.deleteIfExists(proofRoot);
            Files.deleteIfExists(artifactRoot);
            Files.deleteIfExists(processRoot);
        } catch (IOException deletionFailure) {
            return append(failure,
                    new IllegalStateException("Fixture directories were not empty", deletionFailure));
        }
        return failure;
    }

    private static RuntimeException closeResource(
            AutoCloseable resource, RuntimeException accumulated) {
        if (resource == null) {
            return accumulated;
        }
        try {
            resource.close();
            return accumulated;
        } catch (Exception closeFailure) {
            RuntimeException wrapped = closeFailure instanceof RuntimeException runtime
                    ? runtime : new IllegalStateException("Resource close failed", closeFailure);
            return append(accumulated, wrapped);
        }
    }

    private static RuntimeException append(
            RuntimeException accumulated, RuntimeException failure) {
        if (accumulated == null) {
            return failure;
        }
        accumulated.addSuppressed(failure);
        return accumulated;
    }

    private static final class StorePublisher
            implements ArtifactReference.Publisher, AutoCloseable {
        private final FileArtifactStore store;
        private final Path proofRoot;
        private final List<Path> receipts = new ArrayList<>();

        StorePublisher(FileArtifactStore store, Path proofRoot) {
            this.store = store;
            this.proofRoot = proofRoot;
        }

        @Override public synchronized ArtifactReference publish(
                String mediaType, byte[] content) {
            ArtifactMediaType type = ArtifactMediaType.fromValue(mediaType);
            ArtifactId id = store.put(SESSION_ID, type, content,
                    Instant.now().plus(ARTIFACT_LIFETIME));
            ArtifactStore.Metadata metadata = store.metadata(SESSION_ID, id);
            ArtifactReference reference = new ArtifactReference(
                    "artifact:" + id.value(), mediaType, metadata.size(), metadata.sha256());
            Path receipt = proofRoot.resolve(id.value() + ".receipt");
            String proof = reference.reference() + "\n"
                    + reference.mediaType() + "\n"
                    + reference.byteLength() + "\n"
                    + reference.sha256() + "\n";
            try {
                Files.writeString(receipt, proof, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (IOException failure) {
                throw new IllegalStateException("Unable to publish artifact proof receipt", failure);
            }
            receipts.add(receipt);
            return reference;
        }

        @Override public synchronized void close() throws IOException {
            IOException failure = null;
            for (Path receipt : receipts) {
                try {
                    Files.deleteIfExists(receipt);
                } catch (IOException deletionFailure) {
                    if (failure == null) {
                        failure = deletionFailure;
                    } else {
                        failure.addSuppressed(deletionFailure);
                    }
                }
            }
            receipts.clear();
            if (failure != null) {
                throw failure;
            }
        }
    }

    static class ReferenceTraceController
            implements HarnessProtocolService.TraceController, AutoCloseable {
        private final TraceRecorder recorder;
        private final ArtifactReference.Publisher publisher;
        private final LocatorEngine locators;
        private final SemanticObservationStore observationStore =
                new SemanticObservationStore(256);
        private final AtomicLong operationSequence = new AtomicLong();
        private String traceId;
        private boolean active;

        ReferenceTraceController(
                Path root, ArtifactReference.Publisher publisher) {
            this(root, publisher, new StrictResolution());
        }

        ReferenceTraceController(
                Path root, ArtifactReference.Publisher publisher, LocatorEngine locators) {
            recorder = new TraceRecorder(root, Clock.systemUTC());
            this.publisher = publisher;
            this.locators = locators;
        }

        @Override public synchronized CompletionStage<TransitionQueryResult> query(
                TransitionQuery query, Deadline deadline) {
            if (traceId == null || !traceId.equals(query.traceId())) {
                return CompletableFuture.failedFuture(new HarnessException(
                        ErrorCode.NOT_FOUND,
                        "No retained observations for trace " + query.traceId(),
                        ErrorEvidence.empty()));
            }
            List<SemanticObservation> observations =
                    observationStore.observations(query.traceId());
            return CompletableFuture.completedFuture(
                    new TransitionProjector().query(observations, query, locators));
        }

        @Override public synchronized CompletionStage<HarnessResponse.Result.TraceStarted> start(
                Command.TraceStart command, Deadline deadline) {
            if (active) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("a trace is already active"));
            }
            traceId = "trace-" + Long.toUnsignedString(operationSequence.incrementAndGet());
            recorder.start(SESSION_ID, new TraceRecorder.Limits(
                    command.maxBytes(), 10_000, Duration.ofMillis(command.maxDurationMillis())));
            active = true;
            record(new TraceEvent(-1, TraceEvent.Kind.LOG, SESSION_ID, "trace-start",
                    deadline.clock().nanoTime(), null, null, null,
                    Map.of("event", "trace-start")));
            return CompletableFuture.completedFuture(
                    new HarnessResponse.Result.TraceStarted(traceId));
        }

        @Override public synchronized CompletionStage<HarnessResponse.Result.TraceStopped> stop(
                Deadline deadline) {
            if (!active) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("no trace is active"));
            }
            record(new TraceEvent(-1, TraceEvent.Kind.LOG, SESSION_ID, "trace-stop",
                    deadline.clock().nanoTime(), null, null, null,
                    Map.of("event", "trace-stop")));
            TraceManifest manifest = recorder.stop();
            active = false;
            byte[] archive = recorder.consumeArchive(manifest);
            ArtifactReference reference = publisher.publish("application/zip", archive);
            if (!reference.mediaType().equals("application/zip")
                    || reference.byteLength() != archive.length
                    || !reference.sha256().equals(manifest.archiveSha256())) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "Artifact publisher receipt does not match verified trace archive"));
            }
            return CompletableFuture.completedFuture(
                    new HarnessResponse.Result.TraceStopped(traceId, reference.reference(),
                            manifest.eventCount(), reference.byteLength(),
                            manifest.archiveSha256()));
        }

        synchronized TraceSpan commandStarted(String operation, SemanticSnapshot before) {
            if (!active) {
                return null;
            }
            String requestId = "fixture-" + operation.toLowerCase(Locale.ROOT) + "-"
                    + operationSequence.incrementAndGet();
            long sequence = recorder.record(TraceEvent.commandStarted(
                    SESSION_ID, requestId, logicalTime(before), before,
                    Map.of("operation", operation)));
            observationStore.retain(traceId, new SemanticObservation(
                    sequence, before.frame(), before.revision(), before, null));
            return new TraceSpan(sequence, requestId);
        }

        synchronized void commandCompleted(
                String operation, SemanticSnapshot after, TraceSpan span) {
            if (!active || span == null) {
                return;
            }
            long sequence = recorder.record(TraceEvent.commandCompleted(
                    SESSION_ID, span.requestId(), logicalTime(after), after,
                    span.sequence(), Map.of("operation", operation)));
            observationStore.retain(traceId, new SemanticObservation(
                    sequence, after.frame(), after.revision(), after, span.sequence()));
        }

        synchronized void commandFailed(String operation, SemanticSnapshot before,
                Deadline deadline, TraceSpan span) {
            if (!active || span == null) {
                return;
            }
            record(new TraceEvent(-1, TraceEvent.Kind.COMMAND_FAILED, SESSION_ID,
                    span.requestId(),
                    Math.max(logicalTime(before), deadline.clock().nanoTime()),
                    before.frame(), before.revision(), span.sequence(),
                    Map.of("operation", operation)));
        }

        synchronized void snapshot(SemanticSnapshot snapshot, String operation) {
            if (!active) {
                return;
            }
            record(new TraceEvent(-1, TraceEvent.Kind.SNAPSHOT, SESSION_ID,
                    "fixture-" + operation + "-" + operationSequence.incrementAndGet(),
                    logicalTime(snapshot), snapshot.frame(), snapshot.revision(), null,
                    Map.of(
                            "operation", operation,
                            "nodeCount", Integer.toString(snapshot.nodes().size()))));
        }

        synchronized void gesture(TraceEvent event) {
            if (active) {
                record(event);
            }
        }

        synchronized TraceSpan captureStarted(Deadline deadline) {
            if (!active) {
                return null;
            }
            String requestId = "fixture-screenshot-" + operationSequence.incrementAndGet();
            long sequence = record(new TraceEvent(
                    -1, TraceEvent.Kind.COMMAND_STARTED, SESSION_ID, requestId,
                    deadline.clock().nanoTime(), null, null, null,
                    Map.of("operation", "screenshot")));
            return new TraceSpan(sequence, requestId);
        }

        synchronized void captureCompleted(CapturedImage image, TraceSpan span) {
            if (!active || span == null) {
                return;
            }
            record(new TraceEvent(-1, TraceEvent.Kind.COMMAND_COMPLETED, SESSION_ID,
                    span.requestId(), Math.multiplyExact(image.frame(), FIXED_STEP.toNanos()),
                    image.frame(), image.revision(), span.sequence(),
                    Map.of("operation", "screenshot", "sha256", image.sha256())));
        }

        synchronized void captureFailed(Deadline deadline, TraceSpan span) {
            if (!active || span == null) {
                return;
            }
            record(new TraceEvent(-1, TraceEvent.Kind.COMMAND_FAILED, SESSION_ID,
                    span.requestId(), deadline.clock().nanoTime(), null, null, span.sequence(),
                    Map.of("operation", "screenshot")));
        }

        private long record(TraceEvent event) {
            return recorder.record(event);
        }

        private static long logicalTime(SemanticSnapshot snapshot) {
            return Math.multiplyExact(snapshot.frame(), FIXED_STEP.toNanos());
        }

        record TraceSpan(long sequence, String requestId) {}

        @Override public synchronized void close() {
            active = false;
            recorder.close();
        }
    }

    static final class TracingHarness implements Harness {
        private final Harness delegate;
        private final ReferenceTraceController traces;

        TracingHarness(Harness delegate, ReferenceTraceController traces) {
            this.delegate = delegate;
            this.traces = traces;
        }

        @Override public CompletionStage<ActionResult> perform(
                Locator locator, Action action, Deadline deadline) {
            return new TracedAction(locator, action, deadline);
        }

        private final class TracedAction extends CompletableFuture<ActionResult> {
            private final Object lifecycle = new Object();
            private final Locator locator;
            private final Action action;
            private final Deadline deadline;
            private final String operation;
            private CompletableFuture<?> current;
            private SemanticSnapshot before;
            private ReferenceTraceController.TraceSpan span;
            private ActionResult actionResult;
            private Phase phase = Phase.BEFORE;
            private boolean cancelling;

            TracedAction(Locator locator, Action action, Deadline deadline) {
                this.locator = locator;
                this.action = action;
                this.deadline = deadline;
                operation = action.getClass().getSimpleName();
                start();
            }

            private void start() {
                CompletionStage<SemanticSnapshot> snapshot;
                try {
                    snapshot = delegate.snapshot(deadline);
                } catch (Throwable failure) {
                    phase = Phase.TERMINAL;
                    super.completeExceptionally(failure);
                    return;
                }
                CompletableFuture<SemanticSnapshot> source = snapshot.toCompletableFuture();
                synchronized (lifecycle) {
                    current = source;
                }
                source.whenComplete(this::beforeCompleted);
            }

            private void beforeCompleted(
                    SemanticSnapshot snapshot, Throwable snapshotFailure) {
                CompletableFuture<ActionResult> source;
                synchronized (lifecycle) {
                    if (phase != Phase.BEFORE || isDone() || cancelling) {
                        return;
                    }
                    if (snapshotFailure != null) {
                        phase = Phase.TERMINAL;
                        super.completeExceptionally(unwrap(snapshotFailure));
                        return;
                    }
                    before = snapshot;
                    try {
                        span = traces.commandStarted(operation, before);
                        source = delegate.perform(locator, action, deadline)
                                .toCompletableFuture();
                    } catch (Throwable actionFailure) {
                        failAction(unwrap(actionFailure));
                        return;
                    }
                    phase = Phase.ACTION;
                    current = source;
                }
                source.whenComplete(this::actionCompleted);
            }

            private void actionCompleted(ActionResult result, Throwable actionFailure) {
                CompletableFuture<SemanticSnapshot> source;
                synchronized (lifecycle) {
                    if (phase != Phase.ACTION || isDone() || cancelling) {
                        return;
                    }
                    if (actionFailure != null) {
                        failAction(unwrap(actionFailure));
                        return;
                    }
                    actionResult = result;
                    try {
                        source = delegate.snapshot(deadline).toCompletableFuture();
                    } catch (Throwable snapshotFailure) {
                        failAction(unwrap(snapshotFailure));
                        return;
                    }
                    phase = Phase.AFTER;
                    current = source;
                }
                source.whenComplete(this::afterCompleted);
            }

            private void afterCompleted(
                    SemanticSnapshot after, Throwable snapshotFailure) {
                synchronized (lifecycle) {
                    if (phase != Phase.AFTER || isDone()) {
                        return;
                    }
                    if (snapshotFailure != null) {
                        failAction(unwrap(snapshotFailure));
                        return;
                    }
                    try {
                        traces.commandCompleted(operation, after, span);
                    } catch (Throwable recorderFailure) {
                        phase = Phase.TERMINAL;
                        super.completeExceptionally(recorderFailure);
                        return;
                    }
                    phase = Phase.TERMINAL;
                    super.complete(actionResult);
                }
            }

            private void failAction(Throwable actionFailure) {
                try {
                    traces.commandFailed(operation, before, deadline, span);
                } catch (Throwable recorderFailure) {
                    if (recorderFailure != actionFailure) {
                        actionFailure.addSuppressed(recorderFailure);
                    }
                }
                phase = Phase.TERMINAL;
                super.completeExceptionally(actionFailure);
            }

            @Override public boolean cancel(boolean mayInterruptIfRunning) {
                synchronized (lifecycle) {
                    if (phase == Phase.TERMINAL || isDone() || phase == Phase.AFTER) {
                        return false;
                    }
                    cancelling = true;
                    try {
                        if (phase == Phase.ACTION
                                && (current == null
                                        || !current.cancel(mayInterruptIfRunning))) {
                            return false;
                        }
                        if (phase == Phase.BEFORE && current != null) {
                            current.cancel(mayInterruptIfRunning);
                        }
                        if (phase == Phase.ACTION) {
                            try {
                                traces.commandFailed(operation, before, deadline, span);
                            } catch (Throwable ignored) {
                                // Cancellation must still release the routed operation.
                            }
                        }
                        phase = Phase.TERMINAL;
                        return super.cancel(false);
                    } finally {
                        cancelling = false;
                    }
                }
            }

            @Override public boolean complete(ActionResult result) {
                return false;
            }

            @Override public boolean completeExceptionally(Throwable failure) {
                return false;
            }

            private enum Phase {
                BEFORE,
                ACTION,
                AFTER,
                TERMINAL
            }
        }

        private static Throwable unwrap(Throwable failure) {
            Throwable current = failure;
            while (current instanceof CompletionException && current.getCause() != null) {
                current = current.getCause();
            }
            return current;
        }

        @Override public CompletionStage<SemanticSnapshot> snapshot(Deadline deadline) {
            return delegate.snapshot(deadline).thenApply(snapshot -> {
                traces.snapshot(snapshot, "snapshot-or-query");
                return snapshot;
            });
        }
    }

    static final class TracingCapture implements ScreenCapture {
        private final ScreenCapture delegate;
        private final ReferenceTraceController traces;

        TracingCapture(ScreenCapture delegate, ReferenceTraceController traces) {
            this.delegate = delegate;
            this.traces = traces;
        }

        @Override public CompletionStage<CapturedImage> capture(
                CaptureRequest request, Deadline deadline) {
            return new TracedCapture(request, deadline);
        }

        @Override public void close() {
            delegate.close();
        }

        private final class TracedCapture extends CompletableFuture<CapturedImage> {
            private final Object lifecycle = new Object();
            private final Deadline deadline;
            private final ReferenceTraceController.TraceSpan span;
            private CompletableFuture<CapturedImage> source;
            private boolean terminal;
            private boolean cancelling;

            TracedCapture(CaptureRequest request, Deadline deadline) {
                this.deadline = deadline;
                span = traces.captureStarted(deadline);
                try {
                    source = delegate.capture(request, deadline).toCompletableFuture();
                } catch (Throwable captureFailure) {
                    failCapture(TracingHarness.unwrap(captureFailure));
                    return;
                }
                source.whenComplete(this::captureCompleted);
            }

            private void captureCompleted(CapturedImage image, Throwable captureFailure) {
                synchronized (lifecycle) {
                    if (terminal || cancelling || isDone()) {
                        return;
                    }
                    if (captureFailure != null) {
                        failCapture(TracingHarness.unwrap(captureFailure));
                        return;
                    }
                    try {
                        traces.captureCompleted(image, span);
                    } catch (Throwable recorderFailure) {
                        terminal = true;
                        super.completeExceptionally(recorderFailure);
                        return;
                    }
                    terminal = true;
                    super.complete(image);
                }
            }

            private void failCapture(Throwable captureFailure) {
                try {
                    traces.captureFailed(deadline, span);
                } catch (Throwable recorderFailure) {
                    if (recorderFailure != captureFailure) {
                        captureFailure.addSuppressed(recorderFailure);
                    }
                }
                terminal = true;
                super.completeExceptionally(captureFailure);
            }

            @Override public boolean cancel(boolean mayInterruptIfRunning) {
                synchronized (lifecycle) {
                    if (terminal || isDone() || source == null) {
                        return false;
                    }
                    cancelling = true;
                    try {
                        if (!source.cancel(mayInterruptIfRunning)) {
                            return false;
                        }
                        try {
                            traces.captureFailed(deadline, span);
                        } catch (Throwable ignored) {
                            // Cancellation must still release the routed operation.
                        }
                        terminal = true;
                        return super.cancel(false);
                    } finally {
                        cancelling = false;
                    }
                }
            }

            @Override public boolean complete(CapturedImage image) {
                return false;
            }

            @Override public boolean completeExceptionally(Throwable failure) {
                return false;
            }
        }
    }
}
