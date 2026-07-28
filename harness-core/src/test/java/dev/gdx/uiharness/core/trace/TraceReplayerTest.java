package dev.gdx.uiharness.core.trace;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gdx.uiharness.core.error.ErrorCode;
import dev.gdx.uiharness.core.error.HarnessException;
import dev.gdx.uiharness.core.model.Bounds;
import dev.gdx.uiharness.core.model.Role;
import dev.gdx.uiharness.core.model.SemanticNode;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import dev.gdx.uiharness.core.model.SemanticState;
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TraceReplayerTest {
    @TempDir Path temporaryDirectory;

    @Test void reportsMissingAndForwardCausalParentsWithoutExecutingEvents() throws Exception {
        TraceRecorder recorder = new TraceRecorder(temporaryDirectory, Clock.systemUTC());
        recorder.start("session-causal", TraceRecorder.Limits.defaults());
        recorder.record(TraceEvent.commandStarted(
                "session-causal", "request-1", 1, snapshot(1, 1), Map.of()));
        recorder.record(TraceEvent.inputDispatched(
                "session-causal", "request-1", 2, 1, 1, 99, Map.of()));
        Path archive = recorder.stop().archive();

        TraceReplay replay = new TraceReplayer().load(archive);

        assertFalse(replay.causality().isValid());
        assertTrue(replay.causality().errors().stream()
                .anyMatch(error -> error.contains("parent") && error.contains("99")));
    }

    @Test void reportsRequestSessionLogicalTimeAndRevisionCausalErrors() throws Exception {
        TraceRecorder recorder = new TraceRecorder(temporaryDirectory, Clock.systemUTC());
        recorder.start("session-causal", TraceRecorder.Limits.defaults());
        recorder.record(TraceEvent.commandStarted(
                "session-causal", "request-1", 10, snapshot(3, 3), Map.of()));
        recorder.record(new TraceEvent(1, TraceEvent.Kind.COMMAND_COMPLETED,
                "wrong-session", "request-2", 9, 2L, 2L, 0L, Map.of()));
        Path archive = recorder.stop().archive();

        TraceReplay replay = new TraceReplayer().load(archive);

        assertFalse(replay.causality().isValid());
        assertTrue(replay.causality().errors().stream()
                .anyMatch(error -> error.contains("session")));
        assertTrue(replay.causality().errors().stream()
                .anyMatch(error -> error.contains("logical time")));
        assertTrue(replay.causality().errors().stream()
                .anyMatch(error -> error.contains("request")));
        assertTrue(replay.causality().errors().stream()
                .anyMatch(error -> error.contains("revision")));
    }

    @Test void malformedFinalEventReturnsPartialReplayDiagnostics() throws Exception {
        Path archive = temporaryDirectory.resolve("partial.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive),
                StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("events.ndjson"));
            BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(zip, StandardCharsets.UTF_8));
            writer.write("{\"sequence\":0,\"kind\":\"COMMAND_STARTED\","
                    + "\"sessionId\":\"session-a\",\"requestId\":\"request-1\","
                    + "\"logicalTime\":1,\"frame\":1,\"revision\":1,"
                    + "\"parentSequence\":null,\"evidence\":{}}\n");
            writer.write("{\"sequence\":\"one\",\"kind\":\"COMMAND_COMPLETED\","
                    + "\"sessionId\":\"session-a\",\"requestId\":\"request-1\","
                    + "\"logicalTime\":2,\"frame\":2,\"revision\":2,"
                    + "\"parentSequence\":0,\"evidence\":{}}");
            writer.flush();
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(("{\"sessionId\":\"session-a\",\"startedAt\":"
                    + "\"2026-07-28T00:00:00Z\",\"endedAt\":"
                    + "\"2026-07-28T00:00:01Z\",\"complete\":false,"
                    + "\"terminationReason\":\"interrupted\",\"eventCount\":2,"
                    + "\"artifactCount\":0,\"uncompressedBytes\":200}")
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        TraceReplay replay = new TraceReplayer().load(archive);

        assertTrue(replay.partial());
        assertTrue(replay.diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.contains("malformed event 1")));
    }

    @Test void rejectsOversizedAndTraversalArchivesWithTypedLimitFailure() throws Exception {
        Path oversized = temporaryDirectory.resolve("oversized.zip");
        Files.write(oversized, new byte[101]);
        HarnessException sizeFailure = assertThrows(HarnessException.class,
                () -> new TraceReplayer(new TraceReplayer.Limits(100, 10, 1_000))
                        .load(oversized));
        assertTrue(sizeFailure.code() == ErrorCode.LIMIT_EXCEEDED);

        Path traversal = temporaryDirectory.resolve("traversal.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(traversal))) {
            zip.putNextEntry(new ZipEntry("../manifest.json"));
            zip.write("{}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        HarnessException traversalFailure = assertThrows(HarnessException.class,
                () -> new TraceReplayer().load(traversal));
        assertTrue(traversalFailure.code() == ErrorCode.INVALID_REQUEST);
    }

    @Test void malformedManifestTimestampIsTypedInvalidRequest() throws Exception {
        Path archive = temporaryDirectory.resolve("bad-time.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("events.ndjson"));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(("{\"sessionId\":\"session-a\",\"startedAt\":\"not-an-instant\","
                    + "\"endedAt\":\"2026-07-28T00:00:01Z\",\"complete\":false,"
                    + "\"terminationReason\":\"interrupted\",\"eventCount\":0,"
                    + "\"artifactCount\":0,\"uncompressedBytes\":0}")
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        HarnessException failure = assertThrows(HarnessException.class,
                () -> new TraceReplayer().load(archive));

        assertTrue(failure.code() == ErrorCode.INVALID_REQUEST);
    }

    private static SemanticSnapshot snapshot(long revision, long frame) {
        Bounds bounds = new Bounds(0, 0, 100, 100);
        SemanticNode root = new SemanticNode("root", null, List.of(), Role.GROUP, "root", "",
                null, null, null, null, state(), bounds, bounds, bounds, 0, Map.of());
        return new SemanticSnapshot(revision, frame, "root", Map.of("root", root));
    }

    private static SemanticState state() {
        return new SemanticState(true, true, java.util.Optional.empty(),
                java.util.Optional.empty(), java.util.Optional.empty(),
                java.util.Optional.empty(), java.util.Optional.empty(), false, false, 1.0,
                false, true, true);
    }
}
