# Task 2 report: treatment-neutral candidate template

## Status

Complete. The standalone Java 25/libGDX 1.14.2/LWJGL3 template launches a blank root `Stage`, executes a finite bounded local NDJSON stream on the render thread, advances Scene2D with a fixed 1/60-second step, writes bounded results and completed-frame PNGs atomically, and exits after `close` or command exhaustion.

## Files

Created under `benchmarks/agentic-palisade/template/`:

- `settings.gradle.kts` — independent Gradle settings and Maven Central repository policy.
- `build.gradle.kts` — Java 25 application build with libGDX 1.14.2, LWJGL3 desktop natives, JUnit, and macOS `-XstartOnFirstThread` application defaults.
- `src/main/java/benchmark/palisade/CandidateUi.java` — required candidate interface.
- `src/main/java/benchmark/palisade/CandidateState.java` — defensively copied, bounded JSON-compatible state tree.
- `src/main/java/benchmark/palisade/CandidateApplication.java` — exact-target loader, blank fallback, fixed-step Stage lifecycle, and completed-frame control handoff.
- `src/main/java/benchmark/palisade/CandidateLauncher.java` — first-thread LWJGL3 entry point and strict `--commands`/`--evidence` arguments.
- `src/main/java/benchmark/palisade/BenchmarkControl.java` — bounded decoder; `capture`, `resize`, `key`, `pointer`, and `close`; safe capture IDs; completed-frame PNG capture; fixed evidence layout; atomic writes.
- `src/test/java/benchmark/palisade/TemplateContractTest.java` — contract, nested-state, and real subprocess integration coverage.

No Task 1 corpus or protocol file was changed.

## TDD evidence

### Initial red

Command, from `benchmarks/agentic-palisade/template/`:

```text
../../../gradlew test --tests benchmark.palisade.TemplateContractTest --no-daemon
```

Observed result before main types existed:

```text
> Task :compileJava NO-SOURCE
> Task :compileTestJava FAILED
error: cannot find symbol CandidateUi
error: cannot find symbol CandidateState
error: cannot find symbol CandidateLauncher
8 errors
BUILD FAILED in 10s
1 actionable task: 1 executed
```

This was the expected missing-type failure.

### Nested-state red/green refinement

Red command:

```text
../../../gradlew test --tests benchmark.palisade.TemplateContractTest.candidateStateSupportsBoundedNestedJsonDataAndDefensiveCopies --no-daemon
```

Observed before nested JSON-compatible state support:

```text
TemplateContractTest > candidateStateSupportsBoundedNestedJsonDataAndDefensiveCopies() FAILED
java.lang.IllegalArgumentException at TemplateContractTest.java:48
1 test completed, 1 failed
BUILD FAILED in 5s
```

After implementing bounded recursive defensive copies and serialization, the same command produced:

```text
BUILD SUCCESSFUL in 5s
3 actionable tasks: 2 executed, 1 up-to-date
```

### Final focused green

The available display was `DISPLAY=:0`; Xvfb was not installed, so the required real LWJGL3 process ran on that display.

Command:

```text
../../../gradlew clean test --tests benchmark.palisade.TemplateContractTest --no-daemon
```

Observed result:

```text
> Task :clean
> Task :compileJava
> Task :classes
> Task :compileTestJava
> Task :testClasses
> Task :test
BUILD SUCCESSFUL in 6s
4 actionable tasks: 4 executed
```

JUnit XML recorded `tests="3" skipped="0" failures="0" errors="0"`. The integration test launched a separate JVM/LWJGL3 process, rejected an unknown command and `../escape` capture ID, atomically recorded seven ordered JSON results, decoded valid 1280x720 and 1920x1080 PNGs, found no escaped artifact or temporary evidence file, and observed process exit code 0.

### Key-dispatch review fix

Regression command:

```text
../../../gradlew test \
  --tests benchmark.palisade.TemplateContractTest.malformedKeyCharacterIsRejectedBeforeAnyInputDispatch \
  --tests benchmark.palisade.TemplateContractTest.keyCallbackFailureReleasesPressedKeyAndModifiersWithoutReplacingFailure \
  --no-daemon
```

