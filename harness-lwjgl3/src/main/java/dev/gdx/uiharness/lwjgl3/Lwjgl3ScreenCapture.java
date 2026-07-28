package dev.gdx.uiharness.lwjgl3;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.utils.BufferUtils;
import dev.gdx.uiharness.core.capture.CaptureRequest;
import dev.gdx.uiharness.core.capture.CapturedImage;
import dev.gdx.uiharness.core.capture.ScreenCapture;
import dev.gdx.uiharness.core.error.ErrorCode;
import dev.gdx.uiharness.core.error.ErrorEvidence;
import dev.gdx.uiharness.core.error.HarnessException;
import dev.gdx.uiharness.core.locator.LocatorEngine;
import dev.gdx.uiharness.core.locator.StrictResolution;
import dev.gdx.uiharness.core.model.Bounds;
import dev.gdx.uiharness.core.model.SemanticNode;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import dev.gdx.uiharness.core.time.Deadline;
import dev.gdx.uiharness.core.wait.FrameSignal;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** LWJGL3 framebuffer readback captured on its graphics thread after render completion. */
public final class Lwjgl3ScreenCapture implements ScreenCapture {
    private final Lwjgl3FrameFence fence;
    private final SnapshotSource snapshots;
    private final LocatorEngine locators;
    private final PngEncoder encoder;
    private final Object lifecycle = new Object();
    private final Map<CompletableFuture<CapturedImage>, CompletableFuture<CapturedImage>>
            pending = new IdentityHashMap<>();
    private boolean open = true;

    /** Creates a capture adapter with the default strict locator engine and PNG encoder. */
    public Lwjgl3ScreenCapture(Lwjgl3FrameFence fence, SnapshotSource snapshots) {
        this(fence, snapshots, new StrictResolution(), new PngEncoder());
    }

    /** Creates a capture adapter with explicit semantic and encoding collaborators. */
    public Lwjgl3ScreenCapture(
            Lwjgl3FrameFence fence,
            SnapshotSource snapshots,
            LocatorEngine locators,
            PngEncoder encoder) {
        this.fence = Objects.requireNonNull(fence, "fence");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.locators = Objects.requireNonNull(locators, "locators");
        this.encoder = Objects.requireNonNull(encoder, "encoder");
    }

