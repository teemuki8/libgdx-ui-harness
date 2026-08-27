package dev.gdx.uiharness.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.gdx.uiharness.protocol.BinaryAttachment;
import dev.gdx.uiharness.protocol.Command;
import dev.gdx.uiharness.protocol.DiagnosticCode;
import dev.gdx.uiharness.protocol.DiagnosticEnvelope;
import dev.gdx.uiharness.protocol.HarnessProtocolService;
import dev.gdx.uiharness.protocol.HarnessRequest;
import dev.gdx.uiharness.protocol.HarnessResponse;
import dev.gdx.uiharness.protocol.LocatorSuggestionSpec;
import dev.gdx.uiharness.protocol.ProtocolError;
import dev.gdx.uiharness.protocol.ProtocolJson;
import dev.gdx.uiharness.protocol.ProtocolVersion;
import dev.gdx.uiharness.protocol.RecoveryWorkflow;
import dev.gdx.uiharness.core.typography.AffineTransformObservation;
import dev.gdx.uiharness.core.typography.CoordinateBounds;
import dev.gdx.uiharness.core.typography.CoordinatePoint;
import dev.gdx.uiharness.core.typography.EvidenceValue;
import dev.gdx.uiharness.core.typography.GlyphRunObservation;
import dev.gdx.uiharness.core.typography.TypographyDiagnostic;
import dev.gdx.uiharness.core.typography.TypographyReport;
import io.modelcontextprotocol.spec.McpSchema;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.LongSupplier;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/** Translates each MCP tool call into exactly one transport-neutral protocol request. */
public final class HarnessToolHandler implements AutoCloseable {
    private static final long DEFAULT_DEADLINE_MILLIS = 30_000;
    private static final int DEFAULT_ARTIFACT_THRESHOLD_BYTES = 64 * 1_024;
    static final int MAX_LOCATOR_DEPTH = ProtocolJson.MAX_NESTING_DEPTH / 2;
    static final int MAX_LOCATOR_NODES = ProtocolJson.MAX_REQUEST_BYTES / 256;
    private static final ObjectMapper COMMAND_MAPPER = ProtocolJson.mapper();
    private static final java.util.logging.Logger ARTIFACT_LOGGER =
            java.util.logging.Logger.getLogger("dev.gdx.uiharness.mcp.ArtifactPublisher");

    /** Internal protocol source carrying raw capture attachments for direct publication. */
    @FunctionalInterface
    interface ExecutionSource {
        CompletionStage<HarnessProtocolService.Execution> apply(HarnessRequest request);
    }

    private final ExecutionSource protocol;
    private final ArtifactReference.Publisher artifacts;
    private final ArtifactReference.Reader artifactReader;
    private final ExecutorService executor;
    private final Scheduler scheduler;
    private final int artifactThresholdBytes;
    private final LongSupplier nanoClock;
    private final RequestAdmission admission;
    private final HarnessToolCatalog catalog = new HarnessToolCatalog();
    private final AtomicLong requestSequence = new AtomicLong();
    private final RecoveryAccounting diagnosticAccounting;
    private final RecoveryAccounting sessionAccounting;
    /**
     * Bounded per-workflow fingerprint index: session workflow generation token
     * to the distinct fingerprint keys it recorded. Total registered keys are
     * capped by {@link RecoveryAccounting#MAX_ENTRIES} and idle workflows
     * expire against the same TTL clock as the accounting stores, so a stale
     * completion can only ever release the fingerprints it recorded.
     * Guarded by {@code workflows}.
     */
    private final Map<Long, WorkflowFingerprints> workflows = new HashMap<>();
    private int registeredFingerprints;

    /** Creates a handler that owns a Java 25 virtual-thread executor and default admission. */
    public HarnessToolHandler(
            HarnessProtocolService protocol, ArtifactReference.Publisher artifacts) {
        this(Objects.requireNonNull(protocol, "protocol")::executeWithAttachments, artifacts,
                Executors.newVirtualThreadPerTaskExecutor(), DEFAULT_ARTIFACT_THRESHOLD_BYTES,
                System::nanoTime, RequestAdmission.serverDefaults());
    }

    /** Creates a handler with an application-owned artifact publisher and reader. */
    public HarnessToolHandler(HarnessProtocolService protocol,
            ArtifactReference.Publisher artifacts, ArtifactReference.Reader artifactReader) {
        this(Objects.requireNonNull(protocol, "protocol")::executeWithAttachments, artifacts,
                artifactReader, Executors.newVirtualThreadPerTaskExecutor(),
                DEFAULT_ARTIFACT_THRESHOLD_BYTES, System::nanoTime,
                RequestAdmission.serverDefaults(), RecoveryAccounting.MAX_ENTRIES);
    }

    /** Creates a handler for the server with the server-scoped admission. */
    HarnessToolHandler(HarnessProtocolService protocol, ArtifactReference.Publisher artifacts,
            RequestAdmission admission) {
        this(Objects.requireNonNull(protocol, "protocol")::executeWithAttachments, artifacts,
                ArtifactReference.Reader.unavailable(),
                Executors.newVirtualThreadPerTaskExecutor(), DEFAULT_ARTIFACT_THRESHOLD_BYTES,
                System::nanoTime, admission, RecoveryAccounting.MAX_ENTRIES);
    }

    /** Creates a handler for the server with reader and server-scoped admission. */
    HarnessToolHandler(HarnessProtocolService protocol, ArtifactReference.Publisher artifacts,
            ArtifactReference.Reader artifactReader, RequestAdmission admission) {
        this(Objects.requireNonNull(protocol, "protocol")::executeWithAttachments, artifacts,
                artifactReader, Executors.newVirtualThreadPerTaskExecutor(),
                DEFAULT_ARTIFACT_THRESHOLD_BYTES, System::nanoTime, admission,
                RecoveryAccounting.MAX_ENTRIES);
    }

    HarnessToolHandler(Function<HarnessRequest, CompletionStage<HarnessResponse>> protocol,
            ArtifactReference.Publisher artifacts, ExecutorService executor,
            int artifactThresholdBytes) {
        this(protocol, artifacts, executor, artifactThresholdBytes, System::nanoTime,
                RequestAdmission.serverDefaults());
    }

    HarnessToolHandler(Function<HarnessRequest, CompletionStage<HarnessResponse>> protocol,
            ArtifactReference.Publisher artifacts, ArtifactReference.Reader artifactReader,
            ExecutorService executor, int artifactThresholdBytes) {
        this(withEmptyCaptures(protocol), artifacts, artifactReader, executor,
                artifactThresholdBytes, System::nanoTime, RequestAdmission.serverDefaults(),
                RecoveryAccounting.MAX_ENTRIES);
    }

    HarnessToolHandler(Function<HarnessRequest, CompletionStage<HarnessResponse>> protocol,
            ArtifactReference.Publisher artifacts, ExecutorService executor,
            int artifactThresholdBytes, LongSupplier nanoClock) {
        this(withEmptyCaptures(protocol), artifacts, executor, artifactThresholdBytes, nanoClock,
                RequestAdmission.serverDefaults(), RecoveryAccounting.MAX_ENTRIES);
    }

    /** Test constructor with an explicit recovery-accounting capacity. */
    HarnessToolHandler(Function<HarnessRequest, CompletionStage<HarnessResponse>> protocol,
            ArtifactReference.Publisher artifacts, ExecutorService executor,
            int artifactThresholdBytes, LongSupplier nanoClock, int recoveryCapacity) {
        this(withEmptyCaptures(protocol), artifacts, executor, artifactThresholdBytes, nanoClock,
                RequestAdmission.serverDefaults(), recoveryCapacity);
    }

    HarnessToolHandler(Function<HarnessRequest, CompletionStage<HarnessResponse>> protocol,
            ArtifactReference.Publisher artifacts, ExecutorService executor,
            int artifactThresholdBytes, LongSupplier nanoClock, RequestAdmission admission) {
        this(withEmptyCaptures(protocol), artifacts, executor, artifactThresholdBytes, nanoClock,
                admission, RecoveryAccounting.MAX_ENTRIES);
    }

    /**
     * Adapts a response-only protocol source into the execution path with no attachments.
     * The adapter links cancellation back to the raw protocol stage: a derived {@code thenApply}
     * stage would swallow client cancellation, leaving the protocol work running.
     */
    private static ExecutionSource withEmptyCaptures(
            Function<HarnessRequest, CompletionStage<HarnessResponse>> protocol) {
        return request -> {
            CompletableFuture<HarnessResponse> raw = Objects.requireNonNull(
                    protocol.apply(request), "protocol stage").toCompletableFuture();
            CompletableFuture<HarnessProtocolService.Execution> execution = new CompletableFuture<>();
            raw.whenComplete((response, failure) -> {
                if (failure != null) {
                    execution.completeExceptionally(failure);
                } else {
                    execution.complete(new HarnessProtocolService.Execution(response, Map.of()));
                }
            });
            execution.whenComplete((ignored, failure) -> {
                if (failure instanceof CancellationException && !raw.isDone()) {
                    raw.cancel(false);
                }
            });
            return execution;
        };
    }

