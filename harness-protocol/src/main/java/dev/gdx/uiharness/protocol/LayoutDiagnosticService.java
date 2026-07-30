package dev.gdx.uiharness.protocol;

import dev.gdx.uiharness.core.capture.CapturedImage;
import dev.gdx.uiharness.core.capture.ScreenCapture;
import dev.gdx.uiharness.core.layout.LayoutControlReference;
import dev.gdx.uiharness.core.layout.LayoutDiagnosticRequest;
import dev.gdx.uiharness.core.layout.LayoutDiagnosticResult;
import dev.gdx.uiharness.core.layout.LayoutEvidence;
import dev.gdx.uiharness.core.layout.LayoutEvidenceProvider;
import dev.gdx.uiharness.core.layout.LayoutEvaluator;
import dev.gdx.uiharness.core.layout.LayoutObservation;
import dev.gdx.uiharness.core.layout.LayoutQuiescenceEvaluator;
import dev.gdx.uiharness.core.layout.LayoutQuiescencePolicy;
import dev.gdx.uiharness.core.layout.LayoutQuiescenceResult;
import dev.gdx.uiharness.core.layout.LayoutReference;
import dev.gdx.uiharness.core.layout.LayoutReferenceCatalog;
import dev.gdx.uiharness.core.layout.LayoutReport;
import dev.gdx.uiharness.core.layout.LayoutStatus;
import dev.gdx.uiharness.core.time.Deadline;
import dev.gdx.uiharness.core.time.MonotonicClock;
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

/** Coordinates one bounded capture-backed layout, clipping, and viewport diagnosis. */
public final class LayoutDiagnosticService {
    private final String applicationId;
    private final String viewportId;
    private final ScreenCapture capture;
    private final LayoutReferenceCatalog references;
    private final LayoutEvidenceProvider evidence;
    private final MonotonicClock clock;
    private final LayoutEvaluator evaluator = new LayoutEvaluator();
    private final LayoutQuiescenceEvaluator quiescenceEvaluator =
            new LayoutQuiescenceEvaluator();

    /** Creates an explicitly configured layout diagnostic service. */
    public LayoutDiagnosticService(
            String applicationId,
            String viewportId,
            ScreenCapture capture,
            LayoutReferenceCatalog references,
            LayoutEvidenceProvider evidence,
            MonotonicClock clock) {
        this.applicationId = identifier(applicationId, "applicationId");
        this.viewportId = identifier(viewportId, "viewportId");
        this.capture = Objects.requireNonNull(capture, "capture");
        this.references = Objects.requireNonNull(references, "references");
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Executes one attempt and completes normally with an explicit fail-closed status. */
    public CompletionStage<LayoutDiagnosticResult> execute(
            LayoutDiagnosticRequest request, Deadline requestDeadline) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(requestDeadline, "requestDeadline");
        Duration allowed = request.maxDuration().compareTo(requestDeadline.remaining()) < 0
                ? request.maxDuration()
                : requestDeadline.remaining();
        Deadline deadline = Deadline.after(clock, allowed);
        LayoutReference reference = references.find(request.referenceId()).orElse(null);
        if (reference == null) {
            return completed(
                    LayoutStatus.INCOMPLETE, null, null, List.of(), null,
                    diagnostic("REFERENCE_NOT_FOUND", "$.referenceId",
                            "registered layout reference", request.referenceId()),
                    deadline);
        }
        ComparisonDiagnostic compatibility = compatibility(reference, request);
        if (compatibility != null) {
            return completed(
                    LayoutStatus.INCOMPLETE, reference, null, List.of(), null,
                    compatibility, deadline);
        }
        return capture.capture(request.capture(), deadline)
                .thenCompose(current -> evidence.observe(reference, current, deadline)
                        .thenApply(observed -> evaluate(
                                reference, current, observed, request, deadline)))
                .exceptionally(failure -> incompleteFromFailure(reference, deadline, failure));
    }

