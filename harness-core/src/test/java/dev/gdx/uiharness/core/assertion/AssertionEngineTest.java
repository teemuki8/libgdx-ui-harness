package dev.gdx.uiharness.core.assertion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gdx.uiharness.core.error.ErrorCode;
import dev.gdx.uiharness.core.error.HarnessException;
import dev.gdx.uiharness.core.locator.Locator;
import dev.gdx.uiharness.core.model.Bounds;
import dev.gdx.uiharness.core.model.Role;
import dev.gdx.uiharness.core.model.SemanticNode;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import dev.gdx.uiharness.core.model.SemanticState;
import dev.gdx.uiharness.core.time.Deadline;
import dev.gdx.uiharness.core.time.MonotonicClock;
import dev.gdx.uiharness.core.wait.FrameSignal;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

final class AssertionEngineTest {
    private final AssertionEngine engine = new AssertionEngine();

    @Test void retriesWithAFreshSnapshotAndStrictResolutionOnEveryCompletedFrame() {
        FakeClock clock = new FakeClock();
        TestFrames frames = new TestFrames();
        ArrayDeque<SemanticSnapshot> supplied = new ArrayDeque<>(List.of(
                snapshot(1, 0, node("old", "other", "waiting", 0)),
                snapshot(2, 1, node("target", "target", "ready", 0))));
        AtomicInteger reads = new AtomicInteger();

        CompletionStage<AssertionResult> result = engine.assertThat(
                () -> { reads.incrementAndGet(); return supplied.remove(); },
                request(clock, new UiAssertion.TextEquals("ready"), 100), frames, clock);
        assertEquals(1, reads.get());
        frames.emit(2, 1);

        assertEquals(AssertionResult.Status.PASSED, result.toCompletableFuture().join().status());
        assertEquals(2, reads.get());
    }

    @Test void failsAtTheExactMonotonicDeadlineWithExplicitElapsedTime() {
        FakeClock clock = new FakeClock();
        TestFrames frames = new TestFrames();
        CompletionStage<AssertionResult> result = engine.assertThat(
                () -> snapshot(1, 1, node("target", "target", "waiting", 0)),
                request(clock, new UiAssertion.TextEquals("ready"), 10), frames, clock);

        clock.advanceMillis(10);
        frames.emit(2, 2);

        AssertionResult failure = result.toCompletableFuture().join();
        assertEquals(AssertionResult.Status.FAILED, failure.status());
        assertEquals(10_000_000L, failure.elapsedNanos());
        assertEquals("waiting", failure.evidence().observed());
        assertEquals(1, frames.closedSubscriptions());
    }

    @Test void stableForFramesComparesOnlyDeclaredPropertiesAcrossCompletedFrames() {
        FakeClock clock = new FakeClock();
        TestFrames frames = new TestFrames();
        ArrayDeque<SemanticSnapshot> supplied = new ArrayDeque<>(List.of(
                snapshot(0, 0, node("target", "target", "same", 0)),
                snapshot(1, 1, node("target", "target", "same", 10)),
                snapshot(2, 2, node("target", "target", "same", 20)),
                snapshot(3, 3, node("target", "target", "same", 30))));
        CompletionStage<AssertionResult> result = engine.assertThat(supplied::remove,
                request(clock, new UiAssertion.StableForFrames(3,
                        Set.of(UiAssertion.StableProperty.TEXT)), 100), frames, clock);

        frames.emit(1, 1);
        frames.emit(2, 2);
        assertTrue(!result.toCompletableFuture().isDone());
        frames.emit(3, 3);

        AssertionResult passed = result.toCompletableFuture().join();
        assertEquals(AssertionResult.Status.PASSED, passed.status());
        assertEquals(3, passed.evidence().frame());
        assertTrue(passed.evidence().observed().contains("3/3"));
    }

    @Test void stabilityResetsToOneWhenASelectedPropertyChanges() {
        FakeClock clock = new FakeClock();
        TestFrames frames = new TestFrames();
        ArrayDeque<SemanticSnapshot> supplied = new ArrayDeque<>(List.of(
                snapshot(0, 0, node("target", "target", "initial", 0)),
                snapshot(1, 1, node("target", "target", "a", 0)),
                snapshot(2, 2, node("target", "target", "b", 0)),
                snapshot(3, 3, node("target", "target", "b", 0)),
                snapshot(4, 4, node("target", "target", "b", 0))));
        CompletionStage<AssertionResult> result = engine.assertThat(supplied::remove,
                request(clock, new UiAssertion.StableForFrames(3,
                        Set.of(UiAssertion.StableProperty.TEXT)), 100), frames, clock);

        frames.emit(1, 1);
        frames.emit(2, 2);
        frames.emit(3, 3);
        assertTrue(!result.toCompletableFuture().isDone());
        frames.emit(4, 4);
        assertEquals(AssertionResult.Status.PASSED, result.toCompletableFuture().join().status());
    }

