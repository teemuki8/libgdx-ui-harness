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

/** Streams a bounded trace archive to validate its manifest and causal transitions. */
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
            try (ZipFile zip = new ZipFile(archive.toFile())) {
                validateEntries(zip);
                TraceManifest manifest = readManifest(archive, zip);
                return readEvents(zip, manifest);
            }
        } catch (HarnessException exception) {
            throw exception;
        } catch (IOException exception) {
            throw failure(ErrorCode.INVALID_REQUEST, "Trace archive is unreadable", exception);
        }
    }

    private TraceReplay readEvents(ZipFile zip, TraceManifest manifest) throws IOException {
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
                    line = readBoundedLine(input, limits.maxEventBytes());
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

    private TraceManifest readManifest(Path archive, ZipFile zip) throws IOException {
        ZipEntry entry = zip.getEntry("manifest.json");
        if (entry == null || entry.isDirectory()) {
            throw failure(ErrorCode.INVALID_REQUEST, "Trace archive is missing manifest.json", null);
        }
        try (InputStream input = zip.getInputStream(entry)) {
            byte[] json = input.readNBytes(limits.maxEventBytes() + 1);
            if (json.length > limits.maxEventBytes() || input.read() != -1) {
                throw failure(ErrorCode.LIMIT_EXCEEDED,
                        "Trace manifest exceeds replay byte limit", null);
            }
            return TraceManifest.fromJson(archive, json);
        }
    }

    private void validateEntries(ZipFile zip) {
        Set<String> names = new HashSet<>();
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

    private static byte[] readBoundedLine(InputStream input, int maximum)
            throws IOException, LineLimitException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(Math.min(maximum, 1024));
        int value;
        while ((value = input.read()) != -1) {
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
    public record Limits(long maxArchiveBytes, long maxEvents, int maxEventBytes) {
        /** Validates positive replay bounds. */
        public Limits {
            if (maxArchiveBytes <= 0 || maxEvents <= 0 || maxEventBytes <= 0) {
                throw new IllegalArgumentException("replay limits must be positive");
            }
        }

        /** Conservative defaults for local replay. */
        public static Limits defaults() {
            return new Limits(
                    128L * 1024 * 1024, 100_000, TraceEvent.MAX_ENCODED_BYTES);
        }
    }

    private record RequestState(long lastSequence) {}

    @SuppressWarnings("serial")
    private static final class LineLimitException extends Exception {}
}
