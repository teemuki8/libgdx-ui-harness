package dev.gdx.uiharness.protocol;

import dev.gdx.uiharness.core.action.Harness;
import dev.gdx.uiharness.core.capture.ScreenCapture;
import dev.gdx.uiharness.core.contract.StateActionContract;
import dev.gdx.uiharness.core.error.ErrorCode;
import dev.gdx.uiharness.core.error.ErrorEvidence;
import dev.gdx.uiharness.core.error.HarnessException;
import dev.gdx.uiharness.core.locator.LocatorEngine;
import dev.gdx.uiharness.core.scenario.ScenarioRegistry;
import dev.gdx.uiharness.core.scenario.ScenarioRequest;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
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
import java.util.Optional;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Routes validated V1 requests to transport-neutral core session operations. */
public final class HarnessProtocolService {
    private static final Pattern STACK_FRAME = Pattern.compile(
            "\\bat\\s+[A-Za-z_$][\\w.$]*(?:\\([^\\r\\n)]*\\))?");
    private static final Pattern FILE_URI = Pattern.compile(
            "(?i)\\bfile:(?:/{1,3}|\\\\{1,3})[^\\s,;\\\"'{}\\[\\]]+");
    private static final Pattern FILE_PATH = Pattern.compile(
            "(?:[A-Za-z]:\\\\|(?<![A-Za-z0-9:/])/(?!/))[^\\s,;\\\"'{}\\[\\]]+");
    private final Map<String, Session> sessions;
    private final Map<String, ContractProvider> contracts;
    private final Map<String, InspectCaptureCompareService> comparisons;
    private final Map<String, TypographyDiagnosticService> typographyDiagnostics;
    private final Map<String, LayoutDiagnosticService> layoutDiagnostics;
    private final MonotonicClock clock;
    private final Executor blockingExecutor;

    /** Creates a service over an immutable session registry. */
    public HarnessProtocolService(
            Map<String, Session> sessions, MonotonicClock clock, Executor blockingExecutor) {
        this(sessions, Map.of(), Map.of(), Map.of(), Map.of(), clock, blockingExecutor);
    }

    /** Creates a service with optional evaluator-complete contract providers by session ID. */
    public HarnessProtocolService(
            Map<String, Session> sessions,
            Map<String, ContractProvider> contracts,
            MonotonicClock clock,
            Executor blockingExecutor) {
        this(sessions, contracts, Map.of(), Map.of(), Map.of(), clock, blockingExecutor);
    }

    /** Creates a service with optional contract and inspect-compare providers by session ID. */
    public HarnessProtocolService(
            Map<String, Session> sessions,
            Map<String, ContractProvider> contracts,
            Map<String, InspectCaptureCompareService> comparisons,
            MonotonicClock clock,
            Executor blockingExecutor) {
        this(sessions, contracts, comparisons, Map.of(), Map.of(), clock, blockingExecutor);
    }

    /**
     * Creates a service with optional contract, visual comparison, and typography providers.
     */
    public HarnessProtocolService(
            Map<String, Session> sessions,
            Map<String, ContractProvider> contracts,
            Map<String, InspectCaptureCompareService> comparisons,
            Map<String, TypographyDiagnosticService> typographyDiagnostics,
            MonotonicClock clock,
            Executor blockingExecutor) {
        this(sessions, contracts, comparisons, typographyDiagnostics, Map.of(),
                clock, blockingExecutor);
    }

