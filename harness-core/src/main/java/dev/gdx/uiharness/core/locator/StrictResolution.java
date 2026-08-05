package dev.gdx.uiharness.core.locator;

import dev.gdx.uiharness.core.error.ErrorCode;
import dev.gdx.uiharness.core.error.ErrorEvidence;
import dev.gdx.uiharness.core.error.HarnessException;
import dev.gdx.uiharness.core.error.RedactionField;
import dev.gdx.uiharness.core.error.RedactionPolicies;
import dev.gdx.uiharness.core.error.RedactionPolicy;
import dev.gdx.uiharness.core.limits.HarnessLimits;
import dev.gdx.uiharness.core.model.SemanticNode;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import dev.gdx.uiharness.core.model.SemanticState;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/** Default JDK-only locator evaluator and strict resolver. */
public final class StrictResolution implements LocatorEngine {
    private static final int MAX_DIAGNOSTIC_CANDIDATES = 10;
    private static final int MAX_EVIDENCE_STRING_LENGTH = 16_384;
    private static final LocatorSuggestionEngine SUGGESTION_ENGINE =
            new LocatorSuggestionEngine();

    private final HarnessLimits limits;
    private final RedactionPolicy redaction;

    /** Creates an evaluator using {@link HarnessLimits#defaults()}. */
    public StrictResolution() {
        this(HarnessLimits.defaults(), RedactionPolicies.none());
    }

    /** Creates an evaluator using the supplied hard limits. */
    public StrictResolution(HarnessLimits limits) {
        this(limits, RedactionPolicies.none());
    }

