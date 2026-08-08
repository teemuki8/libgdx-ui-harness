package dev.gdx.uiharness.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

final class RecoveryAccountingTest {
    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    private final RecoveryAccounting accounting = new RecoveryAccounting(nanos::get);

    @Test void attemptsAccumulateAcrossCallsForOneKey() {
        assertEquals(1, accounting.recordTransient("game").consumed());
        assertEquals(2, accounting.recordTransient("game").consumed());
        assertEquals(2, accounting.snapshot("game").consumed());
    }

    @Test void elapsedIsMeasuredFromTheWorkflowStartNotServerConstruction() {
        nanos.set(1_000_000_000L);
        accounting.recordTransient("game");
        nanos.set(1_025_000_000L);
        assertEquals(25, accounting.snapshot("game").workflowElapsedMillis());
        accounting.remove("game");
        assertEquals(0, accounting.snapshot("game").workflowElapsedMillis());
    }

    @Test void immediateSnapshotReportsZeroElapsedWithoutAWorkflow() {
        nanos.set(999_000_000_000L);
        RecoveryAccounting.Snapshot fresh = accounting.snapshot("game");
        assertEquals(0, fresh.consumed());
        assertEquals(0, fresh.workflowElapsedMillis());
        assertFalse(fresh.tracked());
    }

    @Test void cardinalityIsBoundedAndNewKeysAreTerminallyRejectedAtCapacity() {
        for (int index = 0; index < RecoveryAccounting.MAX_ENTRIES; index++) {
            assertTrue(accounting.recordTransient("session-" + index).tracked());
        }
        RecoveryAccounting.Snapshot extra = accounting.recordTransient("overflow");
        assertFalse(extra.tracked(), "a new key at capacity must be terminally rejected");
        assertEquals(0, extra.consumed());
        RecoveryAccounting.Snapshot retained = accounting.snapshot("session-0");
        assertTrue(retained.tracked(), "an active key must never be evicted");
        assertTrue(retained.consumed() >= 1);
    }

    @Test void repeatedRejectedKeysCannotResetBudgets() {
        RecoveryAccounting small = new RecoveryAccounting(
                nanos::get, 2, RecoveryAccounting.TTL);
        small.recordTransient("session-a");
        small.recordTransient("session-b");

        RecoveryAccounting.Snapshot first = small.recordTransient("overflow");
        RecoveryAccounting.Snapshot again = small.recordTransient("overflow");
        assertFalse(first.tracked());
        assertFalse(again.tracked());
        assertEquals(0, small.snapshot("overflow").consumed(),
                "a rejected key must never create or reset accounting state");

        RecoveryAccounting.Snapshot existing = small.snapshot("session-a");
        assertTrue(existing.tracked());
        assertEquals(1, existing.consumed(),
                "flooding new keys must not reset an existing key's budget");
    }

    @Test void entriesExpireMonotonicallyAfterTtl() {
        accounting.recordTransient("stale");
        nanos.set(1_000_000_000L + RecoveryAccounting.TTL.toNanos() + 1);
        RecoveryAccounting.Snapshot expired = accounting.snapshot("stale");
        assertEquals(0, expired.consumed(),
                "a workflow older than the TTL must expire monotonically");
        assertFalse(expired.tracked());
    }

    @Test void removeAndClearReleaseState() {
        accounting.recordTransient("game");
        accounting.remove("game");
        assertEquals(0, accounting.snapshot("game").consumed());
        accounting.recordTransient("game");
        accounting.clear();
        assertEquals(0, accounting.snapshot("game").consumed());
    }

    @Test void removeIfOwnedRemovesOnlyTheMatchingGeneration() {
        RecoveryAccounting.Snapshot first = accounting.recordTransient("game");
        assertTrue(first.tracked());
        assertTrue(first.token() != RecoveryAccounting.NO_TOKEN);
        assertTrue(accounting.removeIfOwned("game", first.token()));
        assertEquals(0, accounting.snapshot("game").consumed());

        accounting.recordTransient("game");
        assertFalse(accounting.removeIfOwned("game", first.token()),
                "a stale token must never remove a newer reservation");
        assertTrue(accounting.snapshot("game").tracked());
    }

    @Test void tokenChangesAcrossGenerations() {
        long first = accounting.recordTransient("game").token();
        accounting.remove("game");
        long second = accounting.recordTransient("game").token();
        assertTrue(second != first, "a new reservation must receive a fresh generation token");
        assertFalse(accounting.removeIfOwned("game", first));
        assertTrue(accounting.snapshot("game").tracked());
    }

    @Test void clearReleasesCapacityForNewKeys() {
        RecoveryAccounting small = new RecoveryAccounting(nanos::get, 2, RecoveryAccounting.TTL);
        small.recordTransient("a");
        small.recordTransient("b");
        assertFalse(small.recordTransient("c").tracked());
        small.clear();
        assertTrue(small.recordTransient("c").tracked(),
                "clear must release capacity so a later workflow starts fresh");
    }
}
