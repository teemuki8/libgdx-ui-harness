package dev.gdx.uiharness.scene2d;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.Input.Buttons;
import com.badlogic.gdx.Input.Keys;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.actions.Actions;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane.ScrollPaneStyle;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.Slider.SliderStyle;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton.TextButtonStyle;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.BaseDrawable;
import dev.gdx.uiharness.core.action.Harness;
import dev.gdx.uiharness.core.capture.CaptureRequest;
import dev.gdx.uiharness.core.capture.CapturedImage;
import dev.gdx.uiharness.core.capture.ScreenCapture;
import dev.gdx.uiharness.core.action.Action;
import dev.gdx.uiharness.core.action.ActionResult;
import dev.gdx.uiharness.core.error.ErrorCode;
import dev.gdx.uiharness.core.error.HarnessException;
import dev.gdx.uiharness.core.locator.Locator;
import dev.gdx.uiharness.core.locator.StrictResolution;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import dev.gdx.uiharness.core.wait.WaitEngine;
import dev.gdx.uiharness.mcp.ArtifactReference;
import dev.gdx.uiharness.mcp.HarnessToolHandler;
import dev.gdx.uiharness.protocol.CapabilitySet;
import dev.gdx.uiharness.protocol.HarnessProtocolService;
import io.modelcontextprotocol.spec.McpSchema;
import dev.gdx.uiharness.core.time.Deadline;
import dev.gdx.uiharness.core.time.DeadlineScheduler;
import dev.gdx.uiharness.core.time.MonotonicClock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class Scene2dActionEndToEndTest {
    @Test void clickWaitsForStableUnobscuredButtonAndCompletedPostActionFrame() {
        try (Fixture fixture = new Fixture()) {
            TextButton save = fixture.button("save", "Save", 100, 100);
            Actor cover = new Actor();
            cover.setBounds(90, 90, 180, 80);
            fixture.stage.addActor(cover);

            CompletionStage<ActionResult> click = fixture.harness.click(
                    Locator.testId("save"), fixture.deadline());
            fixture.nextFrame();
            cover.remove();
            fixture.nextFrame();
            assertFalse(click.toCompletableFuture().isDone());
            fixture.nextFrame();

            ActionResult result = click.toCompletableFuture().join();
            assertTrue(save.isChecked());
            assertTrue(result.afterRevision() > result.beforeRevision());
            assertEquals("true", result.observedState());
        }
    }

    @Test void detachedActorIsReplacedAndFreshlyResolvedBeforeDispatch() {
        try (Fixture fixture = new Fixture()) {
            TextButton original = fixture.button("replaceable", "Old", 100, 100);
            CompletionStage<ActionResult> click = fixture.harness.click(
                    Locator.testId("replaceable"), fixture.deadline());
            fixture.nextFrame();

            original.remove();
            TextButton replacement = fixture.button("replaceable", "New", 100, 100);
            fixture.nextFrame();
            assertFalse(click.toCompletableFuture().isDone());
            fixture.nextFrame();
            assertFalse(click.toCompletableFuture().isDone());
            fixture.nextFrame();

            click.toCompletableFuture().join();
            assertFalse(original.isChecked());
            assertTrue(replacement.isChecked());
        }
    }

    @Test void cancellationThatWinsDuringResolutionPreventsInputDispatch() {
        try (Fixture fixture = new Fixture()) {
            BlockingTextButton button =
                    new BlockingTextButton("Cancel", WidgetStyles.textButton());
            button.setBounds(100, 100, 180, 50);
            fixture.tag(button, "cancel-race");
            fixture.stage.addActor(button);
            CompletionStage<ActionResult> click = fixture.harness.click(
                    Locator.testId("cancel-race"), fixture.deadline());
            fixture.nextFrame();
            button.arm();

            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                CompletableFuture<Boolean> cancelled = CompletableFuture.supplyAsync(() -> {
                    button.awaitInspection();
                    try {
                        return click.toCompletableFuture().cancel(false);
                    } finally {
                        button.releaseInspection();
                    }
                }, executor);
                fixture.nextFrame();

                assertTrue(cancelled.join());
                assertTrue(click.toCompletableFuture().isCancelled());
                assertFalse(button.isChecked());
            }
        }
    }

    @Test void mcpCancellationOfQueuedActionPreventsLaterInputDispatch() throws Exception {
        try (Fixture fixture = new Fixture()) {
            TextButton button = fixture.button("mcp-cancel", "Cancel", 100, 100);
            CountDownLatch routed = new CountDownLatch(1);
            AtomicReference<CompletionStage<ActionResult>> actionStage = new AtomicReference<>();
            Harness routedHarness = new Harness() {
                @Override public CompletionStage<ActionResult> perform(
                        Locator locator, Action action, Deadline deadline) {
                    CompletionStage<ActionResult> stage =
                            fixture.harness.perform(locator, action, deadline);
                    actionStage.set(stage);
                    routed.countDown();
                    return stage;
                }

                @Override public CompletionStage<SemanticSnapshot> snapshot(Deadline deadline) {
                    return fixture.harness.snapshot(deadline);
                }
            };
            StrictResolution locators = new StrictResolution();
            WaitEngine waits = new WaitEngine(
                    () -> fixture.session.snapshot(
                            fixture.clock.revision(), fixture.clock.frame()),
                    locators, fixture.clock, fixture.clock);
            ScreenCapture capture = new ScreenCapture() {
                @Override public CompletionStage<CapturedImage> capture(
                        CaptureRequest request, Deadline deadline) {
                    return CompletableFuture.failedFuture(
                            new AssertionError("capture was not expected"));
                }

                @Override public void close() {}
            };
            HarnessProtocolService protocol = new HarnessProtocolService(Map.of("game",
                    new HarnessProtocolService.Session(routedHarness, locators, waits, capture,
                            new CapabilitySet(List.of("action")),
                            HarnessProtocolService.TraceController.unsupported())),
                    fixture.clock, Runnable::run);
            AtomicInteger responses = new AtomicInteger();
            AtomicInteger errors = new AtomicInteger();
            try (HarnessToolHandler handler = new HarnessToolHandler(
                    protocol, ArtifactReference.Publisher.unavailable())) {
                var subscription = handler.handle(McpSchema.CallToolRequest.builder("ui_action")
                                .arguments(Map.of(
                                        "sessionId", "game",
                                        "locator", Map.of(
                                                "kind", "test-id", "testId", "mcp-cancel"),
                                        "action", Map.of(
                                                "kind", "click", "pointer", 0,
                                                "button", 0, "force", false)))
                                .build())
                        .subscribe(ignored -> responses.incrementAndGet(),
                                ignored -> errors.incrementAndGet());
                assertTrue(routed.await(2, TimeUnit.SECONDS));

                subscription.dispose();
                for (int attempt = 0; attempt < 100
                        && !actionStage.get().toCompletableFuture().isCancelled(); attempt++) {
                    Thread.sleep(5);
                }
                fixture.nextFrame();
                fixture.nextFrame();
                fixture.nextFrame();

                assertTrue(actionStage.get().toCompletableFuture().isCancelled());
                assertFalse(button.isChecked());
                assertEquals(0, responses.get());
                assertEquals(0, errors.get());
            } finally {
                waits.close();
            }
        }
    }

    @Test void closeThatWinsDuringResolutionPreventsInputDispatch() {
        try (Fixture fixture = new Fixture()) {
            BlockingTextButton button =
                    new BlockingTextButton("Close", WidgetStyles.textButton());
            button.setBounds(100, 100, 180, 50);
            fixture.tag(button, "close-race");
            fixture.stage.addActor(button);
            CompletionStage<ActionResult> click = fixture.harness.click(
                    Locator.testId("close-race"), fixture.deadline());
            fixture.nextFrame();
            button.arm();

            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                CompletableFuture<Void> closed = CompletableFuture.runAsync(() -> {
                    button.awaitInspection();
                    try {
                        fixture.harness.close();
                    } finally {
                        button.releaseInspection();
                    }
                }, executor);
                fixture.nextFrame();
                closed.join();

                assertEquals(ErrorCode.SESSION_CLOSED, failure(click).code());
                assertFalse(button.isChecked());
            }
        }
    }

    @Test void dispatchThatWinsCannotBeCancelledOrClosedRetroactively() {
        try (Fixture fixture = new Fixture()) {
            TextButton button = fixture.button("dispatch-wins", "Dispatch", 100, 100);
            CompletionStage<ActionResult> click = fixture.harness.click(
                    Locator.testId("dispatch-wins"), fixture.deadline());
            fixture.nextFrame();
            fixture.nextFrame();

            assertTrue(button.isChecked());
            assertFalse(click.toCompletableFuture().isDone());
            assertFalse(click.toCompletableFuture().cancel(false));
            fixture.harness.close();
            assertFalse(click.toCompletableFuture().isDone());
            fixture.nextFrame();

            ActionResult result = click.toCompletableFuture().join();
            assertTrue(result.afterRevision() > result.beforeRevision());
        }
    }

    @Test void sessionActorTokensAreStableUniqueAndMonotonic() {
        try (Fixture fixture = new Fixture()) {
            Actor first = new Actor();
            Actor second = new Actor();

            long firstToken = fixture.session.actorToken(first);
            long secondToken = fixture.session.actorToken(second);

            assertEquals(firstToken, fixture.session.actorToken(first));
            assertNotEquals(firstToken, secondToken);
            assertTrue(secondToken > firstToken);
        }
    }

    @Test void ancestryVisibilityEnabledAndTouchableTransitionsAreRequired() {
        try (Fixture fixture = new Fixture()) {
            Group parent = new Group();
            parent.setBounds(0, 0, 500, 400);
            TextButton button = fixture.unattachedButton("gated", "Gated", 100, 100);
            parent.addActor(button);
            fixture.stage.addActor(parent);
            parent.setVisible(false);
            parent.setTouchable(Touchable.disabled);
            button.setDisabled(true);

            CompletionStage<ActionResult> click = fixture.harness.click(
                    Locator.testId("gated"), fixture.deadline());
            fixture.nextFrame();
            parent.setVisible(true);
            fixture.nextFrame();
            button.setDisabled(false);
            fixture.nextFrame();
            assertFalse(click.toCompletableFuture().isDone());
            parent.setTouchable(Touchable.enabled);
            fixture.nextFrame();
            assertFalse(click.toCompletableFuture().isDone());
            fixture.nextFrame();

            click.toCompletableFuture().join();
            assertTrue(button.isChecked());
        }
    }

    @Test void movingBoundsNeedTwoConsecutiveStableFrames() {
        try (Fixture fixture = new Fixture()) {
            TextButton button = fixture.button("moving", "Moving", 50, 100);
            button.addAction(Actions.moveBy(96, 0, fixture.step.multipliedBy(3).toMillis() / 1000f));

            CompletionStage<ActionResult> click = fixture.harness.click(
                    Locator.testId("moving"), fixture.deadline());
            fixture.nextFrame();
            fixture.nextFrame();
            fixture.nextFrame();
            fixture.nextFrame();
            assertFalse(click.toCompletableFuture().isDone());
            fixture.nextFrame();

            click.toCompletableFuture().join();
            assertTrue(button.isChecked());
        }
    }

    @Test void clippedAndOffscreenActorsWaitUntilAVisibleHitPointExists() {
        try (Fixture fixture = new Fixture()) {
            Group content = new Group();
            content.setSize(400, 160);
            TextButton clipped = fixture.unattachedButton("clipped", "Clipped", 260, 40);
            content.addActor(clipped);
            ScrollPane pane = new ScrollPane(content, new ScrollPaneStyle());
            pane.setBounds(20, 20, 140, 160);
            fixture.stage.addActor(pane);
            pane.validate();
            TextButton offscreen = fixture.button("offscreen", "Offscreen", 900, 250);

            CompletionStage<ActionResult> clippedClick = fixture.harness.click(
                    Locator.testId("clipped"), fixture.deadline());
            CompletionStage<ActionResult> offscreenClick = fixture.harness.click(
                    Locator.testId("offscreen"), fixture.deadline());
            fixture.nextFrame();
            assertFalse(clippedClick.toCompletableFuture().isDone());
            assertFalse(offscreenClick.toCompletableFuture().isDone());
            clipped.setX(20);
            offscreen.setX(300);
            fixture.nextFrame();
            assertFalse(clippedClick.toCompletableFuture().isDone());
            assertFalse(offscreenClick.toCompletableFuture().isDone());
            fixture.nextFrame();
            fixture.nextFrame();

            clippedClick.toCompletableFuture().join();
            offscreenClick.toCompletableFuture().join();
            assertTrue(clipped.isChecked());
            assertTrue(offscreen.isChecked());
        }
    }

    @Test void overlapBlocksClickButDescendantHitTargetIsAccepted() {
        try (Fixture fixture = new Fixture()) {
            TextButton button = fixture.button("layered", "Layered", 100, 100);
            button.getLabel().setTouchable(Touchable.enabled);
            Actor overlap = new Actor();
            overlap.setBounds(100, 100, 180, 50);
            fixture.stage.addActor(overlap);

            CompletionStage<ActionResult> click = fixture.harness.click(
                    Locator.testId("layered"), fixture.deadline());
            fixture.nextFrame();
            overlap.remove();
            fixture.nextFrame();
            fixture.nextFrame();

            click.toCompletableFuture().join();
            assertTrue(button.isChecked(), "a touchable descendant may be the Stage hit target");
        }
    }

    @Test void focusFillPressAndHoverProduceWidgetState() {
        try (Fixture fixture = new Fixture()) {
            TextField field = new TextField("old", WidgetStyles.textField());
            field.setOnlyFontChars(false);
            field.setBounds(100, 100, 240, 50);
            fixture.tag(field, "field");
            fixture.stage.addActor(field);
            TextButton hover = fixture.button("hover", "Hover", 400, 100);

            fixture.complete(fixture.harness.focus(Locator.testId("field"), fixture.deadline()));
            assertSame(field, fixture.stage.getKeyboardFocus());
            fixture.complete(fixture.harness.fill(
                    Locator.testId("field"), "abc", fixture.deadline()));
            assertEquals("abc", field.getText());
            fixture.complete(fixture.harness.press(
                    Locator.testId("field"), Keys.BACKSPACE, fixture.deadline()));
            assertEquals("ab", field.getText());
            fixture.complete(fixture.harness.hover(
                    Locator.testId("hover"), fixture.deadline()));
            assertTrue(hover.isOver());
        }
    }

    @Test void scrollDragAndPointerUseRealStageStateAndPointerCapture() {
        try (Fixture fixture = new Fixture()) {
            Group content = new Group();
            content.setSize(180, 900);
            ScrollPane pane = new ScrollPane(content, new ScrollPaneStyle());
            pane.setBounds(20, 20, 180, 180);
            fixture.tag(pane, "pane");
            fixture.stage.addActor(pane);
            pane.validate();
            Slider slider = new Slider(0, 100, 1, false, sliderStyle());
            slider.setBounds(250, 100, 300, 40);
            slider.setValue(10);
            fixture.tag(slider, "slider");
            fixture.stage.addActor(slider);
            TextButton button = fixture.button("pointer", "Pointer", 300, 250);

            fixture.complete(fixture.harness.scroll(
                    Locator.testId("pane"), 0, 4, fixture.deadline()));
            assertSame(pane, fixture.stage.getScrollFocus());
            assertTrue(pane.getScrollY() > 0);
            fixture.complete(fixture.harness.drag(
                    Locator.testId("slider"), 150, 0, fixture.deadline()));
            assertTrue(slider.getValue() > 10);
            fixture.complete(fixture.harness.pointer(
                    Locator.testId("pointer"), Action.PointerPhase.DOWN,
                    0, 0, 2, Buttons.LEFT, fixture.deadline()));
            assertTrue(button.isPressed());
            fixture.complete(fixture.harness.pointer(
                    Locator.testId("pointer"), Action.PointerPhase.UP,
                    0, 0, 2, Buttons.LEFT, fixture.deadline()));
            assertTrue(button.isChecked());
        }
    }

    @Test void forceBypassesVisibilityStabilityAndHitButNeverStrictness() {
        try (Fixture fixture = new Fixture()) {
            TextButton hidden = fixture.button("forced", "Forced", 100, 100);
            hidden.setVisible(false);
            Actor cover = new Actor();
            cover.setBounds(90, 90, 200, 80);
            fixture.stage.addActor(cover);

            CompletionStage<ActionResult> forced = fixture.harness.perform(
                    Locator.testId("forced"), Action.click(true), fixture.deadline());
            fixture.nextFrame();
            assertFalse(forced.toCompletableFuture().isDone());
            fixture.nextFrame();
            forced.toCompletableFuture().join();
            assertFalse(hidden.isChecked(), "faithful forced input still follows Stage hit testing");

            TextButton duplicate = fixture.button("forced", "Duplicate", 350, 100);
            duplicate.setVisible(false);
            CompletionStage<ActionResult> strict = fixture.harness.perform(
                    Locator.testId("forced"), Action.click(true), fixture.deadline());
            fixture.drain();
            HarnessException error = failure(strict);
            assertEquals(ErrorCode.STRICTNESS_VIOLATION, error.code());
        }
    }

    @Test void strictFailureAttachesSuggestionsAndDispatchesNoInput() {
        AtomicInteger dispatches = new AtomicInteger();
        Stage stage = Scene2dTestSupport.stage();
        try (Fixture fixture = new Fixture(stage, new CountingInput(stage, dispatches))) {
            fixture.button("duplicated", "First", 100, 100);
            fixture.button("duplicated", "Second", 350, 100);

            CompletionStage<ActionResult> strict = fixture.harness.perform(
                    Locator.testId("duplicated"), Action.click(false), fixture.deadline());
            fixture.drain();
            HarnessException error = failure(strict);
            assertEquals(ErrorCode.STRICTNESS_VIOLATION, error.code());
            assertEquals(2, error.evidence().suggestions().size());
            assertEquals(0, dispatches.get());
            assertEquals("role and accessible name",
                    error.evidence().suggestions().getFirst().rationale());
        }
    }

    @Test void timeoutContainsLastRevisionAndBoundedActionabilityEvidence() {
        try (Fixture fixture = new Fixture()) {
            fixture.button("covered", "Covered", 100, 100);
            Actor cover = new Actor();
            cover.setBounds(90, 90, 200, 80);
            fixture.stage.addActor(cover);
            Deadline deadline = Deadline.after(fixture.clock, fixture.step.multipliedBy(2));

            CompletionStage<ActionResult> click = fixture.harness.click(
                    Locator.testId("covered"), deadline);
            fixture.nextFrame();
            fixture.nextFrame();

            HarnessException error = failure(click);
            assertEquals(ErrorCode.TIMEOUT, error.code());
            assertTrue(error.evidence().lastSnapshotRevision().isPresent());
            assertTrue(error.evidence().details().get("unmet").contains("HIT_TARGET"));
            assertTrue(error.evidence().details().size() <= 4);
        }
    }

    @Test void awaitingFrameActionExpiresWithoutAnotherFrame() {
        try (Fixture fixture = new Fixture()) {
            fixture.button("frozen", "Frozen", 100, 100);
            ManualClock manual = new ManualClock();
            CompletionStage<ActionResult> click = fixture.harness.click(
                    Locator.testId("frozen"), Deadline.after(manual, Duration.ofSeconds(1)));
            fixture.nextFrame(); // first inspection establishes the stability baseline
            fixture.nextFrame(); // dispatch completes and the deadline signal is armed

            manual.advance(Duration.ofSeconds(1));
            fixture.deadlines.expire();
            assertTrue(click.toCompletableFuture().isDone(),
                    "the deadline signal must fail the action without a completed frame");
            HarnessException error = failure(click);
            assertEquals(ErrorCode.TIMEOUT, error.code());
            assertTrue(error.evidence().elapsed().toMillis() >= 1000,
                    "the typed timeout must retain the elapsed monotonic time");
            assertTrue(error.evidence().lastSnapshotRevision().isPresent(),
                    "the typed timeout must retain the last observed revision");
            assertTrue(error.evidence().details().containsKey("unmet"),
                    "the typed timeout must retain the last actionability evidence");
        }
    }

    @Test void deadlineSignalRacingACompletedFrameCompletesExactlyOnce() {
        try (Fixture fixture = new Fixture()) {
            fixture.button("race", "Race", 100, 100);
            ManualClock manual = new ManualClock();
            CompletionStage<ActionResult> click = fixture.harness.click(
                    Locator.testId("race"), Deadline.after(manual, Duration.ofSeconds(1)));
            fixture.nextFrame(); // first inspection establishes the stability baseline
            fixture.nextFrame(); // dispatch completes and the deadline signal is armed

            manual.advance(Duration.ofSeconds(1));
            fixture.deadlines.expire();
            assertTrue(click.toCompletableFuture().isDone(),
                    "the signal must win the race before any completed frame");
            fixture.nextFrame(); // a late completed frame must not override the timeout

            assertTrue(click.toCompletableFuture().isCompletedExceptionally(),
                    "the timeout must complete exactly once");
            assertEquals(ErrorCode.TIMEOUT, failure(click).code());
        }
    }

    @Test void completionImmediatelyBeforeTheSignalWins() {
        try (Fixture fixture = new Fixture()) {
            fixture.button("wins", "Wins", 100, 100);
            CompletionStage<ActionResult> click = fixture.harness.click(
                    Locator.testId("wins"), fixture.deadline());
            fixture.nextFrame(); // first inspection establishes the stability baseline
            fixture.nextFrame(); // dispatch completes and the deadline signal is armed
            fixture.nextFrame(); // the completing frame wins and cancels the signal

            ActionResult result = click.toCompletableFuture().join();
            assertTrue(result.afterRevision() > result.beforeRevision());
            fixture.deadlines.expire(); // a late signal must observe the terminal state
            assertFalse(click.toCompletableFuture().isCompletedExceptionally());
            assertEquals("true", click.toCompletableFuture().join().observedState());
        }
    }

    @Test void closingHarnessCancelsArmedActionDeadlineSignals() {
        try (Fixture fixture = new Fixture()) {
            fixture.button("closing", "Closing", 100, 100);
            CompletionStage<ActionResult> click = fixture.harness.click(
                    Locator.testId("closing"), fixture.deadline());
            fixture.nextFrame(); // first inspection establishes the stability baseline
            fixture.nextFrame(); // dispatch completes and the deadline signal is armed

            fixture.harness.close();
            assertTrue(fixture.deadlines.cancelled,
                    "closing the harness must cancel every armed deadline signal");
            fixture.deadlines.expire(); // a late signal must not disturb the dispatched action
            assertFalse(click.toCompletableFuture().isCompletedExceptionally());
            fixture.nextFrame();
            assertTrue(click.toCompletableFuture().join().afterRevision() > 0,
                    "the dispatched action still completes through the post-action frame");
        }
    }

    private static HarnessException failure(CompletionStage<?> stage) {
        CompletionException completion = assertThrows(
                CompletionException.class, () -> stage.toCompletableFuture().join());
        assertTrue(completion.getCause() instanceof HarnessException);
        return (HarnessException) completion.getCause();
    }

    private static SliderStyle sliderStyle() {
        SliderStyle style = new SliderStyle();
        style.background = new BaseDrawable();
        style.knob = new BaseDrawable();
        style.knob.setMinWidth(10);
        style.knob.setMinHeight(10);
        return style;
    }

    /** Deterministic monotonic time source advanced explicitly by the test. */
    private static final class ManualClock implements MonotonicClock {
        private long nowNanos;

        void advance(Duration duration) {
            nowNanos = Math.addExact(nowNanos, duration.toNanos());
        }

        @Override public long nanoTime() {
            return nowNanos;
        }
    }

    /**
     * Records one armed deadline signal. {@link #expire()} fires the signal even when a
     * cancellation raced it, mirroring a real executor whose signal was already dispatched.
     */
    private static final class ManualDeadlineScheduler implements DeadlineScheduler {
        private Runnable signal;
        private boolean cancelled;

        @Override public Cancellation schedule(Duration delay, Runnable signal) {
            this.signal = signal;
            return () -> cancelled = true;
        }

        void expire() {
            if (signal != null) {
                signal.run();
            }
        }
    }

    private static final class CountingInput implements InputProcessor {
        private final InputProcessor delegate;
        private final AtomicInteger dispatches;

        CountingInput(InputProcessor delegate, AtomicInteger dispatches) {
            this.delegate = delegate;
            this.dispatches = dispatches;
        }

        @Override public boolean keyDown(int keycode) {
            dispatches.incrementAndGet();
            return delegate.keyDown(keycode);
        }

        @Override public boolean keyUp(int keycode) {
            dispatches.incrementAndGet();
            return delegate.keyUp(keycode);
        }

        @Override public boolean keyTyped(char character) {
            dispatches.incrementAndGet();
            return delegate.keyTyped(character);
        }

        @Override public boolean touchDown(int screenX, int screenY, int pointer, int button) {
            dispatches.incrementAndGet();
            return delegate.touchDown(screenX, screenY, pointer, button);
        }

        @Override public boolean touchUp(int screenX, int screenY, int pointer, int button) {
            dispatches.incrementAndGet();
            return delegate.touchUp(screenX, screenY, pointer, button);
        }

        @Override public boolean touchDragged(int screenX, int screenY, int pointer) {
            dispatches.incrementAndGet();
            return delegate.touchDragged(screenX, screenY, pointer);
        }

        @Override public boolean mouseMoved(int screenX, int screenY) {
            dispatches.incrementAndGet();
            return delegate.mouseMoved(screenX, screenY);
        }

        @Override public boolean scrolled(float amountX, float amountY) {
            dispatches.incrementAndGet();
            return delegate.scrolled(amountX, amountY);
        }

        @Override public boolean touchCancelled(int screenX, int screenY, int pointer, int button) {
            dispatches.incrementAndGet();
            return delegate.touchCancelled(screenX, screenY, pointer, button);
        }
    }

    private static final class BlockingTextButton extends TextButton {
        private final CountDownLatch inspectionStarted = new CountDownLatch(1);
        private final CountDownLatch inspectionReleased = new CountDownLatch(1);
        private volatile boolean armed;

        BlockingTextButton(String text, TextButtonStyle style) {
            super(text, style);
        }

        void arm() {
            armed = true;
        }

        void awaitInspection() {
            await(inspectionStarted);
        }

        void releaseInspection() {
            inspectionReleased.countDown();
        }

        @Override public float getWidth() {
            if (armed) {
                armed = false;
                inspectionStarted.countDown();
                await(inspectionReleased);
            }
            return super.getWidth();
        }

        private static void await(CountDownLatch latch) {
            try {
                latch.await();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while coordinating render-thread race", error);
            }
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Duration step = Duration.ofMillis(16);
        final Stage stage;
        final ControlledStageClock clock;
        final RenderThreadScheduler scheduler = new RenderThreadScheduler(64);
        final Scene2dSession session;
        final ManualDeadlineScheduler deadlines = new ManualDeadlineScheduler();
        final Scene2dHarness harness;

        Fixture() {
            this(Scene2dTestSupport.stage(), null);
        }

        Fixture(Stage stage, InputProcessor input) {
            this.stage = stage;
            this.clock = new ControlledStageClock(stage, step);
            this.session = new Scene2dSession(stage);
            harness = new Scene2dHarness(
                    stage, input == null ? stage : input, session, scheduler, clock,
                    clock::revision, clock::frame, deadlines);
        }

        Deadline deadline() {
            return Deadline.after(clock, Duration.ofSeconds(5));
        }

        TextButton button(String testId, String text, float x, float y) {
            TextButton button = unattachedButton(testId, text, x, y);
            stage.addActor(button);
            return button;
        }

        TextButton unattachedButton(String testId, String text, float x, float y) {
            TextButton button = new TextButton(text, WidgetStyles.textButton());
            button.setBounds(x, y, 180, 50);
            tag(button, testId);
            return button;
        }

        void tag(Actor actor, String testId) {
            session.semantics().setTestId(actor, testId);
        }

        void drain() {
            scheduler.drain();
        }

        void nextFrame() {
            clock.advance(step);
            scheduler.drain();
        }

        ActionResult complete(CompletionStage<ActionResult> action) {
            for (int index = 0; index < 4 && !action.toCompletableFuture().isDone(); index++) {
                nextFrame();
            }
            return action.toCompletableFuture().join();
        }

        @Override public void close() {
            harness.close();
            session.close();
            scheduler.close();
            clock.close();
            stage.dispose();
        }
    }
}
