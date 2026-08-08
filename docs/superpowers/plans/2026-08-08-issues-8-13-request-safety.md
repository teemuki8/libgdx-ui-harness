# Issues 8–13 Request Safety Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close GitHub issues #8 through #13 with bounded MCP admission, safe text matching, deadlock-free render scheduling, frame-independent deadlines, and exclusive scenario ownership.

**Architecture:** Harden the boundary from the outside in while preserving the layered design. The core owns linear-time text matching and an injected deadline-scheduling abstraction; Scene2D and LWJGL3 own render-thread state machines; MCP owns byte framing and request admission. One cluster PR carries six independently failing behavioral regressions and the ADR amendments required by public security, timing, and lifecycle contracts.

**Tech Stack:** Java 25, Gradle 9.6 wrapper, JUnit 5, libGDX Scene2D/LWJGL3, MCP Java SDK 2.0, Jackson, Reactor, RE2/J.

## Global Constraints

- Use `./gradlew`; never a machine-installed Gradle.
- Compile and test with JDK 25 and `--no-daemon --console=plain --warning-mode=fail`.
- Do not use preview or incubator Java APIs.
- Add each behavioral test first and record its expected failure before production edits.
- Use injected monotonic time, barriers, latches, and owned schedulers; never sleeps.
- Preserve render-thread ownership, lazy strict locators, real input dispatch, bounded immutable protocol data, and distinct zero/multiple locator failures.
- No libGDX or backend type crosses into core or protocol models.
- Every public security, timing, or lifecycle decision is reflected in the named ADR before the PR is opened.

---

## File map

- `harness-core/.../locator/TextMatch.java`: compile and execute regex locators through the linear-time engine.
- `gradle/libs.versions.toml`, `harness-core/build.gradle.kts`, dependency locks and verification metadata: declare and verify RE2/J.
- `harness-core/.../time/DeadlineScheduler.java`: transport-neutral deadline wakeup contract used by Scene2D and LWJGL3.
- `harness-scene2d/.../RenderThreadScheduler.java`: single-monitor render-command state transitions.
- `harness-scene2d/.../Scene2dScenarioRunner.java`: one lease per runner/session and exactly-once release.
- `harness-scene2d/.../Scene2dHarness.java`: action deadline wakeups independent of frames.
- `harness-lwjgl3/.../Lwjgl3FrameFence.java`: capture/fence deadline wakeups independent of frames.
- `harness-lwjgl3/.../Lwjgl3MatrixRunner.java`: release scenario leases on assertion and infrastructure failures.
- `harness-mcp/.../BoundedJsonRpcFramer.java`: strict UTF-8, newline-delimited, byte-capped frame reader.
- `harness-mcp/.../RequestAdmission.java`: global/per-session permits and per-session mutation lanes.
- `harness-mcp/.../HarnessMcpServer.java`: hardened parser, admission, dispatch, timeout, and shutdown wiring.
- `harness-mcp/.../HarnessToolCatalog.java`: authoritative read-only versus mutating tool classification.
- `docs/adr/0031-request-boundary-and-render-lifecycle-hardening.md`: regex, framing, admission, deadline, and lease decisions.

### Task 1: Remove scheduler lock inversion (#11)

**Files:**
- Modify: `harness-scene2d/src/test/java/dev/gdx/uiharness/scene2d/RenderThreadSchedulerTest.java`
- Modify: `harness-scene2d/src/main/java/dev/gdx/uiharness/scene2d/RenderThreadScheduler.java`

**Interfaces:**
- Consumes: existing `submit(Callable<T>, Deadline)`, `drain()`, `close()`, and returned-stage cancellation.
- Produces: the same public API with all queue, batch, command-state, and lifecycle transitions guarded only by `lifecycle`; user callables still execute outside the monitor.

- [ ] **Step 1: Add a deterministic lock-cycle regression**

Add a test named `cancellationCannotDeadlockWithDrainOrClose` that uses a callable blocked on a `CountDownLatch`, starts `drain()` on the scheduler owner thread, races cancellation and `close()` from virtual threads, releases the callable, and joins both actors with `Future.get(2, SECONDS)`. Assert each returned stage reaches exactly one of success, cancellation, or the existing typed closed failure; do not accept a join timeout.

The plausible bug it defends against is a command monitor acquired before `lifecycle` while drain/close acquires them in reverse order.

- [ ] **Step 2: Run the focused test and observe RED**

