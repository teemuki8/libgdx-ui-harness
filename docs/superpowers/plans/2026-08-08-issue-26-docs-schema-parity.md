# Agent-Tools Guide / MCP Catalog Schema Parity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `docs/guides/agent-tools.md` an exact, machine-checkable consumer of the `HarnessToolCatalog` schema authority so a required input can never appear on one side without the other, fixing the documented `ui_runtime_compare` row that omits `maxDurationMillis`.

**Architecture:** `HarnessToolCatalog` stays the single schema authority — no generated schema fork and no second hand-maintained authority. The guide's "Tool-specific input" column becomes a stable machine-readable section with a strict token grammar (`none`, or comma-separated `required `field`` / `optional `field`` tokens). A new `DocsCatalogParityTest` parses that column, derives each tool's required set from the live catalog object, and asserts equality after removing the two preamble-documented envelope fields (`sessionId`, `deadlineMillis`). The catalog's minimal examples — already correct for `ui_runtime_compare` — gain a completeness regression guard. No catalog or production code changes; the schema already requires `maxDurationMillis` and the example already sends it.

**Tech Stack:** Java 25, JUnit 5, Gradle wrapper, the MCP `McpSchema.Tool` catalog model, Markdown table parsing via `java.nio.file` + regex (no new dependencies).

## Global Constraints

