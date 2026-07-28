package dev.gdx.uiharness.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.gdx.uiharness.core.action.Action;
import dev.gdx.uiharness.core.action.Harness;
import dev.gdx.uiharness.core.locator.Locator;
import dev.gdx.uiharness.core.locator.TextMatch;
import dev.gdx.uiharness.core.model.Role;
import dev.gdx.uiharness.core.time.Deadline;
import java.time.Duration;
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
}