```bash
./gradlew :harness-scene2d:test --tests '*RenderThreadSchedulerTest.cancellationCannotDeadlockWithDrainOrClose' --no-daemon --console=plain --warning-mode=fail
```

Expected: FAIL by the test's two-second join deadline against the current cyclic lock order.

- [ ] **Step 3: Collapse command transitions under one monitor**

Make `ScheduledCommand` transition helpers non-`synchronized` and require callers to hold `lifecycle`. Under `lifecycle`, `submit`, cancellation, batch claiming, close, and post-dispatch completion may only claim or record state; invoke the user `Callable` and complete the `CompletableFuture` after leaving the monitor. Keep these legal transitions:

```java
QUEUED -> DISPATCHED -> COMPLETED
QUEUED -> CANCELLED
QUEUED -> FAILED
DISPATCHED -> COMPLETED
```

A cancellation after `DISPATCHED` must not interrupt render-thread work. `close()` fails queued commands and leaves dispatched commands untouched.

- [ ] **Step 4: Run focused and module tests GREEN**

```bash
./gradlew :harness-scene2d:test --tests '*RenderThreadSchedulerTest*' --no-daemon --console=plain --warning-mode=fail
```

Expected: PASS, including the new race and all pre-existing queue/close behavior.

- [ ] **Step 5: Commit the scheduler fix**

```bash
git add harness-scene2d/src/main/java/dev/gdx/uiharness/scene2d/RenderThreadScheduler.java harness-scene2d/src/test/java/dev/gdx/uiharness/scene2d/RenderThreadSchedulerTest.java
git commit -m "fix(scene2d): remove scheduler lock inversion"
```

### Task 2: Bound JSON-RPC frames before materialization (#9)

**Files:**
- Create: `harness-mcp/src/main/java/dev/gdx/uiharness/mcp/BoundedJsonRpcFramer.java`
- Create: `harness-mcp/src/test/java/dev/gdx/uiharness/mcp/BoundedJsonRpcFramerTest.java`
- Modify: `harness-mcp/src/main/java/dev/gdx/uiharness/mcp/HarnessMcpServer.java`
- Modify: `harness-mcp/src/test/java/dev/gdx/uiharness/mcp/HarnessMcpServerContractTest.java`

**Interfaces:**
- Produces: package-private `BoundedJsonRpcFramer(InputStream input, int maxFrameBytes)` and `Frame read()` where `Frame` is `Message(String json)`, `Rejected(String code)`, or `EndOfInput`.
- Produces: one hardened MCP `McpJsonMapper` whose Jackson factory enforces the same request constraints as `ProtocolJson`: nesting depth 64, string length 16,384, number length 128, and frame bytes 1,048,576.

- [ ] **Step 1: Add byte-boundary and recovery tests**

Cover:

```java
assertInstanceOf(Message.class, frameOfBytes(MAX_FRAME_BYTES));
assertEquals("frame-too-large", rejectionOfBytes(MAX_FRAME_BYTES + 1).code());
assertEquals("frame-too-large", rejectionOfUnterminatedBytes(MAX_FRAME_BYTES + 1).code());
assertEquals("invalid-utf8", rejectionOf(new byte[] {(byte) 0xc3, 0x28, '\n'}).code());
```

The framer must drain only through the terminating newline after a rejected frame, cap retained diagnostic bytes, then return the next valid frame. Add stdio contract cases for excessive depth/string/number size and assert a following `initialize` request still succeeds.

- [ ] **Step 2: Run framing tests RED**

```bash
./gradlew :harness-mcp:test --tests '*BoundedJsonRpcFramerTest*' --tests '*HarnessMcpServerContractTest.oversizedOrMalformedFrameDoesNotTerminateServer' --no-daemon --console=plain --warning-mode=fail
```

Expected: FAIL because `BufferedReader.readLine()` materializes the entire input and the MCP mapper lacks equivalent constraints.

- [ ] **Step 3: Implement the bounded strict-UTF-8 framer**

Read fixed-size byte chunks or one byte at a time without growing beyond `maxFrameBytes`; treat LF as the frame terminator and strip one preceding CR. Decode with:

```java
StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT);
```

After overflow, discard bytes to LF using constant memory. EOF with no bytes yields `EndOfInput`; EOF after an in-limit unterminated frame yields a bounded parse rejection; EOF after an oversized frame yields `frame-too-large`.

