package dev.gdx.uiharness.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.gdx.uiharness.protocol.Command;
import dev.gdx.uiharness.protocol.DiagnosticCode;
import dev.gdx.uiharness.protocol.DiagnosticEnvelope;
import dev.gdx.uiharness.protocol.HarnessProtocolService;
import dev.gdx.uiharness.protocol.HarnessRequest;
import dev.gdx.uiharness.protocol.HarnessResponse;
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
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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

    private final Function<HarnessRequest, CompletionStage<HarnessResponse>> protocol;
    private final ArtifactReference.Publisher artifacts;
    private final ExecutorService executor;
    private final Scheduler scheduler;
    private final int artifactThresholdBytes;
    private final LongSupplier nanoClock;
    private final HarnessToolCatalog catalog = new HarnessToolCatalog();
    private final AtomicLong requestSequence = new AtomicLong();
    private final Map<String, Integer> diagnosticAttempts = new ConcurrentHashMap<>();
    private final Map<String, Integer> sessionRecoveryAttempts = new ConcurrentHashMap<>();
    private final long startedNanos;

    /** Creates a handler that owns a Java 25 virtual-thread executor. */
    public HarnessToolHandler(
            HarnessProtocolService protocol, ArtifactReference.Publisher artifacts) {
        this(Objects.requireNonNull(protocol, "protocol")::execute, artifacts,
                Executors.newVirtualThreadPerTaskExecutor(), DEFAULT_ARTIFACT_THRESHOLD_BYTES,
                System::nanoTime);
    }

    HarnessToolHandler(Function<HarnessRequest, CompletionStage<HarnessResponse>> protocol,
            ArtifactReference.Publisher artifacts, ExecutorService executor,
            int artifactThresholdBytes) {
        this(protocol, artifacts, executor, artifactThresholdBytes, System::nanoTime);
    }

    HarnessToolHandler(Function<HarnessRequest, CompletionStage<HarnessResponse>> protocol,
            ArtifactReference.Publisher artifacts, ExecutorService executor,
            int artifactThresholdBytes, LongSupplier nanoClock) {
        this.protocol = Objects.requireNonNull(protocol, "protocol");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        if (artifactThresholdBytes <= 0) {
            throw new IllegalArgumentException("artifactThresholdBytes must be positive");
        }
        this.artifactThresholdBytes = artifactThresholdBytes;
        startedNanos = nanoClock.getAsLong();
        scheduler = Schedulers.fromExecutorService(executor);
    }

    /** Handles one approved tool call asynchronously on an owned virtual thread. */
    public Mono<McpSchema.CallToolResult> handle(McpSchema.CallToolRequest call) {
        Objects.requireNonNull(call, "call");
        return Mono.defer(() -> {
            long sequence = requestSequence.incrementAndGet();
            String requestId = "mcp-" + Long.toUnsignedString(sequence);
            Map<String, Object> arguments = call.arguments() == null ? Map.of() : call.arguments();
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
                        null));
            }
            if (!locatorShapeWithinLimits(arguments)) {
                return Mono.just(diagnostic(
                        requestId, sequence, call.name(), arguments,
                        DiagnosticCode.SCHEMA_CONFLICT,
                        "Locator exceeds adapter complexity limits",
                        List.of(), null));
            }
            List<DiagnosticEnvelope.FieldProblem> problems = SchemaDiagnostics.validate(
                    tool.inputSchema(), arguments,
                    catalog.minimalExample(call.name(), arguments));
            if (!problems.isEmpty()) {
                return Mono.just(diagnostic(
                        requestId, sequence, call.name(), arguments,
                        problems.getFirst().code(),
                        "One or more arguments do not match the operation schema",
                        problems, null));
            }

            HarnessRequest request;
            try {
                request = toProtocolRequest(call.name(), arguments, requestId);
            } catch (RuntimeException failure) {
                return Mono.just(diagnostic(
                        requestId, sequence, call.name(), arguments,
                        DiagnosticCode.SCHEMA_CONFLICT,
                        "Arguments could not be decoded",
                        List.of(), null));
            }

            CompletionStage<HarnessResponse> stage;
            try {
                stage = Objects.requireNonNull(protocol.apply(request), "protocol stage");
            } catch (RuntimeException failure) {
                return Mono.just(diagnostic(
                        requestId, sequence, call.name(), arguments,
                        DiagnosticCode.INTERNAL_ERROR,
                        "Protocol invocation failed", List.of(), null));
            }
            return Mono.fromFuture(stage.toCompletableFuture())
                    .map(response -> toMcpResult(
                            response, call.name(), sequence, arguments))
                    .onErrorResume(failure -> Mono.just(
                            diagnostic(
                                    requestId, sequence, call.name(), arguments,
                                    DiagnosticCode.INTERNAL_ERROR,
                                    "Protocol invocation failed", List.of(), null)));
        }).subscribeOn(scheduler);
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
            case "ui_wait" -> "wait";
            case "ui_screenshot" -> "screenshot";
            case "ui_inspect_compare" -> "inspect-compare";
            case "ui_typography_diagnose" -> "typography-diagnose";
            case "ui_layout_diagnose" -> "layout-diagnose";
            case "ui_trace_start" -> "trace-start";
            case "ui_trace_stop" -> "trace-stop";
            case "ui_capabilities" -> "capabilities";
            default -> throw new IllegalArgumentException("Unknown tool: " + toolName);
        };
    }

    private McpSchema.CallToolResult toMcpResult(
            HarnessResponse response,
            String operation,
            long sequence,
            Map<String, Object> arguments) {
        if (response instanceof HarnessResponse.Failure failure) {
            return protocolError(
                    failure.error(), operation, sequence, arguments);
        }
        HarnessResponse.Success success = (HarnessResponse.Success) response;
        try {
            LinkedHashMap<String, Object> content =
                    new LinkedHashMap<>(structured(success.result()));
            content.put("progress", encodedProgress(
                    DiagnosticEnvelope.Progress.unavailable()));
            content.put("recovery", encodedRecovery(new DiagnosticEnvelope.Recovery(
                    dev.gdx.uiharness.protocol.RecoveryPolicy.VERSION,
                    sessionRecoveryAttempts.getOrDefault(sessionKey(arguments), 0),
                    HarnessToolCatalog.recoveryPolicy().maxSchemaRecoveries(),
                    Math.max(0, (nanoClock.getAsLong() - startedNanos) / 1_000_000),
                    HarnessToolCatalog.recoveryPolicy().maxWallTimeMillis(),
                    "success/v1")));
            return McpSchema.CallToolResult.builder()
                    .structuredContent(Map.copyOf(content))
                    .addTextContent(compactText(content))
                    .isError(false)
                    .build();
        } catch (ArtifactReference.InvalidArtifactReferenceException failure) {
            return localError(
                    operation, sequence, arguments,
                    "invalid-artifact-reference", failure.getMessage());
        } catch (ArtifactReference.ArtifactUnavailableException failure) {
            return localError(
                    operation, sequence, arguments,
                    "artifact-unavailable", failure.getMessage());
        } catch (RuntimeException failure) {
            return localError(
                    operation, sequence, arguments,
                    "internal-error", "Result translation failed");
        }
    }

    private Map<String, Object> structured(HarnessResponse.Result result) {
        byte[] encoded = encodeResult(result);
        if (result instanceof HarnessResponse.Result.Sessions sessions) {
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
            LinkedHashMap<String, Object> content = content("query-result");
            content.put("matchCount", query.matches().size());
            content.put("matches", query.matches().stream()
                    .map(HarnessToolHandler::nodeSummary).toList());
            content.put("evidence", query.evidence());
            offloadLarge(content, encoded, "application/json", "matches", "evidence");
            return Map.copyOf(content);
        }
        if (result instanceof HarnessResponse.Result.Action action) {
            LinkedHashMap<String, Object> content = content("action-result");
            content.put("beforeRevision", action.beforeRevision());
            content.put("afterRevision", action.afterRevision());
            content.put("observedState", action.observedState());
            content.put("evidence", action.evidence());
            offloadLarge(content, encoded, "application/json", "evidence");
            return Map.copyOf(content);
        }
        if (result instanceof HarnessResponse.Result.Wait wait) {
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
            byte[] png = Base64.getDecoder().decode(screenshot.pngBase64());
            ArtifactReference reference = artifacts.publish("image/png", png.clone());
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
            LinkedHashMap<String, Object> content = content("inspect-compare-result");
            content.put("status", comparison.status());
            content.put("policy", comparison.policy());
            content.put("iterations", comparison.iterations());
            content.put("elapsedMillis", comparison.elapsedMillis());
            content.put("differences", COMMAND_MAPPER.convertValue(
                    comparison.differences(), List.class));
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
                if (comparison.currentPngBase64() == null) {
                    throw new IllegalArgumentException(
                            "accepted current evidence is missing PNG bytes");
                }
                byte[] png = Base64.getDecoder().decode(
                        comparison.currentPngBase64());
                ArtifactReference current = artifacts.publish("image/png", png.clone());
                if (!current.sha256().equals(comparison.current().sha256())) {
                    throw new IllegalArgumentException(
                            "published current capture hash changed");
                }
                content.put("currentArtifact", artifactMap(current));
                content.put("revision", comparison.current().revision());
                content.put("frame", comparison.current().frame());
                content.put("width", comparison.current().width());
                content.put("height", comparison.current().height());
                content.put("scaleX", comparison.current().scaleX());
                content.put("scaleY", comparison.current().scaleY());
                content.put("sha256", comparison.current().sha256());
            }
            ArtifactReference evidence = artifacts.publish(
                    "application/json", encoded.clone());
            content.put("evidenceArtifact", artifactMap(evidence));
            return Map.copyOf(content);
        }
        if (result instanceof HarnessResponse.Result.TypographyDiagnostic typography) {
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
                if (typography.currentPngBase64() == null) {
                    throw new IllegalArgumentException(
                            "accepted typography evidence is missing PNG bytes");
                }
                byte[] png = Base64.getDecoder().decode(
                        typography.currentPngBase64());
                ArtifactReference current = artifacts.publish("image/png", png.clone());
                if (!current.sha256().equals(typography.current().sha256())) {
                    throw new IllegalArgumentException(
                            "published typography capture hash changed");
                }
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
                    "application/json", encoded.clone());
            content.put("evidenceArtifact", artifactMap(evidence));
            return Map.copyOf(content);
        }
        if (result instanceof HarnessResponse.Result.LayoutDiagnostic layout) {
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
                if (layout.currentPngBase64() == null) {
                    throw new IllegalArgumentException(
                            "accepted layout evidence is missing PNG bytes");
                }
                byte[] png = Base64.getDecoder().decode(layout.currentPngBase64());
                ArtifactReference current = artifacts.publish("image/png", png.clone());
                if (!current.sha256().equals(layout.current().sha256())) {
                    throw new IllegalArgumentException(
                            "published layout capture hash changed");
                }
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
                    "application/json", encoded.clone());
            content.put("evidenceArtifact", artifactMap(evidence));
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
            return Map.copyOf(content);
        }
        throw new AssertionError("Unhandled protocol result " + result.getClass().getName());
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

    private void offloadLarge(LinkedHashMap<String, Object> content, byte[] encoded,
            String mediaType, String... bulkyFields) {
        if (encoded.length <= artifactThresholdBytes) {
            return;
        }
        ArtifactReference reference = artifacts.publish(mediaType, encoded.clone());
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
            Map<String, Object> arguments) {
        DiagnosticCode code = switch (error.code()) {
            case NOT_FOUND -> DiagnosticCode.LOCATOR_NOT_FOUND;
            case STRICTNESS_VIOLATION -> DiagnosticCode.LOCATOR_AMBIGUOUS;
            case TIMEOUT -> DiagnosticCode.DEADLINE_EXCEEDED;
            case PROTOCOL_VERSION_MISMATCH -> DiagnosticCode.SCHEMA_CONFLICT;
            case INTERNAL_ERROR, RENDER_THREAD_FAILURE ->
                    DiagnosticCode.INTERNAL_ERROR;
            default -> DiagnosticCode.STATE_NOT_READY;
        };
        return diagnostic(
                error.requestId(), sequence, operation, arguments, code,
                error.message(), List.of(),
                error.locator(), error.candidates(), error.details(),
                error.elapsedMillis(), error.traceId(),
                new DiagnosticEnvelope.StateIdentity(
                        null, error.sessionId(), error.lastSnapshotRevision(), null),
                error.traceReference() == null
                        ? List.of() : List.of(error.traceReference()));
    }

    private McpSchema.CallToolResult diagnostic(
            String requestId,
            long sequence,
            String operation,
            Map<String, Object> arguments,
            DiagnosticCode requestedCode,
            String message,
            List<DiagnosticEnvelope.FieldProblem> problems,
            DiagnosticEnvelope.StateIdentity stateIdentity) {
        return diagnostic(
                requestId, sequence, operation, arguments, requestedCode,
                message, problems, null, List.of(), Map.of(), null, null,
                stateIdentity, List.of());
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
            Long operationElapsedMillis,
            String traceId,
            DiagnosticEnvelope.StateIdentity stateIdentity,
            List<String> evidenceRefs) {
        String fingerprint = diagnosticFingerprint(
                operation, arguments, requestedCode, problems);
        boolean transientDiagnostic = requestedCode.defaultDisposition()
                == DiagnosticEnvelope.Disposition.TRANSIENT;
        int equivalentConsumed = transientDiagnostic
                ? diagnosticAttempts.merge(fingerprint, 1, Integer::sum)
                : 0;
        int consumed = transientDiagnostic
                ? sessionRecoveryAttempts.merge(
                        sessionKey(arguments), 1, Integer::sum)
                : sessionRecoveryAttempts.getOrDefault(sessionKey(arguments), 0);
        int limit = HarnessToolCatalog.recoveryPolicy().maxSchemaRecoveries();
        DiagnosticCode code = requestedCode;
        String terminatingRule = recoveryRule(requestedCode);
        if (transientDiagnostic && equivalentConsumed > limit) {
            code = DiagnosticCode.LOOP_DETECTED;
            terminatingRule = "equivalent-diagnostic-budget/v1";
        } else if (transientDiagnostic && consumed > limit) {
            code = DiagnosticCode.RECOVERY_BUDGET_EXHAUSTED;
            terminatingRule = "session-recovery-budget/v1";
        } else if (requestedCode.defaultDisposition()
                == DiagnosticEnvelope.Disposition.TERMINAL) {
            terminatingRule = "terminal-code/v1";
        }
        long elapsedMillis = Math.max(
                0, (nanoClock.getAsLong() - startedNanos) / 1_000_000);
        DiagnosticEnvelope envelope = DiagnosticEnvelope.create(
                requestId, sequence, operation, code, message, problems,
                locator, candidates, details, operationElapsedMillis, traceId,
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
        return errorResult(content);
    }

    private static String sessionKey(Map<String, Object> arguments) {
        Object value = arguments.get("sessionId");
        return value instanceof String sessionId && !sessionId.isBlank()
                ? sessionId : "catalog";
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

    private McpSchema.CallToolResult localError(
            String operation,
            long sequence,
            Map<String, Object> arguments,
            String code,
            String message) {
        return diagnostic(
                "mcp-" + Long.toUnsignedString(sequence),
                sequence,
                operation,
                arguments,
                DiagnosticCode.INTERNAL_ERROR,
                message + " (" + code + ")",
                List.of(),
                null);
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
        scheduler.dispose();
        executor.close();
    }
}
