package dev.gdx.uiharness.core.error;

import java.io.Serial;
import java.util.Objects;

/** Runtime failure with a stable {@link ErrorCode} and immutable structured evidence. */
@SuppressWarnings("serial") // RuntimeException is serializable; structured evidence intentionally is not.
public final class HarnessException extends RuntimeException {
    @Serial private static final long serialVersionUID = 1L;
    private static final int MAX_MESSAGE_LENGTH = 16_384;

    /** Stable failure category. */
    private final ErrorCode code;
    /** Immutable structured context. */
    private final ErrorEvidence evidence;

    /**
     * Creates a typed failure.
     *
     * @param code stable failure category
     * @param message bounded human-readable explanation
     * @param evidence immutable structured context
     */
    public HarnessException(ErrorCode code, String message, ErrorEvidence evidence) {
        this(code, message, evidence, null);
    }

    /**
     * Creates a typed failure retaining its local cause.
     *
     * @param code stable failure category
     * @param message bounded human-readable explanation
     * @param evidence immutable structured context
     * @param cause local cause, or {@code null} when none exists
     */
    public HarnessException(
            ErrorCode code, String message, ErrorEvidence evidence, Throwable cause) {
        super(validateMessage(message), cause);
        this.code = Objects.requireNonNull(code, "code");
        this.evidence = Objects.requireNonNull(evidence, "evidence");
    }

    /**
     * Returns the stable failure category.
     *
     * @return failure category
     */
    public ErrorCode code() {
        return code;
    }

    /**
     * Returns immutable structured context for the failure.
     *
     * @return failure evidence
     */
    public ErrorEvidence evidence() {
        return evidence;
    }

    private static String validateMessage(String message) {
        Objects.requireNonNull(message, "message");
        if (message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        if (message.length() > MAX_MESSAGE_LENGTH) {
            throw new IllegalArgumentException(
                    "message exceeds " + MAX_MESSAGE_LENGTH + " characters");
        }
        return message;
    }
}
