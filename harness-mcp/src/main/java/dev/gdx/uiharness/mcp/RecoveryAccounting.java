package dev.gdx.uiharness.mcp;

import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Bounded recovery-accounting store keyed by caller-controlled session or
 * fingerprint data. Entries are removed on success, terminal termination,
 * session close, and server close; idle entries expire monotonically after a
 * TTL that is far larger than the recovery wall-time ceiling, so an active key
 * can never bypass its attempt policy through eviction.
 */
final class RecoveryAccounting {
    static final int MAX_ENTRIES = 4_096;
    static final Duration TTL = Duration.ofMinutes(10);

    /**
     * One accounting snapshot: consumed attempts, workflow elapsed millis, and
     * whether the key is tracked. {@code tracked == false} means the key was
     * terminally rejected at capacity and never inserted.
     */
    record Snapshot(int consumed, long workflowElapsedMillis, boolean tracked) {}

    private final LongSupplier nanoClock;
    private final int maxEntries;
    private final Duration ttl;
    private final Map<String, Entry> entries; // access-ordered, guarded by this

    RecoveryAccounting(LongSupplier nanoClock) {
        this(nanoClock, MAX_ENTRIES, TTL);
    }

    /** Test constructor with an explicit capacity and TTL. */
    RecoveryAccounting(LongSupplier nanoClock, int maxEntries, Duration ttl) {
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        this.maxEntries = maxEntries;
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        this.entries = new LinkedHashMap<>(16, 0.75f, true);
    }

    /**
     * Records one transient attempt for a key. A new key at capacity is
     * terminally rejected: {@code tracked == false} and no state is created,
     * so an active key is never evicted and no budget can be reset by flooding.
     */
    synchronized Snapshot recordTransient(String key) {
        long now = nanoClock.getAsLong();
        expire(now);
        Entry entry = entries.get(key);
        if (entry == null) {
            if (entries.size() >= maxEntries) {
                return new Snapshot(0, 0, false);
            }
            entry = new Entry(now);
            entries.put(key, entry);
        }
        entry.consumed++;
        return new Snapshot(entry.consumed, elapsed(entry, now), true);
    }

    /** Reads the current snapshot without recording an attempt. */
    synchronized Snapshot snapshot(String key) {
        long now = nanoClock.getAsLong();
        expire(now);
        Entry entry = entries.get(key);
        return entry == null
                ? new Snapshot(0, 0, false)
                : new Snapshot(entry.consumed, elapsed(entry, now), true);
    }

    /** Removes one key after success, terminal termination, or session close. */
    synchronized void remove(String key) {
        entries.remove(key);
    }

    /** Removes all state on server close. */
    synchronized void clear() {
        entries.clear();
    }

    private void expire(long now) {
        Iterator<Map.Entry<String, Entry>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next().getValue();
            if (now - entry.workflowStartedNanos > ttl.toNanos()) {
                iterator.remove();
            }
        }
    }

    private static long elapsed(Entry entry, long now) {
        return Math.max(0, (now - entry.workflowStartedNanos) / 1_000_000);
    }

    private static final class Entry {
        final long workflowStartedNanos;
        int consumed;

        Entry(long workflowStartedNanos) {
            this.workflowStartedNanos = workflowStartedNanos;
        }
    }
}
