package dev.gdx.uiharness.fixtures;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import dev.gdx.uiharness.core.action.Action;
import dev.gdx.uiharness.core.action.ActionResult;
import dev.gdx.uiharness.core.action.Harness;
import dev.gdx.uiharness.core.capture.CaptureRequest;
import dev.gdx.uiharness.core.capture.CapturedImage;
import dev.gdx.uiharness.core.capture.ScreenCapture;
import dev.gdx.uiharness.core.layout.LayoutControlReference;
import dev.gdx.uiharness.core.layout.LayoutEvidence;
import dev.gdx.uiharness.core.layout.LayoutObservation;
import dev.gdx.uiharness.core.layout.LayoutQuiescenceEvaluator;
import dev.gdx.uiharness.core.layout.LayoutQuiescencePolicy;
import dev.gdx.uiharness.core.layout.LayoutReference;
import dev.gdx.uiharness.core.layout.LayoutStabilitySample;
import dev.gdx.uiharness.core.visual.VisualPolicy;
import dev.gdx.uiharness.core.visual.VisualReference;
import dev.gdx.uiharness.core.typography.CoordinateSpace;
import dev.gdx.uiharness.core.typography.TypographyControlReference;
import dev.gdx.uiharness.core.typography.TypographyObservation;
import dev.gdx.uiharness.core.typography.TypographyReference;
import dev.gdx.uiharness.core.locator.Locator;
import dev.gdx.uiharness.core.locator.LocatorEngine;
import dev.gdx.uiharness.core.locator.StrictResolution;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import dev.gdx.uiharness.core.scenario.ScenarioDefinition;
import dev.gdx.uiharness.core.scenario.ScenarioLifecycle;
import dev.gdx.uiharness.core.scenario.ScenarioRegistry;
import dev.gdx.uiharness.core.scenario.ScenarioRequest;
import dev.gdx.uiharness.core.time.Deadline;
import dev.gdx.uiharness.core.trace.TraceEvent;
import dev.gdx.uiharness.core.trace.TraceManifest;
import dev.gdx.uiharness.core.trace.TraceRecorder;
import dev.gdx.uiharness.core.wait.WaitEngine;
import dev.gdx.uiharness.lwjgl3.Lwjgl3FrameFence;
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
import dev.gdx.uiharness.scene2d.Scene2dScenarioRunner;
import dev.gdx.uiharness.scene2d.Scene2dSession;
import dev.gdx.uiharness.scene2d.TypographyCaptureContext;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Owns deterministic render hooks and every cross-module resource for one fixture process. */
public final class FixtureControl implements AutoCloseable {
    /** Stable protocol session selected by all reference workflows. */
    public static final String SESSION_ID = "reference-ui";
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
            "screenshot", "snapshot", "layout", "trace", "typography", "wait");

    private final Path processRoot;
    private final Path artifactRoot;
    private final Path traceRoot;
    private final Path proofRoot;
    private final ControlledStageClock clock;
    private final RenderThreadScheduler scheduler;
    private final Scene2dSession sceneSession;
    private final Scene2dHarness sceneHarness;
    private final ScenarioRegistry scenarios;
    private final Scene2dScenarioRunner scenarioRunner;
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
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean withholdScenarioFrames = new AtomicBoolean();
    private final RegisteredLaunchCoordinator launchCoordinator;
    private HarnessMcpServer server;
    private Future<?> terminationTask;

    /** Attaches one named production protocol session to the supplied real Stage. */
    public FixtureControl(Stage stage, Path newProcessRoot) {
        Objects.requireNonNull(stage, "stage");
        processRoot = Objects.requireNonNull(newProcessRoot, "processRoot")
                .toAbsolutePath().normalize();
        artifactRoot = processRoot.resolve("artifacts");
        traceRoot = processRoot.resolve("traces");
        proofRoot = processRoot.resolve("proofs");
        createOwnedDirectories();

        clock = new ControlledStageClock(stage, FIXED_STEP);
        scheduler = new RenderThreadScheduler(128);
        sceneSession = new Scene2dSession(stage);
        sceneHarness = new Scene2dHarness(stage, stage, sceneSession, scheduler, clock,
                clock::revision, clock::frame);
        scenarios = new ScenarioRegistry();
        ReferenceScenarioLifecycle lifecycle =
                new ReferenceScenarioLifecycle(stage, withholdScenarioFrames);
        scenarios.register(scenario("reference-reset", APPLICATION_ID), lifecycle);
        scenarios.register(scenario(
                "never-ready", APPLICATION_ID, Duration.ofMillis(100)), lifecycle);
        scenarios.register(scenario("incompatible-reference", "another-application"), lifecycle);
        scenarioDeadlines = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("reference-scenario-deadline").factory());
        scenarioRunner = new Scene2dScenarioRunner(
                scenarios, scheduler, clock, (delay, signal) -> {
                    var scheduled = scenarioDeadlines.schedule(
                            signal,
                            delay.plus(Duration.ofMillis(100)).toNanos(),
                            TimeUnit.NANOSECONDS);
                    return () -> scheduled.cancel(false);
                });
        launchCoordinator = (profileId, deadline) -> {
            if (!RESTART_PROFILE.id().equals(profileId)) {
                return CompletableFuture.completedFuture(
                        RegisteredLaunchCoordinator.LaunchFailure.UNKNOWN_PROFILE);
            }
            if (deadline.isExpired()) {
                return CompletableFuture.completedFuture(
                        RegisteredLaunchCoordinator.LaunchFailure.DEADLINE);
            }
            return CompletableFuture.completedFuture(
                    new RegisteredLaunchCoordinator.LaunchResult(
                            1,
                            profileId,
                            APPLICATION_ID,
                            PROCESS_ID,
                            "reference-ui-restarted",
                            SESSION_ID,
                            "reference-ui-restarted",
                            Duration.ZERO));
        };
        fence = new Lwjgl3FrameFence(64);
        capture = new Lwjgl3ScreenCapture(fence, sceneSession::snapshot);
        LocatorEngine locators = new StrictResolution();
        artifactStore = new FileArtifactStore(artifactRoot,
                new ArtifactStore.Limits(32L * 1_024 * 1_024, 64), Clock.systemUTC());
        publisher = new StorePublisher(artifactStore, proofRoot);
        traces = new ReferenceTraceController(traceRoot, publisher);
        tracingHarness = new TracingHarness(sceneHarness, traces);
        waits = new WaitEngine(this::snapshotForWait, locators, clock, clock);
        protocolExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("reference-protocol-", 0).factory());
        terminationExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("reference-mcp-termination-", 0).factory());
    }

    /** Returns semantic metadata for actor tagging after session construction. */
    public dev.gdx.uiharness.scene2d.Semantics semantics() {
        return sceneSession.semantics();
    }

    /** Starts the production MCP server over this process's stdio streams. */
    public void startMcp(InputStream input, OutputStream output) {
        if (server != null) {
            throw new IllegalStateException("MCP server is already started");
        }
        CapabilitySet capabilities = new CapabilitySet(CAPABILITIES);
        ScreenCapture tracingCapture = new TracingCapture(capture, traces);
        HarnessProtocolService.Session session = new HarnessProtocolService.Session(
                tracingHarness, new StrictResolution(), waits, tracingCapture,
                capabilities, traces, Optional.of(scenarios),
                Optional.of(this::startScenario));
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
        if (!withholdScenarioFrames.get()) {
            sceneSession.completedFrame(scenarioRunner, clock.revision(), clock.frame());
        }
        fence.completedFrame(clock.revision(), clock.frame());
    }

    /** Closes every resource in dependency order and removes its server-owned directories. */
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        RuntimeException failure = null;
        failure = closeResource(server, failure);
        failure = closeResource(waits, failure);
        failure = closeResource(capture, failure);
        failure = closeResource(fence, failure);
        failure = closeResource(scenarioRunner, failure);
        failure = closeResource(sceneHarness, failure);
        failure = closeResource(sceneSession, failure);
        failure = closeResource(scheduler, failure);
        failure = closeResource(clock, failure);
        failure = closeResource(traces, failure);
        failure = closeResource(publisher, failure);
        failure = closeResource(artifactStore, failure);
        failure = closeResource(protocolExecutor, failure);
        failure = closeResource(scenarioDeadlines, failure);
        failure = closeResource(terminationExecutor, failure);
        failure = deleteOwnedDirectories(failure);
        if (terminationTask != null && !terminationTask.isDone()) {
            failure = append(failure,
                    new IllegalStateException("MCP termination virtual thread did not stop"));
        }
        if (!protocolExecutor.isTerminated() || !terminationExecutor.isTerminated()
                || !scenarioDeadlines.isTerminated()) {
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
        RegisteredLaunchCoordinator.LaunchOutcome outcome =
                launchCoordinator.restart(request.profileId(), request.deadline())
                        .toCompletableFuture().join();
        if (outcome instanceof RegisteredLaunchCoordinator.LaunchFailure) {
            return CompletableFuture.completedFuture(
                    new HarnessResponse.ScenarioStartOutcome.Rejected(
                            "incompatible-scenario"));
        }
        RegisteredLaunchCoordinator.LaunchResult launched =
                (RegisteredLaunchCoordinator.LaunchResult) outcome;
        CompletableFuture<dev.gdx.uiharness.core.scenario.ScenarioResult> source =
                scenarioRunner.start(
                                request,
                                launched.applicationId(),
                                launched.processId(),
                                launched.sessionId())
                        .toCompletableFuture();
        CompletableFuture<HarnessResponse.ScenarioStartOutcome> mapped =
                new CompletableFuture<>() {
                    @Override public boolean cancel(boolean mayInterruptIfRunning) {
                        return source.cancel(mayInterruptIfRunning)
                                && super.cancel(mayInterruptIfRunning);
                    }
                };
        source.whenComplete((result, failure) -> {
            if (failure != null) {
                mapped.completeExceptionally(failure);
            } else {
                mapped.complete(new HarnessResponse.ScenarioStartOutcome.Completed(result));
            }
        });
        return mapped;
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
        private final AtomicBoolean withholdScenarioFrames;
        private final IdentityHashMap<ScenarioRequest, Integer> readiness = new IdentityHashMap<>();

        ReferenceScenarioLifecycle(Stage stage, AtomicBoolean withholdScenarioFrames) {
            this.stage = stage;
            this.withholdScenarioFrames = withholdScenarioFrames;
        }

        @Override public void setup(ScenarioRequest request) {
            readiness.put(request, 0);
            withholdScenarioFrames.set(Boolean.parseBoolean(
                    request.configuration().getOrDefault("withholdCompletedFrames", "false")));
        }

        @Override public void reset(ScenarioRequest request) {
            textField("username").setText("");
            textField("password").setText("");
            stage.unfocusAll();
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
            Files.createDirectories(processRoot);
            Files.createDirectory(artifactRoot);
            Files.createDirectory(traceRoot);
            Files.createDirectory(proofRoot);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Unable to create fixture directories", failure);
        }
    }


    private SemanticSnapshot snapshotForWait() {
        SemanticSnapshot snapshot = scheduler.submit(
                () -> sceneSession.snapshot(clock.revision(), clock.frame()),
                Deadline.after(clock, Duration.ofSeconds(30)))
                .toCompletableFuture().join();
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
        Map<String, Double> residuals = Map.of(
                "harness-title", 0.0,
                "body-caption", 0.0);
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
                        Set.of("harness-title", "settings-list")));
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
        private final AtomicLong operationSequence = new AtomicLong();
        private String traceId;
        private boolean active;

        ReferenceTraceController(Path root, ArtifactReference.Publisher publisher) {
            recorder = new TraceRecorder(root, Clock.systemUTC());
            this.publisher = publisher;
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
            try {
                byte[] archive = Files.readAllBytes(manifest.archive());
                ArtifactReference reference = publisher.publish("application/zip", archive);
                Files.delete(manifest.archive());
                return CompletableFuture.completedFuture(
                        new HarnessResponse.Result.TraceStopped(traceId, reference.reference(),
                                manifest.eventCount(), reference.byteLength()));
            } catch (IOException failure) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Unable to publish trace archive", failure));
            }
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
            return new TraceSpan(sequence, requestId);
        }

        synchronized void commandCompleted(
                String operation, SemanticSnapshot after, TraceSpan span) {
            if (!active || span == null) {
                return;
            }
            recorder.record(TraceEvent.commandCompleted(
                    SESSION_ID, span.requestId(), logicalTime(after), after,
                    span.sequence(), Map.of("operation", operation)));
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