    private LayoutDiagnosticResult evaluate(
            LayoutReference reference,
            CapturedImage current,
            LayoutEvidence layoutEvidence,
            LayoutDiagnosticRequest request,
            Deadline deadline) {
        Objects.requireNonNull(layoutEvidence, "layoutEvidence");
        List<ComparisonDiagnostic> diagnostics = new ArrayList<>();
        List<LayoutObservation> observations = layoutEvidence.observations();
        LayoutQuiescencePolicy policy = LayoutQuiescencePolicy.issueFour();
        LayoutQuiescenceResult settling = quiescenceEvaluator.evaluate(
                layoutEvidence.settling().samples(),
                layoutEvidence.settling().elapsed(),
                policy);
        LayoutQuiescenceResult captures = quiescenceEvaluator.verifyCaptures(
                layoutEvidence.captures().samples(),
                layoutEvidence.captures().elapsed(),
                policy);
        LayoutEvidence verified = new LayoutEvidence(observations, settling, captures);
        if (!settling.settled() || !captures.settled()) {
            LayoutQuiescenceResult failure = !settling.settled() ? settling : captures;
            diagnostics.add(diagnostic(
                    "LAYOUT_NOT_QUIESCENT",
                    "$.quiescence.status",
                    "settled",
                    failure.status()));
            return result(
                    "not-stable".equals(failure.status())
                            ? LayoutStatus.NOT_STABLE : LayoutStatus.INCOMPLETE,
                    reference, current, List.of(), verified, diagnostics, deadline);
        }
        if (observations.size() > request.maxResults()) {
            diagnostics.add(diagnostic(
                    "RESULT_LIMIT_EXCEEDED", "$.maxResults",
                    Integer.toString(request.maxResults()),
                    Integer.toString(observations.size())));
            return result(LayoutStatus.INCOMPLETE, reference, current, List.of(),
                    verified, diagnostics, deadline);
        }

        Map<String, LayoutControlReference> controls = reference.controlsById();
        LinkedHashMap<String, LayoutObservation> byControl = new LinkedHashMap<>();
        boolean stale = false;
        for (LayoutObservation observation : observations) {
            if (byControl.putIfAbsent(observation.controlId(), observation) != null) {
                diagnostics.add(diagnostic(
                        "DUPLICATE_CONTROL_EVIDENCE",
                        "$.observations." + observation.controlId(),
                        "one observation", "multiple observations"));
                continue;
            }
            if (!current.sha256().equals(observation.captureSha256())
                    || current.revision() != observation.revision()
                    || current.frame() != observation.frame()
                    || !("capture:" + current.sha256()).equals(
                            observation.currentArtifactId())) {
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
                        "CONTROL_EVIDENCE_MISSING", "$.observations." + controlId,
                        "one observation", "absent"));
            }
        }
        for (String controlId : byControl.keySet()) {
            if (!controls.containsKey(controlId)) {
                diagnostics.add(diagnostic(
                        "UNEXPECTED_CONTROL_EVIDENCE", "$.observations." + controlId,
                        "registered control", "unregistered control"));
            }
        }
        if (stale || !diagnostics.isEmpty()) {
            return result(stale ? LayoutStatus.STALE : LayoutStatus.INCOMPLETE,
                    reference, current, List.of(), verified, diagnostics, deadline);
        }

        List<LayoutReport> reports = controls.entrySet().stream()
                .map(entry -> {
                    LayoutObservation observation = byControl.get(entry.getKey());
                    return evaluator.evaluate(
                            observation,
                            entry.getValue().bind(
                                    reference,
                                    current.revision(),
                                    current.frame(),
                                    observation.layoutRevision(),
                                    "capture:" + current.sha256()));
                })
                .toList();
        LayoutStatus status = aggregate(reports);
        if (deadline.isExpired()) {
            diagnostics.add(diagnostic(
                    "OPERATION_BOUND_EXCEEDED", "$.maxDuration",
                    "completed before deadline", deadline.timeout().toString()));
            status = LayoutStatus.INCOMPLETE;
        }
        return result(status, reference, current, reports, verified, diagnostics, deadline);
    }

    private ComparisonDiagnostic compatibility(
            LayoutReference reference, LayoutDiagnosticRequest request) {
        if (!applicationId.equals(reference.applicationId())) {
            return diagnostic("REFERENCE_APPLICATION_MISMATCH",
                    "$.reference.applicationId", applicationId, reference.applicationId());
        }
        if (!viewportId.equals(reference.viewportId())) {
            return diagnostic("REFERENCE_VIEWPORT_MISMATCH",
                    "$.reference.viewportId", viewportId, reference.viewportId());
        }
        if (!viewportId.equals(request.viewportId())) {
            return diagnostic(
                    "VIEWPORT_MISMATCH", "$.viewportId", viewportId, request.viewportId());
        }
        return null;
    }

    private static LayoutStatus aggregate(List<LayoutReport> reports) {
        LayoutStatus status = LayoutStatus.CONFORMANT;
        for (LayoutReport report : reports) {
            if (priority(report.status()) > priority(status)) {
                status = report.status();
            }
        }
        return status;
    }

    private static int priority(LayoutStatus status) {
        return switch (status) {
            case CONFORMANT -> 0;
            case NON_CONFORMANT -> 1;
            case NOT_DIAGNOSABLE -> 2;
            case NOT_STABLE -> 3;
            case STALE -> 4;
            case INCOMPLETE -> 5;
        };
    }

    private static LayoutDiagnosticResult incompleteFromFailure(
            LayoutReference reference, Deadline deadline, Throwable thrown) {
        Throwable failure = thrown;
        while (failure instanceof CompletionException && failure.getCause() != null) {
            failure = failure.getCause();
        }
        return result(
                LayoutStatus.INCOMPLETE, reference, null, List.of(), null,
                List.of(diagnostic(
                        deadline.isExpired()
                                ? "OPERATION_BOUND_EXCEEDED"
                                : "CURRENT_LAYOUT_EVIDENCE_REQUIRED",
                        "$.currentCapture",
                        "capture-backed layout evidence",
                        failure.getClass().getSimpleName())),
                deadline);
    }

    private static CompletionStage<LayoutDiagnosticResult> completed(
            LayoutStatus status,
            LayoutReference reference,
            CapturedImage current,
            List<LayoutReport> reports,
            LayoutEvidence evidence,
            ComparisonDiagnostic diagnostic,
            Deadline deadline) {
        return CompletableFuture.completedFuture(result(
                status, reference, current, reports, evidence, List.of(diagnostic), deadline));
    }

    private static LayoutDiagnosticResult result(
            LayoutStatus status,
            LayoutReference reference,
            CapturedImage current,
            List<LayoutReport> reports,
            LayoutEvidence evidence,
            List<ComparisonDiagnostic> diagnostics,
            Deadline deadline) {
        return new LayoutDiagnosticResult(
                status,
                reference,
                current,
                reports,
                evidence == null ? null : evidence.settling(),
                evidence == null ? null : evidence.captures(),
                diagnostics,
                deadline.elapsed());
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
