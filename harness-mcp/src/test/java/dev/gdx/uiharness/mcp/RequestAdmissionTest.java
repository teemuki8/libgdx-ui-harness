package dev.gdx.uiharness.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

final class RequestAdmissionTest {
    private static final HarnessToolCatalog.AccessMode READ =
            HarnessToolCatalog.AccessMode.READ_ONLY;
    private static final HarnessToolCatalog.AccessMode WRITE =
            HarnessToolCatalog.AccessMode.MUTATING;

    @Test void admitsExactlyGlobalLimitAndRejectsTheNextWithLimitExceeded() {
        RequestAdmission admission = new RequestAdmission(2, 4, 4);
        CompletableFuture<String> g0 = new CompletableFuture<>();
        CompletableFuture<String> g1 = new CompletableFuture<>();
        CompletableFuture<String> g2 = new CompletableFuture<>();
        AtomicInteger invoked = new AtomicInteger();
        AtomicInteger index = new AtomicInteger();

        CompletionStage<String> first =
                admission.submit(RequestAdmission.SessionKey.session("s"), READ, gated(invoked, index, g0, g1, g2));
        CompletionStage<String> second =
                admission.submit(RequestAdmission.SessionKey.session("s"), READ, gated(invoked, index, g0, g1, g2));
        CompletionStage<String> third =
                admission.submit(RequestAdmission.SessionKey.session("s"), READ, gated(invoked, index, g0, g1, g2));

        assertEquals(2, invoked.get());
        assertFalse(first.toCompletableFuture().isDone());
        assertFalse(second.toCompletableFuture().isDone());
        assertRejected(third);

        g0.complete("one");
        assertEquals("one", first.toCompletableFuture().join());
        CompletionStage<String> fourth =
                admission.submit(RequestAdmission.SessionKey.session("s"), READ, gated(invoked, index, g0, g1, g2));
        assertEquals(3, invoked.get());
        g1.complete("two");
        g2.complete("three");
        assertEquals("two", second.toCompletableFuture().join());
        assertEquals("three", fourth.toCompletableFuture().join());
    }

    @Test void perSessionLimitBindsOneSessionWhileAnotherUsesRemainingGlobalCapacity() {
        RequestAdmission admission = new RequestAdmission(8, 2, 4);
        CompletableFuture<String> a0 = new CompletableFuture<>();
        CompletableFuture<String> a1 = new CompletableFuture<>();
        CompletableFuture<String> a2 = new CompletableFuture<>();
        CompletableFuture<String> b0 = new CompletableFuture<>();
        AtomicInteger invoked = new AtomicInteger();
        AtomicInteger indexA = new AtomicInteger();
        AtomicInteger indexB = new AtomicInteger();

        CompletionStage<String> a1st =
                admission.submit(RequestAdmission.SessionKey.session("A"), READ, gated(invoked, indexA, a0, a1, a2));
        CompletionStage<String> a2nd =
                admission.submit(RequestAdmission.SessionKey.session("A"), READ, gated(invoked, indexA, a0, a1, a2));
        CompletionStage<String> a3rd =
                admission.submit(RequestAdmission.SessionKey.session("A"), READ, gated(invoked, indexA, a0, a1, a2));
        CompletionStage<String> b1st =
                admission.submit(RequestAdmission.SessionKey.session("B"), READ, gated(invoked, indexB, b0));

        assertEquals(3, invoked.get());
        assertRejected(a3rd);
        assertFalse(b1st.toCompletableFuture().isDone());

        a0.complete("a0");
        a1.complete("a1");
        b0.complete("b0");
        assertEquals("a0", a1st.toCompletableFuture().join());
        assertEquals("a1", a2nd.toCompletableFuture().join());
        assertEquals("b0", b1st.toCompletableFuture().join());
    }

