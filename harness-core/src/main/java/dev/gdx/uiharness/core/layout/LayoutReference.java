package dev.gdx.uiharness.core.layout;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Named viewport-specific expected layout contract. */
public record LayoutReference(
        String referenceId,
        String applicationId,
        String viewportId,
        String referenceArtifactId,
        List<LayoutControlReference> controls) {
    /** Copies and validates one to 256 unique controls. */
    public LayoutReference {
        LayoutSupport.nonBlank(referenceId, "referenceId");
        LayoutSupport.nonBlank(applicationId, "applicationId");
        LayoutSupport.nonBlank(viewportId, "viewportId");
        LayoutSupport.nonBlank(referenceArtifactId, "referenceArtifactId");
        controls = List.copyOf(Objects.requireNonNull(controls, "controls"));
        if (controls.isEmpty() || controls.size() > 256) {
            throw new IllegalArgumentException(
                    "controls must contain between 1 and 256 entries");
        }
        LinkedHashMap<String, LayoutControlReference> unique = new LinkedHashMap<>();
        controls.forEach(control -> {
            if (unique.putIfAbsent(control.controlId(), control) != null) {
                throw new IllegalArgumentException(
                        "duplicate layout control: " + control.controlId());
            }
        });
    }

    /** Returns controls indexed in declared order. */
    public Map<String, LayoutControlReference> controlsById() {
        LinkedHashMap<String, LayoutControlReference> result = new LinkedHashMap<>();
        controls.forEach(control -> result.put(control.controlId(), control));
        return Collections.unmodifiableMap(result);
    }
}
