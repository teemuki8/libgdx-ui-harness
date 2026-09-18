package dev.gdx.uiharness.mcp;

import dev.gdx.uiharness.core.error.ErrorCode;
import dev.gdx.uiharness.core.error.ErrorEvidence;
import dev.gdx.uiharness.core.error.HarnessException;
import dev.gdx.uiharness.core.locator.LocatorEngine;
import dev.gdx.uiharness.core.locator.StrictResolution;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import dev.gdx.uiharness.core.time.Deadline;
import dev.gdx.uiharness.core.trace.SemanticObservation;
import dev.gdx.uiharness.core.trace.SemanticObservationStore;
import dev.gdx.uiharness.core.trace.TraceEvent;
import dev.gdx.uiharness.core.trace.TraceManifest;
import dev.gdx.uiharness.core.trace.TraceRecorder;
import dev.gdx.uiharness.core.trace.TransitionProjector;
import dev.gdx.uiharness.core.trace.TransitionQuery;
import dev.gdx.uiharness.core.trace.TransitionQueryResult;
import dev.gdx.uiharness.protocol.Command;
import dev.gdx.uiharness.protocol.HarnessProtocolService;
import dev.gdx.uiharness.protocol.HarnessResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded trace recording for applications that own their render loop.
 *
 * <p>The session capture and action decorators record every request as it happens; an application
 * that simply renders frames does not have those calls, so this controller records one observation
 * per completed frame the application reports through {@link #observe(SemanticSnapshot, String)}.
 * Adjacent observations project their real transitions, and causality is attributed only where a
 * caller supplies it - the projector reports unknown causes rather than inventing them.
 *
 * <p>The published archive keeps the same contract as every other artifact: opaque reference,
 * byte length and archive digest, produced by the shared bounded recorder.
 */
public final class RecordingTraceController
        implements HarnessProtocolService.TraceController, AutoCloseable {
    private static final int MAX_OBSERVATIONS = 256;
    /** Event timestamps stay frame-derived: a trace is ordered by frame, not by wall clock. */
    private static final long LOGICAL_NANOS_PER_FRAME = 16_666_667L;

    private final TraceRecorder recorder;
    private final ArtifactReference.Publisher publisher;
    private final LocatorEngine locators;
    private final String sessionId;
    private final SemanticObservationStore observations =
            new SemanticObservationStore(MAX_OBSERVATIONS);
    private final AtomicLong operationSequence = new AtomicLong();
    private String traceId;
    private boolean active;

    /** Creates a controller with the strict locator engine and a system UTC recorder clock. */
    public static RecordingTraceController open(
            Path traceRoot, ArtifactReference.Publisher publisher, String sessionId) {
        return new RecordingTraceController(
                traceRoot, publisher, sessionId, new StrictResolution(), Clock.systemUTC());
    }

    /** Creates a controller with explicit collaborators. */
    public static RecordingTraceController open(
            Path traceRoot,
            ArtifactReference.Publisher publisher,
            String sessionId,
            LocatorEngine locators,
            Clock clock) {
        return new RecordingTraceController(traceRoot, publisher, sessionId, locators, clock);
    }

    private RecordingTraceController(
            Path traceRoot,
            ArtifactReference.Publisher publisher,
            String sessionId,
            LocatorEngine locators,
            Clock clock) {
        this.recorder = new TraceRecorder(
                Objects.requireNonNull(traceRoot, "traceRoot"), Objects.requireNonNull(clock, "clock"));
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.locators = Objects.requireNonNull(locators, "locators");
    }

    /** True while a trace is active, so callers can skip snapshot work entirely. */
    public synchronized boolean active() {
        return active;
    }

    /** Retains one completed frame while a trace is active; a no-op otherwise. */
    public synchronized void observe(SemanticSnapshot snapshot, String operation) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(operation, "operation");
        if (!active) {
            return;
        }
        long sequence = record(new TraceEvent(
                -1,
                TraceEvent.Kind.SNAPSHOT,
                sessionId,
                sessionId + "-" + operation + "-" + operationSequence.incrementAndGet(),
                logicalTime(snapshot),
                snapshot.frame(),
                snapshot.revision(),
                null,
                Map.of(
                        "operation", operation,
                        "nodeCount", Integer.toString(snapshot.nodes().size()))));
        observations.retain(traceId, new SemanticObservation(
                sequence, snapshot.frame(), snapshot.revision(), snapshot, null));
    }

    @Override public synchronized CompletionStage<HarnessResponse.Result.TraceStarted> start(
            Command.TraceStart command, Deadline deadline) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(deadline, "deadline");
        if (active) {
            return CompletableFuture.failedFuture(new HarnessException(
                    ErrorCode.INVALID_REQUEST,
                    "a trace is already active",
                    ErrorEvidence.empty()));
        }
        traceId = "trace-" + Long.toUnsignedString(operationSequence.incrementAndGet());
        recorder.start(sessionId, new TraceRecorder.Limits(
                command.maxBytes(), 10_000, Duration.ofMillis(command.maxDurationMillis())));
        active = true;
        record(new TraceEvent(-1, TraceEvent.Kind.LOG, sessionId, "trace-start",
                deadline.clock().nanoTime(), null, null, null, Map.of("event", "trace-start")));
        return CompletableFuture.completedFuture(
                new HarnessResponse.Result.TraceStarted(traceId));
    }

    @Override public synchronized CompletionStage<HarnessResponse.Result.TraceStopped> stop(
            Deadline deadline) {
        Objects.requireNonNull(deadline, "deadline");
        if (!active) {
            return CompletableFuture.failedFuture(new HarnessException(
                    ErrorCode.INVALID_REQUEST,
                    "no trace is active",
                    ErrorEvidence.empty()));
        }
        record(new TraceEvent(-1, TraceEvent.Kind.LOG, sessionId, "trace-stop",
                deadline.clock().nanoTime(), null, null, null, Map.of("event", "trace-stop")));
        TraceManifest manifest = recorder.stop();
        active = false;
        byte[] archive = recorder.consumeArchive(manifest);
        ArtifactReference reference = publisher.publish("application/zip", archive);
        if (!reference.mediaType().equals("application/zip")
                || reference.byteLength() != archive.length
                || !reference.sha256().equals(manifest.archiveSha256())) {
            return CompletableFuture.failedFuture(new HarnessException(
                    ErrorCode.CAPTURE_FAILURE,
                    "artifact publisher receipt does not match the verified trace archive",
                    ErrorEvidence.empty()));
        }
        return CompletableFuture.completedFuture(new HarnessResponse.Result.TraceStopped(
                traceId,
                reference.reference(),
                manifest.eventCount(),
                reference.byteLength(),
                manifest.archiveSha256()));
    }

    @Override public synchronized CompletionStage<TransitionQueryResult> query(
            TransitionQuery query, Deadline deadline) {
        Objects.requireNonNull(query, "query");
        if (traceId == null || !traceId.equals(query.traceId())) {
            return CompletableFuture.failedFuture(new HarnessException(
                    ErrorCode.NOT_FOUND,
                    "no retained observations for trace " + query.traceId(),
                    ErrorEvidence.empty()));
        }
        List<SemanticObservation> retained = observations.observations(query.traceId());
        return CompletableFuture.completedFuture(
                new TransitionProjector().query(retained, query, locators));
    }

    private long record(TraceEvent event) {
        return recorder.record(event);
    }

    private static long logicalTime(SemanticSnapshot snapshot) {
        return Math.multiplyExact(snapshot.frame(), LOGICAL_NANOS_PER_FRAME);
    }

    @Override public synchronized void close() {
        active = false;
        recorder.close();
    }
}