    @Test void sessionlessScopeIsIndependentFromAClientSessionNamedCatalog() {
        RequestAdmission admission = new RequestAdmission(8, 1, 1);
        CompletableFuture<String> g0 = new CompletableFuture<>();
        CompletableFuture<String> g1 = new CompletableFuture<>();
        AtomicInteger invoked = new AtomicInteger();
        AtomicInteger index = new AtomicInteger();

        // The sessionless scope occupies its own lane while a real client session literally
        // named "catalog" has a separate per-session lane and capacity.
        CompletionStage<String> sessionless = admission.submit(
                RequestAdmission.SessionKey.sessionless(), READ,
                gated(invoked, index, g0, g1));
        CompletionStage<String> catalogSession = admission.submit(
                RequestAdmission.SessionKey.session("catalog"), WRITE,
                gated(invoked, index, g0, g1));

        assertEquals(2, invoked.get());
        assertFalse(sessionless.toCompletableFuture().isDone());
        assertFalse(catalogSession.toCompletableFuture().isDone());

        g0.complete("a");
        g1.complete("b");
        assertEquals("a", sessionless.toCompletableFuture().join());
        assertEquals("b", catalogSession.toCompletableFuture().join());
    }

    @Test void sameSessionMutationsStartInSubmissionOrderAndNeverOverlap() {
        RequestAdmission admission = new RequestAdmission(8, 8, 4);
        CompletableFuture<String> m0 = new CompletableFuture<>();
        CompletableFuture<String> m1 = new CompletableFuture<>();
        CompletableFuture<String> m2 = new CompletableFuture<>();
        AtomicInteger invoked = new AtomicInteger();
        AtomicInteger index = new AtomicInteger();

        CompletionStage<String> first =
                admission.submit(RequestAdmission.SessionKey.session("s"), WRITE, gated(invoked, index, m0, m1, m2));
        CompletionStage<String> second =
                admission.submit(RequestAdmission.SessionKey.session("s"), WRITE, gated(invoked, index, m0, m1, m2));
        CompletionStage<String> third =
                admission.submit(RequestAdmission.SessionKey.session("s"), WRITE, gated(invoked, index, m0, m1, m2));

        // Only the first mutation started; the others wait for the lane tail.
        assertEquals(1, invoked.get());
        assertFalse(first.toCompletableFuture().isDone());
        assertFalse(second.toCompletableFuture().isDone());
        assertFalse(third.toCompletableFuture().isDone());

        m0.complete("m0");
        assertEquals("m0", first.toCompletableFuture().join());
        // The second started only after the first reached a terminal state.
        assertEquals(2, invoked.get());
        assertFalse(second.toCompletableFuture().isDone());

        m1.complete("m1");
        assertEquals("m1", second.toCompletableFuture().join());
        assertEquals(3, invoked.get());

        m2.complete("m2");
        assertEquals("m2", third.toCompletableFuture().join());
    }

    @Test void readOnlyRequestsForOneSessionMayOverlap() {
        RequestAdmission admission = new RequestAdmission(8, 8, 4);
        CompletableFuture<String> r0 = new CompletableFuture<>();
        CompletableFuture<String> r1 = new CompletableFuture<>();
        AtomicInteger invoked = new AtomicInteger();
        AtomicInteger index = new AtomicInteger();

        CompletionStage<String> first =
                admission.submit(RequestAdmission.SessionKey.session("s"), READ, gated(invoked, index, r0, r1));
        CompletionStage<String> second =
                admission.submit(RequestAdmission.SessionKey.session("s"), READ, gated(invoked, index, r0, r1));

        // Both read-only requests started while the other was still in flight.
        assertEquals(2, invoked.get());
        assertFalse(first.toCompletableFuture().isDone());
        assertFalse(second.toCompletableFuture().isDone());

        r0.complete("r0");
        r1.complete("r1");
        assertEquals("r0", first.toCompletableFuture().join());
        assertEquals("r1", second.toCompletableFuture().join());
    }

