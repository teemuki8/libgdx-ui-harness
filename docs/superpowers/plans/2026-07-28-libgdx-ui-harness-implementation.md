# libGDX UI Harness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver a standalone Java 25 library that gives agents and Java tests deterministic, Playwright-grade semantic inspection and control of Scene2D/Scene2D.UI applications on LWJGL3 desktop.

**Architecture:** A backend-neutral semantic core owns immutable snapshots, locators, waits, errors, and traces. Scene2D and LWJGL3 adapters confine live game objects and OpenGL work to the render thread; a versioned protocol and thin MCP adapter expose bounded typed operations without leaking libGDX types.

**Tech Stack:** Java 25, Gradle 9.6.1 Kotlin DSL, libGDX 1.14.2, JUnit 6.1.2, Jackson 2.22.1, MCP Java SDK 2.0.0, LWJGL3, JaCoCo, Maven Central publishing.

## Global Constraints

- V1 supports LWJGL3 desktop and Scene2D/Scene2D.UI only.
- Use JDK 25; published artifacts must not use preview or incubator APIs.
- Group/package root is `dev.gdx.uiharness`; Gradle group is `dev.gdx`.
- No libGDX or backend type may cross into `harness-core` or `harness-protocol` public models.
- Every Stage/Actor access and framebuffer capture runs on the owning render thread.
- Locators are lazy, re-resolve for every operation, and are strict by default.
- Actions use libGDX input dispatch; tests may not prove actions by invoking listeners directly.
- Timeouts use an injected monotonic clock. Synchronization must not use sleeps.
- Remote inputs and outputs are bounded; MCP exposes no reflection, script evaluation, arbitrary method invocation, or filesystem reads.
- New behavior follows red-green-refactor and ends with a real executable scenario.
- Dependency direction is `harness-mcp -> harness-protocol -> harness-core <- harness-scene2d <- harness-lwjgl3`; cycles fail the build.

## Planned repository structure

```text
build.gradle.kts                       shared Java/JUnit/publishing conventions
settings.gradle.kts                    module graph and repositories
gradle/libs.versions.toml              pinned dependency versions
gradle/wrapper/*                       Gradle 9.6.1 wrapper
config/checkstyle/checkstyle.xml       deterministic source rules
harness-core/                          semantic models, queries, waits, traces
harness-scene2d/                       Stage/Actor extraction and input
harness-lwjgl3/                        render completion and screenshots
harness-protocol/                      versioned JSON commands/results/errors
harness-mcp/                           official MCP SDK adapter and server
harness-fixtures/                      controlled Scene2D reference application
benchmarks/                            Playwright-parity task corpus and runner
docs/adr/                              lasting architecture decisions
docs/guides/                           installation and agent usage
.github/workflows/ci.yml               cross-platform verification
.github/workflows/release.yml          signed Maven Central publication
```

---

### Task 1: Reproducible multi-module build

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle/libs.versions.toml`
- Create: `gradle/wrapper/gradle-wrapper.properties`
- Create: `gradle/wrapper/gradle-wrapper.jar` via the wrapper task
- Create: `gradlew`
- Create: `gradlew.bat`
- Create: `.gitignore`
- Create: `config/checkstyle/checkstyle.xml`
- Create: `harness-core/build.gradle.kts`
- Create: `harness-scene2d/build.gradle.kts`
- Create: `harness-lwjgl3/build.gradle.kts`
- Create: `harness-protocol/build.gradle.kts`
- Create: `harness-mcp/build.gradle.kts`
- Create: `harness-fixtures/build.gradle.kts`
- Create: `benchmarks/build.gradle.kts`
- Test: `harness-core/src/test/java/dev/gdx/uiharness/core/BuildContractTest.java`

**Interfaces:**
- Consumes: JDK 25 and Gradle 9.6.1.
- Produces: the module names and dependency direction in Global Constraints; all later tasks use package root `dev.gdx.uiharness`.

- [ ] **Step 1: Generate the wrapper and module directories**

Run:

```bash
gradle wrapper --gradle-version 9.6.1 --distribution-type bin
mkdir -p harness-{core,scene2d,lwjgl3,protocol,mcp,fixtures}/src/{main,test}/java benchmarks/src/{main,test}/java config/checkstyle
```

Expected: `./gradlew --version` reports Gradle 9.6.1 and JVM 25.

- [ ] **Step 2: Write the failing build contract test**

```java
package dev.gdx.uiharness.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

final class BuildContractTest {
    @Test void runsOnJava25() {
        assertEquals(25, Runtime.version().feature());
    }
}
```

- [ ] **Step 3: Configure the build**

Set `rootProject.name = "libgdx-ui-harness"`, include all seven modules, pin `gdx=1.14.2`, `junit=6.1.2`, `jackson=2.22.1`, and `mcp=2.0.0` in the version catalog. Apply `java-library`, `checkstyle`, `jacoco`, `maven-publish`, and signing conventions to publishable modules. Configure Java toolchains and `options.release = 25`, UTF-8, `-Xlint:all`, JUnit Platform, reproducible archives, sources JARs, and Javadocs.

Dependency edges:

```kotlin
// harness-scene2d
api(project(":harness-core"))
implementation(libs.gdx)

// harness-lwjgl3
api(project(":harness-scene2d"))
implementation(libs.gdx.backend.lwjgl3)

// harness-protocol
api(project(":harness-core"))
implementation(libs.jackson.databind)

