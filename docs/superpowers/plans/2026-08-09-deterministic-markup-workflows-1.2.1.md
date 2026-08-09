# Harness 1.2.1 Deterministic Gates and Markup Workflows Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publish harness 1.2.1 with deterministic release gates and a structurally markup-only Agentic Palisade authoring workflow.

**Architecture:** Published harness modules remain markup-free. The unpublished fixture consumes markup 0.4.1 for current-stack interoperability, while the treatment-neutral benchmark template owns Stage and MarkupBuilder construction and injects either NoopSink or HarnessSemanticSink.

**Tech Stack:** Java 25, Gradle 9.6.1, libGDX 1.14.2, markup 0.4.1, agent-runtime 1.0/2.0, JUnit, Python qualification tools, Playwright/Chromium, Xvfb.

## Global Constraints

- No published harness module may depend on markup.
- Markup is mandatory in both agentic benchmark arms and identical except for semantic sink injection.
- The published harness-agent-runtime 1.x POM retains agent-runtime 1.0.0 as its compatibility floor.
- Real-model Agentic Palisade execution is non-blocking; deterministic library and benchmark-contract tests remain blocking.
- Strict locators, render-thread ownership, bounded protocol data, and real input paths remain unchanged.

---

### Task 1: Supersede the empirical release gate

**Files:**
- Create: `docs/adr/0034-deterministic-release-gate.md`
- Modify: `.github/workflows/release.yml`
- Modify: `scripts/validate-workflows.py`
- Modify: `docs/maintainers/releasing.md`
- Modify: `benchmarks/README.md`
- Delete: `.release-gate-exception`

**Interfaces:**
- Produces: a release workflow whose mandatory evidence is deterministic and whose agentic runs are manual/scheduled.

- [ ] **Step 1: Write failing workflow validation tests**

In `validate-workflows.py`, add assertions that release workflow text contains no
`release-gate.py verify`, `.release-gate-exception`, or evidence-tag condition; require the Linux
full gate, parity job/check, API compatibility, Javadocs, and Central validation/publication.

- [ ] **Step 2: Verify RED**

Run: `python3 scripts/validate-workflows.py`

Expected: failure on the existing sealed repeatability and exception paths.

- [ ] **Step 3: Implement the policy change**

Remove only empirical model/repeatability verification from publication. Preserve benchmark
contract tests in CI. Add ADR 0034 explaining the distinction and update release/benchmark docs.

- [ ] **Step 4: Verify GREEN**

Run:

```bash
python3 scripts/validate-workflows.py
python3 benchmarks/agentic-palisade/scripts/test-release-gate.py
```

The historical release-gate tool remains testable as retained benchmark machinery but is no longer
referenced by publication.

- [ ] **Step 5: Commit**

Commit message: `ci: make harness releases deterministic`

### Task 2: Current markup fixture with authoritative state

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `harness-fixtures/build.gradle.kts`
- Modify: `harness-fixtures/src/main/java/dev/gdx/uiharness/fixtures/MarkupSigninScreen.java`
- Modify: `harness-fixtures/src/main/java/dev/gdx/uiharness/fixtures/ReferenceUiModel.java`
- Modify: `harness-fixtures/src/test/java/dev/gdx/uiharness/fixtures/MarkupFixtureEndToEndTest.java`
- Modify: fixture lock and verification files

**Interfaces:**
- Consumes: published markup 0.4.1 and `MarkupRuntimeSource.registerAuthoritative`.
- Produces: a markup fixture whose runtime observation is independent of widget text.

- [ ] **Step 1: Write the failing fixture test**

Extend `MarkupFixtureEndToEndTest` to compare initial/filled equality, invoke a bounded fixture
scenario that changes only the model value, then assert `MISMATCH` with exact displayed/runtime
values. Add an assertion that fixture registration mode is authoritative.

- [ ] **Step 2: Verify RED**

Run:
`xvfb-run -a ./gradlew :harness-fixtures:test --tests '*MarkupFixtureEndToEndTest' --warning-mode=fail`

Expected: current widget-mirror fixture cannot create independent divergence.

- [ ] **Step 3: Implement authoritative registration**

Update the markup fixture to 0.4.1, register values from `ReferenceUiModel`, keep widget listeners as
normal application synchronization, and provide the bounded scenario action used by the test.
Close old registrations transactionally before rebuild/disposal.

- [ ] **Step 4: Update locks and verification metadata**

