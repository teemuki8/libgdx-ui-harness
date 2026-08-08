package dev.gdx.uiharness.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gdx.uiharness.core.capture.CapturedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

final class BinaryAttachmentTest {
    @Test void ofDefensivelyCopiesAndTheValueStaysImmutable() {
        byte[] supplied = {1, 2, 3, 4, 5};
        BinaryAttachment attachment = BinaryAttachment.of(supplied);
        supplied[0] = 99;
        assertEquals(5, attachment.length());
        assertEquals(sha256(new byte[] {1, 2, 3, 4, 5}), attachment.sha256(),
                "the digest must be computed over the owned clone, immune to caller mutation");
        assertArrayEquals(new byte[] {1, 2, 3, 4, 5}, readAll(attachment));
    }

    @Test void ofRejectsEmptyAndOverLimitPayloadsAtTheFactory() {
        assertThrows(IllegalArgumentException.class, () -> BinaryAttachment.of(new byte[0]));
        byte[] exact = new byte[HarnessResponse.Result.Screenshot.MAX_PNG_BYTES];
        assertEquals(HarnessResponse.Result.Screenshot.MAX_PNG_BYTES,
                BinaryAttachment.of(exact).length(), "the exact maximum must be accepted");
        byte[] over = new byte[HarnessResponse.Result.Screenshot.MAX_PNG_BYTES + 1];
        assertThrows(IllegalArgumentException.class, () -> BinaryAttachment.of(over));
    }

    @Test void readOnlyBufferRejectsWritesAndNeverExposesTheArray() {
        BinaryAttachment attachment = BinaryAttachment.of(new byte[] {1, 2, 3});
        ByteBuffer view = attachment.asByteBuffer();
        assertTrue(view.isReadOnly());
        assertThrows(ReadOnlyBufferException.class, () -> view.put((byte) 9));
        assertFalse(view.hasArray(), "the read-only view must never expose the backing array");
        assertThrows(ReadOnlyBufferException.class, view::array);
    }

    @Test void writeToStreamsTheOwnedBytesWithoutMutation() throws Exception {
        BinaryAttachment attachment = BinaryAttachment.of(new byte[] {1, 2, 3, 4});
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        attachment.writeTo(sink);
        assertArrayEquals(new byte[] {1, 2, 3, 4}, sink.toByteArray());
        assertArrayEquals(new byte[] {1, 2, 3, 4}, readAll(attachment),
                "writeTo must not consume or mutate the attachment");
    }

    @Test void equalityAndHashCodeAreContentBased() {
        BinaryAttachment first = BinaryAttachment.of(new byte[] {1, 2, 3});
        BinaryAttachment second = BinaryAttachment.of(new byte[] {1, 2, 3});
        BinaryAttachment other = BinaryAttachment.of(new byte[] {3, 2, 1});
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertNotEquals(first, other);
    }

    @Test void takeCapturedRetainsAReadOnlySliceWithoutCopying() {
        byte[] payload = {9, 8, 7};
        CapturedImage image = new CapturedImage(payload, sha256(payload), 1, 1, 3, 1,
                new CapturedImage.Scale(1, 1));
        BinaryAttachment attachment = BinaryAttachment.takeCaptured(image);
        assertEquals(3, attachment.length());
        assertEquals(sha256(payload), attachment.sha256());
        assertArrayEquals(payload, readAll(attachment));
        assertTrue(attachment.asByteBuffer().isReadOnly());
    }

    @Test void takeCapturedDigestIsTiedToTheImmutableCapturedImage() {
        byte[] payload = {1, 2, 3};
        String expected = sha256(payload);
        CapturedImage image = new CapturedImage(payload, expected, 1, 1, 3, 1,
                new CapturedImage.Scale(1, 1));
        BinaryAttachment attachment = BinaryAttachment.takeCaptured(image);
        payload[0] = 99; // mutating the source array after construction cannot affect the owner
        assertEquals(expected, attachment.sha256(),
                "the digest must be tied to the immutable captured bytes, not the caller array");
        assertArrayEquals(new byte[] {1, 2, 3}, readAll(attachment));
    }

    private static byte[] readAll(BinaryAttachment attachment) {
        ByteBuffer view = attachment.asByteBuffer();
        byte[] bytes = new byte[view.remaining()];
        view.get(bytes);
        return bytes;
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 unavailable", impossible);
        }
    }
}