// harness-mcp
implementation(project(":harness-protocol"))
implementation(libs.mcp)
```

- [ ] **Step 4: Verify the complete empty-module build**

Run: `./gradlew clean check --warning-mode=fail`

Expected: `BUILD SUCCESSFUL`; `BuildContractTest` passes; no dependency cycle or warning is emitted.

- [ ] **Step 5: Commit**

```bash
git add .gitignore build.gradle.kts settings.gradle.kts gradle gradlew gradlew.bat config harness-*/build.gradle.kts benchmarks/build.gradle.kts harness-core/src/test
git commit -m "build: establish Java 25 module boundaries"
```

### Task 2: Immutable semantic and error contracts

**Files:**
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/model/Bounds.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/model/Role.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/model/SemanticState.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/model/SemanticNode.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/model/SemanticSnapshot.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/limits/HarnessLimits.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/error/ErrorCode.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/error/ErrorEvidence.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/error/HarnessException.java`
- Test: `harness-core/src/test/java/dev/gdx/uiharness/core/model/SemanticSnapshotTest.java`
- Test: `harness-core/src/test/java/dev/gdx/uiharness/core/limits/HarnessLimitsTest.java`

**Interfaces:**
- Consumes: no runtime dependency outside the JDK.
- Produces: `SemanticSnapshot(long revision, long frame, String rootId, Map<String, SemanticNode> nodes)`, `HarnessLimits.defaults()`, and typed `HarnessException` used by every later module.

- [ ] **Step 1: Write failing immutability and limit tests**

```java
@Test void snapshotRejectsMissingChildAndCannotBeMutated() {
    var root = node("root", null, List.of("missing"));
    assertThrows(IllegalArgumentException.class,
        () -> new SemanticSnapshot(1, 2, "root", Map.of("root", root)));
}

@Test void limitsRejectOversizedSnapshotBeforePublication() {
    var limits = new HarnessLimits(2, 8, 10, 16_384, 1_048_576, Duration.ofSeconds(5));
    assertEquals(ErrorCode.LIMIT_EXCEEDED,
        assertThrows(HarnessException.class,
            () -> limits.validateNodeCount(3)).code());
}
```

- [ ] **Step 2: Verify RED**

Run: `./gradlew :harness-core:test --tests '*SemanticSnapshotTest' --tests '*HarnessLimitsTest'`

Expected: compilation fails because the model and limit types do not exist.

- [ ] **Step 3: Implement the minimal records and invariants**

Use defensive `List.copyOf`/`Map.copyOf`; validate unique IDs, one root, valid parent/child references, non-negative finite bounds, and bounded strings. Define `SemanticState` with meaningful nullable states represented as `Optional<Boolean>` rather than conflating unsupported with false.

```java
public record SemanticNode(
    String id, String parentId, List<String> childIds, Role role,
    String accessibleName, String text, String label, String testId,
    String actorName, String actorType, SemanticState state,
    Bounds localBounds, Bounds stageBounds, Bounds screenBounds,
    int zIndex, Map<String, String> properties) { }
```

- [ ] **Step 4: Verify GREEN and public API shape**

Run: `./gradlew :harness-core:test :harness-core:javadoc --warning-mode=fail`

Expected: all core tests pass and Javadocs build without warnings.

- [ ] **Step 5: Commit**

```bash
git add harness-core
git commit -m "feat(core): define bounded semantic snapshots"
```

### Task 3: Lazy strict locator engine

**Files:**
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/locator/TextMatch.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/locator/Locator.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/locator/LocatorFilter.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/locator/LocatorEngine.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/locator/QueryResult.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/locator/StrictResolution.java`
- Test: `harness-core/src/test/java/dev/gdx/uiharness/core/locator/LocatorEngineTest.java`
- Test: `harness-core/src/test/java/dev/gdx/uiharness/core/locator/StrictResolutionTest.java`

**Interfaces:**
- Consumes: `SemanticSnapshot`, `SemanticNode`, `HarnessLimits`, `HarnessException`.
- Produces: `QueryResult query(SemanticSnapshot, Locator)` and `SemanticNode resolveStrict(SemanticSnapshot, Locator)`.

- [ ] **Step 1: Write failing behavior tests**

```java
@Test void roleAndNameResolveAfterActorReplacement() {
    Locator locator = Locator.role(Role.BUTTON).withName(TextMatch.exact("Save"));
    assertEquals("old", engine.resolveStrict(snapshotWithButton("old"), locator).id());
    assertEquals("new", engine.resolveStrict(snapshotWithButton("new"), locator).id());
}

@Test void strictnessFailureListsDiscriminatingCandidates() {
    HarnessException error = assertThrows(HarnessException.class,
        () -> engine.resolveStrict(twoSaveButtons(), Locator.text(TextMatch.exact("Save"))));
    assertEquals(ErrorCode.STRICTNESS_VIOLATION, error.code());
    assertEquals(2, error.evidence().candidates().size());
}
```

Cover role/name, label, exact/case-insensitive/substring/regex text, test ID, actor name/type, child/descendant/parent/sibling, `has`, state, index, Unicode whitespace normalization, deterministic document order, zero-match, multiple-match, and result limits.

- [ ] **Step 2: Verify RED**

Run: `./gradlew :harness-core:test --tests '*LocatorEngineTest' --tests '*StrictResolutionTest'`

Expected: compilation fails because locator APIs do not exist.

- [ ] **Step 3: Implement the sealed locator AST and evaluator**

```java
public sealed interface Locator permits RoleLocator, TextLocator, TestIdLocator,
        ActorLocator, RelationLocator, FilteredLocator, IndexedLocator {
    Locator filter(LocatorFilter filter);
    Locator descendant(Locator child);
}

