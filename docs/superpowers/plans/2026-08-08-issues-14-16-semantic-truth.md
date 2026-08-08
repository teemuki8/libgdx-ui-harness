# Issues 14–16 Semantic Truth Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close GitHub issues #15 (immutable registered semantic baselines), #16 (independent typed runtime comparison), and #14 (apply every display-matrix case before assertions) so semantic, runtime, and matrix evidence can no longer be self-confirming.

**Architecture:** Core gains digest-addressed immutable baselines (`BaselineDigest`, `SemanticBaseline.digest`, conflicting-replacement rejection in `SemanticBaselineCatalog`) and a typed value-format gate in `RuntimeComparator`; the agent-runtime adapter preserves the intrinsic `RuntimeValue` format through the observation boundary; the production fixture pre-loads a committed baseline resource, sources runtime values from an independent `ReferenceUiModel`, and applies/observes each matrix case through a host-owned allowlisted `ReferenceCaseApplicator` before scenario acquisition. The runner reports closed `UNSUPPORTED`/`MISAPPLIED` statuses and observed locale/font-set/restart-profile identities.

**Tech Stack:** Java 25, Gradle wrapper, JUnit 5 (`org.junit.jupiter`), Jackson (`jackson-databind`), libGDX 1.14.2 LWJGL3 backend, `io.github.teemuki8:agent-runtime-core:1.0.0`.

## Global Constraints