    /** Queues readback for the next explicitly completed frame. */
    @Override public CompletionStage<CapturedImage> capture(
            CaptureRequest request, Deadline deadline) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(deadline, "deadline");
        CompletableFuture<CapturedImage> result = new CompletableFuture<>();
        CompletableFuture<CapturedImage> frameWork;
        synchronized (lifecycle) {
            if (!open) {
                return CompletableFuture.failedFuture(closedFailure());
            }
            frameWork = fence.afterNextFrame(
                    frame -> captureCompletedFrame(request, frame), deadline)
                    .toCompletableFuture();
            pending.put(result, frameWork);
        }
        frameWork.whenComplete((image, failure) ->
                completePending(result, frameWork, image, failure));
        result.whenComplete((image, failure) -> {
            if (result.isCancelled()) {
                frameWork.cancel(false);
                removePending(result, frameWork);
            }
        });
        return result;
    }

    /** Immediately fails owned pending work without closing the independently owned frame fence. */
    @Override public void close() {
        List<PendingCapture> claimed;
        synchronized (lifecycle) {
            if (!open) {
                return;
            }
            open = false;
            claimed = new ArrayList<>(pending.size());
            pending.forEach((result, frameWork) ->
                    claimed.add(new PendingCapture(result, frameWork)));
            pending.clear();
        }
        HarnessException failure = closedFailure();
        for (PendingCapture capture : claimed) {
            capture.frameWork().cancel(false);
            capture.result().completeExceptionally(failure);
        }
    }

    private void completePending(
            CompletableFuture<CapturedImage> result,
            CompletableFuture<CapturedImage> frameWork,
            CapturedImage image,
            Throwable failure) {
        if (!removePending(result, frameWork)) {
            return;
        }
        if (failure == null) {
            result.complete(image);
        } else {
            result.completeExceptionally(failure);
        }
    }

    private boolean removePending(
            CompletableFuture<CapturedImage> result,
            CompletableFuture<CapturedImage> frameWork) {
        synchronized (lifecycle) {
            return pending.remove(result, frameWork);
        }
    }

    private CapturedImage captureCompletedFrame(
            CaptureRequest request, FrameSignal.Frame frame) {
        try {
            return readFramebuffer(request, frame);
        } catch (HarnessException failure) {
            throw failure;
        } catch (RuntimeException | LinkageError failure) {
            throw new HarnessException(
                    ErrorCode.CAPTURE_FAILURE,
                    "LWJGL3 framebuffer capture failed",
                    ErrorEvidence.ofDetails(Map.of(
                            "frame", Long.toString(frame.frame()),
                            "revision", Long.toString(frame.revision()))),
                    failure);
        }
    }

    private CapturedImage readFramebuffer(
            CaptureRequest request, FrameSignal.Frame frame) {
        requireGraphicsContext();
        int framebufferWidth = Gdx.graphics.getBackBufferWidth();
        int framebufferHeight = Gdx.graphics.getBackBufferHeight();
        int windowWidth = Gdx.graphics.getWidth();
        int windowHeight = Gdx.graphics.getHeight();
        if (framebufferWidth <= 0 || framebufferHeight <= 0
                || windowWidth <= 0 || windowHeight <= 0) {
            throw geometryFailure("empty", null, framebufferWidth, framebufferHeight);
        }
        CapturedImage.Scale scale = new CapturedImage.Scale(
                framebufferWidth / (double) windowWidth,
                framebufferHeight / (double) windowHeight);
        PixelRegion region = request.actorLocator()
                .map(locator -> actorRegion(
                        locator,
                        frame,
                        framebufferWidth,
                        framebufferHeight,
                        scale))
                .orElseGet(() -> new PixelRegion(
                        0, 0, framebufferWidth, framebufferHeight));
        validateAllocation(region.width(), region.height(), request.limits());

        int rgbaBytes = Math.toIntExact(Math.multiplyExact(region.pixels(), 4L));
        ByteBuffer rgba = BufferUtils.newByteBuffer(rgbaBytes);
        readPixels(region, rgba, framebufferHeight);
        PngEncoder.Encoded encoded = encoder.encode(
                rgba, region.width(), region.height(), request.limits().maxPngBytes());
        return new CapturedImage(
                encoded.bytes(),
                encoded.sha256(),
                frame.frame(),
                frame.revision(),
                region.width(),
                region.height(),
                scale);
    }

    private PixelRegion actorRegion(
            dev.gdx.uiharness.core.locator.Locator locator,
            FrameSignal.Frame frame,
            int framebufferWidth,
            int framebufferHeight,
            CapturedImage.Scale scale) {
        SemanticSnapshot snapshot = snapshots.snapshot(frame.revision(), frame.frame());
        SemanticNode actor = locators.resolveStrict(snapshot, locator);
        Bounds bounds = actor.screenBounds();
        if (bounds.width() == 0.0 || bounds.height() == 0.0) {
            throw geometryFailure("empty", bounds, framebufferWidth, framebufferHeight);
        }

        double left = bounds.x() * scale.x();
        double top = bounds.y() * scale.y();
        double right = (bounds.x() + bounds.width()) * scale.x();
        double bottom = (bounds.y() + bounds.height()) * scale.y();
        if (!allFinite(left, top, right, bottom) || right <= left || bottom <= top) {
            throw geometryFailure("impossible", bounds, framebufferWidth, framebufferHeight);
        }
        if (right <= 0.0 || bottom <= 0.0
                || left >= framebufferWidth || top >= framebufferHeight) {
            throw geometryFailure("offscreen", bounds, framebufferWidth, framebufferHeight);
        }

        int clippedLeft = (int) Math.floor(Math.max(0.0, left));
        int clippedTop = (int) Math.floor(Math.max(0.0, top));
        int clippedRight = (int) Math.ceil(Math.min(framebufferWidth, right));
        int clippedBottom = (int) Math.ceil(Math.min(framebufferHeight, bottom));
        int width = clippedRight - clippedLeft;
        int height = clippedBottom - clippedTop;
        if (width <= 0 || height <= 0) {
            throw geometryFailure("empty", bounds, framebufferWidth, framebufferHeight);
        }
        return new PixelRegion(clippedLeft, clippedTop, width, height);
    }

    private static void validateAllocation(
            int width, int height, CaptureRequest.Limits limits) {
        if (width > limits.maxWidth()) {
            throw limitExceeded("width", width, limits.maxWidth());
        }
        if (height > limits.maxHeight()) {
            throw limitExceeded("height", height, limits.maxHeight());
        }
        long pixels = Math.multiplyExact((long) width, height);
        if (pixels > limits.maxPixels()) {
            throw limitExceeded("pixels", pixels, limits.maxPixels());
        }
        long rgbaBytes = Math.multiplyExact(pixels, 4L);
        if (rgbaBytes > Integer.MAX_VALUE) {
            throw limitExceeded("rgbaBytes", rgbaBytes, Integer.MAX_VALUE);
        }
    }

    private static void readPixels(
            PixelRegion region, ByteBuffer rgba, int framebufferHeight) {
        IntBuffer previousPackAlignment = BufferUtils.newIntBuffer(1);
        Gdx.gl.glGetIntegerv(GL20.GL_PACK_ALIGNMENT, previousPackAlignment);
        int glY = framebufferHeight - region.y() - region.height();
        try {
            Gdx.gl.glPixelStorei(GL20.GL_PACK_ALIGNMENT, 1);
            Gdx.gl.glReadPixels(
                    region.x(),
                    glY,
                    region.width(),
                    region.height(),
                    GL20.GL_RGBA,
                    GL20.GL_UNSIGNED_BYTE,
                    rgba);
            int error = Gdx.gl.glGetError();
            if (error != GL20.GL_NO_ERROR) {
                throw new HarnessException(
                        ErrorCode.CAPTURE_FAILURE,
                        "OpenGL framebuffer readback failed",
                        ErrorEvidence.ofDetails(Map.of(
                                "glError", Integer.toString(error))));
            }
        } finally {
            Gdx.gl.glPixelStorei(GL20.GL_PACK_ALIGNMENT, previousPackAlignment.get(0));
        }
        rgba.position(0);
        rgba.limit(Math.multiplyExact(Math.multiplyExact(
                region.width(), region.height()), 4));
    }

    private static void requireGraphicsContext() {
        if (Gdx.graphics == null || Gdx.gl == null) {
            throw new HarnessException(
                    ErrorCode.CAPTURE_FAILURE,
                    "no active LWJGL3 graphics context is bound",
                    ErrorEvidence.empty());
        }
    }

    private static boolean allFinite(double... values) {
        for (double value : values) {
            if (!Double.isFinite(value)) {
                return false;
            }
        }
        return true;
    }

    private static HarnessException geometryFailure(
            String geometry, Bounds bounds, int framebufferWidth, int framebufferHeight) {
        Map<String, String> details;
        if (bounds == null) {
            details = Map.of(
                    "framebufferHeight", Integer.toString(framebufferHeight),
                    "framebufferWidth", Integer.toString(framebufferWidth),
                    "geometry", geometry);
        } else {
            details = Map.of(
                    "bounds", bounds.toString(),
                    "framebufferHeight", Integer.toString(framebufferHeight),
                    "framebufferWidth", Integer.toString(framebufferWidth),
                    "geometry", geometry);
        }
        return new HarnessException(
                ErrorCode.CAPTURE_FAILURE,
                "capture geometry is " + geometry,
                ErrorEvidence.ofDetails(details));
    }

    private static HarnessException limitExceeded(
            String dimension, long actual, long limit) {
        return new HarnessException(
                ErrorCode.LIMIT_EXCEEDED,
                dimension + " exceeds its configured capture limit",
                ErrorEvidence.ofDetails(Map.of(
                        "actual", Long.toString(actual),
                        "dimension", dimension,
                        "limit", Long.toString(limit))));
    }

    private static HarnessException closedFailure() {
        return new HarnessException(
                ErrorCode.SESSION_CLOSED,
                "screen capture is closed",
                ErrorEvidence.empty());
    }

    /** Supplies a fresh immutable semantic snapshot for one completed frame. */
    @FunctionalInterface
    public interface SnapshotSource {
        /** Captures semantic state on the graphics thread at the supplied revision and frame. */
        SemanticSnapshot snapshot(long revision, long frame);
    }

    private record PendingCapture(
            CompletableFuture<CapturedImage> result,
            CompletableFuture<CapturedImage> frameWork) {}

    private record PixelRegion(int x, int y, int width, int height) {
        long pixels() {
            return Math.multiplyExact((long) width, height);
        }
    }
}
