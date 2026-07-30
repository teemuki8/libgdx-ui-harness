package dev.gdx.uiharness.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RecoveryWorkflowTest {
    private static final RecoveryPolicy POLICY =
            new RecoveryPolicy(2, 2, 2, 1, 1, 30_000);

    @Test void requestAndProcessIdChurnCannotResetEquivalentErrorBudget() {
        RecoveryWorkflow workflow = new RecoveryWorkflow(POLICY, 0);
        RecoveryWorkflow.Identity identity = identity("source-a", "runtime-a");

        RecoveryWorkflow.Decision first = workflow.record(
                attempt("request-1", "process-1", "bad-payload",
                        DiagnosticCode.MISSING_ARGUMENT, identity, noProgress()), 1);
        RecoveryWorkflow.Decision second = workflow.record(
                attempt("request-2", "process-2", "bad-payload",
                        DiagnosticCode.MISSING_ARGUMENT, identity, noProgress()), 2);
        RecoveryWorkflow.Decision terminal = workflow.record(
                attempt("request-3", "process-3", "bad-payload",
                        DiagnosticCode.MISSING_ARGUMENT, identity, noProgress()), 3);

        assertTrue(first.retryAllowed());
        assertTrue(second.retryAllowed());
        assertFalse(terminal.retryAllowed());
        assertEquals(DiagnosticCode.LOOP_DETECTED, terminal.terminalCode());
        assertEquals(3, terminal.counters().schemaRecoveries());
        assertEquals(0, terminal.counters().remainingSchemaRecoveries());
        assertTrue(terminal.terminalRecord().digest().matches("[0-9a-f]{64}"));
    }

    @Test void productiveProgressResetsOnlyConsecutiveNoProgress() {
        RecoveryWorkflow workflow = new RecoveryWorkflow(POLICY, 0);
        RecoveryWorkflow.Identity identity = identity("source-a", "runtime-a");
        workflow.record(attempt(
                "r1", "p1", "inspect", DiagnosticCode.NO_PROGRESS,
                identity, noProgress()), 1);

        RecoveryWorkflow.Decision progress = workflow.record(attempt(
                "r2", "p1", "inspect", DiagnosticCode.NO_PROGRESS,
                identity,
                new RecoveryWorkflow.Progress(
                        RecoveryWorkflow.Change.CHANGED,
                        RecoveryWorkflow.Change.UNCHANGED,
                        RecoveryWorkflow.Change.UNCHANGED,
                        RecoveryWorkflow.Change.UNCHANGED,
                        RecoveryWorkflow.Change.UNCHANGED,
                        RecoveryWorkflow.Change.UNCHANGED)), 2);

        assertTrue(progress.retryAllowed());
        assertEquals(0, progress.counters().consecutiveNoProgress());
        assertEquals(2, progress.counters().totalIterations());
    }

    @Test void reuseRequiresEveryImmutableIdentityAndHealthyPriorSuccess() {
        RecoveryWorkflow.Identity current = identity("source-a", "runtime-a");

        assertEquals(RecoveryWorkflow.Reuse.REUSE_BUILD_AND_RUNTIME,
                RecoveryWorkflow.reuse(current, current));
        assertEquals(RecoveryWorkflow.Reuse.REBUILD_AND_RELAUNCH,
                RecoveryWorkflow.reuse(
                        current,
                        new RecoveryWorkflow.Identity(
                                digest("other"), current.dependencySha256(),
                                current.toolchainSha256(), current.buildConfigurationSha256(),
                                current.buildOutputSha256(), current.launchConfigurationSha256(),
                                current.processId(), current.sessionId(),
                                current.applicationSha256(), current.viewportIdentity(),
                                current.revision(), current.runId(), true, true)));
        assertEquals(RecoveryWorkflow.Reuse.REBUILD_AND_RELAUNCH,
                RecoveryWorkflow.reuse(
                        current,
                        new RecoveryWorkflow.Identity(
                                current.sourceSha256(), current.dependencySha256(),
                                current.toolchainSha256(), current.buildConfigurationSha256(),
                                current.buildOutputSha256(), current.launchConfigurationSha256(),
                                current.processId(), current.sessionId(),
                                current.applicationSha256(), current.viewportIdentity(),
                                current.revision(), "other-run", true, true)));
        assertEquals(RecoveryWorkflow.Reuse.RELAUNCH,
                RecoveryWorkflow.reuse(
                        current,
                        new RecoveryWorkflow.Identity(
                                current.sourceSha256(), current.dependencySha256(),
                                current.toolchainSha256(), current.buildConfigurationSha256(),
                                current.buildOutputSha256(), current.launchConfigurationSha256(),
                                current.processId(), current.sessionId(),
                                current.applicationSha256(), current.viewportIdentity(),
                                current.revision(), current.runId(), true, false)));
        assertEquals("unknown-identity/v1",
                RecoveryWorkflow.reuseDecision(current, null).reason());
        assertEquals("cross-run-identity/v1",
                RecoveryWorkflow.reuseDecision(
                        current,
                        new RecoveryWorkflow.Identity(
                                current.sourceSha256(), current.dependencySha256(),
                                current.toolchainSha256(), current.buildConfigurationSha256(),
                                current.buildOutputSha256(), current.launchConfigurationSha256(),
                                current.processId(), current.sessionId(),
                                current.applicationSha256(), current.viewportIdentity(),
                                current.revision(), "other-run", true, true)).reason());
    }

    @Test void unchangedBuildAndLaunchCapsTerminateAtExactBoundary() {
        RecoveryWorkflow.Identity identity = identity("source-a", "runtime-a");
        RecoveryWorkflow builds = new RecoveryWorkflow(POLICY, 0);
        assertTrue(builds.record(attempt(
                "r1", "p1", "build", DiagnosticCode.NO_PROGRESS,
                identity, noProgress()).withCosts(true, false), 1).retryAllowed());
        RecoveryWorkflow.Decision buildTerminal = builds.record(attempt(
                "r2", "p1", "build", DiagnosticCode.NO_PROGRESS,
                identity, noProgress()).withCosts(true, false), 2);
        assertEquals(
                DiagnosticCode.RECOVERY_BUDGET_EXHAUSTED,
                buildTerminal.terminalCode());
        assertEquals("max-unchanged-builds/v1", buildTerminal.ruleId());
        assertEquals(2, buildTerminal.counters().unchangedBuilds());

        RecoveryWorkflow launches = new RecoveryWorkflow(POLICY, 0);
        assertTrue(launches.record(attempt(
                "r1", "p1", "launch", DiagnosticCode.NO_PROGRESS,
                identity, noProgress()).withCosts(false, true), 1).retryAllowed());
        RecoveryWorkflow.Decision launchTerminal = launches.record(attempt(
                "r2", "p2", "launch", DiagnosticCode.NO_PROGRESS,
                identity, noProgress()).withCosts(false, true), 2);
        assertEquals(
                DiagnosticCode.RECOVERY_BUDGET_EXHAUSTED,
                launchTerminal.terminalCode());
        assertEquals("max-unchanged-launches/v1", launchTerminal.ruleId());
        assertEquals(2, launchTerminal.counters().unchangedLaunches());
    }

    @Test void deadlineStopsAtTheExactConfiguredTime() {
        RecoveryWorkflow workflow = new RecoveryWorkflow(POLICY, 10);
        RecoveryWorkflow.Identity identity = identity("source-a", "runtime-a");

        RecoveryWorkflow.Decision before = workflow.record(attempt(
                "r1", "p1", "wait", DiagnosticCode.STATE_NOT_READY,
                identity, noProgress()), 30_009);
        RecoveryWorkflow.Decision terminal = workflow.record(attempt(
                "r2", "p1", "wait", DiagnosticCode.STATE_NOT_READY,
                identity, noProgress()), 30_010);

        assertTrue(before.retryAllowed());
        assertEquals(DiagnosticCode.DEADLINE_EXCEEDED, terminal.terminalCode());
        assertEquals(30_000, terminal.terminalRecord().elapsedMillis());
    }

    @Test void terminalRecordSurvivesWorkflowAndStoreRecreation(
            @TempDir Path temporary) throws Exception {
        RecoveryWorkflow workflow = new RecoveryWorkflow(POLICY, 0);
        RecoveryWorkflow.Identity identity = identity("source-a", "runtime-a");
        RecoveryWorkflow.TerminalRecord terminal = null;
        for (int index = 1; index <= 3; index++) {
            terminal = workflow.record(attempt(
                    "r" + index, "p" + index, "bad",
                    DiagnosticCode.MISSING_ARGUMENT,
                    identity, noProgress()), index).terminalRecord();
        }
        TerminalRecordStore store =
                new TerminalRecordStore(temporary.resolve("terminal.json"));
        store.retain(terminal);

        TerminalRecordStore reopened =
                new TerminalRecordStore(temporary.resolve("terminal.json"));
        assertEquals(terminal, reopened.read());
        assertTrue(reopened.read().digest().matches("[0-9a-f]{64}"));
    }

    @Test void stateRetryThenRevisionProgressSucceedsWithoutResettingHardTotals() {
        RecoveryWorkflow workflow = new RecoveryWorkflow(POLICY, 0);
        RecoveryWorkflow.Identity firstIdentity = identity("source-a", "runtime-a");
        RecoveryWorkflow.Decision waiting = workflow.record(attempt(
                "r1", "p1", "wait", DiagnosticCode.STATE_NOT_READY,
                firstIdentity, noProgress()), 1);
        RecoveryWorkflow.Identity advanced = new RecoveryWorkflow.Identity(
                firstIdentity.sourceSha256(), firstIdentity.dependencySha256(),
                firstIdentity.toolchainSha256(),
                firstIdentity.buildConfigurationSha256(),
                firstIdentity.buildOutputSha256(),
                firstIdentity.launchConfigurationSha256(),
                firstIdentity.processId(), firstIdentity.sessionId(),
                firstIdentity.applicationSha256(), firstIdentity.viewportIdentity(),
                2, firstIdentity.runId(), true, true);
        RecoveryWorkflow.Decision ready = workflow.record(attempt(
                "r2", "p1", "wait", DiagnosticCode.NO_PROGRESS,
                advanced,
                new RecoveryWorkflow.Progress(
                        RecoveryWorkflow.Change.CHANGED,
                        RecoveryWorkflow.Change.UNCHANGED,
                        RecoveryWorkflow.Change.UNCHANGED,
                        RecoveryWorkflow.Change.UNCHANGED,
                        RecoveryWorkflow.Change.UNCHANGED,
                        RecoveryWorkflow.Change.UNCHANGED)), 2);

        assertTrue(waiting.retryAllowed());
        assertTrue(ready.retryAllowed());
        assertEquals(1, ready.counters().stateRetries());
        assertEquals(2, ready.counters().totalIterations());
        assertEquals(0, ready.counters().consecutiveNoProgress());
    }

    @Test void intentNormalizationIgnoresObjectOrderAndRequestProcessChurn() {
        LinkedHashMap<String, Object> first = new LinkedHashMap<>();
        first.put("requestId", "one");
        first.put("maxBytes", 1_024);
        first.put("sessionId", "game");
        LinkedHashMap<String, Object> second = new LinkedHashMap<>();
        second.put("sessionId", "game");
        second.put("maxBytes", 1_024);
        second.put("requestId", "two");
        second.put("processId", "replacement");

        assertEquals(
                RecoveryWorkflow.normalizeIntent(first),
                RecoveryWorkflow.normalizeIntent(second));
    }

    @Test void eachBuildAndRuntimeIdentityMutationInvalidatesOnlySafeReuse() {
        RecoveryWorkflow.Identity baseline = identity("source-a", "runtime-a");
        for (String field : List.of(
                "source", "dependency", "toolchain", "buildConfiguration",
                "buildOutput")) {
            assertEquals(
                    RecoveryWorkflow.Reuse.REBUILD_AND_RELAUNCH,
                    RecoveryWorkflow.reuse(baseline, mutate(baseline, field)),
                    field);
        }
        for (String field : List.of(
                "launchConfiguration", "process", "session",
                "application", "viewport", "revision")) {
            assertEquals(
                    RecoveryWorkflow.Reuse.RELAUNCH,
                    RecoveryWorkflow.reuse(baseline, mutate(baseline, field)),
                    field);
        }
    }

    private static RecoveryWorkflow.Attempt attempt(
            String requestId,
            String processId,
            String intent,
            DiagnosticCode code,
            RecoveryWorkflow.Identity identity,
            RecoveryWorkflow.Progress progress) {
        return new RecoveryWorkflow.Attempt(
                requestId, processId, "ui_screenshot", intent, code,
                identity, progress, Map.of());
    }

    private static RecoveryWorkflow.Progress noProgress() {
        return new RecoveryWorkflow.Progress(
                RecoveryWorkflow.Change.UNCHANGED,
                RecoveryWorkflow.Change.UNCHANGED,
                RecoveryWorkflow.Change.UNCHANGED,
                RecoveryWorkflow.Change.UNCHANGED,
                RecoveryWorkflow.Change.UNCHANGED,
                RecoveryWorkflow.Change.UNCHANGED);
    }

    private static RecoveryWorkflow.Identity identity(String source, String runtime) {
        return new RecoveryWorkflow.Identity(
                digest(source), digest("dependency"), digest("toolchain"),
                digest("build-config"), digest("build-output"),
                digest("launch-config"), runtime, "session-a",
                digest("application"), "1280x720@1", 1, "run-a", true, true);
    }

    private static RecoveryWorkflow.Identity mutate(
            RecoveryWorkflow.Identity value, String field) {
        String changed = digest("changed-" + field);
        return new RecoveryWorkflow.Identity(
                "source".equals(field) ? changed : value.sourceSha256(),
                "dependency".equals(field) ? changed : value.dependencySha256(),
                "toolchain".equals(field) ? changed : value.toolchainSha256(),
                "buildConfiguration".equals(field)
                        ? changed : value.buildConfigurationSha256(),
                "buildOutput".equals(field) ? changed : value.buildOutputSha256(),
                "launchConfiguration".equals(field)
                        ? changed : value.launchConfigurationSha256(),
                "process".equals(field) ? "changed-process" : value.processId(),
                "session".equals(field) ? "changed-session" : value.sessionId(),
                "application".equals(field) ? changed : value.applicationSha256(),
                "viewport".equals(field) ? "changed-viewport" : value.viewportIdentity(),
                "revision".equals(field) ? value.revision() + 1 : value.revision(),
                value.runId(), value.buildSuccessful(), value.runtimeHealthy());
    }

    private static String digest(String seed) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(
                            seed.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