public interface LocatorEngine {
    QueryResult query(SemanticSnapshot snapshot, Locator locator);
    SemanticNode resolveStrict(SemanticSnapshot snapshot, Locator locator);
}
```

Compile regex patterns when locators are constructed, cap input/pattern lengths, traverse nodes iteratively, and stop after `maxMatches + 1` to prove overflow without allocating the full result.

- [ ] **Step 4: Verify GREEN**

Run: `./gradlew :harness-core:test --tests 'dev.gdx.uiharness.core.locator.*'`

Expected: all locator cases pass; no sleeps, backend types, or mutable handles exist.

- [ ] **Step 5: Commit**

```bash
git add harness-core/src/main/java/dev/gdx/uiharness/core/locator harness-core/src/test/java/dev/gdx/uiharness/core/locator
git commit -m "feat(core): add lazy strict locator engine"
```

### Task 4: Scene2D semantic extraction

**Files:**
- Create: `harness-scene2d/src/main/java/dev/gdx/uiharness/scene2d/Scene2dSession.java`
- Create: `harness-scene2d/src/main/java/dev/gdx/uiharness/scene2d/Scene2dSnapshotter.java`
- Create: `harness-scene2d/src/main/java/dev/gdx/uiharness/scene2d/ActorMetadata.java`
- Create: `harness-scene2d/src/main/java/dev/gdx/uiharness/scene2d/Semantics.java`
- Create: `harness-scene2d/src/main/java/dev/gdx/uiharness/scene2d/ActorSemanticAdapter.java`
- Create: `harness-scene2d/src/main/java/dev/gdx/uiharness/scene2d/ActorAdapterRegistry.java`
- Create: `harness-scene2d/src/main/java/dev/gdx/uiharness/scene2d/BuiltinWidgetAdapters.java`
- Create: `harness-scene2d/src/main/java/dev/gdx/uiharness/scene2d/CoordinateMapper.java`
- Test: `harness-scene2d/src/test/java/dev/gdx/uiharness/scene2d/Scene2dSnapshotterTest.java`
- Test: `harness-scene2d/src/test/java/dev/gdx/uiharness/scene2d/BuiltinWidgetAdaptersTest.java`

**Interfaces:**
- Consumes: libGDX `Stage`/`Actor` internally and core semantic models externally.
- Produces: `SemanticSnapshot snapshot(Stage stage, long revision, long frame)`; `Semantics.setTestId(Actor, String)` and other metadata setters; `ActorAdapterRegistry.register(Class<A>, ActorSemanticAdapter<? super A>)`.

- [ ] **Step 1: Write failing Stage fixture tests**

```java
@Test void extractsTransformedNestedActorIntoAllCoordinateSpaces() {
    Group parent = transformedGroup(20, 30, 2f);
    TextButton save = button("Save", 5, 7, 80, 30);
    parent.addActor(save);
    stage.addActor(parent);

    SemanticNode node = snapshotter.snapshot(stage, 1, 1).nodes().values().stream()
        .filter(n -> n.accessibleName().equals("Save"))
        .findFirst().orElseThrow();

    assertEquals(Role.BUTTON, node.role());
    assertEquals(new Bounds(30, 44, 160, 60), node.stageBounds());
}
```

Add fixtures for Label, TextButton, CheckBox, TextField, SelectBox, Slider, List, ScrollPane, Window, Dialog, nested clipping, invisible parent, disabled/touchable actor, keyboard/scroll focus, duplicate names, explicit metadata override, z-order, and custom adapter properties.

- [ ] **Step 2: Verify RED**

Run: `./gradlew :harness-scene2d:test --tests '*Scene2dSnapshotterTest' --tests '*BuiltinWidgetAdaptersTest'`

Expected: compilation fails because snapshot and metadata adapters do not exist.

- [ ] **Step 3: Implement deterministic extraction**

Traverse from `stage.getRoot()` in child order, calculate effective visibility/alpha/clipping, use actor/stage/screen coordinate conversion on reusable vectors, and publish immutable core records only after complete validation. Store metadata in a private actor-keyed weak identity map owned by the session; explicit metadata wins over inferred widget semantics.

```java
@FunctionalInterface
public interface ActorSemanticAdapter<A extends Actor> {
    void contribute(A actor, SemanticNodeBuilder target);
}
```

Keep `SemanticNodeBuilder` package-private so adapter output still passes limits and validation.

- [ ] **Step 4: Verify GREEN and dependency isolation**

Run: `./gradlew :harness-scene2d:test :harness-core:dependencies --configuration runtimeClasspath`

Expected: Scene2D tests pass; `harness-core` runtime classpath contains no libGDX artifact.

- [ ] **Step 5: Commit**

```bash
git add harness-scene2d
git commit -m "feat(scene2d): extract semantic actor snapshots"
```

### Task 5: Render-thread scheduler and deterministic waits

**Files:**
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/time/MonotonicClock.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/time/Deadline.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/wait/FrameSignal.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/wait/WaitCondition.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/wait/WaitEngine.java`
- Create: `harness-scene2d/src/main/java/dev/gdx/uiharness/scene2d/RenderThreadScheduler.java`
- Create: `harness-scene2d/src/main/java/dev/gdx/uiharness/scene2d/ControlledStageClock.java`
- Test: `harness-core/src/test/java/dev/gdx/uiharness/core/wait/WaitEngineTest.java`
- Test: `harness-scene2d/src/test/java/dev/gdx/uiharness/scene2d/RenderThreadSchedulerTest.java`
- Test: `harness-scene2d/src/test/java/dev/gdx/uiharness/scene2d/ControlledStageClockTest.java`

**Interfaces:**
- Consumes: fresh `SemanticSnapshot` suppliers and core locators.
- Produces: `CompletionStage<T> submit(Callable<T>, Deadline)`, `WaitResult await(Locator, WaitCondition, Deadline)`, and `void advance(Duration)` for owned fixtures.

- [ ] **Step 1: Write failing scheduling and virtual-time tests**

