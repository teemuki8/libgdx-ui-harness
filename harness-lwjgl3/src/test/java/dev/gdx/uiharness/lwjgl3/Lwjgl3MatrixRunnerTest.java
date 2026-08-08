package dev.gdx.uiharness.lwjgl3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gdx.uiharness.core.assertion.AssertionRequest;
import dev.gdx.uiharness.core.assertion.AssertionSnapshotSource;
import dev.gdx.uiharness.core.assertion.UiAssertion;
import dev.gdx.uiharness.core.locator.Locator;
import dev.gdx.uiharness.core.locator.StrictResolution;
import dev.gdx.uiharness.core.matrix.MatrixCaseResult;
import dev.gdx.uiharness.core.matrix.MatrixCaseStatus;
import dev.gdx.uiharness.core.matrix.MatrixDefinition;
import dev.gdx.uiharness.core.matrix.MatrixHiDpi;
import dev.gdx.uiharness.core.matrix.MatrixLimits;
import dev.gdx.uiharness.core.matrix.MatrixReport;
import dev.gdx.uiharness.core.matrix.MatrixWindow;
import dev.gdx.uiharness.core.model.Bounds;
import dev.gdx.uiharness.core.model.Role;
import dev.gdx.uiharness.core.model.SemanticNode;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import dev.gdx.uiharness.core.model.SemanticState;
import dev.gdx.uiharness.core.scenario.ScenarioDefinition;
import dev.gdx.uiharness.core.scenario.ScenarioLifecycle;
import dev.gdx.uiharness.core.scenario.ScenarioRegistry;
import dev.gdx.uiharness.core.scenario.ScenarioRequest;
import dev.gdx.uiharness.core.time.Deadline;
import dev.gdx.uiharness.core.wait.FrameSignal;
import dev.gdx.uiharness.core.wait.WaitEngine;
import dev.gdx.uiharness.core.time.DeadlineScheduler;
import dev.gdx.uiharness.core.time.MonotonicClock;
import dev.gdx.uiharness.scene2d.RenderThreadScheduler;
import dev.gdx.uiharness.scene2d.Scene2dScenarioRunner;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class Lwjgl3MatrixRunnerTest {
    @Test void matrixRunsCasesSequentiallyWithAssertionFanOutAndExactProvenance() {
        try (Fixture fixture = new Fixture()) {
            MatrixDefinition definition = new MatrixDefinition(
                    1,
                    "matrix",
                    List.of(new MatrixWindow(1280, 720), new MatrixWindow(1920, 1080)),
                    List.of(1.0),
                    List.of(1.0),
                    List.of(MatrixHiDpi.LOGICAL),
                    List.of("en"),
                    List.of(),
                    List.of(new AssertionRequest(1, Locator.testId("save"),
                            new UiAssertion.Visible(), fixture.deadline())));

            CompletionStage<String> run = fixture.runner.run(
                    definition, MatrixLimits.defaults(), fixture.deadline());
            for (int index = 0; index < 16 && !run.toCompletableFuture().isDone(); index++) {
                fixture.nextFrame();
            }
            String runId = run.toCompletableFuture().join();

            MatrixReport report = fixture.runner.results(runId).orElseThrow();
            assertEquals(2, report.results().size());
            for (var result : report.results()) {
                assertEquals(MatrixCaseStatus.PASSED, result.status());
                assertEquals(result.caseSummary().window(),
                        result.observedWindow());
                assertEquals(1.0, result.observedUiScale());
                assertEquals(MatrixHiDpi.LOGICAL, result.observedHiDpiMode());
                assertEquals("en", result.observedLocale());
                assertEquals("desktop", result.observedRestartProfileId());
                assertEquals(List.of(0), result.passedAssertions());
            }
            assertEquals(2, fixture.acquisitions.get());
            assertEquals(2, fixture.applied.get());
            assertEquals(2, fixture.restored.get());
        }
    }

    @Test void unsupportedCaseIsTypedSkipWithoutScenarioAcquisition() {
        try (Fixture fixture = new Fixture()) {
            fixture.unsupportedReason = "unsupported devicePixelRatio: 2.0";
            MatrixDefinition definition = new MatrixDefinition(
                    1,
                    "matrix",
                    List.of(new MatrixWindow(1280, 720)),
                    List.of(1.0),
                    List.of(1.0),
                    List.of(MatrixHiDpi.LOGICAL),
                    List.of("en"),
                    List.of(),
                    List.of(new AssertionRequest(1, Locator.testId("save"),
                            new UiAssertion.Visible(), fixture.deadline())));

            String runId = fixture.runner.run(
                    definition, MatrixLimits.defaults(), fixture.deadline())
                    .toCompletableFuture().join();

            MatrixReport report = fixture.runner.results(runId).orElseThrow();
            var result = report.results().getFirst();
            assertEquals(MatrixCaseStatus.UNSUPPORTED, result.status());
            assertEquals(0, result.passedAssertions().size());
            assertEquals(0, result.failedAssertions().size());
            assertEquals(0, fixture.acquisitions.get());
            assertEquals(1, fixture.applied.get());
            assertEquals(0, fixture.restored.get());
            assertTrue(result.evidence().contains("devicePixelRatio"));
        }
    }

    @Test void requestedObservedMismatchIsDistinctTerminalWithoutAssertions() {
        try (Fixture fixture = new Fixture()) {
            fixture.observedUiScaleOverride = 2.0;
            MatrixDefinition definition = new MatrixDefinition(
                    1,
                    "matrix",
                    List.of(new MatrixWindow(1280, 720)),
                    List.of(1.0),
                    List.of(1.0),
                    List.of(MatrixHiDpi.LOGICAL),
                    List.of("en"),
                    List.of(),
                    List.of(new AssertionRequest(1, Locator.testId("save"),
                            new UiAssertion.Visible(), fixture.deadline())));

            String runId = fixture.runner.run(
                    definition, MatrixLimits.defaults(), fixture.deadline())
                    .toCompletableFuture().join();

            MatrixReport report = fixture.runner.results(runId).orElseThrow();
            var result = report.results().getFirst();
            assertEquals(MatrixCaseStatus.MISAPPLIED, result.status());
            assertEquals(0, result.passedAssertions().size());
            assertEquals(0, fixture.acquisitions.get());
            assertEquals(1, fixture.restored.get(),
                    "a misapplied case must restore the original display state");
            assertTrue(result.evidence().contains("uiScale requested=1.0 observed=2.0"));
        }
    }

    @Test void hostRestartProfileMismatchIsDistinctTerminalWithoutAssertions() {
        try (Fixture fixture = new Fixture()) {
            fixture.observedRestartProfileOverride = "other-profile";
            MatrixDefinition definition = new MatrixDefinition(
                    1,
                    "matrix",
                    List.of(new MatrixWindow(1280, 720)),
                    List.of(1.0),
                    List.of(1.0),
                    List.of(MatrixHiDpi.LOGICAL),
                    List.of("en"),
                    List.of(),
                    List.of(new AssertionRequest(1, Locator.testId("save"),
                            new UiAssertion.Visible(), fixture.deadline())));

            String runId = fixture.runner.run(
                    definition, MatrixLimits.defaults(), fixture.deadline())
                    .toCompletableFuture().join();

            MatrixReport report = fixture.runner.results(runId).orElseThrow();
            var result = report.results().getFirst();
            assertEquals(MatrixCaseStatus.MISAPPLIED, result.status());
            assertEquals(0, result.passedAssertions().size());
            assertEquals(0, fixture.acquisitions.get());
            assertEquals(1, fixture.restored.get());
            assertTrue(result.evidence().contains(
                    "restartProfile requested=desktop observed=other-profile"));
        }
    }

    @Test void expiredDeadlineMarksCasesUnstartedWithoutApplying() {
        try (Fixture fixture = new Fixture()) {
            MatrixDefinition definition = new MatrixDefinition(
                    1,
                    "matrix",
                    List.of(new MatrixWindow(1280, 720), new MatrixWindow(1920, 1080)),
                    List.of(1.0),
                    List.of(1.0),
                    List.of(MatrixHiDpi.LOGICAL),
                    List.of("en"),
                    List.of(),
                    List.of());
            Deadline expired = Deadline.after(fixture.clock, Duration.ZERO);

            String runId = fixture.runner.run(
                    definition, MatrixLimits.defaults(), expired)
                    .toCompletableFuture().join();

            MatrixReport report = fixture.runner.results(runId).orElseThrow();
            assertEquals(2, report.results().size());
            for (var result : report.results()) {
                assertEquals(MatrixCaseStatus.UNSTARTED, result.status());
            }
            assertEquals(0, fixture.applied.get());
            assertEquals(0, fixture.restored.get());
        }
    }

    @Test void synchronousAcquireFailureRestoresOnceAndContinuesToNextCase() {
        try (Fixture fixture = new Fixture()) {
            fixture.throwOnNextScenarioSchedule = true;
            MatrixDefinition definition = new MatrixDefinition(
                    1,
                    "matrix",
                    List.of(new MatrixWindow(1280, 720), new MatrixWindow(1920, 1080)),
                    List.of(1.0),
                    List.of(1.0),
                    List.of(MatrixHiDpi.LOGICAL),
                    List.of("en"),
                    List.of(),
                    List.of(new AssertionRequest(1, Locator.testId("save"),
                            new UiAssertion.Visible(), fixture.deadline())));

            CompletionStage<String> run = fixture.runner.run(
                    definition, MatrixLimits.defaults(), fixture.deadline());
            for (int index = 0; index < 16 && !run.toCompletableFuture().isDone(); index++) {
                fixture.nextFrame();
            }
            String runId = run.toCompletableFuture().join();

            MatrixReport report = fixture.runner.results(runId).orElseThrow();
            assertEquals(2, report.results().size());
            MatrixCaseResult first = report.results().getFirst();
            assertEquals(MatrixCaseStatus.FAILED, first.status());
            assertEquals(new MatrixWindow(1280, 720), first.observedWindow());
            assertEquals(1.0, first.observedUiScale());
            assertEquals(MatrixHiDpi.LOGICAL, first.observedHiDpiMode());
            assertEquals("en", first.observedLocale());
            assertEquals("desktop", first.observedRestartProfileId());
            assertTrue(first.evidence().contains("scenario deadline scheduling rejected"),
                    first.evidence());
            assertEquals(MatrixCaseStatus.PASSED, report.results().get(1).status(),
                    report.results().get(1).evidence());
            assertEquals(1, fixture.acquisitions.get());
            assertEquals(2, fixture.applied.get());
            assertEquals(2, fixture.restored.get(),
                    "a synchronously failed acquisition must restore exactly once "
                            + "before the next case");
        }
    }

    @Test void restoreFailureFailsAnOtherwisePassingCaseAndNextCaseStillRuns() {
        try (Fixture fixture = new Fixture()) {
            fixture.restoreFailuresRemaining = 1;
            MatrixDefinition definition = new MatrixDefinition(
                    1,
                    "matrix",
                    List.of(new MatrixWindow(1280, 720), new MatrixWindow(1920, 1080)),
                    List.of(1.0),
                    List.of(1.0),
                    List.of(MatrixHiDpi.LOGICAL),
                    List.of("en"),
                    List.of(),
                    List.of(new AssertionRequest(1, Locator.testId("save"),
                            new UiAssertion.Visible(), fixture.deadline())));

            CompletionStage<String> run = fixture.runner.run(
                    definition, MatrixLimits.defaults(), fixture.deadline());
            for (int index = 0; index < 16 && !run.toCompletableFuture().isDone(); index++) {
                fixture.nextFrame();
            }
            String runId = run.toCompletableFuture().join();

            MatrixReport report = fixture.runner.results(runId).orElseThrow();
            assertEquals(2, report.results().size());
            MatrixCaseResult first = report.results().getFirst();
            assertEquals(MatrixCaseStatus.FAILED, first.status());
            assertTrue(first.evidence().contains("display restore failed"), first.evidence());
            assertEquals(MatrixCaseStatus.PASSED, report.results().get(1).status(),
                    "the next case must still run after a restore failure: "
                            + report.results().get(1).evidence());
            assertEquals(2, fixture.applied.get());
            assertEquals(2, fixture.restored.get());
            assertEquals(2, fixture.acquisitions.get());
        }
    }

    @Test void restoreFailureIsAggregatedAfterPrimaryAssertionFailure() {
        try (Fixture fixture = new Fixture()) {
            fixture.saveAbsent = true;
            fixture.mismatchedSnapshots = true;
            fixture.restoreFailuresRemaining = 1;
            MatrixDefinition definition = new MatrixDefinition(
                    1,
                    "matrix",
                    List.of(new MatrixWindow(1280, 720)),
                    List.of(1.0),
                    List.of(1.0),
                    List.of(MatrixHiDpi.LOGICAL),
                    List.of("en"),
                    List.of(),
                    List.of(new AssertionRequest(1, Locator.testId("save"),
                            new UiAssertion.Visible(), fixture.deadline())));

            CompletionStage<String> run = fixture.runner.run(
                    definition, MatrixLimits.defaults(), fixture.deadline());
            for (int index = 0; index < 16 && !run.toCompletableFuture().isDone(); index++) {
                fixture.nextFrame();
            }
            fixture.nowNanos[0] += Duration.ofSeconds(6).toNanos();
            fixture.deadlines.expire();
            for (int index = 0; index < 4 && !run.toCompletableFuture().isDone(); index++) {
                fixture.nextFrame();
            }
            String runId = run.toCompletableFuture().join();

            MatrixReport report = fixture.runner.results(runId).orElseThrow();
            MatrixCaseResult result = report.results().getFirst();
            assertEquals(MatrixCaseStatus.FAILED, result.status());
            String evidence = result.evidence();
            assertTrue(evidence.contains("does not match delivered frame"),
                    "the primary assertion failure must stay first: " + evidence);
            assertTrue(evidence.contains("display restore failed"),
                    "the restore failure must be aggregated after the primary: " + evidence);
            assertTrue(evidence.length() <= 512, "case evidence must stay bounded");
            assertEquals(1, fixture.restored.get());
        }
    }

    @Test void asyncAcquireFailureKeepsObservedIdentityEvidence() {
        try (Fixture fixture = new Fixture()) {
            fixture.failReset = true;
            MatrixDefinition definition = new MatrixDefinition(
                    1,
                    "matrix",
                    List.of(new MatrixWindow(1280, 720)),
                    List.of(1.0),
                    List.of(1.0),
                    List.of(MatrixHiDpi.LOGICAL),
                    List.of("en"),
                    List.of(),
                    List.of(new AssertionRequest(1, Locator.testId("save"),
                            new UiAssertion.Visible(), fixture.deadline())));

            CompletionStage<String> run = fixture.runner.run(
                    definition, MatrixLimits.defaults(), fixture.deadline());
            for (int index = 0; index < 8 && !run.toCompletableFuture().isDone(); index++) {
                fixture.nextFrame();
            }
            String runId = run.toCompletableFuture().join();

            MatrixReport report = fixture.runner.results(runId).orElseThrow();
            MatrixCaseResult result = report.results().getFirst();
            assertEquals(MatrixCaseStatus.FAILED, result.status());
            assertEquals(new MatrixWindow(1280, 720), result.observedWindow());
            assertEquals(1.0, result.observedUiScale());
            assertEquals(MatrixHiDpi.LOGICAL, result.observedHiDpiMode());
            assertEquals("en", result.observedLocale());
            assertEquals("desktop", result.observedRestartProfileId());
            assertTrue(result.evidence().contains("scenario acquisition failed"),
                    result.evidence());
            assertEquals(1, fixture.acquisitions.get());
            assertEquals(1, fixture.applied.get());
            assertEquals(1, fixture.restored.get());
        }
    }

    @Test void restoreFailureUpgradesAMisappliedCaseToFailed() {
        try (Fixture fixture = new Fixture()) {
            fixture.observedUiScaleOverride = 2.0;
            fixture.restoreFailuresRemaining = 1;
            MatrixDefinition definition = new MatrixDefinition(
                    1,
                    "matrix",
                    List.of(new MatrixWindow(1280, 720)),
                    List.of(1.0),
                    List.of(1.0),
                    List.of(MatrixHiDpi.LOGICAL),
                    List.of("en"),
                    List.of(),
                    List.of(new AssertionRequest(1, Locator.testId("save"),
                            new UiAssertion.Visible(), fixture.deadline())));

            String runId = fixture.runner.run(
                    definition, MatrixLimits.defaults(), fixture.deadline())
                    .toCompletableFuture().join();

            MatrixReport report = fixture.runner.results(runId).orElseThrow();
            MatrixCaseResult result = report.results().getFirst();
            assertEquals(MatrixCaseStatus.FAILED, result.status());
            String evidence = result.evidence();
            assertTrue(evidence.contains("display restore failed"),
                    "the restore failure must become the primary failure: " + evidence);
            assertTrue(evidence.contains("requested state not applied"),
                    "the misapplied classification must be retained: " + evidence);
            assertEquals(1, fixture.restored.get());
        }
    }

    @Test void longRestoreFailureKeepsPrimaryEvidenceAfterAssertionFailure() {
        try (Fixture fixture = new Fixture()) {
            fixture.saveAbsent = true;
            fixture.mismatchedSnapshots = true;
            fixture.restoreMessage = "display restore rejected " + "x".repeat(600);
            fixture.restoreFailuresRemaining = 1;
            MatrixDefinition definition = new MatrixDefinition(
                    1,
                    "matrix",
                    List.of(new MatrixWindow(1280, 720)),
                    List.of(1.0),
                    List.of(1.0),
                    List.of(MatrixHiDpi.LOGICAL),
                    List.of("en"),
                    List.of(),
                    List.of(new AssertionRequest(1, Locator.testId("save"),
                            new UiAssertion.Visible(), fixture.deadline())));

            CompletionStage<String> run = fixture.runner.run(
                    definition, MatrixLimits.defaults(), fixture.deadline());
            for (int index = 0; index < 16 && !run.toCompletableFuture().isDone(); index++) {
                fixture.nextFrame();
            }
            fixture.nowNanos[0] += Duration.ofSeconds(6).toNanos();
            fixture.deadlines.expire();
            for (int index = 0; index < 4 && !run.toCompletableFuture().isDone(); index++) {
                fixture.nextFrame();
            }
            String runId = run.toCompletableFuture().join();

            MatrixReport report = fixture.runner.results(runId).orElseThrow();
            MatrixCaseResult result = report.results().getFirst();
            assertEquals(MatrixCaseStatus.FAILED, result.status());
            String evidence = result.evidence();
            assertTrue(evidence.length() <= 512, "case evidence must stay bounded");
            assertTrue(evidence.contains("does not match delivered frame"),
                    "the primary assertion failure must never be replaced: " + evidence);
            assertTrue(evidence.contains("display restore failed"),
                    "the restore failure must still be represented: " + evidence);
        }
    }

    @Test void longRestoreFailureKeepsPrimaryEvidenceAfterAcquisitionFailure() {
        try (Fixture fixture = new Fixture()) {
            fixture.failReset = true;
            fixture.restoreMessage = "display restore rejected " + "y".repeat(600);
            fixture.restoreFailuresRemaining = 1;
            MatrixDefinition definition = new MatrixDefinition(
                    1,
                    "matrix",
                    List.of(new MatrixWindow(1280, 720)),
                    List.of(1.0),
                    List.of(1.0),
                    List.of(MatrixHiDpi.LOGICAL),
                    List.of("en"),
                    List.of(),
                    List.of(new AssertionRequest(1, Locator.testId("save"),
                            new UiAssertion.Visible(), fixture.deadline())));

            CompletionStage<String> run = fixture.runner.run(
                    definition, MatrixLimits.defaults(), fixture.deadline());
            for (int index = 0; index < 8 && !run.toCompletableFuture().isDone(); index++) {
                fixture.nextFrame();
            }
            String runId = run.toCompletableFuture().join();

            MatrixReport report = fixture.runner.results(runId).orElseThrow();
            MatrixCaseResult result = report.results().getFirst();
            assertEquals(MatrixCaseStatus.FAILED, result.status());
            String evidence = result.evidence();
            assertTrue(evidence.length() <= 512, "case evidence must stay bounded");
            assertTrue(evidence.contains("scenario acquisition failed"),
                    "the acquisition failure must never be replaced: " + evidence);
            assertTrue(evidence.contains("display restore failed"),
                    "the restore failure must still be represented: " + evidence);
        }
    }

    @Test void longRestoreFailureAloneStaysBounded() {
        try (Fixture fixture = new Fixture()) {
            fixture.restoreMessage = "display restore rejected " + "z".repeat(600);
            fixture.restoreFailuresRemaining = 1;
            MatrixDefinition definition = new MatrixDefinition(
                    1,
                    "matrix",
                    List.of(new MatrixWindow(1280, 720)),
                    List.of(1.0),
                    List.of(1.0),
                    List.of(MatrixHiDpi.LOGICAL),
                    List.of("en"),
                    List.of(),
                    List.of(new AssertionRequest(1, Locator.testId("save"),
                            new UiAssertion.Visible(), fixture.deadline())));

            CompletionStage<String> run = fixture.runner.run(
                    definition, MatrixLimits.defaults(), fixture.deadline());
            for (int index = 0; index < 16 && !run.toCompletableFuture().isDone(); index++) {
                fixture.nextFrame();
            }
            String runId = run.toCompletableFuture().join();

            MatrixReport report = fixture.runner.results(runId).orElseThrow();
            MatrixCaseResult result = report.results().getFirst();
            assertEquals(MatrixCaseStatus.FAILED, result.status());
            String evidence = result.evidence();
            assertTrue(evidence.length() <= 512, "case evidence must stay bounded");
            assertTrue(evidence.startsWith("display restore failed"),
                    "the restore failure must remain the primary evidence: " + evidence);
        }
    }

    @Test void secondRunIsRejectedWhileFirstIsActiveAndNewRunSucceedsAfterTerminal() {
        try (Fixture fixture = new Fixture()) {
            MatrixDefinition definition = new MatrixDefinition(
                    1,
                    "matrix",
                    List.of(new MatrixWindow(1280, 720)),
                    List.of(1.0),
                    List.of(1.0),
                    List.of(MatrixHiDpi.LOGICAL),
                    List.of("en"),
                    List.of(),
                    List.of(new AssertionRequest(1, Locator.testId("save"),
                            new UiAssertion.Visible(), fixture.deadline())));

            CompletionStage<String> first = fixture.runner.run(
                    definition, MatrixLimits.defaults(), fixture.deadline());
            assertEquals(1, fixture.applied.get(),
                    "the first run applies before its assertion stage waits");

            CompletionStage<String> second = fixture.runner.run(
                    definition, MatrixLimits.defaults(), fixture.deadline());
            java.util.concurrent.CompletionException rejection = assertThrows(
                    java.util.concurrent.CompletionException.class,
                    () -> second.toCompletableFuture().join());
            assertEquals("matrix run already active", rejection.getCause().getMessage());
            assertEquals(1, fixture.applied.get(),
                    "a rejected run must never apply a case");
            assertEquals(0, fixture.acquisitions.get());

            for (int index = 0; index < 16 && !first.toCompletableFuture().isDone(); index++) {
                fixture.nextFrame();
            }
            String firstRunId = first.toCompletableFuture().join();
            assertEquals(MatrixCaseStatus.PASSED, fixture.runner.results(firstRunId)
                    .orElseThrow().results().getFirst().status());

            CompletionStage<String> third = fixture.runner.run(
                    definition, MatrixLimits.defaults(), fixture.deadline());
            for (int index = 0; index < 16 && !third.toCompletableFuture().isDone(); index++) {
                fixture.nextFrame();
            }
            String thirdRunId = third.toCompletableFuture().join();
            assertEquals(MatrixCaseStatus.PASSED, fixture.runner.results(thirdRunId)
                    .orElseThrow().results().getFirst().status());
            assertEquals(2, fixture.applied.get());
            assertEquals(2, fixture.restored.get());
        }
    }

    @Test void applicatorReceivesExactRunDeadline() {
        try (Fixture fixture = new Fixture()) {
            MatrixDefinition definition = new MatrixDefinition(
                    1,
                    "matrix",
                    List.of(new MatrixWindow(1280, 720)),
                    List.of(1.0),
                    List.of(1.0),
                    List.of(MatrixHiDpi.LOGICAL),
                    List.of("en"),
                    List.of(),
                    List.of(new AssertionRequest(1, Locator.testId("save"),
                            new UiAssertion.Visible(), fixture.deadline())));
            Deadline deadline = fixture.deadline();

            CompletionStage<String> run = fixture.runner.run(
                    definition, MatrixLimits.defaults(), deadline);
            assertSame(deadline, fixture.lastApplyDeadline,
                    "the runner must pass the exact run deadline to the applicator");
            for (int index = 0; index < 16 && !run.toCompletableFuture().isDone(); index++) {
                fixture.nextFrame();
            }
            String runId = run.toCompletableFuture().join();
            assertEquals(MatrixCaseStatus.PASSED,
                    fixture.runner.results(runId).orElseThrow().results().getFirst().status());
        }
    }

    @Test void matrixProductLimitRejectsBeforeAnyCaseStarts() {
        try (Fixture fixture = new Fixture()) {
            MatrixDefinition definition = new MatrixDefinition(
                    1,
                    "matrix",
                    List.of(new MatrixWindow(1280, 720), new MatrixWindow(1920, 1080)),
                    List.of(1.0, 2.0, 3.0),
                    List.of(1.0),
                    List.of(MatrixHiDpi.LOGICAL),
                    List.of("en"),
                    List.of(),
                    List.of());

            CompletionStage<String> run = fixture.runner.run(
                    definition, MatrixLimits.builder().maxCases(2).build(),
                    fixture.deadline());

            assertThrows(java.util.concurrent.CompletionException.class,
                    () -> run.toCompletableFuture().join());
            assertEquals(0, fixture.acquisitions.get());
        }
    }

    @Test void deadlineMarksAllCasesUnstartedTerminally() {
        try (Fixture fixture = new Fixture()) {
            MatrixDefinition definition = new MatrixDefinition(
                    1,
                    "matrix",
                    List.of(new MatrixWindow(1280, 720), new MatrixWindow(1920, 1080)),
                    List.of(1.0),
                    List.of(1.0),
                    List.of(MatrixHiDpi.LOGICAL),
                    List.of("en"),
                    List.of(),
                    List.of());
            Deadline expired = Deadline.after(fixture.clock, Duration.ZERO);

            String runId = fixture.runner.run(
                    definition, MatrixLimits.defaults(), expired)
                    .toCompletableFuture().join();

            MatrixReport report = fixture.runner.results(runId).orElseThrow();
            assertEquals(2, report.results().size());
            for (var result : report.results()) {
                assertEquals(MatrixCaseStatus.UNSTARTED, result.status());
            }
        }
    }

    @Test void caseFailureIsReportedWithBoundedEvidence() {
        try (Fixture fixture = new Fixture()) {
            fixture.visible = false;
            MatrixDefinition definition = new MatrixDefinition(
                    1,
                    "matrix",
                    List.of(new MatrixWindow(1280, 720)),
                    List.of(1.0),
                    List.of(1.0),
                    List.of(MatrixHiDpi.LOGICAL),
                    List.of("en"),
                    List.of(),
                    List.of(new AssertionRequest(1, Locator.testId("save"),
                            new UiAssertion.Visible(), fixture.deadline())));

            CompletionStage<String> run = fixture.runner.run(
                    definition, MatrixLimits.defaults(), fixture.deadline());
            for (int index = 0; index < 16 && !run.toCompletableFuture().isDone(); index++) {
                fixture.nextFrame();
            }
            fixture.nowNanos[0] += Duration.ofSeconds(6).toNanos();
            fixture.deadlines.expire();
            for (int index = 0; index < 4 && !run.toCompletableFuture().isDone(); index++) {
                fixture.nextFrame();
            }
            String runId = run.toCompletableFuture().join();

            MatrixReport report = fixture.runner.results(runId).orElseThrow();
            assertEquals(MatrixCaseStatus.FAILED, report.results().getFirst().status());
            assertEquals(List.of(0), report.results().getFirst().failedAssertions());
            assertTrue(!report.results().getFirst().evidence().isEmpty());
        }
    }

    @Test void assertionStageFailureReleasesTheLeaseBeforeTheNextCaseBegins() {
        try (Fixture fixture = new Fixture()) {
            fixture.saveAbsent = true;
            fixture.mismatchedSnapshots = true;
            MatrixDefinition definition = new MatrixDefinition(
                    1,
                    "matrix",
                    List.of(new MatrixWindow(1280, 720), new MatrixWindow(1920, 1080)),
                    List.of(1.0),
                    List.of(1.0),
                    List.of(MatrixHiDpi.LOGICAL),
                    List.of("en"),
                    List.of(),
                    List.of(new AssertionRequest(1, Locator.testId("save"),
                            new UiAssertion.Visible(), fixture.deadline())));

            CompletionStage<String> run = fixture.runner.run(
                    definition, MatrixLimits.defaults(), fixture.deadline());
            for (int index = 0; index < 24 && !run.toCompletableFuture().isDone(); index++) {
                fixture.nextFrame();
            }
            String runId = run.toCompletableFuture().join();

            MatrixReport report = fixture.runner.results(runId).orElseThrow();
            assertEquals(2, report.results().size());
            assertEquals(MatrixCaseStatus.FAILED, report.results().getFirst().status());
            assertTrue(!report.results().getFirst().evidence().isEmpty());
            assertEquals(MatrixCaseStatus.PASSED, report.results().get(1).status(),
                    report.results().get(1).evidence());
            assertEquals(2, fixture.acquisitions.get());
            assertEquals(2, fixture.releases.get());
            assertEquals(1, fixture.releasesAtNextAcquire.get(),
                    "the first case must release its lease before the next case begins");
        }
    }

    @Test void cleanupFailureOnReleaseFailsAnOtherwisePassingCaseWithCleanupEvidence() {
        try (Fixture fixture = new Fixture()) {
            fixture.failCleanup = true;
            MatrixDefinition definition = new MatrixDefinition(
                    1,
                    "matrix",
                    List.of(new MatrixWindow(1280, 720)),
                    List.of(1.0),
                    List.of(1.0),
                    List.of(MatrixHiDpi.LOGICAL),
                    List.of("en"),
                    List.of(),
                    List.of(new AssertionRequest(1, Locator.testId("save"),
                            new UiAssertion.Visible(), fixture.deadline())));

            CompletionStage<String> run = fixture.runner.run(
                    definition, MatrixLimits.defaults(), fixture.deadline());
            for (int index = 0; index < 16 && !run.toCompletableFuture().isDone(); index++) {
                fixture.nextFrame();
            }
            String runId = run.toCompletableFuture().join();

            MatrixReport report = fixture.runner.results(runId).orElseThrow();
            MatrixCaseResult result = report.results().getFirst();
            assertEquals(MatrixCaseStatus.FAILED, result.status());
            assertEquals(List.of(0), result.passedAssertions(),
                    "the assertion itself passed");
            assertTrue(result.evidence().contains("CLEANUP_FAILED"), result.evidence());
            assertEquals(1, fixture.releases.get());
            assertEquals(1, fixture.acquisitions.get());
        }
    }

    @Test void cleanupFailureOnReleaseKeepsPrimaryAssertionFailureAndRetainsCleanupEvidence() {
        try (Fixture fixture = new Fixture()) {
            fixture.saveAbsent = true;
            fixture.mismatchedSnapshots = true;
            fixture.failCleanup = true;
            MatrixDefinition definition = new MatrixDefinition(
                    1,
                    "matrix",
                    List.of(new MatrixWindow(1280, 720)),
                    List.of(1.0),
                    List.of(1.0),
                    List.of(MatrixHiDpi.LOGICAL),
                    List.of("en"),
                    List.of(),
                    List.of(new AssertionRequest(1, Locator.testId("save"),
                            new UiAssertion.Visible(), fixture.deadline())));

            CompletionStage<String> run = fixture.runner.run(
                    definition, MatrixLimits.defaults(), fixture.deadline());
            for (int index = 0; index < 16 && !run.toCompletableFuture().isDone(); index++) {
                fixture.nextFrame();
            }
            String runId = run.toCompletableFuture().join();

            MatrixReport report = fixture.runner.results(runId).orElseThrow();
            MatrixCaseResult result = report.results().getFirst();
            assertEquals(MatrixCaseStatus.FAILED, result.status());
            String evidence = result.evidence();
            assertTrue(evidence.contains("does not match delivered frame"),
                    "the primary assertion failure must remain primary: " + evidence);
            assertTrue(evidence.contains("CLEANUP_FAILED"),
                    "the release cleanup failure must be retained: " + evidence);
            assertEquals(1, fixture.releases.get());
        }
    }

    @Test void overLimitPrimaryAssertionFailureRetainsCleanupClassificationWithinTheBound() {
        try (Fixture fixture = new Fixture()) {
            fixture.saveAbsent = true;
            fixture.longResolutionFailure = true;
            fixture.failCleanup = true;
            MatrixDefinition definition = new MatrixDefinition(
                    1,
                    "matrix",
                    List.of(new MatrixWindow(1280, 720)),
                    List.of(1.0),
                    List.of(1.0),
                    List.of(MatrixHiDpi.LOGICAL),
                    List.of("en"),
                    List.of(),
                    List.of(new AssertionRequest(1, Locator.testId("save"),
                            new UiAssertion.Visible(), fixture.deadline())));

            CompletionStage<String> run = fixture.runner.run(
                    definition, MatrixLimits.defaults(), fixture.deadline());
            for (int index = 0; index < 16 && !run.toCompletableFuture().isDone(); index++) {
                fixture.nextFrame();
            }
            fixture.nowNanos[0] += Duration.ofSeconds(6).toNanos();
            fixture.deadlines.expire();
            for (int index = 0; index < 4 && !run.toCompletableFuture().isDone(); index++) {
                fixture.nextFrame();
            }
            String runId = run.toCompletableFuture().join();

            MatrixReport report = fixture.runner.results(runId).orElseThrow();
            MatrixCaseResult result = report.results().getFirst();
            assertEquals(MatrixCaseStatus.FAILED, result.status());
            String evidence = result.evidence();
            assertTrue(evidence.length() <= 512, "case evidence must stay bounded");
            assertTrue(evidence.startsWith("primary assertion failure"),
                    "the primary failure identity must stay first: " + evidence);
            assertTrue(evidence.contains("CLEANUP_FAILED"),
                    "cleanup classification must remain within the bound: " + evidence);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Duration step = Duration.ofMillis(16);
        final long[] nowNanos = {0};
        final long[] revision = {0};
        final long[] frame = {0};
        final MonotonicClock clock = () -> nowNanos[0];
        final RenderThreadScheduler scheduler = new RenderThreadScheduler(64);
        final ScenarioRegistry registry = new ScenarioRegistry();
        final AtomicInteger acquisitions = new AtomicInteger();
        final AtomicInteger releases = new AtomicInteger();
        final AtomicInteger releasesAtNextAcquire = new AtomicInteger();
        final AtomicInteger applied = new AtomicInteger();
        final AtomicInteger restored = new AtomicInteger();
        String unsupportedReason;
        Double observedUiScaleOverride;
        String observedRestartProfileOverride;
        /** Host-owned active restart profile, never derived from the runner's request. */
        final String hostRestartProfile = "desktop";
        boolean throwOnNextScenarioSchedule;
        boolean failReset;
        int restoreFailuresRemaining;
        String restoreMessage = "display restore rejected";
        Deadline lastApplyDeadline;
        final Lwjgl3MatrixRunner.MatrixCaseApplicator applicator =
                new Lwjgl3MatrixRunner.MatrixCaseApplicator() {
                    @Override public Lwjgl3MatrixRunner.ApplyResult apply(
                            dev.gdx.uiharness.core.matrix.MatrixCase matrixCase,
                            String restartProfileId,
                            Deadline deadline) {
                        applied.incrementAndGet();
                        lastApplyDeadline = deadline;
                        if (unsupportedReason != null) {
                            return new Lwjgl3MatrixRunner.ApplyResult.Unsupported(
                                    unsupportedReason);
                        }
                        return new Lwjgl3MatrixRunner.ApplyResult.Applied(
                                new Lwjgl3MatrixRunner.DisplayObservation(
                                        matrixCase.window(),
                                        observedUiScaleOverride != null
                                                ? observedUiScaleOverride : matrixCase.uiScale(),
                                        matrixCase.devicePixelRatio(),
                                        matrixCase.hiDpiMode(),
                                        matrixCase.locale(),
                                        matrixCase.fontSetId(),
                                        observedRestartProfileOverride != null
                                                ? observedRestartProfileOverride
                                                : hostRestartProfile));
                    }

                    @Override public void restore() {
                        restored.incrementAndGet();
                        // Each case ends with a fresh host state, mirroring the previous
                        // per-case observer reset so ordering tests stay deterministic.
                        saveAbsent = false;
                        mismatchedSnapshots = false;
                        if (restoreFailuresRemaining > 0) {
                            restoreFailuresRemaining--;
                            throw new IllegalStateException(restoreMessage);
                        }
                    }
                };
        final ManualFrames frames = new ManualFrames();
        final ManualDeadlines deadlines = new ManualDeadlines();
        final Scene2dScenarioRunner scenarios;
        final Lwjgl3MatrixRunner runner;
        boolean visible = true;
        boolean saveAbsent;
        boolean mismatchedSnapshots;
        boolean longResolutionFailure;
        boolean failCleanup;

        Fixture() {
            registry.register(new ScenarioDefinition(
                            1, "matrix", "1", "app", List.of("desktop"),
                            2, Duration.ofMinutes(1)),
                    new ScenarioLifecycle() {
                        @Override public void setup(ScenarioRequest request) {}
                        @Override public void reset(ScenarioRequest request) {
                            releasesAtNextAcquire.set(releases.get());
                            acquisitions.incrementAndGet();
                            if (failReset) {
                                throw new IllegalStateException("reset rejected");
                            }
                        }
                        @Override public boolean ready(ScenarioRequest request) {
                            return true;
                        }
                        @Override public String startStateIdentity(
                                ScenarioRequest request, SemanticSnapshot snapshot) {
                            return "ready";
                        }
                        @Override public void cleanup(ScenarioRequest request) {
                            releases.incrementAndGet();
                            if (failCleanup) {
                                throw new IllegalStateException("cleanup rejected");
                            }
                        }
                    });
            DeadlineScheduler scenarioDeadlines =
                    new DeadlineScheduler() {
                        @Override public Cancellation schedule(
                                Duration delay, Runnable signal) {
                            if (Fixture.this.throwOnNextScenarioSchedule) {
                                Fixture.this.throwOnNextScenarioSchedule = false;
                                throw new IllegalStateException(
                                        "scenario deadline scheduling rejected");
                            }
                            return () -> {};
                        }
                    };
            scenarios = Scene2dScenarioRunner.withDeadlineScheduler(
                    registry, scheduler, clock, scenarioDeadlines);
            StrictResolution locators = new StrictResolution();
            AssertionSnapshotSource assertionSnapshots = new AssertionSnapshotSource() {
                @Override public SemanticSnapshot currentSnapshot() {
                    return Fixture.this.snapshot();
                }

                @Override public SemanticSnapshot snapshotFor(FrameSignal.Frame frame) {
                    if (longResolutionFailure) {
                        throw new IllegalStateException("primary assertion failure ".repeat(40));
                    }
                    if (mismatchedSnapshots) {
                        return snapshot(Fixture.this.revision[0] + 1_000,
                                Fixture.this.frame[0] + 1_000);
                    }
                    return Fixture.this.snapshot();
                }
            };
            WaitEngine waits = new WaitEngine(
                    this::snapshot, assertionSnapshots, locators, clock, frames,
                    deadlines);
            runner = new Lwjgl3MatrixRunner(scenarios, waits, applicator,
                    new Lwjgl3MatrixRunner.Scenario(
                            "matrix", 7, Map.of(), "desktop", "app", "process", "session"));
        }

        Deadline deadline() {
            return Deadline.after(clock, Duration.ofSeconds(5));
        }

        SemanticSnapshot snapshot() {
            return snapshot(revision[0], frame[0]);
        }

        SemanticSnapshot snapshot(long revision, long frame) {
            Bounds bounds = new Bounds(0, 0, 100, 50);
            SemanticState state = new SemanticState(
                    visible, true, Optional.of(true), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), false, true, 1.0, false, true, true);
            var byId = new LinkedHashMap<String, SemanticNode>();
            if (saveAbsent) {
                SemanticNode root = new SemanticNode(
                        "root", null, List.of(), Role.GROUP, "root", "", null, null,
                        null, null, state, bounds, bounds, bounds, 0, Map.of());
                byId.put("root", root);
            } else {
                SemanticNode button = new SemanticNode(
                        "save", "root", List.of(), Role.BUTTON, "Save", "Save", null,
                        "save", null, "TextButton", state, bounds, bounds, bounds, 0, Map.of());
                SemanticNode root = new SemanticNode(
                        "root", null, List.of("save"), Role.GROUP, "root", "", null, null,
                        null, null, state, bounds, bounds, bounds, 0, Map.of());
                byId.put("root", root);
                byId.put("save", button);
            }
            return new SemanticSnapshot(revision, frame, "root", byId);
        }

        void nextFrame() {
            nowNanos[0] += step.toNanos();
            revision[0]++;
            frame[0]++;
            SemanticSnapshot snapshot = snapshot();
            frames.fire(revision[0], frame[0]);
            scheduler.drain();
            scenarios.completedFrame(snapshot);
            scheduler.drain();
        }

        @Override public void close() {
            runner.close();
            scheduler.close();
        }
    }

    private static final class ManualDeadlines implements dev.gdx.uiharness.core.assertion.DeadlineWakeup {
        private final List<Entry> entries = new ArrayList<>();

        @Override public Registration schedule(Duration delay, Runnable signal) {
            Entry entry = new Entry(signal);
            entries.add(entry);
            return () -> entry.cancelled = true;
        }

        void expire() {
            for (Entry entry : List.copyOf(entries)) {
                if (!entry.cancelled) {
                    entry.signal.run();
                }
            }
        }

        private static final class Entry {
            final Runnable signal;
            boolean cancelled;

            Entry(Runnable signal) {
                this.signal = signal;
            }
        }
    }

    private static final class ManualFrames implements FrameSignal {
        private final List<FrameListener> listeners = new ArrayList<>();

        @Override public Subscription subscribe(FrameListener listener) {
            listeners.add(listener);
            return () -> listeners.remove(listener);
        }

        void fire(long revision, long frame) {
            FrameSignal.Frame signal = new FrameSignal.Frame(revision, frame);
            for (FrameListener listener : List.copyOf(listeners)) {
                listener.onFrame(signal);
            }
        }
    }
}
