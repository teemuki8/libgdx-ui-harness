# Task 3 report: treatment-specific harness access

## Status

Complete. The harness treatment attaches one non-owning `Scene2dSession` to the candidate's application-owned `Stage`, drains bounded action work before application rendering, publishes frame identity after application drawing, exposes all nine V1 MCP-equivalent operations through bounded NDJSON, closes transient resources cleanly, and retains only bounded content-addressed benchmark evidence. The baseline and harness share byte-identical task wording outside their locked treatment appendices.

## Files

Created under `benchmarks/agentic-palisade/`:

- `treatments/baseline/INSTRUCTIONS.md` — neutral screenshot/log/state iteration appendix.
- `treatments/harness/INSTRUCTIONS.md` — harness semantic/query/action/wait/screenshot/trace iteration appendix over the identical shared task wording.
- `treatments/harness/build-overlay.gradle.kts` — treatment source overlay, published `harness-lwjgl3:1.0.0` and `harness-mcp:1.0.0` coordinates, Java native-access test setting, and post-evaluation `HarnessCli` launcher selection.
- `treatments/harness/src/main/java/benchmark/palisade/HarnessBridge.java` — one session, render-thread scheduler/fence callbacks, protocol session, wait/capture/trace adapters, bounded transient store, content-addressed publisher, and owned lifecycle.
- `treatments/harness/src/main/java/benchmark/palisade/HarnessCli.java` — fixed nine-operation JSON envelope, strict byte/command/response bounds, fixed candidate class, generated per-process run root, and treatment launcher.
- `treatments/harness/src/test/java/benchmark/palisade/HarnessBridgeTest.java` — real LWJGL3 end-to-end coverage of discovery, semantic snapshot, role/name query, action, wait, screenshot, recoverable trace lifecycle, capabilities, arbitrary-input rejection, oversized-input termination, retained artifact ownership, and clean close.
- `scripts/test-treatment-symmetry.py` — frozen shared-input hashing, exact shared/appendix checks, treatment-path allowlist, coordinate checks, and deliberate mutation probes.

No protocol, corpus, neutral template source, evaluator, runner, blinding, CI, fixture, or measured-agent file was changed.

## TDD commands and observed outputs

### Initial harness red

From `benchmarks/agentic-palisade/template/`:

```text
../../../gradlew -p . --init-script ../treatments/harness/build-overlay.gradle.kts test --tests benchmark.palisade.HarnessBridgeTest --no-daemon
```

After correcting init-script setup errors, the focused test reached the intended missing-feature failure:

```text
> Task :compileJava
> Task :compileTestJava FAILED
error: cannot find symbol HarnessBridge
error: cannot find symbol HarnessCli
5 errors
BUILD FAILED in 9s
```

### Initial symmetry red

From `benchmarks/agentic-palisade/`:

```text
python3 scripts/test-treatment-symmetry.py
```

Before the validator existed, both deliberate probes failed at the missing implementation:

```text
ERROR: test_rejects_one_arm_acceptance_assertion
ERROR: test_rejects_one_arm_design_hint
NameError: name 'validate_treatment_symmetry' is not defined
Ran 2 tests
FAILED (errors=2)
```

### Harness green

After implementing the bridge and CLI, the focused real-LWJGL3 test produced:

```text
BUILD SUCCESSFUL in 6s
3 actionable tasks: 3 executed
```

JUnit XML recorded `tests="1" skipped="0" failures="0" errors="0"`. The test observed one discoverable `candidate-ui` session, a semantic root and tagged button, an exact role/name match, one real Stage click, a visible wait, a 320x240 PNG artifact, a two-event trace archive, all nine tool result families, rejection of an arbitrary command and path, bounded content-addressed evidence retained after close, transient store removal, and preservation of an application-owned sibling file and `CandidateUi` lifecycle.

### Overlay ordering red/green

A generated launcher smoke check first exposed that the neutral build's later `application` block overwrote the init-script main-class selection:

```text
../../../gradlew -p . --init-script ../treatments/harness/build-overlay.gradle.kts startScripts --rerun-tasks --no-daemon
generated script: benchmark.palisade.CandidateLauncher
```

Moving selection to post-evaluation and rerunning the same command produced:

