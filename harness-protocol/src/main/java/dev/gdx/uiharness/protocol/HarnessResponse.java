package dev.gdx.uiharness.protocol;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import dev.gdx.uiharness.core.action.ActionResult;
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
import dev.gdx.uiharness.core.model.Bounds;
import dev.gdx.uiharness.core.model.SemanticNode;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import dev.gdx.uiharness.core.model.SemanticState;
import dev.gdx.uiharness.core.wait.WaitResult;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
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
}
