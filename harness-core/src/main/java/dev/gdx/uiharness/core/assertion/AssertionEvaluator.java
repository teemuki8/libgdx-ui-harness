package dev.gdx.uiharness.core.assertion;

import dev.gdx.uiharness.core.locator.LocatorEngine;
import dev.gdx.uiharness.core.locator.StrictResolution;
import dev.gdx.uiharness.core.model.Bounds;
import dev.gdx.uiharness.core.model.SemanticNode;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import java.util.Objects;
import java.util.Optional;

/** Pure evaluation of non-temporal assertions against one supplied semantic snapshot. */
public final class AssertionEvaluator {
    private final LocatorEngine locators;

    public AssertionEvaluator() {
        this(new StrictResolution());
    }

    public AssertionEvaluator(LocatorEngine locators) {
        this.locators = Objects.requireNonNull(locators, "locators");
    }

    /** Evaluates once; strict locator errors are propagated unchanged. */
    public AssertionResult evaluate(SemanticSnapshot snapshot, AssertionRequest request) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(request, "request");
        UiAssertion assertion = request.assertion();
        if (assertion instanceof UiAssertion.StableForFrames) {
            throw new IllegalArgumentException("StableForFrames requires completed-frame evaluation");
        }
        if (assertion instanceof UiAssertion.CountEquals count) {
            int actual = locators.query(snapshot, request.locator()).matches().size();
            return result(actual == count.expected(), "", Integer.toString(count.expected()),
                    Integer.toString(actual), snapshot, request);
        }

        SemanticNode node = locators.resolveStrict(snapshot, request.locator());
        Evaluation evaluation = switch (assertion) {
            case UiAssertion.Visible ignored -> booleanValue(node.state().visible(), true, "visible");
            case UiAssertion.Hidden ignored -> booleanValue(node.state().visible(), false, "visible");
            case UiAssertion.Enabled ignored -> optionalBoolean(node.state().enabled(), true, "enabled");
            case UiAssertion.Disabled ignored -> optionalBoolean(node.state().enabled(), false, "enabled");
            case UiAssertion.Focused ignored -> booleanValue(node.state().focused(), true, "focused");
            case UiAssertion.Checked ignored -> optionalBoolean(node.state().checked(), true, "checked");
            case UiAssertion.TextEquals text -> text(node.text(), text.expected(), false);
            case UiAssertion.TextContains text -> text(node.text(), text.expected(), true);
            case UiAssertion.BoundsInsideViewport bounds -> inside(node.screenBounds(), bounds.viewport());
            case UiAssertion.DoesNotOverlap overlap -> noOverlap(
                    node, locators.resolveStrict(snapshot, overlap.other()));
            case UiAssertion.AccessibleNameExists ignored -> accessibleName(node.accessibleName());
            case UiAssertion.CountEquals ignored -> throw new AssertionError("handled above");
            case UiAssertion.StableForFrames ignored -> throw new AssertionError("handled above");
        };
        return result(evaluation.passed(), node.id(), evaluation.expected(), evaluation.observed(),
                snapshot, request);
    }

    private static Evaluation booleanValue(boolean actual, boolean expected, String label) {
        return new Evaluation(actual == expected, Boolean.toString(expected),
                label + "=" + actual);
    }

    private static Evaluation optionalBoolean(Optional<Boolean> actual, boolean expected, String label) {
        if (actual.isEmpty()) return new Evaluation(false, Boolean.toString(expected), label + "=unsupported");
        return booleanValue(actual.get(), expected, label);
    }

    private static Evaluation text(String actual, String expected, boolean contains) {
        boolean passed = actual != null && (contains ? actual.contains(expected) : actual.equals(expected));
        return new Evaluation(passed, expected, actual == null ? "unsupported" : actual);
    }

    private static Evaluation inside(Bounds actual, Bounds viewport) {
        boolean inside = actual.x() >= viewport.x() && actual.y() >= viewport.y()
                && actual.x() - viewport.x() <= viewport.width()
                && actual.y() - viewport.y() <= viewport.height()
                && actual.width() <= viewport.width() - (actual.x() - viewport.x())
                && actual.height() <= viewport.height() - (actual.y() - viewport.y());
        return new Evaluation(inside, viewport.toString(), actual.toString());
    }

    private static Evaluation noOverlap(SemanticNode first, SemanticNode second) {
        Bounds a = first.screenBounds();
        Bounds b = second.screenBounds();
        double intersectionWidth = Math.min(a.x() + a.width(), b.x() + b.width())
                - Math.max(a.x(), b.x());
        double intersectionHeight = Math.min(a.y() + a.height(), b.y() + b.height())
                - Math.max(a.y(), b.y());
        boolean overlaps = intersectionWidth > 0 && intersectionHeight > 0;
        return new Evaluation(!overlaps, "no positive-area overlap",
                second.id() + " " + b);
    }

    private static Evaluation accessibleName(String name) {
        boolean exists = name != null && !name.isBlank();
        return new Evaluation(exists, "non-blank", exists ? name : "blank");
    }

    private static AssertionResult result(boolean passed, String nodeId, String expected,
            String observed, SemanticSnapshot snapshot, AssertionRequest request) {
        long elapsed = request.deadline().elapsed().toNanos();
        return new AssertionResult(
                passed ? AssertionResult.Status.PASSED : AssertionResult.Status.FAILED,
                new AssertionEvidence(nodeId, expected, observed, snapshot.revision(), snapshot.frame()),
                elapsed);
    }

    private record Evaluation(boolean passed, String expected, String observed) {}
}
