# Gate Iteration Policy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make repeatability qualification fast to iterate: model becomes a sealed environment-stratum identity, failing schedules stop early via a precommitted fail-fast rule, and the per-run ceiling becomes a sealed per-arm parameter (default 40 min, floor 10 min).

**Architecture:** `run-benchmark.py` prepares and supervises the schedule; `release-gate.py` verifies the sealed decision. The model was bound only through treatment hashes and hard-rejected by `FIXED_MODEL`; the ceiling was hard-coded as `FIXED_SECONDS = 45*60`. This plan removes both hard rejections, adds `model` to the sealed environment stratum and `failFast` to the sealed schedule, implements fail-fast cancellation in the supervisor (bounded futures, cancel-on-unrecoverable), and teaches the gate to accept only justified cancellations.

**Tech Stack:** Python 3 (stdlib only: argparse, concurrent.futures, json, hashlib), JSON Schema (draft-07), pytest, Gradle benchmark tests.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-08-06-gate-iteration-policy-design.md` (Approved 2026-08-06).
- Measured baseline: ten retained runs 838–2125 s (max 35.4 min); per-arm `resourceLimits.wallSeconds` and `costCeilings.wallTimeMillis` are sealed per arm already.
- Do NOT change: the all-runs conjunction, the paired-randomization method, the human blind-review gate, or digest fixity of sealed schedules. Any cancelled run without a justified fail-fast reason must fail the gate exactly like a missing run.
- Do NOT change the preflight (exact-model probe) — it stays; only the hard rejection of a different `--model` goes away.
- TDD: every behavior change starts with a failing test.
- Scripts: Python 3, stdlib only, no new dependencies.

---

### Task 1: Free `--model` and `--max-time` in the runner

**Files:**
- Modify: `benchmarks/agentic-palisade/scripts/run-benchmark.py`
- Modify: `benchmarks/agentic-palisade/scripts/test-runner.py`

**Interfaces:**
- Consumes: nothing from later tasks.
- Produces: `_validate_arguments(arguments, max_seconds)` no longer rejects non-fixed model/ceiling; manifest `maxTimeSeconds` uses the parsed value; a module constant `MIN_SECONDS = 10 * 60`.

- [ ] **Step 1: Write the failing test**

In `test-runner.py`, add tests (match existing fixture style; see how `_validate_arguments` is exercised today):
```python
def test_accepts_any_model_and_bounded_max_time():
    args = simple_arguments(model="deepseek/deepseek-v4-flash", max_time="30m")
    # must NOT raise: model differs from FIXED_MODEL, max time differs from 45m
    _validate_arguments(args, parse_duration(args.max_time))


def test_rejects_max_time_below_floor():
    args = simple_arguments(model="deepseek/deepseek-v4-flash", max_time="5m")
    with pytest.raises(ValueError, match="at least"):
        _validate_arguments(args, parse_duration(args.max_time))
```

- [ ] **Step 2: Run to verify failure**

Run: `python3 -m pytest benchmarks/agentic-palisade/scripts/test-runner.py -k 'model or max_time' -q`
Expected: FAIL — `model must be exactly ...` raised.

- [ ] **Step 3: Implement**

In `run-benchmark.py`:
- Add `MIN_SECONDS = 10 * 60` near `FIXED_SECONDS` (keep `FIXED_SECONDS` as the default used by prepare; delete the hard rejections):
```python
def _validate_arguments(arguments, max_seconds):
    required_pairs = RELEASE_PAIRS if arguments.release_candidate else FIXED_PAIRS
    if arguments.pairs != required_pairs:
        raise ValueError(f"pairs must be exactly {required_pairs}")
    if max_seconds < MIN_SECONDS:
        raise ValueError(f"--max-time must be at least {MIN_SECONDS // 60} minutes")
    ...
