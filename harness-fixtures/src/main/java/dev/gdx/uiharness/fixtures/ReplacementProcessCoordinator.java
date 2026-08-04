package dev.gdx.uiharness.fixtures;

import dev.gdx.uiharness.core.scenario.ScenarioRequest;
import dev.gdx.uiharness.lwjgl3.RegisteredLaunchCoordinator;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Fixture-private coordinator that owns one replacement JVM per restart handoff. */
final class ReplacementProcessCoordinator implements RegisteredLaunchCoordinator, AutoCloseable {
    interface Launcher {
        ReplacementProcess launch(ScenarioRequest request) throws Exception;
    }

    private final String profileId;
    private final Executor executor;
    private final Launcher launcher;
    private final AtomicReference<ReplacementProcess> active = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    ReplacementProcessCoordinator(String profileId, Executor executor, Launcher launcher) {
        this.profileId = Objects.requireNonNull(profileId, "profileId");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.launcher = Objects.requireNonNull(launcher, "launcher");
    }

    @Override public CompletionStage<HandoffOutcome> restart(ScenarioRequest request) {
        Objects.requireNonNull(request, "request");
        if (!profileId.equals(request.profileId())) {
            return CompletableFuture.completedFuture(HandoffFailure.UNKNOWN_PROFILE);
        }
        if (request.deadline().isExpired()) {
            return CompletableFuture.completedFuture(HandoffFailure.DEADLINE);
        }
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicReference<ReplacementProcess> launched = new AtomicReference<>();
        CompletableFuture<HandoffOutcome> result = new CompletableFuture<>() {
            @Override public boolean cancel(boolean mayInterruptIfRunning) {
                if (!cancelled.compareAndSet(false, true)) {
                    return false;
                }
                ReplacementProcess process = launched.get();
                if (process != null) {
                    process.cancel();
                    active.compareAndSet(process, null);
                    process.close();
                }
                return super.cancel(mayInterruptIfRunning);
            }
        };
        executor.execute(() -> {
            if (cancelled.get() || closed.get()) {
                result.cancel(false);
                return;
            }
            try {
                ReplacementProcess process = launcher.launch(request);
                launched.set(process);
                if (!active.compareAndSet(null, process)) {
                    process.close();
                    throw new IllegalStateException("replacement process already active");
                }
                if (cancelled.get()) {
                    process.cancel();
                    active.compareAndSet(process, null);
                    process.close();
                    return;
                }
                process.result().whenComplete((outcome, failure) -> {
                    active.compareAndSet(process, null);
                    process.close();
                    if (failure != null) {
                        result.completeExceptionally(failure);
                    } else {
                        result.complete(outcome);
                    }
                });
            } catch (Exception failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    @Override public void close() {
        closed.set(true);
        ReplacementProcess process = active.getAndSet(null);
        if (process != null) {
            process.cancel();
            process.close();
        }
    }
}
