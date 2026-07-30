package benchmark.palisade.eval;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
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
    void publicStateActionFixturePassesAllTwentyFiveGroupsWithoutAliases() throws Exception {
        JsonNode corpus = corpus();
        FunctionalContract contract = FunctionalContract.fromCorpus(corpus);

        FunctionalContract.Result result =
                contract.evaluatePublicContract(publicContractFixture(corpus));

        assertEquals(25, result.assertions().size());
        assertEquals(25, result.passedCount(), () -> result.assertions().toString());
    }

    @Test
    void publicContractMissingFieldUnknownMajorAndDuplicateIdFailClosed() throws Exception {
        JsonNode corpus = corpus();
        ObjectNode missing = publicContractFixture(corpus);
        ((ObjectNode) missing.path("scenarios").path("initial")).remove("focusOrder");
        PublicStateActionContract.ContractDiagnosticException missingFailure = assertThrows(
                PublicStateActionContract.ContractDiagnosticException.class,
                () -> PublicStateActionContract.toFunctionalEvidence(missing));
        assertEquals("$.scenarios.initial.focusOrder", missingFailure.path());
        assertEquals("required field", missingFailure.expected());
        assertTrue(missingFailure.affectedAssertions().contains("controls.focus-order"));
        assertTrue(missingFailure.affectedAssertions().contains("state.initial.values"));

        ObjectNode unknown = publicContractFixture(corpus);
        ((ObjectNode) unknown.path("scenarios").path("initial"))
                .put("schemaVersion", "state-action/v2.0");
        assertEquals("$.scenarios.initial.schemaVersion", assertThrows(
                PublicStateActionContract.ContractDiagnosticException.class,
                () -> PublicStateActionContract.toFunctionalEvidence(unknown)).path());

        ObjectNode duplicate = publicContractFixture(corpus);
        ArrayNode controls = (ArrayNode) duplicate.path("scenarios")
                .path("initial").path("controls");
        controls.add(controls.get(0).deepCopy());
        assertTrue(assertThrows(
                PublicStateActionContract.ContractDiagnosticException.class,
                () -> PublicStateActionContract.toFunctionalEvidence(duplicate))
                .path().endsWith(".controls[19].id"));
    }

    @Test
    void publicEvaluationDistinguishesCompatibilityExecutionAndAssertionFailures()
            throws Exception {
        JsonNode corpus = corpus();
        FunctionalContract contract = FunctionalContract.fromCorpus(corpus);

        ObjectNode incompatible = publicContractFixture(corpus);
        ((ObjectNode) incompatible.path("scenarios").path("initial"))
                .put("schemaVersion", "state-action/v2.0");
        assertEquals(FunctionalContract.PublicStatus.CONTRACT_INCOMPATIBLE,
                contract.evaluatePublicContractOutcome(incompatible).status());

        ObjectNode unexecuted = publicContractFixture(corpus);
        ((ObjectNode) unexecuted.path("scenarios")).remove("escape");
        assertEquals(FunctionalContract.PublicStatus.SCENARIO_UNEXECUTED,
                contract.evaluatePublicContractOutcome(unexecuted).status());

        ObjectNode failed = publicContractFixture(corpus);
        ((ObjectNode) failed.path("scenarios").path("initial")
                .path("controls").path(0).path("currentValue"))
                .put("textValue", "unexpected");
        assertEquals(FunctionalContract.PublicStatus.ASSERTION_FAILED,
                contract.evaluatePublicContractOutcome(failed).status());

        assertEquals(FunctionalContract.PublicStatus.PASSED,
                contract.evaluatePublicContractOutcome(publicContractFixture(corpus)).status());
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
            assertEquals(Set.of("evaluation.json", "evaluation.sha256"), files.map(path -> path.getFileName().toString()).collect(java.util.stream.Collectors.toSet()));
        }
        JsonNode published = JSON.readTree(output.resolve("evaluation.json").toFile());
        assertEquals("agentic-palisade-evaluation/v1", published.path("schemaVersion").asText());
        assertEquals("candidate-fixture", published.path("candidate").path("id").asText());
        assertEquals(before, published.path("candidate").path("sha256").asText());
    }

    @Test
    void completePublicationCopiesEveryHashBoundArtifactBeforeWorkspaceDeletion()
            throws Exception {
        Path candidate = temporary.resolve("artifact-candidate");
        Files.createDirectories(candidate);
        Files.writeString(candidate.resolve("source.txt"), "immutable", StandardCharsets.UTF_8);
        String identity = CandidateEvaluator.treeSha256(candidate);
        Path workspace = temporary.resolve("artifact-workspace");
        Path source = workspace.resolve(
                "evidence-1920/captures/initial-1920x1080-0.png");
        Files.createDirectories(source.getParent());
        byte[] content = "capture-bytes".getBytes(StandardCharsets.UTF_8);
        Files.write(source, content);
        String digest = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(content));
        FunctionalContract.Result functional =
                FunctionalContract.fromCorpus(corpus()).evaluate(JSON.createObjectNode());
        EvaluationRecord base = EvaluationRecord.forTesting(functional);
        EvaluationRecord record = new EvaluationRecord(
                base.schemaVersion(), base.status(),
                new EvaluationRecord.CandidateIdentity("fixture", identity),
                base.corpus(), base.functional(), base.visual(),
                base.structural(),
                List.of(new EvaluationRecord.Artifact(
                        "captures/initial-1920x1080-0.png", content.length, digest)),
                base.diagnostics());
        Path output = temporary.resolve("artifact-output");

        CandidateEvaluator.publishAfterIdentityCheck(
                candidate, identity, output, record, workspace);

        assertArrayEquals(content, Files.readAllBytes(
                output.resolve("captures/initial-1920x1080-0.png")));
        assertTrue(Files.isRegularFile(output.resolve("evaluation.json")));
        String evaluationDigest = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                        Files.readAllBytes(output.resolve("evaluation.json"))));
        assertEquals(evaluationDigest + "  evaluation.json\n",
                Files.readString(output.resolve("evaluation.sha256")));
    }

    @Test
    void evaluatorUsesRunnerCompatibleTreeAndCandidateIdentities()
            throws Exception {
        Path corpus = temporary.resolve("identity-corpus");
        Files.createDirectories(corpus.resolve("schema"));
        Files.writeString(corpus.resolve("schema/x"), "x", StandardCharsets.UTF_8);
        Files.writeString(corpus.resolve("spec.json"), "{}", StandardCharsets.UTF_8);
        assertEquals(
                "0f33016333440b164d0d1d6d69ef5227d716fdeed01b4262cc9f84db85bcc829",
                CandidateEvaluator.treeSha256(corpus));

        Path candidate = temporary.resolve("identity-overlay");
        Path source = candidate.resolve("src/main/java/example/A.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class A {}\n", StandardCharsets.UTF_8);
        Files.writeString(candidate.resolve("INSTRUCTIONS.md"), "neutral", StandardCharsets.UTF_8);
        Files.createDirectories(candidate.resolve("corpus"));
        Files.writeString(candidate.resolve("corpus/spec.json"), "neutral", StandardCharsets.UTF_8);
        Files.createDirectories(candidate.resolve("build"));
        Files.writeString(candidate.resolve("build/generated.bin"), "generated", StandardCharsets.UTF_8);
        assertEquals(
                "f9b9eaa43413a932b58d1e5a389fb6f230a1ab5d0aa14f8ff491274245d31487",
                CandidateEvaluator.candidateSha256(candidate));
    }

    @Test
    void forgedBuildAndNeutralControlAreRejectedWithoutExecution() throws Exception {
        Path candidate = temporary.resolve("forged-candidate");
        Path reserved = candidate.resolve(
                "src/main/java/benchmark/palisade/CandidateLauncher.java");
        Files.createDirectories(reserved.getParent());
        Path marker = temporary.resolve("forged-build-executed");
        Files.writeString(candidate.resolve("build.gradle.kts"),
                "file(\"" + marker.toString().replace("\\", "\\\\")
                        + "\").writeText(\"executed\")\n",
                StandardCharsets.UTF_8);
        Files.writeString(reserved,
                "package benchmark.palisade; public final class CandidateLauncher {}",
                StandardCharsets.UTF_8);
        Path output = temporary.resolve("forged-output");

        EvaluationRecord record = CandidateEvaluator.evaluate(
                new CandidateEvaluator.Request(
                        candidate,
                        Path.of(System.getProperty("palisade.corpus")),
                        output, "forged-candidate",
                        Path.of(System.getProperty("palisade.rootGradle"))));

        assertEquals("invalid-candidate", record.status());
        assertEquals(0, record.functional().passed());
        assertFalse(Files.exists(marker));
        assertTrue(Files.isRegularFile(output.resolve("evaluation.json")));
    }

    @Test
    void focusNavigationUsesThePublicStateActionContractRatherThanCandidateAliases()
            throws Exception {
        JsonNode initial = findById(corpus().path("states"), "initial");
        ObjectNode state = JSON.createObjectNode();
        state.putArray("visibleControls").add("candidateAlias");
        ArrayNode focusOrder = state.putObject("stateAction").putArray("focusOrder");
        for (JsonNode identifier : initial.path("visibleControls")) {
            focusOrder.add(identifier.textValue());
        }

        assertEquals(6, CandidateEvaluator.tabCountTo(
                state, "victoryCondition"));
        assertEquals(14, CandidateEvaluator.tabCountTo(state, "seed"));
        assertEquals(15, CandidateEvaluator.tabCountTo(state, "copySeed"));
        assertEquals(16, CandidateEvaluator.tabCountTo(state, "randomSeed"));
        assertEquals(17, CandidateEvaluator.tabCountTo(state, "cancel"));
        assertEquals(18, CandidateEvaluator.tabCountTo(state, "startBattle"));
        assertThrows(IllegalArgumentException.class,
                () -> CandidateEvaluator.tabCountTo(
                        state, "rivalTargetCount"));
        assertThrows(IllegalArgumentException.class,
                () -> CandidateEvaluator.tabCountTo(
                        JSON.createObjectNode(), "seed"));
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
    void evaluatorRequiresExactBrokerProtocolAmendmentBeforeCandidateInputs() throws Exception {
        Path valid = temporary.resolve("valid-manifest.json");
        Files.writeString(valid,
                "{\"schemaVersion\":\"agentic-palisade/benchmark-manifest-v1\","
                + "\"protocolAmendment\":\"agentic-palisade/task-8-auth-broker-amendment-v1\"}");
        CandidateEvaluator.validateBenchmarkManifest(valid);

        for (String json : List.of(
                "{\"schemaVersion\":\"agentic-palisade/benchmark-manifest-v1\"}",
                "{\"schemaVersion\":\"agentic-palisade/benchmark-manifest-v1\","
                + "\"protocolAmendment\":\"agentic-palisade/wrong\"}")) {
            Path invalid = temporary.resolve("invalid-" + Integer.toUnsignedString(json.hashCode()) + ".json");
            Files.writeString(invalid, json);
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> CandidateEvaluator.main(new String[] {
                        "evaluate",
                        "--benchmark-manifest", invalid.toString(),
                        "--candidate", temporary.resolve("missing-candidate").toString(),
                        "--corpus", temporary.resolve("missing-corpus").toString(),
                        "--output", temporary.resolve("output").toString(),
                        "--candidate-id", "candidate-one"
                    }));
            assertTrue(failure.getMessage().contains("protocol amendment"));
        }
    }

    @Test
    void evaluateCliAcceptsItsDocumentedFiveOptionPairs() throws Exception {
        Path manifest = temporary.resolve("benchmark-manifest.json");
        Files.writeString(manifest,
                "{\"schemaVersion\":\"agentic-palisade/benchmark-manifest-v1\","
                + "\"protocolAmendment\":\"agentic-palisade/task-8-auth-broker-amendment-v1\"}");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> CandidateEvaluator.main(new String[] {
                    "evaluate",
                    "--benchmark-manifest", manifest.toString(),
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

    private static ObjectNode publicContractFixture(JsonNode corpus) {
        ObjectNode legacy = conformingFixture(corpus);
        ObjectNode suite = JSON.createObjectNode();
        suite.put("schemaVersion", "state-action-suite/v1");
        ObjectNode scenarios = suite.putObject("scenarios");
        legacy.path("checkpoints").properties().forEach(entry ->
                scenarios.set(entry.getKey(),
                        publicScenario(corpus, entry.getKey(), entry.getValue())));
        return suite;
    }

    private static ObjectNode publicScenario(
            JsonNode corpus, String name, JsonNode checkpoint) {
        JsonNode initial = findById(corpus.path("states"), "initial");
        JsonNode values = checkpoint.path("values").isObject()
                ? checkpoint.path("values") : initial.path("values");
        JsonNode visible = checkpoint.path("visibleControls").isArray()
                ? checkpoint.path("visibleControls") : initial.path("visibleControls");
        ObjectNode contract = JSON.createObjectNode();
        contract.put("schemaVersion", "state-action/v1.0");
        contract.put("stateId", "fixture-" + name);
        contract.put("revision", 1);
        contract.put("frame", 1);
        ArrayNode controls = contract.putArray("controls");
        int index = 0;
        for (JsonNode definition : corpus.path("controls")) {
            String id = definition.path("id").asText();
            ObjectNode control = controls.addObject();
            control.put("id", id);
            control.put("role", roleFor(definition.path("kind").asText()));
            control.put("kind", definition.path("kind").asText());
            control.put("accessibleName", definition.path("label").asText());
            ArrayNode options = control.putArray("options");
            definition.path("options").forEach(option -> {
                ObjectNode item = options.addObject();
                item.set("value", typed(option.path("value")));
                item.put("label", option.path("label").asText());
            });
            control.set("defaultValue", typed(definition.path("default")));
            JsonNode current = values.path(id);
            if ("seed".equals(id)) {
                if (checkpoint.has("seed")) {
                    current = checkpoint.path("seed");
                } else if (checkpoint.has("seedText")) {
                    current = checkpoint.path("seedText");
                }
            }
            control.set("currentValue", typed(current));
            boolean isVisible = contains(visible, id);
            control.put("visible", isVisible);
            control.put("enabled", true);
            control.put("actionable", isVisible);
            control.put("focusable", isVisible);
            control.put("focused", id.equals(checkpoint.path("focusedControlId").asText()));
            ObjectNode rule = control.putObject("validationRule");
            rule.put("format", definition.path("validation").path("format").asText());
            addOptionalTyped(rule, "minimum",
                    definition.path("validation").get("minimum"));
            addOptionalTyped(rule, "maximum",
                    definition.path("validation").get("maximum"));
            addOptionalTyped(rule, "step", definition.path("validation").get("step"));
            ObjectNode status = control.putObject("validationStatus");
            boolean valid = !"seed".equals(id)
                    || !checkpoint.has("valid") || checkpoint.path("valid").asBoolean();
            status.put("valid", valid);
            ArrayNode messages = status.putArray("messages");
            if (!valid) {
                messages.add("Seed must be an unsigned 32-bit decimal integer");
            }
            index++;
        }
        JsonNode focus = checkpoint.path("focusOrder").isArray()
                ? checkpoint.path("focusOrder") : visible;
        contract.set("focusOrder", focus.deepCopy());
        if (checkpoint.has("focusedControlId")) {
            contract.set("focusedControlId",
                    checkpoint.path("focusedControlId").deepCopy());
        }
        ArrayNode conditions = contract.putArray("conditions");
        for (JsonNode definition : corpus.path("controls")) {
            if (definition.path("visibleWhen").isObject()) {
                ObjectNode condition = conditions.addObject();
                condition.put("controllerId",
                        definition.path("visibleWhen").path("controlId").asText());
                condition.set("equalsValue",
                        typed(definition.path("visibleWhen").path("equals")));
                condition.put("dependentId", definition.path("id").asText());
                condition.put("visibleWhenEqual", true);
                condition.put("actionableWhenEqual", true);
                condition.put("restoreFocusTo",
                        definition.path("visibleWhen").path("controlId").asText());
            }
        }
        ObjectNode viewport = contract.putArray("viewports").addObject();
        viewport.put("id", "configuration");
        viewport.put("width", 1920);
        viewport.put("height", 1080);
        boolean bottom = "bottom".equals(checkpoint.path("scrollPosition").asText());
        viewport.put("scrollX", 0);
        viewport.put("scrollY", bottom ? 1 : 0);
        viewport.put("maxScrollX", 0);
        viewport.put("maxScrollY", 1);
        viewport.set("visibleControlIds", visible.deepCopy());
        addPublicTransition(contract, name, checkpoint);
        return contract;
    }

    private static void addPublicTransition(
            ObjectNode contract, String name, JsonNode checkpoint) {
        String action = switch (name) {
            case "invalidStart" -> "start-battle";
            case "copySeed" -> "copy-seed";
            case "randomSeed" -> "random-seed";
            case "cancel" -> "cancel";
            case "escape" -> "escape";
            case "confirmation" -> "start-battle";
            default -> null;
        };
        if (action == null) {
            return;
        }
        ObjectNode transition = contract.putObject("transition");
        boolean accepted = !"invalidStart".equals(name);
        transition.put("actionId", action);
        transition.put("accepted", accepted);
        if (!accepted) {
            transition.put("rejectionReason", "validation-failed");
        }
        transition.put("resultingStateId", contract.path("stateId").asText());
        transition.put("resultingRevision", contract.path("revision").asLong());
        ObjectNode validation = transition.putObject("validation");
        validation.put("valid", accepted);
        ArrayNode messages = validation.putArray("messages");
        if (!accepted) {
            messages.add("Seed must be an unsigned 32-bit decimal integer");
        }
        String kind = switch (name) {
            case "cancel", "escape" -> "dismissed";
            case "confirmation" -> "confirmation";
            default -> "none";
        };
        transition.put("kind", kind);
        if ("copySeed".equals(name)) {
            transition.put("clipboardText", checkpoint.path("clipboardText").asText());
        }
        ObjectNode payload = transition.putObject("acceptedPayload");
        if ("randomSeed".equals(name)) {
            payload.set("previousSeed", typed(checkpoint.path("previousSeed")));
            payload.set("seed", typed(checkpoint.path("seed")));
        } else if ("confirmation".equals(name)) {
            checkpoint.path("payload").properties().forEach(
                    entry -> payload.set(entry.getKey(), typed(entry.getValue())));
        }
    }

    private static void addOptionalTyped(
            ObjectNode destination, String name, JsonNode value) {
        if (value != null && !value.isNull()) {
            destination.set(name, typed(value));
        }
    }

    private static ObjectNode typed(JsonNode value) {
        ObjectNode typed = JSON.createObjectNode();
        if (value == null || value.isMissingNode() || value.isNull()) {
            return typed.put("type", "null");
        }
        if (value.isBoolean()) {
            return typed.put("type", "boolean")
                    .put("booleanValue", value.booleanValue());
        }
        if (value.isIntegralNumber()) {
            return typed.put("type", "integer")
                    .put("integerValue", value.longValue());
        }
        if (value.isNumber()) {
            return typed.put("type", "decimal")
                    .put("decimalValue", value.decimalValue().toPlainString());
        }
        return typed.put("type", "text").put("textValue", value.asText());
    }

    private static String roleFor(String kind) {
        return switch (kind) {
            case "button" -> "button";
            case "checkbox" -> "checkbox";
            case "select" -> "select";
            case "range" -> "slider";
            default -> "text-field";
        };
    }

    private static boolean contains(JsonNode array, String value) {
        for (JsonNode item : array) {
            if (value.equals(item.asText())) {
                return true;
            }
        }
        return false;
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
