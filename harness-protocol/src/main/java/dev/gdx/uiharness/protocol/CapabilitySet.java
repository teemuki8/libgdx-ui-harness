package dev.gdx.uiharness.protocol;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/** Immutable, canonically ordered capability names advertised by one session. */
public record CapabilitySet(List<String> capabilities) {
    private static final int MAX_REGISTERED_CAPABILITIES = 64;
    private static final int MAX_ADVERTISED_CAPABILITIES = 65;

    /** Validates registration bounds and derives canonically ordered advertised capabilities. */
    public CapabilitySet {
        capabilities = normalize(capabilities, MAX_REGISTERED_CAPABILITIES);
    }

    static List<String> canonicalAdvertised(List<String> capabilities) {
        return normalize(capabilities, MAX_ADVERTISED_CAPABILITIES);
    }

    private static List<String> normalize(List<String> source, int maximumInput) {
        Objects.requireNonNull(source, "capabilities");
        if (source.size() > maximumInput) {
            throw new IllegalArgumentException("too many capabilities");
        }
        TreeSet<String> ordered = new TreeSet<>();
        for (String capability : source) {
            ProtocolJson.requireIdentifier(capability, "capability");
            ordered.add(capability);
        }
        if (source.size() > MAX_REGISTERED_CAPABILITIES
                && (ordered.size() != MAX_ADVERTISED_CAPABILITIES
                        || !ordered.contains("ui_keyboard_gesture")
                        || !ordered.contains("ui_keyboard_gesture_v2"))) {
            throw new IllegalArgumentException(
                    "65 advertised capabilities require the derived gesture v2 capability");
        }
        if (ordered.contains("ui_keyboard_gesture")) {
            ordered.add("ui_keyboard_gesture_v2");
        } else if (ordered.contains("ui_keyboard_gesture_v2")) {
            throw new IllegalArgumentException(
                    "ui_keyboard_gesture_v2 requires ui_keyboard_gesture");
        }
        if (ordered.size() > MAX_ADVERTISED_CAPABILITIES) {
            throw new IllegalArgumentException("too many capabilities");
        }
        return List.copyOf(ordered);
    }

    /** Returns whether this set includes the stable capability name. */
    public boolean supports(String capability) {
        return Collections.binarySearch(capabilities, capability) >= 0;
    }
}
