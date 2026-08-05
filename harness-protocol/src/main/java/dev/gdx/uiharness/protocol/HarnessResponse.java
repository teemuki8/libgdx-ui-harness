package dev.gdx.uiharness.protocol;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import dev.gdx.uiharness.core.action.ActionResult;
import dev.gdx.uiharness.core.assertion.AssertionResult;
import dev.gdx.uiharness.core.capture.CapturedImage;
import dev.gdx.uiharness.core.contract.ConditionalRule;
import dev.gdx.uiharness.core.contract.ContractValue;
import dev.gdx.uiharness.core.contract.ControlState;
import dev.gdx.uiharness.core.contract.StateActionContract;
import dev.gdx.uiharness.core.contract.TransitionOutcome;
import dev.gdx.uiharness.core.contract.ValidationRule;
import dev.gdx.uiharness.core.contract.ValidationStatus;
import dev.gdx.uiharness.core.contract.ViewportState;
import dev.gdx.uiharness.core.error.ErrorCode;
import dev.gdx.uiharness.core.error.ErrorEvidence;
import dev.gdx.uiharness.core.error.HarnessException;
import dev.gdx.uiharness.core.locator.QueryResult;
import dev.gdx.uiharness.core.scenario.ScenarioDefinition;
import dev.gdx.uiharness.core.scenario.ScenarioFailure;
import dev.gdx.uiharness.core.scenario.ScenarioResult;
import dev.gdx.uiharness.core.model.Bounds;
import dev.gdx.uiharness.core.model.SemanticNode;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import dev.gdx.uiharness.core.model.SemanticState;
import dev.gdx.uiharness.core.wait.WaitResult;
import dev.gdx.uiharness.core.visual.VisualComparisonResult;
import dev.gdx.uiharness.core.typography.TypographyDiagnosticResult;
import dev.gdx.uiharness.core.typography.TypographyReport;
import dev.gdx.uiharness.core.layout.LayoutDiagnosticResult;
import dev.gdx.uiharness.core.layout.LayoutQuiescenceResult;
import dev.gdx.uiharness.core.layout.LayoutReport;
import dev.gdx.uiharness.core.layout.LayoutStabilitySample;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Explicit V1 response union, correlated to exactly one request and session. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "status")
@JsonSubTypes({
    @JsonSubTypes.Type(value = HarnessResponse.Success.class, name = "ok"),
    @JsonSubTypes.Type(value = HarnessResponse.Failure.class, name = "error")
})
public sealed interface HarnessResponse permits HarnessResponse.Success, HarnessResponse.Failure {
    /** Protocol version used to encode this response. */
    ProtocolVersion version();

    /** Correlation identifier copied from the request. */
    String requestId();

    /** Session identifier copied from the request. */
    String sessionId();

    /** Successful command response. */
    record Success(
            ProtocolVersion version, String requestId, String sessionId, Result result)
            implements HarnessResponse {
        /** Validates response correlation and result. */
        public Success {
            version = Objects.requireNonNull(version, "version");
            ProtocolJson.requireIdentifier(requestId, "requestId");
            ProtocolJson.requireIdentifier(sessionId, "sessionId");
            result = Objects.requireNonNull(result, "result");
        }
    }

    /** Failed command response containing only remotely safe evidence. */
    record Failure(
            ProtocolVersion version, String requestId, String sessionId, ProtocolError error)
            implements HarnessResponse {
        /** Validates response correlation and error. */
        public Failure {
            version = Objects.requireNonNull(version, "version");
            ProtocolJson.requireIdentifier(requestId, "requestId");
            ProtocolJson.requireIdentifier(sessionId, "sessionId");
            error = Objects.requireNonNull(error, "error");
        }
    }

    /** Explicit allowlisted V1 result union. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = Result.Sessions.class, name = "sessions"),
        @JsonSubTypes.Type(value = Result.Capabilities.class, name = "capabilities"),
        @JsonSubTypes.Type(value = Result.Snapshot.class, name = "snapshot"),
        @JsonSubTypes.Type(value = Result.Query.class, name = "query"),
        @JsonSubTypes.Type(value = Result.Action.class, name = "action"),
        @JsonSubTypes.Type(value = Result.Assertion.class, name = "assertion"),
        @JsonSubTypes.Type(value = Result.Wait.class, name = "wait"),
        @JsonSubTypes.Type(value = Result.Screenshot.class, name = "screenshot"),
        @JsonSubTypes.Type(value = Result.InspectCompare.class, name = "inspect-compare"),
        @JsonSubTypes.Type(
                value = Result.TypographyDiagnostic.class,
                name = "typography-diagnostic"),
        @JsonSubTypes.Type(value = Result.LayoutDiagnostic.class, name = "layout-diagnostic"),
        @JsonSubTypes.Type(value = Result.TraceStarted.class, name = "trace-started"),
        @JsonSubTypes.Type(value = Result.TraceStopped.class, name = "trace-stopped"),
        @JsonSubTypes.Type(value = Result.ScenarioList.class, name = "scenario-list"),
        @JsonSubTypes.Type(value = Result.ScenarioStart.class, name = "scenario-start"),
        @JsonSubTypes.Type(value = Result.Navigation.class, name = "navigation"),
        @JsonSubTypes.Type(value = Result.LayoutValidation.class, name = "layout-validation"),
        @JsonSubTypes.Type(value = Result.MatrixRunStarted.class, name = "matrix-run-started"),
        @JsonSubTypes.Type(value = Result.MatrixReportData.class, name = "matrix-report")
    })
    sealed interface Result permits Result.Sessions, Result.Capabilities, Result.Snapshot,
            Result.Query, Result.Action, Result.Assertion, Result.Wait, Result.Screenshot,
            Result.TraceStarted, Result.InspectCompare, Result.TypographyDiagnostic,
            Result.LayoutDiagnostic, Result.TraceStopped, Result.ScenarioList,
            Result.ScenarioStart, Result.Navigation, Result.LayoutValidation,
            Result.MatrixRunStarted, Result.MatrixReportData {
        /** Active session catalog. */
        record Sessions(List<SessionInfo> sessions) implements Result {
            /** Defensively copies the session catalog. */
            public Sessions {
                sessions = List.copyOf(Objects.requireNonNull(sessions, "sessions"));
            }
        }

        /** Capabilities of the selected session. */
        record Capabilities(List<String> capabilities) implements Result {
            /** Retains canonical capability ordering. */
            public Capabilities {
                capabilities = new CapabilitySet(capabilities).capabilities();
            }
        }

        /** Bounded registered scenarios, or an explicit unavailable catalog. */
        record ScenarioList(boolean available, List<ScenarioDefinitionData> scenarios)
                implements Result {
            /** Copies the stable scenario catalog and enforces unavailable consistency. */
            public ScenarioList {
                scenarios = List.copyOf(Objects.requireNonNull(scenarios, "scenarios"));
                if (!available && !scenarios.isEmpty()) {
                    throw new IllegalArgumentException(
                            "unavailable scenario catalog must be empty");
                }
                if (scenarios.size() > 256) {
                    throw new IllegalArgumentException("scenario catalog exceeds 256 entries");
                }
            }
        }

        /** Closed terminal outcome of one scenario start request. */
        record ScenarioStart(ScenarioStartOutcome outcome) implements Result {
            /** Requires one terminal outcome. */
            public ScenarioStart {
                outcome = Objects.requireNonNull(outcome, "outcome");
            }
        }

        /** Bounded navigation path, known focusables, and unreachable controls. */
        record Navigation(dev.gdx.uiharness.core.navigation.NavigationResult result)
                implements Result {
            /** Requires a closed navigation result. */
            public Navigation {
                result = Objects.requireNonNull(result, "result");
            }
        }

        /** Deterministic bounded whole-stage or subtree layout validation result. */
        record LayoutValidation(dev.gdx.uiharness.core.layout.LayoutValidationResult result)
                implements Result {
            /** Requires a closed layout validation result. */
            public LayoutValidation {
                result = Objects.requireNonNull(result, "result");
            }
        }

        /** Bounded identifier of one started matrix run. */
        record MatrixRunStarted(String runId) implements Result {
            /** Validates the run identifier. */
            public MatrixRunStarted {
                ProtocolJson.requireIdentifier(runId, "runId");
            }
        }

