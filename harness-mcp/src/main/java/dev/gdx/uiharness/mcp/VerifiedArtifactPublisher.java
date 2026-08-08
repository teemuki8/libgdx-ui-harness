package dev.gdx.uiharness.mcp;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Computes the expected SHA-256, byte length, and media type from an immutable
 * snapshot of the exact payload BEFORE an untrusted delegate publisher runs,
 * hands the delegate only that snapshot, and rejects any receipt that does not
 * match the pre-computed expectations — so a mutating or lying delegate can
 * neither redefine the bytes the receipt is verified against nor bind protocol
 * evidence to different bytes.
 */
public final class VerifiedArtifactPublisher implements ArtifactReference.Publisher {
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
        ArtifactReference receipt = delegate.publish(mediaType, snapshot);
        if (!receipt.sha256().equalsIgnoreCase(expectedSha256)
                || receipt.byteLength() != expectedLength
                || !receipt.mediaType().equals(mediaType)) {
            throw new ArtifactReference.ArtifactUnavailableException(
                    "Artifact publisher receipt does not match the published payload");
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
