package dev.gdx.uiharness.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gdx.uiharness.core.action.ActionResult;
import dev.gdx.uiharness.core.action.Harness;
import dev.gdx.uiharness.core.capture.CaptureRequest;
import dev.gdx.uiharness.core.capture.CapturedImage;
import dev.gdx.uiharness.core.capture.ScreenCapture;
import dev.gdx.uiharness.core.locator.Locator;
import dev.gdx.uiharness.core.locator.LocatorEngine;
import dev.gdx.uiharness.core.locator.StrictResolution;
import dev.gdx.uiharness.core.model.Bounds;
import dev.gdx.uiharness.core.model.Role;
import dev.gdx.uiharness.core.model.SemanticNode;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import dev.gdx.uiharness.core.model.SemanticState;
import dev.gdx.uiharness.core.time.Deadline;
import dev.gdx.uiharness.core.time.MonotonicClock;
import dev.gdx.uiharness.core.wait.FrameSignal;
import dev.gdx.uiharness.core.wait.WaitEngine;
import dev.gdx.uiharness.protocol.CapabilitySet;
import dev.gdx.uiharness.protocol.Command;
import dev.gdx.uiharness.protocol.HarnessProtocolService;
import dev.gdx.uiharness.protocol.HarnessRequest;
import dev.gdx.uiharness.protocol.HarnessResponse;
import dev.gdx.uiharness.protocol.ProtocolJson;
import dev.gdx.uiharness.protocol.ProtocolError;
import dev.gdx.uiharness.protocol.ProtocolVersion;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

final class HarnessMcpServerContractTest {
    private static final MonotonicClock CLOCK = System::nanoTime;
    private static final SemanticSnapshot SNAPSHOT = snapshot();

    @Test void clickToolReturnsStructuredActionResultFromOneProtocolRequestOnVirtualThread() {
        RecordingHarness harness = new RecordingHarness();
        RecordingArtifacts artifacts = new RecordingArtifacts();
        try (HarnessToolHandler handler = new HarnessToolHandler(service(harness), artifacts)) {
            McpSchema.CallToolResult result = handler.handle(call("ui_action", Map.of(
                    "sessionId", "game",
                    "locator", Map.of(
                            "kind", "filter",
                            "locator", Map.of("kind", "role", "role", "button"),
                            "filter", Map.of("kind", "name", "match",
                                    Map.of("mode", "exact", "source", "Save"))),
                    "action", Map.of("kind", "click", "pointer", 0, "button", 0,
                            "force", false)))).block(Duration.ofSeconds(10));

            assertNotNull(result);
            assertFalse(result.isError());
            Map<String, Object> content = structured(result);
            assertEquals("action-result", content.get("kind"));
            assertEquals("unavailable",
                    ((Map<?, ?>) content.get("progress")).get("status"));
            assertEquals(0,
                    ((Number) ((Map<?, ?>) content.get("recovery"))
                            .get("consumed")).intValue());
            assertEquals(1, harness.actionCalls.get());
            assertTrue(harness.actionThreadWasVirtual);
            assertEquals("Save", new StrictResolution()
                    .resolveStrict(SNAPSHOT, harness.lastLocator).accessibleName());
        }
    }

    @Test void capabilityAndSnapshotResultsAreCompact() {
        try (HarnessToolHandler handler = new HarnessToolHandler(
                service(new RecordingHarness()), new RecordingArtifacts())) {
            McpSchema.CallToolResult capabilities = handler.handle(call("ui_capabilities",
                    Map.of("sessionId", "game"))).block(Duration.ofSeconds(10));
            assertEquals("capabilities-result", structured(capabilities).get("kind"));
            assertTrue(((List<?>) structured(capabilities).get("capabilities")).contains("action"));
            assertEquals("operation-catalog/v1",
                    structured(capabilities).get("catalogSchemaVersion"));
            assertEquals(12, ((List<?>) structured(capabilities).get("operations")).size());
            assertTrue(String.valueOf(structured(capabilities).get("operations"))
                    .contains("maxWidth=1280"));
            assertTrue(String.valueOf(structured(capabilities).get("operations"))
                    .contains("pointer=0"));

            McpSchema.CallToolResult snapshot = handler.handle(call("ui_snapshot",
                    Map.of("sessionId", "game"))).block(Duration.ofSeconds(10));
            Map<String, Object> content = structured(snapshot);
            assertEquals("snapshot-summary", content.get("kind"));
            assertEquals(1, ((Number) content.get("nodeCount")).intValue());
            assertFalse(content.containsKey("nodes"));
        }
    }

