package dev.gdx.uiharness.lwjgl3;

import dev.gdx.uiharness.core.assertion.AssertionResult;
import dev.gdx.uiharness.core.matrix.MatrixCase;
import dev.gdx.uiharness.core.matrix.MatrixCaseResult;
import dev.gdx.uiharness.core.matrix.MatrixCaseStatus;
import dev.gdx.uiharness.core.matrix.MatrixDefinition;
import dev.gdx.uiharness.core.matrix.MatrixHiDpi;
import dev.gdx.uiharness.core.matrix.MatrixLimits;
import dev.gdx.uiharness.core.matrix.MatrixPlanner;
import dev.gdx.uiharness.core.matrix.MatrixReport;
import dev.gdx.uiharness.core.matrix.MatrixWindow;
import dev.gdx.uiharness.core.scenario.ScenarioRequest;
import dev.gdx.uiharness.core.scenario.ScenarioResult;
import dev.gdx.uiharness.core.time.Deadline;
import dev.gdx.uiharness.core.wait.WaitEngine;
import dev.gdx.uiharness.scene2d.Scene2dScenarioRunner;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/**
 * Sequentially executes one bounded display matrix. Each case starts the registered scenario,
 * evaluates every carried assertion through the shared wait engine, records exact observed
 * display parameters, and releases the scenario. Completed frames are pumped externally on the
 * render thread; this runner only observes their progress through the supplied stages.
 */
public final class Lwjgl3MatrixRunner implements AutoCloseable {
    private static final int MAX_RETAINED_RUNS = 8;

    /** Host-owned display-case applicator for one case. */
    public interface MatrixCaseApplicator {
        /**
         * Applies the case to the real application/window state before scenario acquisition.
         *
         * <p>On failure to apply (including an expired apply deadline), the implementation must
         * restore the original display state before throwing; the runner never observes a
         * partially applied case.
         *
         * @param matrixCase the case to apply
         * @param restartProfileId the runner's scenario restart profile
         * @param deadline the run deadline; application must not start when it is expired and
         *     the implementation may bound its waits to the remaining time
         */
        ApplyResult apply(MatrixCase matrixCase, String restartProfileId, Deadline deadline);

        /** Restores the pre-case display state after the case reaches a terminal state. */
        void restore();
    }

    /** Closed outcome of one case application. */
    public sealed interface ApplyResult permits ApplyResult.Applied, ApplyResult.Unsupported {
        /** The case was applied; {@code observed} holds the same-case observed settings. */
        record Applied(DisplayObservation observed) implements ApplyResult {
            /** Validates the observed settings. */
            public Applied {
                observed = Objects.requireNonNull(observed, "observed");
            }
        }

        /** The case was rejected before application with a bounded reason. */
        record Unsupported(String reason) implements ApplyResult {
            /** Validates the bounded reason. */
            public Unsupported {
                reason = Objects.requireNonNull(reason, "reason");
                if (reason.isBlank() || reason.length() > 512) {
                    throw new IllegalArgumentException(
                            "unsupported reason must be 1..512 characters");
                }
            }
        }
    }

    /** Observed display parameters, distinct from requested parameters. */
    public record DisplayObservation(
            MatrixWindow window, double uiScale, double devicePixelRatio, MatrixHiDpi hiDpiMode,
            String locale, String fontSetId, String restartProfileId) {
        /** Validates observed parameters. */
        public DisplayObservation {
            Objects.requireNonNull(window, "window");
            if (!Double.isFinite(uiScale) || uiScale <= 0.0) {
                throw new IllegalArgumentException("observed uiScale must be positive");
            }
            if (!Double.isFinite(devicePixelRatio) || devicePixelRatio <= 0.0) {
                throw new IllegalArgumentException("observed devicePixelRatio must be positive");
            }
            Objects.requireNonNull(hiDpiMode, "hiDpiMode");
            Objects.requireNonNull(locale, "locale");
            if (locale.isBlank() || locale.length() > 256) {
                throw new IllegalArgumentException(
                        "observed locale must be 1..256 characters");
            }
            Objects.requireNonNull(fontSetId, "fontSetId");
            if (fontSetId.length() > 256) {
                throw new IllegalArgumentException(
                        "observed fontSetId must be at most 256 characters");
            }
            Objects.requireNonNull(restartProfileId, "restartProfileId");
            if (restartProfileId.isBlank() || restartProfileId.length() > 256) {
                throw new IllegalArgumentException(
                        "observed restartProfileId must be 1..256 characters");
            }
        }
    }

