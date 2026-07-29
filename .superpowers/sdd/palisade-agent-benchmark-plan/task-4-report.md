# Task 4 report: hidden functional and visual evaluator

## Status

Complete. The standalone Java 25 evaluator compiles an immutable candidate copy, invokes `benchmark.palisade.CandidateLauncher` explicitly through bounded NDJSON files, validates the resulting evidence, evaluates functional and visual channels independently, and atomically publishes schema `agentic-palisade-evaluation/v1`.

## TDD evidence

### Initial red

From `benchmarks/agentic-palisade/evaluator/`:

```text
../../../gradlew test --tests 'benchmark.palisade.eval.*' --no-daemon
```

After build dependency wiring was corrected, the expected red was missing production types (`VisualMetrics`, `FunctionalContract`, `CandidateEvaluator`, `EvaluationRecord`, and `PublicFeedback`); `compileTestJava` failed with 35 missing-symbol errors.

### Red/green refinements

- Exact identical-image SSIM initially returned `0.9999999999999919`; `VisualMetricsTest.identicalImagesHavePerfectMetrics` failed, then passed after exact-perfection normalization.
- The documented four-option-pair evaluator CLI initially rejected nine arguments at the usage guard; its focused regression failed, then passed after correcting the arity.
- Security regressions were written first for direct scenario-state ownership, explicit `CandidateLauncher`, pre-publication candidate identity, and absent candidate-local resource classpath entries. They initially failed to compile because the required APIs did not exist, then passed after implementation.
- The complete public-behavior fixture was tightened to require bounded, evaluator-aggregated focused-control metadata and a RANDOM SEED precondition of seed `1`; the stable-ID set test failed before those assertions were restored, then passed.

### Pre-review focused green

```text
../../../gradlew clean test --tests 'benchmark.palisade.eval.*' --no-daemon
BUILD SUCCESSFUL in 11s
4 actionable tasks: 4 executed
```

JUnit XML records 11 functional tests and 6 visual tests, with 17 tests, 0 skipped, 0 failures, and 0 errors. Coverage includes every declared transition and state, focus order/restoration evidence, the conditional rival-target row, seed boundaries and invalid start, uncompilable-candidate data, candidate/evidence identity, public-feedback redaction, identical/translation/color/noise/repeatability images, and dimension/count rejection.

### Real neutral-interface smoke

With `DISPLAY=:0` and `PALISADE_GRADLE` set to the repository wrapper:

```text
../../../gradlew run --args='evaluate --candidate ../template --corpus ../corpus --output <new-output> --candidate-id blank-template' --no-daemon
complete
BUILD SUCCESSFUL in 32s
```

Before the blocking review fix, the blank template produced exactly one atomic `evaluation.json`: schema `agentic-palisade-evaluation/v1`, status `complete`, functional `0/25`, three visual outcomes, and 29 hash-bound artifacts. The final trusted-workspace evidence superseding this run is recorded below.

The fixed public command also ran successfully:

```text
../../../gradlew run --args='benchmark-feedback <evaluation.json>' --no-daemon
BUILD SUCCESSFUL in 4s
```

Its output contained only aggregate behavioral counts, corpus-declared reference/viewport IDs, and the declared automated visual metrics.

## Interfaces

- `CandidateEvaluator.Request(candidateDirectory, corpusDirectory, newOutputDirectory, candidateId, gradleExecutable)` validates local identities and requires output outside candidate/corpus.
- The evaluator hashes the immutable candidate and frozen corpus, reconstructs a temporary project from SHA-pinned repository-owned template infrastructure, overlays only verified candidate implementation/helpers/assets, resolves the trusted runtime classpath, and explicitly starts the fixed `benchmark.palisade.CandidateLauncher` class with `--commands` and `--evidence`.
- Ordered neutral scenarios use only `resize`, `pointer`, `key`, `capture`, and `close`. Launcher result schemas, sequence/command/artifact identity, exact evidence layout, extras, viewport dimensions, file bounds, and hashes fail closed.
- `FunctionalContract` returns 25 stable, independent assertions with bounded internal evidence. Compile/runtime failures return zero passed assertions and still publish an evaluation record.
- `VisualMetrics` reports RGB MAE; luminance SSIM at scales 1/2/4; Sobel-edge F1; quantized palette delta; non-background bounds displacement; four clipping flags; five-capture repeatability; and a separate high-pass font-raster residual. It uses only Java ImageIO and bounded dimensions/files/arrays.
- `EvaluationRecord` keeps exact functional and visual channels separate and defines no composite score.
- `PublicFeedback` implements fixed command `benchmark-feedback` and excludes assertion IDs, internal evidence, expected values, hashes, candidate identity, source, and implementation details.