- [ ] **Step 4: Harden the parser used by the MCP SDK path**

Replace `BufferedReader` and `readLine()` in `VirtualStdioProvider` with `BoundedJsonRpcFramer`. Build the MCP mapper from a Jackson `JsonFactory` with `StreamReadConstraints` matching `ProtocolJson`; map every framing/parsing failure to one bounded JSON-RPC parse error and continue when framing permits recovery. Never echo the rejected frame.

- [ ] **Step 5: Run focused and MCP tests GREEN**

```bash
./gradlew :harness-mcp:test --tests '*BoundedJsonRpcFramerTest*' --tests '*HarnessMcpServerContractTest*' --no-daemon --console=plain --warning-mode=fail
```

Expected: PASS; limit-plus-one is rejected, constant-memory drain recovers, malformed input does not close the server.

- [ ] **Step 6: Commit framing hardening**

```bash
git add harness-mcp/src/main/java/dev/gdx/uiharness/mcp/BoundedJsonRpcFramer.java harness-mcp/src/main/java/dev/gdx/uiharness/mcp/HarnessMcpServer.java harness-mcp/src/test/java/dev/gdx/uiharness/mcp/BoundedJsonRpcFramerTest.java harness-mcp/src/test/java/dev/gdx/uiharness/mcp/HarnessMcpServerContractTest.java
git commit -m "fix(mcp): bound stdio frames before parsing"
```

### Task 3: Replace backtracking regex evaluation (#8)

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `harness-core/build.gradle.kts`
- Modify: `harness-core/src/main/java/dev/gdx/uiharness/core/locator/TextMatch.java`
- Modify: `harness-core/src/test/java/dev/gdx/uiharness/core/locator/StrictResolutionTest.java`
- Modify: dependency lockfiles and `gradle/verification-metadata.xml` through the documented Gradle dependency workflow.

**Interfaces:**
- Consumes: `TextMatch.regex(String)` and package-private `matches(String)` unchanged.
- Produces: RE2/J-backed `Mode.REGEX` with linear-time search and compile-time rejection of unsupported constructs; exact and substring modes unchanged.

- [ ] **Step 1: Add supported, unsupported, and adversarial regex tests**

Add `pathologicalRegexCannotBlockResolution` using `TextMatch.regex("(a+)+$")` and a candidate of 16,383 `a` characters plus `!`; resolve on a virtual-thread executor and require completion within two seconds. Assert correct `.find()` semantics for supported grouping, alternation, Unicode text, and anchors. Assert RE2-unsupported backreferences and lookbehind fail at construction with a bounded `IllegalArgumentException` that does not include the candidate string.

- [ ] **Step 2: Run the adversarial test RED**

```bash
./gradlew :harness-core:test --tests '*StrictResolutionTest.pathologicalRegexCannotBlockResolution' --no-daemon --console=plain --warning-mode=fail
```

Expected: FAIL by deadline with `java.util.regex.Pattern` on the pathological candidate.

- [ ] **Step 3: Add and lock RE2/J**

Declare one version-catalog entry and use it only from `harness-core`:

```toml
[versions]
re2j = "1.8"

[libraries]
re2j = { module = "com.google.re2j:re2j", version.ref = "re2j" }
```

```kotlin
dependencies {
    implementation(libs.re2j)
}
```

Regenerate the affected lock and verification entries with Gradle's dependency-locking and dependency-verification commands, then inspect that only RE2/J artifacts were added.

- [ ] **Step 4: Migrate `TextMatch` to RE2/J**

Replace `java.util.regex.Pattern` with `com.google.re2j.Pattern`. Continue compiling in the constructor and using `pattern.matcher(normalize(candidate)).find()`. Translate `PatternSyntaxException` to the current bounded construction failure style without logging or returning candidate data.

- [ ] **Step 5: Run core and Scene2D locator tests GREEN**

```bash
./gradlew :harness-core:test --tests '*StrictResolutionTest*' --tests '*LocatorEngineTest*' :harness-scene2d:test --tests '*Scene2dActionEndToEndTest*' --no-daemon --console=plain --warning-mode=fail
```

Expected: PASS with the adversarial case completing and supported regex semantics unchanged.

- [ ] **Step 6: Commit linear-time regex matching**

