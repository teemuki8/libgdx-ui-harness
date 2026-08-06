# Agent-Runtime Adapter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a publishable `harness-agent-runtime` module whose `AgentRuntimeObservationSource` implements the harness `RuntimeObservationSource` SPI over `io.github.teemuki8:agent-runtime-core:1.0.0`, with strict `UiCorrelationRegistry` frame bridging and canonical `RuntimeValue` rendering, wired into the reference application.

**Architecture:** The adapter resolves harness `RuntimeBinding(entityId, propertyId, …)` against `AgentRuntime.latestFrame()` → `FrameSnapshot.entity(EntityId)` → `EntitySnapshot.property(name)` → `RuntimeValue`, renders the sealed value canonically, and proves the harness frame through the latest recorded `UiFrameCorrelation` whose `correlationToken` matches `binding.correlationId()` and whose `runtimeFrameId` matches the frame the value came from. The reference app records one correlation per render frame and registers the username field as a runtime entity, so `ui_runtime_compare` reports a genuine EQUAL instead of a stage self-read.

**Tech Stack:** Java 25, Gradle (dependency locking + dependency verification), `io.github.teemuki8:agent-runtime-core:1.0.0` (no transitive dependencies, Java 25, class-file major 69), JUnit 5, JApiCmp.

## Global Constraints

- Java 25 toolchain, `options.release.set(25)`, `-Xlint:all` with warnings as errors, checkstyle `maxWarnings = 0`, Javadoc `Werror` — every module follows the root `build.gradle.kts` conventions.
- All verification metadata and lockfiles must stay consistent: `gradle/verification-metadata.xml` (sha256, `verify-metadata=true`) and per-project `gradle.lockfile` (`dependencyLocking.lockAllConfigurations()`).
- The new module is publishable: added to `publishableModules`; the generated `apiCompatibilityHarnessAgentRuntime` task must SKIP when no released baseline jar exists (no `--error-on-*` failure), and run normally once `apiBaselineVersion` has a released jar.
- `RuntimeObservationSource`, `RuntimeBinding`, `RuntimeObservation` come from `dev.gdx.uiharness.core.runtime` in `harness-core`; the harness core must never depend on the adapter module.
- Strict correlation only: the adapter never reads the harness clock; `revision = frame` is a documented limitation.
- TDD: every production behavior change starts with a failing test.
- Spec: `docs/superpowers/specs/2026-08-05-agent-runtime-adapter-design.md` (committed at `4f29e94`).

---

### Task 1: Scaffold the publishable module

**Files:**
- Modify: `settings.gradle.kts` (include list)
- Modify: `gradle/libs.versions.toml`
- Modify: `build.gradle.kts` (`publishableModules` list + apiCompatibility guard)
- Create: `harness-agent-runtime/build.gradle.kts`
- Create (generated): `harness-agent-runtime/gradle.lockfile`; modify `gradle/verification-metadata.xml`

**Interfaces:**
- Consumes: nothing from later tasks.
- Produces: the module `harness-agent-runtime` resolvable as `project(":harness-agent-runtime")` with `api` exposure of `harness-core` and `agent-runtime-core` to Tasks 2–3.

- [ ] **Step 1: Add the module to the build**

`settings.gradle.kts` — add `"harness-agent-runtime",` to the `include(...)` list (after `"harness-mcp"`, before `"harness-fixtures"`).

`gradle/libs.versions.toml` — add to `[versions]`: `agent-runtime = "1.0.0"`; add to `[libraries]`:
```toml
agent-runtime-core = { module = "io.github.teemuki8:agent-runtime-core", version.ref = "agent-runtime" }
```

`build.gradle.kts` — add `"harness-agent-runtime",` to `publishableModules` (after `"harness-mcp"`).

- [ ] **Step 2: Guard the new module's apiCompatibility task**

