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
import java.nio.file.attribute.FileOwnerAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
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
    private final UserPrincipal trustedPrincipal;
    private final Clock clock;
    private final FinalizationInterceptor interceptor;
    private final ChildAttributeReader childAttributeReader;
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
    private long eventsFileSize;
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

    /**
     * Test-only seam that reads one child's nofollow attributes through a verified
     * parent handle. The production default reads real attributes; the inode-reuse
     * regressions override the reported fileKey to deterministically simulate a
     * delete/recreate collision without depending on filesystem allocation behavior.
     */
    @FunctionalInterface
    interface ChildAttributeReader {
        /**
         * Reads the nofollow attributes of {@code name} under {@code parent}.
         * {@code expectedFileKey} is the identity the caller recorded at creation
         * and is offered so a simulation can report a reused key.
         */
        BasicFileAttributes read(SecureDirectoryStream<Path> parent, String name,
                Object expectedFileKey) throws IOException;
    }

    private static final ChildAttributeReader REAL_CHILD_ATTRIBUTES =
            (parent, name, expectedFileKey) -> parent.getFileAttributeView(
                    Path.of(name), BasicFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS).readAttributes();

    /** Creates a recorder rooted below one non-symbolic-link server-owned directory. */
    public TraceRecorder(Path root, Clock clock) {
        this(root, clock, (step, path) -> { });
    }

    /** Test-only constructor injecting the finalization interceptor. */
    TraceRecorder(Path root, Clock clock, FinalizationInterceptor interceptor) {
        this(root, clock, interceptor, REAL_CHILD_ATTRIBUTES);
    }

    /** Test-only constructor injecting the finalization interceptor and attribute seam. */
    TraceRecorder(Path root, Clock clock, FinalizationInterceptor interceptor,
            ChildAttributeReader childAttributeReader) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.interceptor = Objects.requireNonNull(interceptor, "interceptor");
        this.childAttributeReader =
                Objects.requireNonNull(childAttributeReader, "childAttributeReader");
        Objects.requireNonNull(root, "root");
        this.permissionMode = detectPermissionMode(root.getFileSystem());
        this.root = initializeRoot(root, permissionMode);
        try {
            this.rootFileKey = requireFileKey(this.root, "trace root");
            // Secure directory streams are mandatory: recording fails closed when the
            // provider cannot anchor every child operation to a verified directory handle.
            try (SecureDirectoryStream<Path> stream = openSecureStreamOrFail(
                    this.root, this.rootFileKey, "trace root")) {
                // The effective filesystem principal is derived from a probe created
                // under the exact root handle (never from the mutable user.name).
                this.trustedPrincipal = deriveEffectivePrincipal(stream);
            }
            requireExactRootPermissions(this.root, permissionMode, this.trustedPrincipal);
            verifyTrustedAncestorChain(root, permissionMode, this.trustedPrincipal);
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                    "trace root identity or access contract cannot be verified", exception);
        }
    }

    /**
     * Creates an unpredictable probe file under the exact root handle, reads its
     * owner through the same handle, and key-check-deletes it. This proves the
     * effective creator principal under exact root access without consulting the
     * mutable {@code user.name} system property.
     */
    private UserPrincipal deriveEffectivePrincipal(
            SecureDirectoryStream<Path> rootStream) throws IOException {
        String probe = ".principal-probe-" + randomHex(16);
        Object probeKey;
        UserPrincipal probeOwner;
        SeekableByteChannel probeChannel = rootStream.newByteChannel(Path.of(probe),
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                ownerOnlyFileAttribute());
        try {
            probeOwner = rootStream.getFileAttributeView(Path.of(probe),
                    FileOwnerAttributeView.class, LinkOption.NOFOLLOW_LINKS).getOwner();
            probeKey = rootStream.getFileAttributeView(Path.of(probe),
                    BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                    .readAttributes().fileKey();
        } finally {
            probeChannel.close();
        }
        List<Throwable> failures = new ArrayList<>();
        deleteChildChecked(rootStream, probe, probeKey, null, true, failures);
        if (!failures.isEmpty()) {
            IOException failure = new IOException("unable to remove principal probe");
            failures.forEach(failure::addSuppressed);
            throw failure;
        }
        return probeOwner;
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
     * Establishes, before any recording mutation, that every ancestor of the configured
     * root up to a trust boundary is a NOFOLLOW real directory that cannot be replaced
     * or redirected by another principal. Sticky world-writable directories (for example
     * /tmp) and directories owned by another principal that the process cannot write
     * are trust boundaries: their entries are owner-protected or outside our reach.
     */
    private static void verifyTrustedAncestorChain(Path configuredRoot, PermissionMode mode,
            UserPrincipal trustedPrincipal) throws IOException {
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
                if (!Files.getOwner(current, LinkOption.NOFOLLOW_LINKS)
                        .equals(trustedPrincipal)) {
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
                // ACL ancestors: reject ANY non-owner write/delete-child ACE, owned or not.
                verifyAclAncestor(current, trustedPrincipal);
                if (!Files.getOwner(current, LinkOption.NOFOLLOW_LINKS)
                        .equals(trustedPrincipal)) {
                    break; // owned by another principal without write ACEs: boundary
                }
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
            if (entry.type() == AclEntryType.ALLOW
                    && !entry.principal().equals(principal)
                    && entry.permissions().stream()
                            .anyMatch(TraceRecorder::isAclWriteOrDeleteChild)) {
                throw new IOException(
                        "trace path component grants write to a non-owner principal: "
                                + directory);
            }
        }
    }

    private static boolean isAclWriteOrDeleteChild(AclEntryPermission permission) {
        return switch (permission) {
            case WRITE_DATA, APPEND_DATA, DELETE, DELETE_CHILD, WRITE_ATTRIBUTES,
                    WRITE_NAMED_ATTRS, WRITE_ACL, WRITE_OWNER -> true;
            default -> false;
        };
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
        eventsFileSize = 0;
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
            try (SecureDirectoryStream<Path> rootStream = openRootStream()) {
                verifyChildDirectory(rootStream, stagingName, stagingFileKey);
                requireOwnerOnlyChild(rootStream, stagingName, true);
            }

            artifactDirectory = stagingDirectory.resolve("artifacts");
            Files.createDirectory(artifactDirectory, ownerOnlyDirectoryAttributes());
            artifactFileKey = requireFileKey(artifactDirectory, "trace artifact staging");
            try (SecureDirectoryStream<Path> stagingStream = openSecureStreamOrFail(
                    stagingDirectory, stagingFileKey, "trace staging")) {
                verifyChildDirectory(stagingStream, "artifacts", artifactFileKey);
                requireOwnerOnlyChild(stagingStream, "artifacts", true);
            }

            eventFile = stagingDirectory.resolve("events.ndjson");
            try (SecureDirectoryStream<Path> stagingStream = openSecureStreamOrFail(
                    stagingDirectory, stagingFileKey, "trace staging")) {
                SeekableByteChannel channel = stagingStream.newByteChannel(
                        Path.of("events.ndjson"),
                        Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                        ownerOnlyFileAttribute());
                eventOutput = new BufferedOutputStream(Channels.newOutputStream(channel));
                requireOwnerOnlyChild(stagingStream, "events.ndjson", false);
                eventFileKey = stagingStream.getFileAttributeView(Path.of("events.ndjson"),
                        BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                        .readAttributes().fileKey();
            }
            active = true;
        } catch (IOException | HarnessException exception) {
            List<Throwable> cleanupFailures = cleanupStaging(emptyContentEvidence());
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
        eventsFileSize += added;
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
                    requireOwnerOnlyChild(artifactStream,
                            temporary.getFileName().toString(), false);
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
            return failStreamedArtifact(reservation, "byte limit exceeded", exception, true,
                    new ContentEvidence(HexFormat.of().formatHex(digest.digest()), size));
        } catch (IOException exception) {
            return failStreamedArtifact(reservation, "artifact write failed", exception, false,
                    new ContentEvidence(HexFormat.of().formatHex(digest.digest()), size));
        } catch (RuntimeException exception) {
            return failStreamedArtifact(reservation, "artifact callback failed", exception, false,
                    new ContentEvidence(HexFormat.of().formatHex(digest.digest()), size));
        }

        String hash = HexFormat.of().formatHex(digest.digest());
        ContentEvidence streamedEvidence = new ContentEvidence(hash, size);
        synchronized (this) {
            if (!matchesActiveReservation(reservation)) {
                List<Throwable> cleanupFailures =
                        cleanupDetachedReservation(reservation, streamedEvidence);
                HarnessException failure = failure(ErrorCode.SESSION_CLOSED,
                        "Trace closed while artifact evidence was streaming", null);
                cleanupFailures.forEach(failure::addSuppressed);
                throw failure;
            }
            Duration elapsed = Duration.between(startedAt, clock.instant());
            if (elapsed.isNegative() || elapsed.compareTo(limits.maxDuration()) >= 0) {
                List<Throwable> cleanupFailures = new ArrayList<>();
                deleteReservationTemp(reservation, streamedEvidence, cleanupFailures);
                HarnessException failure = failLimit("duration limit exceeded");
                cleanupFailures.forEach(failure::addSuppressed);
                throw failure;
            }
            ArtifactInfo existing = artifacts.get(hash);
            if (existing != null) {
                List<Throwable> cleanupFailures = new ArrayList<>();
                deleteReservationTemp(reservation, streamedEvidence, cleanupFailures);
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
                deleteReservationTemp(reservation, streamedEvidence, cleanupFailures);
                HarnessException failure = failLimit("byte limit exceeded");
                cleanupFailures.forEach(failure::addSuppressed);
                throw failure;
            }
            Path published = reservation.artifactDirectory().resolve(hash);
            Object publishedFileKey = publishArtifact(reservation, published, streamedEvidence);
            artifacts.put(hash, new ArtifactInfo(published, mediaType, size, publishedFileKey));
            uncompressedBytes += size;
            return hash;
        }
    }

    /**
     * Atomically publishes one artifact through the verified artifact directory
     * handle. The staged entry is re-proven (nofollow regular file, recorded
     * fileKey, exact content) before the move so a replacement can never be moved
     * into the published name, even when a reused fileKey collides.
     */
    private Object publishArtifact(ArtifactReservation reservation, Path published,
            ContentEvidence evidence) {
        try (SecureDirectoryStream<Path> artifactsStream = openSecureStreamOrFail(
                reservation.artifactDirectory(), reservation.artifactFileKey(),
                "trace artifact staging")) {
            String temporaryName = reservation.temporary().getFileName().toString();
            BasicFileAttributes current = childAttributeReader.read(
                    artifactsStream, temporaryName, reservation.temporaryFileKey());
            if (!current.isRegularFile()
                    || !Objects.equals(current.fileKey(), reservation.temporaryFileKey())
                    || !matchesContent(artifactsStream, temporaryName, evidence)) {
                throw failure(ErrorCode.INVALID_REQUEST,
                        "Trace artifact staging changed unexpectedly", null);
            }
            artifactsStream.move(reservation.temporary().getFileName(),
                    artifactsStream, Path.of(published.getFileName().toString()));
            requireOwnerOnlyChild(artifactsStream,
                    published.getFileName().toString(), false);
            return artifactsStream.getFileAttributeView(
                    Path.of(published.getFileName().toString()),
                    BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                    .readAttributes().fileKey();
        } catch (HarnessException failure) {
            List<Throwable> cleanupFailures = new ArrayList<>();
            deleteReservationTemp(reservation, evidence, cleanupFailures);
            cleanupFailures.forEach(failure::addSuppressed);
            throw failure;
        } catch (UnsupportedOperationException exception) {
            List<Throwable> cleanupFailures = new ArrayList<>();
            deleteReservationTemp(reservation, evidence, cleanupFailures);
            interruptAfterFailure("artifact atomic publish unsupported", exception);
            HarnessException failure = failure(ErrorCode.INTERNAL_ERROR,
                    "Artifact storage does not support atomic publication", exception);
            cleanupFailures.forEach(failure::addSuppressed);
            throw failure;
        } catch (IOException exception) {
            List<Throwable> cleanupFailures = new ArrayList<>();
            deleteReservationTemp(reservation, evidence, cleanupFailures);
            interruptAfterFailure("artifact publish failed", exception);
            HarnessException failure = failure(ErrorCode.INTERNAL_ERROR,
                    "Unable to publish trace artifact", exception);
            cleanupFailures.forEach(failure::addSuppressed);
            throw failure;
        }
    }

    private void deleteReservationTemp(ArtifactReservation reservation,
            ContentEvidence evidence, List<Throwable> failures) {
        // A null expected key after creation is not silent: deleteChildChecked
        // reports a residual failure and leaves the entry when one exists.
        try (SecureDirectoryStream<Path> artifactStream = openSecureStreamOrFail(
                reservation.artifactDirectory(), reservation.artifactFileKey(),
                "trace artifact staging")) {
            deleteChildChecked(artifactStream,
                    reservation.temporary().getFileName().toString(),
                    reservation.temporaryFileKey(), evidence, true, failures);
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
        // The events file is complete once the stream is closed; its immutable
        // content evidence binds every later cleanup deletion of the entry.
        String eventsSha256 = HexFormat.of().formatHex(eventDigest.digest());
        ContentEvidence eventsEvidence = new ContentEvidence(eventsSha256, eventsFileSize);
        try {
            verifyRoot();
            verifyStaging();
        } catch (HarnessException exception) {
            List<Throwable> cleanupFailures = cleanupStaging(eventsEvidence);
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
        Object destinationReservationKey = null;
        boolean archivePublished = false;
        LinkedHashMap<String, TraceManifest.ArtifactBinding> bindings = new LinkedHashMap<>();
        for (Map.Entry<String, ArtifactInfo> entry : artifacts.entrySet()) {
            bindings.put(entry.getKey(), new TraceManifest.ArtifactBinding(
                    entry.getKey(), entry.getValue().size(), entry.getValue().mediaType()));
        }
        // The manifest written inside the archive cannot carry its own archive digest
        // (self-reference), so it is encoded with blank (legacy) archive identity.
        TraceManifest archiveManifest = new TraceManifest(archive, sessionId, startedAt, endedAt,
                complete, reason, eventCount, artifacts.size(), uncompressedBytes,
                TraceManifest.V2, eventsSha256, bindings);
        MessageDigest archiveDigest = sha256();
        long archiveSize = -1;
        String archiveSha256 = null;
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
                        "events.ndjson", eventFileKey, eventsSha256, eventsFileSize);
                for (Map.Entry<String, ArtifactInfo> entry : artifacts.entrySet()) {
                    copyEntry(zip, "artifacts/" + entry.getKey(),
                            entry.getValue().path(), artifactFileKey,
                            entry.getKey(), entry.getValue().fileKey(), entry.getKey(),
                            entry.getValue().size());
                }
                zip.putNextEntry(new ZipEntry("manifest.json"));
                zip.write(archiveManifest.toJson());
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
            requireOwnerOnlyChild(rootStream, tempName, false);
            archiveSha256 = HexFormat.of().formatHex(archiveDigest.digest());
            verifyArchiveContent(rootStream, tempName, temporaryArchive,
                    tempArchiveFileKey, archiveSha256, archiveSize);
            // Reserve the destination with CREATE_NEW: an occupied destination fails
            // closed at the reservation, and the reservation fileKey lets the move be
            // proven to replace only our own reservation.
            interceptor.before(FinalizationInterceptor.Step.CHECK_DESTINATION, archive);
            SeekableByteChannel reservation;
            try {
                reservation = rootStream.newByteChannel(Path.of(archiveName),
                        Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                        ownerOnlyFileAttribute());
            } catch (java.nio.file.FileAlreadyExistsException exception) {
                throw failure(ErrorCode.INVALID_REQUEST,
                        "Trace archive destination already exists", exception);
            }
            try {
                destinationReservationKey = rootStream.getFileAttributeView(
                        Path.of(archiveName), BasicFileAttributeView.class,
                        LinkOption.NOFOLLOW_LINKS).readAttributes().fileKey();
            } finally {
                reservation.close();
            }
            Object currentDestinationKey = rootStream.getFileAttributeView(
                    Path.of(archiveName), BasicFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS).readAttributes().fileKey();
            if (!Objects.equals(currentDestinationKey, destinationReservationKey)) {
                throw failure(ErrorCode.INVALID_REQUEST,
                        "Trace archive destination changed unexpectedly", null);
            }
            rootStream.move(Path.of(tempName), rootStream, Path.of(archiveName));
            archivePublished = true;
            verifyArchiveContent(rootStream, archiveName, archive,
                    tempArchiveFileKey, archiveSha256, archiveSize);
            requireOwnerOnlyChild(rootStream, archiveName, false);
            interceptor.before(FinalizationInterceptor.Step.AFTER_FINALIZE, archive);
        } catch (IOException exception) {
            List<Throwable> cleanupFailures = new ArrayList<>();
            deleteArchiveEntries(tempName, archiveName, tempArchiveFileKey,
                    destinationReservationKey, archivePublished,
                    archiveSha256 == null ? null
                            : new ContentEvidence(archiveSha256, archiveSize),
                    cleanupFailures);
            cleanupFailures.addAll(cleanupStaging(eventsEvidence));
            HarnessException failure = failure(ErrorCode.INTERNAL_ERROR,
                    "Unable to finalize trace archive", exception);
            cleanupFailures.forEach(failure::addSuppressed);
            throw failure;
        } catch (RuntimeException failure) {
            List<Throwable> cleanupFailures = new ArrayList<>();
            deleteArchiveEntries(tempName, archiveName, tempArchiveFileKey,
                    destinationReservationKey, archivePublished,
                    archiveSha256 == null ? null
                            : new ContentEvidence(archiveSha256, archiveSize),
                    cleanupFailures);
            cleanupFailures.addAll(cleanupStaging(eventsEvidence));
            cleanupFailures.forEach(failure::addSuppressed);
            throw failure;
        }
        TraceManifest manifest = new TraceManifest(archive, sessionId, startedAt, endedAt,
                complete, reason, eventCount, artifacts.size(), uncompressedBytes,
                TraceManifest.V2, eventsSha256, bindings, archiveSha256, archiveSize);
        lastManifest = manifest;
        List<Throwable> cleanupFailures = cleanupStaging(eventsEvidence);
        if (!cleanupFailures.isEmpty()) {
            HarnessException failure = failure(ErrorCode.INTERNAL_ERROR,
                    "Trace archive published but staging cleanup failed", null);
            cleanupFailures.forEach(failure::addSuppressed);
            throw failure;
        }
        return manifest;
    }

    /**
     * Key-checked deletion of the temporary, reservation, and published archive
     * names. Every possibly-created name is attempted unconditionally: an absent
     * entry is fine, and an existing entry whose expected key is unknown or
     * mismatched is left with a residual failure.
     */
    private void deleteArchiveEntries(String tempName, String archiveName,
            Object tempArchiveFileKey, Object destinationReservationKey,
            boolean archivePublished, ContentEvidence archiveEvidence,
            List<Throwable> failures) {
        try (SecureDirectoryStream<Path> rootStream = openRootStream()) {
            if (archivePublished) {
                deleteChildChecked(rootStream, archiveName, tempArchiveFileKey,
                        archiveEvidence, true, failures);
            } else {
                deleteChildChecked(rootStream, archiveName, destinationReservationKey,
                        null, true, failures);
            }
            deleteChildChecked(rootStream, tempName, tempArchiveFileKey,
                    archiveEvidence, true, failures);
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
            String expectedSha256, long expectedSize) throws IOException {
        verifyOwnedRegularFile(source); // read-only fast-fail defense in depth
        interceptor.before(FinalizationInterceptor.Step.OPEN_EVIDENCE, source);
        try (SecureDirectoryStream<Path> parentStream = openSecureStreamOrFail(
                source.getParent(), parentFileKey, "trace evidence parent")) {
            BasicFileAttributes current = childAttributeReader.read(
                    parentStream, childName, expectedFileKey);
            if (!current.isRegularFile()) {
                throw failure(ErrorCode.INVALID_REQUEST,
                        "Trace evidence file is not a regular file", null);
            }
            if (!Objects.equals(current.fileKey(), expectedFileKey)) {
                throw failure(ErrorCode.INVALID_REQUEST,
                        "Trace evidence file changed unexpectedly", null);
            }
            try (SeekableByteChannel channel = parentStream.newByteChannel(Path.of(childName),
                    Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
                    InputStream input = Channels.newInputStream(channel)) {
                zip.putNextEntry(new ZipEntry(entryName));
                MessageDigest copyDigest = sha256();
                long copied = 0;
                byte[] buffer = new byte[COPY_BUFFER_SIZE];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    copyDigest.update(buffer, 0, read);
                    copied += read;
                    zip.write(buffer, 0, read);
                }
                zip.closeEntry();
                if (copied != expectedSize
                        || !HexFormat.of().formatHex(copyDigest.digest())
                                .equals(expectedSha256)) {
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
        BasicFileAttributes current = childAttributeReader.read(
                rootStream, name, expectedFileKey);
        if (!current.isRegularFile()) {
            throw failure(ErrorCode.INVALID_REQUEST,
                    "Trace archive is not a regular file", null);
        }
        if (!Objects.equals(current.fileKey(), expectedFileKey)) {
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

    private String failStreamedArtifact(
            ArtifactReservation reservation,
            String reason,
            Exception originalFailure,
            boolean limitFailure,
            ContentEvidence streamedEvidence) {
        List<Throwable> cleanupFailures = new ArrayList<>();
        deleteReservationTemp(reservation, streamedEvidence, cleanupFailures);
        synchronized (this) {
            if (!matchesActiveReservation(reservation)) {
                cleanupFailures.addAll(
                        cleanupDetachedReservation(reservation, streamedEvidence));
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
    private List<Throwable> cleanupDetachedReservation(ArtifactReservation reservation,
            ContentEvidence streamedEvidence) {
        List<Throwable> failures = new ArrayList<>();
        deleteReservationTemp(reservation, streamedEvidence, failures);
        if (isUntamperedDirectory(
                reservation.artifactDirectory(), reservation.artifactFileKey())) {
            try (SecureDirectoryStream<Path> stagingStream = openSecureStreamOrFail(
                    reservation.stagingDirectory(), reservation.stagingFileKey(),
                    "trace staging")) {
                deleteChildChecked(stagingStream, "artifacts",
                        reservation.artifactFileKey(), null, false, failures);
                // The events binding of a detached trace is not recoverable, and the
                // events entry was already deleted by the finalization cleanup; a
                // same-key substitute is still rejected by type and key checks.
                deleteChildChecked(stagingStream, "events.ndjson",
                        reservation.eventFileKey(), null, true, failures);
            } catch (IOException exception) {
                failures.add(exception);
            }
            try (SecureDirectoryStream<Path> rootStream = openRootStream()) {
                deleteChildChecked(rootStream,
                        reservation.stagingDirectory().getFileName().toString(),
                        reservation.stagingFileKey(), null, false, failures);
            } catch (IOException exception) {
                failures.add(exception);
            }
        } else if (isUntamperedDirectory(
                reservation.stagingDirectory(), reservation.stagingFileKey())) {
            try (SecureDirectoryStream<Path> rootStream = openRootStream()) {
                deleteChildChecked(rootStream,
                        reservation.stagingDirectory().getFileName().toString(),
                        reservation.stagingFileKey(), null, false, failures);
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
            List<Throwable> cleanupFailures = cleanupStaging(
                    new ContentEvidence(HexFormat.of().formatHex(eventDigest.digest()),
                            eventsFileSize));
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
    private List<Throwable> cleanupStaging(ContentEvidence eventsEvidence) {
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
                        ArtifactInfo info = artifacts.get(hash);
                        deleteChildChecked(artifactsStream, hash, info.fileKey(),
                                new ContentEvidence(hash, info.size()), true, failures);
                    }
                } catch (IOException exception) {
                    failures.add(exception);
                }
                try {
                    switch (artifactCleanupState(artifactDirectory)) {
                        case EMPTY -> deleteChildChecked(stagingStream, "artifacts",
                                artifactFileKey, null, false, failures);
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
                } catch (IOException exception) {
                    // preserve the exact enumeration failure for suppressed reporting
                    failures.add(exception);
                }
            } else if (artifactDirectory != null && Files.isSymbolicLink(artifactDirectory)) {
                // A substituted symbolic link is never unlinked: deleting the name
                // would remove an entry we cannot prove we own. Report residual risk.
                failures.add(new IOException(
                        "artifact directory was replaced by a symbolic link; "
                                + "leaving it untouched: " + artifactDirectory));
            } else if (artifactDirectory != null) {
                failures.add(new IOException(
                        "artifact directory identity lost; residual evidence may remain under "
                                + artifactDirectory));
            }
            deleteChildChecked(stagingStream, "events.ndjson", eventFileKey,
                    eventsEvidence, true, failures);
        } catch (IOException exception) {
            failures.add(exception);
        }
        if (!deferred) {
            try (SecureDirectoryStream<Path> rootStream = openRootStream()) {
                deleteChildChecked(rootStream,
                        stagingDirectory.getFileName().toString(),
                        stagingFileKey, null, false, failures);
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

    private static ArtifactCleanup artifactCleanupState(Path artifactDirectory)
            throws IOException {
        boolean sawEntry = false;
        try (var entries = Files.newDirectoryStream(artifactDirectory)) {
            for (Path entry : entries) {
                sawEntry = true;
                if (!entry.getFileName().toString().startsWith(".artifact-")) {
                    return ArtifactCleanup.UNEXPECTED;
                }
            }
        }
        return sawEntry ? ArtifactCleanup.IN_FLIGHT_TEMPS : ArtifactCleanup.EMPTY;
    }

    /**
     * Deletes one child entry by name through a verified parent handle after
     * proving nofollow type, expected fileKey, and (for regular files with
     * recorded evidence) exact content. A mismatch or any failure leaves the
     * entry untouched and records the failure. Deletion never follows a swapped
     * entry and never unlinks a replacement that only collides on fileKey.
     */
    private void deleteChildChecked(SecureDirectoryStream<Path> parentStream,
            String name, Object expectedFileKey, ContentEvidence expectedContent,
            boolean file, List<Throwable> failures) {
        if (expectedFileKey == null) {
            // Creation succeeded but no identity was captured: an existing entry
            // cannot be proven owned, so it is left and reported as residual.
            try {
                parentStream.getFileAttributeView(Path.of(name), BasicFileAttributeView.class,
                        LinkOption.NOFOLLOW_LINKS).readAttributes();
                failures.add(new IOException(
                        "cleanup identity unknown; leaving entry untouched: " + name));
            } catch (NoSuchFileException alreadyRemoved) {
                // nothing there: fine
            } catch (IOException | RuntimeException exception) {
                failures.add(exception);
            }
            return;
        }
        try {
            BasicFileAttributes attributes = childAttributeReader.read(
                    parentStream, name, expectedFileKey);
            if (file && !attributes.isRegularFile()) {
                failures.add(new IOException(
                        "cleanup entry is not a regular file; leaving entry untouched: "
                                + name));
                return;
            }
            if (!file && !attributes.isDirectory()) {
                failures.add(new IOException(
                        "cleanup entry is not a directory; leaving entry untouched: "
                                + name));
                return;
            }
            if (!Objects.equals(attributes.fileKey(), expectedFileKey)) {
                failures.add(new IOException(
                        "cleanup identity mismatch; leaving entry untouched: " + name));
                return;
            }
            if (expectedContent != null && !matchesContent(parentStream, name,
                    expectedContent)) {
                failures.add(new IOException(
                        "cleanup content does not match recorded evidence; "
                                + "leaving entry untouched: " + name));
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

    /**
     * Hashes one child's current bytes through the verified parent handle and
     * compares them against the recorded immutable evidence. Never follows a
     * substituted link; a swapped entry fails the anchored open.
     */
    private static boolean matchesContent(SecureDirectoryStream<Path> parentStream,
            String name, ContentEvidence expected) throws IOException {
        try (SeekableByteChannel channel = parentStream.newByteChannel(Path.of(name),
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
            return size == expected.size()
                    && HexFormat.of().formatHex(digest.digest()).equals(expected.sha256());
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

    /**
     * Sets and then exact-equality verifies owner-only permissions on one child
     * through the verified parent handle — never through an absolute child path.
     */
    private void requireOwnerOnlyChild(SecureDirectoryStream<Path> parentStream,
            String name, boolean directory) throws IOException {
        switch (permissionMode) {
            case POSIX -> {
                PosixFileAttributeView view = parentStream.getFileAttributeView(
                        Path.of(name), PosixFileAttributeView.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (view == null) {
                    throw new IOException("posix view unavailable for " + name);
                }
                Set<PosixFilePermission> expected = directory
                        ? ownerOnlyDirectoryPermissions() : ownerOnlyFilePermissions();
                view.setPermissions(expected);
                if (!view.readAttributes().permissions().equals(expected)) {
                    throw new IOException(
                            "trace storage permissions are not owner-only: " + name);
                }
            }
            case ACL -> requireOwnerOnlyChildAcl(parentStream, name);
        }
    }

    /** Sets and then exact-equality verifies one nonempty owner-only ALLOW entry. */
    private static void requireOwnerOnlyChildAcl(SecureDirectoryStream<Path> parentStream,
            String name) throws IOException {
        AclFileAttributeView view = parentStream.getFileAttributeView(
                Path.of(name), AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) {
            throw new IOException("acl view unavailable for " + name);
        }
        UserPrincipal owner = view.getOwner();
        AclEntry ownerEntry = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                .build();
        view.setAcl(List.of(ownerEntry));
        if (!isExactOwnerOnlyAcl(view.getAcl(), owner)) {
            throw new IOException("trace storage ACL is not exactly owner-only: " + name);
        }
    }

    /**
     * The configured root must already be exactly owner-only and owned by the derived
     * principal. The caller's root is never modified; a non-conforming root is
     * rejected so recording fails closed. The ACL form is verified only, never
     * rewritten: one nonempty owner ALLOW entry with the complete permission set and
     * no flags, inherited, other, or deny entries.
     */
    private static void requireExactRootPermissions(Path root, PermissionMode mode,
            UserPrincipal trustedPrincipal) throws IOException {
        if (mode == PermissionMode.POSIX) {
            if (!Files.getPosixFilePermissions(root).equals(ownerOnlyDirectoryPermissions())) {
                throw new IOException("trace root must be owner-only (0700): " + root);
            }
            if (!Files.getOwner(root, LinkOption.NOFOLLOW_LINKS)
                    .equals(trustedPrincipal)) {
                throw new IOException(
                        "trace root must be owned by the process principal: " + root);
            }
        } else {
            AclFileAttributeView view = Files.getFileAttributeView(
                    root, AclFileAttributeView.class);
            if (view == null) {
                throw new IOException("acl view unavailable for trace root: " + root);
            }
            if (!view.getOwner().equals(trustedPrincipal)) {
                throw new IOException(
                        "trace root must be owned by the process principal: " + root);
            }
            if (!isExactOwnerOnlyAcl(view.getAcl(), trustedPrincipal)) {
                throw new IOException(
                        "trace root ACL must be exactly one owner-only allow entry: "
                                + root);
            }
        }
    }

    private static boolean isExactOwnerOnlyAcl(List<AclEntry> acl, UserPrincipal owner) {
        if (acl.size() != 1) {
            return false;
        }
        AclEntry entry = acl.get(0);
        return entry.type() == AclEntryType.ALLOW
                && entry.principal().equals(owner)
                && entry.permissions().equals(EnumSet.allOf(AclEntryPermission.class))
                && entry.flags().isEmpty();
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

    /**
     * Immutable expected content evidence binding one regular file entry to the
     * bytes the recorder wrote, so cleanup and publication never unlink or move
     * an entry whose content no longer matches, even when a reused fileKey
     * collides with a replacement.
     */
    record ContentEvidence(String sha256, long size) {}

    /** Content evidence of the freshly created empty destination reservation. */
    private static ContentEvidence emptyContentEvidence() {
        return new ContentEvidence(HexFormat.of().formatHex(sha256().digest()), 0);
    }

    @SuppressWarnings("serial")
    private static final class ArtifactLimitException extends RuntimeException {}
}
