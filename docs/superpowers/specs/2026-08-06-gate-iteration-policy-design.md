# Gate Iteration Policy Design

Date: 2026-08-06
Status: Approved (2026-08-06)
Modules: `benchmarks/agentic-palisade/scripts/{run-benchmark.py,release-gate.py,schemas/*,test-*}`; `docs/maintainers/releasing.md`, `docs/adr/0027-gate-iteration-policy.md`, `benchmarks/README.md`
Supersedes: the fixed-model and fixed-45-minute enforcement in `run-benchmark.py`; the unsealed model handling in the precommitment schema.

## Goal

Make repeatability qualification fast to iterate while preserving every evidence guarantee: the all-runs conjunction, the paired-randomization method, the human blind-review gate, and digest fixity of sealed schedules. Specifically: (1) the model becomes a first-class sealed environment-stratum identity so any model can qualify; (2) failing schedules stop early via a precommitted fail-fast rule; (3) the per-run wall ceiling becomes a sealed per-arm parameter with a data-derived default instead of a hard-coded 45 minutes.

## Measured baseline (retained evidence)

`build/reports/agentic-palisade/release-1.1.0-20260730T1247Z/runs/*/run-record.json`: ten runs, durations 838–2125 s (median ~17 min, max 35.4 min). Per-arm `resourceLimits.wallSeconds: 2700` (= 45 min) and `costCeilings.wallTimeMillis: 2700000` are sealed per arm today. The model is bound only through the treatment hashes in `precommitmentHashes`; the precommitment schema has no `model` field. `run-benchmark.py` hard-rejects `--model != openai-codex/gpt-5.6-sol:medium` and `--max-time != 45m`.

## Change A: model as sealed environment-stratum identity

- `run-benchmark.py`: remove the `FIXED_MODEL` rejection (line ~1601) and the exact-45m `--max-time` rejection. `--model` and `--max-time` are free parameters, recorded in the benchmark manifest (model already is; max-time already is) and sealed into the environment stratum.
- `schemas/repeatability-precommitment.schema.json`: `environments[]` items gain required `model` (string, non-empty). The runner writes the model into the stratum it seals.
- `release-gate.py`: for each completed repetition, verify the repetition's environment stratum `model` equals the model recorded for its runs; the decision's claim-scope text names the qualified model. A mismatch fails the gate.
- Historical evidence remains model-scoped history; re-qualification per model is the documented path, and this change makes it cheap (see B and C).

## Change B: precommitted fail-fast cancellation

- Precommitment `schedule` gains optional `failFast` (boolean). When true, the supervisor evaluates the sealed decision criteria after every completed run; once the all-runs conjunction is unrecoverable (any required run failed a criterion), it cancels all remaining scheduled arms.
- Each cancelled arm is recorded in the repeatability manifest with `status: "cancelled"` and a `cancelReason` referencing the precommitted fail-fast rule and the triggering failure.
- `release-gate.py`: a `cancelled` run is accepted only when (a) the schedule declares `failFast: true`, (b) the triggering failure is present in the manifest, and (c) the conjunction is genuinely determined; any cancelled/missing run failing those checks is treated exactly as a missing run (gate failure). Failing schedules therefore cost one run; passing schedules are unchanged.

## Change C: sealed per-run ceiling

- `--max-time` accepts any value >= 10 minutes; the value is sealed per arm into `resourceLimits.wallSeconds` and `costCeilings.wallTimeMillis` exactly as today (per-arm sealing already exists).
- Prepare default becomes 40 minutes (2400 s) instead of 45, derived from the retained distribution (max 2125 s + margin). Lower ceilings are permitted and encouraged for faster models; a misjudged ceiling is cheap under fail-fast.

## ADR and documentation

- New `docs/adr/0027-gate-iteration-policy.md`: model strata, fail-fast rule, per-run ceiling, model-scoped evidence, and the explicit statement that historical evidence is model-scoped and not reusable across model changes.
- `docs/maintainers/releasing.md` precondition 6: rewritten to describe per-model qualification, the `--model`/`--max-time` parameters, and the fail-fast cancellation contract.
- `benchmarks/README.md`: document the model and ceiling as parameters with the measured default.

## Tests

- `test-runner.py`: non-fixed `--model` accepted; `--max-time` 10–40m accepted, < 10m rejected; fail-fast supervisor cancels remaining arms once a required run fails; manifest records cancelled arms with reasons.
- `test-release-gate.py`: model-stratum mismatch rejected; justified cancellation accepted; unjustified or absent-failure cancellation rejected as a missing run; fail-fast + pass-everything schedules unaffected.
- Schema tests (`test-release-gate.py` schema loading): precommitment with/without `model` and with/without `failFast`.

## Acceptance criteria

1. `python3 -m pytest benchmarks/agentic-palisade/scripts/test-runner.py benchmarks/agentic-palisade/scripts/test-release-gate.py` passes (new + existing cases).
2. `./gradlew :benchmarks:test --tests '*StatisticsTest'` passes.
3. `python3 benchmarks/agentic-palisade/scripts/test-release-gate.py` passes unchanged existing cases.
4. Prepare with `--model <any> --max-time 30m --release-candidate --prepare-only` succeeds and seals a precommitment whose `environments[0].model` equals the requested model and whose per-arm `resourceLimits.wallSeconds` equals 1800.
5. `python3 scripts/validate-workflows.py` passes.
6. ADR 0027 committed; `releasing.md` precondition 6 text updated.

## Out of scope

- Changing the all-runs conjunction, the paired-randomization method, the human blind-review gate, or the digest-fixity of sealed schedules.
- The generative UI evaluation axis (separate design).
- Re-validating any historical evidence.