- Spec: approved release design `docs/superpowers/specs/2026-08-08-issues-8-26-release-design.md`, Pull request 5 "schema/documentation parity", issue #26. Issue evidence: `docs/guides/agent-tools.md:26` and `harness-mcp/src/main/java/dev/gdx/uiharness/mcp/HarnessToolCatalog.java:305-311`.
- The MCP tool catalog is the single schema authority; the guide is a consumer. Do NOT introduce a second schema authority, a generated schema file, or a schema fork.
- No production code changes: the catalog schema already requires `maxDurationMillis` for `ui_runtime_compare` (`HarnessToolCatalog.java:308-311`), and its minimal example already includes `sessionId`, `locator`, and `maxDurationMillis` (`HarnessToolCatalog.java:1005-1008`). `harness-mcp/src/test/resources/mcp/tool-catalog-v1.json` (the golden) must remain byte-identical.
- No ADR: the tool schema itself does not change (design: "No ADR is required because the tool schema itself does not change.").
- Envelope contract: `sessionId` (required for every tool except `ui_sessions`) and `deadlineMillis` are documented by the guide preamble and excluded from per-row cells. Keep the preamble's sentence "Except for `ui_sessions`, every tool requires `sessionId`" verbatim.
- Table grammar (enforced by the test): each data-row cell is exactly `none`, or a comma-separated list of `required `field`` / `optional `field`` tokens where `field` matches `[A-Za-z][A-Za-z0-9]*`. No prose, no "and", no semicolons, no trailing descriptors.
- TDD: Task 1 lands the failing parity test before any guide edit; Task 3 is a green regression guard for an already-satisfied property.
- All Gradle commands use the wrapper, JDK 25, `--no-daemon --console=plain --warning-mode=fail`, run from the worktree root (branch `fix/issue-26-docs-parity`).
- Non-goals: the `README.md` tool table (name/purpose only, no input claims); ADR 0025/0026 prose; the pre-existing schema quirk that `ui_assert` and `ui_scenario_start` list `deadlineMillis` in their required arrays (envelope-excluded by this contract; the deadline contract belongs to issue #12's cluster); the golden catalog file.
- This plan document lives under gitignored `docs/superpowers/`; commit it with `git add -f`.

---

### Task 1: Failing catalog/docs parity test (red)

The new test parses the guide's "Tool-specific input" column and compares every tool's documented required inputs against the live catalog schema. With the current guide it must fail: sixteen rows violate the token grammar, and `ui_runtime_compare` is missing `maxDurationMillis` — the reported defect.

**Files:**
- Create: `harness-mcp/src/test/java/dev/gdx/uiharness/mcp/DocsCatalogParityTest.java`
- Test: `harness-mcp/src/test/java/dev/gdx/uiharness/mcp/DocsCatalogParityTest.java`

**Interfaces:**
- Consumes: `HarnessToolCatalog` public API — `tools()`, `toolNames()`, `tool(String)`; `McpSchema.Tool.inputSchema()` maps with `type`/`properties`/`required`.
- Produces: the machine-readable grammar contract and helpers `parseGuide()` → `GuideTable(rows, parseErrors)`, `schemaRequired(McpSchema.Tool)`, `guideFile()`; consumed by Task 2 as the pass condition and by Task 3 as the model for the example-completeness assertion.

- [ ] **Step 1: Write the failing test**

Create `harness-mcp/src/test/java/dev/gdx/uiharness/mcp/DocsCatalogParityTest.java` (checkstyle: no star imports, no tabs, newline at end of file):

```java
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
    /** Envelope fields documented by the guide preamble instead of each tool row. */
    private static final Set<String> ENVELOPE = Set.of("sessionId", "deadlineMillis");

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
```

- [ ] **Step 2: Run the focused test and record the expected failure**

Run from the worktree root:

```bash
./gradlew :harness-mcp:test --tests 'dev.gdx.uiharness.mcp.DocsCatalogParityTest' --no-daemon --console=plain --warning-mode=fail
```

Expected: FAIL.
- `guideTableIsParseableAndCoversEveryCatalogTool` reports the sixteen non-grammar rows: `ui_action`, `ui_assert`, `ui_wait`, `ui_screenshot`, `ui_inspect_compare`, `ui_typography_diagnose`, `ui_layout_diagnose`, `ui_trace_start`, `ui_scenario_start`, `ui_navigation_inspect`, `ui_navigation_validate`, `ui_validate_layout`, `ui_matrix_run`, `ui_matrix_results`, `ui_trace_query`, `ui_semantic_compare` (cells written as prose, "and", or with trailing descriptors).
- `documentedRequiredInputsMatchCatalogSchemasForEveryTool` fails on `ui_runtime_compare`: expected `[locator, maxDurationMillis]` but documented `[locator]` — the exact reported defect (guide omits the schema-required `maxDurationMillis`, `HarnessToolCatalog.java:308-311`).
- `sessionIdPreambleMatchesCatalogEnvelope` passes (catalog-side invariant).

- [ ] **Step 3: Commit the red test**

```bash
git add harness-mcp/src/test/java/dev/gdx/uiharness/mcp/DocsCatalogParityTest.java
git commit -m "test(mcp): assert agent-tools guide parity with the MCP tool catalog"
```

The commit is intentionally red; the branch head is green again after Task 2.

---

### Task 2: Rewrite the guide table to the stable grammar and fix `ui_runtime_compare` (green)

Every row's "Tool-specific input" cell becomes exact grammar tokens derived from the catalog schema (required minus the envelope fields). `ui_runtime_compare` gains `maxDurationMillis` as a required input — the issue's fix — and the three prose rows plus the "and"/descriptor rows are made exact so the parity test can compare every catalog schema. Purpose and Result columns stay byte-identical.

**Files:**
- Modify: `docs/guides/agent-tools.md:3` (add grammar contract paragraph)
- Modify: `docs/guides/agent-tools.md:7-29` (the 23 data rows of the tool table)

**Interfaces:**
- Consumes: the Task 1 grammar and parity test as the pass condition.
- Produces: the stable machine-readable section that the parity test validates on every `check`; the documented `ui_runtime_compare` required inputs `locator` + `maxDurationMillis`.

- [ ] **Step 1: Replace the 23 data rows' Tool-specific input cells**

Replace the table data rows (`docs/guides/agent-tools.md:7-29`) so the "Tool-specific input" column reads exactly:

```markdown
| `ui_sessions` | List active sessions | none | bounded session IDs and capability names |
| `ui_snapshot` | Capture a compact semantic snapshot | none | revision, frame, root ID, node count, optional `state-action/v1` identity/contract and full-snapshot artifact |
| `ui_query` | Evaluate a lazy locator | required `locator` | match count, bounded node summaries/evidence, optional artifact |
| `ui_action` | Perform one allowlisted action | required `locator`, required `action` | before/after revisions, observed state, evidence, optional artifact |
| `ui_assert` | Assert a semantic condition on a resolved locator with typed outcome | required `schemaVersion`, required `locator`, required `assertion` | assertion outcome and evidence |
| `ui_wait` | Wait on semantics | required `locator`, required `condition` | final revision/frame, matches/evidence, optional artifact |
| `ui_screenshot` | Capture completed-frame PNG evidence | optional `locator`, required `maxWidth`, required `maxHeight`, required `maxPixels`, required `maxPngBytes` | opaque artifact receipt plus frame/revision/dimensions/scales |
| `ui_inspect_compare` | Inspect, capture, and compare one current full frame | required `referenceId`, required `policyId`, required `policyVersion`, required `viewportId`, required `maxIterations`, required `maxDurationMillis`, required `maxWidth`, required `maxHeight`, required `maxPixels`, required `maxPngBytes` | explicit convergence status, bounded semantic/spatial differences, current PNG and heatmap artifacts, and full immutable evidence artifact |
| `ui_typography_diagnose` | Capture and diagnose visible registered text controls | required `referenceId`, required `viewportId`, required `maxDurationMillis`, required `maxResults`, required `maxWidth`, required `maxHeight`, required `maxPixels`, required `maxPngBytes` | actor-attributed typography status and reports, current PNG artifact, and immutable diagnostic evidence artifact |
| `ui_layout_diagnose` | Capture and diagnose selected controls after layout quiescence | required `referenceId`, required `viewportId`, required `maxDurationMillis`, required `maxResults`, required `maxWidth`, required `maxHeight`, required `maxPixels`, required `maxPngBytes` | actor-attributed layout status and summaries, quiescence proof, current PNG artifact, and immutable full evidence artifact |
| `ui_trace_start` | Start bounded trace collection | required `maxDurationMillis`, required `maxBytes` | trace ID |
| `ui_trace_stop` | Stop and finalize the active trace | none | trace ID/reference, event count, bytes |
| `ui_scenarios` | List registered bounded scenarios | none | bounded scenario list |
| `ui_scenario_start` | Start one bounded scenario | required `scenarioId`, required `seed`, required `configuration`, required `profileId` | scenario start outcome |
| `ui_navigation_inspect` | Run a bounded navigation path through real input dispatch | required `spec` | bounded navigation path with observed focus steps |
| `ui_navigation_validate` | Validate a navigation path without executing it | required `spec` | validation result |
| `ui_validate_layout` | Validate whole-stage or subtree layout invariants from one completed frame | required `spec` | status and bounded findings |
| `ui_matrix_run` | Run one scenario/assertion set across a bounded display matrix | required `spec` | run ID |
| `ui_matrix_results` | Retrieve one retained matrix run report | required `runId` | bounded report |
| `ui_runtime_compare` | Compare a bound node's displayed value against its runtime observation | required `locator`, required `maxDurationMillis` | typed comparison with correlation |
| `ui_trace_query` | Query compact state transitions from a retained trace | required `spec` | bounded transitions |
| `ui_semantic_compare` | Compare a registered semantic baseline against the current snapshot | required `spec` | matched status and bounded differences |
| `ui_capabilities` | Discover one session's supported operations | none | bounded capability names, exact operation schemas/examples, diagnostic registry, and recovery policy |
```

Field lists above are derived from the catalog: `ui_action` `{locator, action}`; `ui_assert` `{schemaVersion, locator, assertion}`; `ui_wait` `{locator, condition}`; `ui_screenshot` required `{maxWidth, maxHeight, maxPixels, maxPngBytes}` with optional `locator`; `ui_inspect_compare` the ten reference/policy/viewport/iteration/duration/pixel/PNG fields; `ui_typography_diagnose` and `ui_layout_diagnose` the eight reference/viewport/duration/result/pixel/PNG fields; `ui_trace_start` `{maxDurationMillis, maxBytes}`; `ui_scenario_start` `{scenarioId, seed, configuration, profileId}` (`deadlineMillis` is envelope-excluded); `ui_runtime_compare` `{locator, maxDurationMillis}`; the six spec tools (`ui_navigation_inspect`, `ui_navigation_validate`, `ui_validate_layout`, `ui_matrix_run`, `ui_trace_query`, `ui_semantic_compare`) and `ui_matrix_results` as listed. Enum semantics such as `ui_wait`'s `condition` values remain discoverable through `ui_capabilities`' exact operation schemas.

- [ ] **Step 2: Add the grammar contract paragraph to the preamble**

Insert a blank line, this paragraph, and a blank line between the preamble paragraph (line 3) and the existing blank line (line 4), so the table header moves from line 5 to line 7:

```markdown
`sessionId` and `deadlineMillis` are envelope fields documented by this preamble and omitted from the per-tool rows; the per-tool rows name only tool-specific inputs. Each row is `none` or a comma-separated list of `required`/`optional` field tokens, and a schema-parity test fails when a required input appears on either side without the other.
```

- [ ] **Step 3: Run the parity test to verify green**

```bash
./gradlew :harness-mcp:test --tests 'dev.gdx.uiharness.mcp.DocsCatalogParityTest' --no-daemon --console=plain --warning-mode=fail
```

Expected: PASS — all four tests. `ui_runtime_compare` now documents exactly `{locator, maxDurationMillis}`.

- [ ] **Step 4: Run the full harness-mcp suite**

```bash
./gradlew :harness-mcp:test --no-daemon --console=plain --warning-mode=fail
```

Expected: PASS — the existing `HarnessToolCatalogTest` (including `everyAdvertisedExampleValidatesAgainstItsInputSchema`) and `HarnessMcpServerContractTest` are unaffected; no catalog code changed.

- [ ] **Step 5: Commit**

```bash
git add docs/guides/agent-tools.md
git commit -m "docs(guides): list exact required inputs per MCP tool; add maxDurationMillis to ui_runtime_compare"
```

---

### Task 3: Minimal-example completeness regression guard (green)

The catalog's minimal examples are served through `ui_capabilities` and already include every required field — `ui_runtime_compare`'s example is `{sessionId, locator, maxDurationMillis}` (`HarnessToolCatalog.java:1005-1008`). This test locks that property so a future required field can never be added to a schema without its example.

**Files:**
- Modify: `harness-mcp/src/test/java/dev/gdx/uiharness/mcp/HarnessToolCatalogTest.java` (add one test method after `everyAdvertisedExampleValidatesAgainstItsInputSchema`)

**Interfaces:**
- Consumes: `catalog.operationCatalog()` entries `{name, inputSchema, outputSchema, minimalExamples}` (Task 1 helper model).
- Produces: the invariant that every advertised minimal example contains every schema-required input field.

- [ ] **Step 1: Write the test**

Add to `HarnessToolCatalogTest.java`:

```java
@Test void minimalExamplesIncludeEveryRequiredInput() {
    for (Map<String, Object> operation : catalog.operationCatalog()) {
        String name = (String) operation.get("name");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> examples =
                (List<Map<String, Object>>) operation.get("minimalExamples");
        @SuppressWarnings("unchecked")
        List<String> required =
                (List<String>) catalog.tool(name).inputSchema().get("required");
        for (Map<String, Object> example : examples) {
            assertTrue(example.keySet().containsAll(
                    required == null ? List.of() : required),
                    name + " minimal example misses required inputs: " + example.keySet());
        }
    }
}
```

(Imports needed are already present: `List`, `Map`, `assertTrue`.)

- [ ] **Step 2: Run to verify green**

```bash
./gradlew :harness-mcp:test --tests 'dev.gdx.uiharness.mcp.HarnessToolCatalogTest' --no-daemon --console=plain --warning-mode=fail
```

Expected: PASS — 13 tests, including the new completeness guard. If it fails, the example missing a required field must be completed in `HarnessToolCatalog.examples()` — verify against the schema's `required` array — but no example is expected to fail today.

- [ ] **Step 3: Commit**

```bash
git add harness-mcp/src/test/java/dev/gdx/uiharness/mcp/HarnessToolCatalogTest.java
git commit -m "test(mcp): require every minimal example to include every required input"
```

---

### Task 4: Documentation scan and full cluster verification

`docs/AGENTS.md` requires a contradiction/placeholder scan after editing documentation, and the design requires the release gate before each cluster merge. The expected outcome is clean; if a scan surfaces a contradiction, fix it test-first (extend the parity test or the guide) and re-run the focused tests before proceeding.

**Files:**
- Verify only: `docs/guides/agent-tools.md`, `harness-mcp/src/test/java/dev/gdx/uiharness/mcp/DocsCatalogParityTest.java`, `harness-mcp/src/test/java/dev/gdx/uiharness/mcp/HarnessToolCatalogTest.java`, `harness-mcp/src/test/resources/mcp/tool-catalog-v1.json`

- [ ] **Step 1: Scan the guide for contradictions and placeholders**

```bash
grep -n 'required `' docs/guides/agent-tools.md
grep -nE 'TBD|TODO|FIXME|placeholder' docs/guides/agent-tools.md
grep -c '^| `ui_' docs/guides/agent-tools.md
```

Expected: the first grep matches only table data rows (after Task 2 these are lines 9-31; no prose rows match); the second matches nothing; the third prints `23`. The only "required" claim outside the table is the preamble's "every tool requires `sessionId`", which the parity test asserts against the catalog.

- [ ] **Step 2: Verify the schema authority and golden are untouched**

```bash
git status --short
```

Expected: exactly `M docs/guides/agent-tools.md`, `M harness-mcp/src/test/java/dev/gdx/uiharness/mcp/HarnessToolCatalogTest.java`, `?? harness-mcp/src/test/java/dev/gdx/uiharness/mcp/DocsCatalogParityTest.java`. No `HarnessToolCatalog.java` change and no `tool-catalog-v1.json` change.

- [ ] **Step 3: Run the release gate**

```bash
./gradlew clean check javadoc --no-daemon --console=plain --warning-mode=fail
python3 scripts/validate-workflows.py
git diff --check
```

Expected: `BUILD SUCCESSFUL`, `PASS`, and no output from `git diff --check`.

- [ ] **Step 4: Confirm the three implementation commits and the final head are green**

```bash
git log --oneline -4
```

Expected: `test(mcp): require every minimal example to include every required input`, `docs(guides): list exact required inputs per MCP tool; add maxDurationMillis to ui_runtime_compare`, `test(mcp): assert agent-tools guide parity with the MCP tool catalog`, and the plan commit below it — with the full suite green at the head (Step 3 ran at the head).

---

### Task 5: Pull request and issue closure

**Files:**
- Push only: the `fix/issue-26-docs-parity` branch

- [ ] **Step 1: Push the branch**

```bash
git push -u origin fix/issue-26-docs-parity
```

- [ ] **Step 2: Open the pull request with `Fixes #26`**

```bash
gh pr create --base main --head fix/issue-26-docs-parity \
  --title "docs: synchronize agent-tools guide with the MCP tool catalog" \
  --body '## Summary
- `docs/guides/agent-tools.md` now lists `maxDurationMillis` alongside `locator` as required inputs for `ui_runtime_compare`.
- The "Tool-specific input" column is now a stable machine-readable section: each row is `none` or comma-separated `required`/`optional` field tokens; envelope fields (`sessionId`, `deadlineMillis`) stay preamble-documented.
- New `DocsCatalogParityTest` derives the documented required-input set from the guide and compares it with every catalog schema; it fails when a required field is added to either side without the other.
- `HarnessToolCatalogTest` now requires every minimal example to include every required input.

## Root cause
The table was hand-maintained prose. The catalog requires `maxDurationMillis` for `ui_runtime_compare` (`HarnessToolCatalog.java:305-311`) but the guide (`docs/guides/agent-tools.md:26`) listed only `locator`, so agents received MISSING_ARGUMENT before reaching the comparison path.

## Acceptance evidence
- `./gradlew :harness-mcp:test --tests "dev.gdx.uiharness.mcp.DocsCatalogParityTest" --no-daemon --console=plain --warning-mode=fail` — PASS (4 tests)
- `./gradlew :harness-mcp:test --tests "dev.gdx.uiharness.mcp.HarnessToolCatalogTest" --no-daemon --console=plain --warning-mode=fail` — PASS (13 tests)
- `./gradlew clean check javadoc --no-daemon --console=plain --warning-mode=fail` — BUILD SUCCESSFUL
- `python3 scripts/validate-workflows.py` — PASS
- `git diff --check` — clean
- No production code changed; golden catalog `tool-catalog-v1.json` byte-identical.

Fixes #26'
```

- [ ] **Step 3: Review the reviewed SHA per the release design's PR policy**

Review the remote base, head, commit list, files, full patch, compatibility, boundedness, test quality, comments, and checks on the exact head SHA. Reproduce and fix every actionable finding test-first, then re-run Task 4 Step 3 on the new SHA. No ADR is required; no issue acceptance criterion is left without direct test evidence:

- "The guide lists every required input" — Task 2 table + Task 1 parity test (green at head).
- "The documented minimal example succeeds" — existing `everyAdvertisedExampleValidatesAgainstItsInputSchema` (validity) + Task 3 (completeness); `ui_runtime_compare` example is `{sessionId, locator, maxDurationMillis}`.
- "A docs/schema parity check prevents drift" — `DocsCatalogParityTest.documentedRequiredInputsMatchCatalogSchemasForEveryTool`.

- [ ] **Step 4: Merge and verify closure**

```bash
gh pr merge --squash --delete-branch
```

Verify the PR is merged on `origin/main` and issue #26 shows closed; then reconcile the local `main` worktree with `origin/main`.

---

## Self-Review

**Spec coverage:** Issue #26 acceptance criteria map to Task 2 (guide lists every required input), Task 3 + existing example test (documented minimal example succeeds), Tasks 1-2 (docs/schema parity check prevents drift). Design cluster-5 requirements map: `maxDurationMillis` row fix (Task 2), preamble sessionId sentence kept verbatim (Global Constraints + Task 2), parity test over every catalog schema with bidirectional drift failure (Task 1), catalog minimal example remains executable and complete (Task 3), no second schema authority (no generated schema; test derives from the live catalog object), no ADR (Global Constraints).

**Placeholder scan:** Every code step contains complete code or exact command + expected output; no "TBD", "add validation", "similar to Task N", or unimplemented function references. The field lists in Task 2 Step 1 are enumerated for all 23 rows.

**Type consistency:** `GuideTable(rows, parseErrors)`, `ToolInputs(required, optional)`, `schemaRequired(McpSchema.Tool)`, `parseGuide()`, `guideFile()` are defined in Task 1 and used identically in Tasks 1-3. `catalog.operationCatalog()` entries expose `minimalExamples` (Task 3) as consumed. Envelope set `{sessionId, deadlineMillis}` appears in Task 1 code and Global Constraints with the same names.
