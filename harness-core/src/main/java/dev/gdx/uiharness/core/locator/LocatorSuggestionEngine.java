package dev.gdx.uiharness.core.locator;

import dev.gdx.uiharness.core.error.HarnessException;
import dev.gdx.uiharness.core.error.RedactionField;
import dev.gdx.uiharness.core.error.RedactionPolicy;
import dev.gdx.uiharness.core.model.SemanticNode;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Derives bounded, deterministic, schema-valid locator suggestions from the candidate evidence
 * retained by a strict lookup failure. Suggestions are diagnostic only: this engine never
 * dispatches input and never retries the failed operation. Every suggested locator is verified
 * against the same immutable snapshot and is emitted only when it uniquely selects exactly the
 * intended candidate.
 */
public final class LocatorSuggestionEngine {
    /** Maximum suggestions retained for one strict failure. */
    public static final int MAX_SUGGESTIONS = 8;
    private static final int MAX_DISTINCTIONS = 4;
    private static final int MAX_ANCESTOR_LENGTH = 1_024;
    private static final int MAX_DEPTH = 128;

    private static final List<String> DISTINCTION_FIELDS =
            List.of("testId", "accessibleName", "label", "text", "actorName", "actorType",
                    "ancestor");

    /** Bounded suggestion output for one failure. */
    public record SuggestionSet(List<LocatorSuggestion> suggestions, boolean truncated) {
        /** Defensively copies the suggestion list. */
        public SuggestionSet {
            suggestions = List.copyOf(suggestions);
        }
    }

