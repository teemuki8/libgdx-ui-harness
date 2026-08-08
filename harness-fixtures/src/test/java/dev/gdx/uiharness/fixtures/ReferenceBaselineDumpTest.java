package dev.gdx.uiharness.fixtures;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Captures the pristine semantic baseline from a real LWJGL3 reference process and asserts it
 * byte-matches the committed resource. Set {@code UPDATE_REFERENCE_BASELINE_GOLDEN=true} to
 * regenerate the committed resource after a deliberate screen change.
 */
final class ReferenceBaselineDumpTest {
    @Test
    @Timeout(120)
    void dumpedBaselineMatchesTheCommittedResource() throws Exception {
        Path resource = Path.of("src/main/resources/reference-ui/reference-baseline.json");
        boolean update = "true".equals(System.getenv("UPDATE_REFERENCE_BASELINE_GOLDEN"));
        if (!update) {
            assertTrue(Files.isRegularFile(resource),
                    "the reference baseline resource must exist");
        }
        try (ReferenceProcess app = ReferenceProcess.launch("dump-baseline")) {
            Path generated = app.root().resolve("reference-baseline.json");
            assertTrue(Files.isRegularFile(generated),
                    "the reference process must dump its pristine baseline");
            byte[] actual = Files.readAllBytes(generated);
            if (update) {
                Files.write(resource, actual);
            }
            assertArrayEquals(Files.readAllBytes(resource), actual,
                    "the committed baseline must match a fresh pristine process");
        }
    }
}
