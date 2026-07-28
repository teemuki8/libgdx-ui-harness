package dev.gdx.uiharness.core.capture;

import dev.gdx.uiharness.core.locator.Locator;
import java.util.Objects;
import java.util.Optional;

/** Immutable full-window or semantic-actor capture request with allocation hard limits. */
public record CaptureRequest(Optional<Locator> actorLocator, Limits limits) {
    /** Validates and defensively retains the requested target and hard limits. */
    public CaptureRequest {
        actorLocator = Objects.requireNonNull(actorLocator, "actorLocator");
        limits = Objects.requireNonNull(limits, "limits");
    }

    /** Creates a bounded full-window capture request. */
    public static CaptureRequest fullWindow() {
        return new CaptureRequest(Optional.empty(), Limits.defaults());
    }

    /** Creates a bounded actor capture request resolved freshly at the captured frame. */
    public static CaptureRequest actor(Locator locator) {
        return new CaptureRequest(Optional.of(Objects.requireNonNull(locator, "locator")),
                Limits.defaults());
    }

    /** Returns a copy using explicit allocation and encoded-output limits. */
    public CaptureRequest withLimits(Limits replacement) {
        return new CaptureRequest(actorLocator, replacement);
    }

    /** Returns whether this request targets a semantic actor instead of the full window. */
    public boolean isActor() {
        return actorLocator.isPresent();
    }

    /** Hard limits checked before pixel allocation and while PNG bytes are encoded. */
    public record Limits(int maxWidth, int maxHeight, long maxPixels, int maxPngBytes) {
        private static final Limits DEFAULTS =
                new Limits(8_192, 8_192, 33_554_432L, 64 * 1_024 * 1_024);

        /** Validates positive capture bounds. */
        public Limits {
            requirePositive(maxWidth, "maxWidth");
            requirePositive(maxHeight, "maxHeight");
            if (maxPixels <= 0) {
                throw new IllegalArgumentException("maxPixels must be positive");
            }
            requirePositive(maxPngBytes, "maxPngBytes");
        }

        /** Returns conservative defaults suitable for desktop evidence capture. */
        public static Limits defaults() {
            return DEFAULTS;
        }

        private static void requirePositive(int value, String name) {
            if (value <= 0) {
                throw new IllegalArgumentException(name + " must be positive");
            }
        }
    }
}
