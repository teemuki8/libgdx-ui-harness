package dev.gdx.uiharness.core.limits;

import dev.gdx.uiharness.core.error.ErrorCode;
import dev.gdx.uiharness.core.error.ErrorEvidence;
import dev.gdx.uiharness.core.error.HarnessException;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Hard bounds applied before semantic data or query results are published.
 *
 * @param maxNodes maximum nodes in one semantic snapshot
 * @param maxDepth maximum semantic graph depth
 * @param maxMatches maximum locator matches retained in a result
 * @param maxStringLength maximum UTF-16 code units in a string
 * @param maxSnapshotBytes maximum encoded snapshot bytes
 * @param maxDeadline maximum duration accepted for one operation
 */
public record HarnessLimits(
        int maxNodes,
        int maxDepth,
        int maxMatches,
        int maxStringLength,
        long maxSnapshotBytes,
        Duration maxDeadline) {
    private static final HarnessLimits DEFAULTS =
            new HarnessLimits(
                    10_000,
                    128,
                    1_000,
                    16_384,
                    1_048_576,
                    Duration.ofSeconds(30));

    /** Validates configured hard bounds. */
    public HarnessLimits {
        requirePositive(maxNodes, "maxNodes");
        requirePositive(maxDepth, "maxDepth");
        requirePositive(maxMatches, "maxMatches");
        requirePositive(maxStringLength, "maxStringLength");
        if (maxSnapshotBytes <= 0) {
            throw new IllegalArgumentException("maxSnapshotBytes must be positive");
        }
        Objects.requireNonNull(maxDeadline, "maxDeadline");
        if (maxDeadline.isZero() || maxDeadline.isNegative()) {
            throw new IllegalArgumentException("maxDeadline must be positive");
        }
    }

    /**
     * Returns the shared default hard limits.
     *
     * @return default limits
     */
    public static HarnessLimits defaults() {
        return DEFAULTS;
    }

    /**
     * Validates a snapshot node count.
     *
     * @param actual observed node count
     * @throws HarnessException when the configured limit is exceeded
     */
    public void validateNodeCount(int actual) {
        validateCount(actual, maxNodes, "nodes");
    }

    /**
     * Validates semantic graph depth.
     *
     * @param actual observed graph depth
     * @throws HarnessException when the configured limit is exceeded
     */
    public void validateDepth(int actual) {
        validateCount(actual, maxDepth, "depth");
    }

    /**
     * Validates a locator match count.
     *
     * @param actual observed match count
     * @throws HarnessException when the configured limit is exceeded
     */
    public void validateMatchCount(int actual) {
        validateCount(actual, maxMatches, "matches");
    }

    /**
     * Validates one bounded string.
     *
     * @param value string to validate
     * @param fieldName diagnostic field name
     * @throws HarnessException when the configured limit is exceeded
     */
    public void validateString(String value, String fieldName) {
        Objects.requireNonNull(value, "value");
        requireName(fieldName);
        if (value.length() > maxStringLength) {
            throw limitExceeded(fieldName, Integer.toString(value.length()), Integer.toString(maxStringLength));
        }
    }

    /**
     * Validates an encoded snapshot size.
     *
     * @param actual observed encoded bytes
     * @throws HarnessException when the configured limit is exceeded
     */
    public void validateSnapshotBytes(long actual) {
        if (actual < 0) {
            throw new IllegalArgumentException("actual snapshot bytes must be non-negative");
        }
        if (actual > maxSnapshotBytes) {
            throw limitExceeded(
                    "snapshotBytes", Long.toString(actual), Long.toString(maxSnapshotBytes));
        }
    }

    /**
     * Validates an operation deadline duration.
     *
     * @param actual requested duration
     * @throws HarnessException when the configured limit is exceeded
     */
    public void validateDeadline(Duration actual) {
        Objects.requireNonNull(actual, "actual");
        if (actual.isNegative()) {
            throw new IllegalArgumentException("actual deadline must be non-negative");
        }
        if (actual.compareTo(maxDeadline) > 0) {
            throw limitExceeded("deadline", actual.toString(), maxDeadline.toString());
        }
    }

    private static void validateCount(int actual, int limit, String dimension) {
        if (actual < 0) {
            throw new IllegalArgumentException("actual " + dimension + " must be non-negative");
        }
        if (actual > limit) {
            throw limitExceeded(dimension, Integer.toString(actual), Integer.toString(limit));
        }
    }

    private static HarnessException limitExceeded(
            String dimension, String actual, String limit) {
        var evidence =
                ErrorEvidence.ofDetails(
                        Map.of("dimension", dimension, "actual", actual, "limit", limit));
        return new HarnessException(
                ErrorCode.LIMIT_EXCEEDED,
                dimension + " exceeds configured limit " + limit + " (actual " + actual + ")",
                evidence);
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requireName(String name) {
        Objects.requireNonNull(name, "fieldName");
        if (name.isBlank()) {
            throw new IllegalArgumentException("fieldName must not be blank");
        }
    }
}
