package dev.gdx.uiharness.protocol;

import dev.gdx.uiharness.core.error.ErrorCode;
import dev.gdx.uiharness.core.error.ErrorEvidence;
import dev.gdx.uiharness.core.error.HarnessException;
import java.io.BufferedOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Filesystem artifact store with opaque mapping, exact quotas, and session ownership. */
public final class FileArtifactStore implements ArtifactStore {
    private static final int COPY_BUFFER_SIZE = 16 * 1024;
    private static final int MAX_SESSION_ID_LENGTH = 16_384;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path root;
    private final Path realRoot;
    private final Limits limits;
    private final Clock clock;
    private final Map<String, Session> sessions = new HashMap<>();
    private final Set<String> disposedSessions = new HashSet<>();
    private boolean closed;

    /** Creates a store below a non-symbolic-link normalized server-owned root. */
    public FileArtifactStore(Path root, Limits limits, Clock clock) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.root = initializeRoot(root);
        try {
            realRoot = this.root.toRealPath(LinkOption.NOFOLLOW_LINKS);
        } catch (IOException exception) {
            throw new IllegalArgumentException("artifact root cannot be resolved", exception);
        }
    }

    /** Streams, hashes, deduplicates, and atomically publishes one artifact. */
    @Override public synchronized ArtifactId put(
            String sessionId,
            ArtifactMediaType mediaType,
            InputStream source,
            Instant expiresAt) {
        Objects.requireNonNull(source, "source");
        try (source) {
            requireOpen();
            verifyRoot();
            validateSessionId(sessionId);
            Objects.requireNonNull(mediaType, "mediaType");
            Objects.requireNonNull(expiresAt, "expiresAt");
            if (!expiresAt.isAfter(clock.instant())) {
                throw new IllegalArgumentException("expiresAt must be in the future");
            }
            Session session = sessionForWrite(sessionId);
            cleanupSessionExpired(session, clock.instant());
            return streamIntoSession(session, mediaType, source, expiresAt);
        } catch (HarnessException | IllegalArgumentException exception) {
            throw exception;
        } catch (IOException exception) {
            throw failure(ErrorCode.INTERNAL_ERROR, "Unable to close artifact source", exception);
        }
    }

    /** Opens a no-follow tracked read stream for a session-owned unexpired artifact. */
    @Override public synchronized InputStream read(String sessionId, ArtifactId artifactId) {
        EntrySelection selection = select(sessionId, artifactId);
        verifyBlob(selection.session(), selection.entry().blob());
        try {
            InputStream input = Files.newInputStream(selection.entry().blob().path,
                    StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
            TrackedInputStream tracked = new TrackedInputStream(
                    input, selection.session(), artifactId);
            selection.session().readers.add(tracked);
            return tracked;
        } catch (IOException exception) {
            throw failure(ErrorCode.INVALID_REQUEST, "Artifact file is unavailable", exception);
        }
    }

    /** Returns immutable metadata after applying the same ownership and expiry checks as reads. */
    @Override public synchronized Metadata metadata(String sessionId, ArtifactId artifactId) {
        EntrySelection selection = select(sessionId, artifactId);
        Entry entry = selection.entry();
        verifyBlob(selection.session(), entry.blob());
        return new Metadata(entry.mediaType(), entry.blob().size, entry.blob().sha256,
                entry.expiresAt());
    }

    /** Removes expired entries at the exact expiry instant. */
    @Override public synchronized int cleanupExpired() {
        requireOpen();
        verifyRoot();
        int removed = 0;
        Instant now = clock.instant();
        for (Session session : new ArrayList<>(sessions.values())) {
            removed += cleanupSessionExpired(session, now);
            removeEmptySession(session);
        }
        return removed;
    }

    /** Idempotently disposes one session without traversing caller-controlled paths. */
    @Override public synchronized void disposeSession(String sessionId) {
        requireOpen();
        verifyRoot();
        validateSessionId(sessionId);
        disposedSessions.add(sessionId);
        Session session = sessions.get(sessionId);
        if (session != null) {
            deleteSession(session);
            sessions.remove(sessionId, session);
        }
    }

    /** Idempotently closes readers and deletes only directories created by this store. */
    @Override public synchronized void close() {
        if (closed) {
            return;
        }
        for (Session session : new ArrayList<>(sessions.values())) {
            deleteSession(session);
        }
        sessions.clear();
        disposedSessions.clear();
        closed = true;
    }

    private ArtifactId streamIntoSession(
            Session session,
            ArtifactMediaType mediaType,
            InputStream source,
            Instant expiresAt) {
        verifySession(session);
        Path temporary = session.path.resolve(".artifact-" + randomHex(16) + ".tmp");
        MessageDigest digest = sha256();
        long size = 0;
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(temporary,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
            int read;
            while ((read = source.read(buffer)) != -1) {
                if (size > limits.maxBytes() - read) {
                    throw new ArtifactLimitException();
                }
                output.write(buffer, 0, read);
                digest.update(buffer, 0, read);
                size += read;
            }
        } catch (ArtifactLimitException exception) {
            deleteIfExists(temporary);
            removeEmptySession(session);
            throw failure(ErrorCode.LIMIT_EXCEEDED, "Artifact exceeds session byte quota", null);
        } catch (IOException exception) {
            deleteIfExists(temporary);
            removeEmptySession(session);
            throw failure(ErrorCode.INTERNAL_ERROR, "Unable to stream artifact", exception);
        } catch (RuntimeException exception) {
            deleteIfExists(temporary);
            removeEmptySession(session);
            throw exception;
        }

        String hash = HexFormat.of().formatHex(digest.digest());
        DedupKey dedupKey = new DedupKey(hash, mediaType, expiresAt);
        ArtifactId exactDuplicate = session.deduplicatedEntries.get(dedupKey);
        if (exactDuplicate != null) {
            deleteIfExists(temporary);
            return exactDuplicate;
        }
        if (session.entries.size() >= limits.maxArtifacts()) {
            deleteIfExists(temporary);
            removeEmptySession(session);
            throw failure(ErrorCode.LIMIT_EXCEEDED, "Artifact count quota exceeded", null);
        }

        Blob blob = session.blobs.get(hash);
        if (blob == null) {
            if (session.bytes > limits.maxBytes() - size) {
                deleteIfExists(temporary);
                removeEmptySession(session);
                throw failure(ErrorCode.LIMIT_EXCEEDED, "Artifact byte quota exceeded", null);
            }
            Path published = session.path.resolve("blob-" + randomHex(16));
            atomicMove(temporary, published);
            blob = new Blob(published, hash, size);
            session.blobs.put(hash, blob);
            session.bytes += size;
        } else {
            deleteIfExists(temporary);
        }

        ArtifactId id = newArtifactId(session);
        blob.references++;
        session.entries.put(id, new Entry(blob, mediaType, expiresAt, dedupKey));
        session.deduplicatedEntries.put(dedupKey, id);
        return id;
    }

    private EntrySelection select(String sessionId, ArtifactId artifactId) {
        requireOpen();
        verifyRoot();
        validateSessionId(sessionId);
        Objects.requireNonNull(artifactId, "artifactId");
        Session session = sessions.get(sessionId);
        if (session == null || disposedSessions.contains(sessionId)) {
            throw notFound(sessionId, artifactId);
        }
        verifySession(session);
        Entry entry = session.entries.get(artifactId);
        if (entry == null) {
            throw notFound(sessionId, artifactId);
        }
        if (!clock.instant().isBefore(entry.expiresAt())) {
            removeEntry(session, artifactId, entry);
            removeEmptySession(session);
            throw notFound(sessionId, artifactId);
        }
        return new EntrySelection(session, entry);
    }

    private Session sessionForWrite(String sessionId) {
        if (disposedSessions.contains(sessionId)) {
            throw failure(ErrorCode.SESSION_CLOSED,
                    "Artifact session has been disposed", null);
        }
        Session existing = sessions.get(sessionId);
        if (existing != null) {
            verifySession(existing);
            return existing;
        }
        for (int attempt = 0; attempt < 16; attempt++) {
            Path directory = root.resolve("session-" + randomHex(16));
            try {
                Files.createDirectory(directory);
                Session created = new Session(sessionId, directory,
                        directory.toRealPath(LinkOption.NOFOLLOW_LINKS));
                sessions.put(sessionId, created);
                return created;
            } catch (java.nio.file.FileAlreadyExistsException ignored) {
                // Retry the negligible random collision without exposing a caller path.
            } catch (IOException exception) {
                throw failure(ErrorCode.INTERNAL_ERROR,
                        "Unable to create artifact session storage", exception);
            }
        }
        throw failure(ErrorCode.INTERNAL_ERROR,
                "Unable to allocate artifact session storage", null);
    }

    private int cleanupSessionExpired(Session session, Instant now) {
        verifySession(session);
        ArrayList<ArtifactId> expired = new ArrayList<>();
        session.entries.forEach((id, entry) -> {
            if (!now.isBefore(entry.expiresAt())) {
                expired.add(id);
            }
        });
        for (ArtifactId id : expired) {
            Entry entry = session.entries.get(id);
            if (entry != null) {
                removeEntry(session, id, entry);
            }
        }
        return expired.size();
    }

    private void removeEntry(Session session, ArtifactId id, Entry entry) {
        closeReaders(session, id);
        session.entries.remove(id);
        session.deduplicatedEntries.remove(entry.dedupKey(), id);
        Blob blob = entry.blob();
        blob.references--;
        if (blob.references == 0) {
            verifySession(session);
            deleteIfExists(blob.path);
            session.blobs.remove(blob.sha256);
            session.bytes -= blob.size;
        }
    }

    private void removeEmptySession(Session session) {
        if (!session.entries.isEmpty() || !session.blobs.isEmpty() || !session.readers.isEmpty()) {
            return;
        }
        if (!isUntamperedSession(session)) {
            return;
        }
        try {
            Files.deleteIfExists(session.path);
            sessions.remove(session.sessionId, session);
        } catch (IOException ignored) {
            // Keep the owned session registered so close or disposal can retry.
        }
    }

    private void deleteSession(Session session) {
        closeReaders(session, null);
        if (!isUntamperedSession(session)) {
            if (Files.isSymbolicLink(session.path)) {
                deleteIfExists(session.path);
            }
            return;
        }
        for (Blob blob : session.blobs.values()) {
            deleteIfExists(blob.path);
        }
        try {
            Files.deleteIfExists(session.path);
        } catch (IOException exception) {
            throw failure(ErrorCode.INTERNAL_ERROR,
                    "Unable to dispose artifact session storage", exception);
        }
        session.entries.clear();
        session.deduplicatedEntries.clear();
        session.blobs.clear();
        session.bytes = 0;
    }

    private void closeReaders(Session session, ArtifactId selectedId) {
        for (TrackedInputStream reader : new ArrayList<>(session.readers)) {
            if (selectedId == null || selectedId.equals(reader.artifactId)) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                    // File stream close cannot be recovered; continue disposing owned files.
                }
            }
        }
    }

    private void verifyRoot() {
        try {
            if (Files.isSymbolicLink(root)
                    || !root.toRealPath(LinkOption.NOFOLLOW_LINKS).equals(realRoot)) {
                throw failure(ErrorCode.INVALID_REQUEST,
                        "Artifact root changed unexpectedly", null);
            }
        } catch (IOException exception) {
            throw failure(ErrorCode.INVALID_REQUEST, "Artifact root is unavailable", exception);
        }
    }

    private void verifySession(Session session) {
        if (!isUntamperedSession(session)) {
            throw failure(ErrorCode.INVALID_REQUEST,
                    "Artifact session storage changed unexpectedly", null);
        }
    }

    private boolean isUntamperedSession(Session session) {
        Path normalized = session.path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root) || Files.isSymbolicLink(normalized)
                || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try {
            return normalized.toRealPath(LinkOption.NOFOLLOW_LINKS).equals(session.realPath)
                    && session.realPath.startsWith(realRoot);
        } catch (IOException exception) {
            return false;
        }
    }

    private void verifyBlob(Session session, Blob blob) {
        verifySession(session);
        Path normalized = blob.path.toAbsolutePath().normalize();
        if (!normalized.startsWith(session.path) || Files.isSymbolicLink(normalized)
                || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw failure(ErrorCode.INVALID_REQUEST, "Unsafe artifact storage path", null);
        }
    }

    private void atomicMove(Path temporary, Path published) {
        verifyRoot();
        try {
            Files.move(temporary, published, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            deleteIfExists(temporary);
            throw failure(ErrorCode.INTERNAL_ERROR,
                    "Artifact storage does not support atomic publication", exception);
        } catch (IOException exception) {
            deleteIfExists(temporary);
            throw failure(ErrorCode.INTERNAL_ERROR, "Unable to publish artifact", exception);
        }
    }

    private void requireOpen() {
        if (closed) {
            throw failure(ErrorCode.SESSION_CLOSED, "Artifact store is closed", null);
        }
    }

    private HarnessException notFound(String sessionId, ArtifactId artifactId) {
        return new HarnessException(ErrorCode.NOT_FOUND, "Artifact is unavailable",
                ErrorEvidence.ofDetails(Map.of(
                        "sessionId", sessionId,
                        "artifactId", artifactId.value())));
    }

    private static HarnessException failure(ErrorCode code, String message, Throwable cause) {
        return new HarnessException(code, message,
                ErrorEvidence.ofDetails(Map.of("component", "artifact-store")), cause);
    }

    private static Path initializeRoot(Path configuredRoot) {
        Objects.requireNonNull(configuredRoot, "root");
        Path normalized = configuredRoot.toAbsolutePath().normalize();
        try {
            if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)
                    && Files.isSymbolicLink(normalized)) {
                throw new IllegalArgumentException("artifact root must not be a symbolic link");
            }
            Files.createDirectories(normalized);
            if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("artifact root must be a directory");
            }
            return normalized;
        } catch (IOException exception) {
            throw new IllegalArgumentException("artifact root cannot be created", exception);
        }
    }

    private static void validateSessionId(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        if (sessionId.isBlank() || sessionId.length() > MAX_SESSION_ID_LENGTH) {
            throw new IllegalArgumentException("sessionId must be bounded and non-blank");
        }
    }

    private static ArtifactId newArtifactId(Session session) {
        ArtifactId candidate;
        do {
            candidate = new ArtifactId(randomHex(16));
        } while (session.entries.containsKey(candidate));
        return candidate;
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

    private static void deleteIfExists(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // A later close/disposal retries owned directory cleanup.
        }
    }

    private final class TrackedInputStream extends FilterInputStream {
        private final Session session;
        private final ArtifactId artifactId;
        private boolean streamClosed;

        private TrackedInputStream(InputStream input, Session session, ArtifactId artifactId) {
            super(input);
            this.session = session;
            this.artifactId = artifactId;
        }

        @Override public void close() throws IOException {
            synchronized (FileArtifactStore.this) {
                if (streamClosed) {
                    return;
                }
                streamClosed = true;
                try {
                    super.close();
                } finally {
                    session.readers.remove(this);
                }
            }
        }
    }

    private static final class Session {
        private final String sessionId;
        private final Path path;
        private final Path realPath;
        private final Map<ArtifactId, Entry> entries = new HashMap<>();
        private final Map<DedupKey, ArtifactId> deduplicatedEntries = new HashMap<>();
        private final Map<String, Blob> blobs = new HashMap<>();
        private final Set<TrackedInputStream> readers = new HashSet<>();
        private long bytes;

        private Session(String sessionId, Path path, Path realPath) {
            this.sessionId = sessionId;
            this.path = path;
            this.realPath = realPath;
        }
    }

    private static final class Blob {
        private final Path path;
        private final String sha256;
        private final long size;
        private int references;

        private Blob(Path path, String sha256, long size) {
            this.path = path;
            this.sha256 = sha256;
            this.size = size;
        }
    }

    private record DedupKey(String sha256, ArtifactMediaType mediaType, Instant expiresAt) {}

    private record Entry(
            Blob blob,
            ArtifactMediaType mediaType,
            Instant expiresAt,
            DedupKey dedupKey) {}

    private record EntrySelection(Session session, Entry entry) {}

    @SuppressWarnings("serial")
    private static final class ArtifactLimitException extends RuntimeException {}
}