    /** Creates a service with all optional V1 diagnostic providers by session ID. */
    public HarnessProtocolService(
            Map<String, Session> sessions,
            Map<String, ContractProvider> contracts,
            Map<String, InspectCaptureCompareService> comparisons,
            Map<String, TypographyDiagnosticService> typographyDiagnostics,
            Map<String, LayoutDiagnosticService> layoutDiagnostics,
            MonotonicClock clock,
            Executor blockingExecutor) {
        Objects.requireNonNull(sessions, "sessions");
        LinkedHashMap<String, Session> copy = new LinkedHashMap<>();
        sessions.forEach((id, session) -> {
            ProtocolJson.requireIdentifier(id, "sessionId");
            copy.put(id, Objects.requireNonNull(session, "session"));
        });
        this.sessions = Map.copyOf(copy);
        Objects.requireNonNull(contracts, "contracts");
        LinkedHashMap<String, ContractProvider> contractCopy = new LinkedHashMap<>();
        contracts.forEach((id, provider) -> {
            ProtocolJson.requireIdentifier(id, "contract sessionId");
            if (!copy.containsKey(id)) {
                throw new IllegalArgumentException(
                        "contract provider has no matching session: " + id);
            }
            contractCopy.put(id, Objects.requireNonNull(provider, "contract provider"));
        });
        this.contracts = Map.copyOf(contractCopy);
        Objects.requireNonNull(comparisons, "comparisons");
        LinkedHashMap<String, InspectCaptureCompareService> comparisonCopy =
                new LinkedHashMap<>();
        comparisons.forEach((id, comparison) -> {
            ProtocolJson.requireIdentifier(id, "comparison sessionId");
            if (!copy.containsKey(id)) {
                throw new IllegalArgumentException(
                        "comparison service has no matching session: " + id);
            }
            comparisonCopy.put(
                    id, Objects.requireNonNull(comparison, "comparison service"));
        });
        this.comparisons = Map.copyOf(comparisonCopy);
        Objects.requireNonNull(typographyDiagnostics, "typographyDiagnostics");
        LinkedHashMap<String, TypographyDiagnosticService> typographyCopy =
                new LinkedHashMap<>();
        typographyDiagnostics.forEach((id, diagnostic) -> {
            ProtocolJson.requireIdentifier(id, "typography sessionId");
            if (!copy.containsKey(id)) {
                throw new IllegalArgumentException(
                        "typography service has no matching session: " + id);
            }
            typographyCopy.put(
                    id, Objects.requireNonNull(diagnostic, "typography service"));
        });
        this.typographyDiagnostics = Map.copyOf(typographyCopy);
        Objects.requireNonNull(layoutDiagnostics, "layoutDiagnostics");
        LinkedHashMap<String, LayoutDiagnosticService> layoutCopy = new LinkedHashMap<>();
        layoutDiagnostics.forEach((id, diagnostic) -> {
            ProtocolJson.requireIdentifier(id, "layout sessionId");
            if (!copy.containsKey(id)) {
                throw new IllegalArgumentException(
                        "layout service has no matching session: " + id);
            }
            layoutCopy.put(id, Objects.requireNonNull(diagnostic, "layout service"));
        });
        this.layoutDiagnostics = Map.copyOf(layoutCopy);
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
                            null, List.of())));
        }

        Deadline deadline = Deadline.after(clock, Duration.ofMillis(request.deadlineMillis()));
        RoutedOperation<?> operation;
        try {
            operation = route(request, deadline);
        } catch (Throwable routeFailure) {
            return CompletableFuture.completedFuture(failure(request,
                    translate(request, routeFailure)));
        }
        return response(request, operation);
    }

    @SuppressWarnings("unchecked")
    private static <T> CompletionStage<HarnessResponse> response(
            HarnessRequest request, RoutedOperation<?> operation) {
        return new ResponseFuture<>(
                request, (RoutedOperation<T>) operation);
    }

    private RoutedOperation<?> route(HarnessRequest request, Deadline deadline) {
        Command command = request.command();
        if (command instanceof Command.Sessions) {
            List<HarnessResponse.SessionInfo> catalog = sessions.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(entry -> new HarnessResponse.SessionInfo(entry.getKey(),
                            entry.getValue().capabilities().capabilities()))
                    .toList();
            return RoutedOperation.completed(new HarnessResponse.Result.Sessions(catalog));
        }

        Session session = sessions.get(request.sessionId());
        if (session == null) {
            throw new HarnessException(ErrorCode.SESSION_NOT_FOUND,
                    "Session not found: " + request.sessionId(), ErrorEvidence.empty());
        }
        if (command instanceof Command.Capabilities) {
            return RoutedOperation.completed(new HarnessResponse.Result.Capabilities(
                    session.capabilities().capabilities()));
        }
        if (command instanceof Command.ScenarioList) {
            if (session.scenarioRegistry().isEmpty()) {
                return RoutedOperation.completed(
                        new HarnessResponse.Result.ScenarioList(false, List.of()));
            }
            List<HarnessResponse.ScenarioDefinitionData> definitions =
                    session.scenarioRegistry().orElseThrow().definitions().stream()
                            .map(HarnessResponse.ScenarioDefinitionData::fromCore)
                            .toList();
            return RoutedOperation.completed(
                    new HarnessResponse.Result.ScenarioList(true, definitions));
        }
        if (command instanceof Command.ScenarioStart start) {
            if (session.scenarioRegistry().isEmpty()
                    || session.scenarioCoordinator().isEmpty()) {
                return RoutedOperation.completed(new HarnessResponse.Result.ScenarioStart(
                        new HarnessResponse.ScenarioStartOutcome.Unavailable()));
            }
            ScenarioRegistry.RegisteredScenario registered;
            try {
                registered =
                        session.scenarioRegistry().orElseThrow().require(start.scenarioId());
            } catch (IllegalArgumentException unknown) {
                return RoutedOperation.completed(new HarnessResponse.Result.ScenarioStart(
                        new HarnessResponse.ScenarioStartOutcome.Rejected("unknown-scenario")));
            }
            if (!registered.definition().supportedProfileIds().contains(start.profileId())) {
                return RoutedOperation.completed(new HarnessResponse.Result.ScenarioStart(
                        new HarnessResponse.ScenarioStartOutcome.Rejected("unsupported-profile")));
            }
            ScenarioRequest scenarioRequest = new ScenarioRequest(
                    dev.gdx.uiharness.core.scenario.ScenarioDefinition.SCHEMA_VERSION,
                    start.scenarioId(), start.seed(), start.configuration(), start.profileId(),
                    deadline);
            return RoutedOperation.map(
                    session.scenarioCoordinator().orElseThrow().start(scenarioRequest),
                    HarnessResponse.Result.ScenarioStart::new);
        }


        requireCapability(session, capability(command));
        if (command instanceof Command.Snapshot) {
            ContractProvider contract = contracts.get(request.sessionId());
            if (contract != null) {
                return RoutedOperation.map(
                        contract.snapshotWith(session.harness(), deadline),
                        evidence -> new HarnessResponse.Result.Snapshot(
                                HarnessResponse.SnapshotData.fromCore(
                                        evidence.snapshot(), evidence.contract())));
            }
            return RoutedOperation.map(session.harness().snapshot(deadline),
                    snapshot -> new HarnessResponse.Result.Snapshot(
                            HarnessResponse.SnapshotData.fromCore(snapshot)));
        }
        if (command instanceof Command.Query query) {
            return RoutedOperation.map(session.harness().snapshot(deadline),
                    snapshot -> HarnessResponse.Result.Query.fromCore(
                            session.locators().query(snapshot, query.locator().toCore())));
        }
        if (command instanceof Command.Action action) {
            return RoutedOperation.map(session.harness().perform(action.locator().toCore(),
                            action.action().toCore(), deadline),
                    HarnessResponse.Result.Action::fromCore);
        }
        if (command instanceof Command.Assert assertion) {
            return RoutedOperation.map(
                    session.waits().assertThat(assertion.toCore(deadline)),
                    result -> HarnessResponse.Result.Assertion.fromCore(assertion, result));
        }
        if (command instanceof Command.Wait wait) {
            return RoutedOperation.map(submitInterruptibly(() ->
                            session.waits().await(wait.locator().toCore(),
                                    wait.condition().toCore(), deadline)),
                    HarnessResponse.Result.Wait::fromCore);
        }
        if (command instanceof Command.Screenshot screenshot) {
            return RoutedOperation.map(session.capture().capture(screenshot.toCore(), deadline),
                    HarnessResponse.Result.Screenshot::fromCore);
        }
        if (command instanceof Command.InspectCompare compare) {
            InspectCaptureCompareService comparison = comparisons.get(request.sessionId());
            if (comparison == null) {
                throw new HarnessException(ErrorCode.UNSUPPORTED_CAPABILITY,
                        "Session does not support inspect-capture-compare",
                        ErrorEvidence.empty());
            }
            return RoutedOperation.map(
                    comparison.execute(compare.toCore(), deadline),
                    HarnessResponse.Result.InspectCompare::fromCore);
        }
        if (command instanceof Command.TypographyDiagnose typography) {
            TypographyDiagnosticService diagnostic =
                    typographyDiagnostics.get(request.sessionId());
            if (diagnostic == null) {
                throw new HarnessException(
                        ErrorCode.UNSUPPORTED_CAPABILITY,
                        "Session does not support typography diagnosis",
                        ErrorEvidence.empty());
            }
            return RoutedOperation.map(
                    diagnostic.execute(typography.toCore(), deadline),
                    HarnessResponse.Result.TypographyDiagnostic::fromCore);
        }
        if (command instanceof Command.LayoutDiagnose layout) {
            LayoutDiagnosticService diagnostic = layoutDiagnostics.get(request.sessionId());
            if (diagnostic == null) {
                throw new HarnessException(
                        ErrorCode.UNSUPPORTED_CAPABILITY,
                        "Session does not support layout diagnosis",
                        ErrorEvidence.empty());
            }
            return RoutedOperation.map(
                    diagnostic.execute(layout.toCore(), deadline),
                    HarnessResponse.Result.LayoutDiagnostic::fromCore);
        }
        if (command instanceof Command.TraceStart traceStart) {
            return RoutedOperation.map(session.traces().start(traceStart, deadline),
                    Function.identity());
        }
        if (command instanceof Command.TraceStop) {
            return RoutedOperation.map(session.traces().stop(deadline), Function.identity());
        }
        throw new AssertionError("unhandled sealed command " + command.getClass().getName());
    }

    private <T> CompletableFuture<T> submitInterruptibly(Supplier<T> operation) {
        InterruptibleOperationFuture<T> future = new InterruptibleOperationFuture<>(operation);
        blockingExecutor.execute(future);
        return future;
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
        if (command instanceof Command.Assert) {
            return "ui_assert";
        }
        if (command instanceof Command.Wait) {
            return "wait";
        }
        if (command instanceof Command.Screenshot) {
            return "screenshot";
        }
        if (command instanceof Command.InspectCompare) {
            return "compare";
        }
        if (command instanceof Command.TypographyDiagnose) {
            return "typography";
        }
        if (command instanceof Command.LayoutDiagnose) {
            return "layout";
        }
        if (command instanceof Command.ScenarioList) {
            return "scenario-list";
        }
        if (command instanceof Command.ScenarioStart) {
            return "scenario-start";
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
                    Map.of("reason", "cancelled"), null, List.of());
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
                    redactMap(evidence.details()), null,
                    evidence.suggestions().stream()
                            .map(HarnessProtocolService::toSuggestionSpec)
                            .toList());
        }
        String traceId = internalTraceId(request, failure);
        return new ProtocolError(ProtocolError.Code.INTERNAL_ERROR, "Internal harness failure",
                request.requestId(), request.sessionId(), null, 0, null, null, List.of(),
                Map.of(), traceId, List.of());
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

    private static LocatorSuggestionSpec toSuggestionSpec(
            dev.gdx.uiharness.core.locator.LocatorSuggestion suggestion) {
        return new LocatorSuggestionSpec(
                Command.LocatorSpec.fromCore(suggestion.locator()),
                suggestion.stability(),
                suggestion.rationale(),
                suggestion.candidateIdentity(),
                suggestion.distinctions().stream()
                        .map(distinction -> new DistinguishingPropertySpec(
                                distinction.field(), distinction.value()))
                        .toList());
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
        String withoutFileUris = FILE_URI.matcher(Objects.requireNonNull(value, "value"))
                .replaceAll("[redacted]");
        String withoutFrames = STACK_FRAME.matcher(withoutFileUris).replaceAll("[redacted]");
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

    private record RoutedOperation<T>(
            CompletableFuture<T> source,
            Function<? super T, ? extends HarnessResponse.Result> mapper) {
        RoutedOperation {
            source = Objects.requireNonNull(source, "source");
            mapper = Objects.requireNonNull(mapper, "mapper");
        }

        static <T> RoutedOperation<T> map(CompletionStage<T> source,
                Function<? super T, ? extends HarnessResponse.Result> mapper) {
            return new RoutedOperation<>(
                    Objects.requireNonNull(source, "source").toCompletableFuture(), mapper);
        }

        static <T extends HarnessResponse.Result> RoutedOperation<T> completed(T result) {
            return map(CompletableFuture.completedFuture(result), Function.identity());
        }
    }

    private static final class ResponseFuture<T> extends CompletableFuture<HarnessResponse> {
        private final Object lifecycle = new Object();
        private final HarnessRequest request;
        private final RoutedOperation<T> operation;
        private boolean cancelling;
        private Completion<T> deferred;

        ResponseFuture(HarnessRequest request, RoutedOperation<T> operation) {
            this.request = Objects.requireNonNull(request, "request");
            this.operation = Objects.requireNonNull(operation, "operation");
            operation.source().whenComplete(this::sourceCompleted);
        }

        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            synchronized (lifecycle) {
                if (isDone()) {
                    return false;
                }
                cancelling = true;
                boolean sourceCancelled;
                try {
                    sourceCancelled = operation.source().cancel(mayInterruptIfRunning);
                } finally {
                    cancelling = false;
                }
                if (sourceCancelled) {
                    deferred = null;
                    return super.cancel(false);
                }
                if (deferred != null) {
                    Completion<T> completion = deferred;
                    deferred = null;
                    completeResponse(completion.value(), completion.failure());
                }
                return false;
            }
        }

        private void sourceCompleted(T value, Throwable sourceFailure) {
            synchronized (lifecycle) {
                if (isDone()) {
                    return;
                }
                if (cancelling) {
                    deferred = new Completion<>(value, sourceFailure);
                    return;
                }
                completeResponse(value, sourceFailure);
            }
        }

        private void completeResponse(T value, Throwable sourceFailure) {
            HarnessResponse response;
            try {
                response = sourceFailure == null
                        ? new HarnessResponse.Success(
                                ProtocolVersion.V1, request.requestId(), request.sessionId(),
                                operation.mapper().apply(value))
                        : failure(request, translate(request, sourceFailure));
            } catch (Throwable mappingFailure) {
                response = failure(request, translate(request, mappingFailure));
            }
            super.complete(response);
        }

        private record Completion<T>(T value, Throwable failure) {}
    }

    private static final class InterruptibleOperationFuture<T>
            extends CompletableFuture<T> implements Runnable {
        private final Supplier<T> operation;
        private Thread runner;

        InterruptibleOperationFuture(Supplier<T> operation) {
            this.operation = Objects.requireNonNull(operation, "operation");
        }

        @Override public void run() {
            synchronized (this) {
                if (isDone()) {
                    return;
                }
                runner = Thread.currentThread();
            }
            try {
                super.complete(operation.get());
            } catch (Throwable failure) {
                super.completeExceptionally(failure);
            } finally {
                synchronized (this) {
                    runner = null;
                }
            }
        }

        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            Thread running;
            synchronized (this) {
                if (!super.cancel(false)) {
                    return false;
                }
                running = mayInterruptIfRunning ? runner : null;
            }
            if (running != null) {
                running.interrupt();
            }
            return true;
        }
    }

    /** Core operations and immutable protocol capabilities belonging to one selected session. */
    public record Session(
            Harness harness,
            LocatorEngine locators,
            WaitEngine waits,
            ScreenCapture capture,
            CapabilitySet capabilities,
            TraceController traces,
            Optional<ScenarioRegistry> scenarioRegistry,
            Optional<ScenarioCoordinator> scenarioCoordinator) {
        /** Retains source compatibility for sessions without scenario lifecycle registration. */
        public Session(
                Harness harness,
                LocatorEngine locators,
                WaitEngine waits,
                ScreenCapture capture,
                CapabilitySet capabilities,
                TraceController traces) {
            this(harness, locators, waits, capture, capabilities, traces,
                    Optional.empty(), Optional.empty());
        }

        /** Validates all required session operations and optional scenario registration. */
        public Session {
            harness = Objects.requireNonNull(harness, "harness");
            locators = Objects.requireNonNull(locators, "locators");
            waits = Objects.requireNonNull(waits, "waits");
            capture = Objects.requireNonNull(capture, "capture");
            capabilities = Objects.requireNonNull(capabilities, "capabilities");
            traces = Objects.requireNonNull(traces, "traces");
            scenarioRegistry = Objects.requireNonNull(scenarioRegistry, "scenarioRegistry");
            scenarioCoordinator =
                    Objects.requireNonNull(scenarioCoordinator, "scenarioCoordinator");
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

    /** Optional application-owned scenario execution boundary for one session. */
    @FunctionalInterface
    public interface ScenarioCoordinator {
        /** Starts one validated registered scenario and returns one closed terminal outcome. */
        CompletionStage<HarnessResponse.ScenarioStartOutcome> start(ScenarioRequest request);
    }

    /** Render-thread-safe source of the public evaluator-complete contract. */
    @FunctionalInterface
    public interface ContractProvider {
        /** Captures the resulting completed-frame contract before the monotonic deadline. */
        CompletionStage<StateActionContract> snapshot(Deadline deadline);

        /** Captures correlated semantic and contract evidence before the same deadline. */
        default CompletionStage<SnapshotEvidence> snapshotWith(
                Harness harness, Deadline deadline) {
            Objects.requireNonNull(harness, "harness");
            Objects.requireNonNull(deadline, "deadline");
            return harness.snapshot(deadline).thenCombine(
                    snapshot(deadline), SnapshotEvidence::new);
        }
    }

    /** One same-frame semantic snapshot and evaluator-complete contract pair. */
    public record SnapshotEvidence(
            SemanticSnapshot snapshot, StateActionContract contract) {
        /** Rejects mixed-frame evidence at its construction boundary. */
        public SnapshotEvidence {
            snapshot = Objects.requireNonNull(snapshot, "snapshot");
            contract = Objects.requireNonNull(contract, "contract");
            if (snapshot.revision() != contract.revision()
                    || snapshot.frame() != contract.frame()) {
                throw new IllegalArgumentException(
                        "semantic snapshot and state/action contract identities differ");
            }
        }
    }
}
