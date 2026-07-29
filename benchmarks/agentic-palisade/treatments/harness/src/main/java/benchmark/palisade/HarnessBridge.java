package benchmark.palisade;

import com.badlogic.gdx.scenes.scene2d.Stage;
import dev.gdx.uiharness.core.locator.LocatorEngine;
import dev.gdx.uiharness.core.locator.StrictResolution;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import dev.gdx.uiharness.core.time.Deadline;
import dev.gdx.uiharness.core.time.MonotonicClock;
import dev.gdx.uiharness.core.trace.TraceEvent;
import dev.gdx.uiharness.core.trace.TraceManifest;
import dev.gdx.uiharness.core.trace.TraceRecorder;
import dev.gdx.uiharness.core.wait.WaitEngine;
import dev.gdx.uiharness.lwjgl3.Lwjgl3FrameFence;
import dev.gdx.uiharness.lwjgl3.Lwjgl3ScreenCapture;
import dev.gdx.uiharness.mcp.ArtifactReference;
import dev.gdx.uiharness.mcp.HarnessToolHandler;
import dev.gdx.uiharness.protocol.ArtifactId;
import dev.gdx.uiharness.protocol.ArtifactMediaType;
import dev.gdx.uiharness.protocol.ArtifactStore;
import dev.gdx.uiharness.protocol.CapabilitySet;
import dev.gdx.uiharness.protocol.Command;
import dev.gdx.uiharness.protocol.FileArtifactStore;
import dev.gdx.uiharness.protocol.HarnessProtocolService;
import dev.gdx.uiharness.protocol.HarnessResponse;
import dev.gdx.uiharness.scene2d.RenderThreadScheduler;
import dev.gdx.uiharness.scene2d.Scene2dHarness;
import dev.gdx.uiharness.scene2d.Scene2dSession;
import dev.gdx.uiharness.scene2d.Semantics;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Treatment-only adapter between application-owned render callbacks and the V1 harness tools. */
public final class HarnessBridge implements AutoCloseable {
    /** Stable session selected by every treatment command. */
    public static final String SESSION_ID = "candidate-ui";

    private static final List<String> CAPABILITIES = List.of(
            "action", "query", "screenshot", "snapshot", "trace", "wait");
    private static final Duration ARTIFACT_LIFETIME = Duration.ofHours(1);
    private static final int SCHEDULER_CAPACITY = 128;
    private static final int FENCE_CAPACITY = 64;

    private final Path ownedRoot;
    private final MonotonicClock clock = System::nanoTime;
    private final AtomicLong revision = new AtomicLong();
    private final AtomicLong frame = new AtomicLong();
    private final RenderThreadScheduler scheduler;
    private final Scene2dSession sceneSession;
    private final Scene2dHarness sceneHarness;
    private final Lwjgl3FrameFence fence;
    private final Lwjgl3ScreenCapture capture;
    private final WaitEngine waits;
    private final FileArtifactStore artifactStore;
    private final StorePublisher publisher;
    private final TraceController traces;
    private final ExecutorService protocolExecutor;
    private final HarnessToolHandler tools;
    private final AtomicBoolean closed = new AtomicBoolean();