```java
@Test void timeoutUsesMonotonicVirtualTimeWithoutSleeping() {
    FakeClock clock = new FakeClock();
    FakeFrameSignal frames = new FakeFrameSignal(clock);
    Deadline deadline = Deadline.after(clock, Duration.ofMillis(100));

    HarnessException error = assertThrows(HarnessException.class,
        () -> waits.await(locator, WaitCondition.visible(), deadline));

    assertEquals(Duration.ofMillis(100), error.evidence().elapsed());
    assertEquals(ErrorCode.TIMEOUT, error.code());
}

@Test void queuedStageReadExecutesOnRenderThread() {
    assertEquals(renderThread, scheduler.submit(Thread::currentThread, deadline).toCompletableFuture().join());
}
```

Test queue-time deadline expiry, cancellation before start, atomic completion after dispatch, session disposal, frame signals, fixed delta, and snapshot re-resolution after each frame.

- [ ] **Step 2: Verify RED**

Run: `./gradlew :harness-core:test --tests '*WaitEngineTest' :harness-scene2d:test --tests '*RenderThreadSchedulerTest' --tests '*ControlledStageClockTest'`

Expected: compilation fails because scheduling/time APIs do not exist.

- [ ] **Step 3: Implement without polling sleeps**

`RenderThreadScheduler` enqueues bounded commands and drains them from a render-loop hook. `WaitEngine` evaluates once, subscribes to `FrameSignal`, and evaluates again only after a revision/frame change. `ControlledStageClock.advance` calls `stage.act(fixedDelta)` in deterministic increments and emits frame signals.

- [ ] **Step 4: Verify GREEN and thread confinement**

Run: `./gradlew :harness-core:test :harness-scene2d:test`

Expected: all tests pass under a test timeout of 10 seconds; no `Thread.sleep` occurs in main or test sources.

- [ ] **Step 5: Commit**

```bash
git add harness-core/src/main/java/dev/gdx/uiharness/core/{time,wait} harness-core/src/test/java/dev/gdx/uiharness/core/wait harness-scene2d
git commit -m "feat: add render-thread scheduling and deterministic waits"
```

### Task 6: Actionability and faithful input actions

**Files:**
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/action/Action.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/action/Actionability.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/action/ActionabilityCheck.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/action/ActionResult.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/action/Harness.java`
- Create: `harness-scene2d/src/main/java/dev/gdx/uiharness/scene2d/Scene2dActionability.java`
- Create: `harness-scene2d/src/main/java/dev/gdx/uiharness/scene2d/Scene2dInputDispatcher.java`
- Create: `harness-scene2d/src/main/java/dev/gdx/uiharness/scene2d/Scene2dHarness.java`
- Test: `harness-core/src/test/java/dev/gdx/uiharness/core/action/ActionabilityTest.java`
- Test: `harness-scene2d/src/test/java/dev/gdx/uiharness/scene2d/Scene2dInputDispatcherTest.java`
- Test: `harness-scene2d/src/test/java/dev/gdx/uiharness/scene2d/Scene2dActionEndToEndTest.java`

**Interfaces:**
- Consumes: locator engine, scheduler, waits, snapshots, Stage hit testing.
- Produces: Java façade methods `click`, `hover`, `focus`, `fill`, `press`, `scroll`, `drag`, `pointer`; each returns `ActionResult` with before/after revisions.

- [ ] **Step 1: Write failing actionability transition tests**

```java
@Test void clickWaitsForStableUnobscuredButtonThenUsesStageInput() {
    fixture.cover("save");
    CompletionStage<ActionResult> click = harness.click(byRole(Role.BUTTON, "Save"), deadline);
    fixture.nextFrame();
    fixture.uncover("save");
    fixture.nextFrame();
    assertFalse(click.toCompletableFuture().isDone());
    fixture.nextFrame();

    assertEquals("saved", click.toCompletableFuture().join().observedState());
    assertEquals(List.of("touchDown", "touchUp"), fixture.inputLog());
}
```

Cover detached/replaced actor, invisible/disabled/untouchable, moving bounds, clipped/offscreen, overlapping sibling, descendant hit target, keyboard focus, text replacement, scroll focus, drag pointer capture, force semantics, and timeout evidence.

- [ ] **Step 2: Verify RED**

Run: `./gradlew :harness-core:test --tests '*ActionabilityTest' :harness-scene2d:test --tests '*Scene2dInputDispatcherTest' --tests '*Scene2dActionEndToEndTest'`

Expected: compilation fails because action APIs do not exist.

- [ ] **Step 3: Implement the action pipeline**

```java
public interface Harness {
    CompletionStage<ActionResult> perform(Locator locator, Action action, Deadline deadline);
    CompletionStage<SemanticSnapshot> snapshot(Deadline deadline);
}
```

Resolve, check strictness, wait, re-resolve, validate `stage.hit`, dispatch coordinates through the configured `InputProcessor`, wait for a completed post-input frame, and return revisions/evidence. `force` bypasses visibility/stability/hit checks only; it does not bypass strictness, deadlines, or render-thread confinement.

- [ ] **Step 4: Verify GREEN with the complete Scene2D fixture**

Run: `./gradlew :harness-core:test :harness-scene2d:test`

Expected: all action scenarios pass repeatedly with virtual frames and no timing sleeps.

- [ ] **Step 5: Commit**

```bash
git add harness-core/src/main/java/dev/gdx/uiharness/core/action harness-core/src/test/java/dev/gdx/uiharness/core/action harness-scene2d
git commit -m "feat(scene2d): add actionability and input actions"
```

### Task 7: Completed-frame LWJGL3 screenshots

**Files:**
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/capture/CaptureRequest.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/capture/CapturedImage.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/capture/ScreenCapture.java`
- Create: `harness-lwjgl3/src/main/java/dev/gdx/uiharness/lwjgl3/Lwjgl3FrameFence.java`
- Create: `harness-lwjgl3/src/main/java/dev/gdx/uiharness/lwjgl3/Lwjgl3ScreenCapture.java`
- Create: `harness-lwjgl3/src/main/java/dev/gdx/uiharness/lwjgl3/PngEncoder.java`
- Test: `harness-lwjgl3/src/test/java/dev/gdx/uiharness/lwjgl3/Lwjgl3ScreenCaptureTest.java`
- Test: `harness-lwjgl3/src/test/java/dev/gdx/uiharness/lwjgl3/ActorCropTest.java`

