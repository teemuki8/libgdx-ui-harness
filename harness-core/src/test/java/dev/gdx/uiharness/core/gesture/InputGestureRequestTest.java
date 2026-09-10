package dev.gdx.uiharness.core.gesture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.util.List;
import org.junit.jupiter.api.Test;

final class InputGestureRequestTest {
    @Test void acceptsMouseOnlyAndCombinedBalancedInput() {
        assertEquals(1, new InputGestureRequest(1, List.of(
                new InputGestureRequest.MouseMove(40, -20))).steps().size());
        assertEquals(6, new InputGestureRequest(1, List.of(
                new InputGestureRequest.KeyDown(51), new InputGestureRequest.MouseDown(0),
                new InputGestureRequest.MouseMove(40, -20), new InputGestureRequest.WaitTicks(30),
                new InputGestureRequest.MouseUp(0), new InputGestureRequest.KeyUp(51))).steps().size());
    }
    @Test void rejectsUnbalancedButtonsAndInvalidDeltasBeforeDispatch() {
        assertThrows(IllegalArgumentException.class, () -> new InputGestureRequest(1,
                List.of(new InputGestureRequest.MouseDown(0))));
        assertThrows(IllegalArgumentException.class, () -> new InputGestureRequest(1,
                List.of(new InputGestureRequest.MouseUp(0))));
        assertThrows(IllegalArgumentException.class, () -> new InputGestureRequest.MouseMove(4097, 0));
        assertThrows(IllegalArgumentException.class, () -> new InputGestureRequest.MouseDown(5));
        assertThrows(IllegalArgumentException.class, () -> new InputGestureRequest(2,
                List.of(new InputGestureRequest.MouseMove(1, 0))));
    }
    @Test void boundsLongTimelinesWaitTotalsAndSimultaneousKeys() {
        var moves = new java.util.ArrayList<InputGestureRequest.Step>();
        for (int i = 0; i < 256; i++) { moves.add(new InputGestureRequest.MouseMove(-4096, 4096)); }
        assertEquals(256, new InputGestureRequest(1, moves).steps().size());
        moves.add(new InputGestureRequest.MouseMove(0, 0));
        assertThrows(IllegalArgumentException.class, () -> new InputGestureRequest(1, moves));
        assertThrows(IllegalArgumentException.class, () -> new InputGestureRequest(1, List.of(
                new InputGestureRequest.MouseMove(1, 0), new InputGestureRequest.WaitTicks(10_000),
                new InputGestureRequest.WaitTicks(1))));
        var keys = new java.util.ArrayList<InputGestureRequest.Step>();
        for (int i = 0; i < 17; i++) { keys.add(new InputGestureRequest.KeyDown(i)); }
        for (int i = 0; i < 17; i++) { keys.add(new InputGestureRequest.KeyUp(i)); }
        assertThrows(IllegalArgumentException.class, () -> new InputGestureRequest(1, keys));
    }

    @Test void keyAndButtonZeroAreDistinctAndDuplicatePressesFail() {
        assertEquals(4, new InputGestureRequest(1, List.of(
                new InputGestureRequest.KeyDown(0), new InputGestureRequest.MouseDown(0),
                new InputGestureRequest.KeyUp(0), new InputGestureRequest.MouseUp(0))).steps().size());
        assertThrows(IllegalArgumentException.class, () -> new InputGestureRequest(1, List.of(
                new InputGestureRequest.MouseDown(0), new InputGestureRequest.MouseDown(0),
                new InputGestureRequest.MouseUp(0))));
    }

}
