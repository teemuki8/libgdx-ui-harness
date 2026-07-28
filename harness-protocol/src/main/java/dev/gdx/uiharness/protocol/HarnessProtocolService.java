package dev.gdx.uiharness.protocol;

import dev.gdx.uiharness.core.action.Harness;
import dev.gdx.uiharness.core.capture.ScreenCapture;
import dev.gdx.uiharness.core.error.ErrorCode;
import dev.gdx.uiharness.core.error.ErrorEvidence;
import dev.gdx.uiharness.core.error.HarnessException;
import dev.gdx.uiharness.core.locator.LocatorEngine;
import dev.gdx.uiharness.core.time.Deadline;
import dev.gdx.uiharness.core.time.MonotonicClock;
import dev.gdx.uiharness.core.wait.WaitEngine;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

/** Routes validated V1 requests to transport-neutral core session operations. */
public final class HarnessProtocolService {
    private static final Pattern STACK_FRAME = Pattern.compile(
            "\\bat\\s+[A-Za-z_$][\\w.$]*(?:\\([^\\r\\n)]*\\))?");
    private static final Pattern FILE_PATH = Pattern.compile(
            "(?:[A-Za-z]:\\\\|(?<![A-Za-z0-9:/])/(?!/))[^\\s,;\\\"'{}\\[\\]]+");
    private final Map<String, Session> sessions;
    private final MonotonicClock clock;
    private final Executor blockingExecutor;

    /** Creates a service over an immutable session registry. */
    public HarnessProtocolService(
            Map<String, Session> sessions, MonotonicClock clock, Executor blockingExecutor) {
        Objects.requireNonNull(sessions, "sessions");
        LinkedHashMap<String, Session> copy = new LinkedHashMap<>();
        sessions.forEach((id, session) -> {
            ProtocolJson.requireIdentifier(id, "sessionId");
            copy.put(id, Objects.requireNonNull(session, "session"));
        });
        this.sessions = Map.copyOf(copy);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.blockingExecutor = Objects.requireNonNull(blockingExecutor, "blockingExecutor");
    }

    /**
     * Executes one validated request and always completes normally with an explicit success or
     * failure response, including for cancellation and unexpected backend failures.
     */
    public CompletionStage<HarnessResponse> execute(HarnessRequest request) {
        Objects.requireNonNull(request, "request");
        if (!ProtocolVersion.V1.equals(request.version())) {
            return CompletableFuture.completedFuture(failure(request,
                    new ProtocolError(ProtocolError.Code.PROTOCOL_VERSION_MISMATCH,
                            "Unsupported protocol version " + request.version(),
                            request.requestId(), request.sessionId(), null, 0, null, null,
                            List.of(), Map.of("supportedVersion", ProtocolVersion.V1.toString()),
                            null)));
        }

        Deadline deadline = Deadline.after(clock, Duration.ofMillis(request.deadlineMillis()));
        CompletionStage<HarnessResponse.Result> operation;
        try {
            operation = route(request, deadline);
        } catch (Throwable failure) {
            return CompletableFuture.completedFuture(failure(request,
                    translate(request, failure)));
        }
        return operation.handle((result, thrown) -> thrown == null
                ? new HarnessResponse.Success(ProtocolVersion.V1, request.requestId(),
                        request.sessionId(), result)
                : failure(request, translate(request, thrown)));
    }

    private CompletionStage<HarnessResponse.Result> route(
            HarnessRequest request, Deadline deadline) {
        Command command = request.command();
        if (command instanceof Command.Sessions) {
            List<HarnessResponse.SessionInfo> catalog = sessions.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(entry -> new HarnessResponse.SessionInfo(entry.getKey(),
                            entry.getValue().capabilities().capabilities()))
                    .toList();
            return CompletableFuture.completedFuture(
                    new HarnessResponse.Result.Sessions(catalog));
        }

        Session session = sessions.get(request.sessionId());
        if (session == null) {
            throw new HarnessException(ErrorCode.SESSION_NOT_FOUND,
                    "Session not found: " + request.sessionId(), ErrorEvidence.empty());
        }
        if (command instanceof Command.Capabilities) {
            return CompletableFuture.completedFuture(new HarnessResponse.Result.Capabilities(
                    session.capabilities().capabilities()));
        }

        requireCapability(session, capability(command));
        if (command instanceof Command.Snapshot) {
            return session.harness().snapshot(deadline)
                    .thenApply(snapshot -> new HarnessResponse.Result.Snapshot(
                            HarnessResponse.SnapshotData.fromCore(snapshot)));
        }
        if (command instanceof Command.Query query) {
            return session.harness().snapshot(deadline)
                    .thenApply(snapshot -> HarnessResponse.Result.Query.fromCore(
                            session.locators().query(snapshot, query.locator().toCore())));
        }
        if (command instanceof Command.Action action) {
            return session.harness().perform(action.locator().toCore(),
                            action.action().toCore(), deadline)
                    .thenApply(HarnessResponse.Result.Action::fromCore);
        }
        if (command instanceof Command.Wait wait) {
            return CompletableFuture.supplyAsync(() -> HarnessResponse.Result.Wait.fromCore(
                    session.waits().await(wait.locator().toCore(),
                            wait.condition().toCore(), deadline)), blockingExecutor);
        }
        if (command instanceof Command.Screenshot screenshot) {
            return session.capture().capture(screenshot.toCore(), deadline)
                    .thenApply(HarnessResponse.Result.Screenshot::fromCore);
        }
        if (command instanceof Command.TraceStart traceStart) {
            return session.traces().start(traceStart, deadline)
                    .thenApply(result -> result);
        }
        if (command instanceof Command.TraceStop) {
            return session.traces().stop(deadline)
                    .thenApply(result -> result);
        }
        throw new AssertionError("unhandled sealed command " + command.getClass().getName());
    }