    @Test void screenshotAndLargeResultsUseInjectedOpaqueArtifactReferences() {
        RecordingArtifacts artifacts = new RecordingArtifacts();
        try (HarnessToolHandler handler = new HarnessToolHandler(service(new RecordingHarness()), artifacts)) {
            McpSchema.CallToolResult screenshot = handler.handle(call("ui_screenshot", Map.of(
                    "sessionId", "game", "maxWidth", 10, "maxHeight", 10,
                    "maxPixels", 100, "maxPngBytes", 1024))).block(Duration.ofSeconds(10));
            Map<String, Object> screenshotContent = structured(screenshot);
            assertEquals("screenshot-result", screenshotContent.get("kind"));
            assertFalse(screenshotContent.containsKey("pngBase64"));
            assertEquals("artifact:1", artifact(screenshotContent).get("reference"));
            assertEquals(List.of((byte) 1, (byte) 2, (byte) 3), boxed(artifacts.lastBytes));
        }

        CompletableFuture<HarnessResponse> completed = CompletableFuture.completedFuture(
                new HarnessResponse.Success(ProtocolVersion.V1, "mcp-1", "game",
                        new HarnessResponse.Result.Action(1, 2, "clicked", largeEvidence())));
        RecordingArtifacts largeArtifacts = new RecordingArtifacts();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                HarnessToolHandler handler = new HarnessToolHandler(
                        ignored -> completed, largeArtifacts, executor, 1024)) {
            McpSchema.CallToolResult result = handler.handle(call("ui_action", Map.of(
                    "sessionId", "game",
                    "locator", Map.of("kind", "role", "role", "button"),
                    "action", Map.of("kind", "click", "pointer", 0, "button", 0,
                            "force", false)))).block(Duration.ofSeconds(10));
            assertEquals("action-result", structured(result).get("kind"));
            assertEquals("artifact:1", artifact(structured(result)).get("reference"));
            assertNotNull(largeArtifacts.lastBytes);
        }
    }

    @Test void malformedCallsReturnStructuredErrorsWithoutInvokingProtocol() {
        AtomicInteger calls = new AtomicInteger();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                HarnessToolHandler handler = new HarnessToolHandler(request -> {
                    calls.incrementAndGet();
                    return CompletableFuture.failedFuture(new AssertionError());
                }, new RecordingArtifacts(), executor, 1024)) {
            McpSchema.CallToolResult result = handler.handle(call("ui_action", Map.of(
                    "sessionId", "game", "path", "/tmp/attack")))
                    .block(Duration.ofSeconds(10));
            assertTrue(result.isError());
            assertEquals("MISSING_ARGUMENT", structured(result).get("code"));
            assertEquals("diagnostic-envelope/v1",
                    structured(result).get("schemaVersion"));
            assertEquals("transient", structured(result).get("disposition"));
            assertEquals(Boolean.TRUE, structured(result).get("retryable"));
            assertEquals(0, calls.get());
        }
    }

    @Test void rejectedScreenshotShapesReportEveryFieldAndAValidMinimalExample() {
        AtomicInteger calls = new AtomicInteger();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                HarnessToolHandler handler = new HarnessToolHandler(request -> {
                    calls.incrementAndGet();
                    return CompletableFuture.failedFuture(new AssertionError());
                }, new RecordingArtifacts(), executor, 1024)) {
            McpSchema.CallToolResult result = handler.handle(call(
                    "ui_screenshot",
                    Map.of("sessionId", "game", "maxBytes", 1024)))
                    .block(Duration.ofSeconds(10));
            Map<String, Object> diagnostic = structured(result);

            assertTrue(result.isError());
            assertEquals("UNKNOWN_ARGUMENT", diagnostic.get("code"));
            assertEquals(List.of(
                    "$.maxBytes", "$.maxHeight", "$.maxPixels",
                    "$.maxPngBytes", "$.maxWidth"),
                    problemPaths(diagnostic));
            assertTrue(String.valueOf(diagnostic.get("problems"))
                    .contains("inclusive range [1,8192]"));
            assertTrue(String.valueOf(diagnostic.get("minimalExample"))
                    .contains("maxWidth=1280"));
            assertEquals(0, calls.get());
        }
    }

    @Test void malformedScreenshotCanApplyReturnedCorrectionAndSucceedWithinBudget() {
        AtomicInteger calls = new AtomicInteger();
        RecordingArtifacts artifacts = new RecordingArtifacts();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                HarnessToolHandler handler = new HarnessToolHandler(request -> {
                    calls.incrementAndGet();
                    return service(new RecordingHarness()).execute(request);
                }, artifacts, executor, 1024)) {
            Map<String, Object> diagnostic = structured(handler.handle(call(
                    "ui_screenshot",
                    Map.of("sessionId", "game", "maxBytes", 1_024)))
                    .block(Duration.ofSeconds(10)));
            @SuppressWarnings("unchecked")
            Map<String, Object> correction =
                    (Map<String, Object>) diagnostic.get("minimalExample");

            McpSchema.CallToolResult corrected = handler.handle(call(
                    "ui_screenshot", correction)).block(Duration.ofSeconds(10));
            Map<String, Object> content = structured(corrected);

            assertFalse(corrected.isError());
            assertEquals("screenshot-result", content.get("kind"));
            assertEquals(1, calls.get());
            assertEquals(1,
                    ((Number) ((Map<?, ?>) content.get("recovery"))
                            .get("consumed")).intValue());
        }
    }

    @Test void rejectedCompareShapesReportRangesObservedValuesAndMinimalExample() {
        AtomicInteger calls = new AtomicInteger();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                HarnessToolHandler handler = new HarnessToolHandler(request -> {
                    calls.incrementAndGet();
                    return CompletableFuture.failedFuture(new AssertionError());
                }, new RecordingArtifacts(), executor, 1024)) {
            LinkedHashMap<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("sessionId", "");
            arguments.put("referenceId", "main");
            arguments.put("policyId", "pixel-exact");
            arguments.put("policyVersion", 1.5);
            arguments.put("viewportId", "main");
            arguments.put("maxIterations", 0);
            arguments.put("maxDurationMillis", 120_001);
            arguments.put("maxWidth", 8_193);
            arguments.put("maxHeight", 720);
            arguments.put("maxPixelCount", 1280L * 720);
            arguments.put("maxPixels", 0);
            arguments.put("maxPngBytes", "large");
            McpSchema.CallToolResult result = handler.handle(call(
                    "ui_inspect_compare", arguments)).block(Duration.ofSeconds(10));
            Map<String, Object> diagnostic = structured(result);
            String problems = String.valueOf(diagnostic.get("problems"));

            assertTrue(result.isError());
            assertTrue(problems.contains("$.sessionId"));
            assertTrue(problems.contains("$.policyVersion"));
            assertTrue(problems.contains("$.maxIterations"));
            assertTrue(problems.contains("$.maxDurationMillis"));
            assertTrue(problems.contains("$.maxWidth"));
            assertTrue(problems.contains("$.maxPixels"));
            assertTrue(problems.contains("$.maxPngBytes"));
            assertTrue(problems.contains("$.maxPixelCount"));
            assertTrue(problems.contains("observed"));
            assertTrue(String.valueOf(diagnostic.get("minimalExample"))
                    .contains("maxWidth=1280"));
            assertEquals(0, calls.get());
        }
    }

    @Test void clickValidationReportsEveryIndependentFieldInPathOrder() {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                HarnessToolHandler handler = new HarnessToolHandler(
                        ignored -> CompletableFuture.failedFuture(new AssertionError()),
                        new RecordingArtifacts(), executor, 1024)) {
            McpSchema.CallToolResult result = handler.handle(call(
                    "ui_action",
                    Map.of(
                            "sessionId", "game",
                            "locator", Map.of("kind", "role", "role", "button"),
                            "action", Map.of(
                                    "kind", "click",
                                    "pointer", "zero",
                                    "button", 1.5,
                                    "force", "yes",
                                    "script", "ignored"))))
                    .block(Duration.ofSeconds(10));
            Map<String, Object> diagnostic = structured(result);

            assertTrue(result.isError());
            assertEquals(List.of(
                    "$.action.button", "$.action.force",
                    "$.action.pointer", "$.action.script"),
                    problemPaths(diagnostic));
            assertTrue(String.valueOf(diagnostic.get("minimalExample"))
                    .contains("pointer=0"));
        }
    }

    @Test void equivalentInvalidPayloadsShareBudgetAcrossMapOrderAndRequestIds() {
        AtomicLong nanos = new AtomicLong(1_000_000_000L);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                HarnessToolHandler handler = new HarnessToolHandler(
                        ignored -> CompletableFuture.failedFuture(new AssertionError()),
                        new RecordingArtifacts(), executor, 1024, nanos::get)) {
            LinkedHashMap<String, Object> first = new LinkedHashMap<>();
            first.put("sessionId", "game");
            first.put("maxBytes", 1_024);
            LinkedHashMap<String, Object> reordered = new LinkedHashMap<>();
            reordered.put("maxBytes", 1_024);
            reordered.put("sessionId", "game");

            for (int attempt = 0; attempt < 3; attempt++) {
                assertEquals("UNKNOWN_ARGUMENT", structured(handler.handle(call(
                        "ui_screenshot", attempt % 2 == 0 ? first : reordered))
                        .block(Duration.ofSeconds(10))).get("code"));
            }
            nanos.set(1_025_000_000L);
            Map<String, Object> terminal = structured(handler.handle(call(
                    "ui_screenshot", reordered)).block(Duration.ofSeconds(10)));

            assertEquals("LOOP_DETECTED", terminal.get("code"));
            assertEquals("terminal", terminal.get("disposition"));
            assertEquals(Boolean.FALSE, terminal.get("retryable"));
            @SuppressWarnings("unchecked")
            Map<String, Object> recovery =
                    (Map<String, Object>) terminal.get("recovery");
            assertEquals(4, ((Number) recovery.get("consumed")).intValue());
            assertEquals(0, ((Number) recovery.get("remaining")).intValue());
            assertEquals(25, ((Number) recovery.get("elapsedMillis")).intValue());
        }
    }

    @Test void diagnosticOverflowFailsClosedWithoutSilentFieldTruncation() {
        LinkedHashMap<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("sessionId", "game");
        for (int index = 0; index < 300; index++) {
            arguments.put("unknown" + index, index);
        }
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                HarnessToolHandler handler = new HarnessToolHandler(
                        ignored -> CompletableFuture.failedFuture(new AssertionError()),
                        new RecordingArtifacts(), executor, 1024)) {
            Map<String, Object> diagnostic = structured(handler.handle(call(
                    "ui_screenshot", arguments)).block(Duration.ofSeconds(10)));

            assertEquals("SCHEMA_CONFLICT", diagnostic.get("code"));
            assertEquals("terminal", diagnostic.get("disposition"));
            assertEquals(256,
                    ((List<?>) diagnostic.get("problems")).size());
            assertEquals("$", problemPaths(diagnostic).getFirst());
        }
    }

    @Test void protocolFailureRetainsLocatorCandidatesStateAndTraceEvidence() {
        ProtocolError error = new ProtocolError(
                ProtocolError.Code.STRICTNESS_VIOLATION,
                "Locator resolved to multiple actors",
                "mcp-1",
                "game",
                "text=Sign in",
                64,
                20L,
                "trace:strict",
                List.of(
                        Map.of("actorId", "first"),
                        Map.of("actorId", "second")),
                Map.of(
                        "matchCount", "[redacted] 2",
                        "lastActionability", "visible"),
                "trace-1");
        CompletableFuture<HarnessResponse> response = CompletableFuture.completedFuture(
                new HarnessResponse.Failure(
                        ProtocolVersion.V1, "mcp-1", "game", error));
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                HarnessToolHandler handler = new HarnessToolHandler(
                        ignored -> response, new RecordingArtifacts(), executor, 1024)) {
            Map<String, Object> diagnostic = structured(handler.handle(call(
                    "ui_action",
                    Map.of(
                            "sessionId", "game",
                            "locator", Map.of(
                                    "kind", "text",
                                    "field", "text",
                                    "match", Map.of(
                                            "mode", "exact", "source", "Sign in")),
                            "action", Map.of(
                                    "kind", "click", "pointer", 0,
                                    "button", 0, "force", false))))
                    .block(Duration.ofSeconds(10)));

            assertEquals("LOCATOR_AMBIGUOUS", diagnostic.get("code"));
            assertEquals("text=Sign in", diagnostic.get("locator"));
            assertEquals(2, ((List<?>) diagnostic.get("candidates")).size());
            assertEquals("[redacted] 2",
                    ((Map<?, ?>) diagnostic.get("details")).get("matchCount"));
            assertEquals(64, ((Number) diagnostic.get("elapsedMillis")).intValue());
            assertEquals("trace-1", diagnostic.get("traceId"));
            assertEquals(List.of("trace:strict"), diagnostic.get("evidenceRefs"));
        }
    }

    @Test void typographyFailurePublishesBoundedEvidenceArtifact() {
        CompletableFuture<HarnessResponse> response = CompletableFuture.completedFuture(
                new HarnessResponse.Success(
                        ProtocolVersion.V1,
                        "mcp-1",
                        "game",
                        new HarnessResponse.Result.TypographyDiagnostic(
                                "incomplete",
                                "title-reference",
                                null,
                                List.of(),
                                List.of(new HarnessResponse.ComparisonDiagnosticData(
                                        "REFERENCE_NOT_FOUND",
                                        "$.referenceId",
                                        "registered typography reference",
                                        "title-reference")),
                                2,
                                null)));
        RecordingArtifacts artifacts = new RecordingArtifacts();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                HarnessToolHandler handler = new HarnessToolHandler(
                        ignored -> response, artifacts, executor, 1024)) {
            McpSchema.CallToolResult result = handler.handle(call(
                    "ui_typography_diagnose",
                    Map.of(
                            "sessionId", "game",
                            "referenceId", "title-reference",
                            "viewportId", "main",
                            "maxDurationMillis", 30_000,
                            "maxResults", 16,
                            "maxWidth", 1920,
                            "maxHeight", 1080,
                            "maxPixels", 2_073_600,
                            "maxPngBytes", 4_194_304)))
                    .block(Duration.ofSeconds(10));
            Map<String, Object> content = structured(result);

            assertFalse(result.isError());
            assertEquals("typography-diagnostic-result", content.get("kind"));
            assertEquals("incomplete", content.get("status"));
            assertEquals("artifact:1",
                    ((Map<?, ?>) content.get("evidenceArtifact")).get("reference"));
        }
    }

    @Test void layoutFailurePublishesBoundedEvidenceArtifact() {
        CompletableFuture<HarnessResponse> response = CompletableFuture.completedFuture(
                new HarnessResponse.Success(
                        ProtocolVersion.V1,
                        "mcp-1",
                        "game",
                        new HarnessResponse.Result.LayoutDiagnostic(
                                "incomplete",
                                "layout-reference",
                                null,
                                List.of(),
                                null,
                                null,
                                List.of(new HarnessResponse.ComparisonDiagnosticData(
                                        "REFERENCE_NOT_FOUND",
                                        "$.referenceId",
                                        "registered layout reference",
                                        "layout-reference")),
                                2,
                                null)));
        RecordingArtifacts artifacts = new RecordingArtifacts();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                HarnessToolHandler handler = new HarnessToolHandler(
                        ignored -> response, artifacts, executor, 1024)) {
            McpSchema.CallToolResult result = handler.handle(call(
                    "ui_layout_diagnose",
                    Map.of(
                            "sessionId", "game",
                            "referenceId", "layout-reference",
                            "viewportId", "main",
                            "maxDurationMillis", 2_000,
                            "maxResults", 16,
                            "maxWidth", 1920,
                            "maxHeight", 1080,
                            "maxPixels", 2_073_600,
                            "maxPngBytes", 4_194_304)))
                    .block(Duration.ofSeconds(10));
            Map<String, Object> content = structured(result);

            assertFalse(result.isError());
            assertEquals("layout-diagnostic-result", content.get("kind"));
            assertEquals("incomplete", content.get("status"));
            assertEquals("artifact:1",
                    ((Map<?, ?>) content.get("evidenceArtifact")).get("reference"));
        }
    }

    @Test void cancellingToolCallCancelsTheProtocolStage() throws Exception {
        AtomicReference<HarnessRequest> observed = new AtomicReference<>();
        CompletableFuture<HarnessResponse> pending = new CompletableFuture<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                HarnessToolHandler handler = new HarnessToolHandler(request -> {
                    observed.set(request);
                    return pending;
                }, new RecordingArtifacts(), executor, 1024)) {
            var subscription = handler.handle(call("ui_capabilities", Map.of("sessionId", "game")))
                    .subscribe();
            for (int attempt = 0; attempt < 100 && observed.get() == null; attempt++) {
                Thread.sleep(5);
            }
            assertNotNull(observed.get());
            subscription.dispose();
            for (int attempt = 0; attempt < 100 && !pending.isCancelled(); attempt++) {
                Thread.sleep(5);
            }
            assertTrue(pending.isCancelled());
        }
    }

    @Test void artifactReferencesRejectFilesystemPaths() {
        assertThrows(IllegalArgumentException.class, () -> new ArtifactReference(
                "C:/tmp/capture.png", "image/png", 1, "0".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> new ArtifactReference(
                "../capture.png", "image/png", 1, "0".repeat(64)));
    }

    @Test void traceStopRejectsFilesystemLookingProtocolReferences() {
        CompletableFuture<HarnessResponse> response = CompletableFuture.completedFuture(
                new HarnessResponse.Success(ProtocolVersion.V1, "mcp-1", "game",
                        new HarnessResponse.Result.TraceStopped(
                                "trace-1", "/tmp/trace.zip", 1, 32)));
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                HarnessToolHandler handler = new HarnessToolHandler(
                        ignored -> response, new RecordingArtifacts(), executor, 1024)) {
            McpSchema.CallToolResult result = handler.handle(call(
                    "ui_trace_stop", Map.of("sessionId", "game")))
                    .block(Duration.ofSeconds(10));
            assertTrue(result.isError());
            assertEquals("INTERNAL_ERROR", structured(result).get("code"));
        }
    }

    @Test void deeplyNestedAndOversizedLocatorGraphsAreRejectedBeforeProtocolDispatch() {
        AtomicInteger calls = new AtomicInteger();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                HarnessToolHandler handler = new HarnessToolHandler(request -> {
                    calls.incrementAndGet();
                    return CompletableFuture.failedFuture(new AssertionError());
                }, new RecordingArtifacts(), executor, 1024)) {
            assertInvalidLocator(handler, deepLocator(HarnessToolHandler.MAX_LOCATOR_DEPTH + 1));
            assertInvalidLocator(handler, wideLocator(12));
            assertEquals(0, calls.get());
        }
    }

    @Test
    @Timeout(10)
    void stdioContinuesReadingAndDrainsInFlightCallsAfterEof() throws Exception {
        RecordingHarness harness = new RecordingHarness();
        CompletableFuture<ActionResult> pendingAction = new CompletableFuture<>();
        harness.actionResult = pendingAction;
        try (PipedInputStream serverInput = new PipedInputStream();
                PipedOutputStream clientOutput = new PipedOutputStream(serverInput);
                PipedInputStream clientInput = new PipedInputStream();
                PipedOutputStream serverOutput = new PipedOutputStream(clientInput);
                HarnessMcpServer server = HarnessMcpServer.open(
                        service(harness), new RecordingArtifacts(), serverInput, serverOutput);
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                        clientOutput, StandardCharsets.UTF_8));
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        clientInput, StandardCharsets.UTF_8));
                ExecutorService waiter = Executors.newVirtualThreadPerTaskExecutor()) {
            initialize(writer, reader);
            send(writer, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
            send(writer, actionCallJson(3));
            send(writer, "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"ping\",\"params\":{}}");
            assertEquals(4, read(reader).path("id").asInt());

            closeStdin(clientOutput);
            CompletableFuture<Void> termination = CompletableFuture.runAsync(
                    server::awaitTermination, waiter);
            Thread.sleep(50);
            assertFalse(termination.isDone());

            pendingAction.complete(new ActionResult(
                    1, 2, "clicked", Map.of("target", "root")));
            JsonNode action = read(reader);
            assertEquals(3, action.path("id").asInt());
            assertEquals("action-result",
                    action.at("/result/structuredContent/kind").asText());
            termination.get(2, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    @Test
    @Timeout(10)
    void stdioCancellationReachesAnInFlightProtocolCall() throws Exception {
        RecordingHarness harness = new RecordingHarness();
        CompletableFuture<ActionResult> pendingAction = new CompletableFuture<>();
        harness.actionResult = pendingAction;
        try (PipedInputStream serverInput = new PipedInputStream();
                PipedOutputStream clientOutput = new PipedOutputStream(serverInput);
                PipedInputStream clientInput = new PipedInputStream();
                PipedOutputStream serverOutput = new PipedOutputStream(clientInput);
                HarnessMcpServer server = HarnessMcpServer.open(
                        service(harness), new RecordingArtifacts(), serverInput, serverOutput);
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                        clientOutput, StandardCharsets.UTF_8));
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        clientInput, StandardCharsets.UTF_8))) {
            initialize(writer, reader);
            send(writer, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
            send(writer, actionCallJson(3));
            for (int attempt = 0; attempt < 100 && harness.actionCalls.get() == 0; attempt++) {
                Thread.sleep(5);
            }
            assertEquals(1, harness.actionCalls.get());

            send(writer, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\","
                    + "\"params\":{\"requestId\":3,\"reason\":\"contract cancellation\"}}");
            send(writer, "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"ping\",\"params\":{}}");
            assertEquals(4, read(reader).path("id").asInt());
            closeStdin(clientOutput);
            server.awaitTermination();
            assertTrue(pendingAction.isCancelled());
        }
    }

    @Test
    @Timeout(10)
    void stdioInitializeListAndCallRoundTripThenCleanlyCloses() throws Exception {
        RecordingHarness harness = new RecordingHarness();
        try (PipedInputStream serverInput = new PipedInputStream();
                PipedOutputStream clientOutput = new PipedOutputStream(serverInput);
                PipedInputStream clientInput = new PipedInputStream();
                PipedOutputStream serverOutput = new PipedOutputStream(clientInput);
                HarnessMcpServer server = HarnessMcpServer.open(
                        service(harness), new RecordingArtifacts(), serverInput, serverOutput);
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                        clientOutput, StandardCharsets.UTF_8));
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        clientInput, StandardCharsets.UTF_8))) {
            assertNotNull(server);
            send(writer, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                    + "\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
                    + "\"clientInfo\":{\"name\":\"contract\",\"version\":\"1.0\"}}}");
            JsonNode initialize = read(reader);
            assertEquals(1, initialize.path("id").asInt());
            assertEquals("libgdx-ui-harness", initialize.at("/result/serverInfo/name").asText());
            assertTrue(initialize.at("/result/capabilities/tools").isObject());

            send(writer, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
            send(writer, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}");
            JsonNode listed = read(reader);
            assertEquals(12, listed.at("/result/tools").size());

            send(writer, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
                    + "\"params\":{\"name\":\"ui_action\",\"arguments\":{"
                    + "\"sessionId\":\"game\",\"locator\":{\"kind\":\"role\",\"role\":\"button\"},"
                    + "\"action\":{\"kind\":\"click\",\"pointer\":0,\"button\":0,\"force\":false}}}}");
            JsonNode called = read(reader);
            assertFalse(called.at("/result/isError").asBoolean());
            assertEquals("action-result", called.at("/result/structuredContent/kind").asText());
            assertEquals(1, harness.actionCalls.get());
            closeStdin(clientOutput);
        }
    }

    private static void assertInvalidLocator(
            HarnessToolHandler handler, Map<String, Object> locator) {
        McpSchema.CallToolResult result = handler.handle(call(
                "ui_query", Map.of("sessionId", "game", "locator", locator)))
                .block(Duration.ofSeconds(10));
        assertTrue(result.isError());
        assertEquals("SCHEMA_CONFLICT", structured(result).get("code"));
    }

    private static Map<String, Object> deepLocator(int depth) {
        Map<String, Object> locator = Map.of("kind", "role", "role", "button");
        for (int index = 0; index < depth; index++) {
            locator = Map.of("kind", "index", "index", 0, "locator", locator);
        }
        return locator;
    }

    private static Map<String, Object> wideLocator(int depth) {
        if (depth == 0) {
            return Map.of("kind", "role", "role", "button");
        }
        return Map.of("kind", "relation", "relation", "sibling",
                "anchor", wideLocator(depth - 1), "target", wideLocator(depth - 1));
    }

    private static void initialize(BufferedWriter writer, BufferedReader reader)
            throws Exception {
        send(writer, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                + "\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"contract\",\"version\":\"1.0\"}}}");
        assertEquals(1, read(reader).path("id").asInt());
    }

    private static String actionCallJson(int id) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"ui_action\",\"arguments\":{"
                + "\"sessionId\":\"game\",\"locator\":{\"kind\":\"role\",\"role\":\"button\"},"
                + "\"action\":{\"kind\":\"click\",\"pointer\":0,\"button\":0,\"force\":false}}}}";
    }

    private static McpSchema.CallToolRequest call(String name, Map<String, Object> arguments) {
        return McpSchema.CallToolRequest.builder(name).arguments(arguments).build();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(McpSchema.CallToolResult result) {
        assertNotNull(result);
        return (Map<String, Object>) result.structuredContent();
    }

    @SuppressWarnings("unchecked")
    private static List<String> problemPaths(Map<String, Object> diagnostic) {
        return ((List<Map<String, Object>>) diagnostic.get("problems")).stream()
                .map(problem -> (String) problem.get("fieldPath"))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> artifact(Map<String, Object> content) {
        return (Map<String, Object>) content.get("artifact");
    }

    private static List<Byte> boxed(byte[] bytes) {
        Byte[] boxed = new Byte[bytes.length];
        for (int index = 0; index < bytes.length; index++) {
            boxed[index] = bytes[index];
        }
        return List.of(boxed);
    }

    private static Map<String, String> largeEvidence() {
        LinkedHashMap<String, String> evidence = new LinkedHashMap<>();
        for (int index = 0; index < 16; index++) {
            evidence.put("entry-" + index, "x".repeat(1024));
        }
        return evidence;
    }

    private static void send(BufferedWriter writer, String json) throws Exception {
        writer.write(json);
        writer.newLine();
        writer.flush();
    }

    private static void closeStdin(PipedOutputStream clientOutput) throws Exception {
        clientOutput.close();
    }

    private static JsonNode read(BufferedReader reader) throws Exception {
        return ProtocolJson.mapper().readTree(reader.readLine());
    }

    private static HarnessProtocolService service(RecordingHarness harness) {
        LocatorEngine locators = new StrictResolution();
        FrameSignal frames = listener -> () -> {};
        WaitEngine waits = new WaitEngine(() -> SNAPSHOT, locators, CLOCK, frames);
        CapabilitySet capabilities = new CapabilitySet(List.of(
                "action", "capabilities", "query", "screenshot", "snapshot", "trace", "wait"));
        ScreenCapture capture = new ScreenCapture() {
            @Override public CompletionStage<CapturedImage> capture(
                    CaptureRequest request, Deadline deadline) {
                return CompletableFuture.completedFuture(new CapturedImage(new byte[] {1, 2, 3},
                        "0".repeat(64), 1, 1, 1, 1, new CapturedImage.Scale(1, 1)));
            }

            @Override public void close() {}
        };
        HarnessProtocolService.TraceController traces = new HarnessProtocolService.TraceController() {
            @Override public CompletionStage<HarnessResponse.Result.TraceStarted> start(
                    Command.TraceStart command, Deadline deadline) {
                return CompletableFuture.completedFuture(
                        new HarnessResponse.Result.TraceStarted("trace-1"));
            }

            @Override public CompletionStage<HarnessResponse.Result.TraceStopped> stop(Deadline deadline) {
                return CompletableFuture.completedFuture(new HarnessResponse.Result.TraceStopped(
                        "trace-1", "artifact:trace-1", 2, 128));
            }
        };
        return new HarnessProtocolService(Map.of("game", new HarnessProtocolService.Session(
                harness, locators, waits, capture, capabilities, traces)), CLOCK, Runnable::run);
    }

    private static SemanticSnapshot snapshot() {
        Bounds bounds = new Bounds(0, 0, 10, 10);
        SemanticState state = new SemanticState(true, true, Optional.of(true),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                false, false, 1.0, false, true, true);
        SemanticNode root = new SemanticNode("root", null, List.of(), Role.BUTTON, "Save",
                "Save", null, "save", "button", "Button", state, bounds, bounds, bounds,
                0, Map.of());
        return new SemanticSnapshot(1, 1, "root", Map.of("root", root));
    }

    private static final class RecordingHarness implements Harness {
        private final AtomicInteger actionCalls = new AtomicInteger();
        private volatile boolean actionThreadWasVirtual;
        private volatile Locator lastLocator;
        private CompletionStage<ActionResult> actionResult = CompletableFuture.completedFuture(
                new ActionResult(1, 2, "clicked", Map.of("target", "root")));

        @Override public CompletionStage<ActionResult> perform(Locator locator,
                dev.gdx.uiharness.core.action.Action action, Deadline deadline) {
            actionCalls.incrementAndGet();
            actionThreadWasVirtual = Thread.currentThread().isVirtual();
            lastLocator = locator;
            return actionResult;
        }

        @Override public CompletionStage<SemanticSnapshot> snapshot(Deadline deadline) {
            return CompletableFuture.completedFuture(SNAPSHOT);
        }
    }

    private static final class RecordingArtifacts implements ArtifactReference.Publisher {
        private byte[] lastBytes;
        private int count;

        @Override public ArtifactReference publish(String mediaType, byte[] content) {
            lastBytes = content.clone();
            count++;
            return new ArtifactReference("artifact:" + count, mediaType, content.length,
                    "0".repeat(64));
        }
    }
}
