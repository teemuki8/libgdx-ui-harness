package dev.gdx.uiharness.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

final class BuildContractTest {
    @Test void runsOnJava25() {
        assertEquals(25, Runtime.version().feature());
    }
}
