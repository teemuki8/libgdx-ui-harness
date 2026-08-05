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
import java.util.concurrent.CompletionStage;

/**
 * Sequentially executes one bounded display matrix. Each case starts the registered scenario,
 * evaluates every carried assertion through the shared wait engine, records exact observed
 * display parameters, and releases the scenario. Completed frames are pumped externally on the
 * render thread; this runner only observes their progress through the supplied stages.
 */
public final class Lwjgl3MatrixRunner implements AutoCloseable {
    private static final int MAX_RETAINED_RUNS = 8;

    /** Application-owned display parameter observer for one case. */
    public interface DisplayObserver {
        /** Returns the observed window, scale, DPR, and HiDPI mode for one case. */
        DisplayObservation observe(MatrixCase matrixCase);
    }

    /** Observed display parameters, distinct from requested parameters. */
    public record DisplayObservation(
            MatrixWindow window, double uiScale, double devicePixelRatio, MatrixHiDpi hiDpiMode) {
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
    private final DisplayObserver display;
    private final Scenario scenario;
    private final MatrixPlanner planner = new MatrixPlanner();
    private final Object lifecycle = new Object();
    private final LinkedHashMap<String, MatrixReport> retained = new LinkedHashMap<>();
    private boolean open = true;

    /**
     * Creates a matrix runner.
     *
     * @param scenarios scenario lifecycle runner supplying per-case known state
     * @param waits shared wait engine evaluating carried assertions
     * @param display application-owned display parameter observer
     * @param scenario registered scenario binding
     */
    public Lwjgl3MatrixRunner(
            Scene2dScenarioRunner scenarios,
            WaitEngine waits,
            DisplayObserver display,
            Scenario scenario) {
        this.scenarios = Objects.requireNonNull(scenarios, "scenarios");
        this.waits = Objects.requireNonNull(waits, "waits");
        this.display = Objects.requireNonNull(display, "display");
        this.scenario = Objects.requireNonNull(scenario, "scenario");
    }

    /**
     * Plans and executes one bounded matrix, completing with the bounded run identifier once
     * every started case reaches a terminal state.
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
        final List<MatrixCase> cases;
        try {
            cases = planner.plan(definition, limits);
        } catch (IllegalArgumentException rejection) {
            return CompletableFuture.failedFuture(rejection);
        }
        String runId = "matrix-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        List<MatrixCaseResult> results = new ArrayList<>(cases.size());
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (MatrixCase matrixCase : cases) {
            chain = chain.thenCompose(ignored -> executeCase(matrixCase, deadline, results));
        }
        return chain.thenApply(ignored -> {
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
                    null, null, null, null,
                    List.of(), List.of(), List.of(), ""));
            return CompletableFuture.completedFuture(null);
        }
        ScenarioRequest request = new ScenarioRequest(
                dev.gdx.uiharness.core.scenario.ScenarioDefinition.SCHEMA_VERSION,
                scenario.scenarioId(),
                scenario.seed(),
                scenario.configuration(),
                scenario.profileId(),
                deadline);
        return scenarios.acquire(request, scenario.applicationId(),
                scenario.processId(), scenario.sessionId())
                .thenCompose(lease -> runAssertions(matrixCase, lease, deadline))
                .handle((result, failure) -> {
                    if (failure != null) {
                        results.add(new MatrixCaseResult(
                                dev.gdx.uiharness.core.matrix.MatrixCaseSummary.of(matrixCase),
                                MatrixCaseStatus.FAILED,
                                null, null, null, null,
                                List.of(), List.of(), List.of(),
                                bounded(rootMessage(failure))));
                    } else {
                        results.add(result);
                    }
                    return null;
                });
    }

    private CompletionStage<MatrixCaseResult> runAssertions(
            MatrixCase matrixCase, Scene2dScenarioRunner.Lease lease, Deadline deadline) {
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
        return chain.thenCompose(ignored -> lease.release().handle((ignoredResult, failure) -> {
            DisplayObservation observed = display.observe(matrixCase);
            MatrixCaseStatus status = failed.isEmpty()
                    ? MatrixCaseStatus.PASSED : MatrixCaseStatus.FAILED;
            return new MatrixCaseResult(
                    dev.gdx.uiharness.core.matrix.MatrixCaseSummary.of(matrixCase),
                    status,
                    observed.window(),
                    observed.uiScale(),
                    observed.devicePixelRatio(),
                    observed.hiDpiMode(),
                    List.copyOf(passed),
                    List.copyOf(failed),
                    List.of(),
                    failed.isEmpty() ? "" : "assertions failed: " + failed.size());
        }));
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return bounded(current.getMessage());
    }

    private static String bounded(String value) {
        if (value == null) {
            return "case failed";
        }
        return value.length() <= 512 ? value : value.substring(0, 512);
    }

    @Override public void close() {
        synchronized (lifecycle) {
            open = false;
        }
    }
}
