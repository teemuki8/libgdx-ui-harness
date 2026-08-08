package dev.gdx.uiharness.core.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class TraceManifestTest {
    private static final Path ARCHIVE = Path.of("trace.zip");
    private static final Instant STARTED = Instant.parse("2026-07-28T00:00:00Z");
    private static final Instant ENDED = Instant.parse("2026-07-28T00:00:01Z");
    private static final String SHA =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String SHA_2 =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdee";

    @Test void legacyNineArgumentConstructorStillProducesV1Manifest() {
        TraceManifest manifest = new TraceManifest(ARCHIVE, "session-a", STARTED, ENDED,
                false, "interrupted", 1, 0, 100);

        assertEquals(TraceManifest.V1, manifest.schemaVersion());
        assertNull(manifest.eventsSha256());
        assertTrue(manifest.artifacts().isEmpty());
    }

    @Test void v2ConstructorRejectsArtifactCountMismatchingBindings() {
        Map<String, TraceManifest.ArtifactBinding> artifacts = Map.of(
                SHA, binding(SHA), SHA_2, binding(SHA_2));

        assertThrows(IllegalArgumentException.class, () -> new TraceManifest(
                ARCHIVE, "session-a", STARTED, ENDED, true, "completed", 1, 1, 100,
                TraceManifest.V2, SHA, artifacts));
    }

    @Test void v2ConstructorRejectsBindingKeyMismatchingSha256() {
        Map<String, TraceManifest.ArtifactBinding> artifacts = Map.of(
                SHA, binding(SHA_2));

        assertThrows(IllegalArgumentException.class, () -> new TraceManifest(
                ARCHIVE, "session-a", STARTED, ENDED, true, "completed", 1, 1, 100,
                TraceManifest.V2, SHA, artifacts));
    }

    @Test void v2ConstructorRejectsBindingCountOverLimit() {
        Map<String, TraceManifest.ArtifactBinding> artifacts = new LinkedHashMap<>();
        for (int index = 0; index <= TraceManifest.MAX_MANIFEST_ARTIFACTS; index++) {
            String sha = String.format("%064x", index);
            artifacts.put(sha, binding(sha));
        }

        assertThrows(IllegalArgumentException.class, () -> new TraceManifest(
                ARCHIVE, "session-a", STARTED, ENDED, true, "completed", 1,
                artifacts.size(), 100, TraceManifest.V2, SHA, artifacts));
    }

    @Test void v2DecodeRejectsUnknownArtifactBindingFields() {
        String json = "{\"version\":\"trace-manifest/v2\",\"sessionId\":\"session-a\","
                + "\"startedAt\":\"2026-07-28T00:00:00Z\","
                + "\"endedAt\":\"2026-07-28T00:00:01Z\",\"complete\":true,"
                + "\"terminationReason\":\"completed\",\"eventCount\":1,"
                + "\"artifactCount\":1,\"uncompressedBytes\":100,"
                + "\"eventsSha256\":\"" + SHA + "\","
                + "\"artifacts\":{\"" + SHA + "\":{\"sha256\":\"" + SHA + "\","
                + "\"size\":3,\"mediaType\":\"image/png\",\"extra\":true}}}";

        assertThrows(IOException.class, () -> TraceJson.decodeManifest(
                ARCHIVE, json.getBytes(StandardCharsets.UTF_8)));
    }

    private static TraceManifest.ArtifactBinding binding(String sha) {
        return new TraceManifest.ArtifactBinding(sha, 3, "image/png");
    }
}