**Interfaces:**
- Consumes: post-render frame signal, screen-space semantic bounds, limits.
- Produces: `CompletionStage<CapturedImage> capture(CaptureRequest, Deadline)` with PNG bytes, SHA-256, frame/revision, dimensions, and scale.

- [ ] **Step 1: Write failing real-frame tests**

```java
@Test void capturesTopLeftOrientedActorCropAfterRenderedFrame() {
    fixture.renderQuadrants();
    CapturedImage image = capture.capture(CaptureRequest.actor(byTestId("top-left")), deadline).join();
    assertEquals(32, image.width());
    assertEquals(32, image.height());
    assertEquals(RED, decode(image).getRGB(0, 0));
}
```

Also test full-window dimensions, viewport scaling, framebuffer Y-flip, bounds clipping, empty/offscreen crop error, maximum pixels, render-thread use, and capture after an action’s completed frame.

- [ ] **Step 2: Verify RED**

Run: `xvfb-run -a ./gradlew :harness-lwjgl3:test --tests '*Lwjgl3ScreenCaptureTest' --tests '*ActorCropTest'`

Expected: compilation fails because capture APIs do not exist.

- [ ] **Step 3: Implement capture**

Read RGBA pixels on the graphics thread after the frame fence, flip rows into conventional top-left orientation, crop with checked integer math, encode PNG into a bounded byte array, and compute SHA-256 while writing. Reject impossible/oversized geometry before allocation.

- [ ] **Step 4: Verify GREEN on a real LWJGL3 context**

Run: `xvfb-run -a ./gradlew :harness-lwjgl3:test`

Expected: all screenshot tests pass and the process exits without leaked windows or native threads.

- [ ] **Step 5: Commit**

```bash
git add harness-core/src/main/java/dev/gdx/uiharness/core/capture harness-lwjgl3
git commit -m "feat(lwjgl3): capture completed UI frames"
```

### Task 8: Versioned bounded protocol

**Files:**
- Create: `harness-protocol/src/main/java/dev/gdx/uiharness/protocol/ProtocolVersion.java`
- Create: `harness-protocol/src/main/java/dev/gdx/uiharness/protocol/HarnessRequest.java`
- Create: `harness-protocol/src/main/java/dev/gdx/uiharness/protocol/HarnessResponse.java`
- Create: `harness-protocol/src/main/java/dev/gdx/uiharness/protocol/Command.java`
- Create: `harness-protocol/src/main/java/dev/gdx/uiharness/protocol/ProtocolError.java`
- Create: `harness-protocol/src/main/java/dev/gdx/uiharness/protocol/CapabilitySet.java`
- Create: `harness-protocol/src/main/java/dev/gdx/uiharness/protocol/HarnessProtocolService.java`
- Create: `harness-protocol/src/main/java/dev/gdx/uiharness/protocol/ProtocolJson.java`
- Test: `harness-protocol/src/test/java/dev/gdx/uiharness/protocol/ProtocolJsonContractTest.java`
- Test: `harness-protocol/src/test/java/dev/gdx/uiharness/protocol/HarnessProtocolServiceTest.java`
- Test: `harness-protocol/src/test/resources/contracts/v1/*.json`

**Interfaces:**
- Consumes: core harness operations and immutable models.
- Produces: protocol V1 commands for sessions, capabilities, snapshot, query, action, wait, screenshot, trace start/stop; `CompletionStage<HarnessResponse> execute(HarnessRequest)`.

- [ ] **Step 1: Write failing JSON golden-contract tests**

```java
@Test void v1ClickRequestRoundTripsWithoutTypeMetadata() throws Exception {
    String json = resource("contracts/v1/click-request.json");
    HarnessRequest request = ProtocolJson.mapper().readValue(json, HarnessRequest.class);
    assertEquals(new ProtocolVersion(1, 0), request.version());
    assertInstanceOf(Command.Action.class, request.command());
    assertEquals(canonicalJson(json), canonicalJson(ProtocolJson.mapper().writeValueAsString(request)));
}
```

Test every command/result/error, unknown fields, malformed unions, version mismatch, deadline bounds, payload limits, stack/path redaction, request IDs, and cancellation.

- [ ] **Step 2: Verify RED**

Run: `./gradlew :harness-protocol:test --tests '*ProtocolJsonContractTest' --tests '*HarnessProtocolServiceTest'`

Expected: compilation fails because protocol types do not exist.

- [ ] **Step 3: Implement explicit schema mapping**

Use Jackson with an allowlisted sealed command hierarchy and explicit stable discriminator names. Disable default typing. Configure maximum nesting depth, number length, string length, and input bytes before deserialization. Translate every `HarnessException` to `ProtocolError`; unexpected exceptions become redacted `internal-error` with a trace ID.

- [ ] **Step 4: Verify GREEN and dependency boundary**

Run: `./gradlew :harness-protocol:test :harness-protocol:javadoc :harness-protocol:dependencies --configuration runtimeClasspath`

Expected: contract goldens pass; protocol depends on core/Jackson but not libGDX, LWJGL, or MCP.

- [ ] **Step 5: Commit**

```bash
git add harness-protocol
git commit -m "feat(protocol): define bounded versioned UI commands"
```

### Task 9: Typed MCP server adapter

