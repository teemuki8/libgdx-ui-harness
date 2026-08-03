package dev.gdx.uiharness.protocol;

import dev.gdx.uiharness.core.action.Harness;
import dev.gdx.uiharness.core.capture.CaptureRequest;
import dev.gdx.uiharness.core.capture.CapturedImage;
import dev.gdx.uiharness.core.capture.ScreenCapture;
import dev.gdx.uiharness.core.contract.StateActionContract;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import dev.gdx.uiharness.core.time.Deadline;
import dev.gdx.uiharness.core.time.MonotonicClock;
import dev.gdx.uiharness.core.visual.ComparisonDiagnostic;
import dev.gdx.uiharness.core.visual.ComparisonStatus;
import dev.gdx.uiharness.core.visual.CurrentVisualEvidence;
import dev.gdx.uiharness.core.visual.InspectCaptureCompareRequest;
import dev.gdx.uiharness.core.visual.VisualComparator;
import dev.gdx.uiharness.core.visual.VisualComparisonResult;
import dev.gdx.uiharness.core.visual.VisualPolicy;
import dev.gdx.uiharness.core.visual.VisualReference;
import dev.gdx.uiharness.core.visual.VisualReferenceCatalog;
import java.time.Duration;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/** Coordinates one provenance-bound full-frame inspect-capture-compare operation. */
public final class InspectCaptureCompareService {
    private final String sessionId;
    private final String applicationId;
    private final String viewportId;
    private final Harness harness;
    private final ScreenCapture capture;
    private final HarnessProtocolService.ContractProvider contracts;
    private final VisualReferenceCatalog references;
    private final Map<String, VisualPolicy> policies;
    private final VisualComparator comparator;
    private final MonotonicClock clock;
    private final InstantSource instantSource;

    /** Creates an explicit comparison-enabled session service. */
    public InspectCaptureCompareService(
            String sessionId,
            String applicationId,
            String viewportId,
            Harness harness,
            ScreenCapture capture,
            HarnessProtocolService.ContractProvider contracts,
            VisualReferenceCatalog references,
            List<VisualPolicy> policies,
            VisualComparator comparator,
            MonotonicClock clock,
            InstantSource instantSource) {
        this.sessionId = identifier(sessionId, "sessionId");
        this.applicationId = identifier(applicationId, "applicationId");
        this.viewportId = identifier(viewportId, "viewportId");
        this.harness = Objects.requireNonNull(harness, "harness");
        this.capture = Objects.requireNonNull(capture, "capture");
        this.contracts = contracts;
        this.references = Objects.requireNonNull(references, "references");
        Objects.requireNonNull(policies, "policies");
        java.util.LinkedHashMap<String, VisualPolicy> indexed = new java.util.LinkedHashMap<>();
        for (VisualPolicy policy : policies) {
            String key = Objects.requireNonNull(policy, "policy").wireName();
            if (indexed.putIfAbsent(key, policy) != null) {
                throw new IllegalArgumentException("duplicate visual policy: " + key);
            }
        }
        this.policies = Map.copyOf(indexed);
        this.comparator = Objects.requireNonNull(comparator, "comparator");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.instantSource = Objects.requireNonNull(instantSource, "instantSource");
    }

    /** Executes one full-frame attempt and completes normally with an explicit status. */
    public CompletionStage<VisualComparisonResult> execute(
            InspectCaptureCompareRequest request, Deadline requestDeadline) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(requestDeadline, "requestDeadline");
        Duration allowed = request.maxDuration().compareTo(requestDeadline.remaining()) < 0
                ? request.maxDuration() : requestDeadline.remaining();
        Deadline deadline = Deadline.after(clock, allowed);
        VisualPolicy policy = policies.get(request.policyId() + "/v" + request.policyVersion());
        if (policy == null) {
            return completedIncomplete(
                    null, request, deadline, "POLICY_NOT_FOUND", "$.policyId",
                    "registered policy identity",
                    request.policyId() + "/v" + request.policyVersion());
        }
        VisualReference reference = references.find(request.referenceId()).orElse(null);
        if (reference == null) {
            return completedIncomplete(
                    policy, request, deadline, "REFERENCE_NOT_FOUND", "$.referenceId",
                    "registered immutable reference", request.referenceId());
        }
        if (!applicationId.equals(reference.applicationId())) {
            return completedIncomplete(
                    policy, request, deadline, "REFERENCE_APPLICATION_MISMATCH",
                    "$.reference.applicationId",
                    applicationId, reference.applicationId());
        }
        if (!viewportId.equals(reference.viewportId())) {
            return completedIncomplete(
                    policy, request, deadline, "REFERENCE_VIEWPORT_MISMATCH",
                    "$.reference.viewportId",
                    viewportId, reference.viewportId());
        }
        if (!viewportId.equals(request.viewportId())) {
            return completedIncomplete(
                    policy, request, deadline, "VIEWPORT_MISMATCH", "$.viewportId",
                    viewportId, request.viewportId());
        }

