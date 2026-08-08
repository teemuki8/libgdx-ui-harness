package dev.gdx.uiharness.core.trace;

import dev.gdx.uiharness.core.error.ErrorCode;
import dev.gdx.uiharness.core.error.ErrorEvidence;
import dev.gdx.uiharness.core.error.HarnessException;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Streams bounded causal events and evidence into atomically published ZIP traces. */
public final class TraceRecorder implements AutoCloseable {
    private static final int COPY_BUFFER_SIZE = 16 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern FILE_URI = Pattern.compile("file:(?://)?[^\\s\\\",}]+");
    private static final Pattern WINDOWS_PATH =
            Pattern.compile("(?i)[a-z]:\\\\[^\\s\\\",}]+");
    private static final Pattern UNIX_PATH =
            Pattern.compile("(?<![a-zA-Z0-9])/(?:[^\\s\\\",}]+)");
    private static final Pattern STACK_FRAME =
            Pattern.compile("\\bat\\s+[^\\s]+\\([^)]*\\)");

    private final Path root;
    private final Path realRoot;
    private final Clock clock;
    private final Map<String, ArtifactInfo> artifacts = new LinkedHashMap<>();
    private OutputStream eventOutput;
    private Path stagingDirectory;
    private Path realStagingDirectory;
    private Path artifactDirectory;
    private Path realArtifactDirectory;
    private Path eventFile;
    private String sessionId;
    private Limits limits;
    private Instant startedAt;
    private long eventCount;
    private long uncompressedBytes;
    private MessageDigest eventDigest;
    private boolean active;
    private long generation;
    private TraceManifest lastManifest;

    /** Creates a recorder rooted below one non-symbolic-link server-owned directory. */
    public TraceRecorder(Path root, Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.root = initializeRoot(root);
        try {
            realRoot = this.root.toRealPath();
        } catch (IOException exception) {
            throw new IllegalArgumentException("trace root cannot be resolved", exception);
        }
    }

    /** Begins one trace. A recorder has at most one active trace. */
    public synchronized void start(String newSessionId, Limits newLimits) {
        if (active) {
            throw new IllegalStateException("a trace is already active");
        }
        Objects.requireNonNull(newLimits, "limits");
        requireText(newSessionId, "sessionId");
        verifyRoot();
        artifacts.clear();
        sessionId = newSessionId;
        limits = newLimits;
        startedAt = clock.instant();
        eventCount = 0;
        uncompressedBytes = 0;
        eventDigest = sha256();
        lastManifest = null;
        generation++;
        try {
            stagingDirectory = Files.createDirectory(
                    root.resolve(".trace-" + randomHex(16) + ".tmp"));
            realStagingDirectory = stagingDirectory.toRealPath();
            artifactDirectory = Files.createDirectory(stagingDirectory.resolve("artifacts"));
            realArtifactDirectory = artifactDirectory.toRealPath();
            eventFile = stagingDirectory.resolve("events.ndjson");
            eventOutput = new BufferedOutputStream(Files.newOutputStream(eventFile,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE));
            active = true;
        } catch (IOException exception) {
            cleanupStaging();
            throw failure(ErrorCode.INTERNAL_ERROR, "Unable to start trace recording", exception);
        }
    }

    /** Streams one redacted event line and returns its effective sequence number. */
    public synchronized long record(TraceEvent suppliedEvent) {
        requireActive();
        checkDuration();
        Objects.requireNonNull(suppliedEvent, "event");
        if (eventCount >= limits.maxEvents()) {
            throw failLimit("event limit exceeded");
        }
        long sequence = suppliedEvent.sequence() == -1 ? eventCount : suppliedEvent.sequence();
        TraceEvent event = suppliedEvent.withSequence(sequence)
                .withEvidence(redact(suppliedEvent.evidence()));
        byte[] encoded = event.toJson();
        if (encoded.length > TraceEvent.MAX_ENCODED_BYTES) {
            throw failLimit("event line limit exceeded");
        }
        long added = encoded.length + 1L;
        if (added > limits.maxUncompressedBytes()
                || uncompressedBytes > limits.maxUncompressedBytes() - added) {
            throw failLimit("byte limit exceeded");
        }
        try {
            eventOutput.write(encoded);
            eventOutput.write('\n');
            eventOutput.flush();
        } catch (IOException exception) {
            interruptAfterFailure("event write failed", exception);
            throw failure(ErrorCode.INTERNAL_ERROR, "Unable to write trace event", exception);
        }
        eventDigest.update(encoded);
        eventDigest.update((byte) '\n');
        uncompressedBytes += added;
        eventCount++;
        return sequence;
    }

