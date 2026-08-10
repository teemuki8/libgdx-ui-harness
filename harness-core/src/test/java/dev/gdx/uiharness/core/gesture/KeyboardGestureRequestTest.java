package dev.gdx.uiharness.core.gesture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class KeyboardGestureRequestTest {
    @Test void acceptsBalancedFrameAndTickGesturesAndCopiesSteps() {
        ArrayList<KeyboardGestureRequest.Step> source = new ArrayList<>(List.of(
                new KeyboardGestureRequest.KeyDown(59),
                new KeyboardGestureRequest.KeyDown(29),
                new KeyboardGestureRequest.WaitFrames(4_000),
                new KeyboardGestureRequest.WaitFrames(6_000),
                new KeyboardGestureRequest.WaitTicks(10_000),
                new KeyboardGestureRequest.KeyUp(29),
                new KeyboardGestureRequest.KeyUp(59)));

        KeyboardGestureRequest request = new KeyboardGestureRequest(
                KeyboardGestureRequest.SCHEMA_VERSION, source);
        source.clear();

        assertEquals(7, request.steps().size());
        assertThrows(UnsupportedOperationException.class,
                () -> request.steps().add(new KeyboardGestureRequest.KeyDown(1)));
    }

    @Test void acceptsEveryExactPublicBoundary() {
        ArrayList<KeyboardGestureRequest.Step> steps = new ArrayList<>();
        for (int keycode = 0; keycode < KeyboardGestureRequest.MAX_HELD_KEYS; keycode++) {
            steps.add(new KeyboardGestureRequest.KeyDown(keycode));
        }
        steps.add(new KeyboardGestureRequest.WaitFrames(KeyboardGestureRequest.MAX_WAIT));
        steps.add(new KeyboardGestureRequest.WaitTicks(KeyboardGestureRequest.MAX_WAIT));
        while (steps.size() < KeyboardGestureRequest.MAX_STEPS
                - KeyboardGestureRequest.MAX_HELD_KEYS) {
            steps.add(new KeyboardGestureRequest.KeyUp(
                    KeyboardGestureRequest.MAX_HELD_KEYS - 1));
            steps.add(new KeyboardGestureRequest.KeyDown(
                    KeyboardGestureRequest.MAX_HELD_KEYS - 1));
        }
        for (int keycode = KeyboardGestureRequest.MAX_HELD_KEYS - 1; keycode >= 0; keycode--) {
            steps.add(new KeyboardGestureRequest.KeyUp(keycode));
        }

        assertEquals(KeyboardGestureRequest.MAX_STEPS,
                new KeyboardGestureRequest(1, steps).steps().size());
        assertEquals(KeyboardGestureRequest.MAX_KEYCODE,
                new KeyboardGestureRequest(1, List.of(
                        new KeyboardGestureRequest.KeyDown(
                                KeyboardGestureRequest.MAX_KEYCODE),
                        new KeyboardGestureRequest.KeyUp(
                                KeyboardGestureRequest.MAX_KEYCODE)))
                        .steps().stream()
                        .mapToInt(step -> ((KeyboardGestureRequest.KeyDown) step).keycode())
                        .findFirst().orElseThrow());
    }

    @Test void rejectsSchemaListAndStepBounds() {
        assertInvalid(0, balanced());
        assertInvalid(2, balanced());
        assertInvalid(1, List.of(new KeyboardGestureRequest.KeyDown(1)));
        ArrayList<KeyboardGestureRequest.Step> tooMany = new ArrayList<>();
        tooMany.add(new KeyboardGestureRequest.KeyDown(1));
        for (int index = 0; index < KeyboardGestureRequest.MAX_STEPS - 1; index++) {
            tooMany.add(new KeyboardGestureRequest.WaitFrames(1));
        }
        tooMany.add(new KeyboardGestureRequest.KeyUp(1));
        assertInvalid(1, tooMany);
        assertThrows(IllegalArgumentException.class,
                () -> new KeyboardGestureRequest.KeyDown(-1));
        assertThrows(IllegalArgumentException.class,
                () -> new KeyboardGestureRequest.KeyUp(256));
        assertThrows(IllegalArgumentException.class,
                () -> new KeyboardGestureRequest.WaitFrames(0));
        assertThrows(IllegalArgumentException.class,
                () -> new KeyboardGestureRequest.WaitTicks(10_001));
    }

    @Test void rejectsInvalidBalanceAndOwnership() {
        assertInvalid(1, List.of(
                new KeyboardGestureRequest.KeyDown(1),
                new KeyboardGestureRequest.KeyDown(1),
                new KeyboardGestureRequest.KeyUp(1)));
        assertInvalid(1, List.of(
                new KeyboardGestureRequest.KeyDown(1),
                new KeyboardGestureRequest.KeyUp(2),
                new KeyboardGestureRequest.KeyUp(1)));
        assertInvalid(1, List.of(
                new KeyboardGestureRequest.WaitFrames(1),
                new KeyboardGestureRequest.KeyDown(1),
                new KeyboardGestureRequest.KeyUp(1)));
        assertInvalid(1, List.of(
                new KeyboardGestureRequest.KeyDown(1),
                new KeyboardGestureRequest.WaitFrames(1)));
        assertInvalid(1, List.of(
                new KeyboardGestureRequest.WaitFrames(1),
                new KeyboardGestureRequest.WaitTicks(1)));
    }

    @Test void rejectsHeldAndCumulativeWaitLimits() {
        ArrayList<KeyboardGestureRequest.Step> tooManyHeld = new ArrayList<>();
        for (int keycode = 0; keycode <= KeyboardGestureRequest.MAX_HELD_KEYS; keycode++) {
            tooManyHeld.add(new KeyboardGestureRequest.KeyDown(keycode));
        }
        for (int keycode = KeyboardGestureRequest.MAX_HELD_KEYS; keycode >= 0; keycode--) {
            tooManyHeld.add(new KeyboardGestureRequest.KeyUp(keycode));
        }
        assertInvalid(1, tooManyHeld);
        assertInvalid(1, List.of(
                new KeyboardGestureRequest.KeyDown(1),
                new KeyboardGestureRequest.WaitFrames(6_000),
                new KeyboardGestureRequest.WaitFrames(4_001),
                new KeyboardGestureRequest.KeyUp(1)));
        assertInvalid(1, List.of(
                new KeyboardGestureRequest.KeyDown(1),
                new KeyboardGestureRequest.WaitTicks(5_001),
                new KeyboardGestureRequest.WaitTicks(5_000),
                new KeyboardGestureRequest.KeyUp(1)));
    }

    @Test void rejectsNullInputsAndNullSteps() {
        assertThrows(NullPointerException.class,
                () -> new KeyboardGestureRequest(1, null));
        ArrayList<KeyboardGestureRequest.Step> steps = new ArrayList<>();
        steps.add(new KeyboardGestureRequest.KeyDown(1));
        steps.add(null);
        steps.add(new KeyboardGestureRequest.KeyUp(1));
        assertThrows(NullPointerException.class,
                () -> new KeyboardGestureRequest(1, steps));
    }

    private static List<KeyboardGestureRequest.Step> balanced() {
        return List.of(
                new KeyboardGestureRequest.KeyDown(1),
                new KeyboardGestureRequest.KeyUp(1));
    }

    private static void assertInvalid(
            int schemaVersion, List<KeyboardGestureRequest.Step> steps) {
        assertThrows(IllegalArgumentException.class,
                () -> new KeyboardGestureRequest(schemaVersion, steps));
    }
}