## Self-review

- Candidate-controlled captures are bounded and decoded before any hash stream; references use the same bounded loader before hashing.
- Every evidence directory has an exact root/capture allowlist. Functional and visual result files and all fifteen candidate captures are recorded with SHA-256 and byte length.
- Candidate identity is rehashed before publication. Unsupported atomic moves fail rather than falling back to a non-atomic write; unsuccessful publication removes the temporary file/output directory.
- Candidate checkpoint bundles are ignored. Evaluator-owned checkpoints contain direct states observed after evaluator-driven commands; full control metadata is aggregated from one bounded `focusedControl` observation per focus step so no `CandidateState` exceeds its 128-node budget.
- RANDOM SEED first replaces the seed with `1`; the assertion requires both that previous value and the fixture result `305419896`, preventing a no-op button from passing.
- Uncompilable candidates are tested through invalid Java overlaid onto the trusted Gradle project and produce `compile-failed`, zero passed assertions, unchanged source identity, and one atomic output file.
- Two focused independent reviews were performed. The final reviewer found no remaining Critical or Important Task 4 issue after explicit-launcher, checkpoint ownership, CandidateState-budget, publication-order, bounded-decode/hash-order, RANDOM precondition, and metadata fixes.

## Blocking review fix round 1

The evaluator no longer copies or executes candidate-controlled Gradle,
settings, launcher, or control infrastructure. It reconstructs the execution
workspace from seven repository-owned, SHA-256-pinned neutral template files,
then overlays only candidate Java helpers/implementation sources,
`src/main/resources`, and `assets`. Modified reserved files, reserved type
declarations, executable resource collisions, symlinks, destination
collisions, and source/destination hash drift are rejected before Gradle can
run. The regression candidate contains both a forged `build.gradle.kts` with
an execution marker and a forged `CandidateLauncher`; evaluation returns
`invalid-candidate`, zero passed assertions, an atomic record, and no marker.

Focus commands now derive all absolute and relative Tab distances from each
initial `visibleControls` observation instead of corpus focus numbers. With
the conditional `rivalTargetCount` hidden, the asserted sequences are:
`victoryCondition=6`, `seed=14`, `copySeed=15`, `randomSeed=16`, `cancel=17`,
and `startBattle=18`; the hidden target is rejected as non-visible. Relative
seed-to-random and seed-to-start transitions are likewise derived from the
same observed list.

Focused red/green command:

```text
../../../gradlew test \
  --tests 'benchmark.palisade.eval.FunctionalContractTest.forgedBuildAndNeutralControlAreRejectedWithoutExecution' \
  --tests 'benchmark.palisade.eval.FunctionalContractTest.focusNavigationUsesVisibleControlsRatherThanCorpusFocusNumbers' \
  --tests 'benchmark.palisade.eval.FunctionalContractTest.uncompilableCandidatePublishesZeroPassEvaluationWithoutChangingCandidate' \
  --no-daemon
BUILD SUCCESSFUL in 10s
```

Final review-fix verification:

```text
../../../gradlew clean test --tests 'benchmark.palisade.eval.*' --no-daemon
BUILD SUCCESSFUL in 10s
4 actionable tasks: 4 executed
```

The focused suite now records 19 tests: 13 functional and 6 visual, with zero
failures, errors, or skips. The real trusted-workspace blank-template smoke
returned `complete`, functional `0/25`, three visual outcomes, 17 hash-bound
artifacts, and exactly one atomic `evaluation.json` in 22 seconds. An
independent focused re-review found no remaining Critical or Important issue
in either blocking review area.

## Commit

Evaluator implementation:

```text
747397cc0a6414c712c5cbae41357debcb393f36 feat(benchmark): add hidden Palisade evaluator
```

## Concerns

- The frozen template documents `CandidateState` as bounded JSON-compatible state but does not declare the observable field naming convention used by the hidden evaluator (`values`, `visibleControls`, `focusedControlId`/`focusId`, `focusedControl`, validation/outcome/payload fields). The evaluator fails closed when those observations are unavailable, but this weak documentation can classify an otherwise behaving candidate as nonconforming.
- The neutral interface can observe final focus after hiding the conditional row only after keyboard focus returns to `victoryCondition`; it cannot directly change the parent select while `rivalTargetCount` itself owns focus because stable-ID actions are absent. The fixture explicitly covers focus-restoration semantics, but production portability is limited to the observable state exposed by the candidate.
- Captures assume the frozen protocol's device-scale-factor-1 environment. Unsupported displays/native stacks remain runtime-failure data rather than being retried or replaced.