```bash
git add gradle/libs.versions.toml harness-core/build.gradle.kts harness-core/src/main/java/dev/gdx/uiharness/core/locator/TextMatch.java harness-core/src/test/java/dev/gdx/uiharness/core/locator/StrictResolutionTest.java gradle/verification-metadata.xml gradle.lockfile harness-core/gradle.lockfile
git commit -m "fix(core): use linear-time locator regexes"
```

### Task 4: Serialize scenario ownership and release every path (#13)

**Files:**
- Modify: `harness-scene2d/src/test/java/dev/gdx/uiharness/scene2d/Scene2dScenarioRunnerTest.java`
- Modify: `harness-scene2d/src/main/java/dev/gdx/uiharness/scene2d/Scene2dScenarioRunner.java`
- Modify: `harness-lwjgl3/src/test/java/dev/gdx/uiharness/lwjgl3/Lwjgl3MatrixRunnerTest.java`
- Modify: `harness-lwjgl3/src/main/java/dev/gdx/uiharness/lwjgl3/Lwjgl3MatrixRunner.java`
- Modify: `harness-core/src/main/java/dev/gdx/uiharness/core/scenario/ScenarioFailure.java`
- Modify: `harness-protocol/src/test/java/dev/gdx/uiharness/protocol/ProtocolJsonContractTest.java` and the scenario JSON golden containing the enum value set.

**Interfaces:**
- Produces: one atomic active lease per `Scene2dScenarioRunner`; `acquire(ScenarioRequest, Deadline)` returns `ScenarioFailure.SESSION_BUSY` when another lease owns the session.
- Preserves: `ScenarioLease.release()` idempotence and all existing lifecycle correlation fields.

- [ ] **Step 1: Add exclusive-acquire and terminal-release tests**

Use a barrier to submit two acquisitions before the owner thread drains. Assert exactly one succeeds and one reports `BUSY`. Parameterize terminal paths—success, caller cancellation, deadline, setup failure, reset failure, readiness failure, assertion failure, and close—and assert a subsequent acquisition succeeds. Add a stale-release test: releasing the first lease after the second begins cannot clear the second lease.

In `Lwjgl3MatrixRunnerTest`, make one assertion stage complete exceptionally and assert `releaseCount == acquireCount == 1` before the next case begins.

- [ ] **Step 2: Run scenario and matrix tests RED**

```bash
./gradlew :harness-scene2d:test --tests '*Scene2dScenarioRunnerTest*' :harness-lwjgl3:test --tests '*Lwjgl3MatrixRunnerTest*' --no-daemon --console=plain --warning-mode=fail
```

Expected: FAIL because concurrent runs are both added and matrix assertion failure skips release.

- [ ] **Step 3: Add token-checked lease ownership**

Guard one `activeLease` identity under the runner lifecycle monitor. Acquisition atomically installs a unique token before scheduling setup. Every terminal path calls one helper:

```java
private void releaseIfOwner(Run run) {
    synchronized (lifecycle) {
        active.remove(run);
    }
}
```

Use the existing `active` list as the single source of truth and enforce `active.size() <= 1`; a stale release removes only the same `Run` identity and cannot clear its successor. Reject a competing acquisition with bounded scenario/session evidence and no hook execution. Do not let `CompletableFuture.cancel` bypass cleanup.

- [ ] **Step 4: Release matrix leases with `whenComplete`**

Structure each case so assertion evaluation is inside a stage whose `whenComplete` or `handle` always calls the acquired lease's idempotent `release()` before producing the case terminal result. Preserve the original assertion failure as primary when release also fails; attach bounded release evidence rather than swallowing the original cause.

- [ ] **Step 5: Run focused suites GREEN**

```bash
./gradlew :harness-scene2d:test --tests '*Scene2dScenarioRunnerTest*' :harness-lwjgl3:test --tests '*Lwjgl3MatrixRunnerTest*' :harness-fixtures:test --tests '*ScenarioLifecycleFixtureTest*' --no-daemon --console=plain --warning-mode=fail
```

Expected: PASS; no lease remains after any terminal path and concurrent acquisition has one winner.

- [ ] **Step 6: Commit scenario ownership**

```bash
git add harness-core/src/main/java/dev/gdx/uiharness/core/scenario harness-scene2d/src/main/java/dev/gdx/uiharness/scene2d/Scene2dScenarioRunner.java harness-scene2d/src/test/java/dev/gdx/uiharness/scene2d/Scene2dScenarioRunnerTest.java harness-lwjgl3/src/main/java/dev/gdx/uiharness/lwjgl3/Lwjgl3MatrixRunner.java harness-lwjgl3/src/test/java/dev/gdx/uiharness/lwjgl3/Lwjgl3MatrixRunnerTest.java harness-protocol/src/main harness-protocol/src/test
git commit -m "fix(scene2d): serialize scenario ownership"
```

