package dev.gdx.uiharness.protocol;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/** Immutable, canonically ordered capability names advertised by one session. */
public record CapabilitySet(List<String> capabilities) {
    private static final int MAX_CAPABILITIES = 64;

    /** Validates, sorts, de-duplicates, and defensively copies capability names. */
    public CapabilitySet {
        Objects.requireNonNull(capabilities, "capabilities");
        if (capabilities.size() > MAX_CAPABILITIES) {
            throw new IllegalArgumentException("too many capabilities");
        }
        TreeSet<String> ordered = new TreeSet<>();
        for (String capability : capabilities) {
            ProtocolJson.requireIdentifier(capability, "capability");
            ordered.add(capability);
        }
        capabilities = List.copyOf(ordered);
    }

    /** Returns whether this set includes the stable capability name. */
    public boolean supports(String capability) {
        return Collections.binarySearch(capabilities, capability) >= 0;
    }
}