    /**
     * Creates an evaluator using the supplied hard limits and redaction policy.
     *
     * @param limits hard evaluation bounds
     * @param redaction policy applied to evidence before it is ranked or published
     */
    public StrictResolution(HarnessLimits limits, RedactionPolicy redaction) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.redaction = Objects.requireNonNull(redaction, "redaction");
    }

    @Override
    public QueryResult query(SemanticSnapshot snapshot, Locator locator) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(locator, "locator");
        validateLocatorStrings(locator);
        var context = new EvaluationContext(snapshot, limits);
        var matches = new ArrayList<SemanticNode>();
        for (SemanticNode node : context.documentOrder()) {
            if (context.matches(locator, node)) {
                matches.add(node);
                if (matches.size() > limits.maxMatches()) {
                    limits.validateMatchCount(matches.size());
                }
            }
        }
        return new QueryResult(matches, fragileIndexEvidence(locator));
    }

    @Override
    public SemanticNode resolveStrict(SemanticSnapshot snapshot, Locator locator) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(locator, "locator");
        validateLocatorStrings(locator);
        var context = new EvaluationContext(snapshot, limits);
        var matches = new ArrayList<SemanticNode>(2);
        for (SemanticNode node : context.documentOrder()) {
            if (context.matches(locator, node)) {
                matches.add(node);
                if (matches.size() == 2) {
                    break;
                }
            }
        }

        if (matches.size() == 1) {
            return matches.getFirst();
        }
        if (matches.isEmpty()) {
            throw strictFailure(
                    ErrorCode.NOT_FOUND,
                    "No semantic node matches the locator",
                    snapshot,
                    locator,
                    diagnosticAlternatives(context));
        }
        throw strictFailure(
                ErrorCode.STRICTNESS_VIOLATION,
                "Strict locator matched more than one semantic node",
                snapshot,
                locator,
                matches);
    }

    private void validateLocatorStrings(Locator locator) {
        var pending = new ArrayDeque<Locator>();
        pending.push(locator);
        while (!pending.isEmpty()) {
            Locator current = pending.pop();
            switch (current) {
                case RoleLocator ignored -> {
                    // No caller-supplied string.
                }
                case TextLocator text ->
                        limits.validateString(text.text().source(), "locator text");
                case TestIdLocator testId ->
                        limits.validateString(testId.testId(), "locator testId");
                case ActorLocator actor ->
                        limits.validateString(actor.text().source(), "locator actor text");
                case RelationLocator relation -> {
                    pending.push(relation.target());
                    pending.push(relation.anchor());
                }
                case FilteredLocator filtered -> {
                    pending.push(filtered.locator());
                    pushFilterLocator(filtered.filter(), pending);
                    validateFilterText(filtered.filter());
                }
                case IndexedLocator indexed -> pending.push(indexed.locator());
            }
        }
    }

    private void validateFilterText(LocatorFilter filter) {
        switch (filter) {
            case NameFilter name ->
                    limits.validateString(name.name().source(), "locator name");
            case HasTextFilter hasText ->
                    limits.validateString(hasText.text().source(), "locator hasText");
            case HasFilter ignored -> {
                // Nested locator is handled by pushFilterLocator.
            }
            case StateFilter ignored -> {
                // No caller-supplied string.
            }
        }
    }

    private static void pushFilterLocator(LocatorFilter filter, ArrayDeque<Locator> pending) {
        if (filter instanceof HasFilter has) {
            pending.push(has.descendant());
        }
    }

    private HarnessException strictFailure(
            ErrorCode code,
            String message,
            SemanticSnapshot snapshot,
            Locator locator,
            List<SemanticNode> candidateNodes) {
        List<Map<String, String>> candidates = candidateNodes.stream()
                .map(node -> candidateSummary(snapshot, node))
                .toList();
        var suggestionSet = SUGGESTION_ENGINE.suggest(snapshot, candidateNodes, this, redaction);
        var details = new LinkedHashMap<String, String>();
        details.put("suggestions", suggestions(candidates));
        details.put("matchCount", code == ErrorCode.NOT_FOUND ? "0" : "at least 2");
        details.put("redactionPolicyId", redaction.id());
        if (suggestionSet.truncated()) {
            details.put("suggestionsTruncated", "true");
        }
        findIndex(locator).ifPresent(index -> {
            details.put("fragileIndex", "true");
            details.put("index", Integer.toString(index));
        });
        var evidence = new ErrorEvidence(
                Optional.empty(),
                Optional.empty(),
                Optional.of(bounded(locator.toString())),
                Duration.ZERO,
                OptionalLong.of(snapshot.revision()),
                Optional.empty(),
                candidates,
                details,
                suggestionSet.suggestions());
        return new HarnessException(code, message, evidence);
    }

    private List<SemanticNode> diagnosticAlternatives(EvaluationContext context) {
        int bound = Math.min(limits.maxMatches(), MAX_DIAGNOSTIC_CANDIDATES);
        var candidates = new ArrayList<SemanticNode>(bound);
        for (SemanticNode node : context.documentOrder()) {
            if (!node.id().equals(context.snapshot().rootId()) && node.childIds().isEmpty()) {
                candidates.add(node);
                if (candidates.size() == bound) {
                    return candidates;
                }
            }
        }
        if (candidates.isEmpty()) {
            candidates.add(context.snapshot().nodes().get(context.snapshot().rootId()));
        }
        return candidates;
    }

    private Map<String, String> candidateSummary(
            SemanticSnapshot snapshot, SemanticNode node) {
        var summary = new LinkedHashMap<String, String>();
        summary.put("id", bounded(node.id()));
        summary.put("role", node.role().name());
        putPresent(summary, "accessibleName",
                redact(RedactionField.ACCESSIBLE_NAME, node.accessibleName()));
        putPresent(summary, "text", redact(RedactionField.TEXT, node.text()));
        putPresent(summary, "label", redact(RedactionField.LABEL, node.label()));
        putPresent(summary, "testId", redact(RedactionField.TEST_ID, node.testId()));
        putPresent(summary, "actorName", redact(RedactionField.ACTOR_NAME, node.actorName()));
        putPresent(summary, "actorType", redact(RedactionField.ACTOR_TYPE, node.actorType()));
        String ancestor = ancestorSummary(snapshot, node);
        if (!ancestor.isEmpty()) {
            summary.put("ancestor", ancestor);
        }
        return summary;
    }

    private String redact(RedactionField field, String value) {
        return value == null ? null : redaction.redact(field, value);
    }

    private static void putPresent(Map<String, String> target, String key, String value) {
        if (value != null && !value.isEmpty()) {
            target.put(key, bounded(value));
        }
    }

    private String ancestorSummary(SemanticSnapshot snapshot, SemanticNode node) {
        var ancestors = new ArrayDeque<String>();
        String parentId = node.parentId();
        while (parentId != null) {
            SemanticNode parent = snapshot.nodes().get(parentId);
            String discriminator = firstPresent(
                    redact(RedactionField.ACCESSIBLE_NAME, parent.accessibleName()),
                    redact(RedactionField.TEST_ID, parent.testId()),
                    redact(RedactionField.ACTOR_NAME, parent.actorName()),
                    parent.id());
            ancestors.addFirst(parent.role().name() + "[" + discriminator + "]");
            parentId = parent.parentId();
        }
        return bounded(String.join(" > ", ancestors));
    }

    private static String firstPresent(String... values) {
        for (String value : values) {
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        throw new IllegalStateException("candidate discriminator is absent");
    }

    private static String suggestions(List<Map<String, String>> candidates) {
        var discriminators = new ArrayList<String>(4);
        addIfDiscriminating(discriminators, candidates, "role", "role");
        addIfDiscriminating(discriminators, candidates, "accessibleName", "accessibleName");
        addIfDiscriminating(discriminators, candidates, "testId", "testId");
        addIfDiscriminating(discriminators, candidates, "ancestor", "ancestor");
        if (discriminators.isEmpty()) {
            return "role, accessibleName, testId, ancestor";
        }
        return String.join(", ", discriminators);
    }

    private static void addIfDiscriminating(
            List<String> output,
            List<Map<String, String>> candidates,
            String field,
            String label) {
        Set<String> values = new HashSet<>();
        for (Map<String, String> candidate : candidates) {
            String value = candidate.get(field);
            if (value != null) {
                values.add(value);
            }
        }
        if (values.size() > 1) {
            output.add(label);
        }
    }

    private static List<Map<String, String>> fragileIndexEvidence(Locator locator) {
        return findIndex(locator)
                .<List<Map<String, String>>>map(index -> List.of(Map.of(
                        "kind", "fragile-index",
                        "index", Integer.toString(index),
                        "reason", "index depends on document structure")))
                .orElseGet(List::of);
    }

    private static Optional<Integer> findIndex(Locator locator) {
        var pending = new ArrayDeque<Locator>();
        pending.push(locator);
        while (!pending.isEmpty()) {
            Locator current = pending.pop();
            switch (current) {
                case IndexedLocator indexed -> {
                    return Optional.of(indexed.index());
                }
                case RelationLocator relation -> {
                    pending.push(relation.target());
                    pending.push(relation.anchor());
                }
                case FilteredLocator filtered -> {
                    pending.push(filtered.locator());
                    pushFilterLocator(filtered.filter(), pending);
                }
                case RoleLocator ignored -> {
                    // Leaf locator.
                }
                case TextLocator ignored -> {
                    // Leaf locator.
                }
                case TestIdLocator ignored -> {
                    // Leaf locator.
                }
                case ActorLocator ignored -> {
                    // Leaf locator.
                }
            }
        }
        return Optional.empty();
    }

    private static String bounded(String value) {
        if (value.length() <= MAX_EVIDENCE_STRING_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_EVIDENCE_STRING_LENGTH);
    }

    private record TraversalEntry(SemanticNode node, int depth) {}

    private static final class EvaluationContext {
        private final SemanticSnapshot snapshot;
        private final List<SemanticNode> documentOrder;
        private final Map<IndexedLocator, Optional<String>> indexedSelections =
                new IdentityHashMap<>();

        EvaluationContext(SemanticSnapshot snapshot, HarnessLimits limits) {
            this.snapshot = snapshot;
            limits.validateNodeCount(snapshot.nodes().size());
            var ordered = new ArrayList<SemanticNode>(snapshot.nodes().size());
            var pending = new ArrayDeque<TraversalEntry>();
            pending.push(new TraversalEntry(snapshot.nodes().get(snapshot.rootId()), 0));
            while (!pending.isEmpty()) {
                TraversalEntry entry = pending.pop();
                if (entry.depth() > limits.maxDepth()) {
                    limits.validateDepth(entry.depth());
                }
                ordered.add(entry.node());
                List<String> children = entry.node().childIds();
                for (int index = children.size() - 1; index >= 0; index--) {
                    pending.push(new TraversalEntry(
                            snapshot.nodes().get(children.get(index)), entry.depth() + 1));
                }
            }
            documentOrder = ordered;
        }

        SemanticSnapshot snapshot() {
            return snapshot;
        }

        List<SemanticNode> documentOrder() {
            return documentOrder;
        }

        boolean matches(Locator locator, SemanticNode node) {
            return switch (locator) {
                case RoleLocator role -> node.role() == role.role();
                case TextLocator text -> text.text().matches(switch (text.field()) {
                    case TEXT -> node.text();
                    case LABEL -> node.label();
                });
                case TestIdLocator testId -> testId.testId().equals(node.testId());
                case ActorLocator actor -> actor.text().matches(switch (actor.field()) {
                    case NAME -> node.actorName();
                    case TYPE -> node.actorType();
                });
                case RelationLocator relation -> matchesRelation(relation, node);
                case FilteredLocator filtered ->
                        matches(filtered.locator(), node) && matchesFilter(filtered.filter(), node);
                case IndexedLocator indexed -> matchesIndex(indexed, node);
            };
        }

        private boolean matchesRelation(RelationLocator locator, SemanticNode node) {
            if (!matches(locator.target(), node)) {
                return false;
            }
            return switch (locator.relation()) {
                case CHILD -> node.parentId() != null
                        && matches(locator.anchor(), snapshot.nodes().get(node.parentId()));
                case DESCENDANT -> hasMatchingAncestor(node, locator.anchor());
                case PARENT -> hasMatchingDirectChild(node, locator.anchor());
                case SIBLING -> hasMatchingSibling(node, locator.anchor());
            };
        }

        private boolean hasMatchingAncestor(SemanticNode node, Locator locator) {
            String parentId = node.parentId();
            while (parentId != null) {
                SemanticNode parent = snapshot.nodes().get(parentId);
                if (matches(locator, parent)) {
                    return true;
                }
                parentId = parent.parentId();
            }
            return false;
        }

        private boolean hasMatchingDirectChild(SemanticNode node, Locator locator) {
            for (String childId : node.childIds()) {
                if (matches(locator, snapshot.nodes().get(childId))) {
                    return true;
                }
            }
            return false;
        }

        private boolean hasMatchingSibling(SemanticNode node, Locator locator) {
            if (node.parentId() == null) {
                return false;
            }
            SemanticNode parent = snapshot.nodes().get(node.parentId());
            for (String siblingId : parent.childIds()) {
                if (!siblingId.equals(node.id())
                        && matches(locator, snapshot.nodes().get(siblingId))) {
                    return true;
                }
            }
            return false;
        }

        private boolean matchesFilter(LocatorFilter filter, SemanticNode node) {
            return switch (filter) {
                case NameFilter name -> name.name().matches(node.accessibleName());
                case HasFilter has -> hasMatchingDescendant(node, has.descendant());
                case HasTextFilter hasText -> subtreeHasText(node, hasText.text());
                case StateFilter state -> matchesState(node.state(), state);
            };
        }

        private boolean hasMatchingDescendant(SemanticNode node, Locator locator) {
            var pending = new ArrayDeque<String>();
            addChildrenInReverse(node, pending);
            while (!pending.isEmpty()) {
                SemanticNode descendant = snapshot.nodes().get(pending.pop());
                if (matches(locator, descendant)) {
                    return true;
                }
                addChildrenInReverse(descendant, pending);
            }
            return false;
        }

        private boolean subtreeHasText(SemanticNode node, TextMatch text) {
            var pending = new ArrayDeque<String>();
            pending.push(node.id());
            while (!pending.isEmpty()) {
                SemanticNode descendant = snapshot.nodes().get(pending.pop());
                if (text.matches(descendant.text())) {
                    return true;
                }
                addChildrenInReverse(descendant, pending);
            }
            return false;
        }

        private static void addChildrenInReverse(
                SemanticNode node, ArrayDeque<String> pending) {
            List<String> children = node.childIds();
            for (int index = children.size() - 1; index >= 0; index--) {
                pending.push(children.get(index));
            }
        }

        private static boolean matchesState(SemanticState state, StateFilter filter) {
            Optional<Boolean> actual = switch (filter.state()) {
                case VISIBLE -> Optional.of(state.visible());
                case TOUCHABLE -> Optional.of(state.touchable());
                case ENABLED -> state.enabled();
                case CHECKED -> state.checked();
                case SELECTED -> state.selected();
                case EXPANDED -> state.expanded();
                case EDITABLE -> state.editable();
                case FOCUSED -> Optional.of(state.focused());
                case FOCUSABLE -> Optional.of(state.focusable());
                case CLIPPED -> Optional.of(state.clipped());
                case VIEWPORT_INTERSECTING -> Optional.of(state.viewportIntersecting());
                case HIT_TARGET -> Optional.of(state.hitTarget());
            };
            return actual.isPresent() && actual.get() == filter.expected();
        }

        private boolean matchesIndex(IndexedLocator locator, SemanticNode node) {
            Optional<String> selected = indexedSelections.computeIfAbsent(
                    locator, this::selectIndexedNode);
            return selected.isPresent() && selected.orElseThrow().equals(node.id());
        }

        private Optional<String> selectIndexedNode(IndexedLocator locator) {
            int currentIndex = 0;
            for (SemanticNode candidate : documentOrder) {
                if (matches(locator.locator(), candidate)) {
                    if (currentIndex == locator.index()) {
                        return Optional.of(candidate.id());
                    }
                    currentIndex++;
                }
            }
            return Optional.empty();
        }
    }
}
