package dev.gdx.uiharness.protocol;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Deterministic workflow-level progress, reuse, loop, and hard-budget accounting. */
public final class RecoveryWorkflow {
    private final RecoveryPolicy policy;
    private final long startedAtMillis;
    private int totalIterations;
    private int schemaRecoveries;
    private int stateRetries;
    private int unchangedInspectCycles;
    private int unchangedBuilds;
    private int unchangedLaunches;
    private int consecutiveNoProgress;
    private String previousFingerprint;
    private boolean terminal;
    private TerminalRecord terminalRecord;

    /** Starts one isolated recovery workflow at an injected monotonic timestamp. */
    public RecoveryWorkflow(RecoveryPolicy policy, long startedAtMillis) {
        this.policy = Objects.requireNonNull(policy, "policy");
        if (startedAtMillis < 0) {
            throw new IllegalArgumentException("startedAtMillis must be non-negative");
        }
        this.startedAtMillis = startedAtMillis;
    }

    /** Records one attempted iteration and makes one terminal-or-continue decision. */
    public synchronized Decision record(Attempt attempt, long nowMillis) {
        Objects.requireNonNull(attempt, "attempt");
        if (terminal) {
            throw new IllegalStateException("workflow already terminated");
        }
        if (nowMillis < startedAtMillis) {
            throw new IllegalArgumentException("monotonic time moved backwards");
        }
        totalIterations++;
        if (isSchema(attempt.code())) {
            schemaRecoveries++;
        }
        if (attempt.code() == DiagnosticCode.STATE_NOT_READY
                || attempt.code() == DiagnosticCode.STALE_REVISION) {
            stateRetries++;
        }
        boolean progress = attempt.progress().hasProgress();
        consecutiveNoProgress = progress ? 0 : consecutiveNoProgress + 1;
        if (!progress && "ui_inspect_compare".equals(attempt.operation())) {
            unchangedInspectCycles++;
        }
        if (attempt.buildAttempted()
                && attempt.progress().build() != Change.CHANGED) {
            unchangedBuilds++;
        }
        if (attempt.launchAttempted()
                && attempt.progress().runtime() != Change.CHANGED) {
            unchangedLaunches++;
        }
        String fingerprint = attempt.fingerprint();
        boolean equivalent = fingerprint.equals(previousFingerprint);
        previousFingerprint = fingerprint;
        long elapsed = nowMillis - startedAtMillis;

        DiagnosticCode terminalCode = null;
        String rule = "continue";
        if (elapsed >= policy.maxWallTimeMillis()) {
            terminalCode = DiagnosticCode.DEADLINE_EXCEEDED;
            rule = "max-wall-time/v1";
        } else if (isSchema(attempt.code())
                && schemaRecoveries > policy.maxSchemaRecoveries()) {
            terminalCode = equivalent
                    ? DiagnosticCode.LOOP_DETECTED
                    : DiagnosticCode.RECOVERY_BUDGET_EXHAUSTED;
            rule = equivalent
                    ? "equivalent-schema-error/v1"
                    : "max-schema-recoveries/v1";
        } else if (stateRetries > policy.maxStateRetries()) {
            terminalCode = DiagnosticCode.RECOVERY_BUDGET_EXHAUSTED;
            rule = "max-state-retries/v1";
        } else if (unchangedInspectCycles > policy.maxUnchangedInspectCycles()) {
            terminalCode = DiagnosticCode.NO_PROGRESS;
            rule = "max-unchanged-inspect-cycles/v1";
        } else if (unchangedBuilds > policy.maxUnchangedBuilds()) {
            terminalCode = DiagnosticCode.RECOVERY_BUDGET_EXHAUSTED;
            rule = "max-unchanged-builds/v1";
        } else if (unchangedLaunches > policy.maxUnchangedLaunches()) {
            terminalCode = DiagnosticCode.RECOVERY_BUDGET_EXHAUSTED;
            rule = "max-unchanged-launches/v1";
        } else if (attempt.code().defaultDisposition()
                == DiagnosticEnvelope.Disposition.TERMINAL) {
            terminalCode = attempt.code();
            rule = "terminal-code/v1";
        }
        Counters counters = counters();
        if (terminalCode != null) {
            terminal = true;
            terminalRecord = TerminalRecord.create(
                    terminalCode, rule, counters, attempt, elapsed);
        }
        return new Decision(
                terminalCode == null, terminalCode, rule, counters,
                terminalRecord);
    }

