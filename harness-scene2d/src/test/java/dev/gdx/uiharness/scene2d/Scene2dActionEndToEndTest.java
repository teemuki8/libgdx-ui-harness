package dev.gdx.uiharness.scene2d;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.Input.Buttons;
import com.badlogic.gdx.Input.Keys;
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
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.BaseDrawable;
import dev.gdx.uiharness.core.action.Action;
import dev.gdx.uiharness.core.action.ActionResult;
import dev.gdx.uiharness.core.error.ErrorCode;
import dev.gdx.uiharness.core.error.HarnessException;
import dev.gdx.uiharness.core.locator.Locator;
import dev.gdx.uiharness.core.time.Deadline;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
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

    private static final class Fixture implements AutoCloseable {
        final Duration step = Duration.ofMillis(16);
        final Stage stage = Scene2dTestSupport.stage();
        final ControlledStageClock clock = new ControlledStageClock(stage, step);
        final RenderThreadScheduler scheduler = new RenderThreadScheduler(64);
        final Scene2dSession session = new Scene2dSession(stage);
        final Scene2dHarness harness = new Scene2dHarness(
                stage, stage, session, scheduler, clock, clock::revision, clock::frame);

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
