# Task 7 Report: Full Benchmark Pipeline Qualification

## Status

Qualified. No model call or human review was executed. The deterministic fixture pipeline completed preparation, six supervised mock OMP v3 runs, trusted evaluation, blind packaging and leakage scan, synthetic response validation and lock, and unblinding. All expected failures remained immutable analysis rows.

## Implementation commit

- `15818ad` — `test: qualify agentic palisade pipeline`
- `146d2be` — `test: retain all qualification channels`

## Qualification command and exact output

```text
$ python3 scripts/qualify-pipeline.py --output /tmp/palisade-task7-qualification-4
{"runs": 6, "status": "qualified", "summary": "/tmp/palisade-task7-qualification-4/qualification-summary.json"}
```

The command exited `0` in 130.91 seconds. Its summary reported:

```text
schemaVersion=agentic-palisade/qualification-v1
status=qualified
runnerExit=1
runCount=6
retainedRunRecords=6
identityContinuity=true
treatmentSymmetry=true
noProcessLeaks=true
deterministicRerunHashes=true
goodMeanSsim=0.8399171985271313
brokenMeanSsim=0.028715528589484913
ssimDelta=0.8112016699376464
finalReportSha256=b2d510e1e3744a6e653584ac59e72e3890aafb37fe06eee05ba275be84961075
missing-capture mutation: rejected=true, dataRetained=true
mapping-tamper mutation: rejected=true, dataRetained=true
response-tamper mutation: rejected=true, dataRetained=true
```

## Fixture identity

```text
26aab3890073bde7b2e69a0f2b5910295877fd66f2c22885ae65e412d6abbdf2  benchmarks/agentic-palisade/fixtures/good-candidate.patch
ae122a98720f58a77c603c1bf6a1d265a2cb4ebc53ce0bf1fd868fe96563e4bd  benchmarks/agentic-palisade/fixtures/broken-candidate.patch
ee0e5a340a0603e99f29dd3b59dd9e0f857ffafd85a04fc9cfc3495af277354c  benchmarks/agentic-palisade/fixtures/mock-omp.py
```

The good candidate passed all `25/25` hidden functional assertions. The intentionally low-fidelity runnable candidate passed `2/25`; its mean scale-1 luminance SSIM was lower by `0.8112016699376464`. `mock-omp.py` selects cases only from the fixed `(pair, treatment)` matrix, writes complete OMP v3 exports except for the deliberate malformed export, invokes all three production round gates, and never calls a model.

## Failure matrix

| Pair | Arm | Fixture case | Runner classification | Evaluator status | Retained in final report |
|---:|---|---|---|---|---|
| 1 | baseline | conforming | `success` | `complete` | yes |
| 1 | harness | uncompilable | `success` | `compile-failed` | yes |
| 2 | baseline | timeout | `timed_out` | `complete` | yes |
| 2 | harness | malformed OMP export | `telemetry_failure` | `complete` | yes |
| 3 | baseline | missing capture | `success` | `runtime-failed` | yes |
| 3 | harness | leaked child | `round_supervisor_failure` | `complete` | yes |

No run was retried or manually corrected. The runner's expected nonzero exit was retained as `runnerExit=1`; the qualification command returned zero only after verifying the complete expected matrix. The child PID was absent or zombie after supervisor cleanup. The missing-capture, private-mapping, and response mutation copies all failed closed while their six run records and original sealed inputs remained unchanged.

## Focused commands and exact outputs

```text
$ python3 scripts/test-qualification.py
.
----------------------------------------------------------------------
Ran 1 test in 137.898s

OK
```

```text
$ python3 scripts/test-corpus.py
PASS: agentic-palisade/v1 corpus is internally consistent
```

```text
$ python3 scripts/test-treatment-symmetry.py
....
----------------------------------------------------------------------
Ran 4 tests in 0.033s

OK
```

```text
$ python3 scripts/test-telemetry.py
.........
----------------------------------------------------------------------
Ran 9 tests in 0.003s

OK
```

```text
$ python3 scripts/test-runner.py
.........
----------------------------------------------------------------------
Ran 9 tests in 2.801s

OK
```

```text
$ python3 scripts/test-blinding.py
...........
----------------------------------------------------------------------
Ran 11 tests in 13.316s

OK
```

```text
$ ./gradlew -p benchmarks/agentic-palisade/evaluator test --no-daemon --console=plain --warning-mode=fail
BUILD SUCCESSFUL in 9s
3 actionable tasks: 1 executed, 2 up-to-date
```