    /** Streams and SHA-256 deduplicates one artifact without invoking its source under a lock. */
    public String addArtifact(String mediaType, InputStream source) {
        Objects.requireNonNull(source, "source");
        ArtifactReservation reservation;
        try {
            synchronized (this) {
                requireActive();
                checkDuration();
                requireText(mediaType, "mediaType");
                Path temporary = artifactDirectory
                        .resolve(".artifact-" + randomHex(16) + ".tmp");
                reservation = new ArtifactReservation(
                        generation, stagingDirectory, artifactDirectory,
                        realStagingDirectory, realArtifactDirectory, temporary,
                        mediaType, limits.maxUncompressedBytes());
            }
        } catch (RuntimeException exception) {
            closeSourceAfterValidationFailure(source, exception);
            throw exception;
        }

        MessageDigest digest = sha256();
        long size = 0;
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        try (source; OutputStream output = new BufferedOutputStream(Files.newOutputStream(
                reservation.temporary(), StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE))) {
            int read;
            while ((read = source.read(buffer)) != -1) {
                if (size > reservation.maxUncompressedBytes() - read) {
                    throw new ArtifactLimitException();
                }
                output.write(buffer, 0, read);
                digest.update(buffer, 0, read);
                size += read;
            }
        } catch (ArtifactLimitException exception) {
            return failStreamedArtifact(reservation, "byte limit exceeded", exception, true);
        } catch (IOException exception) {
            return failStreamedArtifact(reservation, "artifact write failed", exception, false);
        } catch (RuntimeException exception) {
            return failStreamedArtifact(reservation, "artifact callback failed", exception, false);
        }

        String hash = HexFormat.of().formatHex(digest.digest());
        synchronized (this) {
            if (!matchesActiveReservation(reservation)) {
                deleteIfExists(reservation.temporary());
                cleanupDetachedReservation(reservation);
                throw failure(ErrorCode.SESSION_CLOSED,
                        "Trace closed while artifact evidence was streaming", null);
            }
            Duration elapsed = Duration.between(startedAt, clock.instant());
            if (elapsed.isNegative() || elapsed.compareTo(limits.maxDuration()) >= 0) {
                deleteIfExists(reservation.temporary());
                throw failLimit("duration limit exceeded");
            }
            ArtifactInfo existing = artifacts.get(hash);
            if (existing != null) {
                deleteIfExists(reservation.temporary());
                return hash;
            }
            if (uncompressedBytes > limits.maxUncompressedBytes() - size) {
                deleteIfExists(reservation.temporary());
                throw failLimit("byte limit exceeded");
            }
            Path published = reservation.artifactDirectory().resolve(hash);
            try {
                Files.move(reservation.temporary(), published, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                deleteIfExists(reservation.temporary());
                interruptAfterFailure("artifact atomic publish unsupported", exception);
                throw failure(ErrorCode.INTERNAL_ERROR,
                        "Artifact storage does not support atomic publication", exception);
            } catch (IOException exception) {
                deleteIfExists(reservation.temporary());
                interruptAfterFailure("artifact publish failed", exception);
                throw failure(ErrorCode.INTERNAL_ERROR,
                        "Unable to publish trace artifact", exception);
            }
            artifacts.put(hash, new ArtifactInfo(published, mediaType, size));
            uncompressedBytes += size;
            return hash;
        }
    }

    /** Finalizes and atomically publishes a complete trace. */
    public synchronized TraceManifest stop() {
        requireActive();
        checkDuration();
        return finalizeTrace(true, "completed");
    }

    /** Returns the most recently finalized complete or partial manifest. */
    public synchronized Optional<TraceManifest> lastManifest() {
        return Optional.ofNullable(lastManifest);
    }

    /** Finalizes an active trace as interrupted. Repeated close is safe. */
    @Override public synchronized void close() {
        if (active) {
            finalizeTrace(false, "interrupted");
        }
    }

    private TraceManifest finalizeTrace(boolean complete, String reason) {
        active = false;
        closeEventOutput();
        try {
            verifyRoot();
            verifyStaging();
        } catch (HarnessException exception) {
            cleanupStaging();
            throw exception;
        }
        Instant endedAt = clock.instant();
        if (endedAt.isBefore(startedAt)) {
            endedAt = startedAt;
        }
        Path temporaryArchive = root.resolve(".trace-" + randomHex(16) + ".zip.tmp");
        Path archive = root.resolve("trace-" + randomHex(16) + ".zip");
        LinkedHashMap<String, TraceManifest.ArtifactBinding> bindings = new LinkedHashMap<>();
        for (Map.Entry<String, ArtifactInfo> entry : artifacts.entrySet()) {
            bindings.put(entry.getKey(), new TraceManifest.ArtifactBinding(
                    entry.getKey(), entry.getValue().size(), entry.getValue().mediaType()));
        }
        TraceManifest manifest = new TraceManifest(archive, sessionId, startedAt, endedAt,
                complete, reason, eventCount, artifacts.size(), uncompressedBytes,
                TraceManifest.V2, HexFormat.of().formatHex(eventDigest.digest()), bindings);
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(
                Files.newOutputStream(temporaryArchive, StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE)), StandardCharsets.UTF_8)) {
            copyEntry(zip, "events.ndjson", eventFile);
            for (Map.Entry<String, ArtifactInfo> entry : artifacts.entrySet()) {
                copyEntry(zip, "artifacts/" + entry.getKey(), entry.getValue().path());
            }
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(manifest.toJson());
            zip.closeEntry();
        } catch (IOException exception) {
            deleteIfExists(temporaryArchive);
            cleanupStaging();
            throw failure(ErrorCode.INTERNAL_ERROR, "Unable to finalize trace archive", exception);
        }
        try {
            Files.move(temporaryArchive, archive, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            deleteIfExists(temporaryArchive);
            cleanupStaging();
            throw failure(ErrorCode.INTERNAL_ERROR,
                    "Trace storage does not support atomic publication", exception);
        } catch (IOException exception) {
            deleteIfExists(temporaryArchive);
            cleanupStaging();
            throw failure(ErrorCode.INTERNAL_ERROR, "Unable to publish trace archive", exception);
        }
        lastManifest = manifest;
        cleanupStaging();
        return manifest;
    }

