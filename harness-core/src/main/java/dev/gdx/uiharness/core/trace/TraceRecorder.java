package dev.gdx.uiharness.core.trace;

import dev.gdx.uiharness.core.error.ErrorCode;
import dev.gdx.uiharness.core.error.ErrorEvidence;
import dev.gdx.uiharness.core.error.HarnessException;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
    private final Object rootFileKey;
    private final PermissionMode permissionMode;
    private final Clock clock;
    private final FileOpener fileOpener;
    private final Map<String, ArtifactInfo> artifacts = new LinkedHashMap<>();
    private OutputStream eventOutput;
    private Path stagingDirectory;
    private Path artifactDirectory;
    private Path eventFile;
    private Object stagingFileKey;
    private Object artifactFileKey;
    private Object eventFileKey;
    private String sessionId;
    private Limits limits;
    private Instant startedAt;
    private long eventCount;
    private long uncompressedBytes;
    private MessageDigest eventDigest;
    private boolean active;
    private long generation;
    private TraceManifest lastManifest;

    /** Opens one owned regular file; the default implementation uses NOFOLLOW semantics. */
    @FunctionalInterface
    interface FileOpener {
        /** Opens the file for reading without following a substituted final-component symlink. */
        InputStream open(Path path) throws IOException;
    }

    /** Creates a recorder rooted below one non-symbolic-link server-owned directory. */
    public TraceRecorder(Path root, Clock clock) {
        this(root, clock, TraceRecorder::openNoFollow);
    }

    /** Test-only constructor injecting the finalization file opener. */
    TraceRecorder(Path root, Clock clock, FileOpener fileOpener) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.fileOpener = Objects.requireNonNull(fileOpener, "fileOpener");
        Objects.requireNonNull(root, "root");
        this.permissionMode = detectPermissionMode(root.getFileSystem());
        this.root = initializeRoot(root, permissionMode);
        try {
            this.rootFileKey = requireFileKey(this.root, "trace root");
            verifyRootOwnershipContract(this.root, permissionMode);
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                    "trace root identity or access contract cannot be verified", exception);
        }
    }

    static InputStream openNoFollow(Path path) throws IOException {
        return Channels.newInputStream(FileChannel.open(
                path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
    }

    /** Owner-only enforcement mode for the file system hosting the trace root. */
    private enum PermissionMode { POSIX, ACL }

    private static PermissionMode detectPermissionMode(java.nio.file.FileSystem fileSystem) {
        Set<String> views = fileSystem.supportedFileAttributeViews();
        if (views.contains("posix")) {
            return PermissionMode.POSIX;
        }
        if (views.contains("acl")) {
            return PermissionMode.ACL;
        }
        throw new IllegalArgumentException(
                "trace storage must support owner-only permissions (posix or acl view)");
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
            // SecureDirectoryStream cannot create directories, so directory creation
            // uses a bounded fallback: a random name plus root identity verified
            // before and after creation. Fails closed if the identity moves.
            verifyRoot();
            String stagingName = ".trace-" + randomHex(16) + ".tmp";
            stagingDirectory = root.resolve(stagingName);
            Files.createDirectory(stagingDirectory, ownerOnlyDirectoryAttributes());
            verifyRoot();
            stagingFileKey = requireFileKey(stagingDirectory, "trace staging");
            requireOwnerOnly(stagingDirectory, true);

            if (!Objects.equals(fileKeyOf(stagingDirectory), stagingFileKey)) {
                throw failure(ErrorCode.INVALID_REQUEST,
                        "Trace staging changed during creation", null);
            }
            artifactDirectory = stagingDirectory.resolve("artifacts");
            Files.createDirectory(artifactDirectory, ownerOnlyDirectoryAttributes());
            if (!Objects.equals(fileKeyOf(stagingDirectory), stagingFileKey)) {
                throw failure(ErrorCode.INVALID_REQUEST,
                        "Trace staging changed during creation", null);
            }
            artifactFileKey = requireFileKey(artifactDirectory, "trace artifact staging");
            requireOwnerOnly(artifactDirectory, true);

            eventFile = stagingDirectory.resolve("events.ndjson");
            eventOutput = createAnchoredFile(
                    stagingDirectory, stagingFileKey, "events.ndjson", eventFile);
            requireOwnerOnly(eventFile, false);
            eventFileKey = requireFileKey(eventFile, "trace event file");
            active = true;
        } catch (IOException | HarnessException exception) {
            List<Throwable> cleanupFailures = cleanupStaging();
            if (exception instanceof HarnessException harnessException) {
                cleanupFailures.forEach(harnessException::addSuppressed);
                throw harnessException;
            }
            HarnessException failure = failure(ErrorCode.INTERNAL_ERROR,
                    "Unable to start trace recording", (IOException) exception);
            cleanupFailures.forEach(failure::addSuppressed);
            throw failure;
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
                        stagingFileKey, artifactFileKey, temporary,
                        mediaType, limits.maxUncompressedBytes());
            }
        } catch (RuntimeException exception) {
            closeSourceAfterValidationFailure(source, exception);
            throw exception;
        }

        MessageDigest digest = sha256();
        long size = 0;
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        try (source; OutputStream output = createAnchoredFile(
                artifactDirectory, artifactFileKey,
                reservation.temporary().getFileName().toString(), reservation.temporary())) {
            requireOwnerOnly(reservation.temporary(), false);
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
                List<Throwable> cleanupFailures = cleanupDetachedReservation(reservation);
                HarnessException failure = failure(ErrorCode.SESSION_CLOSED,
                        "Trace closed while artifact evidence was streaming", null);
                cleanupFailures.forEach(failure::addSuppressed);
                throw failure;
            }
            Duration elapsed = Duration.between(startedAt, clock.instant());
            if (elapsed.isNegative() || elapsed.compareTo(limits.maxDuration()) >= 0) {
                List<Throwable> cleanupFailures = new ArrayList<>();
                deleteIfExists(reservation.temporary(), cleanupFailures);
                HarnessException failure = failLimit("duration limit exceeded");
                cleanupFailures.forEach(failure::addSuppressed);
                throw failure;
            }
            ArtifactInfo existing = artifacts.get(hash);
            if (existing != null) {
                deleteOwnedOrFail(reservation.temporary());
                return hash;
            }
            if (uncompressedBytes > limits.maxUncompressedBytes() - size) {
                List<Throwable> cleanupFailures = new ArrayList<>();
                deleteIfExists(reservation.temporary(), cleanupFailures);
                HarnessException failure = failLimit("byte limit exceeded");
                cleanupFailures.forEach(failure::addSuppressed);
                throw failure;
            }
            Path published = reservation.artifactDirectory().resolve(hash);
            Object publishedFileKey = publishArtifact(reservation, published);
            artifacts.put(hash, new ArtifactInfo(published, mediaType, size, publishedFileKey));
            uncompressedBytes += size;
            return hash;
        }
    }

    /** Atomically publishes one artifact, anchored to the verified artifact directory. */
    private Object publishArtifact(ArtifactReservation reservation, Path published) {
        try (SecureDirectoryStream<Path> artifactsStream = openSecureStream(
                reservation.artifactDirectory(), reservation.artifactFileKey())) {
            if (artifactsStream != null) {
                artifactsStream.move(reservation.temporary().getFileName(),
                        artifactsStream, Path.of(published.getFileName().toString()));
                requireOwnerOnly(published, false);
                return artifactsStream.getFileAttributeView(
                        Path.of(published.getFileName().toString()),
                        BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                        .readAttributes().fileKey();
            }
            if (!Objects.equals(fileKeyOf(reservation.artifactDirectory()),
                    reservation.artifactFileKey())) {
                throw failure(ErrorCode.INVALID_REQUEST,
                        "Artifact storage changed unexpectedly", null);
            }
            try {
                Files.move(reservation.temporary(), published,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                deleteOwnedOrFail(reservation.temporary());
                interruptAfterFailure("artifact atomic publish unsupported", exception);
                throw failure(ErrorCode.INTERNAL_ERROR,
                        "Artifact storage does not support atomic publication", exception);
            } catch (IOException exception) {
                deleteOwnedOrFail(reservation.temporary());
                interruptAfterFailure("artifact publish failed", exception);
                throw failure(ErrorCode.INTERNAL_ERROR,
                        "Unable to publish trace artifact", exception);
            }
            requireOwnerOnly(published, false);
            return fileKeyOf(published);
        } catch (HarnessException failure) {
            deleteOwnedOrFail(reservation.temporary());
            throw failure;
        } catch (IOException exception) {
            deleteOwnedOrFail(reservation.temporary());
            interruptAfterFailure("artifact publish failed", exception);
            throw failure(ErrorCode.INTERNAL_ERROR,
                    "Unable to publish trace artifact", exception);
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
            List<Throwable> cleanupFailures = cleanupStaging();
            cleanupFailures.forEach(exception::addSuppressed);
            throw exception;
        }
        Instant endedAt = clock.instant();
        if (endedAt.isBefore(startedAt)) {
            endedAt = startedAt;
        }
        String tempName = ".trace-" + randomHex(16) + ".zip.tmp";
        String archiveName = "trace-" + randomHex(16) + ".zip";
        Path temporaryArchive = root.resolve(tempName);
        Path archive = root.resolve(archiveName);
        String eventsSha256 = HexFormat.of().formatHex(eventDigest.digest());
        LinkedHashMap<String, TraceManifest.ArtifactBinding> bindings = new LinkedHashMap<>();
        for (Map.Entry<String, ArtifactInfo> entry : artifacts.entrySet()) {
            bindings.put(entry.getKey(), new TraceManifest.ArtifactBinding(
                    entry.getKey(), entry.getValue().size(), entry.getValue().mediaType()));
        }
        TraceManifest manifest = new TraceManifest(archive, sessionId, startedAt, endedAt,
                complete, reason, eventCount, artifacts.size(), uncompressedBytes,
                TraceManifest.V2, eventsSha256, bindings);
        MessageDigest archiveDigest = sha256();
        Object tempArchiveFileKey;
        long archiveSize;
        try (SecureDirectoryStream<Path> rootStream = openSecureStream(root, rootFileKey)) {
            try (OutputStream raw = rootStream != null
                    ? Channels.newOutputStream(rootStream.newByteChannel(Path.of(tempName),
                            Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                            ownerOnlyFileAttribute()))
                    : Files.newOutputStream(temporaryArchive,
                            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                    ZipOutputStream zip = new ZipOutputStream(
                            new BufferedOutputStream(new DigestOutputStream(raw, archiveDigest)),
                            StandardCharsets.UTF_8)) {
                copyEntry(zip, "events.ndjson", eventFile, eventsSha256, eventFileKey);
                for (Map.Entry<String, ArtifactInfo> entry : artifacts.entrySet()) {
                    copyEntry(zip, "artifacts/" + entry.getKey(),
                            entry.getValue().path(), entry.getKey(),
                            entry.getValue().fileKey());
                }
                zip.putNextEntry(new ZipEntry("manifest.json"));
                zip.write(manifest.toJson());
                zip.closeEntry();
            }
            if (rootStream != null) {
                BasicFileAttributes attributes = rootStream.getFileAttributeView(
                        Path.of(tempName), BasicFileAttributeView.class,
                        LinkOption.NOFOLLOW_LINKS).readAttributes();
                tempArchiveFileKey = attributes.fileKey();
                archiveSize = attributes.size();
            } else {
                tempArchiveFileKey = fileKeyOf(temporaryArchive);
                archiveSize = Files.size(temporaryArchive);
            }
            requireOwnerOnly(temporaryArchive, false);
            String archiveSha256 = HexFormat.of().formatHex(archiveDigest.digest());
            verifyArchiveIdentity(rootStream, temporaryArchive, tempName,
                    tempArchiveFileKey, archiveSha256, archiveSize);
            if (secureEntryExists(rootStream, archiveName, archive)) {
                throw failure(ErrorCode.INVALID_REQUEST,
                        "Trace archive destination already exists", null);
            }
            if (rootStream != null) {
                rootStream.move(Path.of(tempName), rootStream, Path.of(archiveName));
            } else {
                if (!Objects.equals(fileKeyOf(root), rootFileKey)
                        || !Objects.equals(fileKeyOf(temporaryArchive), tempArchiveFileKey)) {
                    throw failure(ErrorCode.INVALID_REQUEST,
                            "Trace archive storage changed unexpectedly", null);
                }
                try {
                    Files.move(temporaryArchive, archive, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException exception) {
                    throw failure(ErrorCode.INTERNAL_ERROR,
                            "Trace storage does not support atomic publication", exception);
                } catch (IOException exception) {
                    throw failure(ErrorCode.INTERNAL_ERROR,
                            "Unable to publish trace archive", exception);
                }
            }
            verifyArchiveIdentity(rootStream, archive, archiveName,
                    tempArchiveFileKey, archiveSha256, archiveSize);
            requireOwnerOnly(archive, false);
        } catch (IOException exception) {
            List<Throwable> cleanupFailures = new ArrayList<>();
            deleteIfExists(temporaryArchive, cleanupFailures);
            deleteIfExists(archive, cleanupFailures);
            cleanupFailures.addAll(cleanupStaging());
            HarnessException failure = failure(ErrorCode.INTERNAL_ERROR,
                    "Unable to finalize trace archive", exception);
            cleanupFailures.forEach(failure::addSuppressed);
            throw failure;
        } catch (RuntimeException failure) {
            List<Throwable> cleanupFailures = new ArrayList<>();
            deleteIfExists(temporaryArchive, cleanupFailures);
            deleteIfExists(archive, cleanupFailures);
            cleanupFailures.addAll(cleanupStaging());
            cleanupFailures.forEach(failure::addSuppressed);
            throw failure;
        }
        lastManifest = manifest;
        List<Throwable> cleanupFailures = cleanupStaging();
        if (!cleanupFailures.isEmpty()) {
            HarnessException failure = failure(ErrorCode.INTERNAL_ERROR,
                    "Trace archive published but staging cleanup failed", null);
            cleanupFailures.forEach(failure::addSuppressed);
            throw failure;
        }
        return manifest;
    }

    private void copyEntry(ZipOutputStream zip, String name, Path source,
            String expectedSha256, Object expectedFileKey) throws IOException {
        verifyOwnedRegularFile(source); // fast-fail defense in depth; not the identity guarantee
        if (!Objects.equals(fileKeyOf(source), expectedFileKey)) {
            throw failure(ErrorCode.INVALID_REQUEST,
                    "Trace evidence file changed unexpectedly", null);
        }
        try (InputStream input = openVerified(source)) {
            zip.putNextEntry(new ZipEntry(name));
            MessageDigest copyDigest = sha256();
            byte[] buffer = new byte[COPY_BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) != -1) {
                copyDigest.update(buffer, 0, read);
                zip.write(buffer, 0, read);
            }
            zip.closeEntry();
            if (!HexFormat.of().formatHex(copyDigest.digest()).equals(expectedSha256)) {
                throw failure(ErrorCode.INVALID_REQUEST,
                        "Trace evidence content does not match its recorded digest", null);
            }
        }
    }

    /** Reopens the archive no-follow and proves fileKey, byte count, and SHA-256. */
    private void verifyArchiveIdentity(SecureDirectoryStream<Path> rootStream,
            Path fullPath, String name, Object expectedFileKey,
            String expectedDigest, long expectedSize) throws IOException {
        Object currentKey = rootStream != null
                ? rootStream.getFileAttributeView(Path.of(name), BasicFileAttributeView.class,
                        LinkOption.NOFOLLOW_LINKS).readAttributes().fileKey()
                : fileKeyOf(fullPath);
        if (!Objects.equals(currentKey, expectedFileKey)) {
            throw failure(ErrorCode.INVALID_REQUEST,
                    "Trace archive storage changed unexpectedly", null);
        }
        try (InputStream input = openVerified(fullPath)) {
            MessageDigest digest = sha256();
            long size = 0;
            byte[] buffer = new byte[COPY_BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
                size += read;
            }
            if (size != expectedSize
                    || !HexFormat.of().formatHex(digest.digest()).equals(expectedDigest)) {
                throw failure(ErrorCode.INVALID_REQUEST,
                        "Trace archive content does not match its recorded digest", null);
            }
        }
    }

    private static boolean secureEntryExists(SecureDirectoryStream<Path> rootStream,
            String name, Path fullPath) throws IOException {
        if (rootStream != null) {
            try {
                rootStream.getFileAttributeView(Path.of(name), BasicFileAttributeView.class,
                        LinkOption.NOFOLLOW_LINKS).readAttributes();
                return true;
            } catch (NoSuchFileException exception) {
                return false;
            }
        }
        return Files.exists(fullPath, LinkOption.NOFOLLOW_LINKS);
    }

    private InputStream openVerified(Path source) {
        Path normalized = source.toAbsolutePath().normalize();
        try {
            return fileOpener.open(normalized);
        } catch (IOException exception) {
            throw failure(ErrorCode.INVALID_REQUEST,
                    "Trace evidence path is unavailable", exception);
        }
    }

    private String failStreamedArtifact(
            ArtifactReservation reservation,
            String reason,
            Exception originalFailure,
            boolean limitFailure) {
        List<Throwable> cleanupFailures = new ArrayList<>();
        deleteIfExists(reservation.temporary(), cleanupFailures);
        synchronized (this) {
            if (!matchesActiveReservation(reservation)) {
                cleanupFailures.addAll(cleanupDetachedReservation(reservation));
                HarnessException failure = failure(ErrorCode.SESSION_CLOSED,
                        "Trace closed while artifact evidence was streaming",
                        originalFailure);
                cleanupFailures.forEach(failure::addSuppressed);
                throw failure;
            }
            if (limitFailure) {
                HarnessException failure = failLimit(reason);
                cleanupFailures.forEach(failure::addSuppressed);
                throw failure;
            }
            interruptAfterFailure(reason, originalFailure);
            HarnessException failure = failure(ErrorCode.INTERNAL_ERROR,
                    "Unable to stream trace artifact evidence", originalFailure);
            cleanupFailures.forEach(failure::addSuppressed);
            throw failure;
        }
    }

    private boolean matchesActiveReservation(ArtifactReservation reservation) {
        return active
                && generation == reservation.generation()
                && reservation.stagingDirectory().equals(stagingDirectory)
                && reservation.artifactDirectory().equals(artifactDirectory);
    }

    private List<Throwable> cleanupDetachedReservation(ArtifactReservation reservation) {
        List<Throwable> failures = new ArrayList<>();
        deleteIfExists(reservation.temporary(), failures);
        if (isUntamperedDirectory(
                reservation.artifactDirectory(), reservation.artifactFileKey())) {
            deleteDirectoryName("artifacts", reservation.stagingDirectory(),
                    reservation.stagingFileKey(), failures);
        }
        if (isUntamperedDirectory(
                reservation.stagingDirectory(), reservation.stagingFileKey())) {
            deleteIfExists(reservation.stagingDirectory().resolve("events.ndjson"), failures);
            deleteNameFromRoot(
                    reservation.stagingDirectory().getFileName().toString(), failures);
        }
        return failures;
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
            List<Throwable> cleanupFailures = cleanupStaging();
            HarnessException failure = failure(ErrorCode.INTERNAL_ERROR,
                    "Unable to close trace event stream", exception);
            cleanupFailures.forEach(failure::addSuppressed);
            throw failure;
        } finally {
            eventOutput = null;
        }
    }

    /**
     * Deletes every owned staging entry, anchoring each operation to a verified
     * directory handle when the provider supports secure directory streams and
     * never deleting through a directory whose identity cannot be proven.
     * Returns every failure so callers can suppress them onto the primary
     * outcome or turn a nominal finalize into a terminal failure.
     */
    private List<Throwable> cleanupStaging() {
        List<Throwable> failures = new ArrayList<>();
        closeQuietly(eventOutput);
        eventOutput = null;
        if (stagingDirectory == null) {
            clearStagingState();
            return failures;
        }
        if (isUntamperedDirectory(stagingDirectory, stagingFileKey)) {
            cleanupOwnedStaging(failures);
        } else {
            // Never delete through a directory whose identity cannot be proven.
            failures.add(new IOException(
                    "trace staging identity lost; residual evidence may remain under "
                            + stagingDirectory));
            if (Files.isSymbolicLink(stagingDirectory)) {
                deleteNameFromRoot(
                        stagingDirectory.getFileName().toString(), failures);
            }
        }
        clearStagingState();
        return failures;
    }

    private void cleanupOwnedStaging(List<Throwable> failures) {
        boolean deferred = false;
        if (isUntamperedDirectory(artifactDirectory, artifactFileKey)) {
            try (SecureDirectoryStream<Path> artifactsStream =
                    openSecureStream(artifactDirectory, artifactFileKey)) {
                if (artifactsStream != null) {
                    for (String hash : artifacts.keySet()) {
                        try {
                            artifactsStream.deleteFile(Path.of(hash));
                        } catch (IOException | RuntimeException exception) {
                            failures.add(exception);
                        }
                    }
                } else if (!artifacts.isEmpty()) {
                    for (ArtifactInfo artifact : artifacts.values()) {
                        deleteIfExists(artifact.path(), failures);
                    }
                }
            } catch (IOException exception) {
                failures.add(exception);
            }
            switch (artifactCleanupState(artifactDirectory)) {
                case EMPTY -> deleteDirectoryName(
                        "artifacts", stagingDirectory, stagingFileKey, failures);
                case IN_FLIGHT_TEMPS -> {
                    // In-flight reservation streams own these temporary files; the
                    // detached-reservation cleanup removes them when streaming
                    // finishes. Leave both directories in place, not a failure.
                    deferred = true;
                }
                case UNEXPECTED -> failures.add(new IOException(
                        "artifact directory contains unrecognized entries; "
                                + "residual evidence may remain under "
                                + artifactDirectory));
            }
        } else if (artifactDirectory != null && Files.isSymbolicLink(artifactDirectory)) {
            deleteDirectoryName("artifacts", stagingDirectory, stagingFileKey, failures);
        } else if (artifactDirectory != null) {
            failures.add(new IOException(
                    "artifact directory identity lost; residual evidence may remain under "
                            + artifactDirectory));
        }
        deleteIfExists(eventFile, failures);
        if (!deferred) {
            deleteNameFromRoot(stagingDirectory.getFileName().toString(), failures);
        }
    }

    /** Classifies what remains inside the artifact directory after owned files are removed. */
    private enum ArtifactCleanup { EMPTY, IN_FLIGHT_TEMPS, UNEXPECTED }

    private static ArtifactCleanup artifactCleanupState(Path artifactDirectory) {
        boolean sawEntry = false;
        try (var entries = Files.newDirectoryStream(artifactDirectory)) {
            for (Path entry : entries) {
                sawEntry = true;
                if (!entry.getFileName().toString().startsWith(".artifact-")) {
                    return ArtifactCleanup.UNEXPECTED;
                }
            }
        } catch (IOException exception) {
            return ArtifactCleanup.UNEXPECTED;
        }
        return sawEntry ? ArtifactCleanup.IN_FLIGHT_TEMPS : ArtifactCleanup.EMPTY;
    }

    /** Removes one directory entry name from a verified parent, never following it. */
    private void deleteDirectoryName(String name, Path parent, Object parentFileKey,
            List<Throwable> failures) {
        try (SecureDirectoryStream<Path> stream = openSecureStream(parent, parentFileKey)) {
            if (stream != null) {
                try {
                    stream.deleteDirectory(Path.of(name));
                    return;
                } catch (IOException | RuntimeException exception) {
                    try {
                        stream.deleteFile(Path.of(name));
                        return;
                    } catch (IOException | RuntimeException secondary) {
                        failures.add(secondary);
                    }
                }
                return;
            }
        } catch (IOException exception) {
            failures.add(exception);
            return;
        }
        deleteIfExists(parent.resolve(name), failures);
    }

    /** Removes one name directly below the trace root once the root identity is proven. */
    private void deleteNameFromRoot(String name, List<Throwable> failures) {
        if (!Objects.equals(fileKeyOf(root), rootFileKey)) {
            failures.add(new IOException(
                    "trace root identity lost; cannot safely remove " + name));
            return;
        }
        try (SecureDirectoryStream<Path> rootStream = openSecureStream(root, rootFileKey)) {
            if (rootStream != null) {
                try {
                    rootStream.deleteDirectory(Path.of(name));
                    return;
                } catch (IOException | RuntimeException exception) {
                    try {
                        rootStream.deleteFile(Path.of(name));
                        return;
                    } catch (IOException | RuntimeException secondary) {
                        failures.add(secondary);
                    }
                }
                return;
            }
        } catch (IOException exception) {
            failures.add(exception);
            return;
        }
        deleteIfExists(root.resolve(name), failures);
    }

    private void clearStagingState() {
        artifacts.clear();
        stagingDirectory = null;
        artifactDirectory = null;
        eventFile = null;
        stagingFileKey = null;
        artifactFileKey = null;
        eventFileKey = null;
    }

    private void verifyStaging() {
        if (!isUntamperedDirectory(stagingDirectory, stagingFileKey)
                || !isUntamperedDirectory(artifactDirectory, artifactFileKey)) {
            throw failure(ErrorCode.INVALID_REQUEST,
                    "Trace staging storage changed unexpectedly", null);
        }
    }

    private boolean isUntamperedDirectory(Path directory, Object expectedFileKey) {
        if (directory == null || expectedFileKey == null) {
            return false;
        }
        Path normalized = directory.toAbsolutePath().normalize();
        return normalized.startsWith(root)
                && !Files.isSymbolicLink(normalized)
                && Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)
                && Objects.equals(fileKeyOf(normalized), expectedFileKey);
    }

    private void verifyRoot() {
        if (Files.isSymbolicLink(root)
                || !Objects.equals(fileKeyOf(root), rootFileKey)) {
            throw failure(ErrorCode.INVALID_REQUEST, "Trace root changed unexpectedly", null);
        }
    }

    private void verifyOwnedRegularFile(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root) || Files.isSymbolicLink(normalized)
                || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw failure(ErrorCode.INVALID_REQUEST, "Unsafe trace evidence path", null);
        }
        Object parentKey = fileKeyOf(normalized.getParent());
        if (!Objects.equals(parentKey, stagingFileKey)
                && !Objects.equals(parentKey, artifactFileKey)) {
            throw failure(ErrorCode.INVALID_REQUEST, "Trace evidence escaped its root", null);
        }
    }

    private void requireActive() {
        if (!active) {
            throw new IllegalStateException("no trace is active");
        }
        verifyRoot();
        verifyStaging();
    }

    private static Set<PosixFilePermission> ownerOnlyDirectoryPermissions() {
        return Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE);
    }

    private static Set<PosixFilePermission> ownerOnlyFilePermissions() {
        return Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    }

    private FileAttribute<?>[] ownerOnlyDirectoryAttributes() {
        if (permissionMode == PermissionMode.POSIX) {
            return new FileAttribute<?>[] {
                    PosixFilePermissions.asFileAttribute(ownerOnlyDirectoryPermissions()) };
        }
        return new FileAttribute<?>[0];
    }

    private FileAttribute<?>[] ownerOnlyFileAttribute() {
        if (permissionMode == PermissionMode.POSIX) {
            return new FileAttribute<?>[] {
                    PosixFilePermissions.asFileAttribute(ownerOnlyFilePermissions()) };
        }
        return new FileAttribute<?>[0];
    }

    /** Applies and then verifies owner-only permissions; any failure fails closed. */
    private void requireOwnerOnly(Path path, boolean directory) throws IOException {
        switch (permissionMode) {
            case POSIX -> {
                Set<PosixFilePermission> expected =
                        directory ? ownerOnlyDirectoryPermissions() : ownerOnlyFilePermissions();
                Files.setPosixFilePermissions(path, expected);
                if (!Files.getPosixFilePermissions(path).equals(expected)) {
                    throw new IOException(
                            "trace storage permissions are not owner-only: " + path);
                }
            }
            case ACL -> requireOwnerOnlyAcl(path);
        }
    }

    /** Replaces the ACL with an owner-only allow entry and verifies the result. */
    private static void requireOwnerOnlyAcl(Path path) throws IOException {
        AclFileAttributeView view = Files.getFileAttributeView(
                path, AclFileAttributeView.class);
        if (view == null) {
            throw new IOException("acl view unavailable for owner-only enforcement: " + path);
        }
        UserPrincipal owner = view.getOwner();
        AclEntry ownerEntry = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                .build();
        view.setAcl(List.of(ownerEntry));
        for (AclEntry entry : view.getAcl()) {
            if (entry.type() != AclEntryType.ALLOW || !entry.principal().equals(owner)) {
                throw new IOException("trace storage ACL is not owner-only: " + path);
            }
        }
    }

    /** Verifies the supplied root's access contract without modifying it. */
    private static void verifyRootOwnershipContract(Path root, PermissionMode mode)
            throws IOException {
        if (mode == PermissionMode.POSIX) {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(root);
            if (permissions.contains(PosixFilePermission.GROUP_WRITE)
                    || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
                throw new IOException(
                        "trace root must not be writable by group or other principals: "
                                + root);
            }
        }
        // ACL mode: owner-only ACLs are enforced on every recorder-owned entry; the
        // supplied root's write contract is proven by successful creation below it.
    }

    private static Path initializeRoot(Path configuredRoot, PermissionMode mode) {
        Objects.requireNonNull(configuredRoot, "root");
        Path normalized = configuredRoot.toAbsolutePath().normalize();
        try {
            if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)
                    && Files.isSymbolicLink(normalized)) {
                throw new IllegalArgumentException("trace root must not be a symbolic link");
            }
            if (mode == PermissionMode.POSIX) {
                Files.createDirectories(normalized, new FileAttribute<?>[] {
                        PosixFilePermissions.asFileAttribute(ownerOnlyDirectoryPermissions()) });
            } else {
                Files.createDirectories(normalized);
            }
            if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("trace root must be a directory");
            }
            return normalized;
        } catch (IOException exception) {
            throw new IllegalArgumentException("trace root cannot be created", exception);
        }
    }

    private static Object fileKeyOf(Path path) {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS).fileKey();
        } catch (IOException exception) {
            return null;
        }
    }

    private static Object requireFileKey(Path path, String name) throws IOException {
        Object key = fileKeyOf(path);
        if (key == null) {
            throw new IOException(
                    name + " identity cannot be established on this file system");
        }
        return key;
    }

    /**
     * Opens a secure directory stream whose handle is proven to refer to the same
     * filesystem object as {@code expectedFileKey}, or null when the provider
     * offers no secure streams or identity cannot be proven. Callers must close
     * the returned stream.
     */
    @SuppressWarnings("unchecked")
    private static SecureDirectoryStream<Path> openSecureStream(Path directory,
            Object expectedFileKey) {
        if (expectedFileKey == null || !Objects.equals(fileKeyOf(directory), expectedFileKey)) {
            return null;
        }
        DirectoryStream<Path> stream = null;
        try {
            stream = Files.newDirectoryStream(directory);
            if (!(stream instanceof SecureDirectoryStream<?>)) {
                stream.close();
                return null;
            }
            SecureDirectoryStream<Path> secure = (SecureDirectoryStream<Path>) stream;
            Object handleKey = secure.getFileAttributeView(BasicFileAttributeView.class)
                    .readAttributes().fileKey();
            if (!Objects.equals(handleKey, expectedFileKey)) {
                secure.close();
                return null;
            }
            return secure;
        } catch (IOException | RuntimeException exception) {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException ignored) {
                    // best effort after the primary failure
                }
            }
            return null;
        }
    }

    /**
     * Creates one file with CREATE_NEW semantics. When the parent supports secure
     * directory streams the creation is anchored to a verified directory handle;
     * otherwise the fallback verifies parent identity before and after creation.
     */
    private OutputStream createAnchoredFile(Path parent, Object parentFileKey,
            String name, Path fullPath) throws IOException {
        SecureDirectoryStream<Path> stream = openSecureStream(parent, parentFileKey);
        if (stream != null) {
            try (stream) {
                SeekableByteChannel channel = stream.newByteChannel(Path.of(name),
                        Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                        ownerOnlyFileAttribute());
                return new BufferedOutputStream(Channels.newOutputStream(channel));
            }
        }
        if (!Objects.equals(fileKeyOf(parent), parentFileKey)) {
            throw new IOException("trace storage changed during file creation: " + parent);
        }
        OutputStream output = new BufferedOutputStream(Files.newOutputStream(fullPath,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE));
        if (!Objects.equals(fileKeyOf(parent), parentFileKey)) {
            output.close();
            throw new IOException("trace storage changed during file creation: " + parent);
        }
        return output;
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

    private static void deleteIfExists(Path path, List<Throwable> failures) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            failures.add(exception);
        }
    }

    private static void deleteOwnedOrFail(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            throw failure(ErrorCode.INTERNAL_ERROR,
                    "Unable to remove trace staging entry", exception);
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
            Object stagingFileKey,
            Object artifactFileKey,
            Path temporary,
            String mediaType,
            long maxUncompressedBytes) {}

    private record ArtifactInfo(Path path, String mediaType, long size, Object fileKey) {}

    @SuppressWarnings("serial")
    private static final class ArtifactLimitException extends RuntimeException {}
}
