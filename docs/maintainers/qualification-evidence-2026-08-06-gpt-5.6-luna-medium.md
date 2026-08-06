# Qualification Evidence: gpt-5.6-luna:medium at medium reasoning (2026-08-06)

Date: 2026-08-06
Status: Recorded; the model completes the low-confidence release schedule within the 40-minute ceiling.
Raw data: `build/reports/agentic-palisade/qualification-2026-08-06-gpt-5.6-luna-medium-lowconfidence-40m/`
(completed replacement batch) and
`...-lowconfidence-40m-batch1-interrupted/` (operator-interrupted first attempt; retained per
PROTOCOL failure-retention rules; neither is committed).

## Setup

- Candidate: `1.1.0-candidate.d06d63d44b54` (six publishable modules via `publishToMavenLocal`
  into a private repository; repository SHA-256 `9beb0e0487...` sealed into the schedule manifest).
- Model: `openai-codex/gpt-5.6-luna:medium`, reasoning `medium`, image-capable (`images: yes`),
  so the high-confidence profile also remains available for later deep evidence.
- Schedule: low-confidence profile (ADR 0028): 3 pairs x 2 arms = 6 runs, 3 rounds each,
  40-minute ceiling, release-candidate mode, arms in parallel on private 1920x1080 Xvfb displays,
  auth preflight passed. Schedule identity (model, profile, ceilings, candidate repository digest,
  corpus/prompt/template/protocol hashes) matches the interrupted first batch byte-for-byte.

## Interruption note

The first attempt of this batch was interrupted by an operator terminal close (SIGHUP,
2026-08-06 15:24Z): 4 of 6 runs had completed with `success` records; the remaining two
(pair 2 harness, pair 3 harness) had no records. The tooling has no resume path
(`--execute-prepared` rejects pre-existing run records and fixed pair counts), so a
replacement batch with fresh run identities was prepared from the identical inputs and
run to completion. Both batches are retained; only the completed replacement batch is
counted as qualification evidence.

## Measured results (completed replacement batch, 6/6 records)

| pair | arm      | runId     | builds | launches | input tokens | wall    | rounds |
|------|----------|-----------|--------|----------|--------------|---------|--------|
| 1    | baseline | 66310b5c  | 14     | 5        | 134,444      | 14.9 m  | 3/3 accepted |
| 1    | harness  | 62428879  | 8      | 4        | 108,135      | 9.5 m   | 3/3 accepted |
| 2    | baseline | c68811b5  | 9      | 5        | 127,922      | 12.9 m  | 3/3 accepted |
| 2    | harness  | 94352265  | 7      | 6        | 151,858      | 11.1 m  | 3/3 accepted |
| 3    | baseline | 190a1936  | 9      | 3        | 157,217      | 11.5 m  | 3/3 accepted |
| 3    | harness  | 1524f2a0  | 8      | 5        | 111,916      | 11.1 m  | 3/3 accepted |

Every run exited with classification `success`, no timeouts, all three rounds accepted by the
round supervisor. Recorded `failures` arrays contain only transient tool-level errors
(bash/edit/glob), which the low-confidence profile tolerates; no run reached
`deadline`, `omp_exit`, or integrity-failure classification.

## Conclusions

- gpt-5.6-luna:medium completes the full low-confidence schedule: 6/6 runs, 3/3 rounds
  accepted, 9.5-14.9 minutes per run against a 40-minute ceiling (24-37% of ceiling).
- The pace constraint that bound deepseek-v4-flash is gone: builds (7-14) and launches (3-6)
  per run match the historical Codex baseline shape, and input tokens (108-157k per run) are
  within the low-confidence cost ceilings.
- The low-confidence release gate is now reachable: the remaining steps are maintainer actions
  per `docs/maintainers/releasing.md` - seal `precommitment.json` before a measured run,
  assemble the outcome `manifest.json`, run `release-gate.py create-decision`, complete the
  one blind reviewer rating (median fidelity >= 3), and commit/tag the evidence.

Historical evidence remains model-scoped: this record qualifies luna for the low-confidence
schedule and does not invalidate or replace the retained deepseek or Codex records.