    @Test void rejectsStableFrameCountsAboveTheBound() {
        assertThrows(IllegalArgumentException.class, () -> new UiAssertion.StableForFrames(
                UiAssertion.MAX_STABLE_FRAMES + 1, Set.of(UiAssertion.StableProperty.TEXT)));
    }

    @Test void immediateFrameCallbacksAreDrainedIterativelyRatherThanRecursively() {
        FakeClock clock = new FakeClock();
        ImmediateFrames frames = new ImmediateFrames(20_000);
        AtomicInteger revision = new AtomicInteger();
        CompletionStage<AssertionResult> result = engine.assertThat(() -> {
            int attempt = revision.getAndIncrement();
            return snapshot(attempt, attempt,
                    node("target", "target", attempt == 20_000 ? "ready" : "waiting", 0));
        }, request(clock, new UiAssertion.TextEquals("ready"), 100), frames, clock);
        assertEquals(AssertionResult.Status.PASSED, result.toCompletableFuture().join().status());
        assertEquals(20_001, revision.get());
    }

    @Test void rejectedFrameRegistrationCompletesTheStageExceptionally() {
        FakeClock clock = new FakeClock();
        IllegalStateException rejected = new IllegalStateException("closed");
        CompletionStage<AssertionResult> result = engine.assertThat(
                () -> snapshot(0, 0, node("target", "target", "waiting", 0)),
                request(clock, new UiAssertion.Visible(), 100), listener -> { throw rejected; }, clock);
        CompletionException thrown = assertThrows(CompletionException.class,
                () -> result.toCompletableFuture().join());
        assertEquals(rejected, thrown.getCause());
    }

    @Test void finalStrictResolutionFailureIsPropagatedWithBoundedCandidates() {
        FakeClock clock = new FakeClock();
        TestFrames frames = new TestFrames();
        SemanticSnapshot many = snapshot(1, 1,
                node("a", "target", "", 0), node("b", "target", "", 0));
        CompletionStage<AssertionResult> result = engine.assertThat(() -> many,
                request(clock, new UiAssertion.Visible(), 10), frames, clock);
        clock.advanceMillis(10);
        frames.emit(2, 2);

        CompletionException completion = assertThrows(CompletionException.class,
                () -> result.toCompletableFuture().join());
        HarnessException strict = assertInstanceOf(HarnessException.class, completion.getCause());
        assertEquals(ErrorCode.STRICTNESS_VIOLATION, strict.code());
        assertTrue(strict.evidence().candidates().size() <= 20);
    }

    private static AssertionRequest request(FakeClock clock, UiAssertion assertion, long millis) {
        return new AssertionRequest(AssertionRequest.SCHEMA_VERSION, Locator.testId("target"), assertion,
                Deadline.after(clock, Duration.ofMillis(millis)));
    }

    private static SemanticSnapshot snapshot(long revision, long frame, SemanticNode... children) {
        Bounds bounds = new Bounds(0, 0, 100, 100);
        List<String> ids = java.util.Arrays.stream(children).map(SemanticNode::id).toList();
        SemanticNode root = new SemanticNode("root", null, ids, Role.GROUP, "Root", "", null,
                "root", null, "Group", state(true), bounds, bounds, bounds, 0, Map.of());
        Map<String, SemanticNode> nodes = new LinkedHashMap<>();
        nodes.put("root", root);
        for (SemanticNode child : children) nodes.put(child.id(), child);
        return new SemanticSnapshot(revision, frame, "root", nodes);
    }

    private static SemanticNode node(String id, String testId, String text, double x) {
        Bounds bounds = new Bounds(x, 0, 10, 10);
        return new SemanticNode(id, "root", List.of(), Role.BUTTON, "Name", text, null, testId,
                null, "TextButton", state(true), bounds, bounds, bounds, 0, Map.of());
    }

    private static SemanticState state(boolean visible) {
        return new SemanticState(visible, true, Optional.of(true), Optional.of(false),
                Optional.empty(), Optional.empty(), Optional.empty(), false, true, 1, false, true, true);
    }

    private static final class FakeClock implements MonotonicClock {
        private long nanos;
        @Override public long nanoTime() { return nanos; }
        void advanceMillis(long millis) { nanos += Duration.ofMillis(millis).toNanos(); }
    }

    private static class TestFrames implements FrameSignal {
        private FrameListener listener;
        private int closed;
        @Override public Subscription subscribe(FrameListener listener) {
            this.listener = listener;
            return () -> { this.listener = null; closed++; };
        }
        void emit(long revision, long frame) { listener.onFrame(new Frame(revision, frame)); }
        int closedSubscriptions() { return closed; }
    }

    private static final class ImmediateFrames extends TestFrames {
        private final int callbacks;
        ImmediateFrames(int callbacks) { this.callbacks = callbacks; }
        @Override public Subscription subscribe(FrameListener listener) {
            Subscription subscription = super.subscribe(listener);
            for (int i = 1; i <= callbacks; i++) listener.onFrame(new Frame(i, i));
            return subscription;
        }
    }
}
