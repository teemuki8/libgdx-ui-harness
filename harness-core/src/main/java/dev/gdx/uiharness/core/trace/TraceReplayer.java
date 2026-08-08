package dev.gdx.uiharness.core.trace;

import dev.gdx.uiharness.core.error.ErrorCode;
import dev.gdx.uiharness.core.error.ErrorEvidence;
import dev.gdx.uiharness.core.error.HarnessException;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/** Streams a bounded trace archive to validate its manifest and causal transitions.
 *  Entry names, duplicates, per-entry compression ratios, and per-entry SHA-256
 *  identities are checked against bytes measured directly from the archive streams,
 *  and the central directory must match the local headers exactly, so forgeable
 *  central-directory fields cannot bypass the limits or substitute content. V2
 *  manifests additionally bind every event and artifact digest, size, and count to
 *  the archive bytes, so a load reports {@link TraceReplay.Integrity#VERIFIED} only
 *  when every binding matched. */
public final class TraceReplayer {
    private static final Pattern SHA256_PATTERN = Pattern.compile("[0-9a-f]{64}");
    private final Limits limits;
    private final Runnable afterSnapshotHook;

    /** Creates a replayer with conservative untrusted-archive limits. */
    public TraceReplayer() {
        this(Limits.defaults(), null);
    }

    /** Creates a replayer with explicit untrusted-archive limits. */
    public TraceReplayer(Limits limits) {
        this(limits, null);
    }

