package dev.gdx.uiharness.fixtures;

import java.util.Objects;

/** Independent application-domain runtime model for the reference screen. */
public final class ReferenceUiModel {
    private static final int MAX_VALUE = 16_384;
    private volatile String username;
    private volatile String password;

    public ReferenceUiModel(String username, String password) {
        this.username = bounded(username);
        this.password = bounded(password);
    }

    public String username() {
        return username;
    }

    public String password() {
        return password;
    }

    public void setUsername(String value) {
        username = bounded(value);
    }

    public void setPassword(String value) {
        password = bounded(value);
    }

    private static String bounded(String value) {
        Objects.requireNonNull(value, "value");
        if (value.length() > MAX_VALUE) {
            throw new IllegalArgumentException("model value exceeds 16384 characters");
        }
        return value;
    }
}
