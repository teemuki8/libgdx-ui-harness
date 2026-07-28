package dev.gdx.uiharness.protocol;

import java.util.Objects;

/**
 * One bounded protocol request. The relative deadline begins when the service accepts the request
 * and therefore includes routing and backend queue time.
 *
 * @param version requested protocol version
 * @param sessionId target session identifier
 * @param requestId caller-generated correlation identifier
 * @param deadlineMillis positive relative deadline in milliseconds
 * @param command explicit allowlisted command
 */
public record HarnessRequest(
        ProtocolVersion version,
        String sessionId,
        String requestId,
        long deadlineMillis,
        Command command) {
    /** Maximum accepted relative deadline. */
    public static final long MAX_DEADLINE_MILLIS = 120_000;

    /** Validates all request envelope fields before execution. */
    public HarnessRequest {
        version = Objects.requireNonNull(version, "version");
        ProtocolJson.requireIdentifier(sessionId, "sessionId");
        ProtocolJson.requireIdentifier(requestId, "requestId");
        if (deadlineMillis <= 0 || deadlineMillis > MAX_DEADLINE_MILLIS) {
            throw new IllegalArgumentException(
                    "deadlineMillis must be between 1 and " + MAX_DEADLINE_MILLIS);
        }
        command = Objects.requireNonNull(command, "command");
    }
}
