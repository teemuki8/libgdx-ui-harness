package dev.gdx.uiharness.scene2d;

import dev.gdx.uiharness.core.contract.ContractValue;
import dev.gdx.uiharness.core.model.Role;
import java.util.Map;
import java.util.Objects;

/** Immutable semantic values explicitly associated with one Scene2D actor. */
public record ActorMetadata(
        Role role,
        String accessibleName,
        String text,
        String label,
        String testId,
        ControlMetadata control,
        ContractValue currentValue,
        String viewportId,
        TypographyMetadata typography,
        LayoutMetadata layout,
        Map<String, String> properties,
        dev.gdx.uiharness.core.model.RuntimeBinding binding) {
    static final ActorMetadata EMPTY =
            new ActorMetadata(
                    null, null, null, null, null, null, null, null, null, null, Map.of(),
                    null);

    /** Defensively copies the custom property map. */
    public ActorMetadata {
        properties = Map.copyOf(Objects.requireNonNull(properties, "properties"));
    }

    /** Retains the original metadata constructor for locator-only integrations. */
    public ActorMetadata(
            Role role,
            String accessibleName,
            String text,
            String label,
            String testId,
            Map<String, String> properties) {
        this(role, accessibleName, text, label, testId,
                null, null, null, null, null, properties, null);
    }

    /** Retains the evaluator-complete constructor introduced in protocol V1. */
    public ActorMetadata(
            Role role,
            String accessibleName,
            String text,
            String label,
            String testId,
            ControlMetadata control,
            ContractValue currentValue,
            String viewportId,
            Map<String, String> properties) {
        this(role, accessibleName, text, label, testId,
                control, currentValue, viewportId, null, null, properties, null);
    }

    /** Retains the evaluator/typography constructor introduced before layout V1. */
    public ActorMetadata(
            Role role,
            String accessibleName,
            String text,
            String label,
            String testId,
            ControlMetadata control,
            ContractValue currentValue,
            String viewportId,
            TypographyMetadata typography,
            Map<String, String> properties) {
        this(role, accessibleName, text, label, testId,
                control, currentValue, viewportId, typography, null, properties, null);
    }

    ActorMetadata withRole(Role value) {
        return copy(value, accessibleName, text, label, testId,
                control, currentValue, viewportId, typography);
    }

    ActorMetadata withAccessibleName(String value) {
        return copy(role, value, text, label, testId,
                control, currentValue, viewportId, typography);
    }

    ActorMetadata withText(String value) {
        return copy(role, accessibleName, value, label, testId,
                control, currentValue, viewportId, typography);
    }

    ActorMetadata withLabel(String value) {
        return copy(role, accessibleName, text, value, testId,
                control, currentValue, viewportId, typography);
    }

    ActorMetadata withTestId(String value) {
        return copy(role, accessibleName, text, label, value,
                control, currentValue, viewportId, typography);
    }

    ActorMetadata withBinding(dev.gdx.uiharness.core.model.RuntimeBinding value) {
        return new ActorMetadata(
                role, accessibleName, text, label, testId,
                control, currentValue, viewportId, typography, layout, properties, value);
    }

    ActorMetadata withControl(ControlMetadata value) {
        return copy(role, accessibleName, text, label, testId,
                value, currentValue, viewportId, typography);
    }

    ActorMetadata withCurrentValue(ContractValue value) {
        return copy(role, accessibleName, text, label, testId,
                control, value, viewportId, typography);
    }

    ActorMetadata withViewportId(String value) {
        return copy(role, accessibleName, text, label, testId,
                control, currentValue, value, typography);
    }

    ActorMetadata withTypography(TypographyMetadata value) {
        return copy(role, accessibleName, text, label, testId,
                control, currentValue, viewportId, value);
    }

    ActorMetadata withLayout(LayoutMetadata value) {
        return copy(role, accessibleName, text, label, testId,
                control, currentValue, viewportId, typography, value);
    }

    ActorMetadata withProperty(String key, String value) {
        var updated = new java.util.LinkedHashMap<>(properties);
        updated.put(key, value);
        return new ActorMetadata(role, accessibleName, text, label, testId,
                control, currentValue, viewportId, typography, layout, updated, binding);
    }

    private ActorMetadata copy(
            Role nextRole,
            String nextAccessibleName,
            String nextText,
            String nextLabel,
            String nextTestId,
            ControlMetadata nextControl,
            ContractValue nextCurrentValue,
            String nextViewportId,
            TypographyMetadata nextTypography) {
        return copy(nextRole, nextAccessibleName, nextText, nextLabel, nextTestId,
                nextControl, nextCurrentValue, nextViewportId, nextTypography, layout);
    }

    private ActorMetadata copy(
            Role nextRole,
            String nextAccessibleName,
            String nextText,
            String nextLabel,
            String nextTestId,
            ControlMetadata nextControl,
            ContractValue nextCurrentValue,
            String nextViewportId,
            TypographyMetadata nextTypography,
            LayoutMetadata nextLayout) {
        return new ActorMetadata(nextRole, nextAccessibleName, nextText, nextLabel, nextTestId,
                nextControl, nextCurrentValue, nextViewportId,
                nextTypography, nextLayout, properties, binding);
    }
}
