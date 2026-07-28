package dev.gdx.uiharness.scene2d;

import com.badlogic.gdx.scenes.scene2d.Stage;
import dev.gdx.uiharness.core.error.ErrorCode;
import dev.gdx.uiharness.core.error.ErrorEvidence;
import dev.gdx.uiharness.core.error.HarnessException;
import dev.gdx.uiharness.core.time.MonotonicClock;
import dev.gdx.uiharness.core.wait.FrameSignal;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** Fixed-step monotonic clock and frame signal for an owned Scene2D fixture. */
public final class ControlledStageClock
        implements MonotonicClock, FrameSignal, AutoCloseable {
    private final Stage stage;
    private final Thread ownerThread = Thread.currentThread();
    private final long fixedDeltaNanos;
    private final float fixedDeltaSeconds;
    private final CopyOnWriteArrayList<FrameListener> listeners =
            new CopyOnWriteArrayList<>();
    private final Object lifecycle = new Object();
    private volatile long nowNanos;
    private volatile long frame;
    private volatile long revision;
    private boolean open = true;

    /** Creates a fixture clock whose advances must be exact multiples of a positive fixed delta. */
    public ControlledStageClock(Stage stage, Duration fixedDelta) {
        this.stage = Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(fixedDelta, "fixedDelta");
        if (fixedDelta.isZero() || fixedDelta.isNegative()) {
            throw new IllegalArgumentException("fixedDelta must be positive");
        }
        fixedDeltaNanos = fixedDelta.toNanos();
        fixedDeltaSeconds = fixedDeltaNanos / 1_000_000_000.0f;
    }

    /** Advances the Stage in deterministic fixed-delta increments and signals each completed step. */
    public void advance(Duration duration) {
        requireOwnerThread();
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration must not be negative");
        }
        long durationNanos = duration.toNanos();
        if (durationNanos % fixedDeltaNanos != 0) {
            throw new IllegalArgumentException("duration must be an exact multiple of fixedDelta");
        }
        requireOpen();
        long steps = durationNanos / fixedDeltaNanos;
        for (long index = 0; index < steps; index++) {
            stage.act(fixedDeltaSeconds);
            nowNanos = Math.addExact(nowNanos, fixedDeltaNanos);
            frame = Math.incrementExact(frame);
            revision = Math.incrementExact(revision);
            Frame event = new Frame(revision, frame);
            for (FrameListener listener : listeners) {
                listener.onFrame(event);
            }
        }
    }

    /** Returns controlled monotonic nanoseconds since fixture creation. */
    @Override public long nanoTime() {
        return nowNanos;
    }

    /** Returns the number of fixed Stage steps completed. */
    public long frame() {
        return frame;
    }

    /** Returns the semantic revision assigned to the latest fixed step. */
    public long revision() {
        return revision;
    }

    /** Registers a frame listener until its subscription is closed. */
    @Override public Subscription subscribe(FrameListener listener) {
        FrameListener checked = Objects.requireNonNull(listener, "listener");
        synchronized (lifecycle) {
            if (!open) {
                throw sessionClosed();
            }
            listeners.add(checked);
        }
        AtomicBoolean subscribed = new AtomicBoolean(true);
        return () -> {
            if (subscribed.compareAndSet(true, false)) {
                listeners.remove(checked);
            }
        };
    }

    /** Stops advances and removes all listener registrations without disposing the Stage. */
    @Override public void close() {
        FrameListener[] closing;
        synchronized (lifecycle) {
            if (!open) {
                return;
            }
            open = false;
            closing = listeners.toArray(FrameListener[]::new);
            listeners.clear();
        }
        for (FrameListener listener : closing) {
            listener.onClosed();
        }
    }

    private void requireOwnerThread() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException("Stage may only advance on its owning render thread");
        }
    }

    private void requireOpen() {
        synchronized (lifecycle) {
            if (!open) {
                throw sessionClosed();
            }
        }
    }

    private static HarnessException sessionClosed() {
        return new HarnessException(
                ErrorCode.SESSION_CLOSED,
                "Controlled Stage clock is closed",
                ErrorEvidence.empty());
    }
}
