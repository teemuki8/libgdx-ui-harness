package dev.gdx.uiharness.mcp;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Computes the expected SHA-256, byte length, and media type from an immutable
 * snapshot of the exact payload BEFORE an untrusted delegate publisher runs,
 * hands the delegate only that snapshot, and rejects any receipt that does not
 * match the pre-computed expectations. The immutable captured bytes define the
 * expected receipt digest, length, and media type, so a mutating or lying
 * delegate cannot redefine those receipt claims. Opaque-reference storage and
 * readback integrity remain the publisher's responsibility. A delegate
 * failure, a null receipt, or a mismatched receipt all surface as one fixed
 * {@link ArtifactReference.ArtifactUnavailableException} — the delegate's own
 * message never escapes the boundary.
 */
public final class VerifiedArtifactPublisher implements ArtifactReference.Publisher {
    private static final String UNAVAILABLE_MESSAGE =
            "Artifact publisher receipt is unavailable or invalid";

    private final ArtifactReference.Publisher delegate;

    /** Wraps the injected publisher with boundary verification. */
    public VerifiedArtifactPublisher(ArtifactReference.Publisher delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override public ArtifactReference publish(String mediaType, byte[] content) {
        Objects.requireNonNull(mediaType, "mediaType");
        Objects.requireNonNull(content, "content");
        byte[] snapshot = content.clone();               // immutable copy for the delegate
        String expectedSha256 = sha256(snapshot);        // computed BEFORE the delegate runs
        long expectedLength = snapshot.length;
        ArtifactReference receipt;
        try {
            receipt = delegate.publish(mediaType, snapshot);
        } catch (RuntimeException failure) {
            throw new ArtifactReference.ArtifactUnavailableException(
                    UNAVAILABLE_MESSAGE, failure);
        }
        if (receipt == null
                || !receipt.sha256().equals(expectedSha256)   // exact lowercase digest
                || receipt.byteLength() != expectedLength
                || !receipt.mediaType().equals(mediaType)) {
            throw new ArtifactReference.ArtifactUnavailableException(UNAVAILABLE_MESSAGE);
        }
        return receipt;
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 is required by the Java platform", impossible);
        }
    }
}
