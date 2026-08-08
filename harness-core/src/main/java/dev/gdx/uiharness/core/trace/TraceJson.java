package dev.gdx.uiharness.core.trace;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Strict package-local JSON codec for bounded trace records; depends only on the JDK. */
final class TraceJson {
    private static final Set<String> EVENT_FIELDS = Set.of(
            "sequence", "kind", "sessionId", "requestId", "logicalTime", "frame",
            "revision", "parentSequence", "evidence");
    private static final Set<String> MANIFEST_FIELDS_V1 = Set.of(
            "sessionId", "startedAt", "endedAt", "complete", "terminationReason",
            "eventCount", "artifactCount", "uncompressedBytes");
    private static final Set<String> MANIFEST_FIELDS_V2 = Set.of(
            "sessionId", "startedAt", "endedAt", "complete", "terminationReason",
            "eventCount", "artifactCount", "uncompressedBytes", "version",
            "eventsSha256", "artifacts");
    private static final int MAX_EVENT_NESTING = 1;
    private static final int MAX_MANIFEST_NESTING = 2;

    private TraceJson() {}

    static byte[] encodeEvent(TraceEvent event) {
        StringBuilder json = new StringBuilder(512);
        json.append('{');
        number(json, "sequence", event.sequence());
        comma(json);
        string(json, "kind", event.kind().name());
        comma(json);
        string(json, "sessionId", event.sessionId());
        comma(json);
        nullableString(json, "requestId", event.requestId());
        comma(json);
        number(json, "logicalTime", event.logicalTime());
        comma(json);
        nullableNumber(json, "frame", event.frame());
        comma(json);
        nullableNumber(json, "revision", event.revision());
        comma(json);
        nullableNumber(json, "parentSequence", event.parentSequence());
        comma(json);
        quoted(json, "evidence");
        json.append(':').append('{');
        boolean first = true;
        for (Map.Entry<String, String> entry : event.evidence().entrySet()) {
            if (!first) {
                comma(json);
            }
            quoted(json, entry.getKey());
            json.append(':');
            quoted(json, entry.getValue());
            first = false;
        }
        json.append('}').append('}');
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    static TraceEvent decodeEvent(byte[] bytes) throws IOException {
        Map<String, Object> object = parse(bytes);
        requireExactFields(object, EVENT_FIELDS, "event");
        Map<String, String> evidence = stringMap(requiredObject(object, "evidence"));
        try {
            return new TraceEvent(
                    requiredLong(object, "sequence"),
                    TraceEvent.Kind.valueOf(requiredString(object, "kind")),
                    requiredString(object, "sessionId"),
                    nullableString(object, "requestId"),
                    requiredLong(object, "logicalTime"),
                    nullableLong(object, "frame"),
                    nullableLong(object, "revision"),
                    nullableLong(object, "parentSequence"),
                    evidence);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IOException("invalid event fields", exception);
        }
    }

    static byte[] encodeManifest(TraceManifest manifest) {
        StringBuilder json = new StringBuilder(512);
        json.append('{');
        if (manifest.schemaVersion().equals(TraceManifest.V2)) {
            string(json, "version", manifest.schemaVersion());
            comma(json);
        }
        string(json, "sessionId", manifest.sessionId());
        comma(json);
        string(json, "startedAt", manifest.startedAt().toString());
        comma(json);
        string(json, "endedAt", manifest.endedAt().toString());
        comma(json);
        bool(json, "complete", manifest.complete());
        comma(json);
        string(json, "terminationReason", manifest.terminationReason());
        comma(json);
        number(json, "eventCount", manifest.eventCount());
        comma(json);
        number(json, "artifactCount", manifest.artifactCount());
        comma(json);
        number(json, "uncompressedBytes", manifest.uncompressedBytes());
        if (manifest.schemaVersion().equals(TraceManifest.V2)) {
            comma(json);
            string(json, "eventsSha256", manifest.eventsSha256());
            comma(json);
            quoted(json, "artifacts");
            json.append(":{");
            boolean first = true;
            for (Map.Entry<String, TraceManifest.ArtifactBinding> entry
                    : manifest.artifacts().entrySet()) {
                if (!first) {
                    comma(json);
                }
                quoted(json, entry.getKey());
                json.append(":{");
                string(json, "sha256", entry.getValue().sha256());
                comma(json);
                number(json, "size", entry.getValue().size());
                comma(json);
                string(json, "mediaType", entry.getValue().mediaType());
                json.append('}');
                first = false;
            }
            json.append('}');
        }
        json.append('}');
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    static TraceManifest decodeManifest(java.nio.file.Path archive, byte[] bytes)
            throws IOException {
        Map<String, Object> object = parse(bytes, MAX_MANIFEST_NESTING);
        boolean v2 = object.containsKey("version");
        requireExactFields(object,
                v2 ? MANIFEST_FIELDS_V2 : MANIFEST_FIELDS_V1, "manifest");
        try {
            String version = v2 ? requiredString(object, "version") : TraceManifest.V1;
            if (v2 && !version.equals(TraceManifest.V2)) {
                throw new IOException("unsupported manifest version: " + version);
            }
            String eventsSha256 = v2 ? requiredString(object, "eventsSha256") : null;
            Map<String, TraceManifest.ArtifactBinding> artifacts =
                    v2 ? artifactBindings(object) : Map.of();
            return new TraceManifest(
                    archive,
                    requiredString(object, "sessionId"),
                    Instant.parse(requiredString(object, "startedAt")),
                    Instant.parse(requiredString(object, "endedAt")),
                    requiredBoolean(object, "complete"),
                    requiredString(object, "terminationReason"),
                    requiredLong(object, "eventCount"),
                    requiredLong(object, "artifactCount"),
                    requiredLong(object, "uncompressedBytes"),
                    version,
                    eventsSha256,
                    artifacts);
        } catch (IllegalArgumentException | java.time.DateTimeException exception) {
            throw new IOException("invalid manifest fields", exception);
        }
    }

    private static final Set<String> ARTIFACT_BINDING_FIELDS = Set.of(
            "sha256", "size", "mediaType");

    @SuppressWarnings("unchecked")
    private static Map<String, TraceManifest.ArtifactBinding> artifactBindings(
            Map<String, Object> object) throws IOException {
        Object raw = object.get("artifacts");
        if (!(raw instanceof Map<?, ?> values)
                || values.size() > TraceManifest.MAX_MANIFEST_ARTIFACTS) {
            throw new IOException("manifest artifacts must be a bounded object");
        }
        LinkedHashMap<String, TraceManifest.ArtifactBinding> bindings =
                new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            if (!(entry.getKey() instanceof String id)
                    || !(entry.getValue() instanceof Map<?, ?> binding)) {
                throw new IOException("invalid artifact binding");
            }
            requireExactFields((Map<String, Object>) binding,
                    ARTIFACT_BINDING_FIELDS, "artifact binding");
            try {
                bindings.put(id, new TraceManifest.ArtifactBinding(
                        requiredString((Map<String, Object>) binding, "sha256"),
                        requiredLong((Map<String, Object>) binding, "size"),
                        requiredString((Map<String, Object>) binding, "mediaType")));
            } catch (ClassCastException exception) {
                throw new IOException("invalid artifact binding", exception);
            }
        }
        return Map.copyOf(bindings);
    }

    private static Map<String, Object> parse(byte[] bytes) throws IOException {
        return parse(bytes, MAX_EVENT_NESTING);
    }

    private static Map<String, Object> parse(byte[] bytes, int maxNesting) throws IOException {
        String json;
        try {
            json = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new IOException("trace JSON is not valid UTF-8", exception);
        }
        Parser parser = new Parser(json, maxNesting);
        Map<String, Object> object = parser.object(0);
        parser.whitespace();
        if (!parser.finished()) {
            throw new IOException("unexpected content after JSON object");
        }
        return object;
    }

    private static void requireExactFields(
            Map<String, Object> object, Set<String> expected, String kind) throws IOException {
        if (!object.keySet().equals(expected)) {
            throw new IOException(kind + " fields do not match the trace schema");
        }
    }

    private static String requiredString(Map<String, Object> object, String name)
            throws IOException {
        Object value = object.get(name);
        if (!(value instanceof String text)) {
            throw new IOException(name + " must be a string");
        }
        return text;
    }

    private static String nullableString(Map<String, Object> object, String name)
            throws IOException {
        Object value = object.get(name);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw new IOException(name + " must be a string or null");
        }
        return text;
    }