    @Test void cancellingInFlightWorkReleasesItsPermit() {
        RequestAdmission admission = new RequestAdmission(1, 4, 4);
        CompletableFuture<String> g0 = new CompletableFuture<>();
        CompletableFuture<String> g1 = new CompletableFuture<>();
        AtomicInteger invoked = new AtomicInteger();
        AtomicInteger index = new AtomicInteger();

        CompletionStage<String> first =
                admission.submit(RequestAdmission.SessionKey.session("s"), READ, gated(invoked, index, g0, g1));
        first.toCompletableFuture().cancel(false);
        assertTrue(g0.isCancelled());

        CompletionStage<String> second =
                admission.submit(RequestAdmission.SessionKey.session("s"), READ, gated(invoked, index, g0, g1));
        assertEquals(2, invoked.get());
        g1.complete("ok");
        assertEquals("ok", second.toCompletableFuture().join());
    }

    @Test void cancellingQueuedMutationReleasesItsQueueSlotWithoutStartingIt() {
        RequestAdmission admission = new RequestAdmission(8, 8, 1);
        CompletableFuture<String> m0 = new CompletableFuture<>();
        CompletableFuture<String> m1 = new CompletableFuture<>();
        AtomicInteger invoked = new AtomicInteger();
        AtomicInteger index = new AtomicInteger();

        CompletionStage<String> first =
                admission.submit(RequestAdmission.SessionKey.session("s"), WRITE, gated(invoked, index, m0, m1));
        CompletionStage<String> queued =
                admission.submit(RequestAdmission.SessionKey.session("s"), WRITE, gated(invoked, index, m0, m1));
        assertEquals(1, invoked.get());

        queued.toCompletableFuture().cancel(false);
        // The released queue slot admits a replacement mutation immediately.
        CompletionStage<String> next =
                admission.submit(RequestAdmission.SessionKey.session("s"), WRITE, gated(invoked, index, m0, m1));
        assertFalse(next.toCompletableFuture().isDone());

        m0.complete("m0");
        assertEquals("m0", first.toCompletableFuture().join());
        assertEquals(2, invoked.get());
        m1.complete("m1");
        assertEquals("m1", next.toCompletableFuture().join());
    }

    @Test void exceptionalCompletionReleasesEveryPermit() {
        RequestAdmission admission = new RequestAdmission(1, 4, 4);
        CompletableFuture<String> g0 = new CompletableFuture<>();
        CompletableFuture<String> g1 = new CompletableFuture<>();
        AtomicInteger invoked = new AtomicInteger();
        AtomicInteger index = new AtomicInteger();

        CompletionStage<String> first =
                admission.submit(RequestAdmission.SessionKey.session("s"), READ, gated(invoked, index, g0, g1));
        g0.completeExceptionally(new IllegalStateException("boom"));
        assertThrows(CompletionException.class, first.toCompletableFuture()::join);

        CompletionStage<String> second =
                admission.submit(RequestAdmission.SessionKey.session("s"), READ, gated(invoked, index, g0, g1));
        assertEquals(2, invoked.get());
        g1.complete("ok");
        assertEquals("ok", second.toCompletableFuture().join());
    }

    @Test void synchronousSupplierFailureReleasesEveryPermit() {
        RequestAdmission admission = new RequestAdmission(1, 4, 4);
        CompletableFuture<String> g1 = new CompletableFuture<>();
        AtomicInteger invoked = new AtomicInteger();
        AtomicInteger index = new AtomicInteger();

        CompletionStage<String> first = admission.submit(RequestAdmission.SessionKey.session("s"), READ, () -> {
            invoked.incrementAndGet();
            throw new IllegalStateException("supplier exploded");
        });
        assertThrows(CompletionException.class, first.toCompletableFuture()::join);
        assertEquals(1, invoked.get());

        CompletionStage<String> second =
                admission.submit(RequestAdmission.SessionKey.session("s"), READ, gated(invoked, index, g1));
        assertEquals(2, invoked.get());
        g1.complete("ok");
        assertEquals("ok", second.toCompletableFuture().join());
    }

