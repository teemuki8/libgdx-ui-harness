package dev.gdx.uiharness.core.assertion;

import dev.gdx.uiharness.core.limits.HarnessLimits;
import dev.gdx.uiharness.core.locator.Locator;
import dev.gdx.uiharness.core.time.Deadline;
import java.util.Objects;

/** Versioned immutable request for one declarative assertion. */
public record AssertionRequest(
        int schemaVersion, Locator locator, UiAssertion assertion, Deadline deadline) {
    public static final int SCHEMA_VERSION = 1;

    public AssertionRequest {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported assertion schema version: " + schemaVersion);
        }
        Objects.requireNonNull(locator, "locator");
        Objects.requireNonNull(assertion, "assertion");
        Objects.requireNonNull(deadline, "deadline");
        HarnessLimits.defaults().validateDeadline(deadline.timeout());
    }
}
