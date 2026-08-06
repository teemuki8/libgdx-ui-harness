# Benchmark Profiles Design

Date: 2026-08-06
Status: Approved (2026-08-06)
Modules: `benchmarks/agentic-palisade/scripts/{run-benchmark.py,release-gate.py,schemas/repeatability-precommitment.schema.json,test-runner.py,test-release-gate.py}`; `docs/adr/0028-benchmark-profiles.md`, `docs/maintainers/releasing.md`, `benchmarks/README.md`

## Goal

Introduce named sealed benchmark profiles. The **low-confidence** profile becomes the release gate: optimized for fast iteration (1 repetition schedule, 3 pairs, 2 rounds, lowered thresholds) and reliably passable by fast or mid-tier models. The **high-confidence** profile preserves the current strict requirements (5 pairs, 3 rounds, 2+ repetitions with cross-schedule digest equality, 25/25 semantic, 5 PNG digests, 2 reviewers, median fidelity 5) and stays fully supported but is **not** a gate between releases.

## Architecture

- The repeatability precommitment gains a required sealed field `"profile"` with values `"low-confidence"` | `"high-confidence"`. It is part of the canonical seal (any profile change invalidates the seal).
- `release-gate.py` defines one `PROFILES` table and resolves all gate thresholds from the **sealed** precommitment's profile. A release cannot verify under a different profile than the one sealed.
- `run-benchmark.py` gains `--profile` (default `low-confidence`), derives schedule defaults (pairs, rounds, ceiling) from it, validates it, and records it in the benchmark manifest.
- The CI release workflow `verify` step is unchanged: it reads the sealed profile from the evidence commit.
- High-confidence remains exercisable by preparing/executing with `--profile high-confidence`; it is simply not the release default and its precommitments do not need to exist between releases.

## Model image capability

The corpus uses images as evidence: the reference PNGs, the screenshot-diagnosis scenario, and the visual-parity channel. Both profiles therefore seal `modelImagesRequired: true` — a qualification model must support image input.

- The runner validates the model's vision support before prepare/execute using a capability map (authoritative source: `omp models`, whose `images` column reports `yes`/`no`; verified: `deepseek/deepseek-v4-flash` and `deepseek/deepseek-v4-pro` report `no`, the gitlab-duo claude models report `yes`, `openai-codex/gpt-5.6-sol:medium` reports `yes`).
- Unknown models fail closed with a message naming the missing capability entry, rather than burning a schedule.
- Measured consequence: the deepseek runs could not interpret reference PNGs or screenshots; this is a probable contributor to their failure to progress, in addition to the measured slowness.

## Profile threshold bundles

Shared by both profiles (not profile-tunable): the all-runs conjunction within a schedule, the fail-fast rule, seal fixity, and the retained negative controls (A/C/D/E/F must remain capture-stable but human-unusable).

| Requirement | low-confidence | high-confidence (current) |
|---|---|---|
| Model image input required | yes | yes |
| Pairs per stratum | 3 | 5 |
| Rounds | 2 | 3 |
| Required repetition schedules | 1 (no cross-schedule digest equality check) | 2 (same digest across candidate repetitions in a stratum) |
| Semantic assertion pass rate | >= 60% of the 25 assertion groups | 25/25 |
| PNG digests per observation | 3 identical | 5 identical |
| Settling frames | >= 3 unchanged completed frames | >= 3 |
| Blind reviewers | 1 | >= 2 |
| Median fidelity | >= 3 | >= 5 |
| Majority unusable | fails | fails |
| Cost ceilings (per run) | 500,000 input tokens, 10 builds, 30 launches, 40 minutes wall | current sealed values (1,000,000 input tokens, 100 builds, 100 launches, per-arm wall) |

## Gate implementation notes

- The `scenarioAssertionGroups` precommitment (25 groups) stays at 25 for both profiles; the profile changes the **pass-rate threshold** applied to the per-repetition semantic evidence, not the precommitted group count.
- The cross-schedule digest-equality check (canonical states, transition hashes, capture sets across repetitions) runs only when the profile's required repetition count is >= 2.
- The retained-controls negative check runs unconditionally.
- Human-channel checks apply the profile's reviewer count, fidelity median, and unusable-majority flag.

## Acceptance criteria

1. `python3 benchmarks/agentic-palisade/scripts/test-release-gate.py` passes with new profile-resolution cases: a low-confidence precommitment verifies under the lowered thresholds; a high-confidence precommitment still enforces 25/25, 2 reviewers, fidelity 5, 5 digests, 2+ repetitions.
2. `python3 benchmarks/agentic-palisade/scripts/test-runner.py` passes with `--profile` validation (unknown profile rejected, default low-confidence) and image-capability validation (deepseek models rejected when the profile requires images; claude models accepted).
3. A prepare with `--profile low-confidence --pairs 3 --max-time 40m` produces a manifest recording `profile: low-confidence`, pairs 3; the precommitment schema accepts `profile` and rejects its absence. A prepare with an image-incapable model is rejected before any schedule is produced.
4. `python3 scripts/validate-workflows.py` passes (workflow unchanged).
5. ADR 0028 documents the profiles, the shared invariants, and the release-gate change; `releasing.md` precondition 6 states low-confidence is the release gate and high-confidence is optional.

## Out of scope

- Changing the corpus, rounds machinery internals, or the paired-randomization method itself.
- Tuning low-confidence thresholds further (a follow-up after first measured low-confidence runs).
- Any change to the retained controls or the all-runs conjunction.
