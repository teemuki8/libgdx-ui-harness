package dev.gdx.uiharness.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gdx.uiharness.core.matrix.MatrixCase;
import dev.gdx.uiharness.core.matrix.MatrixHiDpi;
import dev.gdx.uiharness.core.matrix.MatrixWindow;
import dev.gdx.uiharness.core.time.Deadline;
import dev.gdx.uiharness.core.time.MonotonicClock;
import dev.gdx.uiharness.lwjgl3.Lwjgl3MatrixRunner;
import dev.gdx.uiharness.scene2d.RenderThreadScheduler;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
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

                            @Override public void applyLocale(Locale locale) {
                                Locale.setDefault(locale);
                            }
                        };
                ReferenceCaseApplicator applicator = new ReferenceCaseApplicator(
                        scheduler, clock, "desktop-restart-1280x720", failing);
                MatrixCase matrixCase = new MatrixCase(
                        0, new MatrixWindow(1920, 1080), 1.0, 1.0, MatrixHiDpi.PIXELS,
                        "en-US", "", 16.0 / 9.0, List.of());

                assertThrows(IllegalStateException.class,
                        () -> applicator.apply(matrixCase, "desktop-restart-1280x720"));
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

                        @Override public void applyLocale(Locale locale) {
                        }
                    };
            ReferenceCaseApplicator applicator = new ReferenceCaseApplicator(
                    scheduler, clock, "host-owned-profile", noop);
            MatrixCase matrixCase = new MatrixCase(
                    0, new MatrixWindow(1280, 720), 1.0, 1.0, MatrixHiDpi.PIXELS,
                    "en-US", "", 16.0 / 9.0, List.of());

            Lwjgl3MatrixRunner.ApplyResult unknown =
                    applicator.apply(matrixCase, "other-profile");

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

                        @Override public void applyLocale(Locale locale) {
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

                            @Override public void applyLocale(Locale locale) {
                                Locale.setDefault(locale);
                            }
                        };
                ReferenceCaseApplicator applicator = new ReferenceCaseApplicator(
                        scheduler, clock, "desktop-restart-1280x720", alwaysFailing);
                MatrixCase matrixCase = new MatrixCase(
                        0, new MatrixWindow(1920, 1080), 1.0, 1.0, MatrixHiDpi.PIXELS,
                        "en-US", "", 16.0 / 9.0, List.of());

                IllegalStateException failure = assertThrows(IllegalStateException.class,
                        () -> applicator.apply(matrixCase, "desktop-restart-1280x720"));
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

                            @Override public void applyLocale(Locale locale) {
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

                        @Override public void applyLocale(Locale locale) {
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
}