```text
BUILD SUCCESSFUL in 10s
generated script: benchmark.palisade.HarnessCli
```

### Oversized-input red/green

The regression probe supplies a maximum-length JSON command without a newline and throws if the CLI reads another byte. Before the fix:

```text
HarnessBridgeTest > fixedJsonCliExercisesOneApplicationOwnedSessionAndArtifacts() FAILED
Caused by: java.lang.AssertionError: CLI read beyond its maximum JSON command size
BUILD FAILED in 6s
```

After stopping immediately at the byte bound and emitting one `limit-exceeded` response:

```text
BUILD SUCCESSFUL in 6s
3 actionable tasks: 3 executed
```

### Review lifecycle red/green

Focused review found that clean EOF removed the opaque screenshot/trace evidence and that a valid one-byte trace limit could fail the mandatory start event after leaving the controller marked active. The combined lifecycle regression first failed at the removed evidence root:

```text
HarnessBridgeTest > fixedJsonCliExercisesOneApplicationOwnedSessionAndArtifacts() FAILED
org.opentest4j.AssertionFailedError at HarnessBridgeTest.java:62
BUILD FAILED in 6s
```

The bridge now retains only content-addressed `published/<sha256>` files, removes its transient store/trace roots, creates a fresh generated run directory, and recovers/publishes partial traces before allowing another trace start. The regression sends `maxBytes:1`, then successfully starts and stops a normal trace. Reviewer recheck cleared both findings, and the focused test produced:

```text
BUILD SUCCESSFUL in 6s
3 actionable tasks: 3 executed
```

### Final focused green

```text
../../../gradlew -p . --init-script ../treatments/harness/build-overlay.gradle.kts test --tests benchmark.palisade.HarnessBridgeTest --rerun-tasks --no-daemon
BUILD SUCCESSFUL in 6s
3 actionable tasks: 3 executed

python3 scripts/test-treatment-symmetry.py
....
Ran 4 tests in 0.023s
OK
```

## Self-review

- `HarnessBridge.open(CandidateUi, Path)` validates the application-owned `Stage` before attaching and never calls `CandidateUi.dispose()` or `Stage.dispose()`.
- One `Scene2dSession` supplies semantics, snapshotting, locator actions, waits, and screenshot metadata. `beforeRender()` drains only bounded queued work; `afterRender()` fences only the completed application draw.
- The CLI delegates schemas and semantics to the published `HarnessToolCatalog` and `HarnessToolHandler`; it does not define a second locator/action convention.
- The CLI envelope is closed to exactly `operation` and `arguments`, and the catalog restricts operation names to the nine V1 names. Process arguments, class selection, scripts, arbitrary commands, and artifact paths are not inputs.
- Input bytes, command count, JSON nesting/token sizes, operation deadlines, response bytes, screenshot limits, trace duration/bytes, artifact count/bytes, scheduler/fence queues, and owned-directory traversal depth are bounded. Oversized unterminated input stops at the byte boundary instead of being drained indefinitely.
- Artifact publication uses opaque content-addressed identifiers and the production bounded `FileArtifactStore`. The bridge requires a new run root below an existing non-symlink parent, atomically retains only `published/<sha256>` evidence, removes transient trace/store roots, and never deletes sibling application files.
- The overlay uses only the requested published harness coordinates for harness functionality and selects `HarnessCli` after the neutral build has finished configuring its launcher.
- The symmetry validator freezes the shared protocol/template/corpus digests, locks the exact common wording and each approved appendix, rejects symlinks and unexpected treatment files, and proves rejection of one-arm design hints and hidden acceptance assertions.

## Commits

```text
0a66467089c340036107b65fd11651d57d14b16d feat(benchmark): add harness treatment access
f345c263c18a4944fc8a2c79c8869dcf2ee74cda fix(benchmark): harden harness treatment overlay
790fb91835febc8ad83ca78be775bd6c69be9e07 fix(benchmark): retain harness run evidence
```

## Concerns

- LWJGL emits its known `ThreadLocalUtil` unsupported-JNI-version warning under Java 25 during the real focused test; the application and all assertions still complete successfully.
- Clean runs intentionally accumulate under generated `build/harness-artifacts/run-*` directories so evidence remains inspectable. A later benchmark runner must archive or retire completed run directories; runner lifecycle is outside Task 3.
