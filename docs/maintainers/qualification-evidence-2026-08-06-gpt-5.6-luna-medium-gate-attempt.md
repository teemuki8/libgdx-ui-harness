# Qualification Evidence: gpt-5.6-luna:medium low-confidence gate attempt (2026-08-06)

Date: 2026-08-06
Status: Recorded; the low-confidence release gate was NOT passed. Release held.
Raw data (retained, not committed):
`build/reports/agentic-palisade/release-1.1.0-20260806-gate-run-medium/` (run records,
precommitment `f414ac7b`, evaluator output),
`build/reports/agentic-palisade/release-1.1.0-20260806-gate-run-high/` (luna@high attempt),
`build/reports/agentic-palisade/review-20260806-gate-medium/` (blind review package and
sealed `report.json`), `build/reports/agentic-palisade/ratings-lock-20260806.json`.

## Setup

- Candidate: `1.1.0-candidate.d06d63d44b54`; precommitment sealed before execution
  (`sealedAt 2026-08-06T18:30:25Z` < all `startedAt`), allocation seed
  `191428166656591347`, environment `nobsa-linux-x11-jdk25-20260806`.
- Schedule: low-confidence profile (ADR 0028), 3 pairs x 2 arms = 6 runs, 3 rounds each,
  40-minute ceiling, release-candidate mode, arms in parallel on private Xvfb displays.
- Model attempts: `openai-codex/gpt-5.6-luna:medium` at `medium` reasoning (gate run) and
  `high` reasoning (earlier attempt, same ceiling).

## Measured results

luna@high, 40 m ceiling: 2/6 completed (harness arms, 33.4-36.9 min, 3/3 rounds accepted);
4/6 `timed_out` at exactly 2400 s (all recorded rounds accepted). High reasoning does not
fit the ceiling.

luna@medium, 40 m ceiling (precommitment-bound): 5/6 success (9.1-15.9 min, 3/3 rounds
accepted); 1 arm (`f5058b9d`, pair 2 harness) `timed_out` with 0 rounds - 20 tool calls in
40 min with repeated aborted generations, consistent with provider quota throttling.

Hidden evaluator (per run, `evaluation/evaluation.json`):

| run | status | functional |
|---|---|---|
| pair 1 harness | complete | 6/25 |
| pair 1 baseline | complete | 5/25 |
| pair 3 harness | complete | 5/25 |
| pair 3 baseline | runtime-failed | state-action contract missing `observed` |
| pair 2 baseline | runtime-failed | state-action contract missing `observed` |
| pair 2 harness | compile-failed | throttled arm |

Blind human review (one reviewer, package A-F, sealed via `unblind-report.py`):
median fidelity 1 per arm (low-confidence gate requires >= 3). Best candidate E
(harness, pair 3) at fidelity 3, ranking 1; candidates A, B unusable per reviewer;
C, D, F had no captures (failed evaluations) and were tie-break-rated.

## Conclusions

- The gate fails on both measured channels: functional <= 6/25 vs the 15/25
  low-confidence bar, and human median fidelity 1 vs the >= 3 bar. No candidate
  reconstructs the reference Skirmish Configuration UI.
- The round gate (state-action contract) is much more lenient than the hidden
  evaluator (25 assertions + visual metrics + human review); "rounds accepted" is not
  evidence of evaluator conformance.
- The gap is model capability, not harness machinery: the runs, telemetry, evaluator,
  blind review, and precommitment tooling all worked end to end (see also the
  qualification records for deepseek and luna@medium). The low-confidence gate awaits
  a model that can rebuild the corpus UI from the spec under blinding (historically
  only `openai-codex/gpt-5.6-sol:medium` produced conforming runs).
- Release v1.1.0 is held pending a qualifying model.
