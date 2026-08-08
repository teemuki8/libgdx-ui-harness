package dev.gdx.uiharness.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The agent-tools guide's "Tool-specific input" column is a stable machine-readable section:
 * each row is {@code none} or a comma-separated list of {@code required `field`} /
 * {@code optional `field`} tokens. The MCP tool catalog is the single schema authority; this
 * test fails when a required input is added to either side without the other.
 */
final class DocsCatalogParityTest {
    private static final Pattern TOKEN = Pattern.compile(
            "^(required|optional) `([A-Za-z][A-Za-z0-9]*)`$");
    /** The only globally documented envelope field; every other required input appears per row. */
    private static final Set<String> ENVELOPE = Set.of("sessionId");

    private final HarnessToolCatalog catalog = new HarnessToolCatalog();

    private record ToolInputs(Set<String> required, Set<String> optional) {}

    private record GuideTable(
            Map<String, ToolInputs> rows, Map<String, String> parseErrors) {}

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
        Map<String, ToolInputs> rows = new LinkedHashMap<>();
        Map<String, String> errors = new LinkedHashMap<>();
        for (String line : Files.readAllLines(guideFile(), StandardCharsets.UTF_8)) {
            if (!line.startsWith("|")) {
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
            name = name.replace("`", "");
            String cell = cells[3].trim();
            Set<String> required = new LinkedHashSet<>();
            Set<String> optional = new LinkedHashSet<>();
            if (!"none".equals(cell)) {
                for (String rawToken : cell.split(",")) {
                    Matcher matcher = TOKEN.matcher(rawToken.trim());
                    if (!matcher.matches()) {
                        errors.put(name, rawToken.trim());
                    } else if ("required".equals(matcher.group(1))) {
                        required.add(matcher.group(2));
                    } else {
                        optional.add(matcher.group(2));
                    }
                }
            }
            rows.put(name, new ToolInputs(required, optional));
        }
        return new GuideTable(rows, errors);
    }

    private static Set<String> schemaRequired(McpSchema.Tool tool) {
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) tool.inputSchema().get("required");
        return required == null ? Set.of() : new LinkedHashSet<>(required);
    }

    @Test void guideTableIsParseableAndCoversEveryCatalogTool() throws Exception {
        GuideTable table = parseGuide();
        assertTrue(table.parseErrors().isEmpty(),
                "Non-grammar Tool-specific input tokens: " + table.parseErrors());
        assertEquals(catalog.toolNames(), table.rows().keySet());
    }

    @Test void documentedRequiredInputsMatchCatalogSchemasForEveryTool() throws Exception {
        GuideTable table = parseGuide();
        for (McpSchema.Tool tool : catalog.tools()) {
            ToolInputs documented = table.rows().get(tool.name());
            if (documented == null) {
                continue; // rows missing from the guide are reported by the coverage test
            }
            Set<String> expected = new LinkedHashSet<>(schemaRequired(tool));
            expected.removeAll(ENVELOPE);
            assertEquals(expected, documented.required(),
                    tool.name() + " documented required inputs");
        }
    }

    @Test void documentedOptionalInputsExistAndAreNotRequired() throws Exception {
        GuideTable table = parseGuide();
        for (McpSchema.Tool tool : catalog.tools()) {
            ToolInputs documented = table.rows().get(tool.name());
            if (documented == null) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> properties =
                    (Map<String, Object>) tool.inputSchema().get("properties");
            Set<String> required = schemaRequired(tool);
            for (String field : documented.optional()) {
                assertTrue(properties.containsKey(field),
                        tool.name() + " documents optional " + field
                                + " without a schema property");
                assertFalse(required.contains(field),
                        tool.name() + " documents optional " + field
                                + " but the schema requires it");
            }
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
}