Generate changes locally with Gradle's write flags, review exact markup 0.4.1 artifacts/signatures,
then run normal strict resolution without write flags.

- [ ] **Step 5: Verify GREEN**

Run:

```bash
xvfb-run -a ./gradlew :harness-fixtures:test \
  --tests '*MarkupFixtureEndToEndTest' --warning-mode=fail
xvfb-run -a ./gradlew :harness-fixtures:test \
  --tests '*RuntimeProductionFixtureTest' --warning-mode=fail
```

- [ ] **Step 6: Commit**

Commit message: `test: qualify authoritative markup fixture`

### Task 3: Agent-runtime minimum/current lanes

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `build.gradle.kts`
- Create: `scripts/test-ecosystem-profile.py`
- Create: `scripts/verify-ecosystem-profile.py`
- Modify: `.github/workflows/ci.yml`
- Add/modify profile-specific lock state

**Interfaces:**
- Consumes: `ecosystemProfile=minimum|current`.
- Produces: `minimumEcosystemTest` (runtime 1.0.0) and `currentEcosystemTest` (runtime 2.0.0 + markup 0.4.1 fixture).

- [ ] **Step 1: Write failing profile/workflow tests**

Require exact versions, no dynamic selectors, profile-specific locks, both root tasks, and named CI
steps. Require the published harness-agent-runtime dependency to remain 1.0.0.

- [ ] **Step 2: Verify RED**

Run: `python3 scripts/test-ecosystem-profile.py`

- [ ] **Step 3: Implement exact profiles**

Default production publication to the 1.0.0 floor. Use isolated configurations/nested builds to
force 2.0.0 for the current runtime test and include markup 0.4.1 only in unpublished fixtures.

- [ ] **Step 4: Generate and review dependency evidence**

Update locks/verification metadata outside CI; verify both normal profiles without write flags.

- [ ] **Step 5: Verify GREEN**

Run:

```bash
python3 scripts/test-ecosystem-profile.py
xvfb-run -a ./gradlew minimumEcosystemTest currentEcosystemTest --warning-mode=fail
```

- [ ] **Step 6: Commit**

Commit message: `build: qualify runtime 1 and 2 compatibility`

### Task 4: Shared markup-owned candidate construction

**Files:**
- Modify: `benchmarks/agentic-palisade/template/build.gradle.kts`
- Modify: `benchmarks/agentic-palisade/template/src/main/java/benchmark/palisade/CandidateUi.java`
- Create: `benchmarks/agentic-palisade/template/src/main/java/benchmark/palisade/CandidateMarkupStage.java`
- Modify: `benchmarks/agentic-palisade/template/src/main/java/benchmark/palisade/CandidateApplication.java`
- Modify: `benchmarks/agentic-palisade/template/src/main/java/benchmark/palisade/BlankCandidateUi.java`
- Create: `benchmarks/agentic-palisade/template/src/main/resources/ui/skirmish.xml`
- Create: `benchmarks/agentic-palisade/template/src/main/resources/ui/skirmish.css`
- Modify: `benchmarks/agentic-palisade/template/src/test/java/benchmark/palisade/TemplateContractTest.java`

**Interfaces:**
- `CandidateUi` produces bounded controller/state hooks, not a Stage.
- `CandidateMarkupStage` owns Stage, resource parsing, MarkupBuilder, root sizing, and disposal.
- A `SemanticSink` parameter selects baseline or harness semantics without changing candidate content.

- [ ] **Step 1: Write failing template contract tests**

Replace the `stage()` reflection assertion with controller hooks and test that missing/invalid XML or
CSS fails with typed markup diagnostics. Assert a valid blank candidate returns a Stage whose actor
tree came from the required resources and contains markup-declared IDs.

- [ ] **Step 2: Verify RED**

Run: `./gradlew -p benchmarks/agentic-palisade/template test --warning-mode=fail`

Expected: old candidate-owned Stage contract fails the new assertions.

- [ ] **Step 3: Implement shared construction minimally**

Move Stage ownership/build/disposal into `CandidateMarkupStage`. Load fixed classpath XML/CSS under
the existing markup parser bounds. Give the candidate only `bind(BuiltUi)`, reset/state, and close
hooks. Baseline `CandidateApplication` passes `NoopSink`.

- [ ] **Step 4: Verify GREEN**