```
- Remove the `if arguments.model != FIXED_MODEL:` and `if max_seconds != FIXED_SECONDS:` blocks.
- Fix the manifest construction at line ~1744: `"maxTimeSeconds": FIXED_SECONDS` → `"maxTimeSeconds": max_seconds`.

- [ ] **Step 4: Run to verify pass**

Run: `python3 -m pytest benchmarks/agentic-palisade/scripts/test-runner.py -k 'model or max_time' -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add benchmarks/agentic-palisade/scripts/run-benchmark.py benchmarks/agentic-palisade/scripts/test-runner.py
git commit -m "feat(benchmark): accept any model and sealed per-run max-time >= 10m"
```

---

### Task 2: Schema — model stratum and failFast schedule flag

**Files:**
- Modify: `benchmarks/agentic-palisade/scripts/schemas/repeatability-precommitment.schema.json`
- Modify: `benchmarks/agentic-palisade/scripts/test-release-gate.py`

**Interfaces:**
- Produces: precommitment `environments[]` items require non-empty string `model`; precommitment `schedule` items allow optional boolean `failFast` (top-level alternative: `schedule.failFast`); consumed by Task 3 (gate model check) and Task 5 (fail-fast).

- [ ] **Step 1: Write the failing schema test**

In `test-release-gate.py`, extend the schema-validation test (it already loads both schemas and validates fixture precommitments at ~line 205):
```python
def test_precommitment_schema_requires_model_and_allows_fail_fast():
    validator = jsonschema.Draft7Validator(load("repeatability-precommitment.schema.json"))
    missing_model = {"schemaVersion": "agentic-palisade/repeatability-precommitment-v1",
                     "environments": [{"id": "stratum-a"}], "schedule": []}
    assert not validator.is_valid(missing_model)
    with_fail_fast = {"schemaVersion": "agentic-palisade/repeatability-precommitment-v1",
                      "environments": [{"id": "stratum-a", "model": "m"}],
                      "schedule": [{"failFast": True}]}
    assert validator.is_valid(with_fail_fast)
```

- [ ] **Step 2: Run to verify failure**

Run: `python3 -m pytest benchmarks/agentic-palisade/scripts/test-release-gate.py -k schema -q`
Expected: FAIL — schema accepts the model-less environment.

- [ ] **Step 3: Implement the schema**

In `repeatability-precommitment.schema.json`: the `environments` items schema is currently unconstrained (`items: null`). Add an items schema:
```json
"environments": {
  "type": "array",
  "minItems": 1,
  "items": {
    "type": "object",
    "required": ["id", "model"],
    "properties": {
      "id": {"type": "string", "minLength": 1},
      "model": {"type": "string", "minLength": 1}
    }
  }
}
```
(`additionalProperties: true` so the existing environment fields — backend, display, gpu, jvm, os, etc. — remain valid.) For `schedule`, keep the loose items but ensure a `failFast` boolean is permitted; if the schedule has no items schema, add `"properties": {"failFast": {"type": "boolean"}}` on the items object without adding required fields.

- [ ] **Step 4: Run to verify pass**

Run: `python3 -m pytest benchmarks/agentic-palisade/scripts/test-release-gate.py -k schema -q`
Expected: PASS. Also run the full gate suite to confirm the existing fixture precommitments (which lack `model`) are updated if the suite now fails — update the test fixtures to include `"model": "openai-codex/gpt-5.6-sol:medium"` in their environment entries, since the schema now requires it.

- [ ] **Step 5: Commit**

```bash
git add benchmarks/agentic-palisade/scripts/schemas/repeatability-precommitment.schema.json \
  benchmarks/agentic-palisade/scripts/test-release-gate.py
git commit -m "feat(benchmark): seal model as required environment-stratum field; allow failFast"
```

---

### Task 3: Gate — model-stratum consistency and claim scope

**Files:**
- Modify: `benchmarks/agentic-palisade/scripts/release-gate.py`
- Modify: `benchmarks/agentic-palisade/scripts/test-release-gate.py`

**Interfaces:**
- Consumes: Task 2 schema (`environments[].model`).
- Produces: the decision fails when non-cancelled repetitions reference strata with differing or absent models; the decision `scope` text names the qualified model.

- [ ] **Step 1: Write the failing test**

In `test-release-gate.py`, add (reuse the existing fixture builders at ~line 118/165):
```python
def test_decision_rejects_conflicting_stratum_models():
    precommitment, manifest = valid_pair()   # existing fixture helper
    precommitment["environments"][0]["model"] = "model-a"
    # add a second environment with a different model and point one repetition at it
    failures = verify(manifest, precommitment, ...)   # mirror existing verify() call shape
    assert any("model" in failure for failure in failures)
