package dev.gdx.uiharness.mcp;

import dev.gdx.uiharness.protocol.DiagnosticCode;
import dev.gdx.uiharness.protocol.DiagnosticEnvelope;
import dev.gdx.uiharness.protocol.ProtocolJson;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Deterministic all-errors validator for the bounded JSON Schema subset used by tools. */
final class SchemaDiagnostics {
    private static final int MAX_PROBLEMS = 256;
    private static final String OVERFLOW_PATH = "$";

    private SchemaDiagnostics() {}

    static List<DiagnosticEnvelope.FieldProblem> validate(
            Map<String, Object> rootSchema,
            Map<String, Object> arguments,
            Map<String, Object> minimalExample) {
        ArrayList<DiagnosticEnvelope.FieldProblem> problems = new ArrayList<>();
        validateValue(
                "$", rootSchema, arguments, rootSchema, false,
                minimalExample, problems, 0);
        problems.sort(Comparator
                .comparing(DiagnosticEnvelope.FieldProblem::fieldPath)
                .thenComparing(problem -> problem.code().name()));
        if (problems.size() > MAX_PROBLEMS) {
            return List.copyOf(problems.subList(0, MAX_PROBLEMS));
        }
        return List.copyOf(problems);
    }

    @SuppressWarnings("unchecked")
    private static void validateValue(
            String path,
            Map<String, Object> schema,
            Object value,
            Map<String, Object> rootSchema,
            boolean required,
            Map<String, Object> minimalExample,
            List<DiagnosticEnvelope.FieldProblem> problems,
            int depth) {
        if (depth > ProtocolJson.MAX_NESTING_DEPTH || problems.size() >= MAX_PROBLEMS) {
            overflow(problems);
            return;
        }
        Map<String, Object> resolved = resolve(schema, rootSchema);
        Object variants = resolved.get("oneOf");
        if (variants instanceof List<?> list) {
            Map<String, Object> selected = selectVariant(list, value, rootSchema);
            if (selected == null) {
                List<String> kinds = variantKinds(list, rootSchema);
                add(problems, DiagnosticCode.INVALID_ENUM_VALUE,
                        path + ".kind", observed(kind(value, "kind")),
                        new DiagnosticEnvelope.Expected(
                                "string", true, "kind", kinds,
                                null, null, null, null, null, false),
                        kinds, minimalExample);
                return;
            }
            validateValue(path, selected, value, rootSchema, required,
                    minimalExample, problems, depth + 1);
            return;
        }
        String type = stringValue(resolved.get("type"));
        if (type != null && !matches(type, value)) {
            add(problems, DiagnosticCode.INVALID_ARGUMENT_TYPE, path,
                    observed(value), expected(resolved, required),
                    admissible(resolved), minimalExample);
            return;
        }
        if ("object".equals(type) && value instanceof Map<?, ?> objectValue) {
            Map<String, Object> properties = map(resolved.get("properties"));
            List<String> requiredNames = stringList(resolved.get("required"));
            requiredNames.stream()
                    .filter(name -> !objectValue.containsKey(name))
                    .forEach(name -> add(
                            problems, DiagnosticCode.MISSING_ARGUMENT,
                            child(path, name), "absent",
                            expected(map(properties.get(name)), true),
                            admissible(map(properties.get(name))), minimalExample));
            if (Boolean.FALSE.equals(resolved.get("additionalProperties"))) {
                objectValue.keySet().stream()
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .filter(name -> !properties.containsKey(name))
                        .forEach(name -> add(
                                problems, DiagnosticCode.UNKNOWN_ARGUMENT,
                                child(path, name), observed(objectValue.get(name)),
                                new DiagnosticEnvelope.Expected(
                                        "known property", false, null,
                                        properties.keySet().stream().sorted().toList(),
                                        null, null, null, null, null, false),
                                properties.keySet().stream().sorted().toList(),
                                minimalExample));
            }
            properties.entrySet().stream()
                    .filter(entry -> objectValue.containsKey(entry.getKey()))
                    .forEach(entry -> validateValue(
                            child(path, entry.getKey()), map(entry.getValue()),
                            objectValue.get(entry.getKey()), rootSchema,
                            requiredNames.contains(entry.getKey()), minimalExample,
                            problems, depth + 1));
            return;
        }
        List<String> enumValues = stringList(resolved.get("enum"));
        if (!enumValues.isEmpty() && !enumValues.contains(String.valueOf(value))) {
            add(problems, DiagnosticCode.INVALID_ENUM_VALUE, path,
                    observed(value), expected(resolved, required),
                    enumValues, minimalExample);
        }
        if (value instanceof Number number) {
            double observed = number.doubleValue();
            Number minimum = numberValue(resolved.get("minimum"));
            Number maximum = numberValue(resolved.get("maximum"));
            if ((minimum != null && observed < minimum.doubleValue())
                    || (maximum != null && observed > maximum.doubleValue())) {
                add(problems, DiagnosticCode.OUT_OF_RANGE, path,
                        observed(value), expected(resolved, required),
                        range(resolved), minimalExample);
            }
        }
        if (value instanceof String text) {
            Number minimum = numberValue(resolved.get("minLength"));
            Number maximum = numberValue(resolved.get("maxLength"));
            if ((minimum != null && text.length() < minimum.intValue())
                    || (maximum != null && text.length() > maximum.intValue())) {
                add(problems, DiagnosticCode.OUT_OF_RANGE, path,
                        observed(text), expected(resolved, required),
                        lengthRange(resolved), minimalExample);
            }
        }
    }