    HarnessToolHandler(ExecutionSource protocol,
            ArtifactReference.Publisher artifacts, ExecutorService executor,
            int artifactThresholdBytes, LongSupplier nanoClock) {
        this(protocol, artifacts, executor, artifactThresholdBytes, nanoClock,
                RequestAdmission.serverDefaults(), RecoveryAccounting.MAX_ENTRIES);
    }

    HarnessToolHandler(ExecutionSource protocol,
            ArtifactReference.Publisher artifacts, ExecutorService executor,
            int artifactThresholdBytes, LongSupplier nanoClock, RequestAdmission admission) {
        this(protocol, artifacts, executor, artifactThresholdBytes, nanoClock, admission,
                RecoveryAccounting.MAX_ENTRIES);
    }

    HarnessToolHandler(ExecutionSource protocol,
            ArtifactReference.Publisher artifacts, ExecutorService executor,
            int artifactThresholdBytes, LongSupplier nanoClock, RequestAdmission admission,
            int recoveryCapacity) {
        this(protocol, artifacts, ArtifactReference.Reader.unavailable(), executor,
                artifactThresholdBytes, nanoClock, admission, recoveryCapacity);
    }

    HarnessToolHandler(ExecutionSource protocol,
            ArtifactReference.Publisher artifacts, ArtifactReference.Reader artifactReader,
            ExecutorService executor, int artifactThresholdBytes, LongSupplier nanoClock,
            RequestAdmission admission, int recoveryCapacity) {
        this.protocol = Objects.requireNonNull(protocol, "protocol");
        this.artifacts = new VerifiedArtifactPublisher(
                Objects.requireNonNull(artifacts, "artifacts"));
        this.artifactReader = Objects.requireNonNull(artifactReader, "artifactReader");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        if (artifactThresholdBytes <= 0) {
            throw new IllegalArgumentException("artifactThresholdBytes must be positive");
        }
        this.artifactThresholdBytes = artifactThresholdBytes;
        this.admission = Objects.requireNonNull(admission, "admission");
        diagnosticAccounting = new RecoveryAccounting(nanoClock, recoveryCapacity,
                RecoveryAccounting.TTL);
        sessionAccounting = new RecoveryAccounting(nanoClock, recoveryCapacity,
                RecoveryAccounting.TTL);
        scheduler = Schedulers.fromExecutorService(executor);
    }

    /** Handles one approved tool call asynchronously on an owned virtual thread. */
    public Mono<McpSchema.CallToolResult> handle(McpSchema.CallToolRequest call) {
        Objects.requireNonNull(call, "call");
        return Mono.defer(() -> {
            long sequence = requestSequence.incrementAndGet();
            String requestId = "mcp-" + Long.toUnsignedString(sequence);
            Map<String, Object> arguments = call.arguments() == null ? Map.of() : call.arguments();
            // The workflow generation is captured at request start so a stale completion
            // can only ever release the workflow it actually participated in.
            long[] workflowToken = {
                    sessionAccounting.tokenOf(sessionKey(arguments)) };
            McpSchema.Tool tool;
            try {
                tool = catalog.tool(call.name());
            } catch (IllegalArgumentException failure) {
                return Mono.just(diagnostic(
                        requestId, sequence, call.name(), arguments,
                        DiagnosticCode.UNKNOWN_OPERATION,
                        "Operation is not allowlisted",
                        List.of(new DiagnosticEnvelope.FieldProblem(
                                DiagnosticCode.UNKNOWN_OPERATION,
                                "$.operation",
                                boundedObserved(call.name()),
                                new DiagnosticEnvelope.Expected(
                                        "string", true, null,
                                        catalog.toolNames().stream().sorted().toList(),
                                        null, null, null, null, null, false),
                                catalog.toolNames().stream().sorted().toList(),
                                Map.of())),
                        null, workflowToken));
            }
            if (!locatorShapeWithinLimits(arguments)) {
                return Mono.just(diagnostic(
                        requestId, sequence, call.name(), arguments,
                        DiagnosticCode.SCHEMA_CONFLICT,
                        "Locator exceeds adapter complexity limits",
                        List.of(), null, workflowToken));
            }
            List<DiagnosticEnvelope.FieldProblem> problems = SchemaDiagnostics.validate(
                    tool.inputSchema(), arguments,
                    catalog.minimalExample(call.name(), arguments));
            if (!problems.isEmpty()) {
                return Mono.just(diagnostic(
                        requestId, sequence, call.name(), arguments,
                        problems.getFirst().code(),
                        "One or more arguments do not match the operation schema",
                        problems, null, workflowToken));
            }
            if ("ui_artifact_read".equals(call.name())) {
                CompletionStage<McpSchema.CallToolResult> admitted = admission.submit(
                        admissionKey(arguments), HarnessToolCatalog.AccessMode.READ_ONLY,
                        () -> CompletableFuture.completedFuture(readArtifact(
                                sequence, arguments, workflowToken)));
                return Mono.fromFuture(admitted.toCompletableFuture())
                        .onErrorResume(RequestAdmission.LimitExceededException.class,
                                failure -> Mono.just(diagnostic(
                                        requestId, sequence, call.name(), arguments,
                                        DiagnosticCode.LIMIT_EXCEEDED, failure.getMessage(),
                                        List.of(), null, workflowToken)));
            }


            HarnessRequest request;
            try {
                request = toProtocolRequest(call.name(), arguments, requestId);
            } catch (RuntimeException failure) {
                return Mono.just(diagnostic(
                        requestId, sequence, call.name(), arguments,
                        DiagnosticCode.SCHEMA_CONFLICT,
                        "Arguments could not be decoded",
                        List.of(), null, workflowToken));
            }

            // Bound admission before protocol dispatch: excess work is rejected immediately
            // with a stable LIMIT_EXCEEDED diagnostic and never reaches the protocol service.
            HarnessToolCatalog.AccessMode mode = catalog.accessMode(call.name());
            CompletionStage<McpSchema.CallToolResult> admitted = admission.submit(
                    admissionKey(arguments), mode,
                    () -> execute(request, call.name(), sequence, arguments, workflowToken));
            return Mono.fromFuture(admitted.toCompletableFuture())
                    .onErrorResume(RequestAdmission.LimitExceededException.class,
                            failure -> Mono.just(limitExceeded(
                                    request, sequence, call.name(), arguments, failure,
                                    workflowToken)));
        }).subscribeOn(scheduler);
    }

    /**
     * Dispatches one admitted request to the protocol service and translates its response.
     * The returned stage reaches a terminal state only after translation and output
     * accounting, so the admission permit is held across the full output lifecycle.
     */
    private CompletionStage<McpSchema.CallToolResult> execute(
            HarnessRequest request,
            String operation,
            long sequence,
            Map<String, Object> arguments,
            long[] workflowToken) {
        CompletionStage<HarnessProtocolService.Execution> stage;
        try {
            stage = Objects.requireNonNull(protocol.apply(request), "protocol stage");
        } catch (RuntimeException | Error failure) {
            return CompletableFuture.completedFuture(classifyBoundaryFailure(
                    failure, operation, sequence, arguments,
                    "Protocol invocation failed", workflowToken));
        }
        if ("ui_keyboard_gesture".equals(operation)) {
            return gestureTranslation(
                    stage, operation, sequence, arguments, workflowToken);
        }
        return Mono.fromFuture(stage.toCompletableFuture())
                .map(execution -> toMcpResult(
                        execution.response(), execution.captures(),
                        operation, sequence, arguments, workflowToken))
                .onErrorResume(failure -> Mono.just(classifyBoundaryFailure(
                        failure, operation, sequence, arguments,
                        "Protocol invocation failed", workflowToken)))
                .toFuture();
    }

