package dev.gdx.uiharness.benchmarks;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.gdx.uiharness.fixtures.ReferenceJvmCommand;
import dev.gdx.uiharness.protocol.ProtocolJson;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipInputStream;

/** Reproducible real-system parity runner and fail-closed raw-data aggregator. */
public final class BenchmarkRunner {
    private static final ObjectMapper JSON = ProtocolJson.mapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final String SESSION_ID = "reference-ui";
    private static final int ACTION_DEADLINE_MILLIS = 500;
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(15);
    private static final List<String> CORPUS_IDS = List.of(
            "sign-in",
            "ambiguous-locator-recovery",
            "delayed-enablement",
            "moving-target",
            "obscured-target",
            "scroll-and-select",
            "modal-dialog",
            "actor-replacement",
            "screenshot-diagnosis",
            "intentional-failure-trace");

    private BenchmarkRunner() {}

    /** Runs both systems or re-aggregates existing raw records. */
    public static void main(String[] args) {
        int exitCode;
        try {
            exitCode = execute(Configuration.parse(args));
        } catch (Throwable failure) {
            System.err.println("BENCHMARK_FAILED: " + diagnostic(failure));
            exitCode = 2;
        }
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    private static int execute(Configuration configuration) throws Exception {
        List<BenchmarkScenario> scenarios = BenchmarkScenario.parse(configuration.corpus());
        List<String> ids = scenarios.stream().map(BenchmarkScenario::id).toList();
        if (!CORPUS_IDS.equals(ids)) {
            throw new IllegalArgumentException("Corpus must contain the exact ordered V1 scenarios: "
                    + CORPUS_IDS);
        }

        if (!configuration.aggregateOnly()) {
            requireFreshRawOutput(configuration.output());
            runHarness(configuration, scenarios);
            runPlaywright(configuration);
        }
        Aggregate aggregate = aggregate(configuration, scenarios);
        System.out.printf(Locale.ROOT,
                "PARITY_VERDICT %s harness=%d/%d playwright=%d/%d raw=%d%n",
                aggregate.verdict().passed() ? "PASS" : "FAIL",
                aggregate.harness().result().completedRuns(),
                aggregate.harness().result().totalRuns(),
                aggregate.playwright().result().completedRuns(),
                aggregate.playwright().result().totalRuns(),
                aggregate.rawRecordCount());
        return aggregate.verdict().passed() ? 0 : 1;
    }

    private static void requireFreshRawOutput(Path output) throws IOException {
        Path raw = output.resolve("raw");
        if (Files.exists(raw)) {
            try (var entries = Files.walk(raw)) {
                if (entries.anyMatch(path -> Files.isRegularFile(path)
                        && path.getFileName().toString().endsWith(".json"))) {
                    throw new IllegalStateException(
                            "Refusing to mix a new run with existing raw records: " + raw);
                }
            }
        }
        Files.createDirectories(raw.resolve("harness"));
        Files.createDirectories(raw.resolve("playwright"));
        Files.createDirectories(output.resolve("traces/harness"));
        Files.createDirectories(output.resolve("traces/playwright"));
        Files.createDirectories(output.resolve("evidence/harness"));
        Files.createDirectories(output.resolve("evidence/playwright"));
        Files.createDirectories(output.resolve("work"));
    }

    private static void runHarness(
            Configuration configuration, List<BenchmarkScenario> scenarios) throws Exception {
        Path raw = configuration.output().resolve("raw/harness");
        for (BenchmarkScenario scenario : scenarios) {
            for (int run = 1; run <= configuration.runs(); run++) {
                RunRecord record = executeHarness(configuration, scenario, run);
                Path target = raw.resolve(fileName(scenario.id(), run, ".json"));
                writeJsonAtomically(target, record);
                System.out.printf("HARNESS_RECORD %s %d %s%n",
                        scenario.id(), run, record.completed());
            }
        }
    }

    private static RunRecord executeHarness(
            Configuration configuration, BenchmarkScenario scenario, int run) {
        long started = System.nanoTime();
        ArrayList<String> diagnostics = new ArrayList<>();
        long traceBytes = 0;
        long screenshotBytes = 0;
        Screenshot capturedScreenshot = null;
        String evidence = "";
        String error = null;
        boolean completed = false;
        boolean timeout = false;
        int toolCalls = 0;
        HarnessProcess process = null;
        McpClient client = null;
        boolean traceStarted = false;
        try {
            process = HarnessProcess.launch(configuration, scenario);
            client = McpClient.connect(process);
            client.startTrace();
            traceStarted = true;
            for (BenchmarkScenario.Step step : scenario.steps()) {
                StepEvidence stepEvidence = executeHarnessStep(client, step);
                diagnostics.addAll(stepEvidence.diagnostics());
                if (stepEvidence.screenshot() != null) {
                    capturedScreenshot = stepEvidence.screenshot();
                    screenshotBytes = Math.addExact(
                            screenshotBytes, capturedScreenshot.byteLength());
                    copyArtifactAtomically(process, capturedScreenshot.reference(),
                            configuration.output().resolve("evidence/harness")
                                    .resolve(fileName(scenario.id(), run, ".png")));
                }
            }
            evidence = verifyHarnessExpected(
                    client, scenario.expected(), diagnostics, capturedScreenshot).value();
            completed = true;
        } catch (Throwable failure) {
            error = diagnostic(failure);
            diagnostics.add(error);
            timeout = error.toLowerCase(Locale.ROOT).contains("timeout");
            if (client != null) {
                try {
                    Screenshot screenshot = client.screenshot();
                    screenshotBytes = Math.addExact(screenshotBytes, screenshot.byteLength());
                    copyArtifactAtomically(process, screenshot.reference(),
                            configuration.output().resolve("evidence/harness")
                                    .resolve(fileName(scenario.id(), run, "-failure.png")));
                } catch (Throwable screenshotFailure) {
                    diagnostics.add("failure screenshot: " + diagnostic(screenshotFailure));
                }
            }
        } finally {
            if (client != null && traceStarted) {
                try {
                    Trace trace = client.stopTrace();
                    traceBytes = trace.bytes();
                    copyArtifactAtomically(process, trace.reference(),
                            configuration.output().resolve("traces/harness")
                                    .resolve(fileName(scenario.id(), run, ".zip")));
                    if (!validZip(configuration.output().resolve("traces/harness")
                            .resolve(fileName(scenario.id(), run, ".zip")))) {
                        throw new IllegalStateException("Harness trace is not a readable ZIP");
                    }
                } catch (Throwable traceFailure) {
                    diagnostics.add("trace stop: " + diagnostic(traceFailure));
                    if (completed) {
                        completed = false;
                        error = diagnostics.getLast();
                    }
                }
            }
            if (client != null) {
                toolCalls = client.toolCalls();
                try {
                    client.close();
                } catch (IOException closeFailure) {
                    diagnostics.add("MCP close: " + diagnostic(closeFailure));
                    completed = false;
                    error = diagnostics.getLast();
                }
            }
            if (process != null) {
                try {
                    process.awaitCleanExit();
                } catch (Throwable closeFailure) {
                    diagnostics.add("process close: " + diagnostic(closeFailure));
                    completed = false;
                    error = diagnostics.getLast();
                } finally {
                    process.closeQuietly();
                }
            }
        }

        boolean actionable = completed && traceBytes > 0 && !evidence.isEmpty()
                && (!"intentional-failure-trace".equals(scenario.id())
                        || (!diagnostics.isEmpty() && screenshotBytes > 0));
        String repeatabilityKey = sha256(JSON.valueToTree(Map.of(
                "scenario", scenario.id(),
                "completed", completed,
                "timeout", timeout,
                "actionableEvidence", actionable,
                "evidence", evidence,
                "diagnosticKinds", diagnostics.stream()
                        .map(BenchmarkRunner::normalizeDiagnostic).toList(),
                "screenshotPresent", screenshotBytes > 0,
                "tracePresent", traceBytes > 0)).toString().getBytes(StandardCharsets.UTF_8));
        return new RunRecord(1, "harness", scenario.id(), run, completed, timeout,
                !completed && !timeout, !completed, toolCalls, actionable,
                (System.nanoTime() - started) / 1_000_000.0, traceBytes, screenshotBytes,
                repeatabilityKey, diagnostics, error);
    }

    private static StepEvidence executeHarnessStep(
            McpClient client, BenchmarkScenario.Step step) throws Exception {
        ArrayList<String> diagnostics = new ArrayList<>();
        Screenshot screenshot = null;
        switch (step.action()) {
            case "fill" -> client.action(step.locator(), Map.of(
                    "kind", "fill", "value", step.value(), "force", false));
            case "click" -> client.action(step.locator(), Map.of(
                    "kind", "click", "pointer", 0, "button", 0, "force", false));
            case "wait-visible" -> client.waitVisible(step.locator());
            case "scroll" -> client.action(step.locator(), Map.of(
                    "kind", "scroll", "amountX", 0.0, "amountY", step.amountY(),
                    "force", false));
            case "screenshot" -> screenshot = client.screenshot();
            case "expect-click-failure" -> {
                try {
                    client.action(step.locator(), Map.of(
                            "kind", "click", "pointer", 0, "button", 0, "force", false));
                } catch (ToolFailure failure) {
                    if (!step.expectedFailure().matchesHarness(failure.error())) {
                        throw new IllegalStateException(
                                "Expected " + step.expectedFailure().category()
                                        + " but harness returned " + failure.error(),
                                failure);
                    }
                    diagnostics.add("expected " + step.expectedFailure().category()
                            + ": " + failure.getMessage());
                    break;
                }
                throw new IllegalStateException("Expected strict click failure but click succeeded");
            }
            default -> throw new IllegalArgumentException("Unsupported action: " + step.action());
        }
        return new StepEvidence(diagnostics, screenshot);
    }

    private static ExpectedEvidence verifyHarnessExpected(
            McpClient client, String expected, List<String> diagnostics,
            Screenshot screenshot) throws Exception {
        int separator = expected.indexOf(':');
        String kind = expected.substring(0, separator);
        String value = expected.substring(separator + 1);
        return switch (kind) {
            case "text" -> {
                String actual = client.singleText(value);
                if (!value.equals(actual)) {
                    throw new IllegalStateException(
                            "Expected text " + value + ", received " + actual);
                }
                yield new ExpectedEvidence("text:" + actual);
            }
            case "screenshot" -> {
                if (!"1280x720".equals(value) || screenshot == null
                        || screenshot.width() != 1280 || screenshot.height() != 720
                        || screenshot.byteLength() <= 0) {
                    throw new IllegalStateException("Expected a real 1280x720 screenshot");
                }
                yield new ExpectedEvidence("screenshot:" + value);
            }
            case "failure" -> {
                if (diagnostics.isEmpty() || screenshot == null
                        || screenshot.byteLength() <= 0) {
                    throw new IllegalStateException(
                            "Intentional failure omitted its diagnostic or screenshot");
                }
                yield new ExpectedEvidence("failure:" + value);
            }
            default -> throw new IllegalArgumentException(
                    "Unsupported expected outcome: " + expected);
        };
    }

    private static void runPlaywright(Configuration configuration) throws Exception {
        Path playwright = configuration.project().resolve("benchmarks/playwright");
        List<String> command = List.of(
                "npm", "run", "benchmark", "--",
                "--runs", Integer.toString(configuration.runs()),
                "--corpus", configuration.corpus().toString(),
                "--raw-dir", configuration.output().resolve("raw/playwright").toString(),
                "--trace-dir", configuration.output().resolve("traces/playwright").toString(),
                "--evidence-dir", configuration.output().resolve("evidence/playwright").toString());
        ProcessSupervisor.Result result = ProcessSupervisor.run(
                command, playwright, Duration.ofMinutes(20), 1024 * 1024);
        System.out.print(result.output());
        if (result.outputTruncated()) {
            System.out.println("\\n[Playwright output truncated at 1048576 bytes]");
        }
        if (result.timedOut()) {
            throw new IllegalStateException("Playwright benchmark exceeded 20 minutes");
        }
        if (result.exitCode() != 0) {
            throw new IllegalStateException(
                    "Playwright benchmark exited " + result.exitCode());
        }
    }

    private static Aggregate aggregate(
            Configuration configuration, List<BenchmarkScenario> scenarios) throws Exception {
        List<RunRecord> records = readAndValidateRaw(configuration, scenarios);
        BenchmarkArtifactValidator.validate(configuration.output(), records, scenarios);
        List<RunRecord> harnessRecords = records.stream()
                .filter(record -> "harness".equals(record.system())).toList();
        List<RunRecord> playwrightRecords = records.stream()
                .filter(record -> "playwright".equals(record.system())).toList();
        Summary harness = summarize("harness", harnessRecords, configuration.runs(), scenarios);
        Summary playwright = summarize(
                "playwright", playwrightRecords, configuration.runs(), scenarios);
        Statistics.ParityVerdict verdict = Statistics.meetsParity(
                harness.result(), playwright.result());

        LinkedHashMap<String, Object> rawDocument = new LinkedHashMap<>();
        rawDocument.put("schemaVersion", 1);
        rawDocument.put("runsPerScenario", configuration.runs());
        rawDocument.put("scenarioOrder", CORPUS_IDS);
        rawDocument.put("recordCount", records.size());
        rawDocument.put("records", records);
        writeJsonAtomically(configuration.output().resolve("raw-results.json"), rawDocument);

        LinkedHashMap<String, Object> verdictDocument = new LinkedHashMap<>();
        verdictDocument.put("schemaVersion", 1);
        verdictDocument.put("status", verdict.passed() ? "PASS" : "FAIL");
        verdictDocument.put("passed", verdict.passed());
        verdictDocument.put("rawRecordCount", records.size());
        verdictDocument.put("runsPerScenario", configuration.runs());
        verdictDocument.put("threshold", Map.of(
                "completion", "harness >= playwright",
                "actionableEvidence", "harness >= playwright",
                "timeoutOrFlaky", "harness <= Playwright two-sided 95% Wilson upper bound",
                "medianToolCalls", "reported only"));
        verdictDocument.put("harness", harness);
        verdictDocument.put("playwright", playwright);
        verdictDocument.put("playwrightTimeoutOrFlakyWilson95",
                verdict.playwrightFailureWilson());
        verdictDocument.put("failures", verdict.failures());
        verdictDocument.put("environment", environment(configuration));
        writeJsonAtomically(configuration.output().resolve("verdict.json"), verdictDocument);
        writeCsv(configuration.output().resolve("aggregate.csv"), records, scenarios,
                configuration.runs());
        return new Aggregate(harness, playwright, verdict, records.size());
    }

    private static List<RunRecord> readAndValidateRaw(
            Configuration configuration, List<BenchmarkScenario> scenarios) throws Exception {
        ArrayList<RunRecord> records = new ArrayList<>();
        for (String system : List.of("harness", "playwright")) {
            Path directory = configuration.output().resolve("raw").resolve(system);
            if (!Files.isDirectory(directory)) {
                throw new IllegalStateException("Missing raw directory: " + directory);
            }
            try (var paths = Files.list(directory)) {
                for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                    if (!path.getFileName().toString().endsWith(".json")) {
                        continue;
                    }
                    try {
                        records.add(JSON.readValue(path.toFile(), RunRecord.class));
                    } catch (IOException | RuntimeException failure) {
                        throw new IllegalStateException("Malformed raw record: " + path, failure);
                    }
                }
            }
        }
        int expectedCount = 2 * scenarios.size() * configuration.runs();
        if (records.size() != expectedCount) {
            throw new IllegalStateException(
                    "Expected " + expectedCount + " raw records, found " + records.size());
        }
        HashSet<String> identities = new HashSet<>();
        Set<String> expectedIds = Set.copyOf(CORPUS_IDS);
        for (RunRecord record : records) {
            if (!Set.of("harness", "playwright").contains(record.system())
                    || !expectedIds.contains(record.scenarioId())
                    || record.run() < 1 || record.run() > configuration.runs()) {
                throw new IllegalStateException("Unexpected raw record identity: " + record);
            }
            String identity = record.system() + '/' + record.scenarioId() + '/' + record.run();
            if (!identities.add(identity)) {
                throw new IllegalStateException("Duplicate raw record: " + identity);
            }
        }
        records.sort(Comparator.comparing(RunRecord::system)
                .thenComparingInt(record -> CORPUS_IDS.indexOf(record.scenarioId()))
                .thenComparingInt(RunRecord::run));
        return List.copyOf(records);
    }