**Files:**
- Create: `harness-mcp/src/main/java/dev/gdx/uiharness/mcp/HarnessMcpServer.java`
- Create: `harness-mcp/src/main/java/dev/gdx/uiharness/mcp/HarnessToolCatalog.java`
- Create: `harness-mcp/src/main/java/dev/gdx/uiharness/mcp/HarnessToolHandler.java`
- Create: `harness-mcp/src/main/java/dev/gdx/uiharness/mcp/ArtifactReference.java`
- Create: `harness-mcp/src/main/java/dev/gdx/uiharness/mcp/Main.java`
- Test: `harness-mcp/src/test/java/dev/gdx/uiharness/mcp/HarnessToolCatalogTest.java`
- Test: `harness-mcp/src/test/java/dev/gdx/uiharness/mcp/HarnessMcpServerContractTest.java`
- Test: `harness-mcp/src/test/resources/mcp/tool-catalog-v1.json`

**Interfaces:**
- Consumes: `HarnessProtocolService` and MCP Java SDK 2.0.0.
- Produces: stdio MCP server with exactly `ui_sessions`, `ui_snapshot`, `ui_query`, `ui_action`, `ui_wait`, `ui_screenshot`, `ui_trace_start`, `ui_trace_stop`, and `ui_capabilities`.

- [ ] **Step 1: Write failing tool-catalog and end-to-end tests**

```java
@Test void exposesOnlyTheApprovedBoundedTools() {
    assertEquals(Set.of("ui_sessions", "ui_snapshot", "ui_query", "ui_action",
        "ui_wait", "ui_screenshot", "ui_trace_start", "ui_trace_stop",
        "ui_capabilities"), catalog.toolNames());
}

@Test void clickToolReturnsStructuredActionResult() {
    McpCallResult result = client.call("ui_action", clickArguments("Save"));
    assertFalse(result.isError());
    assertEquals("action-result", result.structuredContent().get("kind").asText());
}
```

Test invalid schemas, tool cancellation, capability discovery, compact snapshot summaries, artifact references for large outputs, and absence of arbitrary execution/path parameters.

- [ ] **Step 2: Verify RED**

Run: `./gradlew :harness-mcp:test --tests '*HarnessToolCatalogTest' --tests '*HarnessMcpServerContractTest'`

Expected: compilation fails because MCP adapter types do not exist.

- [ ] **Step 3: Implement the thin adapter**

Declare MCP schemas from protocol DTO schemas, convert each call to one `HarnessRequest`, and return protocol results without duplicating locator/action logic. Run connection handling on virtual threads. Default to stdio; loopback network serving remains disabled unless explicitly configured.

- [ ] **Step 4: Verify GREEN by launching the server process**

Run:

```bash
./gradlew :harness-mcp:test :harness-mcp:installDist
printf '%s\n' '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"smoke","version":"1.0.0"}}}' |
  timeout 10 harness-mcp/build/install/harness-mcp/bin/harness-mcp
```

Expected: contract tests pass; the process emits a JSON-RPC initialize result for request `1` and exits after stdin closes.

- [ ] **Step 5: Commit**

```bash
git add harness-mcp
git commit -m "feat(mcp): expose typed UI harness tools"
```

### Task 10: Trace and artifact lifecycle

**Files:**
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/trace/TraceEvent.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/trace/TraceRecorder.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/trace/TraceManifest.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/trace/TraceReplayer.java`
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/trace/TraceReplay.java`
- Create: `harness-protocol/src/main/java/dev/gdx/uiharness/protocol/ArtifactStore.java`
- Create: `harness-protocol/src/main/java/dev/gdx/uiharness/protocol/ArtifactId.java`
- Create: `harness-protocol/src/main/java/dev/gdx/uiharness/protocol/ArtifactMediaType.java`
- Create: `harness-protocol/src/main/java/dev/gdx/uiharness/protocol/FileArtifactStore.java`
- Test: `harness-core/src/test/java/dev/gdx/uiharness/core/trace/TraceRecorderTest.java`
- Test: `harness-core/src/test/java/dev/gdx/uiharness/core/trace/TraceReplayerTest.java`
- Test: `harness-protocol/src/test/java/dev/gdx/uiharness/protocol/FileArtifactStoreTest.java`

**Interfaces:**
- Consumes: request IDs, command/result/error records, snapshots, input events, screenshots.
- Produces: bounded ZIP trace with `manifest.json`, newline-delimited events, and deduplicated artifacts by SHA-256; `TraceReplay load(Path)`; opaque `ArtifactId` values with session-scoped reads and cleanup.

- [ ] **Step 1: Write failing trace lifecycle tests**

```java
@Test void recordsCausalBeforeAndAfterEvidenceAndReplaysTransitions() {
    recorder.start(sessionId, limits);
    recorder.commandStarted(request, before);
    recorder.inputDispatched(input);
    recorder.commandCompleted(result, after);
    TraceManifest manifest = recorder.stop();

    TraceReplay replay = replayer.load(manifest.archive());
    assertEquals(List.of(before.revision(), after.revision()), replay.semanticRevisions());
    assertTrue(replay.causality().isValid());
}

@Test void expiredArtifactCannotEscapeItsSession() {
    ArtifactId id = store.put(sessionA, ArtifactMediaType.PNG, bytes,
        clock.instant().plusSeconds(5));
    assertThrows(HarnessException.class, () -> store.read(sessionB, id));
    clock.advance(Duration.ofSeconds(6));
    assertThrows(HarnessException.class, () -> store.read(sessionA, id));
}
```

Cover size/duration limits, redaction, duplicate image hashes, interrupted trace finalization, causal parents, cleanup on session disposal, path traversal IDs, and partial replay diagnostics.

- [ ] **Step 2: Verify RED**

