package dev.gdx.uiharness.benchmarks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BenchmarkScenarioTest {
    @TempDir Path temporary;

    @Test void parserPreservesDeclaredOrderAndImmutableSteps() throws Exception {
        Path corpus = write("""
                {"schemaVersion":1,"scenarios":[
                  {"id":"sign-in","description":"Sign in","logicalDelayMillis":96,
                   "steps":[{"action":"fill","locator":{"kind":"label","value":"Username"},
                             "value":"Ada"}],"expected":"Welcome, Ada"}
                ]}
                """);

        List<BenchmarkScenario> scenarios = BenchmarkScenario.parse(corpus);

        assertEquals(List.of("sign-in"), scenarios.stream().map(BenchmarkScenario::id).toList());
        assertEquals("label", scenarios.getFirst().steps().getFirst().locator().kind());
        assertThrows(UnsupportedOperationException.class,
                () -> scenarios.getFirst().steps().clear());
        assertThrows(UnsupportedOperationException.class, scenarios::clear);
    }

    @Test void parserRejectsDuplicateScenarioIds() throws Exception {
        Path corpus = write("""
                {"schemaVersion":1,"scenarios":[
                  {"id":"same","description":"one","logicalDelayMillis":16,
                   "steps":[{"action":"click","locator":{"kind":"test-id","value":"one"}}],
                   "expected":"x"},
                  {"id":"same","description":"two","logicalDelayMillis":16,
                   "steps":[{"action":"click","locator":{"kind":"test-id","value":"two"}}],
                   "expected":"x"}
                ]}
                """);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> BenchmarkScenario.parse(corpus));
        assertEquals("Duplicate scenario id: same", failure.getMessage());
    }

    @Test void parserRejectsMalformedOrUnknownCorpusFields() throws Exception {
        Path malformed = write("{\"schemaVersion\":1,\"scenarios\":[] trailing");
        assertThrows(IllegalArgumentException.class, () -> BenchmarkScenario.parse(malformed));

        Path unknown = write("""
                {"schemaVersion":1,"unexpected":true,"scenarios":[]}
                """);
        assertThrows(IllegalArgumentException.class, () -> BenchmarkScenario.parse(unknown));
    }

    @Test void parserRejectsNonFixedLogicalDelay() throws Exception {
        Path corpus = write("""
                {"schemaVersion":1,"scenarios":[
                  {"id":"bad-delay","description":"bad","logicalDelayMillis":15,
                   "steps":[{"action":"click","locator":{"kind":"test-id","value":"target"}}],
                   "expected":"x"}
                ]}
                """);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> BenchmarkScenario.parse(corpus));
        assertEquals("logicalDelayMillis must be a positive multiple of 16",
                rootCause(failure).getMessage());
    }

    @Test void parserRejectsExpectedFailureWithoutExactContract() throws Exception {
        Path corpus = write("""
                {"schemaVersion":1,"scenarios":[
                  {"id":"bad-failure","description":"bad","logicalDelayMillis":16,
                   "steps":[{"action":"expect-click-failure",
                             "locator":{"kind":"test-id","value":"missing"}}],
                   "expected":"failure:strict-locator"}
                ]}
                """);

        assertThrows(IllegalArgumentException.class, () -> BenchmarkScenario.parse(corpus));
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private Path write(String content) throws Exception {
        Path file = temporary.resolve("scenarios-" + System.nanoTime() + ".json");
        Files.writeString(file, content);
        return file;
    }
}