    private static long requiredLong(Map<String, Object> object, String name) throws IOException {
        Object value = object.get(name);
        if (!(value instanceof Long number)) {
            throw new IOException(name + " must be an integer");
        }
        return number;
    }

    private static Long nullableLong(Map<String, Object> object, String name) throws IOException {
        Object value = object.get(name);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Long number)) {
            throw new IOException(name + " must be an integer or null");
        }
        return number;
    }

    private static boolean requiredBoolean(Map<String, Object> object, String name)
            throws IOException {
        Object value = object.get(name);
        if (!(value instanceof Boolean bool)) {
            throw new IOException(name + " must be a boolean");
        }
        return bool;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requiredObject(Map<String, Object> object, String name)
            throws IOException {
        Object value = object.get(name);
        if (!(value instanceof Map<?, ?>)) {
            throw new IOException(name + " must be an object");
        }
        return (Map<String, Object>) value;
    }

    private static Map<String, String> stringMap(Map<String, Object> object) throws IOException {
        Map<String, String> strings = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : object.entrySet()) {
            if (!(entry.getValue() instanceof String value)) {
                throw new IOException("evidence values must be strings");
            }
            strings.put(entry.getKey(), value);
        }
        return strings;
    }

    private static void string(StringBuilder json, String name, String value) {
        quoted(json, name);
        json.append(':');
        quoted(json, value);
    }

    private static void nullableString(StringBuilder json, String name, String value) {
        quoted(json, name);
        json.append(':');
        if (value == null) {
            json.append("null");
        } else {
            quoted(json, value);
        }
    }

    private static void number(StringBuilder json, String name, long value) {
        quoted(json, name);
        json.append(':').append(value);
    }

    private static void nullableNumber(StringBuilder json, String name, Long value) {
        quoted(json, name);
        json.append(':');
        if (value == null) {
            json.append("null");
        } else {
            json.append(value);
        }
    }

    private static void bool(StringBuilder json, String name, boolean value) {
        quoted(json, name);
        json.append(':').append(value);
    }

    private static void comma(StringBuilder json) {
        json.append(',');
    }

    private static void quoted(StringBuilder json, String value) {
        json.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (character < 0x20) {
                        json.append(String.format("\\u%04x", (int) character));
                    } else {
                        json.append(character);
                    }
                }
            }
        }
        json.append('"');
    }

    private static final class Parser {
        private final String json;
        private final int maxNesting;
        private int index;

        private Parser(String json, int maxNesting) {
            this.json = json;
            this.maxNesting = maxNesting;
        }

        private Map<String, Object> object(int depth) throws IOException {
            if (depth > maxNesting) {
                throw new IOException("trace JSON nesting is too deep");
            }
            expect('{');
            whitespace();
            Map<String, Object> object = new LinkedHashMap<>();
            if (consume('}')) {
                return object;
            }
            while (true) {
                whitespace();
                String name = string();
                whitespace();
                expect(':');
                whitespace();
                Object value = value(depth);
                if (object.containsKey(name)) {
                    throw new IOException("duplicate JSON field: " + name);
                }
                object.put(name, value);
                whitespace();
                if (consume('}')) {
                    return object;
                }
                expect(',');
            }
        }

        private Object value(int depth) throws IOException {
            if (finished()) {
                throw new IOException("missing JSON value");
            }
            char current = json.charAt(index);
            if (current == '"') {
                return string();
            }
            if (current == '{') {
                return object(depth + 1);
            }
            if (current == 't' && consumeLiteral("true")) {
                return Boolean.TRUE;
            }
            if (current == 'f' && consumeLiteral("false")) {
                return Boolean.FALSE;
            }
            if (current == 'n' && consumeLiteral("null")) {
                return null;
            }
            return integer();
        }

        private Long integer() throws IOException {
            int start = index;
            if (consume('-') && finished()) {
                throw new IOException("incomplete JSON integer");
            }
            if (consume('0')) {
                if (!finished() && Character.isDigit(json.charAt(index))) {
                    throw new IOException("JSON integer has a leading zero");
                }
            } else {
                int digits = 0;
                while (!finished() && Character.isDigit(json.charAt(index))) {
                    index++;
                    digits++;
                }
                if (digits == 0) {
                    throw new IOException("invalid JSON value");
                }
            }
            if (!finished() && (json.charAt(index) == '.' || json.charAt(index) == 'e'
                    || json.charAt(index) == 'E')) {
                throw new IOException("trace numbers must be integers");
            }
            try {
                return Long.valueOf(json.substring(start, index));
            } catch (NumberFormatException exception) {
                throw new IOException("JSON integer is out of range", exception);
            }
        }

        private String string() throws IOException {
            expect('"');
            StringBuilder value = new StringBuilder();
            while (!finished()) {
                char character = json.charAt(index++);
                if (character == '"') {
                    return value.toString();
                }
                if (character < 0x20) {
                    throw new IOException("unescaped control character in JSON string");
                }
                if (character != '\\') {
                    value.append(character);
                    continue;
                }
                if (finished()) {
                    throw new IOException("incomplete JSON escape");
                }
                char escaped = json.charAt(index++);
                switch (escaped) {
                    case '"', '\\', '/' -> value.append(escaped);
                    case 'b' -> value.append('\b');
                    case 'f' -> value.append('\f');
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    case 'u' -> value.append(unicode());
                    default -> throw new IOException("invalid JSON escape");
                }
            }
            throw new IOException("unterminated JSON string");
        }

        private char unicode() throws IOException {
            if (index + 4 > json.length()) {
                throw new IOException("incomplete Unicode escape");
            }
            int value = 0;
            for (int offset = 0; offset < 4; offset++) {
                int digit = Character.digit(json.charAt(index++), 16);
                if (digit < 0) {
                    throw new IOException("invalid Unicode escape");
                }
                value = value * 16 + digit;
            }
            return (char) value;
        }

        private void expect(char expected) throws IOException {
            whitespace();
            if (finished() || json.charAt(index) != expected) {
                throw new IOException("expected '" + expected + "'");
            }
            index++;
        }

        private boolean consume(char expected) {
            if (!finished() && json.charAt(index) == expected) {
                index++;
                return true;
            }
            return false;
        }

        private boolean consumeLiteral(String literal) {
            if (json.startsWith(literal, index)) {
                index += literal.length();
                return true;
            }
            return false;
        }

        private void whitespace() {
            while (!finished()) {
                char character = json.charAt(index);
                if (character != ' ' && character != '\n'
                        && character != '\r' && character != '\t') {
                    return;
                }
                index++;
            }
        }

        private boolean finished() {
            return index >= json.length();
        }
    }
}
