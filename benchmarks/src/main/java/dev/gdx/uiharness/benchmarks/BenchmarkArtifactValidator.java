package dev.gdx.uiharness.benchmarks;

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipInputStream;

/** Fail-closed correlation of raw artifact claims to preserved trace and screenshot files. */
final class BenchmarkArtifactValidator {
    private static final byte[] PNG_SIGNATURE = new byte[] {
        (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'
    };

    private BenchmarkArtifactValidator() {}

    static void validate(
            Path output,
            List<BenchmarkRunner.RunRecord> records,
            List<BenchmarkScenario> scenarios) throws IOException {
        Path outputRoot = output.toAbsolutePath().normalize();
        Map<String, BenchmarkScenario> scenariosById = scenarios.stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        BenchmarkScenario::id, scenario -> scenario));
        ClaimedFiles claimed = new ClaimedFiles();
        Set<String> systems = new HashSet<>();
        for (BenchmarkRunner.RunRecord record : records) {
            systems.add(record.system());
            BenchmarkScenario scenario = scenariosById.get(record.scenarioId());
            if (scenario == null) {
                throw new IllegalStateException(
                        "Raw artifact identity is absent from corpus: " + identity(record));
            }
            String stem = record.scenarioId() + '-'
                    + String.format(Locale.ROOT, "%02d", record.run());
            Path traceRoot = outputRoot.resolve("traces").resolve(record.system());
            Path trace = traceRoot.resolve(stem + ".zip").normalize();
            validateTraceClaim(outputRoot, traceRoot, record, trace, claimed);

            Path evidenceRoot = outputRoot.resolve("evidence").resolve(record.system());
            Path normal = evidenceRoot.resolve(stem + ".png").normalize();
            Path failure = evidenceRoot.resolve(stem + "-failure.png").normalize();
            boolean screenshotRequired = scenario.steps().stream()
                    .anyMatch(step -> "screenshot".equals(step.action()));
            validateScreenshotClaims(
                    outputRoot, evidenceRoot, record, normal,
                    List.of(normal, failure), claimed, screenshotRequired);
        }
        for (String system : systems) {
            rejectUnclaimed(
                    outputRoot, outputRoot.resolve("traces").resolve(system), claimed);
            rejectUnclaimed(
                    outputRoot, outputRoot.resolve("evidence").resolve(system), claimed);
        }
    }

    private static void validateTraceClaim(
            Path outputRoot,
            Path traceRoot,
            BenchmarkRunner.RunRecord record,
            Path trace,
            ClaimedFiles claimed) throws IOException {
        if (record.traceBytes() <= 0) {
            throw new IllegalStateException(
                    "Every observation requires a positive trace claim: " + identity(record));
        }
        requireOwnedRegular(outputRoot, traceRoot, trace, "claimed trace", claimed);
        try (SeekableByteChannel channel = openNoFollow(trace)) {
            if (channel.size() != record.traceBytes()) {
                throw new IllegalStateException("Trace byte claim mismatch: " + trace);
            }
            try (ZipInputStream zip = new ZipInputStream(Channels.newInputStream(channel))) {
                if (zip.getNextEntry() == null) {
                    throw new IllegalStateException(
                            "Claimed trace is not a readable ZIP: " + trace);
                }
            }
        }
    }

    private static void validateScreenshotClaims(
            Path outputRoot,
            Path evidenceRoot,
            BenchmarkRunner.RunRecord record,
            Path normal,
            List<Path> candidates,
            ClaimedFiles claimed,
            boolean screenshotRequired) throws IOException {
        if (screenshotRequired) {
            if (record.screenshotBytes() <= 0) {
                throw new IllegalStateException(
                        "Corpus requires a positive screenshot claim: " + identity(record));
            }
            if (!Files.exists(normal, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException(
                        "Missing corpus-required screenshot: " + normal);
            }
        }
        long bytes = 0;
        int count = 0;
        for (Path candidate : candidates) {
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            requireOwnedRegular(
                    outputRoot, evidenceRoot, candidate, "claimed screenshot", claimed);
            try (SeekableByteChannel channel = openNoFollow(candidate)) {
                long size = channel.size();
                byte[] signature = Channels.newInputStream(channel)
                        .readNBytes(PNG_SIGNATURE.length);
                if (!java.util.Arrays.equals(PNG_SIGNATURE, signature)) {
                    throw new IllegalStateException(
                            "Claimed screenshot has wrong PNG signature: " + candidate);
                }
                bytes = Math.addExact(bytes, size);
            }
            count++;
        }
        if (bytes != record.screenshotBytes()) {
            throw new IllegalStateException(
                    "Screenshot byte claim mismatch for " + identity(record));
        }
        if (record.screenshotBytes() > 0 && count == 0) {
            throw new IllegalStateException(
                    "Claimed screenshot is missing for " + identity(record));
        }
    }

    private static void requireOwnedRegular(
            Path outputRoot,
            Path exactRoot,
            Path candidate,
            String kind,
            ClaimedFiles claimed) throws IOException {
        Path root = exactRoot.toAbsolutePath().normalize();
        Path file = candidate.toAbsolutePath().normalize();
        if (!root.startsWith(outputRoot) || !file.startsWith(root)) {
            throw new IllegalStateException(
                    "Artifact escaped its exact output root: " + file);
        }
        requireNoSymlinkComponents(outputRoot, file);
        Path realOutput = outputRoot.toRealPath(LinkOption.NOFOLLOW_LINKS);
        Path realRoot = root.toRealPath(LinkOption.NOFOLLOW_LINKS);
        Path realFile = file.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!realRoot.startsWith(realOutput) || !realFile.startsWith(realRoot)) {
            throw new IllegalStateException(
                    "Artifact real path escaped its exact output root: " + file);
        }
        BasicFileAttributes attributes = Files.readAttributes(
                file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
            throw new IllegalStateException("Missing real " + kind + ": " + file);
        }
        claimed.add(file, attributes);
    }

    private static void requireNoSymlinkComponents(Path outputRoot, Path candidate)
            throws IOException {
        Path output = outputRoot.toAbsolutePath().normalize();
        Path file = candidate.toAbsolutePath().normalize();
        if (!file.startsWith(output)) {
            throw new IllegalStateException("Artifact is outside output root: " + file);
        }
        Path current = output;
        requireRealPathComponent(current, !current.equals(file));
        for (Path component : output.relativize(file)) {
            current = current.resolve(component);
            requireRealPathComponent(current, !current.equals(file));
        }
    }

    private static void requireRealPathComponent(Path path, boolean directory)
            throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("Missing owned artifact path component: " + path);
        }
        BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink()) {
            throw new IllegalStateException(
                    "Symbolic links are forbidden in benchmark artifacts: " + path);
        }
        if (directory && !attributes.isDirectory()) {
            throw new IllegalStateException(
                    "Owned artifact path component is not a directory: " + path);
        }
    }

    private static SeekableByteChannel openNoFollow(Path path) throws IOException {
        Set<OpenOption> options = Set.of(
                StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        return Files.newByteChannel(path, options);
    }

    private static void rejectUnclaimed(
            Path outputRoot, Path directory, ClaimedFiles claimed) throws IOException {
        Path root = directory.toAbsolutePath().normalize();
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        requireNoSymlinkComponents(outputRoot, root);
        ArrayList<Path> extras = new ArrayList<>();
        try (var paths = Files.walk(root)) {
            var iterator = paths.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                BasicFileAttributes attributes = Files.readAttributes(
                        path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink()) {
                    throw new IllegalStateException(
                            "Symbolic links are forbidden in benchmark artifacts: " + path);
                }
                if (attributes.isRegularFile()) {
                    Path normalized = path.toAbsolutePath().normalize();
                    if (!claimed.contains(normalized)) {
                        extras.add(normalized);
                    }
                } else if (!attributes.isDirectory()) {
                    throw new IllegalStateException(
                            "Unsupported benchmark artifact file type: " + path);
                }
            }
        }
        if (!extras.isEmpty()) {
            throw new IllegalStateException("Unclaimed benchmark artifacts: " + extras);
        }
    }

    private static final class ClaimedFiles {
        private final Set<Path> paths = new HashSet<>();
        private final Map<Object, Path> fileKeys = new HashMap<>();
        private final List<Path> files = new ArrayList<>();

        private void add(Path path, BasicFileAttributes attributes) throws IOException {
            if (!paths.add(path)) {
                throw new IllegalStateException(
                        "Artifact claimed by duplicate identity: " + path);
            }
            Object fileKey = attributes.fileKey();
            if (fileKey != null) {
                Path previous = fileKeys.putIfAbsent(fileKey, path);
                if (previous != null) {
                    throw new IllegalStateException(
                            "Artifact identities alias one underlying file: "
                                    + previous + " and " + path);
                }
            }
            for (Path previous : files) {
                if (Files.isSameFile(previous, path)) {
                    throw new IllegalStateException(
                            "Artifact identities alias one underlying file: "
                                    + previous + " and " + path);
                }
            }
            files.add(path);
        }

        private boolean contains(Path path) {
            return paths.contains(path);
        }
    }

    private static String identity(BenchmarkRunner.RunRecord record) {
        return record.system() + '/' + record.scenarioId() + '/' + record.run();
    }
}
