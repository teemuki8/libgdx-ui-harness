package dev.gdx.uiharness.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gdx.uiharness.core.error.ErrorCode;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

final class ProtocolErrorCodeContractTest {
    private static final Pattern KEBAB_CASE = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

    @Test void renderThreadViolationUsesExactWireName() {
        ProtocolError.Code violation = ProtocolError.Code.valueOf("RENDER_THREAD_VIOLATION");
        assertEquals("render-thread-violation", violation.wireName());
        assertEquals(violation, ProtocolError.Code.fromWireName("render-thread-violation"));
    }

    @Test void fromCoreMapsRenderThreadViolation() {
        ProtocolError.Code violation = ProtocolError.Code.valueOf("RENDER_THREAD_VIOLATION");
        assertEquals(violation,
                ProtocolError.Code.fromCore(ErrorCode.valueOf("RENDER_THREAD_VIOLATION")));
    }

    @Test void fromWireNameRejectsMisspelledRenderThreadViolation() {
        for (String misspelled : List.of(
                "render_thread_violation",
                "render-thread-violation-extra",
                "Render-Thread-Violation",
                "render-thread-violation ")) {
            assertThrows(IllegalArgumentException.class,
                    () -> ProtocolError.Code.fromWireName(misspelled), misspelled);
        }
    }

    @Test void everyCoreCodeMapsToAProtocolCodeAndBack() {
        for (ErrorCode core : ErrorCode.values()) {
            ProtocolError.Code protocol = ProtocolError.Code.fromCore(core);
            assertEquals(core.name(), protocol.name());
            assertEquals(protocol, ProtocolError.Code.fromWireName(protocol.wireName()));
        }
        for (ProtocolError.Code protocol : ProtocolError.Code.values()) {
            ErrorCode core = ErrorCode.valueOf(protocol.name());
            assertEquals(protocol, ProtocolError.Code.fromCore(core));
        }
    }

    @Test void everyProtocolWireNameIsUniqueKebabCase() {
        Set<String> wireNames = new HashSet<>();
        for (ProtocolError.Code code : ProtocolError.Code.values()) {
            assertTrue(wireNames.add(code.wireName()), "duplicate wire name " + code.wireName());
            assertTrue(KEBAB_CASE.matcher(code.wireName()).matches(), code.wireName());
        }
    }

    @Test void renderThreadViolationErrorRoundTripsOnTheWire() throws Exception {
        ProtocolError error = new ProtocolError(
                ProtocolError.Code.valueOf("RENDER_THREAD_VIOLATION"),
                "Scene2D session access requires the owning render thread",
                "req-error", "game", null, 0, null, null,
                List.of(), Map.of("operation", "snapshot"), null, List.of());

        String json = ProtocolJson.mapper().writeValueAsString(error);
        assertTrue(json.contains("\"code\":\"render-thread-violation\""), json);
        assertTrue(json.contains("\"details\":{\"operation\":\"snapshot\"}"), json);
        assertFalse(json.contains("render-thread-failure"), json);

        ProtocolError decoded = ProtocolJson.mapper().readValue(json, ProtocolError.class);
        assertEquals(error, decoded);
        assertEquals(json, ProtocolJson.mapper().writeValueAsString(decoded));
    }
}
