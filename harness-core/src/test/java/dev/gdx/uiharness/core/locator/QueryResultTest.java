package dev.gdx.uiharness.core.locator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class QueryResultTest {
    @Test void candidateEvidenceMapsAreDefensivelyCopied() {
        Map<String, String> candidate = new HashMap<>();
        candidate.put("id", "save");
        List<Map<String, String>> candidates = new ArrayList<>();
        candidates.add(candidate);

        QueryResult result = new QueryResult(List.of(), candidates);
        candidate.put("id", "mutated");
        candidates.clear();

        assertEquals(List.of(Map.of("id", "save")), result.evidence());
        assertThrows(UnsupportedOperationException.class,
                () -> result.evidence().getFirst().put("role", "button"));
    }
}
