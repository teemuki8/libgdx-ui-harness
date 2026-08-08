package dev.gdx.uiharness.core.trace;

import dev.gdx.uiharness.core.error.ErrorCode;
import dev.gdx.uiharness.core.error.ErrorEvidence;
import dev.gdx.uiharness.core.error.HarnessException;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
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
    private final FinalizationInterceptor interceptor;
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

    /**
     * Test-only finalization interceptor; the production default is a no-op.
     * Fires immediately before the named step touches {@code path}, giving the
     * swap/collision regressions a deterministic injection point that is
     * independent of any production open path.
     */
    @FunctionalInterface
    interface FinalizationInterceptor {
        /** Fired immediately before the named finalization step touches {@code path}. */
        void before(Step step, Path path) throws IOException;

        /** Named finalization steps used by the swap/collision regressions. */
        enum Step {
            /** About to open one evidence file for the archive copy. */
            OPEN_EVIDENCE,
            /** About to verify the temporary or published archive identity and content. */
            VERIFY_ARCHIVE,
            /** About to check that the publication destination is absent. */
            CHECK_DESTINATION,
            /** Publication proof completed; staging cleanup is next. */
            AFTER_FINALIZE
        }
    }

    /** Creates a recorder rooted below one non-symbolic-link server-owned directory. */
    public TraceRecorder(Path root, Clock clock) {
        this(root, clock, (step, path) -> { });
    }

    /** Test-only constructor injecting the finalization interceptor. */
    TraceRecorder(Path root, Clock clock, FinalizationInterceptor interceptor) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.interceptor = Objects.requireNonNull(interceptor, "interceptor");
        Objects.requireNonNull(root, "root");
        this.permissionMode = detectPermissionMode(root.getFileSystem());
        try {
            verifyTrustedAncestorChain(root, permissionMode);
        } catch (IOException exception) {
            throw new IllegalArgumentException("trace root ancestry is not trusted", exception);
        }
        this.root = initializeRoot(root, permissionMode);
        try {
            requireExactRootPermissions(this.root, permissionMode);
            this.rootFileKey = requireFileKey(this.root, "trace root");
            // Secure directory streams are mandatory: recording fails closed when the
            // provider cannot anchor every child operation to a verified directory handle.
            try (SecureDirectoryStream<Path> stream = openSecureStreamOrFail(
                    this.root, this.rootFileKey, "trace root")) {
                // capability and handle identity proven
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                    "trace root identity or access contract cannot be verified", exception);
        }
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

    /**
     * Establishes, before any mutation, that every ancestor of the configured root up
     * to a trust boundary is a NOFOLLOW real directory that cannot be replaced or
     * redirected by another principal. Sticky world-writable directories (for example
     * /tmp) and directories owned by another principal that the process cannot write
     * are trust boundaries: their entries are owner-protected or outside our reach.
     */
    private static void verifyTrustedAncestorChain(Path configuredRoot, PermissionMode mode)
            throws IOException {
        UserPrincipal principal = processPrincipal();
        Path current = configuredRoot.toAbsolutePath().normalize().getParent();
        while (current != null) {
            if (Files.isSymbolicLink(current)) {
                throw new IOException(
                        "trace path traverses a symbolic link: " + current);
            }
            if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("trace path component is not a directory: " + current);
            }
            if (isTrustBoundary(current, mode)) {
                break;
            }
            if (mode == PermissionMode.POSIX) {
                if (!Files.getOwner(current, LinkOption.NOFOLLOW_LINKS).equals(principal)) {
                    if (Files.isWritable(current)) {
                        throw new IOException(
                                "trace path component is a shared writable directory: "
                                        + current);
                    }
                    break; // owned by another principal and not writable by us: boundary
                }
                Set<PosixFilePermission> permissions =
                        Files.getPosixFilePermissions(current);
                if (permissions.contains(PosixFilePermission.GROUP_WRITE)
                        || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
                    throw new IOException(
                            "trace path component is writable by group or others: "
                                    + current);
                }
            } else {
                verifyAclAncestor(current, principal);
            }
            current = current.getParent();
        }
    }

    private static boolean isTrustBoundary(Path directory, PermissionMode mode)
            throws IOException {
        if (mode != PermissionMode.POSIX) {
            return false;
        }
        if (!Files.getPosixFilePermissions(directory)
                .contains(PosixFilePermission.OTHERS_WRITE)) {
            return false;
        }
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("unix")) {
            Object modeValue = Files.getAttribute(directory, "unix:mode");
            if (modeValue instanceof Integer value && (value & 01000) != 0) {
                return true; // sticky: entries inside are owner-protected
            }
        }
        return false;
    }

    private static void verifyAclAncestor(Path directory, UserPrincipal principal)
            throws IOException {
        AclFileAttributeView view = Files.getFileAttributeView(
                directory, AclFileAttributeView.class);
        if (view == null) {
            throw new IOException("acl view unavailable for trace path component: "
                    + directory);
        }
        for (AclEntry entry : view.getAcl()) {
            if (entry.type() == AclEntryType.DENY) {
                throw new IOException(
                        "trace path component carries a deny ACL entry: " + directory);
            }
        }
        if (!view.getOwner().equals(principal)) {
            if (aclGrantsWriteToNonOwner(view)) {
                throw new IOException(
                        "trace path component is writable by other principals: "
                                + directory);
            }
            // otherwise a trust boundary above our reach
        }
    }

    private static boolean aclGrantsWriteToNonOwner(AclFileAttributeView view)
            throws IOException {
        UserPrincipal owner = view.getOwner();
        for (AclEntry entry : view.getAcl()) {
            if (entry.type() == AclEntryType.ALLOW
                    && !entry.principal().equals(owner)
                    && entry.permissions().stream().anyMatch(TraceRecorder::isAclWrite)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAclWrite(AclEntryPermission permission) {
        return switch (permission) {
            case WRITE_DATA, APPEND_DATA, DELETE, DELETE_CHILD, WRITE_ATTRIBUTES,
                    WRITE_NAMED_ATTRS, WRITE_ACL, WRITE_OWNER -> true;
            default -> false;
        };
    }

    private static UserPrincipal processPrincipal() throws IOException {
        return FileSystems.getDefault().getUserPrincipalLookupService()
                .lookupPrincipalByName(System.getProperty("user.name"));
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
            // Java exposes no anchored mkdir; directory creation by pathname is safe
            // because the trusted-ancestor precondition guarantees no other principal
            // can replace any component. Every created directory is then re-opened
            // through the parent secure handle and its identity and exact permissions
            // are verified.
            verifyRoot();
            String stagingName = ".trace-" + randomHex(16) + ".tmp";
            stagingDirectory = root.resolve(stagingName);
            Files.createDirectory(stagingDirectory, ownerOnlyDirectoryAttributes());
            stagingFileKey = requireFileKey(stagingDirectory, "trace staging");
            requireOwnerOnly(stagingDirectory, true);
            try (SecureDirectoryStream<Path> rootStream = openRootStream()) {
                verifyChildDirectory(rootStream, stagingName, stagingFileKey);
            }

            artifactDirectory = stagingDirectory.resolve("artifacts");
            Files.createDirectory(artifactDirectory, ownerOnlyDirectoryAttributes());
            artifactFileKey = requireFileKey(artifactDirectory, "trace artifact staging");
            requireOwnerOnly(artifactDirectory, true);
            try (SecureDirectoryStream<Path> stagingStream = openSecureStreamOrFail(
                    stagingDirectory, stagingFileKey, "trace staging")) {
                verifyChildDirectory(stagingStream, "artifacts", artifactFileKey);
            }

            eventFile = stagingDirectory.resolve("events.ndjson");
            try (SecureDirectoryStream<Path> stagingStream = openSecureStreamOrFail(
                    stagingDirectory, stagingFileKey, "trace staging")) {
                SeekableByteChannel channel = stagingStream.newByteChannel(
                        Path.of("events.ndjson"),
                        Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                        ownerOnlyFileAttribute());
                eventOutput = new BufferedOutputStream(Channels.newOutputStream(channel));
                eventFileKey = stagingStream.getFileAttributeView(Path.of("events.ndjson"),
                        BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                        .readAttributes().fileKey();
            }
            requireOwnerOnly(eventFile, false);
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

    private static void verifyChildDirectory(SecureDirectoryStream<Path> parentStream,
            String name, Object expectedFileKey) throws IOException {
        Object key = parentStream.getFileAttributeView(Path.of(name),
                BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                .readAttributes().fileKey();
        if (!Objects.equals(key, expectedFileKey)) {
            throw new IOException(
                    "trace directory identity changed after creation: " + name);
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
        SeekableByteChannel tempChannel;
        try {
            synchronized (this) {
                requireActive();
                checkDuration();
                requireText(mediaType, "mediaType");
                Path temporary = artifactDirectory
                        .resolve(".artifact-" + randomHex(16) + ".tmp");
                Object temporaryFileKey;
                try (SecureDirectoryStream<Path> artifactStream = openSecureStreamOrFail(
                        artifactDirectory, artifactFileKey, "trace artifact staging")) {
                    tempChannel = artifactStream.newByteChannel(
                            Path.of(temporary.getFileName().toString()),
                            Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                            ownerOnlyFileAttribute());
                    temporaryFileKey = artifactStream.getFileAttributeView(
                            Path.of(temporary.getFileName().toString()),
                            BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                            .readAttributes().fileKey();
                }
                reservation = new ArtifactReservation(
                        generation, stagingDirectory, artifactDirectory,
                        stagingFileKey, artifactFileKey, eventFileKey, temporary,
                        temporaryFileKey, mediaType, limits.maxUncompressedBytes());
            }
        } catch (IOException exception) {
            closeSourceAfterValidationFailure(source, exception);
            throw failure(ErrorCode.INTERNAL_ERROR,
                    "Unable to prepare artifact staging", exception);
        } catch (RuntimeException exception) {
            closeSourceAfterValidationFailure(source, exception);
            throw exception;
        }

        MessageDigest digest = sha256();
        long size = 0;
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        try (source; OutputStream output = new BufferedOutputStream(
                Channels.newOutputStream(tempChannel))) {
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
                deleteReservationTemp(reservation, cleanupFailures);
                HarnessException failure = failLimit("duration limit exceeded");
                cleanupFailures.forEach(failure::addSuppressed);
                throw failure;
            }
            ArtifactInfo existing = artifacts.get(hash);
            if (existing != null) {
                List<Throwable> cleanupFailures = new ArrayList<>();
                deleteReservationTemp(reservation, cleanupFailures);
                if (!cleanupFailures.isEmpty()) {
                    HarnessException failure = failure(ErrorCode.INTERNAL_ERROR,
                            "Unable to remove duplicate artifact staging", null);
                    cleanupFailures.forEach(failure::addSuppressed);
                    throw failure;
                }
                return hash;
            }
            if (uncompressedBytes > limits.maxUncompressedBytes() - size) {
                List<Throwable> cleanupFailures = new ArrayList<>();
                deleteReservationTemp(reservation, cleanupFailures);
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

    /** Atomically publishes one artifact through the verified artifact directory handle. */
    private Object publishArtifact(ArtifactReservation reservation, Path published) {
        try (SecureDirectoryStream<Path> artifactsStream = openSecureStreamOrFail(
                reservation.artifactDirectory(), reservation.artifactFileKey(),
                "trace artifact staging")) {
            artifactsStream.move(reservation.temporary().getFileName(),
                    artifactsStream, Path.of(published.getFileName().toString()));
            requireOwnerOnly(published, false);
            return artifactsStream.getFileAttributeView(
                    Path.of(published.getFileName().toString()),
                    BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                    .readAttributes().fileKey();
        } catch (HarnessException failure) {
            deleteOwnedTempOrFail(reservation);
            throw failure;
        } catch (UnsupportedOperationException exception) {
            deleteOwnedTempOrFail(reservation);
            interruptAfterFailure("artifact atomic publish unsupported", exception);
            throw failure(ErrorCode.INTERNAL_ERROR,
                    "Artifact storage does not support atomic publication", exception);
        } catch (IOException exception) {
            deleteOwnedTempOrFail(reservation);
            interruptAfterFailure("artifact publish failed", exception);
            throw failure(ErrorCode.INTERNAL_ERROR,
                    "Unable to publish trace artifact", exception);
        }
    }

    /** Deletes one reservation temporary file through the artifact handle, failing on error. */
    private void deleteOwnedTempOrFail(ArtifactReservation reservation) {
        if (reservation.temporaryFileKey() == null) {
            return;
        }
        List<Throwable> failures = new ArrayList<>();
        deleteReservationTemp(reservation, failures);
        if (!failures.isEmpty()) {
            HarnessException failure = failure(ErrorCode.INTERNAL_ERROR,
                    "Unable to remove trace staging entry", null);
            failures.forEach(failure::addSuppressed);
            throw failure;
        }
    }

    private void deleteReservationTemp(ArtifactReservation reservation,
            List<Throwable> failures) {
        if (reservation.temporaryFileKey() == null) {
            return;
        }
        try (SecureDirectoryStream<Path> artifactStream = openSecureStreamOrFail(
                reservation.artifactDirectory(), reservation.artifactFileKey(),
                "trace artifact staging")) {
            deleteChildChecked(artifactStream,
                    reservation.temporary().getFileName().toString(),
                    reservation.temporaryFileKey(), true, failures);
        } catch (IOException exception) {
            failures.add(exception);
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
        Object tempArchiveFileKey = null;
        boolean archivePublished = false;
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
        long archiveSize;
        try (SecureDirectoryStream<Path> rootStream = openRootStream()) {
            SeekableByteChannel tempChannel = rootStream.newByteChannel(Path.of(tempName),
                    Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                    ownerOnlyFileAttribute());
            tempArchiveFileKey = rootStream.getFileAttributeView(Path.of(tempName),
                    BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                    .readAttributes().fileKey();
            try (OutputStream raw = Channels.newOutputStream(tempChannel);
                    ZipOutputStream zip = new ZipOutputStream(
                            new BufferedOutputStream(new DigestOutputStream(raw, archiveDigest)),
                            StandardCharsets.UTF_8)) {
                copyEntry(zip, "events.ndjson", eventFile, stagingFileKey,
                        "events.ndjson", eventFileKey, eventsSha256);
                for (Map.Entry<String, ArtifactInfo> entry : artifacts.entrySet()) {
                    copyEntry(zip, "artifacts/" + entry.getKey(),
                            entry.getValue().path(), artifactFileKey,
                            entry.getKey(), entry.getValue().fileKey(), entry.getKey());
                }
                zip.putNextEntry(new ZipEntry("manifest.json"));
                zip.write(manifest.toJson());
                zip.closeEntry();
            }
            BasicFileAttributes attributes = rootStream.getFileAttributeView(
                    Path.of(tempName), BasicFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS).readAttributes();
            if (!Objects.equals(attributes.fileKey(), tempArchiveFileKey)) {
                throw failure(ErrorCode.INVALID_REQUEST,
                        "Trace archive storage changed unexpectedly", null);
            }
            archiveSize = attributes.size();
            requireOwnerOnly(temporaryArchive, false);
            String archiveSha256 = HexFormat.of().formatHex(archiveDigest.digest());
            verifyArchiveContent(rootStream, tempName, temporaryArchive,
                    tempArchiveFileKey, archiveSha256, archiveSize);
            interceptor.before(FinalizationInterceptor.Step.CHECK_DESTINATION, archive);
            if (secureEntryExists(rootStream, archiveName)) {
                throw failure(ErrorCode.INVALID_REQUEST,
                        "Trace archive destination already exists", null);
            }
            rootStream.move(Path.of(tempName), rootStream, Path.of(archiveName));
            archivePublished = true;
            verifyArchiveContent(rootStream, archiveName, archive,
                    tempArchiveFileKey, archiveSha256, archiveSize);
            requireOwnerOnly(archive, false);
            interceptor.before(FinalizationInterceptor.Step.AFTER_FINALIZE, archive);
        } catch (IOException exception) {
            List<Throwable> cleanupFailures = new ArrayList<>();
            deleteArchiveEntries(tempName, archiveName, tempArchiveFileKey,
                    archivePublished, cleanupFailures);
            cleanupFailures.addAll(cleanupStaging());
            HarnessException failure = failure(ErrorCode.INTERNAL_ERROR,
                    "Unable to finalize trace archive", exception);
            cleanupFailures.forEach(failure::addSuppressed);
            throw failure;
        } catch (RuntimeException failure) {
            List<Throwable> cleanupFailures = new ArrayList<>();
            deleteArchiveEntries(tempName, archiveName, tempArchiveFileKey,
                    archivePublished, cleanupFailures);
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

    /** Key-checked deletion of the temporary and published archive names below the root. */
    private void deleteArchiveEntries(String tempName, String archiveName,
            Object tempArchiveFileKey, boolean archivePublished,
            List<Throwable> failures) {
        try (SecureDirectoryStream<Path> rootStream = openRootStream()) {
            if (tempArchiveFileKey != null) {
                deleteChildChecked(rootStream, tempName, tempArchiveFileKey, true, failures);
            }
            if (archivePublished) {
                deleteChildChecked(rootStream, archiveName, tempArchiveFileKey, true, failures);
            }
        } catch (IOException exception) {
            failures.add(exception);
        }
    }

    /**
     * Copies one evidence file into the archive. The open, the identity check, and
     * the stream are all anchored to the verified parent directory handle, and the
     * bytes read from that handle are hashed and matched against the digest the
     * recorder recorded when it wrote the file.
     */
    private void copyEntry(ZipOutputStream zip, String entryName, Path source,
            Object parentFileKey, String childName, Object expectedFileKey,
            String expectedSha256) throws IOException {
        verifyOwnedRegularFile(source); // read-only fast-fail defense in depth
        interceptor.before(FinalizationInterceptor.Step.OPEN_EVIDENCE, source);
        try (SecureDirectoryStream<Path> parentStream = openSecureStreamOrFail(
                source.getParent(), parentFileKey, "trace evidence parent")) {
            Object currentKey = parentStream.getFileAttributeView(Path.of(childName),
                    BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                    .readAttributes().fileKey();
            if (!Objects.equals(currentKey, expectedFileKey)) {
                throw failure(ErrorCode.INVALID_REQUEST,
                        "Trace evidence file changed unexpectedly", null);
            }
            try (SeekableByteChannel channel = parentStream.newByteChannel(Path.of(childName),
                    Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
                    InputStream input = Channels.newInputStream(channel)) {
                zip.putNextEntry(new ZipEntry(entryName));
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
    }

    /**
     * Reopens the archive through the root handle and proves fileKey, byte count,
     * and SHA-256 under the same trusted exclusive namespace used to write it.
     */
    private void verifyArchiveContent(SecureDirectoryStream<Path> rootStream, String name,
            Path fullPath, Object expectedFileKey, String expectedDigest,
            long expectedSize) throws IOException {
        interceptor.before(FinalizationInterceptor.Step.VERIFY_ARCHIVE, fullPath);
        Object currentKey = rootStream.getFileAttributeView(Path.of(name),
                BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                .readAttributes().fileKey();
        if (!Objects.equals(currentKey, expectedFileKey)) {
            throw failure(ErrorCode.INVALID_REQUEST,
                    "Trace archive storage changed unexpectedly", null);
        }
        try (SeekableByteChannel channel = rootStream.newByteChannel(Path.of(name),
                Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
                InputStream input = Channels.newInputStream(channel)) {
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
            String name) throws IOException {
        try {
            rootStream.getFileAttributeView(Path.of(name), BasicFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS).readAttributes();
            return true;
        } catch (NoSuchFileException exception) {
            return false;
        }
    }

    private String failStreamedArtifact(
            ArtifactReservation reservation,
            String reason,
            Exception originalFailure,
            boolean limitFailure) {
        List<Throwable> cleanupFailures = new ArrayList<>();
        deleteReservationTemp(reservation, cleanupFailures);
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

    /** Cleans a reservation whose trace closed while the artifact was streaming. */
    private List<Throwable> cleanupDetachedReservation(ArtifactReservation reservation) {
        List<Throwable> failures = new ArrayList<>();
        deleteReservationTemp(reservation, failures);
        if (isUntamperedDirectory(
                reservation.artifactDirectory(), reservation.artifactFileKey())) {
            try (SecureDirectoryStream<Path> stagingStream = openSecureStreamOrFail(
                    reservation.stagingDirectory(), reservation.stagingFileKey(),
                    "trace staging")) {
                deleteChildChecked(stagingStream, "artifacts",
                        reservation.artifactFileKey(), false, failures);
                deleteChildChecked(stagingStream, "events.ndjson",
                        reservation.eventFileKey(), true, failures);
            } catch (IOException exception) {
                failures.add(exception);
            }
            try (SecureDirectoryStream<Path> rootStream = openRootStream()) {
                deleteChildChecked(rootStream,
                        reservation.stagingDirectory().getFileName().toString(),
                        reservation.stagingFileKey(), false, failures);
            } catch (IOException exception) {
                failures.add(exception);
            }
        } else if (isUntamperedDirectory(
                reservation.stagingDirectory(), reservation.stagingFileKey())) {
            try (SecureDirectoryStream<Path> rootStream = openRootStream()) {
                deleteChildChecked(rootStream,
                        reservation.stagingDirectory().getFileName().toString(),
                        reservation.stagingFileKey(), false, failures);
            } catch (IOException exception) {
                failures.add(exception);
            }
        }
        return failures;
    }

    private static void closeSourceAfterValidationFailure(
            InputStream source, Exception originalFailure) {
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

    /** Closes the event stream; a close failure is a primary finalization failure. */
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
     * Deletes every owned staging entry through verified secure directory handles,
     * comparing the expected fileKey of each child before deleting and never
     * deleting through a directory whose identity cannot be proven. Returns every
     * failure so callers can suppress them onto the primary outcome or turn a
     * nominal finalize into a terminal failure.
     */
    private List<Throwable> cleanupStaging() {
        List<Throwable> failures = new ArrayList<>();
        closeEventOutputCollecting(failures);
        eventOutput = null;
        if (stagingDirectory == null) {
            clearStagingState();
            return failures;
        }
        if (!isUntamperedDirectory(stagingDirectory, stagingFileKey)) {
            failures.add(new IOException(
                    "trace staging identity lost; residual evidence may remain under "
                            + stagingDirectory));
            clearStagingState();
            return failures;
        }
        boolean deferred = false;
        try (SecureDirectoryStream<Path> stagingStream = openSecureStreamOrFail(
                stagingDirectory, stagingFileKey, "trace staging")) {
            if (isUntamperedDirectory(artifactDirectory, artifactFileKey)) {
                try (SecureDirectoryStream<Path> artifactsStream = openSecureStreamOrFail(
                        artifactDirectory, artifactFileKey, "trace artifact staging")) {
                    for (String hash : artifacts.keySet()) {
                        deleteChildChecked(artifactsStream, hash,
                                artifacts.get(hash).fileKey(), true, failures);
                    }
                } catch (IOException exception) {
                    failures.add(exception);
                }
                switch (artifactCleanupState(artifactDirectory)) {
                    case EMPTY -> deleteChildChecked(stagingStream, "artifacts",
                            artifactFileKey, false, failures);
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
                try {
                    stagingStream.deleteFile(Path.of("artifacts")); // removes the link only
                } catch (IOException | RuntimeException exception) {
                    failures.add(exception);
                }
            } else if (artifactDirectory != null) {
                failures.add(new IOException(
                        "artifact directory identity lost; residual evidence may remain under "
                                + artifactDirectory));
            }
            deleteChildChecked(stagingStream, "events.ndjson", eventFileKey, true, failures);
        } catch (IOException exception) {
            failures.add(exception);
        }
        if (!deferred) {
            try (SecureDirectoryStream<Path> rootStream = openRootStream()) {
                deleteChildChecked(rootStream,
                        stagingDirectory.getFileName().toString(),
                        stagingFileKey, false, failures);
            } catch (IOException exception) {
                failures.add(exception);
            }
        }
        clearStagingState();
        return failures;
    }

    private void closeEventOutputCollecting(List<Throwable> failures) {
        if (eventOutput == null) {
            return;
        }
        try {
            eventOutput.close();
        } catch (IOException exception) {
            failures.add(exception);
        } finally {
            eventOutput = null;
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

    /**
     * Deletes one child entry by name through a verified parent handle, comparing
     * the expected fileKey first. A mismatch or any failure leaves the entry
     * untouched and records the failure. Deletion never follows a swapped entry.
     */
    private static void deleteChildChecked(SecureDirectoryStream<Path> parentStream,
            String name, Object expectedFileKey, boolean file, List<Throwable> failures) {
        if (expectedFileKey == null) {
            return;
        }
        try {
            BasicFileAttributes attributes = parentStream.getFileAttributeView(
                    Path.of(name), BasicFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS).readAttributes();
            if (!Objects.equals(attributes.fileKey(), expectedFileKey)) {
                failures.add(new IOException(
                        "cleanup identity mismatch; leaving entry untouched: " + name));
                return;
            }
            if (file) {
                parentStream.deleteFile(Path.of(name));
            } else {
                parentStream.deleteDirectory(Path.of(name));
            }
        } catch (NoSuchFileException alreadyRemoved) {
            // already gone: nothing to do
        } catch (IOException | RuntimeException exception) {
            failures.add(exception);
        }
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

    /** Replaces the ACL with an exact owner-only allow entry and verifies the result. */
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
        List<AclEntry> acl = view.getAcl();
        if (acl.isEmpty()) {
            throw new IOException("trace storage ACL is empty: " + path);
        }
        for (AclEntry entry : acl) {
            if (entry.type() != AclEntryType.ALLOW || !entry.principal().equals(owner)) {
                throw new IOException("trace storage ACL is not owner-only: " + path);
            }
        }
    }

    /**
     * The configured root must already be exact owner-only and owned by the process
     * principal. The caller's root is never modified; a non-conforming root is
     * rejected so recording fails closed.
     */
    private static void requireExactRootPermissions(Path root, PermissionMode mode)
            throws IOException {
        if (mode == PermissionMode.POSIX) {
            if (!Files.getPosixFilePermissions(root).equals(ownerOnlyDirectoryPermissions())) {
                throw new IOException("trace root must be owner-only (0700): " + root);
            }
            if (!Files.getOwner(root, LinkOption.NOFOLLOW_LINKS)
                    .equals(processPrincipal())) {
                throw new IOException(
                        "trace root must be owned by the process principal: " + root);
            }
        } else {
            requireOwnerOnlyAcl(root);
        }
    }

    private static Path initializeRoot(Path configuredRoot, PermissionMode mode) {
        Objects.requireNonNull(configuredRoot, "root");
        Path normalized = configuredRoot.toAbsolutePath().normalize();
        try {
            if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(normalized)) {
                    throw new IllegalArgumentException(
                            "trace root must not be a symbolic link");
                }
                if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalArgumentException("trace root must be a directory");
                }
                return normalized; // exact permissions are verified by the caller
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
     * filesystem object as {@code expectedFileKey}. Recording fails closed when the
     * provider offers no secure directory streams or when identity cannot be
     * proven — there is no pathname fallback. Callers must close the stream.
     */
    @SuppressWarnings("unchecked")
    private static SecureDirectoryStream<Path> openSecureStreamOrFail(Path directory,
            Object expectedFileKey, String name) throws IOException {
        if (expectedFileKey == null || !Objects.equals(fileKeyOf(directory), expectedFileKey)) {
            throw new IOException(name + " identity cannot be proven");
        }
        DirectoryStream<Path> stream = Files.newDirectoryStream(directory);
        if (!(stream instanceof SecureDirectoryStream<?>)) {
            stream.close();
            throw new IOException(
                    name + " does not support secure directory streams");
        }
        SecureDirectoryStream<Path> secure = (SecureDirectoryStream<Path>) stream;
        try {
            Object handleKey = secure.getFileAttributeView(BasicFileAttributeView.class)
                    .readAttributes().fileKey();
            if (!Objects.equals(handleKey, expectedFileKey)) {
                secure.close();
                throw new IOException(
                        name + " identity changed between verification and open");
            }
            return secure;
        } catch (IOException | RuntimeException exception) {
            try {
                secure.close();
            } catch (IOException ignored) {
                // best effort after the primary failure
            }
            throw exception;
        }
    }

    private SecureDirectoryStream<Path> openRootStream() throws IOException {
        return openSecureStreamOrFail(root, rootFileKey, "trace root");
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
            Object eventFileKey,
            Path temporary,
            Object temporaryFileKey,
            String mediaType,
            long maxUncompressedBytes) {}

    private record ArtifactInfo(Path path, String mediaType, long size, Object fileKey) {}

    @SuppressWarnings("serial")
    private static final class ArtifactLimitException extends RuntimeException {}
}
