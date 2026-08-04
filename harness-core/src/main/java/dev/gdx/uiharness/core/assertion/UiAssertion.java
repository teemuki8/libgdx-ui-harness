package dev.gdx.uiharness.core.assertion;

import dev.gdx.uiharness.core.locator.Locator;
import dev.gdx.uiharness.core.model.Bounds;
import java.util.Objects;
import java.util.Set;

/** Closed, immutable set of transport-neutral UI assertions. */
public sealed interface UiAssertion permits UiAssertion.Visible, UiAssertion.Hidden,
        UiAssertion.Enabled, UiAssertion.Disabled, UiAssertion.Focused, UiAssertion.Checked,
        UiAssertion.TextEquals, UiAssertion.TextContains, UiAssertion.CountEquals,
        UiAssertion.BoundsInsideViewport, UiAssertion.DoesNotOverlap,
        UiAssertion.StableForFrames, UiAssertion.AccessibleNameExists {
    int MAX_TEXT_LENGTH = 16_384;
    int MAX_STABLE_FRAMES = 10_000;

    record Visible() implements UiAssertion {}
    record Hidden() implements UiAssertion {}
    record Enabled() implements UiAssertion {}
    record Disabled() implements UiAssertion {}
    record Focused() implements UiAssertion {}
    record Checked() implements UiAssertion {}

    record TextEquals(String expected) implements UiAssertion {
        public TextEquals { expected = bounded(expected, "expected"); }
    }

    record TextContains(String expected) implements UiAssertion {
        public TextContains { expected = bounded(expected, "expected"); }
    }

    record CountEquals(int expected) implements UiAssertion {
        public CountEquals {
            if (expected < 0) throw new IllegalArgumentException("expected must be non-negative");
        }
    }

    record BoundsInsideViewport(Bounds viewport) implements UiAssertion {
        public BoundsInsideViewport { Objects.requireNonNull(viewport, "viewport"); }
    }

    record DoesNotOverlap(Locator other) implements UiAssertion {
        public DoesNotOverlap { Objects.requireNonNull(other, "other"); }
    }

    /** Snapshot fields compared by a frame-stability assertion. */
    enum StableProperty { BOUNDS, TEXT, ACCESSIBLE_NAME, VISIBLE, ENABLED, CHECKED, FOCUSED }

    record StableForFrames(int frames, Set<StableProperty> properties) implements UiAssertion {
        public StableForFrames {
            if (frames <= 0 || frames > MAX_STABLE_FRAMES) {
                throw new IllegalArgumentException("frames must be between 1 and " + MAX_STABLE_FRAMES);
            }
            properties = Set.copyOf(Objects.requireNonNull(properties, "properties"));
            if (properties.isEmpty()) throw new IllegalArgumentException("properties must not be empty");
        }
    }

    record AccessibleNameExists() implements UiAssertion {}

    private static String bounded(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException(name + " exceeds " + MAX_TEXT_LENGTH + " characters");
        }
        return value;
    }
}