    /** Immutable binding to the registered scenario that establishes each case's state. */
    public record Scenario(
            String scenarioId,
            long seed,
            Map<String, String> configuration,
            String profileId,
            String applicationId,
            String processId,
            String sessionId) {
        /** Validates the binding. */
        public Scenario {
            Objects.requireNonNull(scenarioId, "scenarioId");
            configuration = Map.copyOf(Objects.requireNonNull(configuration, "configuration"));
            Objects.requireNonNull(profileId, "profileId");
            Objects.requireNonNull(applicationId, "applicationId");
            Objects.requireNonNull(processId, "processId");
            Objects.requireNonNull(sessionId, "sessionId");
        }
    }

    private final Scene2dScenarioRunner scenarios;
    private final WaitEngine waits;
    private final MatrixCaseApplicator applicator;
    private final Scenario scenario;
    private final MatrixPlanner planner = new MatrixPlanner();
    private final Object lifecycle = new Object();
    private final LinkedHashMap<String, MatrixReport> retained = new LinkedHashMap<>();
    private boolean open = true;
    private boolean activeRun;

    /**
     * Creates a matrix runner.
     *
     * @param scenarios scenario lifecycle runner supplying per-case known state
     * @param waits shared wait engine evaluating carried assertions
     * @param applicator host-owned display-case applicator
     * @param scenario registered scenario binding
     */
    public Lwjgl3MatrixRunner(
            Scene2dScenarioRunner scenarios,
            WaitEngine waits,
            MatrixCaseApplicator applicator,
            Scenario scenario) {
        this.scenarios = Objects.requireNonNull(scenarios, "scenarios");
        this.waits = Objects.requireNonNull(waits, "waits");
        this.applicator = Objects.requireNonNull(applicator, "applicator");
        this.scenario = Objects.requireNonNull(scenario, "scenario");
    }