        CaptureRequest captureRequest = CaptureRequest.fullWindow()
                .withLimits(request.captureLimits());
        CompletionStage<VisualComparisonResult> operation = capture.capture(captureRequest, deadline)
                .thenCompose(image -> inspect(reference, policy, image, deadline))
                .exceptionally(failure -> incompleteFromFailure(
                        policy, reference, deadline, failure));
        return operation;
    }

    private CompletionStage<VisualComparisonResult> inspect(
            VisualReference reference,
            VisualPolicy policy,
            CapturedImage image,
            Deadline deadline) {
        CompletionStage<SemanticSnapshot> semantics = harness.snapshot(deadline);
        CompletionStage<StateActionContract> stateAction = contracts == null
                ? CompletableFuture.completedFuture(null) : contracts.snapshot(deadline);
        return semantics.thenCombine(stateAction, (snapshot, contract) -> {
            CurrentVisualEvidence current;
            try {
                current = new CurrentVisualEvidence(
                        sessionId, applicationId, viewportId, image,
                        instantSource.instant(), snapshot, contract);
            } catch (IllegalArgumentException stale) {
                return new VisualComparisonResult(
                        ComparisonStatus.STALE, policy, reference, null, null,
                        List.of(), List.of(new ComparisonDiagnostic(
                                "CAPTURE_REVISION_STALE", "$.currentCapture.revision",
                                snapshot.revision() + ":" + snapshot.frame(),
                                image.revision() + ":" + image.frame())),
                        1, deadline.elapsed());
            }
            if (!VisualComparisonResult.compatible(reference, current, policy)) {
                return new VisualComparisonResult(
                        ComparisonStatus.INCOMPLETE, policy, reference, current, null,
                        List.of(), List.of(new ComparisonDiagnostic(
                                "REFERENCE_VIEWPORT_INCOMPATIBLE", "$.reference.viewport",
                                reference.width() + "x" + reference.height()
                                        + "@" + reference.scale(),
                                image.width() + "x" + image.height()
                                        + "@" + image.scale())),
                        1, deadline.elapsed());
            }
            VisualComparator.Comparison comparison =
                    comparator.compare(reference, current, policy);
            boolean passingMetrics =
                    comparison.metrics().differingPixels() <= policy.maxDifferingPixels()
                    && comparison.metrics().meanAbsoluteError()
                    <= policy.maxMeanAbsoluteError();
            boolean blocking =
                    comparison.differences().stream().anyMatch(difference -> difference.blocking());
            ComparisonStatus status = passingMetrics && !blocking
                    ? ComparisonStatus.CONVERGED : ComparisonStatus.NOT_CONVERGED;
            if (deadline.isExpired()) {
                return new VisualComparisonResult(
                        ComparisonStatus.INCOMPLETE, policy, reference, current,
                        comparison.metrics(), comparison.differences(),
                        comparison.regions(), comparison.heatmap(),
                        List.of(new ComparisonDiagnostic(
                                "OPERATION_BOUND_EXCEEDED", "$.maxDurationMillis",
                                "completed before deadline", deadline.timeout().toString())),
                        1, deadline.elapsed());
            }
            return new VisualComparisonResult(
                    status, policy, reference, current, comparison.metrics(),
                    comparison.differences(), comparison.regions(), comparison.heatmap(),
                    List.of(), 1, deadline.elapsed());
        });
    }

    private static CompletionStage<VisualComparisonResult> completedIncomplete(
            VisualPolicy policy,
            InspectCaptureCompareRequest request,
            Deadline deadline,
            String code,
            String path,
            String expected,
            String observed) {
        VisualPolicy safePolicy = policy == null
                ? new VisualPolicy(
                        request.policyId(), request.policyVersion(),
                        0, 0, true, true)
                : policy;
        return CompletableFuture.completedFuture(VisualComparisonResult.incomplete(
                safePolicy, null,
                List.of(new ComparisonDiagnostic(code, path, expected, observed)),
                deadline.elapsed()));
    }

    private static VisualComparisonResult incompleteFromFailure(
            VisualPolicy policy,
            VisualReference reference,
            Deadline deadline,
            Throwable thrown) {
        Throwable failure = thrown;
        while (failure instanceof CompletionException && failure.getCause() != null) {
            failure = failure.getCause();
        }
        String code = deadline.isExpired()
                ? "OPERATION_BOUND_EXCEEDED" : "CURRENT_CAPTURE_REQUIRED";
        return VisualComparisonResult.incomplete(
                policy, reference,
                List.of(new ComparisonDiagnostic(
                        code, "$.currentCapture", "accepted full-frame capture",
                        failure.getClass().getSimpleName())),
                deadline.elapsed());
    }

    private static String identifier(String value, String name) {
        ProtocolJson.requireIdentifier(value, name);
        return value;
    }
}