```text
$ ./gradlew -p benchmarks/agentic-palisade/template test --no-daemon --console=plain --warning-mode=fail
BUILD SUCCESSFUL in 5s
3 actionable tasks: 1 executed, 2 up-to-date
```

```text
$ python3 scripts/validate-workflows.py
workflow security invariants: PASS
```

```text
$ ./gradlew clean check javadoc --warning-mode=fail
BUILD SUCCESSFUL in 44s
59 actionable tasks: 59 executed
```

```text
$ git diff --check
```

The last command produced no output and exited `0`.

## Proven integration defects fixed at source

1. The trusted evaluator recorded capture identities but deleted its temporary evidence without publishing the files. It now atomically publishes every hash-verified artifact plus `evaluation.json` and `evaluation.sha256`; a focused publication regression covers this.
2. The runner output omitted the frozen corpus and trusted template required by the evaluator and blinder CLIs. Dry-run regressions now bind and verify both published trees.
3. Runner and evaluator used incompatible candidate/corpus tree digest algorithms. The evaluator now uses the runner's file-only, generated-input-aware digest contract, covered by literal cross-contract hash regressions.
4. Deep output paths exceeded the AF_UNIX path limit. Round sockets now use a bounded UUID path in the platform temporary directory; a deep-output supervision regression covers it.
5. Blind packaging, review locking, and visual aggregation assumed every evaluation had fifteen captures. Failed evaluations now remain as capture-free blinded candidates, validate and lock normally, and contribute explicit empty visual outcomes without breaking arm summaries. Complete evaluations remain strictly required to provide all canonical captures.
6. The corpus leakage check attempted to decode generated Gradle class files after focused builds. It now excludes only the established generated directory names while continuing to scan all committed benchmark inputs.
7. Final-channel continuity originally checked only the functional raw row count. Qualification now requires the exact same six unique run IDs in `functional`, `automatedVisual`, `humanVisual`, and `telemetryTreatment`; unavailable token categories remain explicit `null` raw metrics, and independent drop mutations for all four channels must be rejected without modifying their bytes.
8. Response-tamper retention originally compared the untouched primary response. Qualification now hashes and reads the actual tampered `human-ratings.json` before invoking rejection and requires those exact bytes afterward. A destructive deletion regression proves the guard fails if rejected-input handling removes the evidence.

## Self-review

- Confirmed the production runner, evaluator application, blind builder, response locker, and unblinder CLIs are invoked rather than reimplemented.
- Confirmed the public package contains only A–F labels, reference/candidate images, and strict JSON support files; the production leakage scanner and an explicit run-ID/path/treatment scan both passed.
- Confirmed benchmark manifest, input manifest, run record sidecar, final candidate, corpus, evaluation sidecar, private mapping, response, lock, and final report identities remain continuous.
- Confirmed deterministic public package hashes match for two builds from the same frozen input and seed.
- Confirmed CI invokes only fixed fixtures, pins every added action by full SHA, and contains no measured OMP command.
- Confirmed frozen `PROTOCOL.md`, corpus, schema, and reference images were not modified.
- Confirmed each final channel contains the exact same six run IDs, including empty visual outcomes and explicit null token metrics for unavailable evidence.
- Confirmed each of four channel-drop mutations is rejected and the exact mutated report bytes remain present.
- Confirmed response rejection preserves the exact tampered ratings bytes rather than merely preserving the original response.

## Fix-round focused evidence

```text
$ python3 scripts/test-qualification.py
...
----------------------------------------------------------------------
Ran 3 tests in 128.405s

OK
```

```text
$ python3 scripts/test-blinding.py
............
----------------------------------------------------------------------
Ran 12 tests in 13.837s

OK
```

```text
$ python3 scripts/validate-workflows.py
workflow security invariants: PASS
```

## Concerns

- GitHub CI was not pushed or observed from this local worker session. The pinned CI job and workflow validator pass locally; green hosted CI remains a precondition before any real benchmark run.
- The local workstation has an active display but no `xvfb-run` binary. The qualification and native release gate passed on display `:0`; the added Ubuntu CI job explicitly installs Xvfb and runs the qualification under it.
- The signing and Maven Central upload portion of the release workflow was intentionally not invoked because it requires release credentials. The full local build/test/Javadoc gate passed.
