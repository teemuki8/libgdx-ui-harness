package dev.gdx.uiharness.core.action;

import dev.gdx.uiharness.core.locator.Locator;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import dev.gdx.uiharness.core.time.Deadline;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** Backend-neutral asynchronous facade for snapshots and faithful input actions. */
public interface Harness {
    CompletionStage<ActionResult> perform(Locator locator, Action action, Deadline deadline);

    CompletionStage<SemanticSnapshot> snapshot(Deadline deadline);

    default CompletionStage<ActionResult> click(Locator locator, Deadline deadline) {
        return perform(locator, Action.click(), deadline);
    }

    default CompletionStage<ActionResult> hover(Locator locator, Deadline deadline) {
        return perform(locator, Action.hover(), deadline);
    }

    default CompletionStage<ActionResult> focus(Locator locator, Deadline deadline) {
        return perform(locator, Action.focus(), deadline);
    }

    default CompletionStage<ActionResult> fill(
            Locator locator, String value, Deadline deadline) {
        return perform(locator, Action.fill(value), deadline);
    }

    default CompletionStage<ActionResult> press(
            Locator locator, int keycode, Deadline deadline) {
        return perform(locator, Action.press(keycode), deadline);
    }

    default CompletionStage<ActionResult> scroll(
            Locator locator, float amountX, float amountY, Deadline deadline) {
        return perform(locator, Action.scroll(amountX, amountY), deadline);
    }

    default CompletionStage<ActionResult> drag(
            Locator locator, float deltaX, float deltaY, Deadline deadline) {
        return perform(locator, Action.drag(deltaX, deltaY), deadline);
    }

    default CompletionStage<ActionResult> pointer(
            Locator locator,
            Action.PointerPhase phase,
            float offsetX,
            float offsetY,
            int pointer,
            int button,
            Deadline deadline) {
        Objects.requireNonNull(phase, "phase");
        return perform(locator,
                Action.pointer(phase, offsetX, offsetY, pointer, button), deadline);
    }
}