- Approved design: `docs/superpowers/specs/2026-08-08-issues-8-26-release-design.md` (merge order: cluster 2 "semantic truth" starts from the merged #8–13 branch). This plan is written against the post-#8–13 state of `Lwjgl3MatrixRunner.java` (lease-release failure handling in `runAssertions`/`terminalCase`) and `FixtureControl.java` (`DeadlineScheduler` plumbing). If the #8–13 merge introduced other conflicts, resolve them before starting Task 6.
- Every Gradle command uses the wrapper, JDK 25, and `--no-daemon --console=plain --warning-mode=fail`. Project warnings fail the build.
- Red-green-refactor: every behavior change starts with a failing behavioral test; record the expected failure before implementing.
- No sleeps for synchronization: wait on observable state with a monotonic deadline; synchronization tests use latches/barriers, never sleeps.
- No global mutable singleton harness; lifecycle and ownership stay explicit.
- Public protocol data stays immutable, versioned, bounded, deterministic, and serializable. New failures use typed public statuses/errors; user-controlled strings never become unbounded diagnostics.
- `MatrixCaseStatus` and the MCP `matrix-report` schema are public protocol surface: any enum/schema change updates `HarnessToolCatalog` and the committed golden `harness-mcp/src/test/resources/mcp/tool-catalog-v1.json` (regenerated with `UPDATE_TOOL_CATALOG_GOLDEN=true`, never hand-edited).
- Real LWJGL3 fixture smoke coverage is required for the display-matrix, semantic-baseline, and runtime-change proofs.
- Docs under `docs/` follow `docs/AGENTS.md`: exact paths/symbols, measurable acceptance criteria, no `TBD`/`TODO`/placeholders.
- `docs/superpowers/` is gitignored; plan commits use `git add -f docs/superpowers/plans/...`.
- Commit messages follow repo conventions: `feat(core): ...`, `test(fixtures): ...`, `docs(adr): ...`.

## Context map (current state at branch `fix/issues-14-16-semantic-truth`)

- #15: `FixtureControl.java:371-385` — `semanticCoordinator` auto-registers any unknown `baselineId` from the live snapshot (`baselineCatalog.contains` + `register(new SemanticBaseline(1, 0, spec.baselineId(), toBaselineNode(...), spec.strictNodes()))`). `SemanticBaselineCatalog.register` overwrites by id; `SemanticBaseline` has no digest.
- #16: `FixtureControl.java:306-315` — the `reference-ui-user/value` runtime property is read back off the `username` `TextField` actor. `AgentRuntimeObservationSource.observe` passes `binding.valueFormatId()` (usually `null`) as the observation's value-format, losing the `RuntimeValue` type. `RuntimeComparator.typedEqual` compares only strings; `ReferenceScreen.java:96-97` binds the field with `new RuntimeBinding("reference-ui-user", "value", null, null, "reference-ui-frame")`.
- #14: `Lwjgl3MatrixRunner.java` — `DisplayObserver` is called inside `terminalCase` *after* assertions/release; `FixtureControl.java:293-297` supplies a lambda returning hard-coded `(1280,720), 1.0, 1.0, LOGICAL`; the case's locale/font set are never applied; the reference window is fixed by `setWindowSizeLimits(WIDTH, HEIGHT, WIDTH, HEIGHT)` and `setResizable(false)`.

---

### Task 1: Core digest identity for registered baselines (#15)

**Files:**
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/golden/BaselineDigest.java`
- Modify: `harness-core/src/main/java/dev/gdx/uiharness/core/golden/SemanticBaseline.java`
- Modify: `harness-core/src/main/java/dev/gdx/uiharness/core/golden/SemanticBaselineCatalog.java`
- Create test: `harness-core/src/test/java/dev/gdx/uiharness/core/golden/SemanticBaselineCatalogTest.java`
- Modify test: `harness-core/src/test/java/dev/gdx/uiharness/core/golden/SemanticComparatorTest.java` (migrate the two `new SemanticBaseline(...)` sites at lines 183 and 206)

**Interfaces:**
- Produces: `SemanticBaseline(int majorVersion, int minorVersion, String id, BaselineNode root, boolean strictNodes, String digest)` — record with format-validated digest; `SemanticBaseline.registered(int majorVersion, int minorVersion, String id, BaselineNode root, boolean strictNodes)` — computes the canonical digest; `BaselineDigest.canonical(SemanticBaseline) -> String` (64 lowercase hex); `BaselineDigest.isValidFormat(String) -> boolean`; `SemanticBaselineCatalog.register` validates the digest and rejects conflicting replacement (same id, different digest); `require(id)` unchanged (still throws `IllegalArgumentException` for unknown ids). Consumed by Tasks 2–3.

- [ ] **Step 1: Write the failing test**

Create `harness-core/src/test/java/dev/gdx/uiharness/core/golden/SemanticBaselineCatalogTest.java`:

```java
package dev.gdx.uiharness.core.golden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.gdx.uiharness.core.model.Role;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class SemanticBaselineCatalogTest {
    private static final BaselineNode ROOT = new BaselineNode(
            Role.GROUP, "root", null, null, null, null, null,
            null, null, null, null, null, null, null, null,
            null, null, Map.of(), List.of());

    @Test void registeredBaselineIsImmutableAndDigestAddressed() {
        SemanticBaselineCatalog catalog = new SemanticBaselineCatalog();
        SemanticBaseline registered =
                SemanticBaseline.registered(1, 0, "reference-screen", ROOT, false);

        catalog.register(registered);
        SemanticBaseline required = catalog.require("reference-screen");

        assertSame(registered, required);
        assertEquals(64, registered.digest().length());
        assertEquals(registered.digest(), BaselineDigest.canonical(registered));
    }

    @Test void unknownAndMisspelledIdsAreRejected() {
        SemanticBaselineCatalog catalog = new SemanticBaselineCatalog();
        catalog.register(SemanticBaseline.registered(1, 0, "reference-screen", ROOT, false));

        assertThrows(IllegalArgumentException.class, () -> catalog.require("reference-scren"));
        assertThrows(IllegalArgumentException.class, () -> catalog.require("unknown-golden"));
    }

    @Test void conflictingReplacementIsRejected() {
        SemanticBaselineCatalog catalog = new SemanticBaselineCatalog();
        catalog.register(SemanticBaseline.registered(1, 0, "reference-screen", ROOT, false));
        BaselineNode changed = new BaselineNode(
                Role.GROUP, "root", "changed", null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, Map.of(), List.of());

        assertThrows(IllegalArgumentException.class, () -> catalog.register(
                SemanticBaseline.registered(1, 0, "reference-screen", changed, false)));
    }

    @Test void identicalReplacementIsIdempotent() {
        SemanticBaselineCatalog catalog = new SemanticBaselineCatalog();
        SemanticBaseline first = SemanticBaseline.registered(1, 0, "reference-screen", ROOT, false);
        catalog.register(first);

        catalog.register(SemanticBaseline.registered(1, 0, "reference-screen", ROOT, false));
        assertSame(first, catalog.require("reference-screen"));
    }

    @Test void registrationValidatesTheClaimedDigest() {
        SemanticBaselineCatalog catalog = new SemanticBaselineCatalog();
        SemanticBaseline tampered =
                new SemanticBaseline(1, 0, "reference-screen", ROOT, false, "0".repeat(64));

        assertThrows(IllegalArgumentException.class, () -> catalog.register(tampered));
    }

    @Test void digestDistinguishesCollidingPropertyEncodings() {
        BaselineNode singleValue = new BaselineNode(
                Role.GROUP, "root", null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, Map.of("a", "b, c=d"), List.of());
        BaselineNode twoValues = new BaselineNode(
                Role.GROUP, "root", null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, Map.of("a", "b", "c", "d"), List.of());

        assertNotEquals(
                SemanticBaseline.registered(1, 0, "r", singleValue, false).digest(),
                SemanticBaseline.registered(1, 0, "r", twoValues, false).digest(),
                "the canonical encoding must be injective for property maps");
    }

    @Test void digestDistinguishesPropertySplitAcrossEntries() {
        BaselineNode first = new BaselineNode(
                Role.GROUP, "root", null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, Map.of("a", "b, c=d", "e", "f"), List.of());
        BaselineNode second = new BaselineNode(
                Role.GROUP, "root", null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, Map.of("a", "b", "c", "d, e=f"), List.of());

        assertNotEquals(
                SemanticBaseline.registered(1, 0, "r", first, false).digest(),
                SemanticBaseline.registered(1, 0, "r", second, false).digest(),
                "the length-prefixed encoding must keep entry boundaries unambiguous");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :harness-core:test --tests 'dev.gdx.uiharness.core.golden.SemanticBaselineCatalogTest' --no-daemon --console=plain --warning-mode=fail`
Expected: FAIL — `SemanticBaseline.registered` and `BaselineDigest` do not exist (compilation error), and `SemanticBaseline` has no 6-component constructor. This is the red for the new public API.

- [ ] **Step 3: Implement the minimal core changes**

Create `harness-core/src/main/java/dev/gdx/uiharness/core/golden/BaselineDigest.java`:

```java
package dev.gdx.uiharness.core.golden;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Canonical SHA-256 digest over the complete versioned semantic baseline. The encoding is
 * deterministic (no maps without ordering, no locale-sensitive formatting) so the resource
 * digest is stable across processes and JVMs.
 */
public final class BaselineDigest {
    /** Hex length of a SHA-256 digest. */
    public static final int HEX_LENGTH = 64;

    private BaselineDigest() {}

    /** Computes the canonical digest of one versioned baseline. */
    public static String canonical(SemanticBaseline baseline) {
        Objects.requireNonNull(baseline, "baseline");
        StringBuilder out = new StringBuilder();
        out.append("semantic-baseline/v1\n");
        out.append("major=").append(baseline.majorVersion()).append('\n');
        out.append("minor=").append(baseline.minorVersion()).append('\n');
        out.append("id=").append(baseline.id()).append('\n');
        out.append("strictNodes=").append(baseline.strictNodes()).append('\n');
        appendNode(out, baseline.root(), 0);
        return sha256(out.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Validates the bounded lowercase-hex digest format. */
    public static boolean isValidFormat(String digest) {
        if (digest == null || digest.length() != HEX_LENGTH) {
            return false;
        }
        for (int index = 0; index < digest.length(); index++) {
            char c = digest.charAt(index);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    private static void appendNode(StringBuilder out, BaselineNode node, int depth) {
        String indent = "  ".repeat(depth);
        out.append(indent).append("node\n");
        out.append(indent).append("  role=").append(node.role()).append('\n');
        appendText(out, indent + "  accessibleName", node.accessibleName());
        appendText(out, indent + "  text", node.text());
        appendText(out, indent + "  label", node.label());
        appendText(out, indent + "  testId", node.testId());
        appendText(out, indent + "  actorName", node.actorName());
        appendText(out, indent + "  actorType", node.actorType());
        out.append(indent).append("  visible=").append(node.visible()).append('\n');
        out.append(indent).append("  enabled=").append(node.enabled()).append('\n');
        out.append(indent).append("  checked=").append(node.checked()).append('\n');
        out.append(indent).append("  selected=").append(node.selected()).append('\n');
        out.append(indent).append("  expanded=").append(node.expanded()).append('\n');
        out.append(indent).append("  editable=").append(node.editable()).append('\n');
        out.append(indent).append("  focused=").append(node.focused()).append('\n');
        out.append(indent).append("  focusable=").append(node.focusable()).append('\n');
        out.append(indent).append("  stageBounds=").append(node.stageBounds()).append('\n');
        appendText(out, indent + "  placement", node.placement());
        out.append(indent).append("  properties.count=")
                .append(node.properties().size()).append('\n');
        for (Map.Entry<String, String> property
                : new TreeMap<>(node.properties()).entrySet()) {
            appendText(out, indent + "    propertyKey", property.getKey());
            appendText(out, indent + "    propertyValue", property.getValue());
        }
        for (BaselineNode child : node.children()) {
            appendNode(out, child, depth + 1);
        }
    }

    /**
     * Appends a possibly null, possibly multi-line string with an explicit length prefix so
     * the encoding is injective: embedded newlines, colons, or comma-space sequences inside a
     * value can never be confused with the next field boundary.
     */
    private static void appendText(StringBuilder out, String label, String value) {
        if (value == null) {
            out.append(label).append("=null\n");
            return;
        }
        out.append(label).append(".len=").append(value.length()).append(':')
                .append(value).append('\n');
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK lacks SHA-256", impossible);
        }
    }
}
```

Replace the body of `harness-core/src/main/java/dev/gdx/uiharness/core/golden/SemanticBaseline.java` with:

```java
package dev.gdx.uiharness.core.golden;

import java.util.Objects;

/**
 * Versioned golden semantic baseline. Unknown major versions fail closed; minor versions are
 * additive and retained. The canonical digest binds the complete versioned baseline: the
 * identifier and digest together identify immutable content.
 */
public record SemanticBaseline(
        int majorVersion,
        int minorVersion,
        String id,
        BaselineNode root,
        boolean strictNodes,
        String digest) {
    public static final int CURRENT_MAJOR_VERSION = 1;

    /** Validates the version, identifier, root expectation, and digest identity. */
    public SemanticBaseline {
        if (majorVersion != CURRENT_MAJOR_VERSION) {
            throw new IllegalArgumentException(
                    "unsupported baseline major version: " + majorVersion);
        }
        if (minorVersion < 0) {
            throw new IllegalArgumentException("minorVersion must be non-negative");
        }
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("baseline id must not be blank");
        }
        Objects.requireNonNull(root, "root");
        if (!BaselineDigest.isValidFormat(digest)) {
            throw new IllegalArgumentException(
                    "baseline digest must be 64 lowercase hex characters");
        }
    }

    /** Creates a registered baseline whose digest is computed from the complete content. */
    public static SemanticBaseline registered(
            int majorVersion, int minorVersion, String id, BaselineNode root,
            boolean strictNodes) {
        SemanticBaseline provisional = new SemanticBaseline(
                majorVersion, minorVersion, id, root, strictNodes,
                "0".repeat(BaselineDigest.HEX_LENGTH));
        return new SemanticBaseline(majorVersion, minorVersion, id, root, strictNodes,
                BaselineDigest.canonical(provisional));
    }
}
```

Replace the body of `harness-core/src/main/java/dev/gdx/uiharness/core/golden/SemanticBaselineCatalog.java` with:

```java
package dev.gdx.uiharness.core.golden;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * In-memory application-registered catalog of immutable semantic baselines. Identifiers are
 * bounded strings; no filesystem path or external source is accepted. Registration validates
 * the canonical digest over the complete versioned baseline and rejects conflicting
 * replacement under an existing identifier.
 */
public final class SemanticBaselineCatalog {
    private final Map<String, SemanticBaseline> byId = new LinkedHashMap<>();

    /** Registers one immutable baseline, validating its digest and rejecting conflicts. */
    public void register(SemanticBaseline baseline) {
        Objects.requireNonNull(baseline, "baseline");
        if (!baseline.digest().equals(BaselineDigest.canonical(baseline))) {
            throw new IllegalArgumentException(
                    "semantic baseline digest mismatch for " + baseline.id());
        }
        SemanticBaseline existing = byId.get(baseline.id());
        if (existing != null && !existing.digest().equals(baseline.digest())) {
            throw new IllegalArgumentException(
                    "conflicting replacement for immutable semantic baseline " + baseline.id());
        }
        byId.putIfAbsent(baseline.id(), baseline);
    }

    /** Requires a registered baseline or throws with the missing identifier. */
    public SemanticBaseline require(String id) {
        Objects.requireNonNull(id, "id");
        SemanticBaseline baseline = byId.get(id);
        if (baseline == null) {
            throw new IllegalArgumentException("unknown semantic baseline: " + id);
        }
        return baseline;
    }

    /** Returns whether the named baseline is registered. */
    public boolean contains(String id) {
        return byId.containsKey(Objects.requireNonNull(id, "id"));
    }
}
```

Migrate `harness-core/src/test/java/dev/gdx/uiharness/core/golden/SemanticComparatorTest.java`:

- Line 206: `return new SemanticBaseline(1, 0, id, root, false);` → `return SemanticBaseline.registered(1, 0, id, root, false);`
- Lines 183-187 (`unknownMajorVersionFailsClosed`): `new SemanticBaseline(2, 0, "future", ..., false)` → `new SemanticBaseline(2, 0, "future", ..., false, "0".repeat(64))` (the major-version check must still throw before digest content validation; `"0".repeat(64)` satisfies the format check).

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :harness-core:test --tests 'dev.gdx.uiharness.core.golden.*' --no-daemon --console=plain --warning-mode=fail`
Expected: PASS — all `SemanticBaselineCatalogTest` tests and all migrated `SemanticComparatorTest` tests.

- [ ] **Step 5: Commit**

```bash
git add harness-core/src/main/java/dev/gdx/uiharness/core/golden/BaselineDigest.java harness-core/src/main/java/dev/gdx/uiharness/core/golden/SemanticBaseline.java harness-core/src/main/java/dev/gdx/uiharness/core/golden/SemanticBaselineCatalog.java harness-core/src/test/java/dev/gdx/uiharness/core/golden/SemanticBaselineCatalogTest.java harness-core/src/test/java/dev/gdx/uiharness/core/golden/SemanticComparatorTest.java
git commit -m "feat(core): bind semantic baselines to canonical digest identity"
```

---

### Task 2: Fixture baseline codec and committed resource (#15)

**Files:**
- Create: `harness-fixtures/src/main/java/dev/gdx/uiharness/fixtures/ReferenceBaselineCodec.java`
- Modify: `harness-fixtures/src/main/java/dev/gdx/uiharness/fixtures/FixtureControl.java` (add public `pristineBaseline()`)
- Modify: `harness-fixtures/src/main/java/dev/gdx/uiharness/fixtures/ReferenceUiApplication.java` (add the `dump-baseline` launch mode)
- Create test: `harness-fixtures/src/test/java/dev/gdx/uiharness/fixtures/ReferenceBaselineDumpTest.java`
- Create (generated, then committed): `harness-fixtures/src/main/resources/reference-ui/reference-baseline.json`

**Interfaces:**
- Consumes: Task 1 `SemanticBaseline.registered`, `BaselineDigest`.
- Produces: `ReferenceBaselineCodec.read(Path|InputStream) -> SemanticBaseline`, `ReferenceBaselineCodec.write(Path, SemanticBaseline)`; `FixtureControl.pristineBaseline() -> SemanticBaseline` (id `reference-screen`, `strictNodes=false`); `ReferenceUiApplication` accepts `java ... <processRoot> dump-baseline` and writes `<processRoot>/reference-baseline.json` before printing `REFERENCE_UI_READY`. Consumed by Task 3.

- [ ] **Step 1: Write the failing test**

Create `harness-fixtures/src/test/java/dev/gdx/uiharness/fixtures/ReferenceBaselineDumpTest.java`:

```java
package dev.gdx.uiharness.fixtures;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Captures the pristine semantic baseline from a real LWJGL3 reference process and asserts it
 * byte-matches the committed resource. Set {@code UPDATE_REFERENCE_BASELINE_GOLDEN=true} to
 * regenerate the committed resource after a deliberate screen change.
 */
final class ReferenceBaselineDumpTest {
    @Test
    @Timeout(120)
    void dumpedBaselineMatchesTheCommittedResource() throws Exception {
        Path resource = Path.of("src/main/resources/reference-ui/reference-baseline.json");
        boolean update = "true".equals(System.getenv("UPDATE_REFERENCE_BASELINE_GOLDEN"));
        if (!update) {
            assertTrue(Files.isRegularFile(resource),
                    "the reference baseline resource must exist");
        }
        try (ReferenceProcess app = ReferenceProcess.launch("dump-baseline")) {
            Path generated = app.root().resolve("reference-baseline.json");
            assertTrue(Files.isRegularFile(generated),
                    "the reference process must dump its pristine baseline");
            byte[] actual = Files.readAllBytes(generated);
            if (update) {
                Files.write(resource, actual);
            }
            assertArrayEquals(Files.readAllBytes(resource), actual,
                    "the committed baseline must match a fresh pristine process");
        }
    }
}
```

- [ ] **Step 2: Implement the codec and the dump mode**

Create `harness-fixtures/src/main/java/dev/gdx/uiharness/fixtures/ReferenceBaselineCodec.java`:

```java
package dev.gdx.uiharness.fixtures;

import dev.gdx.uiharness.core.golden.SemanticBaseline;
import dev.gdx.uiharness.protocol.ProtocolJson;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Jackson codec for the canonical committed reference baseline resource. */
public final class ReferenceBaselineCodec {
    private ReferenceBaselineCodec() {}

    /** Reads a baseline from the committed canonical resource path. */
    public static SemanticBaseline read(Path path) {
        Objects.requireNonNull(path, "path");
        try {
            return ProtocolJson.mapper().readValue(Files.readAllBytes(path),
                    SemanticBaseline.class);
        } catch (IOException failure) {
            throw new IllegalArgumentException(
                    "Unable to read semantic baseline resource " + path, failure);
        }
    }

    /** Reads a baseline from the committed canonical resource stream. */
    public static SemanticBaseline read(InputStream input) {
        Objects.requireNonNull(input, "input");
        try {
            return ProtocolJson.mapper().readValue(input, SemanticBaseline.class);
        } catch (IOException failure) {
            throw new IllegalArgumentException(
                    "Unable to read semantic baseline resource", failure);
        }
    }

    /** Writes the canonical pretty-JSON resource. */
    public static void write(Path path, SemanticBaseline baseline) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(baseline, "baseline");
        Files.writeString(path,
                ProtocolJson.mapper().writerWithDefaultPrettyPrinter()
                        .writeValueAsString(baseline) + System.lineSeparator(),
                StandardCharsets.UTF_8);
    }
}
```

- [ ] **Step 3: Add the dump mode**

In `ReferenceUiApplication.java`, extend `main` argument parsing (replace the block that currently rejects a final non-`markup` argument) and thread a `dumpBaseline` flag through:

```java
boolean dumpBaseline = args.length == 2 && "dump-baseline".equals(args[1]);
boolean markup = (args.length == 2 || args.length == 4)
        && "markup".equals(args[args.length - 1]);
if ((args.length == 2 || args.length == 4) && !markup && !dumpBaseline) {
    throw new IllegalArgumentException("Final argument must be \"markup\" or \"dump-baseline\"");
}
```

Change the field and constructor of `ReferenceUiApplication` to carry `boolean dumpBaseline`, pass it from `main`, and in `create()` after `control.startMcp(System.in, System.out);` add:

```java
if (dumpBaseline) {
    try {
        ReferenceBaselineCodec.write(
                processRoot.resolve("reference-baseline.json"),
                control.pristineBaseline());
    } catch (IOException failure) {
        throw new IllegalStateException("Unable to dump reference baseline", failure);
    }
}
```

In `FixtureControl.java`, add the public pristine-baseline capture (uses the existing private `toBaselineNode` helpers):

```java
/** Captures the pristine semantic baseline from the current stage for the dump mode. */
public SemanticBaseline pristineBaseline() {
    SemanticSnapshot current = sceneSession.snapshot(clock.revision(), clock.frame());
    return SemanticBaseline.registered(
            1, 0, REFERENCE_ID, toBaselineNode(current.nodes(), current.rootId()), false);
}
```

- [ ] **Step 4: Run the test to verify it fails (resource missing)**

Run: `./gradlew :harness-fixtures:test --tests 'dev.gdx.uiharness.fixtures.ReferenceBaselineDumpTest' --no-daemon --console=plain --warning-mode=fail`
Expected: FAIL — `src/main/resources/reference-ui/reference-baseline.json` does not exist yet (`assertTrue(Files.isRegularFile(resource))` fails).

- [ ] **Step 5: Generate and commit the resource**

Run: `UPDATE_REFERENCE_BASELINE_GOLDEN=true ./gradlew :harness-fixtures:test --tests 'dev.gdx.uiharness.fixtures.ReferenceBaselineDumpTest' --no-daemon --console=plain --warning-mode=fail`
Expected: PASS — the test writes `harness-fixtures/src/main/resources/reference-ui/reference-baseline.json` from a fresh real LWJGL3 process and then asserts the byte match. The file contains the pretty-JSON `SemanticBaseline` record (fields `majorVersion`, `minorVersion`, `id` = `reference-screen`, `root`, `strictNodes` = `false`, `digest` = 64 lowercase hex).

- [ ] **Step 6: Commit**

```bash
git add harness-fixtures/src/main/java/dev/gdx/uiharness/fixtures/ReferenceBaselineCodec.java harness-fixtures/src/main/java/dev/gdx/uiharness/fixtures/ReferenceUiApplication.java harness-fixtures/src/main/java/dev/gdx/uiharness/fixtures/FixtureControl.java harness-fixtures/src/test/java/dev/gdx/uiharness/fixtures/ReferenceBaselineDumpTest.java harness-fixtures/src/main/resources/reference-ui/reference-baseline.json
git commit -m "feat(fixtures): commit the canonical reference semantic baseline resource"
```

---

### Task 3: Preload baselines, reject unknown ids, never learn from the UI (#15)

**Files:**
- Modify: `harness-fixtures/src/main/java/dev/gdx/uiharness/fixtures/FixtureControl.java` (baseline catalog field + resource preload; `semanticCoordinator` no longer learns; typed `not-found`)
- Modify test: `harness-fixtures/src/test/java/dev/gdx/uiharness/fixtures/HarnessMcpClient.java` (add `semanticCompareFailure`)
- Modify test: `harness-fixtures/src/test/java/dev/gdx/uiharness/fixtures/SemanticGoldensProductionFixtureTest.java` (rewrite)
- Modify: `docs/adr/0023-semantic-golden-comparison.md` (amendment)

**Interfaces:**
- Consumes: Task 1 digest catalog, Task 2 `ReferenceBaselineCodec.read`, committed `reference-baseline.json`.
- Produces: `FixtureControl` field `private final SemanticBaselineCatalog baselineCatalog = new SemanticBaselineCatalog();` preloaded in the constructor from `/reference-ui/reference-baseline.json`; `ui_semantic_compare` with an unknown or misspelled `baselineId` returns an MCP error of code `not-found` whose message contains the id; the fixture never registers a baseline from a live snapshot. The `SemanticCompareSpec.strictNodes` request flag no longer mutates the registered baseline.

- [ ] **Step 1: Write the failing fixture tests**

Add to `HarnessMcpClient.java` (mirrors the existing `assertFailure` error helper):

```java
JsonNode semanticCompareFailure(String sessionId, String baselineId, long deadlineMillis)
        throws Exception {
    Map<String, Object> spec = Map.of(
            "baselineId", baselineId,
            "strictNodes", false,
            "tolerances", List.of(),
            "excludedProperties", List.of(),
            "maxDifferences", 4096,
            "maxDurationMillis", 5000);
    JsonNode result = request("tools/call", Map.of(
            "name", "ui_semantic_compare",
            "arguments", Map.of(
                    "sessionId", sessionId, "spec", spec, "deadlineMillis", deadlineMillis)));
    if (!result.path("isError").asBoolean()) {
        throw new IllegalStateException("Expected MCP semantic-compare failure: " + result);
    }
    JsonNode content = result.path("structuredContent");
    requireKind(content, "error");
    return content;
}
```

Replace `harness-fixtures/src/test/java/dev/gdx/uiharness/fixtures/SemanticGoldensProductionFixtureTest.java` with:

```java
package dev.gdx.uiharness.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Real LWJGL3 semantic-golden fixture: the reference process serves the production MCP server
 * with its committed baseline resource pre-registered; {@code ui_semantic_compare} compares
 * against that immutable baseline only. An unknown or misspelled baseline id returns a typed
 * {@code not-found} error and the fixture never learns a baseline from the current UI.
 */
final class SemanticGoldensProductionFixtureTest {
    private static final String SESSION_ID = "reference-ui";
    private static final String BASELINE_ID = "reference-screen";

    private static boolean hasTextDrift(JsonNode comparison) {
        for (JsonNode difference : comparison.path("differences")) {
            if (!"CHANGED".equals(difference.path("kind").asText())) {
                continue;
            }
            for (JsonNode path : difference.path("propertyPaths")) {
                if ("text".equals(path.asText())) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    @Timeout(120)
    void registeredBaselineDetectsDriftThroughProductionMcp() throws Exception {
        try (ReferenceProcess app = ReferenceProcess.launch()) {
            try (HarnessMcpClient client = HarnessMcpClient.connect(app)) {
                JsonNode pristine = client.semanticCompare(SESSION_ID, BASELINE_ID, 5_000);
                assertTrue(pristine.path("comparedNodes").asInt() > 0);
                assertTrue(!hasTextDrift(pristine),
                        "the pristine screen must match its pre-registered resource baseline");

                client.fillByLabel(SESSION_ID, "Username", "Ada");

                JsonNode drifted = client.semanticCompare(SESSION_ID, BASELINE_ID, 5_000);
                assertTrue(drifted.path("comparedNodes").asInt() > 0);
                assertTrue(hasTextDrift(drifted),
                        "the fill must be detected as text drift against the resource baseline");
            }
        }
    }

    @Test
    @Timeout(120)
    void unknownBaselineReturnsTypedNotFoundAndNeverLearnsFromTheUi() throws Exception {
        try (ReferenceProcess app = ReferenceProcess.launch()) {
            try (HarnessMcpClient client = HarnessMcpClient.connect(app)) {
                client.fillByLabel(SESSION_ID, "Username", "Ada");

                for (String misspelled : List.of("reference-scren", "unknown-golden")) {
                    JsonNode error = client.semanticCompareFailure(SESSION_ID, misspelled, 5_000);
                    assertEquals("not-found", error.path("code").asText(), error.toString());
                    assertTrue(error.path("message").asText().contains(misspelled),
                            error.toString());
                }

                JsonNode known = client.semanticCompare(SESSION_ID, BASELINE_ID, 5_000);
                assertTrue(hasTextDrift(known),
                        "the filled UI must still drift against the pre-registered baseline");
            }
        }
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :harness-fixtures:test --tests 'dev.gdx.uiharness.fixtures.SemanticGoldensProductionFixtureTest' --no-daemon --console=plain --warning-mode=fail`
Expected: FAIL — on the current code the coordinator auto-registers `reference-scren`/`unknown-golden` from the live snapshot, so `semanticCompareFailure` throws "Expected MCP semantic-compare failure"; and the filled `reference-screen` compare registers the filled snapshot as its own baseline, so `hasTextDrift(known)` is false.

- [ ] **Step 3: Implement preload and the typed not-found result**

In `FixtureControl.java`:

1. Add the field next to the other fixture fields:
```java
private final SemanticBaselineCatalog baselineCatalog = new SemanticBaselineCatalog();
```
2. In the constructor (after the `matrixRunner` wiring is fine, before `terminationExecutor`), call `loadReferenceBaselines();` and add:
```java
private void loadReferenceBaselines() {
    try (InputStream input = FixtureControl.class.getResourceAsStream(
            "/reference-ui/reference-baseline.json")) {
        if (input == null) {
            throw new IllegalStateException("Reference semantic baseline resource is missing");
        }
        baselineCatalog.register(ReferenceBaselineCodec.read(input));
    } catch (IOException failure) {
        throw new IllegalStateException("Unable to read reference semantic baseline", failure);
    }
}
```
3. Delete the local declaration `SemanticBaselineCatalog baselineCatalog = new SemanticBaselineCatalog();` (currently at line 371) and replace the `semanticCoordinator` lambda (currently lines 373-385) with:

```java
HarnessProtocolService.SemanticCompareCoordinator semanticCoordinator =
        (spec, deadline) -> scheduler.submit(() -> {
            SemanticSnapshot current = sceneSession.snapshot(
                    clock.revision(), clock.frame());
            SemanticBaseline baseline;
            try {
                baseline = baselineCatalog.require(spec.baselineId());
            } catch (IllegalArgumentException missing) {
                throw new HarnessException(ErrorCode.NOT_FOUND,
                        "unknown semantic baseline: " + spec.baselineId(),
                        ErrorEvidence.empty());
            }
            return semanticComparator.compare(baseline, current, toCorePolicy(spec));
        }, deadline);
```

`HarnessException`, `ErrorCode`, `ErrorEvidence`, `SemanticBaselineCatalog`, `SemanticBaseline`, and `InputStream` are already imported in `FixtureControl.java`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :harness-fixtures:test --tests 'dev.gdx.uiharness.fixtures.SemanticGoldensProductionFixtureTest' --tests 'dev.gdx.uiharness.fixtures.ReferenceBaselineDumpTest' --no-daemon --console=plain --warning-mode=fail`
Expected: PASS — both fixture tests and the resource round-trip test.

- [ ] **Step 5: Amend ADR 0023**

Append to `docs/adr/0023-semantic-golden-comparison.md`:

```markdown
## Amendment (2026-08-08): registration and digest identity

Baselines are immutable and digest-addressed. `SemanticBaseline` carries a canonical SHA-256
`digest` computed by `BaselineDigest` over the complete versioned baseline (version, id,
strict-node flag, and the full `BaselineNode` tree). `SemanticBaselineCatalog.register`
validates the claimed digest against the recomputed canonical value and rejects any
conflicting replacement under an existing identifier; identical content is an idempotent
no-op. An unknown or misspelled identifier returns a typed `not-found` result and the harness
never learns a baseline from the current observation. The production fixture pre-loads its
committed `reference-ui/reference-baseline.json` resource before serving requests. The
request's `strictNodes` flag no longer mutates the registered baseline; strictness is a
property of the registered immutable baseline.
```

- [ ] **Step 6: Commit**

```bash
git add harness-fixtures/src/main/java/dev/gdx/uiharness/fixtures/FixtureControl.java harness-fixtures/src/test/java/dev/gdx/uiharness/fixtures/HarnessMcpClient.java harness-fixtures/src/test/java/dev/gdx/uiharness/fixtures/SemanticGoldensProductionFixtureTest.java docs/adr/0023-semantic-golden-comparison.md
git commit -m "feat(fixtures): preload immutable baselines and reject unknown ids"
```

---

### Task 4: Preserve runtime value type through the observation boundary (#16)

**Files:**
- Modify: `harness-agent-runtime/src/main/java/dev/gdx/uiharness/agentruntime/RuntimeValueRenderer.java` (add `formatId`)
- Modify: `harness-agent-runtime/src/main/java/dev/gdx/uiharness/agentruntime/AgentRuntimeObservationSource.java` (observe the runtime format)
- Modify: `harness-core/src/main/java/dev/gdx/uiharness/core/runtime/RuntimeComparator.java` (typed format gate)
- Create test: `harness-core/src/test/java/dev/gdx/uiharness/core/runtime/RuntimeComparatorTest.java`
- Modify test: `harness-agent-runtime/src/test/java/dev/gdx/uiharness/agentruntime/RuntimeValueRendererTest.java`
- Modify test: `harness-agent-runtime/src/test/java/dev/gdx/uiharness/agentruntime/AgentRuntimeObservationSourceTest.java`

**Interfaces:**
- Consumes: `RuntimeValue` sealed hierarchy, `RuntimeObservation.valueFormatId`, `RuntimeBinding.valueFormatId`.
- Produces: `RuntimeValueRenderer.formatId(RuntimeValue) -> String` in `{"null","boolean","integer","decimal","string","enum","vector2","list","object"}`; `AgentRuntimeObservationSource.observe` sets the observation `valueFormatId` from the runtime value's intrinsic type (not from the binding); `RuntimeComparator.compare` returns `AMBIGUOUS` with details `{"reason":"value-format-mismatch","declaredFormat":...,"runtimeFormat":...}` when the binding declares a format that differs from the observed runtime format, and never reports `EQUAL` in that case. Bindings with a null declared format keep textual equality (backward compatible).

- [ ] **Step 1: Write the failing tests**

Create `harness-core/src/test/java/dev/gdx/uiharness/core/runtime/RuntimeComparatorTest.java`:

```java
package dev.gdx.uiharness.core.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.gdx.uiharness.core.locator.Locator;
import dev.gdx.uiharness.core.locator.StrictResolution;
import dev.gdx.uiharness.core.model.Bounds;
import dev.gdx.uiharness.core.model.Role;
import dev.gdx.uiharness.core.model.RuntimeBinding;
import dev.gdx.uiharness.core.model.SemanticNode;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import dev.gdx.uiharness.core.model.SemanticState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class RuntimeComparatorTest {
    private final StrictResolution locators = new StrictResolution();

    @Test void equalTypedValuesWithMatchingDeclaredFormatReportEqual() {
        RuntimeComparator comparator =
                new RuntimeComparator(observation("100", "integer"));
        assertEquals(DisplayedRuntimeComparison.Status.EQUAL,
                comparator.compare(snapshot("100", format("integer")),
                        Locator.testId("health"), locators).status());
    }

    @Test void incompatibleDeclaredAndRuntimeFormatsCannotReportEqual() {
        RuntimeComparator comparator =
                new RuntimeComparator(observation("100", "integer"));
        DisplayedRuntimeComparison result = comparator.compare(
                snapshot("100", format("string")), Locator.testId("health"), locators);

        assertEquals(DisplayedRuntimeComparison.Status.AMBIGUOUS, result.status());
        assertEquals("value-format-mismatch", result.details().get("reason"));
        assertEquals("string", result.details().get("declaredFormat"));
        assertEquals("integer", result.details().get("runtimeFormat"));
    }

    @Test void valueDesynchronizationReportsMismatchOnCorrelatedFrames() {
        RuntimeComparator comparator =
                new RuntimeComparator(observation("50", "integer"));
        assertEquals(DisplayedRuntimeComparison.Status.MISMATCH,
                comparator.compare(snapshot("100", format("integer")),
                        Locator.testId("health"), locators).status());
    }

    @Test void undeclaredBindingFormatRetainsTextualEquality() {
        RuntimeComparator comparator =
                new RuntimeComparator(observation("100", "integer"));
        assertEquals(DisplayedRuntimeComparison.Status.EQUAL,
                comparator.compare(snapshot("100", null),
                        Locator.testId("health"), locators).status());
    }

    private static RuntimeObservationSource observation(String value, String runtimeFormat) {
        return binding -> Optional.of(new RuntimeObservation(
                "enemy-1", "health", 10, 10, value, runtimeFormat));
    }

    private static RuntimeBinding format(String declaredFormat) {
        return new RuntimeBinding(
                "enemy-1", "health", declaredFormat, null, "frame-1");
    }

    private static SemanticSnapshot snapshot(String displayed, RuntimeBinding binding) {
        Bounds bounds = new Bounds(0, 0, 100, 50);
        SemanticState state = new SemanticState(
                true, true, Optional.of(true), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), false, true, 1.0, false, true, true);
        SemanticNode node = new SemanticNode(
                "n1", "root", List.of(), Role.GROUP, "Health", displayed, null,
                "health", null, "Label", state, bounds, bounds, bounds, 0, Map.of(), binding);
        SemanticNode root = new SemanticNode(
                "root", null, List.of("n1"), Role.GROUP, "root", "", null, null,
                null, null, state, bounds, bounds, bounds, 0, Map.of());
        var byId = new LinkedHashMap<String, SemanticNode>();
        byId.put("root", root);
        byId.put("n1", node);
        return new SemanticSnapshot(1, 10, "root", byId);
    }
}
```

Add to `harness-agent-runtime/src/test/java/dev/gdx/uiharness/agentruntime/RuntimeValueRendererTest.java`:

```java
@Test
void exposesBoundedFormatIdentityPerVariant() {
    assertEquals("null", RuntimeValueRenderer.formatId(RuntimeValues.nullValue()));
    assertEquals("boolean", RuntimeValueRenderer.formatId(RuntimeValues.bool(true)));
    assertEquals("integer", RuntimeValueRenderer.formatId(RuntimeValues.integer(1)));
    assertEquals("decimal", RuntimeValueRenderer.formatId(RuntimeValues.decimal("1.5")));
    assertEquals("string", RuntimeValueRenderer.formatId(RuntimeValues.string("Ada")));
    assertEquals("enum", RuntimeValueRenderer.formatId(RuntimeValues.enumValue("LOGIN")));
    assertEquals("vector2", RuntimeValueRenderer.formatId(RuntimeValues.vector2(1, 2)));
    assertEquals("list", RuntimeValueRenderer.formatId(
            RuntimeValues.list(RuntimeValues.string("a"))));
    assertEquals("object", RuntimeValueRenderer.formatId(
            RuntimeValues.object(RuntimeValues.field("a", RuntimeValues.string("b")))));
}
```

Update `harness-agent-runtime/src/test/java/dev/gdx/uiharness/agentruntime/AgentRuntimeObservationSourceTest.java`:

- In `observesProvenRuntimeValue`, change the expected observation to `new RuntimeObservation("user", "name", 42, 42, "Ada", "string")`.
- Add:

```java
@Test void observesRuntimeFormatIdentity() {
    runtime.entities().register(
            EntityId.of("score"), EntityType.of("score"),
            () -> "Score",
            inspector -> inspector.property("value", () -> RuntimeValues.integer(7)));
    advanceFrame();
    recordCorrelation(42, CORRELATION_TOKEN);

    Optional<RuntimeObservation> observation = observe("score", "value", CORRELATION_TOKEN);

    assertTrue(observation.isPresent());
    assertEquals("integer", observation.orElseThrow().valueFormatId());
    assertEquals("7", observation.orElseThrow().value());
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :harness-core:test --tests 'dev.gdx.uiharness.core.runtime.RuntimeComparatorTest' :harness-agent-runtime:test --tests 'dev.gdx.uiharness.agentruntime.*' --no-daemon --console=plain --warning-mode=fail`
Expected: FAIL — `RuntimeValueRenderer.formatId` does not exist (compilation), `incompatibleDeclaredAndRuntimeFormatsCannotReportEqual` would currently report `EQUAL`, and the observation tests currently see a `null` value-format.

- [ ] **Step 3: Implement format identity and the comparator gate**

In `RuntimeValueRenderer.java`, add:

```java
/** Returns the bounded format identity of one runtime value. */
public static String formatId(RuntimeValue value) {
    if (value == null) {
        return "null";
    }
    return switch (value) {
        case RuntimeValue.NullValue _ -> "null";
        case RuntimeValue.BooleanValue _ -> "boolean";
        case RuntimeValue.IntegerValue _ -> "integer";
        case RuntimeValue.DecimalValue _ -> "decimal";
        case RuntimeValue.StringValue _ -> "string";
        case RuntimeValue.EnumValue _ -> "enum";
        case RuntimeValue.Vector2Value _ -> "vector2";
        case RuntimeValue.ListValue _ -> "list";
        case RuntimeValue.ObjectValue _ -> "object";
    };
}
```

In `AgentRuntimeObservationSource.java`, in `observe`, change the observation construction from `RuntimeValueRenderer.render(value.orElseThrow()), binding.valueFormatId()` to:

```java
RuntimeValue runtimeValue = value.orElseThrow();
return Optional.of(new RuntimeObservation(
        binding.entityId(),
        binding.propertyId(),
        provenFrame,
        provenFrame,
        RuntimeValueRenderer.render(runtimeValue),
        RuntimeValueRenderer.formatId(runtimeValue)));
```

In `RuntimeComparator.java`, insert the format gate immediately before `boolean equal = typedEqual(displayed, runtime.value(), binding);`:

```java
String runtimeFormat = runtime.valueFormatId();
if (binding.valueFormatId() != null && runtimeFormat != null
        && !binding.valueFormatId().equals(runtimeFormat)) {
    return new DisplayedRuntimeComparison(
            DisplayedRuntimeComparison.Status.AMBIGUOUS,
            binding.entityId(), binding.propertyId(), displayed, runtime.value(),
            binding.comparatorId(), binding.correlationId(),
            snapshot.frame(), runtime.frame(), false, Map.of(
                    "reason", "value-format-mismatch",
                    "declaredFormat", binding.valueFormatId(),
                    "runtimeFormat", runtimeFormat));
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :harness-core:test --tests 'dev.gdx.uiharness.core.runtime.*' :harness-agent-runtime:test --no-daemon --console=plain --warning-mode=fail`
Expected: PASS — `RuntimeComparatorTest`, `RuntimeValueRendererTest`, and `AgentRuntimeObservationSourceTest` all green.

- [ ] **Step 5: Commit**

```bash
git add harness-agent-runtime/src/main/java/dev/gdx/uiharness/agentruntime/RuntimeValueRenderer.java harness-agent-runtime/src/main/java/dev/gdx/uiharness/agentruntime/AgentRuntimeObservationSource.java harness-core/src/main/java/dev/gdx/uiharness/core/runtime/RuntimeComparator.java harness-core/src/test/java/dev/gdx/uiharness/core/runtime/RuntimeComparatorTest.java harness-agent-runtime/src/test/java/dev/gdx/uiharness/agentruntime/RuntimeValueRendererTest.java harness-agent-runtime/src/test/java/dev/gdx/uiharness/agentruntime/AgentRuntimeObservationSourceTest.java
git commit -m "feat(core): gate runtime equality on declared value-format identity"
```

---

### Task 5: Independent fixture runtime model with desynchronization proof (#16)

**Files:**
- Create: `harness-fixtures/src/main/java/dev/gdx/uiharness/fixtures/ReferenceUiModel.java`
- Modify: `harness-fixtures/src/main/java/dev/gdx/uiharness/fixtures/FixtureControl.java` (model field, entity registration from model, change-listener sync, scenario reset sync)
- Modify: `harness-fixtures/src/main/java/dev/gdx/uiharness/fixtures/ReferenceScreen.java` (binding declares format `string`)
- Modify test: `harness-fixtures/src/test/java/dev/gdx/uiharness/fixtures/RuntimeProductionFixtureTest.java` (desync test)
- Modify: `docs/adr/0025-runtime-entity-bindings.md`, `docs/adr/0026-agent-runtime-adapter.md` (amendments)

**Interfaces:**
- Consumes: Task 4 format identity.
- Produces: `ReferenceUiModel(String username, String password)` with `username()`, `password()`, `setUsername(String)`, `setPassword(String)` (bounded 16 384 chars, volatile, thread-safe). `FixtureControl` registers `reference-ui-user/value` from `uiModel.username()`; the `username` actor's `ChangeListener` synchronizes the model on real input; the model starts with username `"Ada"` while the pristine UI renders `""` (a deliberate, deterministic UI/model desynchronization). `ReferenceScreen` binds `valueFormatId` `"string"`.

- [ ] **Step 1: Write the failing desynchronization test**

Create `harness-fixtures/src/main/java/dev/gdx/uiharness/fixtures/ReferenceUiModel.java`:

```java
package dev.gdx.uiharness.fixtures;

import java.util.Objects;

/** Independent application-domain runtime model for the reference screen. */
public final class ReferenceUiModel {
    private static final int MAX_VALUE = 16_384;
    private volatile String username;
    private volatile String password;

    public ReferenceUiModel(String username, String password) {
        this.username = bounded(username);
        this.password = bounded(password);
    }

    public String username() {
        return username;
    }

    public String password() {
        return password;
    }

    public void setUsername(String value) {
        username = bounded(value);
    }

    public void setPassword(String value) {
        password = bounded(value);
    }

    private static String bounded(String value) {
        Objects.requireNonNull(value, "value");
        if (value.length() > MAX_VALUE) {
            throw new IllegalArgumentException("model value exceeds 16384 characters");
        }
        return value;
    }
}
```

Append to `harness-fixtures/src/test/java/dev/gdx/uiharness/fixtures/RuntimeProductionFixtureTest.java`:

```java
@Test
@Timeout(120)
void desynchronizedModelAndUiReportMismatchThroughProductionMcp() throws Exception {
    try (ReferenceProcess app = ReferenceProcess.launch()) {
        try (HarnessMcpClient client = HarnessMcpClient.connect(app)) {
            JsonNode comparison = client.runtimeCompare(SESSION_ID, 5_000);
            assertEquals("MISMATCH", comparison.path("status").asText());
            assertEquals("reference-ui-user", comparison.path("entityId").asText());
            assertEquals("value", comparison.path("propertyId").asText());
            assertEquals("", comparison.path("displayedValue").asText());
            assertEquals("Ada", comparison.path("runtimeValue").asText());
            assertEquals(comparison.path("displayedFrame").asLong(),
                    comparison.path("runtimeFrame").asLong(),
                    "the mismatch must carry bounded same-frame correlation evidence");
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :harness-fixtures:test --tests 'dev.gdx.uiharness.fixtures.RuntimeProductionFixtureTest' --no-daemon --console=plain --warning-mode=fail`
Expected: FAIL — the current provider reads the `username` actor, so the pristine UI (`""`) compares equal to the runtime (`""`) and the status is `EQUAL`, not `MISMATCH`.

- [ ] **Step 3: Implement the independent model**

In `FixtureControl.java`:

1. Add the field: `private final ReferenceUiModel uiModel = new ReferenceUiModel("Ada", "");`
2. Replace the `agentRuntime.entities().register(...)` block (currently lines 306-315) with:

```java
agentRuntime.entities().register(
        io.github.teemuki8.libgdx.agent.runtime.core.EntityId.of("reference-ui-user"),
        io.github.teemuki8.libgdx.agent.runtime.core.EntityType.of("user"),
        () -> "Reference UI user",
        inspector -> inspector.property("value", () ->
                io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValues.string(
                        uiModel.username())));
```

3. After the registration block, wire the UI-to-model synchronization and add the helper:

```java
wireModelToUsernameField();
```

```java
private void wireModelToUsernameField() {
    var usernameField = stage.getRoot().findActor("username");
    if (usernameField instanceof com.badlogic.gdx.scenes.scene2d.ui.TextField textField) {
        textField.addListener(new com.badlogic.gdx.scenes.scene2d.utils.ChangeListener() {
            @Override public void changed(ChangeEvent event, com.badlogic.gdx.scenes.scene2d.Actor actor) {
                uiModel.setUsername(textField.getText());
            }
        });
    }
}
```

4. In `ReferenceScenarioLifecycle.reset()` (currently clears the two text fields), also sync the model so a scenario reset leaves the UI and model aligned: change `new ReferenceScenarioLifecycle(stage, withholdScenarioFrames)` to `new ReferenceScenarioLifecycle(stage, uiModel, withholdScenarioFrames)`, add a `ReferenceUiModel uiModel` constructor field, and after `textField("username").setText("");` add `uiModel.setUsername("");`.

In `ReferenceScreen.java:96-97`, change the binding to declare the display format:

```java
newSemantics.bind(username, new dev.gdx.uiharness.core.model.RuntimeBinding(
        "reference-ui-user", "value", "string", null, "reference-ui-frame"));
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :harness-fixtures:test --tests 'dev.gdx.uiharness.fixtures.RuntimeProductionFixtureTest' --no-daemon --console=plain --warning-mode=fail`
Expected: PASS — the fill-driven test still reports `EQUAL` (the change listener synchronizes the model, both values are `"Ada"`, declared and runtime formats are both `"string"`), and the new desync test reports `MISMATCH` on the pristine process.

- [ ] **Step 5: Amend ADR 0025 and ADR 0026**

Append to `docs/adr/0025-runtime-entity-bindings.md`:

```markdown
## Amendment (2026-08-08): type compatibility and independent ownership

The runtime observation preserves the runtime value's type or format identity through the
observation boundary: the adapter reports the intrinsic `RuntimeValue` format (null, boolean,
integer, decimal, string, enum, vector2, list, object) as the observation's value-format
identity rather than echoing the binding's declared format. The comparator never reports
`EQUAL` when the binding declares a value format that differs from the observed runtime
format; the incompatible case returns the closed `AMBIGUOUS` status with bounded mismatch
details. Bindings may target properties on already-registered runtime entities without
deriving the observation provider from the bound actor. Applications that install no runtime
provider, and bindings that declare no format, retain the prior textual comparison and
unavailable results.
```

Append to `docs/adr/0026-agent-runtime-adapter.md`:

```markdown
## Amendment (2026-08-08): runtime type identity and independent model ownership

The fixture's runtime values come from an independent `ReferenceUiModel` rather than being
read back off the Stage actors; the UI synchronizes the model through normal widget change
events and the observation provider reads the model only. This closes the self-observation
hole: a UI faithfully displaying the wrong model value reports `MISMATCH` with bounded
same-frame correlation evidence. `RuntimeValueRenderer` additionally exposes the bounded
`formatId` of each sealed `RuntimeValue` variant so the observation carries the runtime type
identity required by ADR 0025's compatibility gate.
```

- [ ] **Step 6: Commit**

```bash
git add harness-fixtures/src/main/java/dev/gdx/uiharness/fixtures/ReferenceUiModel.java harness-fixtures/src/main/java/dev/gdx/uiharness/fixtures/FixtureControl.java harness-fixtures/src/main/java/dev/gdx/uiharness/fixtures/ReferenceScreen.java harness-fixtures/src/test/java/dev/gdx/uiharness/fixtures/RuntimeProductionFixtureTest.java docs/adr/0025-runtime-entity-bindings.md docs/adr/0026-agent-runtime-adapter.md
git commit -m "feat(fixtures): source runtime values from an independent reference model"
```

---

### Task 6: Matrix case statuses and observed identity fields (#14)

**Files:**
- Modify: `harness-core/src/main/java/dev/gdx/uiharness/core/matrix/MatrixCaseStatus.java` (add `UNSUPPORTED`, `MISAPPLIED`)
- Modify: `harness-core/src/main/java/dev/gdx/uiharness/core/matrix/MatrixCaseResult.java` (add observed locale/font-set/restart-profile)
- Modify: `harness-lwjgl3/src/main/java/dev/gdx/uiharness/lwjgl3/Lwjgl3MatrixRunner.java` (three constructor sites pass `null` for the new fields until Task 7)
- Modify: `harness-mcp/src/main/java/dev/gdx/uiharness/mcp/HarnessToolCatalog.java` (status enum + nullable observed fields)
- Modify golden: `harness-mcp/src/test/resources/mcp/tool-catalog-v1.json` (regenerate, never hand-edit)
- Modify test: `harness-protocol/src/test/java/dev/gdx/uiharness/protocol/MatrixProtocolTest.java`
- Modify test: `harness-mcp/src/test/java/dev/gdx/uiharness/mcp/HarnessMcpServerContractTest.java`

**Interfaces:**
- Produces: `MatrixCaseStatus.UNSUPPORTED` ("the case was rejected before application because a display dimension is unsupported") and `MatrixCaseStatus.MISAPPLIED` ("the case was applied but the observed display state did not match the request"); `MatrixCaseResult` gains `String observedLocale`, `String observedFontSetId`, `String observedRestartProfileId` (nullable, bounded 256, non-blank when present except `observedFontSetId` which allows `""`); the MCP `matrix-report` schema and golden carry the two new statuses and three nullable observed fields. Consumed by Tasks 7-8.

- [ ] **Step 1: Write the failing protocol test**

In `harness-protocol/src/test/java/dev/gdx/uiharness/protocol/MatrixProtocolTest.java`, add:

```java
@Test void matrixReportCarriesAppliedAndRejectedCaseEvidence() throws Exception {
    MatrixReport report = new MatrixReport("run-1", "matrix", List.of(
            new MatrixCaseResult(
                    new dev.gdx.uiharness.core.matrix.MatrixCaseSummary(
                            0, new MatrixWindow(1280, 720), 1.0, 1.0,
                            MatrixHiDpi.LOGICAL, "en", "", 16.0 / 9.0),
                    MatrixCaseStatus.UNSUPPORTED,
                    null, null, null, null, null, null, null,
                    List.of(), List.of(), List.of(),
                    "unsupported case: unsupported devicePixelRatio: 2.0"),
            new MatrixCaseResult(
                    new dev.gdx.uiharness.core.matrix.MatrixCaseSummary(
                            1, new MatrixWindow(1280, 720), 1.0, 1.0,
                            MatrixHiDpi.LOGICAL, "en", "", 16.0 / 9.0),
                    MatrixCaseStatus.MISAPPLIED,
                    new MatrixWindow(1280, 720), 2.0, 1.0, MatrixHiDpi.LOGICAL,
                    "en", "", "desktop-restart-1280x720",
                    List.of(), List.of(), List.of(),
                    "requested state not applied: uiScale")), false);

    String json = ProtocolJson.mapper().writeValueAsString(report);

    assertEquals(report, ProtocolJson.mapper().readValue(json, MatrixReport.class));
    assertTrue(json.contains("\"status\":\"UNSUPPORTED\""));
    assertTrue(json.contains("\"status\":\"MISAPPLIED\""));
    assertTrue(json.contains("\"observedRestartProfileId\":\"desktop-restart-1280x720\""));
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :harness-protocol:test --tests 'dev.gdx.uiharness.protocol.MatrixProtocolTest' --no-daemon --console=plain --warning-mode=fail`
Expected: FAIL — `MatrixCaseStatus.UNSUPPORTED`/`MISAPPLIED` and the three `MatrixCaseResult` components do not exist (compilation error).

- [ ] **Step 3: Implement the statuses, fields, and migrate every constructor site**

`MatrixCaseStatus.java` becomes:

```java
package dev.gdx.uiharness.core.matrix;

/** Closed terminal classification of one matrix case. */
public enum MatrixCaseStatus {
    /** All carried assertions passed. */
    PASSED,
    /** At least one carried assertion failed or the case failed to start. */
    FAILED,
    /** The run terminated before this case started. */
    UNSTARTED,
    /** The case was cancelled after starting. */
    CANCELLED,
    /** The case was rejected before application because a display dimension is unsupported. */
    UNSUPPORTED,
    /** The case was applied but the observed display state did not match the request. */
    MISAPPLIED
}
```

`MatrixCaseResult.java` gains three components after `observedHiDpiMode` (record becomes 13 components) with validation in the compact constructor:

```java
if (observedLocale != null && (observedLocale.isBlank()
        || observedLocale.length() > 256)) {
    throw new IllegalArgumentException("observedLocale must be null or 1..256 characters");
}
if (observedFontSetId != null && observedFontSetId.length() > 256) {
    throw new IllegalArgumentException("observedFontSetId must be null or at most 256 characters");
}
if (observedRestartProfileId != null && (observedRestartProfileId.isBlank()
        || observedRestartProfileId.length() > 256)) {
    throw new IllegalArgumentException(
            "observedRestartProfileId must be null or 1..256 characters");
}
```

Update the Javadoc `@param` lines for the three new components.

Migrate every `MatrixCaseResult` constructor site to the 13-argument form (new fields `null` for now, completed in Task 7):

1. `Lwjgl3MatrixRunner.java` — `executeCase` UNSTARTED/CANCELLED site and FAILED site: add `null, null, null` between `observedHiDpiMode` and `passedAssertions`. `terminalCase`: add `null, null, null` after `observed.hiDpiMode()`.
2. `MatrixProtocolTest.java` — `RecordingCoordinator.results`: add `"en", "", null` after `MatrixHiDpi.LOGICAL`.
3. `HarnessMcpServerContractTest.java` — `matrixService` `MatrixCaseResult` (line ~390): add `"en", "", null` after `MatrixHiDpi.LOGICAL`.

`HarnessToolCatalog.java`:

- Line ~621: `resultProperties.put("status", enumString("PASSED", "FAILED", "UNSTARTED", "CANCELLED"));` becomes `enumString("PASSED", "FAILED", "UNSTARTED", "CANCELLED", "UNSUPPORTED", "MISAPPLIED")`.
- After the `observedHiDpiMode` entry in `matrixReportSchema`, add:

```java
resultProperties.put("observedLocale", nullableString());
resultProperties.put("observedFontSetId", nullableString());
resultProperties.put("observedRestartProfileId", nullableString());
```

- Add the helper next to `nullableEnum`:

```java
private static Map<String, Object> nullableString() {
    return Map.of("oneOf", List.of(
            string(0, MAX_IDENTIFIER), Map.of("type", "null")));
}
```

- [ ] **Step 4: Verify the catalog golden fails red, then regenerate it**

Run: `./gradlew :harness-mcp:test --tests 'dev.gdx.uiharness.mcp.HarnessToolCatalogTest' --no-daemon --console=plain --warning-mode=fail`
Expected: FAIL — `goldenCatalogMatchesTypedSchemas` reports the typed schema no longer matches `tool-catalog-v1.json` (new statuses and observed fields).

Run: `UPDATE_TOOL_CATALOG_GOLDEN=true ./gradlew :harness-mcp:test --tests 'dev.gdx.uiharness.mcp.HarnessToolCatalogTest' --no-daemon --console=plain --warning-mode=fail`
Expected: PASS — the test writes `harness-mcp/src/test/resources/mcp/tool-catalog-v1.json` and the schema/golden match.

- [ ] **Step 5: Run the affected tests to verify they pass**

Run: `./gradlew :harness-core:test --tests 'dev.gdx.uiharness.core.matrix.*' :harness-protocol:test --tests 'dev.gdx.uiharness.protocol.MatrixProtocolTest' :harness-mcp:test --tests 'dev.gdx.uiharness.mcp.HarnessToolCatalogTest' :harness-mcp:test --tests 'dev.gdx.uiharness.mcp.HarnessMcpServerContractTest' --no-daemon --console=plain --warning-mode=fail`
Expected: PASS. Note: `:harness-lwjgl3:test` also compiles with the `null` migration; run it here if the local Gradle graph requires it for `:harness-protocol:test` (it does not; the lwjgl3 check happens in Task 7).

- [ ] **Step 6: Commit**

```bash
git add harness-core/src/main/java/dev/gdx/uiharness/core/matrix/MatrixCaseStatus.java harness-core/src/main/java/dev/gdx/uiharness/core/matrix/MatrixCaseResult.java harness-lwjgl3/src/main/java/dev/gdx/uiharness/lwjgl3/Lwjgl3MatrixRunner.java harness-mcp/src/main/java/dev/gdx/uiharness/mcp/HarnessToolCatalog.java harness-mcp/src/test/resources/mcp/tool-catalog-v1.json harness-protocol/src/test/java/dev/gdx/uiharness/protocol/MatrixProtocolTest.java harness-mcp/src/test/java/dev/gdx/uiharness/mcp/HarnessMcpServerContractTest.java
git commit -m "feat(core): classify unsupported and misapplied matrix cases with observed identities"
```

---

### Task 7: Case application before scenario acquisition in the matrix runner (#14)

**Files:**
- Modify: `harness-lwjgl3/src/main/java/dev/gdx/uiharness/lwjgl3/Lwjgl3MatrixRunner.java`
- Modify test: `harness-lwjgl3/src/test/java/dev/gdx/uiharness/lwjgl3/Lwjgl3MatrixRunnerTest.java`

**Interfaces:**
- Consumes: Task 6 statuses/fields.
- Produces: `Lwjgl3MatrixRunner.MatrixCaseApplicator` (replaces `DisplayObserver`) with `ApplyResult apply(MatrixCase matrixCase, String restartProfileId)` and `void restore()`; sealed `ApplyResult permits Applied, Unsupported` where `Applied(DisplayObservation observed)` and `Unsupported(String reason)` (reason bounded 1..512); `DisplayObservation(MatrixWindow window, double uiScale, double devicePixelRatio, MatrixHiDpi hiDpiMode, String locale, String fontSetId, String restartProfileId)`. Runner sequence per case: deadline/open check → `apply` → `Unsupported` ⇒ `UNSUPPORTED` terminal (no acquisition) → requested/observed mismatch (including the restart profile vs the runner's scenario profile) ⇒ `MISAPPLIED` terminal (no acquisition, no assertions, display restored) → acquire → assertions → release → `restore` → terminal result. `DisplayObservation` gains the three identity fields validated (locale/restartProfileId non-blank ≤ 256; fontSetId ≤ 256). This task intentionally leaves `harness-fixtures` uncompilable until Task 8 rewires `FixtureControl`; verify with `:harness-lwjgl3:test` only.

- [ ] **Step 1: Write the failing tests**

In `harness-lwjgl3/src/test/java/dev/gdx/uiharness/lwjgl3/Lwjgl3MatrixRunnerTest.java`, replace the `DisplayObserver` lambda inside the `Fixture` constructor (the block starting `runner = new Lwjgl3MatrixRunner(scenarios, waits, matrixCase -> {`) with a recording applicator; delete the now-unused `final AtomicInteger observed = new AtomicInteger();` field and add:

```java
final AtomicInteger applied = new AtomicInteger();
final AtomicInteger restored = new AtomicInteger();
String unsupportedReason;
Double observedUiScaleOverride;
String observedRestartProfileOverride;
/** Host-owned active restart profile, never derived from the runner's request. */
final String hostRestartProfile = "desktop";
final Lwjgl3MatrixRunner.MatrixCaseApplicator applicator =
        new Lwjgl3MatrixRunner.MatrixCaseApplicator() {
            @Override public Lwjgl3MatrixRunner.ApplyResult apply(
                    dev.gdx.uiharness.core.matrix.MatrixCase matrixCase,
                    String restartProfileId) {
                applied.incrementAndGet();
                if (unsupportedReason != null) {
                    return new Lwjgl3MatrixRunner.ApplyResult.Unsupported(unsupportedReason);
                }
                return new Lwjgl3MatrixRunner.ApplyResult.Applied(
                        new Lwjgl3MatrixRunner.DisplayObservation(
                                matrixCase.window(),
                                observedUiScaleOverride != null
                                        ? observedUiScaleOverride : matrixCase.uiScale(),
                                matrixCase.devicePixelRatio(),
                                matrixCase.hiDpiMode(),
                                matrixCase.locale(),
                                matrixCase.fontSetId(),
                                observedRestartProfileOverride != null
                                        ? observedRestartProfileOverride
                                        : hostRestartProfile));
            }

            @Override public void restore() {
                restored.incrementAndGet();
            }
        };
```

and `runner = new Lwjgl3MatrixRunner(scenarios, waits, applicator, new Lwjgl3MatrixRunner.Scenario("matrix", 7, Map.of(), "desktop", "app", "process", "session"));`

Update the existing assertions in `matrixRunsCasesSequentiallyWithAssertionFanOutAndExactProvenance`: replace `assertEquals(2, fixture.observed.get());` with `assertEquals(2, fixture.applied.get());` and `assertEquals(2, fixture.restored.get());`, and inside the per-result loop add `assertEquals("en", result.observedLocale());` and `assertEquals("desktop", result.observedRestartProfileId());`.

Add three new tests:

```java
@Test void unsupportedCaseIsTypedSkipWithoutScenarioAcquisition() {
    try (Fixture fixture = new Fixture()) {
        fixture.unsupportedReason = "unsupported devicePixelRatio: 2.0";
        MatrixDefinition definition = new MatrixDefinition(
                1,
                "matrix",
                List.of(new MatrixWindow(1280, 720)),
                List.of(1.0),
                List.of(1.0),
                List.of(MatrixHiDpi.LOGICAL),
                List.of("en"),
                List.of(),
                List.of(new AssertionRequest(1, Locator.testId("save"),
                        new UiAssertion.Visible(), fixture.deadline())));

        String runId = fixture.runner.run(
                definition, MatrixLimits.defaults(), fixture.deadline())
                .toCompletableFuture().join();

        MatrixReport report = fixture.runner.results(runId).orElseThrow();
        var result = report.results().getFirst();
        assertEquals(MatrixCaseStatus.UNSUPPORTED, result.status());
        assertEquals(0, result.passedAssertions().size());
        assertEquals(0, result.failedAssertions().size());
        assertEquals(0, fixture.acquisitions.get());
        assertEquals(1, fixture.applied.get());
        assertEquals(0, fixture.restored.get());
        assertTrue(result.evidence().contains("devicePixelRatio"));
    }
}

@Test void requestedObservedMismatchIsDistinctTerminalWithoutAssertions() {
    try (Fixture fixture = new Fixture()) {
        fixture.observedUiScaleOverride = 2.0;
        MatrixDefinition definition = new MatrixDefinition(
                1,
                "matrix",
                List.of(new MatrixWindow(1280, 720)),
                List.of(1.0),
                List.of(1.0),
                List.of(MatrixHiDpi.LOGICAL),
                List.of("en"),
                List.of(),
                List.of(new AssertionRequest(1, Locator.testId("save"),
                        new UiAssertion.Visible(), fixture.deadline())));

        String runId = fixture.runner.run(
                definition, MatrixLimits.defaults(), fixture.deadline())
                .toCompletableFuture().join();

        MatrixReport report = fixture.runner.results(runId).orElseThrow();
        var result = report.results().getFirst();
        assertEquals(MatrixCaseStatus.MISAPPLIED, result.status());
        assertEquals(0, result.passedAssertions().size());
        assertEquals(0, fixture.acquisitions.get());
        assertEquals(1, fixture.restored.get(),
                "a misapplied case must restore the original display state");
        assertTrue(result.evidence().contains("uiScale requested=1.0 observed=2.0"));
    }
}

@Test void hostRestartProfileMismatchIsDistinctTerminalWithoutAssertions() {
    try (Fixture fixture = new Fixture()) {
        fixture.observedRestartProfileOverride = "other-profile";
        MatrixDefinition definition = new MatrixDefinition(
                1,
                "matrix",
                List.of(new MatrixWindow(1280, 720)),
                List.of(1.0),
                List.of(1.0),
                List.of(MatrixHiDpi.LOGICAL),
                List.of("en"),
                List.of(),
                List.of(new AssertionRequest(1, Locator.testId("save"),
                        new UiAssertion.Visible(), fixture.deadline())));

        String runId = fixture.runner.run(
                definition, MatrixLimits.defaults(), fixture.deadline())
                .toCompletableFuture().join();

        MatrixReport report = fixture.runner.results(runId).orElseThrow();
        var result = report.results().getFirst();
        assertEquals(MatrixCaseStatus.MISAPPLIED, result.status());
        assertEquals(0, result.passedAssertions().size());
        assertEquals(0, fixture.acquisitions.get());
        assertEquals(1, fixture.restored.get());
        assertTrue(result.evidence().contains(
                "restartProfile requested=desktop observed=other-profile"));
    }
}

@Test void expiredDeadlineMarksCasesUnstartedWithoutApplying() {
    try (Fixture fixture = new Fixture()) {
        MatrixDefinition definition = new MatrixDefinition(
                1,
                "matrix",
                List.of(new MatrixWindow(1280, 720), new MatrixWindow(1920, 1080)),
                List.of(1.0),
                List.of(1.0),
                List.of(MatrixHiDpi.LOGICAL),
                List.of("en"),
                List.of(),
                List.of());
        Deadline expired = Deadline.after(fixture.clock, Duration.ZERO);

        String runId = fixture.runner.run(
                definition, MatrixLimits.defaults(), expired)
                .toCompletableFuture().join();

        MatrixReport report = fixture.runner.results(runId).orElseThrow();
        assertEquals(2, report.results().size());
        for (var result : report.results()) {
            assertEquals(MatrixCaseStatus.UNSTARTED, result.status());
        }
        assertEquals(0, fixture.applied.get());
        assertEquals(0, fixture.restored.get());
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :harness-lwjgl3:test --tests 'dev.gdx.uiharness.lwjgl3.Lwjgl3MatrixRunnerTest' --no-daemon --console=plain --warning-mode=fail`
Expected: FAIL — `MatrixCaseApplicator`/`ApplyResult`/`Applied`/`Unsupported` do not exist and `DisplayObservation` has the wrong arity (compilation error). After the implementation compiles, the behavioral assertions for `UNSUPPORTED`/`MISAPPLIED`/no-acquisition fail on the current runner (assertions run before any observation; statuses do not exist).

- [ ] **Step 3: Implement the applicator and the apply-verify-restore sequence**

In `Lwjgl3MatrixRunner.java`:

1. Replace the `DisplayObserver` interface and `DisplayObservation` record with:

```java
/** Host-owned display-case applicator for one case. */
public interface MatrixCaseApplicator {
    /**
     * Applies the case to the real application/window state before scenario acquisition.
     *
     * <p>On failure to apply (including an expired apply deadline), the implementation must
     * restore the original display state before throwing; the runner never observes a
     * partially applied case.
     */
    ApplyResult apply(MatrixCase matrixCase, String restartProfileId);

    /** Restores the pre-case display state after the case reaches a terminal state. */
    void restore();
}

/** Closed outcome of one case application. */
public sealed interface ApplyResult permits Applied, Unsupported {
    /** The case was applied; {@code observed} holds the same-case observed settings. */
    record Applied(DisplayObservation observed) implements ApplyResult {
        /** Validates the observed settings. */
        public Applied {
            observed = Objects.requireNonNull(observed, "observed");
        }
    }

    /** The case was rejected before application with a bounded reason. */
    record Unsupported(String reason) implements ApplyResult {
        /** Validates the bounded reason. */
        public Unsupported {
            reason = Objects.requireNonNull(reason, "reason");
            if (reason.isBlank() || reason.length() > 512) {
                throw new IllegalArgumentException(
                        "unsupported reason must be 1..512 characters");
            }
        }
    }
}

/** Observed display parameters, distinct from requested parameters. */
public record DisplayObservation(
        MatrixWindow window, double uiScale, double devicePixelRatio, MatrixHiDpi hiDpiMode,
        String locale, String fontSetId, String restartProfileId) {
    /** Validates observed parameters. */
    public DisplayObservation {
        Objects.requireNonNull(window, "window");
        if (!Double.isFinite(uiScale) || uiScale <= 0.0) {
            throw new IllegalArgumentException("observed uiScale must be positive");
        }
        if (!Double.isFinite(devicePixelRatio) || devicePixelRatio <= 0.0) {
            throw new IllegalArgumentException("observed devicePixelRatio must be positive");
        }
        Objects.requireNonNull(hiDpiMode, "hiDpiMode");
        Objects.requireNonNull(locale, "locale");
        if (locale.isBlank() || locale.length() > 256) {
            throw new IllegalArgumentException(
                    "observed locale must be 1..256 characters");
        }
        Objects.requireNonNull(fontSetId, "fontSetId");
        if (fontSetId.length() > 256) {
            throw new IllegalArgumentException(
                    "observed fontSetId must be at most 256 characters");
        }
        Objects.requireNonNull(restartProfileId, "restartProfileId");
        if (restartProfileId.isBlank() || restartProfileId.length() > 256) {
            throw new IllegalArgumentException(
                    "observed restartProfileId must be 1..256 characters");
        }
    }
}
```

2. Change the field and constructor parameter `DisplayObserver display` to `MatrixCaseApplicator applicator` (field `private final MatrixCaseApplicator applicator;`, assignment `this.applicator = Objects.requireNonNull(applicator, "applicator");`).

3. Replace `executeCase` with:

```java
private CompletionStage<Void> executeCase(
        MatrixCase matrixCase, Deadline deadline, List<MatrixCaseResult> results) {
    if (deadline.isExpired() || !open) {
        results.add(new MatrixCaseResult(
                dev.gdx.uiharness.core.matrix.MatrixCaseSummary.of(matrixCase),
                deadline.isExpired() ? MatrixCaseStatus.UNSTARTED
                        : MatrixCaseStatus.CANCELLED,
                null, null, null, null, null, null, null,
                List.of(), List.of(), List.of(), ""));
        return CompletableFuture.completedFuture(null);
    }
    ApplyResult applied;
    try {
        applied = applicator.apply(matrixCase, scenario.profileId());
    } catch (RuntimeException failure) {
        results.add(new MatrixCaseResult(
                dev.gdx.uiharness.core.matrix.MatrixCaseSummary.of(matrixCase),
                MatrixCaseStatus.FAILED,
                null, null, null, null, null, null, null,
                List.of(), List.of(), List.of(),
                bounded("case application failed: " + rootMessage(failure))));
        return CompletableFuture.completedFuture(null);
    }
    if (applied instanceof ApplyResult.Unsupported unsupported) {
        results.add(new MatrixCaseResult(
                dev.gdx.uiharness.core.matrix.MatrixCaseSummary.of(matrixCase),
                MatrixCaseStatus.UNSUPPORTED,
                null, null, null, null, null, null, null,
                List.of(), List.of(), List.of(),
                bounded("unsupported case: " + unsupported.reason())));
        return CompletableFuture.completedFuture(null);
    }
    DisplayObservation observed = ((ApplyResult.Applied) applied).observed();
    String mismatch = requestedMismatch(matrixCase, observed, scenario.profileId());
    if (mismatch != null) {
        // The case was applied but does not match the request: restore the original display
        // state so the next case starts clean, then record the distinct terminal status.
        applicator.restore();
        results.add(new MatrixCaseResult(
                dev.gdx.uiharness.core.matrix.MatrixCaseSummary.of(matrixCase),
                MatrixCaseStatus.MISAPPLIED,
                observed.window(), observed.uiScale(), observed.devicePixelRatio(),
                observed.hiDpiMode(), observed.locale(), observed.fontSetId(),
                observed.restartProfileId(),
                List.of(), List.of(), List.of(),
                bounded("requested state not applied: " + mismatch)));
        return CompletableFuture.completedFuture(null);
    }
    ScenarioRequest request = new ScenarioRequest(
            dev.gdx.uiharness.core.scenario.ScenarioDefinition.SCHEMA_VERSION,
            scenario.scenarioId(),
            scenario.seed(),
            scenario.configuration(),
            scenario.profileId(),
            deadline);
    return scenarios.acquire(request, scenario.applicationId(),
            scenario.processId(), scenario.sessionId())
            .thenCompose(lease -> runAssertions(matrixCase, lease, deadline, observed))
            .handle((result, failure) -> {
                applicator.restore();
                if (failure != null) {
                    results.add(new MatrixCaseResult(
                            dev.gdx.uiharness.core.matrix.MatrixCaseSummary.of(matrixCase),
                            MatrixCaseStatus.FAILED,
                            null, null, null, null, null, null, null,
                            List.of(), List.of(), List.of(),
                            bounded(rootMessage(failure))));
                } else {
                    results.add(result);
                }
                return null;
            });
}

private static String requestedMismatch(
        MatrixCase matrixCase, DisplayObservation observed, String requestedRestartProfile) {
    if (!observed.window().equals(matrixCase.window())) {
        return "window requested=" + matrixCase.window()
                + " observed=" + observed.window();
    }
    if (!nearlyEqual(observed.uiScale(), matrixCase.uiScale())) {
        return "uiScale requested=" + matrixCase.uiScale()
                + " observed=" + observed.uiScale();
    }
    if (!nearlyEqual(observed.devicePixelRatio(), matrixCase.devicePixelRatio())) {
        return "devicePixelRatio requested=" + matrixCase.devicePixelRatio()
                + " observed=" + observed.devicePixelRatio();
    }
    if (observed.hiDpiMode() != matrixCase.hiDpiMode()) {
        return "hiDpiMode requested=" + matrixCase.hiDpiMode()
                + " observed=" + observed.hiDpiMode();
    }
    if (!observed.locale().equals(matrixCase.locale())) {
        return "locale requested=" + matrixCase.locale()
                + " observed=" + observed.locale();
    }
    if (!observed.fontSetId().equals(matrixCase.fontSetId())) {
        return "fontSetId requested=" + matrixCase.fontSetId()
                + " observed=" + observed.fontSetId();
    }
    if (!observed.restartProfileId().equals(requestedRestartProfile)) {
        return "restartProfile requested=" + requestedRestartProfile
                + " observed=" + observed.restartProfileId();
    }
    return null;
}

private static boolean nearlyEqual(double first, double second) {
    return Math.abs(first - second) <= 1e-9;
}
```

The `restartProfileId` identity participates in mismatch validation against the runner's
scenario profile: the observation must come from host-owned active state (never an echo of
the request), so a host whose active profile differs from the requested profile is a
`MISAPPLIED` terminal, not a pass.

4. Change `runAssertions` to take the observed settings and pass them into `terminalCase`:

```java
private CompletionStage<MatrixCaseResult> runAssertions(
        MatrixCase matrixCase, Scene2dScenarioRunner.Lease lease, Deadline deadline,
        DisplayObservation observed) {
    ...
    return chain.handle((ignored, assertionFailure) -> assertionFailure)
            .thenCompose(assertionFailure -> {
                CompletionStage<ScenarioResult> released;
                try {
                    released = lease.release();
                } catch (RuntimeException failure) {
                    return CompletableFuture.completedFuture(
                            terminalCase(matrixCase, passed, failed,
                                    assertionFailure, failure, observed));
                }
                return released.handle((releasedResult, releaseFailure) ->
                        terminalCase(matrixCase, passed, failed, assertionFailure,
                                releaseFailure(releasedResult, releaseFailure), observed));
            });
}
```

5. Update `terminalCase` to use the supplied observed settings and the 13-argument result:

```java
private MatrixCaseResult terminalCase(
        MatrixCase matrixCase,
        List<Integer> passed,
        List<Integer> failed,
        Throwable assertionFailure,
        Throwable releaseFailure,
        DisplayObservation observed) {
    boolean succeeded = assertionFailure == null && releaseFailure == null && failed.isEmpty();
    MatrixCaseStatus status = succeeded ? MatrixCaseStatus.PASSED : MatrixCaseStatus.FAILED;
    String evidence = "";
    if (assertionFailure != null) {
        evidence = bounded(rootMessage(assertionFailure));
        if (releaseFailure != null) {
            evidence = composeWithSuffix(evidence,
                    " (lease release failed: " + rootMessage(releaseFailure) + ")");
        }
    } else if (releaseFailure != null) {
        evidence = bounded("lease release failed: " + rootMessage(releaseFailure));
    } else if (!failed.isEmpty()) {
        evidence = "assertions failed: " + failed.size();
    }
    return new MatrixCaseResult(
            dev.gdx.uiharness.core.matrix.MatrixCaseSummary.of(matrixCase),
            status,
            observed.window(),
            observed.uiScale(),
            observed.devicePixelRatio(),
            observed.hiDpiMode(),
            observed.locale(),
            observed.fontSetId(),
            observed.restartProfileId(),
            List.copyOf(passed),
            List.copyOf(failed),
            List.of(),
            evidence);
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :harness-lwjgl3:test --tests 'dev.gdx.uiharness.lwjgl3.Lwjgl3MatrixRunnerTest' --no-daemon --console=plain --warning-mode=fail`
Expected: PASS — sequential execution applies/restores per case, unsupported cases are typed skips without acquisition, misapplied cases (including a host restart-profile mismatch) are distinct terminals without assertions and restore the display, and expired deadlines never apply.
Note: `:harness-fixtures` does not compile until Task 8 replaces the `DisplayObserver` wiring in `FixtureControl.java`; do not run the full `check` between Tasks 7 and 8.

- [ ] **Step 5: Commit**

```bash
git add harness-lwjgl3/src/main/java/dev/gdx/uiharness/lwjgl3/Lwjgl3MatrixRunner.java harness-lwjgl3/src/test/java/dev/gdx/uiharness/lwjgl3/Lwjgl3MatrixRunnerTest.java
git commit -m "feat(lwjgl3): apply and verify each matrix case before scenario acquisition"
```

---

### Task 8: Allowlisted fixture applicator, real LWJGL3 smoke, ADR 0022 (#14)

**Files:**
- Create: `harness-fixtures/src/main/java/dev/gdx/uiharness/fixtures/ReferenceCaseApplicator.java`
- Create test: `harness-fixtures/src/test/java/dev/gdx/uiharness/fixtures/ReferenceCaseApplicatorTest.java`
- Modify: `harness-fixtures/src/main/java/dev/gdx/uiharness/fixtures/FixtureControl.java` (wire `ReferenceCaseApplicator`)
- Modify: `harness-fixtures/src/main/java/dev/gdx/uiharness/fixtures/ReferenceUiApplication.java` (resizable window, bounded size limits)
- Modify test: `harness-fixtures/src/test/java/dev/gdx/uiharness/fixtures/HarnessMcpClient.java` (PIXELS default + `MatrixSpec` overload)
- Modify test: `harness-fixtures/src/test/java/dev/gdx/uiharness/fixtures/MatrixProductionFixtureTest.java` (rewrite)
- Modify: `docs/adr/0022-display-matrix-lifecycle.md` (amendment)

**Interfaces:**
- Consumes: Task 7 `MatrixCaseApplicator`/`ApplyResult`/`DisplayObservation`.
- Produces: `ReferenceCaseApplicator(RenderThreadScheduler, MonotonicClock, String restartProfileId)` (public) and `ReferenceCaseApplicator(RenderThreadScheduler, MonotonicClock, String, CaseApplication)` (package-private, injectable application step; `CaseApplication.apply(MatrixWindow, Locale, Deadline)`). The host's `restartProfileId` is the sole source of the observed restart-profile identity; a request naming a different profile is rejected as `Unsupported` before application; any apply/observe failure restores the original window and locale before propagating. Allowlist: windows `{1280×720, 1920×1080}`, locales `{en-US, fi-FI}`, uiScale `1.0`, DPR `1.0`, HiDPI `PIXELS`, font set `""`. `FixtureControl` wires it with `RESTART_PROFILE.id()`.

- [ ] **Step 1: Write the failing smoke tests**

In `HarnessMcpClient.java`, add a `MatrixSpec` record and a parametrized `runMatrix` overload, then make the existing convenience delegate with `PIXELS`:

```java
record MatrixSpec(
        List<Map<String, Integer>> windows,
        List<Double> uiScales,
        List<Double> devicePixelRatios,
        List<String> hiDpiModes,
        List<String> locales,
        List<String> fontSetIds,
        int maxCases) {}

String runMatrix(String sessionId, MatrixSpec spec, long deadlineMillis) throws Exception {
    Map<String, Object> matrixSpec = Map.ofEntries(
            Map.entry("scenarioId", "navigation"),
            Map.entry("windows", spec.windows()),
            Map.entry("uiScales", spec.uiScales()),
            Map.entry("devicePixelRatios", spec.devicePixelRatios()),
            Map.entry("hiDpiModes", spec.hiDpiModes()),
            Map.entry("locales", spec.locales()),
            Map.entry("fontSetIds", spec.fontSetIds()),
            Map.entry("assertions", List.of()),
            Map.entry("maxCases", spec.maxCases()),
            Map.entry("maxDurationMillis", deadlineMillis));
    JsonNode content = call("ui_matrix_run", Map.of(
            "sessionId", sessionId, "spec", matrixSpec, "deadlineMillis", deadlineMillis));
    requireKind(content, "matrix-run-started");
    return content.path("runId").asText();
}
```

Change the existing `runMatrix(String sessionId, long deadlineMillis)` body to call the overload with `new MatrixSpec(List.of(Map.of("width", 1280, "height", 720)), List.of(1.0), List.of(1.0), List.of("PIXELS"), List.of("en-US"), List.of(), 1)`.

Replace `harness-fixtures/src/test/java/dev/gdx/uiharness/fixtures/MatrixProductionFixtureTest.java` with:

```java
package dev.gdx.uiharness.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Real LWJGL3 display-matrix fixture: the reference process serves the production MCP server;
 * {@code ui_matrix_run} applies each case to the real window before its assertions run and
 * {@code ui_matrix_results} returns the compact retained report with exact observed settings.
 */
final class MatrixProductionFixtureTest {
    private static final String SESSION_ID = "reference-ui";
    private static final String RESTART_PROFILE = "desktop-restart-1280x720";

    @Test
    @Timeout(120)
    void matrixRunCompletesAndRetainsReportThroughProductionMcp() throws Exception {
        try (ReferenceProcess app = ReferenceProcess.launch()) {
            try (HarnessMcpClient client = HarnessMcpClient.connect(app)) {
                String runId = client.runMatrix(SESSION_ID, 5_000);
                JsonNode report = client.matrixResults(SESSION_ID, runId, 5_000);
                JsonNode data = report.path("report");
                assertEquals(runId, data.path("runId").asText());
                assertEquals("navigation", data.path("scenarioId").asText());
                assertTrue(data.path("results").size() >= 1);
                assertEquals("PASSED", data.path("results").get(0).path("status").asText());
                assertEquals(1280, data.path("results").get(0)
                        .path("observedWindow").path("width").asInt());
                assertEquals(720, data.path("results").get(0)
                        .path("observedWindow").path("height").asInt());
                assertEquals("en-US", data.path("results").get(0)
                        .path("observedLocale").asText());
                assertEquals(RESTART_PROFILE, data.path("results").get(0)
                        .path("observedRestartProfileId").asText());
            }
        }
    }

    @Test
    @Timeout(120)
    void matrixAppliesAndObservesTwoMateriallyDifferentCases() throws Exception {
        try (ReferenceProcess app = ReferenceProcess.launch()) {
            try (HarnessMcpClient client = HarnessMcpClient.connect(app)) {
                HarnessMcpClient.MatrixSpec spec = new HarnessMcpClient.MatrixSpec(
                        List.of(Map.of("width", 1280, "height", 720),
                                Map.of("width", 1920, "height", 1080)),
                        List.of(1.0), List.of(1.0), List.of("PIXELS"),
                        List.of("en-US"), List.of(), 2);

                String runId = client.runMatrix(SESSION_ID, spec, 20_000);
                JsonNode data = client.matrixResults(SESSION_ID, runId, 5_000)
                        .path("report");
                assertEquals(2, data.path("results").size());
                assertEquals("PASSED", data.path("results").get(0).path("status").asText());
                assertEquals("PASSED", data.path("results").get(1).path("status").asText());
                assertEquals(1280, data.path("results").get(0)
                        .path("observedWindow").path("width").asInt());
                assertEquals(1920, data.path("results").get(1)
                        .path("observedWindow").path("width").asInt());
                assertEquals(1080, data.path("results").get(1)
                        .path("observedWindow").path("height").asInt());
                assertEquals("en-US", data.path("results").get(0)
                        .path("observedLocale").asText());
                assertEquals(RESTART_PROFILE, data.path("results").get(0)
                        .path("observedRestartProfileId").asText());
                assertTrue(data.path("results").get(0).path("passedAssertions").isArray());
            }
        }
    }

    @Test
    @Timeout(120)
    void unsupportedCaseIsATypedSkipThroughProductionMcp() throws Exception {
        try (ReferenceProcess app = ReferenceProcess.launch()) {
            try (HarnessMcpClient client = HarnessMcpClient.connect(app)) {
                HarnessMcpClient.MatrixSpec spec = new HarnessMcpClient.MatrixSpec(
                        List.of(Map.of("width", 1280, "height", 720)),
                        List.of(1.0), List.of(2.0), List.of("PIXELS"),
                        List.of("en-US"), List.of(), 1);

                String runId = client.runMatrix(SESSION_ID, spec, 20_000);
                JsonNode result = client.matrixResults(SESSION_ID, runId, 5_000)
                        .path("report").path("results").get(0);
                assertEquals("UNSUPPORTED", result.path("status").asText());
                assertTrue(result.path("evidence").asText().contains("devicePixelRatio"));
                assertEquals(0, result.path("passedAssertions").size());
            }
        }
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :harness-fixtures:test --tests 'dev.gdx.uiharness.fixtures.MatrixProductionFixtureTest' --no-daemon --console=plain --warning-mode=fail`
Expected: FAIL — the fixture still wires the removed `DisplayObserver` (compilation), and once wiring compiles, the hard-coded observer reports `(1280,720)` for the 1920×1080 case (assertion on observed width fails) and reports `PASSED` for the DPR-2.0 case (assertion on `UNSUPPORTED` fails).

- [ ] **Step 3: Implement the fixture applicator and wire it**

Write `ReferenceCaseApplicatorTest` (below) first and run it red:

Run: `./gradlew :harness-fixtures:test --tests 'dev.gdx.uiharness.fixtures.ReferenceCaseApplicatorTest' --no-daemon --console=plain --warning-mode=fail`
Expected: FAIL — `ReferenceCaseApplicator` does not exist (compilation error). Then create the applicator class and `FixtureControl` wiring below and rerun the test (expected: PASS — the failure path restores window and locale; the unknown-profile request is rejected before application).

Create `harness-fixtures/src/test/java/dev/gdx/uiharness/fixtures/ReferenceCaseApplicatorTest.java`:

```java
package dev.gdx.uiharness.fixtures;

import com.badlogic.gdx.Gdx;
import dev.gdx.uiharness.core.matrix.MatrixCase;
import dev.gdx.uiharness.core.matrix.MatrixHiDpi;
import dev.gdx.uiharness.core.matrix.MatrixWindow;
import dev.gdx.uiharness.core.time.Deadline;
import dev.gdx.uiharness.core.time.MonotonicClock;
import dev.gdx.uiharness.lwjgl3.Lwjgl3MatrixRunner;
import dev.gdx.uiharness.scene2d.RenderThreadScheduler;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;

/** Allowlisted host-owned applicator that applies and observes one matrix case. */
public final class ReferenceCaseApplicator implements Lwjgl3MatrixRunner.MatrixCaseApplicator {
    private static final Set<MatrixWindow> ALLOWED_WINDOWS = Set.of(
            new MatrixWindow(1280, 720), new MatrixWindow(1920, 1080));
    private static final Set<String> ALLOWED_LOCALES = Set.of("en-US", "fi-FI");
    private static final MatrixWindow DEFAULT_WINDOW = new MatrixWindow(1280, 720);
    private static final Duration APPLY_DEADLINE = Duration.ofSeconds(15);

    /** One host-owned window/locale application step; injectable for failure-path tests. */
    interface CaseApplication {
        void apply(MatrixWindow window, Locale locale, Deadline deadline);
    }

    private final RenderThreadScheduler scheduler;
    private final MonotonicClock clock;
    /** The host's active restart profile; observations come from this state, never the request. */
    private final String restartProfileId;
    private final Locale originalLocale;
    private final CaseApplication caseApplication;

    /** Creates an applicator for the registered restart profile using the real window backend. */
    public ReferenceCaseApplicator(
            RenderThreadScheduler scheduler, MonotonicClock clock, String restartProfileId) {
        this(scheduler, clock, restartProfileId, null);
    }

    /** Creates an applicator with an injectable application step (package-private for tests). */
    ReferenceCaseApplicator(
            RenderThreadScheduler scheduler, MonotonicClock clock, String restartProfileId,
            CaseApplication caseApplication) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.restartProfileId = Objects.requireNonNull(restartProfileId, "restartProfileId");
        originalLocale = Locale.getDefault();
        this.caseApplication =
                caseApplication != null ? caseApplication : this::applyWindowAndLocale;
    }

    @Override
    public Lwjgl3MatrixRunner.ApplyResult apply(MatrixCase matrixCase, String profileId) {
        Objects.requireNonNull(matrixCase, "matrixCase");
        String unsupported = unsupportedReason(matrixCase, profileId);
        if (unsupported != null) {
            return new Lwjgl3MatrixRunner.ApplyResult.Unsupported(unsupported);
        }
        try {
            caseApplication.apply(matrixCase.window(),
                    Locale.forLanguageTag(matrixCase.locale()),
                    Deadline.after(clock, APPLY_DEADLINE));
            return new Lwjgl3MatrixRunner.ApplyResult.Applied(observe(matrixCase));
        } catch (RuntimeException failure) {
            // Restore any partially applied window/locale state before propagating, so the
            // next case always starts from the host-owned defaults.
            restore();
            throw failure;
        }
    }

    @Override
    public void restore() {
        try {
            caseApplication.apply(DEFAULT_WINDOW, originalLocale,
                    Deadline.after(clock, APPLY_DEADLINE));
        } catch (RuntimeException failure) {
            // Best-effort restore: the next case still starts from the host-owned defaults.
        }
    }

    private String unsupportedReason(MatrixCase matrixCase, String profileId) {
        if (!restartProfileId.equals(profileId)) {
            return "unknown restart profile: " + profileId;
        }
        if (!ALLOWED_WINDOWS.contains(matrixCase.window())) {
            return "unsupported window: " + matrixCase.window();
        }
        if (matrixCase.uiScale() != 1.0) {
            return "unsupported uiScale: " + matrixCase.uiScale();
        }
        if (matrixCase.devicePixelRatio() != 1.0) {
            return "unsupported devicePixelRatio: " + matrixCase.devicePixelRatio();
        }
        if (matrixCase.hiDpiMode() != MatrixHiDpi.PIXELS) {
            return "unsupported hiDpiMode: " + matrixCase.hiDpiMode();
        }
        if (!ALLOWED_LOCALES.contains(matrixCase.locale())) {
            return "unsupported locale: " + matrixCase.locale();
        }
        if (matrixCase.fontSetId() != null && !matrixCase.fontSetId().isEmpty()) {
            return "unsupported fontSetId: " + matrixCase.fontSetId();
        }
        return null;
    }

    private void applyWindowAndLocale(MatrixWindow window, Locale locale, Deadline deadline) {
        scheduler.submit(() -> {
                    Gdx.graphics.setWindowedMode(window.width(), window.height());
                    return null;
                },
                deadline)
                .toCompletableFuture().join();
        while (!deadline.isExpired()
                && (Gdx.graphics.getBackBufferWidth() != window.width()
                        || Gdx.graphics.getBackBufferHeight() != window.height())) {
            LockSupport.parkNanos(1_000_000L);
        }
        if (Gdx.graphics.getBackBufferWidth() != window.width()
                || Gdx.graphics.getBackBufferHeight() != window.height()) {
            throw new IllegalStateException("window resize did not complete: requested="
                    + window + " observed=" + new MatrixWindow(
                            Gdx.graphics.getBackBufferWidth(),
                            Gdx.graphics.getBackBufferHeight()));
        }
        Locale.setDefault(locale);
    }

    private Lwjgl3MatrixRunner.DisplayObservation observe(MatrixCase matrixCase) {
        int logicalWidth = Gdx.graphics.getWidth();
        int logicalHeight = Gdx.graphics.getHeight();
        return new Lwjgl3MatrixRunner.DisplayObservation(
                new MatrixWindow(logicalWidth, logicalHeight),
                1.0,
                (double) Gdx.graphics.getBackBufferWidth() / logicalWidth,
                MatrixHiDpi.PIXELS,
                Locale.getDefault().toLanguageTag(),
                matrixCase.fontSetId() == null ? "" : matrixCase.fontSetId(),
                restartProfileId);
    }
}
```

Create `harness-fixtures/src/test/java/dev/gdx/uiharness/fixtures/ReferenceCaseApplicatorTest.java`:

```java
package dev.gdx.uiharness.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.gdx.uiharness.core.matrix.MatrixCase;
import dev.gdx.uiharness.core.matrix.MatrixHiDpi;
import dev.gdx.uiharness.core.matrix.MatrixWindow;
import dev.gdx.uiharness.core.time.MonotonicClock;
import dev.gdx.uiharness.scene2d.RenderThreadScheduler;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

final class ReferenceCaseApplicatorTest {
    private final MonotonicClock clock = System::nanoTime;

    @Test
    void applyRestoresPartialWindowAndLocaleStateWhenApplicationFails() {
        Locale original = Locale.forLanguageTag("de-DE");
        Locale.setDefault(original);
        try {
            try (RenderThreadScheduler scheduler = new RenderThreadScheduler(16)) {
                AtomicBoolean first = new AtomicBoolean(true);
                List<MatrixWindow> appliedWindows = new ArrayList<>();
                ReferenceCaseApplicator.CaseApplication failing = (window, locale, deadline) -> {
                    appliedWindows.add(window);
                    // Faithfully apply the locale argument on every invocation so the restore
                    // call is observable; only the first invocation fails.
                    Locale.setDefault(locale);
                    if (first.getAndSet(false)) {
                        throw new IllegalStateException("window resize timed out");
                    }
                };
                ReferenceCaseApplicator applicator = new ReferenceCaseApplicator(
                        scheduler, clock, "desktop-restart-1280x720", failing);
                MatrixCase matrixCase = new MatrixCase(
                        0, new MatrixWindow(1920, 1080), 1.0, 1.0, MatrixHiDpi.PIXELS,
                        "en-US", "", 16.0 / 9.0, List.of());

                assertThrows(IllegalStateException.class,
                        () -> applicator.apply(matrixCase, "desktop-restart-1280x720"));
                assertEquals(List.of(new MatrixWindow(1920, 1080), new MatrixWindow(1280, 720)),
                        appliedWindows,
                        "the original window must be restored after the failure");
                assertEquals(original, Locale.getDefault(),
                        "the original locale (distinct from the requested en-US) must be "
                                + "restored after the failure");
            }
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void unknownRestartProfileIsRejectedBeforeApplication() {
        try (RenderThreadScheduler scheduler = new RenderThreadScheduler(16)) {
            ReferenceCaseApplicator.CaseApplication noop = (window, locale, deadline) -> {};
            ReferenceCaseApplicator applicator = new ReferenceCaseApplicator(
                    scheduler, clock, "host-owned-profile", noop);
            MatrixCase matrixCase = new MatrixCase(
                    0, new MatrixWindow(1280, 720), 1.0, 1.0, MatrixHiDpi.PIXELS,
                    "en-US", "", 16.0 / 9.0, List.of());

            Lwjgl3MatrixRunner.ApplyResult unknown =
                    applicator.apply(matrixCase, "other-profile");

            assertEquals("unknown restart profile: other-profile",
                    ((Lwjgl3MatrixRunner.ApplyResult.Unsupported) unknown).reason());
        }
    }
}
```

Note: a request naming a profile the host does not own is rejected as `Unsupported` before any
GL access; the applied-path observation reads the host-owned `restartProfileId` field rather
than the request parameter (structural property), is exercised end-to-end by
`MatrixProductionFixtureTest` (observed profile equals `RESTART_PROFILE`), and its mismatch
terminal is covered by `hostRestartProfileMismatchIsDistinctTerminalWithoutAssertions` in
Task 7.

In `FixtureControl.java`, replace the `matrixRunner` construction (currently lines 293-297) with:

```java
matrixRunner = new Lwjgl3MatrixRunner(
        scenarioRunner, waits,
        new ReferenceCaseApplicator(scheduler, clock, RESTART_PROFILE.id()),
        new Lwjgl3MatrixRunner.Scenario(
                "navigation", 7, Map.of(), RESTART_PROFILE.id(), APPLICATION_ID,
                PROCESS_ID, SESSION_ID));
```

In `ReferenceUiApplication.java`, change the configuration so cases can resize the window:

```java
configuration.setWindowedMode(WIDTH, HEIGHT);
configuration.setWindowSizeLimits(320, 240, 3840, 2160);
configuration.setResizable(true);
```

- [ ] **Step 4: Run the smoke tests to verify they pass**

Run: `./gradlew :harness-fixtures:test --tests 'dev.gdx.uiharness.fixtures.MatrixProductionFixtureTest' --tests 'dev.gdx.uiharness.fixtures.ReferenceCaseApplicatorTest' --tests 'dev.gdx.uiharness.fixtures.ReferenceApplicationSmokeTest' --no-daemon --console=plain --warning-mode=fail`
Expected: PASS — the failure-path applicator test restores the original window and locale, two materially different cases are applied and observed (1280×720 and 1920×1080), the DPR-2.0 case is a typed `UNSUPPORTED` skip, and the full reference workflow (screenshots, typography, layout, traces at the restored 1280×720 window) still passes.

- [ ] **Step 5: Amend ADR 0022**

Append to `docs/adr/0022-display-matrix-lifecycle.md`:

```markdown
## Amendment (2026-08-08): application, observation, and restart coordination

Every case is applied to the real application/window state before scenario acquisition and
verified before any assertion runs. A host-owned allowlisted `MatrixCaseApplicator` applies
and observes each requested dimension (window, UI scale, device pixel ratio, HiDPI mode,
locale, font set, restart profile); a requested dimension that cannot be applied produces the
closed `UNSUPPORTED` terminal status with bounded evidence, and a requested/observed mismatch
produces the distinct `MISAPPLIED` terminal status with no passing assertion result. The
observed restart profile comes from host-owned active state and is never echoed from the
request; a request naming an unowned profile is rejected as `UNSUPPORTED`. Observed settings
are captured for the same case and frame window as the assertions, the original display state
is restored deterministically after every started case (including misapplied ones and
application failures), and the Cartesian product remains preflight-bounded. `MatrixCaseResult`
now carries observed locale, font-set, and restart-profile identities.
```

- [ ] **Step 6: Commit**

```bash
git add harness-fixtures/src/main/java/dev/gdx/uiharness/fixtures/ReferenceCaseApplicator.java harness-fixtures/src/test/java/dev/gdx/uiharness/fixtures/ReferenceCaseApplicatorTest.java harness-fixtures/src/main/java/dev/gdx/uiharness/fixtures/FixtureControl.java harness-fixtures/src/main/java/dev/gdx/uiharness/fixtures/ReferenceUiApplication.java harness-fixtures/src/test/java/dev/gdx/uiharness/fixtures/HarnessMcpClient.java harness-fixtures/src/test/java/dev/gdx/uiharness/fixtures/MatrixProductionFixtureTest.java docs/adr/0022-display-matrix-lifecycle.md
git commit -m "feat(fixtures): apply and observe matrix cases through an allowlisted applicator"
```

---

## Cluster verification

Run the complete focused suite for the cluster (all module tests the cluster touched, including the real LWJGL3 fixture smoke):

```bash
./gradlew :harness-core:test :harness-agent-runtime:test :harness-lwjgl3:test :harness-protocol:test :harness-mcp:test :harness-fixtures:test --no-daemon --console=plain --warning-mode=fail
```

Run the repository gate:

```bash
./gradlew clean check javadoc --no-daemon --console=plain --warning-mode=fail
python3 scripts/validate-workflows.py
git diff --check
```

Acceptance mapping (each criterion has direct current-state evidence):

- #15: `SemanticBaselineCatalogTest` (unknown/misspelled ids rejected, conflicting replacement rejected, digest validated), `SemanticGoldensProductionFixtureTest` (typed `not-found` over MCP, no self-learning after a fill, deliberate text drift detected against the committed resource), `ReferenceBaselineDumpTest` (resource loads and matches a fresh pristine process).
- #16: `RuntimeComparatorTest` (declared/runtime format mismatch cannot report `EQUAL`; value desync reports `MISMATCH`; null declared format keeps textual equality), `AgentRuntimeObservationSourceTest`/`RuntimeValueRendererTest` (runtime type identity through the observation boundary), `RuntimeProductionFixtureTest` (model-driven `EQUAL` after a fill; `MISMATCH` for a deliberately desynchronized model).
- #14: `Lwjgl3MatrixRunnerTest` (apply before acquisition, `UNSUPPORTED` skip without acquisition, `MISAPPLIED` terminals — including a host restart-profile mismatch — without assertions and with display restore, restore after started cases, unstarted without apply), `ReferenceCaseApplicatorTest` (partial window/locale state restored on application failure; host profile governs application and observation), `MatrixProductionFixtureTest` (two materially different applied+observed cases and a typed unsupported skip through real LWJGL3), `MatrixProtocolTest`/`HarnessToolCatalogTest` (statuses and observed identities round-trip and match the regenerated golden).

Pull request body (cluster 2, from `fix/issues-14-16-semantic-truth` into `main`):

- Title: `fix: semantic truth for baselines, runtime comparison, and display matrices`
- Body: one line per issue with root cause, acceptance evidence (the tests above), and the exact gate commands, then:

```
Fixes #15
Fixes #16
Fixes #14
```

Review checklist before merge: `git diff --check`, the complete PR patch, each issue's acceptance criteria against current tests, GitHub review threads, and CI checks on the exact head SHA; merge only the reviewed SHA when all required checks pass and no actionable thread remains; verify the PR is merged and #14, #15, #16 are closed before starting cluster 3.