### Task 5: Bound admission and serialize mutations (#10)

**Files:**
- Create: `harness-mcp/src/main/java/dev/gdx/uiharness/mcp/RequestAdmission.java`
- Create: `harness-mcp/src/test/java/dev/gdx/uiharness/mcp/RequestAdmissionTest.java`
- Modify: `harness-mcp/src/main/java/dev/gdx/uiharness/mcp/HarnessToolCatalog.java`
- Modify: `harness-mcp/src/main/java/dev/gdx/uiharness/mcp/HarnessToolHandler.java`
- Modify: `harness-mcp/src/main/java/dev/gdx/uiharness/mcp/HarnessMcpServer.java`
- Modify: `harness-mcp/src/test/java/dev/gdx/uiharness/mcp/HarnessMcpServerContractTest.java`

**Interfaces:**
- Produces: `HarnessToolCatalog.accessMode(String toolName)` returning `READ_ONLY` or `MUTATING` from the same immutable tool definition used for schemas.
- Produces: package-private `RequestAdmission(int globalLimit, int perSessionLimit, int maxQueuedMutations)` with `CompletionStage<T> submit(String sessionId, AccessMode mode, Supplier<CompletionStage<T>> work)`.
- Preserves: requests admitted before close complete or cancel exactly once; rejected requests never call the protocol service.

- [ ] **Step 1: Add limit, ordering, cleanup, and concurrency tests**

Use controllable futures to hold work in flight. Assert:

- exactly `globalLimit` requests are admitted and limit-plus-one returns a bounded `DiagnosticCode.LIMIT_EXCEEDED` result;
- one session cannot exceed `perSessionLimit` while another session can use remaining global capacity;
- two mutating operations for one session start in submission order and never overlap;
- read-only operations for one session may overlap within limits;
- cancellation, exceptional completion, synchronous supplier failure, and `close()` release every permit and queued mutation slot;
- output translation is bounded before a permit is released if output bytes count toward admission.

- [ ] **Step 2: Run admission tests RED**

```bash
./gradlew :harness-mcp:test --tests '*RequestAdmissionTest*' --tests '*HarnessMcpServerContractTest.requestAdmissionIsBoundedAndMutationsAreSerialized' --no-daemon --console=plain --warning-mode=fail
```

Expected: FAIL because virtual-thread dispatch and handler subscription are unbounded and unclassified.

- [ ] **Step 3: Add access mode to the authoritative catalog**

Extend the catalog's internal immutable tool definition with:

```java
enum AccessMode { READ_ONLY, MUTATING }
```

Classify state/action/session/trace/scenario/matrix operations by whether they mutate session or application state. Do not expose a second tool-name set in `HarnessToolHandler`; handler and operation catalog consume the same definition. Add a catalog test asserting every allowlisted tool has exactly one access mode.

- [ ] **Step 4: Implement admission and wire dispatch**

Use bounded counters/queues guarded by one internal monitor. Never block a virtual thread waiting for a permit: reject excess immediately. For admitted mutating work, append to a per-session completion tail and start only after the previous tail reaches a terminal state. Remove empty session lanes. Release global/per-session counters in one `whenComplete` path after result translation/output accounting. `close()` rejects queued work and prevents new admission without interrupting already executing render work.

- [ ] **Step 5: Run MCP suites GREEN**

```bash
./gradlew :harness-mcp:test --tests '*RequestAdmissionTest*' --tests '*HarnessMcpServerContractTest*' --tests '*HarnessToolCatalogTest*' --no-daemon --console=plain --warning-mode=fail
```

Expected: PASS with stable limit errors, serialized mutations, concurrent reads, and no leaked permits.

- [ ] **Step 6: Commit request admission**