    /** Returns the last digest-bound terminal record, if termination occurred. */
    public synchronized TerminalRecord terminalRecord() {
        return terminalRecord;
    }

    private Counters counters() {
        return new Counters(
                totalIterations,
                schemaRecoveries,
                Math.max(0, policy.maxSchemaRecoveries() - schemaRecoveries),
                stateRetries,
                Math.max(0, policy.maxStateRetries() - stateRetries),
                unchangedInspectCycles,
                unchangedBuilds,
                unchangedLaunches,
                consecutiveNoProgress);
    }

    private static boolean isSchema(DiagnosticCode code) {
        return switch (code) {
            case MISSING_ARGUMENT, UNKNOWN_ARGUMENT, INVALID_ARGUMENT_TYPE,
                    OUT_OF_RANGE, INVALID_ENUM_VALUE, SCHEMA_CONFLICT -> true;
            default -> false;
        };
    }

    /** Produces a bounded semantic intent identity independent of map and churn IDs. */
    public static String normalizeIntent(Map<String, Object> payload) {
        Objects.requireNonNull(payload, "payload");
        try {
            return sha256(ProtocolJson.mapper().writeValueAsString(canonical(payload)));
        } catch (Exception failure) {
            throw new IllegalArgumentException("intent could not be normalized", failure);
        }
    }

    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            map.forEach((key, nested) -> {
                if (key instanceof String name
                        && !"requestId".equals(name)
                        && !"processId".equals(name)) {
                    sorted.put(name, canonical(nested));
                }
            });
            return sorted;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(RecoveryWorkflow::canonical).toList();
        }
        return value;
    }

    /** Selects safe build/runtime reuse from complete immutable identities. */
    public static Reuse reuse(Identity previous, Identity current) {
        return reuseDecision(previous, current).reuse();
    }

    /** Selects reuse and records the first deterministic invalidation reason. */
    public static ReuseDecision reuseDecision(Identity previous, Identity current) {
        if (previous == null || current == null) {
            return new ReuseDecision(
                    Reuse.REBUILD_AND_RELAUNCH, "unknown-identity/v1");
        }
        if (!previous.runId().equals(current.runId())) {
            return new ReuseDecision(
                    Reuse.REBUILD_AND_RELAUNCH, "cross-run-identity/v1");
        }
        if (!previous.buildSuccessful() || !current.buildSuccessful()) {
            return new ReuseDecision(
                    Reuse.REBUILD_AND_RELAUNCH, "failed-build/v1");
        }
        if (!previous.buildIdentity().equals(current.buildIdentity())) {
            return new ReuseDecision(
                    Reuse.REBUILD_AND_RELAUNCH, "build-identity-changed/v1");
        }
        if (!previous.runtimeHealthy() || !current.runtimeHealthy()) {
            return new ReuseDecision(Reuse.RELAUNCH, "runtime-unhealthy/v1");
        }
        if (!previous.runtimeIdentity().equals(current.runtimeIdentity())) {
            return new ReuseDecision(Reuse.RELAUNCH, "runtime-identity-changed/v1");
        }
        return new ReuseDecision(
                Reuse.REUSE_BUILD_AND_RUNTIME, "identical-healthy-state/v1");
    }

    public enum Change {
        CHANGED, UNCHANGED, UNAVAILABLE
    }

    public enum Reuse {
        REBUILD_AND_RELAUNCH, RELAUNCH, REUSE_BUILD_AND_RUNTIME
    }

    /** Reuse action plus a stable auditable reason. */
    public record ReuseDecision(Reuse reuse, String reason) {
        public ReuseDecision {
            Objects.requireNonNull(reuse, "reuse");
            if (reason == null || reason.isBlank()
                    || reason.length() > ProtocolJson.MAX_STRING_LENGTH) {
                throw new IllegalArgumentException("invalid reuse reason");
            }
        }
    }

    /** Separate semantic, visual, source, build, runtime, and evaluator deltas. */
    public record Progress(
            Change semanticAssertions,
            Change semanticState,
            Change visualCapture,
            Change comparison,
            Change artifact,
            Change transitions,
            Change source,
            Change build,
            Change runtime,
            Change humanFidelity) {
        public Progress(
                Change semantic,
                Change visual,
                Change artifact,
                Change source,
                Change build,
                Change runtime) {
            this(
                    semantic, semantic, visual, visual, artifact, semantic,
                    source, build, runtime, Change.UNAVAILABLE);
        }

        public Progress {
            Objects.requireNonNull(semanticAssertions, "semanticAssertions");
            Objects.requireNonNull(semanticState, "semanticState");
            Objects.requireNonNull(visualCapture, "visualCapture");
            Objects.requireNonNull(comparison, "comparison");
            Objects.requireNonNull(artifact, "artifact");
            Objects.requireNonNull(transitions, "transitions");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(build, "build");
            Objects.requireNonNull(runtime, "runtime");
            Objects.requireNonNull(humanFidelity, "humanFidelity");
        }

        boolean hasProgress() {
            return List.of(
                    semanticAssertions, semanticState, visualCapture, comparison,
                    artifact, transitions, source, build, runtime)
                    .contains(Change.CHANGED);
        }
    }

    /** Complete immutable identities needed for safe reuse and progress checks. */
    public record Identity(
            String sourceSha256,
            String dependencySha256,
            String toolchainSha256,
            String buildConfigurationSha256,
            String buildOutputSha256,
            String launchConfigurationSha256,
            String processId,
            String sessionId,
            String applicationSha256,
            String viewportIdentity,
            long revision,
            String runId,
            boolean buildSuccessful,
            boolean runtimeHealthy) {
        public Identity {
            for (String digest : List.of(
                    sourceSha256, dependencySha256, toolchainSha256,
                    buildConfigurationSha256, buildOutputSha256,
                    launchConfigurationSha256, applicationSha256)) {
                if (digest == null || !digest.matches("[0-9a-f]{64}")) {
                    throw new IllegalArgumentException("invalid identity digest");
                }
            }
            for (String value : List.of(
                    processId, sessionId, viewportIdentity, runId)) {
                if (value == null || value.isBlank()
                        || value.length() > ProtocolJson.MAX_STRING_LENGTH) {
                    throw new IllegalArgumentException("invalid identity text");
                }
            }
            if (revision < 0) {
                throw new IllegalArgumentException("revision must be non-negative");
            }
        }

        private String buildIdentity() {
            return String.join(":", sourceSha256, dependencySha256, toolchainSha256,
                    buildConfigurationSha256, buildOutputSha256);
        }

        private String runtimeIdentity() {
            return String.join(":", launchConfigurationSha256, processId, sessionId,
                    applicationSha256, viewportIdentity, Long.toString(revision));
        }
    }

    /** One normalized attempt; request and process IDs do not define equivalence. */
    public record Attempt(
            String requestId,
            String processId,
            String operation,
            String normalizedIntent,
            DiagnosticCode code,
            Identity identity,
            Progress progress,
            Map<String, String> evidence,
            boolean buildAttempted,
            boolean launchAttempted) {
        public Attempt(
                String requestId,
                String processId,
                String operation,
                String normalizedIntent,
                DiagnosticCode code,
                Identity identity,
                Progress progress,
                Map<String, String> evidence) {
            this(requestId, processId, operation, normalizedIntent, code,
                    identity, progress, evidence, false, false);
        }

        public Attempt {
            for (String value : List.of(
                    requestId, processId, operation, normalizedIntent)) {
                if (value == null || value.isBlank()
                        || value.length() > ProtocolJson.MAX_STRING_LENGTH) {
                    throw new IllegalArgumentException("invalid attempt text");
                }
            }
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(progress, "progress");
            evidence = Map.copyOf(Objects.requireNonNull(evidence, "evidence"));
            if (evidence.size() > 256) {
                throw new IllegalArgumentException("too much attempt evidence");
            }
        }

        /** Returns the same immutable attempt with explicit build/launch cost events. */
        public Attempt withCosts(boolean build, boolean launch) {
            return new Attempt(
                    requestId, processId, operation, normalizedIntent,
                    code, identity, progress, evidence, build, launch);
        }

        private String fingerprint() {
            return String.join(":", operation, normalizedIntent, code.name(),
                    identity.sourceSha256(), identity.dependencySha256(),
                    identity.toolchainSha256(), identity.buildConfigurationSha256(),
                    identity.buildOutputSha256(), identity.launchConfigurationSha256(),
                    identity.applicationSha256(), identity.viewportIdentity(),
                    Long.toString(identity.revision()), identity.runId());
        }
    }

    /** Monotonic consumed and remaining counters. */
    public record Counters(
            int totalIterations,
            int schemaRecoveries,
            int remainingSchemaRecoveries,
            int stateRetries,
            int remainingStateRetries,
            int unchangedInspectCycles,
            int unchangedBuilds,
            int unchangedLaunches,
            int consecutiveNoProgress) {}

    /** Continue-or-stop decision at the exact observed boundary. */
    public record Decision(
            boolean retryAllowed,
            DiagnosticCode terminalCode,
            String ruleId,
            Counters counters,
            TerminalRecord terminalRecord) {}

    /** Digest-bound terminal explanation retained after workflow shutdown. */
    public record TerminalRecord(
            String schemaVersion,
            String digest,
            DiagnosticCode code,
            String ruleId,
            Counters counters,
            Attempt lastAttempt,
            long elapsedMillis) {
        public TerminalRecord {
            if (!"terminal-recovery-record/v1".equals(schemaVersion)
                    || digest == null || !digest.matches("[0-9a-f]{64}")
                    || code == null || ruleId == null || ruleId.isBlank()
                    || counters == null || lastAttempt == null || elapsedMillis < 0) {
                throw new IllegalArgumentException("invalid terminal recovery record");
            }
        }

        public static TerminalRecord create(
                DiagnosticCode code,
                String ruleId,
                Counters counters,
                Attempt attempt,
                long elapsedMillis) {
            return new TerminalRecord(
                    "terminal-recovery-record/v1",
                    digest(code, ruleId, counters, attempt, elapsedMillis),
                    code,
                    ruleId,
                    counters,
                    attempt,
                    elapsedMillis);
        }

        /** Verifies the record independently after persistence or transport. */
        public boolean hasValidDigest() {
            return digest.equals(digest(
                    code, ruleId, counters, lastAttempt, elapsedMillis));
        }

        private static String digest(
                DiagnosticCode code,
                String ruleId,
                Counters counters,
                Attempt attempt,
                long elapsedMillis) {
            try {
                return sha256(ProtocolJson.mapper().writeValueAsString(Map.of(
                        "code", code.name(),
                        "ruleId", ruleId,
                        "counters", counters,
                        "attempt", attempt,
                        "elapsedMillis", elapsedMillis)));
            } catch (Exception failure) {
                throw new IllegalArgumentException(
                        "terminal record could not be digested", failure);
            }
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
