package dev.gdx.uiharness.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.gdx.uiharness.core.matrix.MatrixCase;
import dev.gdx.uiharness.core.matrix.MatrixHiDpi;
import dev.gdx.uiharness.core.matrix.MatrixWindow;
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
                ReferenceCaseApplicator.CaseApplication failing = (window, locale, deadline) -> {
                    appliedWindows.add(window);
                    // Faithfully apply the locale argument on every invocation so the restore
                    // call is observable; only the first invocation fails.
                    Locale.setDefault(locale);
                    if (first.getAndSet(false)) {
                        throw new IllegalStateException("window resize timed out");
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
            ReferenceCaseApplicator.CaseApplication noop = (window, locale, deadline) -> {};
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
}