    private CompletionStage<McpSchema.CallToolResult> gestureTranslation(
            CompletionStage<HarnessProtocolService.Execution> stage,
            String operation,
            long sequence,
            Map<String, Object> arguments,
            long[] workflowToken) {
        CompletableFuture<HarnessProtocolService.Execution> source = stage.toCompletableFuture();
        CompletableFuture<McpSchema.CallToolResult> translated =
                new CompletableFuture<>() {
                    private boolean cancellationRequested;

                    @Override public synchronized boolean cancel(
                            boolean mayInterruptIfRunning) {
                        if (isDone() || cancellationRequested) {
                            return false;
                        }
                        cancellationRequested = true;
                        source.cancel(false);
                        return false;
                    }
                };
        source.whenComplete((execution, failure) -> {
            if (failure != null) {
                translated.complete(classifyBoundaryFailure(
                        failure, operation, sequence, arguments,
                        "Protocol invocation failed", workflowToken));

                return;
            }
            try {
                translated.complete(toMcpResult(
                        execution.response(), execution.captures(),
                        operation, sequence, arguments, workflowToken));
            } catch (RuntimeException | Error translationFailure) {
                translated.complete(classifyBoundaryFailure(
                        translationFailure, operation, sequence, arguments,
                        "Result translation failed", workflowToken));
            }
        });
        return translated;
    }
    private McpSchema.CallToolResult readArtifact(
            long sequence, Map<String, Object> arguments, long[] workflowToken) {
        String operation = "ui_artifact_read";
        String sessionId = (String) arguments.get("sessionId");
        String reference = (String) arguments.get("reference");
        long offset = ((Number) arguments.get("offset")).longValue();
        int maxBytes = ((Number) arguments.get("maxBytes")).intValue();
        try {
            ArtifactReference.requireOpaque(reference);
            ArtifactReference.Chunk chunk = Objects.requireNonNull(
                    artifactReader.read(sessionId, reference, offset, maxBytes),
                    "artifact reader chunk");
            ArtifactReference artifact = chunk.artifact();
            byte[] bytes = chunk.content();
            if (!artifact.reference().equals(reference)
                    || chunk.offset() != offset
                    || bytes.length > maxBytes
                    || (offset < artifact.byteLength() && bytes.length == 0)) {
                throw new ArtifactReference.ArtifactReadUnavailableException(
                        "Artifact reader returned inconsistent metadata");
            }
            if (offset == 0 && chunk.eof()
                    && !sha256(bytes).equalsIgnoreCase(artifact.sha256())) {
                throw new ArtifactReference.ArtifactIntegrityException();
            }
            LinkedHashMap<String, Object> content = new LinkedHashMap<>();
            content.put("kind", "artifact-chunk");
            content.put("reference", artifact.reference());
            content.put("mediaType", artifact.mediaType());
            content.put("totalByteLength", artifact.byteLength());
            content.put("sha256", artifact.sha256().toLowerCase(java.util.Locale.ROOT));
            content.put("offset", chunk.offset());
            content.put("nextOffset", chunk.nextOffset());
            content.put("eof", chunk.eof());
            content.put("data", Base64.getEncoder().encodeToString(bytes));
            content.put("progress", encodedProgress(DiagnosticEnvelope.Progress.unavailable()));
            RecoveryAccounting.Snapshot session =
                    sessionAccounting.snapshot(sessionKey(arguments));
            content.put("recovery", encodedRecovery(new DiagnosticEnvelope.Recovery(
                    dev.gdx.uiharness.protocol.RecoveryPolicy.VERSION,
                    session.consumed(),
                    HarnessToolCatalog.recoveryPolicy().maxSchemaRecoveries(),
                    session.workflowElapsedMillis(),
                    HarnessToolCatalog.recoveryPolicy().maxWallTimeMillis(),
                    "success/v1")));
            endWorkflow(sessionKey(arguments), workflowToken[0]);
            return McpSchema.CallToolResult.builder()
                    .structuredContent(Map.copyOf(content))
                    .addTextContent(compactText(content))
                    .isError(false)
                    .build();
        } catch (Throwable failure) {
            Throwable root = unwrapBoundaryFailure(failure);
            if (root instanceof VirtualMachineError || root instanceof ThreadDeath) {
                throw (Error) root;
            }
            String code;
            String message;
            if (root instanceof ArtifactReference.InvalidArtifactReferenceException) {
                code = "invalid-artifact-reference";
                message = "Artifact reference is not transport-safe";
            } else if (root instanceof ArtifactReference.InvalidArtifactOffsetException) {
                code = "invalid-artifact-offset";
                message = "Artifact offset is outside the payload";
            } else if (root instanceof ArtifactReference.ArtifactNotFoundException) {
                code = "artifact-not-found";
                message = "Artifact is unavailable for this session";
            } else if (root instanceof ArtifactReference.ArtifactIntegrityException) {
                code = "artifact-integrity-failed";
                message = "Artifact integrity verification failed";
            } else {
                code = "artifact-read-unavailable";
                message = "Artifact retrieval is unavailable";
            }
            String traceId = internalTraceId();
            ARTIFACT_LOGGER.log(java.util.logging.Level.FINE,
                    "artifact reader rejected request " + traceId, (Throwable) null);
            return localError(operation, sequence, arguments, code, message, traceId,
                    workflowToken);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private McpSchema.CallToolResult limitExceeded(
            HarnessRequest request,
            long sequence,
            String operation,
            Map<String, Object> arguments,
            RequestAdmission.LimitExceededException failure,
            long[] workflowToken) {
        return diagnostic(
                request.requestId(), sequence, operation, arguments,
                DiagnosticCode.LIMIT_EXCEEDED,
                failure.getMessage(), List.of(), null, workflowToken);
    }

    private HarnessRequest toProtocolRequest(
            String toolName, Map<String, Object> arguments, String requestId) {
        LinkedHashMap<String, Object> commandJson = new LinkedHashMap<>(arguments);
        Object sessionValue = commandJson.remove("sessionId");
        Object deadlineValue = commandJson.remove("deadlineMillis");
        String sessionId = sessionValue == null ? "catalog" : (String) sessionValue;
        long deadlineMillis = deadlineValue == null
                ? DEFAULT_DEADLINE_MILLIS : ((Number) deadlineValue).longValue();
        commandJson.put("type", commandType(toolName));
        Command command = COMMAND_MAPPER.convertValue(commandJson, Command.class);
        return new HarnessRequest(
                ProtocolVersion.V1, sessionId, requestId, deadlineMillis, command);
    }

    private static String commandType(String toolName) {
        return switch (toolName) {
            case "ui_sessions" -> "sessions";
            case "ui_snapshot" -> "snapshot";
            case "ui_query" -> "query";
            case "ui_action" -> "action";
            case "ui_keyboard_gesture" -> "keyboard-gesture";
            case "ui_assert" -> "assert";
            case "ui_wait" -> "wait";
            case "ui_screenshot" -> "screenshot";
            case "ui_inspect_compare" -> "inspect-compare";
            case "ui_typography_diagnose" -> "typography-diagnose";
            case "ui_layout_diagnose" -> "layout-diagnose";
            case "ui_trace_start" -> "trace-start";
            case "ui_trace_stop" -> "trace-stop";
            case "ui_scenarios" -> "scenario-list";
            case "ui_scenario_start" -> "scenario-start";
            case "ui_navigation_inspect" -> "navigation-inspect";
            case "ui_navigation_validate" -> "navigation-validate";
            case "ui_validate_layout" -> "layout-validate";
            case "ui_matrix_run" -> "matrix-run";
            case "ui_matrix_results" -> "matrix-results";
            case "ui_semantic_compare" -> "semantic-compare";
            case "ui_trace_query" -> "trace-query";
            case "ui_runtime_compare" -> "runtime-compare";
            case "ui_capabilities" -> "capabilities";
            default -> throw new IllegalArgumentException("Unknown tool: " + toolName);
        };
    }

    private McpSchema.CallToolResult toMcpResult(
            HarnessResponse response,
            Map<String, BinaryAttachment> captures,
            String operation,
            long sequence,
            Map<String, Object> arguments,
            long[] workflowToken) {
        if (response instanceof HarnessResponse.Failure failure) {
            McpSchema.CallToolResult result = protocolError(
                    failure.error(), operation, sequence, arguments, workflowToken);
            if (failure.error().code() == ProtocolError.Code.SESSION_CLOSED) {
                endWorkflow(sessionKey(arguments), workflowToken[0]);
            }
            return result;
        }
        HarnessResponse.Success success = (HarnessResponse.Success) response;
        try {
            LinkedHashMap<String, Object> content =
                    new LinkedHashMap<>(structured(success.result(), captures));
            content.put("progress", encodedProgress(
                    DiagnosticEnvelope.Progress.unavailable()));
            RecoveryAccounting.Snapshot session =
                    sessionAccounting.snapshot(sessionKey(arguments));
            content.put("recovery", encodedRecovery(new DiagnosticEnvelope.Recovery(
                    dev.gdx.uiharness.protocol.RecoveryPolicy.VERSION,
                    session.consumed(),
                    HarnessToolCatalog.recoveryPolicy().maxSchemaRecoveries(),
                    session.workflowElapsedMillis(),
                    HarnessToolCatalog.recoveryPolicy().maxWallTimeMillis(),
                    "success/v1")));
            endWorkflow(sessionKey(arguments), workflowToken[0]);
            boolean gestureError = success.result()
                    instanceof HarnessResponse.Result.KeyboardGesture gesture
                    && !"completed".equals(gesture.gesture().outcome());
            return McpSchema.CallToolResult.builder()
                    .structuredContent(Map.copyOf(content))
                    .addTextContent(compactText(content))
                    .isError(gestureError)
                    .build();
        } catch (RuntimeException | Error failure) {
            return classifyBoundaryFailure(
                    failure, operation, sequence, arguments,
                    "Result translation failed", workflowToken);
        }
    }

    private Map<String, Object> structured(
            HarnessResponse.Result result, Map<String, BinaryAttachment> captures) {
        if (result instanceof HarnessResponse.Result.Sessions sessions) {
            byte[] encoded = encodeResult(result);
            LinkedHashMap<String, Object> content = content("sessions-result");
            content.put("sessions", sessions.sessions().stream().map(session -> Map.of(
                    "sessionId", session.sessionId(),
                    "capabilities", session.capabilities())).toList());
            offloadLarge(content, encoded, "application/json", "sessions");
            return Map.copyOf(content);
        }
        if (result instanceof HarnessResponse.Result.Capabilities capabilities) {
            LinkedHashMap<String, Object> content = content("capabilities-result");
            content.put("capabilities", capabilities.capabilities());
            content.put("catalogSchemaVersion", "operation-catalog/v1");
            content.put("operations", catalog.operationCatalog());
            content.put("diagnosticRegistryVersion", DiagnosticCode.REGISTRY_VERSION);
            content.put("diagnosticRegistry", HarnessToolCatalog.diagnosticRegistry());
            content.put("recoveryPolicyVersion",
                    dev.gdx.uiharness.protocol.RecoveryPolicy.VERSION);
            content.put("recoveryPolicy", HarnessToolCatalog.recoveryPolicy());
            return Map.copyOf(content);
        }
        if (result instanceof HarnessResponse.Result.Snapshot snapshotResult) {
            byte[] encoded = encodeResult(result);
            var snapshot = snapshotResult.snapshot();
            LinkedHashMap<String, Object> content = content("snapshot-summary");
            content.put("revision", snapshot.revision());
            content.put("frame", snapshot.frame());
            content.put("rootId", snapshot.rootId());
            content.put("nodeCount", snapshot.nodes().size());
            if (snapshot.contract() != null) {
                content.put("contractSchemaVersion", snapshot.contract().schemaVersion());
                content.put("stateId", snapshot.contract().stateId());
                content.put("controlCount", snapshot.contract().controls().size());
                @SuppressWarnings("unchecked")
                Map<String, Object> contract = COMMAND_MAPPER.convertValue(
                        snapshot.contract(), Map.class);
                content.put("contract", contract);
            }
            offloadLarge(content, encoded, "application/json", "contract");
            return Map.copyOf(content);
        }
        if (result instanceof HarnessResponse.Result.Query query) {
            byte[] encoded = encodeResult(result);
            LinkedHashMap<String, Object> content = content("query-result");
            content.put("matchCount", query.matches().size());
            content.put("matches", query.matches().stream()
                    .map(HarnessToolHandler::nodeSummary).toList());
            content.put("evidence", query.evidence());
            offloadLarge(content, encoded, "application/json", "matches", "evidence");
            return Map.copyOf(content);
        }
        if (result instanceof HarnessResponse.Result.Action action) {
            byte[] encoded = encodeResult(result);
            LinkedHashMap<String, Object> content = content("action-result");
            content.put("beforeRevision", action.beforeRevision());
            content.put("afterRevision", action.afterRevision());
            content.put("observedState", action.observedState());
            content.put("evidence", action.evidence());
            offloadLarge(content, encoded, "application/json", "evidence");
            return Map.copyOf(content);
        }
        if (result instanceof HarnessResponse.Result.KeyboardGesture gesture) {
            LinkedHashMap<String, Object> content = content("keyboard-gesture-result");
            @SuppressWarnings("unchecked")
            Map<String, Object> evidence = COMMAND_MAPPER.convertValue(
                    gesture.gesture(), Map.class);
            content.putAll(evidence);
            return Map.copyOf(content);
        }
        if (result instanceof HarnessResponse.Result.Assertion assertion) {
            byte[] encoded = encodeResult(result);
            LinkedHashMap<String, Object> content = content("assertion-result");
            content.put("schemaVersion", assertion.schemaVersion());
            content.put("outcome", assertion.outcome());
            content.put("locator", COMMAND_MAPPER.convertValue(assertion.locator(), Map.class));
            content.put("assertion",
                    COMMAND_MAPPER.convertValue(assertion.assertion(), Map.class));
            content.put("nodeId", assertion.nodeId());
            content.put("expected", assertion.expected());
            content.put("lastObserved", assertion.lastObserved());
            content.put("actionability", assertion.actionability());
            content.put("revision", assertion.revision());
            content.put("frame", assertion.frame());
            content.put("elapsedMillis", assertion.elapsedMillis());
            content.put("candidates", assertion.candidates());
            content.put("truncated", assertion.truncated());
            if (assertion.traceId() != null) {
                content.put("traceId", assertion.traceId());
            }
            offloadLarge(content, encoded, "application/json", "candidates");
            return Map.copyOf(content);
        }
        if (result instanceof HarnessResponse.Result.Wait wait) {
            byte[] encoded = encodeResult(result);
            LinkedHashMap<String, Object> content = content("wait-result");
            content.put("revision", wait.revision());
            content.put("frame", wait.frame());
            content.put("matchCount", wait.matches().size());
            content.put("matches", wait.matches().stream()
                    .map(HarnessToolHandler::nodeSummary).toList());
            content.put("evidence", wait.evidence());
            offloadLarge(content, encoded, "application/json", "matches", "evidence");
            return Map.copyOf(content);
        }
        if (result instanceof HarnessResponse.Result.Screenshot screenshot) {
            BinaryAttachment png = requireCapture(captures, HarnessProtocolService.SCREENSHOT_CAPTURE);
            ArtifactReference reference = artifacts.publishBuffer("image/png", png.asByteBuffer());
            requireReceiptMatches(reference, "image/png", png.length(), png.sha256());
            LinkedHashMap<String, Object> content = content("screenshot-result");
            content.put("artifact", artifactMap(reference));
            content.put("frame", screenshot.frame());
            content.put("revision", screenshot.revision());
            content.put("width", screenshot.width());
            content.put("height", screenshot.height());
            content.put("scaleX", screenshot.scaleX());
            content.put("scaleY", screenshot.scaleY());
            return Map.copyOf(content);
        }
        if (result instanceof HarnessResponse.Result.InspectCompare comparison) {
            byte[] encoded = encodeResult(result);
            LinkedHashMap<String, Object> content = content("inspect-compare-result");
            content.put("status", comparison.status());
            content.put("policy", comparison.policy());
            content.put("iterations", comparison.iterations());
            content.put("elapsedMillis", comparison.elapsedMillis());
            content.put("differences", COMMAND_MAPPER.convertValue(
                    comparison.differences(), List.class));
            content.put("regions", COMMAND_MAPPER.convertValue(
                    comparison.regions(), List.class));
            content.put("diagnostics", COMMAND_MAPPER.convertValue(
                    comparison.diagnostics(), List.class));
            if (comparison.reference() != null) {
                content.put("referenceId", comparison.reference().referenceId());
            }
            if (comparison.metrics() != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> metrics = COMMAND_MAPPER.convertValue(
                        comparison.metrics(), Map.class);
                content.put("metrics", metrics);
            }
            if (comparison.current() != null) {
                BinaryAttachment currentCapture = requireCapture(captures,
                        HarnessProtocolService.COMPARE_CURRENT_CAPTURE);
                ArtifactReference current = artifacts.publishBuffer(
                        "image/png", currentCapture.asByteBuffer());
                requireReceiptMatches(current, "image/png",
                        currentCapture.length(), currentCapture.sha256());
                content.put("currentArtifact", artifactMap(current));
                content.put("revision", comparison.current().revision());
                content.put("frame", comparison.current().frame());
                content.put("width", comparison.current().width());
                content.put("height", comparison.current().height());
                content.put("scaleX", comparison.current().scaleX());
                content.put("scaleY", comparison.current().scaleY());
                content.put("sha256", comparison.current().sha256());
            }
            if (comparison.heatmap() != null) {
                BinaryAttachment heatmapCapture = requireCapture(captures,
                        HarnessProtocolService.COMPARE_HEATMAP_CAPTURE);
                ArtifactReference heatmap = artifacts.publishBuffer(
                        "image/png", heatmapCapture.asByteBuffer());
                requireReceiptMatches(heatmap, "image/png",
                        heatmapCapture.length(), heatmapCapture.sha256());
                content.put("heatmapArtifact", artifactMap(heatmap));
            }
            ArtifactReference evidence = artifacts.publish(
                    "application/json", encoded);
            content.put("evidenceArtifact", artifactMap(evidence));
            return Map.copyOf(content);
        }
        if (result instanceof HarnessResponse.Result.TypographyDiagnostic typography) {
            byte[] encoded = encodeResult(result);
            LinkedHashMap<String, Object> content =
                    content("typography-diagnostic-result");
            content.put("status", typography.status());
            content.put("reportCount", typography.reports().size());
            content.put("reports", typography.reports().stream()
                    .map(HarnessToolHandler::typographyReport)
                    .toList());
            content.put("diagnostics", COMMAND_MAPPER.convertValue(
                    typography.diagnostics(), List.class));
            content.put("elapsedMillis", typography.elapsedMillis());
            if (typography.referenceId() != null) {
                content.put("referenceId", typography.referenceId());
            }
            if (typography.current() != null) {
                BinaryAttachment currentCapture = requireCapture(captures,
                        HarnessProtocolService.TYPOGRAPHY_CURRENT_CAPTURE);
                ArtifactReference current = artifacts.publishBuffer(
                        "image/png", currentCapture.asByteBuffer());
                requireReceiptMatches(current, "image/png",
                        currentCapture.length(), currentCapture.sha256());
                content.put("currentArtifact", artifactMap(current));
                content.put("revision", typography.current().revision());
                content.put("frame", typography.current().frame());
                content.put("width", typography.current().width());
                content.put("height", typography.current().height());
                content.put("scaleX", typography.current().scaleX());
                content.put("scaleY", typography.current().scaleY());
                content.put("sha256", typography.current().sha256());
            }
            ArtifactReference evidence = artifacts.publish(
                    "application/json", encoded);
            content.put("evidenceArtifact", artifactMap(evidence));
            return Map.copyOf(content);
        }
        if (result instanceof HarnessResponse.Result.LayoutDiagnostic layout) {
            byte[] encoded = encodeResult(result);
            LinkedHashMap<String, Object> content = content("layout-diagnostic-result");
            content.put("status", layout.status());
            content.put("reportCount", layout.reports().size());
            content.put("reports", layout.reports().stream()
                    .map(report -> Map.<String, Object>of(
                            "controlId", report.observation().controlId(),
                            "actorId", report.observation().actorId(),
                            "status", report.status().name()
                                    .toLowerCase(java.util.Locale.ROOT)
                                    .replace('_', '-'),
                            "diagnosticCount", report.diagnostics().size()))
                    .toList());
            content.put("diagnostics", COMMAND_MAPPER.convertValue(
                    layout.diagnostics(), List.class));
            content.put("elapsedMillis", layout.elapsedMillis());
            if (layout.settling() != null && layout.captures() != null) {
                content.put("quiescence", Map.of(
                        "settled", layout.settling().settled()
                                && layout.captures().settled(),
                        "status", layout.settling().settled()
                                ? layout.captures().status() : layout.settling().status(),
                        "stableFrameCount", layout.settling().stableFrameCount(),
                        "elapsedMillis", Math.max(
                                layout.settling().elapsedMillis(),
                                layout.captures().elapsedMillis()),
                        "sampleCount", layout.settling().samples().size()
                                + layout.captures().samples().size()));
            }
            if (layout.referenceId() != null) {
                content.put("referenceId", layout.referenceId());
            }
            if (layout.current() != null) {
                BinaryAttachment currentCapture = requireCapture(captures,
                        HarnessProtocolService.LAYOUT_CURRENT_CAPTURE);
                ArtifactReference current = artifacts.publishBuffer(
                        "image/png", currentCapture.asByteBuffer());
                requireReceiptMatches(current, "image/png",
                        currentCapture.length(), currentCapture.sha256());
                content.put("currentArtifact", artifactMap(current));
                content.put("revision", layout.current().revision());
                content.put("frame", layout.current().frame());
                content.put("width", layout.current().width());
                content.put("height", layout.current().height());
                content.put("scaleX", layout.current().scaleX());
                content.put("scaleY", layout.current().scaleY());
                content.put("sha256", layout.current().sha256());
            }
            ArtifactReference evidence = artifacts.publish(
                    "application/json", encoded);
            content.put("evidenceArtifact", artifactMap(evidence));
            return Map.copyOf(content);
        }
        if (result instanceof HarnessResponse.Result.ScenarioList scenarios) {
            LinkedHashMap<String, Object> content = content("scenarios-result");
            content.put("available", scenarios.available());
            content.put("scenarios", scenarios.scenarios());
            return Map.copyOf(content);
        }
        if (result instanceof HarnessResponse.Result.ScenarioStart started) {
            LinkedHashMap<String, Object> content = content("scenario-start-result");
            content.put("outcome", COMMAND_MAPPER.convertValue(started.outcome(), Map.class));
            return Map.copyOf(content);
        }
        if (result instanceof HarnessResponse.Result.LayoutValidation validation) {
            LinkedHashMap<String, Object> content = content("layout-validation-result");
            content.put("result", COMMAND_MAPPER.convertValue(
                    validation.result(), Map.class));
            return Map.copyOf(content);
        }
        if (result instanceof HarnessResponse.Result.MatrixRunStarted started) {
            LinkedHashMap<String, Object> content = content("matrix-run-started");
            content.put("runId", started.runId());
            return Map.copyOf(content);
        }
        if (result instanceof HarnessResponse.Result.MatrixReportData report) {
            LinkedHashMap<String, Object> content = content("matrix-report");
            content.put("report", COMMAND_MAPPER.convertValue(report.report(), Map.class));
            return Map.copyOf(content);
        }
        if (result instanceof HarnessResponse.Result.SemanticCompare compare) {
            LinkedHashMap<String, Object> content = content("semantic-compare-result");
            content.put("matched", compare.result().matched());
            content.put("differences", COMMAND_MAPPER.convertValue(
                    compare.result().differences(), List.class));
            content.put("comparedNodes", compare.result().comparedNodes());
            content.put("truncated", compare.result().truncated());
            return Map.copyOf(content);
        }
        if (result instanceof HarnessResponse.Result.TraceQuery traceQuery) {
            LinkedHashMap<String, Object> content = content("trace-query-result");
            content.put("traceId", traceQuery.result().traceId());
            content.put("transitions", COMMAND_MAPPER.convertValue(
                    traceQuery.result().transitions(), List.class));
            content.put("truncated", traceQuery.result().truncated());
            content.put("gapCount", traceQuery.result().gapCount());
            content.put("unknownCauseCount", traceQuery.result().unknownCauseCount());
            return Map.copyOf(content);
        }
        if (result instanceof HarnessResponse.Result.RuntimeCompare compare) {
            LinkedHashMap<String, Object> content = content("runtime-compare-result");
            content.put("status", compare.comparison().status().name());
            content.put("entityId", compare.comparison().entityId());
            content.put("propertyId", compare.comparison().propertyId());
            content.put("displayedValue", compare.comparison().displayedValue());
            content.put("runtimeValue", compare.comparison().runtimeValue());
            content.put("displayedFrame", compare.comparison().displayedFrame());
            content.put("runtimeFrame", compare.comparison().runtimeFrame());
            return Map.copyOf(content);
        }
        if (result instanceof HarnessResponse.Result.Navigation navigation) {
            LinkedHashMap<String, Object> content = content("navigation-result");
            content.put("result", COMMAND_MAPPER.convertValue(
                    navigation.result(), Map.class));
            return Map.copyOf(content);
        }
        if (result instanceof HarnessResponse.Result.TraceStarted started) {
            LinkedHashMap<String, Object> content = content("trace-started");
            content.put("traceId", started.traceId());
            return Map.copyOf(content);
        }
        if (result instanceof HarnessResponse.Result.TraceStopped stopped) {
            LinkedHashMap<String, Object> content = content("trace-stopped");
            content.put("traceId", stopped.traceId());
            content.put("traceReference", ArtifactReference.requireOpaque(
                    stopped.traceReference()));
            content.put("eventCount", stopped.eventCount());
            content.put("bytes", stopped.bytes());
            content.put("archiveSha256", requireArchiveDigest(stopped.archiveSha256()));
            return Map.copyOf(content);
        }
        throw new AssertionError("Unhandled protocol result " + result.getClass().getName());
    }

    private static BinaryAttachment requireCapture(
            Map<String, BinaryAttachment> captures, String key) {
        BinaryAttachment attachment = captures.get(key);
        if (attachment == null) {
            throw new IllegalArgumentException(
                    "accepted screenshot evidence is missing PNG bytes");
        }
        return attachment;
    }

    /**
     * Validates the publication receipt against the exact bytes handed to the publisher, so an
     * inconsistent or fake receipt (wrong media type, length, or digest) fails closed instead of
     * being reported as success.
     */
    private static void requireReceiptMatches(
            ArtifactReference reference, String mediaType, int byteLength, String sha256) {
        if (!reference.mediaType().equals(mediaType)
                || reference.byteLength() != byteLength
                || !reference.sha256().equals(sha256)) {
            throw new IllegalArgumentException(
                    "publisher receipt does not match the published bytes");
        }
    }

    private static Map<String, Object> typographyReport(TypographyReport report) {
        var observation = report.observation();
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("controlId", observation.controlId());
        result.put("actorId", observation.actorId());
        result.put("status", wire(report.status()));
        result.put("text", observation.text());
        result.put("textStart", observation.textStart());
        result.put("textEnd", observation.textEnd());
        result.put("glyphRuns", observation.glyphRuns().stream()
                .map(HarnessToolHandler::glyphRun).toList());
        result.put("revision", observation.revision());
        result.put("frame", observation.frame());
        result.put("currentArtifactId", observation.currentArtifactId());
        result.put("captureSha256", observation.captureSha256());
        result.put("transformSha256", observation.transformSha256());
        result.put("font", font(observation.font()));
        @SuppressWarnings("unchecked")
        Map<String, Object> display = COMMAND_MAPPER.convertValue(
                observation.display(), Map.class);
        result.put("display", display);
        result.put("transforms", observation.transforms().mappings().stream()
                .map(HarnessToolHandler::transform).toList());
        result.put("origins", observation.geometry().origins().stream()
                .map(HarnessToolHandler::point).toList());
        result.put("baselines", observation.geometry().baselines().stream()
                .map(HarnessToolHandler::point).toList());
        result.put("layoutBounds", observation.geometry().layoutBounds().stream()
                .map(HarnessToolHandler::bounds).toList());
        result.put("inkBounds", observation.geometry().inkBounds().stream()
                .map(HarnessToolHandler::bounds).toList());
        result.put("fractionalTranslationX",
                observation.geometry().fractionalTranslationX());
        result.put("fractionalTranslationY",
                observation.geometry().fractionalTranslationY());
        result.put("rasterResidual", observation.rasterResidual());
        result.put("diagnostics", report.diagnostics().stream()
                .map(HarnessToolHandler::typographyDifference).toList());
        result.put("sourceMechanisms", report.sourceMechanisms());
        result.put("controlledResults", report.controlledResults());
        result.put("unresolvedHypotheses", report.unresolvedHypotheses());
        return Map.copyOf(result);
    }

    private static Map<String, Object> glyphRun(GlyphRunObservation run) {
        return Map.of(
                "textStart", run.textStart(),
                "textEnd", run.textEnd(),
                "text", run.text(),
                "origin", point(run.origin()),
                "baseline", point(run.baseline()),
                "inkBounds", bounds(run.inkBounds()));
    }

    private static Map<String, Object> font(
            dev.gdx.uiharness.core.typography.FontObservation font) {
        return Map.ofEntries(
                Map.entry("sourceId", evidence(font.sourceId())),
                Map.entry("atlasPageIds", font.atlasPageIds()),
                Map.entry("nominalSize", evidence(font.nominalSize())),
                Map.entry("generatedGlyphSize", evidence(font.generatedGlyphSize())),
                Map.entry("effectiveSizeX", font.effectiveSizeX()),
                Map.entry("effectiveSizeY", font.effectiveSizeY()),
                Map.entry("bitmapScaleX", font.bitmapScaleX()),
                Map.entry("bitmapScaleY", font.bitmapScaleY()),
                Map.entry("minificationFilter", evidence(font.minificationFilter())),
                Map.entry("magnificationFilter", evidence(font.magnificationFilter())),
                Map.entry("distanceField", evidence(font.distanceField())),
                Map.entry("weight", evidence(font.weight())),
                Map.entry("letterSpacing", evidence(font.letterSpacing())));
    }

    private static Map<String, Object> evidence(EvidenceValue<?> evidence) {
        if (evidence.isAvailable()) {
            return Map.of(
                    "availability", "available",
                    "value", evidence.value());
        }
        return Map.of(
                "availability", "unavailable",
                "reason", evidence.unavailableReason().protocolValue(),
                "detail", evidence.detail());
    }

    private static Map<String, Object> transform(AffineTransformObservation value) {
        return Map.ofEntries(
                Map.entry("source", wire(value.source())),
                Map.entry("target", wire(value.target())),
                Map.entry("m00", value.m00()),
                Map.entry("m01", value.m01()),
                Map.entry("translateX", value.translateX()),
                Map.entry("m10", value.m10()),
                Map.entry("m11", value.m11()),
                Map.entry("translateY", value.translateY()),
                Map.entry("effectiveScaleX", value.effectiveScaleX()),
                Map.entry("effectiveScaleY", value.effectiveScaleY()),
                Map.entry("rotationDegrees", value.rotationDegrees()),
                Map.entry("shear", value.shear()),
                Map.entry("fractionalTranslationX", value.fractionalTranslationX()),
                Map.entry("fractionalTranslationY", value.fractionalTranslationY()),
                Map.entry("invertible", value.invertible()));
    }

    private static Map<String, Object> point(CoordinatePoint value) {
        return Map.of(
                "space", wire(value.space()),
                "x", value.x(),
                "y", value.y());
    }

    private static Map<String, Object> bounds(CoordinateBounds value) {
        return Map.of(
                "space", wire(value.space()),
                "x", value.x(),
                "y", value.y(),
                "width", value.width(),
                "height", value.height());
    }

    private static Map<String, Object> typographyDifference(
            TypographyDiagnostic value) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("controlId", value.controlId());
        result.put("path", value.path());
        result.put("expected", value.expected());
        result.put("observed", value.observed());
        result.put("units", value.units());
        putNullable(result, "coordinateSpace", value.coordinateSpace());
        result.put("referenceArtifactId", value.referenceArtifactId());
        result.put("currentArtifactId", value.currentArtifactId());
        return Map.copyOf(result);
    }

    private static String wire(Enum<?> value) {
        return value.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }

    private static boolean locatorShapeWithinLimits(Map<String, Object> arguments) {
        Object root = arguments.get("locator");
        if (root == null) {
            return true;
        }
        ArrayDeque<LocatorFrame> pending = new ArrayDeque<>();
        pending.push(new LocatorFrame(root, 1));
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        int nodeCount = 0;
        while (!pending.isEmpty()) {
            LocatorFrame frame = pending.pop();
            if (frame.depth() > MAX_LOCATOR_DEPTH
                    || !(frame.value() instanceof Map<?, ?> locator)
                    || !seen.add(frame.value())
                    || ++nodeCount > MAX_LOCATOR_NODES) {
                return false;
            }
            Object kindValue = locator.get("kind");
            if (!(kindValue instanceof String kind)) {
                return false;
            }
            switch (kind) {
                case "relation" -> {
                    if (!push(pending, locator.get("anchor"), frame.depth() + 1)
                            || !push(pending, locator.get("target"), frame.depth() + 1)) {
                        return false;
                    }
                }
                case "filter" -> {
                    if (!push(pending, locator.get("locator"), frame.depth() + 1)
                            || !(locator.get("filter") instanceof Map<?, ?> filter)) {
                        return false;
                    }
                    if ("has".equals(filter.get("kind"))
                            && !push(pending, filter.get("locator"), frame.depth() + 2)) {
                        return false;
                    }
                }
                case "index" -> {
                    if (!push(pending, locator.get("locator"), frame.depth() + 1)) {
                        return false;
                    }
                }
                default -> {
                    // Non-composite and unknown variants are handled by the JSON schema.
                }
            }
        }
        return true;
    }

    private static String boundedObserved(Object value) {
        String observed = String.valueOf(value);
        return observed.length() <= 128
                ? observed : observed.substring(0, 128);
    }

    private static boolean push(
            ArrayDeque<LocatorFrame> pending, Object locator, int depth) {
        if (locator == null) {
            return false;
        }
        pending.push(new LocatorFrame(locator, depth));
        return true;
    }

    private record LocatorFrame(Object value, int depth) {}

    private ArtifactReference publishCapture(byte[] png, String claimedSha256) {
        ArtifactReference reference = artifacts.publish("image/png", png);
        if (!claimedSha256.equals(reference.sha256())) {
            throw new ArtifactReference.ArtifactUnavailableException(
                    "Capture digest does not match the published bytes");
        }
        return reference;
    }

    private void offloadLarge(LinkedHashMap<String, Object> content, byte[] encoded,
            String mediaType, String... bulkyFields) {
        if (encoded.length <= artifactThresholdBytes) {
            return;
        }
        ArtifactReference reference = artifacts.publish(mediaType, encoded);
        for (String field : bulkyFields) {
            content.remove(field);
        }
        content.put("artifact", artifactMap(reference));
    }

    private static LinkedHashMap<String, Object> content(String kind) {
        LinkedHashMap<String, Object> content = new LinkedHashMap<>();
        content.put("kind", kind);
        return content;
    }

    private static Map<String, Object> nodeSummary(HarnessResponse.NodeData node) {
        LinkedHashMap<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", node.id());
        summary.put("role", node.role());
        putNullable(summary, "accessibleName", node.accessibleName());
        putNullable(summary, "text", node.text());
        putNullable(summary, "testId", node.testId());
        return Map.copyOf(summary);
    }

    private static void putNullable(Map<String, Object> destination, String key, Object value) {
        if (value != null) {
            destination.put(key, value);
        }
    }

    /**
     * Fail-closed guard for the {@code ui_trace_stop} receipt: the catalog output
     * schema requires {@code archiveSha256}, so a controller that omits the verified
     * digest (or supplies a non-canonical one) must surface as an INTERNAL_ERROR
     * instead of a successful digest-less result.
     */
    private static String requireArchiveDigest(String digest) {
        if (digest == null || !digest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "archiveSha256 must be a lowercase 64-character hexadecimal digest");
        }
        return digest;
    }

