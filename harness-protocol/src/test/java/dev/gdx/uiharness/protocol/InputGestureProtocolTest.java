package dev.gdx.uiharness.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

final class InputGestureProtocolTest {
    @Test void roundTripsMouseAndKeyboardTimelineAndRejectsUnbalancedInput() throws Exception {
        String json = """
                {"type":"input-gesture","schemaVersion":1,"steps":[
                {"kind":"key-down","keycode":51},
                {"kind":"mouse-move","deltaX":20,"deltaY":-10},
                {"kind":"mouse-down","button":0},
                {"kind":"wait-ticks","count":30},
                {"kind":"mouse-up","button":0},
                {"kind":"key-up","keycode":51}]}
                """;
        Command command = ProtocolJson.mapper().readValue(json, Command.class);
        assertEquals(command, ProtocolJson.mapper().readValue(
                ProtocolJson.mapper().writeValueAsString(command), Command.class));
        assertThrows(Exception.class, () -> ProtocolJson.mapper().readValue(
                json.replace("\"button\":0}", "\"button\":5}"), Command.class));
    }
}
