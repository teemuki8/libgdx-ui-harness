package dev.gdx.uiharness.core.trace;

import dev.gdx.uiharness.core.error.ErrorCode;
import dev.gdx.uiharness.core.error.ErrorEvidence;
import dev.gdx.uiharness.core.error.HarnessException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Streams a bounded trace archive to validate its manifest and causal transitions.
 *  The untrusted archive is opened once and read through a single descriptor into
 *  an exact bounded byte array; the digest, the local-header parse, and the strict
 *  central-directory parse all operate on those same immutable bytes, so no path
 *  is ever reopened and a concurrent source replacement cannot change the bytes
 *  that are validated or the digest that is reported. Entry names, duplicates,
 *  per-entry compression ratios, and per-entry byte totals are measured directly
 *  from the DEFLATE streams, the central directory must name exactly the same
 *  entries with the same sizes, and v2 manifests bind every event and artifact
 *  digest, size, and count to the measured bytes, so a load reports
 *  {@link TraceReplay.Integrity#VERIFIED} only when every binding matched. */
public final class TraceReplayer {
    private static final Pattern SHA256_PATTERN = Pattern.compile("[0-9a-f]{64}");
    private static final int ZIP64_MARKER = 0xffff;
    private final Limits limits;
    private final Consumer<Path> afterSnapshotHook;

    /** Creates a replayer with conservative untrusted-archive limits. */
    public TraceReplayer() {
        this(Limits.defaults(), null);
    }

    /** Creates a replayer with explicit untrusted-archive limits. */
    public TraceReplayer(Limits limits) {
        this(limits, null);
    }

    /** Creates a replayer with explicit untrusted-archive limits, a clock, and a
     *  snapshot directory. The clock and directory are accepted for released-API
     *  compatibility: replay validation is deterministic and reads the archive
     *  into a bounded private byte array, so no snapshot file is materialized. */
    public TraceReplayer(Limits limits, Clock clock, Path snapshotDirectory) {
        this(limits, null);
    }

    /** Package-private test seam: runs once the archive bytes are safely captured
     *  and receipt-verified, receiving the archive path, before any parsing. */
    TraceReplayer(Limits limits, Consumer<Path> afterSnapshotHook) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.afterSnapshotHook = afterSnapshotHook;
    }

    /** Loads and validates one trace without retaining its event or artifact contents.
     *  The archive is opened once and read into an exact bounded byte array, so a
     *  concurrent source replacement cannot change the bytes that are validated or
     *  the digest that is reported. */
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
        try (FileChannel channel = FileChannel.open(archive,
                StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long size = channel.size();
            if (size > limits.maxArchiveBytes() || size > Integer.MAX_VALUE) {
                throw failure(ErrorCode.LIMIT_EXCEEDED,
                        "Trace archive exceeds replay byte limit", null);
            }
            byte[] bytes = new byte[(int) size];
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) == -1) {
                    throw failure(ErrorCode.INVALID_REQUEST,
                            "Trace archive shrank while reading", null);
                }
            }
            if (channel.read(ByteBuffer.allocate(1)) != -1) {
                throw failure(ErrorCode.INVALID_REQUEST,
                        "Trace archive grew while reading", null);
            }
            String archiveDigest = digestBytes(bytes);
            if (expectedArchiveSha256 != null && !archiveDigest.equals(expectedArchiveSha256)) {
                throw failure(ErrorCode.INVALID_REQUEST,
                        "Trace archive digest does not match the receipt", null);
            }
            if (expectedArchiveSize != -1 && size != expectedArchiveSize) {
                throw failure(ErrorCode.INVALID_REQUEST,
                        "Trace archive size does not match the receipt", null);
            }
            if (afterSnapshotHook != null) {
                afterSnapshotHook.accept(archive);
            }
            ReplayBudget budget = new ReplayBudget();
            Structural structural = scanLocalEntries(archive, bytes, budget);
            CentralDirectory central = parseCentralDirectory(bytes);
            requireMatchingCentral(bytes, structural, central);
            boolean verifiedFormat = TraceManifest.V2.equals(structural.manifest().schemaVersion());
            return readEvents(bytes, structural.manifest(), budget, archiveDigest,
                    verifiedFormat
                            ? TraceReplay.Integrity.VERIFIED
                            : TraceReplay.Integrity.UNVERIFIED);
        } catch (HarnessException exception) {
            throw exception;
        } catch (IOException exception) {
            throw failure(ErrorCode.INVALID_REQUEST, "Trace archive is unreadable", exception);
        }
    }

    /** SHA-256 over the exact captured archive bytes. */
    private static String digestBytes(byte[] bytes) {
        return HexFormat.of().formatHex(sha256().digest(bytes));
    }

    /** One bounded streaming pass over the local entry headers that rejects unsafe
     *  names, duplicates, and unreasonable per-entry compression ratios (measured
     *  from the actual DEFLATE streams, never from forgeable metadata), decodes the
     *  manifest entry, and records the ordered names and measured sizes that the
     *  central directory must match exactly. */
    private Structural scanLocalEntries(Path archive, byte[] bytes, ReplayBudget budget)
            throws IOException {
        int ratioLimit = limits.maxCompressionRatio();
        List<String> names = new ArrayList<>();
        List<Integer> methods = new ArrayList<>();
        List<Long> inflatedSizes = new ArrayList<>();
        List<Long> compressedSizes = new ArrayList<>();
        List<Long> crcs = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        ByteArrayOutputStream manifestJson = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long inflatedTotal = 0;
        int entries = 0;
        boolean manifestSeen = false;
        try (MeasuringZipInputStream zip = new MeasuringZipInputStream(
                new ByteArrayInputStream(bytes))) {
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
                if (!seen.add(name)) {
                    throw failure(ErrorCode.INVALID_REQUEST,
                            "Trace archive contains duplicate entries", null);
                }
                long inflated = 0;
                CRC32 crc = new CRC32();
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    inflated += read;
                    inflatedTotal += read;
                    crc.update(buffer, 0, read);
                    if (inflatedTotal > limits.maxTotalInflatedBytes()) {
                        throw failure(ErrorCode.LIMIT_EXCEEDED,
                                "Trace exceeds cumulative inflated byte limit", null);
                    }
                    if (name.equals("manifest.json")) {
                        budget.charge(read);
                        if (manifestJson.size() + read > limits.maxEventBytes()) {
                            throw failure(ErrorCode.LIMIT_EXCEEDED,
                                    "Trace manifest exceeds replay byte limit", null);
                        }
                        manifestJson.write(buffer, 0, read);
                    }
                }
                long compressed = entry.getMethod() == ZipEntry.STORED
                        ? inflated : zip.compressedBytes();
                if (inflated > 0 && (compressed == 0
                        || inflated / ratioLimit > compressed
                        || (inflated / ratioLimit == compressed
                        && inflated % ratioLimit != 0))) {
                    throw failure(ErrorCode.LIMIT_EXCEEDED,
                            "Trace entry compression ratio exceeds replay limit", null);
                }
                names.add(name);
                methods.add(entry.getMethod());
                inflatedSizes.add(inflated);
                compressedSizes.add(compressed);
                crcs.add(crc.getValue());
                if (name.equals("manifest.json")) {
                    manifestSeen = true;
                }
            }
        }
        if (!manifestSeen) {
            throw failure(ErrorCode.INVALID_REQUEST,
                    "Trace archive is missing manifest.json", null);
        }
        return new Structural(TraceManifest.fromJson(archive, manifestJson.toByteArray()),
                names, methods, inflatedSizes, compressedSizes, crcs);
    }

    /** Ordered local entry names, methods, measured byte sizes, and content CRCs. */
    private record Structural(TraceManifest manifest, List<String> names,
            List<Integer> methods, List<Long> inflatedSizes, List<Long> compressedSizes,
            List<Long> crcs) {}

    /** Strictly parses the central directory from the same captured bytes, so it
     *  cannot be a second interpretation of different content. Rejects multi-disk,
     *  ZIP64, encrypted, non-UTF-8-named, and unsupported-method archives. */
    private static CentralDirectory parseCentralDirectory(byte[] archive) throws IOException {
        int end = endOfCentralDirectory(archive);
        int disk = littleEndianShort(archive, end + 4);
        int centralDisk = littleEndianShort(archive, end + 6);
        int diskEntries = littleEndianShort(archive, end + 8);
        int totalEntries = littleEndianShort(archive, end + 10);
        long centralSize = littleEndianInt(archive, end + 12) & 0xffff_ffffL;
        long centralOffset = littleEndianInt(archive, end + 16) & 0xffff_ffffL;
        if (end + 22 + littleEndianShort(archive, end + 20) != archive.length) {
            throw failure(ErrorCode.INVALID_REQUEST,
                    "Trace archive central directory is malformed", null);
        }
        if (disk != 0 || centralDisk != 0 || diskEntries != totalEntries) {
            throw failure(ErrorCode.INVALID_REQUEST,
                    "Trace archive spans multiple disks", null);
        }
        if (diskEntries == ZIP64_MARKER || totalEntries == ZIP64_MARKER
                || centralSize == 0xffff_ffffL || centralOffset == 0xffff_ffffL) {
            throw failure(ErrorCode.INVALID_REQUEST,
                    "Trace archive uses ZIP64 extensions", null);
        }
        // the central directory must end exactly where the end-of-central-directory
        // record starts: no ambiguous gap, comment, or extra record in between
        if (centralOffset + centralSize != end) {
            throw failure(ErrorCode.INVALID_REQUEST,
                    "Trace archive central directory is malformed", null);
        }
        List<String> names = new ArrayList<>();
        List<Integer> methods = new ArrayList<>();
        List<Integer> flagsList = new ArrayList<>();
        List<Long> crcs = new ArrayList<>();
        List<Long> compressedSizes = new ArrayList<>();
        List<Long> uncompressedSizes = new ArrayList<>();
        List<Long> localOffsets = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int cursor = (int) centralOffset;
        int entriesEnd = (int) (centralOffset + centralSize);
        for (int index = 0; index < diskEntries; index++) {
            if (cursor + 46 > entriesEnd
                    || littleEndianInt(archive, cursor) != 0x02014b50) {
                throw failure(ErrorCode.INVALID_REQUEST,
                        "Trace archive central directory is malformed", null);
            }
            int flags = littleEndianShort(archive, cursor + 8);
            int method = littleEndianShort(archive, cursor + 10);
            long crc = littleEndianInt(archive, cursor + 16) & 0xffff_ffffL;
            long compressed = littleEndianInt(archive, cursor + 20) & 0xffff_ffffL;
            long uncompressed = littleEndianInt(archive, cursor + 24) & 0xffff_ffffL;
            int nameLength = littleEndianShort(archive, cursor + 28);
            int extraLength = littleEndianShort(archive, cursor + 30);
            int commentLength = littleEndianShort(archive, cursor + 32);
            int diskStart = littleEndianShort(archive, cursor + 34);
            long localOffset = littleEndianInt(archive, cursor + 42) & 0xffff_ffffL;
            if ((flags & 0x1) != 0) {
                throw failure(ErrorCode.INVALID_REQUEST,
                        "Trace archive entries are encrypted", null);
            }
            if (method != ZipEntry.STORED && method != ZipEntry.DEFLATED) {
                throw failure(ErrorCode.INVALID_REQUEST,
                        "Trace archive uses an unsupported compression method", null);
            }
            if (diskStart != 0) {
                throw failure(ErrorCode.INVALID_REQUEST,
                        "Trace archive spans multiple disks", null);
            }
            if (nameLength == 0 || nameLength > 1_000
                    || compressed == 0xffff_ffffL || uncompressed == 0xffff_ffffL
                    || localOffset == 0xffff_ffffL) {
                throw failure(ErrorCode.INVALID_REQUEST,
                        "Trace archive central directory is malformed", null);
            }
            // the whole variable-length record must fit before any slice or decode
            if (cursor + 46L + nameLength + extraLength + commentLength > entriesEnd) {
                throw failure(ErrorCode.INVALID_REQUEST,
                        "Trace archive central directory is malformed", null);
            }
            String name = decodeUtf8(archive, cursor + 46, nameLength);
            if (isUnsafeName(name) || !seen.add(name)) {
                throw failure(ErrorCode.INVALID_REQUEST,
                        "Trace archive central directory is malformed", null);
            }
            names.add(name);
            methods.add(method);
            flagsList.add(flags);
            crcs.add(crc);
            compressedSizes.add(compressed);
            uncompressedSizes.add(uncompressed);
            localOffsets.add(localOffset);
            cursor += 46 + nameLength + extraLength + commentLength;
        }
        if (cursor != entriesEnd) {
            throw failure(ErrorCode.INVALID_REQUEST,
                    "Trace archive central directory is malformed", null);
        }
        return new CentralDirectory(names, methods, flagsList, crcs, compressedSizes,
                uncompressedSizes, localOffsets, centralOffset);
    }

    /** Ordered central entry names, methods, flags, CRCs, claimed byte sizes, the
     *  local header offset each central entry points at, and the offset where the
     *  central directory itself begins. */
    private record CentralDirectory(List<String> names, List<Integer> methods,
            List<Integer> flags, List<Long> crcs, List<Long> compressedSizes,
            List<Long> uncompressedSizes, List<Long> localOffsets, long centralOffset) {}

    /** Requires the central directory to name exactly the local entries in the same
     *  order with the same methods, flags, and sizes, and to point each entry at the
     *  local header that actually carries that name and method (offsets unique), so
     *  central-only extras, local-only extras, offset swaps, and undeclared or
     *  missing entries are all rejected; for v2 that exact set must also be the
     *  manifest allowlist. */
    private void requireMatchingCentral(byte[] archive, Structural structural,
            CentralDirectory central) throws IOException {
        if (!structural.names().equals(central.names())) {
            throw failure(ErrorCode.INVALID_REQUEST,
                    "Trace archive entry set does not match its central directory", null);
        }
        for (int index = 0; index < structural.names().size(); index++) {
            long measuredInflated = structural.inflatedSizes().get(index);
            long measuredCompressed = structural.compressedSizes().get(index);
            long claimedCompressed = central.compressedSizes().get(index);
            long claimedUncompressed = central.uncompressedSizes().get(index);
            if (structural.methods().get(index) != central.methods().get(index)) {
                throw failure(ErrorCode.INVALID_REQUEST,
                        "Trace archive central directory method does not match its local entry",
                        null);
            }
            if (structural.crcs().get(index).longValue() != central.crcs().get(index).longValue()) {
                throw failure(ErrorCode.INVALID_REQUEST,
                        "Trace archive central directory CRC does not match its entries", null);
            }
            if (structural.methods().get(index) == ZipEntry.STORED) {
                if (claimedCompressed != measuredInflated
                        || claimedUncompressed != measuredInflated) {
                    throw failure(ErrorCode.INVALID_REQUEST,
                            "Trace archive central directory sizes do not match its entries",
                            null);
                }
            } else if (claimedCompressed != measuredCompressed
                    || claimedUncompressed != measuredInflated) {
                throw failure(ErrorCode.INVALID_REQUEST,
                        "Trace archive central directory sizes do not match its entries",
                        null);
            }
        }
        requireSequentialLocalHeaders(archive, central);
        if (TraceManifest.V2.equals(structural.manifest().schemaVersion())) {
            Set<String> allowlist = v2Allowlist(structural.manifest());
            Set<String> names = new HashSet<>(structural.names());
            for (String declared : allowlist) {
                if (!names.contains(declared)) {
                    throw failure(ErrorCode.INVALID_REQUEST,
                            declared.startsWith("artifacts/")
                                    ? "Trace artifact " + declared.substring("artifacts/".length())
                                    + " is missing from the archive"
                                    : "Trace archive entry " + declared
                                    + " is missing from the archive",
                            null);
                }
            }
            for (String name : names) {
                if (!allowlist.contains(name)) {
                    throw failure(ErrorCode.INVALID_REQUEST,
                            "Trace archive contains an undeclared entry", null);
                }
            }
        }
    }

    /** Strictly walks the sequential local headers from offset zero to the central
     *  directory, requiring each header to sit exactly at the offset the matching
     *  central record claims and to carry the same name, method, and flags, and
     *  binding the entry bytes through the fixed local sizes or the data descriptor
     *  to the same CRC and byte sizes the central record claims. Because the walk
     *  advances by the real local structure (including extra fields and data
     *  descriptors), a fake local header hidden in an extra field or payload can
     *  never satisfy the next central offset, and offset swaps between same-sized
     *  entries are rejected. */
    private static void requireSequentialLocalHeaders(byte[] archive, CentralDirectory central)
            throws IOException {
        long cursor = 0;
        for (int index = 0; index < central.names().size(); index++) {
            if (cursor != central.localOffsets().get(index) || cursor + 30 > archive.length
                    || littleEndianInt(archive, (int) cursor) != 0x04034b50) {
                throw failure(ErrorCode.INVALID_REQUEST,
                        "Trace archive local headers do not match its central directory", null);
            }
            int flags = littleEndianShort(archive, (int) cursor + 6);
            int method = littleEndianShort(archive, (int) cursor + 8);
            long localCrc = littleEndianInt(archive, (int) cursor + 14) & 0xffff_ffffL;
            long localCompressed = littleEndianInt(archive, (int) cursor + 18) & 0xffff_ffffL;
            long localUncompressed = littleEndianInt(archive, (int) cursor + 22) & 0xffff_ffffL;
            int nameLength = littleEndianShort(archive, (int) cursor + 26);
            int extraLength = littleEndianShort(archive, (int) cursor + 28);
            if (nameLength == 0 || nameLength > 1_000
                    || method != central.methods().get(index)
                    || flags != central.flags().get(index)) {
                throw failure(ErrorCode.INVALID_REQUEST,
                        "Trace archive local headers do not match its central directory", null);
            }
            long headerEnd = cursor + 30L + nameLength + extraLength;
            if (headerEnd > archive.length) {
                throw failure(ErrorCode.INVALID_REQUEST,
                        "Trace archive local headers do not match its central directory", null);
            }
            String name = decodeUtf8(archive, (int) cursor + 30, nameLength);
            if (!name.equals(central.names().get(index))) {
                throw failure(ErrorCode.INVALID_REQUEST,
                        "Trace archive local headers do not match its central directory", null);
            }
            long dataEnd = headerEnd + central.compressedSizes().get(index);
            if (dataEnd > central.centralOffset()) {
                throw failure(ErrorCode.INVALID_REQUEST,
                        "Trace archive local headers do not match its central directory", null);
            }
            if ((flags & 0x8) != 0) {
                // data descriptor: optional signature then crc, compressed, uncompressed
                long descriptorCrc;
                int descriptorLength;
                if (dataEnd + 4 <= archive.length
                        && littleEndianInt(archive, (int) dataEnd) == 0x08074b50) {
                    descriptorLength = 16;
                    descriptorCrc = littleEndianInt(archive, (int) dataEnd + 4) & 0xffff_ffffL;
                    if (dataEnd + 16 > archive.length
                            || (littleEndianInt(archive, (int) dataEnd + 8) & 0xffff_ffffL)
                            != central.compressedSizes().get(index)
                            || (littleEndianInt(archive, (int) dataEnd + 12) & 0xffff_ffffL)
                            != central.uncompressedSizes().get(index)) {
                        throw failure(ErrorCode.INVALID_REQUEST,
                                "Trace archive data descriptors do not match its central directory",
                                null);
                    }
                } else {
                    descriptorLength = 12;
                    descriptorCrc = littleEndianInt(archive, (int) dataEnd) & 0xffff_ffffL;
                    if (dataEnd + 12 > archive.length
                            || (littleEndianInt(archive, (int) dataEnd + 4) & 0xffff_ffffL)
                            != central.compressedSizes().get(index)
                            || (littleEndianInt(archive, (int) dataEnd + 8) & 0xffff_ffffL)
                            != central.uncompressedSizes().get(index)) {
                        throw failure(ErrorCode.INVALID_REQUEST,
                                "Trace archive data descriptors do not match its central directory",
                                null);
                    }
                }
                if (descriptorCrc != central.crcs().get(index)) {
                    throw failure(ErrorCode.INVALID_REQUEST,
                            "Trace archive data descriptors do not match its central directory",
                            null);
                }
                cursor = dataEnd + descriptorLength;
            } else {
                if (localCrc != central.crcs().get(index)
                        || localCompressed != central.compressedSizes().get(index)
                        || localUncompressed != central.uncompressedSizes().get(index)) {
                    throw failure(ErrorCode.INVALID_REQUEST,
                            "Trace archive local headers do not match its central directory", null);
                }
                cursor = dataEnd;
            }
        }
        if (cursor != central.centralOffset()) {
            throw failure(ErrorCode.INVALID_REQUEST,
                    "Trace archive local headers do not match its central directory", null);
        }
    }

    /** The exact v2 entry set: manifest.json, events.ndjson, and every declared
     *  artifact entry. */
    private static Set<String> v2Allowlist(TraceManifest manifest) {
        Set<String> allowlist = new HashSet<>();
        allowlist.add("manifest.json");
        allowlist.add("events.ndjson");
        for (Map.Entry<String, TraceManifest.ArtifactBinding> binding
                : manifest.artifacts().entrySet()) {
            if (!binding.getKey().equals(binding.getValue().sha256())) {
                throw failure(ErrorCode.INVALID_REQUEST,
                        "Trace artifact identity is not its digest", null);
            }
            allowlist.add("artifacts/" + binding.getKey());
        }
        return allowlist;
    }

    /** Streams the events entry in one pass (each line parsed and causally
     *  validated, the raw entry bytes digested), and for v2 also streams every
     *  artifact entry against its manifest binding. v1 keeps the legacy partial
     *  diagnostics and reports {@link TraceReplay.Integrity#UNVERIFIED}. */
    private TraceReplay readEvents(byte[] bytes, TraceManifest manifest, ReplayBudget budget,
            String archiveSha256, TraceReplay.Integrity integrity) throws IOException {
        boolean verifiedFormat = TraceReplay.Integrity.VERIFIED.equals(integrity);
        Set<String> seenArtifacts = new HashSet<>();
        boolean eventsSeen = false;
        List<Long> revisions = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        Map<String, RequestState> activeRequests = new HashMap<>();
        long expectedSequence = 0;
        long lastLogicalTime = -1;
        long lastFrame = -1;
        long lastRevision = -1;
        boolean malformed = false;
        MessageDigest eventsDigest = sha256();
        try (MeasuringZipInputStream zip = new MeasuringZipInputStream(
                new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.equals("manifest.json")) {
                    continue;
                }
                if (name.equals("events.ndjson")) {
                    eventsSeen = true;
                    while (true) {
                        byte[] line;
                        try {
                            line = readBoundedLine(zip, limits.maxEventBytes(), budget,
                                    eventsDigest);
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
                        validateEvent(event, manifest, expectedSequence, lastLogicalTime,
                                lastFrame, lastRevision, activeRequests, errors);
                        if (event.revision() != null
                                && (revisions.isEmpty()
                                || revisions.get(revisions.size() - 1).longValue()
                                != event.revision())) {
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
                } else if (name.startsWith("artifacts/")) {
                    if (!verifiedFormat) {
                        continue;
                    }
                    String id = name.substring("artifacts/".length());
                    TraceManifest.ArtifactBinding binding = manifest.artifacts().get(id);
                    if (binding == null || !seenArtifacts.add(id)) {
                        throw failure(ErrorCode.INVALID_REQUEST,
                                "Trace archive contains an undeclared artifact entry", null);
                    }
                    EntryDigest actual = digestEntry(zip, budget);
                    if (!actual.sha256().equals(binding.sha256())) {
                        throw failure(ErrorCode.INVALID_REQUEST,
                                "Trace artifact digest does not match the manifest", null);
                    }
                    if (actual.size() != binding.size()) {
                        throw failure(ErrorCode.INVALID_REQUEST,
                                "Trace artifact size does not match the manifest", null);
                    }
                } else if (verifiedFormat) {
                    throw failure(ErrorCode.INVALID_REQUEST,
                            "Trace archive contains an undeclared entry", null);
                }
            }
        }
        if (!eventsSeen) {
            throw failure(ErrorCode.INVALID_REQUEST,
                    "Trace archive is missing events.ndjson", null);
        }
        if (verifiedFormat) {
            String actual = HexFormat.of().formatHex(eventsDigest.digest());
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
        if (manifest.complete() && !activeRequests.isEmpty()) {
            errors.add("complete trace has unfinished requests: " + activeRequests.keySet());
        }
        boolean partial = !manifest.complete() || malformed
                || (!verifiedFormat && expectedSequence != manifest.eventCount());
        return new TraceReplay(manifest, revisions, new TraceReplay.Causality(errors), partial,
                diagnostics, archiveSha256, integrity);
    }

    /** Digest and observed byte count of one streamed artifact entry. */
    private record EntryDigest(String sha256, long size) {}

    private static EntryDigest digestEntry(MeasuringZipInputStream zip, ReplayBudget budget)
            throws IOException {
        MessageDigest digest = sha256();
        byte[] buffer = new byte[16 * 1024];
        long total = 0;
        int read;
        while ((read = zip.read(buffer)) != -1) {
            budget.charge(read);
            budget.recordContent(read);
            digest.update(buffer, 0, read);
            total += read;
        }
        return new EntryDigest(HexFormat.of().formatHex(digest.digest()), total);
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

    private static int endOfCentralDirectory(byte[] archive) throws IOException {
        int limit = Math.max(0, archive.length - 65_557);
        for (int index = archive.length - 22; index >= limit; index--) {
            if (littleEndianInt(archive, index) == 0x06054b50
                    && index + 22 + littleEndianShort(archive, index + 20) == archive.length) {
                return index;
            }
        }
        throw failure(ErrorCode.INVALID_REQUEST,
                "Trace archive has no end-of-central-directory record", null);
    }

    private static String decodeUtf8(byte[] bytes, int offset, int length) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, length)).toString();
        } catch (CharacterCodingException exception) {
            throw failure(ErrorCode.INVALID_REQUEST,
                    "Trace archive entry name is not valid UTF-8", null);
        }
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

    /** Cumulative inflated-byte accounting for one archive load, shared across the
     *  manifest, artifact bindings, and events. */
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

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    /** ZipInputStream that reports the compressed bytes actually consumed per
     *  entry from the DEFLATE stream, immune to forgeable size metadata. */
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
