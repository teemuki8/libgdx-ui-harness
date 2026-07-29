# Task 5 report: isolated OMP execution and exact telemetry

## Status

Complete. The runner prepares the fixed three baseline/harness pairs in six UUID-bound repositories, supervises the six OMP process groups concurrently, enforces the 45-minute/three-round precommitment, and writes immutable hash-bound input manifests and retained run outcomes. It does not run measured agents, evaluate candidates, blind outputs, or add CI.

## TDD evidence

### Initial red

From `benchmarks/agentic-palisade/`:

```text
python3 scripts/test-telemetry.py
```

Failed at `setUpClass` with the expected missing `scripts/parse-omp-session.py`.

```text
python3 scripts/test-runner.py
```

Failed at both test classes with the expected missing `scripts/run-benchmark.py`.

### Red/green refinements

- The first runner green attempt exposed a fake-executable shebang fixture error, an insufficient existing-output diagnostic, and a manifest assertion using the wrong nesting. The fixture/assertion were corrected and the production diagnostic now says the output directory already exists.
- The process-group fixture then remained in state `S` after the leader accepted `SIGTERM`. `terminate_process_group` was corrected to wait the full grace interval and send `SIGKILL` to the process group even when the leader has already exited. The child is now absent or a terminal zombie rather than running.
- Telemetry fixture tests were green after implementation: 8 tests, 0 failures. They cover all provider token categories, explicit unavailable categories, partial-category fail-closed accounting, malformed/truncated JSONL, hidden-reasoning exclusion, failed tools, and exact round ordering.

### Runner/telemetry focused green

```text
python3 -m unittest scripts/test-telemetry.py scripts/test-runner.py
............
Ran 12 tests in 1.053s
OK
```

The runner fixture creates six concurrent fake OMP processes and observes one success plus retained missing-round, rejected-duplicate, nonzero-exit, process-group-timeout, and malformed-session outcomes. Parser mutation cases separately reject missing, duplicate, reordered, overflow, and rejected round attempts. Exactly six invocation records exist; no retry is issued.

### Prerequisite symmetry-coordinate repair

The existing symmetry suite was red before Task 5 integration because its
neutral-template coordinate still predated the approved Task 2 template
change:

```text
python3 scripts/test-treatment-symmetry.py
FAILED (failures=4): shared input changed: template
```

With parent authorization, only `EXPECTED_SHARED_HASHES["template"]` was
updated from `4f165a...` to the current trusted digest
`137777c7c69bda2865e889df7b9f74cbf76d8060e2deb7e783dcbc12b39501c4`.
No validation was loosened and no frozen input changed. The validator and all
three mutation probes then passed:

```text
python3 scripts/test-treatment-symmetry.py
....
Ran 4 tests in 0.029s
OK
```

Final combined verification:

```text
python3 -m unittest scripts/test-telemetry.py scripts/test-runner.py scripts/test-treatment-symmetry.py
....................
Ran 20 tests in 0.960s
OK
```

### Independent review fixes

The first independent review found four fail-closed gaps. Focused regressions
were red before each fix: a protected input could drift undetected; a custom
OMP path accepted `500ms`; a newline-terminated session with an unfinished tool
call parsed successfully; and a candidate-created symlink prevented the sixth
run record from being written. After the fixes:
The re-review identified the sibling harness overlay as one more protected
input; its mutation regression failed before overlay hashing was added and
passed afterward.

```text
python3 -m unittest \
  scripts.test-telemetry.TelemetryTest.test_rejects_a_complete_export_with_an_unfinished_tool_call \
  scripts.test-runner.DryRunTest.test_detects_any_protected_input_drift \
  scripts.test-runner.DryRunTest.test_rejects_short_deadline_even_with_a_custom_omp_path \
  scripts.test-runner.SupervisionTest.test_runs_six_concurrently_with_fixed_isolation_and_retains_failures \
  scripts.test-runner.SupervisionTest.test_terminates_the_entire_process_group_after_timeout
.....
Ran 5 tests in 0.722s
OK
```

