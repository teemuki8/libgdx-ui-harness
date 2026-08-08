package dev.gdx.uiharness.lwjgl3;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.utils.viewport.FitViewport;
import dev.gdx.uiharness.core.capture.CaptureRequest;
import dev.gdx.uiharness.core.capture.CapturedImage;
import dev.gdx.uiharness.core.locator.Locator;
import dev.gdx.uiharness.core.time.Deadline;
import dev.gdx.uiharness.core.time.DeadlineScheduler;
import dev.gdx.uiharness.core.time.MonotonicClock;
import dev.gdx.uiharness.core.wait.FrameSignal;
import dev.gdx.uiharness.scene2d.Scene2dSession;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;

final class Lwjgl3CaptureFixture implements AutoCloseable {
    static final int WINDOW_SIZE = 64;
    static final int RED = 0xFFFF0000;
    static final int GREEN = 0xFF00FF00;
    static final int BLUE = 0xFF0000FF;
    static final int YELLOW = 0xFFFFFF00;
    static final Duration TIMEOUT = Duration.ofSeconds(5);
    static final MonotonicClock CLOCK = System::nanoTime;

    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private final AtomicReference<Throwable> applicationFailure = new AtomicReference<>();
    private final ScheduledExecutorService deadlineExecutor =
            Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform()
                    .name("lwjgl3-capture-fixture-deadlines").daemon().factory());
    private final DeadlineScheduler deadlineScheduler = (delay, signal) -> {
        var scheduled = deadlineExecutor.schedule(signal, delay.toNanos(), TimeUnit.NANOSECONDS);
        return () -> scheduled.cancel(false);
    };
    private final FixtureApplication application = new FixtureApplication();
    private final Thread applicationThread;

    Lwjgl3CaptureFixture() {
        applicationThread = new Thread(this::runApplication, "lwjgl3-capture-fixture");
        applicationThread.start();
        await(ready);
    }

    Lwjgl3ScreenCapture capture() {
        return application.capture;
    }

    Lwjgl3FrameFence fence() {
        return application.fence;
    }

    long latestFrame() {
        return application.frame;
    }

    Thread applicationThread() {
        return applicationThread;
    }

    Deadline deadline() {
        return Deadline.after(CLOCK, TIMEOUT);
    }

    CapturedImage captureFullWindow() {
        return await(capture().capture(CaptureRequest.fullWindow(), deadline()));
    }

    CapturedImage captureActor(String testId) {
        return await(capture().capture(CaptureRequest.actor(Locator.testId(testId)), deadline()));
    }

    CompletionStage<FrameSignal.Frame> setTopLeftColorAfterAction(Color color) {
        Objects.requireNonNull(color, "color");
        CompletableFuture<Void> applied = new CompletableFuture<>();
        CompletableFuture<FrameSignal.Frame> completedFrame = new CompletableFuture<>();
        long previousFrame = latestFrame();
        FrameSignal.Subscription subscription = fence().subscribe(frame -> {
            if (applied.isDone() && frame.frame() > previousFrame) {
                completedFrame.complete(frame);
            }
        });
        completedFrame.whenComplete((ignored, failure) -> subscription.close());
        Gdx.app.postRunnable(() -> {
            application.topLeft.setActorColor(color);
            applied.complete(null);
        });
        return completedFrame;
    }

    void configureQuadrants(float worldSize) {
        awaitRenderedMutation(() -> application.configureQuadrants(worldSize));
    }

    void setTopLeftBounds(float x, float y, float width, float height) {
        awaitRenderedMutation(() -> application.topLeft.setBounds(x, y, width, height));
    }

    private void awaitRenderedMutation(Runnable mutation) {
        CompletableFuture<Void> applied = new CompletableFuture<>();
        CompletableFuture<FrameSignal.Frame> rendered = new CompletableFuture<>();
        long previousFrame = latestFrame();
        FrameSignal.Subscription subscription = fence().subscribe(frame -> {
            if (applied.isDone() && frame.frame() > previousFrame) {
                rendered.complete(frame);
            }
        });
        Gdx.app.postRunnable(() -> {
            mutation.run();
            applied.complete(null);
        });
        try {
            await(rendered);
        } finally {
            subscription.close();
        }
    }

    static BufferedImage decode(CapturedImage image) {
        try {
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(image.pngBytes()));
            if (decoded == null) {
                throw new AssertionError("capture was not a PNG image");
            }
            return decoded;
        } catch (IOException exception) {
            throw new AssertionError("capture could not be decoded", exception);
        }
    }

    static <T> T await(CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new AssertionError("asynchronous operation failed", cause);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while awaiting fixture", exception);
        } catch (TimeoutException exception) {
            throw new AssertionError("timed out awaiting fixture", exception);
        }
    }

    @Override public void close() {
        if (application.fence != null) {
            Gdx.app.postRunnable(() -> {
                application.fence.close();
                Gdx.app.exit();
            });
        }
        try {
            applicationThread.join(TIMEOUT.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while closing fixture", exception);
        }
        if (applicationThread.isAlive()) {
            throw new AssertionError("LWJGL3 application thread did not exit");
        }
        deadlineExecutor.shutdownNow();
        Throwable failure = applicationFailure.get();
        if (failure != null) {
            throw new AssertionError("LWJGL3 application failed", failure);
        }
    }

    private void runApplication() {
        try {
            Lwjgl3ApplicationConfiguration configuration =
                    new Lwjgl3ApplicationConfiguration();
            configuration.setTitle("ui-harness-capture-test");
            configuration.setWindowedMode(WINDOW_SIZE, WINDOW_SIZE);
            configuration.setInitialVisible(false);
            configuration.disableAudio(true);
            configuration.useVsync(false);
            configuration.setForegroundFPS(120);
            new Lwjgl3Application(application, configuration);
        } catch (Throwable failure) {
            applicationFailure.set(failure);
            ready.completeExceptionally(failure);
        }
    }

    private final class FixtureApplication extends ApplicationAdapter {
        private Stage stage;
        private Texture white;
        private Scene2dSession semantics;
        private Lwjgl3FrameFence fence;
        private Lwjgl3ScreenCapture capture;
        private ColorActor topLeft;
        private long revision;
        private volatile long frame;

        @Override public void create() {
            Pixmap pixel = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
            pixel.setColor(Color.WHITE);
            pixel.fill();
            white = new Texture(pixel);
            pixel.dispose();
            stage = new Stage(new FitViewport(WINDOW_SIZE, WINDOW_SIZE));
            semantics = new Scene2dSession(stage);
            fence = new Lwjgl3FrameFence(deadlineScheduler);
            capture = new Lwjgl3ScreenCapture(fence, semantics::snapshot);
            configureQuadrants(WINDOW_SIZE);
        }

        void configureQuadrants(float worldSize) {
            stage.getViewport().setWorldSize(worldSize, worldSize);
            stage.getViewport().update(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), true);
            stage.getRoot().clearChildren();
            float half = worldSize / 2.0f;
            topLeft = actor("top-left", Color.RED, 0, half, half, half);
            actor("top-right", Color.GREEN, half, half, half, half);
            actor("bottom-left", Color.BLUE, 0, 0, half, half);
            actor("bottom-right", Color.YELLOW, half, 0, half, half);
        }

        private ColorActor actor(
                String testId, Color color, float x, float y, float width, float height) {
            ColorActor actor = new ColorActor(white, color);
            actor.setBounds(x, y, width, height);
            semantics.semantics().setTestId(actor, testId);
            stage.addActor(actor);
            return actor;
        }

        @Override public void render() {
            Gdx.gl.glClearColor(0, 0, 0, 1);
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
            stage.act(1.0f / 120.0f);
            stage.draw();
            revision++;
            frame++;
            fence.completedFrame(revision, frame);
            ready.complete(null);
        }

        @Override public void resize(int width, int height) {
            if (stage != null) {
                stage.getViewport().update(width, height, true);
            }
        }

        @Override public void dispose() {
            if (capture != null) {
                capture.close();
            }
            if (semantics != null) {
                semantics.close();
            }
            if (stage != null) {
                stage.dispose();
            }
            if (white != null) {
                white.dispose();
            }
        }
    }

    private static final class ColorActor extends Actor {
        private final Texture texture;
        private final Color actorColor = new Color();

        ColorActor(Texture texture, Color color) {
            this.texture = Objects.requireNonNull(texture, "texture");
            setActorColor(color);
        }

        void setActorColor(Color color) {
            actorColor.set(Objects.requireNonNull(color, "color"));
        }

        @Override public void draw(Batch batch, float parentAlpha) {
            batch.setColor(actorColor.r, actorColor.g, actorColor.b,
                    actorColor.a * parentAlpha);
            batch.draw(texture, getX(), getY(), getWidth(), getHeight());
            batch.setColor(Color.WHITE);
        }
    }
}
