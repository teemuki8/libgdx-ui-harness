package dev.gdx.uiharness.core.wait;

/** Event source for completed semantic revisions and frames. */
public interface FrameSignal {
    /** Registers a listener until its returned subscription is closed. */
    Subscription subscribe(FrameListener listener);

    /** Immutable identity of one completed semantic frame. */
    record Frame(long revision, long frame) {
        /** Validates non-negative semantic counters. */
        public Frame {
            if (revision < 0) {
                throw new IllegalArgumentException("revision must be non-negative");
            }
            if (frame < 0) {
                throw new IllegalArgumentException("frame must be non-negative");
            }
        }
    }

    /** Receives completed semantic frame events. Implementations must return promptly. */
    @FunctionalInterface
    interface FrameListener {
        /** Publishes one completed semantic frame. */
        void onFrame(Frame frame);

        /** Notifies the listener that this signal source cannot publish another frame. */
        default void onClosed() {}
    }

    /** Explicit lifetime for one frame listener registration. */
    @FunctionalInterface
    interface Subscription extends AutoCloseable {
        /** Removes the listener. This operation is idempotent. */
        @Override void close();
    }
}