Run: `./gradlew :harness-core:test --tests '*Trace*' :harness-protocol:test --tests '*ArtifactStoreTest'`

Expected: compilation fails because trace/artifact APIs do not exist.

- [ ] **Step 3: Implement streaming bounded traces**

Stream NDJSON and artifact bytes; never retain a complete trace in memory. Use random opaque artifact IDs mapped to server-owned normalized paths, atomic temp-file finalization, SHA-256 deduplication, per-session quotas, expiry, and recursive cleanup only below the configured artifact root.

- [ ] **Step 4: Verify GREEN and resource cleanup**

Run: `./gradlew :harness-core:test :harness-protocol:test`

Expected: trace/replay tests pass, temporary directories are empty after each test, and limit failures preserve a readable partial manifest.

- [ ] **Step 5: Commit**

```bash
git add harness-core/src/main/java/dev/gdx/uiharness/core/trace harness-core/src/test/java/dev/gdx/uiharness/core/trace harness-protocol
git commit -m "feat: record bounded reproducible UI traces"
```

### Task 11: Reference application and end-to-end agent scenario

**Files:**
- Create: `harness-fixtures/src/main/java/dev/gdx/uiharness/fixtures/ReferenceUiApplication.java`
- Create: `harness-fixtures/src/main/java/dev/gdx/uiharness/fixtures/ReferenceScreen.java`
- Create: `harness-fixtures/src/main/java/dev/gdx/uiharness/fixtures/FixtureControl.java`
- Create: `harness-fixtures/src/test/java/dev/gdx/uiharness/fixtures/ReferenceApplicationSmokeTest.java`
- Create: `harness-fixtures/src/test/java/dev/gdx/uiharness/fixtures/ReferenceProcess.java`
- Create: `harness-fixtures/src/test/java/dev/gdx/uiharness/fixtures/HarnessMcpClient.java`
- Create: `harness-fixtures/src/test/resources/golden/reference-semantic.json`
- Create: `harness-fixtures/src/test/resources/golden/reference-screen.png`
- Modify: `harness-mcp/src/test/java/dev/gdx/uiharness/mcp/HarnessMcpServerContractTest.java`

**Interfaces:**
- Consumes: all library modules.
- Produces: a deterministic sign-in/settings/list/dialog fixture and a process-level scenario proving MCP inspect → locate → act → assert → screenshot → trace.

- [ ] **Step 1: Write the failing smoke scenario**

```java
@Test void agentCanCompleteReferenceWorkflowThroughMcp() {
    try (ReferenceProcess app = ReferenceProcess.launch();
         HarnessMcpClient agent = HarnessMcpClient.connect(app.mcpEndpoint())) {
        agent.call("ui_action", fill("Username", "Ada"));
        agent.call("ui_action", fill("Password", "correct horse"));
        agent.call("ui_action", click(Role.BUTTON, "Sign in"));
        assertEquals("Welcome, Ada", agent.query(text("Welcome, Ada")).single().text());
        assertTrue(agent.screenshot().width() > 0);
        assertTrue(agent.stopTrace().events() >= 6);
    }
}
```

Run the scenario five times in one JVM to expose lifecycle/flakiness defects.

- [ ] **Step 2: Verify RED**

Run: `xvfb-run -a ./gradlew :harness-fixtures:test --tests '*ReferenceApplicationSmokeTest'`

Expected: compilation fails because the reference application/process does not exist.

- [ ] **Step 3: Implement the reference application and MCP attachment**

Use a fixed 1280×720 window, fixed assets checked into test resources, deterministic clock, named Stage session, built-in widgets, overlapping/transformed actors, scroll content, a modal dialog, and dynamic actor replacement after sign-in. Start MCP over stdio or an authenticated loopback test transport chosen by the MCP SDK test fixture; do not expose a production unauthenticated port.

- [ ] **Step 4: Verify GREEN end to end**

Run: `xvfb-run -a ./gradlew :harness-fixtures:test --tests '*ReferenceApplicationSmokeTest' --rerun-tasks`

Expected: five consecutive workflows pass; each process closes its window, MCP transport, virtual threads, and artifact directory.

- [ ] **Step 5: Commit**

```bash
git add harness-fixtures harness-mcp/src/test
git commit -m "test: prove MCP-driven Scene2D workflow"
```

### Task 12: Playwright parity benchmark

**Files:**
- Create: `benchmarks/src/main/java/dev/gdx/uiharness/benchmarks/BenchmarkRunner.java`
- Create: `benchmarks/src/main/java/dev/gdx/uiharness/benchmarks/BenchmarkScenario.java`
- Create: `benchmarks/src/main/java/dev/gdx/uiharness/benchmarks/BenchmarkResult.java`
- Create: `benchmarks/src/main/java/dev/gdx/uiharness/benchmarks/Statistics.java`
- Create: `benchmarks/src/test/java/dev/gdx/uiharness/benchmarks/StatisticsTest.java`
- Create: `benchmarks/corpus/scenarios.json`
- Create: `benchmarks/playwright/package.json`
- Create: `benchmarks/playwright/package-lock.json`
- Create: `benchmarks/playwright/playwright.config.ts`
- Create: `benchmarks/playwright/reference.spec.ts`
- Create: `benchmarks/README.md`

**Interfaces:**
- Consumes: the reference app, MCP tools, and a semantically equivalent local Playwright reference page.
- Produces: JSON/CSV results for completion, timeout, tool calls, actionable evidence, repeatability over 20 runs, and trace bytes; non-zero exit when agreed parity thresholds fail.

- [ ] **Step 1: Write failing statistics and threshold tests**

