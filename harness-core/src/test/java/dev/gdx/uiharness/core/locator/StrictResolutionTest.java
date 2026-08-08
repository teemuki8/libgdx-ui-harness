package dev.gdx.uiharness.core.locator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.gdx.uiharness.core.error.ErrorCode;
import dev.gdx.uiharness.core.error.HarnessException;
import dev.gdx.uiharness.core.limits.HarnessLimits;
import dev.gdx.uiharness.core.model.Bounds;
import dev.gdx.uiharness.core.model.Role;
import dev.gdx.uiharness.core.model.SemanticNode;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import dev.gdx.uiharness.core.model.SemanticState;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

final class StrictResolutionTest {
    private final LocatorEngine engine = new StrictResolution(HarnessLimits.defaults());

    @Test void zeroMatchesProduceNotFoundWithBoundedCandidateSuggestions() {
        HarnessException error = assertThrows(HarnessException.class,
                () -> engine.resolveStrict(twoSaveButtons(), Locator.testId("missing")));

        assertEquals(ErrorCode.NOT_FOUND, error.code());
        assertEquals(7L, error.evidence().lastSnapshotRevision().orElseThrow());
        assertTrue(error.evidence().locator().orElseThrow().contains("missing"));
        assertEquals(List.of("right-save", "left-save"), error.evidence().candidates().stream()
                .map(candidate -> candidate.get("id"))
                .toList());
        assertTrue(error.evidence().details().get("suggestions").contains("testId"));
    }

    @Test void strictnessFailureListsDiscriminatingCandidatesInDocumentOrder() {
        HarnessException error = assertThrows(HarnessException.class,
                () -> engine.resolveStrict(
                        twoSaveButtons(), Locator.text(TextMatch.exact("Save"))));

        assertEquals(ErrorCode.STRICTNESS_VIOLATION, error.code());
        assertEquals(2, error.evidence().candidates().size());
        assertEquals(List.of("right-save", "left-save"), error.evidence().candidates().stream()
                .map(candidate -> candidate.get("id"))
                .toList());
        assertEquals("right", error.evidence().candidates().getFirst().get("testId"));
        assertEquals("left", error.evidence().candidates().getLast().get("testId"));
        assertTrue(error.evidence().candidates().getFirst().get("ancestor").contains("Right pane"));
        assertTrue(error.evidence().candidates().getLast().get("ancestor").contains("Left pane"));
        assertTrue(error.evidence().details().get("suggestions").contains("ancestor"));
    }

    @Test void strictResolutionStopsOnceMultiplicityIsProvenEvenWithSmallQueryLimit() {
        HarnessLimits limits = new HarnessLimits(
                100, 20, 1, 16_384, 1_048_576, Duration.ofSeconds(1));
        LocatorEngine bounded = new StrictResolution(limits);

        HarnessException error = assertThrows(HarnessException.class,
                () -> bounded.resolveStrict(
                        twoSaveButtons(), Locator.role(Role.BUTTON)));

        assertEquals(ErrorCode.STRICTNESS_VIOLATION, error.code());
        assertEquals(2, error.evidence().candidates().size());
    }

    @Test void strictIndexFailuresCarryFragileIndexEvidence() {
        HarnessException error = assertThrows(HarnessException.class,
                () -> engine.resolveStrict(
                        twoSaveButtons(), Locator.role(Role.BUTTON).atIndex(9)));

        assertEquals(ErrorCode.NOT_FOUND, error.code());
        assertEquals("true", error.evidence().details().get("fragileIndex"));
        assertEquals("9", error.evidence().details().get("index"));
    }

    @Test void pathologicalRegexCannotBlockResolution() throws Exception {
        String pathologicalCandidate = "a".repeat(16_383) + "!";
        TextMatch regex = TextMatch.regex("(a+)+$");
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        Future<Boolean> evaluation = executor.submit(() -> regex.matches(pathologicalCandidate));
        try {
            assertFalse(evaluation.get(2, TimeUnit.SECONDS),
                    "regex evaluation must complete within two seconds");
        } catch (TimeoutException deadline) {
            fail("regex evaluation exceeded the two-second deadline");
        } finally {
            evaluation.cancel(true);
            executor.shutdownNow();
        }
    }

    @Test void regexFindSemanticsSupportGroupingAlternationUnicodeAndAnchors() {
        assertTrue(TextMatch.regex("game").matches("Save game"));
        assertTrue(TextMatch.regex("(ab)+").matches("xababy"));
        assertTrue(TextMatch.regex("cat|dog").matches("a dog here"));
        assertTrue(TextMatch.regex("\\p{L}+").matches("caf\u00e9"));
        assertTrue(TextMatch.regex("^Save").matches("Save game"));
        assertTrue(TextMatch.regex("game$").matches("Save game"));
        assertFalse(TextMatch.regex("^game$").matches("Save game"));
    }

    @Test void re2UnsupportedConstructsFailAtConstructionBoundedly() {
        String candidate = "f0a9-secret-candidate";
        IllegalArgumentException backreference = assertThrows(IllegalArgumentException.class,
                () -> TextMatch.regex("(a)\\1" + candidate));
        IllegalArgumentException lookbehind = assertThrows(IllegalArgumentException.class,
                () -> TextMatch.regex("(?<=a)b" + candidate));
        assertFalse(backreference.getMessage().contains(candidate));
        assertFalse(lookbehind.getMessage().contains(candidate));
    }

    private static SemanticSnapshot twoSaveButtons() {
        SemanticNode root = node(
                "root", null, List.of("right-pane", "left-pane"), Role.GROUP,
                "root", "", null, null);
        SemanticNode rightPane = node(
                "right-pane", "root", List.of("right-save"), Role.GROUP,
                "Right pane", "", "right-pane", null);
        SemanticNode rightSave = node(
                "right-save", "right-pane", List.of(), Role.BUTTON,
                "Save", "Save", "right", "TextButton");
        SemanticNode leftPane = node(
                "left-pane", "root", List.of("left-save"), Role.GROUP,
                "Left pane", "", "left-pane", null);
        SemanticNode leftSave = node(
                "left-save", "left-pane", List.of(), Role.BUTTON,
                "Save", "Save", "left", "TextButton");
        Map<String, SemanticNode> deliberatelyDifferentMapOrder = new LinkedHashMap<>();
        deliberatelyDifferentMapOrder.put("left-save", leftSave);
        deliberatelyDifferentMapOrder.put("left-pane", leftPane);
        deliberatelyDifferentMapOrder.put("right-save", rightSave);
        deliberatelyDifferentMapOrder.put("right-pane", rightPane);
        deliberatelyDifferentMapOrder.put("root", root);
        return new SemanticSnapshot(7, 11, "root", deliberatelyDifferentMapOrder);
    }

    private static SemanticNode node(
            String id,
            String parentId,
            List<String> children,
            Role role,
            String name,
            String text,
            String testId,
            String actorType) {
        Bounds bounds = new Bounds(0, 0, 10, 10);
        SemanticState state = new SemanticState(
                true,
                true,
                Optional.of(true),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                false,
                true,
                1.0,
                false,
                true,
                true);
        return new SemanticNode(
                id,
                parentId,
                children,
                role,
                name,
                text,
                null,
                testId,
                null,
                actorType,
                state,
                bounds,
                bounds,
                bounds,
                0,
                Map.of());
    }
}
