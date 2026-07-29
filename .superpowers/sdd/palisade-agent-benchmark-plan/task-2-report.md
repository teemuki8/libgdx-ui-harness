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

## Commit

Template implementation commit:

```text
395150f64a2ef9fa06871d2f37ae05837440944a feat(benchmark): add neutral candidate template
```

## Concerns

- Captures intentionally rely on the benchmark protocol's device scale factor 1. `HdpiMode.Pixels` preserves pixel coordinates under that precondition; a runner on a Retina/high-DPI display must provide the declared scale-1 environment.
- Evidence publication deliberately fails rather than degrading to a non-atomic move when the evidence filesystem does not support same-directory atomic moves.
