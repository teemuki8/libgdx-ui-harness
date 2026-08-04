package dev.gdx.uiharness.protocol;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import dev.gdx.uiharness.core.assertion.AssertionRequest;
import dev.gdx.uiharness.core.assertion.UiAssertion;
import dev.gdx.uiharness.core.capture.CaptureRequest;
import dev.gdx.uiharness.core.locator.Locator;
import dev.gdx.uiharness.core.model.Bounds;
import dev.gdx.uiharness.core.time.Deadline;
import dev.gdx.uiharness.core.locator.LocatorFilter;
import dev.gdx.uiharness.core.locator.TextMatch;
import java.util.Collections;
import java.util.Map;
import java.util.List;
import java.util.TreeMap;
import java.util.Locale;
import java.util.Objects;

/** Explicit allowlisted V1 command union. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = Command.Sessions.class, name = "sessions"),
    @JsonSubTypes.Type(value = Command.Capabilities.class, name = "capabilities"),
    @JsonSubTypes.Type(value = Command.Snapshot.class, name = "snapshot"),
    @JsonSubTypes.Type(value = Command.Query.class, name = "query"),
    @JsonSubTypes.Type(value = Command.Action.class, name = "action"),
    @JsonSubTypes.Type(value = Command.Assert.class, name = "assert"),
    @JsonSubTypes.Type(value = Command.Wait.class, name = "wait"),
    @JsonSubTypes.Type(value = Command.Screenshot.class, name = "screenshot"),
    @JsonSubTypes.Type(value = Command.InspectCompare.class, name = "inspect-compare"),
    @JsonSubTypes.Type(value = Command.TypographyDiagnose.class, name = "typography-diagnose"),
    @JsonSubTypes.Type(value = Command.LayoutDiagnose.class, name = "layout-diagnose"),
    @JsonSubTypes.Type(value = Command.TraceStart.class, name = "trace-start"),
    @JsonSubTypes.Type(value = Command.TraceStop.class, name = "trace-stop"),
    @JsonSubTypes.Type(value = Command.ScenarioList.class, name = "scenario-list"),
    @JsonSubTypes.Type(value = Command.ScenarioStart.class, name = "scenario-start")
})
public sealed interface Command permits Command.Sessions, Command.Capabilities, Command.Snapshot,
        Command.Query, Command.Action, Command.Assert, Command.Wait, Command.Screenshot,
        Command.TraceStart, Command.InspectCompare, Command.TypographyDiagnose,
        Command.LayoutDiagnose, Command.TraceStop, Command.ScenarioList, Command.ScenarioStart {
    /** Lists active sessions. */
    record Sessions() implements Command {}

    /** Reads capabilities for the selected session. */
    record Capabilities() implements Command {}

    /** Lists bounded application-registered scenario definitions. */
    record ScenarioList() implements Command {}

    /** Starts one registered scenario with canonical bounded inputs. */
    record ScenarioStart(
            String scenarioId,
            long seed,
            Map<String, String> configuration,
            String profileId) implements Command {
        /** Validates identifiers and canonical configuration bounds. */
        public ScenarioStart {
            ProtocolJson.requireIdentifier(scenarioId, "scenarioId");
            ProtocolJson.requireIdentifier(profileId, "profileId");
            Objects.requireNonNull(configuration, "configuration");
            if (configuration.size() > 256) {
                throw new IllegalArgumentException("configuration exceeds 256 entries");
            }
            TreeMap<String, String> canonical = new TreeMap<>();
            configuration.forEach((key, value) -> canonical.put(
                    ProtocolJson.requireIdentifier(key, "configuration key"),
                    ProtocolJson.requireText(value, "configuration value")));
            configuration = Collections.unmodifiableMap(canonical);
        }
    }

    /** Captures a fresh semantic snapshot. */
    record Snapshot() implements Command {}

    /** Evaluates one locator against a fresh semantic snapshot. */
    record Query(LocatorSpec locator) implements Command {
        /** Validates the locator. */
        public Query {
            Objects.requireNonNull(locator, "locator");
        }
    }

    /** Performs one input action after fresh strict locator resolution. */
    record Action(LocatorSpec locator, ActionSpec action) implements Command {
        /** Validates locator and action. */
        public Action {
            Objects.requireNonNull(locator, "locator");
            Objects.requireNonNull(action, "action");
        }
    }
    /** Evaluates one closed, versioned declarative assertion until its deadline. */
    record Assert(int schemaVersion, LocatorSpec locator, AssertionSpec assertion)
            implements Command {
        /** Validates the assertion contract version and operands. */
        public Assert {
            if (schemaVersion != AssertionRequest.SCHEMA_VERSION) {
                throw new IllegalArgumentException(
                        "unsupported assertion schema version: " + schemaVersion);
            }
            Objects.requireNonNull(locator, "locator");
            Objects.requireNonNull(assertion, "assertion");
        }

        AssertionRequest toCore(Deadline deadline) {
            return new AssertionRequest(schemaVersion, locator.toCore(),
                    assertion.toCore(), Objects.requireNonNull(deadline, "deadline"));
        }
    }


    /** Waits for one semantic condition. */
    record Wait(LocatorSpec locator, WaitCondition condition) implements Command {
        /** Validates locator and condition. */
        public Wait {
            Objects.requireNonNull(locator, "locator");
            Objects.requireNonNull(condition, "condition");
        }
    }

    /** Captures a bounded PNG of the full window or an actor selected by {@code locator}. */
    record Screenshot(
            LocatorSpec locator,
            int maxWidth,
            int maxHeight,
            long maxPixels,
            int maxPngBytes) implements Command {
        /** Validates allocation and output limits. */
        public Screenshot {
            if (maxWidth <= 0 || maxWidth > 8_192) {
                throw new IllegalArgumentException("maxWidth must be between 1 and 8192");
            }
            if (maxHeight <= 0 || maxHeight > 8_192) {
                throw new IllegalArgumentException("maxHeight must be between 1 and 8192");
            }
            if (maxPixels <= 0 || maxPixels > 33_554_432L) {
                throw new IllegalArgumentException("maxPixels exceeds protocol limit");
            }
            if (maxPngBytes <= 0
                    || maxPngBytes > HarnessResponse.Result.Screenshot.MAX_PNG_BYTES) {
                throw new IllegalArgumentException(
                        "maxPngBytes exceeds protocol response limit");
            }
        }

        CaptureRequest toCore() {
            CaptureRequest request = locator == null
                    ? CaptureRequest.fullWindow() : CaptureRequest.actor(locator.toCore());
            return request.withLimits(
                    new CaptureRequest.Limits(maxWidth, maxHeight, maxPixels, maxPngBytes));
        }
    }

    /** Runs one bounded full-frame inspect-capture-reference comparison. */
    record InspectCompare(
            String referenceId,
            String policyId,
            int policyVersion,
            String viewportId,
            int maxIterations,
            long maxDurationMillis,
            int maxWidth,
            int maxHeight,
            long maxPixels,
            int maxPngBytes) implements Command {
        /** Validates discoverable identities and all operation/capture bounds. */
        public InspectCompare {
            ProtocolJson.requireIdentifier(referenceId, "referenceId");
            ProtocolJson.requireIdentifier(policyId, "policyId");
            ProtocolJson.requireIdentifier(viewportId, "viewportId");
            if (policyId.length() > 240
                    || policyVersion <= 0 || maxIterations <= 0 || maxIterations > 64
                    || maxDurationMillis <= 0 || maxDurationMillis > 120_000) {
                throw new IllegalArgumentException(
                        "comparison policy, iteration, or duration bound is invalid");
            }
            new CaptureRequest.Limits(
                    maxWidth, maxHeight, maxPixels, maxPngBytes);
            if (maxWidth > 8_192 || maxHeight > 8_192
                    || maxPixels > 33_554_432L
                    || maxPngBytes > HarnessResponse.Result.Screenshot.MAX_PNG_BYTES) {
                throw new IllegalArgumentException(
                        "comparison capture bound exceeds protocol limit");
            }
        }

        dev.gdx.uiharness.core.visual.InspectCaptureCompareRequest toCore() {
            return new dev.gdx.uiharness.core.visual.InspectCaptureCompareRequest(
                    referenceId, policyId, policyVersion, viewportId, maxIterations,
                    java.time.Duration.ofMillis(maxDurationMillis),
                    new CaptureRequest.Limits(
                            maxWidth, maxHeight, maxPixels, maxPngBytes));
        }
    }

    /** Runs one bounded capture-backed typography diagnosis. */
    record TypographyDiagnose(
            String referenceId,
            String viewportId,
            long maxDurationMillis,
            int maxResults,
            int maxWidth,
            int maxHeight,
            long maxPixels,
            int maxPngBytes) implements Command {
        /** Validates reference identity and all duration/result/capture bounds. */
        public TypographyDiagnose {
            ProtocolJson.requireIdentifier(referenceId, "referenceId");
            ProtocolJson.requireIdentifier(viewportId, "viewportId");
            if (maxDurationMillis <= 0 || maxDurationMillis > 120_000) {
                throw new IllegalArgumentException(
                        "maxDurationMillis must be between 1 and 120000");
            }
            if (maxResults <= 0 || maxResults > 256) {
                throw new IllegalArgumentException("maxResults must be between 1 and 256");
            }
            new CaptureRequest.Limits(maxWidth, maxHeight, maxPixels, maxPngBytes);
            if (maxWidth > 8_192 || maxHeight > 8_192
                    || maxPixels > 33_554_432L
                    || maxPngBytes > HarnessResponse.Result.Screenshot.MAX_PNG_BYTES) {
                throw new IllegalArgumentException(
                        "typography capture bound exceeds protocol limit");
            }
        }

        dev.gdx.uiharness.core.typography.TypographyDiagnosticRequest toCore() {
            return new dev.gdx.uiharness.core.typography.TypographyDiagnosticRequest(
                    referenceId,
                    viewportId,
                    java.time.Duration.ofMillis(maxDurationMillis),
                    maxResults,
                    new CaptureRequest.Limits(
                            maxWidth, maxHeight, maxPixels, maxPngBytes));
        }
    }

    /** Runs one bounded capture-backed layout, clipping, and viewport diagnosis. */
    record LayoutDiagnose(
            String referenceId,
            String viewportId,
            long maxDurationMillis,
            int maxResults,
            int maxWidth,
            int maxHeight,
            long maxPixels,
            int maxPngBytes) implements Command {
        /** Validates reference identity and fixed issue-four operation bounds. */
        public LayoutDiagnose {
            ProtocolJson.requireIdentifier(referenceId, "referenceId");
            ProtocolJson.requireIdentifier(viewportId, "viewportId");
            if (maxDurationMillis <= 0 || maxDurationMillis > 2_000) {
                throw new IllegalArgumentException(
                        "maxDurationMillis must be between 1 and 2000");
            }
            if (maxResults <= 0 || maxResults > 256) {
                throw new IllegalArgumentException("maxResults must be between 1 and 256");
            }
            new CaptureRequest.Limits(maxWidth, maxHeight, maxPixels, maxPngBytes);
            if (maxWidth > 8_192 || maxHeight > 8_192
                    || maxPixels > 33_554_432L
                    || maxPngBytes > HarnessResponse.Result.Screenshot.MAX_PNG_BYTES) {
                throw new IllegalArgumentException(
                        "layout capture bound exceeds protocol limit");
            }
        }

        dev.gdx.uiharness.core.layout.LayoutDiagnosticRequest toCore() {
            return new dev.gdx.uiharness.core.layout.LayoutDiagnosticRequest(
                    referenceId,
                    viewportId,
                    java.time.Duration.ofMillis(maxDurationMillis),
                    maxResults,
                    CaptureRequest.fullWindow().withLimits(
                            new CaptureRequest.Limits(
                                    maxWidth, maxHeight, maxPixels, maxPngBytes)));
        }
    }

    /** Starts bounded trace collection for the selected session. */
    record TraceStart(long maxDurationMillis, long maxBytes) implements Command {
        /** Validates trace duration and storage bounds. */
        public TraceStart {
            if (maxDurationMillis <= 0 || maxDurationMillis > 3_600_000) {
                throw new IllegalArgumentException("maxDurationMillis exceeds protocol limit");
            }
            if (maxBytes <= 0 || maxBytes > 64L * 1_024 * 1_024) {
                throw new IllegalArgumentException("maxBytes exceeds protocol limit");
            }
        }
    }

    /** Stops active trace collection for the selected session. */
    record TraceStop() implements Command {}

    /** Stable wait predicates exposed by V1. */
    public enum WaitCondition {
        /** Exactly one matching node exists. */
        PRESENT("present"),
        /** Exactly one matching node is effectively visible. */
        VISIBLE("visible");

        private final String wireName;

        WaitCondition(String wireName) {
            this.wireName = wireName;
        }

        /** Parses a stable wire name. */
        @com.fasterxml.jackson.annotation.JsonCreator
        public static WaitCondition fromWireName(String name) {
            for (WaitCondition value : values()) {
                if (value.wireName.equals(name)) {
                    return value;
                }
            }
            throw new IllegalArgumentException("unknown wait condition: " + name);
        }

        /** Returns the stable wire name. */
        @com.fasterxml.jackson.annotation.JsonValue
        public String wireName() {
            return wireName;
        }

        dev.gdx.uiharness.core.wait.WaitCondition toCore() {
            return this == PRESENT
                    ? dev.gdx.uiharness.core.wait.WaitCondition.present()
                    : dev.gdx.uiharness.core.wait.WaitCondition.visible();
        }
    }

    /** Closed transport union for the thirteen declarative assertion variants. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = AssertionSpec.Visible.class, name = "visible"),
        @JsonSubTypes.Type(value = AssertionSpec.Hidden.class, name = "hidden"),
        @JsonSubTypes.Type(value = AssertionSpec.Enabled.class, name = "enabled"),
        @JsonSubTypes.Type(value = AssertionSpec.Disabled.class, name = "disabled"),
        @JsonSubTypes.Type(value = AssertionSpec.Focused.class, name = "focused"),
        @JsonSubTypes.Type(value = AssertionSpec.Checked.class, name = "checked"),
        @JsonSubTypes.Type(value = AssertionSpec.TextEquals.class, name = "text-equals"),
        @JsonSubTypes.Type(value = AssertionSpec.TextContains.class, name = "text-contains"),
        @JsonSubTypes.Type(value = AssertionSpec.CountEquals.class, name = "count-equals"),
        @JsonSubTypes.Type(
                value = AssertionSpec.BoundsInsideViewport.class,
                name = "bounds-inside-viewport"),
        @JsonSubTypes.Type(
                value = AssertionSpec.DoesNotOverlap.class,
                name = "does-not-overlap"),
        @JsonSubTypes.Type(
                value = AssertionSpec.StableForFrames.class,
                name = "stable-for-frames"),
        @JsonSubTypes.Type(
                value = AssertionSpec.AccessibleNameExists.class,
                name = "accessible-name-exists")
    })
    sealed interface AssertionSpec permits AssertionSpec.Visible, AssertionSpec.Hidden,
            AssertionSpec.Enabled, AssertionSpec.Disabled, AssertionSpec.Focused,
            AssertionSpec.Checked, AssertionSpec.TextEquals, AssertionSpec.TextContains,
            AssertionSpec.CountEquals, AssertionSpec.BoundsInsideViewport,
            AssertionSpec.DoesNotOverlap, AssertionSpec.StableForFrames,
            AssertionSpec.AccessibleNameExists {
        UiAssertion toCore();

        record Visible() implements AssertionSpec {
            @Override public UiAssertion toCore() { return new UiAssertion.Visible(); }
        }

        record Hidden() implements AssertionSpec {
            @Override public UiAssertion toCore() { return new UiAssertion.Hidden(); }
        }

        record Enabled() implements AssertionSpec {
            @Override public UiAssertion toCore() { return new UiAssertion.Enabled(); }
        }

        record Disabled() implements AssertionSpec {
            @Override public UiAssertion toCore() { return new UiAssertion.Disabled(); }
        }

        record Focused() implements AssertionSpec {
            @Override public UiAssertion toCore() { return new UiAssertion.Focused(); }
        }

        record Checked() implements AssertionSpec {
            @Override public UiAssertion toCore() { return new UiAssertion.Checked(); }
        }

        record TextEquals(String expected) implements AssertionSpec {
            public TextEquals { requireAssertionText(expected); }
            @Override public UiAssertion toCore() { return new UiAssertion.TextEquals(expected); }
        }

        record TextContains(String expected) implements AssertionSpec {
            public TextContains { requireAssertionText(expected); }
            @Override public UiAssertion toCore() { return new UiAssertion.TextContains(expected); }
        }

        record CountEquals(int expected) implements AssertionSpec {
            public CountEquals {
                if (expected < 0) throw new IllegalArgumentException("expected must be non-negative");
            }
            @Override public UiAssertion toCore() { return new UiAssertion.CountEquals(expected); }
        }

        private static void requireAssertionText(String expected) {
            Objects.requireNonNull(expected, "expected");
            if (expected.length() > ProtocolJson.MAX_STRING_LENGTH) {
                throw new IllegalArgumentException("expected exceeds protocol string limit");
            }
        }

        record BoundsInsideViewport(Bounds viewport) implements AssertionSpec {
            public BoundsInsideViewport { Objects.requireNonNull(viewport, "viewport"); }
            @Override public UiAssertion toCore() {
                return new UiAssertion.BoundsInsideViewport(viewport);
            }
        }

        record DoesNotOverlap(LocatorSpec other) implements AssertionSpec {
            public DoesNotOverlap { Objects.requireNonNull(other, "other"); }
            @Override public UiAssertion toCore() {
                return new UiAssertion.DoesNotOverlap(other.toCore());
            }
        }

        record StableForFrames(int frames, java.util.List<String> properties)
                implements AssertionSpec {
            public StableForFrames {
                properties = List.copyOf(Objects.requireNonNull(properties, "properties"));
                if (properties.isEmpty()) {
                    throw new IllegalArgumentException("properties must not be empty");
                }
                java.util.HashSet<String> unique = new java.util.HashSet<>();
                for (String property : properties) {
                    parseStableProperty(property);
                    if (!unique.add(property)) {
                        throw new IllegalArgumentException("properties must be unique");
                    }
                }
                new UiAssertion.StableForFrames(frames, properties.stream()
                        .map(AssertionSpec::parseStableProperty)
                        .collect(java.util.stream.Collectors.toSet()));
            }

            @Override public UiAssertion toCore() {
                return new UiAssertion.StableForFrames(frames, properties.stream()
                        .map(AssertionSpec::parseStableProperty)
                        .collect(java.util.stream.Collectors.toSet()));
            }
        }

        record AccessibleNameExists() implements AssertionSpec {
            @Override public UiAssertion toCore() {
                return new UiAssertion.AccessibleNameExists();
            }
        }

        private static UiAssertion.StableProperty parseStableProperty(String value) {
            Objects.requireNonNull(value, "stable property");
            return switch (value) {
                case "bounds" -> UiAssertion.StableProperty.BOUNDS;
                case "text" -> UiAssertion.StableProperty.TEXT;
                case "accessible-name" -> UiAssertion.StableProperty.ACCESSIBLE_NAME;
                case "visible" -> UiAssertion.StableProperty.VISIBLE;
                case "enabled" -> UiAssertion.StableProperty.ENABLED;
                case "checked" -> UiAssertion.StableProperty.CHECKED;
                case "focused" -> UiAssertion.StableProperty.FOCUSED;
                default -> throw new IllegalArgumentException(
                        "unknown stable property: " + value);
            };
        }
    }

    /** Explicit recursively composable locator union. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = LocatorSpec.Role.class, name = "role"),
        @JsonSubTypes.Type(value = LocatorSpec.Text.class, name = "text"),
        @JsonSubTypes.Type(value = LocatorSpec.TestId.class, name = "test-id"),
        @JsonSubTypes.Type(value = LocatorSpec.Actor.class, name = "actor"),
        @JsonSubTypes.Type(value = LocatorSpec.Relation.class, name = "relation"),
        @JsonSubTypes.Type(value = LocatorSpec.Filter.class, name = "filter"),
        @JsonSubTypes.Type(value = LocatorSpec.Index.class, name = "index")
    })
    sealed interface LocatorSpec permits LocatorSpec.Role, LocatorSpec.Text, LocatorSpec.TestId,
            LocatorSpec.Actor, LocatorSpec.Relation, LocatorSpec.Filter, LocatorSpec.Index {
        Locator toCore();

        /** Locator by semantic role. */
        record Role(String role) implements LocatorSpec {
            /** Validates a role wire name. */
            public Role {
                parseRole(role);
            }

            @Override public Locator toCore() {
                return Locator.role(parseRole(role));
            }
        }

        /** Locator by text or label. */
        record Text(String field, TextMatchSpec match) implements LocatorSpec {
            /** Validates field and matcher. */
            public Text {
                requireOneOf(field, "field", "text", "label");
                Objects.requireNonNull(match, "match");
            }

            @Override public Locator toCore() {
                return "text".equals(field) ? Locator.text(match.toCore())
                        : Locator.label(match.toCore());
            }
        }

        /** Locator by explicit test identifier. */
        record TestId(String testId) implements LocatorSpec {
            /** Validates the test identifier. */
            public TestId {
                ProtocolJson.requireText(testId, "testId");
            }

            @Override public Locator toCore() {
                return Locator.testId(testId);
            }
        }

        /** Locator by diagnostic backend actor name or type. */
        record Actor(String field, TextMatchSpec match) implements LocatorSpec {
            /** Validates field and matcher. */
            public Actor {
                requireOneOf(field, "field", "name", "type");
                Objects.requireNonNull(match, "match");
            }

            @Override public Locator toCore() {
                return "name".equals(field) ? Locator.actorName(match.toCore())
                        : Locator.actorType(match.toCore());
            }
        }

        /** Structural relationship between two locators. */
        record Relation(LocatorSpec anchor, LocatorSpec target, String relation)
                implements LocatorSpec {
            /** Validates both locators and relationship. */
            public Relation {
                Objects.requireNonNull(anchor, "anchor");
                Objects.requireNonNull(target, "target");
                requireOneOf(relation, "relation", "child", "descendant", "parent", "sibling");
            }

            @Override public Locator toCore() {
                return switch (relation) {
                    case "child" -> anchor.toCore().child(target.toCore());
                    case "descendant" -> anchor.toCore().descendant(target.toCore());
                    case "parent" -> anchor.toCore().parent(target.toCore());
                    case "sibling" -> anchor.toCore().sibling(target.toCore());
                    default -> throw new AssertionError(relation);
                };
            }
        }

        /** Locator restricted by an explicit predicate. */
        record Filter(LocatorSpec locator, FilterSpec filter) implements LocatorSpec {
            /** Validates locator and filter. */
            public Filter {
                Objects.requireNonNull(locator, "locator");
                Objects.requireNonNull(filter, "filter");
            }

            @Override public Locator toCore() {
                return locator.toCore().filter(filter.toCore());
            }
        }

        /** Zero-based positional locator selection. */
        record Index(LocatorSpec locator, int index) implements LocatorSpec {
            /** Validates locator and index. */
            public Index {
                Objects.requireNonNull(locator, "locator");
                if (index < 0) {
                    throw new IllegalArgumentException("index must be non-negative");
                }
            }

            @Override public Locator toCore() {
                return locator.toCore().atIndex(index);
            }
        }
    }

    /**
     * Stable text comparison DTO.
     *
     * @param mode exact, case-insensitive-exact, substring, or regex
     * @param source bounded source text or regular expression
     */
    record TextMatchSpec(String mode, String source) {
        /** Validates comparison mode, bounded source text, and regular-expression syntax. */
        public TextMatchSpec {
            requireOneOf(mode, "mode", "exact", "case-insensitive-exact", "substring", "regex");
            ProtocolJson.requireText(source, "source");
            if ("regex".equals(mode)) {
                TextMatch.regex(source);
            }
        }

        TextMatch toCore() {
            return switch (mode) {
                case "exact" -> TextMatch.exact(source);
                case "case-insensitive-exact" -> TextMatch.caseInsensitiveExact(source);
                case "substring" -> TextMatch.substring(source);
                case "regex" -> TextMatch.regex(source);
                default -> throw new AssertionError(mode);
            };
        }
    }

    /** Explicit locator-filter union. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = FilterSpec.Name.class, name = "name"),
        @JsonSubTypes.Type(value = FilterSpec.Has.class, name = "has"),
        @JsonSubTypes.Type(value = FilterSpec.HasText.class, name = "has-text"),
        @JsonSubTypes.Type(value = FilterSpec.State.class, name = "state")
    })
    sealed interface FilterSpec permits FilterSpec.Name, FilterSpec.Has, FilterSpec.HasText,
            FilterSpec.State {
        LocatorFilter toCore();

        /** Accessible-name predicate. */
        record Name(TextMatchSpec match) implements FilterSpec {
            /** Validates matcher. */
            public Name {
                Objects.requireNonNull(match, "match");
            }

            @Override public LocatorFilter toCore() {
                return LocatorFilter.name(match.toCore());
            }
        }

        /** Matching-descendant predicate. */
        record Has(LocatorSpec locator) implements FilterSpec {
            /** Validates descendant locator. */
            public Has {
                Objects.requireNonNull(locator, "locator");
            }

            @Override public LocatorFilter toCore() {
                return LocatorFilter.has(locator.toCore());
            }
        }

        /** Matching-subtree-text predicate. */
        record HasText(TextMatchSpec match) implements FilterSpec {
            /** Validates matcher. */
            public HasText {
                Objects.requireNonNull(match, "match");
            }

            @Override public LocatorFilter toCore() {
                return LocatorFilter.hasText(match.toCore());
            }
        }

        /** Exact semantic-state predicate. */
        record State(String state, boolean expected) implements FilterSpec {
            /** Validates stable state name. */
            public State {
                parseState(state);
            }

            @Override public LocatorFilter toCore() {
                return LocatorFilter.state(parseState(state), expected);
            }
        }
    }

    /** Explicit allowlisted action union. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = ActionSpec.Click.class, name = "click"),
        @JsonSubTypes.Type(value = ActionSpec.Hover.class, name = "hover"),
        @JsonSubTypes.Type(value = ActionSpec.Focus.class, name = "focus"),
        @JsonSubTypes.Type(value = ActionSpec.Fill.class, name = "fill"),
        @JsonSubTypes.Type(value = ActionSpec.Press.class, name = "press"),
        @JsonSubTypes.Type(value = ActionSpec.Scroll.class, name = "scroll"),
        @JsonSubTypes.Type(value = ActionSpec.Drag.class, name = "drag"),
        @JsonSubTypes.Type(value = ActionSpec.Pointer.class, name = "pointer")
    })
    sealed interface ActionSpec permits ActionSpec.Click, ActionSpec.Hover, ActionSpec.Focus,
            ActionSpec.Fill, ActionSpec.Press, ActionSpec.Scroll, ActionSpec.Drag,
            ActionSpec.Pointer {
        dev.gdx.uiharness.core.action.Action toCore();

        /** Primary-button click. */
        record Click(int pointer, int button, boolean force) implements ActionSpec {
            /** Validates pointer. */
            public Click {
                requirePointer(pointer);
            }

            @Override public dev.gdx.uiharness.core.action.Action toCore() {
                return new dev.gdx.uiharness.core.action.Action.Click(pointer, button, force);
            }
        }

        /** Pointer hover. */
        record Hover(boolean force) implements ActionSpec {
            @Override public dev.gdx.uiharness.core.action.Action toCore() {
                return dev.gdx.uiharness.core.action.Action.hover(force);
            }
        }

        /** Keyboard focus assignment. */
        record Focus(boolean force) implements ActionSpec {
            @Override public dev.gdx.uiharness.core.action.Action toCore() {
                return dev.gdx.uiharness.core.action.Action.focus(force);
            }
        }

        /** Complete text replacement. */
        record Fill(String value, boolean force) implements ActionSpec {
            /** Validates bounded input text. */
            public Fill {
                Objects.requireNonNull(value, "value");
                if (value.length() > ProtocolJson.MAX_STRING_LENGTH) {
                    throw new IllegalArgumentException("value exceeds protocol string limit");
                }
            }

            @Override public dev.gdx.uiharness.core.action.Action toCore() {
                return dev.gdx.uiharness.core.action.Action.fill(value, force);
            }
        }

        /** Key-down/key-up pair. */
        record Press(int keycode, boolean force) implements ActionSpec {
            /** Validates key code. */
            public Press {
                if (keycode < 0) {
                    throw new IllegalArgumentException("keycode must be non-negative");
                }
            }

            @Override public dev.gdx.uiharness.core.action.Action toCore() {
                return dev.gdx.uiharness.core.action.Action.press(keycode, force);
            }
        }

        /** Two-axis scroll input. */
        record Scroll(float amountX, float amountY, boolean force) implements ActionSpec {
            /** Validates finite deltas. */
            public Scroll {
                requireFinite(amountX, "amountX");
                requireFinite(amountY, "amountY");
            }

            @Override public dev.gdx.uiharness.core.action.Action toCore() {
                return dev.gdx.uiharness.core.action.Action.scroll(amountX, amountY, force);
            }
        }

        /** Pointer drag by a screen-space delta. */
        record Drag(float deltaX, float deltaY, int pointer, int button, boolean force)
                implements ActionSpec {
            /** Validates deltas and pointer. */
            public Drag {
                requireFinite(deltaX, "deltaX");
                requireFinite(deltaY, "deltaY");
                requirePointer(pointer);
            }

            @Override public dev.gdx.uiharness.core.action.Action toCore() {
                return dev.gdx.uiharness.core.action.Action.drag(
                        deltaX, deltaY, pointer, button, force);
            }
        }

        /** One explicit pointer transition. */
        record Pointer(String phase, float offsetX, float offsetY, int pointer, int button,
                boolean force) implements ActionSpec {
            /** Validates phase, offsets, and pointer. */
            public Pointer {
                requireOneOf(phase, "phase", "down", "move", "up");
                requireFinite(offsetX, "offsetX");
                requireFinite(offsetY, "offsetY");
                requirePointer(pointer);
            }

            @Override public dev.gdx.uiharness.core.action.Action toCore() {
                return dev.gdx.uiharness.core.action.Action.pointer(
                        dev.gdx.uiharness.core.action.Action.PointerPhase.valueOf(
                                phase.toUpperCase(Locale.ROOT)),
                        offsetX, offsetY, pointer, button, force);
            }
        }
    }

    private static dev.gdx.uiharness.core.model.Role parseRole(String value) {
        ProtocolJson.requireIdentifier(value, "role");
        try {
            return dev.gdx.uiharness.core.model.Role.valueOf(
                    value.toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("unknown role: " + value, failure);
        }
    }

    private static LocatorFilter.State parseState(String value) {
        ProtocolJson.requireIdentifier(value, "state");
        try {
            return LocatorFilter.State.valueOf(value.toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("unknown state: " + value, failure);
        }
    }

    private static void requireOneOf(String value, String name, String... allowed) {
        Objects.requireNonNull(value, name);
        for (String candidate : allowed) {
            if (candidate.equals(value)) {
                return;
            }
        }
        throw new IllegalArgumentException("unknown " + name + ": " + value);
    }

    private static void requirePointer(int pointer) {
        if (pointer < 0) {
            throw new IllegalArgumentException("pointer must be non-negative");
        }
    }

    private static void requireFinite(float value, String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