    private void copyEntry(ZipOutputStream zip, String name, Path source) throws IOException {
        verifyOwnedRegularFile(source);
        zip.putNextEntry(new ZipEntry(name));
        try (InputStream input = Files.newInputStream(source, StandardOpenOption.READ)) {
            input.transferTo(zip);
        }
        zip.closeEntry();
    }

    private String failStreamedArtifact(
            ArtifactReservation reservation,
            String reason,
            Exception originalFailure,
            boolean limitFailure) {
        deleteIfExists(reservation.temporary());
        synchronized (this) {
            if (!matchesActiveReservation(reservation)) {
                cleanupDetachedReservation(reservation);
                throw failure(ErrorCode.SESSION_CLOSED,
                        "Trace closed while artifact evidence was streaming",
                        originalFailure);
            }
            if (limitFailure) {
                throw failLimit(reason);
            }
            interruptAfterFailure(reason, originalFailure);
            throw failure(ErrorCode.INTERNAL_ERROR,
                    "Unable to stream trace artifact evidence", originalFailure);
        }
    }

    private boolean matchesActiveReservation(ArtifactReservation reservation) {
        return active
                && generation == reservation.generation()
                && reservation.stagingDirectory().equals(stagingDirectory)
                && reservation.artifactDirectory().equals(artifactDirectory);
    }