    private static Summary summarize(
            String system, List<RunRecord> records, int runs,
            List<BenchmarkScenario> scenarios) {
        int completed = (int) records.stream().filter(RunRecord::completed).count();
        int timeoutOrFlaky = (int) records.stream().filter(RunRecord::timeoutOrFlaky).count();
        int timeouts = (int) records.stream().filter(RunRecord::timeout).count();
        int flakyFailures = (int) records.stream().filter(RunRecord::flakyFailure).count();
        int actionable = (int) records.stream().filter(RunRecord::actionableEvidence).count();
        long traceBytes = records.stream().mapToLong(RunRecord::traceBytes).sum();
        double medianCalls = median(records.stream().map(RunRecord::toolCalls).sorted().toList());
        double medianDuration = median(records.stream()
                .map(RunRecord::durationMillis).sorted().toList());
        int repeatable = 0;
        for (BenchmarkScenario scenario : scenarios) {
            List<RunRecord> scenarioRecords = records.stream()
                    .filter(record -> scenario.id().equals(record.scenarioId())).toList();
            if (scenarioRecords.size() == runs
                    && scenarioRecords.stream().map(RunRecord::repeatabilityKey)
                            .distinct().count() == 1) {
                repeatable += runs;
            }
        }
        BenchmarkResult result = new BenchmarkResult(completed, records.size(), timeoutOrFlaky,
                actionable, medianCalls, traceBytes);
        return new Summary(system, result, timeouts, flakyFailures,
                result.completionRate(), result.timeoutOrFlakyRate(),
                result.actionableEvidenceRate(), medianDuration,
                (double) repeatable / records.size(),
                Statistics.wilsonInterval(completed, records.size()),
                Statistics.wilsonInterval(actionable, records.size()),
                Statistics.wilsonInterval(timeoutOrFlaky, records.size()));
    }

