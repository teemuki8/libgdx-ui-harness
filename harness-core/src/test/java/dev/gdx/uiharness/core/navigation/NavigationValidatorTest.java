package dev.gdx.uiharness.core.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.gdx.uiharness.core.error.HarnessException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

final class NavigationValidatorTest {
    private final NavigationValidator validator = new NavigationValidator();

    @Test
    void returnsKnownFocusablesInStableSemanticOrderAndReportsDefaultFocus() {
        NavigationResult result = validator.validate(request(
                List.of(step(NavigationInput.TAB, "menu/z", "menu/a", "root")),
                List.of("menu/z", "menu/a")));

        assertEquals(List.of("menu/a", "menu/z"), result.knownFocusables());
        assertEquals("menu/z", result.path().defaultFocusIdentity());
        assertEquals(NavigationReason.COMPLETE, result.path().reason());
    }

    @Test
    void distinguishesARepeatedStateCycleFromAnInputThatDoesNotMoveFocus() {
        NavigationResult cycle = validator.validate(request(
                List.of(
                        step(NavigationInput.TAB, "a", "b", "root"),
                        step(NavigationInput.TAB, "b", "a", "root")),
                List.of("a", "b")));
        NavigationResult deadEnd = validator.validate(request(
                List.of(step(NavigationInput.RIGHT, "a", "a", "root")),
                List.of("a", "b")));

        assertEquals(NavigationReason.CYCLE, cycle.path().reason());
        assertEquals(NavigationReason.DEAD_END, deadEnd.path().reason());
    }

    @Test
    void cycleIdentityIncludesModalContextRatherThanSnapshotNodeIdentity() {
        NavigationResult result = validator.validate(request(
                List.of(
                        step(NavigationInput.TAB, "dialog/ok", "dialog/cancel", "dialog-1"),
                        step(NavigationInput.TAB, "dialog/cancel", "dialog/ok", "dialog-2")),
                List.of("dialog/ok", "dialog/cancel")));

        assertEquals(NavigationReason.COMPLETE, result.path().reason());
    }

    @Test
    void reportsUnreachableFocusableControls() {
        NavigationResult result = validator.validate(request(
                List.of(step(NavigationInput.TAB, "a", "b", null)),
                List.of("a", "b", "c")));

        assertEquals(NavigationReason.UNREACHABLE_CONTROL, result.path().reason());
        assertEquals(List.of("c"), result.unreachableFocusables());
    }

    @Test
    void reportsModalEscapeBeforeGeneralReachability() {
        NavigationRequest request = new NavigationRequest(
                1, List.of(step(NavigationInput.ESCAPE, "dialog/ok", "screen/menu", null)),
                List.of("dialog/ok", "screen/menu"), "dialog", true, false,
                32, 32, 4096, 4096, Duration.ofSeconds(1));

        assertEquals(NavigationReason.MODAL_ESCAPE, validator.validate(request).path().reason());
    }

    @Test
    void reportsFocusLossAndUnsupportedControllerPathWithClosedReasons() {
        NavigationResult focusLost = validator.validate(request(
                List.of(step(NavigationInput.TAB, "a", null, null)), List.of("a")));
        NavigationRequest unsupported = new NavigationRequest(
                1, List.of(step(NavigationInput.CONTROLLER_RIGHT, "a", "b", null)),
                List.of("a", "b"), null, false, false,
                32, 32, 4096, 4096, Duration.ofSeconds(1));

        assertEquals(NavigationReason.FOCUS_LOST, focusLost.path().reason());
        assertEquals(
                NavigationReason.UNSUPPORTED_CONTROLLER_PATH,
                validator.validate(unsupported).path().reason());
    }

    @Test
    void deadlineAndStepBoundTerminateDeterministically() {
        NavigationRequest deadline = new NavigationRequest(
                1, List.of(step(NavigationInput.TAB, "a", "b", null)), List.of("a", "b"),
                null, true, true, 32, 32, 4096, 4096, Duration.ofSeconds(1));
        NavigationRequest truncated = new NavigationRequest(
                1,
                List.of(
                        step(NavigationInput.TAB, "a", "b", null),
                        step(NavigationInput.TAB, "b", "c", null)),
                List.of("a", "b", "c"), null, true, false,
                1, 32, 4096, 4096, Duration.ofSeconds(1));

        assertEquals(NavigationReason.DEADLINE, validator.validate(deadline).path().reason());
        NavigationResult result = validator.validate(truncated);
        assertEquals(NavigationReason.TRUNCATED, result.path().reason());
        assertEquals(1, result.path().steps().size());
    }

    @Test
    void rejectsEveryInvalidBoundAndUnsupportedVersion() {
        List<NavigationStep> steps = List.of(step(NavigationInput.TAB, "a", "b", null));
        List<String> actors = List.of("a", "b");
        assertThrows(IllegalArgumentException.class, () -> new NavigationRequest(
                2, steps, actors, null, true, false, 1, 2, 1, 1, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new NavigationRequest(
                1, steps, actors, null, true, false, 0, 2, 1, 1, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new NavigationRequest(
                1, steps, actors, null, true, false, 1, 0, 1, 1, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new NavigationRequest(
                1, steps, actors, null, true, false, 1, 2, 0, 1, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new NavigationRequest(
                1, steps, actors, null, true, false, 1, 2, 1, 0, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new NavigationRequest(
                1, steps, actors, null, true, false, 1, 2, 1, 1, Duration.ZERO));
        assertThrows(HarnessException.class, () -> new NavigationRequest(
                1, steps, actors, null, true, false, 1, 2, 1, 1, Duration.ofSeconds(31)));
        assertThrows(IllegalArgumentException.class, () -> new NavigationRequest(
                1, steps, actors, null, true, false, 1, 1, 1, 1, Duration.ofSeconds(1)));
    }

    @Test
    void rejectsDuplicateOrAmbiguousSemanticIdentitiesDeterministically() {
        assertThrows(IllegalArgumentException.class, () -> request(List.of(), List.of("a", "a")));
        assertThrows(IllegalArgumentException.class, () -> request(List.of(), List.of("a", " a ")));
        assertThrows(IllegalArgumentException.class, () -> request(
                List.of(step(NavigationInput.TAB, "missing", "a", null)), List.of("a")));
    }

    private static NavigationRequest request(List<NavigationStep> steps, List<String> actors) {
        return new NavigationRequest(
                1, steps, actors, null, true, false,
                32, 32, 4096, 4096, Duration.ofSeconds(1));
    }

    private static NavigationStep step(
            NavigationInput input, String before, String after, String modalBoundary) {
        return new NavigationStep(input, 10, 20, 11, 21, before, after, modalBoundary);
    }
}