```

- [ ] **Step 2: Run to verify failure**

Run: `python3 -m pytest benchmarks/agentic-palisade/scripts/test-release-gate.py -k model -q`
Expected: FAIL — no model check exists yet.

- [ ] **Step 3: Implement**

In `release-gate.py`, inside the repetition loop (after the environment-stratum bookkeeping at ~line 243), add:
```python
models = {stratum.get("model") for stratum in precommitment.get("environments", [])
          if isinstance(stratum, dict)}
used_models = {environment_by_id.get(environment_id, {}).get("model")
               for environment_id in {r.get("environmentId") for r in repetitions
                                      if r.get("status") != "cancelled"}}
used_models.discard(None)
if len(used_models) != 1:
    _failure(failures, "all completed strata must declare one identical model")
elif len(models) != 1 or next(iter(models)) != next(iter(used_models)):
    _failure(failures, "completed strata model does not match the declared environment model")
qualified_model = next(iter(used_models))
```
And in the decision construction (~line 476), change the scope text to include the model:
```python
"scope": (f"observed matched pairs only, model {qualified_model}; "
          "no population or universal determinism claim"),
```
(Thread `qualified_model` to where the decision dict is built; if the value is empty because all repetitions were cancelled, the scope names no model and the failure above already fired.)

- [ ] **Step 4: Run to verify pass**

Run: `python3 -m pytest benchmarks/agentic-palisade/scripts/test-release-gate.py -q`
Expected: PASS (existing cases + new one; update existing fixtures to declare `model` per Task 2).

- [ ] **Step 5: Commit**

```bash
git add benchmarks/agentic-palisade/scripts/release-gate.py benchmarks/agentic-palisade/scripts/test-release-gate.py
git commit -m "feat(benchmark): gate verifies model-stratum consistency and scopes the claim"
```

---

### Task 4: Runner — fail-fast supervision

**Files:**
- Modify: `benchmarks/agentic-palisade/scripts/run-benchmark.py`
- Modify: `benchmarks/agentic-palisade/scripts/test-runner.py`

**Interfaces:**
- Consumes: Task 2 `schedule[].failFast`.
- Produces: when the sealed schedule declares `failFast: true`, the supervisor cancels pending runs once any required run fails; cancelled runs are recorded with `status: "cancelled"` and `cancelReason`; consumed by Task 5.

- [ ] **Step 1: Write the failing test**

In `test-runner.py`, add a unit test for the new predicate and a supervision test using the qualification mock OMP fixture (mirror existing supervision tests):
```python
def test_fail_fast_cancels_pending_when_required_run_fails(tmp_path):
    # schedule with failFast; two runs; first completes as failure classification
    # assert the second is cancelled and recorded with cancelReason
    ...
```
Exercise `_unrecoverable(classifications, runs)` directly: a single failure classification must be unrecoverable when the schedule requires every run.

- [ ] **Step 2: Run to verify failure**

Run: `python3 -m pytest benchmarks/agentic-palisade/scripts/test-runner.py -k fail_fast -q`
Expected: FAIL — no fail-fast machinery exists.

- [ ] **Step 3: Implement**

In `run-benchmark.py`:
- Add a helper near `_classify`:
```python
def _unrecoverable(classifications):
    return any(classification != "success" for classification in classifications)

def _failure_reason(classifications, runs):
    for item, classification in zip(runs, classifications):
        if classification != "success":
            return {"runId": item["runId"], "classification": classification}
    return None
```
- Replace the supervision block (currently a ThreadPoolExecutor with `max_workers=len(runs)` and a flat `as_completed` loop) with:
```python
classifications = []
cancelled = []
reason = None
with ThreadPoolExecutor(max_workers=len(runs), thread_name_prefix="palisade-run") as executor:
    futures = {executor.submit(_run_one, output, item, arguments.omp, arguments.model,
                               arguments.max_time,
                               QUALIFICATION_SECONDS if arguments.qualification else max_seconds,
                               hashes, arguments.auth_broker_url or FIXED_BROKER_URL,
                               broker_token, not arguments.qualification): item
               for item in runs}
    pending = set(futures)
    for future in as_completed(pending):
        item = futures[future]
        classification = future.result()
        classifications.append(classification)
        pending.discard(future)
        if reason is None and _unrecoverable(classifications):
            reason = _failure_reason(classifications, runs)
            for remaining in list(pending):
                remaining.cancel()
                cancelled_item = futures[remaining]
                pending.discard(remaining)
                cancelled.append({
                    "runId": cancelled_item["runId"],
                    "status": "cancelled",
                    "cancelReason": reason,
                })
    for future in pending:
        future.cancel()
