package dev.gdx.uiharness.core.contract;

import java.io.Serial;
import java.util.Objects;

/** Fail-closed contract diagnostic with a stable JSON path and observed constraint. */
public final class ContractViolationException extends IllegalArgumentException {
    @Serial
    private static final long serialVersionUID = 1L;

    private final String path;
    private final String expected;
    private final String observed;
    private final String schemaVersion;

    /** Creates one bounded actionable contract diagnostic. */
    public ContractViolationException(
            String path, String expected, String observed, ContractVersion version) {
        super(ContractSupport.text(path, "path") + " expected "
                + ContractSupport.text(expected, "expected") + " but observed "
                + ContractSupport.text(observed, "observed"));
        this.path = path;
        this.expected = expected;
        this.observed = observed;
        schemaVersion = Objects.requireNonNull(version, "version").wireName();
    }

    public String path() {
        return path;
    }

    public String expected() {
        return expected;
    }

    public String observed() {
        return observed;
    }

    public String schemaVersion() {
        return schemaVersion;
    }
}
