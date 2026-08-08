package dev.gdx.uiharness.fixtures;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.gdx.uiharness.core.golden.BaselineDigest;
import dev.gdx.uiharness.core.golden.SemanticBaseline;
import dev.gdx.uiharness.protocol.ProtocolJson;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;

/**
 * Strict Jackson codec for the canonical committed reference baseline resource.
 *
 * <p>Decoding reuses the hardened {@link ProtocolJson} mapper configuration: bounded JSON
 * nesting, string, and number tokens; unknown-property rejection; trailing-token rejection;
 * and primitive-null rejection. On top of that this codec consumes at most the protocol byte
 * ceiling plus one byte before rejecting an oversized resource, rejects duplicate object
 * keys, and verifies the resource's claimed canonical digest against a fresh recomputation,
 * so a tampered or stale resource fails closed instead of silently registering bad
 * expectations.
 */
public final class ReferenceBaselineCodec {
    /** The committed resource must stay within the established protocol request ceiling. */
    private static final int MAX_RESOURCE_BYTES = ProtocolJson.MAX_REQUEST_BYTES;

    /**
     * Independently mutable canonical-mapper copy hardened with duplicate-key rejection; the
     * shared {@link ProtocolJson} configuration itself is never mutated.
     */
    private static final ObjectMapper MAPPER =
            ProtocolJson.mapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private ReferenceBaselineCodec() {}

    /** Reads a baseline from the committed canonical resource path. */
    public static SemanticBaseline read(Path path) {
        Objects.requireNonNull(path, "path");
        try (InputStream input = Files.newInputStream(path)) {
            return decode(readBounded(input, path.toString()), path.toString());
        } catch (IOException failure) {
            throw new IllegalArgumentException(
                    "Unable to read semantic baseline resource " + path, failure);
        }
    }

    /**
     * Reads a baseline from the committed canonical resource stream. The caller's stream is
     * never closed; at most {@code MAX_RESOURCE_BYTES + 1} bytes are consumed before an
     * oversized resource is rejected.
     */
    public static SemanticBaseline read(InputStream input) {
        Objects.requireNonNull(input, "input");
        return decode(readBounded(input, "input stream"), "input stream");
    }

    /** Writes the canonical pretty-JSON resource. */
    public static void write(Path path, SemanticBaseline baseline) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(baseline, "baseline");
        byte[] json = (ProtocolJson.mapper().writerWithDefaultPrettyPrinter()
                .writeValueAsString(baseline) + System.lineSeparator())
                .getBytes(StandardCharsets.UTF_8);
        if (json.length > MAX_RESOURCE_BYTES) {
            throw new IllegalArgumentException(
                    "semantic baseline resource exceeds " + MAX_RESOURCE_BYTES + " bytes");
        }
        Files.write(path, json);
    }

    /**
     * Consumes at most {@code MAX_RESOURCE_BYTES + 1} bytes and rejects anything larger
     * before reading further, so an oversized input cannot grow without bound.
     */
    private static byte[] readBounded(InputStream input, String source) {
        byte[] buffer = new byte[MAX_RESOURCE_BYTES + 1];
        int total = 0;
        try {
            int read;
            while (total < buffer.length
                    && (read = input.read(buffer, total, buffer.length - total)) != -1) {
                total += read;
            }
        } catch (IOException failure) {
            throw new IllegalArgumentException(
                    "Unable to read semantic baseline resource " + source, failure);
        }
        if (total > MAX_RESOURCE_BYTES) {
            throw new IllegalArgumentException(
                    "semantic baseline resource exceeds " + MAX_RESOURCE_BYTES + " bytes: "
                            + source);
        }
        return Arrays.copyOf(buffer, total);
    }

    private static SemanticBaseline decode(byte[] json, String source) {
        if (json.length > MAX_RESOURCE_BYTES) {
            throw new IllegalArgumentException(
                    "semantic baseline resource exceeds " + MAX_RESOURCE_BYTES
                            + " bytes: " + source);
        }
        SemanticBaseline decoded;
        try {
            decoded = MAPPER.readValue(json, SemanticBaseline.class);
        } catch (IOException failure) {
            throw new IllegalArgumentException(
                    "Unable to read semantic baseline resource " + source, failure);
        }
        String recomputed = BaselineDigest.canonical(decoded);
        if (!recomputed.equals(decoded.digest())) {
            throw new IllegalArgumentException(
                    "semantic baseline resource digest mismatch in " + source
                            + ": claimed " + decoded.digest() + " but canonical digest is "
                            + recomputed);
        }
        return decoded;
    }
}
