package benchmark.palisade;

import com.badlogic.gdx.scenes.scene2d.Stage;
import dev.gdx.uiharness.core.action.Harness;
import dev.gdx.uiharness.core.capture.CapturedImage;
import dev.gdx.uiharness.core.contract.StateActionContract;
import dev.gdx.uiharness.core.locator.LocatorEngine;
import dev.gdx.uiharness.core.locator.StrictResolution;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import dev.gdx.uiharness.core.time.Deadline;
import dev.gdx.uiharness.core.time.MonotonicClock;
import dev.gdx.uiharness.core.trace.TraceEvent;
import dev.gdx.uiharness.core.trace.TraceManifest;
import dev.gdx.uiharness.core.trace.TraceRecorder;
import dev.gdx.uiharness.core.wait.WaitEngine;
import dev.gdx.uiharness.core.visual.VisualPolicy;
import dev.gdx.uiharness.core.visual.VisualReference;
import dev.gdx.uiharness.lwjgl3.Lwjgl3FrameFence;
import dev.gdx.uiharness.lwjgl3.Lwjgl3ScreenCapture;
import dev.gdx.uiharness.lwjgl3.Lwjgl3VisualComparator;
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
import dev.gdx.uiharness.protocol.InspectCaptureCompareService;
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
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
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
            "action", "compare", "query", "screenshot", "snapshot", "trace", "wait");
    private static final Duration ARTIFACT_LIFETIME = Duration.ofHours(1);
    private static final int SCHEDULER_CAPACITY = 128;
    private static final int FENCE_CAPACITY = 64;

    private final Path ownedRoot;
    private final Path traceRoot;
    private final Path artifactRoot;
    private final Path publishedRoot;
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
    private final CandidateUi candidate;

    private HarnessBridge(CandidateUi candidate, Path artifactRoot) {
        this.candidate = Objects.requireNonNull(candidate, "candidate");
        Stage stage = Objects.requireNonNull(candidate.stage(), "candidate.stage()");
        ownedRoot = prepareOwnedRoot(artifactRoot);
        this.artifactRoot = ownedRoot.resolve("artifacts");
        traceRoot = ownedRoot.resolve("traces");
        publishedRoot = createOwnedDirectory(ownedRoot.resolve("published"));

        scheduler = new RenderThreadScheduler(SCHEDULER_CAPACITY);
        sceneSession = new Scene2dSession(stage);
        fence = new Lwjgl3FrameFence(FENCE_CAPACITY);
        sceneHarness = new Scene2dHarness(stage, stage, sceneSession, scheduler, fence,
                revision::get, frame::get);
        capture = new Lwjgl3ScreenCapture(fence, sceneSession::snapshot);
        LocatorEngine locators = new StrictResolution();
        artifactStore = new FileArtifactStore(this.artifactRoot,
                new ArtifactStore.Limits(32L * 1024 * 1024, 64), Clock.systemUTC());
        publisher = new StorePublisher(artifactStore, publishedRoot);
        traces = new TraceController(traceRoot, publisher, clock);
        waits = new WaitEngine(this::snapshotForWait, locators, clock, fence);
        protocolExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("palisade-harness-protocol-", 0).factory());
        HarnessProtocolService.Session session = new HarnessProtocolService.Session(
                sceneHarness, locators, waits, capture, new CapabilitySet(CAPABILITIES), traces);
        HarnessProtocolService.ContractProvider contracts =
                new HarnessProtocolService.ContractProvider() {
                    @Override public CompletionStage<StateActionContract> snapshot(
                            Deadline deadline) {
                        return scheduler.submit(
                                () -> sceneSession.stateActionContract(
                                        revision.get(), frame.get()),
                                deadline);
                    }

                    @Override public CompletionStage<HarnessProtocolService.SnapshotEvidence>
                            snapshotWith(Harness ignored, Deadline deadline) {
                        return scheduler.submit(() -> {
                            long currentRevision = revision.get();
                            long currentFrame = frame.get();
                            return new HarnessProtocolService.SnapshotEvidence(
                                    sceneSession.snapshot(currentRevision, currentFrame),
                                    sceneSession.stateActionContract(
                                            currentRevision, currentFrame));
                        }, deadline);
                    }
                };
        Map<String, VisualReference> references = visualReferences(Path.of("."));
        String viewportId = "desktop-" + stage.getViewport().getScreenWidth()
                + "x" + stage.getViewport().getScreenHeight();
        VisualPolicy policy = VisualPolicy.pixelExactV1();
        InspectCaptureCompareService comparison = new InspectCaptureCompareService(
                SESSION_ID, "palisade-skirmish", viewportId,
                sceneHarness, capture, contracts,
                id -> java.util.Optional.ofNullable(references.get(id)),
                List.of(policy), new Lwjgl3VisualComparator(), clock,
                InstantSource.system());
        HarnessProtocolService protocol = new HarnessProtocolService(
                Map.of(SESSION_ID, session),
                Map.of(SESSION_ID, contracts),
                Map.of(SESSION_ID, comparison),
                clock, protocolExecutor);
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

    /** Reads the treatment-neutral candidate contract on the owning render thread. */
    CompletionStage<Map<String, Object>> candidateContract() {
        requireOpen();
        return scheduler.submit(() -> {
            Object value = candidate.snapshotState().values().get("stateAction");
            if (!(value instanceof Map<?, ?> raw)) {
                return Map.of();
            }
            java.util.LinkedHashMap<String, Object> contract =
                    new java.util.LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException(
                            "Candidate stateAction keys must be strings");
                }
                contract.put(key, entry.getValue());
            }
            return java.util.Collections.unmodifiableMap(contract);
        }, Deadline.after(clock, Duration.ofSeconds(30)));
    }

    /** Closes harness resources while retaining only bounded published benchmark evidence. */
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
            deleteOwnedTree(traceRoot, 0);
            deleteOwnedTree(artifactRoot, 0);
        } catch (IOException deletionFailure) {
            failure = append(failure,
                    new IllegalStateException("Unable to remove transient harness storage",
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

    static Map<String, VisualReference> visualReferences(Path workingDirectory) {
        java.util.LinkedHashMap<String, VisualReference> references =
                new java.util.LinkedHashMap<>();
        for (ReferenceDescriptor descriptor : List.of(
                new ReferenceDescriptor(
                        "initial-1920x1080", "desktop-1920x1080", 1920, 1080,
                        "98c092bfd976171cb17745b425e8d0ae357e93f085ed8eae9e618ee56c0f5cb3"),
                new ReferenceDescriptor(
                        "bottom-1920x1080", "desktop-1920x1080", 1920, 1080,
                        "92b4dd35574d3b614bd1f4a05c172dd9df9c41ef96396a7fab45902e7f4f2fb6"),
                new ReferenceDescriptor(
                        "initial-1280x720", "desktop-1280x720", 1280, 720,
                        "9de83761bb4135d618c48830afc280b85c36ff413f7d3b7248d0fb168b8d5ad0"))) {
            VisualReference reference = visualReference(workingDirectory, descriptor);
            references.put(reference.referenceId(), reference);
        }
        return java.util.Collections.unmodifiableMap(references);
    }

    private static VisualReference visualReference(
            Path workingDirectory, ReferenceDescriptor descriptor) {
        Path reference = locateReference(workingDirectory, descriptor.id());
        try {
            byte[] png = Files.readAllBytes(reference);
            return new VisualReference(
                    descriptor.id(), "palisade-skirmish", "corpus-v1",
                    descriptor.viewportId(), png, descriptor.sha256(),
                    descriptor.width(), descriptor.height(), new CapturedImage.Scale(1, 1),
                    Instant.EPOCH, null, null);
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Unable to read the fixed public visual reference", failure);
        }
    }

    static Path locateInitialReference(Path workingDirectory) {
        return locateReference(workingDirectory, "initial-1280x720");
    }

    static Path locateReference(Path workingDirectory, String referenceId) {
        Path root = Objects.requireNonNull(
                workingDirectory, "workingDirectory").toAbsolutePath().normalize();
        if (!List.of("initial-1920x1080", "bottom-1920x1080", "initial-1280x720")
                .contains(referenceId)) {
            throw new IllegalArgumentException("Unknown canonical reference: " + referenceId);
        }
        String filename = referenceId + ".png";
        for (Path candidate : List.of(
                root.resolve("corpus/reference").resolve(filename),
                root.resolve("../corpus/reference").resolve(filename),
                root.resolve("benchmarks/agentic-palisade/corpus/reference").resolve(filename))) {
            Path normalized = candidate.normalize();
            if (Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(normalized)) {
                return normalized;
            }
        }
        throw new IllegalStateException("Fixed public visual reference is missing");
    }

    private record ReferenceDescriptor(
            String id, String viewportId, int width, int height, String sha256) {
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
    private static Path createOwnedDirectory(Path path) {
        try {
            return Files.createDirectory(path);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Unable to create harness evidence directory",
                    failure);
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
        private final Path publishedRoot;

        StorePublisher(FileArtifactStore store, Path publishedRoot) {
            this.store = store;
            this.publishedRoot = publishedRoot;
        }

        @Override public synchronized ArtifactReference publish(
                String mediaType, byte[] content) {
            ArtifactId id = store.put(SESSION_ID, ArtifactMediaType.fromValue(mediaType), content,
                    Instant.now().plus(ARTIFACT_LIFETIME));
            ArtifactStore.Metadata metadata = store.metadata(SESSION_ID, id);
            Path published = publishedRoot.resolve(metadata.sha256());
            if (!Files.exists(published, LinkOption.NOFOLLOW_LINKS)) {
                publishAtomically(published, content);
            } else if (!Files.isRegularFile(published, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(published)) {
                throw new IllegalStateException("Published artifact target is unsafe");
            }
            return new ArtifactReference("artifact:" + metadata.sha256(), mediaType,
                    metadata.size(), metadata.sha256());
        }

        private void publishAtomically(Path published, byte[] content) {
            Path temporary = null;
            try {
                temporary = Files.createTempFile(publishedRoot, ".artifact-", ".tmp");
                Files.write(temporary, content, StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE);
                Files.move(temporary, published, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException failure) {
                if (temporary != null) {
                    try {
                        Files.deleteIfExists(temporary);
                    } catch (IOException cleanupFailure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
                throw new IllegalStateException("Unable to publish benchmark artifact", failure);
            }
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
            try {
                recorder.start(SESSION_ID, new TraceRecorder.Limits(command.maxBytes(), 10_000,
                        Duration.ofMillis(command.maxDurationMillis())));
                record("trace-start", deadline);
                active = true;
                return CompletableFuture.completedFuture(
                        new HarnessResponse.Result.TraceStarted(traceId));
            } catch (RuntimeException failure) {
                active = false;
                recoverPartial(failure);
                return CompletableFuture.failedFuture(failure);
            }
        }

        @Override public synchronized CompletionStage<HarnessResponse.Result.TraceStopped> stop(
                Deadline deadline) {
            if (!active) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("no trace is active"));
            }
            try {
                record("trace-stop", deadline);
                TraceManifest manifest = recorder.stop();
                active = false;
                ArtifactReference reference = publish(manifest);
                return CompletableFuture.completedFuture(
                        new HarnessResponse.Result.TraceStopped(traceId, reference.reference(),
                                manifest.eventCount(), reference.byteLength()));
            } catch (RuntimeException failure) {
                active = false;
                recoverPartial(failure);
                return CompletableFuture.failedFuture(failure);
            }
        }

        private ArtifactReference publish(TraceManifest manifest) {
            try {
                byte[] archive = Files.readAllBytes(manifest.archive());
                ArtifactReference reference = publisher.publish("application/zip", archive);
                Files.delete(manifest.archive());
                return reference;
            } catch (IOException failure) {
                throw new IllegalStateException("Unable to publish trace archive", failure);
            }
        }

        private void recoverPartial(RuntimeException originalFailure) {
            try {
                recorder.close();
                publishLastManifest();
            } catch (RuntimeException cleanupFailure) {
                originalFailure.addSuppressed(cleanupFailure);
            }
        }

        private void publishLastManifest() {
            recorder.lastManifest()
                    .filter(manifest -> Files.exists(
                            manifest.archive(), LinkOption.NOFOLLOW_LINKS))
                    .ifPresent(this::publish);
        }

        private void record(String event, Deadline deadline) {
            recorder.record(new TraceEvent(-1, TraceEvent.Kind.LOG, SESSION_ID, event,
                    Math.max(clock.nanoTime(), deadline.clock().nanoTime()),
                    null, null, null, Map.of("event", event)));
        }

        @Override public synchronized void close() {
            if (active) recorder.close();
            active = false;
            publishLastManifest();
        }
    }
}
