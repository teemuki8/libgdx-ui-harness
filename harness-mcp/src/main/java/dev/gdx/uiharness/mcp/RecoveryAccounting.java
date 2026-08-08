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
    /** Sentinel for keys with no workflow reservation. */
    static final long NO_TOKEN = 0;

    /**
     * One accounting snapshot: consumed attempts, workflow elapsed millis,
     * whether the key is tracked, and the workflow generation token of the
     * current reservation. {@code tracked == false} means the key was
     * terminally rejected at capacity and never inserted; the token is then
     * {@link #NO_TOKEN}.
     */
    record Snapshot(int consumed, long workflowElapsedMillis, boolean tracked, long token) {}

    private final LongSupplier nanoClock;
    private final int maxEntries;
    private final Duration ttl;
    private final Map<String, Entry> entries; // access-ordered, guarded by this
    private long nextToken = 1; // workflow generation counter, guarded by this

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
     * A new reservation receives a fresh workflow generation token.
     */
    synchronized Snapshot recordTransient(String key) {
        long now = nanoClock.getAsLong();
        expire(now);
        Entry entry = entries.get(key);
        if (entry == null) {
            if (entries.size() >= maxEntries) {
                return new Snapshot(0, 0, false, NO_TOKEN);
            }
            entry = new Entry(now, nextToken++);
            entries.put(key, entry);
        }
        entry.consumed++;
        return new Snapshot(entry.consumed, elapsed(entry, now), true, entry.token);
    }

    /** Reads the current snapshot without recording an attempt. */
    synchronized Snapshot snapshot(String key) {
        long now = nanoClock.getAsLong();
        expire(now);
        Entry entry = entries.get(key);
        return entry == null
                ? new Snapshot(0, 0, false, NO_TOKEN)
                : new Snapshot(entry.consumed, elapsed(entry, now), true, entry.token);
    }

    /** Returns the current workflow generation token for a key, or {@link #NO_TOKEN}. */
    synchronized long tokenOf(String key) {
        long now = nanoClock.getAsLong();
        expire(now);
        Entry entry = entries.get(key);
        return entry == null ? NO_TOKEN : entry.token;
    }

    /**
     * Removes a key only when its reservation still belongs to the given
     * workflow generation, so a stale completion can never delete a newer
     * workflow's state. Returns whether the key was removed.
     */
    synchronized boolean removeIfOwned(String key, long token) {
        Entry entry = entries.get(key);
        if (entry == null || entry.token != token) {
            return false;
        }
        entries.remove(key);
        return true;
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
        final long token;
        int consumed;

        Entry(long workflowStartedNanos, long token) {
            this.workflowStartedNanos = workflowStartedNanos;
            this.token = token;
        }
    }
}
