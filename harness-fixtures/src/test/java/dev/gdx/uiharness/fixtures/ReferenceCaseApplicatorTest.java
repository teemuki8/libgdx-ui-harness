package dev.gdx.uiharness.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gdx.uiharness.core.matrix.MatrixCase;
import dev.gdx.uiharness.core.matrix.MatrixHiDpi;
import dev.gdx.uiharness.core.matrix.MatrixWindow;
import dev.gdx.uiharness.core.time.Deadline;
import dev.gdx.uiharness.core.time.MonotonicClock;
import dev.gdx.uiharness.lwjgl3.Lwjgl3MatrixRunner;
import dev.gdx.uiharness.scene2d.RenderThreadScheduler;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class ReferenceCaseApplicatorTest {
    private final MonotonicClock clock = System::nanoTime;

    @Test
    void applyRestoresPartialWindowAndLocaleStateWhenApplicationFails() {
        Locale previousDefault = Locale.getDefault();
        Locale hostLocale = Locale.forLanguageTag("de-DE");
        Locale.setDefault(hostLocale);
        try {
            try (RenderThreadScheduler scheduler = new RenderThreadScheduler(16)) {
                AtomicBoolean first = new AtomicBoolean(true);
                List<MatrixWindow> appliedWindows = new ArrayList<>();
                ReferenceCaseApplicator.CaseApplication failing =
                        new ReferenceCaseApplicator.CaseApplication() {
                            @Override public void applyWindow(
                                    MatrixWindow window, Deadline deadline) {
                                appliedWindows.add(window);
                                if (first.getAndSet(false)) {
                                    throw new IllegalStateException("window resize timed out");
                                }
                            }

                            @Override public void applyLocale(Locale locale, Deadline deadline) {
                                Locale.setDefault(locale);
                            }
                        };
                ReferenceCaseApplicator applicator = new ReferenceCaseApplicator(
                        scheduler, clock, "desktop-restart-1280x720", failing);
                MatrixCase matrixCase = new MatrixCase(
                        0, new MatrixWindow(1920, 1080), 1.0, 1.0, MatrixHiDpi.PIXELS,
                        "en-US", "", 16.0 / 9.0, List.of());

                assertThrows(IllegalStateException.class,
                        () -> applicator.apply(matrixCase, "desktop-restart-1280x720",
                                Deadline.after(clock, Duration.ofSeconds(30))));
                assertEquals(List.of(new MatrixWindow(1920, 1080), new MatrixWindow(1280, 720)),
                        appliedWindows,
                        "the original window must be restored after the failure");
                assertEquals(hostLocale, Locale.getDefault(),
                        "the host locale (distinct from the requested en-US) must be "
                                + "restored after the failure");
            }
        } finally {
            Locale.setDefault(previousDefault);
        }
    }

    @Test
    void unknownRestartProfileIsRejectedBeforeApplication() {
        try (RenderThreadScheduler scheduler = new RenderThreadScheduler(16)) {
            ReferenceCaseApplicator.CaseApplication noop =
                    new ReferenceCaseApplicator.CaseApplication() {
                        @Override public void applyWindow(
                                MatrixWindow window, Deadline deadline) {
                        }

                        @Override public void applyLocale(Locale locale, Deadline deadline) {
                        }
                    };
            ReferenceCaseApplicator applicator = new ReferenceCaseApplicator(
                    scheduler, clock, "host-owned-profile", noop);
            MatrixCase matrixCase = new MatrixCase(
                    0, new MatrixWindow(1280, 720), 1.0, 1.0, MatrixHiDpi.PIXELS,
                    "en-US", "", 16.0 / 9.0, List.of());

            Lwjgl3MatrixRunner.ApplyResult unknown =
                    applicator.apply(matrixCase, "other-profile",
                            Deadline.after(clock, Duration.ofSeconds(30)));

            assertEquals("unknown restart profile: other-profile",
                    ((Lwjgl3MatrixRunner.ApplyResult.Unsupported) unknown).reason());
        }
    }

    @Test
    void publicRestorePropagatesWindowApplicationFailure() {
        try (RenderThreadScheduler scheduler = new RenderThreadScheduler(16)) {
            ReferenceCaseApplicator.CaseApplication failingWindow =
                    new ReferenceCaseApplicator.CaseApplication() {
                        @Override public void applyWindow(
                                MatrixWindow window, Deadline deadline) {
                            throw new IllegalStateException("window resize stuck");
                        }

                        @Override public void applyLocale(Locale locale, Deadline deadline) {
                            Locale.setDefault(locale);
                        }
                    };
            ReferenceCaseApplicator applicator = new ReferenceCaseApplicator(
                    scheduler, clock, "host-owned-profile", failingWindow);

            IllegalStateException failure = assertThrows(
                    IllegalStateException.class, applicator::restore);
            assertEquals("window resize stuck", failure.getMessage());
        }
    }

    @Test
    void applyFailureSurfacesRestorationRiskWhenRestoreAlsoFails() {
        Locale previousDefault = Locale.getDefault();
        Locale hostLocale = Locale.forLanguageTag("de-DE");
        Locale.setDefault(hostLocale);
        try {
            try (RenderThreadScheduler scheduler = new RenderThreadScheduler(16)) {
                List<MatrixWindow> appliedWindows = new ArrayList<>();
                ReferenceCaseApplicator.CaseApplication alwaysFailing =
                        new ReferenceCaseApplicator.CaseApplication() {
                            @Override public void applyWindow(
                                    MatrixWindow window, Deadline deadline) {
                                appliedWindows.add(window);
                                throw new IllegalStateException("window resize timed out");
                            }

                            @Override public void applyLocale(Locale locale, Deadline deadline) {
                                Locale.setDefault(locale);
                            }
                        };
                ReferenceCaseApplicator applicator = new ReferenceCaseApplicator(
                        scheduler, clock, "desktop-restart-1280x720", alwaysFailing);
                MatrixCase matrixCase = new MatrixCase(
                        0, new MatrixWindow(1920, 1080), 1.0, 1.0, MatrixHiDpi.PIXELS,
                        "en-US", "", 16.0 / 9.0, List.of());

                IllegalStateException failure = assertThrows(IllegalStateException.class,
                        () -> applicator.apply(matrixCase, "desktop-restart-1280x720",
                                Deadline.after(clock, Duration.ofSeconds(30))));
                assertTrue(failure.getMessage().contains("window resize timed out"),
                        "the primary failure must be preserved: " + failure.getMessage());
                assertTrue(failure.getMessage().contains("restore"),
                        "the restoration risk must be surfaced: " + failure.getMessage());
                assertEquals(List.of(new MatrixWindow(1920, 1080), new MatrixWindow(1280, 720)),
                        appliedWindows,
                        "the restore must still be attempted with the host-owned default window");
                assertEquals(hostLocale, Locale.getDefault(),
                        "the locale restore step must still run after the window failure");
            }
        } finally {
            Locale.setDefault(previousDefault);
        }
    }

    @Test
    void restoreAppliesLocaleIndependentlyWhenWindowRestoreFails() {
        Locale previousDefault = Locale.getDefault();
        Locale hostLocale = Locale.forLanguageTag("de-DE");
        Locale.setDefault(hostLocale);
        try {
            try (RenderThreadScheduler scheduler = new RenderThreadScheduler(16)) {
                List<Locale> appliedLocales = new ArrayList<>();
                ReferenceCaseApplicator.CaseApplication failingWindow =
                        new ReferenceCaseApplicator.CaseApplication() {
                            @Override public void applyWindow(
                                    MatrixWindow window, Deadline deadline) {
                                throw new IllegalStateException("window resize stuck");
                            }

                            @Override public void applyLocale(Locale locale, Deadline deadline) {
                                appliedLocales.add(locale);
                                Locale.setDefault(locale);
                            }
                        };
                ReferenceCaseApplicator applicator = new ReferenceCaseApplicator(
                        scheduler, clock, "host-owned-profile", failingWindow);

                IllegalStateException failure = assertThrows(
                        IllegalStateException.class, applicator::restore);
                assertEquals("window resize stuck", failure.getMessage());
                assertEquals(List.of(hostLocale), appliedLocales,
                        "the locale restore must run even when the window restore fails");
            }
        } finally {
            Locale.setDefault(previousDefault);
        }
    }

    @Test
    void restoreAggregatesWindowAndLocaleFailures() {
        try (RenderThreadScheduler scheduler = new RenderThreadScheduler(16)) {
            ReferenceCaseApplicator.CaseApplication bothFailing =
                    new ReferenceCaseApplicator.CaseApplication() {
                        @Override public void applyWindow(
                                MatrixWindow window, Deadline deadline) {
                            throw new IllegalStateException("window resize stuck");
                        }

                        @Override public void applyLocale(Locale locale, Deadline deadline) {
                            throw new IllegalStateException("locale not applied");
                        }
                    };
            ReferenceCaseApplicator applicator = new ReferenceCaseApplicator(
                    scheduler, clock, "host-owned-profile", bothFailing);

            IllegalStateException failure = assertThrows(
                    IllegalStateException.class, applicator::restore);
            assertTrue(failure.getMessage().contains("window resize stuck"),
                    "window failure aggregated: " + failure.getMessage());
            assertTrue(failure.getMessage().contains("locale not applied"),
                    "locale failure aggregated: " + failure.getMessage());
            assertTrue(failure.getSuppressed().length >= 1,
                    "original failures preserved as suppressed");
        }
    }

    @Test
    void applyDoesNotMutateWhenRunDeadlineAlreadyExpired() {
        AtomicLong now = new AtomicLong(0L);
        MonotonicClock manual = now::get;
        try (RenderThreadScheduler scheduler = new RenderThreadScheduler(16)) {
            List<MatrixWindow> appliedWindows = new ArrayList<>();
            List<Locale> appliedLocales = new ArrayList<>();
            ReferenceCaseApplicator.CaseApplication recording =
                    new ReferenceCaseApplicator.CaseApplication() {
                        @Override public void applyWindow(
                                MatrixWindow window, Deadline deadline) {
                            appliedWindows.add(window);
                        }

                        @Override public void applyLocale(Locale locale, Deadline deadline) {
                            appliedLocales.add(locale);
                        }
                    };
            ReferenceCaseApplicator applicator = new ReferenceCaseApplicator(
                    scheduler, manual, "host-owned-profile", recording);
            Deadline run = Deadline.after(manual, Duration.ofSeconds(1));
            now.addAndGet(2_000_000_000L);
            MatrixCase matrixCase = new MatrixCase(
                    0, new MatrixWindow(1920, 1080), 1.0, 1.0, MatrixHiDpi.PIXELS,
                    "en-US", "", 16.0 / 9.0, List.of());

            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> applicator.apply(matrixCase, "host-owned-profile", run));
            assertTrue(failure.getMessage().contains("deadline expired"),
                    "the expired run deadline must abort application: " + failure.getMessage());
            assertEquals(List.of(new MatrixWindow(1280, 720)), appliedWindows,
                    "only the mandatory restore may mutate the window after expiry");
            assertEquals(List.of(Locale.getDefault()), appliedLocales,
                    "only the mandatory restore may set the locale after expiry");
        }
    }

    @Test
    void applyStopsBeforeLocaleMutationWhenRunDeadlineExpiresMidApply() {
        AtomicLong now = new AtomicLong(0L);
        MonotonicClock manual = now::get;
        try (RenderThreadScheduler scheduler = new RenderThreadScheduler(16)) {
            List<MatrixWindow> appliedWindows = new ArrayList<>();
            List<Locale> appliedLocales = new ArrayList<>();
            ReferenceCaseApplicator.CaseApplication expiring =
                    new ReferenceCaseApplicator.CaseApplication() {
                        @Override public void applyWindow(
                                MatrixWindow window, Deadline deadline) {
                            appliedWindows.add(window);
                            now.addAndGet(31_000_000_000L);
                        }

                        @Override public void applyLocale(Locale locale, Deadline deadline) {
                            appliedLocales.add(locale);
                        }
                    };
            ReferenceCaseApplicator applicator = new ReferenceCaseApplicator(
                    scheduler, manual, "host-owned-profile", expiring);
            Deadline run = Deadline.after(manual, Duration.ofSeconds(30));
            MatrixCase matrixCase = new MatrixCase(
                    0, new MatrixWindow(1920, 1080), 1.0, 1.0, MatrixHiDpi.PIXELS,
                    "en-US", "", 16.0 / 9.0, List.of());

            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> applicator.apply(matrixCase, "host-owned-profile", run));
            assertTrue(failure.getMessage().contains("deadline expired"),
                    "expiry between mutations must abort before the locale mutation: "
                            + failure.getMessage());
            assertEquals(List.of(new MatrixWindow(1920, 1080), new MatrixWindow(1280, 720)),
                    appliedWindows,
                    "the window apply and the mandatory restore both run");
            assertEquals(List.of(Locale.getDefault()), appliedLocales,
                    "the requested en-US locale must not be applied after the deadline expired");
        }
    }

    @Test
    void applyNeverReturnsAppliedWhenRunDeadlineExpiresDuringLocaleApply() {
        AtomicLong now = new AtomicLong(0L);
        MonotonicClock manual = now::get;
        try (RenderThreadScheduler scheduler = new RenderThreadScheduler(16)) {
            List<MatrixWindow> appliedWindows = new ArrayList<>();
            List<Locale> appliedLocales = new ArrayList<>();
            ReferenceCaseApplicator.CaseApplication expiringLocale =
                    new ReferenceCaseApplicator.CaseApplication() {
                        @Override public void applyWindow(
                                MatrixWindow window, Deadline deadline) {
                            appliedWindows.add(window);
                        }

                        @Override public void applyLocale(Locale locale, Deadline deadline) {
                            appliedLocales.add(locale);
                            now.addAndGet(31_000_000_000L);
                        }
                    };
            ReferenceCaseApplicator applicator = new ReferenceCaseApplicator(
                    scheduler, manual, "host-owned-profile", expiringLocale);
            Deadline run = Deadline.after(manual, Duration.ofSeconds(30));
            MatrixCase matrixCase = new MatrixCase(
                    0, new MatrixWindow(1920, 1080), 1.0, 1.0, MatrixHiDpi.PIXELS,
                    "en-US", "", 16.0 / 9.0, List.of());

            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> applicator.apply(matrixCase, "host-owned-profile", run));
            assertTrue(failure.getMessage().contains("deadline expired"),
                    "expiry after the locale mutation must still fail the application: "
                            + failure.getMessage());
            assertEquals(List.of(new MatrixWindow(1920, 1080), new MatrixWindow(1280, 720)),
                    appliedWindows,
                    "the window apply and the bounded cleanup restore both run");
            assertEquals(List.of(Locale.forLanguageTag("en-US"), Locale.getDefault()),
                    appliedLocales,
                    "the requested locale and the restored host locale are both applied");
        }
    }

    @Test
    void scheduledWindowCommandRefusesBeforeGdxWhenExecutedAfterExpiry() throws Exception {
        AtomicLong now = new AtomicLong(0L);
        MonotonicClock manual = now::get;
        Deadline run = Deadline.after(manual, Duration.ofSeconds(30));
        CountDownLatch schedulerReady = new CountDownLatch(1);
        CountDownLatch stop = new CountDownLatch(1);
        AtomicReference<RenderThreadScheduler> schedulerRef = new AtomicReference<>();
        Thread owner = Thread.ofPlatform().name("fixture-scheduler-owner").start(() -> {
            schedulerRef.set(new RenderThreadScheduler(16));
            schedulerReady.countDown();
            try {
                stop.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        Locale previousDefault = Locale.getDefault();
        try {
            assertTrue(schedulerReady.await(10, TimeUnit.SECONDS), "scheduler owner must start");
            List<Runnable> captured = new ArrayList<>();
            ReferenceCaseApplicator.WindowCommandScheduler capturing =
                    (window, command, deadline) -> captured.add(command);
            ReferenceCaseApplicator.Observation harmless = matrixCase ->
                    new Lwjgl3MatrixRunner.DisplayObservation(
                            new MatrixWindow(1920, 1080), 1.0, 1.0, MatrixHiDpi.PIXELS,
                            "en-US", "", "host-owned-profile");
            ReferenceCaseApplicator applicator = new ReferenceCaseApplicator(
                    schedulerRef.get(), manual, "host-owned-profile",
                    null, harmless, capturing);
            MatrixCase matrixCase = new MatrixCase(
                    0, new MatrixWindow(1920, 1080), 1.0, 1.0, MatrixHiDpi.PIXELS,
                    "en-US", "", 16.0 / 9.0, List.of());

            // Apply runs on a non-owner virtual thread: the outer pre-check passes and the
            // real production window command (deadline guard + setWindowedMode) is captured by
            // the fake scheduler, which does not execute it. Apply completes with the command
            // retained for the guard assertion below.
            CompletableFuture<Lwjgl3MatrixRunner.ApplyResult> applied =
                    CompletableFuture.supplyAsync(() ->
                            applicator.apply(matrixCase, "host-owned-profile", run));
            assertTrue(applied.join() instanceof Lwjgl3MatrixRunner.ApplyResult.Applied,
                    "apply completes with the command captured");
            assertEquals(1, captured.size(), "the production window command must be submitted");

            // The clock advances while the command sits queued; invoking the actual production
            // command must refuse with the deadline exception BEFORE any Gdx access. Removing
            // the guard would surface an NPE from the uninitialized Gdx instead of this
            // refusal, so this test fails independently if the guard is removed.
            now.addAndGet(31_000_000_000L);
            IllegalStateException refusal = assertThrows(IllegalStateException.class,
                    () -> captured.get(0).run());
            assertTrue(refusal.getMessage().contains("deadline expired"),
                    "the guard must refuse before Gdx access: " + refusal.getMessage());
        } finally {
            Locale.setDefault(previousDefault);
            stop.countDown();
            owner.interrupt();
            owner.join(5_000);
        }
    }

    @Test
    void realSchedulerRejectsExpiredQueuedWindowCommand() throws Exception {
        AtomicLong now = new AtomicLong(0L);
        MonotonicClock manual = now::get;
        Deadline run = Deadline.after(manual, Duration.ofSeconds(30));
        CountDownLatch schedulerReady = new CountDownLatch(1);
        CountDownLatch runQueued = new CountDownLatch(1);
        CountDownLatch restoreQueued = new CountDownLatch(1);
        CountDownLatch drainRun = new CountDownLatch(1);
        CountDownLatch drainRestore = new CountDownLatch(1);
        CountDownLatch ownerDone = new CountDownLatch(1);
        AtomicReference<RenderThreadScheduler> schedulerRef = new AtomicReference<>();
        AtomicReference<Throwable> ownerFailure = new AtomicReference<>();
        AtomicBoolean firstSubmit = new AtomicBoolean(true);

        Thread owner = Thread.ofPlatform().name("fixture-scheduler-owner").start(() -> {
            try {
                RenderThreadScheduler scheduler = new RenderThreadScheduler(16);
                schedulerRef.set(scheduler);
                schedulerReady.countDown();
                if (!drainRun.await(15, TimeUnit.SECONDS)) {
                    return;
                }
                scheduler.drain();
                if (!drainRestore.await(15, TimeUnit.SECONDS)) {
                    return;
                }
                scheduler.drain();
                scheduler.close();
            } catch (Throwable failure) {
                ownerFailure.set(failure);
            } finally {
                ownerDone.countDown();
            }
        });

        try {
            assertTrue(schedulerReady.await(10, TimeUnit.SECONDS), "scheduler owner must start");
            ReferenceCaseApplicator.WindowCommandScheduler wiring =
                    (window, command, deadline) -> {
                        CompletionStage<Void> executed = schedulerRef.get().submit(
                                () -> {
                                    command.run();
                                    return null;
                                }, deadline);
                        // The submit returned: the outer pre-check passed and the command is
                        // queued (or rejected) on the real render-thread scheduler before any
                        // clock advance.
                        if (firstSubmit.getAndSet(false)) {
                            runQueued.countDown();
                        } else {
                            restoreQueued.countDown();
                        }
                        executed.toCompletableFuture().join();
                    };
            ReferenceCaseApplicator applicator = new ReferenceCaseApplicator(
                    schedulerRef.get(), manual, "host-owned-profile", wiring);
            MatrixCase matrixCase = new MatrixCase(
                    0, new MatrixWindow(1920, 1080), 1.0, 1.0, MatrixHiDpi.PIXELS,
                    "en-US", "", 16.0 / 9.0, List.of());
            CompletableFuture<Lwjgl3MatrixRunner.ApplyResult> applied = new CompletableFuture<>();
            Thread.ofVirtual().name("fixture-mcp-apply").start(() -> {
                try {
                    applied.complete(applicator.apply(matrixCase, "host-owned-profile", run));
                } catch (RuntimeException failure) {
                    applied.completeExceptionally(failure);
                }
            });

            // The run command is queued with the pre-check passed: advance past the run
            // deadline and drain once on the owner; the real scheduler must reject the expired
            // command before executing it (its runnable never runs).
            assertTrue(runQueued.await(10, TimeUnit.SECONDS), "run command must be queued");
            now.addAndGet(31_000_000_000L);
            drainRun.countDown();
            // The failed apply triggers the bounded cleanup restore, which queues a second
            // command under its own cleanup deadline (created now, at 31s): advance past it
            // and drain again so the restore cannot hang and its failure is bounded.
            assertTrue(restoreQueued.await(10, TimeUnit.SECONDS),
                    "restore command must be queued");
            now.addAndGet(16_000_000_000L);
            drainRestore.countDown();
            assertTrue(ownerDone.await(10, TimeUnit.SECONDS), "scheduler owner must finish");

            Throwable failure = assertThrows(CompletionException.class, applied::join);
            Throwable root = failure;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            assertTrue(root.getMessage().contains("exceeded its deadline"),
                    "the real scheduler must reject the expired queued command: "
                            + root.getMessage());
            assertNull(ownerFailure.get(), "scheduler owner must not fail: " + ownerFailure.get());
        } finally {
            drainRun.countDown();
            drainRestore.countDown();
            owner.interrupt();
            ownerDone.await(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void applyNeverReturnsAppliedWhenRunDeadlineExpiresDuringObservation() {
        AtomicLong now = new AtomicLong(0L);
        MonotonicClock manual = now::get;
        try (RenderThreadScheduler scheduler = new RenderThreadScheduler(16)) {
            List<MatrixWindow> appliedWindows = new ArrayList<>();
            List<Locale> appliedLocales = new ArrayList<>();
            ReferenceCaseApplicator.CaseApplication recording =
                    new ReferenceCaseApplicator.CaseApplication() {
                        @Override public void applyWindow(
                                MatrixWindow window, Deadline deadline) {
                            appliedWindows.add(window);
                        }

                        @Override public void applyLocale(Locale locale, Deadline deadline) {
                            appliedLocales.add(locale);
                        }
                    };
            ReferenceCaseApplicator.Observation expiringObservation = matrixCase -> {
                now.addAndGet(31_000_000_000L);
                return new Lwjgl3MatrixRunner.DisplayObservation(
                        new MatrixWindow(1920, 1080), 1.0, 1.0, MatrixHiDpi.PIXELS,
                        "en-US", "", "host-owned-profile");
            };
            ReferenceCaseApplicator applicator = new ReferenceCaseApplicator(
                    scheduler, manual, "host-owned-profile", recording, expiringObservation);
            Deadline run = Deadline.after(manual, Duration.ofSeconds(30));
            MatrixCase matrixCase = new MatrixCase(
                    0, new MatrixWindow(1920, 1080), 1.0, 1.0, MatrixHiDpi.PIXELS,
                    "en-US", "", 16.0 / 9.0, List.of());

            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> applicator.apply(matrixCase, "host-owned-profile", run));
            assertTrue(failure.getMessage().contains("deadline expired during observation"),
                    "expiry during observation must fail before any Applied is returned: "
                            + failure.getMessage());
            assertEquals(List.of(new MatrixWindow(1920, 1080), new MatrixWindow(1280, 720)),
                    appliedWindows,
                    "the window apply and the bounded cleanup restore both run");
            assertEquals(List.of(Locale.forLanguageTag("en-US"), Locale.getDefault()),
                    appliedLocales,
                    "the requested locale and the restored host locale are both applied");
        }
    }

    @Test
    void restoreUsesSeparateBoundedCleanupDeadlineAfterRunExpiry() {
        AtomicLong now = new AtomicLong(0L);
        MonotonicClock manual = now::get;
        try (RenderThreadScheduler scheduler = new RenderThreadScheduler(16)) {
            List<Deadline> windowDeadlines = new ArrayList<>();
            List<MatrixWindow> restoredWindows = new ArrayList<>();
            List<Locale> restoredLocales = new ArrayList<>();
            ReferenceCaseApplicator.CaseApplication recording =
                    new ReferenceCaseApplicator.CaseApplication() {
                        @Override public void applyWindow(
                                MatrixWindow window, Deadline deadline) {
                            windowDeadlines.add(deadline);
                            restoredWindows.add(window);
                        }

                        @Override public void applyLocale(Locale locale, Deadline deadline) {
                            restoredLocales.add(locale);
                        }
                    };
            ReferenceCaseApplicator applicator = new ReferenceCaseApplicator(
                    scheduler, manual, "host-owned-profile", recording);
            Deadline run = Deadline.after(manual, Duration.ofSeconds(5));
            now.addAndGet(6_000_000_000L);

            applicator.restore();

            assertEquals(List.of(new MatrixWindow(1280, 720)), restoredWindows,
                    "restoration runs with the host-owned default window");
            assertEquals(List.of(Locale.getDefault()), restoredLocales,
                    "restoration runs with the host-owned original locale");
            assertEquals(1, windowDeadlines.size());
            assertFalse(windowDeadlines.get(0).isExpired(),
                    "the cleanup deadline must not reuse the expired run deadline");
            assertEquals(Duration.ofSeconds(15), windowDeadlines.get(0).timeout(),
                    "the cleanup deadline is separately bounded");
        }
    }
}