        /** Compact immutable report of one matrix run; never embeds screenshots. */
        record MatrixReportData(dev.gdx.uiharness.core.matrix.MatrixReport report)
                implements Result {
            /** Requires a closed matrix report. */
            public MatrixReportData {
                report = Objects.requireNonNull(report, "report");
            }
        }

        /** Fresh semantic snapshot. */
        record Snapshot(SnapshotData snapshot) implements Result {
            /** Validates snapshot data. */
            public Snapshot {
                Objects.requireNonNull(snapshot, "snapshot");
            }
        }

        /** Bounded locator matches and diagnostics. */
        record Query(List<NodeData> matches, List<Map<String, String>> evidence)
                implements Result {
            /** Defensively copies query data. */
            public Query {
                matches = List.copyOf(Objects.requireNonNull(matches, "matches"));
                evidence = copyEvidence(evidence, "query evidence");
            }

            static Query fromCore(QueryResult result) {
                return new Query(result.matches().stream().map(NodeData::fromCore).toList(),
                        result.evidence());
            }
        }

        /** Result of an input action. */
        record Action(
                long beforeRevision,
                long afterRevision,
                String observedState,
                Map<String, String> evidence) implements Result {
            /** Validates revisions and copies evidence. */
            public Action {
                if (beforeRevision < 0 || afterRevision <= beforeRevision) {
                    throw new IllegalArgumentException("invalid action revisions");
                }
                ProtocolJson.requireText(observedState, "observedState");
                evidence = copyBoundedMap(evidence, "action evidence");
            }

            static Action fromCore(ActionResult result) {
                return new Action(result.beforeRevision(), result.afterRevision(),
                        result.observedState(), result.evidence());
            }
        }

        /** Complete bounded evidence for one declarative assertion outcome. */
        record Assertion(
                int schemaVersion,
                String outcome,
                Command.LocatorSpec locator,
                Command.AssertionSpec assertion,
                String nodeId,
                String expected,
                String lastObserved,
                String actionability,
                long revision,
                long frame,
                long elapsedMillis,
                List<Map<String, String>> candidates,
                boolean truncated,
                String traceId) implements Result {
            /** Validates and defensively bounds assertion evidence. */
            public Assertion {
                if (schemaVersion != dev.gdx.uiharness.core.assertion.AssertionRequest.SCHEMA_VERSION) {
                    throw new IllegalArgumentException(
                            "unsupported assertion schema version: " + schemaVersion);
                }
                if (!"passed".equals(outcome) && !"failed".equals(outcome)) {
                    throw new IllegalArgumentException("unknown assertion outcome: " + outcome);
                }
                locator = Objects.requireNonNull(locator, "locator");
                assertion = Objects.requireNonNull(assertion, "assertion");
                requireBoundedEvidenceText(nodeId, "nodeId");
                requireBoundedEvidenceText(expected, "expected");
                requireBoundedEvidenceText(lastObserved, "lastObserved");
                if (!"satisfied".equals(actionability) && !"retryable".equals(actionability)) {
                    throw new IllegalArgumentException(
                            "unknown assertion actionability: " + actionability);
                }
                if (revision < 0 || frame < 0 || elapsedMillis < 0) {
                    throw new IllegalArgumentException(
                            "assertion counters and elapsed time must be non-negative");
                }
                candidates = copyEvidence(candidates, "assertion candidates");
                if (candidates.size() > 1_000) {
                    throw new IllegalArgumentException(
                            "assertion candidates exceed protocol limit");
                }
                if (traceId != null) {
                    ProtocolJson.requireIdentifier(traceId, "traceId");
                }
            }

            static Assertion fromCore(
                    Command.Assert command, AssertionResult result) {
                AssertionResult.Status status = result.status();
                return new Assertion(
                        command.schemaVersion(),
                        status == AssertionResult.Status.PASSED ? "passed" : "failed",
                        command.locator(),
                        command.assertion(),
                        result.evidence().nodeId(),
                        result.evidence().expected(),
                        result.evidence().observed(),
                        status == AssertionResult.Status.PASSED ? "satisfied" : "retryable",
                        result.evidence().revision(),
                        result.evidence().frame(),
                        result.elapsedNanos() / 1_000_000,
                        List.of(),
                        false,
                        null);
            }
            private static void requireBoundedEvidenceText(String value, String name) {
                Objects.requireNonNull(value, name);
                if (value.length() > ProtocolJson.MAX_STRING_LENGTH) {
                    throw new IllegalArgumentException(name + " exceeds protocol string limit");
                }
            }
        }

        /** Semantic state that completed a wait. */
        record Wait(
                long revision,
                long frame,
                List<NodeData> matches,
                List<Map<String, String>> evidence) implements Result {
            /** Validates and copies completed wait data. */
            public Wait {
                if (revision < 0 || frame < 0) {
                    throw new IllegalArgumentException("wait counters must be non-negative");
                }
                matches = List.copyOf(Objects.requireNonNull(matches, "matches"));
                evidence = copyEvidence(evidence, "wait evidence");
            }

            static Wait fromCore(WaitResult result) {
                return new Wait(result.snapshot().revision(), result.snapshot().frame(),
                        result.queryResult().matches().stream().map(NodeData::fromCore).toList(),
                        result.queryResult().evidence());
            }
        }

        /** Bounded base64 PNG and capture metadata. */
        record Screenshot(
                String pngBase64,
                String sha256,
                long frame,
                long revision,
                int width,
                int height,
                double scaleX,
                double scaleY) implements Result {
            /**
             * Maximum PNG bytes whose base64 form leaves room for the response envelope within
             * {@link ProtocolJson#MAX_RESPONSE_BYTES}.
             */
            public static final int MAX_PNG_BYTES =
                    ((ProtocolJson.MAX_RESPONSE_BYTES - 4_096) / 4) * 3;
            private static final int MAX_BASE64_LENGTH = (MAX_PNG_BYTES / 3) * 4;

            /** Validates encoded screenshot metadata. */
            public Screenshot {
                Objects.requireNonNull(pngBase64, "pngBase64");
                if (pngBase64.isBlank() || pngBase64.length() > MAX_BASE64_LENGTH) {
                    throw new IllegalArgumentException(
                            "pngBase64 exceeds protocol screenshot limit");
                }
                ProtocolJson.requireText(sha256, "sha256");
                if (frame < 0 || revision < 0 || width <= 0 || height <= 0) {
                    throw new IllegalArgumentException("invalid screenshot metadata");
                }
                if (!Double.isFinite(scaleX) || scaleX <= 0
                        || !Double.isFinite(scaleY) || scaleY <= 0) {
                    throw new IllegalArgumentException("invalid screenshot scale");
                }
            }

            static Screenshot fromCore(CapturedImage image) {
                byte[] pngBytes = image.pngBytes();
                if (pngBytes.length > MAX_PNG_BYTES) {
                    throw new HarnessException(ErrorCode.LIMIT_EXCEEDED,
                            "Captured PNG exceeds protocol response byte limit",
                            ErrorEvidence.ofDetails(Map.of(
                                    "limit", "response-byte-limit",
                                    "maximumBytes", Integer.toString(MAX_PNG_BYTES),
                                    "actualBytes", Integer.toString(pngBytes.length))));
                }
                return new Screenshot(Base64.getEncoder().encodeToString(pngBytes),
                        image.sha256(), image.frame(), image.revision(), image.width(),
                        image.height(), image.scale().x(), image.scale().y());
            }
        }

