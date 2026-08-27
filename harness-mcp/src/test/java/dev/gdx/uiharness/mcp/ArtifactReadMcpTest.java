package dev.gdx.uiharness.mcp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gdx.uiharness.protocol.HarnessResponse;
import dev.gdx.uiharness.protocol.ProtocolJson;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

final class ArtifactReadMcpTest {
    private static final String SESSION = "game";
    private static final String REFERENCE = "artifact:receipt-1";

    @Test void serverKeepsPublisherOnlyOverloadAndAddsOptionalReaderOverload()
            throws Exception {
        assertEquals(HarnessMcpServer.class, HarnessMcpServer.class.getMethod(
                "open", dev.gdx.uiharness.protocol.HarnessProtocolService.class,
                ArtifactReference.Publisher.class, java.io.InputStream.class,
                java.io.OutputStream.class).getReturnType());
        assertEquals(HarnessMcpServer.class, HarnessMcpServer.class.getMethod(
                "open", dev.gdx.uiharness.protocol.HarnessProtocolService.class,
                ArtifactReference.Publisher.class, ArtifactReference.Reader.class,
                java.io.InputStream.class, java.io.OutputStream.class).getReturnType());
        ArtifactReference.Publisher lambda = (mediaType, content) ->
                new ArtifactReference("opaque", mediaType, content.length, sha256(content));
        assertEquals("opaque", lambda.publish(
                "application/octet-stream", new byte[0]).reference());
    }
    @Test void chunkOwnsItsBoundedBytes() {
        byte[] source = {1, 2, 3};
        ArtifactReference.Chunk chunk =
                new ArtifactReference.Chunk(receipt(source), 0, 3, true, source);
        source[0] = 9;
        byte[] returned = chunk.content();
        returned[1] = 9;
        assertArrayEquals(new byte[] {1, 2, 3}, chunk.content());
    }


