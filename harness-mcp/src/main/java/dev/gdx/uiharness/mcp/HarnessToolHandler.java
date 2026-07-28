package dev.gdx.uiharness.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.gdx.uiharness.protocol.Command;
import dev.gdx.uiharness.protocol.HarnessProtocolService;
import dev.gdx.uiharness.protocol.HarnessRequest;
import dev.gdx.uiharness.protocol.HarnessResponse;
import dev.gdx.uiharness.protocol.ProtocolError;
import dev.gdx.uiharness.protocol.ProtocolJson;
import dev.gdx.uiharness.protocol.ProtocolVersion;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/** Translates each MCP tool call into exactly one transport-neutral protocol request. */
public final class HarnessToolHandler implements AutoCloseable {
    private static final long DEFAULT_DEADLINE_MILLIS = 30_000;
    private static final int DEFAULT_ARTIFACT_THRESHOLD_BYTES = 64 * 1_024;
    private static final ObjectMapper COMMAND_MAPPER = ProtocolJson.mapper().copy();

    private final Function<HarnessRequest, CompletionStage<HarnessResponse>> protocol;
    private final ArtifactReference.Publisher artifacts;
    private final ExecutorService executor;
    private final Scheduler scheduler;
    private final int artifactThresholdBytes;
    private final HarnessToolCatalog catalog = new HarnessToolCatalog();
    private final AtomicLong requestSequence = new AtomicLong();

    /** Creates a handler that owns a Java 25 virtual-thread executor. */
    public HarnessToolHandler(
            HarnessProtocolService protocol, ArtifactReference.Publisher artifacts) {
        this(Objects.requireNonNull(protocol, "protocol")::execute, artifacts,
                Executors.newVirtualThreadPerTaskExecutor(), DEFAULT_ARTIFACT_THRESHOLD_BYTES);
    }

    HarnessToolHandler(Function<HarnessRequest, CompletionStage<HarnessResponse>> protocol,
            ArtifactReference.Publisher artifacts, ExecutorService executor,
            int artifactThresholdBytes) {
        this.protocol = Objects.requireNonNull(protocol, "protocol");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.executor = Objects.requireNonNull(executor, "executor");
        if (artifactThresholdBytes <= 0) {
            throw new IllegalArgumentException("artifactThresholdBytes must be positive");
        }
        this.artifactThresholdBytes = artifactThresholdBytes;
        scheduler = Schedulers.fromExecutorService(executor);
    }

    /** Handles one approved tool call asynchronously on an owned virtual thread. */
    public Mono<McpSchema.CallToolResult> handle(McpSchema.CallToolRequest call) {
        Objects.requireNonNull(call, "call");
        return Mono.defer(() -> {
            Map<String, Object> arguments = call.arguments() == null ? Map.of() : call.arguments();
            McpSchema.Tool tool;
            try {
                tool = catalog.tool(call.name());
            } catch (IllegalArgumentException failure) {
                return Mono.just(localError("unknown-tool", failure.getMessage()));
            }
            var validation = McpJsonDefaults.getSchemaValidator()
                    .validate(tool.inputSchema(), arguments);
            if (!validation.valid()) {
                return Mono.just(localError("invalid-arguments", "Arguments do not match tool schema"));
            }

            HarnessRequest request;
            try {
                request = toProtocolRequest(call.name(), arguments);
            } catch (RuntimeException failure) {
                return Mono.just(localError("invalid-arguments", "Arguments could not be decoded"));
            }

            CompletionStage<HarnessResponse> stage;
            try {
                stage = Objects.requireNonNull(protocol.apply(request), "protocol stage");
            } catch (RuntimeException failure) {
                return Mono.just(localError("internal-error", "Protocol invocation failed"));
            }
            return Mono.fromFuture(stage.toCompletableFuture())
                    .map(this::toMcpResult)
                    .onErrorResume(failure -> Mono.just(
                            localError("internal-error", "Protocol invocation failed")));
        }).subscribeOn(scheduler);
    }

    private HarnessRequest toProtocolRequest(String toolName, Map<String, Object> arguments) {
        LinkedHashMap<String, Object> commandJson = new LinkedHashMap<>(arguments);
        Object sessionValue = commandJson.remove("sessionId");
        Object deadlineValue = commandJson.remove("deadlineMillis");
        String sessionId = sessionValue == null ? "catalog" : (String) sessionValue;
        long deadlineMillis = deadlineValue == null
                ? DEFAULT_DEADLINE_MILLIS : ((Number) deadlineValue).longValue();
        commandJson.put("type", commandType(toolName));
        Command command = COMMAND_MAPPER.convertValue(commandJson, Command.class);
        String requestId = "mcp-" + Long.toUnsignedString(requestSequence.incrementAndGet());
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
            case "ui_trace_start" -> "trace-start";
            case "ui_trace_stop" -> "trace-stop";
            case "ui_capabilities" -> "capabilities";
            default -> throw new IllegalArgumentException("Unknown tool: " + toolName);
        };
    }

    private McpSchema.CallToolResult toMcpResult(HarnessResponse response) {
        if (response instanceof HarnessResponse.Failure failure) {
            return protocolError(failure.error());
        }
        HarnessResponse.Success success = (HarnessResponse.Success) response;
        try {
            Map<String, Object> content = structured(success.result());
            return McpSchema.CallToolResult.builder()
                    .structuredContent(content)
                    .addTextContent(compactText(content))
                    .isError(false)
                    .build();
        } catch (ArtifactReference.ArtifactUnavailableException failure) {
            return localError("artifact-unavailable", failure.getMessage());
        } catch (RuntimeException failure) {
            return localError("internal-error", "Result translation failed");
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
            return Map.copyOf(content);
        }
        if (result instanceof HarnessResponse.Result.Snapshot snapshotResult) {
            var snapshot = snapshotResult.snapshot();
            LinkedHashMap<String, Object> content = content("snapshot-summary");
            content.put("revision", snapshot.revision());
            content.put("frame", snapshot.frame());
            content.put("rootId", snapshot.rootId());
            content.put("nodeCount", snapshot.nodes().size());
            offloadLarge(content, encoded, "application/json");
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
        if (result instanceof HarnessResponse.Result.TraceStarted started) {
            LinkedHashMap<String, Object> content = content("trace-started");
            content.put("traceId", started.traceId());
            return Map.copyOf(content);
        }
        if (result instanceof HarnessResponse.Result.TraceStopped stopped) {
            LinkedHashMap<String, Object> content = content("trace-stopped");
            content.put("traceId", stopped.traceId());
            content.put("traceReference", stopped.traceReference());
            content.put("eventCount", stopped.eventCount());
            content.put("bytes", stopped.bytes());
            return Map.copyOf(content);
        }
        throw new AssertionError("Unhandled protocol result " + result.getClass().getName());
    }

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

    private static McpSchema.CallToolResult protocolError(ProtocolError error) {
        LinkedHashMap<String, Object> content = content("error");
        content.put("code", error.code().wireName());
        content.put("message", error.message());
        content.put("requestId", error.requestId());
        content.put("sessionId", error.sessionId());
        if (!error.details().isEmpty()) {
            content.put("details", error.details());
        }
        if (error.traceId() != null) {
            content.put("traceId", error.traceId());
        }
        return errorResult(content);
    }

    private static McpSchema.CallToolResult localError(String code, String message) {
        LinkedHashMap<String, Object> content = content("error");
        content.put("code", code);
        content.put("message", message);
        return errorResult(content);
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