`build.gradle.kts` — the `apiCompatibilityTasks` loop currently throws inside `doFirst` when the baseline jar is missing. For `harness-agent-runtime` only, skip instead. Replace the loop body's doFirst jar check with:
```kotlin
val moduleName = moduleName // loop variable
tasks.register<JavaExec>(
    "apiCompatibility${moduleName.split('-').joinToString("") { it.replaceFirstChar(Char::uppercase) }}",
) {
    group = "verification"
    description = "Checks $moduleName against the supplied released Maven repository"
    classpath = japicmp
    mainClass.set("japicmp.JApiCmp")
    dependsOn(project(":$moduleName").tasks.named("jar"))
    doFirst {
        val baselineRepository = providers.gradleProperty("apiBaselineRepository")
            .orNull ?: throw GradleException("apiBaselineRepository is required")
        val baselineVersion = providers.gradleProperty("apiBaselineVersion")
            .orNull ?: throw GradleException("apiBaselineVersion is required")
        val oldJar = file(
            "$baselineRepository/$mavenGroupPath/$moduleName/$baselineVersion/"
                + "$moduleName-$baselineVersion.jar",
        )
        if (!oldJar.isFile) {
            if (moduleName == "harness-agent-runtime") {
                logger.lifecycle("apiCompatibility: no released baseline for $moduleName; skipping")
                return@doFirst
            }
            throw GradleException("Missing API baseline artifact: $oldJar")
        }
        val newJar = project(":$moduleName").tasks.named<Jar>("jar")
            .get().archiveFile.get().asFile
        setArgs(
            listOf(
                "--old", oldJar.absolutePath,
                "--new", newJar.absolutePath,
                "--only-modified",
                "--error-on-binary-incompatibility",
                "--error-on-source-incompatibility",
                "--ignore-missing-classes",
            ),
        )
    }
}
```
Note: the existing five modules keep the throwing behavior exactly as today.

- [ ] **Step 3: Create the module build file**

`harness-agent-runtime/build.gradle.kts`:
```kotlin
dependencies {
    api(project(":harness-core"))
    api(libs.agent.runtime.core)
}
```

- [ ] **Step 4: Refresh locks and verification metadata**

Run: `./gradlew :harness-agent-runtime:compileJava --write-locks --write-verification-metadata sha256`
Expected: `harness-agent-runtime/gradle.lockfile` created; `gradle/verification-metadata.xml` gains sha256 entries for `agent-runtime-core-1.0.0.jar` and `.module`.

If `--write-verification-metadata` is rejected, run `./gradlew --write-verification-metadata sha256 help` once instead, then re-run the compile.

- [ ] **Step 5: Verify the scaffold**

Run: `./gradlew :harness-agent-runtime:compileJava :harness-agent-runtime:checkstyleMain`
Expected: BUILD SUCCESSFUL (empty module compiles warning-free).

Run: `./gradlew test checkstyleMain checkstyleTest`
Expected: BUILD SUCCESSFUL (existing modules unaffected; new module contributes no tests yet).

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts gradle/libs.versions.toml build.gradle.kts \
  harness-agent-runtime/build.gradle.kts harness-agent-runtime/gradle.lockfile \
  gradle/verification-metadata.xml
git commit -m "build: scaffold publishable harness-agent-runtime module"
```

---

### Task 2: Canonical RuntimeValue renderer (TDD)

**Files:**
- Create: `harness-agent-runtime/src/main/java/dev/gdx/uiharness/agentruntime/RuntimeValueRenderer.java`
- Create: `harness-agent-runtime/src/test/java/dev/gdx/uiharness/agentruntime/RuntimeValueRendererTest.java`

**Interfaces:**
- Consumes: `io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValue` and nested records (`NullValue`, `BooleanValue`, `IntegerValue`, `DecimalValue`, `StringValue`, `EnumValue`, `Vector2Value`, `ListValue`, `ObjectValue`, `Field`); `RuntimeValues` factories.
- Produces: `public final class RuntimeValueRenderer` with `public static String render(RuntimeValue value)` — used by Task 3.

- [ ] **Step 1: Write the failing test**

`RuntimeValueRendererTest.java`:
```java
package dev.gdx.uiharness.agentruntime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.gdx.uiharness.agentruntime.RuntimeValueRenderer;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValue;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValues;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

final class RuntimeValueRendererTest {
    @Test void rendersEveryScalarVariantCanonically() {
        assertEquals("", RuntimeValueRenderer.render(RuntimeValues.nullValue()));
        assertEquals("true", RuntimeValueRenderer.render(RuntimeValues.bool(true)));
        assertEquals("false", RuntimeValueRenderer.render(RuntimeValues.bool(false)));
        assertEquals("-42", RuntimeValueRenderer.render(RuntimeValues.integer(-42)));
        assertEquals("0", RuntimeValueRenderer.render(RuntimeValues.decimal("0.00")));
        assertEquals("12.5", RuntimeValueRenderer.render(RuntimeValues.decimal("12.50")));
        assertEquals("Ada", RuntimeValueRenderer.render(RuntimeValues.string("Ada")));
        assertEquals("LOGIN", RuntimeValueRenderer.render(RuntimeValues.enumValue("LOGIN")));
    }

