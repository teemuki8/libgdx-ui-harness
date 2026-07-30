package dev.gdx.uiharness.protocol;

import dev.gdx.uiharness.core.capture.CaptureRequest;
import dev.gdx.uiharness.core.capture.CapturedImage;
import dev.gdx.uiharness.core.capture.ScreenCapture;
import dev.gdx.uiharness.core.time.Deadline;
import dev.gdx.uiharness.core.time.MonotonicClock;
import dev.gdx.uiharness.core.typography.TypographyControlReference;
import dev.gdx.uiharness.core.typography.TypographyDiagnosticRequest;
import dev.gdx.uiharness.core.typography.TypographyDiagnosticResult;
import dev.gdx.uiharness.core.typography.TypographyEvidenceProvider;
import dev.gdx.uiharness.core.typography.TypographyEvaluator;
import dev.gdx.uiharness.core.typography.TypographyObservation;
import dev.gdx.uiharness.core.typography.TypographyReference;
import dev.gdx.uiharness.core.typography.TypographyReferenceCatalog;
import dev.gdx.uiharness.core.typography.TypographyReport;
import dev.gdx.uiharness.core.typography.TypographyStatus;
import dev.gdx.uiharness.core.visual.ComparisonDiagnostic;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/** Coordinates one bounded capture-backed actor-attributed typography diagnosis. */
public final class TypographyDiagnosticService {
    private final String applicationId;
    private final String viewportId;
    private final ScreenCapture capture;
    private final TypographyReferenceCatalog references;
    private final TypographyEvidenceProvider evidence;
    private final MonotonicClock clock;
    private final TypographyEvaluator evaluator = new TypographyEvaluator();

    /** Creates an explicitly configured typography diagnostic service. */
    public TypographyDiagnosticService(
            String applicationId,
            String viewportId,
            ScreenCapture capture,
            TypographyReferenceCatalog references,
            TypographyEvidenceProvider evidence,
            MonotonicClock clock) {
        this.applicationId = identifier(applicationId, "applicationId");
        this.viewportId = identifier(viewportId, "viewportId");
        this.capture = Objects.requireNonNull(capture, "capture");
        this.references = Objects.requireNonNull(references, "references");
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Executes one attempt and completes normally with an explicit fail-closed status. */
    public CompletionStage<TypographyDiagnosticResult> execute(
            TypographyDiagnosticRequest request, Deadline requestDeadline) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(requestDeadline, "requestDeadline");
        Duration allowed = request.maxDuration().compareTo(requestDeadline.remaining()) < 0
                ? request.maxDuration()
                : requestDeadline.remaining();
        Deadline deadline = Deadline.after(clock, allowed);
        TypographyReference reference = references.find(request.referenceId()).orElse(null);
        if (reference == null) {
            return completed(
                    TypographyStatus.INCOMPLETE,
                    null,
                    null,
                    List.of(),
                    diagnostic("REFERENCE_NOT_FOUND", "$.referenceId",
                            "registered typography reference", request.referenceId()),
                    deadline);
        }
        ComparisonDiagnostic compatibility = compatibility(reference, request);
        if (compatibility != null) {
            return completed(
                    TypographyStatus.INCOMPLETE,
                    reference,
                    null,
                    List.of(),
                    compatibility,
                    deadline);
        }
        CaptureRequest captureRequest = CaptureRequest.fullWindow()
                .withLimits(request.captureLimits());
        return capture.capture(captureRequest, deadline)
                .thenCompose(current -> evidence.inspect(reference, current, deadline)
                        .thenApply(observations ->
                                evaluate(reference, current, observations, request, deadline)))
                .exceptionally(failure ->
                        incompleteFromFailure(reference, deadline, failure));
    }

