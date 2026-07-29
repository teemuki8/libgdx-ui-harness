package dev.gdx.uiharness.core.contract;

import java.util.List;
import java.util.Objects;

/** One ordered viewport observation and the controls intersecting its visible region. */
public record ViewportState(
        String id,
        double width,
        double height,
        double scrollX,
        double scrollY,
        double maxScrollX,
        double maxScrollY,
        List<String> visibleControlIds) {
    public ViewportState {
        ContractSupport.text(id, "id");
        ContractSupport.finiteNonNegative(width, "width");
        ContractSupport.finiteNonNegative(height, "height");
        ContractSupport.finiteNonNegative(scrollX, "scrollX");
        ContractSupport.finiteNonNegative(scrollY, "scrollY");
        ContractSupport.finiteNonNegative(maxScrollX, "maxScrollX");
        ContractSupport.finiteNonNegative(maxScrollY, "maxScrollY");
        if (scrollX > maxScrollX || scrollY > maxScrollY) {
            throw new IllegalArgumentException("viewport scroll exceeds its range");
        }
        visibleControlIds = List.copyOf(
                Objects.requireNonNull(visibleControlIds, "visibleControlIds"));
        visibleControlIds.forEach(idValue ->
                ContractSupport.text(idValue, "visibleControlId"));
    }
}
