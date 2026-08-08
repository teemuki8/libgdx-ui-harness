package dev.gdx.uiharness.scene2d;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import dev.gdx.uiharness.core.model.Role;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import dev.gdx.uiharness.core.wait.FrameSignal;
import dev.gdx.uiharness.core.wait.WaitEngine;
import dev.gdx.uiharness.mcp.ArtifactReference;
import dev.gdx.uiharness.mcp.HarnessToolHandler;
import dev.gdx.uiharness.protocol.CapabilitySet;
import dev.gdx.uiharness.protocol.HarnessProtocolService;
import io.modelcontextprotocol.spec.McpSchema;
import dev.gdx.uiharness.core.time.Deadline;
import dev.gdx.uiharness.core.time.DeadlineScheduler;
import dev.gdx.uiharness.core.time.MonotonicClock;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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

    @Test void dispatchedInputIsNotUndoneByCancellationOrClose() {
        try (Fixture fixture = new Fixture()) {
            TextButton button = fixture.button("dispatch-wins", "Dispatch", 100, 100);
            CompletionStage<ActionResult> click = fixture.harness.click(
                    Locator.testId("dispatch-wins"), fixture.deadline());
            fixture.nextFrame(); // first inspection establishes the stability baseline
            fixture.nextFrame(); // dispatch completes; the confirmation frame is pending

            assertTrue(button.isChecked());
            assertFalse(click.toCompletableFuture().isDone());
            assertTrue(click.toCompletableFuture().cancel(false),
                    "post-dispatch cancellation claims the pending confirmation");
            assertTrue(click.toCompletableFuture().isCancelled());
            assertTrue(button.isChecked(), "cancellation must not undo the dispatched input");
            fixture.harness.close();
            fixture.nextFrame(); // a late completed frame must not override the cancellation
            assertTrue(click.toCompletableFuture().isCancelled());
            assertTrue(button.isChecked());
        }
    }

    @Test void postDispatchCancellationCancelsTokenAndLateSignalNoOps() {
        try (Fixture fixture = new Fixture()) {
            fixture.button("cancel-awaiting", "Cancel", 100, 100);
            CompletionStage<ActionResult> click = fixture.harness.click(
                    Locator.testId("cancel-awaiting"), fixture.deadline());
            fixture.nextFrame(); // first inspection establishes the stability baseline
            fixture.nextFrame(); // dispatch completes and the deadline signal is armed

            assertFalse(fixture.deadlines.cancelled,
                    "the armed signal is live before cancellation");
            assertTrue(click.toCompletableFuture().cancel(false));
            assertTrue(fixture.deadlines.cancelled,
                    "post-dispatch cancellation must cancel the armed deadline signal");
            fixture.deadlines.expire(); // a late signal observes the terminal state
            assertTrue(click.toCompletableFuture().isCancelled());
            fixture.nextFrame(); // a late completed frame observes the terminal state
            assertTrue(click.toCompletableFuture().isCancelled());
            assertTrue(click.toCompletableFuture().isDone());
            assertThrows(java.util.concurrent.CancellationException.class,
                    click.toCompletableFuture()::join);
        }
    }

    @Test void reconciliationCancellationNeverRunsWhileHoldingTheRequestMonitor() {
        ManualClock manual = new ManualClock();
        AtomicBoolean cancellationInvoked = new AtomicBoolean();
        AtomicReference<Boolean> cancellationHeldMonitor = new AtomicReference<>();
        CompletableFuture<?>[] request = new CompletableFuture<?>[1];
        DeadlineScheduler inlineFiringScheduler = (delay, signal) -> {
            manual.advance(delay); // simulate the delay elapsing before an inline zero-delay fire
            signal.run(); // claims the timeout before the install-or-cancel reconcile runs
            return () -> {
                cancellationInvoked.set(true);
                cancellationHeldMonitor.set(Thread.holdsLock(request[0]));
            };
        };
        try (Fixture fixture = new Fixture(
                Scene2dTestSupport.stage(), null, inlineFiringScheduler)) {
            fixture.button("reconcile", "Reconcile", 100, 100);
            CompletionStage<ActionResult> click = fixture.harness.click(
                    Locator.testId("reconcile"), Deadline.after(manual, Duration.ofSeconds(1)));
            request[0] = click.toCompletableFuture();
            fixture.nextFrame(); // first inspection establishes the stability baseline
            fixture.nextFrame(); // dispatch arms; the inline signal claims; the reconcile cancels

            assertTrue(cancellationInvoked.get(),
                    "the in-flight schedule token must be cancelled by the reconcile");
            assertTrue(click.toCompletableFuture().isCompletedExceptionally());
            assertEquals(ErrorCode.TIMEOUT, failure(click).code());
            assertFalse(cancellationHeldMonitor.get(),
                    "reconciliation must cancel the token only after leaving the request monitor");
        }
    }

    @Test void inlineDeadlineSignalNeverCompletesTheFutureWhileHoldingTheRequestMonitor() {
        ManualClock manual = new ManualClock();
        try (Fixture fixture = new Fixture(
                Scene2dTestSupport.stage(), null, new InlineDeadlineScheduler(manual))) {
            fixture.button("inline", "Inline", 100, 100);
            AtomicBoolean continuationRan = new AtomicBoolean();
            AtomicReference<Boolean> continuationHeldMonitor = new AtomicReference<>();
            CompletionStage<ActionResult> click = fixture.harness.click(
                    Locator.testId("inline"), Deadline.after(manual, Duration.ofSeconds(1)));
            click.whenComplete((ignored, failure) -> {
                continuationRan.set(true);
                continuationHeldMonitor.set(Thread.holdsLock(click.toCompletableFuture()));
            });
            fixture.nextFrame(); // first inspection establishes the stability baseline
            fixture.nextFrame(); // dispatch arms the signal; the inline fake fires and claims

            assertTrue(continuationRan.get(),
                    "the inline zero-delay signal must complete the action");
            assertTrue(click.toCompletableFuture().isCompletedExceptionally());
            assertEquals(ErrorCode.TIMEOUT, failure(click).code());
            assertFalse(continuationHeldMonitor.get(),
                    "the completion continuation must never run while the request monitor "
                            + "is retained");
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

    @Test void releasedActorMetadataConstructorIsRetainedWithNullBinding() {
        ActorMetadata metadata = new ActorMetadata(
                Role.BUTTON, "Save", "Save", null, "save",
                null, null, null, null, null,
                Map.of("k", "v"));

        assertEquals(Role.BUTTON, metadata.role());
        assertEquals("Save", metadata.accessibleName());
        assertEquals("save", metadata.testId());
        assertEquals(Map.of("k", "v"), metadata.properties());
        assertNull(metadata.binding(),
                "the released constructor must default the new binding to null");
    }

    @Test void legacyConstructorHonorsNoFrameActionDeadlinesWithRealScheduler() {
        try (Fixture fixture = Fixture.legacy()) {
            fixture.button("frozen-legacy", "Frozen", 100, 100);
            CompletionStage<ActionResult> click = fixture.harness.click(
                    Locator.testId("frozen-legacy"),
                    Deadline.after(System::nanoTime, Duration.ofMillis(250)));
            fixture.nextFrame(); // first inspection establishes the stability baseline
            fixture.nextFrame(); // dispatch completes and the owned scheduler arms the signal

            HarnessException error = failure(click); // no frame; the real signal must fire
            assertEquals(ErrorCode.TIMEOUT, error.code());
            assertTrue(error.evidence().elapsed().toMillis() >= 100,
                    "the typed timeout must retain real elapsed monotonic time");
        }
    }

    @Test void legacyConstructorShutsOwnedSchedulerOnCloseAndFailsPendingAction() {
        try (Fixture fixture = Fixture.legacy()) {
            fixture.button("dispatched-legacy", "Dispatch", 100, 100);
            fixture.button("blocked-legacy", "Blocked", 400, 100);
            Actor cover = new Actor();
            cover.setBounds(390, 90, 200, 80);
            fixture.stage.addActor(cover);
            CompletionStage<ActionResult> dispatched = fixture.harness.click(
                    Locator.testId("dispatched-legacy"), fixture.deadline());
            CompletionStage<ActionResult> blocked = fixture.harness.click(
                    Locator.testId("blocked-legacy"), fixture.deadline());
            fixture.nextFrame();
            fixture.nextFrame(); // dispatched is AWAITING_FRAME with an armed signal
            ScheduledThreadPoolExecutor owned = ownedScheduler(fixture.harness);
            assertNotNull(owned);
            assertFalse(owned.isShutdown());

            long closeStarted = System.nanoTime();
            fixture.harness.close();
            long closeMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - closeStarted);

            assertTrue(owned.isShutdown(),
                    "closing a legacy harness must shut down its owned scheduler");
            assertTrue(owned.isTerminated(),
                    "the owned scheduler must terminate promptly");
            assertTrue(closeMillis < 5_000,
                    "closing with an armed deadline must stay bounded");
            assertEquals(ErrorCode.SESSION_CLOSED, failure(blocked).code());
            fixture.nextFrame(); // a post-close frame completes the dispatched action
            assertTrue(dispatched.toCompletableFuture().join().afterRevision() > 0,
                    "a dispatched action still completes through the post-action frame");
        }
    }

    @Test void injectedConstructorLeavesExternalSchedulerOwned() {
        AtomicBoolean signalled = new AtomicBoolean();
        DeadlineScheduler external = (delay, signal) -> {
            signal.run();
            return () -> {};
        };
        try (Fixture fixture = new Fixture(Scene2dTestSupport.stage(), null, external)) {
            assertNull(ownedScheduler(fixture.harness),
                    "an injected scheduler must not be wrapped in an owned scheduler");
            fixture.harness.close();
            external.schedule(Duration.ZERO, () -> signalled.set(true)).cancel();
            assertTrue(signalled.get(),
                    "closing the harness must leave the injected scheduler usable by its owner");
        }
    }

    @Test void concurrentSecondHarnessCloseWaitsForTheFirstCleanup() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.button("wait-close", "Wait", 100, 100);
            CompletionStage<ActionResult> pending = fixture.harness.click(
                    Locator.testId("wait-close"), fixture.deadline());
            CountDownLatch continuationEntered = new CountDownLatch(1);
            CountDownLatch releaseContinuation = new CountDownLatch(1);
            pending.whenComplete((ignored, failure) -> {
                continuationEntered.countDown();
                try {
                    releaseContinuation.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("continuation interrupted", exception);
                }
            });
            Thread firstCloser = new Thread(fixture.harness::close, "first-harness-closer");
            firstCloser.start();
            assertTrue(continuationEntered.await(1, TimeUnit.SECONDS),
                    "the first close must reach the blocking continuation");
            AtomicBoolean secondCloseReturned = new AtomicBoolean();
            Thread secondCloser = new Thread(() -> {
                fixture.harness.close();
                secondCloseReturned.set(true);
            }, "second-harness-closer");
            secondCloser.start();
            long waitDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (secondCloser.getState() != Thread.State.WAITING
                    && System.nanoTime() < waitDeadline) {
                Thread.yield();
            }
            assertEquals(Thread.State.WAITING, secondCloser.getState(),
                    "a concurrent second close must block until the first cleanup finishes");
            releaseContinuation.countDown();
            firstCloser.join(1_000);
            secondCloser.join(1_000);
            assertFalse(firstCloser.isAlive());
            assertFalse(secondCloser.isAlive());
            assertTrue(secondCloseReturned.get(),
                    "the second close must return once the first cleanup has finished");
        }
    }

    @Test void reentrantHarnessCloseFromContinuationDoesNotDeadlock() {
        try (Fixture fixture = new Fixture()) {
            fixture.button("reentrant-close", "Close", 100, 100);
            CompletionStage<ActionResult> pending = fixture.harness.click(
                    Locator.testId("reentrant-close"), fixture.deadline());
            AtomicBoolean reentrantCloseReturned = new AtomicBoolean();
            pending.whenComplete((ignored, failure) -> {
                fixture.harness.close();
                reentrantCloseReturned.set(true);
            });
            fixture.harness.close();

            assertTrue(reentrantCloseReturned.get(),
                    "a reentrant close from the closing thread's own continuation must return");
            assertEquals(ErrorCode.SESSION_CLOSED, failure(pending).code());
        }
    }

    @Test void legacyHarnessCloseAggregatesCleanupFailuresAndShutsOwnedScheduler() {
        FrameSignal throwingSubscriptions = new FrameSignal() {
            @Override public FrameSignal.Subscription subscribe(FrameSignal.FrameListener listener) {
                return () -> {
                    throw new IllegalStateException("subscription-close");
                };
            }
        };
        Stage stage = Scene2dTestSupport.stage();
        ControlledStageClock clock = new ControlledStageClock(stage, Duration.ofMillis(16));
        RenderThreadScheduler scheduler = new RenderThreadScheduler(64);
        Scene2dSession session = new Scene2dSession(stage);
        Scene2dHarness legacy = new Scene2dHarness(
                stage, stage, session, scheduler, throwingSubscriptions,
                clock::revision, clock::frame);
        try {
            CompletionStage<ActionResult> first = legacy.click(
                    Locator.testId("nope"), Deadline.after(clock, Duration.ofSeconds(5)));
            CompletionStage<ActionResult> second = legacy.click(
                    Locator.testId("nope"), Deadline.after(clock, Duration.ofSeconds(5)));
            ScheduledThreadPoolExecutor owned = ownedScheduler(legacy);
            assertNotNull(owned);

            IllegalStateException closeFailure = assertThrows(
                    IllegalStateException.class, legacy::close);

            assertEquals("subscription-close", closeFailure.getMessage(),
                    "the first cleanup failure must be the primary close failure");
            assertTrue(owned.isShutdown(),
                    "the owned scheduler must shut down even when a cleanup step fails");
            assertTrue(owned.isTerminated());
            assertEquals(ErrorCode.SESSION_CLOSED, failure(first).code());
            assertEquals(ErrorCode.SESSION_CLOSED, failure(second).code());
        } finally {
            legacy.close();
            session.close();
            scheduler.close();
            clock.close();
            stage.dispose();
        }
    }

    private static HarnessException failure(CompletionStage<?> stage) {
        CompletionException completion = assertThrows(
                CompletionException.class, () -> stage.toCompletableFuture().join());
        assertTrue(completion.getCause() instanceof HarnessException);
        return (HarnessException) completion.getCause();
    }

    /**
     * Returns the executor behind the scheduler a legacy-constructed harness owns, or {@code null}
     * when the harness received an injected scheduler. Reflection is required because the owned
     * scheduler is an implementation detail; the legacy constructor contract is that the harness
     * creates and shuts down its own scheduler.
     */
    private static ScheduledThreadPoolExecutor ownedScheduler(Scene2dHarness harness) {
        try {
            Field field = Scene2dHarness.class.getDeclaredField("ownedScheduler");
            field.setAccessible(true);
            Object owned = field.get(harness);
            if (owned == null) {
                return null;
            }
            Field executor = owned.getClass().getDeclaredField("executor");
            executor.setAccessible(true);
            return (ScheduledThreadPoolExecutor) executor.get(owned);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(
                    "the owned scheduler must be observable for lifecycle assertions", exception);
        }
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
     * Fires the signal inline inside {@link #schedule(Duration, Runnable)} with the delay
     * already elapsed, simulating the worst-case zero-delay scheduler that invokes the signal
     * on the scheduling thread.
     */
    private static final class InlineDeadlineScheduler implements DeadlineScheduler {
        private final ManualClock clock;

        InlineDeadlineScheduler(ManualClock clock) {
            this.clock = clock;
        }

        @Override public Cancellation schedule(Duration delay, Runnable signal) {
            clock.advance(delay);
            signal.run();
            return () -> {};
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
            this(stage, input, null);
        }

        Fixture(Stage stage, InputProcessor input, DeadlineScheduler harnessDeadlines) {
            this(stage, input, harnessDeadlines, false);
        }

        static Fixture legacy() {
            return new Fixture(Scene2dTestSupport.stage(), null, null, true);
        }

        private Fixture(
                Stage stage,
                InputProcessor input,
                DeadlineScheduler harnessDeadlines,
                boolean legacyHarness) {
            this.stage = stage;
            this.clock = new ControlledStageClock(stage, step);
            this.session = new Scene2dSession(stage);
            DeadlineScheduler injected = harnessDeadlines == null ? deadlines : harnessDeadlines;
            harness = legacyHarness
                    ? new Scene2dHarness(
                            stage, input == null ? stage : input, session, scheduler, clock,
                            clock::revision, clock::frame)
                    : new Scene2dHarness(
                            stage, input == null ? stage : input, session, scheduler, clock,
                            clock::revision, clock::frame,
                            injected);
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