```bash
git add harness-mcp/src/main/java/dev/gdx/uiharness/mcp/RequestAdmission.java harness-mcp/src/main/java/dev/gdx/uiharness/mcp/HarnessToolCatalog.java harness-mcp/src/main/java/dev/gdx/uiharness/mcp/HarnessToolHandler.java harness-mcp/src/main/java/dev/gdx/uiharness/mcp/HarnessMcpServer.java harness-mcp/src/test/java/dev/gdx/uiharness/mcp/RequestAdmissionTest.java harness-mcp/src/test/java/dev/gdx/uiharness/mcp/HarnessMcpServerContractTest.java harness-mcp/src/test/java/dev/gdx/uiharness/mcp/HarnessToolCatalogTest.java
git commit -m "fix(mcp): bound and serialize request admission"
```

### Task 6: Enforce deadlines without another frame (#12)

**Files:**
- Create: `harness-core/src/main/java/dev/gdx/uiharness/core/time/DeadlineScheduler.java`
- Remove: `harness-scene2d/src/main/java/dev/gdx/uiharness/scene2d/Scene2dScenarioDeadlineScheduler.java` after migrating all callers.
- Modify: `harness-scene2d/src/main/java/dev/gdx/uiharness/scene2d/Scene2dScenarioRunner.java`
- Modify: `harness-scene2d/src/main/java/dev/gdx/uiharness/scene2d/Scene2dHarness.java`
- Modify: `harness-scene2d/src/test/java/dev/gdx/uiharness/scene2d/Scene2dActionEndToEndTest.java`, `Scene2dScenarioRunnerTest.java`, and fixture construction.
- Modify: `harness-lwjgl3/src/main/java/dev/gdx/uiharness/lwjgl3/Lwjgl3FrameFence.java`
- Modify: `harness-lwjgl3/src/test/java/dev/gdx/uiharness/lwjgl3/Lwjgl3ScreenCaptureTest.java`
- Modify: `harness-mcp/src/main/java/dev/gdx/uiharness/mcp/HarnessMcpServer.java`
- Modify: `harness-mcp/src/test/java/dev/gdx/uiharness/mcp/HarnessMcpServerContractTest.java`
- Modify: `harness-fixtures/src/main/java/dev/gdx/uiharness/fixtures/FixtureControl.java` and replacement-host wiring.

**Interfaces:**
- Produces:

```java
@FunctionalInterface
public interface DeadlineScheduler {
    Cancellation schedule(Duration delay, Runnable signal);
    @FunctionalInterface interface Cancellation { void cancel(); }
}
```

- `Scene2dHarness` and `Lwjgl3FrameFence` receive the scheduler explicitly; no global singleton or unowned executor.
- `HarnessMcpServer` outer timeout accepts the full published scenario maximum while each request retains its own validated deadline.

- [ ] **Step 1: Add no-frame and race regressions**

Add deterministic fake-scheduler tests for:

- an action in `AWAITING_FRAME` expires without invoking `completedFrame`;
- a frame-fence capture expires without invoking `completedFrame`;
- completion immediately before the signal wins;
- signal racing a frame completes exactly once;
- close cancels all scheduled signals;
- an MCP `ui_scenario_start` with `maxDurationMillis` above 120,000 is not aborted by the outer SDK timeout.

Assert the typed deadline error retains elapsed time, trace/session identity, and last actionability evidence.

- [ ] **Step 2: Run deadline tests RED**

```bash
./gradlew :harness-scene2d:test --tests '*Scene2dActionEndToEndTest*' :harness-lwjgl3:test --tests '*Lwjgl3ScreenCaptureTest*' :harness-mcp:test --tests '*HarnessMcpServerContractTest.scenarioDeadlineCanExceedDefaultRequestDeadline' --no-daemon --console=plain --warning-mode=fail
```

Expected: FAIL because each pending path checks time only on another frame and MCP applies a 120-second outer timeout.

- [ ] **Step 3: Introduce and migrate the shared scheduler contract**

Move the existing functional shape from `Scene2dScenarioDeadlineScheduler` to core `DeadlineScheduler`. Use LSP symbol rename/move support for all references. Keep the concrete executor owned by the application/session assembly and close it with that owner. Test fakes advance monotonic time and explicitly run due signals.

- [ ] **Step 4: Arm and cancel action/fence deadline signals**

When an action or frame command enters a pending state, schedule once for `deadline.remaining()`. The callback only claims timeout under the existing lifecycle monitor and completes the future after leaving it. Normal completion, cancellation, and close cancel the registered signal. A late callback observes terminal state and does nothing.

- [ ] **Step 5: Align the MCP outer timeout**

