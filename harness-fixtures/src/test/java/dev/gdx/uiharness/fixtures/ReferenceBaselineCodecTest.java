package dev.gdx.uiharness.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gdx.uiharness.core.golden.BaselineDigest;
import dev.gdx.uiharness.core.golden.BaselineNode;
import dev.gdx.uiharness.core.golden.SemanticBaseline;
import dev.gdx.uiharness.protocol.ProtocolJson;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Adversarial coverage of the strict bounded codec contract: the committed resource must
 * decode losslessly, and tampering with the digest, key uniqueness, unknown fields, or the
 * byte ceiling must fail closed instead of silently producing a baseline.
 */
final class ReferenceBaselineCodecTest {
    private static final String ZERO_DIGEST = "0".repeat(BaselineDigest.HEX_LENGTH);

    @TempDir
    Path tempDir;

    @Test
    @Timeout(30)
    void committedResourceDecodesWithAVerifiedCanonicalDigest() {
        Path resource = Path.of("src/main/resources/reference-ui/reference-baseline.json");
        SemanticBaseline baseline = ReferenceBaselineCodec.read(resource);
        assertEquals("reference-screen", baseline.id());
        assertEquals(1, baseline.majorVersion());
        assertEquals(0, baseline.minorVersion());
        assertEquals(false, baseline.strictNodes());
        assertEquals(BaselineDigest.canonical(baseline), baseline.digest());
    }

    @Test
    @Timeout(30)
    void writeThenReadRoundTripsAnUnregisteredBaseline() throws Exception {
        BaselineNode root = new BaselineNode(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, Map.of(), List.of());
        SemanticBaseline baseline = SemanticBaseline.registered(1, 0, "codec-test", root, false);
        Path file = tempDir.resolve("baseline.json");
        ReferenceBaselineCodec.write(file, baseline);
        assertEquals(baseline, ReferenceBaselineCodec.read(file));
        assertEquals(baseline, ReferenceBaselineCodec.read(Files.newInputStream(file)));
    }

    @Test
    @Timeout(30)
    void rejectsAResourceWhoseClaimedDigestMismatchesTheRecomputedCanonicalDigest()
            throws Exception {
        Path tampered = tempDir.resolve("tampered-digest.json");
        Files.writeString(tampered, resourceText().replaceFirst(
                "\"digest\" : \"[0-9a-f]{64}\"", "\"digest\" : \"" + ZERO_DIGEST + "\""));
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> ReferenceBaselineCodec.read(tampered));
        assertTrue(failure.getMessage().contains("digest mismatch"),
                "expected a digest mismatch rejection, got: " + failure.getMessage());
    }

    @Test
    @Timeout(30)
    void rejectsDuplicateObjectKeys() throws Exception {
        Path duplicate = tempDir.resolve("duplicate-keys.json");
        Files.writeString(duplicate, resourceText().replaceFirst(
                "\"id\" : \"reference-screen\"",
                "\"id\" : \"reference-screen\",\n  \"id\" : \"reference-screen\""));
        assertThrows(IllegalArgumentException.class,
                () -> ReferenceBaselineCodec.read(duplicate));
    }

    @Test
    @Timeout(30)
    void rejectsUnknownFields() throws Exception {
        Path unknown = tempDir.resolve("unknown-field.json");
        Files.writeString(unknown, resourceText().replaceFirst(
                "\"id\" : \"reference-screen\"",
                "\"id\" : \"reference-screen\",\n  \"bogus\" : 1"));
        assertThrows(IllegalArgumentException.class, () -> ReferenceBaselineCodec.read(unknown));
    }

    @Test
    @Timeout(30)
    void rejectsAResourceBeyondTheProtocolByteCeiling() throws Exception {
        Path oversized = tempDir.resolve("oversized.json");
        Files.write(oversized, new byte[ProtocolJson.MAX_REQUEST_BYTES + 1]);
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> ReferenceBaselineCodec.read(oversized));
        assertTrue(failure.getMessage().contains("exceeds"),
                "expected a byte-limit rejection, got: " + failure.getMessage());
    }

    private static String resourceText() throws Exception {
        return Files.readString(
                Path.of("src/main/resources/reference-ui/reference-baseline.json"));
    }
}
