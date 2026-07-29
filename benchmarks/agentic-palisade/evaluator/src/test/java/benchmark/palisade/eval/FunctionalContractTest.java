package benchmark.palisade.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FunctionalContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temporary;

    @Test
    void fixtureCoversEveryCorpusDeclaredBehaviorIncludingConditionalFocusRestoration() throws Exception {
        JsonNode corpus = corpus();
        FunctionalContract contract = FunctionalContract.fromCorpus(corpus);
        ObjectNode fixture = conformingFixture(corpus);

        FunctionalContract.Result result = contract.evaluate(fixture);

        assertTrue(result.allPassed(), () -> result.assertions().toString());
        assertEquals(result.assertions().size(), result.passedCount());
        assertEquals(Set.of(
                "controls.order", "controls.kinds", "controls.labels",
                "controls.options", "controls.defaults", "controls.focus-order",
                "controls.validation", "state.initial.values",
                "state.initial.visibility", "state.bottom.scroll",
                "conditional.rival-target.visible", "conditional.rival-target.value",
                "conditional.rival-target.hidden", "conditional.rival-target.focus-restored",
                "seed.minimum.valid", "seed.maximum.valid", "seed.below-minimum.invalid",
                "seed.above-maximum.invalid", "seed.invalid.start-blocked",
                "transition.scroll-to-bottom", "transition.copy-seed", "transition.random-seed",
                "transition.cancel", "transition.escape", "transition.start-battle"),
                new HashSet<>(contract.assertionIds()));
        assertEquals(contract.assertionIds().size(), new HashSet<>(contract.assertionIds()).size());
    }

    @Test
    void failuresRemainIndependentAndRetainBoundedEvidence() throws Exception {
        JsonNode corpus = corpus();
        FunctionalContract contract = FunctionalContract.fromCorpus(corpus);
        ObjectNode fixture = conformingFixture(corpus);
        fixture.withObject("checkpoints").withObject("copySeed").put("clipboardText", "wrong");

        FunctionalContract.Result result = contract.evaluate(fixture);

        assertFalse(result.allPassed());
        assertEquals(1, result.assertions().stream().filter(assertion -> !assertion.passed()).count());
        FunctionalContract.Assertion failure = result.assertions().stream()
                .filter(assertion -> !assertion.passed()).findFirst().orElseThrow();
        assertEquals("transition.copy-seed", failure.id());
        assertTrue(failure.evidence().length() <= 512);
    }

    @Test
    void malformedOrMissingEvidenceFailsEveryAssertionWithoutThrowing() throws Exception {
        FunctionalContract contract = FunctionalContract.fromCorpus(corpus());

        FunctionalContract.Result result = contract.evaluate(JSON.createObjectNode());

        assertEquals(0, result.passedCount());
        assertEquals(contract.assertionIds().size(), result.assertions().size());
    }

    @Test
    void uncompilableCandidatePublishesZeroPassEvaluationWithoutChangingCandidate() throws Exception {
        Path candidate = temporary.resolve("candidate");
        Files.createDirectories(candidate.resolve("src/main/java/benchmark/palisade"));
        Files.writeString(candidate.resolve("settings.gradle.kts"), "rootProject.name = \"broken\"\n", StandardCharsets.UTF_8);
        Files.writeString(candidate.resolve("build.gradle.kts"), "plugins { java }\njava { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }\n", StandardCharsets.UTF_8);
        Files.writeString(candidate.resolve("src/main/java/benchmark/palisade/Broken.java"),
                "package benchmark.palisade; public class Broken { this is not Java; }\n",
                StandardCharsets.UTF_8);
        String before = CandidateEvaluator.treeSha256(candidate);
        Path output = temporary.resolve("evaluation-output");

        EvaluationRecord record = CandidateEvaluator.evaluate(new CandidateEvaluator.Request(
                candidate, Path.of(System.getProperty("palisade.corpus")), output,
                "candidate-fixture", Path.of(System.getProperty("palisade.rootGradle"))));

        assertEquals("compile-failed", record.status());
        assertEquals(0, record.functional().passed());
        assertEquals(record.functional().total(), record.functional().assertions().size());
        assertEquals(before, CandidateEvaluator.treeSha256(candidate));
        assertTrue(Files.isRegularFile(output.resolve("evaluation.json")));
        try (var files = Files.list(output)) {
            assertEquals(Set.of("evaluation.json"), files.map(path -> path.getFileName().toString()).collect(java.util.stream.Collectors.toSet()));
        }
        JsonNode published = JSON.readTree(output.resolve("evaluation.json").toFile());
        assertEquals("agentic-palisade-evaluation/v1", published.path("schemaVersion").asText());
        assertEquals("candidate-fixture", published.path("candidate").path("id").asText());
        assertEquals(before, published.path("candidate").path("sha256").asText());
    }

    @Test
    void candidateIdentityAndUnexpectedEvidenceFailClosed() throws Exception {
        Path candidate = temporary.resolve("identity-candidate");
        Files.createDirectories(candidate);
        Files.writeString(candidate.resolve("source.txt"), "immutable", StandardCharsets.UTF_8);
        Path evidence = temporary.resolve("evidence");
        Files.createDirectories(evidence.resolve("captures"));
        Files.writeString(evidence.resolve("results.ndjson"), "", StandardCharsets.UTF_8);
        Files.writeString(evidence.resolve("unexpected.txt"), "no", StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class, () -> new CandidateEvaluator.Request(
                candidate, Path.of(System.getProperty("palisade.corpus")), temporary.resolve("out"),
                "../escape", Path.of(System.getProperty("palisade.rootGradle"))));
        assertThrows(IllegalArgumentException.class,
                () -> CandidateEvaluator.validateEvidenceLayout(evidence, Set.of()));
    }

    @Test
    void publicFeedbackOmitsHiddenAssertionIdsAndExpectedEvidence() throws Exception {
        FunctionalContract contract = FunctionalContract.fromCorpus(corpus());
        FunctionalContract.Result functional = contract.evaluate(conformingFixture(corpus()));
        EvaluationRecord record = EvaluationRecord.forTesting(functional);

        String feedback = PublicFeedback.toJson(record);

        assertTrue(feedback.contains("agentic-palisade-feedback/v1"));
        assertTrue(feedback.contains("behavioral"));
        assertFalse(feedback.contains("controls.order"));
        assertFalse(feedback.contains("expected"));
        assertFalse(feedback.contains("evidence"));
    }

    @Test
    void evaluateCliAcceptsItsDocumentedFourOptionPairs() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> CandidateEvaluator.main(new String[] {
                    "evaluate",
                    "--candidate", temporary.resolve("missing-candidate").toString(),
                    "--corpus", temporary.resolve("missing-corpus").toString(),
                    "--output", temporary.resolve("output").toString(),
                    "--candidate-id", "candidate-one"
                }));

        assertFalse(failure.getMessage().startsWith("Expected evaluate"));
    }

    @Test
    void evaluatorUsesDirectScenarioStateRatherThanCandidateSuppliedCheckpointBundle()
            throws Exception {
        JsonNode corpus = corpus();
        FunctionalContract contract = FunctionalContract.fromCorpus(corpus);
        ObjectNode fixture = conformingFixture(corpus);
        ObjectNode observed = JSON.createObjectNode();
        observed.put("seed", 305419896L).put("clipboardText", "wrong");
        observed.putObject("copySeed")
                .put("seed", 305419896L).put("clipboardText", "305419896");

        CandidateEvaluator.copyObservedCheckpoint(
                fixture.withObject("checkpoints"), "copySeed", observed);
        FunctionalContract.Result result = contract.evaluate(fixture);

        assertFalse(result.assertions().stream()
                .filter(assertion -> assertion.id().equals("transition.copy-seed"))
                .findFirst().orElseThrow().passed());
    }

    @Test
    void launcherCommandNamesFrozenCandidateLauncherExplicitly() {
        var command = CandidateEvaluator.launchCommand(
                Path.of("/runtime/bin/java"), "classes:dependencies",
                Path.of("/input.ndjson"), Path.of("/evidence"));

        assertEquals("/runtime/bin/java", command.getFirst());
        assertTrue(command.contains("benchmark.palisade.CandidateLauncher"));
        assertFalse(command.contains("run"));
    }

    @Test
    void candidateLocalMissingResourcesDirectoryIsAValidJavaClasspathEntry()
            throws Exception {
        Path candidate = temporary.resolve("classpath-candidate");
        Path classes = candidate.resolve("build/classes/java/main");
        Files.createDirectories(classes);
        Path absentResources = candidate.resolve("build/resources/main");
        String classpath = classes + java.io.File.pathSeparator + absentResources;

        assertEquals(classpath,
                CandidateEvaluator.validateRuntimeClasspath(classpath, candidate));
    }

    @Test
    void changedCandidateIsRejectedBeforeEvaluationPublication() throws Exception {
        Path candidate = temporary.resolve("changing-candidate");
        Files.createDirectories(candidate);
        Path source = candidate.resolve("source.txt");
        Files.writeString(source, "before", StandardCharsets.UTF_8);
        String identity = CandidateEvaluator.treeSha256(candidate);
        Files.writeString(source, "after", StandardCharsets.UTF_8);
        Path output = temporary.resolve("identity-output");
        FunctionalContract.Result functional =
                FunctionalContract.fromCorpus(corpus()).evaluate(JSON.createObjectNode());

        assertThrows(IllegalStateException.class,
                () -> CandidateEvaluator.publishAfterIdentityCheck(
                        candidate, identity, output, EvaluationRecord.forTesting(functional)));
        assertFalse(Files.exists(output));
    }

    private static JsonNode corpus() throws IOException {
        return JSON.readTree(Path.of(System.getProperty("palisade.corpus")).resolve("spec.json").toFile());
    }

    private static ObjectNode conformingFixture(JsonNode corpus) {
        ObjectNode fixture = JSON.createObjectNode();
        ObjectNode checkpoints = fixture.putObject("checkpoints");
        JsonNode initialState = findById(corpus.path("states"), "initial");
        JsonNode bottomState = findById(corpus.path("states"), "bottom");
        JsonNode confirmationState = findById(corpus.path("states"), "confirmation");

        ObjectNode initial = checkpoints.putObject("initial");
        ArrayNode controlOrder = initial.putArray("controlOrder");
        corpus.path("controls").forEach(control -> controlOrder.add(control.path("id").asText()));
        initial.set("controls", corpus.path("controls").deepCopy());
        initial.set("values", initialState.path("values").deepCopy());
        initial.set("visibleControls", initialState.path("visibleControls").deepCopy());
        initial.put("scrollPosition", "top");
        ArrayNode focusOrder = initial.putArray("focusOrder");
        initialState.path("visibleControls").forEach(focusOrder::add);

        ObjectNode bottom = checkpoints.putObject("bottom");
        bottom.set("values", bottomState.path("values").deepCopy());
        bottom.set("visibleControls", bottomState.path("visibleControls").deepCopy());
        bottom.put("scrollPosition", "bottom");

        ObjectNode conditionalVisible = checkpoints.putObject("conditionalVisible");
        conditionalVisible.set("values", initialState.path("values").deepCopy());
        conditionalVisible.withObject("values").put("victoryCondition", "rival-target").put("rivalTargetCount", 2);
        ArrayNode conditionalControls = conditionalVisible.putArray("visibleControls");
        initialState.path("visibleControls").forEach(conditionalControls::add);
        conditionalControls.insert(6, "rivalTargetCount");
        conditionalVisible.put("focusedControlId", "rivalTargetCount");

        ObjectNode conditionalHidden = checkpoints.putObject("conditionalHidden");
        conditionalHidden.set("values", initialState.path("values").deepCopy());
        conditionalHidden.set("visibleControls", initialState.path("visibleControls").deepCopy());
        conditionalHidden.put("focusedControlId", "victoryCondition");

        checkpoints.putObject("minimumSeed").put("seed", 0L).put("valid", true);
        checkpoints.putObject("maximumSeed").put("seed", 4294967295L).put("valid", true);
        checkpoints.putObject("belowMinimumSeed").put("seedText", "-1").put("valid", false);
        checkpoints.putObject("aboveMaximumSeed").put("seedText", "4294967296").put("valid", false);
        checkpoints.putObject("invalidStart").put("started", false).put("valid", false);

        checkpoints.putObject("copySeed").put("seed", 305419896L).put("clipboardText", "305419896");
        checkpoints.putObject("randomSeed").put("previousSeed", 1L)
                .put("seed", 305419896L).put("valid", true);
        checkpoints.putObject("cancel").put("outcome", "dismissed").put("payloadDiscarded", true);
        checkpoints.putObject("escape").put("outcome", "dismissed").put("payloadDiscarded", true);
        ObjectNode confirmation = checkpoints.putObject("confirmation");
        confirmation.put("outcome", "confirmation");
        confirmation.set("payload", confirmationState.path("payload").deepCopy());
        return fixture;
    }

    private static JsonNode findById(JsonNode array, String id) {
        for (JsonNode node : array) {
            if (id.equals(node.path("id").asText())) {
                return node;
            }
        }
        throw new IllegalArgumentException("Missing corpus id " + id);
    }
}
