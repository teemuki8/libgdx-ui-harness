package dev.gdx.uiharness.core.navigation;

import java.util.List;
import java.util.Objects;

/**
 * Versioned immutable validation result with deterministic actor ordering.
 *
 * <p>{@link #wireSizeUpperBound()} conservatively accounts for every field as a JSON-compatible
 * UTF-8 wire representation. Strings reserve six bytes per UTF-16 code unit, covering escaping
 * controls, quotes, backslashes, BMP characters, and both halves of supplementary characters.
 * A protocol encoder may use the estimate for safe admission, then enforce its exact encoded size.
 */
public record NavigationResult(
        int schemaVersion,
        NavigationPath path,
        List<String> knownFocusables,
        List<String> unreachableFocusables,
        boolean truncated) {
    public static final int SCHEMA_VERSION = 1;

    public NavigationResult {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported navigation result schema version: " + schemaVersion);
        }
        Objects.requireNonNull(path, "path");
        knownFocusables = List.copyOf(Objects.requireNonNull(knownFocusables, "knownFocusables"));
        unreachableFocusables =
                List.copyOf(Objects.requireNonNull(unreachableFocusables, "unreachableFocusables"));
        if (knownFocusables.size() > NavigationRequest.MAX_ACTORS
                || unreachableFocusables.size() > NavigationRequest.MAX_ACTORS) {
            throw new IllegalArgumentException("navigation result exceeds hard actor bound");
        }
    }

    /** Returns a deterministic conservative UTF-8/wire byte upper bound for this complete result. */
    public int wireSizeUpperBound() {
        long size = 2;
        size += field("schemaVersion", decimal(schemaVersion));
        size += 1 + field("path", pathSize(path));
        size += 1 + field("knownFocusables", listSize(knownFocusables));
        size += 1 + field("unreachableFocusables", listSize(unreachableFocusables));
        size += 1 + field("truncated", truncated ? 4 : 5);
        return size > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) size;
    }

    static int minimumWireSizeUpperBound() {
        NavigationPath path = new NavigationPath(
                NavigationPath.SCHEMA_VERSION,
                null,
                List.of(),
                NavigationReason.UNSUPPORTED_CONTROLLER_PATH);
        return new NavigationResult(SCHEMA_VERSION, path, List.of(), List.of(), true)
                .wireSizeUpperBound();
    }

    private static long pathSize(NavigationPath path) {
        long size = 2;
        size += field("schemaVersion", decimal(path.schemaVersion()));
        size += 1 + field(
                "defaultFocusIdentity",
                path.defaultFocusIdentity() == null ? 4 : stringSize(path.defaultFocusIdentity()));
        size += 1 + field("steps", stepsSize(path.steps()));
        size += 1 + field("reason", asciiStringSize(path.reason().name()));
        return size;
    }

    private static long stepsSize(List<NavigationStep> steps) {
        long size = 2;
        for (int index = 0; index < steps.size(); index++) {
            if (index != 0) {
                size++;
            }
            NavigationStep step = steps.get(index);
            long stepSize = 2;
            stepSize += field("input", asciiStringSize(step.input().name()));
            stepSize += 1 + field("beforeFrame", decimal(step.beforeFrame()));
            stepSize += 1 + field("beforeRevision", decimal(step.beforeRevision()));
            stepSize += 1 + field("afterFrame", decimal(step.afterFrame()));
            stepSize += 1 + field("afterRevision", decimal(step.afterRevision()));
            stepSize += 1 + field("beforeIdentity", stringSize(step.beforeIdentity()));
            stepSize += 1 + field(
                    "afterIdentity",
                    step.afterIdentity() == null ? 4 : stringSize(step.afterIdentity()));
            stepSize += 1 + field(
                    "modalBoundaryId",
                    step.modalBoundaryId() == null ? 4 : stringSize(step.modalBoundaryId()));
            size += stepSize;
        }
        return size;
    }

    private static long listSize(List<String> values) {
        long size = 2;
        for (int index = 0; index < values.size(); index++) {
            if (index != 0) {
                size++;
            }
            size += stringSize(values.get(index));
        }
        return size;
    }

    private static long field(String name, long valueSize) {
        return asciiStringSize(name) + 1 + valueSize;
    }

    private static long asciiStringSize(String value) {
        return 2L + value.length();
    }

    private static long stringSize(String value) {
        return 2L + 6L * value.length();
    }

    private static int decimal(long value) {
        return Long.toString(value).length();
    }
}