    /**
     * Plans and executes one bounded matrix, completing with the bounded run identifier once
     * every started case reaches a terminal state.
     *
     * <p>Only one matrix run may be active at a time because the host-owned applicator is
     * shared: a second concurrent run is rejected with {@code matrix run already active}
     * before any case is planned or applied, and admission is released again when the active
     * run reaches a terminal state on every path (normal, exceptional, or cancelled).
     *
     * @param definition immutable matrix definition
     * @param limits hard case bounds
     * @param deadline monotonic run deadline
     * @return a stage completing with the run identifier
     */
    public CompletionStage<String> run(MatrixDefinition definition, MatrixLimits limits,
            Deadline deadline) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(deadline, "deadline");
        synchronized (lifecycle) {
            if (!open) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("matrix runner is closed"));
            }
            if (activeRun) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("matrix run already active"));
            }
            activeRun = true;
        }
        final List<MatrixCase> cases;
        try {
            cases = planner.plan(definition, limits);
        } catch (IllegalArgumentException rejection) {
            releaseRun();
            return CompletableFuture.failedFuture(rejection);
        }
        String runId = "matrix-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        List<MatrixCaseResult> results = new ArrayList<>(cases.size());
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (MatrixCase matrixCase : cases) {
            chain = chain.thenCompose(ignored -> executeCase(matrixCase, deadline, results));
        }
        return chain.handle((ignored, failure) -> {
            releaseRun();
            if (failure != null) {
                throw new CompletionException(failure);
            }
            return ignored;
        }).thenApply(ignored -> {
            MatrixReport report = new MatrixReport(runId, definition.scenarioId(),
                    List.copyOf(results), false);
            synchronized (lifecycle) {
                if (!open) {
                    throw new IllegalStateException("matrix runner is closed");
                }
                retained.put(runId, report);
                while (retained.size() > MAX_RETAINED_RUNS) {
                    retained.remove(retained.keySet().iterator().next());
                }
            }
            return runId;
        });
    }

    private void releaseRun() {
        synchronized (lifecycle) {
            activeRun = false;
        }
    }

    /** Returns the compact retained report for one run, or empty when not retained. */
    public Optional<MatrixReport> results(String runId) {
        Objects.requireNonNull(runId, "runId");
        synchronized (lifecycle) {
            return Optional.ofNullable(retained.get(runId));
        }
    }

    private CompletionStage<Void> executeCase(
            MatrixCase matrixCase, Deadline deadline, List<MatrixCaseResult> results) {
        if (deadline.isExpired() || !open) {
            results.add(new MatrixCaseResult(
                    dev.gdx.uiharness.core.matrix.MatrixCaseSummary.of(matrixCase),
                    deadline.isExpired() ? MatrixCaseStatus.UNSTARTED
                            : MatrixCaseStatus.CANCELLED,
                    null, null, null, null, null, null, null,
                    List.of(), List.of(), List.of(), ""));
            return CompletableFuture.completedFuture(null);
        }
        ApplyResult applied;
        try {
            applied = applicator.apply(matrixCase, scenario.profileId(), deadline);
        } catch (RuntimeException failure) {
            results.add(new MatrixCaseResult(
                    dev.gdx.uiharness.core.matrix.MatrixCaseSummary.of(matrixCase),
                    MatrixCaseStatus.FAILED,
                    null, null, null, null, null, null, null,
                    List.of(), List.of(), List.of(),
                    bounded("case application failed: " + rootMessage(failure))));
            return CompletableFuture.completedFuture(null);
        }
        if (applied instanceof ApplyResult.Unsupported unsupported) {
            results.add(new MatrixCaseResult(
                    dev.gdx.uiharness.core.matrix.MatrixCaseSummary.of(matrixCase),
                    MatrixCaseStatus.UNSUPPORTED,
                    null, null, null, null, null, null, null,
                    List.of(), List.of(), List.of(),
                    bounded("unsupported case: " + unsupported.reason())));
            return CompletableFuture.completedFuture(null);
        }
        DisplayObservation observed = ((ApplyResult.Applied) applied).observed();
        String mismatch = requestedMismatch(matrixCase, observed, scenario.profileId());
        if (mismatch != null) {
            // The case was applied but does not match the request: restore the original display
            // state so the next case starts clean, then record the distinct terminal status. A
            // restore failure upgrades the terminal to FAILED with the restore evidence.
            return terminalAfterRestore(matrixCase, observed, results,
                    new MatrixCaseResult(
                            dev.gdx.uiharness.core.matrix.MatrixCaseSummary.of(matrixCase),
                            MatrixCaseStatus.MISAPPLIED,
                            observed.window(), observed.uiScale(), observed.devicePixelRatio(),
                            observed.hiDpiMode(), observed.locale(), observed.fontSetId(),
                            observed.restartProfileId(),
                            List.of(), List.of(), List.of(),
                            bounded("requested state not applied: " + mismatch)));
        }
        ScenarioRequest request = new ScenarioRequest(
                dev.gdx.uiharness.core.scenario.ScenarioDefinition.SCHEMA_VERSION,
                scenario.scenarioId(),
                scenario.seed(),
                scenario.configuration(),
                scenario.profileId(),
                deadline);
        CompletionStage<MatrixCaseResult> terminal;
        try {
            terminal = scenarios.acquire(request, scenario.applicationId(),
                    scenario.processId(), scenario.sessionId())
                    .thenCompose(lease -> runAssertions(matrixCase, lease, deadline, observed))
                    .handle((result, failure) -> failure != null
                            ? failureResult(matrixCase, observed, failure)
                            : result);
        } catch (RuntimeException acquireFailure) {
            // acquire threw synchronously before returning a stage: the case never started.
            terminal = CompletableFuture.completedFuture(
                    failureResult(matrixCase, observed, acquireFailure));
        }
        return terminal.handle((result, failure) -> {
            // Restore exactly once on every terminal path; a restore failure must never escape
            // the handler or abort the report and the next case.
            Throwable restoreFailure;
            try {
                applicator.restore();
                restoreFailure = null;
            } catch (RuntimeException restoration) {
                restoreFailure = restoration;
            }
            MatrixCaseResult base = failure != null
                    ? failureResult(matrixCase, observed, failure) : result;
            results.add(restoreAware(base, restoreFailure));
            return null;
        });
    }

    /** Restores the display exactly once and records the terminal, upgrading on restore failure. */
    private CompletionStage<Void> terminalAfterRestore(
            MatrixCase matrixCase, DisplayObservation observed, List<MatrixCaseResult> results,
            MatrixCaseResult base) {
        Throwable restoreFailure;
        try {
            applicator.restore();
            restoreFailure = null;
        } catch (RuntimeException restoration) {
            restoreFailure = restoration;
        }
        results.add(restoreAware(base, restoreFailure));
        return CompletableFuture.completedFuture(null);
    }

    private static MatrixCaseResult failureResult(
            MatrixCase matrixCase, DisplayObservation observed, Throwable failure) {
        return new MatrixCaseResult(
                dev.gdx.uiharness.core.matrix.MatrixCaseSummary.of(matrixCase),
                MatrixCaseStatus.FAILED,
                observed.window(), observed.uiScale(), observed.devicePixelRatio(),
                observed.hiDpiMode(), observed.locale(), observed.fontSetId(),
                observed.restartProfileId(),
                List.of(), List.of(), List.of(),
                bounded(rootMessage(failure)));
    }

    private static MatrixCaseResult restoreAware(MatrixCaseResult base, Throwable restoreFailure) {
        if (restoreFailure == null) {
            return base;
        }
        String restoreEvidence = "display restore failed: " + rootMessage(restoreFailure);
        if (base.status() == MatrixCaseStatus.FAILED && !base.evidence().isEmpty()) {
            // Aggregate the restore failure after the primary failure, retaining both within the
            // evidence bound (established primary/suffix aggregation pattern).
            return withStatusAndEvidence(base, MatrixCaseStatus.FAILED,
                    composeWithSuffix(base.evidence(), " (" + restoreEvidence + ")"));
        }
        if (!base.evidence().isEmpty()) {
            // A non-failed terminal (e.g. misapplied) with a restore failure: the restore failure
            // becomes the primary failure; the prior classification is retained as suffix evidence.
            return withStatusAndEvidence(base, MatrixCaseStatus.FAILED,
                    composeWithSuffix(bounded(restoreEvidence), " (" + base.evidence() + ")"));
        }
        // An otherwise-passing case: the restore failure is the primary failure.
        return withStatusAndEvidence(base, MatrixCaseStatus.FAILED, bounded(restoreEvidence));
    }

    private static MatrixCaseResult withStatusAndEvidence(
            MatrixCaseResult base, MatrixCaseStatus status, String evidence) {
        return new MatrixCaseResult(
                base.caseSummary(), status,
                base.observedWindow(), base.observedUiScale(), base.observedDevicePixelRatio(),
                base.observedHiDpiMode(), base.observedLocale(), base.observedFontSetId(),
                base.observedRestartProfileId(),
                base.passedAssertions(), base.failedAssertions(), base.artifactReferences(),
                evidence);
    }

    private static String requestedMismatch(
            MatrixCase matrixCase, DisplayObservation observed, String requestedRestartProfile) {
        if (!observed.window().equals(matrixCase.window())) {
            return "window requested=" + matrixCase.window()
                    + " observed=" + observed.window();
        }
        if (!nearlyEqual(observed.uiScale(), matrixCase.uiScale())) {
            return "uiScale requested=" + matrixCase.uiScale()
                    + " observed=" + observed.uiScale();
        }
        if (!nearlyEqual(observed.devicePixelRatio(), matrixCase.devicePixelRatio())) {
            return "devicePixelRatio requested=" + matrixCase.devicePixelRatio()
                    + " observed=" + observed.devicePixelRatio();
        }
        if (observed.hiDpiMode() != matrixCase.hiDpiMode()) {
            return "hiDpiMode requested=" + matrixCase.hiDpiMode()
                    + " observed=" + observed.hiDpiMode();
        }
        if (!observed.locale().equals(matrixCase.locale())) {
            return "locale requested=" + matrixCase.locale()
                    + " observed=" + observed.locale();
        }
        if (!observed.fontSetId().equals(matrixCase.fontSetId())) {
            return "fontSetId requested=" + matrixCase.fontSetId()
                    + " observed=" + observed.fontSetId();
        }
        if (!observed.restartProfileId().equals(requestedRestartProfile)) {
            return "restartProfile requested=" + requestedRestartProfile
                    + " observed=" + observed.restartProfileId();
        }
        return null;
    }

    private static boolean nearlyEqual(double first, double second) {
        return Math.abs(first - second) <= 1e-9;
    }

    private CompletionStage<MatrixCaseResult> runAssertions(
            MatrixCase matrixCase, Scene2dScenarioRunner.Lease lease, Deadline deadline,
            DisplayObservation observed) {
        var passed = new ArrayList<Integer>();
        var failed = new ArrayList<Integer>();
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        List<dev.gdx.uiharness.core.assertion.AssertionRequest> assertions =
                matrixCase.assertions();
        for (int index = 0; index < assertions.size(); index++) {
            final int assertionIndex = index;
            chain = chain.thenCompose(ignored ->
                    waits.assertThat(assertions.get(assertionIndex)).thenApply(result -> {
                        if (result.status() == AssertionResult.Status.PASSED) {
                            passed.add(assertionIndex);
                        } else {
                            failed.add(assertionIndex);
                        }
                        return null;
                    }));
        }
        // Release the lease on every terminal path, including an exceptionally completed
        // assertion stage, before producing the case terminal result. A release that completes
        // normally with an unclean terminal result (cleanup failure) is treated as a release
        // failure so a passing case cannot hide it.
        return chain.handle((ignored, assertionFailure) -> assertionFailure)
                .thenCompose(assertionFailure -> {
                    CompletionStage<ScenarioResult> released;
                    try {
                        released = lease.release();
                    } catch (RuntimeException failure) {
                        return CompletableFuture.completedFuture(
                                terminalCase(matrixCase, passed, failed,
                                        assertionFailure, failure, observed));
                    }
                    return released.handle((releasedResult, releaseFailure) ->
                            terminalCase(matrixCase, passed, failed, assertionFailure,
                                    releaseFailure(releasedResult, releaseFailure), observed));
                });
    }

    private static Throwable releaseFailure(ScenarioResult released, Throwable failure) {
        if (failure != null) {
            return failure;
        }
        if (released != null && released.failure().isPresent()) {
            return new IllegalStateException(
                    "scenario did not terminate cleanly: " + released.failure().orElseThrow());
        }
        return null;
    }

    private MatrixCaseResult terminalCase(
            MatrixCase matrixCase,
            List<Integer> passed,
            List<Integer> failed,
            Throwable assertionFailure,
            Throwable releaseFailure,
            DisplayObservation observed) {
        boolean succeeded = assertionFailure == null && releaseFailure == null && failed.isEmpty();
        MatrixCaseStatus status = succeeded ? MatrixCaseStatus.PASSED : MatrixCaseStatus.FAILED;
        String evidence = "";
        if (assertionFailure != null) {
            // Preserve the original assertion failure as primary when release also fails. Reserve
            // suffix space so the cleanup classification is never truncated away by the bound.
            evidence = bounded(rootMessage(assertionFailure));
            if (releaseFailure != null) {
                evidence = composeWithSuffix(evidence,
                        " (lease release failed: " + rootMessage(releaseFailure) + ")");
            }
        } else if (releaseFailure != null) {
            evidence = bounded("lease release failed: " + rootMessage(releaseFailure));
        } else if (!failed.isEmpty()) {
            evidence = "assertions failed: " + failed.size();
        }
        return new MatrixCaseResult(
                dev.gdx.uiharness.core.matrix.MatrixCaseSummary.of(matrixCase),
                status,
                observed.window(),
                observed.uiScale(),
                observed.devicePixelRatio(),
                observed.hiDpiMode(),
                observed.locale(),
                observed.fontSetId(),
                observed.restartProfileId(),
                List.copyOf(passed),
                List.copyOf(failed),
                List.of(),
                evidence);
    }

    private static final int MAX_EVIDENCE_LENGTH = 512;

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return bounded(current.getMessage());
    }

    /**
     * Appends {@code suffix} to {@code primary} within {@link #MAX_EVIDENCE_LENGTH}. Short
     * suffixes (bounded classifications such as cleanup) are always retained in full, truncating
     * only the primary tail to make room. A suffix that alone nearly or fully exhausts the bound
     * must never replace the primary: the primary prefix is kept and the longest suffix prefix
     * that fits is appended, reserving at least half the bound for the primary.
     */
    private static String composeWithSuffix(String primary, String suffix) {
        if (primary.length() + suffix.length() <= MAX_EVIDENCE_LENGTH) {
            return primary + suffix;
        }
        int suffixBudget = Math.min(suffix.length(), MAX_EVIDENCE_LENGTH / 2);
        int primaryBudget = MAX_EVIDENCE_LENGTH - suffixBudget;
        int primaryKeep = Math.min(primaryBudget, primary.length());
        return primary.substring(0, primaryKeep) + suffix.substring(0, suffixBudget);
    }

    private static String bounded(String value) {
        if (value == null) {
            return "case failed";
        }
        return value.length() <= MAX_EVIDENCE_LENGTH
                ? value : value.substring(0, MAX_EVIDENCE_LENGTH);
    }

    @Override public void close() {
        synchronized (lifecycle) {
            open = false;
        }
    }
}
