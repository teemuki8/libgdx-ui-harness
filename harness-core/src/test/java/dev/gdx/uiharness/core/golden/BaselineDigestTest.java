package dev.gdx.uiharness.core.golden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import dev.gdx.uiharness.core.model.Bounds;
import dev.gdx.uiharness.core.model.Role;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class BaselineDigestTest {
    private static final BaselineNode ROOT = new BaselineNode(
            Role.GROUP, "root", null, null, null, null, null,
            null, null, null, null, null, null, null, null,
            null, null, Map.of(), List.of());

    @Test void unpairedSurrogateCodeUnitsHashLosslessly() {
        BaselineNode high = nodeWithText(new String(new char[] {0xD800}));
        BaselineNode low = nodeWithText(new String(new char[] {0xD801}));

        assertNotEquals(
                SemanticBaseline.registered(1, 0, "r", high, false).digest(),
                SemanticBaseline.registered(1, 0, "r", low, false).digest(),
                "unpaired surrogate code units must not be replaced by the encoding");
    }

    @Test void signedZeroBoundsAreDistinguished() {
        BaselineNode positiveZero = nodeWithBounds(new Bounds(0.0, 0.0, 1.0, 1.0));
        BaselineNode negativeZero = nodeWithBounds(new Bounds(-0.0, 0.0, 1.0, 1.0));

        assertNotEquals(
                SemanticBaseline.registered(1, 0, "r", positiveZero, false).digest(),
                SemanticBaseline.registered(1, 0, "r", negativeZero, false).digest(),
                "IEEE-754 bits must distinguish signed zero");
    }

    @Test void extremeExponentBoundsAreDistinguished() {
        BaselineNode huge = nodeWithBounds(new Bounds(1.0E300, 0.0, 1.0, 1.0));
        BaselineNode tiny = nodeWithBounds(new Bounds(1.0E-300, 0.0, 1.0, 1.0));

        assertNotEquals(
                SemanticBaseline.registered(1, 0, "r", huge, false).digest(),
                SemanticBaseline.registered(1, 0, "r", tiny, false).digest(),
                "bounds exponents must participate through their IEEE-754 bits");
    }

    @Test void everyBoundsComponentParticipates() {
        BaselineNode base = nodeWithBounds(new Bounds(1.0, 2.0, 3.0, 4.0));
        BaselineNode taller = nodeWithBounds(new Bounds(1.0, 2.0, 3.0, 5.0));

        assertNotEquals(
                SemanticBaseline.registered(1, 0, "r", base, false).digest(),
                SemanticBaseline.registered(1, 0, "r", taller, false).digest(),
                "a change in the last bounds component must change the digest");
    }

    @Test void identifierContentIsFramedSeparately() {
        SemanticBaseline embedded =
                SemanticBaseline.registered(1, 0, "x\nstrictNodes=false", ROOT, true);
        SemanticBaseline plain = SemanticBaseline.registered(1, 0, "x", ROOT, true);

        assertNotEquals(embedded.digest(), plain.digest(),
                "identifier text must be length-framed, never merged with fields");
    }

    @Test void nullRoleBaselineDigestsStably() {
        SemanticBaseline first =
                SemanticBaseline.registered(1, 0, "null-role", nodeWithRole(null), false);
        SemanticBaseline second =
                SemanticBaseline.registered(1, 0, "null-role", nodeWithRole(null), false);

        assertEquals(first.digest(), second.digest(),
                "a null role must encode to a stable marker, not throw");
        assertNotEquals(first.digest(),
                SemanticBaseline.registered(1, 0, "null-role", ROOT, false).digest(),
                "the null role marker must not collide with a named role");
    }

    private static BaselineNode nodeWithRole(Role role) {
        return new BaselineNode(role, "root", null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, Map.of(), List.of());
    }

    private static BaselineNode nodeWithText(String text) {
        return new BaselineNode(Role.GROUP, "root", text, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, Map.of(), List.of());
    }

    private static BaselineNode nodeWithBounds(Bounds bounds) {
        return new BaselineNode(Role.GROUP, "root", null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                bounds, null, Map.of(), List.of());
    }
}
