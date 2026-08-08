package dev.gdx.uiharness.core.trace;

import dev.gdx.uiharness.core.error.ErrorCode;
import dev.gdx.uiharness.core.error.ErrorEvidence;
import dev.gdx.uiharness.core.error.HarnessException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/** Streams a bounded trace archive to validate its manifest and causal transitions.
 *  Entry names, duplicates, and per-entry compression ratios are checked against
 *  bytes measured directly from the archive streams, so forgeable central-directory
 *  size fields cannot bypass the limits. */
public final class TraceReplayer {
    private final Limits limits;

    /** Creates a replayer with conservative untrusted-archive limits. */
    public TraceReplayer() {
        this(Limits.defaults());
    }

    /** Creates a replayer with explicit untrusted-archive limits. */
    public TraceReplayer(Limits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /** Loads and validates one trace without retaining its event or artifact contents. */
    public TraceReplay load(Path suppliedArchive) {
        Objects.requireNonNull(suppliedArchive, "archive");
        Path archive = suppliedArchive.toAbsolutePath().normalize();
        validateArchiveFile(archive);
        try {
            if (Files.size(archive) > limits.maxArchiveBytes()) {
                throw failure(ErrorCode.LIMIT_EXCEEDED,
                        "Trace archive exceeds replay byte limit", null);
            }
            validateEntriesBounded(archive);
            try (ZipFile zip = new ZipFile(archive.toFile())) {
                ReplayBudget budget = new ReplayBudget();
                TraceManifest manifest = readManifest(archive, zip, budget);
                return readEvents(zip, manifest, budget);
            }
        } catch (HarnessException exception) {
            throw exception;
        } catch (IOException exception) {
            throw failure(ErrorCode.INVALID_REQUEST, "Trace archive is unreadable", exception);
        }
    }

    private TraceReplay readEvents(ZipFile zip, TraceManifest manifest, ReplayBudget budget)
            throws IOException {
        ZipEntry eventsEntry = zip.getEntry("events.ndjson");
        if (eventsEntry == null || eventsEntry.isDirectory()) {
            throw failure(ErrorCode.INVALID_REQUEST, "Trace archive is missing events.ndjson", null);
        }
        List<Long> revisions = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        Map<String, RequestState> activeRequests = new HashMap<>();
        long expectedSequence = 0;
        long lastLogicalTime = -1;
        long lastFrame = -1;
        long lastRevision = -1;
        boolean malformed = false;
        try (InputStream input = zip.getInputStream(eventsEntry)) {
            while (true) {
                byte[] line;
                try {
                    line = readBoundedLine(input, limits.maxEventBytes(), budget);
                } catch (LineLimitException exception) {
                    throw failure(ErrorCode.LIMIT_EXCEEDED,
                            "Trace event exceeds replay byte limit", exception);
                }
                if (line == null) {
                    break;
                }
                if (expectedSequence >= limits.maxEvents()) {
                    throw failure(ErrorCode.LIMIT_EXCEEDED,
                            "Trace exceeds replay event limit", null);
                }
                budget.recordContent(line.length + 1L);
                TraceEvent event;
                try {
                    event = TraceEvent.fromJson(line);
                } catch (IOException exception) {
                    diagnostics.add("malformed event " + expectedSequence + ": "
                            + exception.getMessage());
                    malformed = true;
                    break;
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
        }
        if (expectedSequence != manifest.eventCount()) {
            diagnostics.add("manifest event count " + manifest.eventCount()
                    + " differs from readable count " + expectedSequence);
        }
        if (manifest.complete() && !activeRequests.isEmpty()) {
            errors.add("complete trace has unfinished requests: " + activeRequests.keySet());
        }
        boolean partial = !manifest.complete() || malformed
                || expectedSequence != manifest.eventCount();
        return new TraceReplay(manifest, revisions, new TraceReplay.Causality(errors), partial,
                diagnostics);
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

    private TraceManifest readManifest(Path archive, ZipFile zip, ReplayBudget budget)
            throws IOException {
        ZipEntry entry = zip.getEntry("manifest.json");
        if (entry == null || entry.isDirectory()) {
            throw failure(ErrorCode.INVALID_REQUEST, "Trace archive is missing manifest.json", null);
        }
        try (InputStream input = zip.getInputStream(entry)) {
            ByteArrayOutputStream json = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                budget.charge(read);
                total += read;
                if (total > limits.maxEventBytes()) {
                    throw failure(ErrorCode.LIMIT_EXCEEDED,
                            "Trace manifest exceeds replay byte limit", null);
                }
                json.write(buffer, 0, read);
            }
            return TraceManifest.fromJson(archive, json.toByteArray());
        }
    }

    /** Rejects unsafe names, duplicates, and unreasonable per-entry compression
     *  ratios in one bounded streaming pass before any trusted parse. Sizes are
     *  measured from the actual DEFLATE streams, never from the forgeable
     *  central-directory fields. */
    private void validateEntriesBounded(Path archive) throws IOException {
        int ratioLimit = limits.maxCompressionRatio();
        try (InputStream raw = Files.newInputStream(archive);
                MeasuringZipInputStream zip = new MeasuringZipInputStream(raw)) {
            Set<String> names = new HashSet<>();
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
                if (name.isBlank() || name.startsWith("/") || name.startsWith("\\")
                        || name.contains("\\") || isDriveQualified(name)
                        || containsParentSegment(name)) {
                    throw failure(ErrorCode.INVALID_REQUEST,
                            "Trace archive contains an unsafe entry", null);
                }
                if (!names.add(name)) {
                    throw failure(ErrorCode.INVALID_REQUEST,
                            "Trace archive contains duplicate entries", null);
                }
                long inflated = 0;
                int read;
                while ((read = zip.read(buffer)) != -1) {
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
            }
        }
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
            ReplayBudget budget) throws IOException, LineLimitException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(Math.min(maximum, 1024));
        int value;
        while ((value = input.read()) != -1) {
            budget.charge(1);
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