    private static Map<String, Object> selectVariant(
            List<?> variants, Object value, Map<String, Object> rootSchema) {
        Object actualKind = kind(value, "kind");
        for (Object item : variants) {
            Map<String, Object> variant = resolve(map(item), rootSchema);
            Map<String, Object> properties = map(variant.get("properties"));
            Map<String, Object> kindSchema = map(properties.get("kind"));
            if (Objects.equals(kindSchema.get("const"), actualKind)) {
                return variant;
            }
        }
        return null;
    }

    private static List<String> variantKinds(
            List<?> variants, Map<String, Object> rootSchema) {
        return variants.stream()
                .map(SchemaDiagnostics::map)
                .map(schema -> resolve(schema, rootSchema))
                .map(schema -> map(schema.get("properties")))
                .map(properties -> map(properties.get("kind")).get("const"))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .sorted()
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> resolve(
            Map<String, Object> schema, Map<String, Object> rootSchema) {
        Object reference = schema.get("$ref");
        if (!(reference instanceof String path)
                || !path.startsWith("#/$defs/")) {
            return schema;
        }
        String name = path.substring("#/$defs/".length());
        return map(map(rootSchema.get("$defs")).get(name));
    }

    private static DiagnosticEnvelope.Expected expected(
            Map<String, Object> schema, boolean required) {
        String type = stringValue(schema.get("type"));
        if (type == null && schema.containsKey("oneOf")) {
            type = "tagged union";
        }
        Number minimum = numberValue(schema.get("minimum"));
        Number maximum = numberValue(schema.get("maximum"));
        Number minLength = numberValue(schema.get("minLength"));
        Number maxLength = numberValue(schema.get("maxLength"));
        return new DiagnosticEnvelope.Expected(
                type == null ? "schema value" : type,
                required,
                schema.containsKey("const") ? String.valueOf(schema.get("const")) : null,
                stringList(schema.get("enum")),
                decimal(minimum),
                decimal(maximum),
                schema.get("pattern") instanceof String pattern ? pattern : null,
                minLength == null ? null : minLength.intValue(),
                maxLength == null ? null : maxLength.intValue(),
                !Boolean.FALSE.equals(schema.get("additionalProperties")));
    }

    private static List<String> admissible(Map<String, Object> schema) {
        List<String> values = stringList(schema.get("enum"));
        if (!values.isEmpty()) {
            return values;
        }
        List<String> range = range(schema);
        return range.isEmpty() ? lengthRange(schema) : range;
    }

    private static List<String> range(Map<String, Object> schema) {
        Number minimum = numberValue(schema.get("minimum"));
        Number maximum = numberValue(schema.get("maximum"));
        return minimum == null && maximum == null
                ? List.of()
                : List.of("inclusive range ["
                        + (minimum == null ? "-infinity" : minimum)
                        + "," + (maximum == null ? "infinity" : maximum) + "]");
    }

    private static List<String> lengthRange(Map<String, Object> schema) {
        Number minimum = numberValue(schema.get("minLength"));
        Number maximum = numberValue(schema.get("maxLength"));
        return minimum == null && maximum == null
                ? List.of()
                : List.of("length ["
                        + (minimum == null ? 0 : minimum)
                        + "," + (maximum == null ? "bounded" : maximum) + "]");
    }

    private static void add(
            List<DiagnosticEnvelope.FieldProblem> destination,
            DiagnosticCode code,
            String path,
            String observed,
            DiagnosticEnvelope.Expected expected,
            List<String> admissible,
            Map<String, Object> minimalExample) {
        if (destination.size() < MAX_PROBLEMS) {
            destination.add(new DiagnosticEnvelope.FieldProblem(
                    code, path, observed, expected, admissible, minimalExample));
        } else {
            overflow(destination);
        }
    }

    private static void overflow(List<DiagnosticEnvelope.FieldProblem> destination) {
        if (destination.stream().anyMatch(
                problem -> OVERFLOW_PATH.equals(problem.fieldPath())
                        && problem.code() == DiagnosticCode.SCHEMA_CONFLICT)) {
            return;
        }
        if (destination.size() == MAX_PROBLEMS) {
            destination.removeLast();
        }
        destination.add(new DiagnosticEnvelope.FieldProblem(
                DiagnosticCode.SCHEMA_CONFLICT,
                OVERFLOW_PATH,
                "more than 256 independently detectable problems",
                new DiagnosticEnvelope.Expected(
                        "bounded request", true, null, List.of(),
                        null, null, null, null, null, false),
                List.of("at most 256 field problems"),
                Map.of()));
    }

    private static String child(String path, String name) {
        return "$".equals(path) ? "$." + name : path + "." + name;
    }

    private static boolean matches(String type, Object value) {
        return switch (type) {
            case "object" -> value instanceof Map<?, ?>;
            case "array" -> value instanceof List<?>;
            case "string" -> value instanceof String;
            case "boolean" -> value instanceof Boolean;
            case "integer" -> value instanceof Number number
                    && Double.isFinite(number.doubleValue())
                    && number.doubleValue() == Math.rint(number.doubleValue());
            case "number" -> value instanceof Number number
                    && Double.isFinite(number.doubleValue());
            default -> true;
        };
    }

    private static Object kind(Object value, String name) {
        return value instanceof Map<?, ?> map ? map.get(name) : null;
    }

    private static String observed(Object value) {
        String text = String.valueOf(value);
        return text.length() <= 256 ? text : text.substring(0, 256);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        raw.forEach((key, item) -> {
            if (key instanceof String name) {
                copy.put(name, item);
            }
        });
        return copy;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList();
    }

    private static String stringValue(Object value) {
        return value instanceof String text ? text : null;
    }

    private static Number numberValue(Object value) {
        return value instanceof Number number ? number : null;
    }

    private static java.math.BigDecimal decimal(Number value) {
        return value == null ? null
                : new java.math.BigDecimal(value.toString());
    }
}
