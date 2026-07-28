package dev.gdx.uiharness.benchmarks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipInputStream;

/** Fail-closed correlation of raw artifact claims to preserved trace and screenshot files. */
final class BenchmarkArtifactValidator {
    private static final byte[] PNG_SIGNATURE = new byte[] {
        (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'
    };

    private BenchmarkArtifactValidator() {}

    static void validate(Path output, List<BenchmarkRunner.RunRecord> records)
            throws IOException {
        Set<Path> claimed = new HashSet<>();
        Set<String> systems = new HashSet<>();
        for (BenchmarkRunner.RunRecord record : records) {
            systems.add(record.system());
            String stem = record.scenarioId() + '-'
                    + String.format(Locale.ROOT, "%02d", record.run());
            Path trace = output.resolve("traces").resolve(record.system())
                    .resolve(stem + ".zip").toAbsolutePath().normalize();
            validateTraceClaim(record, trace, claimed);

            Path evidence = output.resolve("evidence").resolve(record.system());
            Path normal = evidence.resolve(stem + ".png").toAbsolutePath().normalize();
            Path failure = evidence.resolve(stem + "-failure.png")
                    .toAbsolutePath().normalize();
            validateScreenshotClaims(record, List.of(normal, failure), claimed);
        }
        for (String system : systems) {
            rejectUnclaimed(output.resolve("traces").resolve(system), claimed);
            rejectUnclaimed(output.resolve("evidence").resolve(system), claimed);
        }
    }

    private static void validateTraceClaim(
            BenchmarkRunner.RunRecord record, Path trace, Set<Path> claimed)
            throws IOException {
        if (record.traceBytes() == 0) {
            if (Files.exists(trace)) {
                throw new IllegalStateException("Unclaimed trace exists: " + trace);
            }
            if (record.actionableEvidence()) {
                throw new IllegalStateException(
                        "Actionable record omitted its trace: " + identity(record));
            }
            return;
        }
        requireRegular(trace, "claimed trace");
        requireUnique(trace, claimed);
        if (Files.size(trace) != record.traceBytes()) {
            throw new IllegalStateException("Trace byte claim mismatch: " + trace);
        }
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(trace))) {
            if (zip.getNextEntry() == null) {
                throw new IllegalStateException("Claimed trace is not a readable ZIP: " + trace);
            }
        }
    }

    private static void validateScreenshotClaims(
            BenchmarkRunner.RunRecord record, List<Path> candidates, Set<Path> claimed)
            throws IOException {
        long bytes = 0;
        int count = 0;
        for (Path candidate : candidates) {
            if (!Files.exists(candidate)) {
                continue;
            }
            requireRegular(candidate, "claimed screenshot");
            requireUnique(candidate, claimed);
            byte[] signature;
            try (var input = Files.newInputStream(candidate)) {
                signature = input.readNBytes(PNG_SIGNATURE.length);
            }
            if (!java.util.Arrays.equals(PNG_SIGNATURE, signature)) {
                throw new IllegalStateException(
                        "Claimed screenshot has wrong PNG signature: " + candidate);
            }
            bytes = Math.addExact(bytes, Files.size(candidate));
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

    private static void rejectUnclaimed(Path directory, Set<Path> claimed) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            List<Path> extras = paths.filter(Files::isRegularFile)
                    .map(path -> path.toAbsolutePath().normalize())
                    .filter(path -> !claimed.contains(path))
                    .toList();
            if (!extras.isEmpty()) {
                throw new IllegalStateException("Unclaimed benchmark artifacts: " + extras);
            }
        }
    }

    private static void requireRegular(Path path, String kind) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("Missing " + kind + ": " + path);
        }
    }

    private static void requireUnique(Path path, Set<Path> claimed) {
        if (!claimed.add(path)) {
            throw new IllegalStateException("Artifact claimed by duplicate identity: " + path);
        }
    }

    private static String identity(BenchmarkRunner.RunRecord record) {
        return record.system() + '/' + record.scenarioId() + '/' + record.run();
    }
}
