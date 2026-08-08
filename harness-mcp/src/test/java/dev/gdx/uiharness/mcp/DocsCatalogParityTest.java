package dev.gdx.uiharness.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The agent-tools guide's "Tool-specific input" column is a stable machine-readable section:
 * each row is {@code none} or a comma-separated list of {@code required `field`} /
 * {@code optional `field`} tokens. Rows follow the catalog's {@code tools()} order and field
 * tokens follow the catalog schema's own order (the {@code required} array minus the
 * preamble-documented {@code sessionId} envelope field; optional tokens follow the schema
 * {@code properties} order). The MCP tool catalog is the single schema authority; this test
 * fails when a required input, a tool row, or an ordering is added to either side without the
 * other.
 */
final class DocsCatalogParityTest {
    private static final Pattern TOKEN = Pattern.compile(
            "^(required|optional) `([A-Za-z][A-Za-z0-9]*)`$");
    /** The only globally documented envelope field; every other required input appears per row. */
    private static final Set<String> ENVELOPE = Set.of("sessionId");

    private final HarnessToolCatalog catalog = new HarnessToolCatalog();

    private record ToolInputs(List<String> required, List<String> optional) {}

    private record ToolRow(String name, ToolInputs inputs) {}

    private record GuideTable(List<ToolRow> rows, Map<String, String> parseErrors) {}

