package dev.gdx.uiharness.agentruntime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gdx.uiharness.core.gesture.ExactTickCoordinator.TickAdvanceResult;
import dev.gdx.uiharness.core.gesture.ExactTickCoordinator.TickFailureCategory;
import dev.gdx.uiharness.core.gesture.ExactTickCoordinator.TickPreflight;
import dev.gdx.uiharness.core.time.Deadline;
import dev.gdx.uiharness.core.time.DeadlineScheduler;
import dev.gdx.uiharness.core.time.MonotonicClock;
import dev.gdx.uiharness.core.wait.FrameSignal;
import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime;
import io.github.teemuki8.libgdx.agent.runtime.core.BaselineKind;
import io.github.teemuki8.libgdx.agent.runtime.core.ControlLimits;
import io.github.teemuki8.libgdx.agent.runtime.core.FrameId;
import io.github.teemuki8.libgdx.agent.runtime.core.SessionId;
import io.github.teemuki8.libgdx.agent.runtime.core.SimulationControllerSpec;
import io.github.teemuki8.libgdx.agent.runtime.core.UiFrameCorrelation;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

final class AgentRuntimeTickCoordinatorTest {
    private static final long DELTA_NANOS = 16_000_000L;

    @Test void preflightRejectsMissingControlDispatchPauseDeltaAndBounds() {
        try (AgentRuntime noDispatch = AgentRuntime.builder()
                .sessionId(new SessionId("no-dispatch")).build()) {
            noDispatch.start();
            AgentRuntimeTickCoordinator coordinator = coordinator(noDispatch, DELTA_NANOS,
                    new ManualFrames(), new ManualDeadlines());
            assertRejected(TickFailureCategory.UNSUPPORTED_CAPABILITY,
                    coordinator.preflight(1, deadline()));
        }

        try (Fixture missingController = new Fixture(false, false, DELTA_NANOS, null)) {
            assertRejected(TickFailureCategory.UNSUPPORTED_CAPABILITY,
                    missingController.coordinator.preflight(1, missingController.deadline()));
        }
        try (Fixture running = new Fixture(true, false, DELTA_NANOS, null)) {
            assertRejected(TickFailureCategory.INVALID_STATE,
                    running.coordinator.preflight(1, running.deadline()));
            assertEquals(0, running.ticks.get());
        }
        try (Fixture invalidDelta = new Fixture(true, true, 0, null)) {
            assertRejected(TickFailureCategory.INVALID_STATE,
                    invalidDelta.coordinator.preflight(1, invalidDelta.deadline()));
        }
        ControlLimits lowDelta = new ControlLimits(4, 4, 16, DELTA_NANOS - 1);
        try (Fixture excessiveDelta = new Fixture(true, true, DELTA_NANOS, lowDelta)) {
            assertRejected(TickFailureCategory.INVALID_STATE,
                    excessiveDelta.coordinator.preflight(1, excessiveDelta.deadline()));
        }
        ControlLimits lowLimit = new ControlLimits(4, 2, 16, DELTA_NANOS);
        try (Fixture bounded = new Fixture(true, true, DELTA_NANOS, lowLimit)) {
            assertRejected(TickFailureCategory.LIMIT_EXCEEDED,
                    bounded.coordinator.preflight(3, bounded.deadline()));
            assertEquals(2, assertInstanceOf(TickPreflight.Ready.class,
                    bounded.coordinator.preflight(2, bounded.deadline())).maximumTicks());
        }
        try (Fixture expired = new Fixture(true, true, DELTA_NANOS, null)) {
            Deadline deadline = expired.deadline();
            expired.clock.advance(Duration.ofSeconds(5));
            assertRejected(TickFailureCategory.TIMED_OUT,
                    expired.coordinator.preflight(1, deadline));
            assertEquals(0, expired.ticks.get());
        }
    }

