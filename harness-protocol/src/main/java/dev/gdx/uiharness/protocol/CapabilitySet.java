package dev.gdx.uiharness.protocol;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/** Immutable, canonically ordered capability names advertised by one session. */
public record CapabilitySet(List<String> capabilities) {
    private static final int MAX_REGISTERED_CAPABILITIES = 64;
    private static final int MAX_ADVERTISED_CAPABILITIES = 65;

    /** Validates, sorts, de-duplicates, and defensively copies capability names. */
    public CapabilitySet {
        Objects.requireNonNull(capabilities, "capabilities");
        if (capabilities.size() > MAX_REGISTERED_CAPABILITIES) {
            throw new IllegalArgumentException("too many capabilities");
        }
        TreeSet<String> ordered = new TreeSet<>();
        for (String capability : capabilities) {
            ProtocolJson.requireIdentifier(capability, "capability");
            ordered.add(capability);
        }
        if (ordered.contains("ui_keyboard_gesture")) {
            ordered.add("ui_keyboard_gesture_v2");
        }
        if (ordered.size() > MAX_ADVERTISED_CAPABILITIES) {
            throw new IllegalArgumentException("too many capabilities");
        }
        capabilities = List.copyOf(ordered);
    }

    /** Returns whether this set includes the stable capability name. */
    public boolean supports(String capability) {
        return Collections.binarySearch(capabilities, capability) >= 0;
    }
}
