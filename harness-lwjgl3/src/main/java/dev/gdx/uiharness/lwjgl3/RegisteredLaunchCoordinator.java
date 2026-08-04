package dev.gdx.uiharness.lwjgl3;

import dev.gdx.uiharness.core.scenario.ScenarioRequest;
import dev.gdx.uiharness.core.scenario.ScenarioResult;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * Optional host-owned boundary for transferring a validated scenario to a replacement host
 * context.
 *
 * <p>The host implementation privately owns launch, transport, command, and reconnect details.
 * Callers provide only the already bounded registered request.
 */
@FunctionalInterface
public interface RegisteredLaunchCoordinator {
    CompletionStage<HandoffOutcome> restart(ScenarioRequest request);

    /** Closed terminal outcome for a registered restart handoff. */
    sealed interface HandoffOutcome permits HandoffResult, HandoffFailure {}

    /** Terminal failures that do not expose replacement identities. */
    enum HandoffFailure implements HandoffOutcome {
        UNKNOWN_PROFILE,
        INCOMPATIBLE_APPLICATION,
        DEADLINE,
        CANCELLED
    }

    /** Terminal replacement result and the host's opaque bounded reconnect identity. */
    record HandoffResult(
            ScenarioResult scenario, String reconnectIdentity) implements HandoffOutcome {
        public HandoffResult {
            scenario = Objects.requireNonNull(scenario, "scenario");
            reconnectIdentity = LaunchProfile.identifier(
                    reconnectIdentity, "reconnectIdentity");
        }
    }
}