```
- When `reason` is not None, write the cancelled arms into the output run records (append to the existing run-record emission path, or a `cancellations.json` next to the manifest) so the repeatability-manifest construction can record them with `status: "cancelled"` + `cancelReason`. Keep the final summary print; if `reason` is not None, return exit code 1 with `"status": "complete-with-failures"`.

- [ ] **Step 4: Run to verify pass**

Run: `python3 -m pytest benchmarks/agentic-palisade/scripts/test-runner.py -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add benchmarks/agentic-palisade/scripts/run-benchmark.py benchmarks/agentic-palisade/scripts/test-runner.py
git commit -m "feat(benchmark): fail-fast supervision cancels pending runs once the conjunction is unrecoverable"
```

---

### Task 5: Gate — accept only justified cancellations

**Files:**
- Modify: `benchmarks/agentic-palisade/scripts/release-gate.py`
- Modify: `benchmarks/agentic-palisade/scripts/test-release-gate.py`

**Interfaces:**
- Consumes: Task 4 cancelled run records (`status: "cancelled"`, `cancelReason`).
- Produces: cancelled repetitions accepted only when the schedule declares `failFast: true` AND the triggering failure is present in the manifest; otherwise treated as missing runs.

- [ ] **Step 1: Write the failing test**

In `test-release-gate.py`:
```python
def test_justified_cancellation_accepted_when_fail_fast_declared():
    # schedule with failFast true; one repetition cancelled with cancelReason naming a
    # failed run that IS present in the manifest
    assert no failure mentions the cancelled run

def test_cancellation_without_fail_fast_is_missing_run():
    # same cancellation but schedule.failFast false -> failure like a missing run

def test_cancellation_without_triggering_failure_is_rejected():
    # cancelReason references a run that is NOT failed in the manifest -> failure
```

- [ ] **Step 2: Run to verify failure**

Run: `python3 -m pytest benchmarks/agentic-palisade/scripts/test-release-gate.py -k cancel -q`
Expected: FAIL — cancelled repetitions are treated as ordinary missing runs today.

- [ ] **Step 3: Implement**

In `release-gate.py`, inside the repetition loop, before the strict `scheduled != planned` comparison, add:
```python
cancelled = repetition.get("status") == "cancelled"
if cancelled:
    fail_fast = any(isinstance(s, dict) and s.get("failFast") is True
                    for s in precommitment.get("schedule", []))
    if not fail_fast:
        _failure(failures, f"{prefix} cancelled without a precommitted fail-fast rule")
        continue
    reason = repetition.get("cancelReason")
    if not isinstance(reason, dict) or not reason.get("runId"):
        _failure(failures, f"{prefix} cancellation lacks a runId reason")
        continue
    triggering = next((r for r in manifest.get("repetitions", [])
                       if isinstance(r, dict)
                       and r.get("id") == reason.get("runId")), None)
    failed = bool(triggering) and any(
        arm.get("classification") != "success"
        for arm_name in ("candidate", "baseline")
        for arm in [triggering.get(arm_name, {})])
    if not failed:
        _failure(failures, f"{prefix} cancellation reason does not name a failed run")
        continue
    continue  # justified cancellation: skip strict per-arm schedule comparison