Run: `xvfb-run -a ./gradlew -p benchmarks/agentic-palisade/template test --warning-mode=fail`

- [ ] **Step 5: Commit**

Commit message: `feat: make candidate UI construction markup-only`

### Task 5: Harness sink injection and treatment symmetry

**Files:**
- Modify: `benchmarks/agentic-palisade/treatments/harness/build-overlay.gradle.kts`
- Modify: `benchmarks/agentic-palisade/treatments/harness/src/main/java/benchmark/palisade/HarnessCli.java`
- Modify: `benchmarks/agentic-palisade/treatments/harness/src/main/java/benchmark/palisade/HarnessBridge.java`
- Modify: harness treatment tests
- Modify: baseline and harness `INSTRUCTIONS.md`
- Modify: `benchmarks/agentic-palisade/scripts/test-treatment-symmetry.py`
- Modify: `benchmarks/agentic-palisade/scripts/treatment-preflight.py`

**Interfaces:**
- Baseline and harness consume identical XML/CSS and markup 0.4.1.
- Harness creates `HarnessSemanticSink` from its session semantics and injects it before build.

- [ ] **Step 1: Write failing harness/symmetry tests**

Test that both prepared arms record identical markup coordinate/digest/resources and that changing
any one is rejected. Test that harness semantic query resolves a markup-declared ID without
imperative semantic tagging. Require common instructions to mandate markup and forbid parallel UI
trees.

- [ ] **Step 2: Verify RED**

Run:

```bash
python3 benchmarks/agentic-palisade/scripts/test-treatment-symmetry.py
./gradlew -p benchmarks/agentic-palisade/template test --warning-mode=fail
```

- [ ] **Step 3: Implement treatment injection**

Refactor harness startup so Stage exists before the session, the bridge/session exists before
MarkupBuilder, and the resulting `HarnessSemanticSink` is passed into the shared build. Remove
candidate/bridge imperative semantic fallbacks that markup now supplies.

- [ ] **Step 4: Update common instructions and preflight identity**

Give both arms the same markup-first authoring text and build commands; keep only harness tool usage
in the harness appendix. Seal markup artifact and resource digests in preflight evidence.

- [ ] **Step 5: Verify GREEN**

Publish a local 1.2.1 candidate and run template tests, symmetry tests, offline treatment preflight,
and the synthetic qualification pipeline under Xvfb.

- [ ] **Step 6: Commit**

Commit message: `benchmark: share markup construction across treatments`

### Task 6: 1.2.1 documentation and release candidate

**Files:**
- Modify: `README.md`
- Modify: relevant getting-started/benchmark/release guides
- Create: `docs/releases/v1.2.1.md`
- Modify: `.github/workflows/ci.yml`
- Modify: `.github/workflows/release.yml`

- [ ] **Step 1: Add failing version/documentation checks**

Extend repository validators to reject stale 1.1.0 “current release” text and require explicit
deterministic-versus-empirical qualification language.

- [ ] **Step 2: Verify RED**

Run repository workflow and documentation validators.

- [ ] **Step 3: Update public material and required jobs**

Document 1.2.1, markup 0.4.1 fixture interoperability, runtime 1/2 compatibility, markup-only
agentic construction, and manual benchmark status. Preserve the one-run parity CI job.

- [ ] **Step 4: Run full verification**

Run workflow validation, all Python/evaluator/template contracts, both ecosystem tasks, Xvfb clean
check/Javadocs/API compatibility, and Playwright parity.

- [ ] **Step 5: Commit**

Commit message: `docs: prepare harness 1.2.1 release`

### Task 7: Review, publish, and verify 1.2.1

- [ ] **Step 1: Request code review**

Review dependency direction, 1.x compatibility, treatment symmetry, markup structural ownership,
render-thread lifecycle, bounds, workflow removal scope, and release documentation.

- [ ] **Step 2: Remediate and re-verify exact head**

Run every affected deterministic gate after fixes and require remote PR checks on the reviewed SHA.

- [ ] **Step 3: Merge and reconcile main**

Merge the reviewed branch and fast-forward local main.

- [ ] **Step 4: Tag/publish**

Create/push `v1.2.1`; monitor signed GitHub release workflow through Central validation and final
publication of all six artifacts.

- [ ] **Step 5: Verify public coordinates**

Resolve all six 1.2.1 coordinates from Maven Central and run the downstream bootstrap against them
before tagging bootstrap 1.2.0.