        /** Provenance-bound inspect-capture-compare result with bounded current PNG evidence. */
        record InspectCompare(
                String status,
                String policy,
                ReferenceVisualData reference,
                CurrentVisualData current,
                MetricsData metrics,
                List<DifferenceData> differences,
                List<RegionData> regions,
                HeatmapData heatmap,
                List<ComparisonDiagnosticData> diagnostics,
                int iterations,
                long elapsedMillis,
                String currentPngBase64,
                String heatmapPngBase64) implements Result {
            /** Validates the bounded wire projection. */
            public InspectCompare {
                ProtocolJson.requireIdentifier(status, "comparison status");
                ProtocolJson.requireIdentifier(policy, "comparison policy");
                differences = List.copyOf(
                        Objects.requireNonNull(differences, "differences"));
                regions = List.copyOf(Objects.requireNonNull(regions, "regions"));
                diagnostics = List.copyOf(
                        Objects.requireNonNull(diagnostics, "diagnostics"));
                if (!Set.of("incomplete", "stale", "not-converged", "converged")
                        .contains(status)
                        || differences.size() > 1_024 || regions.size() > 256
                        || diagnostics.size() > 256
                        || iterations < 0 || iterations > 64
                        || elapsedMillis < 0 || elapsedMillis > 120_000) {
                    throw new IllegalArgumentException(
                            "comparison result is outside protocol bounds");
                }
                if ((current == null) != (currentPngBase64 == null)) {
                    throw new IllegalArgumentException(
                            "current metadata and PNG evidence must appear together");
                }
                if ((heatmap == null) != (heatmapPngBase64 == null)) {
                    throw new IllegalArgumentException(
                            "heatmap metadata and PNG evidence must appear together");
                }
                if (current != null && (regions.stream().anyMatch(region ->
                        (long) region.x() + region.width() > current.width()
                                || (long) region.y() + region.height() > current.height())
                        || heatmap != null && (heatmap.width() != current.width()
                        || heatmap.height() != current.height()))) {
                    throw new IllegalArgumentException(
                            "spatial evidence exceeds current capture bounds");
                }
                if (("converged".equals(status) || "not-converged".equals(status))
                        && (reference == null || current == null || metrics == null)) {
                    throw new IllegalArgumentException(
                            "completed comparison requires full evidence");
                }
                if (("incomplete".equals(status) || "stale".equals(status))
                        && diagnostics.isEmpty()) {
                    throw new IllegalArgumentException(
                            "incomplete comparison requires diagnostics");
                }
                if (currentPngBase64 != null
                        && currentPngBase64.length() > Screenshot.MAX_BASE64_LENGTH) {
                    throw new IllegalArgumentException(
                            "comparison PNG exceeds protocol response limit");
                }
                if (currentPngBase64 != null) {
                    byte[] png;
                    try {
                        png = Base64.getDecoder().decode(currentPngBase64);
                    } catch (IllegalArgumentException invalid) {
                        throw new IllegalArgumentException(
                                "comparison PNG is not valid base64", invalid);
                    }
                    if (!sha256(png).equals(current.sha256())) {
                        throw new IllegalArgumentException(
                                "comparison PNG hash does not match current metadata");
                    }
                }
                if (heatmapPngBase64 != null) {
                    if (heatmapPngBase64.length() > Screenshot.MAX_BASE64_LENGTH) {
                        throw new IllegalArgumentException(
                                "heatmap PNG exceeds protocol response limit");
                    }
                    byte[] png;
                    try {
                        png = Base64.getDecoder().decode(heatmapPngBase64);
                    } catch (IllegalArgumentException invalid) {
                        throw new IllegalArgumentException(
                                "heatmap PNG is not valid base64", invalid);
                    }
                    if (!sha256(png).equals(heatmap.sha256())) {
                        throw new IllegalArgumentException(
                                "heatmap PNG hash does not match metadata");
                    }
                }
            }

            /** Compatibility constructor for responses without spatial evidence. */
            public InspectCompare(
                    String status,
                    String policy,
                    ReferenceVisualData reference,
                    CurrentVisualData current,
                    MetricsData metrics,
                    List<DifferenceData> differences,
                    List<ComparisonDiagnosticData> diagnostics,
                    int iterations,
                    long elapsedMillis,
                    String currentPngBase64) {
                this(status, policy, reference, current, metrics, differences,
                        List.of(), null, diagnostics, iterations, elapsedMillis,
                        currentPngBase64, null);
            }

            static InspectCompare fromCore(VisualComparisonResult result) {
                Objects.requireNonNull(result, "result");
                String png = result.current() == null ? null
                        : Base64.getEncoder().encodeToString(
                                result.current().image().pngBytes());
                String heatmapPng = result.heatmap() == null ? null
                        : Base64.getEncoder().encodeToString(
                                result.heatmap().pngBytes());
                return new InspectCompare(
                        wire(result.status().name()), result.policy().wireName(),
                        result.reference() == null ? null
                                : ReferenceVisualData.fromCore(result.reference()),
                        result.current() == null ? null
                                : CurrentVisualData.fromCore(result.current()),
                        result.metrics() == null ? null
                                : MetricsData.fromCore(result.metrics()),
                        result.differences().stream()
                                .map(DifferenceData::fromCore).toList(),
                        result.regions().stream().map(RegionData::fromCore).toList(),
                        result.heatmap() == null ? null
                                : HeatmapData.fromCore(result.heatmap()),
                        result.diagnostics().stream()
                                .map(ComparisonDiagnosticData::fromCore).toList(),
                        result.iterations(), result.elapsed().toMillis(), png,
                        heatmapPng);
            }
        }

        /** Capture-backed actor-attributed typography diagnostic result. */
        record TypographyDiagnostic(
                String status,
                String referenceId,
                CurrentCaptureData current,
                List<TypographyReport> reports,
                List<ComparisonDiagnosticData> diagnostics,
                long elapsedMillis,
                String currentPngBase64) implements Result {

            /** Validates the bounded wire projection and current PNG integrity. */
            public TypographyDiagnostic {
                if (!Set.of(
                                "pixel-sharp",
                                "not-pixel-sharp",
                                "incomplete",
                                "not-diagnosable",
                                "stale",
                                "not-stable")
                        .contains(status)) {
                    throw new IllegalArgumentException("unknown typography status");
                }
                if (referenceId != null) {
                    ProtocolJson.requireIdentifier(referenceId, "referenceId");
                }
                reports = List.copyOf(Objects.requireNonNull(reports, "reports"));
                diagnostics =
                        List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
                if (reports.size() > 256 || diagnostics.size() > 256
                        || elapsedMillis < 0 || elapsedMillis > 120_000) {
                    throw new IllegalArgumentException(
                            "typography result is outside protocol bounds");
                }
                if ((current == null) != (currentPngBase64 == null)) {
                    throw new IllegalArgumentException(
                            "current typography metadata and PNG must appear together");
                }
                if (currentPngBase64 != null) {
                    byte[] png;
                    try {
                        png = Base64.getDecoder().decode(currentPngBase64);
                    } catch (IllegalArgumentException invalid) {
                        throw new IllegalArgumentException(
                                "typography PNG is not valid base64", invalid);
                    }
                    if (png.length > Screenshot.MAX_PNG_BYTES
                            || !sha256(png).equals(current.sha256())) {
                        throw new IllegalArgumentException(
                                "typography PNG does not match bounded current metadata");
                    }
                }
            }

            static TypographyDiagnostic fromCore(TypographyDiagnosticResult result) {
                Objects.requireNonNull(result, "result");
                String png = result.current() == null
                        ? null
                        : Base64.getEncoder().encodeToString(
                                result.current().pngBytes());
                return new TypographyDiagnostic(
                        wire(result.status().name()),
                        result.reference() == null
                                ? null : result.reference().referenceId(),
                        result.current() == null
                                ? null : CurrentCaptureData.fromCore(result.current()),
                        result.reports(),
                        result.diagnostics().stream()
                                .map(ComparisonDiagnosticData::fromCore)
                                .toList(),
                        result.elapsed().toMillis(),
                        png);
            }
        }