```
(Adjust the arm classification field name to whatever the manifest repetitions actually carry — the test fixtures define it; read `test-release-gate.py`'s fixture shape first.)

- [ ] **Step 4: Run to verify pass**

Run: `python3 -m pytest benchmarks/agentic-palisade/scripts/test-release-gate.py -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add benchmarks/agentic-palisade/scripts/release-gate.py benchmarks/agentic-palisade/scripts/test-release-gate.py
git commit -m "feat(benchmark): gate accepts fail-fast cancellations only with a present triggering failure"
```

---

### Task 6: ADR 0027 and documentation

**Files:**
- Create: `docs/adr/0027-gate-iteration-policy.md`
- Modify: `docs/maintainers/releasing.md`, `benchmarks/README.md`

**Interfaces:**
- Documents Tasks 1–5.

- [ ] **Step 1: Write ADR 0027**

Follow the existing ADR format. Status Accepted. Context: fixed model/45-minute enforcement made qualification slow and single-model; the all-runs conjunction and human gate must survive. Decision: model is a sealed environment-stratum identity; schedules may seal `failFast: true` with cancellation recorded and gated; the per-run ceiling is a sealed per-arm parameter (default 40 min, floor 10 min); historical evidence is model-scoped and not reusable across model changes. Consequences: any model can qualify; failing schedules cost one run; passing schedules unchanged; model changes invalidate only that model's precommitments.

- [ ] **Step 2: Update releasing.md**

Precondition 6: describe per-model qualification — the `--model` and `--max-time` parameters (floor 10 min, prepare default 40 min), the fail-fast cancellation contract (cancelled arms recorded with reason; the gate accepts them only when justified), and that evidence is scoped to the qualified model.

- [ ] **Step 3: Update benchmarks/README.md**

Document `--model` and `--max-time` as parameters with the measured default and the fail-fast behavior. Do not change the corpus/symmetry sections.

- [ ] **Step 4: Commit**

```bash
git add docs/adr/0027-gate-iteration-policy.md docs/maintainers/releasing.md benchmarks/README.md
git commit -m "docs(adr): gate iteration policy — model strata, fail-fast, sealed ceilings"
```

---

### Task 7: Full verification

**Files:** none (verification only).

- [ ] **Step 1: Run the benchmark test suites**

Run: `python3 -m pytest benchmarks/agentic-palisade/scripts/test-runner.py benchmarks/agentic-palisade/scripts/test-release-gate.py benchmarks/agentic-palisade/scripts/test-qualification.py -q`
Expected: all pass.

- [ ] **Step 2: Run the gradle benchmark tests**

Run: `./gradlew :benchmarks:test --tests '*StatisticsTest'`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run the workflow validator**

Run: `python3 scripts/validate-workflows.py`
Expected: `workflow security invariants: PASS`.

- [ ] **Step 4: Prepare probe with a non-fixed model and ceiling**

Run:
```bash
candidate="$(git rev-parse HEAD)"
./gradlew publishToMavenLocal -Dmaven.repo.local=/tmp/qual-gate -PreleaseVersion=1.1.0-candidate.${candidate:0:12} --no-daemon -q
python3 benchmarks/agentic-palisade/scripts/run-benchmark.py --output /tmp/qual-gate-prep \
  --model deepseek/deepseek-v4-flash --max-time 30m --pairs 5 --release-candidate --prepare-only \
  --candidate-maven-repository /tmp/qual-gate --candidate-version 1.1.0-candidate.${candidate:0:12}
```
Expected: `{"status": "prepared", "runs": 10, ...}` and the produced `benchmark-manifest.json` has `"maxTimeSeconds": 1800` and `"model": "deepseek/deepseek-v4-flash"`.

- [ ] **Step 5: Commit any residual changes and confirm the tree**

Run: `git status --short`
Expected: clean (or commit the probe's scratch output only if it is inside the repo — prefer an output path outside the tree; remove `/tmp/qual-gate-prep`).

---

## Self-Review Notes

- Spec coverage: Change A → Tasks 1–3; Change B → Tasks 4–5; Change C → Task 1 (ceiling parameterization) with the default set in Task 6 docs; ADR/docs → Task 6; tests → Tasks 1–5 + 7; acceptance criteria 1–6 map to Task 7 steps 1–4.
- Placeholder scan: every step carries concrete code or an exact command; the only conditional is Task 5's arm-classification field name, explicitly flagged to read the fixture shape first.
- Type consistency: `environments[].model` string (Tasks 2–3); `schedule[].failFast` boolean (Tasks 2, 4–5); cancelled run record `{status, cancelReason{runId, classification}}` (Tasks 4–5); `MIN_SECONDS`/`max_seconds` (Task 1).