    /** Package-private test seam: runs once the untrusted archive bytes are safely
     *  captured in a private snapshot, before any parsing of those bytes. */
    TraceReplayer(Limits limits, Runnable afterSnapshotHook) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.afterSnapshotHook = afterSnapshotHook;
    }

    /** Loads and validates one trace without retaining its event or artifact contents.
     *  The untrusted archive is captured once into a private owner-only snapshot
     *  before any parsing, so a concurrent source replacement cannot change the
     *  bytes that are validated or the digest that is reported. */
    public TraceReplay load(Path suppliedArchive) {
        return load(suppliedArchive, null, -1);
    }

    /** Loads and validates one trace exactly like {@link #load(Path)}, additionally
     *  requiring the captured archive bytes to match a caller-supplied receipt.
     *  Either receipt field may be omitted with {@code null} (digest) or {@code -1}
     *  (size); a provided digest must be lowercase hex SHA-256 and a provided size
     *  non-negative, else {@link IllegalArgumentException}. Any mismatch between the
     *  captured archive digest or size and the receipt rejects the load with
     *  {@link ErrorCode#INVALID_REQUEST} immediately after capture, before the
     *  archive bytes are parsed. */
    public TraceReplay load(Path suppliedArchive, String expectedArchiveSha256,
            long expectedArchiveSize) {
        Objects.requireNonNull(suppliedArchive, "archive");
        if (expectedArchiveSha256 != null
                && !SHA256_PATTERN.matcher(expectedArchiveSha256).matches()) {
            throw new IllegalArgumentException(
                    "expected archive digest must be a SHA-256 or null");
        }
        if (expectedArchiveSize < -1) {
            throw new IllegalArgumentException(
                    "expected archive size must be -1 or non-negative");
        }
        Path archive = suppliedArchive.toAbsolutePath().normalize();
        validateArchiveFile(archive);
        Path snapshot = null;
        try {
            if (Files.size(archive) > limits.maxArchiveBytes()) {
                throw failure(ErrorCode.LIMIT_EXCEEDED,
                        "Trace archive exceeds replay byte limit", null);
            }
            snapshot = createPrivateSnapshot();
            Capture captured = captureSnapshot(archive, snapshot);
            if (expectedArchiveSha256 != null
                    && !captured.sha256().equals(expectedArchiveSha256)) {
                throw failure(ErrorCode.INVALID_REQUEST,
                        "Trace archive digest does not match the receipt", null);
            }
            if (expectedArchiveSize != -1 && captured.size() != expectedArchiveSize) {
                throw failure(ErrorCode.INVALID_REQUEST,
                        "Trace archive size does not match the receipt", null);
            }
            if (afterSnapshotHook != null) {
                afterSnapshotHook.run();
            }
            Map<String, EntryIdentity> identities = validateEntriesBounded(snapshot);
            try (ZipFile zip = new ZipFile(snapshot.toFile())) {
                validateCentralEntries(zip, identities.keySet());
                // one budget for the whole load: the manifest charges it first,
                // then artifact bindings and events share it
                ReplayBudget budget = new ReplayBudget();
                TraceManifest manifest = readManifest(archive, zip, budget, identities);
                boolean verifiedFormat = TraceManifest.V2.equals(manifest.schemaVersion());
                if (verifiedFormat) {
                    verifyBindings(zip, manifest, budget, identities.keySet());
                }
                TraceReplay replay = readEvents(zip, manifest, budget, identities,
                        captured.sha256(),
                        verifiedFormat
                                ? TraceReplay.Integrity.VERIFIED
                                : TraceReplay.Integrity.UNVERIFIED);
                deleteSnapshot(snapshot, null);
                snapshot = null;
                return replay;
            }
        } catch (HarnessException exception) {
            deleteSnapshot(snapshot, exception);
            throw exception;
        } catch (IOException exception) {
            deleteSnapshot(snapshot, exception);
            throw failure(ErrorCode.INVALID_REQUEST, "Trace archive is unreadable", exception);
        } catch (RuntimeException exception) {
            deleteSnapshot(snapshot, exception);
            throw exception;
        }
    }

    /** Copies the untrusted archive once into a private owner-only snapshot, hashing
     *  the exact captured bytes while enforcing the archive byte ceiling, so every
     *  later parse operates on immutable bytes that cannot be swapped out from under
     *  the replayer. */
    private Capture captureSnapshot(Path source, Path snapshot) throws IOException {
        MessageDigest digest = sha256();
        byte[] buffer = new byte[16 * 1024];
        long total = 0;
        try (InputStream input = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS);
                OutputStream output = Files.newOutputStream(snapshot)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > limits.maxArchiveBytes()) {
                    throw failure(ErrorCode.LIMIT_EXCEEDED,
                            "Trace archive exceeds replay byte limit", null);
                }
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        }
        return new Capture(HexFormat.of().formatHex(digest.digest()), total);
    }

    /** Exact SHA-256 and byte size of the captured archive bytes. */
    private record Capture(String sha256, long size) {}

    /** Creates a private owner-only temporary snapshot file in the system temp
     *  directory; non-POSIX filesystems fall back to the default temp permissions. */
    private static Path createPrivateSnapshot() throws IOException {
        Path snapshot = Files.createTempFile("trace-replay-", ".zip");
        try {
            Files.setPosixFilePermissions(snapshot, EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException exception) {
            // Non-POSIX filesystem: the default temp-directory permissions apply.
        }
        return snapshot;
    }

    /** Deletes the snapshot, aggregating a cleanup failure as a suppressed cause of
     *  an in-flight failure, or failing closed on the success path. */
    private static void deleteSnapshot(Path snapshot, Throwable failure) {
        if (snapshot == null) {
            return;
        }
        try {
            Files.deleteIfExists(snapshot);
        } catch (IOException cleanupFailure) {
            if (failure != null) {
                failure.addSuppressed(cleanupFailure);
            } else {
                throw failure(ErrorCode.INTERNAL_ERROR,
                        "Unable to clean up replay snapshot", cleanupFailure);
            }
        }
    }

    private TraceReplay readEvents(ZipFile zip, TraceManifest manifest, ReplayBudget budget,
            Map<String, EntryIdentity> identities, String archiveSha256,
            TraceReplay.Integrity integrity) throws IOException {
        ZipEntry eventsEntry = zip.getEntry("events.ndjson");
        if (eventsEntry == null || eventsEntry.isDirectory()) {
            throw failure(ErrorCode.INVALID_REQUEST, "Trace archive is missing events.ndjson", null);
        }
        boolean verifiedFormat = TraceReplay.Integrity.VERIFIED.equals(integrity);
        List<Long> revisions = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        Map<String, RequestState> activeRequests = new HashMap<>();
        long expectedSequence = 0;
        long lastLogicalTime = -1;
        long lastFrame = -1;
        long lastRevision = -1;
        boolean malformed = false;
        try (InputStream raw = zip.getInputStream(eventsEntry);
                CountingInputStream input = new CountingInputStream(raw)) {
            MessageDigest digest = sha256();
            while (true) {
                byte[] line;
                try {
                    line = readBoundedLine(input, limits.maxEventBytes(), budget, digest);
                } catch (LineLimitException exception) {
                    throw failure(ErrorCode.LIMIT_EXCEEDED,
                            "Trace event exceeds replay byte limit", exception);
                }
                if (line == null) {
                    break;
                }
                budget.recordContent(line.length + 1L);
                if (malformed) {
                    continue;
                }
                if (expectedSequence >= limits.maxEvents()) {
                    throw failure(ErrorCode.LIMIT_EXCEEDED,
                            "Trace exceeds replay event limit", null);
                }
                TraceEvent event;
                try {
                    event = TraceEvent.fromJson(line);
                } catch (IOException exception) {
                    diagnostics.add("malformed event " + expectedSequence + ": "
                            + exception.getMessage());
                    malformed = true;
                    continue;
                }
                validateEvent(event, manifest, expectedSequence, lastLogicalTime, lastFrame,
                        lastRevision, activeRequests, errors);
                if (event.revision() != null
                        && (revisions.isEmpty()
                        || revisions.get(revisions.size() - 1).longValue() != event.revision())) {
                    revisions.add(event.revision());
                }
                lastLogicalTime = Math.max(lastLogicalTime, event.logicalTime());
                if (event.frame() != null) {
                    lastFrame = Math.max(lastFrame, event.frame());
                }
                if (event.revision() != null) {
                    lastRevision = Math.max(lastRevision, event.revision());
                }
                expectedSequence++;
            }
            byte[] streamDigest = digest.digest();
            verifyIdentity("events.ndjson", streamDigest, input.count(), identities);
            if (verifiedFormat) {
                String actual = HexFormat.of().formatHex(streamDigest);
                if (!actual.equals(manifest.eventsSha256())) {
                    throw failure(ErrorCode.INVALID_REQUEST,
                            "Trace event digest does not match the manifest", null);
                }
                if (malformed) {
                    throw failure(ErrorCode.INVALID_REQUEST,
                            "Trace contains a malformed event", null);
                }
                if (expectedSequence != manifest.eventCount()) {
                    throw failure(ErrorCode.INVALID_REQUEST,
                            "Trace event count does not match the manifest", null);
                }
                if (budget.contentBytes() != manifest.uncompressedBytes()) {
                    throw failure(ErrorCode.INVALID_REQUEST,
                            "Trace uncompressed byte count does not match the manifest", null);
                }
            } else if (expectedSequence != manifest.eventCount()) {
                diagnostics.add("manifest event count " + manifest.eventCount()
                        + " differs from readable count " + expectedSequence);
            }
        }
        if (manifest.complete() && !activeRequests.isEmpty()) {
            errors.add("complete trace has unfinished requests: " + activeRequests.keySet());
        }
        boolean partial = !manifest.complete() || malformed
                || (!verifiedFormat && expectedSequence != manifest.eventCount());
        return new TraceReplay(manifest, revisions, new TraceReplay.Causality(errors), partial,
                diagnostics, archiveSha256, integrity);
    }

    private static void validateEvent(
            TraceEvent event,
            TraceManifest manifest,
            long expectedSequence,
            long lastLogicalTime,
            long lastFrame,
            long lastRevision,
            Map<String, RequestState> activeRequests,
            List<String> errors) {
        if (event.sequence() != expectedSequence) {
            errors.add("event sequence " + event.sequence()
                    + " does not match expected " + expectedSequence);
        }
        if (!manifest.sessionId().equals(event.sessionId())) {
            errors.add("event " + event.sequence() + " has a different session");
        }
        if (event.logicalTime() < lastLogicalTime) {
            errors.add("event " + event.sequence() + " logical time moved backwards");
        }
        if (event.frame() != null && event.frame() < lastFrame) {
            errors.add("event " + event.sequence() + " frame moved backwards");
        }
        if (event.revision() != null && event.revision() < lastRevision) {
            errors.add("event " + event.sequence() + " revision moved backwards");
        }
        if (event.parentSequence() != null
                && event.parentSequence() >= expectedSequence) {
            errors.add("event " + event.sequence() + " parent " + event.parentSequence()
                    + " is not an earlier event");
        }
        switch (event.kind()) {
            case COMMAND_STARTED -> validateStart(event, activeRequests, errors);
            case INPUT_DISPATCHED, COMMAND_COMPLETED, COMMAND_FAILED ->
                    validateRequestChild(event, activeRequests, errors);
            case SNAPSHOT, LOG -> {
                // Standalone evidence still participates in sequence and logical-time validation.
            }
        }
    }

    private static void validateStart(
            TraceEvent event,
            Map<String, RequestState> activeRequests,
            List<String> errors) {
        if (event.requestId() == null) {
            errors.add("command start " + event.sequence() + " has no request");
            return;
        }
        if (event.parentSequence() != null) {
            errors.add("command start " + event.sequence() + " unexpectedly has a parent");
        }
        RequestState previous = activeRequests.put(event.requestId(),
                new RequestState(event.sequence()));
        if (previous != null) {
            errors.add("request " + event.requestId() + " started more than once");
        }
    }

    private static void validateRequestChild(
            TraceEvent event,
            Map<String, RequestState> activeRequests,
            List<String> errors) {
        if (event.requestId() == null) {
            errors.add("event " + event.sequence() + " has no request");
            return;
        }
        RequestState request = activeRequests.get(event.requestId());
        if (request == null) {
            errors.add("event " + event.sequence() + " request " + event.requestId()
                    + " has no command start");
            return;
        }
        if (event.parentSequence() == null) {
            errors.add("event " + event.sequence() + " has no causal parent");
        } else if (event.parentSequence() != request.lastSequence()) {
            errors.add("event " + event.sequence() + " parent " + event.parentSequence()
                    + " does not match request predecessor " + request.lastSequence());
        }
        if (event.kind() == TraceEvent.Kind.COMMAND_COMPLETED
                || event.kind() == TraceEvent.Kind.COMMAND_FAILED) {
            activeRequests.remove(event.requestId());
        } else {
            activeRequests.put(event.requestId(), new RequestState(event.sequence()));
        }
    }

    private TraceManifest readManifest(Path archive, ZipFile zip, ReplayBudget budget,
            Map<String, EntryIdentity> identities) throws IOException {
        ZipEntry entry = zip.getEntry("manifest.json");
        if (entry == null || entry.isDirectory()) {
            throw failure(ErrorCode.INVALID_REQUEST, "Trace archive is missing manifest.json", null);
        }
        try (InputStream input = zip.getInputStream(entry)) {
            ByteArrayOutputStream json = new ByteArrayOutputStream();
            MessageDigest digest = sha256();
            byte[] buffer = new byte[4096];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                budget.charge(read);
                digest.update(buffer, 0, read);
                total += read;
                if (total > limits.maxEventBytes()) {
                    throw failure(ErrorCode.LIMIT_EXCEEDED,
                            "Trace manifest exceeds replay byte limit", null);
                }
                json.write(buffer, 0, read);
            }
            verifyIdentity("manifest.json", digest.digest(), total, identities);
            return TraceManifest.fromJson(archive, json.toByteArray());
        }
    }

    /** Streams every bound artifact entry once, recomputing its SHA-256 and byte
     *  count against the manifest binding, and requires the archive entry set to be
     *  exactly the v2 allowlist (manifest.json, events.ndjson, and the declared
     *  artifact entries), so arbitrary safe extras and directories cannot ride along.
     *  The events digest is verified in the single parse pass inside readEvents,
     *  where the entry bytes are exactly Σ(line + '\n'). */
    private void verifyBindings(ZipFile zip, TraceManifest manifest, ReplayBudget budget,
            Set<String> localNames) throws IOException {
        Set<String> allowlist = new HashSet<>();
        allowlist.add("manifest.json");
        allowlist.add("events.ndjson");
        for (Map.Entry<String, TraceManifest.ArtifactBinding> binding
                : manifest.artifacts().entrySet()) {
            if (!binding.getKey().equals(binding.getValue().sha256())) {
                throw failure(ErrorCode.INVALID_REQUEST,
                        "Trace artifact identity is not its digest", null);
            }
            ZipEntry artifactEntry = zip.getEntry("artifacts/" + binding.getKey());
            if (artifactEntry == null || artifactEntry.isDirectory()) {
                throw failure(ErrorCode.INVALID_REQUEST,
                        "Trace artifact " + binding.getKey() + " is missing from the archive",
                        null);
            }
            EntryDigest actual = digestEntry(zip, artifactEntry, budget, sha256());
            if (!actual.sha256().equals(binding.getValue().sha256())) {
                throw failure(ErrorCode.INVALID_REQUEST,
                        "Trace artifact digest does not match the manifest", null);
            }
            if (actual.size() != binding.getValue().size()) {
                throw failure(ErrorCode.INVALID_REQUEST,
                        "Trace artifact size does not match the manifest", null);
            }
            allowlist.add("artifacts/" + binding.getKey());
        }
        // local and central entry sets are equal (validateCentralEntries), so this
        // single pass covers every local and central entry
        for (String name : localNames) {
            if (!allowlist.contains(name)) {
                throw failure(ErrorCode.INVALID_REQUEST,
                        "Trace archive contains an undeclared entry", null);
            }
        }
        if (manifest.artifactCount() != manifest.artifacts().size()) {
            throw failure(ErrorCode.INVALID_REQUEST,
                    "Trace artifact count does not match the manifest bindings", null);
        }
    }

    /** Digest and observed byte count of one streamed artifact entry. */
    private record EntryDigest(String sha256, long size) {}

    private static EntryDigest digestEntry(ZipFile zip, ZipEntry entry,
            ReplayBudget budget, MessageDigest digest) throws IOException {
        long total = 0;
        byte[] buffer = new byte[16 * 1024];
        try (InputStream input = zip.getInputStream(entry)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                budget.charge(read);
                budget.recordContent(read);
                digest.update(buffer, 0, read);
                total += read;
            }
        }
        if (total != entry.getSize() && entry.getSize() != -1) {
            throw failure(ErrorCode.INVALID_REQUEST,
                    "Trace entry size changed while streaming", null);
        }
        return new EntryDigest(HexFormat.of().formatHex(digest.digest()), total);
    }

    /** Rejects unsafe names, duplicates, and unreasonable per-entry compression
     *  ratios in one bounded streaming pass before any trusted parse, recording a
     *  SHA-256 identity per entry so the central-directory parse cannot substitute
     *  different content for the same name. Sizes are measured from the actual
     *  DEFLATE streams, never from the forgeable central-directory fields. */
    private Map<String, EntryIdentity> validateEntriesBounded(Path archive) throws IOException {
        int ratioLimit = limits.maxCompressionRatio();
        try (InputStream raw = Files.newInputStream(archive);
                MeasuringZipInputStream zip = new MeasuringZipInputStream(raw)) {
            Set<String> names = new HashSet<>();
            Map<String, EntryIdentity> identities = new HashMap<>();
            byte[] buffer = new byte[8192];
            long inflatedTotal = 0;
            int entries = 0;
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries++;
                if (entries > limits.maxEvents() + 10_000L) {
                    throw failure(ErrorCode.LIMIT_EXCEEDED,
                            "Trace archive contains too many entries", null);
                }
                String name = entry.getName();
                if (isUnsafeName(name)) {
                    throw failure(ErrorCode.INVALID_REQUEST,
                            "Trace archive contains an unsafe entry", null);
                }
                if (!names.add(name)) {
                    throw failure(ErrorCode.INVALID_REQUEST,
                            "Trace archive contains duplicate entries", null);
                }
                MessageDigest digest = sha256();
                long inflated = 0;
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                    inflated += read;
                    inflatedTotal += read;
                    if (inflatedTotal > limits.maxTotalInflatedBytes()) {
                        throw failure(ErrorCode.LIMIT_EXCEEDED,
                                "Trace exceeds cumulative inflated byte limit", null);
                    }
                }
                long stored = entry.getMethod() == ZipEntry.STORED
                        ? inflated : zip.compressedBytes();
                if (stored > 0 && inflated > 0
                        && (inflated / ratioLimit > stored
                        || (inflated / ratioLimit == stored
                        && inflated % ratioLimit != 0))) {
                    throw failure(ErrorCode.LIMIT_EXCEEDED,
                            "Trace entry compression ratio exceeds replay limit", null);
                }
                identities.put(name, new EntryIdentity(digest.digest(), inflated));
            }
            return identities;
        }
    }

    /** Rejects unsafe, duplicate, or extra central-directory entries and requires
     *  the central entry set to match the local headers exactly, so aliases and
     *  central-only entries cannot hide from the local prepass. */
    private void validateCentralEntries(ZipFile zip, Set<String> localNames) {
        Set<String> centralNames = new HashSet<>();
        int entries = 0;
        var enumeration = zip.entries();
        while (enumeration.hasMoreElements()) {
            ZipEntry entry = enumeration.nextElement();
            String name = entry.getName();
            entries++;
            if (entries > limits.maxEvents() + 10_000L) {
                throw failure(ErrorCode.LIMIT_EXCEEDED,
                        "Trace archive contains too many entries", null);
            }
            if (isUnsafeName(name)) {
                throw failure(ErrorCode.INVALID_REQUEST,
                        "Trace archive contains an unsafe entry", null);
            }
            if (!centralNames.add(name)) {
                throw failure(ErrorCode.INVALID_REQUEST,
                        "Trace archive contains duplicate entries", null);
            }
        }
        if (!centralNames.equals(localNames)) {
            throw failure(ErrorCode.INVALID_REQUEST,
                    "Trace archive entry set does not match its local headers", null);
        }
    }

    private static boolean isUnsafeName(String name) {
        return name.isBlank() || name.startsWith("/") || name.startsWith("\\")
                || name.contains("\\") || isDriveQualified(name)
                || containsParentSegment(name);
    }

    private static boolean isDriveQualified(String name) {
        return name.length() >= 3
                && Character.isLetter(name.charAt(0))
                && name.charAt(1) == ':'
                && name.charAt(2) == '/';
    }

    private static boolean containsParentSegment(String name) {
        for (String segment : name.split("/", -1)) {
            if (segment.equals("..")) {
                return true;
            }
        }
        return false;
    }

    private static byte[] readBoundedLine(InputStream input, int maximum,
            ReplayBudget budget, MessageDigest digest)
            throws IOException, LineLimitException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(Math.min(maximum, 1024));
        int value;
        while ((value = input.read()) != -1) {
            budget.charge(1);
            digest.update((byte) value);
            if (value == '\n') {
                return line.toByteArray();
            }
            if (line.size() >= maximum) {
                throw new LineLimitException();
            }
            line.write(value);
        }
        return line.size() == 0 ? null : line.toByteArray();
    }

    private static void validateArchiveFile(Path archive) {
        if (Files.isSymbolicLink(archive)
                || !Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)) {
            throw failure(ErrorCode.INVALID_REQUEST, "Trace archive must be a regular file", null);
        }
    }

    private static HarnessException failure(ErrorCode code, String message, Throwable cause) {
        return new HarnessException(code, message,
                ErrorEvidence.ofDetails(Map.of("component", "trace-replay")), cause);
    }

    /** Hard bounds applied before and during untrusted archive parsing. */
    public record Limits(long maxArchiveBytes, long maxEvents, int maxEventBytes,
            long maxTotalInflatedBytes, int maxCompressionRatio) {
        /** Validates positive replay bounds. */
        public Limits {
            if (maxArchiveBytes <= 0 || maxEvents <= 0 || maxEventBytes <= 0
                    || maxTotalInflatedBytes <= 0 || maxCompressionRatio < 1) {
                throw new IllegalArgumentException("replay limits must be positive");
            }
        }

        /** Conservative defaults for local replay. */
        public static Limits defaults() {
            return new Limits(
                    128L * 1024 * 1024, 100_000, TraceEvent.MAX_ENCODED_BYTES,
                    128L * 1024 * 1024, 100);
        }

        /** Backward-compatible bounds for callers that only tune archive, event,
         *  and line sizes: the cumulative inflated-byte ceiling and the per-entry
         *  compression-ratio limit take the conservative defaults(). */
        public Limits(long maxArchiveBytes, long maxEvents, int maxEventBytes) {
            this(maxArchiveBytes, maxEvents, maxEventBytes,
                    defaults().maxTotalInflatedBytes(),
                    defaults().maxCompressionRatio());
        }
    }

    /** Cumulative inflated-byte accounting for one archive load. */
    private final class ReplayBudget {
        private long inflatedBytes;
        private long contentBytes;

        void charge(long bytes) {
            if (bytes < 0 || inflatedBytes > limits.maxTotalInflatedBytes() - bytes) {
                throw failure(ErrorCode.LIMIT_EXCEEDED,
                        "Trace exceeds cumulative inflated byte limit", null);
            }
            inflatedBytes += bytes;
        }

        void recordContent(long bytes) {
            contentBytes += bytes;
        }

        long contentBytes() {
            return contentBytes;
        }
    }

    private record RequestState(long lastSequence) {}

    /** Immutable SHA-256 identity of one entry's inflated content, recorded by the
     *  local prepass and verified while the central-directory parse streams it. */
    private record EntryIdentity(byte[] sha256, long inflatedSize) {}

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static void verifyIdentity(String name, byte[] sha256, long inflatedSize,
            Map<String, EntryIdentity> identities) {
        EntryIdentity expected = identities.get(name);
        if (expected == null || expected.inflatedSize() != inflatedSize
                || !MessageDigest.isEqual(expected.sha256(), sha256)) {
            throw failure(ErrorCode.INVALID_REQUEST,
                    "Trace archive entry content does not match its local header", null);
        }
    }

    /** InputStream that counts the bytes actually delivered, with no buffering. */
    private static final class CountingInputStream extends FilterInputStream {
        private long count;

        CountingInputStream(InputStream input) {
            super(input);
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value != -1) {
                count++;
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = super.read(bytes, offset, length);
            if (read > 0) {
                count += read;
            }
            return read;
        }

        long count() {
            return count;
        }
    }

    /** ZipInputStream that reports the compressed bytes actually consumed per
     *  entry, immune to forgeable central-directory size fields. */
    private static final class MeasuringZipInputStream extends ZipInputStream {
        private long compressedStart;

        MeasuringZipInputStream(InputStream input) {
            super(input);
        }

        @Override
        public ZipEntry getNextEntry() throws IOException {
            ZipEntry entry = super.getNextEntry();
            compressedStart = inf.getBytesRead();
            return entry;
        }

        long compressedBytes() {
            return inf.getBytesRead() - compressedStart;
        }
    }

    @SuppressWarnings("serial")
    private static final class LineLimitException extends Exception {}
}