        /** Capture-backed actor-attributed layout, clipping, and viewport result. */
        record LayoutDiagnostic(
                String status,
                String referenceId,
                CurrentCaptureData current,
                List<LayoutReport> reports,
                QuiescenceData settling,
                QuiescenceData captures,
                List<ComparisonDiagnosticData> diagnostics,
                long elapsedMillis,
                String currentPngBase64) implements Result {

            /** Validates bounded wire data and current PNG integrity. */
            public LayoutDiagnostic {
                if (!Set.of(
                                "conformant",
                                "non-conformant",
                                "incomplete",
                                "not-diagnosable",
                                "stale",
                                "not-stable")
                        .contains(status)) {
                    throw new IllegalArgumentException("unknown layout status");
                }
                if (referenceId != null) {
                    ProtocolJson.requireIdentifier(referenceId, "referenceId");
                }
                reports = List.copyOf(Objects.requireNonNull(reports, "reports"));
                diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
                if (reports.size() > 256 || diagnostics.size() > 256
                        || elapsedMillis < 0 || elapsedMillis > 2_000) {
                    throw new IllegalArgumentException(
                            "layout result is outside protocol bounds");
                }
                if ((current == null) != (currentPngBase64 == null)) {
                    throw new IllegalArgumentException(
                            "current layout metadata and PNG must appear together");
                }
                if (currentPngBase64 != null) {
                    byte[] png;
                    try {
                        png = Base64.getDecoder().decode(currentPngBase64);
                    } catch (IllegalArgumentException invalid) {
                        throw new IllegalArgumentException(
                                "layout PNG is not valid base64", invalid);
                    }
                    if (png.length > Screenshot.MAX_PNG_BYTES
                            || !sha256(png).equals(current.sha256())) {
                        throw new IllegalArgumentException(
                                "layout PNG does not match bounded current metadata");
                    }
                }
            }

            static LayoutDiagnostic fromCore(LayoutDiagnosticResult result) {
                Objects.requireNonNull(result, "result");
                String png = result.current() == null
                        ? null
                        : Base64.getEncoder().encodeToString(result.current().pngBytes());
                return new LayoutDiagnostic(
                        wire(result.status().name()),
                        result.reference() == null
                                ? null : result.reference().referenceId(),
                        result.current() == null
                                ? null : CurrentCaptureData.fromCore(result.current()),
                        result.reports(),
                        result.settling() == null
                                ? null : QuiescenceData.fromCore(result.settling()),
                        result.captures() == null
                                ? null : QuiescenceData.fromCore(result.captures()),
                        result.diagnostics().stream()
                                .map(ComparisonDiagnosticData::fromCore)
                                .toList(),
                        result.elapsed().toMillis(),
                        png);
            }

            /** Duration-free bounded wire projection of one quiescence proof. */
            public record QuiescenceData(
                    boolean settled,
                    String status,
                    int stableFrameCount,
                    long elapsedMillis,
                    List<LayoutStabilitySample> samples) {
                /** Validates the fixed issue-four proof bounds. */
                public QuiescenceData {
                    if (!Set.of("settled", "not-stable", "incomplete").contains(status)
                            || stableFrameCount < 0 || stableFrameCount > 125
                            || elapsedMillis < 0 || elapsedMillis > 2_000) {
                        throw new IllegalArgumentException(
                                "layout quiescence data is outside fixed bounds");
                    }
                    samples = List.copyOf(Objects.requireNonNull(samples, "samples"));
                    if (samples.size() > 125) {
                        throw new IllegalArgumentException(
                                "layout quiescence samples exceed fixed bounds");
                    }
                }

                static QuiescenceData fromCore(LayoutQuiescenceResult result) {
                    return new QuiescenceData(
                            result.settled(),
                            result.status(),
                            result.stableFrameCount(),
                            result.elapsed().toMillis(),
                            result.samples());
                }
            }
        }

        /** Successful trace start. */
        record TraceStarted(String traceId) implements Result {
            /** Validates trace identifier. */
            public TraceStarted {
                ProtocolJson.requireIdentifier(traceId, "traceId");
            }
        }

        /** Successful trace stop and bounded artifact reference. */
        record TraceStopped(
                String traceId, String traceReference, long eventCount, long bytes)
                implements Result {
            /** Validates trace result metadata. */
            public TraceStopped {
                ProtocolJson.requireIdentifier(traceId, "traceId");
                ProtocolJson.requireText(traceReference, "traceReference");
                if (eventCount < 0 || bytes < 0) {
                    throw new IllegalArgumentException("trace counters must be non-negative");
                }
            }
        }

