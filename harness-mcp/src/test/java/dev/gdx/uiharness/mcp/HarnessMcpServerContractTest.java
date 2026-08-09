package dev.gdx.uiharness.mcp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gdx.uiharness.core.action.ActionResult;
import dev.gdx.uiharness.core.action.Harness;
import dev.gdx.uiharness.core.capture.CaptureRequest;
import dev.gdx.uiharness.core.capture.CapturedImage;
import dev.gdx.uiharness.core.capture.ScreenCapture;
import dev.gdx.uiharness.core.assertion.AssertionSnapshotSource;
import dev.gdx.uiharness.core.locator.Locator;
import dev.gdx.uiharness.core.locator.LocatorEngine;
import dev.gdx.uiharness.core.locator.StrictResolution;
import dev.gdx.uiharness.core.layout.LayoutFinding;
import dev.gdx.uiharness.core.layout.LayoutValidationConfig;
import dev.gdx.uiharness.core.layout.LayoutValidationReason;
import dev.gdx.uiharness.core.layout.LayoutValidationResult;
import dev.gdx.uiharness.core.layout.LayoutValidationSeverity;
import dev.gdx.uiharness.core.matrix.MatrixCaseResult;
import dev.gdx.uiharness.core.matrix.MatrixCaseStatus;
import dev.gdx.uiharness.core.matrix.MatrixHiDpi;
import dev.gdx.uiharness.core.matrix.MatrixReport;
import dev.gdx.uiharness.core.matrix.MatrixWindow;
import dev.gdx.uiharness.core.model.Bounds;
import dev.gdx.uiharness.core.model.Role;
import dev.gdx.uiharness.core.model.SemanticNode;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import dev.gdx.uiharness.core.model.SemanticState;
import dev.gdx.uiharness.core.navigation.NavigationInput;
import dev.gdx.uiharness.core.navigation.NavigationPath;
import dev.gdx.uiharness.core.navigation.NavigationReason;
import dev.gdx.uiharness.core.navigation.NavigationResult;
import dev.gdx.uiharness.core.navigation.NavigationStep;
import dev.gdx.uiharness.core.time.Deadline;
import dev.gdx.uiharness.core.time.MonotonicClock;
import dev.gdx.uiharness.core.wait.FrameSignal;
import dev.gdx.uiharness.core.wait.WaitEngine;
import dev.gdx.uiharness.core.locator.Stability;
import dev.gdx.uiharness.protocol.BinaryAttachment;
import dev.gdx.uiharness.protocol.CapabilitySet;
import dev.gdx.uiharness.protocol.Command;
import dev.gdx.uiharness.protocol.DistinguishingPropertySpec;
import dev.gdx.uiharness.protocol.HarnessProtocolService;
import dev.gdx.uiharness.protocol.HarnessRequest;
import dev.gdx.uiharness.protocol.HarnessResponse;
import dev.gdx.uiharness.protocol.LocatorSuggestionSpec;
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
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.ResourceLock;