    @Test void rendersVectorAsParenthesizedDoubles() {
        assertEquals("(1.5, -2.0)",
                RuntimeValueRenderer.render(RuntimeValues.vector2(1.5, -2.0)));
    }

    @Test void rendersListsAndObjectsDeterministically() {
        assertEquals("[a, b]", RuntimeValueRenderer.render(
                RuntimeValues.list(RuntimeValues.string("a"), RuntimeValues.string("b"))));
        assertEquals("{age=3, name=Ada}", RuntimeValueRenderer.render(
                RuntimeValues.object(
                        RuntimeValues.field("name", RuntimeValues.string("Ada")),
                        RuntimeValues.field("age", RuntimeValues.integer(3)))));
    }

    @Test void truncatesDeepNestingWithMarker() {
        RuntimeValue nested = RuntimeValues.list(RuntimeValues.list(
                RuntimeValues.list(RuntimeValues.string("deep"))));
        String rendered = RuntimeValueRenderer.render(nested);
        assertEquals(256, rendered.length(), "deep output must be length-capped");
        org.junit.jupiter.api.Assertions.assertTrue(rendered.endsWith("..."),
                "truncation must carry an explicit marker");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :harness-agent-runtime:test --tests 'dev.gdx.uiharness.agentruntime.RuntimeValueRendererTest'`
Expected: FAIL — `RuntimeValueRenderer` cannot be found.

- [ ] **Step 3: Implement the renderer**

`RuntimeValueRenderer.java`:
```java
package dev.gdx.uiharness.agentruntime;

import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValue;
import java.util.ArrayList;
import java.util.List;

/** Deterministic bounded canonical rendering of agent-runtime values. */
public final class RuntimeValueRenderer {
    private static final int MAX_DEPTH = 8;
    private static final int MAX_ITEMS = 32;
    private static final int MAX_LENGTH = 256;
    private static final String TRUNCATION = "...";

    private RuntimeValueRenderer() {}

    /** Renders one value to its canonical display-comparable string. */
    public static String render(RuntimeValue value) {
        StringBuilder out = new StringBuilder();
        render(value, 0, out);
        return out.toString();
    }

    private static void render(RuntimeValue value, int depth, StringBuilder out) {
        if (value == null || depth > MAX_DEPTH || out.length() >= MAX_LENGTH) {
            if (out.length() >= MAX_LENGTH) {
                return;
            }
            out.append(TRUNCATION);
            return;
        }
        if (value instanceof RuntimeValue.NullValue) {
            return;
        }
        if (value instanceof RuntimeValue.BooleanValue b) {
            out.append(b.value());
            return;
        }
        if (value instanceof RuntimeValue.IntegerValue i) {
            out.append(i.value());
            return;
        }
        if (value instanceof RuntimeValue.DecimalValue d) {
            out.append(d.value().toPlainString());
            return;
        }
        if (value instanceof RuntimeValue.StringValue s) {
            appendBounded(s.value(), out);
            return;
        }
        if (value instanceof RuntimeValue.EnumValue e) {
            out.append(e.value());
            return;
        }
        if (value instanceof RuntimeValue.Vector2Value v) {
            out.append('(').append(v.x().value().toPlainString()).append(", ")
                    .append(v.y().value().toPlainString()).append(')');
            return;
        }
        if (value instanceof RuntimeValue.ListValue l) {
            renderSequence(l.values(), '[', ']', depth, out);
            return;
        }
        if (value instanceof RuntimeValue.ObjectValue o) {
            List<String> fields = new ArrayList<>();
            for (RuntimeValue.Field field : o.fields()) {
                StringBuilder fieldText = new StringBuilder();
                fieldText.append(field.name()).append('=');
                render(field.value(), depth + 1, fieldText);
                fields.add(fieldText.toString());
            }
            out.append('{');
            appendBounded(String.join(", ", fields), out);
            out.append('}');
        }
    }

    private static void renderSequence(
            List<RuntimeValue> values, char open, char close, int depth, StringBuilder out) {
        out.append(open);
        int shown = 0;
        for (RuntimeValue item : values) {
            if (shown >= MAX_ITEMS || out.length() >= MAX_LENGTH) {
                out.append(TRUNCATION);
                break;
            }
            if (shown > 0) {
                out.append(", ");
            }
            render(item, depth + 1, out);
            shown++;
        }
        out.append(close);
    }

    private static void appendBounded(String text, StringBuilder out) {
        int remaining = MAX_LENGTH - out.length();
        if (text.length() <= remaining) {
            out.append(text);
        } else if (remaining > TRUNCATION.length()) {
            out.append(text, 0, remaining - TRUNCATION.length()).append(TRUNCATION);
        } else {
            out.append(TRUNCATION, 0, remaining);
        }
    }
}
```
(The `depth > MAX_DEPTH` guard appends the truncation marker exactly once; the `MAX_LENGTH` guard keeps every output ≤ 256 characters with an explicit `...` marker.)

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :harness-agent-runtime:test --tests 'dev.gdx.uiharness.agentruntime.RuntimeValueRendererTest'`
Expected: all four tests PASS.

- [ ] **Step 5: Commit**

```bash
git add harness-agent-runtime/src/main/java/dev/gdx/uiharness/agentruntime/RuntimeValueRenderer.java \
  harness-agent-runtime/src/test/java/dev/gdx/uiharness/agentruntime/RuntimeValueRendererTest.java
git commit -m "feat(agent-runtime): canonical bounded RuntimeValue renderer"
```

---

### Task 3: AgentRuntimeObservationSource (TDD)

**Files:**
- Create: `harness-agent-runtime/src/main/java/dev/gdx/uiharness/agentruntime/AgentRuntimeObservationSource.java`
- Create: `harness-agent-runtime/src/test/java/dev/gdx/uiharness/agentruntime/AgentRuntimeObservationSourceTest.java`

**Interfaces:**
- Consumes: `RuntimeValueRenderer.render` (Task 2); `dev.gdx.uiharness.core.runtime.RuntimeObservationSource`, `RuntimeBinding`, `RuntimeObservation` (harness-core); agent-runtime `AgentRuntime`, `EntityId`, `EntityType`, `EntityInspector`, `RuntimeValues`, `UiFrameCorrelation`, `UiCorrelationRegistry`, `SessionId`.
- Produces: `public final class AgentRuntimeObservationSource implements RuntimeObservationSource` with constructor `(AgentRuntime runtime, String uiSessionId)`; used by Task 4.

- [ ] **Step 1: Write the failing test**

`AgentRuntimeObservationSourceTest.java`:
```java
package dev.gdx.uiharness.agentruntime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gdx.uiharness.core.runtime.RuntimeBinding;
import dev.gdx.uiharness.core.runtime.RuntimeObservation;
import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityId;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityType;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValues;
import io.github.teemuki8.libgdx.agent.runtime.core.SessionId;
import io.github.teemuki8.libgdx.agent.runtime.core.UiFrameCorrelation;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class AgentRuntimeObservationSourceTest {
    private static final String UI_SESSION = "ui-session";
    private static final String CORRELATION = "corr-token";

    private AgentRuntime runtime;

    private AgentRuntime newRuntime() {
        runtime = AgentRuntime.builder().sessionId(new SessionId("test")).build();
        runtime.start();
        return runtime;
    }

    @AfterEach void closeRuntime() {
        if (runtime != null) {
            runtime.close();
        }
    }

    private void registerUserEntity(String value) {
        runtime.entities().register(
                EntityId.of("user"), EntityType.of("user"), () -> "User",
                inspector -> inspector.property("name", () -> RuntimeValues.string(value)));
    }

    private void completeFrameAndRecordCorrelation(long harnessFrame) {
        runtime.beginFrame(16_000_000L);
        runtime.endFrame();
        var frame = runtime.latestFrame().orElseThrow().frameId();
        runtime.uiCorrelations().recordFrame(new UiFrameCorrelation(
                runtime.currentEpoch(), frame, UI_SESSION,
                Optional.of(Long.toString(harnessFrame)), Optional.of(CORRELATION)));
    }

    @Test void observesBoundPropertyWithCorrelatedHarnessFrame() {
        newRuntime();
        registerUserEntity("Ada");
        completeFrameAndRecordCorrelation(42);

        AgentRuntimeObservationSource source =
                new AgentRuntimeObservationSource(runtime, UI_SESSION);
        Optional<RuntimeObservation> observation = source.observe(
                new RuntimeBinding("user", "name", null, null, CORRELATION));

        assertTrue(observation.isPresent());
        assertEquals("Ada", observation.orElseThrow().value());
        assertEquals(42L, observation.orElseThrow().frame());
        assertEquals(42L, observation.orElseThrow().revision());
    }

    @Test void emptyWhenNoCorrelationMatches() {
        newRuntime();
        registerUserEntity("Ada");
        completeFrameAndRecordCorrelation(42);

        AgentRuntimeObservationSource source =
                new AgentRuntimeObservationSource(runtime, UI_SESSION);
        assertTrue(source.observe(new RuntimeBinding(
                "user", "name", null, null, "other-token")).isEmpty());
    }

    @Test void emptyWhenCorrelationPredatesTheValueFrame() {
        newRuntime();
        registerUserEntity("Ada");
        completeFrameAndRecordCorrelation(42);
        runtime.beginFrame(16_000_000L);
        runtime.endFrame(); // frame advances without a fresh correlation

        AgentRuntimeObservationSource source =
                new AgentRuntimeObservationSource(runtime, UI_SESSION);
        assertTrue(source.observe(new RuntimeBinding(
                "user", "name", null, null, CORRELATION)).isEmpty());
    }

    @Test void emptyWhenEntityOrPropertyMissing() {
        newRuntime();
        completeFrameAndRecordCorrelation(42);

        AgentRuntimeObservationSource source =
                new AgentRuntimeObservationSource(runtime, UI_SESSION);
        assertTrue(source.observe(new RuntimeBinding(
                "missing", "name", null, null, CORRELATION)).isEmpty());
        assertTrue(source.observe(new RuntimeBinding(
                "user", "missing", null, null, CORRELATION)).isEmpty());
    }

    @Test void emptyWhenNoCompletedFrameExists() {
        runtime = AgentRuntime.builder().sessionId(new SessionId("test")).build();
        runtime.start();
        AgentRuntimeObservationSource source =
                new AgentRuntimeObservationSource(runtime, UI_SESSION);
        assertTrue(source.observe(new RuntimeBinding(
                "user", "name", null, null, CORRELATION)).isEmpty());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :harness-agent-runtime:test --tests 'dev.gdx.uiharness.agentruntime.AgentRuntimeObservationSourceTest'`
Expected: FAIL — `AgentRuntimeObservationSource` cannot be found.

- [ ] **Step 3: Implement the source**

`AgentRuntimeObservationSource.java`:
```java
package dev.gdx.uiharness.agentruntime;

import dev.gdx.uiharness.core.runtime.RuntimeBinding;
import dev.gdx.uiharness.core.runtime.RuntimeObservation;
import dev.gdx.uiharness.core.runtime.RuntimeObservationSource;
import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityId;
import io.github.teemuki8.libgdx.agent.runtime.core.FrameSnapshot;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValue;
import io.github.teemuki8.libgdx.agent.runtime.core.UiFrameCorrelation;
import java.util.Objects;
import java.util.Optional;

/**
 * ADR 0025 SPI realization over an {@link AgentRuntime}. Values are read from the latest
 * completed runtime frame; the harness frame is proven strictly through the recorded
 * {@link UiFrameCorrelation} whose correlation token matches the binding and whose runtime
 * frame equals the frame the value came from. {@code revision} mirrors the proven harness
 * frame because the correlation bridge carries only a frame identifier.
 */
public final class AgentRuntimeObservationSource implements RuntimeObservationSource {
    private static final int CORRELATION_PAGE_LIMIT = 64;

    private final AgentRuntime runtime;
    private final String uiSessionId;

    /** Creates a source bound to one runtime and UI session. */
    public AgentRuntimeObservationSource(AgentRuntime runtime, String uiSessionId) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.uiSessionId = Objects.requireNonNull(uiSessionId, "uiSessionId");
    }

    @Override public boolean available(RuntimeBinding binding) {
        return runtime.latestFrame()
                .flatMap(frame -> frame.entity(EntityId.of(binding.entityId())))
                .flatMap(entity -> entity.property(binding.propertyId()))
                .isPresent();
    }

    @Override public Optional<RuntimeObservation> observe(RuntimeBinding binding) {
        Optional<FrameSnapshot> latest = runtime.latestFrame();
        if (latest.isEmpty()) {
            return Optional.empty();
        }
        FrameSnapshot frame = latest.orElseThrow();
        RuntimeValue value = frame.entity(EntityId.of(binding.entityId()))
                .flatMap(entity -> entity.property(binding.propertyId()))
                .orElse(null);
        if (value == null) {
            return Optional.empty();
        }
        Optional<Long> harnessFrame = correlatedHarnessFrame(frame.frameId(), binding.correlationId());
        if (harnessFrame.isEmpty()) {
            return Optional.empty();
        }
        long provenFrame = harnessFrame.orElseThrow();
        return Optional.of(new RuntimeObservation(
                binding.entityId(),
                binding.propertyId(),
                provenFrame,
                provenFrame,
                RuntimeValueRenderer.render(value),
                binding.valueFormatId()));
    }

    private Optional<Long> correlatedHarnessFrame(
            io.github.teemuki8.libgdx.agent.runtime.core.FrameId runtimeFrameId,
            String correlationToken) {
        if (correlationToken == null) {
            return Optional.empty();
        }
        UiFrameCorrelation best = null;
        for (UiFrameCorrelation correlation : runtime.uiCorrelations()
                .framesForUiSession(uiSessionId, CORRELATION_PAGE_LIMIT).items()) {
            if (!correlationToken.equals(correlation.correlationToken().orElse(null))) {
                continue;
            }
            if (!correlation.runtimeFrameId().equals(runtimeFrameId)) {
                continue;
            }
            if (best == null || correlation.runtimeFrameId().compareTo(best.runtimeFrameId()) > 0) {
                best = correlation;
            }
        }
        return best == null || best.uiFrameId().isEmpty()
                ? Optional.empty()
                : parseFrame(best.uiFrameId().orElseThrow());
    }

    private static Optional<Long> parseFrame(String uiFrameId) {
        try {
            long frame = Long.parseLong(uiFrameId);
            return frame >= 0 ? Optional.of(frame) : Optional.empty();
        } catch (NumberFormatException malformed) {
            return Optional.empty();
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :harness-agent-runtime:test --tests 'dev.gdx.uiharness.agentruntime.AgentRuntimeObservationSourceTest'`
Expected: all five tests PASS. If `runtime.close()` is not idempotent or `AgentRuntime.builder()` requires more, adapt `newRuntime()` using the failures as evidence — the builder requires only `sessionId` for a headless runtime.

- [ ] **Step 5: Verify the module gate**

Run: `./gradlew :harness-agent-runtime:test :harness-agent-runtime:checkstyleMain :harness-agent-runtime:checkstyleTest :harness-agent-runtime:javadoc`
Expected: BUILD SUCCESSFUL (checkstyle `maxWarnings = 0`, Javadoc `Werror`).

- [ ] **Step 6: Commit**

```bash
git add harness-agent-runtime/src/main/java/dev/gdx/uiharness/agentruntime/AgentRuntimeObservationSource.java \
  harness-agent-runtime/src/test/java/dev/gdx/uiharness/agentruntime/AgentRuntimeObservationSourceTest.java
git commit -m "feat(agent-runtime): RuntimeObservationSource over AgentRuntime with strict frame correlation"
```

---

### Task 4: Reference-app integration

**Files:**
- Modify: `harness-fixtures/src/main/java/dev/gdx/uiharness/fixtures/FixtureControl.java`
- Test: `harness-fixtures/src/test/java/dev/gdx/uiharness/fixtures/RuntimeProductionFixtureTest.java` (existing, must stay green)

**Interfaces:**
- Consumes: `AgentRuntimeObservationSource` (Task 3); harness `RuntimeBinding` with `correlationId` `"reference-ui-frame"` already set by `ReferenceScreen.attachSemantics`; the fixture's `stage`, `clock`, `sceneSession`, and the Session's runtime compare coordinator slot.
- Produces: the production `ui_runtime_compare` path where EQUAL is proven through a real `AgentRuntime`.

- [ ] **Step 1: Construct and wire the runtime in FixtureControl**

In `FixtureControl(Stage stage, Path newProcessRoot)`:
- Add fields `private final io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime agentRuntime;`
- Where the current inline `RuntimeObservationSource runtimeSource = binding -> { var field = stage.getRoot().findActor("username"); ... }` lambda is defined, replace it with:
```java
RuntimeObservationSource runtimeSource =
        new AgentRuntimeObservationSource(agentRuntime, SESSION_ID);
```
- Before that, construct the runtime (after `sceneSession` exists, on the render thread):
```java
agentRuntime = io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime.builder()
        .sessionId(new io.github.teemuki8.libgdx.agent.runtime.core.SessionId(SESSION_ID))
        .build();
agentRuntime.start();
agentRuntime.entities().register(
        io.github.teemuki8.libgdx.agent.runtime.core.EntityId.of("reference-ui-user"),
        io.github.teemuki8.libgdx.agent.runtime.core.EntityType.of("user"),
        () -> "Reference UI user",
        inspector -> inspector.property("value", () -> {
            var field = stage.getRoot().findActor("username");
            return io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValues.string(
                    field instanceof com.badlogic.gdx.scenes.scene2d.ui.TextField usernameField
                            ? usernameField.getText() : "");
        }));
```
Note: `stage` is already a field of `FixtureControl`. Register the entity AFTER `stage` is assigned and after `agentRuntime.start()`; both inside the constructor. Add the entity registration next to the existing runtime coordinator block so the ordering stays readable.

- [ ] **Step 2: Record one correlation per frame**

In `afterDraw()`, after `sceneSession.completedFrame(...)`:
```java
agentRuntime.beginFrame(FIXED_STEP_NANOS);
agentRuntime.endFrame();
var completedFrame = agentRuntime.latestFrame().orElseThrow().frameId();
agentRuntime.uiCorrelations().recordFrame(
        new io.github.teemuki8.libgdx.agent.runtime.core.UiFrameCorrelation(
                agentRuntime.currentEpoch(), completedFrame, SESSION_ID,
                java.util.Optional.of(Long.toString(clock.frame())),
                java.util.Optional.of("reference-ui-frame")));
```
`FIXED_STEP` is `private static final Duration FIXED_STEP = Duration.ofMillis(16)` (line 144 of `FixtureControl.java`); use `FIXED_STEP.toNanos()`. If `agentRuntime` is not started (e.g. construction order), guard with a null check or ensure `start()` runs before the first `afterDraw()`.

- [ ] **Step 3: Close the runtime on shutdown**

In the close chain (where `failure = closeResource(replacementCoordinator, failure);` and friends are listed), add:
```java
failure = closeResource(agentRuntime, failure);
```
(`AgentRuntime` implements `AutoCloseable`.)

- [ ] **Step 4: Run the production fixture**

Run: `./gradlew :harness-fixtures:test --tests 'dev.gdx.uiharness.fixtures.RuntimeProductionFixtureTest'`
Expected: PASS — `EQUAL` with `entityId=reference-ui-user`, `propertyId=value`, `displayedValue="Ada"`, `runtimeValue="Ada"`, now sourced from the `AgentRuntime` entity registry.

If the assertion fails, inspect the failure message: a non-EQUAL status means the correlation or frame bridge is off — verify the recorded correlation's token/frame against the binding (`reference-ui-frame`), and that the comparator's snapshot frame equals the correlation's harness frame (both should be `clock.frame()` of the same render cycle).

- [ ] **Step 5: Commit**

```bash
git add harness-fixtures/src/main/java/dev/gdx/uiharness/fixtures/FixtureControl.java
git commit -m "feat(fixtures): serve ui_runtime_compare from a real AgentRuntime"
```

---

### Task 5: ADR 0026 and docs

**Files:**
- Create: `docs/adr/0026-agent-runtime-adapter.md`

**Interfaces:**
- Consumes: nothing; documents Tasks 1–4.

- [ ] **Step 1: Write the ADR**

`docs/adr/0026-agent-runtime-adapter.md` — follow the existing ADR format (see `0025-runtime-entity-bindings.md`): Status Accepted, Context, Decision, Consequences. Content:
- Context: the ADR 0025 SPI is provider-agnostic; the reference app previously read values back off the Stage, which cannot prove runtime state. `io.github.teemuki8:agent-runtime-core:1.0.0` is the authoritative runtime provider.
- Decision: new publishable `harness-agent-runtime` module; `AgentRuntimeObservationSource` resolves `EntityId`/property from `latestFrame()`; canonical bounded `RuntimeValueRenderer`; strict frame correlation via `UiCorrelationRegistry` (token + runtime frame match), `revision = frame` limitation documented; apiCompatibility gate skips until a released baseline exists.
- Consequences: `ui_runtime_compare` EQUAL now proves a real runtime value; applications must record one `UiFrameCorrelation` per render frame; STALE is unreachable through this source (empty observation → UNAVAILABLE); the module is a 6th publishable artifact.

- [ ] **Step 2: Commit**

```bash
git add docs/adr/0026-agent-runtime-adapter.md
git commit -m "docs(adr): agent-runtime adapter module (0026)"
```

---

### Task 6: Full gate and pull request

**Files:**
- None (verification only).

- [ ] **Step 1: Run the full gate**

Run: `./gradlew test checkstyleMain checkstyleTest`
Expected: BUILD SUCCESSFUL across all modules including `harness-agent-runtime` and the fixtures.

- [ ] **Step 2: Verify the apiCompatibility guard**

Run: `./gradlew apiCompatibility -PapiBaselineRepository=/nonexistent -PapiBaselineVersion=1.0.0`
Expected: the five existing modules fail with the missing-baseline error (unchanged behavior); `apiCompatibilityHarnessAgentRuntime` logs "no released baseline ... skipping" without failing. To confirm the skip in isolation:
Run: `./gradlew apiCompatibilityHarnessAgentRuntime -PapiBaselineRepository=/nonexistent -PapiBaselineVersion=1.0.0`
Expected: BUILD SUCCESSFUL with the skip log line.

- [ ] **Step 3: Verify publishability**

Run: `./gradlew verifyPublishedLicenseFiles`
Expected: BUILD SUCCESSFUL — every published JAR (including `harness-agent-runtime`) carries `META-INF/LICENSE`.
Run: `./gradlew stageRelease -PreleaseVersion=1.1.0 -Prelease -PapiBaselineRepository=/nonexistent`
Expected: fails on missing release secrets (environment) — do not fix; note that the module is staged like the others. If secrets exist in the environment, instead run `./gradlew verifyCentralStaging -PreleaseVersion=1.1.0 -Prelease` and expect success with `harness-agent-runtime` staged and no fixture/benchmark leakage.

- [ ] **Step 4: Branch, push, PR**

```bash
git checkout -b feat/agent-runtime-adapter
git push -u origin feat/agent-runtime-adapter
gh pr create --title "Add publishable agent-runtime adapter for the #38 runtime SPI" --body "..."
```
PR body: summary of Tasks 1–5, verification commands and results from Steps 1–3, note that `RuntimeProductionFixtureTest` now proves EQUAL through a real `AgentRuntime`, and the apiCompatibility baseline guard rationale.

- [ ] **Step 5: Merge after green CI**

```bash
gh pr merge <number> --squash --delete-branch
git fetch origin && git merge --ff-only origin/main
```
If the local delete-branch step fails because `main` is checked out elsewhere, merge via the API result and reconcile with `git branch --delete <branch>` after the fast-forward, per the repo's established pattern.

---

## Self-Review Notes

- Spec coverage: module + dependency (Task 1), adapter API + strict correlation + revision limitation (Task 3), canonical renderer (Task 2), reference-app integration (Task 4), publishable + apiCompatibility guard (Task 1), ADR (Task 5), acceptance criteria 1–7 map to Tasks 1, 2, 3, 4, 6, 1, 5 respectively.
- Placeholder scan: no TBD/TODO; every code step carries full source.
- Type consistency: `AgentRuntimeObservationSource(AgentRuntime, String)` matches Task 3 → Task 4; `RuntimeValueRenderer.render(RuntimeValue)` matches Task 2 → Task 3; `RuntimeObservation(entityId, propertyId, frame, revision, value, valueFormatId)` matches the harness record; the `UiFrameCorrelation` constructor argument order `(epoch, frameId, uiSessionId, uiFrameId, correlationToken)` matches the verified record definition.
