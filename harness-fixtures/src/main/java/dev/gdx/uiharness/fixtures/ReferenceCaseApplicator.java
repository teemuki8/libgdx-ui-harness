package dev.gdx.uiharness.fixtures;

import com.badlogic.gdx.Gdx;
import dev.gdx.uiharness.core.matrix.MatrixCase;
import dev.gdx.uiharness.core.matrix.MatrixHiDpi;
import dev.gdx.uiharness.core.matrix.MatrixWindow;
import dev.gdx.uiharness.core.time.Deadline;
import dev.gdx.uiharness.core.time.MonotonicClock;
import dev.gdx.uiharness.lwjgl3.Lwjgl3MatrixRunner;
import dev.gdx.uiharness.scene2d.RenderThreadScheduler;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;

/** Allowlisted host-owned applicator that applies and observes one matrix case. */
public final class ReferenceCaseApplicator implements Lwjgl3MatrixRunner.MatrixCaseApplicator {
    private static final Set<MatrixWindow> ALLOWED_WINDOWS = Set.of(
            new MatrixWindow(1280, 720), new MatrixWindow(1920, 1080));
    private static final Set<String> ALLOWED_LOCALES = Set.of("en-US", "fi-FI");
    private static final MatrixWindow DEFAULT_WINDOW = new MatrixWindow(1280, 720);
    /**
     * Bounded cleanup deadline for restoration. Restoration is mandatory even when the run
     * deadline expired, so it runs under this separately bounded deadline and never reuses the
     * possibly-expired request deadline.
     */
    private static final Duration CLEANUP_DEADLINE = Duration.ofSeconds(15);

    /**
     * One host-owned window/locale application seam; injectable for failure-path tests. The
     * window and locale steps are independent so a failed window restore cannot prevent the
     * locale restore (and vice versa): restoration aggregates both before reporting.
     */
    interface CaseApplication {
        void applyWindow(MatrixWindow window, Deadline deadline);

        void applyLocale(Locale locale);
    }

    private final RenderThreadScheduler scheduler;
    private final MonotonicClock clock;
    /** The host's active restart profile; observations come from this state, never the request. */
    private final String restartProfileId;
    private final Locale originalLocale;
    private final CaseApplication caseApplication;

    /** Creates an applicator for the registered restart profile using the real window backend. */
    public ReferenceCaseApplicator(
            RenderThreadScheduler scheduler, MonotonicClock clock, String restartProfileId) {
        this(scheduler, clock, restartProfileId, null);
    }

