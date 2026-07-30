package benchmark.palisade;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** A bounded JSON-compatible state snapshot suitable for deterministic evidence. */
public record CandidateState(Map<String, Object> values) {
    private static final int MAX_CONTAINER_ENTRIES = 64;
    private static final int MAX_DEPTH = 8;
    private static final int MAX_KEY_LENGTH = 128;
    private static final int MAX_NODES = 4_096;
    private static final int MAX_STRING_LENGTH = 256;

    /** Validates and defensively copies a snapshot. */
    public CandidateState {
        Objects.requireNonNull(values, "values");
        values = copyMap(values, 0, new Budget());
    }

    private static Map<String, Object> copyMap(
            Map<?, ?> source, int depth, Budget budget) {
        checkDepthAndSize(source.size(), depth);
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key)
                    || key.isEmpty() || key.length() > MAX_KEY_LENGTH) {
                throw new IllegalArgumentException("Candidate state key is invalid");
            }
            copy.put(key, copyValue(entry.getValue(), depth + 1, budget));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static List<Object> copyList(List<?> source, int depth, Budget budget) {
        checkDepthAndSize(source.size(), depth);
        List<Object> copy = new ArrayList<>(source.size());
        for (Object value : source) {
            copy.add(copyValue(value, depth + 1, budget));
        }
        return Collections.unmodifiableList(copy);
    }

    private static Object copyValue(Object value, int depth, Budget budget) {
        budget.consume();
        if (value instanceof Map<?, ?> map) {
            return copyMap(map, depth, budget);
        }
        if (value instanceof List<?> list) {
            return copyList(list, depth, budget);
        }
        if (value instanceof String text) {
            if (text.length() > MAX_STRING_LENGTH) {
                throw new IllegalArgumentException("Candidate state string is too long");
            }
            return text;
        }
        if (value instanceof Double number && !Double.isFinite(number)) {
            throw new IllegalArgumentException("Candidate state number must be finite");
        }
        if (value instanceof Float number && !Float.isFinite(number)) {
            throw new IllegalArgumentException("Candidate state number must be finite");
        }
        if (value == null || value instanceof Boolean
                || value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long
                || value instanceof Float || value instanceof Double) {
            return value;
        }
        throw new IllegalArgumentException(
                "Candidate state contains a non-JSON-compatible value");
    }

    private static void checkDepthAndSize(int size, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("Candidate state is too deeply nested");
        }
        if (size > MAX_CONTAINER_ENTRIES) {
            throw new IllegalArgumentException("Candidate state container is too large");
        }
    }

    private static final class Budget {
        private int nodes;

        private void consume() {
            if (++nodes > MAX_NODES) {
                throw new IllegalArgumentException("Candidate state has too many values");
            }
        }
    }

    /** Returns an empty state for a blank candidate. */
    public static CandidateState empty() {
        return new CandidateState(Map.of());
    }
}
