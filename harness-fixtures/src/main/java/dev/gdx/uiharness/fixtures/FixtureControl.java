package dev.gdx.uiharness.fixtures;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.scenes.scene2d.Stage;
import dev.gdx.uiharness.core.action.Action;
import dev.gdx.uiharness.core.action.ActionResult;
import dev.gdx.uiharness.core.action.Harness;
import dev.gdx.uiharness.core.capture.CaptureRequest;
import dev.gdx.uiharness.core.capture.CapturedImage;
import dev.gdx.uiharness.core.capture.ScreenCapture;
import dev.gdx.uiharness.core.locator.Locator;
import dev.gdx.uiharness.core.locator.LocatorEngine;
import dev.gdx.uiharness.core.locator.StrictResolution;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import dev.gdx.uiharness.core.time.Deadline;
import dev.gdx.uiharness.core.trace.TraceEvent;
import dev.gdx.uiharness.core.trace.TraceManifest;
import dev.gdx.uiharness.core.trace.TraceRecorder;
import dev.gdx.uiharness.core.wait.WaitEngine;
import dev.gdx.uiharness.lwjgl3.Lwjgl3FrameFence;
import dev.gdx.uiharness.lwjgl3.Lwjgl3ScreenCapture;
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
import dev.gdx.uiharness.scene2d.ControlledStageClock;
import dev.gdx.uiharness.scene2d.RenderThreadScheduler;
import dev.gdx.uiharness.scene2d.Scene2dHarness;
import dev.gdx.uiharness.scene2d.Scene2dSession;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Owns deterministic render hooks and every cross-module resource for one fixture process. */
public final class FixtureControl implements AutoCloseable {
    /** Stable protocol session selected by all reference workflows. */
    public static final String SESSION_ID = "reference-ui";

    private static final Duration FIXED_STEP = Duration.ofMillis(16);
    private static final Duration ARTIFACT_LIFETIME = Duration.ofHours(1);
    private static final List<String> CAPABILITIES = List.of(
            "action", "query", "screenshot", "snapshot", "trace", "wait");

    private final Path processRoot;
    private final Path artifactRoot;
    private final Path traceRoot;
    private final Path proofRoot;
    private final ControlledStageClock clock;
    private final RenderThreadScheduler scheduler;
    private final Scene2dSession sceneSession;
    private final Scene2dHarness sceneHarness;
    private final Lwjgl3FrameFence fence;
    private final Lwjgl3ScreenCapture capture;
    private final WaitEngine waits;
    private final FileArtifactStore artifactStore;
    private final StorePublisher publisher;
    private final ReferenceTraceController traces;
    private final Harness tracingHarness;
    private final ExecutorService protocolExecutor;
    private final ExecutorService terminationExecutor;
    private final AtomicBoolean closed = new AtomicBoolean();
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
        HarnessProtocolService.Session session = new HarnessProtocolService.Session(
                tracingHarness, new StrictResolution(), waits, new TracingCapture(capture, traces),
                capabilities, traces);
        HarnessProtocolService protocol = new HarnessProtocolService(
                Map.of(SESSION_ID, session), clock, protocolExecutor);
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
        clock.advance(FIXED_STEP);
        scheduler.drain();
    }

    /** Publishes identity for the framebuffer that was just rendered. */
    public void afterDraw() {
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
        failure = closeResource(sceneHarness, failure);
        failure = closeResource(sceneSession, failure);
        failure = closeResource(scheduler, failure);
        failure = closeResource(clock, failure);
        failure = closeResource(traces, failure);
        failure = closeResource(publisher, failure);
        failure = closeResource(artifactStore, failure);
        failure = closeResource(protocolExecutor, failure);
        failure = closeResource(terminationExecutor, failure);
        failure = deleteOwnedDirectories(failure);
        if (terminationTask != null && !terminationTask.isDone()) {
            failure = append(failure,
                    new IllegalStateException("MCP termination virtual thread did not stop"));
        }
        if (!protocolExecutor.isTerminated() || !terminationExecutor.isTerminated()) {
            failure = append(failure,
                    new IllegalStateException("fixture virtual-thread executors did not terminate"));
        }
        if (failure != null) {
            throw failure;
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

    private static final class ReferenceTraceController
            implements HarnessProtocolService.TraceController, AutoCloseable {
        private final TraceRecorder recorder;
        private final StorePublisher publisher;
        private final AtomicLong operationSequence = new AtomicLong();
        private String traceId;
        private boolean active;

        ReferenceTraceController(Path root, StorePublisher publisher) {
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

        private long record(TraceEvent event) {
            return recorder.record(event);
        }

        private static long logicalTime(SemanticSnapshot snapshot) {
            return Math.multiplyExact(snapshot.frame(), FIXED_STEP.toNanos());
        }

        private record TraceSpan(long sequence, String requestId) {}

        @Override public synchronized void close() {
            active = false;
            recorder.close();
        }
    }

    private static final class TracingHarness implements Harness {
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
                    if (isDone() || cancelling) {
                        return;
                    }
                    if (snapshotFailure != null) {
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
                    current = source;
                }
                source.whenComplete(this::actionCompleted);
            }

            private void actionCompleted(ActionResult result, Throwable actionFailure) {
                CompletableFuture<SemanticSnapshot> source;
                synchronized (lifecycle) {
                    if (isDone() || cancelling) {
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
                    current = source;
                }
                source.whenComplete(this::afterCompleted);
            }

            private void afterCompleted(
                    SemanticSnapshot after, Throwable snapshotFailure) {
                synchronized (lifecycle) {
                    if (isDone() || cancelling) {
                        return;
                    }
                    if (snapshotFailure != null) {
                        failAction(unwrap(snapshotFailure));
                        return;
                    }
                    try {
                        traces.commandCompleted(operation, after, span);
                    } catch (Throwable recorderFailure) {
                        super.completeExceptionally(recorderFailure);
                        return;
                    }
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
                super.completeExceptionally(actionFailure);
            }

            @Override public boolean cancel(boolean mayInterruptIfRunning) {
                synchronized (lifecycle) {
                    if (isDone()) {
                        return false;
                    }
                    cancelling = true;
                    try {
                        if (current == null || !current.cancel(mayInterruptIfRunning)) {
                            return false;
                        }
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

    private static final class TracingCapture implements ScreenCapture {
        private final ScreenCapture delegate;
        private final ReferenceTraceController traces;

        TracingCapture(ScreenCapture delegate, ReferenceTraceController traces) {
            this.delegate = delegate;
            this.traces = traces;
        }

        @Override public CompletionStage<CapturedImage> capture(
                CaptureRequest request, Deadline deadline) {
            ReferenceTraceController.TraceSpan span = traces.captureStarted(deadline);
            return delegate.capture(request, deadline).thenApply(image -> {
                traces.captureCompleted(image, span);
                return image;
            });
        }

        @Override public void close() {
            delegate.close();
        }
    }
}
