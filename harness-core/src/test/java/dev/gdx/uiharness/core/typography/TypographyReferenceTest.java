package dev.gdx.uiharness.core.typography;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.gdx.uiharness.core.capture.CapturedImage;
import java.util.List;
import org.junit.jupiter.api.Test;

final class TypographyReferenceTest {
    private static final String PNG_SHA256 =
            "8f8cbb7dcf46e0bc7d53265749a6c17d116093a6ba95e442764060c76fd4a86c";

    @Test
    void preservesDeclaredControlOrder() {
        TypographyReference reference = reference(
                PNG_SHA256, List.of(control("title"), control("caption")));

        assertEquals(List.of("title", "caption"),
                reference.controlsById().keySet().stream().toList());
    }

    @Test
    void rejectsAHashThatDoesNotDescribeTheReferenceBytes() {
        assertThrows(IllegalArgumentException.class, () ->
                reference("0".repeat(64), List.of(control("title"))));
    }

    private static TypographyReference reference(
            String hash, List<TypographyControlReference> controls) {
        return new TypographyReference(
                "reference",
                "application",
                "viewport",
                "artifact",
                new byte[] {'p', 'n', 'g'},
                hash,
                1,
                1,
                new CapturedImage.Scale(1, 1),
                controls);
    }

    private static TypographyControlReference control(String id) {
        return new TypographyControlReference(
                id,
                "font",
                15,
                15,
                1,
                1,
                "Nearest",
                "Nearest",
                1,
                1,
                EvidenceValue.unavailable(UnavailableReason.UNSUPPORTED, "weight"),
                EvidenceValue.unavailable(UnavailableReason.UNSUPPORTED, "spacing"),
                new CoordinateBounds(CoordinateSpace.FRAMEBUFFER, 0, 0, 1, 1),
                0,
                0,
                0,
                0,
                0,
                "0".repeat(64));
    }
}