    @Test void boundedMutationQueueRejectsTheNextQueuedMutation() {
        RequestAdmission admission = new RequestAdmission(8, 8, 2);
        CompletableFuture<String> m0 = new CompletableFuture<>();
        CompletableFuture<String> m1 = new CompletableFuture<>();
        CompletableFuture<String> m2 = new CompletableFuture<>();
        CompletableFuture<String> m3 = new CompletableFuture<>();
        AtomicInteger invoked = new AtomicInteger();
        AtomicInteger index = new AtomicInteger();

        CompletionStage<String> first =
                admission.submit(RequestAdmission.SessionKey.session("s"), WRITE, gated(invoked, index, m0, m1, m2, m3));
        CompletionStage<String> second =
                admission.submit(RequestAdmission.SessionKey.session("s"), WRITE, gated(invoked, index, m0, m1, m2, m3));
        CompletionStage<String> third =
                admission.submit(RequestAdmission.SessionKey.session("s"), WRITE, gated(invoked, index, m0, m1, m2, m3));
        CompletionStage<String> fourth =
                admission.submit(RequestAdmission.SessionKey.session("s"), WRITE, gated(invoked, index, m0, m1, m2, m3));

        assertEquals(1, invoked.get());
        assertRejected(fourth);

        m0.complete("m0");
        assertEquals("m0", first.toCompletableFuture().join());
        assertEquals(2, invoked.get());
        CompletionStage<String> fifth =
                admission.submit(RequestAdmission.SessionKey.session("s"), WRITE, gated(invoked, index, m0, m1, m2, m3));
        assertFalse(fifth.toCompletableFuture().isDone());

        m1.complete("m1");
        m2.complete("m2");
        m3.complete("m3");
        assertEquals("m1", second.toCompletableFuture().join());
        assertEquals("m2", third.toCompletableFuture().join());
        assertEquals("m3", fifth.toCompletableFuture().join());
    }

    @Test void closeRejectsQueuedWorkAndStopsNewAdmissionWithoutInterruptingRunningWork() {
        RequestAdmission admission = new RequestAdmission(8, 8, 4);
        CompletableFuture<String> m0 = new CompletableFuture<>();
        CompletableFuture<String> m1 = new CompletableFuture<>();
        AtomicInteger invoked = new AtomicInteger();
        AtomicInteger index = new AtomicInteger();

        CompletionStage<String> first =
                admission.submit(RequestAdmission.SessionKey.session("s"), WRITE, gated(invoked, index, m0, m1));
        CompletionStage<String> queued =
                admission.submit(RequestAdmission.SessionKey.session("s"), WRITE, gated(invoked, index, m0, m1));
        assertEquals(1, invoked.get());

        admission.close();
        assertRejected(queued);
        assertRejected(admission.submit(RequestAdmission.SessionKey.session("s"), READ, () -> CompletableFuture.completedFuture("x")));
        assertEquals(1, invoked.get());

        // Running work admitted before close still completes normally.
        m0.complete("m0");
        assertEquals("m0", first.toCompletableFuture().join());
        admission.close();
    }

    @Test void permitIsHeldUntilTheWorkStageIncludingTranslationIsTerminal() {
        RequestAdmission admission = new RequestAdmission(1, 4, 4);
        CompletableFuture<String> translation = new CompletableFuture<>();

        CompletionStage<String> first = admission.submit(RequestAdmission.SessionKey.session("s"), READ, () -> translation);
        // The permit is still held while the work stage (which includes result translation
        // and output accounting) is not terminal.
        assertRejected(admission.submit(RequestAdmission.SessionKey.session("s"), READ,
                () -> CompletableFuture.completedFuture("x")));

        translation.complete("done");
        assertEquals("done", first.toCompletableFuture().join());
        assertEquals("x", admission.submit(RequestAdmission.SessionKey.session("s"), READ,
                () -> CompletableFuture.completedFuture("x"))
                .toCompletableFuture().join());
    }