Protected instructions, protocol, corpus, and harness overlay are read-only
and rehashed after the process; drift or an unexpected baseline overlay is an
`input_integrity_failure`. The 45-minute limit is unconditional. Every tool
call must have one matching result. Final candidate hash failures publish
`null` plus a retained `final_candidate_hash` failure instead of dropping the
record.

### Fix round 1: runner-owned rounds and authoritative outcomes

The collision/forgery integration regression was red against the prior
implementation: a fake measured process precreated `run-record.json`, which
was then loaded as `{\"forged\": true}` and caused the six-record assertion to
fail. The same process wrote a formerly valid three-round `rounds.jsonl`
without making a third gate request, then sent a syntactically valid round-3
request directly to the supervisor. Before peer binding, that direct request
was accepted and incorrectly changed the run classification to `success`.

Round acceptance now travels through a per-run Unix supervisor channel. The
fixed `benchmark-feedback` executable sends one schema-bound request and waits
for its matching result. Linux peer credentials and `/proc/<pid>/cmdline` bind
the request to the exact immutable gate path and round argument; direct socket
requests are rejected. The runner validates order/limit, independently hashes
the candidate, and retains request/result identity in memory. Only
after the measured process group has terminated does the runner atomically
replace `rounds.jsonl` with its own evidence and publish an immutable SHA-256
sidecar. The forged file produced no third acceptance; authoritative accepted
rounds remained `[1, 2]`.

`run-record.json` is likewise serialized to a same-directory temporary file,
fsynced, atomically replaced over any candidate collision, chmod-protected,
and bound by `run-record.sha256`. The forged record and forged sidecar were
both replaced; all six authoritative records remained available.

```text
python3 -m unittest scripts/test-telemetry.py scripts/test-runner.py scripts/test-treatment-symmetry.py
....................
Ran 20 tests in 0.980s
OK
```

The post-fix six-record failure fixture validated against the Draft 2020-12
run-record schema with format checking: 6 records validated.

### Dry-run proof

No agent was invoked:

```text
python3 scripts/run-benchmark.py \
  --output /tmp/agentic-palisade-task5-dry-run-gate \
  --model openai-codex/gpt-5.6-sol:medium \
  --max-time 45m \
  --pairs 3 \
  --dry-run
{"status": "prepared", "runs": 6, "output": "/tmp/agentic-palisade-task5-dry-run-gate"}
```

The dry-run test proves three matched pairs, six UUIDs, six distinct workspace/profile/cache/session/artifact/display coordinates, identical candidate hashes, an identical instruction prefix, and byte-identical shared repository inventories. The only inventory differences are `template/INSTRUCTIONS.md` after `## Treatment appendix` and the source-identical `treatments/harness/` overlay tree. Top-level and per-run input manifests have mode `0444`; mutating a candidate changes its recorded candidate hash.

A generated six-record failure-retention run was also validated with Python `jsonschema` Draft 2020-12 plus format checking against `scripts/schemas/run-record.schema.json`: 6 records validated.

## Interfaces

