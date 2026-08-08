package dev.gdx.uiharness.core.trace;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.io.ByteArrayOutputStream;
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
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
                () -> new TraceReplayer(new TraceReplayer.Limits(100, 10, 1_000, 1_000_000, 1_000))
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

    @Test void rejectsWindowsDriveQualifiedArchiveEntries() throws Exception {
        Path archive = temporaryDirectory.resolve("drive-qualified.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("C:/manifest.json"));
            zip.write("{}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        HarnessException failure = assertThrows(HarnessException.class,
                () -> new TraceReplayer().load(archive));

        assertTrue(failure.code() == ErrorCode.INVALID_REQUEST);
        assertEquals("Trace archive contains an unsafe entry", failure.getMessage());
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

    @Test void cumulativeInflatedByteBudgetRejectsMultiLineBombs() throws Exception {
        byte[] line = ("{\"sequence\":0,\"kind\":\"COMMAND_STARTED\","
                + "\"sessionId\":\"session-a\",\"requestId\":\"request-1\","
                + "\"logicalTime\":1,\"frame\":1,\"revision\":1,"
                + "\"parentSequence\":null,\"evidence\":{}}\n")
                .getBytes(StandardCharsets.UTF_8);
        byte[] events = concat(line, line, line);
        long manifestBytes = v1ManifestBytes(3, events.length).length;
        Path archive = v1Archive(temporaryDirectory.resolve("bomb.zip"), events, 3);

        HarnessException failure = assertThrows(HarnessException.class,
                () -> new TraceReplayer(new TraceReplayer.Limits(
                        1_000_000, 100, 1_048_576,
                        manifestBytes + line.length * 2L, 1_000))
                        .load(archive));

        assertEquals(ErrorCode.LIMIT_EXCEEDED, failure.code());
        assertTrue(failure.getMessage().contains("cumulative"));
    }

    @Test void exactCumulativeBudgetLoadsAndOneByteOverFails() throws Exception {
        byte[] line = ("{\"sequence\":0,\"kind\":\"COMMAND_STARTED\","
                + "\"sessionId\":\"session-a\",\"requestId\":\"request-1\","
                + "\"logicalTime\":1,\"frame\":1,\"revision\":1,"
                + "\"parentSequence\":null,\"evidence\":{}}\n")
                .getBytes(StandardCharsets.UTF_8);
        byte[] events = concat(line, line, line);
        long manifestBytes = v1ManifestBytes(3, events.length).length;
        Path archive = v1Archive(temporaryDirectory.resolve("exact.zip"), events, 3);
        long exact = manifestBytes + line.length * 3L;

        assertDoesNotThrow(() -> new TraceReplayer(new TraceReplayer.Limits(
                1_000_000, 100, 1_048_576, exact, 1_000)).load(archive));

        HarnessException failure = assertThrows(HarnessException.class,
                () -> new TraceReplayer(new TraceReplayer.Limits(
                        1_000_000, 100, 1_048_576, exact - 1, 1_000))
                        .load(archive));
        assertEquals(ErrorCode.LIMIT_EXCEEDED, failure.code());
        assertTrue(failure.getMessage().contains("cumulative"));
    }

    @Test void manifestBytesCountTowardTheCumulativeBudget() throws Exception {
        byte[] line = ("{\"sequence\":0,\"kind\":\"COMMAND_STARTED\","
                + "\"sessionId\":\"session-a\",\"requestId\":\"request-1\","
                + "\"logicalTime\":1,\"frame\":1,\"revision\":1,"
                + "\"parentSequence\":null,\"evidence\":{}}\n")
                .getBytes(StandardCharsets.UTF_8);
        byte[] events = concat(line, line, line);
        long manifestBytes = v1ManifestBytes(3, events.length).length;
        Path archive = v1Archive(temporaryDirectory.resolve("manifest-only.zip"), events, 3);

        // The events alone fit; only the manifest's inflated bytes push the total over.
        HarnessException failure = assertThrows(HarnessException.class,
                () -> new TraceReplayer(new TraceReplayer.Limits(
                        1_000_000, 100, 1_048_576,
                        events.length + manifestBytes - 1, 1_000))
                        .load(archive));

        assertEquals(ErrorCode.LIMIT_EXCEEDED, failure.code());
        assertTrue(failure.getMessage().contains("cumulative"));
    }

    @Test void remainingBudgetSmallerThanNextLineFailsBeforeFullAllocation() throws Exception {
        byte[] line = ("{\"sequence\":0,\"kind\":\"COMMAND_STARTED\","
                + "\"sessionId\":\"session-a\",\"requestId\":\"request-1\","
                + "\"logicalTime\":1,\"frame\":1,\"revision\":1,"
                + "\"parentSequence\":null,\"evidence\":{}}\n")
                .getBytes(StandardCharsets.UTF_8);
        byte[] events = concat(line, line, line);
        long manifestBytes = v1ManifestBytes(3, events.length).length;
        Path archive = v1Archive(temporaryDirectory.resolve("short-budget.zip"), events, 3);

        HarnessException failure = assertThrows(HarnessException.class,
                () -> new TraceReplayer(new TraceReplayer.Limits(
                        1_000_000, 100, 1_048_576,
                        manifestBytes + line.length * 2L + line.length / 2, 1_000))
                        .load(archive));

        assertEquals(ErrorCode.LIMIT_EXCEEDED, failure.code());
        assertTrue(failure.getMessage().contains("cumulative"));
    }

    @Test void unreasonablePerEntryCompressionRatioIsRejected() throws Exception {
        byte[] inflated = "AAAAAAAAAABBBBBBBBBB".repeat(1_000)
                .getBytes(StandardCharsets.UTF_8);
        Path archive = temporaryDirectory.resolve("ratio.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.setLevel(9);
            zip.putNextEntry(new ZipEntry("events.ndjson"));
            zip.write(inflated);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(("{\"sessionId\":\"session-a\",\"startedAt\":"
                    + "\"2026-07-28T00:00:00Z\",\"endedAt\":"
                    + "\"2026-07-28T00:00:01Z\",\"complete\":false,"
                    + "\"terminationReason\":\"interrupted\",\"eventCount\":0,"
                    + "\"artifactCount\":0,\"uncompressedBytes\":" + inflated.length + "}")
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        HarnessException failure = assertThrows(HarnessException.class,
                () -> new TraceReplayer(new TraceReplayer.Limits(
                        1_000_000, 100, 1_048_576, 1_000_000, 10)).load(archive));

        assertEquals(ErrorCode.LIMIT_EXCEEDED, failure.code());
        assertTrue(failure.getMessage().contains("compression ratio"));
    }

    @Test void threeArgLimitsConstructorPreservesReleasedApiWithConservativeDefaults() {
        TraceReplayer.Limits limits = new TraceReplayer.Limits(100, 10, 1_000);

        assertEquals(100, limits.maxArchiveBytes());
        assertEquals(10, limits.maxEvents());
        assertEquals(1_000, limits.maxEventBytes());
        TraceReplayer.Limits defaults = TraceReplayer.Limits.defaults();
        assertEquals(defaults.maxTotalInflatedBytes(), limits.maxTotalInflatedBytes());
        assertEquals(defaults.maxCompressionRatio(), limits.maxCompressionRatio());
    }

    @Test void forgedCentralDirectorySizesCannotBypassCompressionRatioCheck() throws Exception {
        byte[] inflated = "AAAAAAAAAABBBBBBBBBB".repeat(1_000)
                .getBytes(StandardCharsets.UTF_8);
        byte[] manifest = v1ManifestBytes(0, inflated.length);
        // Forged central sizes claim compressed=2_000 so that inflated == 2_000 * 10
        // exactly: a central-directory-trusting ratio check would accept, while the
        // measured ~71-byte DEFLATE stream is far beyond the ratio-10 limit.
        Path archive = temporaryDirectory.resolve("forged.zip");
        Files.write(archive, forgeCentralDirectorySizes(
                zipBytes("events.ndjson", inflated, "manifest.json", manifest),
                "events.ndjson", 2_000, inflated.length));

        HarnessException failure = assertThrows(HarnessException.class,
                () -> new TraceReplayer(new TraceReplayer.Limits(
                        1_000_000, 100, 1_048_576, 1_000_000, 10))
                        .load(archive));

        assertEquals(ErrorCode.LIMIT_EXCEEDED, failure.code());
        assertTrue(failure.getMessage().contains("compression ratio"));

        // The identical archive with honest central sizes is comfortably within a
        // generous ratio limit, proving the forgery, not the payload, triggered it.
        Path honest = temporaryDirectory.resolve("honest.zip");
        Files.write(honest, zipBytes("events.ndjson", inflated, "manifest.json", manifest));
        assertDoesNotThrow(() -> new TraceReplayer(new TraceReplayer.Limits(
                1_000_000, 100, 1_048_576, 1_000_000, 1_000)).load(honest));
    }

    @Test void storedEntriesMeasureCompressionRatioOne() throws Exception {
        byte[] payload = "stored-payload".repeat(1_000)
                .getBytes(StandardCharsets.UTF_8);
        Path archive = temporaryDirectory.resolve("stored.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            ZipEntry stored = new ZipEntry("events.ndjson");
            stored.setMethod(ZipEntry.STORED);
            stored.setSize(payload.length);
            stored.setCompressedSize(payload.length);
            CRC32 crc = new CRC32();
            crc.update(payload);
            stored.setCrc(crc.getValue());
            zip.putNextEntry(stored);
            zip.write(payload);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(v1ManifestBytes(1, payload.length));
            zip.closeEntry();
        }

        // STORED entries measure ratio 1, so even a tight ratio-2 limit accepts them.
        assertDoesNotThrow(() -> new TraceReplayer(new TraceReplayer.Limits(
                1_000_000, 100, 1_048_576, 1_000_000, 2)).load(archive));
    }

    @Test void incompressibleDeflatedEntriesAreNotRejectedAsRatioBombs() throws Exception {
        byte[] payload = new byte[50_000];
        new java.util.Random(42).nextBytes(payload);
        Path archive = temporaryDirectory.resolve("random.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.setLevel(9);
            zip.putNextEntry(new ZipEntry("events.ndjson"));
            zip.write(payload);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(v1ManifestBytes(1, payload.length));
            zip.closeEntry();
        }

        // Random data does not compress: measured ratio is ~1, so a tight ratio-2
        // limit must not treat the archive as a compression bomb.
        assertDoesNotThrow(() -> new TraceReplayer(new TraceReplayer.Limits(
                1_000_000, 100, 1_048_576, 1_000_000, 2)).load(archive));
    }

    @Test void centralDirectoryAliasEntryIsRejected() throws Exception {
        byte[] line = ("{\"sequence\":0,\"kind\":\"COMMAND_STARTED\","
                + "\"sessionId\":\"session-a\",\"requestId\":\"request-1\","
                + "\"logicalTime\":1,\"frame\":1,\"revision\":1,"
                + "\"parentSequence\":null,\"evidence\":{}}\n")
                .getBytes(StandardCharsets.UTF_8);
        byte[] events = concat(line, line, line);
        Path archive = temporaryDirectory.resolve("alias.zip");
        Files.write(archive, renameCentralEntry(
                zipBytes("events.ndjson", events, "manifest.json",
                        v1ManifestBytes(3, events.length)),
                "manifest.json", "other-13.json"));

        HarnessException failure = assertThrows(HarnessException.class,
                () -> new TraceReplayer(new TraceReplayer.Limits(
                        1_000_000, 100, 1_048_576, 1_000_000, 1_000))
                        .load(archive));

        assertEquals(ErrorCode.INVALID_REQUEST, failure.code());
        assertTrue(failure.getMessage().contains("does not match"));
    }

    @Test void centralDirectoryDuplicateEntryIsRejected() throws Exception {
        byte[] line = ("{\"sequence\":0,\"kind\":\"COMMAND_STARTED\","
                + "\"sessionId\":\"session-a\",\"requestId\":\"request-1\","
                + "\"logicalTime\":1,\"frame\":1,\"revision\":1,"
                + "\"parentSequence\":null,\"evidence\":{}}\n")
                .getBytes(StandardCharsets.UTF_8);
        byte[] events = concat(line, line, line);
        Path archive = temporaryDirectory.resolve("duplicate.zip");
        Files.write(archive, renameCentralEntry(
                zipBytes("events.ndjson", events, "manifest.json",
                        v1ManifestBytes(3, events.length)),
                "manifest.json", "events.ndjson"));

        HarnessException failure = assertThrows(HarnessException.class,
                () -> new TraceReplayer(new TraceReplayer.Limits(
                        1_000_000, 100, 1_048_576, 1_000_000, 1_000))
                        .load(archive));

        assertEquals(ErrorCode.INVALID_REQUEST, failure.code());
        assertTrue(failure.getMessage().contains("duplicate"));
    }

    @Test void centralDirectorySwappedOffsetsFailIdentityVerification() throws Exception {
        byte[] line = ("{\"sequence\":0,\"kind\":\"COMMAND_STARTED\","
                + "\"sessionId\":\"session-a\",\"requestId\":\"request-1\","
                + "\"logicalTime\":1,\"frame\":1,\"revision\":1,"
                + "\"parentSequence\":null,\"evidence\":{}}\n")
                .getBytes(StandardCharsets.UTF_8);
        byte[] events = concat(line, line, line);
        Path archive = temporaryDirectory.resolve("swapped.zip");
        Files.write(archive, swapCentralEntryOffsets(
                zipBytes("events.ndjson", events, "manifest.json",
                        v1ManifestBytes(3, events.length)),
                "events.ndjson", "manifest.json"));

        // The central directory still names exactly events.ndjson and manifest.json,
        // but points each name at the other local header: the SHA-256 identity the
        // prepass recorded for manifest.json must not match the swapped content.
        HarnessException failure = assertThrows(HarnessException.class,
                () -> new TraceReplayer(new TraceReplayer.Limits(
                        1_000_000, 100, 1_048_576, 1_000_000, 1_000))
                        .load(archive));

        assertEquals(ErrorCode.INVALID_REQUEST, failure.code());
        assertTrue(failure.getMessage().contains("content"));
    }

    private static byte[] zipBytes(String firstName, byte[] firstContent,
            String secondName, byte[] secondContent) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.setLevel(9);
            zip.putNextEntry(new ZipEntry(firstName));
            zip.write(firstContent);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(secondName));
            zip.write(secondContent);
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    private static byte[] forgeCentralDirectorySizes(byte[] zip, String entryName,
            long compressedSize, long uncompressedSize) {
        byte[] forged = zip.clone();
        int cursor = findCentralEntry(forged, entryName);
        putLittleEndianInt(forged, cursor + 20, compressedSize);
        putLittleEndianInt(forged, cursor + 24, uncompressedSize);
        return forged;
    }

    private static byte[] renameCentralEntry(byte[] zip, String oldName, String newName) {
        if (oldName.length() != newName.length()) {
            throw new IllegalArgumentException("central rename must keep the name length");
        }
        byte[] forged = zip.clone();
        int cursor = findCentralEntry(forged, oldName);
        byte[] nameBytes = newName.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(nameBytes, 0, forged, cursor + 46, nameBytes.length);
        return forged;
    }

    private static byte[] swapCentralEntryOffsets(byte[] zip, String firstName,
            String secondName) {
        byte[] forged = zip.clone();
        int first = findCentralEntry(forged, firstName);
        int second = findCentralEntry(forged, secondName);
        int firstOffset = littleEndianInt(forged, first + 42);
        int secondOffset = littleEndianInt(forged, second + 42);
        putLittleEndianInt(forged, first + 42, secondOffset);
        putLittleEndianInt(forged, second + 42, firstOffset);
        return forged;
    }

    private static int findCentralEntry(byte[] zip, String name) {
        int eocd = endOfCentralDirectory(zip);
        int centralOffset = littleEndianInt(zip, eocd + 16);
        int entryCount = littleEndianShort(zip, eocd + 10);
        int cursor = centralOffset;
        for (int index = 0; index < entryCount; index++) {
            if (littleEndianInt(zip, cursor) != 0x02014b50) {
                throw new AssertionError("unexpected central-directory signature");
            }
            int nameLength = littleEndianShort(zip, cursor + 28);
            int extraLength = littleEndianShort(zip, cursor + 30);
            int commentLength = littleEndianShort(zip, cursor + 32);
            String entryName = new String(zip, cursor + 46, nameLength,
                    StandardCharsets.UTF_8);
            if (entryName.equals(name)) {
                return cursor;
            }
            cursor += 46 + nameLength + extraLength + commentLength;
        }
        throw new AssertionError("central-directory entry not found: " + name);
    }

    private static int endOfCentralDirectory(byte[] zip) {
        for (int index = zip.length - 22; index >= 0; index--) {
            if (littleEndianInt(zip, index) == 0x06054b50) {
                return index;
            }
        }
        throw new AssertionError("no end-of-central-directory record");
    }

    private static int littleEndianInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff)
                | (bytes[offset + 1] & 0xff) << 8
                | (bytes[offset + 2] & 0xff) << 16
                | (bytes[offset + 3] & 0xff) << 24;
    }

    private static int littleEndianShort(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | (bytes[offset + 1] & 0xff) << 8;
    }

    private static void putLittleEndianInt(byte[] bytes, int offset, long value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
        bytes[offset + 2] = (byte) (value >>> 16);
        bytes[offset + 3] = (byte) (value >>> 24);
    }

    private static byte[] v1ManifestBytes(long eventCount, long uncompressedBytes) {
        return ("{\"sessionId\":\"session-a\",\"startedAt\":"
                + "\"2026-07-28T00:00:00Z\",\"endedAt\":"
                + "\"2026-07-28T00:00:01Z\",\"complete\":true,"
                + "\"terminationReason\":\"completed\",\"eventCount\":" + eventCount + ","
                + "\"artifactCount\":0,\"uncompressedBytes\":" + uncompressedBytes + "}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static Path v1Archive(Path archive, byte[] events, long eventCount) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive),
                StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("events.ndjson"));
            zip.write(events);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(v1ManifestBytes(eventCount, events.length));
            zip.closeEntry();
        }
        return archive;
    }

    private static byte[] concat(byte[] first, byte[]... rest) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(first);
        for (byte[] part : rest) {
            out.write(part);
        }
        return out.toByteArray();
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