Set the SDK connection timeout to at least `HarnessRequest.MAX_SCENARIO_DEADLINE_MILLIS` plus a small fixed bounded translation allowance, while retaining request-specific protocol deadlines. Do not disable the outer timeout. Add a constructor-level assertion/test proving no accepted request duration exceeds the outer bound.

- [ ] **Step 6: Run all affected suites GREEN**

```bash
./gradlew :harness-scene2d:test :harness-lwjgl3:test :harness-protocol:test :harness-mcp:test :harness-fixtures:test --no-daemon --console=plain --warning-mode=fail
```

Expected: PASS; every no-frame path expires exactly once and accepted scenario durations are representable end to end.

- [ ] **Step 7: Commit deadline wakeups**

```bash
git add harness-core/src/main/java/dev/gdx/uiharness/core/time harness-scene2d/src/main harness-scene2d/src/test harness-lwjgl3/src/main harness-lwjgl3/src/test harness-mcp/src/main harness-mcp/src/test harness-fixtures/src/main harness-fixtures/src/test
git commit -m "fix: enforce deadlines without rendered frames"
```

### Task 7: Document decisions, verify, and open the cluster PR

**Files:**
- Create: `docs/adr/0031-request-boundary-and-render-lifecycle-hardening.md`
- Modify: public guide text only where the implemented regex, framing, overload, deadline, or scenario-busy behavior is user-visible.
- Modify: `docs/superpowers/plans/2026-08-08-issues-8-13-request-safety.md` only to check completed plan steps before commit if repository convention retains execution state.

**Interfaces:**
- Consumes: final public behavior from Tasks 1–6.
- Produces: one accepted ADR and one issue-only PR with `Fixes #8` through `Fixes #13`.

- [ ] **Step 1: Write ADR 0031**

Record context, decision, alternatives, and consequences for RE2/J compatibility, 1 MiB strict-UTF-8 framing, explicit admission limits/access modes, frame-independent deadline scheduling, single-monitor scheduler transitions, and one scenario lease. State exact public limits and typed failure/status values from the implementation; do not document guessed names.

- [ ] **Step 2: Run focused gates in dependency order**

```bash
./gradlew :harness-core:test --tests '*StrictResolutionTest*' --tests '*LocatorEngineTest*' :harness-scene2d:test --tests '*RenderThreadSchedulerTest*' --tests '*Scene2dScenarioRunnerTest*' --tests '*Scene2dActionEndToEndTest*' :harness-lwjgl3:test --tests '*Lwjgl3MatrixRunnerTest*' --tests '*Lwjgl3ScreenCaptureTest*' :harness-mcp:test --tests '*BoundedJsonRpcFramerTest*' --tests '*RequestAdmissionTest*' --tests '*HarnessMcpServerContractTest*' --tests '*HarnessToolCatalogTest*' --no-daemon --console=plain --warning-mode=fail
```

Expected: PASS, zero project warnings.

- [ ] **Step 3: Run graphical and full repository gates**

```bash
./gradlew :harness-fixtures:test --tests '*ScenarioLifecycleFixtureTest*' --tests '*ReferenceApplicationSmokeTest*' --no-daemon --console=plain --warning-mode=fail
./gradlew clean check javadoc --no-daemon --console=plain --warning-mode=fail
python3 scripts/validate-workflows.py
git diff --check origin/main...HEAD
```

Expected: all commands exit 0; graphical fixtures exercise real LWJGL3; no whitespace errors.

- [ ] **Step 4: Commit ADR and direct documentation**

```bash
git add docs/adr/0031-request-boundary-and-render-lifecycle-hardening.md docs/guides
git commit -m "docs: record request safety contracts"
```

- [ ] **Step 5: Review cluster scope and publish**

Verify the merge-base range contains only the design/plan, #8–#13 tests and implementation, ADR 0031, direct docs, and dependency metadata. Push `fix/issues-8-13-request-safety`, open a ready PR with root causes, acceptance coverage, exact validation, and six separate closure lines:

```text
Fixes #8
Fixes #9
Fixes #10
Fixes #11
Fixes #12
Fixes #13
```

- [ ] **Step 6: Review and merge exact head**

Review the remote PR base/head/commits/files/full patch, fetch all review comments, and wait for required checks on the reviewed SHA. Reproduce actionable findings test-first; push and repeat review/checks. Merge only the reviewed green SHA using the repository's established merge method. Verify the PR is `MERGED` and issues #8–#13 are closed.
