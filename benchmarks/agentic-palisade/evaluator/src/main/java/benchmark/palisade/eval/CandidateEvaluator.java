package benchmark.palisade.eval;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Standalone hidden evaluator; it communicates with candidates only through CandidateLauncher files. */
public final class CandidateEvaluator {
    private static final Pattern CANDIDATE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");
    private static final int MAX_TREE_FILES = 20_000;
    private static final long MAX_TREE_BYTES = 512L * 1024L * 1024L;
    private static final long MAX_RESULT_BYTES = 16L * 1024L * 1024L;
    private static final String BENCHMARK_MANIFEST_VERSION =
            "agentic-palisade/benchmark-manifest-v1";
    private static final String PROTOCOL_AMENDMENT =
            "agentic-palisade/task-8-auth-broker-amendment-v1";
    private static final int MAX_RESULT_LINES = 512;
    private static final Duration PROCESS_TIMEOUT = Duration.ofMinutes(3);
    private static final Set<String> GENERATED_NAMES =
            Set.of(".gradle", "build", "__pycache__");
    private static final Set<String> CANDIDATE_INPUT_NAMES =
            Set.of("INSTRUCTIONS.md", "PROTOCOL.md", "corpus");
    private static final Map<String, String> TRUSTED_TEMPLATE_FILES = Map.ofEntries(
            Map.entry("build.gradle.kts", "537819f6baf1d593a8298a439b65eea2080bc5d2633573d178d669bb277f6ac6"),
            Map.entry("settings.gradle.kts", "2098bf5d2904d7d13b7a4e5e9d1b309800b63e5002e990f44bac0299a2693301"),
            Map.entry("src/main/java/benchmark/palisade/CandidateLauncher.java", "aa8bf5d629899646f770b6e395b877879e72111baaf6305239fda71348bc8024"),
            Map.entry("src/main/java/benchmark/palisade/BenchmarkControl.java", "5c33f4fbf41a53e2986d962dfe40adac599dcb4c948688e24e771b89caa0db68"),
            Map.entry("src/main/java/benchmark/palisade/CandidateApplication.java", "8d20fb4f2b11de529ce30ae9f846abb45ce1e937fe0dd14563c7490de5625725"),
            Map.entry("src/main/java/benchmark/palisade/CandidateState.java", "e35bad6e32e78f109012db94bc0a0293fdc477c39c003d0eeeadea215023fddc"),
            Map.entry("src/main/java/benchmark/palisade/CandidateUi.java", "fe93166097d0a357b85452a09f91fd7c2690e8d4f7d6648bc426456741c10eb7"));
    private static final Pattern RESERVED_TYPE_DECLARATION = Pattern.compile(
            "\\b(?:class|record|interface|enum)\\s+(?:CandidateLauncher|BenchmarkControl|CandidateApplication|CandidateState|CandidateUi)\\b");
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(32).maxStringLength(65_536).maxNumberLength(128)
                    .maxDocumentLength(MAX_RESULT_BYTES).build()).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private CandidateEvaluator() {
    }

    /** Evaluates a candidate copy and publishes exactly one atomic evaluation.json. */
    public static EvaluationRecord evaluate(Request request) throws IOException, InterruptedException {
        Objects.requireNonNull(request, "request");
        validateCorpusLayout(request.corpusDirectory());
        JsonNode corpus = readJson(request.corpusDirectory().resolve("spec.json"), 1_048_576L);
        FunctionalContract contract = FunctionalContract.fromCorpus(corpus);
        validateReferences(request.corpusDirectory(), corpus);
        String candidateHash = candidateSha256(request.candidateDirectory());
        String corpusHash = treeSha256(request.corpusDirectory());
        var candidateIdentity = new EvaluationRecord.CandidateIdentity(request.candidateId(), candidateHash);
        var corpusIdentity = new EvaluationRecord.CorpusIdentity(corpus.path("schemaVersion").asText(), corpusHash);
        FunctionalContract.Result noEvidence = contract.evaluate(JSON.createObjectNode());

        Path workspace = Files.createTempDirectory("palisade-candidate-");
        try {
            Path candidateCopy = workspace.resolve("candidate");
            try {
                prepareTrustedWorkspace(
                        request.candidateDirectory(),
                        request.corpusDirectory().getParent().resolve("template"),
                        candidateCopy);
            } catch (CandidateRejected rejected) {
                EvaluationRecord failed = record(
                        "invalid-candidate", candidateIdentity, corpusIdentity,
                        noEvidence, List.of(), List.of(), List.of(),
                        List.of(boundDiagnostic(rejected)));
                publishAfterIdentityCheck(
                        request.candidateDirectory(), candidateHash,
                        request.outputDirectory(), failed);
                return failed;
            }
            ProcessResult compilation = runProcess(List.of(request.gradleExecutable().toString(), "-p",
                    candidateCopy.toString(), "classes", "--no-daemon", "--console=plain"), workspace);
            if (compilation.exitCode() != 0) {
                EvaluationRecord failed = record("compile-failed", candidateIdentity, corpusIdentity,
                        noEvidence, List.of(), List.of(),
                        List.of(),
                        List.of("candidate compilation failed; exit=" + compilation.exitCode()));
                publishAfterIdentityCheck(
                        request.candidateDirectory(), candidateHash, request.outputDirectory(), failed);
                return failed;
            }
            try {
                String runtimeClasspath = resolveRuntimeClasspath(
                        request.gradleExecutable(), candidateCopy, workspace);
                EvaluationData data = evaluateLaunches(
                        request, candidateCopy, corpus, contract, workspace, runtimeClasspath);
                EvaluationRecord complete = record("complete", candidateIdentity, corpusIdentity,
                        data.functional(), data.visual(), data.structural(),
                        data.artifacts(), List.of());
                publishAfterIdentityCheck(
                        request.candidateDirectory(), candidateHash,
                        request.outputDirectory(), complete, workspace);
                return complete;
            } catch (RuntimeException | IOException launchFailure) {
                EvaluationRecord failed = record("runtime-failed", candidateIdentity, corpusIdentity,
                        noEvidence, List.of(), List.of(), List.of(),
                        List.of(boundDiagnostic(launchFailure)));
                publishAfterIdentityCheck(
                        request.candidateDirectory(), candidateHash, request.outputDirectory(), failed);
                return failed;
            }
        } finally {
            deleteTree(workspace);
        }
    }

    /** CLI: evaluate, or emit the fixed candidate-visible benchmark-feedback projection. */
    public static void main(String[] args) throws Exception {
        if (args.length == 2 && PublicFeedback.COMMAND.equals(args[0])) {
            JsonNode root = readJson(Path.of(args[1]), MAX_RESULT_BYTES);
            PublicFeedback.validateEvaluationJson(root);
            System.out.println(PublicFeedback.toJson(JSON.treeToValue(root, EvaluationRecord.class)));
            return;
        }
        if (args.length != 11 || !"evaluate".equals(args[0])) {
            throw new IllegalArgumentException(
                    "Expected evaluate --benchmark-manifest <file> --candidate <dir> "
                    + "--corpus <dir> --output <new-dir> --candidate-id <id>");
        }
        Map<String, String> options = parseOptions(args, 1);
        validateBenchmarkManifest(Path.of(required(options, "--benchmark-manifest")));
        String gradle = System.getenv().getOrDefault("PALISADE_GRADLE", "gradle");
        EvaluationRecord record = evaluate(new Request(Path.of(required(options, "--candidate")),
                Path.of(required(options, "--corpus")), Path.of(required(options, "--output")),
                required(options, "--candidate-id"), Path.of(gradle)));
        System.out.println(record.status());
    }

    private static EvaluationData evaluateLaunches(Request request, Path candidateCopy, JsonNode corpus,
            FunctionalContract contract, Path workspace, String runtimeClasspath)
            throws IOException, InterruptedException {
        Path evidence1920 = workspace.resolve("evidence-1920");
        List<ObjectNode> commands1920 = new ArrayList<>();
        commands1920.add(command("resize").put("width", 1920).put("height", 1080));
        addCaptures(commands1920, "initial-1920x1080");
        for (int index = 0; index < 24; index++) {
            commands1920.add(command("pointer").put("action", "scroll").put("amountX", 0).put("amountY", 100));
        }
        addCaptures(commands1920, "bottom-1920x1080");
        commands1920.add(command("close"));
        runLauncher(runtimeClasspath, candidateCopy, workspace, commands1920, evidence1920);
        validateEvidenceLayout(evidence1920, captureNames("initial-1920x1080", "bottom-1920x1080"));
        List<ResultLine> results1920 = readResults(
                evidence1920.resolve("results.ndjson"), commands1920);

        Path evidence1280 = workspace.resolve("evidence-1280");
        List<ObjectNode> commands1280 = new ArrayList<>();
        commands1280.add(command("resize").put("width", 1280).put("height", 720));
        addCaptures(commands1280, "initial-1280x720");
        commands1280.add(command("close"));
        runLauncher(runtimeClasspath, candidateCopy, workspace, commands1280, evidence1280);
        validateEvidenceLayout(evidence1280, captureNames("initial-1280x720"));
        List<ResultLine> results1280 = readResults(
                evidence1280.resolve("results.ndjson"), commands1280);

        ObjectNode initialState = stateForCapture(
                results1920, "captures/initial-1920x1080-0.png");
        ObjectNode bottomState = stateForCapture(
                results1920, "captures/bottom-1920x1080-0.png");
        ObjectNode initial1280State = stateForCapture(
                results1280, "captures/initial-1280x720-0.png");
        List<EvaluationRecord.Artifact> artifacts = new ArrayList<>();
        ObjectNode functionalEvidence = runFunctionalScenarios(
                runtimeClasspath, candidateCopy, workspace, initialState);
        addFunctionalArtifacts(workspace, artifacts);
        ObjectNode checkpoints = functionalEvidence.withObject("checkpoints");
        copyObservedCheckpoint(checkpoints, "bottom", bottomState);
        FunctionalContract.Result functional = contract.evaluate(functionalEvidence);

        List<EvaluationRecord.VisualOutcome> visual = new ArrayList<>();
        List<StructuralUsability.Result> structural = new ArrayList<>();
        for (JsonNode reference : corpus.path("references")) {
            String referenceId = reference.path("id").asText();
            Path evidence = referenceId.endsWith("1280x720") ? evidence1280 : evidence1920;
            ObjectNode observedState = referenceId.equals("bottom-1920x1080")
                    ? bottomState
                    : referenceId.endsWith("1280x720") ? initial1280State : initialState;
            List<Path> captures = capturePaths(evidence, referenceId);
            Path referencePath = request.corpusDirectory().resolve(reference.path("file").asText()).normalize();
            VisualMetrics.Result metrics =
                    VisualMetrics.compare(referencePath, captures);
            List<String> captureHashes = new ArrayList<>();
            for (Path capture : captures) {
                String hash = fileSha256(capture);
                captureHashes.add(hash);
                artifacts.add(new EvaluationRecord.Artifact("captures/" + capture.getFileName(),
                        Files.size(capture), hash));
            }
            visual.add(new EvaluationRecord.VisualOutcome(
                    referenceId, reference.path("viewportId").asText(),
                    reference.path("sha256").asText(), captureHashes, metrics));
            structural.add(structuralOutcome(
                    reference,
                    statesForCaptures(
                            referenceId.endsWith("1280x720") ? results1280 : results1920,
                            referenceId),
                    captureHashes));
        }
        artifacts.add(artifact("evidence/1920/results.ndjson", evidence1920.resolve("results.ndjson")));
        artifacts.add(artifact("evidence/1280/results.ndjson", evidence1280.resolve("results.ndjson")));
        return new EvaluationData(functional, visual, structural, artifacts);
    }

    private static StructuralUsability.Result structuralOutcome(
            JsonNode reference, List<ObjectNode> observedStates, List<String> captureHashes) {
        String referenceId = reference.path("id").asText();
        String expectedState = reference.path("stateId").asText();
        String observedScroll = observedStates.getFirst().path("scrollPosition").asText("");
        String observedStateId = "bottom".equals(observedScroll) ? "bottom" : "initial";
        int width = reference.path("width").asInt();
        int height = reference.path("height").asInt();
        StructuralUsability.Rect panel = structuralPanel(referenceId);
        StructuralUsability.Policy policy = new StructuralUsability.Policy(
                "agentic-palisade-structural/v1",
                1,
                "structural-usability-evaluator/v1",
                StructuralUsability.implementationSha256(),
                reference.path("sha256").asText(),
                expectedState,
                reference.path("viewportId").asText(),
                width,
                height,
                1,
                12,
                0.5,
                4.5,
                34,
                34,
                "bottom".equals(expectedState) ? "costlyCavalry" : "majorRivalCount",
                panel,
                structuralControl(referenceId),
                "form-row",
                "form",
                "scroll",
                "scroll",
                structuralControl(referenceId));
        List<StructuralUsability.Observation> observations = new ArrayList<>();
        for (ObjectNode state : observedStates) {
            JsonNode observation = state.path("structuralUsability");
            if (observation.isObject()) {
                try {
                    observations.add(JSON.treeToValue(
                            observation, StructuralUsability.Observation.class));
                } catch (JsonProcessingException invalid) {
                    throw new IllegalArgumentException(
                            "Invalid structural usability observation", invalid);
                }
            }
        }
        if (observations.size() == observedStates.size()) {
            StructuralUsability.Observation first = observations.getFirst();
            List<StructuralUsability.FrameEvidence> boundFrames = new ArrayList<>();
            for (int index = 0; index < observations.size(); index++) {
                StructuralUsability.Observation observation = observations.get(index);
                boundFrames.add(new StructuralUsability.FrameEvidence(
                        index,
                        observation.semanticRevision(),
                        observation.layoutRevision(),
                        observation.scrollY(),
                        observation.semanticSha256(),
                        observation.layoutSha256(),
                        observation.regionSha256(),
                        captureHashes.get(index)));
            }
            StructuralUsability.Evidence bound = new StructuralUsability.Evidence(
                    "structural-usability/v1",
                    policy.evaluatorId(),
                    policy.evaluatorSha256(),
                    policy.referenceSha256(),
                    captureHashes.getFirst(),
                    observedStateId,
                    policy.viewportId(),
                    width,
                    height,
                    1,
                    first.semanticRevision(),
                    first.layoutRevision(),
                    first.frameEdgeClipped(),
                    first.panelBounds(),
                    first.controls(),
                    boundFrames);
            return StructuralUsability.evaluate(policy, bound, null);
        }
        List<StructuralUsability.FrameEvidence> frames = new ArrayList<>();
        for (int index = 0; index < captureHashes.size(); index++) {
            frames.add(new StructuralUsability.FrameEvidence(
                    index,
                    0,
                    0,
                    "bottom".equals(observedScroll) ? 1 : 0,
                    "0".repeat(64),
                    "0".repeat(64),
                    "0".repeat(64),
                    captureHashes.get(index)));
        }
        StructuralUsability.Evidence evidence = new StructuralUsability.Evidence(
                "structural-usability/v1",
                policy.evaluatorId(),
                policy.evaluatorSha256(),
                policy.referenceSha256(),
                captureHashes.getFirst(),
                observedStateId,
                reference.path("viewportId").asText(),
                width,
                height,
                1,
                -1,
                -1,
                false,
                panel,
                List.of(),
                frames);
        return StructuralUsability.evaluate(policy, evidence, null);
    }

    private static StructuralUsability.Rect structuralPanel(String referenceId) {
        int width = referenceId.endsWith("1280x720") ? 1280 : 1920;
        int height = referenceId.endsWith("1280x720") ? 720 : 1080;
        return new StructuralUsability.Rect((width - 680) / 2.0, 24, 680, height - 48);
    }

    private static StructuralUsability.Rect structuralControl(String referenceId) {
        double x = structuralPanel(referenceId).x() + 455;
        double y = referenceId.startsWith("bottom-") ? 121 : 229;
        return new StructuralUsability.Rect(x, y, 180, 34);
    }

    private static List<ObjectNode> statesForCaptures(
            List<ResultLine> lines, String referenceId) {
        List<ObjectNode> states = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            states.add(stateForCapture(
                    lines, "captures/" + referenceId + "-" + index + ".png"));
        }
        return List.copyOf(states);
    }

    private static String resolveRuntimeClasspath(
            Path gradle, Path candidateCopy, Path workspace)
            throws IOException, InterruptedException {
        Path initScript = workspace.resolve("runtime-classpath.init.gradle");
        Path output = workspace.resolve("runtime-classpath.txt");
        String script = """
                gradle.projectsEvaluated {
                    rootProject.tasks.register("palisadeRuntimeClasspath") {
                        doLast {
                            def sets = rootProject.extensions.getByType(
                                org.gradle.api.tasks.SourceSetContainer)
                            file(System.getProperty("palisade.classpath.output")).text =
                                sets.getByName("main").runtimeClasspath.asPath
                        }
                    }
                }
                """;
        Files.writeString(initScript, script, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        ProcessResult result = runProcess(List.of(
                gradle.toString(), "-p", candidateCopy.toString(), "-I", initScript.toString(),
                "-Dpalisade.classpath.output=" + output,
                "palisadeRuntimeClasspath", "--no-daemon", "--console=plain"), workspace);
        if (result.exitCode() != 0 || !Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS)
                || Files.size(output) > 1_048_576L) {
            throw new IllegalArgumentException("Could not resolve candidate runtime classpath");
        }
        return validateRuntimeClasspath(
                Files.readString(output, StandardCharsets.UTF_8), candidateCopy);
    }

    static String validateRuntimeClasspath(String classpath, Path candidateCopy) {
        if (classpath.isBlank() || classpath.indexOf('\n') >= 0 || classpath.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Invalid candidate runtime classpath");
        }
        Path classes = candidateCopy.resolve("build/classes/java/main")
                .toAbsolutePath().normalize();
        Path candidateBuild = candidateCopy.resolve("build").toAbsolutePath().normalize();
        boolean hasCandidateClasses = false;
        for (String entry : classpath.split(
                Pattern.quote(java.io.File.pathSeparator), -1)) {
            Path path = Path.of(entry).toAbsolutePath().normalize();
            boolean missingCandidateOutput =
                    !Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                    && path.startsWith(candidateBuild);
            if (entry.isBlank()
                    || (!missingCandidateOutput
                            && !Files.exists(path, LinkOption.NOFOLLOW_LINKS))
                    || Files.isSymbolicLink(path)) {
                throw new IllegalArgumentException(
                        "Invalid candidate runtime classpath entry");
            }
            if (path.equals(classes)
                    && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                hasCandidateClasses = true;
            }
        }
        if (!hasCandidateClasses) {
            throw new IllegalArgumentException(
                    "Candidate classes are absent from runtime classpath");
        }
        return classpath;
    }

    static List<String> launchCommand(
            Path java, String runtimeClasspath, Path commands, Path evidence) {
        List<String> command = new ArrayList<>();
        command.add(java.toString());
        command.add("--enable-native-access=ALL-UNNAMED");
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac")) {
            command.add("-XstartOnFirstThread");
        }
        command.add("-cp");
        command.add(runtimeClasspath);
        command.add("benchmark.palisade.CandidateLauncher");
        command.add("--commands");
        command.add(commands.toString());
        command.add("--evidence");
        command.add(evidence.toString());
        return List.copyOf(command);
    }

    private static Path javaExecutable() {
        String executable = System.getProperty("os.name").toLowerCase(Locale.ROOT)
                .contains("win") ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable)
                .toAbsolutePath().normalize();
        if (!Files.isRegularFile(java, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("Current Java executable is unavailable");
        }
        return java;
    }

    private static void runLauncher(String runtimeClasspath, Path candidateCopy, Path workspace,
            List<ObjectNode> commands, Path evidence) throws IOException, InterruptedException {
        Files.createDirectories(evidence);
        Path commandsFile = workspace.resolve("commands-" + evidence.getFileName() + ".ndjson");
        StringBuilder content = new StringBuilder();
        for (ObjectNode command : commands) {
            content.append(JSON.writeValueAsString(command)).append('\n');
        }
        Files.writeString(commandsFile, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        ProcessResult result = runProcess(
                launchCommand(javaExecutable(), runtimeClasspath, commandsFile, evidence), workspace);
        if (result.exitCode() != 0) {
            throw new IllegalStateException("Candidate launcher failed; exit=" + result.exitCode());
        }
    }

    private static ObjectNode runFunctionalScenarios(
            String runtimeClasspath, Path candidateCopy, Path workspace,
            ObjectNode initialState) throws IOException, InterruptedException {
        ObjectNode evidence = JSON.createObjectNode();
        ObjectNode checkpoints = evidence.putObject("checkpoints");
        int visibleCount;
        try {
            visibleCount = visibleControls(initialState).size();
        } catch (IllegalArgumentException missingObservation) {
            copyObservedCheckpoint(checkpoints, "initial", initialState);
            return evidence;
        }
        List<ObjectNode> focusCommands = tabs(visibleCount);
        List<ResultLine> focusResults = runFunctionalScenario(
                runtimeClasspath, candidateCopy, workspace, "focus", focusCommands);
        ObjectNode initial = initialState.deepCopy();
        ArrayNode observedFocusOrder = initial.putArray("focusOrder");
        ArrayNode observedControlOrder = initial.putArray("controlOrder");
        ArrayNode observedControls = initial.putArray("controls");
        for (ResultLine line : focusResults.subList(0, focusCommands.size())) {
            String focused = observableText(
                    line.state(), "focusedControlId", "focusId");
            if (focused != null) {
                observedFocusOrder.add(focused);
                observedControlOrder.add(focused);
                addFocusedControl(observedControls, line.state(), focused, -1);
            }
        }

        List<ObjectNode> conditional = new ArrayList<>();
        conditional.addAll(tabs(
                tabCountTo(initialState, "victoryCondition")));
        conditional.add(key("ENTER", false));
        conditional.add(key("DOWN", false));
        conditional.add(key("ENTER", false));
        int visibleIndex = conditional.size() - 1;
        conditional.add(key("TAB", false));
        conditional.add(key("TAB", true));
        conditional.add(key("ENTER", false));
        conditional.add(key("UP", false));
        conditional.add(key("ENTER", false));
        List<ResultLine> conditionalResults = runFunctionalScenario(
                runtimeClasspath, candidateCopy, workspace, "conditional", conditional);
        ObjectNode targetState = conditionalResults.get(visibleIndex + 1).state();
        String targetFocus = observableText(
                targetState, "focusedControlId", "focusId");
        if (targetFocus != null) {
            observedControlOrder.insert(6, targetFocus);
            addFocusedControl(observedControls, targetState, targetFocus, 6);
        }
        copyObservedCheckpoint(checkpoints, "initial", initial);
        copyObservedCheckpoint(checkpoints, "conditionalVisible", conditionalResults.get(visibleIndex).state());
        copyObservedCheckpoint(checkpoints, "conditionalHidden", conditionalResults.get(conditional.size() - 1).state());

        int seedTabs = tabCountTo(initialState, "seed");
        int seedToStart = tabDistance(
                initialState, "seed", "startBattle");
        copyObservedCheckpoint(checkpoints, "minimumSeed", runSeedScenario(
                runtimeClasspath, candidateCopy, workspace,
                "seed-minimum", "0", false, seedTabs, seedToStart));
        copyObservedCheckpoint(checkpoints, "maximumSeed", runSeedScenario(
                runtimeClasspath, candidateCopy, workspace,
                "seed-maximum", "4294967295", false,
                seedTabs, seedToStart));
        copyObservedCheckpoint(checkpoints, "belowMinimumSeed", runSeedScenario(
                runtimeClasspath, candidateCopy, workspace,
                "seed-below", "-1", false, seedTabs, seedToStart));
        copyObservedCheckpoint(checkpoints, "aboveMaximumSeed", runSeedScenario(
                runtimeClasspath, candidateCopy, workspace,
                "seed-above", "4294967296", false,
                seedTabs, seedToStart));
        copyObservedCheckpoint(checkpoints, "invalidStart", runSeedScenario(
                runtimeClasspath, candidateCopy, workspace,
                "invalid-start", "-1", true, seedTabs, seedToStart));

        copyObservedCheckpoint(checkpoints, "copySeed", runActionScenario(
                runtimeClasspath, candidateCopy, workspace, "copy-seed",
                tabCountTo(initialState, "copySeed")));
        copyObservedCheckpoint(checkpoints, "randomSeed", runRandomSeedScenario(
                runtimeClasspath, candidateCopy, workspace, seedTabs,
                tabDistance(initialState, "seed", "randomSeed")));
        copyObservedCheckpoint(checkpoints, "cancel", runActionScenario(
                runtimeClasspath, candidateCopy, workspace, "cancel",
                tabCountTo(initialState, "cancel")));
        copyObservedCheckpoint(checkpoints, "confirmation", runActionScenario(
                runtimeClasspath, candidateCopy, workspace, "start-battle",
                tabCountTo(initialState, "startBattle")));
        copyObservedCheckpoint(checkpoints, "escape", runEscapeScenario(
                runtimeClasspath, candidateCopy, workspace));
        return evidence;
    }

    private static void addFunctionalArtifacts(
            Path workspace, List<EvaluationRecord.Artifact> artifacts)
            throws IOException {
        for (String name : List.of(
                "focus", "conditional", "seed-minimum", "seed-maximum",
                "seed-below", "seed-above", "invalid-start", "copy-seed",
                "random-seed", "cancel", "start-battle", "escape")) {
            Path results = workspace.resolve("functional-" + name)
                    .resolve("results.ndjson");
            if (!Files.isRegularFile(results, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            artifacts.add(artifact(
                    "evidence/functional/" + name + "/results.ndjson", results));
        }
    }

    private static ObjectNode runSeedScenario(
            String runtimeClasspath, Path candidateCopy, Path workspace,
            String name, String seed, boolean activateStart,
            int seedTabs, int seedToStart)
            throws IOException, InterruptedException {
        List<ObjectNode> commands = new ArrayList<>(tabs(seedTabs));
        commands.add(key("A", false).put("control", true));
        for (int index = 0; index < seed.length(); index++) {
            commands.add(command("key").put("action", "type")
                    .put("character", String.valueOf(seed.charAt(index))));
        }
        if (activateStart) {
            commands.addAll(tabs(seedToStart));
            commands.add(key("ENTER", false));
        }
        List<ResultLine> results = runFunctionalScenario(
                runtimeClasspath, candidateCopy, workspace, name, commands);
        return results.get(commands.size() - 1).state();
    }

    private static ObjectNode runRandomSeedScenario(
            String runtimeClasspath, Path candidateCopy, Path workspace,
            int seedTabs, int seedToRandom)
            throws IOException, InterruptedException {
        List<ObjectNode> commands = new ArrayList<>(tabs(seedTabs));
        commands.add(key("A", false).put("control", true));
        commands.add(command("key").put("action", "type").put("character", "1"));
        int previousIndex = commands.size() - 1;
        commands.addAll(tabs(seedToRandom));
        commands.add(key("ENTER", false));
        List<ResultLine> results = runFunctionalScenario(
                runtimeClasspath, candidateCopy, workspace,
                "random-seed", commands);
        ObjectNode outcome = results.get(commands.size() - 1).state().deepCopy();
        JsonNode previousState = results.get(previousIndex).state();
        JsonNode previousSeed = previousState.path("seed");
        if (!previousSeed.isIntegralNumber()) {
            previousSeed = previousState.path("values").path("seed");
        }
        if (previousSeed.isIntegralNumber()) {
            outcome.put("previousSeed", previousSeed.longValue());
        }
        return outcome;
    }

    private static ObjectNode runActionScenario(String runtimeClasspath, Path candidateCopy, Path workspace,
            String name, int tabs) throws IOException, InterruptedException {
        List<ObjectNode> commands = new ArrayList<>(tabs(tabs));
        commands.add(key("ENTER", false));
        List<ResultLine> results = runFunctionalScenario(
                runtimeClasspath, candidateCopy, workspace, name, commands);
        return results.get(commands.size() - 1).state();
    }

    private static ObjectNode runEscapeScenario(String runtimeClasspath, Path candidateCopy, Path workspace)
            throws IOException, InterruptedException {
        List<ObjectNode> commands = List.of(key("ESCAPE", false));
        return runFunctionalScenario(runtimeClasspath, candidateCopy, workspace, "escape", commands)
                .get(0).state();
    }

    private static List<ResultLine> runFunctionalScenario(String runtimeClasspath, Path candidateCopy,
            Path workspace, String name, List<ObjectNode> actions)
            throws IOException, InterruptedException {
        List<ObjectNode> commands = new ArrayList<>(actions);
        commands.add(command("close"));
        Path scenarioEvidence = workspace.resolve("functional-" + name);
        runLauncher(runtimeClasspath, candidateCopy, workspace, commands, scenarioEvidence);
        validateEvidenceLayout(scenarioEvidence, Set.of());
        return readResults(
                scenarioEvidence.resolve("results.ndjson"), commands, false);
    }

    static int tabCountTo(JsonNode state, String target) {
        List<String> visible = visibleControls(state);
        int index = visible.indexOf(target);
        if (index < 0) {
            throw new IllegalArgumentException(
                    "Target control is not visible: " + target);
        }
        return index + 1;
    }

    private static int tabDistance(
            JsonNode state, String current, String target) {
        List<String> visible = visibleControls(state);
        int currentIndex = visible.indexOf(current);
        int targetIndex = visible.indexOf(target);
        if (currentIndex < 0 || targetIndex <= currentIndex) {
            throw new IllegalArgumentException(
                    "Invalid visible focus transition");
        }
        return targetIndex - currentIndex;
    }

    private static List<String> visibleControls(JsonNode state) {
        JsonNode observed = state.path("visibleControls");
        if (!observed.isArray() || observed.isEmpty()
                || observed.size() > 64) {
            throw new IllegalArgumentException(
                    "Missing bounded visibleControls observation");
        }
        List<String> visible = new ArrayList<>(observed.size());
        for (JsonNode value : observed) {
            String id = value.textValue();
            if (id == null || id.isBlank() || visible.contains(id)) {
                throw new IllegalArgumentException(
                        "Invalid visibleControls observation");
            }
            visible.add(id);
        }
        return List.copyOf(visible);
    }

    private static List<ObjectNode> tabs(int count) {
        List<ObjectNode> commands = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            commands.add(key("TAB", false));
        }
        return commands;
    }

    private static ObjectNode key(String key, boolean shift) {
        ObjectNode command = command("key").put("action", "press").put("key", key);
        if (shift) {
            command.put("shift", true);
        }
        return command;
    }

    private static String observableText(ObjectNode state, String primary, String alternate) {
        String value = state.path(primary).textValue();
        return value != null ? value : state.path(alternate).textValue();
    }

    private static void addFocusedControl(
            ArrayNode controls, ObjectNode state, String focused, int index) {
        JsonNode metadata = state.path("focusedControl");
        if (!metadata.isObject()
                || !focused.equals(metadata.path("id").textValue())) {
            return;
        }
        if (index < 0) {
            controls.add(metadata.deepCopy());
        } else {
            controls.insert(index, metadata.deepCopy());
        }
    }

    private static void addCaptures(List<ObjectNode> commands, String referenceId) {
        for (int index = 0; index < 5; index++) {
            commands.add(command("capture").put("id", referenceId + "-" + index));
        }
    }

    private static ObjectNode command(String name) {
        return JSON.createObjectNode().put("command", name);
    }

    private static Set<String> captureNames(String... referenceIds) {
        Set<String> names = new LinkedHashSet<>();
        for (String referenceId : referenceIds) {
            for (int index = 0; index < 5; index++) {
                names.add(referenceId + "-" + index + ".png");
            }
        }
        return names;
    }

    private static List<Path> capturePaths(Path evidence, String referenceId) {
        List<Path> paths = new ArrayList<>(5);
        for (int index = 0; index < 5; index++) {
            paths.add(evidence.resolve("captures").resolve(referenceId + "-" + index + ".png"));
        }
        return paths;
    }

    private static List<ResultLine> readResults(
            Path results, List<ObjectNode> expectedCommands) throws IOException {
        return readResults(results, expectedCommands, true);
    }

    private static List<ResultLine> readResults(
            Path results, List<ObjectNode> expectedCommands,
            boolean requireSuccess) throws IOException {
        if (Files.size(results) > MAX_RESULT_BYTES) {
            throw new IllegalArgumentException("Result evidence exceeds byte limit");
        }
        List<String> lines = Files.readAllLines(results, StandardCharsets.UTF_8);
        if (lines.size() != expectedCommands.size()
                || lines.size() > MAX_RESULT_LINES) {
            throw new IllegalArgumentException(
                    "Result evidence has the wrong command count");
        }
        List<ResultLine> parsed = new ArrayList<>(lines.size());
        for (int sequence = 0; sequence < lines.size(); sequence++) {
            JsonNode node = JSON.readTree(lines.get(sequence));
            requireFields(node, Set.of(
                    "sequence", "command", "ok", "error", "artifact", "state"));
            String expectedCommand =
                    expectedCommands.get(sequence).path("command").textValue();
            boolean ok = node.path("ok").isBoolean()
                    && node.path("ok").booleanValue();
            JsonNode error = node.get("error");
            JsonNode artifact = node.get("artifact");
            String expectedArtifact = "capture".equals(expectedCommand)
                    ? "captures/"
                            + expectedCommands.get(sequence).path("id").asText()
                            + ".png"
                    : null;
            if (!node.path("sequence").isIntegralNumber()
                    || node.path("sequence").intValue() != sequence
                    || !expectedCommand.equals(node.path("command").textValue())
                    || !node.path("ok").isBoolean()
                    || (requireSuccess && !ok)
                    || (ok && error != null)
                    || (!ok && (error == null || !error.isTextual()))
                    || (artifact != null && !artifact.isTextual())
                    || !Objects.equals(
                            expectedArtifact,
                            artifact == null ? null : artifact.textValue())
                    || !node.path("state").isObject()) {
                throw new IllegalArgumentException(
                        "Invalid launcher result identity");
            }
            parsed.add(new ResultLine(
                    expectedCommand, expectedArtifact,
                    (ObjectNode) node.path("state")));
        }
        return parsed;
    }

    private static ObjectNode stateForCapture(List<ResultLine> lines, String artifact) {
        return lines.stream().filter(line -> artifact.equals(line.artifact())).map(ResultLine::state)
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Missing capture result " + artifact));
    }


    static void copyObservedCheckpoint(
            ObjectNode destination, String name, ObjectNode state) {
        destination.set(name, state.deepCopy());
    }

    private static void copyCheckpointIfAbsent(
            ObjectNode destination, String name, ObjectNode state) {
        if (!destination.has(name)) {
            copyObservedCheckpoint(destination, name, state);
        }
    }

    /** Rejects any symlink, unexpected root entry, or unexpected capture. */
    public static void validateEvidenceLayout(Path evidenceDirectory, Set<String> expectedCaptureFiles)
            throws IOException {
        Objects.requireNonNull(expectedCaptureFiles, "expectedCaptureFiles");
        Path root = evidenceDirectory.toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Evidence root is not a local directory");
        }
        if (!names(root).equals(Set.of("captures", "results.ndjson"))) {
            throw new IllegalArgumentException("Evidence contains missing or extra root entries");
        }
        Path results = root.resolve("results.ndjson");
        Path captures = root.resolve("captures");
        if (!Files.isRegularFile(results, LinkOption.NOFOLLOW_LINKS)
                || !Files.isDirectory(captures, LinkOption.NOFOLLOW_LINKS)
                || !names(captures).equals(expectedCaptureFiles)) {
            throw new IllegalArgumentException("Evidence capture identity mismatch");
        }
        for (String name : expectedCaptureFiles) {
            if (!name.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}\\.png")
                    || !Files.isRegularFile(captures.resolve(name), LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("Invalid capture evidence");
            }
        }
    }

    /** Computes the runner-compatible SHA-256 over relative file paths and bytes. */
    public static String treeSha256(Path root) throws IOException {
        return runnerTreeSha256(root, false);
    }

    /** Computes the runner-compatible candidate overlay identity. */
    static String candidateSha256(Path root) throws IOException {
        return runnerTreeSha256(root, true);
    }

    private static String runnerTreeSha256(
            Path root, boolean candidateOnly) throws IOException {
        Path normalized = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(
                    "Tree root must be a local directory");
        }
        List<Path> paths;
        try (var stream = Files.walk(normalized)) {
            paths = stream.filter(path -> !path.equals(normalized))
                    .sorted(Comparator.comparing(
                            path -> unixRelative(normalized, path))).toList();
        }
        MessageDigest digest = sha256Digest();
        long totalBytes = 0;
        int fileCount = 0;
        for (Path path : paths) {
            Path relativePath = normalized.relativize(path);
            boolean generated = false;
            for (Path part : relativePath) {
                if (GENERATED_NAMES.contains(part.toString())) {
                    generated = true;
                    break;
                }
            }
            if (generated
                    || (candidateOnly && relativePath.getNameCount() > 0
                        && CANDIDATE_INPUT_NAMES.contains(
                                relativePath.getName(0).toString()))) {
                continue;
            }
            if (Files.isSymbolicLink(path)) {
                throw new IllegalArgumentException(
                        "Symbolic links are not accepted");
            }
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("Unsupported tree entry");
            }
            if (++fileCount > MAX_TREE_FILES) {
                throw new IllegalArgumentException("Tree has too many files");
            }
            long bytes = Files.size(path);
            totalBytes += bytes;
            if (totalBytes > MAX_TREE_BYTES) {
                throw new IllegalArgumentException("Tree exceeds byte limit");
            }
            byte[] relative = unixRelative(normalized, path)
                    .getBytes(StandardCharsets.UTF_8);
            digest.update(java.nio.ByteBuffer.allocate(Long.BYTES)
                    .putLong(relative.length).array());
            digest.update(relative);
            digest.update(java.nio.ByteBuffer.allocate(Long.BYTES)
                    .putLong(bytes).array());
            try (InputStream input = Files.newInputStream(path)) {
                input.transferTo(new java.security.DigestOutputStream(
                        java.io.OutputStream.nullOutputStream(), digest));
            }
        }
        return hex(digest.digest());
    }

    private static void validateCorpusLayout(Path corpus) throws IOException {
        Set<String> expected = Set.of("spec.json", "schema/spec.schema.json",
                "reference/initial-1920x1080.png", "reference/bottom-1920x1080.png",
                "reference/initial-1280x720.png");
        Set<String> actual = new HashSet<>();
        try (var paths = Files.walk(corpus)) {
            for (Path path : paths.toList()) {
                if (path.equals(corpus)) continue;
                if (Files.isSymbolicLink(path)) throw new IllegalArgumentException("Corpus links are forbidden");
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) actual.add(unixRelative(corpus, path));
            }
        }
        if (!actual.equals(expected)) throw new IllegalArgumentException("Corpus contains missing or extra files");
    }

    private static void validateReferences(Path corpusDirectory, JsonNode corpus) throws IOException {
        Set<String> ids = new HashSet<>();
        for (JsonNode reference : corpus.path("references")) {
            requireFields(reference, Set.of("id", "stateId", "viewportId", "file", "width", "height", "bytes", "sha256"));
            if (!ids.add(reference.path("id").asText())) throw new IllegalArgumentException("Duplicate reference identity");
            Path path = corpusDirectory.resolve(reference.path("file").asText()).normalize();
            if (!path.startsWith(corpusDirectory.toAbsolutePath().normalize())) {
                throw new IllegalArgumentException("Reference path escapes corpus");
            }
            var image = VisualMetrics.readBoundedPng(path);
            if (Files.size(path) != reference.path("bytes").longValue()
                    || !fileSha256(path).equals(reference.path("sha256").asText())) {
                throw new IllegalArgumentException("Reference artifact identity mismatch");
            }
            if (image == null || image.getWidth() != reference.path("width").intValue()
                    || image.getHeight() != reference.path("height").intValue()) {
                throw new IllegalArgumentException("Reference viewport identity mismatch");
            }
        }
    }


    static void validateBenchmarkManifest(Path path) throws IOException {
        JsonNode manifest = readJson(path, MAX_RESULT_BYTES);
        if (!BENCHMARK_MANIFEST_VERSION.equals(manifest.path("schemaVersion").textValue())
                || !PROTOCOL_AMENDMENT.equals(
                        manifest.path("protocolAmendment").textValue())) {
            throw new IllegalArgumentException(
                    "Unsupported benchmark protocol amendment");
        }
    }


    private static JsonNode readJson(Path path, long maxBytes) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > maxBytes) {
            throw new IllegalArgumentException("JSON evidence is missing or oversized");
        }
        return JSON.readTree(path.toFile());
    }

    private static void requireFields(JsonNode object, Set<String> allowed) {
        if (!object.isObject()) throw new IllegalArgumentException("Expected JSON object");
        object.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) throw new IllegalArgumentException("Unexpected JSON field " + field);
        });
    }

    private static EvaluationRecord record(String status, EvaluationRecord.CandidateIdentity candidate,
            EvaluationRecord.CorpusIdentity corpus, FunctionalContract.Result functional,
            List<EvaluationRecord.VisualOutcome> visual,
            List<StructuralUsability.Result> structural,
            List<EvaluationRecord.Artifact> artifacts,
            List<String> diagnostics) {
        return new EvaluationRecord(EvaluationRecord.SCHEMA_VERSION, status, candidate, corpus,
                EvaluationRecord.FunctionalOutcome.from(functional), visual, structural,
                artifacts, diagnostics);
    }

    static void publishAfterIdentityCheck(
            Path candidate, String expectedIdentity, Path output, EvaluationRecord record)
            throws IOException {
        publishAfterIdentityCheck(candidate, expectedIdentity, output, record, null);
    }

    static void publishAfterIdentityCheck(
            Path candidate, String expectedIdentity, Path output, EvaluationRecord record,
            Path workspace) throws IOException {
        verifyCandidateIdentity(candidate, expectedIdentity);
        publish(output, record, workspace);
    }

    private static void publish(
            Path outputDirectory, EvaluationRecord record, Path workspace)
            throws IOException {
        Path output = outputDirectory.toAbsolutePath().normalize();
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(
                    "Evaluation output must not already exist");
        }
        Files.createDirectories(output.getParent());
        Path temporary = Files.createTempDirectory(
                output.getParent(), "." + output.getFileName() + ".tmp-");
        boolean moved = false;
        try {
            for (EvaluationRecord.Artifact artifact : record.artifacts()) {
                if (workspace == null) {
                    throw new IllegalArgumentException(
                            "Evaluation artifacts require a trusted workspace");
                }
                Path source = artifactSource(workspace, artifact.path());
                Path destination = temporary.resolve(artifact.path()).normalize();
                if (!destination.startsWith(temporary)
                        || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(source)
                        || Files.size(source) != artifact.bytes()
                        || !fileSha256(source).equals(artifact.sha256())) {
                    throw new IllegalArgumentException(
                            "Evaluation artifact identity mismatch: "
                                    + artifact.path());
                }
                Files.createDirectories(destination.getParent());
                Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES);
                if (Files.size(destination) != artifact.bytes()
                        || !fileSha256(destination).equals(artifact.sha256())) {
                    throw new IOException(
                            "Published evaluation artifact changed: "
                                    + artifact.path());
                }
            }
            Path evaluation = temporary.resolve("evaluation.json");
            byte[] bytes = JSON.writeValueAsBytes(record);
            try (var channel = java.nio.channels.FileChannel.open(
                    evaluation, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                channel.write(java.nio.ByteBuffer.wrap(bytes));
                channel.force(true);
            }
            Path sidecar = temporary.resolve("evaluation.sha256");
            byte[] sidecarBytes = (
                    fileSha256(evaluation) + "  evaluation.json\n")
                    .getBytes(StandardCharsets.US_ASCII);
            try (var channel = java.nio.channels.FileChannel.open(
                    sidecar, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                channel.write(java.nio.ByteBuffer.wrap(sidecarBytes));
                channel.force(true);
            }
            try {
                Files.move(
                        temporary, output, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException(
                        "Evaluation filesystem does not support atomic publication",
                        unsupported);
            }
            moved = true;
        } finally {
            if (!moved) {
                deleteTree(temporary);
            }
        }
    }

    private static Path artifactSource(Path workspace, String logicalPath) {
        if (logicalPath.startsWith("captures/")) {
            String name = logicalPath.substring("captures/".length());
            String evidence = name.contains("1280x720")
                    ? "evidence-1280" : "evidence-1920";
            return workspace.resolve(evidence).resolve("captures").resolve(name);
        }
        if ("evidence/1920/results.ndjson".equals(logicalPath)) {
            return workspace.resolve("evidence-1920/results.ndjson");
        }
        if ("evidence/1280/results.ndjson".equals(logicalPath)) {
            return workspace.resolve("evidence-1280/results.ndjson");
        }
        String prefix = "evidence/functional/";
        String suffix = "/results.ndjson";
        if (logicalPath.startsWith(prefix) && logicalPath.endsWith(suffix)) {
            String name = logicalPath.substring(
                    prefix.length(), logicalPath.length() - suffix.length());
            if (!name.isBlank() && name.indexOf('/') < 0) {
                return workspace.resolve(
                        "functional-" + name + "/results.ndjson");
            }
        }
        throw new IllegalArgumentException(
                "Unsupported evaluation artifact path: " + logicalPath);
    }

    private static ProcessResult runProcess(List<String> command, Path directory)
            throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        Thread drain = Thread.ofVirtual().start(() -> {
            try (InputStream input = process.getInputStream()) {
                input.transferTo(java.io.OutputStream.nullOutputStream());
            } catch (IOException ignored) {
                // Process exit remains authoritative.
            }
        });
        if (!process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            process.waitFor();
        }
        drain.join();
        return new ProcessResult(process.exitValue());
    }

    private static void prepareTrustedWorkspace(
            Path candidate, Path trustedTemplate, Path destination)
            throws IOException {
        if (!Files.isDirectory(trustedTemplate, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("Trusted candidate template is unavailable");
        }
        Files.createDirectories(destination);
        for (Map.Entry<String, String> entry : TRUSTED_TEMPLATE_FILES.entrySet()) {
            Path source = trustedTemplate.resolve(entry.getKey());
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                    || !entry.getValue().equals(fileSha256(source))) {
                throw new IllegalStateException(
                        "Trusted candidate template identity mismatch");
            }
            copyVerified(source, destination.resolve(entry.getKey()));
        }

        List<Path> candidateFiles;
        try (var paths = Files.walk(candidate)) {
            candidateFiles = paths.filter(path -> !path.equals(candidate))
                    .filter(path -> Files.isRegularFile(
                            path, LinkOption.NOFOLLOW_LINKS))
                    .sorted(Comparator.comparing(
                            path -> unixRelative(candidate, path)))
                    .toList();
        }
        for (Path source : candidateFiles) {
            String relative = unixRelative(candidate, source);
            String trustedHash = TRUSTED_TEMPLATE_FILES.get(relative);
            if (trustedHash != null) {
                if (!trustedHash.equals(fileSha256(source))) {
                    throw new CandidateRejected(
                            "Candidate modified reserved neutral infrastructure");
                }
                continue;
            }
            boolean javaSource = relative.startsWith("src/main/java/")
                    && relative.endsWith(".java");
            boolean resource = relative.startsWith("src/main/resources/")
                    || relative.startsWith("assets/");
            if (!javaSource && !resource) {
                continue;
            }
            if (resource && (relative.endsWith(".class")
                    || relative.endsWith(".jar")
                    || relative.endsWith(".gradle")
                    || relative.endsWith(".kts"))) {
                throw new CandidateRejected(
                        "Candidate resource collides with executable infrastructure");
            }
            if (javaSource) {
                String sourceText = Files.readString(
                        source, StandardCharsets.UTF_8);
                if (RESERVED_TYPE_DECLARATION.matcher(sourceText).find()) {
                    throw new CandidateRejected(
                            "Candidate declared a reserved neutral type");
                }
            }
            Path target = destination.resolve(relative).normalize();
            if (!target.startsWith(destination)
                    || Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new CandidateRejected(
                        "Candidate overlay collides with trusted workspace");
            }
            copyVerified(source, target);
        }
    }

    private static void copyVerified(Path source, Path destination)
            throws IOException {
        String before = fileSha256(source);
        Files.createDirectories(destination.getParent());
        Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES);
        if (!before.equals(fileSha256(source))
                || !before.equals(fileSha256(destination))) {
            throw new CandidateRejected(
                    "Candidate input changed while constructing workspace");
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static void verifyCandidateIdentity(Path candidate, String expected) throws IOException {
        if (!expected.equals(candidateSha256(candidate))) {
            throw new IllegalStateException("Candidate changed during evaluation");
        }
    }

    private static Set<String> names(Path directory) throws IOException {
        try (var entries = Files.list(directory)) {
            Set<String> names = new HashSet<>();
            for (Path entry : entries.toList()) names.add(entry.getFileName().toString());
            return names;
        }
    }

    private static EvaluationRecord.Artifact artifact(String logicalPath, Path path) throws IOException {
        return new EvaluationRecord.Artifact(logicalPath, Files.size(path), fileSha256(path));
    }

    private static String fileSha256(Path path) throws IOException {
        MessageDigest digest = sha256Digest();
        try (InputStream input = Files.newInputStream(path)) {
            input.transferTo(new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(), digest));
        }
        return hex(digest.digest());
    }

    private static MessageDigest sha256Digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }

    private static void update(MessageDigest digest, String value) { digest.update(value.getBytes(StandardCharsets.UTF_8)); }
    private static String hex(byte[] bytes) { return java.util.HexFormat.of().formatHex(bytes); }
    private static String unixRelative(Path root, Path path) {
        return root.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize())
                .toString().replace(path.getFileSystem().getSeparator(), "/");
    }
    private static String boundDiagnostic(Throwable failure) {
        String message = failure.getMessage();
        String safe = failure.getClass().getSimpleName() + (message == null ? "" : ":" + message);
        return safe.length() <= 512 ? safe : safe.substring(0, 512);
    }

    private static Map<String, String> parseOptions(String[] args, int start) {
        Map<String, String> options = new LinkedHashMap<>();
        for (int index = start; index < args.length; index += 2) {
            if (index + 1 >= args.length || options.put(args[index], args[index + 1]) != null) {
                throw new IllegalArgumentException("Invalid evaluator options");
            }
        }
        if (!options.keySet().equals(Set.of(
                "--benchmark-manifest", "--candidate", "--corpus",
                "--output", "--candidate-id"))) {
            throw new IllegalArgumentException("Invalid evaluator options");
        }
        return options;
    }
    private static String required(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + key);
        return value;
    }

    /** Validated immutable evaluator inputs. */
    public record Request(Path candidateDirectory, Path corpusDirectory, Path outputDirectory,
            String candidateId, Path gradleExecutable) {
        public Request {
            candidateDirectory = normalizeDirectory(candidateDirectory, "candidate");
            corpusDirectory = normalizeDirectory(corpusDirectory, "corpus");
            outputDirectory = Objects.requireNonNull(outputDirectory, "outputDirectory").toAbsolutePath().normalize();
            gradleExecutable = Objects.requireNonNull(gradleExecutable, "gradleExecutable").toAbsolutePath().normalize();
            if (!CANDIDATE_ID.matcher(Objects.requireNonNull(candidateId, "candidateId")).matches()) {
                throw new IllegalArgumentException("Invalid candidate identity");
            }
            if (outputDirectory.startsWith(candidateDirectory) || outputDirectory.startsWith(corpusDirectory)
                    || !Files.isRegularFile(gradleExecutable, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("Unsafe evaluator path identity");
            }
        }
        private static Path normalizeDirectory(Path path, String name) {
            Path normalized = Objects.requireNonNull(path, name).toAbsolutePath().normalize();
            if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException(name + " must be a local directory");
            return normalized;
        }
    }

    private static final class CandidateRejected extends RuntimeException {
        private CandidateRejected(String message) {
            super(message, null, false, false);
        }
    }

    private record EvaluationData(FunctionalContract.Result functional,
            List<EvaluationRecord.VisualOutcome> visual,
            List<StructuralUsability.Result> structural,
            List<EvaluationRecord.Artifact> artifacts) {}
    private record ResultLine(String command, String artifact, ObjectNode state) {}
    private record ProcessResult(int exitCode) {}
}
