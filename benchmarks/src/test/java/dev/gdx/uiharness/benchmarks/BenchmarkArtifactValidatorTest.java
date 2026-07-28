package dev.gdx.uiharness.benchmarks;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BenchmarkArtifactValidatorTest {
    private static final byte[] PNG = new byte[] {
        (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 1, 2, 3
    };

    @TempDir Path temporary;

    @Test void acceptsCorrectlyNamedSignedArtifactsWithExactClaimedSizes() throws Exception {
        Fixture fixture = fixture("sign-in", zip("trace"), null);

        assertDoesNotThrow(() -> BenchmarkArtifactValidator.validate(
                temporary, List.of(fixture.record())));
    }

    @Test void rejectsDeletedClaimedTrace() throws Exception {
        Fixture fixture = fixture("sign-in", zip("trace"), null);
        Files.delete(fixture.trace());

        assertThrows(IllegalStateException.class, () -> BenchmarkArtifactValidator.validate(
                temporary, List.of(fixture.record())));
    }

    @Test void rejectsTruncatedOrWronglySignedTrace() throws Exception {
        Fixture fixture = fixture("sign-in", zip("trace"), null);
        Files.write(fixture.trace(), new byte[] {'P', 'K'});

        assertThrows(IllegalStateException.class, () -> BenchmarkArtifactValidator.validate(
                temporary, List.of(fixture.record())));
    }

    @Test void rejectsArtifactMovedFromAnotherRunIdentity() throws Exception {
        Fixture first = fixture("sign-in", zip("one"), null);
        Fixture second = fixture("modal-dialog", zip("a longer second trace"), null);
        byte[] firstBytes = Files.readAllBytes(first.trace());
        byte[] secondBytes = Files.readAllBytes(second.trace());
        Files.write(first.trace(), secondBytes);
        Files.write(second.trace(), firstBytes);

        assertThrows(IllegalStateException.class, () -> BenchmarkArtifactValidator.validate(
                temporary, List.of(first.record(), second.record())));
    }

    @Test void rejectsMissingRequiredScreenshotAndUnclaimedExtras() throws Exception {
        Fixture fixture = fixture("screenshot-diagnosis", zip("trace"), PNG);
        Files.delete(fixture.screenshot());
        assertThrows(IllegalStateException.class, () -> BenchmarkArtifactValidator.validate(
                temporary, List.of(fixture.record())));

        Files.write(fixture.screenshot(), PNG);
        Files.write(temporary.resolve("traces/harness/unclaimed-01.zip"), zip("extra"));
        assertThrows(IllegalStateException.class, () -> BenchmarkArtifactValidator.validate(
                temporary, List.of(fixture.record())));
    }

    private Fixture fixture(String scenario, byte[] trace, byte[] screenshot) throws Exception {
        Path tracePath = temporary.resolve("traces/harness/" + scenario + "-01.zip");
        Files.createDirectories(tracePath.getParent());
        Files.write(tracePath, trace);
        Path screenshotPath = temporary.resolve("evidence/harness/" + scenario + "-01.png");
        Files.createDirectories(screenshotPath.getParent());
        if (screenshot != null) {
            Files.write(screenshotPath, screenshot);
        }
        BenchmarkRunner.RunRecord record = new BenchmarkRunner.RunRecord(
                1, "harness", scenario, 1, true, false, false, false,
                3, true, 1.0, trace.length, screenshot == null ? 0 : screenshot.length,
                "repeatable", List.of(), null);
        return new Fixture(record, tracePath, screenshotPath);
    }

    private static byte[] zip(String content) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("trace.txt"));
            zip.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private record Fixture(
            BenchmarkRunner.RunRecord record, Path trace, Path screenshot) {}
}