    private TypographyDiagnosticResult evaluate(
            TypographyReference reference,
            CapturedImage current,
            List<TypographyObservation> observations,
            TypographyDiagnosticRequest request,
            Deadline deadline) {
        Objects.requireNonNull(observations, "observations");
        List<ComparisonDiagnostic> diagnostics = new ArrayList<>();
        if (observations.size() > request.maxResults()) {
            diagnostics.add(diagnostic(
                    "RESULT_LIMIT_EXCEEDED",
                    "$.maxResults",
                    Integer.toString(request.maxResults()),
                    Integer.toString(observations.size())));
            return result(
                    TypographyStatus.INCOMPLETE,
                    reference,
                    current,
                    List.of(),
                    diagnostics,
                    deadline);
        }
        Map<String, TypographyControlReference> controls = reference.controlsById();
        LinkedHashMap<String, TypographyObservation> byControl = new LinkedHashMap<>();
        boolean stale = false;
        for (TypographyObservation observation : observations) {
            if (byControl.putIfAbsent(observation.controlId(), observation) != null) {
                diagnostics.add(diagnostic(
                        "DUPLICATE_CONTROL_EVIDENCE",
                        "$.observations." + observation.controlId(),
                        "one observation",
                        "multiple observations"));
                continue;
            }
            if (!current.sha256().equals(observation.captureSha256())
                    || current.frame() != observation.frame()
                    || current.revision() != observation.revision()) {
                stale = true;
                diagnostics.add(diagnostic(
                        "CAPTURE_EVIDENCE_STALE",
                        "$.observations." + observation.controlId() + ".capture",
                        current.sha256() + ":" + current.revision() + ":" + current.frame(),
                        observation.captureSha256() + ":"
                                + observation.revision() + ":" + observation.frame()));
            }
            if (observation.display().framebufferWidth() != current.width()
                    || observation.display().framebufferHeight() != current.height()
                    || Double.compare(
                            observation.display().deviceScaleX(), current.scale().x()) != 0
                    || Double.compare(
                            observation.display().deviceScaleY(), current.scale().y()) != 0) {
                stale = true;
                diagnostics.add(diagnostic(
                        "DISPLAY_CAPTURE_MISMATCH",
                        "$.observations." + observation.controlId() + ".display",
                        current.width() + "x" + current.height() + "@" + current.scale(),
                        observation.display().framebufferWidth() + "x"
                                + observation.display().framebufferHeight() + "@"
                                + observation.display().deviceScaleX() + "x"
                                + observation.display().deviceScaleY()));
            }
        }
        for (String controlId : controls.keySet()) {
            if (!byControl.containsKey(controlId)) {
                diagnostics.add(diagnostic(
                        "CONTROL_EVIDENCE_MISSING",
                        "$.observations." + controlId,
                        "one observation",
                        "absent"));
            }
        }
        for (String controlId : byControl.keySet()) {
            if (!controls.containsKey(controlId)) {
                diagnostics.add(diagnostic(
                        "UNEXPECTED_CONTROL_EVIDENCE",
                        "$.observations." + controlId,
                        "registered control",
                        "unregistered control"));
            }
        }
        if (stale) {
            return result(
                    TypographyStatus.STALE,
                    reference,
                    current,
                    List.of(),
                    diagnostics,
                    deadline);
        }
        if (!diagnostics.isEmpty()) {
            return result(
                    TypographyStatus.INCOMPLETE,
                    reference,
                    current,
                    List.of(),
                    diagnostics,
                    deadline);
        }

        String currentArtifactId = "capture:" + current.sha256();
        List<TypographyReport> reports = controls.entrySet().stream()
                .map(entry -> evaluator.evaluate(
                        byControl.get(entry.getKey()),
                        entry.getValue().bind(
                                reference,
                                current.revision(),
                                current.frame(),
                                currentArtifactId)))
                .toList();
        TypographyStatus status = aggregate(reports);
        if (deadline.isExpired()) {
            diagnostics.add(diagnostic(
                    "OPERATION_BOUND_EXCEEDED",
                    "$.maxDuration",
                    "completed before deadline",
                    deadline.timeout().toString()));
            status = TypographyStatus.INCOMPLETE;
        }
        return result(status, reference, current, reports, diagnostics, deadline);
    }

    private ComparisonDiagnostic compatibility(
            TypographyReference reference, TypographyDiagnosticRequest request) {
        if (!applicationId.equals(reference.applicationId())) {
            return diagnostic(
                    "REFERENCE_APPLICATION_MISMATCH",
                    "$.reference.applicationId",
                    applicationId,
                    reference.applicationId());
        }
        if (!viewportId.equals(reference.viewportId())) {
            return diagnostic(
                    "REFERENCE_VIEWPORT_MISMATCH",
                    "$.reference.viewportId",
                    viewportId,
                    reference.viewportId());
        }
        if (!viewportId.equals(request.viewportId())) {
            return diagnostic(
                    "VIEWPORT_MISMATCH",
                    "$.viewportId",
                    viewportId,
                    request.viewportId());
        }
        return null;
    }

    private static TypographyStatus aggregate(List<TypographyReport> reports) {
        TypographyStatus result = TypographyStatus.PIXEL_SHARP;
        for (TypographyReport report : reports) {
            if (priority(report.status()) > priority(result)) {
                result = report.status();
            }
        }
        return result;
    }

    private static int priority(TypographyStatus status) {
        return switch (status) {
            case PIXEL_SHARP -> 0;
            case NOT_PIXEL_SHARP -> 1;
            case NOT_DIAGNOSABLE -> 2;
            case NOT_STABLE -> 3;
            case STALE -> 4;
            case INCOMPLETE -> 5;
        };
    }

    private static TypographyDiagnosticResult incompleteFromFailure(
            TypographyReference reference, Deadline deadline, Throwable thrown) {
        Throwable failure = thrown;
        while (failure instanceof CompletionException && failure.getCause() != null) {
            failure = failure.getCause();
        }
        String code = deadline.isExpired()
                ? "OPERATION_BOUND_EXCEEDED"
                : "CURRENT_TYPOGRAPHY_EVIDENCE_REQUIRED";
        return result(
                TypographyStatus.INCOMPLETE,
                reference,
                null,
                List.of(),
                List.of(diagnostic(
                        code,
                        "$.currentCapture",
                        "capture-backed typography evidence",
                        failure.getClass().getSimpleName())),
                deadline);
    }

    private static CompletionStage<TypographyDiagnosticResult> completed(
            TypographyStatus status,
            TypographyReference reference,
            CapturedImage current,
            List<TypographyReport> reports,
            ComparisonDiagnostic diagnostic,
            Deadline deadline) {
        return CompletableFuture.completedFuture(result(
                status, reference, current, reports, List.of(diagnostic), deadline));
    }

    private static TypographyDiagnosticResult result(
            TypographyStatus status,
            TypographyReference reference,
            CapturedImage current,
            List<TypographyReport> reports,
            List<ComparisonDiagnostic> diagnostics,
            Deadline deadline) {
        return new TypographyDiagnosticResult(
                status, reference, current, reports, diagnostics, deadline.elapsed());
    }

    private static ComparisonDiagnostic diagnostic(
            String code, String path, String expected, String observed) {
        return new ComparisonDiagnostic(code, path, expected, observed);
    }

    private static String identifier(String value, String name) {
        ProtocolJson.requireIdentifier(value, name);
        return value;
    }
}
