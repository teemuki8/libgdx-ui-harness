package dev.gdx.uiharness.core.wait;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gdx.uiharness.core.error.ErrorCode;
import dev.gdx.uiharness.core.error.HarnessException;
import dev.gdx.uiharness.core.limits.HarnessLimits;
import dev.gdx.uiharness.core.locator.Locator;
import dev.gdx.uiharness.core.locator.LocatorEngine;
import dev.gdx.uiharness.core.locator.StrictResolution;
import dev.gdx.uiharness.core.model.Bounds;
import dev.gdx.uiharness.core.model.Role;
import dev.gdx.uiharness.core.model.SemanticNode;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import dev.gdx.uiharness.core.model.SemanticState;
import dev.gdx.uiharness.core.time.Deadline;
import dev.gdx.uiharness.core.time.MonotonicClock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

final class WaitEngineTest {
    private static final Locator TARGET = Locator.testId("target");
    private static final LocatorEngine LOCATORS = new StrictResolution(HarnessLimits.defaults());

    @Test void timeoutUsesMonotonicVirtualTimeWithoutSleeping() throws Exception {
        FakeClock clock = new FakeClock();
        TestFrameSignal frames = new TestFrameSignal();
        CountDownLatch initialSnapshotRead = new CountDownLatch(1);
        Supplier<SemanticSnapshot> snapshots = () -> {
            initialSnapshotRead.countDown();
            return snapshot(0, 0, "target", false);
        };
        WaitEngine waits = new WaitEngine(snapshots, LOCATORS, clock, frames);
        Deadline deadline = Deadline.after(clock, Duration.ofMillis(100));

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<WaitResult> result = CompletableFuture.supplyAsync(
                    () -> waits.await(TARGET, WaitCondition.visible(), deadline), executor);
            initialSnapshotRead.await();
            clock.advance(Duration.ofMillis(100));
            frames.emit(1, 1);

            CompletionException completion = assertThrows(CompletionException.class, result::join);
            HarnessException error = assertInstanceOf(HarnessException.class, completion.getCause());
            assertEquals(ErrorCode.TIMEOUT, error.code());
            assertEquals(Duration.ofMillis(100), error.evidence().elapsed());
            assertEquals(0L, error.evidence().lastSnapshotRevision().orElseThrow());
        }
    }

    @Test void monotonicDeadlineExpiresWithoutAnotherFrameSignal() {
        MonotonicClock clock = MonotonicClock.system();
        TestFrameSignal frames = new TestFrameSignal();
        AtomicInteger reads = new AtomicInteger();
        WaitEngine waits = new WaitEngine(
                () -> {
                    reads.incrementAndGet();
                    return snapshot(0, 0, "target", false);
                },
                LOCATORS,
                clock,
                frames);
        Duration timeout = Duration.ofMillis(100);

        HarnessException error = assertThrows(
                HarnessException.class,
                () -> waits.await(
                        TARGET,
                        WaitCondition.visible(),
                        Deadline.after(clock, timeout)));

        assertEquals(ErrorCode.TIMEOUT, error.code());
        assertTrue(error.evidence().elapsed().compareTo(timeout) >= 0);
        assertEquals(0L, error.evidence().lastSnapshotRevision().orElseThrow());
        assertEquals(1, reads.get());
    }

    @Test void reevaluatesOnlyForChangedFrameOrRevisionAndUsesFreshSnapshot() throws Exception {
        FakeClock clock = new FakeClock();
        TestFrameSignal frames = new TestFrameSignal();
        AtomicInteger reads = new AtomicInteger();
        CountDownLatch initialRead = new CountDownLatch(1);
        CountDownLatch secondRead = new CountDownLatch(1);
        Supplier<SemanticSnapshot> snapshots = () -> {
            int read = reads.incrementAndGet();
            if (read == 1) {
                initialRead.countDown();
                return snapshot(0, 0, "old-target", false);
            }
            if (read == 2) {
                secondRead.countDown();
                return snapshot(1, 1, "replacement", false);
            }
            return snapshot(2, 2, "replacement", true);
        };
        WaitEngine waits = new WaitEngine(snapshots, LOCATORS, clock, frames);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<WaitResult> result = CompletableFuture.supplyAsync(
                    () -> waits.await(TARGET, WaitCondition.visible(),
                            Deadline.after(clock, Duration.ofSeconds(1))), executor);
            initialRead.await();
            frames.emit(0, 0);
            frames.emit(1, 1);
            secondRead.await();

            assertEquals(2, reads.get());
            assertFalse(result.isDone());

            frames.emit(2, 2);
            WaitResult completed = result.join();
            assertEquals(3, reads.get());
            assertEquals(2L, completed.snapshot().revision());
            assertEquals("replacement", completed.queryResult().matches().getFirst().id());
        }
    }

    @Test void closeReleasesActiveWaitAndRejectsLaterWaits() throws Exception {
        FakeClock clock = new FakeClock();
        TestFrameSignal frames = new TestFrameSignal();
        CountDownLatch initialRead = new CountDownLatch(1);
        WaitEngine waits = new WaitEngine(
                () -> {
                    initialRead.countDown();
                    return snapshot(0, 0, "target", false);
                },
                LOCATORS,
                clock,
                frames);
        Deadline deadline = Deadline.after(clock, Duration.ofSeconds(1));

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<WaitResult> active = CompletableFuture.supplyAsync(
                    () -> waits.await(TARGET, WaitCondition.visible(), deadline), executor);
            initialRead.await();
            waits.close();

            CompletionException completion = assertThrows(CompletionException.class, active::join);
            HarnessException activeError = assertInstanceOf(
                    HarnessException.class, completion.getCause());
            assertEquals(ErrorCode.SESSION_CLOSED, activeError.code());
            HarnessException laterError = assertThrows(HarnessException.class,
                    () -> waits.await(TARGET, WaitCondition.visible(), deadline));
            assertEquals(ErrorCode.SESSION_CLOSED, laterError.code());
        }
    }

    @Test void closeWinsOverAnInProgressSnapshotEvaluation() throws Exception {
        FakeClock clock = new FakeClock();
        TestFrameSignal frames = new TestFrameSignal();
        CountDownLatch snapshotEntered = new CountDownLatch(1);
        CountDownLatch releaseSnapshot = new CountDownLatch(1);
        WaitEngine waits = new WaitEngine(
                () -> {
                    snapshotEntered.countDown();
                    try {
                        releaseSnapshot.await();
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(error);
                    }
                    return snapshot(0, 0, "target", true);
                },
                LOCATORS,
                clock,
                frames);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<WaitResult> active = CompletableFuture.supplyAsync(
                    () -> waits.await(
                            TARGET,
                            WaitCondition.visible(),
                            Deadline.after(clock, Duration.ofSeconds(1))),
                    executor);
            snapshotEntered.await();
            waits.close();
            releaseSnapshot.countDown();

            CompletionException completion = assertThrows(CompletionException.class, active::join);
            HarnessException error = assertInstanceOf(
                    HarnessException.class, completion.getCause());
            assertEquals(ErrorCode.SESSION_CLOSED, error.code());
        }
    }

    @Test void closingFrameSourceReleasesActiveWait() throws Exception {
        FakeClock clock = new FakeClock();
        TestFrameSignal frames = new TestFrameSignal();
        CountDownLatch initialRead = new CountDownLatch(1);
        WaitEngine waits = new WaitEngine(
                () -> {
                    initialRead.countDown();
                    return snapshot(0, 0, "target", false);
                },
                LOCATORS,
                clock,
                frames);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<WaitResult> active = CompletableFuture.supplyAsync(
                    () -> waits.await(
                            TARGET,
                            WaitCondition.visible(),
                            Deadline.after(clock, Duration.ofSeconds(1))),
                    executor);
            initialRead.await();
            frames.close();

            CompletionException completion = assertThrows(CompletionException.class, active::join);
            HarnessException error = assertInstanceOf(
                    HarnessException.class, completion.getCause());
            assertEquals(ErrorCode.SESSION_CLOSED, error.code());
        }
    }

    private static SemanticSnapshot snapshot(
            long revision, long frame, String targetId, boolean visible) {
        SemanticNode root = node("root", null, List.of(targetId), true, null);
        SemanticNode target = node(targetId, "root", List.of(), visible, "target");
        Map<String, SemanticNode> nodes = new LinkedHashMap<>();
        nodes.put(root.id(), root);
        nodes.put(target.id(), target);
        return new SemanticSnapshot(revision, frame, root.id(), nodes);
    }

    private static SemanticNode node(
            String id, String parentId, List<String> children, boolean visible, String testId) {
        Bounds bounds = new Bounds(0, 0, 10, 10);
        SemanticState state = new SemanticState(
                visible,
                true,
                Optional.of(true),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                false,
                true,
                1.0,
                false,
                true,
                true);
        return new SemanticNode(
                id,
                parentId,
                children,
                parentId == null ? Role.GROUP : Role.BUTTON,
                id,
                "",
                null,
                testId,
                id,
                parentId == null ? "Group" : "Button",
                state,
                bounds,
                bounds,
                bounds,
                0,
                Map.of());
    }

    private static final class FakeClock implements MonotonicClock {
        private final AtomicLong nanos = new AtomicLong();

        @Override public long nanoTime() {
            return nanos.get();
        }

        void advance(Duration duration) {
            nanos.addAndGet(duration.toNanos());
        }
    }

    private static final class TestFrameSignal implements FrameSignal {
        private final CopyOnWriteArrayList<FrameListener> listeners =
                new CopyOnWriteArrayList<>();

        @Override public Subscription subscribe(FrameListener listener) {
            listeners.add(listener);
            return () -> listeners.remove(listener);
        }

        void emit(long revision, long frame) {
            Frame event = new Frame(revision, frame);
            for (FrameListener listener : listeners) {
                listener.onFrame(event);
            }
        }

        void close() {
            for (FrameListener listener : listeners) {
                listener.onClosed();
            }
            listeners.clear();
        }
    }
}