    private void cleanupDetachedReservation(ArtifactReservation reservation) {
        if (isUntamperedDirectory(
                reservation.artifactDirectory(), reservation.realArtifactDirectory())) {
            deleteIfExists(reservation.artifactDirectory());
        }
        if (isUntamperedDirectory(
                reservation.stagingDirectory(), reservation.realStagingDirectory())) {
            deleteIfExists(reservation.stagingDirectory().resolve("events.ndjson"));
            deleteIfExists(reservation.stagingDirectory());
        }
    }

    private static void closeSourceAfterValidationFailure(
            InputStream source, RuntimeException originalFailure) {
        try {
            source.close();
        } catch (IOException closeFailure) {
            originalFailure.addSuppressed(closeFailure);
        }
    }

    private HarnessException failLimit(String reason) {
        HarnessException failure = failure(ErrorCode.LIMIT_EXCEEDED,
                "Trace " + reason, null);
        try {
            finalizeTrace(false, reason);
        } catch (HarnessException finalizationFailure) {
            failure.addSuppressed(finalizationFailure);
        }
        return failure;
    }

    private void checkDuration() {
        Duration elapsed = Duration.between(startedAt, clock.instant());
        if (elapsed.isNegative() || elapsed.compareTo(limits.maxDuration()) >= 0) {
            throw failLimit("duration limit exceeded");
        }
    }

    private void interruptAfterFailure(String reason, Exception originalFailure) {
        if (!active) {
            return;
        }
        try {
            finalizeTrace(false, reason);
        } catch (HarnessException finalizationFailure) {
            originalFailure.addSuppressed(finalizationFailure);
        }
    }

    private void closeEventOutput() {
        if (eventOutput == null) {
            return;
        }
        try {
            eventOutput.close();
        } catch (IOException exception) {
            cleanupStaging();
            throw failure(ErrorCode.INTERNAL_ERROR, "Unable to close trace event stream", exception);
        } finally {
            eventOutput = null;
        }
    }

    private void cleanupStaging() {
        closeQuietly(eventOutput);
        eventOutput = null;
        if (stagingDirectory == null
                || !stagingDirectory.toAbsolutePath().normalize().startsWith(root)) {
            clearStagingState();
            return;
        }
        if (!isUntamperedDirectory(stagingDirectory, realStagingDirectory)) {
            if (Files.isSymbolicLink(stagingDirectory)) {
                deleteIfExists(stagingDirectory);
            }
            clearStagingState();
            return;
        }
        if (isUntamperedDirectory(artifactDirectory, realArtifactDirectory)) {
            for (ArtifactInfo artifact : artifacts.values()) {
                deleteIfExists(artifact.path());
            }
            deleteIfExists(artifactDirectory);
        } else if (artifactDirectory != null && Files.isSymbolicLink(artifactDirectory)) {
            deleteIfExists(artifactDirectory);
        }
        deleteIfExists(eventFile);
        deleteIfExists(stagingDirectory);
        clearStagingState();
    }

    private void clearStagingState() {
        artifacts.clear();
        stagingDirectory = null;
        realStagingDirectory = null;
        artifactDirectory = null;
        realArtifactDirectory = null;
        eventFile = null;
    }

    private void verifyStaging() {
        if (!isUntamperedDirectory(stagingDirectory, realStagingDirectory)
                || !isUntamperedDirectory(artifactDirectory, realArtifactDirectory)) {
            throw failure(ErrorCode.INVALID_REQUEST,
                    "Trace staging storage changed unexpectedly", null);
        }
    }

