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
        Fixture fixture = fixture("harness", "sign-in", zip("trace"), null);

        assertDoesNotThrow(() -> BenchmarkArtifactValidator.validate(
                temporary, List.of(fixture.record()),
                List.of(scenario("sign-in", false))));
    }

    @Test void rejectsDeletedClaimedTrace() throws Exception {
        Fixture fixture = fixture("harness", "sign-in", zip("trace"), null);
        Files.delete(fixture.trace());

        assertThrows(IllegalStateException.class, () -> BenchmarkArtifactValidator.validate(
                temporary, List.of(fixture.record()),
                List.of(scenario("sign-in", false))));
    }

    @Test void rejectsZeroedPlaywrightTraceClaimAndDeletedArtifact() throws Exception {
        Fixture fixture = fixture("playwright", "sign-in", zip("trace"), null);
        Files.delete(fixture.trace());
        BenchmarkRunner.RunRecord zeroed = claims(fixture.record(), 0, 0, false);

        assertThrows(IllegalStateException.class, () -> BenchmarkArtifactValidator.validate(
                temporary, List.of(zeroed),
                List.of(scenario("sign-in", false))));
    }

    @Test void rejectsTruncatedOrWronglySignedTrace() throws Exception {
        Fixture fixture = fixture("harness", "sign-in", zip("trace"), null);
        Files.write(fixture.trace(), new byte[] {'P', 'K'});

        assertThrows(IllegalStateException.class, () -> BenchmarkArtifactValidator.validate(
                temporary, List.of(fixture.record()),
                List.of(scenario("sign-in", false))));
    }

    @Test void rejectsArtifactMovedFromAnotherRunIdentity() throws Exception {
        Fixture first = fixture("harness", "sign-in", zip("one"), null);
        Fixture second = fixture(
                "harness", "modal-dialog", zip("a longer second trace"), null);
        byte[] firstBytes = Files.readAllBytes(first.trace());
        byte[] secondBytes = Files.readAllBytes(second.trace());
        Files.write(first.trace(), secondBytes);
        Files.write(second.trace(), firstBytes);

        assertThrows(IllegalStateException.class, () -> BenchmarkArtifactValidator.validate(
                temporary, List.of(first.record(), second.record()),
                List.of(scenario("sign-in", false),
                        scenario("modal-dialog", false))));
    }

    @Test void rejectsMissingRequiredScreenshotAndUnclaimedExtras() throws Exception {
        Fixture fixture = fixture(
                "harness", "screenshot-diagnosis", zip("trace"), PNG);
        Files.delete(fixture.screenshot());
        assertThrows(IllegalStateException.class, () -> BenchmarkArtifactValidator.validate(
                temporary, List.of(fixture.record()),
                List.of(scenario("screenshot-diagnosis", true))));

        Files.write(fixture.screenshot(), PNG);
        Files.write(temporary.resolve("traces/harness/unclaimed-01.zip"), zip("extra"));
        assertThrows(IllegalStateException.class, () -> BenchmarkArtifactValidator.validate(
                temporary, List.of(fixture.record()),
                List.of(scenario("screenshot-diagnosis", true))));
    }

    @Test void rejectsZeroedMandatoryPlaywrightScreenshotClaimAndDeletedPng()
            throws Exception {
        Fixture fixture = fixture(
                "playwright", "screenshot-diagnosis", zip("trace"), PNG);
        Files.delete(fixture.screenshot());
        BenchmarkRunner.RunRecord zeroed = claims(
                fixture.record(), fixture.record().traceBytes(), 0, false);

        assertThrows(IllegalStateException.class, () -> BenchmarkArtifactValidator.validate(
                temporary, List.of(zeroed),
                List.of(scenario("screenshot-diagnosis", true))));
    }

    private Fixture fixture(
            String system, String scenario, byte[] trace, byte[] screenshot) throws Exception {
        Path tracePath = temporary.resolve(
                "traces/" + system + '/' + scenario + "-01.zip");
        Files.createDirectories(tracePath.getParent());
        Files.write(tracePath, trace);
        Path screenshotPath = temporary.resolve(
                "evidence/" + system + '/' + scenario + "-01.png");
        Files.createDirectories(screenshotPath.getParent());
        if (screenshot != null) {
            Files.write(screenshotPath, screenshot);
        }
        BenchmarkRunner.RunRecord record = new BenchmarkRunner.RunRecord(
                1, system, scenario, 1, true, false, false, false,
                3, true, 1.0, trace.length, screenshot == null ? 0 : screenshot.length,
                "repeatable", List.of(), null);
        return new Fixture(record, tracePath, screenshotPath);
    }

    private static BenchmarkRunner.RunRecord claims(
            BenchmarkRunner.RunRecord source, long traceBytes, long screenshotBytes,
            boolean actionableEvidence) {
        return new BenchmarkRunner.RunRecord(
                source.schemaVersion(), source.system(), source.scenarioId(), source.run(),
                source.completed(), source.timeout(), source.flakyFailure(),
                source.timeoutOrFlaky(), source.toolCalls(), actionableEvidence,
                source.durationMillis(), traceBytes, screenshotBytes,
                source.repeatabilityKey(), source.diagnostics(), source.error());
    }

    private static BenchmarkScenario scenario(String id, boolean screenshot) {
        BenchmarkScenario.Step step = screenshot
                ? new BenchmarkScenario.Step("screenshot", null, null, null, null)
                : new BenchmarkScenario.Step(
                        "wait-visible",
                        new BenchmarkScenario.Locator("test-id", "target", null, true),
                        null, null, null);
        return new BenchmarkScenario(id, id, 16, List.of(step), "text:expected");
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
