package dev.gdx.uiharness.core.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
                        step(10, 20, 11, 21, "a", "b"),
                        step(11, 21, 12, 22, "b", "a")),
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
                        new NavigationStep(
                                NavigationInput.TAB, 10, 20, 11, 21,
                                "dialog/ok", "dialog/cancel", "dialog-1"),
                        new NavigationStep(
                                NavigationInput.TAB, 11, 21, 12, 22,
                                "dialog/cancel", "dialog/ok", "dialog-2")),
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
                        step(10, 20, 11, 21, "a", "b"),
                        step(11, 21, 12, 22, "b", "c")),
                List.of("a", "b", "c"), null, true, false,
                1, 32, 4096, 4096, Duration.ofSeconds(1));

        assertEquals(NavigationReason.DEADLINE, validator.validate(deadline).path().reason());
        NavigationResult result = validator.validate(truncated);
        assertEquals(NavigationReason.TRUNCATED, result.path().reason());
        assertEquals(1, result.path().steps().size());
    }
    @Test
    void rejectsDiscontinuousAdjacentStepIdentity() {
        NavigationStep first = step(10, 20, 11, 21, "a", "b");
        NavigationStep second = step(11, 21, 12, 22, "c", "a");

        assertThrows(IllegalArgumentException.class, () ->
                request(List.of(first, second), List.of("a", "b", "c")));
    }

    @Test
    void rejectsDiscontinuousAdjacentStepFrame() {
        NavigationStep first = step(10, 20, 11, 21, "a", "b");
        NavigationStep second = step(12, 21, 13, 22, "b", "a");

        assertThrows(IllegalArgumentException.class, () ->
                request(List.of(first, second), List.of("a", "b")));
    }

    @Test
    void rejectsDiscontinuousAdjacentStepRevision() {
        NavigationStep first = step(10, 20, 11, 21, "a", "b");
        NavigationStep second = step(11, 22, 12, 23, "b", "a");

        assertThrows(IllegalArgumentException.class, () ->
                request(List.of(first, second), List.of("a", "b")));
    }

    @Test
    void reportsEvidenceTruncationWithoutHidingEveryTerminalReason() {
        assertTruncatedReason(
                NavigationReason.DEAD_END,
                List.of(step(NavigationInput.RIGHT, "a", "a", null)),
                List.of("a", "unvisited"));
        assertTruncatedReason(
                NavigationReason.CYCLE,
                List.of(
                        step(10, 20, 11, 21, "a", "b"),
                        step(11, 21, 12, 22, "b", "a")),
                List.of("a", "b", "unvisited"));
        assertTruncatedReason(
                NavigationReason.MODAL_ESCAPE,
                List.of(step(NavigationInput.ESCAPE, "a", "b", null)),
                List.of("a", "b", "unvisited"),
                "modal", true, false);
        assertTruncatedReason(
                NavigationReason.FOCUS_LOST,
                List.of(step(NavigationInput.TAB, "a", null, null)),
                List.of("a", "unvisited"));
        assertTruncatedReason(
                NavigationReason.UNSUPPORTED_CONTROLLER_PATH,
                List.of(step(NavigationInput.CONTROLLER_RIGHT, "a", "b", null)),
                List.of("a", "b", "unvisited"),
                null, false, false);
        assertTruncatedReason(
                NavigationReason.DEADLINE,
                List.of(step(NavigationInput.TAB, "a", "b", null)),
                List.of("a", "b", "unvisited"),
                null, true, true);
        assertTruncatedReason(
                NavigationReason.COMPLETE,
                List.of(step(NavigationInput.TAB, "a", "b", null)),
                List.of("a", "b"));
        assertTruncatedReason(
                NavigationReason.UNREACHABLE_CONTROL,
                List.of(step(NavigationInput.TAB, "a", "b", null)),
                List.of("a", "b", "unvisited"));
    }

    @Test
    void maxResultBytesBoundsWholeResultWithEscapedAndMultibyteValues() {
        int budget = 400;
        NavigationRequest request = new NavigationRequest(
                1,
                List.of(step(NavigationInput.TAB, "a\"\\\u0001\u754c", "b\uD83D\uDE00", null)),
                List.of("a\"\\\u0001\u754c", "b\uD83D\uDE00", "metadata-only-overflow"),
                null, true, false, 32, 32, budget, 4096, Duration.ofSeconds(1));

        NavigationResult result = validator.validate(request);

        assertTrue(result.wireSizeUpperBound() <= budget);
        assertTrue(result.truncated());
    }

    @Test
    void maxResultBytesTruncatesMetadataEvenWhenThereAreNoSteps() {
        int budget = 220;
        NavigationRequest request = new NavigationRequest(
                1, List.of(), List.of("escaped-\"\\\u0001-\u754c-\uD83D\uDE00"),
                null, true, false, 32, 32, budget, 4096, Duration.ofSeconds(1));

        NavigationResult result = validator.validate(request);

        assertTrue(result.wireSizeUpperBound() <= budget);
        assertTrue(result.truncated());
        assertEquals(List.of(), result.knownFocusables());
        assertEquals(List.of(), result.unreachableFocusables());
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
                1, steps, actors, null, true, false, 1, 2, 4096, 1, Duration.ofSeconds(31)));
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

    private void assertTruncatedReason(
            NavigationReason reason, List<NavigationStep> steps, List<String> actors) {
        assertTruncatedReason(reason, steps, actors, null, true, false);
    }

    private void assertTruncatedReason(
            NavigationReason reason,
            List<NavigationStep> steps,
            List<String> actors,
            String modalBoundary,
            boolean controllerSupported,
            boolean deadlineExpired) {
        NavigationRequest request = new NavigationRequest(
                1, steps, actors, modalBoundary, controllerSupported, deadlineExpired,
                32, 32, 4096, 1, Duration.ofSeconds(1));

        NavigationResult result = validator.validate(request);

        assertEquals(reason, result.path().reason());
        assertTrue(result.truncated());
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

    private static NavigationStep step(
            long beforeFrame,
            long beforeRevision,
            long afterFrame,
            long afterRevision,
            String before,
            String after) {
        return new NavigationStep(
                NavigationInput.TAB,
                beforeFrame,
                beforeRevision,
                afterFrame,
                afterRevision,
                before,
                after,
                null);
    }
}
