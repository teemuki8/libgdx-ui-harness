package dev.gdx.uiharness.core.capture;

import dev.gdx.uiharness.core.time.Deadline;
import java.util.concurrent.CompletionStage;

/** Backend-neutral asynchronous capture of a completed rendered frame. */
public interface ScreenCapture extends AutoCloseable {
    /** Captures the requested evidence after a completed frame within the supplied deadline. */
    CompletionStage<CapturedImage> capture(CaptureRequest request, Deadline deadline);

    /** Stops accepting captures and releases queued backend work. */
    @Override void close();
}