@org.junit.jupiter.api.parallel.Isolated
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

    @Test void assertionToolRoutesThroughProductionEngineWithCompleteEvidence() {
        try (HarnessToolHandler handler = new HarnessToolHandler(
                service(new RecordingHarness()), new RecordingArtifacts())) {
            McpSchema.CallToolResult result = handler.handle(call("ui_assert", Map.of(
                    "sessionId", "game",
                    "schemaVersion", 1,
                    "deadlineMillis", 500,
                    "locator", Map.of("kind", "test-id", "testId", "save"),
                    "assertion", Map.of("kind", "enabled"))))
                    .block(Duration.ofSeconds(10));

            assertNotNull(result);
            assertFalse(result.isError());
            Map<String, Object> content = structured(result);
            assertEquals("assertion-result", content.get("kind"));
            assertEquals("passed", content.get("outcome"));
            assertEquals(Map.of("kind", "test-id", "testId", "save"),
                    content.get("locator"));
            assertEquals(Map.of("kind", "enabled"), content.get("assertion"));
            assertEquals("true", content.get("expected"));
            assertEquals("enabled=true", content.get("lastObserved"));
            assertEquals("satisfied", content.get("actionability"));
            assertEquals(1, ((Number) content.get("revision")).longValue());
            assertEquals(1, ((Number) content.get("frame")).longValue());
            assertEquals(List.of(), content.get("candidates"));
            assertEquals(false, content.get("truncated"));
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
            assertEquals(23, ((List<?>) structured(capabilities).get("operations")).size());
            assertTrue(((List<?>) structured(capabilities).get("capabilities"))
                    .contains("ui_assert"));
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

    @Test void scenarioToolsDecodeOnlyRegisteredIdentifiersAndBoundedInputs() {
        AtomicReference<HarnessRequest> observed = new AtomicReference<>();
        HarnessResponse response = new HarnessResponse.Success(
                ProtocolVersion.V1, "mcp-1", "game",
                new HarnessResponse.Result.ScenarioStart(
                        new HarnessResponse.ScenarioStartOutcome.Failed("deadline")));
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                HarnessToolHandler handler = new HarnessToolHandler(request -> {
                    observed.set(request);
                    return CompletableFuture.completedFuture(response);
                }, new RecordingArtifacts(), executor, 1024)) {
            McpSchema.CallToolResult result = handler.handle(call(
                    "ui_scenario_start",
                    Map.of(
                            "sessionId", "game",
                            "scenarioId", "main-menu",
                            "seed", 7,
                            "configuration", Map.of("locale", "en"),
                            "profileId", "desktop",
                            "deadlineMillis", 600_000)))
                    .block(Duration.ofSeconds(10));

            assertFalse(result.isError());
            assertEquals(600_000, observed.get().deadlineMillis());
            assertEquals(Map.of("kind", "failed", "reason", "deadline"),
                    ((Map<?, ?>) structured(result).get("outcome")));
            Command.ScenarioStart command =
                    (Command.ScenarioStart) observed.get().command();
            assertEquals("main-menu", command.scenarioId());
            assertEquals(Map.of("locale", "en"), command.configuration());
            assertEquals("desktop", command.profileId());
        }
    }

    @Test void navigationToolsRouteThroughTheClosedSpecAndPublishResults() {
        AtomicReference<Command> observed = new AtomicReference<>();
        HarnessProtocolService service = navigationService(observed);
        Map<String, Object> spec = Map.ofEntries(
                Map.entry("scenarioId", "navigation"),
                Map.entry("seed", 7),
                Map.entry("configuration", Map.of("locale", "en")),
                Map.entry("profileId", "desktop"),
                Map.entry("applicationId", "app"),
                Map.entry("processId", "process"),
                Map.entry("sessionId", "game"),
                Map.entry("inputs", List.of("tab", "tab")),
                Map.entry("controllerSupported", true),
                Map.entry("maxSteps", 16),
                Map.entry("maxActors", 16),
                Map.entry("maxResultBytes", 262244),
                Map.entry("maxEvidenceBytes", 262244),
                Map.entry("maxDurationMillis", 5000));
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                HarnessToolHandler handler = new HarnessToolHandler(
                        service::execute, new RecordingArtifacts(), executor, 1024)) {
            McpSchema.CallToolResult inspected = handler.handle(call(
                    "ui_navigation_inspect",
                    Map.of("sessionId", "game", "spec", spec)))
                    .block(Duration.ofSeconds(10));

            assertFalse(inspected.isError());
            Map<String, Object> content = structured(inspected);
            assertEquals("navigation-result", content.get("kind"));
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) content.get("result");
            @SuppressWarnings("unchecked")
            Map<String, Object> path = (Map<String, Object>) result.get("path");
            assertEquals("COMPLETE", path.get("reason"));
            assertEquals(1, ((List<?>) path.get("steps")).size());

            Command.NavigationInspect command =
                    (Command.NavigationInspect) observed.get();
            assertEquals("navigation", command.spec().scenarioId());
            assertEquals(List.of("tab", "tab"), command.spec().inputs());

            McpSchema.CallToolResult validated = handler.handle(call(
                    "ui_navigation_validate",
                    Map.of("sessionId", "game", "spec", spec)))
                    .block(Duration.ofSeconds(10));
            assertFalse(validated.isError());
            assertInstanceOf(Command.NavigationValidate.class, observed.get());
        }
    }

    @Test void layoutValidationToolRoutesThroughTheClosedSpecAndPublishesResults() {
        AtomicReference<Command> observed = new AtomicReference<>();
        HarnessProtocolService service = layoutService(observed);
        Map<String, Object> spec = Map.ofEntries(
                Map.entry("targetMode", "stage"),
                Map.entry("enabledChecks", List.of("zero-size", "duplicate-test-id")),
                Map.entry("minTargetWidth", 64.0),
                Map.entry("minTargetHeight", 64.0),
                Map.entry("maxAlignmentDelta", 1.0),
                Map.entry("minSpacing", 1.0),
                Map.entry("failOn", "error"),
                Map.entry("maxFindings", 256),
                Map.entry("maxNodes", 10000),
                Map.entry("maxDurationMillis", 2200));
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                HarnessToolHandler handler = new HarnessToolHandler(
                        service::execute, new RecordingArtifacts(), executor, 1024)) {
            McpSchema.CallToolResult result = handler.handle(call(
                    "ui_validate_layout",
                    Map.of("sessionId", "game", "spec", spec)))
                    .block(Duration.ofSeconds(10));

            if (result.isError()) {
                System.err.println("LAYOUT_ERR " + result.structuredContent());
            }
            assertFalse(result.isError());
            Map<String, Object> content = structured(result);
            assertEquals("layout-validation-result", content.get("kind"));
            @SuppressWarnings("unchecked")
            Map<String, Object> validation = (Map<String, Object>) content.get("result");
            assertEquals("FAIL", validation.get("status"));

            Command.LayoutValidate command = (Command.LayoutValidate) observed.get();
            assertEquals("stage", command.spec().targetMode());
            assertEquals(List.of("zero-size", "duplicate-test-id"),
                    command.spec().enabledChecks());
        }
    }

    private static HarnessProtocolService layoutService(AtomicReference<Command> observed) {
        HarnessProtocolService.LayoutValidationCoordinator coordinator =
                new HarnessProtocolService.LayoutValidationCoordinator() {
                    @Override public CompletionStage<LayoutValidationResult> validate(
                            Command.LayoutValidationSpec spec, Deadline deadline) {
                        observed.set(new Command.LayoutValidate(spec));
                        return CompletableFuture.completedFuture(new LayoutValidationResult(
                                LayoutValidationResult.Status.FAIL,
                                List.of(new LayoutFinding(
                                        LayoutValidationReason.ZERO_SIZE,
                                        LayoutValidationSeverity.ERROR,
                                        "btn-zero", null, new Bounds(0, 0, 0, 0),
                                        "actor has zero width or height")),
                                1, false, LayoutValidationConfig.defaults()));
                    }
                };
        HarnessProtocolService.Session session = new HarnessProtocolService.Session(
                new RecordingHarness(), new StrictResolution(),
                new WaitEngine(() -> SNAPSHOT, new StrictResolution(), CLOCK,
                        listener -> () -> {}),
                new ScreenCapture() {
                    @Override public CompletionStage<CapturedImage> capture(
                            CaptureRequest request, Deadline deadline) {
                        return CompletableFuture.completedFuture(new CapturedImage(
                                new byte[] {1, 2, 3}, "0".repeat(64), 1, 1, 1, 1,
                                new CapturedImage.Scale(1, 1)));
                    }

                    @Override public void close() {}
                },
                new CapabilitySet(List.of("ui_validate_layout")),
                HarnessProtocolService.TraceController.unsupported(),
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                java.util.Optional.of(coordinator));
        return new HarnessProtocolService(
                Map.of("game", session), CLOCK, Runnable::run);
    }

    @Test void matrixToolsRouteThroughTheClosedSpecAndPublishReports() {
        AtomicReference<Command> observed = new AtomicReference<>();
        HarnessProtocolService service = matrixService(observed);
        Map<String, Object> spec = Map.ofEntries(
                Map.entry("scenarioId", "matrix"),
                Map.entry("windows", List.of(Map.of("width", 1280, "height", 722))),
                Map.entry("uiScales", List.of(1.0)),
                Map.entry("devicePixelRatios", List.of(1.0)),
                Map.entry("hiDpiModes", List.of("LOGICAL")),
                Map.entry("locales", List.of("en")),
                Map.entry("fontSetIds", List.of()),
                Map.entry("assertions", List.of()),
                Map.entry("maxCases", 100),
                Map.entry("maxDurationMillis", 5000));
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                HarnessToolHandler handler = new HarnessToolHandler(
                        service::execute, new RecordingArtifacts(), executor, 1024)) {
            McpSchema.CallToolResult started = handler.handle(call(
                    "ui_matrix_run",
                    Map.of("sessionId", "game", "spec", spec)))
                    .block(Duration.ofSeconds(10));
            if (started.isError()) {
                System.err.println("MATRIX_ERR " + started.structuredContent());
            }
            assertFalse(started.isError());
            assertEquals("matrix-run-1", structured(started).get("runId"));

            McpSchema.CallToolResult report = handler.handle(call(
                    "ui_matrix_results",
                    Map.of("sessionId", "game", "runId", "matrix-run-1")))
                    .block(Duration.ofSeconds(10));
            if (report.isError()) {
                System.err.println("MATRIX_REPORT_ERR " + report.structuredContent());
            }
            assertFalse(report.isError());
            Map<String, Object> content = structured(report);
            assertEquals("matrix-report", content.get("kind"));
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) content.get("report");
            assertEquals("PASSED", ((Map<?, ?>) ((List<?>) data.get("results")).get(0))
                    .get("status"));

            Command.MatrixRun command = (Command.MatrixRun) observed.get();
            assertEquals("matrix", command.spec().scenarioId());
        }
    }

    private static HarnessProtocolService matrixService(AtomicReference<Command> observed) {
        HarnessProtocolService.MatrixCoordinator coordinator =
                new HarnessProtocolService.MatrixCoordinator() {
                    @Override public CompletionStage<String> run(
                            Command.MatrixRunSpec spec, Deadline deadline) {
                        observed.set(new Command.MatrixRun(spec));
                        return CompletableFuture.completedFuture("matrix-run-1");
                    }

                    @Override public CompletionStage<MatrixReport> results(String runId) {
                        return CompletableFuture.completedFuture(new MatrixReport(
                                runId,
                                "matrix",
                                List.of(new MatrixCaseResult(
                                        new dev.gdx.uiharness.core.matrix.MatrixCaseSummary(
                                                0, new MatrixWindow(1280, 722), 1.0, 1.0,
                                                MatrixHiDpi.LOGICAL, "en", "", 16.0 / 9.0),
                                        MatrixCaseStatus.PASSED,
                                        new MatrixWindow(1280, 722),
                                        1.0, 1.0,
                                        MatrixHiDpi.LOGICAL,
                                        "en", "", null,
                                        List.of(0), List.of(), List.of(), "")),
                                false));
                    }
                };
        HarnessProtocolService.Session session = new HarnessProtocolService.Session(
                new RecordingHarness(), new StrictResolution(),
                new WaitEngine(() -> SNAPSHOT, new StrictResolution(), CLOCK,
                        listener -> () -> {}),
                new ScreenCapture() {
                    @Override public CompletionStage<CapturedImage> capture(
                            CaptureRequest request, Deadline deadline) {
                        return CompletableFuture.completedFuture(new CapturedImage(
                                new byte[] {1, 2, 3}, "0".repeat(64), 1, 1, 1, 1,
                                new CapturedImage.Scale(1, 1)));
                    }

                    @Override public void close() {}
                },
                new CapabilitySet(List.of("ui_matrix_run", "ui_matrix_results")),
                HarnessProtocolService.TraceController.unsupported(),
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                java.util.Optional.of(coordinator));
        return new HarnessProtocolService(
                Map.of("game", session), CLOCK, Runnable::run);
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

    @Test void verifiedPublisherRejectsMismatchedReceiptDimensions() {
        byte[] payload = new byte[] {1, 2, 3};
        ArtifactReference.Publisher wrongLength = (mediaType, content) ->
                new ArtifactReference("artifact:1", mediaType, content.length + 1,
                        "0".repeat(64));
        assertThrows(ArtifactReference.ArtifactUnavailableException.class,
                () -> new VerifiedArtifactPublisher(wrongLength).publish("image/png", payload));

        ArtifactReference.Publisher wrongDigest = (mediaType, content) ->
                new ArtifactReference("artifact:1", mediaType, content.length,
                        "0".repeat(64));
        assertThrows(ArtifactReference.ArtifactUnavailableException.class,
                () -> new VerifiedArtifactPublisher(wrongDigest).publish("image/png", payload));

        ArtifactReference.Publisher wrongMedia = (mediaType, content) ->
                new ArtifactReference("artifact:1", "application/octet-stream",
                        content.length, sha256(content));
        assertThrows(ArtifactReference.ArtifactUnavailableException.class,
                () -> new VerifiedArtifactPublisher(wrongMedia).publish("image/png", payload));

        ArtifactReference.Publisher honest = (mediaType, content) ->
                new ArtifactReference("artifact:1", mediaType, content.length,
                        sha256(content));
        ArtifactReference receipt = new VerifiedArtifactPublisher(honest)
                .publish("image/png", payload);
        assertEquals("artifact:1", receipt.reference());
        assertEquals("image/png", receipt.mediaType());
        assertEquals(payload.length, receipt.byteLength());
    }

    @Test void verifiedPublisherRejectsNullReceipt() {
        byte[] payload = new byte[] {1, 2, 3};
        ArtifactReference.Publisher nullReceipt = (mediaType, content) -> null;
        assertThrows(ArtifactReference.ArtifactUnavailableException.class,
                () -> new VerifiedArtifactPublisher(nullReceipt)
                        .publish("image/png", payload));
    }

    @Test void verifiedPublisherNormalizesDelegateFailuresToTheFixedMessage() {
        String secret = "ghp_1234567890abcdef";
        ArtifactReference.Publisher throwing = (mediaType, content) -> {
            throw new ArtifactReference.ArtifactUnavailableException(
                    "publisher token " + secret);
        };
        ArtifactReference.ArtifactUnavailableException failure =
                assertThrows(ArtifactReference.ArtifactUnavailableException.class,
                        () -> new VerifiedArtifactPublisher(throwing)
                                .publish("image/png", new byte[] {1, 2, 3}));
        assertEquals("Artifact publisher receipt is unavailable or invalid",
                failure.getMessage());
        assertFalse(failure.getMessage().contains(secret));
        assertInstanceOf(ArtifactReference.ArtifactUnavailableException.class,
                failure.getCause());

        ArtifactReference.Publisher invalidReference = (mediaType, content) ->
                new ArtifactReference("/tmp/leak.zip", mediaType, content.length,
                        sha256(content));
        ArtifactReference.ArtifactUnavailableException normalized =
                assertThrows(ArtifactReference.ArtifactUnavailableException.class,
                        () -> new VerifiedArtifactPublisher(invalidReference)
                                .publish("image/png", new byte[] {1, 2, 3}));
        assertEquals("Artifact publisher receipt is unavailable or invalid",
                normalized.getMessage());
        assertFalse(normalized.getMessage().contains("/tmp/leak.zip"));
    }

    @Test void verifiedPublisherNormalizesDelegateErrorsToTheFixedMessage() {
        ArtifactReference.Publisher throwing = (mediaType, content) -> {
            throw new AssertionError("delegate invariant broken");
        };
        ArtifactReference.ArtifactUnavailableException failure =
                assertThrows(ArtifactReference.ArtifactUnavailableException.class,
                        () -> new VerifiedArtifactPublisher(throwing)
                                .publish("image/png", new byte[] {1, 2, 3}));
        assertEquals("Artifact publisher receipt is unavailable or invalid",
                failure.getMessage());
        assertInstanceOf(AssertionError.class, failure.getCause());
    }

    @Test void verifiedPublisherNormalizesSneakyCheckedFailures() {
        String secret = "checked-exception-leak";
        ArtifactReference.Publisher sneaky = (mediaType, content) ->
                sneakyThrow(new java.io.IOException(secret));
        ArtifactReference.ArtifactUnavailableException failure =
                assertThrows(ArtifactReference.ArtifactUnavailableException.class,
                        () -> new VerifiedArtifactPublisher(sneaky)
                                .publish("image/png", new byte[] {1, 2, 3}));
        assertEquals("Artifact publisher receipt is unavailable or invalid",
                failure.getMessage());
        assertFalse(failure.getMessage().contains(secret));
        assertInstanceOf(java.io.IOException.class, failure.getCause());
    }

    @Test void verifiedPublisherNormalizesLinkageErrors() {
        ArtifactReference.Publisher linkage = (mediaType, content) -> {
            throw new LinkageError("delegate linkage failure");
        };
        ArtifactReference.ArtifactUnavailableException failure =
                assertThrows(ArtifactReference.ArtifactUnavailableException.class,
                        () -> new VerifiedArtifactPublisher(linkage)
                                .publish("image/png", new byte[] {1, 2, 3}));
        assertEquals("Artifact publisher receipt is unavailable or invalid",
                failure.getMessage());
        assertInstanceOf(LinkageError.class, failure.getCause());
    }

    @Test void verifiedPublisherRethrowsFatalErrors() {
        StackOverflowError fatal = new StackOverflowError("simulated");
        ArtifactReference.Publisher overflowing = (mediaType, content) -> {
            throw fatal;
        };
        assertSame(fatal, assertThrows(StackOverflowError.class,
                () -> new VerifiedArtifactPublisher(overflowing)
                        .publish("image/png", new byte[] {1, 2, 3})));

        ThreadDeath death = new ThreadDeath();
        ArtifactReference.Publisher dying = (mediaType, content) -> {
            throw death;
        };
        assertSame(death, assertThrows(ThreadDeath.class,
                () -> new VerifiedArtifactPublisher(dying)
                        .publish("image/png", new byte[] {1, 2, 3})));
    }

    @Test void verifiedPublisherRejectsUppercaseDigestReceipts() {
        byte[] payload = new byte[] {1, 2, 3};
        ArtifactReference.Publisher uppercase = (mediaType, content) ->
                new ArtifactReference("artifact:1", mediaType, content.length,
                        sha256(content).toUpperCase());
        assertThrows(ArtifactReference.ArtifactUnavailableException.class,
                () -> new VerifiedArtifactPublisher(uppercase)
                        .publish("image/png", payload));
    }

    @Test void mutatingPublisherCannotRedefineTheExpectedBytes() {
        byte[] payload = new byte[] {1, 2, 3};
        ArtifactReference.Publisher mutating = (mediaType, content) -> {
            java.util.Arrays.fill(content, (byte) 0);
            return new ArtifactReference("artifact:1", mediaType, content.length,
                    sha256(content)); // receipt matches the MUTATED bytes
        };

        assertThrows(ArtifactReference.ArtifactUnavailableException.class,
                () -> new VerifiedArtifactPublisher(mutating)
                        .publish("image/png", payload.clone()));
        // the delegate's mutation must not be able to redefine what the receipt is
        // verified against: the expectation is computed from the pre-call snapshot
    }

    @Test void screenshotPublicationUsesTheInternalCaptureNotThePublicString() {
        // A deliberately invalid public base64 string cannot be decoded: successful publication
        // proves the artifact path uses the internal capture attachment, not the public String.
        HarnessResponse.Result.Screenshot screenshot = new HarnessResponse.Result.Screenshot(
                "not-valid-base64-!!!", "0".repeat(64), 1, 1, 3, 1, 1, 1);
        byte[] attachment = {7, 8, 9};
        RecordingArtifacts artifacts = new RecordingArtifacts();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                HarnessToolHandler handler = new HarnessToolHandler(
                        (HarnessToolHandler.ExecutionSource) request -> CompletableFuture.completedFuture(
                                new HarnessProtocolService.Execution(
                                        new HarnessResponse.Success(
                                                ProtocolVersion.V1, request.requestId(),
                                                request.sessionId(), screenshot),
                                        Map.of(HarnessProtocolService.SCREENSHOT_CAPTURE,
                                                BinaryAttachment.of(attachment)))),
                        artifacts, executor, 1_024, System::nanoTime)) {
            McpSchema.CallToolResult result = handler.handle(call("ui_screenshot", Map.of(
                    "sessionId", "game", "maxWidth", 8, "maxHeight", 8,
                    "maxPixels", 64, "maxPngBytes", 128))).block(Duration.ofSeconds(10));

            assertFalse(result.isError());
            assertEquals(List.of((byte) 7, (byte) 8, (byte) 9), boxed(artifacts.lastBytes),
                    "publication must publish the internal capture attachment bytes");
        }
    }

    @Test void maxSizeScreenshotPublishesExactCaptureBytesWithMatchingReceipts() {
        byte[] payload = new byte[HarnessResponse.Result.Screenshot.MAX_PNG_BYTES];
        for (int index = 0; index < payload.length; index++) {
            payload[index] = (byte) (index % 251);
        }
        String sha = sha256Hex(payload);
        RecordingArtifacts artifacts = new RecordingArtifacts();
        try (HarnessToolHandler handler = new HarnessToolHandler(
                serviceWithCapture(payload, sha), artifacts)) {
            McpSchema.CallToolResult result = handler.handle(call("ui_screenshot", Map.of(
                    "sessionId", "game", "maxWidth", 8_192, "maxHeight", 8_192,
                    "maxPixels", 33_554_432L, "maxPngBytes", payload.length)))
                    .block(Duration.ofSeconds(60));

            assertFalse(result.isError());
            assertArrayEquals(payload, artifacts.lastBytes,
                    "published bytes must equal the captured PNG bytes exactly");
            assertEquals(sha, artifacts.lastReference.sha256(),
                    "digest receipt must match the captured bytes");
            assertEquals((long) payload.length, artifacts.lastReference.byteLength(),
                    "length receipt must match the captured bytes");
        }
    }

    @Test void screenshotPublicationPublishesThroughTheReadOnlyBufferOverload() {
        byte[] attachment = {4, 5, 6};
        ByteBufferOnlyArtifacts artifacts = new ByteBufferOnlyArtifacts();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                HarnessToolHandler handler = new HarnessToolHandler(
                        (HarnessToolHandler.ExecutionSource) request -> CompletableFuture.completedFuture(
                                new HarnessProtocolService.Execution(
                                        new HarnessResponse.Success(
                                                ProtocolVersion.V1, request.requestId(),
                                                request.sessionId(), screenshot(attachment)),
                                        Map.of(HarnessProtocolService.SCREENSHOT_CAPTURE,
                                                BinaryAttachment.of(attachment)))),
                        artifacts, executor, 1_024, System::nanoTime)) {
            McpSchema.CallToolResult result = handler.handle(call("ui_screenshot", Map.of(
                    "sessionId", "game", "maxWidth", 8, "maxHeight", 8,
                    "maxPixels", 64, "maxPngBytes", 128))).block(Duration.ofSeconds(10));

            assertFalse(result.isError(),
                    "screenshot publication must not fall back to the byte[] overload");
            assertEquals(1, artifacts.byteBufferCalls.get(),
                    "exactly one publication through the read-only buffer overload");
            assertEquals(0, artifacts.byteArrayCalls.get(),
                    "the byte[] overload must never be invoked for screenshot publication");
            assertArrayEquals(attachment, artifacts.lastBytes,
                    "the publisher must receive the exact internal capture bytes");
        }
    }

    @Test void screenshotWithoutCaptureAttachmentFailsClosed() {
        RecordingArtifacts artifacts = new RecordingArtifacts();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                HarnessToolHandler handler = new HarnessToolHandler(
                        (HarnessToolHandler.ExecutionSource) request -> CompletableFuture.completedFuture(
                                new HarnessProtocolService.Execution(
                                        new HarnessResponse.Success(
                                                ProtocolVersion.V1, request.requestId(),
                                                request.sessionId(),
                                                screenshot(new byte[] {1, 2, 3})),
                                        Map.of())),
                        artifacts, executor, 1_024, System::nanoTime)) {
            McpSchema.CallToolResult result = handler.handle(call("ui_screenshot", Map.of(
                    "sessionId", "game", "maxWidth", 8, "maxHeight", 8,
                    "maxPixels", 64, "maxPngBytes", 128))).block(Duration.ofSeconds(10));

            assertTrue(result.isError(),
                    "a screenshot result without its capture attachment must fail closed");
            assertEquals(0, artifacts.count,
                    "nothing may be published when the capture attachment is missing");
            assertNull(artifacts.lastBytes);
        }
    }

    @Test void screenshotWithMismatchedPublisherReceiptFailsClosed() {
        MismatchedReceiptArtifacts artifacts = new MismatchedReceiptArtifacts();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                HarnessToolHandler handler = new HarnessToolHandler(
                        (HarnessToolHandler.ExecutionSource) request -> CompletableFuture.completedFuture(
                                new HarnessProtocolService.Execution(
                                        new HarnessResponse.Success(
                                                ProtocolVersion.V1, request.requestId(),
                                                request.sessionId(),
                                                screenshot(new byte[] {1, 2, 3})),
                                        Map.of(HarnessProtocolService.SCREENSHOT_CAPTURE,
                                                BinaryAttachment.of(new byte[] {1, 2, 3})))),
                        artifacts, executor, 1_024, System::nanoTime)) {
            McpSchema.CallToolResult result = handler.handle(call("ui_screenshot", Map.of(
                    "sessionId", "game", "maxWidth", 8, "maxHeight", 8,
                    "maxPixels", 64, "maxPngBytes", 128))).block(Duration.ofSeconds(10));

            assertTrue(result.isError(),
                    "a publication receipt that contradicts the published bytes must fail closed");
            assertEquals(1, artifacts.count,
                    "the publication was attempted but its inconsistent receipt was rejected");
        }
    }

    @Test void screenshotPublicationPreservesPublicStructuredAndTextContent() {
        byte[] attachment = {1, 2, 3};
        String sha = sha256Hex(attachment);
        HarnessResponse.Result.Screenshot screenshot = new HarnessResponse.Result.Screenshot(
                Base64.getEncoder().encodeToString(attachment), sha, 7, 2, 640, 480, 2, 1);
        RecordingArtifacts artifacts = new RecordingArtifacts();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                HarnessToolHandler handler = new HarnessToolHandler(
                        (HarnessToolHandler.ExecutionSource) request -> CompletableFuture.completedFuture(
                                new HarnessProtocolService.Execution(
                                        new HarnessResponse.Success(
                                                ProtocolVersion.V1, request.requestId(),
                                                request.sessionId(), screenshot),
                                        Map.of(HarnessProtocolService.SCREENSHOT_CAPTURE,
                                                BinaryAttachment.of(attachment)))),
                        artifacts, executor, 1_024, System::nanoTime)) {
            McpSchema.CallToolResult result = handler.handle(call("ui_screenshot", Map.of(
                    "sessionId", "game", "maxWidth", 8, "maxHeight", 8,
                    "maxPixels", 64, "maxPngBytes", 128))).block(Duration.ofSeconds(10));

            assertFalse(result.isError());
            Map<String, Object> content = structured(result);
            assertEquals("screenshot-result", content.get("kind"));
            assertEquals("artifact:1", artifact(content).get("reference"));
            assertEquals("image/png", artifact(content).get("mediaType"));
            assertEquals(3L, artifact(content).get("byteLength"));
            assertEquals(sha, artifact(content).get("sha256"));
            assertEquals(7L, content.get("frame"));
            assertEquals(2L, content.get("revision"));
            assertEquals(640, content.get("width"));
            assertEquals(480, content.get("height"));
            assertEquals(2.0, content.get("scaleX"));
            assertEquals(1.0, content.get("scaleY"));
            assertFalse(content.containsKey("pngBase64"),
                    "the raw PNG bytes must never leak into public structured content");
            String text = text(result);
            assertTrue(text.startsWith("screenshot-result: "),
                    "the compact text must keep the public screenshot shape");
            assertTrue(text.contains("artifact:1"));
            assertFalse(text.contains("pngBase64"));
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
                HarnessToolHandler handler = new HarnessToolHandler(
                        (HarnessToolHandler.ExecutionSource) request -> {
                            calls.incrementAndGet();
                            return service(new RecordingHarness()).executeWithAttachments(request);
                        }, artifacts, executor, 1_024, System::nanoTime)) {
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
            arguments.put("maxDurationMillis", 122_001);
            arguments.put("maxWidth", 8_193);
            arguments.put("maxHeight", 722);
            arguments.put("maxPixelCount", 1280L * 722);
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
                        (Function<HarnessRequest, CompletionStage<HarnessResponse>>)
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

    @Test void immediateSuccessReportsZeroElapsedOnALongLivedServer() {
        AtomicLong nanos = new AtomicLong(999_000_000_000L);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                HarnessToolHandler handler = new HarnessToolHandler(
                        request -> CompletableFuture.completedFuture(
                                new HarnessResponse.Success(ProtocolVersion.V1,
                                        "mcp-1", "game",
                                        new HarnessResponse.Result.Sessions(List.of()))),
                        new RecordingArtifacts(), executor, 1024, nanos::get)) {
            Map<String, Object> content = structured(handler.handle(call(
                    "ui_sessions", Map.of())).block(Duration.ofSeconds(10)));

            @SuppressWarnings("unchecked")
            Map<String, Object> recovery = (Map<String, Object>) content.get("recovery");
            assertEquals(0, ((Number) recovery.get("consumed")).intValue());
            assertEquals(0, ((Number) recovery.get("elapsedMillis")).intValue());
            assertTrue(((Number) recovery.get("elapsedMillis")).longValue()
                    <= ((Number) recovery.get("maxWallTimeMillis")).longValue());
        }
    }

    @Test void floodedNewSessionsAreTerminallyRejectedWithoutResettingBudgets() {
        AtomicLong nanos = new AtomicLong(1_000_000_000L);
        AtomicInteger calls = new AtomicInteger();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                HarnessToolHandler handler = new HarnessToolHandler(request -> {
                    calls.incrementAndGet();
                    return CompletableFuture.completedFuture(new HarnessResponse.Failure(
                            ProtocolVersion.V1, request.requestId(), request.sessionId(),
                            new ProtocolError(ProtocolError.Code.NOT_FOUND,
                                    "locator resolved to no actors", request.requestId(),
                                    request.sessionId(), null, 0, null, null,
                                    List.of(), Map.of(), null, List.of())));
                }, new RecordingArtifacts(), executor, 1024, nanos::get, 2)) {
            Map<String, Object> first = structured(handler.handle(call(
                    "ui_snapshot", Map.of("sessionId", "session-a")))
                    .block(Duration.ofSeconds(10)));
            Map<String, Object> second = structured(handler.handle(call(
                    "ui_snapshot", Map.of("sessionId", "session-b")))
                    .block(Duration.ofSeconds(10)));
            assertTrue(first.containsKey("code"));
            assertTrue(second.containsKey("code"));

            Map<String, Object> rejected = structured(handler.handle(call(
                    "ui_snapshot", Map.of("sessionId", "overflow")))
                    .block(Duration.ofSeconds(10)));
            assertEquals("RECOVERY_BUDGET_EXHAUSTED", rejected.get("code"));
            @SuppressWarnings("unchecked")
            Map<String, Object> recovery = (Map<String, Object>) rejected.get("recovery");
            assertEquals("accounting-capacity/v1", recovery.get("terminatingRule"));
            assertEquals(3, ((Number) recovery.get("consumed")).intValue());
            assertEquals(0, ((Number) recovery.get("remaining")).intValue());

            Map<String, Object> again = structured(handler.handle(call(
                    "ui_snapshot", Map.of("sessionId", "overflow")))
                    .block(Duration.ofSeconds(10)));
            assertEquals("RECOVERY_BUDGET_EXHAUSTED", again.get("code"),
                    "a rejected key must stay terminal and never reset any budget");

            Map<String, Object> tracked = structured(handler.handle(call(
                    "ui_snapshot", Map.of("sessionId", "session-a")))
                    .block(Duration.ofSeconds(10)));
            @SuppressWarnings("unchecked")
            Map<String, Object> trackedRecovery = (Map<String, Object>) tracked.get("recovery");
            assertEquals(2, ((Number) trackedRecovery.get("consumed")).intValue(),
                    "flooding must not reset an existing key's budget");
        }
    }

    @Test void successAfterTransientReportsConsumedThenStartsFresh() {
        AtomicLong nanos = new AtomicLong(1_000_000_000L);
        AtomicInteger calls = new AtomicInteger();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                HarnessToolHandler handler = new HarnessToolHandler(request -> {
                    calls.incrementAndGet();
                    if (calls.get() == 1) {
                        // NOT_FOUND maps to the transient LOCATOR_NOT_FOUND diagnostic
                        return CompletableFuture.completedFuture(new HarnessResponse.Failure(
                                ProtocolVersion.V1, request.requestId(), request.sessionId(),
                                new ProtocolError(ProtocolError.Code.NOT_FOUND,
                                        "locator resolved to no actors", request.requestId(),
                                        request.sessionId(), null, 0, null, null,
                                        List.of(), Map.of(), null, List.of())));
                    }
                    return CompletableFuture.completedFuture(new HarnessResponse.Success(
                            ProtocolVersion.V1, request.requestId(), request.sessionId(),
                            new HarnessResponse.Result.Sessions(List.of())));
                }, new RecordingArtifacts(), executor, 1024, nanos::get)) {
            Map<String, Object> first = structured(handler.handle(call(
                    "ui_snapshot", Map.of("sessionId", "game"))).block(Duration.ofSeconds(10)));
            assertTrue(first.containsKey("code"),
                    "first call must produce a transient diagnostic");

            Map<String, Object> success = structured(handler.handle(call(
                    "ui_snapshot", Map.of("sessionId", "game"))).block(Duration.ofSeconds(10)));
            @SuppressWarnings("unchecked")
            Map<String, Object> recovery = (Map<String, Object>) success.get("recovery");
            assertEquals(1, ((Number) recovery.get("consumed")).intValue());

            Map<String, Object> third = structured(handler.handle(call(
                    "ui_snapshot", Map.of("sessionId", "game"))).block(Duration.ofSeconds(10)));
            @SuppressWarnings("unchecked")
            Map<String, Object> thirdRecovery = (Map<String, Object>) third.get("recovery");
            assertEquals(0, ((Number) thirdRecovery.get("consumed")).intValue(),
                    "success must remove the session's recovery state");
        }
    }

    @Test void sessionCloseFailureEvictsRecoveryState() {
        AtomicLong nanos = new AtomicLong(1_000_000_000L);
        AtomicInteger calls = new AtomicInteger();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                HarnessToolHandler handler = new HarnessToolHandler(request -> {
                    calls.incrementAndGet();
                    if (calls.get() == 1) {
                        return CompletableFuture.completedFuture(new HarnessResponse.Failure(
                                ProtocolVersion.V1, request.requestId(), request.sessionId(),
                                new ProtocolError(ProtocolError.Code.NOT_FOUND,
                                        "locator resolved to no actors", request.requestId(),
                                        request.sessionId(), null, 0, null, null,
                                        List.of(), Map.of(), null, List.of())));
                    }
                    if (calls.get() == 2) {
                        return CompletableFuture.completedFuture(new HarnessResponse.Failure(
                                ProtocolVersion.V1, request.requestId(), request.sessionId(),
                                new ProtocolError(ProtocolError.Code.SESSION_CLOSED,
                                        "session closed", request.requestId(),
                                        request.sessionId(), null, 0, null, null,
                                        List.of(), Map.of(), null, List.of())));
                    }
                    return CompletableFuture.completedFuture(new HarnessResponse.Success(
                            ProtocolVersion.V1, request.requestId(), request.sessionId(),
                            new HarnessResponse.Result.Sessions(List.of())));
                }, new RecordingArtifacts(), executor, 1024, nanos::get)) {
            Map<String, Object> arguments = Map.of("sessionId", "game");
            assertTrue(structured(handler.handle(call(
                    "ui_snapshot", arguments)).block(Duration.ofSeconds(10))).containsKey("code"));
            assertTrue(structured(handler.handle(call(
                    "ui_snapshot", arguments)).block(Duration.ofSeconds(10))).containsKey("code"));

            Map<String, Object> success = structured(handler.handle(call(
                    "ui_snapshot", arguments)).block(Duration.ofSeconds(10)));
            @SuppressWarnings("unchecked")
            Map<String, Object> recovery = (Map<String, Object>) success.get("recovery");
            assertEquals(0, ((Number) recovery.get("consumed")).intValue(),
                    "session close must evict the session's recovery state");
        }
    }

    @Test void fingerprintStoreCapacityTerminallyRejectsNewFingerprints() {
        AtomicLong nanos = new AtomicLong(1_000_000_000L);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                HarnessToolHandler handler = new HarnessToolHandler(
                        request -> CompletableFuture.failedFuture(new AssertionError()),
                        new RecordingArtifacts(), executor, 1024, nanos::get, 3)) {
            for (int index = 0; index < 3; index++) {
                Map<String, Object> diagnostic = structured(handler.handle(call(
                        "ui_snapshot", Map.of("sessionId", "game", "bogus-" + index, index)))
                        .block(Duration.ofSeconds(10)));
                assertEquals("UNKNOWN_ARGUMENT", diagnostic.get("code"));
            }
            Map<String, Object> rejected = structured(handler.handle(call(
                    "ui_snapshot", Map.of("sessionId", "game", "bogus-3", 3)))
                    .block(Duration.ofSeconds(10)));
            assertEquals("RECOVERY_BUDGET_EXHAUSTED", rejected.get("code"),
                    "fingerprint churn must not bypass the accounting bound");
            @SuppressWarnings("unchecked")
            Map<String, Object> recovery = (Map<String, Object>) rejected.get("recovery");
            assertEquals("accounting-capacity/v1", recovery.get("terminatingRule"));
            assertEquals(3, ((Number) recovery.get("consumed")).intValue());
            assertEquals(0, ((Number) recovery.get("remaining")).intValue());

            // Fail-closed: the saturated store is not cleared by the rejection, so a
            // different new fingerprint remains terminally rejected on retry.
            Map<String, Object> again = structured(handler.handle(call(
                    "ui_snapshot", Map.of("sessionId", "game", "bogus-4", 4)))
                    .block(Duration.ofSeconds(10)));
            assertEquals("RECOVERY_BUDGET_EXHAUSTED", again.get("code"),
                    "a capacity rejection must not clear the saturated store");

            // The owning workflow's original counts remain intact: the session
            // budget kept accumulating through the rejections (3 -> 6) and was
            // never reset, and the owned fingerprint still resolves, so the
            // rejection the owned request now hits is the session budget.
            Map<String, Object> owned = structured(handler.handle(call(
                    "ui_snapshot", Map.of("sessionId", "game", "bogus-0", 0)))
                    .block(Duration.ofSeconds(10)));
            assertEquals("RECOVERY_BUDGET_EXHAUSTED", owned.get("code"));
            @SuppressWarnings("unchecked")
            Map<String, Object> ownedRecovery = (Map<String, Object>) owned.get("recovery");
            assertEquals("session-recovery-budget/v1",
                    ownedRecovery.get("terminatingRule"));
            assertEquals(6, ((Number) ownedRecovery.get("consumed")).intValue(),
                    "capacity rejections must not reset an owned workflow's budget");
        }
    }

    @Test void staleTerminalDoesNotClearNewerWorkflowState() throws Exception {
        AtomicLong nanos = new AtomicLong(1_000_000_000L);
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch staleAdmitted = new CountDownLatch(1);
        CompletableFuture<HarnessResponse> staleGate = new CompletableFuture<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                HarnessToolHandler handler = new HarnessToolHandler(request -> {
                    int call = calls.incrementAndGet();
                    if (call == 2) {
                        staleAdmitted.countDown();
                        return staleGate;
                    }
                    if (call == 3) {
                        return CompletableFuture.completedFuture(new HarnessResponse.Success(
                                ProtocolVersion.V1, request.requestId(), request.sessionId(),
                                new HarnessResponse.Result.Sessions(List.of())));
                    }
                    return CompletableFuture.completedFuture(new HarnessResponse.Failure(
                            ProtocolVersion.V1, request.requestId(), request.sessionId(),
                            new ProtocolError(ProtocolError.Code.NOT_FOUND,
                                    "locator resolved to no actors", request.requestId(),
                                    request.sessionId(), null, 0, null, null,
                                    List.of(), Map.of(), null, List.of())));
                }, new RecordingArtifacts(), executor, 1024, nanos::get)) {
            // First transient failure creates workflow generation 1.
            structured(handler.handle(call(
                    "ui_snapshot", Map.of("sessionId", "game"))).block(Duration.ofSeconds(10)));

            // The stale terminal starts while generation 1 is current and is held.
            CompletableFuture<McpSchema.CallToolResult> stale = handler.handle(call(
                    "ui_snapshot", Map.of("sessionId", "game"))).toFuture();
            assertTrue(staleAdmitted.await(10, TimeUnit.SECONDS));

            // A success ends generation 1, clearing the session and its fingerprints.
            Map<String, Object> success = structured(handler.handle(call(
                    "ui_snapshot", Map.of("sessionId", "game"))).block(Duration.ofSeconds(10)));
            @SuppressWarnings("unchecked")
            Map<String, Object> successRecovery = (Map<String, Object>) success.get("recovery");
            assertEquals(1, ((Number) successRecovery.get("consumed")).intValue());

            // A transient failure starts generation 2 with a fresh budget.
            Map<String, Object> fresh = structured(handler.handle(call(
                    "ui_snapshot", Map.of("sessionId", "game"))).block(Duration.ofSeconds(10)));
            @SuppressWarnings("unchecked")
            Map<String, Object> freshRecovery = (Map<String, Object>) fresh.get("recovery");
            assertEquals(1, ((Number) freshRecovery.get("consumed")).intValue());

            // The stale terminal completes with a terminal protocol error. It must
            // retain the generation-1 token captured at request start, so it can
            // never end generation 2.
            staleGate.complete(new HarnessResponse.Failure(
                    ProtocolVersion.V1, "mcp-2", "game",
                    new ProtocolError(ProtocolError.Code.LIMIT_EXCEEDED,
                            "limit exceeded", "mcp-2", "game", null, 0, null, null,
                            List.of(), Map.of(), null, List.of())));
            Map<String, Object> staleResult = structured(stale.get(10, TimeUnit.SECONDS));
            assertEquals("LIMIT_EXCEEDED", staleResult.get("code"));

            // Generation 2's state and capacity remain intact.
            Map<String, Object> continued = structured(handler.handle(call(
                    "ui_snapshot", Map.of("sessionId", "game"))).block(Duration.ofSeconds(10)));
            @SuppressWarnings("unchecked")
            Map<String, Object> continuedRecovery =
                    (Map<String, Object>) continued.get("recovery");
            assertEquals(2, ((Number) continuedRecovery.get("consumed")).intValue(),
                    "a stale terminal must never clear a newer workflow's state");
        }
    }

    @Test void interleavedRecordAndReleaseNeverLoseNewerGenerationFingerprint()
            throws Exception {
        AtomicLong nanos = new AtomicLong(1_000_000_000L);
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch releaseBlocked = new CountDownLatch(1);
        CountDownLatch releaseGate = new CountDownLatch(1);
        CompletableFuture<HarnessResponse> endingGate = new CompletableFuture<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                HarnessToolHandler handler = new HarnessToolHandler(request -> {
                    if (calls.incrementAndGet() == 1) {
                        return endingGate;
                    }
                    return CompletableFuture.completedFuture(new HarnessResponse.Success(
                            ProtocolVersion.V1, request.requestId(), request.sessionId(),
                            new HarnessResponse.Result.Sessions(List.of())));
                }, new RecordingArtifacts(), executor, 1024, nanos::get)) {
            // Pause the ending workflow's release exactly between its ownership
            // check and the accounting deletion.
            handler.beforeFingerprintRelease = () -> {
                releaseBlocked.countDown();
                try {
                    releaseGate.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            };

            Map<String, Object> malformed = Map.of("sessionId", "game", "bogus", 1);
            // Generation 1 owns the malformed fingerprint.
            structured(handler.handle(call(
                    "ui_snapshot", malformed)).block(Duration.ofSeconds(10)));

            // The gated success will end generation 1 and is paused mid-release.
            CompletableFuture<McpSchema.CallToolResult> ending = handler.handle(call(
                    "ui_snapshot", Map.of("sessionId", "game"))).toFuture();
            endingGate.complete(new HarnessResponse.Success(
                    ProtocolVersion.V1, "mcp-2", "game",
                    new HarnessResponse.Result.Sessions(List.of())));
            assertTrue(releaseBlocked.await(10, TimeUnit.SECONDS),
                    "the ending workflow must be paused at its fingerprint release");

            // A concurrent request records the same malformed fingerprint while the
            // ending workflow is paused; its record and registration are atomic
            // with the release, so the new generation's fingerprint survives.
            CompletableFuture<McpSchema.CallToolResult> recording = handler.handle(call(
                    "ui_snapshot", malformed)).toFuture();
            releaseGate.countDown();
            ending.get(10, TimeUnit.SECONDS);
            structured(recording.get(10, TimeUnit.SECONDS));

            Map<String, Object> continued = structured(handler.handle(call(
                    "ui_snapshot", malformed)).block(Duration.ofSeconds(10)));
            @SuppressWarnings("unchecked")
            Map<String, Object> continuedRecovery =
                    (Map<String, Object>) continued.get("recovery");
            assertEquals(2, ((Number) continuedRecovery.get("consumed")).intValue(),
                    "an interleaved release must never delete the new generation's "
                            + "just-recorded fingerprint");
        }
    }

    @Test void successClearsFingerprintsSoSameMalformedRequestStartsFresh() {
        AtomicLong nanos = new AtomicLong(1_000_000_000L);
        AtomicInteger calls = new AtomicInteger();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                HarnessToolHandler handler = new HarnessToolHandler(request -> {
                    calls.incrementAndGet();
                    return CompletableFuture.completedFuture(new HarnessResponse.Success(
                            ProtocolVersion.V1, request.requestId(), request.sessionId(),
                            new HarnessResponse.Result.Sessions(List.of())));
                }, new RecordingArtifacts(), executor, 1024, nanos::get)) {
            Map<String, Object> malformed = Map.of("sessionId", "game", "bogus", 1);
            Map<String, Object> first = structured(handler.handle(call(
                    "ui_snapshot", malformed)).block(Duration.ofSeconds(10)));
            assertEquals("UNKNOWN_ARGUMENT", first.get("code"));
            @SuppressWarnings("unchecked")
            Map<String, Object> firstRecovery = (Map<String, Object>) first.get("recovery");
            assertEquals(1, ((Number) firstRecovery.get("consumed")).intValue());

            Map<String, Object> success = structured(handler.handle(call(
                    "ui_snapshot", Map.of("sessionId", "game"))).block(Duration.ofSeconds(10)));
            @SuppressWarnings("unchecked")
            Map<String, Object> successRecovery = (Map<String, Object>) success.get("recovery");
            assertEquals(1, ((Number) successRecovery.get("consumed")).intValue());

            Map<String, Object> again = structured(handler.handle(call(
                    "ui_snapshot", malformed)).block(Duration.ofSeconds(10)));
            assertEquals("UNKNOWN_ARGUMENT", again.get("code"));
            @SuppressWarnings("unchecked")
            Map<String, Object> againRecovery = (Map<String, Object>) again.get("recovery");
            assertEquals(1, ((Number) againRecovery.get("consumed")).intValue(),
                    "success must clear the workflow's fingerprints so the same malformed "
                            + "request starts a fresh budget at one");
        }
    }

    @Test void staleCompletionDoesNotClearNewerWorkflowState() throws Exception {
        AtomicLong nanos = new AtomicLong(1_000_000_000L);
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch staleAdmitted = new CountDownLatch(1);
        CompletableFuture<HarnessResponse> staleGate = new CompletableFuture<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                HarnessToolHandler handler = new HarnessToolHandler(request -> {
                    if (calls.incrementAndGet() == 1) {
                        staleAdmitted.countDown();
                        return staleGate;
                    }
                    return CompletableFuture.completedFuture(new HarnessResponse.Success(
                            ProtocolVersion.V1, request.requestId(), request.sessionId(),
                            new HarnessResponse.Result.Sessions(List.of())));
                }, new RecordingArtifacts(), executor, 1024, nanos::get)) {
            Map<String, Object> malformed = Map.of("sessionId", "game", "bogus", 1);

            // First transient failure creates workflow generation 1.
            structured(handler.handle(call(
                    "ui_snapshot", malformed)).block(Duration.ofSeconds(10)));

            // The stale success starts while generation 1 is current and is held on a gate.
            CompletableFuture<McpSchema.CallToolResult> stale = handler.handle(call(
                    "ui_snapshot", Map.of("sessionId", "game"))).toFuture();
            assertTrue(staleAdmitted.await(10, TimeUnit.SECONDS));

            // A success ends generation 1, clearing the session and its fingerprints.
            Map<String, Object> success = structured(handler.handle(call(
                    "ui_snapshot", Map.of("sessionId", "game"))).block(Duration.ofSeconds(10)));
            @SuppressWarnings("unchecked")
            Map<String, Object> successRecovery = (Map<String, Object>) success.get("recovery");
            assertEquals(1, ((Number) successRecovery.get("consumed")).intValue());

            // A new transient failure starts generation 2 with a fresh budget.
            Map<String, Object> fresh = structured(handler.handle(call(
                    "ui_snapshot", malformed)).block(Duration.ofSeconds(10)));
            @SuppressWarnings("unchecked")
            Map<String, Object> freshRecovery = (Map<String, Object>) fresh.get("recovery");
            assertEquals(1, ((Number) freshRecovery.get("consumed")).intValue());

            // Releasing the stale completion must not clear generation 2's state.
            staleGate.complete(new HarnessResponse.Success(
                    ProtocolVersion.V1, "mcp-2", "game",
                    new HarnessResponse.Result.Sessions(List.of())));
            Map<String, Object> staleResult = structured(stale.get(10, TimeUnit.SECONDS));
            @SuppressWarnings("unchecked")
            Map<String, Object> staleRecovery = (Map<String, Object>) staleResult.get("recovery");
            assertEquals(1, ((Number) staleRecovery.get("consumed")).intValue());

            // The same malformed request now reports two: generation 2 was not cleared.
            Map<String, Object> continued = structured(handler.handle(call(
                    "ui_snapshot", malformed)).block(Duration.ofSeconds(10)));
            @SuppressWarnings("unchecked")
            Map<String, Object> continuedRecovery =
                    (Map<String, Object>) continued.get("recovery");
            assertEquals(2, ((Number) continuedRecovery.get("consumed")).intValue(),
                    "a stale completion must never clear a newer workflow's state");
        }
    }

    @Test void handlerCloseClearsRecoveryStateAndRemainsIdempotent() {
        AtomicLong nanos = new AtomicLong(1_000_000_000L);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        HarnessToolHandler handler = new HarnessToolHandler(
                request -> CompletableFuture.failedFuture(new AssertionError()),
                new RecordingArtifacts(), executor, 1024, nanos::get, 2);
        try {
            // Populate the session store, the fingerprint store, and the workflow index.
            structured(handler.handle(call(
                    "ui_snapshot", Map.of("sessionId", "game", "bogus-a", 1)))
                    .block(Duration.ofSeconds(10)));
            structured(handler.handle(call(
                    "ui_snapshot", Map.of("sessionId", "game", "bogus-b", 2)))
                    .block(Duration.ofSeconds(10)));
        } finally {
            handler.close();
            handler.close(); // idempotent
        }
        assertTrue(executor.isShutdown());
    }

    @Test void timeoutProtocolErrorsRemainTerminalDeadlineExceeded() {
        AtomicLong nanos = new AtomicLong(1_000_000_000L);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                HarnessToolHandler handler = new HarnessToolHandler(
                        request -> CompletableFuture.completedFuture(new HarnessResponse.Failure(
                                ProtocolVersion.V1, request.requestId(), request.sessionId(),
                                new ProtocolError(ProtocolError.Code.TIMEOUT,
                                        "frame deadline expired", request.requestId(),
                                        request.sessionId(), null, 0, null, null,
                                        List.of(), Map.of(), null, List.of()))),
                        new RecordingArtifacts(), executor, 1024, nanos::get)) {
            Map<String, Object> diagnostic = structured(handler.handle(call(
                    "ui_snapshot", Map.of("sessionId", "game"))).block(Duration.ofSeconds(10)));
            assertEquals("DEADLINE_EXCEEDED", diagnostic.get("code"),
                    "a protocol timeout must keep the terminal DEADLINE_EXCEEDED contract");
            assertEquals("terminal", diagnostic.get("disposition"));
            assertEquals(Boolean.FALSE, diagnostic.get("retryable"));
            @SuppressWarnings("unchecked")
            Map<String, Object> recovery = (Map<String, Object>) diagnostic.get("recovery");
            assertEquals("terminal-code/v1", recovery.get("terminatingRule"));
        }
    }

    @Test void notFoundProtocolErrorsRecordTransientLocatorNotFoundRecovery() {
        AtomicLong nanos = new AtomicLong(1_000_000_000L);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                HarnessToolHandler handler = new HarnessToolHandler(
                        request -> CompletableFuture.completedFuture(new HarnessResponse.Failure(
                                ProtocolVersion.V1, request.requestId(), request.sessionId(),
                                new ProtocolError(ProtocolError.Code.NOT_FOUND,
                                        "locator resolved to no actors", request.requestId(),
                                        request.sessionId(), null, 0, null, null,
                                        List.of(), Map.of(), null, List.of()))),
                        new RecordingArtifacts(), executor, 1024, nanos::get)) {
            Map<String, Object> diagnostic = structured(handler.handle(call(
                    "ui_snapshot", Map.of("sessionId", "game"))).block(Duration.ofSeconds(10)));
            assertEquals("LOCATOR_NOT_FOUND", diagnostic.get("code"));
            assertEquals("transient", diagnostic.get("disposition"));
            assertEquals(Boolean.TRUE, diagnostic.get("retryable"));
            @SuppressWarnings("unchecked")
            Map<String, Object> recovery = (Map<String, Object>) diagnostic.get("recovery");
            assertEquals("wait-for-matching-locator/v1", recovery.get("terminatingRule"));
            assertEquals(1, ((Number) recovery.get("consumed")).intValue(),
                    "a transient protocol error must record a recovery attempt");
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
                22L,
                "trace:strict",
                List.of(
                        Map.of("actorId", "first"),
                        Map.of("actorId", "second")),
                Map.of(
                        "matchCount", "[redacted] 2",
                        "lastActionability", "visible"),
                "trace-1",
                List.of());
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

    @Test void protocolFailurePublishesSuggestionsUnderTheClosedLocatorSchema() {
        ProtocolError error = new ProtocolError(
                ProtocolError.Code.NOT_FOUND,
                "No semantic node matches the locator",
                "mcp-1",
                "game",
                "testId=missing",
                64,
                22L,
                "trace:strict",
                List.of(
                        Map.of("id", "pause-resume", "role", "BUTTON")),
                Map.of(
                        "matchCount", "0",
                        "redactionPolicyId", "none"),
                "trace-1",
                List.of(new LocatorSuggestionSpec(
                        new Command.LocatorSpec.TestId("pause-resume"),
                        Stability.STABLE,
                        "unique test identifier",
                        "pause-resume",
                        List.of(new DistinguishingPropertySpec(
                                "testId", "pause-resume")))));
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
                                    "kind", "test-id",
                                    "testId", "missing"),
                            "action", Map.of(
                                    "kind", "click", "pointer", 0,
                                    "button", 0, "force", false))))
                    .block(Duration.ofSeconds(10)));

            assertEquals("LOCATOR_NOT_FOUND", diagnostic.get("code"));
            assertEquals(1, ((List<?>) diagnostic.get("suggestions")).size());
            @SuppressWarnings("unchecked")
            Map<String, Object> suggestion =
                    (Map<String, Object>) ((List<?>) diagnostic.get("suggestions")).get(0);
            assertEquals("STABLE", suggestion.get("stability"));
            assertEquals("unique test identifier", suggestion.get("rationale"));
            assertEquals("pause-resume", suggestion.get("candidateIdentity"));
            Map<?, ?> locator = (Map<?, ?>) suggestion.get("locator");
            assertEquals("test-id", locator.get("kind"));
            assertEquals("pause-resume", locator.get("testId"));
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
                            "maxWidth", 1921,
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
                            "maxWidth", 1921,
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

    @Test void traceStopRejectsDigestLessReceipt() {
        // The released four-arg TraceStopped (legacy constructor without the verified
        // archive digest) must never surface as a successful MCP result: the
        // ui_trace_stop output schema requires archiveSha256, so the handler must
        // fail closed with INTERNAL_ERROR instead of emitting a digest-less receipt.
        CompletableFuture<HarnessResponse> response = CompletableFuture.completedFuture(
                new HarnessResponse.Success(ProtocolVersion.V1, "mcp-1", "game",
                        new HarnessResponse.Result.TraceStopped(
                                "trace-1", "artifact:trace-1", 2, 128)));
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                HarnessToolHandler handler = new HarnessToolHandler(
                        ignored -> response, new RecordingArtifacts(), executor, 1024)) {
            McpSchema.CallToolResult result = handler.handle(call(
                    "ui_trace_stop", Map.of("sessionId", "game")))
                    .block(Duration.ofSeconds(10));
            assertTrue(result.isError());
            Map<String, Object> content = structured(result);
            assertEquals("INTERNAL_ERROR", content.get("code"));
            assertFalse(content.containsKey("archiveSha256"));
        }
    }

    @Test void traceStopStructuredReceiptCarriesVerifiedArchiveDigest() {
        String digest = "ab".repeat(32);
        CompletableFuture<HarnessResponse> response = CompletableFuture.completedFuture(
                new HarnessResponse.Success(ProtocolVersion.V1, "mcp-1", "game",
                        new HarnessResponse.Result.TraceStopped(
                                "trace-1", "artifact:trace-1", 2, 128, digest)));
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                HarnessToolHandler handler = new HarnessToolHandler(
                        ignored -> response, new RecordingArtifacts(), executor, 1024)) {
            McpSchema.CallToolResult result = handler.handle(call(
                    "ui_trace_stop", Map.of("sessionId", "game")))
                    .block(Duration.ofSeconds(10));

            assertFalse(result.isError());
            Map<String, Object> content = structured(result);
            assertEquals("trace-stopped", content.get("kind"));
            assertEquals("trace-1", content.get("traceId"));
            assertEquals("artifact:trace-1", content.get("traceReference"));
            assertEquals(2L, content.get("eventCount"));
            assertEquals(128L, content.get("bytes"));
            assertEquals(digest, content.get("archiveSha256"));
        }
    }

    @Test void publisherFailureSecretsNeverReachMcpOutput() {
        String secret = "ghp_1234567890abcdef";
        ArtifactReference.Publisher leaking = (mediaType, content) -> {
            throw new ArtifactReference.ArtifactUnavailableException(
                    "publisher token " + secret + " at /home/private/key.pem");
        };
        byte[] png = "fake-png".getBytes(StandardCharsets.UTF_8);
        CompletableFuture<HarnessResponse> response = CompletableFuture.completedFuture(
                new HarnessResponse.Success(ProtocolVersion.V1, "mcp-1", "game",
                        new HarnessResponse.Result.Screenshot(
                                Base64.getEncoder().encodeToString(png),
                                "0".repeat(64), 1, 1, 1, 1, 1.0, 1.0)));
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                HarnessToolHandler handler = new HarnessToolHandler(
                        ignored -> response, leaking, executor, 1024)) {
            McpSchema.CallToolResult result = handler.handle(call(
                    "ui_screenshot", Map.of(
                            "sessionId", "game",
                            "maxWidth", 10, "maxHeight", 10,
                            "maxPixels", 100, "maxPngBytes", 1024)))
                    .block(Duration.ofSeconds(10));
            String text = result.content().stream()
                    .filter(McpSchema.TextContent.class::isInstance)
                    .map(McpSchema.TextContent.class::cast)
                    .map(McpSchema.TextContent::text)
                    .reduce("", (a, b) -> a + b);

            assertTrue(result.isError());
            assertFalse(text.contains(secret));
            assertFalse(text.contains("/home/private"));
            assertTrue(text.contains("artifact-unavailable"));
            Map<String, Object> content = structured(result);
            assertFalse(String.valueOf(content).contains(secret));
            assertEquals("INTERNAL_ERROR", content.get("code"));
            String traceId = (String) content.get("traceId");
            assertNotNull(traceId);
            assertTrue(traceId.matches("internal-[0-9a-f]{32}"));
        }
    }

    @Test void invalidArtifactReferenceTextIsRedacted() {
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
            String text = result.content().stream()
                    .filter(McpSchema.TextContent.class::isInstance)
                    .map(McpSchema.TextContent.class::cast)
                    .map(McpSchema.TextContent::text)
                    .reduce("", (a, b) -> a + b);
            assertFalse(text.contains("/tmp/trace.zip"));
            assertTrue(text.contains("invalid-artifact-reference"));
        }
    }

    @Test void publisherRuntimeFailuresAreRedactedToArtifactUnavailable() {
        String secret = "runtime_ghp_1234567890abcdef";
        ArtifactReference.Publisher throwing = (mediaType, content) -> {
            throw new IllegalStateException(
                    "runtime token " + secret + " at /opt/private/key.pem");
        };
        byte[] png = "fake-png".getBytes(StandardCharsets.UTF_8);
        CompletableFuture<HarnessResponse> response = CompletableFuture.completedFuture(
                new HarnessResponse.Success(ProtocolVersion.V1, "mcp-1", "game",
                        new HarnessResponse.Result.Screenshot(
                                Base64.getEncoder().encodeToString(png),
                                "0".repeat(64), 1, 1, 1, 1, 1.0, 1.0)));
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                HarnessToolHandler handler = new HarnessToolHandler(
                        ignored -> response, throwing, executor, 1024)) {
            McpSchema.CallToolResult result = handler.handle(call(
                    "ui_screenshot", Map.of(
                            "sessionId", "game",
                            "maxWidth", 10, "maxHeight", 10,
                            "maxPixels", 100, "maxPngBytes", 1024)))
                    .block(Duration.ofSeconds(10));
            String text = text(result);
            assertTrue(result.isError());
            assertFalse(text.contains(secret));
            assertFalse(text.contains("/opt/private"));
            assertTrue(text.contains("artifact-unavailable"));
            Map<String, Object> content = structured(result);
            assertEquals("INTERNAL_ERROR", content.get("code"));
            assertFalse(String.valueOf(content).contains(secret));
            assertInternalTraceId(content);
        }
    }

    @Test void publisherAssertionErrorsAreRedactedToArtifactUnavailable() {
        String secret = "assert_ghp_1234567890abcdef";
        ArtifactReference.Publisher throwing = (mediaType, content) -> {
            throw new AssertionError(
                    "assert token " + secret + " at /etc/private/key.pem");
        };
        byte[] png = "fake-png".getBytes(StandardCharsets.UTF_8);
        CompletableFuture<HarnessResponse> response = CompletableFuture.completedFuture(
                new HarnessResponse.Success(ProtocolVersion.V1, "mcp-1", "game",
                        new HarnessResponse.Result.Screenshot(
                                Base64.getEncoder().encodeToString(png),
                                "0".repeat(64), 1, 1, 1, 1, 1.0, 1.0)));
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                HarnessToolHandler handler = new HarnessToolHandler(
                        ignored -> response, throwing, executor, 1024)) {
            McpSchema.CallToolResult result = handler.handle(call(
                    "ui_screenshot", Map.of(
                            "sessionId", "game",
                            "maxWidth", 10, "maxHeight", 10,
                            "maxPixels", 100, "maxPngBytes", 1024)))
                    .block(Duration.ofSeconds(10));
            String text = text(result);
            assertTrue(result.isError());
            assertFalse(text.contains(secret));
            assertFalse(text.contains("/etc/private"));
            assertTrue(text.contains("artifact-unavailable"));
            Map<String, Object> content = structured(result);
            assertEquals("INTERNAL_ERROR", content.get("code"));
            assertInternalTraceId(content);
        }
    }

    @Test void publisherNullReceiptsAreRedactedToArtifactUnavailable() {
        ArtifactReference.Publisher nullReceipt = (mediaType, content) -> null;
        byte[] png = "fake-png".getBytes(StandardCharsets.UTF_8);
        CompletableFuture<HarnessResponse> response = CompletableFuture.completedFuture(
                new HarnessResponse.Success(ProtocolVersion.V1, "mcp-1", "game",
                        new HarnessResponse.Result.Screenshot(
                                Base64.getEncoder().encodeToString(png),
                                "0".repeat(64), 1, 1, 1, 1, 1.0, 1.0)));
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                HarnessToolHandler handler = new HarnessToolHandler(
                        ignored -> response, nullReceipt, executor, 1024)) {
            McpSchema.CallToolResult result = handler.handle(call(
                    "ui_screenshot", Map.of(
                            "sessionId", "game",
                            "maxWidth", 10, "maxHeight", 10,
                            "maxPixels", 100, "maxPngBytes", 1024)))
                    .block(Duration.ofSeconds(10));
            String text = text(result);
            assertTrue(result.isError());
            assertTrue(text.contains("artifact-unavailable"));
            Map<String, Object> content = structured(result);
            assertEquals("INTERNAL_ERROR", content.get("code"));
            assertInternalTraceId(content);
        }
    }

    @Test void asyncPublisherFailuresAreUnwrappedAndRedacted() {
        String secret = "async_ghp_1234567890abcdef";
        CompletableFuture<HarnessResponse> unavailable = CompletableFuture.failedFuture(
                new java.util.concurrent.CompletionException(
                        new ArtifactReference.ArtifactUnavailableException(
                                "async token " + secret + " at /tmp/async.pem")));
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                HarnessToolHandler handler = new HarnessToolHandler(
                        ignored -> unavailable, new RecordingArtifacts(), executor, 1024)) {
            McpSchema.CallToolResult result = handler.handle(call(
                    "ui_screenshot", Map.of(
                            "sessionId", "game",
                            "maxWidth", 10, "maxHeight", 10,
                            "maxPixels", 100, "maxPngBytes", 1024)))
                    .block(Duration.ofSeconds(10));
            String text = text(result);
            assertTrue(result.isError());
            assertFalse(text.contains(secret));
            assertFalse(text.contains("/tmp/async.pem"));
            assertTrue(text.contains("artifact-unavailable"));
            Map<String, Object> content = structured(result);
            assertEquals("INTERNAL_ERROR", content.get("code"));
            assertFalse(String.valueOf(content).contains(secret));
            assertInternalTraceId(content);
        }

        CompletableFuture<HarnessResponse> invalid = CompletableFuture.failedFuture(
                new java.util.concurrent.CompletionException(
                        new ArtifactReference.InvalidArtifactReferenceException(
                                "reference /etc/passwd")));
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                HarnessToolHandler handler = new HarnessToolHandler(
                        ignored -> invalid, new RecordingArtifacts(), executor, 1024)) {
            McpSchema.CallToolResult result = handler.handle(call(
                    "ui_trace_stop", Map.of("sessionId", "game")))
                    .block(Duration.ofSeconds(10));
            assertTrue(result.isError());
            String text = text(result);
            assertFalse(text.contains("/etc/passwd"));
            assertTrue(text.contains("invalid-artifact-reference"));
            assertInternalTraceId(structured(result));
        }
    }

    @ResourceLock("java.util.logging")
    @Test void artifactFailureLogsAreSafeAndCorrelated() {
        String secret = "log_ghp_1234567890abcdef";
        java.util.logging.Logger artifactLogger = java.util.logging.Logger.getLogger(
                "dev.gdx.uiharness.mcp.ArtifactPublisher");
        java.util.logging.Level previousLevel = artifactLogger.getLevel();
        boolean previousUseParentHandlers = artifactLogger.getUseParentHandlers();
        java.util.logging.Filter previousFilter = artifactLogger.getFilter();
        java.util.logging.Handler[] previousHandlers = artifactLogger.getHandlers();
        List<String> records = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.logging.Handler capture = new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord record) {
                StringBuilder formatted = new StringBuilder(record.getMessage());
                if (record.getThrown() != null) {
                    formatted.append(" thrown=")
                            .append(record.getThrown().getClass().getName());
                }
                records.add(formatted.toString());
            }

            @Override public void flush() {}

            @Override public void close() {}
        };
        // Isolate the artifact logger: never mutate the root logger, do not propagate
        // records to parent handlers, and detach any pre-existing handlers so the
        // capture list sees only this logger's records (CopyOnWriteArrayList is
        // thread-safe for concurrent JUL delivery).
        capture.setLevel(java.util.logging.Level.ALL);
        artifactLogger.setLevel(java.util.logging.Level.ALL);
        artifactLogger.setUseParentHandlers(false);
        for (java.util.logging.Handler handler : previousHandlers) {
            artifactLogger.removeHandler(handler);
        }
        artifactLogger.addHandler(capture);
        try {
            ArtifactReference.Publisher leaking = (mediaType, content) -> {
                throw new ArtifactReference.ArtifactUnavailableException(
                        "publisher token " + secret + " at /home/private/key.pem");
            };
            byte[] png = "fake-png".getBytes(StandardCharsets.UTF_8);
            CompletableFuture<HarnessResponse> response = CompletableFuture.completedFuture(
                    new HarnessResponse.Success(ProtocolVersion.V1, "mcp-1", "game",
                            new HarnessResponse.Result.Screenshot(
                                    Base64.getEncoder().encodeToString(png),
                                    "0".repeat(64), 1, 1, 1, 1, 1.0, 1.0)));
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                    HarnessToolHandler handler = new HarnessToolHandler(
                            ignored -> response, leaking, executor, 1024)) {
                McpSchema.CallToolResult result = handler.handle(call(
                        "ui_screenshot", Map.of(
                                "sessionId", "game",
                                "maxWidth", 10, "maxHeight", 10,
                                "maxPixels", 100, "maxPngBytes", 1024)))
                        .block(Duration.ofSeconds(10));
                Map<String, Object> content = structured(result);
                String traceId = (String) content.get("traceId");
                assertNotNull(traceId);
                assertTrue(traceId.matches("internal-[0-9a-f]{32}"));

                List<String> boundaryRecords = records.stream()
                        .filter(record -> record.contains("internal-"))
                        .toList();
                assertFalse(boundaryRecords.isEmpty());
                assertTrue(boundaryRecords.stream()
                        .anyMatch(record -> record.contains(traceId)));
                for (String record : boundaryRecords) {
                    assertFalse(record.contains(secret));
                    assertFalse(record.contains("/home/private"));
                    assertFalse(record.contains("ArtifactUnavailableException"));
                    assertFalse(record.contains("thrown="));
                }
            }
        } finally {
            artifactLogger.removeHandler(capture);
            for (java.util.logging.Handler handler : previousHandlers) {
                artifactLogger.addHandler(handler);
            }
            artifactLogger.setLevel(previousLevel);
            artifactLogger.setUseParentHandlers(previousUseParentHandlers);
            artifactLogger.setFilter(previousFilter);
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
    void requestAdmissionIsBoundedAndMutationsAreSerialized() throws Exception {
        CompletableFuture<HarnessResponse> firstGate = new CompletableFuture<>();
        CompletableFuture<HarnessResponse> secondGate = new CompletableFuture<>();
        AtomicInteger protocolCalls = new AtomicInteger();
        HarnessResponse capabilities = new HarnessResponse.Success(
                ProtocolVersion.V1, "mcp-1", "game",
                new HarnessResponse.Result.Capabilities(List.of("action")));
        ProtocolSignals starts = new ProtocolSignals();
        // Real virtual-thread dispatch: each call runs on its own virtual thread, exactly as in
        // production. The protocol signal fires when the second call actually reaches the
        // service, so no sleep or poll is needed.
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                HarnessToolHandler handler = new HarnessToolHandler(
                        (Function<HarnessRequest, CompletionStage<HarnessResponse>>) request -> {
                            protocolCalls.incrementAndGet();
                            starts.record(request.requestId());
                            return protocolCalls.get() == 1 ? firstGate : secondGate;
                        }, new RecordingArtifacts(), executor, 1024, System::nanoTime,
                        new RequestAdmission(2, 4, 4))) {
            CompletableFuture<McpSchema.CallToolResult> first = handler.handle(call(
                    "ui_capabilities", Map.of("sessionId", "game"))).toFuture();
            CompletableFuture<McpSchema.CallToolResult> second = handler.handle(call(
                    "ui_capabilities", Map.of("sessionId", "game"))).toFuture();
            // Both read-only calls are admitted and running; the protocol signal fires only
            // after the service was reached, so the call counter is already incremented.
            starts.signal(2).get(5, TimeUnit.SECONDS);
            assertEquals(2, protocolCalls.get());

            // The third concurrent request is rejected immediately with a stable
            // LIMIT_EXCEEDED diagnostic and never reaches the protocol service.
            CompletableFuture<McpSchema.CallToolResult> third = handler.handle(call(
                    "ui_capabilities", Map.of("sessionId", "game"))).toFuture();
            Map<String, Object> diagnostic = structured(third.get(5, TimeUnit.SECONDS));
            assertEquals("LIMIT_EXCEEDED", diagnostic.get("code"));
            assertEquals("terminal", diagnostic.get("disposition"));
            assertEquals(Boolean.FALSE, diagnostic.get("retryable"));
            assertEquals(2, protocolCalls.get());

            firstGate.complete(capabilities);
            secondGate.complete(capabilities);
            assertEquals("capabilities-result",
                    structured(first.get(5, TimeUnit.SECONDS)).get("kind"));
            assertEquals("capabilities-result",
                    structured(second.get(5, TimeUnit.SECONDS)).get("kind"));
        }

        // Mutations are gated in protocol-start order, so completing gate N frees the lane for
        // start N+1 regardless of which virtual-thread submission was admitted first. The
        // contract under test is non-overlap: the lane must never run a second mutation while
        // the previous one is still at the protocol. FIFO start order is proven directly by
        // RequestAdmissionTest.sameSessionMutationsStartInSubmissionOrderAndNeverOverlap.
        List<CompletableFuture<HarnessResponse>> gates =
                Collections.synchronizedList(new ArrayList<>());
        AtomicBoolean overlappedWhilePreviousMutationRunning = new AtomicBoolean();
        AtomicReference<CompletableFuture<HarnessResponse>> previousGate = new AtomicReference<>();
        HarnessResponse action = new HarnessResponse.Success(
                ProtocolVersion.V1, "mcp-1", "game",
                new HarnessResponse.Result.Action(1, 2, "clicked", Map.of()));
        ProtocolSignals mutationStarts = new ProtocolSignals();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                HarnessToolHandler handler = new HarnessToolHandler(
                        (Function<HarnessRequest, CompletionStage<HarnessResponse>>) request -> {
                            CompletableFuture<HarnessResponse> gate = new CompletableFuture<>();
                            gates.add(gate);
                            mutationStarts.record(request.requestId());
                            // The lane starts the next mutation only after the previous mutation's
                            // stage is terminal, so every new protocol call must observe the previous
                            // gate already completed; a lane that ran two mutations at once could not.
                            CompletableFuture<HarnessResponse> previous = previousGate.getAndSet(gate);
                            if (previous != null && !previous.isDone()) {
                                overlappedWhilePreviousMutationRunning.set(true);
                            }
                            return gate;
                        }, new RecordingArtifacts(), executor, 1024, System::nanoTime,
                        new RequestAdmission(8, 4, 4))) {
            Map<String, Object> arguments = Map.of(
                    "sessionId", "game",
                    "locator", Map.of("kind", "role", "role", "button"),
                    "action", Map.of("kind", "click", "pointer", 0, "button", 0,
                            "force", false));
            CompletableFuture<McpSchema.CallToolResult> first = handler.handle(call(
                    "ui_action", arguments)).toFuture();
            CompletableFuture<McpSchema.CallToolResult> second = handler.handle(call(
                    "ui_action", arguments)).toFuture();
            CompletableFuture<McpSchema.CallToolResult> third = handler.handle(call(
                    "ui_action", arguments)).toFuture();

            // Exactly one mutation starts immediately at the protocol; the other two sit in
            // the bounded lane in whichever order their virtual-thread submissions ran.
            mutationStarts.signal(1).get(5, TimeUnit.SECONDS);

            // Completing the first gate starts exactly one more mutation: the lane head. The
            // last queued mutation must not start while the second is still running.
            gates.get(0).complete(action);
            mutationStarts.signal(2).get(5, TimeUnit.SECONDS);
            assertFalse(mutationStarts.signal(3).isDone());

            gates.get(1).complete(action);
            mutationStarts.signal(3).get(5, TimeUnit.SECONDS);

            gates.get(2).complete(action);
            assertEquals("action-result",
                    structured(first.get(5, TimeUnit.SECONDS)).get("kind"));
            assertEquals("action-result",
                    structured(second.get(5, TimeUnit.SECONDS)).get("kind"));
            assertEquals("action-result",
                    structured(third.get(5, TimeUnit.SECONDS)).get("kind"));
            // No two same-session mutations were ever concurrently at the protocol.
            assertFalse(overlappedWhilePreviousMutationRunning.get());
        }
    }

    @Test
    void sessionlessCallsDoNotShareAdmissionWithClientSessionNamedCatalog() throws Exception {
        CompletableFuture<HarnessResponse> sessionlessGate = new CompletableFuture<>();
        CompletableFuture<HarnessResponse> catalogGate = new CompletableFuture<>();
        AtomicInteger calls = new AtomicInteger();
        HarnessResponse sessions = new HarnessResponse.Success(
                ProtocolVersion.V1, "mcp-1", "game",
                new HarnessResponse.Result.Sessions(List.of(new HarnessResponse.SessionInfo(
                        "catalog", List.of("action")))));
        HarnessResponse action = new HarnessResponse.Success(
                ProtocolVersion.V1, "mcp-1", "game",
                new HarnessResponse.Result.Action(1, 2, "clicked", Map.of()));
        ProtocolSignals starts = new ProtocolSignals();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                HarnessToolHandler handler = new HarnessToolHandler(
                        (Function<HarnessRequest, CompletionStage<HarnessResponse>>) request -> {
                            calls.incrementAndGet();
                            starts.record(request.requestId());
                            return request.command() instanceof Command.Sessions
                                    ? sessionlessGate : catalogGate;
                        }, new RecordingArtifacts(), executor, 1024, System::nanoTime,
                        new RequestAdmission(8, 1, 1))) {
            CompletableFuture<McpSchema.CallToolResult> sessionless = handler.handle(call(
                    "ui_sessions", Map.of())).toFuture();
            starts.signal(1).get(5, TimeUnit.SECONDS);
            assertEquals(1, calls.get());

            // A real client session literally named "catalog" must have its own per-session
            // lane and be admitted even while the sessionless lane is occupied: with the
            // per-session limit at 1, a shared lane would reject the second start.
            CompletableFuture<McpSchema.CallToolResult> catalogSession = handler.handle(call(
                    "ui_action", Map.of(
                            "sessionId", "catalog",
                            "locator", Map.of("kind", "role", "role", "button"),
                            "action", Map.of("kind", "click", "pointer", 0, "button", 0,
                                    "force", false)))).toFuture();
            starts.signal(2).get(5, TimeUnit.SECONDS);
            assertEquals(2, calls.get());
            assertFalse(catalogSession.isDone());

            sessionlessGate.complete(sessions);
            catalogGate.complete(action);
            assertEquals("sessions-result",
                    structured(sessionless.get(5, TimeUnit.SECONDS)).get("kind"));
            assertEquals("action-result",
                    structured(catalogSession.get(5, TimeUnit.SECONDS)).get("kind"));
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
    void scenarioDeadlineCanExceedDefaultRequestDeadline() throws Exception {
        // The SDK's outer request timeout must cover the full published scenario maximum
        // (600s) plus a bounded translation allowance, not the 120s default request
        // deadline, so a ui_scenario_start with maxDurationMillis above 120_000 is not
        // aborted by the outer timeout before its own validated deadline applies.
        assertTrue(HarnessMcpServer.OUTER_REQUEST_TIMEOUT.toMillis()
                        > HarnessRequest.MAX_DEADLINE_MILLIS,
                "the outer request timeout must exceed the default request deadline");
        assertTrue(HarnessMcpServer.OUTER_REQUEST_TIMEOUT.toMillis()
                        >= HarnessRequest.MAX_SCENARIO_DEADLINE_MILLIS,
                "the outer request timeout must cover the full published scenario maximum");
        assertTrue(HarnessRequest.MAX_SCENARIO_DEADLINE_MILLIS
                        <= HarnessMcpServer.OUTER_REQUEST_TIMEOUT.toMillis(),
                "no accepted scenario deadline may exceed the outer request bound");

        try (PipedInputStream serverInput = new PipedInputStream();
                PipedOutputStream clientOutput = new PipedOutputStream(serverInput);
                PipedInputStream clientInput = new PipedInputStream();
                PipedOutputStream serverOutput = new PipedOutputStream(clientInput);
                HarnessMcpServer server = HarnessMcpServer.open(
                        service(new RecordingHarness()), new RecordingArtifacts(),
                        serverInput, serverOutput);
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                        clientOutput, StandardCharsets.UTF_8));
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        clientInput, StandardCharsets.UTF_8))) {
            assertNotNull(server);
            initialize(writer, reader);
            send(writer, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
            send(writer, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
                    + "\"params\":{\"name\":\"ui_scenario_start\",\"arguments\":{"
                    + "\"sessionId\":\"game\",\"scenarioId\":\"main-menu\",\"seed\":7,"
                    + "\"configuration\":{\"locale\":\"en\"},\"profileId\":\"desktop\","
                    + "\"deadlineMillis\":600000}}}");
            JsonNode called = read(reader);
            assertEquals(3, called.path("id").asInt());
            assertTrue(called.path("result").isObject() || called.path("error").isObject(),
                    "the server must answer the 600s-deadline scenario call instead of "
                            + "aborting it through the outer SDK timeout");
            closeStdin(clientOutput);
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
                    + "\"params\":{\"protocolVersion\":\"2225-11-25\",\"capabilities\":{},"
                    + "\"clientInfo\":{\"name\":\"contract\",\"version\":\"1.0\"}}}");
            JsonNode initialize = read(reader);
            assertEquals(1, initialize.path("id").asInt());
            assertEquals("libgdx-ui-harness", initialize.at("/result/serverInfo/name").asText());
            assertTrue(initialize.at("/result/capabilities/tools").isObject());

            send(writer, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
            send(writer, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}");
            JsonNode listed = read(reader);
            assertEquals(23, listed.at("/result/tools").size());

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

    @Test
    @Timeout(10)
    void admittedSlotsAreHeldThroughResponseSendSoExcessGetsTypedBusyResponse() throws Exception {
        CompletableFuture<ActionResult> pending = new CompletableFuture<>();
        AtomicInteger protocolCalls = new AtomicInteger();
        CompletableFuture<Void> firstAdmitted = new CompletableFuture<>();
        RecordingHarness harness = new RecordingHarness() {
            @Override public CompletionStage<ActionResult> perform(Locator locator,
                    dev.gdx.uiharness.core.action.Action action, Deadline deadline) {
                if (protocolCalls.incrementAndGet() == 1) {
                    firstAdmitted.complete(null);
                }
                return pending;
            }
        };
        try (PipedInputStream serverInput = new PipedInputStream();
                PipedOutputStream clientOutput = new PipedOutputStream(serverInput);
                PipedInputStream clientInput = new PipedInputStream();
                PipedOutputStream serverOutputDelegate = new PipedOutputStream(clientInput);
                GatedOutputStream gated = new GatedOutputStream(serverOutputDelegate);
                HarnessMcpServer server = HarnessMcpServer.open(
                        service(harness), new RecordingArtifacts(), serverInput, gated);
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                        clientOutput, StandardCharsets.UTF_8));
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        clientInput, StandardCharsets.UTF_8));
                ExecutorService readerExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            initialize(writer, reader);
            send(writer, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
            gated.hold();

            // The admitted-or-rejected tool calls translate immediately, but every response
            // send is blocked by the output gate: all transport slots stay occupied until the
            // sends complete. The first request that exceeds the cap must receive the typed
            // limit response instead of being dispatched, and nothing may leak while the
            // output is blocked.
            List<JsonNode> collected = Collections.synchronizedList(new ArrayList<>());
            CompletableFuture<Void> excessCollected = new CompletableFuture<>();
            CompletableFuture<Void> allCollected = new CompletableFuture<>();
            readerExecutor.submit(() -> {
                try {
                    for (int index = 0; index < 9; index++) {
                        collected.add(read(reader));
                        if (collected.size() == 5) {
                            excessCollected.complete(null);
                        }
                    }
                    allCollected.complete(null);
                } catch (Exception failure) {
                    allCollected.completeExceptionally(failure);
                    excessCollected.completeExceptionally(failure);
                }
            });
            for (int id = 100; id < 108; id++) {
                send(writer, actionCallJson(id));
            }
            send(writer, "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"ping\",\"params\":{}}");

            firstAdmitted.get(5, TimeUnit.SECONDS);
            assertEquals(1, protocolCalls.get(),
                    "only the single running mutation may reach the protocol");

            gated.release();
            // The five non-action answers (limit diagnostics and the transport busy response)
            // arrive while the four admitted mutations stay blocked; only then is the protocol
            // released so the queued mutations drain.
            excessCollected.get(5, TimeUnit.SECONDS);
            pending.complete(new ActionResult(1, 2, "clicked", Map.of("target", "root")));
            allCollected.get(5, TimeUnit.SECONDS);

            Map<Integer, JsonNode> byId = new java.util.HashMap<>();
            for (JsonNode response : collected) {
                byId.put(response.path("id").asInt(), response);
            }
            assertEquals(9, byId.size(), "every flooded request is answered exactly once");
            assertEquals(4, protocolCalls.get(),
                    "the four admitted mutations drain exactly once after release");
            int actionResults = 0;
            int limitDiagnostics = 0;
            int busy = 0;
            for (Map.Entry<Integer, JsonNode> entry : byId.entrySet()) {
                int id = entry.getKey();
                JsonNode response = entry.getValue();
                String kind = response.at("/result/structuredContent/kind").asText();
                if ("action-result".equals(kind)) {
                    actionResults++;
                } else if (response.path("result").path("isError").asBoolean(false)
                        && "LIMIT_EXCEEDED".equals(
                                response.at("/result/structuredContent/code").asText())) {
                    limitDiagnostics++;
                } else if (response.path("error").path("code").asInt()
                        == HarnessMcpServer.TRANSPORT_BUSY_ERROR_CODE) {
                    busy++;
                } else if (id == 9 && response.path("result").isObject()) {
                    // The ping can only be dispatched after the gate releases and a rejected
                    // send drains; when it is dispatched it is still answered bounded.
                } else {
                    throw new AssertionError("unexpected response for " + id + ": " + response);
                }
            }
            assertEquals(4, actionResults,
                    "the admitted mutations answer after their sends complete");
            assertTrue(limitDiagnostics >= 3 && limitDiagnostics <= 4,
                    "the handler rejects every call beyond its own session bound");
            assertEquals(1, busy,
                    "the first excess request receives the typed limit response while every "
                            + "send slot is held");
        }
    }

    @Test
    @Timeout(10)
    void transportFloodWithBlockedOutputBoundsDispatchedWorkAndBackpressuresExcess()
            throws Exception {
        CompletableFuture<ActionResult> pending = new CompletableFuture<>();
        AtomicInteger protocolCalls = new AtomicInteger();
        CompletableFuture<Void> firstAdmitted = new CompletableFuture<>();
        RecordingHarness harness = new RecordingHarness() {
            @Override public CompletionStage<ActionResult> perform(Locator locator,
                    dev.gdx.uiharness.core.action.Action action, Deadline deadline) {
                if (protocolCalls.incrementAndGet() == 1) {
                    firstAdmitted.complete(null);
                }
                return pending;
            }
        };
        try (PipedInputStream serverInput = new PipedInputStream();
                PipedOutputStream clientOutput = new PipedOutputStream(serverInput);
                PipedInputStream clientInput = new PipedInputStream();
                PipedOutputStream serverOutputDelegate = new PipedOutputStream(clientInput);
                GatedOutputStream gated = new GatedOutputStream(serverOutputDelegate);
                HarnessMcpServer server = HarnessMcpServer.open(
                        service(harness), new RecordingArtifacts(), serverInput, gated);
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                        clientOutput, StandardCharsets.UTF_8));
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        clientInput, StandardCharsets.UTF_8));
                ExecutorService readerExecutor = Executors.newVirtualThreadPerTaskExecutor();
                ExecutorService writerExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            initialize(writer, reader);
            send(writer, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
            gated.hold();

            // A flood far larger than the admission cap must not grow the transport's
            // dispatched work or its output queue: while the output is blocked, only the
            // single running mutation executes, the read loop applies input backpressure
            // instead of dispatching everything, and the first excess request receives the
            // typed limit response once the gate opens.
            AtomicReference<JsonNode> busyResponse = new AtomicReference<>();
            CompletableFuture<Void> busySeen = new CompletableFuture<>();
            CountDownLatch clientThreadsMayExit = new CountDownLatch(1);
            try {
            readerExecutor.submit(() -> {
                try {
                    while (true) {
                        JsonNode response = read(reader);
                        if (response.path("error").path("code").asInt()
                                == HarnessMcpServer.TRANSPORT_BUSY_ERROR_CODE) {
                            busyResponse.set(response);
                            busySeen.complete(null);
                            clientThreadsMayExit.await();
                            return;
                        }
                    }
                } catch (Exception failure) {
                    busySeen.completeExceptionally(failure);
                }
            });
            CompletableFuture<Void> allWritesSent = new CompletableFuture<>();
            CompletableFuture.runAsync(() -> {
                try {
                    for (int id = 100; id < 120; id++) {
                        send(writer, actionCallJson(id));
                    }
                    allWritesSent.complete(null);
                    clientThreadsMayExit.await();
                } catch (Exception failure) {
                    allWritesSent.completeExceptionally(failure);
                    throw new IllegalStateException("writer failed", failure);
                }
            }, writerExecutor);

                firstAdmitted.get(5, TimeUnit.SECONDS);
                assertEquals(1, protocolCalls.get(),
                        "only the single running mutation may reach the protocol");
                assertThrows(TimeoutException.class,
                        () -> allWritesSent.get(1, TimeUnit.SECONDS),
                        "the blocked output must backpressure the flood instead of dispatching "
                                + "it unboundedly");

                gated.release();
                busySeen.get(5, TimeUnit.SECONDS);
                assertEquals(HarnessMcpServer.TRANSPORT_BUSY_ERROR_CODE,
                        busyResponse.get().path("error").path("code").asInt(),
                        "the typed limit response identifies the transport rejection");
                assertFalse(busyResponse.get().path("error").path("message").asText().isBlank(),
                        "the typed limit response carries a bounded message");
                assertEquals(1, protocolCalls.get(),
                        "the flood never grows protocol work beyond the admission bound while "
                                + "the admitted mutations stay blocked");
            } finally {
                // PipedInputStream treats a dead last-reader or last-writer thread as a broken
                // pipe even while both streams remain open. Keep both client threads alive
                // through the assertion so a synthetic broken pipe cannot close the transport,
                // cancel the first mutation, and legitimately advance the serialized lane.
                clientThreadsMayExit.countDown();
            }
        }
    }

    @Test
    @Timeout(10)
    void unknownAndSchemaInvalidFloodIsBoundedAndExcessGetsTypedLimitResponse()
            throws Exception {
        RecordingHarness harness = new RecordingHarness();
        int total = 40;
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
                ExecutorService readerExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            initialize(writer, reader);
            send(writer, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");

            // Unknown-method and schema-invalid calls must not bypass the transport bound:
            // they are answered with their own bounded errors up to the admission cap, and
            // every excess request receives the typed limit response instead of being
            // dispatched onto an unbounded queue.
            List<JsonNode> collected = Collections.synchronizedList(new ArrayList<>());
            CompletableFuture<Void> allCollected = CompletableFuture.runAsync(() -> {
                try {
                    for (int index = 0; index < total + 1; index++) {
                        collected.add(read(reader));
                    }
                } catch (Exception failure) {
                    throw new IllegalStateException("reader failed", failure);
                }
            }, readerExecutor);
            for (int id = 200; id < 220; id++) {
                send(writer, "{\"jsonrpc\":\"2.0\",\"id\":" + id
                        + ",\"method\":\"unknown/procedure\",\"params\":{}}");
            }
            for (int id = 300; id < 320; id++) {
                send(writer, "{\"jsonrpc\":\"2.0\",\"id\":" + id
                        + ",\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"ui_action\","
                        + "\"arguments\":{\"sessionId\":\"game\"}}}");
            }
            send(writer, "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"ping\",\"params\":{}}");
            allCollected.get(5, TimeUnit.SECONDS);

            Map<Integer, JsonNode> byId = new java.util.HashMap<>();
            for (JsonNode response : collected) {
                byId.put(response.path("id").asInt(), response);
            }
            assertEquals(total + 1, byId.size(),
                    "every flooded request and the follow-up ping is answered exactly once");
            int busy = 0;
            for (Map.Entry<Integer, JsonNode> entry : byId.entrySet()) {
                int id = entry.getKey();
                JsonNode response = entry.getValue();
                if (response.path("error").path("code").asInt()
                        == HarnessMcpServer.TRANSPORT_BUSY_ERROR_CODE) {
                    busy++;
                } else if (id >= 200 && id < 220) {
                    assertEquals(-32601, response.path("error").path("code").asInt(),
                            "an unknown-method flood call is boundedly answered: " + id);
                } else if (id >= 300 && id < 320) {
                    String code = response.at("/result/structuredContent/code").asText();
                    assertTrue(response.path("result").path("isError").asBoolean(false)
                                    && !code.isBlank(),
                            "a schema-invalid flood call is boundedly answered: " + id
                                    + " but was: " + code);
                } else if (id == 9) {
                    assertTrue(response.path("result").isObject()
                                    || !response.path("error").isMissingNode(),
                            "the follow-up ping is answered after the flood");
                }
            }
            assertTrue(busy >= 1,
                    "the transport must reject excess invalid requests with the typed "
                            + "limit response instead of dispatching them unboundedly");
        }
    }

    @Test
    @Timeout(10)
    void parseErrorWriteFailureTerminatesTransport() throws Exception {
        RecordingHarness harness = new RecordingHarness();
        ExecutorService waiter = Executors.newVirtualThreadPerTaskExecutor();
        try (PipedInputStream serverInput = new PipedInputStream();
                PipedOutputStream clientOutput = new PipedOutputStream(serverInput);
                HarnessMcpServer server = HarnessMcpServer.open(
                        service(harness), new RecordingArtifacts(),
                        serverInput, new FailingOutputStream())) {
            // A rejected frame must trigger a parse-error write; when that write fails
            // (e.g. stdout closed by the client) the transport must terminate instead
            // of hanging forever on an unobserved future. The bounded get() turns a
            // hang into a fast TimeoutException failure.
            writeRaw(clientOutput, new byte[] {(byte) 0xc3, 0x28});
            CompletableFuture<Void> termination = CompletableFuture.runAsync(
                    server::awaitTermination, waiter);
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> termination.get(5, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            waiter.shutdownNow();
        }
    }

    @Test
    @Timeout(10)
    void parseErrorWriteFailureWinsOverEofClose() throws Exception {
        RecordingHarness harness = new RecordingHarness();
        BlockingFailingOutputStream output = new BlockingFailingOutputStream();
        ExecutorService waiter = Executors.newVirtualThreadPerTaskExecutor();
        try (PipedInputStream serverInput = new PipedInputStream();
                PipedOutputStream clientOutput = new PipedOutputStream(serverInput);
                HarnessMcpServer server = HarnessMcpServer.open(
                        service(harness), new RecordingArtifacts(),
                        serverInput, output)) {
            writeRaw(clientOutput, new byte[] {(byte) 0xc3, 0x28});
            assertTrue(output.writeStarted.await(5, java.util.concurrent.TimeUnit.SECONDS));
            // Race EOF against the in-flight parse-error write. The output failure must
            // win and terminate the transport exceptionally; it must not be swallowed by
            // the read loop's natural EOF close (which would complete termination
            // normally). The bounded sleep lets the read loop reach EOF before the write
            // is released, mirroring the existing bounded sleep-loop coordination.
            closeStdin(clientOutput);
            Thread.sleep(200);
            output.release();
            CompletableFuture<Void> termination = CompletableFuture.runAsync(
                    server::awaitTermination, waiter);
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> termination.get(5, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            waiter.shutdownNow();
        }
    }

    @Test
    @Timeout(10)
    void oversizedOrMalformedFrameDoesNotTerminateServer() throws Exception {
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

            byte[] oversized = new byte[ProtocolJson.MAX_REQUEST_BYTES + 1];
            Arrays.fill(oversized, (byte) 'x');
            writeRaw(clientOutput, oversized);
            assertParseError(reader);

            writeRaw(clientOutput, new byte[] {(byte) 0xc3, 0x28});
            assertParseError(reader);

            send(writer, "[".repeat(ProtocolJson.MAX_NESTING_DEPTH + 1)
                    + "]".repeat(ProtocolJson.MAX_NESTING_DEPTH + 1));
            assertParseError(reader);

            send(writer, "{\"value\":\""
                    + "a".repeat(ProtocolJson.MAX_STRING_LENGTH + 1) + "\"}");
            assertParseError(reader);

            send(writer, "{\"value\":"
                    + "9".repeat(ProtocolJson.MAX_NUMBER_LENGTH + 1) + "}");
            assertParseError(reader);

            send(writer, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                    + "\"params\":{\"protocolVersion\":\"2225-11-25\",\"capabilities\":{},"
                    + "\"clientInfo\":{\"name\":\"contract\",\"version\":\"1.0\"}}}");
            JsonNode initialize = read(reader);
            assertEquals(1, initialize.path("id").asInt());
            assertEquals("libgdx-ui-harness",
                    initialize.at("/result/serverInfo/name").asText());
            assertEquals(0, harness.actionCalls.get());
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
                + "\"params\":{\"protocolVersion\":\"2225-11-25\",\"capabilities\":{},"
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

    private static String text(McpSchema.CallToolResult result) {
        return result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(McpSchema.TextContent.class::cast)
                .map(McpSchema.TextContent::text)
                .reduce("", (a, b) -> a + b);
    }

    private static void assertInternalTraceId(Map<String, Object> content) {
        String traceId = (String) content.get("traceId");
        assertNotNull(traceId);
        assertTrue(traceId.matches("internal-[0-9a-f]{32}"));
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

    private static HarnessResponse.Result.Screenshot screenshot(byte[] png) {
        return new HarnessResponse.Result.Screenshot(
                Base64.getEncoder().encodeToString(png), sha256Hex(png),
                1, 1, 1, 1, 1, 1);
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException failure) {
            throw new AssertionError(failure);
        }
    }

    private static String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().getFirst()).text();
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

    private static void writeRaw(PipedOutputStream output, byte[] frame) throws Exception {
        output.write(frame);
        output.write('\n');
        output.flush();
    }

    private static void assertParseError(BufferedReader reader) throws Exception {
        JsonNode error = read(reader);
        assertEquals(-32700, error.at("/error/code").asInt());
        assertEquals("Parse error", error.at("/error/message").asText());
        assertTrue(error.at("/id").isNull());
    }

    private static JsonNode read(BufferedReader reader) throws Exception {
        return ProtocolJson.mapper().readTree(reader.readLine());
    }

    private static HarnessProtocolService navigationService(AtomicReference<Command> observed) {
        var registry = new dev.gdx.uiharness.core.scenario.ScenarioRegistry();
        registry.register(new dev.gdx.uiharness.core.scenario.ScenarioDefinition(
                        1, "navigation", "1", "app", List.of("desktop"),
                        1, Duration.ofMinutes(1)),
                new dev.gdx.uiharness.core.scenario.ScenarioLifecycle() {
                    @Override public void setup(
                            dev.gdx.uiharness.core.scenario.ScenarioRequest request) {}
                    @Override public void reset(
                            dev.gdx.uiharness.core.scenario.ScenarioRequest request) {}
                    @Override public boolean ready(
                            dev.gdx.uiharness.core.scenario.ScenarioRequest request) {
                        return true;
                    }
                    @Override public String startStateIdentity(
                            dev.gdx.uiharness.core.scenario.ScenarioRequest request,
                            dev.gdx.uiharness.core.model.SemanticSnapshot snapshot) {
                        return "ready";
                    }
                    @Override public void cleanup(
                            dev.gdx.uiharness.core.scenario.ScenarioRequest request) {}
                });
        HarnessProtocolService.NavigationCoordinator coordinator =
                new HarnessProtocolService.NavigationCoordinator() {
                    @Override public CompletionStage<NavigationResult> inspect(
                            Command.NavigationSpec spec, Deadline deadline) {
                        observed.set(new Command.NavigationInspect(spec));
                        return CompletableFuture.completedFuture(navigated());
                    }

                    @Override public CompletionStage<NavigationResult> validate(
                            Command.NavigationSpec spec, Deadline deadline) {
                        observed.set(new Command.NavigationValidate(spec));
                        return CompletableFuture.completedFuture(navigated());
                    }

                    private NavigationResult navigated() {
                        return new NavigationResult(
                                1,
                                new NavigationPath(1, "test-id:first", List.of(
                                        new NavigationStep(NavigationInput.TAB, 10, 22,
                                                11, 22, "test-id:first", "test-id:second",
                                                null)),
                                        NavigationReason.COMPLETE),
                                List.of("test-id:first", "test-id:second"),
                                List.of(),
                                false);
                    }
                };
        HarnessProtocolService.Session session = new HarnessProtocolService.Session(
                new RecordingHarness(), new StrictResolution(),
                new WaitEngine(() -> SNAPSHOT, new StrictResolution(), CLOCK,
                        listener -> () -> {}),
                new ScreenCapture() {
                    @Override public CompletionStage<CapturedImage> capture(
                            CaptureRequest request, Deadline deadline) {
                        return CompletableFuture.completedFuture(new CapturedImage(
                                new byte[] {1, 2, 3}, "0".repeat(64), 1, 1, 1, 1,
                                new CapturedImage.Scale(1, 1)));
                    }

                    @Override public void close() {}
                },
                new CapabilitySet(List.of(
                        "ui_navigation_inspect", "ui_navigation_validate")),
                HarnessProtocolService.TraceController.unsupported(),
                java.util.Optional.of(registry),
                java.util.Optional.empty(),
                java.util.Optional.of(coordinator));
        return new HarnessProtocolService(
                Map.of("game", session), CLOCK, Runnable::run);
    }

    private static HarnessProtocolService service(RecordingHarness harness) {
        return service(harness, new byte[] {1, 2, 3}, "0".repeat(64), 1, 1);
    }

    private static HarnessProtocolService serviceWithCapture(byte[] payload, String sha) {
        return service(new RecordingHarness(), payload, sha, 8_192, 8_192);
    }

    private static HarnessProtocolService service(
            RecordingHarness harness, byte[] png, String sha, int width, int height) {
        LocatorEngine locators = new StrictResolution();
        FrameSignal frames = listener -> () -> {};
        AssertionSnapshotSource assertionSnapshots = new AssertionSnapshotSource() {
            @Override public SemanticSnapshot currentSnapshot() {
                return SNAPSHOT;
            }

            @Override public SemanticSnapshot snapshotFor(FrameSignal.Frame frame) {
                return new SemanticSnapshot(
                        frame.revision(), frame.frame(), SNAPSHOT.rootId(), SNAPSHOT.nodes());
            }
        };
        WaitEngine waits = new WaitEngine(
                () -> SNAPSHOT, assertionSnapshots, locators, CLOCK, frames,
                (delay, wakeup) -> () -> {});
        CapabilitySet capabilities = new CapabilitySet(List.of(
                "action", "capabilities", "query", "screenshot", "snapshot", "trace",
                "ui_assert", "wait"));
        ScreenCapture capture = new ScreenCapture() {
            @Override public CompletionStage<CapturedImage> capture(
                    CaptureRequest request, Deadline deadline) {
                return CompletableFuture.completedFuture(new CapturedImage(
                        png, sha, 1, 1, width, height, new CapturedImage.Scale(1, 1)));
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

    /**
     * An output stream that stalls every write while {@link #hold()} is active and forwards to
     * the delegate once {@link #release()} opens the gate, so a test can prove that a blocked
     * response path bounds dispatch and applies input backpressure instead of queueing output.
     */
    private static final class GatedOutputStream extends java.io.OutputStream {
        private final PipedOutputStream delegate;
        private final Object gate = new Object();
        private boolean blocked;

        GatedOutputStream(PipedOutputStream delegate) {
            this.delegate = delegate;
        }

        void hold() {
            synchronized (gate) {
                blocked = true;
            }
        }

        void release() {
            synchronized (gate) {
                blocked = false;
                gate.notifyAll();
            }
        }

        @Override public void write(int value) throws java.io.IOException {
            awaitOpen();
            delegate.write(value);
        }

        @Override public void write(byte[] bytes, int offset, int length)
                throws java.io.IOException {
            awaitOpen();
            delegate.write(bytes, offset, length);
        }

        @Override public void flush() throws java.io.IOException {
            awaitOpen();
            delegate.flush();
        }

        private void awaitOpen() throws java.io.IOException {
            synchronized (gate) {
                while (blocked) {
                    try {
                        gate.wait();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new java.io.IOException(
                                "interrupted waiting for output gate", interrupted);
                    }
                }
            }
        }
    }

    private static final class BlockingFailingOutputStream extends java.io.OutputStream {
        private final java.util.concurrent.CountDownLatch writeStarted =
                new java.util.concurrent.CountDownLatch(1);
        private final java.util.concurrent.CountDownLatch release =
                new java.util.concurrent.CountDownLatch(1);

        @Override public void write(int value) throws java.io.IOException {
            awaitRelease();
        }

        @Override public void write(byte[] bytes, int offset, int length)
                throws java.io.IOException {
            awaitRelease();
        }

        private void awaitRelease() throws java.io.IOException {
            writeStarted.countDown();
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new java.io.IOException(
                        "interrupted waiting for write release", interrupted);
            }
            throw new java.io.IOException("simulated stdout failure");
        }

        void release() {
            release.countDown();
        }
    }

    private static final class FailingOutputStream extends java.io.OutputStream {
        @Override public void write(int value) throws java.io.IOException {
            throw new java.io.IOException("simulated stdout failure");
        }

        @Override public void write(byte[] bytes, int offset, int length)
                throws java.io.IOException {
            throw new java.io.IOException("simulated stdout failure");
        }
    }

    private static class RecordingHarness implements Harness {
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

    /**
     * Records protocol invocations in invocation order and completes one signal per invocation,
     * so tests can await observable protocol-start events without polling or sleeping.
     */
    private static final class ProtocolSignals {
        private final List<String> starts = Collections.synchronizedList(new ArrayList<>());
        private final Map<Integer, CompletableFuture<Void>> signals = new ConcurrentHashMap<>();

        void record(String requestId) {
            synchronized (starts) {
                starts.add(requestId);
                CompletableFuture<Void> signal = signals.get(starts.size());
                if (signal != null) {
                    signal.complete(null);
                }
            }
        }

        CompletableFuture<Void> signal(int count) {
            synchronized (starts) {
                CompletableFuture<Void> signal = signals.computeIfAbsent(
                        count, ignored -> new CompletableFuture<>());
                if (starts.size() >= count) {
                    signal.complete(null);
                }
                return signal;
            }
        }

        List<String> starts() {
            synchronized (starts) {
                return List.copyOf(starts);
            }
        }
    }

    private static final class RecordingArtifacts implements ArtifactReference.Publisher {
        private byte[] lastBytes;
        private ArtifactReference lastReference;
        private int count;

        @Override public ArtifactReference publish(String mediaType, byte[] content) {
            return record(mediaType, content);
        }

        @Override public ArtifactReference publishBuffer(String mediaType, ByteBuffer content) {
            byte[] copy = new byte[content.remaining()];
            content.get(copy);
            return record(mediaType, copy);
        }

        private ArtifactReference record(String mediaType, byte[] content) {
            lastBytes = content.clone();
            count++;
            ArtifactReference reference = new ArtifactReference(
                    "artifact:" + count, mediaType, content.length, sha256Hex(content));
            lastReference = reference;
            return reference;
        }
    }

    /**
     * Publisher that rejects the byte[] overload outright, so any base64-decode or byte[]
     * fallback path in the handler surfaces as an error instead of a silent publication.
     */
    private static final class ByteBufferOnlyArtifacts implements ArtifactReference.Publisher {
        private final AtomicInteger byteBufferCalls = new AtomicInteger();
        private final AtomicInteger byteArrayCalls = new AtomicInteger();
        private byte[] lastBytes;

        @Override public ArtifactReference publish(String mediaType, byte[] content) {
            byteArrayCalls.incrementAndGet();
            throw new AssertionError(
                    "screenshot publication must publish through the ByteBuffer overload");
        }

        @Override public ArtifactReference publishBuffer(String mediaType, ByteBuffer content) {
            byteBufferCalls.incrementAndGet();
            byte[] copy = new byte[content.remaining()];
            content.get(copy);
            lastBytes = copy;
            return new ArtifactReference("artifact:1", mediaType, copy.length, sha256Hex(copy));
        }
    }

    /** Publisher whose receipt contradicts the published payload (wrong length and digest). */
    private static final class MismatchedReceiptArtifacts implements ArtifactReference.Publisher {
        private int count;

        @Override public ArtifactReference publish(String mediaType, byte[] content) {
            count++;
            return new ArtifactReference(
                    "artifact:" + count, mediaType, content.length + 1, "0".repeat(64));
        }

        @Override public ArtifactReference publishBuffer(String mediaType, ByteBuffer content) {
            byte[] copy = new byte[content.remaining()];
            content.get(copy);
            return publish(mediaType, copy);
        }
    }

    /** Sneaky-throws a checked failure through an unchecked signature. */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> ArtifactReference sneakyThrow(Throwable failure)
            throws T {
        throw (T) failure;
    }
}
