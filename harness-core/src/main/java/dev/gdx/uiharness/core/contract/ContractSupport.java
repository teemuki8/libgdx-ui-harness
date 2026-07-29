package dev.gdx.uiharness.core.contract;

import java.util.Objects;

final class ContractSupport {
    static final int MAX_TEXT_LENGTH = 16_384;

    private ContractSupport() {}

    static String text(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (value.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException(name + " exceeds " + MAX_TEXT_LENGTH + " characters");
        }
        return value;
    }

    static void finiteNonNegative(double value, String name) {
        if (!Double.isFinite(value) || value < 0) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
    }
}
