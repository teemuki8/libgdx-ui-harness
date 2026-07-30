package dev.gdx.uiharness.core.layout;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** Evaluates ordered completed-frame samples against a finite settling policy. */
public final class LayoutQuiescenceEvaluator {
    /** Returns settled only at the first declared consecutive-stability boundary. */
    public LayoutQuiescenceResult evaluate(
            List<LayoutStabilitySample> samples,
            Duration elapsed,
            LayoutQuiescencePolicy policy) {
        List<LayoutStabilitySample> values =
                List.copyOf(Objects.requireNonNull(samples, "samples"));
        Objects.requireNonNull(elapsed, "elapsed");
        Objects.requireNonNull(policy, "policy");
        int stable = values.isEmpty() ? 0 : 1;
        for (int index = 1; index < values.size(); index++) {
            stable = values.get(index).stableAfter(values.get(index - 1))
                    ? stable + 1 : 1;
            if (stable >= policy.consecutiveStableFrames()) {
                return new LayoutQuiescenceResult(
                        true, "settled", stable, elapsed, values.subList(0, index + 1));
            }
        }
        boolean timedOut = values.size() >= policy.maxFrames()
                || elapsed.compareTo(policy.maxDuration()) >= 0;
        return new LayoutQuiescenceResult(
                false,
                timedOut ? "not-stable" : "incomplete",
                stable,
                elapsed,
                values);
    }

    /** Verifies the five post-settle samples are identical one rendered frame apart. */
    public LayoutQuiescenceResult verifyCaptures(
            List<LayoutStabilitySample> samples,
            Duration elapsed,
            LayoutQuiescencePolicy policy) {
        List<LayoutStabilitySample> values = List.copyOf(samples);
        if (values.size() != policy.captureFrames()) {
            return new LayoutQuiescenceResult(
                    false, "incomplete", 0, elapsed, values);
        }
        int stable = 1;
        for (int index = 1; index < values.size(); index++) {
            if (!values.get(index).stableAfter(values.get(index - 1))) {
                return new LayoutQuiescenceResult(
                        false, "not-stable", stable, elapsed, values);
            }
            stable++;
        }
        return new LayoutQuiescenceResult(
                true, "settled", stable, elapsed, values);
    }
}