    @Test void advancesExactTicksAfterApplicationDispatchAndFrameSignal() {
        try (Fixture fixture = new Fixture(true, true, DELTA_NANOS, null)) {
            CompletableFuture<TickAdvanceResult> result = fixture.coordinator
                    .advance(3, fixture.deadline()).toCompletableFuture();

            assertFalse(result.isDone());
            assertEquals(1, fixture.dispatcher.size());
            fixture.dispatcher.runNext();
            assertEquals(3, fixture.ticks.get());
            assertEquals(List.of(DELTA_NANOS, DELTA_NANOS, DELTA_NANOS), fixture.deltas);
            fixture.recordCorrelation(new FrameId(1), 40);
            fixture.recordCorrelation(new FrameId(3), 42);
            assertFalse(result.isDone(), "runtime completion is polled only on observable state");

            fixture.frames.publish(42, 42);

            TickAdvanceResult.Completed completed = assertInstanceOf(
                    TickAdvanceResult.Completed.class, result.join());
            assertEquals(3, completed.evidence().requestedTicks());
            assertEquals(3, completed.evidence().completedTicks());
            assertEquals(0, completed.evidence().startTick());
            assertEquals(3, completed.evidence().finalTick());
            assertEquals(1, completed.evidence().firstRuntimeFrame().orElseThrow());
            assertEquals(3, completed.evidence().finalRuntimeFrame().orElseThrow());
            assertEquals(40, completed.evidence().firstUiFrame().orElseThrow());
            assertEquals(42, completed.evidence().finalUiFrame().orElseThrow());
            assertEquals(DELTA_NANOS, completed.evidence().configuredDeltaNanos());
        }
    }

    @Test void callbackFailureAndCancellationReturnClosedFailures() {
        try (Fixture failed = new Fixture(true, true, DELTA_NANOS, null)) {
            failed.failTick.set(true);
            CompletableFuture<TickAdvanceResult> result = failed.coordinator
                    .advance(2, failed.deadline()).toCompletableFuture();
            failed.dispatcher.runNext();
            failed.frames.publish(1, 1);
            TickAdvanceResult.Failed failure = assertInstanceOf(
                    TickAdvanceResult.Failed.class, result.join());
            assertEquals(TickFailureCategory.CALLBACK_FAILED, failure.failure().category());
        }

        try (Fixture cancelled = new Fixture(true, true, DELTA_NANOS, null)) {
            CompletableFuture<TickAdvanceResult> result = cancelled.coordinator
                    .advance(2, cancelled.deadline()).toCompletableFuture();
            assertFalse(result.cancel(false));
            TickAdvanceResult.Failed failure = assertInstanceOf(
                    TickAdvanceResult.Failed.class, result.join());
            assertEquals(TickFailureCategory.CANCELLED, failure.failure().category());
            assertEquals(0, cancelled.ticks.get());
        }
    }

    @Test void epochChangeAndDeadlineCancelQueuedRuntimeWork() {
        try (Fixture changedEpoch = new Fixture(true, true, DELTA_NANOS, null)) {
            CompletableFuture<TickAdvanceResult> result = changedEpoch.coordinator
                    .advance(2, changedEpoch.deadline()).toCompletableFuture();
            changedEpoch.runtime.startEpoch(BaselineKind.SCENARIO_RESET);
            changedEpoch.frames.publish(1, 1);

            TickAdvanceResult.Failed failure = assertInstanceOf(
                    TickAdvanceResult.Failed.class, result.join());
            assertEquals(TickFailureCategory.EPOCH_CHANGED, failure.failure().category());
            changedEpoch.dispatcher.runNext();
            assertEquals(0, changedEpoch.ticks.get(),
                    "epoch failure must cancel queued application work");
        }

        try (Fixture timedOut = new Fixture(true, true, DELTA_NANOS, null)) {
            Deadline deadline = timedOut.deadline();
            CompletableFuture<TickAdvanceResult> result = timedOut.coordinator
                    .advance(2, deadline).toCompletableFuture();
            timedOut.clock.advance(Duration.ofSeconds(5));
            timedOut.deadlines.fire();

            TickAdvanceResult.Failed failure = assertInstanceOf(
                    TickAdvanceResult.Failed.class, result.join());
            assertEquals(TickFailureCategory.TIMED_OUT, failure.failure().category());
            timedOut.dispatcher.runNext();
            assertEquals(0, timedOut.ticks.get());
        }
    }

    @Test void incompleteUiCorrelationIsOmittedAsOneUnprovenPair() {
        try (Fixture fixture = new Fixture(true, true, DELTA_NANOS, null)) {
            CompletableFuture<TickAdvanceResult> result = fixture.coordinator
                    .advance(2, fixture.deadline()).toCompletableFuture();
            fixture.dispatcher.runNext();
            fixture.recordCorrelation(new FrameId(1), 40);
            fixture.frames.publish(41, 41);

            TickAdvanceResult.Completed completed = assertInstanceOf(
                    TickAdvanceResult.Completed.class, result.join());
            assertTrue(completed.evidence().firstUiFrame().isEmpty());
            assertTrue(completed.evidence().finalUiFrame().isEmpty());
        }
    }