    private static Map<String, Object> artifactMap(ArtifactReference reference) {
        return Map.of(
                "reference", reference.reference(),
                "mediaType", reference.mediaType(),
                "byteLength", reference.byteLength(),
                "sha256", reference.sha256());
    }

    private static byte[] encodeResult(HarnessResponse.Result result) {
        try {
            return COMMAND_MAPPER.writeValueAsBytes(result);
        } catch (Exception failure) {
            throw new IllegalArgumentException("Protocol result could not be encoded", failure);
        }
    }

    private static String compactText(Map<String, Object> structured) {
        return structured.get("kind") + ": " + structured;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> encodedProgress(
            DiagnosticEnvelope.Progress progress) {
        return COMMAND_MAPPER.convertValue(progress, Map.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> encodedRecovery(
            DiagnosticEnvelope.Recovery recovery) {
        return COMMAND_MAPPER.convertValue(recovery, Map.class);
    }

    private McpSchema.CallToolResult protocolError(
            ProtocolError error,
            String operation,
            long sequence,
            Map<String, Object> arguments,
            long[] workflowToken) {
        DiagnosticCode code = switch (error.code()) {
            case NOT_FOUND -> DiagnosticCode.LOCATOR_NOT_FOUND;
            case STRICTNESS_VIOLATION -> DiagnosticCode.LOCATOR_AMBIGUOUS;
            case TIMEOUT -> DiagnosticCode.DEADLINE_EXCEEDED;
            case LIMIT_EXCEEDED -> DiagnosticCode.LIMIT_EXCEEDED;
            case PROTOCOL_VERSION_MISMATCH -> DiagnosticCode.SCHEMA_CONFLICT;
            case INTERNAL_ERROR, RENDER_THREAD_FAILURE ->
                    DiagnosticCode.INTERNAL_ERROR;
            default -> DiagnosticCode.STATE_NOT_READY;
        };
        return diagnostic(
                error.requestId(), sequence, operation, arguments, code,
                error.message(), List.of(),
                error.locator(), error.candidates(), error.details(),
                error.suggestions(),
                error.elapsedMillis(), error.traceId(),
                new DiagnosticEnvelope.StateIdentity(
                        null, error.sessionId(), error.lastSnapshotRevision(), null),
                error.traceReference() == null
                        ? List.of() : List.of(error.traceReference()),
                workflowToken);
    }

    private McpSchema.CallToolResult diagnostic(
            String requestId,
            long sequence,
            String operation,
            Map<String, Object> arguments,
            DiagnosticCode requestedCode,
            String message,
            List<DiagnosticEnvelope.FieldProblem> problems,
            DiagnosticEnvelope.StateIdentity stateIdentity,
            long[] workflowToken) {
        return diagnostic(
                requestId, sequence, operation, arguments, requestedCode,
                message, problems, null, List.of(), Map.of(), List.of(), null, null,
                stateIdentity, List.of(), workflowToken);
    }

    private McpSchema.CallToolResult diagnostic(
            String requestId,
            long sequence,
            String operation,
            Map<String, Object> arguments,
            DiagnosticCode requestedCode,
            String message,
            List<DiagnosticEnvelope.FieldProblem> problems,
            String locator,
            List<Map<String, String>> candidates,
            Map<String, String> details,
            List<LocatorSuggestionSpec> suggestions,
            Long operationElapsedMillis,
            String traceId,
            DiagnosticEnvelope.StateIdentity stateIdentity,
            List<String> evidenceRefs,
            long[] workflowToken) {
        String fingerprint = diagnosticFingerprint(
                operation, arguments, requestedCode, problems);
        boolean transientDiagnostic = requestedCode.defaultDisposition()
                == DiagnosticEnvelope.Disposition.TRANSIENT;
        RecoveryAccounting.Snapshot session;
        RecoveryAccounting.Snapshot fingerprintSnapshot;
        boolean capacityRejection;
        // Recording, fingerprint registration, workflow release, and conditional
        // session removal share one lock, so a fingerprint record/register can
        // never interleave with an ending workflow's ownership check and delete.
        synchronized (workflows) {
            pruneExpiredWorkflows();
            session = transientDiagnostic
                    ? sessionAccounting.recordTransient(sessionKey(arguments))
                    : sessionAccounting.snapshot(sessionKey(arguments));
            fingerprintSnapshot = transientDiagnostic && session.tracked()
                    ? diagnosticAccounting.recordTransient(fingerprint)
                    : new RecoveryAccounting.Snapshot(
                            0, 0, false, RecoveryAccounting.NO_TOKEN);
            // Only a request that participates through a recorded transient attempt
            // adopts the workflow generation; terminal and non-transient requests
            // keep the token captured at request start, so a stale terminal can
            // never end a newer workflow.
            if (transientDiagnostic && session.tracked()) {
                workflowToken[0] = session.token();
                if (fingerprintSnapshot.tracked()) {
                    registerFingerprint(session.token(), fingerprint);
                }
            }
            capacityRejection = transientDiagnostic
                    && (!session.tracked() || !fingerprintSnapshot.tracked());
        }
        int equivalentConsumed = fingerprintSnapshot.consumed();
        int limit = HarnessToolCatalog.recoveryPolicy().maxSchemaRecoveries();
        DiagnosticCode code = requestedCode;
        String terminatingRule = recoveryRule(requestedCode);
        int consumed = session.consumed();
        if (capacityRejection) {
            // Either store at capacity: fingerprint churn cannot bypass the bound.
            code = DiagnosticCode.RECOVERY_BUDGET_EXHAUSTED;
            terminatingRule = "accounting-capacity/v1";
            consumed = limit; // terminal appearance: consumed == limit, remaining == 0
        } else if (transientDiagnostic && equivalentConsumed > limit) {
            code = DiagnosticCode.LOOP_DETECTED;
            terminatingRule = "equivalent-diagnostic-budget/v1";
        } else if (transientDiagnostic && consumed > limit) {
            code = DiagnosticCode.RECOVERY_BUDGET_EXHAUSTED;
            terminatingRule = "session-recovery-budget/v1";
        } else if (requestedCode.defaultDisposition()
                == DiagnosticEnvelope.Disposition.TERMINAL) {
            terminatingRule = "terminal-code/v1";
        }
        long elapsedMillis = session.workflowElapsedMillis();
        DiagnosticEnvelope envelope = DiagnosticEnvelope.create(
                requestId, sequence, operation, code, message, problems,
                locator, candidates, details, suggestions, operationElapsedMillis, traceId,
                stateIdentity, DiagnosticEnvelope.Progress.unavailable(),
                new DiagnosticEnvelope.Recovery(
                        dev.gdx.uiharness.protocol.RecoveryPolicy.VERSION,
                        consumed, limit, elapsedMillis,
                        HarnessToolCatalog.recoveryPolicy().maxWallTimeMillis(),
                        terminatingRule),
                evidenceRefs);
        @SuppressWarnings("unchecked")
        Map<String, Object> encoded = COMMAND_MAPPER.convertValue(envelope, Map.class);
        LinkedHashMap<String, Object> content = new LinkedHashMap<>(encoded);
        content.put("kind", "error");
        // A capacity rejection must not clear the saturated store: retries stay
        // fail-closed until the owning workflow terminates, expires, or closes.
        if (code.defaultDisposition() == DiagnosticEnvelope.Disposition.TERMINAL
                && !capacityRejection) {
            endWorkflow(sessionKey(arguments), workflowToken[0]);
        }
        return errorResult(content);
    }

    /**
     * Registers one accepted fingerprint under the session's current workflow
     * generation so it can be released when that workflow ends. The reverse
     * index is bounded by the same cardinality cap as the accounting stores;
     * at cap a registration is skipped and the fingerprint is simply
     * TTL-expired by its store instead.
     */
    private void registerFingerprint(long token, String fingerprint) {
        synchronized (workflows) {
            pruneExpiredWorkflows();
            if (registeredFingerprints >= RecoveryAccounting.MAX_ENTRIES) {
                return;
            }
            WorkflowFingerprints workflow = workflows.computeIfAbsent(token,
                    ignored -> new WorkflowFingerprints(nanoClock.getAsLong()));
            if (workflow.keys.add(fingerprint)) {
                registeredFingerprints++;
            }
        }
    }

    /**
     * Test seam: invoked between a fingerprint's ownership check and its release
     * so interleaving tests can deterministically pause an ending workflow at the
     * exact point a concurrent record would previously have been lost.
     */
    Runnable beforeFingerprintRelease = () -> {};

    /**
     * Ends the workflow for the given session generation: releases the
     * fingerprint keys it recorded (only those not also owned by another live
     * workflow) and removes the session reservation, but only when that
     * reservation still belongs to this generation. A stale completion with an
     * old token therefore never clears a newer workflow's state. The ownership
     * check, deletion, and conditional session removal are atomic with the
     * record/register path under one lock.
     */
    private void endWorkflow(String sessionKey, long token) {
        synchronized (workflows) {
            pruneExpiredWorkflows();
            WorkflowFingerprints workflow = workflows.remove(token);
            if (workflow != null) {
                registeredFingerprints -= workflow.keys.size();
                for (String fingerprint : workflow.keys) {
                    if (!ownedByAnotherWorkflow(fingerprint)) {
                        beforeFingerprintRelease.run();
                        diagnosticAccounting.remove(fingerprint);
                    }
                }
            }
            sessionAccounting.removeIfOwned(sessionKey, token);
        }
    }

    /** Returns whether another live workflow generation still owns the fingerprint. */
    private boolean ownedByAnotherWorkflow(String fingerprint) {
        synchronized (workflows) {
            for (WorkflowFingerprints workflow : workflows.values()) {
                if (workflow.keys.contains(fingerprint)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Drops workflows idle past the accounting TTL and releases their fingerprints. */
    private void pruneExpiredWorkflows() {
        long now = nanoClock.getAsLong();
        long ttlNanos = RecoveryAccounting.TTL.toNanos();
        Iterator<Map.Entry<Long, WorkflowFingerprints>> iterator =
                workflows.entrySet().iterator();
        while (iterator.hasNext()) {
            WorkflowFingerprints workflow = iterator.next().getValue();
            if (now - workflow.startedNanos > ttlNanos) {
                registeredFingerprints -= workflow.keys.size();
                for (String fingerprint : workflow.keys) {
                    if (!ownedByAnotherWorkflow(fingerprint)) {
                        diagnosticAccounting.remove(fingerprint);
                    }
                }
                iterator.remove();
            }
        }
    }

    /** One workflow generation's registered fingerprint keys and its TTL start. */
    private static final class WorkflowFingerprints {
        final long startedNanos;
        final LinkedHashSet<String> keys = new LinkedHashSet<>();

        WorkflowFingerprints(long startedNanos) {
            this.startedNanos = startedNanos;
        }
    }

    private static String sessionKey(Map<String, Object> arguments) {
        Object value = arguments.get("sessionId");
        return value instanceof String sessionId && !sessionId.isBlank()
                ? sessionId : "catalog";
    }

    /**
     * Returns the typed admission scope for one tool call. Requests without a session
     * identifier use the distinct sessionless scope instead of a string sentinel, so a real
     * client session literally named "catalog" never shares per-session admission with
     * sessionless calls.
     */
    private static RequestAdmission.SessionKey admissionKey(Map<String, Object> arguments) {
        Object value = arguments.get("sessionId");
        if (value instanceof String sessionId && !sessionId.isBlank()) {
            return RequestAdmission.SessionKey.session(sessionId);
        }
        return RequestAdmission.SessionKey.sessionless();
    }

    private static String recoveryRule(DiagnosticCode code) {
        return switch (code) {
            case MISSING_ARGUMENT, UNKNOWN_ARGUMENT, INVALID_ARGUMENT_TYPE,
                    OUT_OF_RANGE, INVALID_ENUM_VALUE -> "correct-request/v1";
            case LOCATOR_NOT_FOUND -> "wait-for-matching-locator/v1";
            case STALE_REVISION -> "refresh-state-identity/v1";
            case STATE_NOT_READY, NO_PROGRESS -> "wait-for-state-change/v1";
            default -> "terminal-code/v1";
        };
    }

    private static String diagnosticFingerprint(
            String operation,
            Map<String, Object> arguments,
            DiagnosticCode code,
            List<DiagnosticEnvelope.FieldProblem> problems) {
        return operation + ":" + code.name() + ":"
                + RecoveryWorkflow.normalizeIntent(arguments)
                + ":" + problems.stream()
                        .map(problem -> problem.code() + ":" + problem.fieldPath())
                        .toList();
    }

    private static final SecureRandom TRACE_ID_SOURCE = new SecureRandom();

    /**
     * Derives an opaque, random correlation ID for one boundary failure. The ID is
     * 128 bits of {@link SecureRandom} entropy formatted as {@code internal-} plus 32
     * lowercase hex digits, so it carries no exception class, sequence, or payload
     * information and cannot be predicted or correlated across handler instances.
     * The same ID is emitted in the MCP response and in the safe log record so an
     * operator can match the restricted {@code dev.gdx.uiharness.mcp.ArtifactPublisher}
     * log line to the boundary response without exposing the raw failure.
     */
    private static String internalTraceId() {
        byte[] random = new byte[16];
        TRACE_ID_SOURCE.nextBytes(random);
        return "internal-" + HexFormat.of().formatHex(random);
    }

    /**
     * Unwraps transport and reactive wrappers so a publisher failure that surfaced
     * asynchronously (e.g. {@link java.util.concurrent.CompletionException} from a
     * failed future or a reactor wrapper) is classified by its real cause.
     */
    private static Throwable unwrapBoundaryFailure(Throwable failure) {
        Throwable current = failure;
        while (current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException) {
            Throwable cause = current.getCause();
            if (cause == null) {
                return current;
            }
            current = cause;
        }
        return reactor.core.Exceptions.unwrap(current);
    }

    /**
     * Classifies a failure that crossed the publisher boundary into a stable,
     * secret-free diagnostic. {@link ArtifactReference.InvalidArtifactReferenceException}
     * keeps its distinct {@code invalid-artifact-reference} sub-code; every other
     * publisher-boundary outcome maps to {@code artifact-unavailable} with an opaque
     * trace ID. Fatal JVM errors ({@link VirtualMachineError}, {@link ThreadDeath})
     * are rethrown — they are never converted into a public diagnostic. The raw
     * failure is deliberately NOT passed to the logger: the {@code ARTIFACT_LOGGER}
     * record carries only a fixed message and the trace ID, so a custom exception
     * message, path, or secret can never reach any JUL handler. The raw failure
     * remains reachable in-process (it is the exception currently being handled) for
     * debugger inspection.
     */
    private McpSchema.CallToolResult classifyBoundaryFailure(
            Throwable failure,
            String operation,
            long sequence,
            Map<String, Object> arguments,
            String genericMessage,
            long[] workflowToken) {
        Throwable root = unwrapBoundaryFailure(failure);
        if (root instanceof ArtifactReference.InvalidArtifactReferenceException) {
            String traceId = internalTraceId();
            ARTIFACT_LOGGER.log(java.util.logging.Level.FINE,
                    "invalid artifact reference " + traceId, (Throwable) null);
            return localError(operation, sequence, arguments, "invalid-artifact-reference",
                    "Artifact reference is not transport-safe", traceId, workflowToken);
        }
        if (root instanceof ArtifactReference.ArtifactUnavailableException) {
            String traceId = internalTraceId();
            ARTIFACT_LOGGER.log(java.util.logging.Level.FINE,
                    "artifact publisher unavailable or unverified " + traceId,
                    (Throwable) null);
            return localError(operation, sequence, arguments, "artifact-unavailable",
                    "Artifact persistence is unavailable or rejected the payload",
                    traceId, workflowToken);
        }
        if (root instanceof VirtualMachineError || root instanceof ThreadDeath) {
            throw (Error) root;
        }
        String traceId = internalTraceId();
        ARTIFACT_LOGGER.log(java.util.logging.Level.FINE,
                "boundary failure " + traceId, (Throwable) null);
        return localError(operation, sequence, arguments, "internal-error",
                genericMessage, traceId, workflowToken);
    }

    private McpSchema.CallToolResult localError(
            String operation,
            long sequence,
            Map<String, Object> arguments,
            String code,
            String message,
            String traceId,
            long[] workflowToken) {
        return diagnostic(
                "mcp-" + Long.toUnsignedString(sequence),
                sequence,
                operation,
                arguments,
                DiagnosticCode.INTERNAL_ERROR,
                message + " (" + code + ")",
                List.of(),
                null,
                List.of(),
                Map.of(),
                List.of(),
                null,
                traceId,
                new DiagnosticEnvelope.StateIdentity(
                        null, sessionKey(arguments), null, null),
                List.of(),
                workflowToken);
    }

    private static McpSchema.CallToolResult errorResult(Map<String, Object> content) {
        return McpSchema.CallToolResult.builder()
                .structuredContent(Map.copyOf(content))
                .addTextContent(content.get("code") + ": " + content.get("message"))
                .isError(true)
                .build();
    }

    /** Shuts down all virtual-thread dispatch owned by this handler. */
    @Override public void close() {
        synchronized (workflows) {
            sessionAccounting.clear();
            diagnosticAccounting.clear();
            workflows.clear();
            registeredFingerprints = 0;
        }
        admission.close();
        scheduler.dispose();
        executor.close();
    }
}
