package dev.gdx.uiharness.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.gdx.uiharness.core.action.Action;
import dev.gdx.uiharness.core.action.Harness;
import dev.gdx.uiharness.core.gesture.ExactTickCoordinator;
import dev.gdx.uiharness.core.gesture.KeyboardGestureRequest;
import dev.gdx.uiharness.core.locator.Locator;
import dev.gdx.uiharness.core.locator.TextMatch;
import dev.gdx.uiharness.core.model.Role;
import dev.gdx.uiharness.core.time.Deadline;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

final class PublicApiExampleTest {
    @Test void documentedJavaFlowCompilesAndRuns() {
        try (FixtureHarness fixture = FixtureHarness.start()) {
            Harness ui = fixture.harness();
            ui.perform(Locator.role(Role.BUTTON).withName(TextMatch.exact("Save")),
                Action.click(), Deadline.after(fixture.clock(), Duration.ofSeconds(2)))
              .toCompletableFuture().join();
            assertEquals("saved", fixture.state());
        }
    }

    @Test void keyboardGestureAndOptionalTickContractsAreConstructible() {
        KeyboardGestureRequest request = new KeyboardGestureRequest(1, List.of(
                new KeyboardGestureRequest.KeyDown(29),
                new KeyboardGestureRequest.WaitTicks(3),
                new KeyboardGestureRequest.KeyUp(29)));
        ExactTickCoordinator ticks = new ExactTickCoordinator() {
            @Override public TickPreflight preflight(int count, Deadline deadline) {
                return new TickPreflight.Ready(16);
            }

            @Override public java.util.concurrent.CompletionStage<TickAdvanceResult> advance(
                    int count, Deadline deadline) {
                return CompletableFuture.completedFuture(new TickAdvanceResult.Completed(
                        new TickEvidence(
                                count, count, 4, 4 + count, 2,
                                java.util.OptionalLong.of(10),
                                java.util.OptionalLong.of(10 + count - 1),
                                java.util.OptionalLong.empty(),
                                java.util.OptionalLong.empty(),
                                Duration.ofMillis(16).toNanos())));
            }
        };

        assertEquals(3, request.steps().size());
        assertEquals(16, ((ExactTickCoordinator.TickPreflight.Ready) ticks.preflight(
                3, Deadline.after(System::nanoTime, Duration.ofSeconds(1))))
                .maximumTicks());
    }
}
