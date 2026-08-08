package dev.gdx.uiharness.lwjgl3;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
                assertEquals(List.of(0), result.passedAssertions());
            }
            assertEquals(2, fixture.acquisitions.get());
            assertEquals(2, fixture.observed.get());
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
            assertEquals(MatrixCaseStatus.PASSED, report.results().get(1).status());
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
        final AtomicInteger observed = new AtomicInteger();
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
                            return () -> {};
                        }
                    };
            scenarios = new Scene2dScenarioRunner(
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
            runner = new Lwjgl3MatrixRunner(scenarios, waits, matrixCase -> {
                observed.incrementAndGet();
                saveAbsent = false;
                mismatchedSnapshots = false;
                return new Lwjgl3MatrixRunner.DisplayObservation(
                        matrixCase.window(), matrixCase.uiScale(),
                        matrixCase.devicePixelRatio(), matrixCase.hiDpiMode());
            }, new Lwjgl3MatrixRunner.Scenario(
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