    private static String capability(Command command) {
        if (command instanceof Command.Snapshot) {
            return "snapshot";
        }
        if (command instanceof Command.Query) {
            return "query";
        }
        if (command instanceof Command.Action) {
            return "action";
        }
        if (command instanceof Command.Wait) {
            return "wait";
        }
        if (command instanceof Command.Screenshot) {
            return "screenshot";
        }
        return "trace";
    }

    private static void requireCapability(Session session, String capability) {
        if (!session.capabilities().supports(capability)) {
            throw new HarnessException(ErrorCode.UNSUPPORTED_CAPABILITY,
                    "Session does not support capability: " + capability,
                    ErrorEvidence.ofDetails(Map.of("capability", capability)));
        }
    }

    private static HarnessResponse.Failure failure(
            HarnessRequest request, ProtocolError error) {
        return new HarnessResponse.Failure(ProtocolVersion.V1, request.requestId(),
                request.sessionId(), error);
    }

    private static ProtocolError translate(HarnessRequest request, Throwable thrown) {
        Throwable failure = unwrap(thrown);
        if (failure instanceof CancellationException) {
            return new ProtocolError(ProtocolError.Code.TIMEOUT, "Request was cancelled",
                    request.requestId(), request.sessionId(), null, 0, null, null, List.of(),
                    Map.of("reason", "cancelled"), null);
        }
        if (failure instanceof HarnessException harnessFailure) {
            ErrorEvidence evidence = harnessFailure.evidence();
            return new ProtocolError(ProtocolError.Code.fromCore(harnessFailure.code()),
                    redact(harnessFailure.getMessage()), request.requestId(), request.sessionId(),
                    redactNullable(evidence.locator().orElse(null)),
                    elapsedMillis(evidence.elapsed()),
                    evidence.lastSnapshotRevision().isPresent()
                            ? evidence.lastSnapshotRevision().getAsLong() : null,
                    redactNullable(evidence.traceReference().orElse(null)),
                    evidence.candidates().stream().map(HarnessProtocolService::redactMap).toList(),
                    redactMap(evidence.details()), null);
        }
        String traceId = internalTraceId(request, failure);
        return new ProtocolError(ProtocolError.Code.INTERNAL_ERROR, "Internal harness failure",
                request.requestId(), request.sessionId(), null, 0, null, null, List.of(),
                Map.of(), traceId);
    }

    private static Throwable unwrap(Throwable thrown) {
        Throwable current = thrown;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static long elapsedMillis(Duration duration) {
        try {
            return duration.toMillis();
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private static Map<String, String> redactMap(Map<String, String> source) {
        LinkedHashMap<String, String> redacted = new LinkedHashMap<>();
        source.forEach((key, value) -> redacted.put(redact(key), redact(value)));
        return Map.copyOf(redacted);
    }

    private static String redactNullable(String value) {
        return value == null ? null : redact(value);
    }

    private static String redact(String value) {
        String withoutFrames = STACK_FRAME.matcher(Objects.requireNonNull(value, "value"))
                .replaceAll("[redacted]");
        return FILE_PATH.matcher(withoutFrames).replaceAll("[redacted]");
    }

    private static String internalTraceId(HarnessRequest request, Throwable failure) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(request.requestId().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(request.sessionId().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(failure.getClass().getName().getBytes(StandardCharsets.UTF_8));
            return "internal-" + HexFormat.of().formatHex(digest.digest(), 0, 8);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 unavailable", impossible);
        }
    }

    /** Core operations and immutable protocol capabilities belonging to one selected session. */
    public record Session(
            Harness harness,
            LocatorEngine locators,
            WaitEngine waits,
            ScreenCapture capture,
            CapabilitySet capabilities,
            TraceController traces) {
        /** Validates all required session operations. */
        public Session {
            harness = Objects.requireNonNull(harness, "harness");
            locators = Objects.requireNonNull(locators, "locators");
            waits = Objects.requireNonNull(waits, "waits");
            capture = Objects.requireNonNull(capture, "capture");
            capabilities = Objects.requireNonNull(capabilities, "capabilities");
            traces = Objects.requireNonNull(traces, "traces");
        }
    }

    /** Transport-neutral trace lifecycle; artifact persistence is supplied by a later module. */
    public interface TraceController {
        /** Starts one bounded trace. */
        CompletionStage<HarnessResponse.Result.TraceStarted> start(
                Command.TraceStart command, Deadline deadline);

        /** Stops the active trace. */
        CompletionStage<HarnessResponse.Result.TraceStopped> stop(Deadline deadline);

        /** Returns an implementation that reports the typed unsupported-capability failure. */
        static TraceController unsupported() {
            return new TraceController() {
                @Override public CompletionStage<HarnessResponse.Result.TraceStarted> start(
                        Command.TraceStart command, Deadline deadline) {
                    return CompletableFuture.failedFuture(unsupportedFailure());
                }

                @Override public CompletionStage<HarnessResponse.Result.TraceStopped> stop(
                        Deadline deadline) {
                    return CompletableFuture.failedFuture(unsupportedFailure());
                }

                private HarnessException unsupportedFailure() {
                    return new HarnessException(ErrorCode.UNSUPPORTED_CAPABILITY,
                            "Trace collection is unavailable", ErrorEvidence.empty());
                }
            };
        }
    }
}