        private static List<Map<String, String>> copyEvidence(
                List<Map<String, String>> evidence, String name) {
            Objects.requireNonNull(evidence, "evidence");
            return evidence.stream()
                    .map(item -> copyBoundedMap(item, name))
                    .toList();
        }
    }

    /** Closed terminal outcomes for a protocol scenario start. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = ScenarioStartOutcome.Unavailable.class, name = "unavailable"),
        @JsonSubTypes.Type(value = ScenarioStartOutcome.Rejected.class, name = "rejected"),
        @JsonSubTypes.Type(value = ScenarioStartOutcome.Failed.class, name = "failed"),
        @JsonSubTypes.Type(value = ScenarioStartOutcome.Completed.class, name = "completed")
    })
    sealed interface ScenarioStartOutcome permits ScenarioStartOutcome.Unavailable,
            ScenarioStartOutcome.Rejected, ScenarioStartOutcome.Failed,
            ScenarioStartOutcome.Completed {
        /** The selected session has no scenario registry or coordinator. */
        record Unavailable() implements ScenarioStartOutcome {}

        /** The registered boundary rejected the selected identity before execution. */
        record Rejected(String reason) implements ScenarioStartOutcome {
            private static final Set<String> REASONS = Set.of(
                    "unknown-scenario", "incompatible-scenario", "unsupported-profile");

            /** Restricts rejections to the closed pre-execution failure set. */
            public Rejected {
                if (!REASONS.contains(reason)) {
                    throw new IllegalArgumentException("unknown scenario rejection: " + reason);
                }
            }
        }

        /** The validated start reached a closed terminal host handoff failure. */
        record Failed(String reason) implements ScenarioStartOutcome {
            private static final Set<String> REASONS = Set.of("deadline", "cancelled");

            /** Restricts failures to the closed post-validation terminal set. */
            public Failed {
                if (!REASONS.contains(reason)) {
                    throw new IllegalArgumentException("unknown scenario start failure: " + reason);
                }
            }
        }

        /** One completed scenario lifecycle with bounded terminal evidence. */
        record Completed(
                ScenarioResultData scenario, String reconnectIdentity) implements ScenarioStartOutcome {
            /** Converts in-process core terminal evidence to its transport projection. */
            public Completed(ScenarioResult result) {
                this(ScenarioResultData.fromCore(result), null);
            }

            /** Converts replacement core evidence and its opaque host reconnect identity. */
            public Completed(ScenarioResult result, String reconnectIdentity) {
                this(ScenarioResultData.fromCore(result), reconnectIdentity);
            }

            /** Requires terminal scenario evidence and bounds an optional reconnect identity. */
            public Completed {
                scenario = Objects.requireNonNull(scenario, "scenario");
                if (reconnectIdentity != null) {
                    ProtocolJson.requireIdentifier(reconnectIdentity, "reconnectIdentity");
                }
            }
        }
    }

    /** Bounded wire metadata for an application-registered scenario. */
    record ScenarioDefinitionData(
            int schemaVersion,
            String id,
            String definitionVersion,
            String applicationId,
            List<String> supportedProfileIds,
            int maxSetupAttempts,
            long maxDurationMillis) {
        /** Validates and copies definition metadata. */
        public ScenarioDefinitionData {
            if (schemaVersion != ScenarioDefinition.SCHEMA_VERSION) {
                throw new IllegalArgumentException("schemaVersion must be 1");
            }
            ProtocolJson.requireIdentifier(id, "id");
            ProtocolJson.requireText(definitionVersion, "definitionVersion");
            ProtocolJson.requireIdentifier(applicationId, "applicationId");
            supportedProfileIds =
                    List.copyOf(Objects.requireNonNull(supportedProfileIds, "supportedProfileIds"));
            if (supportedProfileIds.size() > 256) {
                throw new IllegalArgumentException("supportedProfileIds exceeds 256 entries");
            }
            supportedProfileIds.forEach(
                    profile -> ProtocolJson.requireIdentifier(profile, "supportedProfileId"));
            if (maxSetupAttempts < 1 || maxSetupAttempts > 16
                    || maxDurationMillis <= 0 || maxDurationMillis > 600_000) {
                throw new IllegalArgumentException("scenario definition bounds are invalid");
            }
        }

        static ScenarioDefinitionData fromCore(ScenarioDefinition definition) {
            return new ScenarioDefinitionData(
                    definition.schemaVersion(), definition.id(), definition.definitionVersion(),
                    definition.applicationId(), definition.supportedProfileIds(),
                    definition.maxSetupAttempts(), definition.maxDuration().toMillis());
        }
    }

    /** Closed wire projection of core terminal scenario failures. */
    enum ScenarioFailureData {
        UNKNOWN_SCENARIO("unknown-scenario"),
        INCOMPATIBLE_SCENARIO("incompatible-scenario"),
        UNSUPPORTED_PROFILE("unsupported-profile"),
        SETUP_REJECTED("setup-rejected"),
        RESET_REJECTED("reset-rejected"),
        READINESS_REJECTED("readiness-rejected"),
        READINESS_DEADLINE("readiness-deadline"),
        PROCESS_REPLACED("process-replaced"),
        SESSION_REPLACED("session-replaced"),
        STALE_REVISION("stale-revision"),
        CLEANUP_FAILED("cleanup-failed"),
        NONDETERMINISTIC_INITIAL_STATE("nondeterministic-initial-state"),
        DISPATCH_FAILED("dispatch-failed"),
        CANCELLED("cancelled");

        private final String wireName;

        ScenarioFailureData(String wireName) {
            this.wireName = wireName;
        }

        /** Returns the stable protocol spelling. */
        @JsonValue
        public String wireName() {
            return wireName;
        }

        /** Resolves only recognized protocol spellings. */
        @JsonCreator
        public static ScenarioFailureData fromWireName(String wireName) {
            for (ScenarioFailureData value : values()) {
                if (value.wireName.equals(wireName)) {
                    return value;
                }
            }
            throw new IllegalArgumentException("Unknown scenario failure " + wireName);
        }

        /** Exhaustively projects one core failure to its transport value. */
        static ScenarioFailureData fromCore(ScenarioFailure failure) {
            return switch (failure) {
                case UNKNOWN_SCENARIO -> UNKNOWN_SCENARIO;
                case INCOMPATIBLE_SCENARIO -> INCOMPATIBLE_SCENARIO;
                case UNSUPPORTED_PROFILE -> UNSUPPORTED_PROFILE;
                case SETUP_REJECTED -> SETUP_REJECTED;
                case RESET_REJECTED -> RESET_REJECTED;
                case READINESS_REJECTED -> READINESS_REJECTED;
                case READINESS_DEADLINE -> READINESS_DEADLINE;
                case PROCESS_REPLACED -> PROCESS_REPLACED;
                case SESSION_REPLACED -> SESSION_REPLACED;
                case STALE_REVISION -> STALE_REVISION;
                case CLEANUP_FAILED -> CLEANUP_FAILED;
                case NONDETERMINISTIC_INITIAL_STATE -> NONDETERMINISTIC_INITIAL_STATE;
                case DISPATCH_FAILED -> DISPATCH_FAILED;
                case CANCELLED -> CANCELLED;
            };
        }
    }

    /** Bounded terminal scenario execution evidence. */
    record ScenarioResultData(
            int schemaVersion,
            String scenarioId,
            String definitionVersion,
            String configurationDigest,
            long seed,
            String applicationId,
            String processId,
            String sessionId,
            long startFrame,
            long startRevision,
            long readyFrame,
            long readyRevision,
            String profileId,
            String startStateIdentity,
            long elapsedMillis,
            int setupAttempts,
            boolean cleanupCompleted,
            ScenarioFailureData failure) {
        /** Validates public result bounds and terminal correlation. */
        public ScenarioResultData {
            if (schemaVersion != ScenarioDefinition.SCHEMA_VERSION) {
                throw new IllegalArgumentException("schemaVersion must be 1");
            }
            ProtocolJson.requireIdentifier(scenarioId, "scenarioId");
            ProtocolJson.requireText(definitionVersion, "definitionVersion");
            ProtocolJson.requireText(configurationDigest, "configurationDigest");
            ProtocolJson.requireIdentifier(applicationId, "applicationId");
            ProtocolJson.requireIdentifier(processId, "processId");
            ProtocolJson.requireIdentifier(sessionId, "sessionId");
            ProtocolJson.requireIdentifier(profileId, "profileId");
            ProtocolJson.requireText(startStateIdentity, "startStateIdentity");
            if (startFrame < 0 || startRevision < 0 || readyFrame < 0 || readyRevision < 0
                    || elapsedMillis < 0 || elapsedMillis > 600_000
                    || setupAttempts < 0 || setupAttempts > 16) {
                throw new IllegalArgumentException("scenario result bounds are invalid");
            }
            if (failure == null
                    && (readyFrame < startFrame || readyRevision < startRevision)) {
                throw new IllegalArgumentException(
                        "successful scenario readiness precedes its start evidence");
            }
        }

        static ScenarioResultData fromCore(ScenarioResult result) {
            return new ScenarioResultData(
                    result.schemaVersion(), result.scenarioId(), result.definitionVersion(),
                    result.configurationDigest(), result.seed(), result.applicationId(),
                    result.processId(), result.sessionId(), result.startFrame(),
                    result.startRevision(), result.readyFrame(), result.readyRevision(),
                    result.profileId(), result.startStateIdentity(), result.elapsed().toMillis(),
                    result.setupAttempts(), result.cleanupCompleted(),
                    result.failure().map(ScenarioFailureData::fromCore).orElse(null));
        }
    }

    /** One session and its canonical capability names. */
    record SessionInfo(String sessionId, List<String> capabilities) {
        /** Validates session identity and canonicalizes capabilities. */
        public SessionInfo {
            ProtocolJson.requireIdentifier(sessionId, "sessionId");
            capabilities = new CapabilitySet(capabilities).capabilities();
        }
    }

    /** Explicit transport representation of a semantic snapshot. */
    record SnapshotData(
            long revision,
            long frame,
            String rootId,
            List<NodeData> nodes,
            ContractData contract) {
        /** Validates and copies snapshot data. */
        public SnapshotData {
            if (revision < 0 || frame < 0) {
                throw new IllegalArgumentException("snapshot counters must be non-negative");
            }
            ProtocolJson.requireIdentifier(rootId, "rootId");
            nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
        }

        /** Retains the original V1 constructor for sessions without a domain contract. */
        public SnapshotData(
                long revision, long frame, String rootId, List<NodeData> nodes) {
            this(revision, frame, rootId, nodes, null);
        }

        static SnapshotData fromCore(SemanticSnapshot snapshot) {
            List<NodeData> nodes = new ArrayList<>(snapshot.nodes().size());
            appendDepthFirst(snapshot, snapshot.rootId(), nodes);
            return new SnapshotData(
                    snapshot.revision(), snapshot.frame(), snapshot.rootId(), nodes, null);
        }

        static SnapshotData fromCore(
                SemanticSnapshot snapshot, StateActionContract contract) {
            Objects.requireNonNull(contract, "contract");
            if (snapshot.revision() != contract.revision()
                    || snapshot.frame() != contract.frame()) {
                throw new IllegalArgumentException(
                        "semantic snapshot and state/action contract identities differ");
            }
            SnapshotData semantic = fromCore(snapshot);
            return new SnapshotData(
                    semantic.revision(), semantic.frame(), semantic.rootId(), semantic.nodes(),
                    ContractData.fromCore(contract));
        }

        private static void appendDepthFirst(
                SemanticSnapshot snapshot, String id, List<NodeData> destination) {
            SemanticNode node = snapshot.nodes().get(id);
            destination.add(NodeData.fromCore(node));
            for (String childId : node.childIds()) {
                appendDepthFirst(snapshot, childId, destination);
            }
        }
    }

    /** Accepted current capture identity without backend or semantic object leakage. */
    record CurrentCaptureData(
            String sha256,
            long revision,
            long frame,
            int width,
            int height,
            double scaleX,
            double scaleY) {
        /** Validates bounded capture identity. */
        public CurrentCaptureData {
            ProtocolJson.requireText(sha256, "current sha256");
            if (revision < 0 || frame < 0 || width <= 0 || height <= 0
                    || !Double.isFinite(scaleX) || scaleX <= 0
                    || !Double.isFinite(scaleY) || scaleY <= 0) {
                throw new IllegalArgumentException("invalid current capture metadata");
            }
        }

        static CurrentCaptureData fromCore(CapturedImage image) {
            return new CurrentCaptureData(
                    image.sha256(),
                    image.revision(),
                    image.frame(),
                    image.width(),
                    image.height(),
                    image.scale().x(),
                    image.scale().y());
        }
    }

    /** Immutable reference identity and its complete inspected semantic state. */
    record ReferenceVisualData(
            String referenceId,
            String applicationId,
            String sourceSessionId,
            String viewportId,
            String sha256,
            int width,
            int height,
            double scaleX,
            double scaleY,
            String capturedAt,
            SnapshotData snapshot) {
        public ReferenceVisualData {
            ProtocolJson.requireIdentifier(referenceId, "referenceId");
            ProtocolJson.requireIdentifier(applicationId, "applicationId");
            ProtocolJson.requireIdentifier(sourceSessionId, "sourceSessionId");
            ProtocolJson.requireIdentifier(viewportId, "viewportId");
            ProtocolJson.requireText(sha256, "reference sha256");
            ProtocolJson.requireText(capturedAt, "reference capturedAt");
        }

        static ReferenceVisualData fromCore(
                dev.gdx.uiharness.core.visual.VisualReference reference) {
            SnapshotData snapshot = reference.semanticSnapshot() == null
                    ? null
                    : reference.stateActionContract() == null
                            ? SnapshotData.fromCore(reference.semanticSnapshot())
                            : SnapshotData.fromCore(
                                    reference.semanticSnapshot(),
                                    reference.stateActionContract());
            return new ReferenceVisualData(
                    reference.referenceId(), reference.applicationId(),
                    reference.sourceSessionId(), reference.viewportId(),
                    reference.sha256(), reference.width(), reference.height(),
                    reference.scale().x(), reference.scale().y(),
                    reference.capturedAt().toString(), snapshot);
        }
    }

    /** Accepted current capture identity and its complete inspected semantic state. */
    record CurrentVisualData(
            String sessionId,
            String applicationId,
            String viewportId,
            String sha256,
            long revision,
            long frame,
            int width,
            int height,
            double scaleX,
            double scaleY,
            String capturedAt,
            SnapshotData snapshot) {
        public CurrentVisualData {
            ProtocolJson.requireIdentifier(sessionId, "current sessionId");
            ProtocolJson.requireIdentifier(applicationId, "current applicationId");
            ProtocolJson.requireIdentifier(viewportId, "current viewportId");
            ProtocolJson.requireText(sha256, "current sha256");
            ProtocolJson.requireText(capturedAt, "current capturedAt");
            Objects.requireNonNull(snapshot, "snapshot");
            if (revision != snapshot.revision() || frame != snapshot.frame()) {
                throw new IllegalArgumentException(
                        "current visual and semantic identities differ");
            }
        }

        static CurrentVisualData fromCore(
                dev.gdx.uiharness.core.visual.CurrentVisualEvidence current) {
            SnapshotData snapshot = current.stateActionContract() == null
                    ? SnapshotData.fromCore(current.semanticSnapshot())
                    : SnapshotData.fromCore(
                            current.semanticSnapshot(), current.stateActionContract());
            return new CurrentVisualData(
                    current.sessionId(), current.applicationId(), current.viewportId(),
                    current.image().sha256(), current.image().revision(),
                    current.image().frame(), current.image().width(),
                    current.image().height(), current.image().scale().x(),
                    current.image().scale().y(), current.capturedAt().toString(), snapshot);
        }
    }

    /** Deterministic raster metrics retained separately from status. */
    record MetricsData(
            long differingPixels, double meanAbsoluteError, int maximumChannelDelta) {
        public MetricsData {
            if (differingPixels < 0 || differingPixels > 33_554_432L
                    || !Double.isFinite(meanAbsoluteError)
                    || meanAbsoluteError < 0 || meanAbsoluteError > 255
                    || maximumChannelDelta < 0 || maximumChannelDelta > 255) {
                throw new IllegalArgumentException("invalid comparison metrics");
            }
        }

        static MetricsData fromCore(
                dev.gdx.uiharness.core.visual.VisualMetrics metrics) {
            return new MetricsData(
                    metrics.differingPixels(), metrics.meanAbsoluteError(),
                    metrics.maximumChannelDelta());
        }
    }

    /** One ordered attributed or residual difference. */
    record DifferenceData(
            String category,
            String controlId,
            String path,
            String expected,
            String observed,
            boolean blocking) {
        public DifferenceData {
            ProtocolJson.requireIdentifier(category, "difference category");
            if (controlId != null) {
                ProtocolJson.requireIdentifier(controlId, "difference controlId");
            }
            ProtocolJson.requireText(path, "difference path");
            ProtocolJson.requireText(expected, "difference expected");
            ProtocolJson.requireText(observed, "difference observed");
        }

        static DifferenceData fromCore(
                dev.gdx.uiharness.core.visual.VisualDifference difference) {
            return new DifferenceData(
                    wire(difference.category().name()), difference.controlId(),
                    difference.path(), difference.expected(), difference.observed(),
                    difference.blocking());
        }
    }

    /** One bounded framebuffer-top-left spatial difference region. */
    record RegionData(
            String category,
            String controlId,
            int x,
            int y,
            int width,
            int height,
            long differingPixels,
            double meanAbsoluteError) {
        public RegionData {
            ProtocolJson.requireIdentifier(category, "region category");
            if (controlId != null) {
                ProtocolJson.requireIdentifier(controlId, "region controlId");
            }
            if (x < 0 || y < 0 || width <= 0 || height <= 0
                    || differingPixels < 0
                    || differingPixels > (long) width * height
                    || !Double.isFinite(meanAbsoluteError)
                    || meanAbsoluteError < 0 || meanAbsoluteError > 255) {
                throw new IllegalArgumentException("invalid comparison region");
            }
        }

        static RegionData fromCore(
                dev.gdx.uiharness.core.visual.VisualRegion region) {
            return new RegionData(
                    wire(region.category().name()), region.controlId(),
                    region.x(), region.y(), region.width(), region.height(),
                    region.differingPixels(), region.meanAbsoluteError());
        }
    }

    /** Hash-bound heatmap metadata; encoded bytes travel separately. */
    record HeatmapData(String sha256, int width, int height) {
        public HeatmapData {
            if (sha256 == null || !sha256.matches("[0-9a-f]{64}")
                    || width <= 0 || height <= 0
                    || (long) width * height > 33_554_432L) {
                throw new IllegalArgumentException("invalid heatmap metadata");
            }
        }

        static HeatmapData fromCore(
                dev.gdx.uiharness.core.visual.VisualHeatmap heatmap) {
            return new HeatmapData(
                    heatmap.sha256(), heatmap.width(), heatmap.height());
        }
    }

    /** One actionable comparison diagnostic. */
    record ComparisonDiagnosticData(
            String code, String path, String expected, String observed) {
        public ComparisonDiagnosticData {
            ProtocolJson.requireIdentifier(code, "comparison diagnostic code");
            ProtocolJson.requireText(path, "comparison diagnostic path");
            ProtocolJson.requireText(expected, "comparison diagnostic expected");
            ProtocolJson.requireText(observed, "comparison diagnostic observed");
        }

        static ComparisonDiagnosticData fromCore(
                dev.gdx.uiharness.core.visual.ComparisonDiagnostic diagnostic) {
            return new ComparisonDiagnosticData(
                    diagnostic.code(), diagnostic.path(),
                    diagnostic.expected(), diagnostic.observed());
        }
    }

    /** Strict transport representation of the public evaluator-complete contract. */
    record ContractData(
            String schemaVersion,
            String stateId,
            long revision,
            long frame,
            List<ControlData> controls,
            List<String> focusOrder,
            String focusedControlId,
            List<ConditionData> conditions,
            List<ViewportData> viewports,
            TransitionData transition) {
        public ContractData {
            ProtocolJson.requireText(schemaVersion, "contract schemaVersion");
            if (!schemaVersion.startsWith("state-action/v1.")) {
                throw new IllegalArgumentException("unsupported state/action contract major");
            }
            ProtocolJson.requireText(stateId, "contract stateId");
            if (revision < 0 || frame < 0) {
                throw new IllegalArgumentException("contract counters must be non-negative");
            }
            controls = List.copyOf(Objects.requireNonNull(controls, "controls"));
            focusOrder = List.copyOf(Objects.requireNonNull(focusOrder, "focusOrder"));
            conditions = List.copyOf(Objects.requireNonNull(conditions, "conditions"));
            viewports = List.copyOf(Objects.requireNonNull(viewports, "viewports"));
            if (controls.size() > 256 || focusOrder.size() > 256
                    || conditions.size() > 256 || viewports.size() > 256) {
                throw new IllegalArgumentException("contract collection exceeds 256 entries");
            }
            java.util.Set<String> ids = new java.util.HashSet<>();
            for (int index = 0; index < controls.size(); index++) {
                if (!ids.add(controls.get(index).id())) {
                    throw new IllegalArgumentException(
                            "duplicate contract control ID at $.controls[" + index + "].id");
                }
            }
            if (!ids.containsAll(focusOrder)
                    || focusOrder.size() != new java.util.HashSet<>(focusOrder).size()
                    || (focusedControlId != null && !ids.contains(focusedControlId))) {
                throw new IllegalArgumentException("contract focus references unknown control");
            }
            for (ConditionData condition : conditions) {
                if (!ids.contains(condition.controllerId())
                        || !ids.contains(condition.dependentId())
                        || (condition.restoreFocusTo() != null
                        && !ids.contains(condition.restoreFocusTo()))) {
                    throw new IllegalArgumentException(
                            "contract condition references unknown control");
                }
            }
            java.util.Set<String> viewportIds = new java.util.HashSet<>();
            for (ViewportData viewport : viewports) {
                if (!viewportIds.add(viewport.id())
                        || !ids.containsAll(viewport.visibleControlIds())) {
                    throw new IllegalArgumentException(
                            "contract viewport identity or control reference is invalid");
                }
            }
            if (transition != null
                    && (transition.resultingRevision() != revision
                    || !transition.resultingStateId().equals(stateId))) {
                throw new IllegalArgumentException(
                        "contract transition does not identify the resulting state");
            }
        }

        static ContractData fromCore(StateActionContract contract) {
            Objects.requireNonNull(contract, "contract");
            return new ContractData(
                    contract.schemaVersion().wireName(), contract.stateId(),
                    contract.revision(), contract.frame(),
                    contract.controls().stream().map(ControlData::fromCore).toList(),
                    contract.focusOrder(), contract.focusedControlId(),
                    contract.conditions().stream().map(ConditionData::fromCore).toList(),
                    contract.viewports().stream().map(ViewportData::fromCore).toList(),
                    contract.transition() == null
                            ? null : TransitionData.fromCore(contract.transition()));
        }
    }

    record ControlData(
            String id,
            String role,
            String kind,
            String accessibleName,
            List<OptionData> options,
            ValueData defaultValue,
            ValueData currentValue,
            boolean visible,
            boolean enabled,
            boolean actionable,
            boolean focusable,
            boolean focused,
            ValidationRuleData validationRule,
            ValidationStatusData validationStatus) {
        public ControlData {
            ProtocolJson.requireIdentifier(id, "control id");
            ProtocolJson.requireIdentifier(role, "control role");
            ProtocolJson.requireIdentifier(kind, "control kind");
            if (!java.util.Set.of(
                    "button", "checkbox", "number", "range", "select", "text")
                    .contains(kind)) {
                throw new IllegalArgumentException("unknown control kind: " + kind);
            }
            ProtocolJson.requireText(accessibleName, "control accessibleName");
            options = List.copyOf(Objects.requireNonNull(options, "options"));
            Objects.requireNonNull(defaultValue, "defaultValue");
            Objects.requireNonNull(currentValue, "currentValue");
            Objects.requireNonNull(validationRule, "validationRule");
            Objects.requireNonNull(validationStatus, "validationStatus");
            if (focused && !focusable) {
                throw new IllegalArgumentException("focused control must be focusable");
            }
            if (actionable && (!visible || !enabled)) {
                throw new IllegalArgumentException(
                        "actionable control must be visible and enabled");
            }
        }

        static ControlData fromCore(ControlState state) {
            return new ControlData(
                    state.id(), wire(state.role().name()), wire(state.kind().name()),
                    state.accessibleName(),
                    state.options().stream()
                            .map(option -> new OptionData(
                                    ValueData.fromCore(option.value()), option.label()))
                            .toList(),
                    ValueData.fromCore(state.defaultValue()),
                    ValueData.fromCore(state.currentValue()),
                    state.visible(), state.enabled(), state.actionable(), state.focusable(),
                    state.focused(), ValidationRuleData.fromCore(state.validationRule()),
                    ValidationStatusData.fromCore(state.validationStatus()));
        }
    }

    record OptionData(ValueData value, String label) {
        public OptionData {
            Objects.requireNonNull(value, "value");
            ProtocolJson.requireText(label, "option label");
        }
    }

    record ValueData(
            String type,
            Boolean booleanValue,
            Long integerValue,
            String decimalValue,
            String textValue) {
        public ValueData {
            ProtocolJson.requireIdentifier(type, "value type");
            int present = (booleanValue == null ? 0 : 1)
                    + (integerValue == null ? 0 : 1)
                    + (decimalValue == null ? 0 : 1)
                    + (textValue == null ? 0 : 1);
            int expected = "null".equals(type) ? 0 : 1;
            if (present != expected) {
                throw new IllegalArgumentException("typed value has an invalid payload count");
            }
            switch (type) {
                case "null" -> {
                    // No payload.
                }
                case "boolean" -> Objects.requireNonNull(booleanValue, "booleanValue");
                case "integer" -> Objects.requireNonNull(integerValue, "integerValue");
                case "decimal" -> ProtocolJson.requireText(decimalValue, "decimalValue");
                case "text" -> {
                    Objects.requireNonNull(textValue, "textValue");
                    if (textValue.length() > ProtocolJson.MAX_STRING_LENGTH) {
                        throw new IllegalArgumentException("textValue exceeds protocol limit");
                    }
                }
                default -> throw new IllegalArgumentException("unknown typed value: " + type);
            }
        }

        static ValueData fromCore(ContractValue value) {
            return switch (value) {
                case ContractValue.NullValue ignored ->
                        new ValueData("null", null, null, null, null);
                case ContractValue.BooleanValue item ->
                        new ValueData("boolean", item.value(), null, null, null);
                case ContractValue.IntegerValue item ->
                        new ValueData("integer", null, item.value(), null, null);
                case ContractValue.DecimalValue item ->
                        new ValueData("decimal", null, null,
                                item.value().toPlainString(), null);
                case ContractValue.TextValue item ->
                        new ValueData("text", null, null, null, item.value());
            };
        }
    }

    record ValidationRuleData(
            String format, ValueData minimum, ValueData maximum, ValueData step) {
        public ValidationRuleData {
            ProtocolJson.requireIdentifier(format, "validation format");
        }

        static ValidationRuleData fromCore(ValidationRule rule) {
            return new ValidationRuleData(
                    rule.format(), nullable(rule.minimum()), nullable(rule.maximum()),
                    nullable(rule.step()));
        }

        private static ValueData nullable(ContractValue value) {
            return value == null ? null : ValueData.fromCore(value);
        }
    }

    record ValidationStatusData(boolean valid, List<String> messages) {
        public ValidationStatusData {
            messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
            messages.forEach(message ->
                    ProtocolJson.requireText(message, "validation message"));
            if (valid && !messages.isEmpty()) {
                throw new IllegalArgumentException(
                        "valid status must not contain validation messages");
            }
        }

        static ValidationStatusData fromCore(ValidationStatus status) {
            return new ValidationStatusData(status.valid(), status.messages());
        }
    }

    record ConditionData(
            String controllerId,
            ValueData equalsValue,
            String dependentId,
            boolean visibleWhenEqual,
            boolean actionableWhenEqual,
            String restoreFocusTo) {
        public ConditionData {
            ProtocolJson.requireIdentifier(controllerId, "condition controllerId");
            Objects.requireNonNull(equalsValue, "equalsValue");
            ProtocolJson.requireIdentifier(dependentId, "condition dependentId");
            if (restoreFocusTo != null) {
                ProtocolJson.requireIdentifier(restoreFocusTo, "condition restoreFocusTo");
            }
        }

        static ConditionData fromCore(ConditionalRule condition) {
            return new ConditionData(
                    condition.controllerId(), ValueData.fromCore(condition.equalsValue()),
                    condition.dependentId(), condition.visibleWhenEqual(),
                    condition.actionableWhenEqual(), condition.restoreFocusTo());
        }
    }

    record ViewportData(
            String id,
            double width,
            double height,
            double scrollX,
            double scrollY,
            double maxScrollX,
            double maxScrollY,
            List<String> visibleControlIds) {
        public ViewportData {
            ProtocolJson.requireIdentifier(id, "viewport id");
            if (!finiteNonNegative(width) || !finiteNonNegative(height)
                    || !finiteNonNegative(scrollX) || !finiteNonNegative(scrollY)
                    || !finiteNonNegative(maxScrollX) || !finiteNonNegative(maxScrollY)
                    || scrollX > maxScrollX || scrollY > maxScrollY) {
                throw new IllegalArgumentException("invalid viewport dimensions or scroll");
            }
            visibleControlIds = List.copyOf(
                    Objects.requireNonNull(visibleControlIds, "visibleControlIds"));
            if (visibleControlIds.size() > 256) {
                throw new IllegalArgumentException(
                        "viewport visible controls exceeds 256 entries");
            }
        }

        static ViewportData fromCore(ViewportState viewport) {
            return new ViewportData(
                    viewport.id(), viewport.width(), viewport.height(), viewport.scrollX(),
                    viewport.scrollY(), viewport.maxScrollX(), viewport.maxScrollY(),
                    viewport.visibleControlIds());
        }
    }

    record TransitionData(
            String actionId,
            boolean accepted,
            String rejectionReason,
            String resultingStateId,
            long resultingRevision,
            ValidationStatusData validation,
            String kind,
            String clipboardText,
            Map<String, ValueData> acceptedPayload) {
        public TransitionData {
            ProtocolJson.requireIdentifier(actionId, "transition actionId");
            if (accepted && rejectionReason != null) {
                throw new IllegalArgumentException(
                        "accepted transition has a rejection reason");
            }
            if (!accepted) {
                ProtocolJson.requireText(rejectionReason, "transition rejectionReason");
            }
            ProtocolJson.requireText(resultingStateId, "transition resultingStateId");
            if (resultingRevision < 0) {
                throw new IllegalArgumentException(
                        "transition resultingRevision must be non-negative");
            }
            Objects.requireNonNull(validation, "validation");
            ProtocolJson.requireIdentifier(kind, "transition kind");
            acceptedPayload = java.util.Collections.unmodifiableMap(
                    new LinkedHashMap<>(
                            Objects.requireNonNull(
                                    acceptedPayload, "acceptedPayload")));
            if (acceptedPayload.size() > 256) {
                throw new IllegalArgumentException(
                        "transition payload exceeds 256 entries");
            }
            if (!accepted && !acceptedPayload.isEmpty()) {
                throw new IllegalArgumentException(
                        "rejected transition has an accepted payload");
            }
        }

        static TransitionData fromCore(TransitionOutcome transition) {
            LinkedHashMap<String, ValueData> payload = new LinkedHashMap<>();
            transition.acceptedPayload().forEach(
                    (key, value) -> payload.put(key, ValueData.fromCore(value)));
            return new TransitionData(
                    transition.actionId(), transition.accepted(),
                    transition.rejectionReason(), transition.resultingStateId(),
                    transition.resultingRevision(),
                    ValidationStatusData.fromCore(transition.validation()),
                    wire(transition.kind().name()), transition.clipboardText(), payload);
        }
    }

    private static boolean finiteNonNegative(double value) {
        return Double.isFinite(value) && value >= 0;
    }

    private static String wire(String name) {
        return name.toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** Explicit transport representation of one semantic node. */
    record NodeData(
            String id,
            String parentId,
            List<String> childIds,
            String role,
            String accessibleName,
            String text,
            String label,
            String testId,
            String actorName,
            String actorType,
            StateData state,
            BoundsData localBounds,
            BoundsData stageBounds,
            BoundsData screenBounds,
            int zIndex,
            Map<String, String> properties) {
        /** Validates and copies semantic node data. */
        public NodeData {
            ProtocolJson.requireIdentifier(id, "id");
            if (parentId != null) {
                ProtocolJson.requireIdentifier(parentId, "parentId");
            }
            childIds = List.copyOf(Objects.requireNonNull(childIds, "childIds"));
            ProtocolJson.requireIdentifier(role, "role");
            state = Objects.requireNonNull(state, "state");
            localBounds = Objects.requireNonNull(localBounds, "localBounds");
            stageBounds = Objects.requireNonNull(stageBounds, "stageBounds");
            screenBounds = Objects.requireNonNull(screenBounds, "screenBounds");
            properties = copyBoundedMap(properties, "node properties");
        }

        static NodeData fromCore(SemanticNode node) {
            return new NodeData(node.id(), node.parentId(), node.childIds(),
                    node.role().name().toLowerCase(Locale.ROOT).replace('_', '-'),
                    node.accessibleName(), node.text(), node.label(), node.testId(),
                    node.actorName(), node.actorType(), StateData.fromCore(node.state()),
                    BoundsData.fromCore(node.localBounds()), BoundsData.fromCore(node.stageBounds()),
                    BoundsData.fromCore(node.screenBounds()), node.zIndex(), node.properties());
        }
    }

    /** Explicit transport representation of optional and required semantic state. */
    record StateData(
            boolean visible,
            boolean touchable,
            Boolean enabled,
            Boolean checked,
            Boolean selected,
            Boolean expanded,
            Boolean editable,
            boolean focused,
            boolean focusable,
            double effectiveAlpha,
            boolean clipped,
            boolean viewportIntersecting,
            boolean hitTarget) {
        /** Validates semantic alpha. */
        public StateData {
            if (!Double.isFinite(effectiveAlpha)
                    || effectiveAlpha < 0.0 || effectiveAlpha > 1.0) {
                throw new IllegalArgumentException("effectiveAlpha must be between zero and one");
            }
        }

        static StateData fromCore(SemanticState state) {
            return new StateData(state.visible(), state.touchable(), nullable(state.enabled()),
                    nullable(state.checked()), nullable(state.selected()),
                    nullable(state.expanded()), nullable(state.editable()), state.focused(),
                    state.focusable(), state.effectiveAlpha(), state.clipped(),
                    state.viewportIntersecting(), state.hitTarget());
        }

        private static Boolean nullable(Optional<Boolean> value) {
            return value.orElse(null);
        }
    }

    /** Explicit transport rectangle. */
    record BoundsData(double x, double y, double width, double height) {
        /** Validates finite non-negative dimensions. */
        public BoundsData {
            if (!Double.isFinite(x) || !Double.isFinite(y)
                    || !Double.isFinite(width) || width < 0
                    || !Double.isFinite(height) || height < 0) {
                throw new IllegalArgumentException("invalid bounds");
            }
        }

        static BoundsData fromCore(Bounds bounds) {
            return new BoundsData(bounds.x(), bounds.y(), bounds.width(), bounds.height());
        }
    }

    private static Map<String, String> copyBoundedMap(
            Map<String, String> source, String name) {
        Objects.requireNonNull(source, name);
        if (source.size() > 256) {
            throw new IllegalArgumentException(name + " exceeds 256 entries");
        }
        for (Map.Entry<String, String> entry : source.entrySet()) {
            ProtocolJson.requireText(entry.getKey(), name + " key");
            String value = Objects.requireNonNull(entry.getValue(), name + " value");
            if (value.length() > ProtocolJson.MAX_STRING_LENGTH) {
                throw new IllegalArgumentException(name + " value exceeds protocol string limit");
            }
        }
        return Map.copyOf(source);
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 is unavailable", impossible);
        }
    }
}