- `scripts/run-benchmark.py --output <new-dir> --model openai-codex/gpt-5.6-sol:medium --max-time 45m --pairs 3 [--dry-run]` rejects an existing output, any other measured model/pair count/deadline, and writes `benchmark-manifest.json` once with mode `0444`.
- Each run contains a fresh minimal repository skeleton and candidate template at `runs/<uuid>/repository/benchmarks/agentic-palisade/template`, plus unique `profile-home`, cache, Gradle cache, session, temporary, log, artifact, gate, and display coordinates. Generated template `.gradle`, `build`, Python caches, and symlinks are not copied.
- Each OMP invocation fixes model/reasoning, cwd, JSON print mode, profile, session directory, config overlay, 45-minute internal limit, tool allowlist, auto approval/yolo, and disabled extensions/skills/rules/LSP/title generation. The supervisor adds the same external deadline and starts a new process session for whole-group termination.
- The child environment is allowlisted and replaces `HOME`, all XDG roots, Gradle cache, temporary directory, display, OMP artifact root, and benchmark paths. API keys, SSH agents/askpass, cloud credential paths, Docker/Kubernetes config, PI tool-bridge state, and proxy/config leakage are not inherited.
- `prompts/task.md` requires an initial candidate followed by `benchmark-feedback 1`, `2`, and `3` exactly once and in order. The fixed gate exchanges a schema-bound request/result with the runner-owned per-run supervisor; only the runner records accepted/rejected attempts, candidate hashes, request IDs, and ordered evidence after process termination. Missing, duplicate, reordered, overflow, malformed, or forged-file rounds fail closed.
- `scripts/parse-omp-session.py` accepts exactly one newline-terminated OMP v3 session JSONL export. It reads only stable assistant `usage`, `toolCall`, and `toolResult` fields; it never reads or interprets `thinking`. It aggregates input/output/cache-read/cache-write/reasoning categories only when every provider usage row supplies that category; otherwise the category is explicitly `unavailable` rather than undercounted.
- `run-record.json` conforms to `agentic-palisade/run-record-v1` and records hashes, timestamps/wall time, exit code/signal/classification, provider tokens, tools by name, edit/build/launch/screenshot counts, tool and runner failures, and up to three gate-bound round markers. After process termination it atomically replaces any collision, is protected with mode `0444`, and is bound by an immutable SHA-256 sidecar. Timed-out, crashed, nonzero, malformed-telemetry, round-protocol, protected-input, and final-hash failures retain raw stdout/stderr, session bytes, artifacts, runner-owned round evidence, and either the final candidate hash or explicit `null` when hashing itself failed.

## Self-review

- No candidate, corpus, protocol, template, treatment, evaluator, or blinding file changed. `git diff --quiet 96faaf7` confirmed the frozen protocol/corpus/template/treatments remain unchanged.
- Candidate hashing excludes frozen public inputs and generated `.gradle`/`build`/Python caches, while instructions, protocol, the complete corpus tree, and the harness overlay are independently rehashed after execution. Input/template/corpus/protocol/prompt/treatment hashes remain separately bound in immutable manifests; a final candidate hash failure is retained rather than aborting record publication.
- Direct process results take precedence over derived telemetry failures, so timeouts, signals, and nonzero exits cannot be mislabeled or erased by a missing/truncated session. A zero exit still fails closed on malformed telemetry or any round protocol violation.
- No retry path exists. All six workspaces are prepared before the six-worker executor starts, and every worker publishes one retained run record even if OMP cannot launch.
- The schema permits fewer than three accepted markers only because failed outcomes must remain serializable; successful classification requires the parser to observe exactly `[1, 2, 3]`.
- An independent reviewer completed two fix rounds and reported no remaining Critical or Important finding after protected-input/overlay integrity, unconditional deadline, unfinished-tool, and final-hash retention fixes.

## Commit

Implementation: `c33675e` (`feat(benchmark): isolate OMP runs and telemetry`).
Review fixes, prerequisite coordinate repair, and evidence report: `956630c` (`fix(benchmark): fail closed on run integrity`).

## Concerns

- The runner deliberately strips credentials from the child environment. A measured operator must provide OMP authentication through an OMP-supported broker/profile mechanism that does not expose or mount credentials into candidate tools; this task did not provision credentials or execute measured agents.
- Displays `:220` through `:225` are isolated coordinates, but the operator must provide those display servers before measured execution. An unavailable display is retained as a launch/build failure and is never retried.
- No additional known implementation concern. The two operational prerequisites above remain explicit and fail as retained outcomes rather than triggering retries.