    @Test void readsFirstMiddleFinalAndEofChunksForExactReassembly() throws Exception {
        byte[] payload = "opaque artifact bytes".getBytes(StandardCharsets.UTF_8);
        ArtifactReference receipt = receipt(payload);
        List<Long> offsets = new ArrayList<>();
        ArtifactReference.Reader reader = (sessionId, reference, offset, maxBytes) -> {
            assertEquals(SESSION, sessionId);
            assertEquals(REFERENCE, reference);
            offsets.add(offset);
            int start = Math.toIntExact(offset);
            int end = Math.min(payload.length, start + maxBytes);
            return new ArtifactReference.Chunk(receipt, offset, end, end == payload.length,
                    java.util.Arrays.copyOfRange(payload, start, end));
        };

        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
                HarnessToolHandler handler = new HarnessToolHandler(
                        ignored -> CompletableFuture.completedFuture((HarnessResponse) null),
                        ArtifactReference.Publisher.unavailable(), reader, executor, 1024)) {
            byte[] reassembled = new byte[payload.length];
            int copied = 0;
            long offset = 0;
            for (int maxBytes : List.of(6, 7, 64)) {
                Map<String, Object> result = structured(handler.handle(call(offset, maxBytes))
                        .block(Duration.ofSeconds(5)));
                assertTrue(McpJsonDefaults.getSchemaValidator().validate(
                        new HarnessToolCatalog().tool("ui_artifact_read").outputSchema(),
                        result).valid());
                assertEquals("artifact-chunk", result.get("kind"));
                assertEquals(REFERENCE, result.get("reference"));
                assertEquals("image/png", result.get("mediaType"));
                assertEquals((long) payload.length, ((Number) result.get("totalByteLength")).longValue());
                assertEquals(sha256(payload), result.get("sha256"));
                byte[] chunk = Base64.getDecoder().decode((String) result.get("data"));
                System.arraycopy(chunk, 0, reassembled, copied, chunk.length);
                copied += chunk.length;
                offset = ((Number) result.get("nextOffset")).longValue();
            }
            Map<String, Object> eof = structured(handler.handle(call(payload.length, 4))
                    .block(Duration.ofSeconds(5)));
            assertEquals("", eof.get("data"));
            assertEquals(true, eof.get("eof"));
            assertEquals((long) payload.length, ((Number) eof.get("nextOffset")).longValue());
            assertArrayEquals(payload, reassembled);
            assertEquals(sha256(payload), sha256(reassembled));
            assertEquals(List.of(0L, 6L, 13L, (long) payload.length), offsets);
        }
    }

    @Test void unavailableReaderIsTypedAndDoesNotInvokeProtocol() {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
                HarnessToolHandler handler = new HarnessToolHandler(
                        ignored -> { throw new AssertionError(
                                "artifact reads are server-scoped"); },
                        ArtifactReference.Publisher.unavailable(), executor, 1024)) {
            Map<String, Object> result = structured(handler.handle(call(0, 1))
                    .block(Duration.ofSeconds(5)));
            assertEquals(true, handler.handle(call(0, 1))
                    .block(Duration.ofSeconds(5)).isError());
            assertTrue(String.valueOf(result.get("message")).contains("artifact-read-unavailable"));
            assertFalse(result.toString().contains("/tmp"));
        }
    }
    @Test void normalizesMalformedMissingExpiredWrongSessionAndReaderFailures() {
        for (String reason : List.of("unknown", "expired", "wrong-session")) {
            ArtifactReference.Reader missing = (sessionId, reference, offset, maxBytes) -> {
                throw new ArtifactReference.ArtifactNotFoundException();
            };
            Map<String, Object> result = invoke(missing, REFERENCE, 0, 1);
            assertTrue(String.valueOf(result.get("message")).contains("artifact-not-found"),
                    reason);
            assertFalse(result.toString().contains(reason));
        }

        Map<String, Object> malformed = invoke(
                (sessionId, reference, offset, maxBytes) -> {
                    throw new AssertionError("malformed references must not reach reader");
                }, "/tmp/server-secret.png", 0, 1);
        assertTrue(String.valueOf(malformed.get("message"))
                .contains("invalid-artifact-reference"));
        assertFalse(malformed.toString().contains("/tmp/server-secret.png"));

        Map<String, Object> failed = invoke(
                (sessionId, reference, offset, maxBytes) -> {
                    throw new IllegalStateException("/srv/private/artifact.png");
                }, REFERENCE, 0, 1);
        assertTrue(String.valueOf(failed.get("message"))
                .contains("artifact-read-unavailable"));
        assertFalse(failed.toString().contains("/srv/private/artifact.png"));
    }

    @Test void rethrowsFatalReaderFailuresAndNormalizesOrdinaryErrors() {
        ThreadDeath death = new ThreadDeath();
        assertSame(death, assertThrows(ThreadDeath.class,
                () -> invoke((sessionId, reference, offset, maxBytes) -> {
                    throw death;
                }, REFERENCE, 0, 1)));

        OutOfMemoryError virtualMachineFailure = new OutOfMemoryError("fatal");
        assertSame(virtualMachineFailure, assertThrows(OutOfMemoryError.class,
                () -> invoke((sessionId, reference, offset, maxBytes) -> {
                    throw virtualMachineFailure;
                }, REFERENCE, 0, 1)));

        AssertionError ordinary = new AssertionError("/private/reader/path");
        Map<String, Object> normalized = invoke(
                (sessionId, reference, offset, maxBytes) -> {
                    throw ordinary;
                }, REFERENCE, 0, 1);
        assertTrue(String.valueOf(normalized.get("message"))
                .contains("artifact-read-unavailable"));
        assertFalse(normalized.toString().contains("/private/reader/path"));
    }

    @Test void rejectsReaderMetadataMutationAndOffsetBeyondTotal() {
        byte[] payload = {1, 2, 3};
        ArtifactReference honest = receipt(payload);
        Map<String, Object> mutated = invoke(
                (sessionId, reference, offset, maxBytes) -> new ArtifactReference.Chunk(
                        new ArtifactReference("artifact:other", honest.mediaType(),
                                honest.byteLength(), honest.sha256()),
                        0, 3, true, payload),
                REFERENCE, 0, 3);
        assertTrue(String.valueOf(mutated.get("message"))
                .contains("artifact-read-unavailable"));

        Map<String, Object> outside = invoke(
                (sessionId, reference, offset, maxBytes) -> {
                    throw new ArtifactReference.InvalidArtifactOffsetException();
                }, REFERENCE, 4, 1);
        assertTrue(String.valueOf(outside.get("message"))
                .contains("invalid-artifact-offset"));
        assertFalse(outside.toString().contains("/secret"));
    }


    @Test void maximumChunkResponseStaysBelowProtocolResponseBound() throws Exception {
        byte[] payload = new byte[ArtifactReference.MAX_CHUNK_BYTES];
        java.util.Arrays.fill(payload, (byte) 0xff);
        ArtifactReference receipt = receipt(payload);
        ArtifactReference.Reader reader = (sessionId, reference, offset, maxBytes) ->
                new ArtifactReference.Chunk(receipt, 0, payload.length, true, payload);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
                HarnessToolHandler handler = new HarnessToolHandler(
                        ignored -> CompletableFuture.completedFuture((HarnessResponse) null),
                        ArtifactReference.Publisher.unavailable(), reader, executor, 1024)) {
            Map<String, Object> result = structured(handler.handle(
                    call(0, ArtifactReference.MAX_CHUNK_BYTES)).block(Duration.ofSeconds(5)));
            byte[] encoded = ProtocolJson.mapper().writeValueAsBytes(result);
            assertTrue(encoded.length < ProtocolJson.MAX_RESPONSE_BYTES,
                    () -> "encoded response was " + encoded.length + " bytes");
        }
    }
    private static Map<String, Object> invoke(
            ArtifactReference.Reader reader, String reference, long offset, int maxBytes) {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
                HarnessToolHandler handler = new HarnessToolHandler(
                        ignored -> CompletableFuture.completedFuture((HarnessResponse) null),
                        ArtifactReference.Publisher.unavailable(), reader, executor, 1024)) {
            McpSchema.CallToolRequest request =
                    McpSchema.CallToolRequest.builder("ui_artifact_read").arguments(Map.of(
                            "sessionId", SESSION,
                            "reference", reference,
                            "offset", offset,
                            "maxBytes", maxBytes)).build();
            return structured(handler.handle(request).block(Duration.ofSeconds(5)));
        }
    }


    private static McpSchema.CallToolRequest call(long offset, int maxBytes) {
        return McpSchema.CallToolRequest.builder("ui_artifact_read").arguments(Map.of(
                "sessionId", SESSION,
                "reference", REFERENCE,
                "offset", offset,
                "maxBytes", maxBytes)).build();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(McpSchema.CallToolResult result) {
        return (Map<String, Object>) result.structuredContent();
    }

    private static ArtifactReference receipt(byte[] payload) {
        return new ArtifactReference(REFERENCE, "image/png", payload.length, sha256(payload));
    }

    private static String sha256(byte[] payload) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
