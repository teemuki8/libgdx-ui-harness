package dev.gdx.uiharness.benchmarks;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gdx.uiharness.protocol.ProtocolJson;
import org.junit.jupiter.api.Test;

final class FailureContractTest {
    private static final BenchmarkScenario.FailureExpectation STRICT_LOCATOR =
            new BenchmarkScenario.FailureExpectation(
                    "strict-locator",
                    "strictness-violation",
                    "/details/matchCount",
                    "[redacted] 2",
                    "Error",
                    "strict mode violation");

    @Test void harnessRejectsTimeoutWhenStrictLocatorFailureWasExpected() throws Exception {
        JsonNode timeout = ProtocolJson.mapper().readTree("""
                {"kind":"error","code":"timeout","details":{"unmet":"ATTACHED"}}
                """);

        assertFalse(STRICT_LOCATOR.matchesHarness(timeout));
    }

    @Test void harnessRequiresExactCodeAndNamedEvidence() throws Exception {
        JsonNode strict = ProtocolJson.mapper().readTree("""
                {"kind":"error","code":"strictness-violation",
                 "details":{"matchCount":"[redacted] 2"}}
                """);
        JsonNode noEvidence = ProtocolJson.mapper().readTree("""
                {"kind":"error","code":"strictness-violation","details":{}}
                """);
        JsonNode wrongEvidence = ProtocolJson.mapper().readTree("""
                {"kind":"error","code":"strictness-violation",
                 "details":{"matchCount":"[redacted] 3"}}
                """);

        assertTrue(STRICT_LOCATOR.matchesHarness(strict));
        assertFalse(STRICT_LOCATOR.matchesHarness(noEvidence));
        assertFalse(STRICT_LOCATOR.matchesHarness(wrongEvidence));
    }
}