    /** Creates an applicator with an injectable application step (package-private for tests). */
    ReferenceCaseApplicator(
            RenderThreadScheduler scheduler, MonotonicClock clock, String restartProfileId,
            CaseApplication caseApplication) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.restartProfileId = Objects.requireNonNull(restartProfileId, "restartProfileId");
        originalLocale = Locale.getDefault();
        this.caseApplication = caseApplication != null ? caseApplication : new RealApplication();
    }

    @Override
    public Lwjgl3MatrixRunner.ApplyResult apply(
            MatrixCase matrixCase, String profileId, Deadline deadline) {
        Objects.requireNonNull(matrixCase, "matrixCase");
        Objects.requireNonNull(deadline, "deadline");
        String unsupported = unsupportedReason(matrixCase, profileId);
        if (unsupported != null) {
            return new Lwjgl3MatrixRunner.ApplyResult.Unsupported(unsupported);
        }
        try {
            // Application must never continue beyond the request bound: every mutation is
            // gated on the run deadline and every window wait is bounded by its remaining time.
            if (deadline.isExpired()) {
                throw new IllegalStateException("case application deadline expired");
            }
            caseApplication.applyWindow(matrixCase.window(), deadline);
            if (deadline.isExpired()) {
                throw new IllegalStateException(
                        "case application deadline expired after window apply");
            }
            caseApplication.applyLocale(Locale.forLanguageTag(matrixCase.locale()));
            return new Lwjgl3MatrixRunner.ApplyResult.Applied(observe(matrixCase));
        } catch (RuntimeException failure) {
            // The runner contract requires the original display state to be restored before
            // throwing; the runner never observes a partially applied case. Restoration runs
            // under its own bounded cleanup deadline (never the expired run deadline) and, if it
            // also fails, the thrown failure must surface that risk (never silently claim
            // restored) while preserving the primary failure: both are suppressed on a composite
            // whose root message carries both texts for the runner's bounded evidence.
            RuntimeException restoreFailure = restoreSafely();
            if (restoreFailure != null) {
                IllegalStateException composite = new IllegalStateException(
                        rootMessage(failure)
                                + "; display restore also failed: " + rootMessage(restoreFailure));
                if (failure != restoreFailure) {
                    composite.addSuppressed(failure);
                }
                composite.addSuppressed(restoreFailure);
                throw composite;
            }
            throw failure;
        }
    }

    @Override
    public void restore() {
        // Restore as much as possible: the window and the locale are restored independently and
        // both outcomes are aggregated before reporting. Restoration remains mandatory even when
        // the run deadline expired, so it runs under the separately bounded cleanup deadline and
        // never reuses the expired request deadline. Each restore() call re-attempts the full
        // restoration, so an incomplete restoration is retried on the next call rather than
        // latched into a permanent no-op state.
        Deadline deadline = Deadline.after(clock, CLEANUP_DEADLINE);
        RuntimeException windowFailure = null;
        try {
            caseApplication.applyWindow(DEFAULT_WINDOW, deadline);
        } catch (RuntimeException failure) {
            windowFailure = failure;
        }
        RuntimeException localeFailure = null;
        try {
            caseApplication.applyLocale(originalLocale);
        } catch (RuntimeException failure) {
            localeFailure = failure;
        }
        if (windowFailure != null && localeFailure != null) {
            IllegalStateException aggregate = new IllegalStateException(
                    "window restore failed: " + rootMessage(windowFailure)
                            + "; locale restore failed: " + rootMessage(localeFailure));
            if (windowFailure != localeFailure) {
                aggregate.addSuppressed(windowFailure);
            }
            aggregate.addSuppressed(localeFailure);
            throw aggregate;
        }
        if (windowFailure != null) {
            throw windowFailure;
        }
        if (localeFailure != null) {
            throw localeFailure;
        }
    }

    /** Attempts a full restore, returning the restore failure (or {@code null}) instead of throwing. */
    private RuntimeException restoreSafely() {
        try {
            restore();
            return null;
        } catch (RuntimeException failure) {
            return failure;
        }
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? "case failed" : current.getMessage();
    }

    private String unsupportedReason(MatrixCase matrixCase, String profileId) {
        if (!restartProfileId.equals(profileId)) {
            return "unknown restart profile: " + profileId;
        }
        if (!ALLOWED_WINDOWS.contains(matrixCase.window())) {
            return "unsupported window: " + matrixCase.window();
        }
        if (matrixCase.uiScale() != 1.0) {
            return "unsupported uiScale: " + matrixCase.uiScale();
        }
        if (matrixCase.devicePixelRatio() != 1.0) {
            return "unsupported devicePixelRatio: " + matrixCase.devicePixelRatio();
        }
        if (matrixCase.hiDpiMode() != MatrixHiDpi.PIXELS) {
            return "unsupported hiDpiMode: " + matrixCase.hiDpiMode();
        }
        if (!ALLOWED_LOCALES.contains(matrixCase.locale())) {
            return "unsupported locale: " + matrixCase.locale();
        }
        if (matrixCase.fontSetId() != null && !matrixCase.fontSetId().isEmpty()) {
            return "unsupported fontSetId: " + matrixCase.fontSetId();
        }
        return null;
    }

    /** Real LWJGL3 window/locale backend. */
    private final class RealApplication implements CaseApplication {
        @Override
        public void applyWindow(MatrixWindow window, Deadline deadline) {
            if (scheduler.isOwnerThread()) {
                // The matrix runner's final restore can complete on the render thread inside a
                // scheduler drain (via a completed-frame observation); submitting and joining here
                // would deadlock, since only the render thread can drain. Window state is owned by
                // the render thread, so apply it directly.
                Gdx.graphics.setWindowedMode(window.width(), window.height());
            } else {
                scheduler.submit(() -> {
                            Gdx.graphics.setWindowedMode(window.width(), window.height());
                            return null;
                        },
                        deadline)
                        .toCompletableFuture().join();
            }
            while (!deadline.isExpired()
                    && (Gdx.graphics.getBackBufferWidth() != window.width()
                            || Gdx.graphics.getBackBufferHeight() != window.height())) {
                LockSupport.parkNanos(1_000_000L);
            }
            if (Gdx.graphics.getBackBufferWidth() != window.width()
                    || Gdx.graphics.getBackBufferHeight() != window.height()) {
                throw new IllegalStateException("window resize did not complete: requested="
                        + window + " observed=" + new MatrixWindow(
                                Gdx.graphics.getBackBufferWidth(),
                                Gdx.graphics.getBackBufferHeight()));
            }
        }

        @Override
        public void applyLocale(Locale locale) {
            Locale.setDefault(locale);
        }
    }

    private Lwjgl3MatrixRunner.DisplayObservation observe(MatrixCase matrixCase) {
        int logicalWidth = Gdx.graphics.getWidth();
        int logicalHeight = Gdx.graphics.getHeight();
        return new Lwjgl3MatrixRunner.DisplayObservation(
                new MatrixWindow(logicalWidth, logicalHeight),
                1.0,
                (double) Gdx.graphics.getBackBufferWidth() / logicalWidth,
                MatrixHiDpi.PIXELS,
                Locale.getDefault().toLanguageTag(),
                matrixCase.fontSetId() == null ? "" : matrixCase.fontSetId(),
                restartProfileId);
    }
}
