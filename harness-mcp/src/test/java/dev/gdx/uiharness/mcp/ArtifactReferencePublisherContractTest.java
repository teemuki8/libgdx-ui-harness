package dev.gdx.uiharness.mcp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Contract for the read-only {@link ByteBuffer} overload of {@link ArtifactReference.Publisher}. */
final class ArtifactReferencePublisherContractTest {

    @Test void defaultByteBufferOverloadCopiesExactlyTheRemainingRegion() {
        Recording publisher = new Recording();
        ByteBuffer buffer = ByteBuffer.wrap(new byte[] {0, 1, 2, 3, 4, 5, 6, 7});
        buffer.position(2);
        buffer.limit(6);

        ArtifactReference reference = publisher.publish("image/png", buffer);

        assertArrayEquals(new byte[] {2, 3, 4, 5}, publisher.lastBytes,
                "only the remaining region must be copied into the byte[] overload");
        assertEquals("image/png", publisher.lastMediaType,
                "the media type must pass through unchanged");
        assertEquals(4L, reference.byteLength(),
                "the receipt must describe exactly the published region");
        assertEquals(buffer.limit(), buffer.position(),
                "the default overload must consume the buffer through the remaining region");
        assertFalse(buffer.hasRemaining());
    }

    @Test void defaultByteBufferOverloadHandlesReadOnlyAndDirectBuffers() {
        Recording publisher = new Recording();
        byte[] heapBytes = {1, 2, 3, 4};

        publisher.publish("image/png", ByteBuffer.wrap(heapBytes).asReadOnlyBuffer());
        assertArrayEquals(heapBytes, publisher.lastBytes,
                "a read-only heap view must publish its full content");

        ByteBuffer direct = ByteBuffer.allocateDirect(3);
        direct.put(new byte[] {5, 6, 7});
        direct.flip();
        publisher.publish("image/png", direct);
        assertArrayEquals(new byte[] {5, 6, 7}, publisher.lastBytes,
                "a direct buffer must publish its full content");
    }

    @Test void defaultByteBufferOverloadPublishesEmptyRemainingRegion() {
        Recording publisher = new Recording();
        ByteBuffer empty = ByteBuffer.allocate(4);
        empty.position(4);

        ArtifactReference reference = publisher.publish("image/png", empty);

        assertArrayEquals(new byte[0], publisher.lastBytes,
                "an exhausted buffer must publish an empty payload");
        assertEquals(0L, reference.byteLength());
    }

    @Test void byteBufferOverloadCanBeOverriddenAndStaysACompatibleFunctionalInterface() {
        AtomicInteger byteArrayCalls = new AtomicInteger();
        AtomicInteger byteBufferCalls = new AtomicInteger();
        // A lambda still satisfies the single abstract method, so the default ByteBuffer
        // overload must not break the functional-interface contract.
        ArtifactReference.Publisher lambda = (mediaType, content) -> {
            byteArrayCalls.incrementAndGet();
            return new ArtifactReference("lambda", mediaType, content.length, "0".repeat(64));
        };
        // A zero-copy override must win over the default without touching the byte[] SAM.
        ArtifactReference.Publisher overriding = new ArtifactReference.Publisher() {
            @Override public ArtifactReference publish(String mediaType, byte[] content) {
                byteArrayCalls.incrementAndGet();
                return new ArtifactReference(
                        "override", mediaType, content.length, "0".repeat(64));
            }

            @Override public ArtifactReference publish(String mediaType, ByteBuffer content) {
                byteBufferCalls.incrementAndGet();
                byte[] copy = new byte[content.remaining()];
                content.get(copy);
                return new ArtifactReference("override", mediaType, copy.length, sha256Hex(copy));
            }
        };

        ArtifactReference fromLambda = lambda.publish("image/png",
                ByteBuffer.wrap(new byte[] {1, 2, 3}));
        assertEquals(1, byteArrayCalls.get(),
                "the default ByteBuffer overload must delegate to the byte[] SAM");
        assertEquals(3L, fromLambda.byteLength());

        ArtifactReference fromOverride = overriding.publish("image/png",
                ByteBuffer.wrap(new byte[] {4, 5, 6}));
        assertEquals(1, byteBufferCalls.get(),
                "the overridden ByteBuffer overload must be invoked for its publisher");
        assertEquals(1, byteArrayCalls.get(),
                "the override must not fall back to the byte[] SAM");
        assertEquals(3L, fromOverride.byteLength());
    }

    private static final class Recording implements ArtifactReference.Publisher {
        private byte[] lastBytes;
        private String lastMediaType;

        @Override public ArtifactReference publish(String mediaType, byte[] content) {
            lastBytes = content.clone();
            lastMediaType = mediaType;
            return new ArtifactReference("ref", mediaType, content.length, sha256Hex(content));
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException failure) {
            throw new AssertionError(failure);
        }
    }
}
