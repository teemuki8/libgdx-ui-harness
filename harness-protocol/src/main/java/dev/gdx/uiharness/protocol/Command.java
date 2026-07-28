package dev.gdx.uiharness.protocol;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import dev.gdx.uiharness.core.capture.CaptureRequest;
import dev.gdx.uiharness.core.locator.Locator;
import dev.gdx.uiharness.core.locator.LocatorFilter;
import dev.gdx.uiharness.core.locator.TextMatch;
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
    @JsonSubTypes.Type(value = Command.Wait.class, name = "wait"),
    @JsonSubTypes.Type(value = Command.Screenshot.class, name = "screenshot"),
    @JsonSubTypes.Type(value = Command.TraceStart.class, name = "trace-start"),
    @JsonSubTypes.Type(value = Command.TraceStop.class, name = "trace-stop")
})
public sealed interface Command permits Command.Sessions, Command.Capabilities, Command.Snapshot,
        Command.Query, Command.Action, Command.Wait, Command.Screenshot, Command.TraceStart,
        Command.TraceStop {
    /** Lists active sessions. */
    record Sessions() implements Command {}

    /** Reads capabilities for the selected session. */
    record Capabilities() implements Command {}

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

    /** Stable text comparison DTO. */
    record TextMatchSpec(String mode, String source) {
        /** Validates comparison mode and bounded source text. */
        public TextMatchSpec {
            requireOneOf(mode, "mode", "exact", "case-insensitive-exact", "substring", "regex");
            ProtocolJson.requireText(source, "source");
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
