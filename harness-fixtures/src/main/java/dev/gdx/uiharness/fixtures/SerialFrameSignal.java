package dev.gdx.uiharness.fixtures;

import dev.gdx.uiharness.core.wait.FrameSignal;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Serial off-thread delivery for frame notifications emitted by the render thread. */
final class SerialFrameSignal implements FrameSignal, AutoCloseable {
    private final FrameSignal source;
    private final BooleanSupplier enabled;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("reference-assertion-frame").factory());
    private final Set<Registration> registrations = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    SerialFrameSignal(FrameSignal source, BooleanSupplier enabled) {
        this.source = Objects.requireNonNull(source, "source");
        this.enabled = Objects.requireNonNull(enabled, "enabled");
    }

    @Override public synchronized Subscription subscribe(FrameListener listener) {
        Objects.requireNonNull(listener, "listener");
        if (closed.get()) {
            throw new IllegalStateException("frame signal is closed");
        }
        Registration registration = new Registration(listener);
        registrations.add(registration);
        try {
            registration.sourceSubscription = source.subscribe(registration);
        } catch (RuntimeException failure) {
            registrations.remove(registration);
            registration.active.set(false);
            throw failure;
        }
        return registration;
    }

    @Override public synchronized void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (Registration registration : registrations) {
            registration.closeSource();
            registration.deliverClosed();
        }
        executor.close();
        registrations.clear();
    }

    private final class Registration implements FrameListener, Subscription {
        private final FrameListener listener;
        private final AtomicBoolean active = new AtomicBoolean(true);
        private volatile Subscription sourceSubscription;

        Registration(FrameListener listener) {
            this.listener = listener;
        }

        @Override public void onFrame(Frame frame) {
            if (active.get() && enabled.getAsBoolean()) {
                submit(() -> {
                    if (active.get()) {
                        listener.onFrame(frame);
                    }
                });
            }
        }

        @Override public void onClosed() {
            closeSource();
            deliverClosed();
        }

        @Override public void close() {
            if (!active.compareAndSet(true, false)) {
                return;
            }
            closeSource();
            registrations.remove(this);
        }

        void closeSource() {
            Subscription subscription = sourceSubscription;
            if (subscription != null) {
                subscription.close();
            }
        }

        void deliverClosed() {
            if (!active.compareAndSet(true, false)) {
                return;
            }
            registrations.remove(this);
            submit(listener::onClosed);
        }

        void submit(Runnable task) {
            try {
                executor.execute(task);
            } catch (RejectedExecutionException ignored) {
                // Closing owns rejection: no callback may escape after executor termination.
            }
        }
    }
}
