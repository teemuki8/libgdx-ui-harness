package dev.gdx.uiharness.fixtures;

import dev.gdx.uiharness.core.action.Action;
import dev.gdx.uiharness.core.action.ActionResult;
import dev.gdx.uiharness.core.action.Harness;
import dev.gdx.uiharness.core.locator.Locator;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import dev.gdx.uiharness.core.time.Deadline;
import dev.gdx.uiharness.core.time.MonotonicClock;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Minimal owned fixture used to compile and execute the documented public Java flow. */
final class FixtureHarness implements AutoCloseable {
    private final MonotonicClock clock = MonotonicClock.system();
    private final Harness harness = new ExampleHarness();
    private String state = "unsaved";
    private boolean open = true;

    private FixtureHarness() {}

    static FixtureHarness start() {
        return new FixtureHarness();
    }

    Harness harness() {
        requireOpen();
        return harness;
    }

    MonotonicClock clock() {
        requireOpen();
        return clock;
    }

    String state() {
        return state;
    }

    @Override public void close() {
        open = false;
    }

    private void requireOpen() {
        if (!open) {
            throw new IllegalStateException("fixture is closed");
        }
    }

    private final class ExampleHarness implements Harness {
        @Override public CompletionStage<ActionResult> perform(
                Locator locator, Action action, Deadline deadline) {
            requireOpen();
            Objects.requireNonNull(locator, "locator");
            Objects.requireNonNull(deadline, "deadline");
            if (!(Objects.requireNonNull(action, "action") instanceof Action.Click)) {
                return CompletableFuture.failedFuture(
                        new IllegalArgumentException("fixture supports only click"));
            }
            if (deadline.isExpired()) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("fixture action deadline expired"));
            }
            state = "saved";
            return CompletableFuture.completedFuture(
                    new ActionResult(0, 1, state, Map.of("fixture", "public-api")));
        }

        @Override public CompletionStage<SemanticSnapshot> snapshot(Deadline deadline) {
            return CompletableFuture.failedFuture(
                    new UnsupportedOperationException("example does not request a snapshot"));
        }
    }
}