    private static double median(List<? extends Number> sorted) {
        if (sorted.isEmpty()) {
            throw new IllegalArgumentException("Cannot compute median of no values");
        }
        int middle = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(middle).doubleValue();
        }
        return (sorted.get(middle - 1).doubleValue() + sorted.get(middle).doubleValue()) / 2.0;
    }

    private static void writeCsv(
            Path target, List<RunRecord> records, List<BenchmarkScenario> scenarios, int runs)
            throws Exception {
        StringBuilder csv = new StringBuilder();
        csv.append("system,scope,total,completed,completion_rate,timeouts,flaky_failures,")
                .append("timeout_or_flaky,timeout_or_flaky_rate,actionable_evidence,")
                .append("actionable_evidence_rate,median_tool_calls,median_duration_millis,")
                .append("repeatability_rate,trace_bytes\n");
        for (String system : List.of("harness", "playwright")) {
            List<RunRecord> systemRecords = records.stream()
                    .filter(record -> system.equals(record.system())).toList();
            appendCsvRow(csv, system, "all", systemRecords, runs, scenarios.size());
            for (BenchmarkScenario scenario : scenarios) {
                appendCsvRow(csv, system, scenario.id(), systemRecords.stream()
                        .filter(record -> scenario.id().equals(record.scenarioId())).toList(),
                        runs, 1);
            }
        }
        writeAtomically(target, csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void appendCsvRow(
            StringBuilder csv, String system, String scope, List<RunRecord> records,
            int runs, int scenarioCount) {
        int total = records.size();
        int completed = (int) records.stream().filter(RunRecord::completed).count();
        int failures = (int) records.stream().filter(RunRecord::timeoutOrFlaky).count();
        int timeouts = (int) records.stream().filter(RunRecord::timeout).count();
        int flakyFailures = (int) records.stream().filter(RunRecord::flakyFailure).count();
        int actionable = (int) records.stream().filter(RunRecord::actionableEvidence).count();
        long repeatable = records.stream().collect(java.util.stream.Collectors.groupingBy(
                        RunRecord::scenarioId,
                        java.util.stream.Collectors.mapping(RunRecord::repeatabilityKey,
                                java.util.stream.Collectors.toSet())))
                .values().stream().filter(keys -> keys.size() == 1).count() * runs;
        csv.append(system).append(',').append(scope).append(',').append(total).append(',')
                .append(completed).append(',').append(rate(completed, total)).append(',')
                .append(timeouts).append(',').append(flakyFailures).append(',')
                .append(failures).append(',').append(rate(failures, total)).append(',')
                .append(actionable).append(',').append(rate(actionable, total)).append(',')
                .append(format(median(records.stream().map(RunRecord::toolCalls).sorted().toList())))
                .append(',').append(format(median(records.stream()
                        .map(RunRecord::durationMillis).sorted().toList())))
                .append(',').append(format((double) repeatable / (runs * scenarioCount)))
                .append(',').append(records.stream().mapToLong(RunRecord::traceBytes).sum())
                .append('\n');
    }

    private static Map<String, Object> environment(Configuration configuration) throws Exception {
        JsonNode browsers = JSON.readTree(configuration.project()
                .resolve("benchmarks/playwright/node_modules/playwright-core/browsers.json")
                .toFile());
        JsonNode chromium = browsers.path("browsers").valueStream()
                .filter(node -> "chromium".equals(node.path("name").asText()))
                .findFirst().orElseThrow();
        LinkedHashMap<String, Object> environment = new LinkedHashMap<>();
        environment.put("os", System.getProperty("os.name") + " "
                + System.getProperty("os.version") + " " + System.getProperty("os.arch"));
        environment.put("java", System.getProperty("java.version"));
        environment.put("node", commandOutput("node", "--version"));
        environment.put("npm", commandOutput("npm", "--version"));
        environment.put("display", System.getenv("DISPLAY"));
        environment.put("xvfbRunAvailable", Files.isExecutable(Path.of("/usr/bin/xvfb-run")));
        environment.put("playwright", "1.61.1");
        environment.put("chromiumRevision", chromium.path("revision").asText());
        environment.put("playwrightPageTransport", "page.setContent (no network listener)");
        return environment;
    }

    private static String commandOutput(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output;
        try (var input = process.getInputStream()) {
            output = new String(input.readAllBytes(), StandardCharsets.UTF_8).strip();
        }
        if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IllegalStateException("Unable to identify runtime: " + command[0]);
        }
        return output;
    }

    private static void copyArtifactAtomically(
            HarnessProcess process, String reference, Path target) throws Exception {
        writeAtomically(target, process.readArtifact(reference));
    }

    private static boolean validZip(Path path) {
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(path))) {
            return zip.getNextEntry() != null;
        } catch (IOException failure) {
            return false;
        }
    }

    private static void writeJsonAtomically(Path target, Object value) throws Exception {
        writeAtomically(target, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(value));
    }

    private static void writeAtomically(Path target, byte[] bytes) throws Exception {
        Files.createDirectories(target.toAbsolutePath().normalize().getParent());
        Path temporary = target.resolveSibling('.' + target.getFileName().toString()
                + '.' + ProcessHandle.current().pid() + ".tmp");
        try (FileChannel channel = FileChannel.open(
                temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException failure) {
            Files.deleteIfExists(temporary);
            throw new IllegalStateException(
                    "Output filesystem does not support required atomic publication: " + target,
                    failure);
        }
    }

    private static String fileName(String scenario, int run, String suffix) {
        return scenario + '-' + String.format(Locale.ROOT, "%02d", run) + suffix;
    }

    private static String rate(int count, int total) {
        return format((double) count / total);
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }

    private static String normalizeDiagnostic(String value) {
        String codePrefix = "\"code\":\"";
        int codeStart = value.indexOf(codePrefix);
        if (codeStart >= 0) {
            int valueStart = codeStart + codePrefix.length();
            int valueEnd = value.indexOf('"', valueStart);
            if (valueEnd > valueStart) {
                return "mcp-code:" + value.substring(valueStart, valueEnd);
            }
        }
        int newline = value.indexOf('\n');
        String first = newline < 0 ? value : value.substring(0, newline);
        return first.replaceAll("\\d+ms", "<duration>");
    }

    private static String diagnostic(Throwable failure) {
        StringBuilder value = new StringBuilder(failure.toString());
        for (StackTraceElement element : failure.getStackTrace()) {
            value.append("\n\tat ").append(element);
        }
        Throwable cause = failure.getCause();
        if (cause != null && cause != failure) {
            value.append("\nCaused by: ").append(diagnostic(cause));
        }
        return value.toString();
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** One atomically persisted raw observation. */
    public record RunRecord(
            int schemaVersion,
            String system,
            String scenarioId,
            int run,
            boolean completed,
            boolean timeout,
            boolean flakyFailure,
            boolean timeoutOrFlaky,
            int toolCalls,
            boolean actionableEvidence,
            double durationMillis,
            long traceBytes,
            long screenshotBytes,
            String repeatabilityKey,
            List<String> diagnostics,
            String error) {
        /** Validates invariants and defensively copies diagnostic evidence. */
        public RunRecord {
            if (schemaVersion != 1 || system == null || scenarioId == null || run <= 0
                    || toolCalls < 0 || !Double.isFinite(durationMillis) || durationMillis < 0
                    || traceBytes < 0 || screenshotBytes < 0 || repeatabilityKey == null) {
                throw new IllegalArgumentException("Invalid raw benchmark record");
            }
            diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
            if (timeoutOrFlaky != (timeout || flakyFailure)
                    || flakyFailure != (!completed && !timeout)) {
                throw new IllegalArgumentException("Inconsistent raw failure classification");
            }
        }
    }

    /** Machine-readable aggregate for one system. */
    public record Summary(
            String system,
            BenchmarkResult result,
            int timeoutRuns,
            int flakyFailureRuns,
            double completionRate,
            double timeoutOrFlakyRate,
            double actionableEvidenceRate,
            double medianDurationMillis,
            double repeatabilityRate,
            Statistics.WilsonInterval completionWilson95,
            Statistics.WilsonInterval actionableEvidenceWilson95,
            Statistics.WilsonInterval timeoutOrFlakyWilson95) {}

    private record Configuration(
            int runs, Path output, Path corpus, Path project, boolean aggregateOnly) {
        private static Configuration parse(String[] args) {
            HashMap<String, String> values = new HashMap<>();
            boolean aggregateOnly = false;
            for (int index = 0; index < args.length; index++) {
                if ("--aggregate-only".equals(args[index])) {
                    aggregateOnly = true;
                    continue;
                }
                if (!args[index].startsWith("--") || index + 1 >= args.length) {
                    throw new IllegalArgumentException("Malformed argument: " + args[index]);
                }
                String previous = values.put(args[index].substring(2), args[++index]);
                if (previous != null) {
                    throw new IllegalArgumentException("Duplicate argument: " + args[index - 1]);
                }
            }
            Set<String> unknown = new HashSet<>(values.keySet());
            unknown.removeAll(Set.of("runs", "output", "corpus"));
            if (!unknown.isEmpty()) {
                throw new IllegalArgumentException("Unknown arguments: " + unknown);
            }
            int runs;
            try {
                runs = Integer.parseInt(values.getOrDefault("runs", "20"));
            } catch (NumberFormatException failure) {
                throw new IllegalArgumentException("--runs must be a positive integer", failure);
            }
            if (runs <= 0) {
                throw new IllegalArgumentException("--runs must be a positive integer");
            }
            String projectProperty = System.getProperty("benchmark.project.dir");
            if (projectProperty == null || projectProperty.isBlank()) {
                throw new IllegalStateException("Gradle did not provide benchmark.project.dir");
            }
            Path project = Path.of(projectProperty).toAbsolutePath().normalize();
            Path corpus = path(project, values.getOrDefault(
                    "corpus", "benchmarks/corpus/scenarios.json"));
            Path output = path(project, values.getOrDefault(
                    "output", "build/reports/parity"));
            return new Configuration(runs, output, corpus, project, aggregateOnly);
        }

        private static Path path(Path project, String value) {
            Path path = Path.of(value);
            return (path.isAbsolute() ? path : project.resolve(path)).toAbsolutePath().normalize();
        }
    }

    private record Aggregate(
            Summary harness,
            Summary playwright,
            Statistics.ParityVerdict verdict,
            int rawRecordCount) {}

    private record StepEvidence(List<String> diagnostics, Screenshot screenshot) {
        private StepEvidence {
            diagnostics = List.copyOf(diagnostics);
        }
    }

    private record ExpectedEvidence(String value) {}

    private record Screenshot(int width, int height, String reference, long byteLength) {}

    private record Trace(String reference, long bytes) {}

    private static final class ToolFailure extends Exception {
        private static final long serialVersionUID = 1L;
        private final transient JsonNode error;

        private ToolFailure(String message, JsonNode error) {
            super(message);
            this.error = error.deepCopy();
        }

        private JsonNode error() {
            return error;
        }
    }

    private static final class McpClient implements AutoCloseable {
        private final BufferedReader input;
        private final BufferedWriter output;
        private long requestId;
        private int toolCalls;
        private boolean closed;

        private McpClient(HarnessProcess process) {
            input = new BufferedReader(new InputStreamReader(
                    process.process().getInputStream(), StandardCharsets.UTF_8));
            output = new BufferedWriter(new OutputStreamWriter(
                    process.process().getOutputStream(), StandardCharsets.UTF_8));
        }

        private static McpClient connect(HarnessProcess process) throws Exception {
            McpClient client = new McpClient(process);
            JsonNode initialized = client.request("initialize", Map.of(
                    "protocolVersion", "2025-11-25",
                    "capabilities", Map.of(),
                    "clientInfo", Map.of("name", "parity-benchmark", "version", "1.0")));
            if (!"libgdx-ui-harness".equals(initialized.at("/serverInfo/name").asText())) {
                throw new IllegalStateException("Unexpected MCP server identity: " + initialized);
            }
            client.notify("notifications/initialized", Map.of());
            JsonNode tools = client.request("tools/list", Map.of());
            if (tools.path("tools").size() != 21) {
                throw new IllegalStateException("Expected twenty-one production MCP tools");
            }
            return client;
        }

        private int toolCalls() {
            return toolCalls;
        }

        private void startTrace() throws Exception {
            JsonNode content = call("ui_trace_start", Map.of(
                    "sessionId", SESSION_ID,
                    "deadlineMillis", ACTION_DEADLINE_MILLIS,
                    "maxDurationMillis", 30_000,
                    "maxBytes", 4L * 1024 * 1024));
            requireKind(content, "trace-started");
        }

        private Trace stopTrace() throws Exception {
            JsonNode content = call("ui_trace_stop", Map.of(
                    "sessionId", SESSION_ID,
                    "deadlineMillis", ACTION_DEADLINE_MILLIS));
            requireKind(content, "trace-stopped");
            return new Trace(content.path("traceReference").asText(),
                    content.path("bytes").asLong());
        }

        private void action(BenchmarkScenario.Locator locator, Map<String, Object> action)
                throws Exception {
            JsonNode content = call("ui_action", Map.of(
                    "sessionId", SESSION_ID,
                    "deadlineMillis", ACTION_DEADLINE_MILLIS,
                    "locator", locator(locator),
                    "action", action));
            requireKind(content, "action-result");
        }

        private void waitVisible(BenchmarkScenario.Locator locator) throws Exception {
            JsonNode content = call("ui_wait", Map.of(
                    "sessionId", SESSION_ID,
                    "deadlineMillis", ACTION_DEADLINE_MILLIS,
                    "locator", locator(locator),
                    "condition", "visible"));
            requireKind(content, "wait-result");
        }

        private String singleText(String text) throws Exception {
            JsonNode content = call("ui_query", Map.of(
                    "sessionId", SESSION_ID,
                    "deadlineMillis", ACTION_DEADLINE_MILLIS,
                    "locator", textLocator("text", text)));
            JsonNode matches = content.path("matches");
            if (matches.size() != 1) {
                throw new IllegalStateException("Expected one semantic text match: " + content);
            }
            return matches.get(0).path("text").asText();
        }

        private Screenshot screenshot() throws Exception {
            JsonNode content = call("ui_screenshot", Map.of(
                    "sessionId", SESSION_ID,
                    "deadlineMillis", ACTION_DEADLINE_MILLIS,
                    "maxWidth", 1280,
                    "maxHeight", 720,
                    "maxPixels", 1280L * 720,
                    "maxPngBytes", 4 * 1024 * 1024));
            requireKind(content, "screenshot-result");
            JsonNode artifact = content.path("artifact");
            return new Screenshot(content.path("width").asInt(), content.path("height").asInt(),
                    artifact.path("reference").asText(), artifact.path("byteLength").asLong());
        }

        private JsonNode call(String tool, Map<String, Object> arguments) throws Exception {
            toolCalls++;
            JsonNode result = request("tools/call", Map.of(
                    "name", tool,
                    "arguments", arguments));
            if (result.path("isError").asBoolean()) {
                throw new ToolFailure(
                        "MCP " + tool + " failed: " + result,
                        result.path("structuredContent"));
            }
            JsonNode content = result.path("structuredContent");
            if (!content.isObject()) {
                throw new IllegalStateException("MCP tool omitted structured content: " + result);
            }
            return content;
        }

        private JsonNode request(String method, Map<String, Object> params) throws Exception {
            long id = ++requestId;
            send(Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params));
            JsonNode message;
            do {
                String line = input.readLine();
                if (line == null) {
                    throw new IllegalStateException("MCP stdout closed while awaiting " + method);
                }
                message = JSON.readTree(line);
            } while (!message.has("id"));
            if (message.path("id").asLong() != id) {
                throw new IllegalStateException("Out-of-order MCP response: " + message);
            }
            if (message.has("error")) {
                throw new IllegalStateException("MCP request failed: " + message.path("error"));
            }
            return message.path("result");
        }

        private void notify(String method, Map<String, Object> params) throws Exception {
            send(Map.of("jsonrpc", "2.0", "method", method, "params", params));
        }

        private void send(Map<String, Object> message) throws Exception {
            output.write(JSON.writeValueAsString(message));
            output.newLine();
            output.flush();
        }

        @Override public void close() throws IOException {
            if (!closed) {
                closed = true;
                output.close();
                input.close();
            }
        }

        private static Map<String, Object> locator(BenchmarkScenario.Locator portable) {
            return switch (portable.kind()) {
                case "test-id" -> Map.of("kind", "test-id", "testId", portable.value());
                case "text" -> textLocator("text", portable.value());
                case "label" -> textLocator("label", portable.value());
                case "role" -> Map.of(
                        "kind", "filter",
                        "locator", Map.of("kind", "role", "role", portable.value()),
                        "filter", Map.of("kind", "name", "match", exact(portable.name())));
                default -> throw new IllegalArgumentException(
                        "Unsupported locator kind: " + portable.kind());
            };
        }

        private static Map<String, Object> textLocator(String field, String value) {
            return Map.of("kind", "text", "field", field, "match", exact(value));
        }

        private static Map<String, Object> exact(String value) {
            return Map.of("mode", "exact", "source", value);
        }

        private static void requireKind(JsonNode content, String expected) {
            if (!expected.equals(content.path("kind").asText())) {
                throw new IllegalStateException("Expected " + expected + ": " + content);
            }
        }
    }

    private static final class HarnessProcess {
        private final Path root;
        private final Process process;
        private final StringBuilder stderr = new StringBuilder();
        private final CompletableFuture<Void> ready = new CompletableFuture<>();
        private final Thread errorPump;
        private boolean closed;

        private HarnessProcess(Path root, Process process) {
            this.root = root;
            this.process = process;
            errorPump = Thread.ofPlatform().name("benchmark-reference-stderr").start(this::pumpErrors);
        }

        private static HarnessProcess launch(
                Configuration configuration, BenchmarkScenario scenario) throws Exception {
            String classpath = System.getProperty("benchmark.runtime.classpath");
            if (classpath == null || classpath.isBlank()) {
                throw new IllegalStateException(
                        "Gradle did not provide benchmark.runtime.classpath");
            }
            Path root = Files.createTempDirectory(configuration.output().resolve("work"),
                    "reference-");
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            Process process = new ProcessBuilder(ReferenceJvmCommand.build(
                    java,
                    classpath,
                    System.getProperty("os.name"),
                    root.toString(),
                    scenario.id(),
                    Integer.toString(scenario.logicalDelayMillis())))
                    .start();
            HarnessProcess reference = new HarnessProcess(root, process);
            try {
                reference.ready.get(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                if (!process.isAlive()) {
                    throw new IllegalStateException("Reference process exited before MCP connect\n"
                            + reference.stderr);
                }
                return reference;
            } catch (Throwable failure) {
                reference.closeQuietly();
                throw failure;
            }
        }

        private Process process() {
            return process;
        }

        private byte[] readArtifact(String reference) throws Exception {
            if (reference == null || !reference.matches("artifact:[0-9a-f]{32}")) {
                throw new IllegalArgumentException("Invalid opaque artifact reference");
            }
            String id = reference.substring("artifact:".length());
            List<String> proof = Files.readAllLines(
                    root.resolve("proofs").resolve(id + ".receipt"), StandardCharsets.UTF_8);
            if (proof.size() != 4 || !reference.equals(proof.get(0))) {
                throw new IllegalStateException("Malformed artifact proof receipt");
            }
            String expectedHash = proof.get(3);
            List<byte[]> matches;
            try (var paths = Files.walk(root.resolve("artifacts"))) {
                matches = paths.filter(Files::isRegularFile).map(path -> {
                    try {
                        return Files.readAllBytes(path);
                    } catch (IOException failure) {
                        throw new IllegalStateException(failure);
                    }
                }).filter(bytes -> expectedHash.equals(sha256(bytes))).toList();
            }
            if (matches.size() != 1) {
                throw new IllegalStateException("Expected one artifact blob, found "
                        + matches.size());
            }
            byte[] bytes = matches.getFirst();
            if (Long.parseLong(proof.get(2)) != bytes.length) {
                throw new IllegalStateException("Artifact byte length disagrees with receipt");
            }
            return bytes;
        }

        private void awaitCleanExit() throws Exception {
            int exit = process.onExit().get(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                    .exitValue();
            errorPump.join(PROCESS_TIMEOUT);
            if (exit != 0 || !stderr.toString().contains("REFERENCE_UI_CLOSED")
                    || stderr.toString().contains("REFERENCE_UI_CLOSE_FAILED")) {
                throw new IllegalStateException("Reference lifecycle failed with exit " + exit
                        + '\n' + stderr);
            }
        }

        private void closeQuietly() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                process.getOutputStream().close();
                if (process.isAlive()
                        && !process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    process.destroy();
                    if (!process.waitFor(2, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                    }
                }
                errorPump.join(PROCESS_TIMEOUT);
            } catch (Throwable ignored) {
                process.destroyForcibly();
            }
            if (Files.exists(root)) {
                try (var paths = Files.walk(root)) {
                    paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                            // Best-effort cleanup after a failed owned fixture process.
                        }
                    });
                } catch (IOException ignored) {
                    // The fixture normally removes this root itself.
                }
            }
        }

        private void pumpErrors() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (stderr) {
                        if (stderr.length() < 256_000) {
                            stderr.append(line).append('\n');
                        }
                    }
                    if (line.contains("REFERENCE_UI_READY")) {
                        ready.complete(null);
                    }
                }
                if (!ready.isDone()) {
                    ready.completeExceptionally(new IllegalStateException(
                            "Reference process closed before ready\n" + stderr));
                }
            } catch (IOException failure) {
                ready.completeExceptionally(failure);
            }
        }
    }
}