    private boolean isUntamperedDirectory(Path directory, Path expectedRealPath) {
        if (directory == null || expectedRealPath == null) {
            return false;
        }
        Path normalized = directory.toAbsolutePath().normalize();
        if (!normalized.startsWith(root) || Files.isSymbolicLink(normalized)
                || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try {
            return normalized.toRealPath().equals(expectedRealPath)
                    && expectedRealPath.startsWith(realRoot);
        } catch (IOException exception) {
            return false;
        }
    }

    private void verifyRoot() {
        try {
            if (Files.isSymbolicLink(root)
                    || !root.toRealPath().equals(realRoot)) {
                throw failure(ErrorCode.INVALID_REQUEST, "Trace root changed unexpectedly", null);
            }
        } catch (IOException exception) {
            throw failure(ErrorCode.INVALID_REQUEST, "Trace root is unavailable", exception);
        }
    }

    private void verifyOwnedRegularFile(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root) || Files.isSymbolicLink(normalized)
                || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw failure(ErrorCode.INVALID_REQUEST, "Unsafe trace evidence path", null);
        }
        try {
            Path realParent = normalized.getParent().toRealPath();
            if (!realParent.equals(realStagingDirectory)
                    && !realParent.equals(realArtifactDirectory)) {
                throw failure(ErrorCode.INVALID_REQUEST, "Trace evidence escaped its root", null);
            }
        } catch (IOException exception) {
            throw failure(ErrorCode.INVALID_REQUEST, "Trace evidence path is unavailable", exception);
        }
    }

    private void requireActive() {
        if (!active) {
            throw new IllegalStateException("no trace is active");
        }
        verifyRoot();
        verifyStaging();
    }

    private static Path initializeRoot(Path configuredRoot) {
        Objects.requireNonNull(configuredRoot, "root");
        Path normalized = configuredRoot.toAbsolutePath().normalize();
        try {
            if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)
                    && Files.isSymbolicLink(normalized)) {
                throw new IllegalArgumentException("trace root must not be a symbolic link");
            }
            Files.createDirectories(normalized);
            if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("trace root must be a directory");
            }
            return normalized;
        } catch (IOException exception) {
            throw new IllegalArgumentException("trace root cannot be created", exception);
        }
    }

    private static Map<String, String> redact(Map<String, String> evidence) {
        Map<String, String> sanitized = new LinkedHashMap<>();
        evidence.forEach((key, value) -> sanitized.put(redactValue(key), redactValue(value)));
        return sanitized;
    }

    private static String redactValue(String value) {
        String redacted = STACK_FRAME.matcher(value).replaceAll("[redacted]");
        redacted = FILE_URI.matcher(redacted).replaceAll("[redacted]");
        redacted = WINDOWS_PATH.matcher(redacted).replaceAll("[redacted]");
        return UNIX_PATH.matcher(redacted).replaceAll("[redacted]");
    }

    private static HarnessException failure(ErrorCode code, String message, Throwable cause) {
        ErrorEvidence evidence = ErrorEvidence.ofDetails(Map.of("component", "trace"));
        return new HarnessException(code, message, evidence, cause);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("SHA-256 is required by the Java platform", exception);
        }
    }

    private static String randomHex(int byteCount) {
        byte[] bytes = new byte[byteCount];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > TraceEvent.MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException(name + " must be bounded and non-blank");
        }
    }

    private static void closeQuietly(OutputStream output) {
        if (output == null) {
            return;
        }
        try {
            output.close();
        } catch (IOException ignored) {
            // Cleanup is best effort after the primary failure.
        }
    }

    private static void deleteIfExists(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Cleanup is best effort after the primary operation has completed or failed.
        }
    }

    /** Hard recording limits over uncompressed evidence, event count, and wall duration. */
    public record Limits(long maxUncompressedBytes, long maxEvents, Duration maxDuration) {
        /** Validates positive hard limits. */
        public Limits {
            if (maxUncompressedBytes <= 0 || maxEvents <= 0) {
                throw new IllegalArgumentException("trace byte and event limits must be positive");
            }
            Objects.requireNonNull(maxDuration, "maxDuration");
            if (maxDuration.isZero() || maxDuration.isNegative()) {
                throw new IllegalArgumentException("trace duration limit must be positive");
            }
        }

        /** Conservative defaults for local trace recording. */
        public static Limits defaults() {
            return new Limits(64L * 1024 * 1024, 100_000, Duration.ofMinutes(10));
        }
    }

    private record ArtifactReservation(
            long generation,
            Path stagingDirectory,
            Path artifactDirectory,
            Path realStagingDirectory,
            Path realArtifactDirectory,
            Path temporary,
            String mediaType,
            long maxUncompressedBytes) {}

    private record ArtifactInfo(Path path, String mediaType, long size) {}

    @SuppressWarnings("serial")
    private static final class ArtifactLimitException extends RuntimeException {}
}