    @Test void throwingAdmissionObserverLeaksNoPermitOrLaneSlot() {
        AtomicInteger observerCalls = new AtomicInteger();
        RequestAdmission admission = new RequestAdmission(1, 1, 1, requestId -> {
            if (observerCalls.getAndIncrement() == 0) {
                throw new IllegalStateException("observer exploded");
            }
        });
        CompletableFuture<String> g0 = new CompletableFuture<>();
        AtomicInteger invoked = new AtomicInteger();
        AtomicInteger index = new AtomicInteger();

        // The observer throws before any permit or lane state is committed, so the failure
        // propagates out of submit and neither the global nor the per-session permit is used.
        assertThrows(IllegalStateException.class, () -> admission.submit(
                RequestAdmission.SessionKey.session("s"), READ,
                gated(invoked, index, g0)));
        assertEquals(0, invoked.get());

        // With both limits at 1, the next request would be rejected if the throwing observer
        // had consumed either permit or left a stale lane entry behind.
        CompletionStage<String> next = admission.submit(
                RequestAdmission.SessionKey.session("s"), READ,
                gated(invoked, index, g0));
        assertEquals(1, invoked.get());
        assertFalse(next.toCompletableFuture().isDone());

        g0.complete("ok");
        assertEquals("ok", next.toCompletableFuture().join());
    }

    @Test void throwingObserverOnQueuedMutationLeaksNoQueueSlotOrPermit() {
        AtomicInteger observerCalls = new AtomicInteger();
        RequestAdmission admission = new RequestAdmission(8, 8, 1, requestId -> {
            if (observerCalls.getAndIncrement() == 1) {
                throw new IllegalStateException("observer exploded");
            }
        });
        CompletableFuture<String> m0 = new CompletableFuture<>();
        CompletableFuture<String> m1 = new CompletableFuture<>();
        AtomicInteger invoked = new AtomicInteger();
        AtomicInteger index = new AtomicInteger();

        CompletionStage<String> first = admission.submit(
                RequestAdmission.SessionKey.session("s"), WRITE,
                gated(invoked, index, m0, m1));
        assertEquals(1, invoked.get());

        // The queued-path observer throws before the queue slot or its permits are committed,
        // so the failed attempt must not occupy the single queue slot or hold a permit.
        assertThrows(IllegalStateException.class, () -> admission.submit(
                RequestAdmission.SessionKey.session("s"), WRITE,
                gated(invoked, index, m0, m1)));
        assertEquals(1, invoked.get());

        // The single queue slot is still free: a replacement mutation is admitted and runs
        // after the first completes, proving no slot or permit leaked.
        CompletionStage<String> next = admission.submit(
                RequestAdmission.SessionKey.session("s"), WRITE,
                gated(invoked, index, m0, m1));
        assertEquals(1, invoked.get());
        assertFalse(next.toCompletableFuture().isDone());

        m0.complete("m0");
        assertEquals("m0", first.toCompletableFuture().join());
        assertEquals(2, invoked.get());
        m1.complete("m1");
        assertEquals("m1", next.toCompletableFuture().join());
    }

    @SafeVarargs
    private static Supplier<CompletionStage<String>> gated(
            AtomicInteger invoked,
            AtomicInteger index,
            CompletableFuture<String>... gates) {
        return () -> {
            invoked.incrementAndGet();
            return gates[index.getAndIncrement()];
        };
    }

    private static void assertRejected(CompletionStage<?> stage) {
        CompletableFuture<?> future = stage.toCompletableFuture();
        assertTrue(future.isDone());
        assertTrue(future.isCompletedExceptionally());
        try {
            future.join();
            throw new AssertionError("expected exceptional completion");
        } catch (CompletionException failure) {
            assertInstanceOf(RequestAdmission.LimitExceededException.class, failure.getCause());
        }
    }
}
