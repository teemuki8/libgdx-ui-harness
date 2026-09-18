package dev.gdx.uiharness.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gdx.uiharness.core.error.ErrorCode;
import dev.gdx.uiharness.core.error.HarnessException;
import dev.gdx.uiharness.core.locator.StrictResolution;
import dev.gdx.uiharness.core.model.Bounds;
import dev.gdx.uiharness.core.model.Role;
import dev.gdx.uiharness.core.model.SemanticNode;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import dev.gdx.uiharness.core.model.SemanticState;
import dev.gdx.uiharness.core.time.Deadline;
import dev.gdx.uiharness.core.time.MonotonicClock;
import dev.gdx.uiharness.core.trace.TransitionQuery;
import dev.gdx.uiharness.core.trace.TransitionQueryResult;
import dev.gdx.uiharness.protocol.Command;
import dev.gdx.uiharness.protocol.HarnessResponse;
import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A tool that owns its render loop still gets bounded trace evidence from published classes. */
final class RecordingTraceControllerTest {
    private static final MonotonicClock CLOCK = MonotonicClock.system();
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @TempDir Path traceRoot;

    @Test
    void recordsFrameObservationsAndStopsWithAVerifiedArchive() throws IOException {
        List<byte[]> published = new ArrayList<>();
        RecordingTraceController traces = RecordingTraceController.open(
                traceRoot, publisher(published), "starter-hud");

        try (traces) {
            HarnessResponse.Result.TraceStarted started = traces
                    .start(new Command.TraceStart(60_000, 4_000_000), deadline())
                    .toCompletableFuture()
                    .join();

            traces.observe(snapshot(10, "0"), "frame");
            traces.observe(snapshot(11, "300"), "frame");

            HarnessResponse.Result.TraceStopped stopped = traces
                    .stop(deadline())
                    .toCompletableFuture()
                    .join();

            assertEquals(started.traceId(), stopped.traceId());
            assertEquals(1, published.size(), "one stop publishes exactly one archive");
            assertEquals(stopped.bytes(), published.get(0).length);
            assertEquals(stopped.archiveSha256(), sha256Hex(published.get(0)));
            assertTrue(stopped.eventCount() >= 3, "start, both frames and stop are recorded");

            TransitionQueryResult result = traces
                    .query(new TransitionQuery(stopped.traceId(), null, Set.of(), Set.of(),
                            null, null, 64, 65_536), deadline())
                    .toCompletableFuture()
                    .join();
            assertEquals(stopped.traceId(), result.traceId());
            assertFalse(result.transitions().isEmpty(),
                    "adjacent frame observations project the real transition between them");
        }
    }

    @Test
    void refusesToStopOrQueryWithoutAnActiveTrace() {
        try (RecordingTraceController traces = RecordingTraceController.open(
                traceRoot, publisher(new ArrayList<>()), "starter-hud")) {
            assertEquals(ErrorCode.INVALID_REQUEST, failureCode(
                    traces.stop(deadline()).toCompletableFuture()));
            assertEquals(ErrorCode.NOT_FOUND, failureCode(traces
                    .query(new TransitionQuery("trace-unknown", null, Set.of(), Set.of(),
                            null, null, 64, 65_536), deadline())
                    .toCompletableFuture()));
        }
    }

    private static ErrorCode failureCode(java.util.concurrent.CompletableFuture<?> pending) {
        CompletionException failure = assertThrows(CompletionException.class, pending::join);
        assertInstanceOf(HarnessException.class, failure.getCause(), failure.toString());
        return ((HarnessException) failure.getCause()).code();
    }

    private static ArtifactReference.Publisher publisher(List<byte[]> published) {
        return (mediaType, content) -> {
            published.add(content);
            return new ArtifactReference(
                    "artifact://traces/" + published.size(), mediaType, content.length, sha256Hex(content));
        };
    }

    private static Deadline deadline() {
        return Deadline.after(CLOCK, TIMEOUT);
    }

    private static SemanticSnapshot snapshot(long frame, String text) {
        SemanticNode root = new SemanticNode(
                "n0", null, List.of(), Role.LABEL, "score", text, "score", "score-value",
                "score-value", "Label",
                new SemanticState(true, true, Optional.of(true), Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty(), false, false, 1.0, false, true, true),
                new Bounds(0, 0, 80, 24), new Bounds(0, 0, 80, 24), new Bounds(0, 0, 80, 24),
                0, Map.of());
        return new SemanticSnapshot(frame, frame, "n0", Map.of("n0", root));
    }

    private static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static StrictResolution locators() {
        return new StrictResolution();
    }
}
