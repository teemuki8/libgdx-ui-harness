package dev.gdx.uiharness.agentruntime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValue;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValues;
import org.junit.jupiter.api.Test;

final class RuntimeValueRendererTest {
    @Test
    void rendersEveryScalarVariantCanonically() {
        assertEquals("", RuntimeValueRenderer.render(RuntimeValues.nullValue()));
        assertEquals("true", RuntimeValueRenderer.render(RuntimeValues.bool(true)));
        assertEquals("false", RuntimeValueRenderer.render(RuntimeValues.bool(false)));
        assertEquals("-42", RuntimeValueRenderer.render(RuntimeValues.integer(-42)));
        assertEquals("0", RuntimeValueRenderer.render(RuntimeValues.decimal("0.00")));
        assertEquals("12.5", RuntimeValueRenderer.render(RuntimeValues.decimal("12.50")));
        assertEquals("Ada", RuntimeValueRenderer.render(RuntimeValues.string("Ada")));
        assertEquals("LOGIN", RuntimeValueRenderer.render(RuntimeValues.enumValue("LOGIN")));
    }

    @Test
    void rendersVectorAsParenthesizedDoubles() {
        assertEquals("(1.5, -2.0)",
                RuntimeValueRenderer.render(RuntimeValues.vector2(1.5, -2.0)));
    }

    @Test
    void rendersListsAndObjectsDeterministically() {
        assertEquals("[a, b]", RuntimeValueRenderer.render(
                RuntimeValues.list(RuntimeValues.string("a"), RuntimeValues.string("b"))));
        assertEquals("{age=3, name=Ada}", RuntimeValueRenderer.render(
                RuntimeValues.object(
                        RuntimeValues.field("name", RuntimeValues.string("Ada")),
                        RuntimeValues.field("age", RuntimeValues.integer(3)))));
    }

    @Test
    void truncatesDeepNestingWithMarker() {
        // Six levels of four-item lists stay inside the depth (8) and item (32) bounds,
        // while the natural expansion far exceeds 253 characters; only the length cap
        // applies, so truncation must yield exactly 256 characters ending with "...".
        RuntimeValue nested = RuntimeValues.string("deep");
        for (int level = 0; level < 6; level++) {
            RuntimeValue child = nested;
            nested = RuntimeValues.list(child, child, child, child);
        }
        String rendered = RuntimeValueRenderer.render(nested);
        assertEquals(256, rendered.length(), "deep output must be length-capped");
        assertTrue(rendered.endsWith("..."), "truncation must carry an explicit marker");
    }
}
