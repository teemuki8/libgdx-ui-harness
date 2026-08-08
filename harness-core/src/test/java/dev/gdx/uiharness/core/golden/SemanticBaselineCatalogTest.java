package dev.gdx.uiharness.core.golden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gdx.uiharness.core.model.Role;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class SemanticBaselineCatalogTest {
    private static final BaselineNode ROOT = new BaselineNode(
            Role.GROUP, "root", null, null, null, null, null,
            null, null, null, null, null, null, null, null,
            null, null, Map.of(), List.of());

    @Test void registeredBaselineIsImmutableAndDigestAddressed() {
        SemanticBaselineCatalog catalog = new SemanticBaselineCatalog();
        SemanticBaseline registered =
                SemanticBaseline.registered(1, 0, "reference-screen", ROOT, false);

        catalog.register(registered);
        SemanticBaseline required = catalog.require("reference-screen");

        assertSame(registered, required);
        assertEquals(64, registered.digest().length());
        assertEquals(registered.digest(), BaselineDigest.canonical(registered));
    }

    @Test void unknownAndMisspelledIdsAreRejected() {
        SemanticBaselineCatalog catalog = new SemanticBaselineCatalog();
        catalog.register(SemanticBaseline.registered(1, 0, "reference-screen", ROOT, false));

        assertThrows(IllegalArgumentException.class, () -> catalog.require("reference-scren"));
        assertThrows(IllegalArgumentException.class, () -> catalog.require("unknown-golden"));
    }

    @Test void conflictingReplacementIsRejected() {
        SemanticBaselineCatalog catalog = new SemanticBaselineCatalog();
        catalog.register(SemanticBaseline.registered(1, 0, "reference-screen", ROOT, false));
        BaselineNode changed = new BaselineNode(
                Role.GROUP, "root", "changed", null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, Map.of(), List.of());

        assertThrows(IllegalArgumentException.class, () -> catalog.register(
                SemanticBaseline.registered(1, 0, "reference-screen", changed, false)));
    }

    @Test void identicalReplacementIsIdempotent() {
        SemanticBaselineCatalog catalog = new SemanticBaselineCatalog();
        SemanticBaseline first = SemanticBaseline.registered(1, 0, "reference-screen", ROOT, false);
        catalog.register(first);

        catalog.register(SemanticBaseline.registered(1, 0, "reference-screen", ROOT, false));
        assertSame(first, catalog.require("reference-screen"));
    }

    @Test void registrationValidatesTheClaimedDigest() {
        SemanticBaselineCatalog catalog = new SemanticBaselineCatalog();
        SemanticBaseline tampered =
                new SemanticBaseline(1, 0, "reference-screen", ROOT, false, "0".repeat(64));

        assertThrows(IllegalArgumentException.class, () -> catalog.register(tampered));
    }

    @Test void digestDistinguishesCollidingPropertyEncodings() {
        BaselineNode singleValue = new BaselineNode(
                Role.GROUP, "root", null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, Map.of("a", "b, c=d"), List.of());
        BaselineNode twoValues = new BaselineNode(
                Role.GROUP, "root", null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, Map.of("a", "b", "c", "d"), List.of());

        assertNotEquals(
                SemanticBaseline.registered(1, 0, "r", singleValue, false).digest(),
                SemanticBaseline.registered(1, 0, "r", twoValues, false).digest(),
                "the canonical encoding must be injective for property maps");
    }

    @Test void digestDistinguishesPropertySplitAcrossEntries() {
        BaselineNode first = new BaselineNode(
                Role.GROUP, "root", null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, Map.of("a", "b, c=d", "e", "f"), List.of());
        BaselineNode second = new BaselineNode(
                Role.GROUP, "root", null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, Map.of("a", "b", "c", "d, e=f"), List.of());

        assertNotEquals(
                SemanticBaseline.registered(1, 0, "r", first, false).digest(),
                SemanticBaseline.registered(1, 0, "r", second, false).digest(),
                "the length-prefixed encoding must keep entry boundaries unambiguous");
    }

    @Test void nullRoleBaselineRegisters() {
        SemanticBaselineCatalog catalog = new SemanticBaselineCatalog();
        BaselineNode nullRole = new BaselineNode(
                null, "root", null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, Map.of(), List.of());
        SemanticBaseline baseline =
                SemanticBaseline.registered(1, 0, "null-role", nullRole, false);

        catalog.register(baseline);

        assertSame(baseline, catalog.require("null-role"));
    }

    @Test void concurrentConflictingRegistrationSucceedsExactlyOnce() throws Exception {
        int threads = 8;
        SemanticBaselineCatalog catalog = new SemanticBaselineCatalog();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier start = new CyclicBarrier(threads);
        List<Future<SemanticBaseline>> attempts = new ArrayList<>();
        for (int index = 0; index < threads; index++) {
            BaselineNode variant = new BaselineNode(
                    Role.GROUP, "root", "variant-" + index, null, null, null, null,
                    null, null, null, null, null, null, null, null,
                    null, null, Map.of(), List.of());
            SemanticBaseline candidate =
                    SemanticBaseline.registered(1, 0, "race", variant, false);
            Future<SemanticBaseline> attempt = pool.submit(() -> {
                start.await();
                try {
                    catalog.register(candidate);
                    return candidate;
                } catch (IllegalArgumentException conflict) {
                    return null;
                }
            });
            attempts.add(attempt);
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        List<SemanticBaseline> winners = new ArrayList<>();
        for (Future<SemanticBaseline> attempt : attempts) {
            SemanticBaseline winner = attempt.get();
            if (winner != null) {
                winners.add(winner);
            }
        }
        assertEquals(1, winners.size(),
                "exactly one conflicting registration may win the race");
        assertSame(winners.get(0), catalog.require("race"),
                "the catalog must retain the registration that won");
    }

    @Test void concurrentIdenticalRegistrationIsIdempotent() throws Exception {
        int threads = 8;
        SemanticBaselineCatalog catalog = new SemanticBaselineCatalog();
        SemanticBaseline baseline = SemanticBaseline.registered(1, 0, "shared", ROOT, false);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier start = new CyclicBarrier(threads);
        List<Future<Boolean>> attempts = new ArrayList<>();
        for (int index = 0; index < threads; index++) {
            Future<Boolean> attempt = pool.submit(() -> {
                start.await();
                catalog.register(baseline);
                return true;
            });
            attempts.add(attempt);
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        for (Future<Boolean> attempt : attempts) {
            assertTrue(attempt.get(), "identical concurrent registrations must all succeed");
        }
        assertSame(baseline, catalog.require("shared"),
                "the catalog must keep the first registered instance");
    }
}
