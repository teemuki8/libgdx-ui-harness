package dev.gdx.uiharness.protocol;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import dev.gdx.uiharness.core.action.ActionResult;
import dev.gdx.uiharness.core.capture.CapturedImage;
import dev.gdx.uiharness.core.error.ErrorCode;
import dev.gdx.uiharness.core.error.ErrorEvidence;
import dev.gdx.uiharness.core.error.HarnessException;
import dev.gdx.uiharness.core.locator.QueryResult;
import dev.gdx.uiharness.core.model.Bounds;
import dev.gdx.uiharness.core.model.SemanticNode;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import dev.gdx.uiharness.core.model.SemanticState;
import dev.gdx.uiharness.core.wait.WaitResult;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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
        @JsonSubTypes.Type(value = Result.Wait.class, name = "wait"),
        @JsonSubTypes.Type(value = Result.Screenshot.class, name = "screenshot"),
        @JsonSubTypes.Type(value = Result.TraceStarted.class, name = "trace-started"),
        @JsonSubTypes.Type(value = Result.TraceStopped.class, name = "trace-stopped")
    })
    sealed interface Result permits Result.Sessions, Result.Capabilities, Result.Snapshot,
            Result.Query, Result.Action, Result.Wait, Result.Screenshot, Result.TraceStarted,
            Result.TraceStopped {
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

    /** One session and its canonical capability names. */
    record SessionInfo(String sessionId, List<String> capabilities) {
        /** Validates session identity and canonicalizes capabilities. */
        public SessionInfo {
            ProtocolJson.requireIdentifier(sessionId, "sessionId");
            capabilities = new CapabilitySet(capabilities).capabilities();
        }
    }

    /** Explicit transport representation of a semantic snapshot. */
    record SnapshotData(long revision, long frame, String rootId, List<NodeData> nodes) {
        /** Validates and copies snapshot data. */
        public SnapshotData {
            if (revision < 0 || frame < 0) {
                throw new IllegalArgumentException("snapshot counters must be non-negative");
            }
            ProtocolJson.requireIdentifier(rootId, "rootId");
            nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
        }

        static SnapshotData fromCore(SemanticSnapshot snapshot) {
            List<NodeData> nodes = new ArrayList<>(snapshot.nodes().size());
            appendDepthFirst(snapshot, snapshot.rootId(), nodes);
            return new SnapshotData(snapshot.revision(), snapshot.frame(), snapshot.rootId(), nodes);
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
}