    /**
     * Suggests locators for the supplied failure candidates.
     *
     * @param snapshot the immutable observation the failed lookup used
     * @param candidates bounded candidate nodes retained by the failure
     * @param evaluator engine used to verify that each suggestion resolves uniquely
     * @param redaction policy applied to every value before ranking and output
     * @return bounded suggestions in deterministic candidate order, plus a truncation flag
     */
    public SuggestionSet suggest(
            SemanticSnapshot snapshot,
            List<SemanticNode> candidates,
            LocatorEngine evaluator,
            RedactionPolicy redaction) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(evaluator, "evaluator");
        Objects.requireNonNull(redaction, "redaction");
        var suggestions = new ArrayList<LocatorSuggestion>(MAX_SUGGESTIONS);
        for (SemanticNode candidate : candidates) {
            if (suggestions.size() == MAX_SUGGESTIONS) {
                break;
            }
            suggestForCandidate(snapshot, candidates, candidate, evaluator, redaction)
                    .ifPresent(suggestions::add);
        }
        return new SuggestionSet(suggestions,
                suggestions.size() == MAX_SUGGESTIONS && candidates.size() > suggestions.size());
    }

    private static Optional<LocatorSuggestion> suggestForCandidate(
            SemanticSnapshot snapshot,
            List<SemanticNode> candidates,
            SemanticNode candidate,
            LocatorEngine evaluator,
            RedactionPolicy redaction) {
        for (Variant variant : variants(snapshot, candidates, candidate, redaction)) {
            if (resolvesUniquely(evaluator, snapshot, variant.locator(), candidate)) {
                return Optional.of(new LocatorSuggestion(
                        variant.locator(),
                        variant.stability(),
                        variant.rationale(),
                        candidate.id(),
                        distinctions(snapshot, candidates, candidate, redaction)));
            }
        }
        return Optional.empty();
    }

    private static boolean resolvesUniquely(
            LocatorEngine evaluator, SemanticSnapshot snapshot, Locator locator,
            SemanticNode candidate) {
        try {
            QueryResult result = evaluator.query(snapshot, locator);
            return result.matches().size() == 1
                    && result.matches().getFirst().id().equals(candidate.id());
        } catch (HarnessException verificationFailure) {
            // A candidate locator that itself exceeds evaluation limits is not a usable
            // suggestion; fall through to the next variant rather than masking the strict
            // failure with a different error.
            return false;
        }
    }

    private static List<Variant> variants(
            SemanticSnapshot snapshot,
            List<SemanticNode> candidates,
            SemanticNode candidate,
            RedactionPolicy redaction) {
        var variants = new ArrayList<Variant>(7);
        String testId = redact(redaction, RedactionField.TEST_ID, candidate.testId());
        if (nonBlank(testId)) {
            variants.add(new Variant(
                    Locator.testId(testId),
                    Stability.STABLE,
                    "unique test identifier"));
        }
        String name = redact(redaction, RedactionField.ACCESSIBLE_NAME,
                candidate.accessibleName());
        if (nonBlank(name)) {
            variants.add(new Variant(
                    Locator.role(candidate.role()).withName(TextMatch.exact(name)),
                    Stability.STABLE,
                    "role and accessible name"));
        }
        String label = redact(redaction, RedactionField.LABEL, candidate.label());
        if (nonBlank(label)) {
            variants.add(new Variant(
                    Locator.label(TextMatch.exact(label)),
                    Stability.STABLE,
                    "associated label"));
        }
        String text = redact(redaction, RedactionField.TEXT, candidate.text());
        if (nonBlank(text)) {
            variants.add(new Variant(
                    Locator.role(candidate.role())
                            .filter(LocatorFilter.hasText(TextMatch.exact(text))),
                    Stability.STABLE,
                    "role and visible text"));
        }
        String actorName = redact(redaction, RedactionField.ACTOR_NAME, candidate.actorName());
        if (nonBlank(actorName)) {
            variants.add(new Variant(
                    Locator.actorName(TextMatch.exact(actorName)),
                    Stability.FRAGILE,
                    "backend actor name"));
        }
        String actorType = redact(redaction, RedactionField.ACTOR_TYPE, candidate.actorType());
        if (nonBlank(actorType)) {
            variants.add(new Variant(
                    Locator.actorType(TextMatch.exact(actorType)),
                    Stability.FRAGILE,
                    "backend actor type"));
        }
        variants.add(new Variant(
                Locator.role(candidate.role()).atIndex(roleIndex(snapshot, candidate)),
                Stability.FRAGILE,
                "positional index"));
        return List.copyOf(variants);
    }

    private static String redact(RedactionPolicy redaction, RedactionField field, String value) {
        return value == null ? null : redaction.redact(field, value);
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isEmpty();
    }

    private static List<DistinguishingProperty> distinctions(
            SemanticSnapshot snapshot,
            List<SemanticNode> candidates,
            SemanticNode candidate,
            RedactionPolicy redaction) {
        var result = new ArrayList<DistinguishingProperty>(MAX_DISTINCTIONS);
        for (String field : DISTINCTION_FIELDS) {
            if (result.size() == MAX_DISTINCTIONS) {
                break;
            }
            String value = distinctionValue(snapshot, candidate, field, redaction);
            if (value == null) {
                continue;
            }
            boolean unique = true;
            for (SemanticNode other : candidates) {
                if (other != candidate
                        && Objects.equals(
                                value, distinctionValue(snapshot, other, field, redaction))) {
                    unique = false;
                    break;
                }
            }
            if (unique) {
                result.add(new DistinguishingProperty(field, value));
            }
        }
        return List.copyOf(result);
    }

    private static String distinctionValue(
            SemanticSnapshot snapshot,
            SemanticNode node,
            String field,
            RedactionPolicy redaction) {
        return switch (field) {
            case "testId" -> redact(redaction, RedactionField.TEST_ID, node.testId());
            case "accessibleName" ->
                    redact(redaction, RedactionField.ACCESSIBLE_NAME, node.accessibleName());
            case "label" -> redact(redaction, RedactionField.LABEL, node.label());
            case "text" -> redact(redaction, RedactionField.TEXT, node.text());
            case "actorName" -> redact(redaction, RedactionField.ACTOR_NAME, node.actorName());
            case "actorType" -> redact(redaction, RedactionField.ACTOR_TYPE, node.actorType());
            case "ancestor" -> ancestor(snapshot, node, redaction);
            default -> throw new AssertionError(field);
        };
    }

    private static String ancestor(
            SemanticSnapshot snapshot, SemanticNode node, RedactionPolicy redaction) {
        var ancestors = new ArrayDeque<String>();
        String parentId = node.parentId();
        int depth = 0;
        while (parentId != null && depth < MAX_DEPTH) {
            SemanticNode parent = snapshot.nodes().get(parentId);
            String discriminator = firstPresent(
                    redact(redaction, RedactionField.ACCESSIBLE_NAME, parent.accessibleName()),
                    redact(redaction, RedactionField.TEST_ID, parent.testId()),
                    redact(redaction, RedactionField.ACTOR_NAME, parent.actorName()),
                    parent.id());
            ancestors.addFirst(parent.role().name() + "[" + discriminator + "]");
            parentId = parent.parentId();
            depth++;
        }
        String joined = String.join(" > ", ancestors);
        if (joined.length() > MAX_ANCESTOR_LENGTH) {
            return joined.substring(0, MAX_ANCESTOR_LENGTH);
        }
        return joined;
    }

    private static String firstPresent(String... values) {
        for (String value : values) {
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        throw new IllegalStateException("candidate discriminator is absent");
    }

    /**
     * Returns the zero-based document-order index of the candidate among nodes with the same
     * role, mirroring the traversal used by {@link StrictResolution}.
     */
    private static int roleIndex(SemanticSnapshot snapshot, SemanticNode candidate) {
        var pending = new ArrayDeque<String>();
        pending.push(snapshot.rootId());
        int index = 0;
        while (!pending.isEmpty()) {
            SemanticNode node = snapshot.nodes().get(pending.pop());
            if (node.role() == candidate.role()) {
                if (node.id().equals(candidate.id())) {
                    return index;
                }
                index++;
            }
            List<String> children = node.childIds();
            for (int childIndex = children.size() - 1; childIndex >= 0; childIndex--) {
                pending.push(children.get(childIndex));
            }
        }
        throw new IllegalStateException("candidate is absent from the snapshot");
    }

    private record Variant(Locator locator, Stability stability, String rationale) {}
}
