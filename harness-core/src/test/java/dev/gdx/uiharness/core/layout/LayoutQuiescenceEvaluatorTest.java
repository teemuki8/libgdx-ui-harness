package dev.gdx.uiharness.core.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

final class LayoutQuiescenceEvaluatorTest {
    private final LayoutQuiescenceEvaluator evaluator = new LayoutQuiescenceEvaluator();
    private final LayoutQuiescencePolicy policy = LayoutQuiescencePolicy.issueFour();

    @Test
    void settlesAtThreeConsecutiveCompletedFrames() {
        List<LayoutStabilitySample> samples = List.of(
                sample(1, "moving", true),
                sample(2, "stable", false),
                sample(3, "stable", false),
                sample(4, "stable", false),
                sample(5, "unreachable", false));

        LayoutQuiescenceResult result =
                evaluator.evaluate(samples, Duration.ofMillis(64), policy);

        assertTrue(result.settled());
        assertEquals(3, result.stableFrameCount());
        assertEquals(4, result.samples().size());
    }

    @Test
    void timesOutAtTheFixedFrameBoundWithoutAcceptingLastFrame() {
        List<LayoutStabilitySample> samples = IntStream.rangeClosed(1, 120)
                .mapToObj(frame -> sample(frame, "frame-" + frame, false))
                .toList();

        LayoutQuiescenceResult result =
                evaluator.evaluate(samples, Duration.ofMillis(1_920), policy);

        assertFalse(result.settled());
        assertEquals("not-stable", result.status());
        assertEquals(120, result.samples().size());
    }

    @Test
    void fivePostSettleCapturesMustAllMatchOneFrameApart() {
        List<LayoutStabilitySample> stable = IntStream.rangeClosed(20, 24)
                .mapToObj(frame -> sample(frame, "stable", false))
                .toList();
        List<LayoutStabilitySample> drift = IntStream.rangeClosed(20, 24)
                .mapToObj(frame -> sample(
                        frame, frame == 22 ? "one-pixel-drift" : "stable", false))
                .toList();

        assertTrue(evaluator.verifyCaptures(
                stable, Duration.ofMillis(80), policy).settled());
        assertEquals("not-stable", evaluator.verifyCaptures(
                drift, Duration.ofMillis(80), policy).status());
    }

    @Test
    void activeSmoothScrollCannotCountAsStable() {
        List<LayoutStabilitySample> samples = List.of(
                sample(1, "same", true),
                sample(2, "same", true),
                sample(3, "same", true));

        LayoutQuiescenceResult result =
                evaluator.evaluate(samples, Duration.ofMillis(48), policy);

        assertFalse(result.settled());
        assertEquals("incomplete", result.status());
    }

    @Test
    void controlledSmoothScrollToggleChangesOnlyActiveMotionClassification() {
        List<LayoutStabilitySample> enabled = IntStream.rangeClosed(1, 3)
                .mapToObj(frame -> sample(frame, "same", true))
                .toList();
        List<LayoutStabilitySample> disabled = IntStream.rangeClosed(1, 3)
                .mapToObj(frame -> sample(frame, "same", false))
                .toList();

        LayoutQuiescenceResult enabledResult =
                evaluator.evaluate(enabled, Duration.ofMillis(48), policy);
        LayoutQuiescenceResult disabledResult =
                evaluator.evaluate(disabled, Duration.ofMillis(48), policy);

        assertFalse(enabledResult.settled());
        assertTrue(disabledResult.settled());
    }

    private static LayoutStabilitySample sample(
            int frame, String identity, boolean active) {
        String digest = Integer.toHexString(identity.hashCode());
        return new LayoutStabilitySample(
                frame,
                7,
                11,
                0,
                300,
                0,
                300,
                "viewport",
                "content",
                "clip",
                digest,
                digest,
                active);
    }
}