```java
@Test void failsParityWhenHarnessCompletionFallsBelowPlaywright() {
    BenchmarkResult harness = result(18, 20, 1, 0.95, 8);
    BenchmarkResult playwright = result(20, 20, 0, 1.00, 7);
    assertFalse(Statistics.meetsParity(harness, playwright).passed());
}

@Test void acceptsEqualCompletionAndDiagnosticsWithoutFlakeIncrease() {
    BenchmarkResult harness = result(20, 20, 0, 1.00, 6);
    BenchmarkResult playwright = result(20, 20, 0, 1.00, 7);
    assertTrue(Statistics.meetsParity(harness, playwright).passed());
}
```

Define the initial corpus: sign-in, ambiguous locator recovery, delayed enablement, moving target, obscured target, scroll-and-select, modal dialog, actor replacement, screenshot diagnosis, and intentional failure trace.

- [ ] **Step 2: Verify RED**

Run: `./gradlew :benchmarks:test --tests '*StatisticsTest'`

Expected: compilation fails because benchmark result/statistics types do not exist.

- [ ] **Step 3: Implement reproducible runners**

Pin Playwright and browser versions in `package-lock.json`; use identical text/role/test-ID semantics and fixed logical delays. Persist raw per-run records before aggregation. Define parity as: harness completion rate and actionable-evidence rate are each at least Playwright’s; harness timeout/flaky-failure rate is no greater than Playwright’s plus a two-sided 95% Wilson interval tolerance; median tool calls are reported but do not alone fail V1.

- [ ] **Step 4: Run the 20× corpus and inspect raw evidence**

Run:

```bash
npm ci --prefix benchmarks/playwright
xvfb-run -a ./gradlew :benchmarks:run --args='--runs 20 --output build/reports/parity'
```

Expected: the runner emits raw JSON, aggregate CSV, and a machine-readable verdict. Before V1 release, verdict must be PASS; during earlier implementation, a FAIL is retained as measured backlog rather than suppressed.

- [ ] **Step 5: Commit**

```bash
git add benchmarks
git commit -m "bench: measure Playwright semantic parity"
```

### Task 13: CI, API compatibility, security checks, and publication

**Files:**
- Create: `.github/workflows/ci.yml`
- Create: `.github/workflows/release.yml`
- Create: `docs/adr/0001-layered-semantic-harness.md`
- Create: `docs/adr/0002-java-25-baseline.md`
- Create: `docs/guides/getting-started.md`
- Create: `docs/guides/agent-tools.md`
- Create: `docs/guides/semantic-metadata.md`
- Create: `harness-fixtures/src/test/java/dev/gdx/uiharness/fixtures/PublicApiExampleTest.java`
- Create: `harness-fixtures/src/test/java/dev/gdx/uiharness/fixtures/FixtureHarness.java`
- Modify: `build.gradle.kts`
- Modify: publishable module build files

**Interfaces:**
- Consumes: all completed modules and parity report.
- Produces: repeatable Linux/Windows/macOS checks, public API compatibility gate, signed Maven artifacts, and compilable usage documentation.

- [ ] **Step 1: Write a failing compiled public API example**

```java
@Test void documentedJavaFlowCompilesAndRuns() {
    try (FixtureHarness fixture = FixtureHarness.start()) {
        Harness ui = fixture.harness();
        ui.perform(Locator.role(Role.BUTTON).withName(TextMatch.exact("Save")),
            Action.click(), Deadline.after(fixture.clock(), Duration.ofSeconds(2)))
          .toCompletableFuture().join();
        assertEquals("saved", fixture.state());
    }
}
```

Copy this exact flow into `getting-started.md`; the test remains the source of truth.

- [ ] **Step 2: Add CI and compatibility gates**

Linux runs `xvfb-run -a ./gradlew clean check` and the parity smoke subset. Windows and macOS run `./gradlew clean check` including native LWJGL3 smoke tests. Add binary/source API comparison against the latest release after the first release tag, dependency locking, dependency verification, CodeQL, and artifact retention for failed traces/screenshots.

- [ ] **Step 3: Configure publication and write operational docs**

Publish `harness-core`, `harness-scene2d`, `harness-lwjgl3`, `harness-protocol`, and `harness-mcp`; do not publish fixtures/benchmarks. Require signed tags, Maven Central credentials, in-memory PGP keys, sources, Javadocs, license metadata, SCM coordinates, and staging verification. Document installation, session lifecycle, semantic metadata, all nine MCP tools, limits, trace artifacts, and explicit V1 non-goals.

- [ ] **Step 4: Run the release candidate gate**

Run:

```bash
xvfb-run -a ./gradlew clean check javadoc publishToMavenLocal --warning-mode=fail
./gradlew --offline clean check
```

Expected: all suites and examples pass; Javadocs have no warnings; Maven-local POMs contain correct dependencies; the locked offline build succeeds; no fixture/benchmark artifact is published.

- [ ] **Step 5: Commit**

```bash
git add .github build.gradle.kts harness-*/build.gradle.kts docs harness-fixtures/src/test
git commit -m "ci: gate and publish the UI harness"
```

## Final release verification

- [ ] Run `./gradlew clean check --warning-mode=fail` on JDK 25.
- [ ] Run `xvfb-run -a ./gradlew :harness-fixtures:test :harness-lwjgl3:test --rerun-tasks` on Linux.
- [ ] Run the reference MCP workflow five consecutive times.
- [ ] Run the parity corpus 20 times per scenario and retain raw output.
- [ ] Confirm the parity verdict satisfies the V1 criteria in the approved design.
- [ ] Inspect generated MCP tool schemas for only the nine approved operations.
- [ ] Inspect Maven-local artifacts for module boundaries, sources, Javadocs, licenses, and dependency versions.
- [ ] Verify a clean offline checkout builds from the wrapper and dependency locks.
- [ ] Request code review focused separately on public API/threading, protocol/security, and native lifecycle.
- [ ] Record the exact verification commands and outputs in the release notes before tagging V1.