    private static AgentRuntimeTickCoordinator coordinator(
            AgentRuntime runtime,
            long delta,
            FrameSignal frames,
            DeadlineScheduler deadlines) {
        return new AgentRuntimeTickCoordinator(
                runtime, "ui-session", delta, frames, deadlines);
    }

    private static Deadline deadline() {
        return Deadline.after(System::nanoTime, Duration.ofSeconds(5));
    }

    private static void assertRejected(
            TickFailureCategory category, TickPreflight preflight) {
        assertEquals(category,
                assertInstanceOf(TickPreflight.Rejected.class, preflight).failure().category());
    }

    private static final class Fixture implements AutoCloseable {
        final QueueDispatcher dispatcher = new QueueDispatcher();
        final ManualClock clock = new ManualClock();
        final ManualFrames frames = new ManualFrames();
        final ManualDeadlines deadlines = new ManualDeadlines();
        final AtomicBoolean paused = new AtomicBoolean();
        final AtomicBoolean failTick = new AtomicBoolean();
        final AtomicInteger ticks = new AtomicInteger();
        final List<Long> deltas = new ArrayList<>();
        final AgentRuntime runtime;
        final AgentRuntimeTickCoordinator coordinator;

        Fixture(boolean registerController, boolean startPaused, long delta,
                ControlLimits limits) {
            AgentRuntime.Builder builder = AgentRuntime.builder()
                    .sessionId(new SessionId("runtime"))
                    .captureThread(Thread.currentThread())
                    .commandDispatcher(dispatcher);
            if (limits != null) {
                builder.controlLimits(limits);
            }
            runtime = builder.build();
            runtime.start();
            if (registerController) {
                runtime.controls().register(SimulationControllerSpec.builder()
                        .pause(() -> paused.set(true))
                        .resume(() -> paused.set(false))
                        .tick(value -> {
                            if (failTick.get()) {
                                throw new IllegalStateException("tick failed");
                            }
                            deltas.add(value);
                            ticks.incrementAndGet();
                        })
                        .build());
            }
            if (startPaused) {
                runtime.controls().control(true, "pause", Duration.ofSeconds(1));
                dispatcher.runNext();
                assertTrue(runtime.controls().paused());
            }
            coordinator = coordinator(runtime, delta, frames, deadlines);
        }

        Deadline deadline() {
            return Deadline.after(clock, Duration.ofSeconds(5));
        }

        void recordCorrelation(FrameId runtimeFrame, long uiFrame) {
            runtime.uiCorrelations().recordFrame(new UiFrameCorrelation(
                    runtime.currentEpoch(), runtimeFrame, "ui-session",
                    Optional.of(Long.toString(uiFrame)), Optional.empty()));
        }

        @Override public void close() {
            coordinator.close();
            runtime.close();
        }
    }

    private static final class QueueDispatcher
            implements io.github.teemuki8.libgdx.agent.runtime.core.ApplicationCommandDispatcher {
        private final ArrayDeque<Runnable> queue = new ArrayDeque<>();

        @Override public void dispatch(Runnable command) {
            queue.add(command);
        }

        int size() {
            return queue.size();
        }

        void runNext() {
            queue.removeFirst().run();
        }
    }

    private static final class ManualClock implements MonotonicClock {
        private final AtomicLong nanos = new AtomicLong();

        void advance(Duration duration) {
            nanos.addAndGet(duration.toNanos());
        }

        @Override public long nanoTime() {
            return nanos.get();
        }
    }

    private static final class ManualFrames implements FrameSignal {
        private final CopyOnWriteArrayList<FrameListener> listeners =
                new CopyOnWriteArrayList<>();

        @Override public Subscription subscribe(FrameListener listener) {
            listeners.add(listener);
            return () -> listeners.remove(listener);
        }

        void publish(long revision, long frame) {
            Frame value = new Frame(revision, frame);
            listeners.forEach(listener -> listener.onFrame(value));
        }
    }

    private static final class ManualDeadlines implements DeadlineScheduler {
        private Runnable signal;

        @Override public Cancellation schedule(Duration delay, Runnable signal) {
            this.signal = signal;
            return () -> {
                if (this.signal == signal) {
                    this.signal = null;
                }
            };
        }

        void fire() {
            Runnable pending = signal;
            signal = null;
            if (pending != null) {
                pending.run();
            }
        }
    }
}
