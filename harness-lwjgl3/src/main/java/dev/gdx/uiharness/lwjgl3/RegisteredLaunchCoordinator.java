package dev.gdx.uiharness.lwjgl3;

import dev.gdx.uiharness.core.time.Deadline;
import java.time.Duration;
import java.util.concurrent.CompletionStage;

/**
 * Optional host-owned boundary for selecting an allowlisted LWJGL3 restart profile.
 *
 * <p>The host implementation owns all process-launch details. Callers provide only a registered
 * profile identifier and a deadline, and remain responsible for scheduling deadline expiry.
 */
@FunctionalInterface
public interface RegisteredLaunchCoordinator {
    CompletionStage<LaunchOutcome> restart(String registeredProfileId, Deadline deadline);

    /** Closed terminal outcome for a registered restart attempt. */
    sealed interface LaunchOutcome permits LaunchResult, LaunchFailure {}

    /** Terminal failures that do not expose replacement identities. */
    enum LaunchFailure implements LaunchOutcome {
        UNKNOWN_PROFILE,
        INCOMPATIBLE_APPLICATION,
        DEADLINE,
        CANCELLED
    }

    /** Immutable successful restart evidence containing identities, never launch instructions. */
    record LaunchResult(
            int schemaVersion,
            String profileId,
            String applicationId,
            String previousProcessId,
            String processId,
            String previousSessionId,
            String sessionId,
            Duration elapsed) implements LaunchOutcome {
        public LaunchResult {
            schemaVersion = LaunchProfile.supportedSchemaVersion(schemaVersion);
            profileId = LaunchProfile.identifier(profileId, "profileId");
            applicationId = LaunchProfile.identifier(applicationId, "applicationId");
            previousProcessId = LaunchProfile.identifier(previousProcessId, "previousProcessId");
            processId = LaunchProfile.identifier(processId, "processId");
            previousSessionId = LaunchProfile.identifier(previousSessionId, "previousSessionId");
            sessionId = LaunchProfile.identifier(sessionId, "sessionId");
            if (previousProcessId.equals(processId)) {
                throw new IllegalArgumentException("processId must identify a replacement process");
            }
            if (previousSessionId.equals(sessionId)) {
                throw new IllegalArgumentException("sessionId must identify a replacement session");
            }
            elapsed = LaunchProfile.timing(elapsed, "elapsed");
        }
    }
}