    private HarnessBridge(CandidateUi candidate, Path artifactRoot) {
        Objects.requireNonNull(candidate, "candidate");
        Stage stage = Objects.requireNonNull(candidate.stage(), "candidate.stage()");
        ownedRoot = prepareOwnedRoot(artifactRoot);
        Path artifactRootPath = ownedRoot.resolve("artifacts");
        Path traceRoot = ownedRoot.resolve("traces");

        scheduler = new RenderThreadScheduler(SCHEDULER_CAPACITY);
        sceneSession = new Scene2dSession(stage);
        fence = new Lwjgl3FrameFence(FENCE_CAPACITY);
        sceneHarness = new Scene2dHarness(stage, stage, sceneSession, scheduler, fence,
                revision::get, frame::get);
        capture = new Lwjgl3ScreenCapture(fence, sceneSession::snapshot);
        LocatorEngine locators = new StrictResolution();
        artifactStore = new FileArtifactStore(artifactRootPath,
                new ArtifactStore.Limits(32L * 1024 * 1024, 64), Clock.systemUTC());
        publisher = new StorePublisher(artifactStore);
        traces = new TraceController(traceRoot, publisher, clock);
        waits = new WaitEngine(this::snapshotForWait, locators, clock, fence);
        protocolExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("palisade-harness-protocol-", 0).factory());
        HarnessProtocolService.Session session = new HarnessProtocolService.Session(
                sceneHarness, locators, waits, capture, new CapabilitySet(CAPABILITIES), traces);
        HarnessProtocolService protocol = new HarnessProtocolService(
                Map.of(SESSION_ID, session), clock, protocolExecutor);
        tools = new HarnessToolHandler(protocol, publisher);
    }


    /** Attaches exactly one non-owning semantic session and one owned artifact tree. */
    public static HarnessBridge open(CandidateUi candidate, Path artifactRoot) {
        return new HarnessBridge(candidate, artifactRoot);
    }

    /** Returns metadata tagging for actors in the attached application-owned Stage. */
    public Semantics semantics() {
        requireOpen();
        return sceneSession.semantics();
    }

    /** Drains bounded harness work before the application advances its Stage. */
    public void beforeRender() {
        requireOpen();
        scheduler.drain();
    }

    /** Publishes the framebuffer and semantic identity after the application draws its Stage. */
    public void afterRender() {
        requireOpen();
        long completedFrame = frame.incrementAndGet();
        long completedRevision = revision.incrementAndGet();
        fence.completedFrame(completedRevision, completedFrame);
    }

    /** Executes one allowlisted MCP-equivalent operation for the bounded JSON CLI. */
    CompletionStage<McpSchema.CallToolResult> call(
            String operation, Map<String, Object> arguments) {
        requireOpen();
        return tools.handle(McpSchema.CallToolRequest.builder(operation)
                .arguments(Map.copyOf(arguments))
                .build()).toFuture();
    }

    /** Closes harness resources and deletes only the artifact tree created by this bridge. */
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        RuntimeException failure = null;
        failure = closeResource(tools, failure);
        failure = closeResource(waits, failure);
        failure = closeResource(capture, failure);
        failure = closeResource(fence, failure);
        failure = closeResource(sceneHarness, failure);
        failure = closeResource(sceneSession, failure);
        failure = closeResource(scheduler, failure);
        failure = closeResource(traces, failure);
        failure = closeResource(artifactStore, failure);
        failure = closeResource(protocolExecutor, failure);
        try {
            deleteOwnedTree(ownedRoot, 0);
        } catch (IOException deletionFailure) {
            failure = append(failure,
                    new IllegalStateException("Unable to remove bridge-owned artifacts",
                            deletionFailure));
        }
        if (failure != null) throw failure;
    }

    private SemanticSnapshot snapshotForWait() {
        return scheduler.submit(
                () -> sceneSession.snapshot(revision.get(), frame.get()),
                Deadline.after(clock, Duration.ofSeconds(30)))
                .toCompletableFuture().join();
    }

    private void requireOpen() {
        if (closed.get()) throw new IllegalStateException("Harness bridge is closed");
    }

    private static Path prepareOwnedRoot(Path supplied) {
        Path root = Objects.requireNonNull(supplied, "artifactRoot")
                .toAbsolutePath().normalize();
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Harness artifact root must not already exist");
        }
        Path parent = root.getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(parent)) {
            throw new IllegalArgumentException(
                    "Harness artifact root requires an existing non-symbolic parent");
        }
        try {
            return Files.createDirectory(root);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Unable to create harness artifact root", failure);
        }
    }

    private static void deleteOwnedTree(Path path, int depth) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(path) || depth > 6) {
            throw new IOException("Unsafe entry in bridge-owned artifact tree");
        }
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(path)) {
                for (Path entry : entries) deleteOwnedTree(entry, depth + 1);
            }
        }
        Files.delete(path);
    }

    private static RuntimeException closeResource(
            AutoCloseable resource, RuntimeException accumulated) {
        if (resource == null) return accumulated;
        try {
            resource.close();
            return accumulated;
        } catch (Exception failure) {
            RuntimeException wrapped = failure instanceof RuntimeException runtime
                    ? runtime : new IllegalStateException("Harness resource close failed", failure);
            return append(accumulated, wrapped);
        }
    }

    private static RuntimeException append(
            RuntimeException accumulated, RuntimeException failure) {
        if (accumulated == null) return failure;
        accumulated.addSuppressed(failure);
        return accumulated;
    }

    private static final class StorePublisher implements ArtifactReference.Publisher {
        private final FileArtifactStore store;

        StorePublisher(FileArtifactStore store) {
            this.store = store;
        }

        @Override public ArtifactReference publish(String mediaType, byte[] content) {
            ArtifactId id = store.put(SESSION_ID, ArtifactMediaType.fromValue(mediaType), content,
                    Instant.now().plus(ARTIFACT_LIFETIME));
            ArtifactStore.Metadata metadata = store.metadata(SESSION_ID, id);
            return new ArtifactReference("artifact:" + id.value(), mediaType,
                    metadata.size(), metadata.sha256());
        }
    }

    private static final class TraceController
            implements HarnessProtocolService.TraceController, AutoCloseable {
        private final TraceRecorder recorder;
        private final ArtifactReference.Publisher publisher;
        private final MonotonicClock clock;
        private final AtomicLong sequence = new AtomicLong();
        private String traceId;
        private boolean active;

        TraceController(Path root, ArtifactReference.Publisher publisher, MonotonicClock clock) {
            recorder = new TraceRecorder(root, Clock.systemUTC());
            this.publisher = publisher;
            this.clock = clock;
        }

        @Override public synchronized CompletionStage<HarnessResponse.Result.TraceStarted> start(
                Command.TraceStart command, Deadline deadline) {
            if (active) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("a trace is already active"));
            }
            traceId = "trace-" + Long.toUnsignedString(sequence.incrementAndGet());
            recorder.start(SESSION_ID, new TraceRecorder.Limits(
                    command.maxBytes(), 10_000, Duration.ofMillis(command.maxDurationMillis())));
            active = true;
            record("trace-start", deadline);
            return CompletableFuture.completedFuture(
                    new HarnessResponse.Result.TraceStarted(traceId));
        }

        @Override public synchronized CompletionStage<HarnessResponse.Result.TraceStopped> stop(
                Deadline deadline) {
            if (!active) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("no trace is active"));
            }
            record("trace-stop", deadline);
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

        private void record(String event, Deadline deadline) {
            recorder.record(new TraceEvent(-1, TraceEvent.Kind.LOG, SESSION_ID, event,
                    Math.max(clock.nanoTime(), deadline.clock().nanoTime()),
                    null, null, null, Map.of("event", event)));
        }

        @Override public synchronized void close() {
            active = false;
            recorder.close();
        }
    }
}