After correcting the test fixture so it exercised `BenchmarkControl`, the expected red result was:

```text
TemplateContractTest > malformedKeyCharacterIsRejectedBeforeAnyInputDispatch() FAILED
expected: <true> but was: <false>
TemplateContractTest > keyCallbackFailureReleasesPressedKeyAndModifiersWithoutReplacingFailure() FAILED
expected key events included up:29 but observed no up:29
2 tests completed, 2 failed
BUILD FAILED in 5s
```

This demonstrated both root causes: optional character validation occurred after modifier/key dispatch, and a callback failure between key-down and key-up left the primary key logically down.

After parsing the complete key command before dispatch and adding balanced failure cleanup, the identical command produced:

```text
BUILD SUCCESSFUL in 5s
3 actionable tasks: 2 executed, 1 up-to-date
```

The first complete focused run then exposed an existing asynchronous resize race rather than a key regression:

```text
../../../gradlew clean test --tests benchmark.palisade.TemplateContractTest --no-daemon
TemplateContractTest > launchesCapturesBothViewportsRejectsUnsafeCommandsAndExitsCleanly() FAILED
expected: <1920> but was: <1280>
5 tests completed, 1 failed
BUILD FAILED in 6s
```

`BenchmarkControl` now retains a resize command until the completed back buffer reaches the requested bounded dimensions, so the next command cannot race the native resize. Final focused commands and results:

```text
../../../gradlew clean test --tests benchmark.palisade.TemplateContractTest --no-daemon
BUILD SUCCESSFUL in 6s
4 actionable tasks: 4 executed

../../../gradlew test --tests benchmark.palisade.TemplateContractTest --rerun-tasks --no-daemon
BUILD SUCCESSFUL in 6s
3 actionable tasks: 3 executed
```

The repeated final JUnit XML recorded `tests="5" skipped="0" failures="0" errors="0"`.

## Self-review

- The measured implementation target is loaded only by the exact name `benchmark.palisade.SkirmishConfigurationUi`; when present it must implement `CandidateUi` and expose a public no-argument constructor.
- The pre-treatment template intentionally falls back to a private blank candidate containing only an empty root `Stage`, allowing the standalone template contract to launch before a measured candidate is authored.
- Commands are preloaded from a non-symlink regular local file and bounded by file bytes, line characters, and command count. Each command has a closed field set and bounded values.
- Capture IDs are tokens, not paths; generated PNGs are restricted to the fixed `captures/<id>.png` layout. Result output is fixed at `results.ndjson`. There is no caller-selected artifact path inside the evidence directory.
- Results are bounded per line, candidate state is bounded recursively, viewport dimensions and command count are bounded, and all outputs remain finite.
- PNGs are read after `Stage.draw()` from the completed back buffer with vertical correction. Both required exact dimensions were decoded through `ImageIO` in the real subprocess test.
- Every result-file replacement and PNG publication uses a same-directory temporary file, file flush, and atomic move; unsupported atomic filesystems fail closed.
- Source review found no socket/listener, process execution, runtime command, harness package, or harness dependency in production sources. The only transport is the finite local command file.
- Java application construction is synchronous in `main`; Gradle supplies `-XstartOnFirstThread` on macOS.
- A separate focused reviewer initially raised blank-target and high-DPI concerns, then retracted both after applying the explicit blank-template requirement and protocol device-scale-factor-1 precondition. The corrected review had no actionable findings.
- Key commands are fully parsed and action-specifically validated before any `Stage` input method is called. Pressed keys and scoped modifiers are released on callback failure; cleanup failures are suppressed onto the original runtime failure rather than replacing it.
- Native resize completion is condition-based and bounded to 120 completed frames before failing closed, preventing the following capture from observing the prior viewport.

## Commit

Template implementation commit:

```text
395150f64a2ef9fa06871d2f37ae05837440944a feat(benchmark): add neutral candidate template
```

Key-dispatch review-fix commit:

```text
0353296fbe2434ab367c11af0a595eb319c30f78 fix(benchmark): balance validated key dispatch
```

## Concerns

- Captures intentionally rely on the benchmark protocol's device scale factor 1. `HdpiMode.Pixels` preserves pixel coordinates under that precondition; a runner on a Retina/high-DPI display must provide the declared scale-1 environment.
- Evidence publication deliberately fails rather than degrading to a non-atomic move when the evidence filesystem does not support same-directory atomic moves.

## Canonical key-name repair

### Regression red

The protocol-facing names are exact uppercase canonical names, while
`Input.Keys.valueOf` resolves libGDX's display-case names. The focused regression command was:

```text
../../../gradlew test \
  --tests benchmark.palisade.TemplateContractTest.canonicalUppercaseKeyNamesDispatchBalancedPressEvents \
  --tests benchmark.palisade.TemplateContractTest.nonCanonicalKeyNameCasingIsRejectedBeforeInputDispatch \
  --tests benchmark.palisade.TemplateContractTest.pressRejectsEveryMemberOutsideItsClosedSchemaBeforeInputDispatch \
  --no-daemon --console=plain
```

Before the boundary fix, the expected red result was:

```text
TemplateContractTest > canonicalUppercaseKeyNamesDispatchBalancedPressEvents() FAILED
LEFT ==> expected: <[down:21, up:21]> but was: <[]>
3 tests completed, 1 failed
BUILD FAILED in 5s
```

JUnit XML recorded `tests="3" skipped="0" failures="1" errors="0"`. The casing-policy
and closed-schema tests passed in the red run; only real uppercase canonical key dispatch
failed.

### Regression green

After the fix, the identical command produced:

```text
BUILD SUCCESSFUL in 5s
3 actionable tasks: 2 executed, 1 up-to-date
```

The complete focused contract command then passed:

```text
../../../gradlew test --tests benchmark.palisade.TemplateContractTest \
  --no-daemon --console=plain
BUILD SUCCESSFUL in 6s
3 actionable tasks: 2 executed, 1 up-to-date
```

JUnit XML recorded `tests="8" skipped="0" failures="0" errors="0"`.

### Real launcher smoke

The finite input was:

```text
{"command":"key","action":"press","key":"TAB"}
{"command":"key","action":"press","key":"ENTER"}
{"command":"key","action":"press","key":"ESCAPE"}
{"command":"close"}
```

It was executed through the real LWJGL3 launcher:

```text
../../../gradlew run \
  --args="--commands /tmp/palisade-key-smoke-20260729.ndjson --evidence /tmp/palisade-key-smoke-20260729-evidence" \
  --no-daemon --console=plain
BUILD SUCCESSFUL in 4s
2 actionable tasks: 1 executed, 1 up-to-date
```

The launcher exited with status 0 and wrote these ordered results:

```text
{"sequence":0,"command":"key","ok":true,"state":{}}
{"sequence":1,"command":"key","ok":true,"state":{}}
{"sequence":2,"command":"key","ok":true,"state":{}}
{"sequence":3,"command":"close","ok":true,"state":{}}
```

### Canonical key-name self-review

- A single bounded startup map derives exact uppercase protocol names from libGDX's
  display names and retains those display names for `Input.Keys.valueOf`; it is not a
  hand-maintained alias table.
- Per-command lookup is exact, so mixed- and lowercase spellings remain rejected as
  `INVALID_KEY` instead of becoming undeclared aliases.
- `parseKey` still validates the complete action-specific command before `applyKey`
  emits any event. The press-schema regression enumerates every member belonging to
  the other command shapes plus an arbitrary unknown member and observes zero events.
- Successful presses still dispatch the real libGDX code as one balanced down/up pair.
  Callback-failure cleanup and modifier release paths were not widened or bypassed.
- No evaluator, frozen corpus, or public protocol file was changed.