    private static Path guideFile() {
        Path start = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path dir = start; dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve("docs/guides/agent-tools.md");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "Cannot locate docs/guides/agent-tools.md from " + start);
    }

    private GuideTable parseGuide() throws IOException {
        return parseGuide(guideFile());
    }

    private static GuideTable parseGuide(Path file) throws IOException {
        List<ToolRow> rows = new ArrayList<>();
        Map<String, String> errors = new LinkedHashMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (!line.startsWith("|")) {
                continue;
            }
            if (line.contains("\\|")) {
                errors.put("row " + rows.size(),
                        "escaped pipe \\| is outside the strict token grammar");
                continue;
            }
            String[] cells = line.split("\\|", -1);
            if (cells.length < 4) {
                continue;
            }
            String name = cells[1].trim();
            if (!name.startsWith("`ui_")) {
                continue;
            }
            String toolName = name.replace("`", "");
            String cell = cells[3].trim();
            List<String> required = new ArrayList<>();
            List<String> optional = new ArrayList<>();
            if (!"none".equals(cell)) {
                for (String rawToken : cell.split(",")) {
                    Matcher matcher = TOKEN.matcher(rawToken.trim());
                    if (!matcher.matches()) {
                        errors.put(toolName, rawToken.trim());
                    } else if ("required".equals(matcher.group(1))) {
                        required.add(matcher.group(2));
                    } else {
                        optional.add(matcher.group(2));
                    }
                }
            }
            if (rows.stream().anyMatch(row -> row.name().equals(toolName))) {
                errors.put(toolName, "duplicate row");
            }
            List<String> allFields = new ArrayList<>(required);
            allFields.addAll(optional);
            for (String field : allFields) {
                if (allFields.indexOf(field) != allFields.lastIndexOf(field)) {
                    errors.put(toolName, "duplicate field " + field);
                }
            }
            rows.add(new ToolRow(toolName, new ToolInputs(required, optional)));
        }
        return new GuideTable(rows, errors);
    }

    private static Path writeGuide(Path dir, String... lines) throws IOException {
        Path file = dir.resolve("agent-tools.md");
        Files.write(file, List.of(lines), StandardCharsets.UTF_8);
        return file;
    }

    private static List<String> schemaRequired(McpSchema.Tool tool) {
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) tool.inputSchema().get("required");
        return required == null ? new ArrayList<>() : new ArrayList<>(required);
    }

    private static ToolInputs documented(GuideTable table, String name) {
        return table.rows().stream()
                .filter(row -> row.name().equals(name))
                .map(ToolRow::inputs)
                .findFirst()
                .orElse(null);
    }

    @Test void guideTableIsParseableAndCoversEveryCatalogToolInOrder() throws Exception {
        GuideTable table = parseGuide();
        assertTrue(table.parseErrors().isEmpty(),
                "Non-grammar Tool-specific input tokens: " + table.parseErrors());
        List<String> documentedNames =
                table.rows().stream().map(ToolRow::name).toList();
        List<String> catalogNames =
                catalog.tools().stream().map(McpSchema.Tool::name).toList();
        assertEquals(catalogNames, documentedNames, "tool row order");
    }

    @Test void documentedRequiredInputsMatchCatalogSchemasForEveryTool() throws Exception {
        GuideTable table = parseGuide();
        for (McpSchema.Tool tool : catalog.tools()) {
            ToolInputs documented = documented(table, tool.name());
            if (documented == null) {
                continue; // rows missing from the guide are reported by the coverage test
            }
            List<String> expected = schemaRequired(tool);
            expected.removeAll(ENVELOPE);
            assertEquals(expected, documented.required(),
                    tool.name() + " documented required inputs (order follows the schema)");
        }
    }

    @Test void documentedOptionalInputsExistAndAreNotRequired() throws Exception {
        GuideTable table = parseGuide();
        for (McpSchema.Tool tool : catalog.tools()) {
            ToolInputs documented = documented(table, tool.name());
            if (documented == null) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> properties =
                    (Map<String, Object>) tool.inputSchema().get("properties");
            Set<String> required = new LinkedHashSet<>(schemaRequired(tool));
            for (String field : documented.optional()) {
                assertTrue(properties.containsKey(field),
                        tool.name() + " documents optional " + field
                                + " without a schema property");
                assertFalse(required.contains(field),
                        tool.name() + " documents optional " + field
                                + " but the schema requires it");
            }
            List<String> expectedOptional = properties.keySet().stream()
                    .filter(documented.optional()::contains)
                    .toList();
            assertEquals(expectedOptional, documented.optional(),
                    tool.name() + " documented optional inputs (order follows the schema)");
        }
    }

    @Test void sessionIdPreambleMatchesCatalogEnvelope() {
        Set<String> withoutSessionId = new LinkedHashSet<>();
        for (McpSchema.Tool tool : catalog.tools()) {
            if (!schemaRequired(tool).contains("sessionId")) {
                withoutSessionId.add(tool.name());
            }
        }
        assertEquals(Set.of("ui_sessions"), withoutSessionId);
    }

    @Test void escapedPipesInTableRowsAreRejected(@TempDir Path tempDir) throws Exception {
        GuideTable table = parseGuide(writeGuide(tempDir,
                "| Tool | Purpose | Tool-specific input | Result |",
                "| `ui_query` | Evaluate a lazy locator | required `locator` | match count |",
                "| `ui_wait` | Wait on semantics | required `locator` \\| required `condition` "
                        + "| result |"));
        assertTrue(table.parseErrors().values().stream()
                        .anyMatch(error -> error.contains("escaped pipe")),
                "escaped pipes must be rejected: " + table.parseErrors());
        assertFalse(table.rows().stream()
                        .anyMatch(row -> row.name().equals("ui_wait")),
                "a row with an escaped pipe must not be parsed");
    }

    @Test void duplicateToolRowsAreReported(@TempDir Path tempDir) throws Exception {
        GuideTable table = parseGuide(writeGuide(tempDir,
                "| Tool | Purpose | Tool-specific input | Result |",
                "| `ui_query` | Evaluate a lazy locator | required `locator` | match count |",
                "| `ui_query` | Duplicate row | required `locator` | match count |"));
        assertEquals("duplicate row", table.parseErrors().get("ui_query"));
    }

    @Test void duplicateFieldTokensAreReported(@TempDir Path tempDir) throws Exception {
        GuideTable table = parseGuide(writeGuide(tempDir,
                "| Tool | Purpose | Tool-specific input | Result |",
                "| `ui_query` | Evaluate a lazy locator | required `locator`, "
                        + "required `locator` | match count |"));
        assertEquals("duplicate field locator", table.parseErrors().get("ui_query"));
    }
}
