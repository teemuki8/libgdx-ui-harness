# ADR 0028: Benchmark profiles

## Status

Accepted

## Context

The repeatability release gate demands the full strict qualification: 5 pairs, 3 rounds, 2+ repetition schedules, a 25/25 semantic pass rate, 5 PNG digests per observation, and a blind human review by 2 reviewers at median fidelity 5. That bundle costs roughly six schedules of tokens per qualification and walls out fast iteration and mid-tier models, so exploring cheaper or faster models at release-grade rigor is prohibitively expensive. Measured deepseek runs showed the corpus is model-speed-bound: qualification wall time tracks the model's inference speed, so a slow model pays the full cost of every schedule. The same runs also showed that deepseek models cannot read images (`omp models` reports `images: no` for them), a probable contributor to their failure to progress through the screenshot-diagnosis scenarios; qualification therefore cannot simply relax thresholds for a model that cannot see the evidence it is judged on.

## Decision

Qualification is sealed as one of two benchmark profiles. The `low-confidence` profile is the release gate: 3 pairs, 2 rounds, 1 repetition schedule, a >=60% semantic pass rate, 3 PNG digests per observation, 1 blind reviewer at median fidelity >=3, and tighter cost ceilings. The `high-confidence` profile preserves the current strict requirements — 5 pairs, 3 rounds, 2+ repetition schedules, 25/25 semantic, 5 digests, 2 reviewers at median fidelity 5 — and stays supported, but it is not required between releases. Both profiles require an image-capable model, validated by the runner before any schedule is produced; a model whose image support is unknown fails closed. The selected profile is part of the precommitment seal, so the gate reads it from the sealed precommitment rather than from any mutable caller state. The shared invariants apply to both profiles unchanged: the all-runs conjunction still requires every scheduled run, fail-fast still requires a predeclared schedule and a recorded triggering failure, the seal remains fixed, and the retained negative controls (strictness-violation and intentional-failure scenarios) still gate the evidence.

## Consequences

Releases need roughly one sixth of the qualification tokens, because the low-confidence profile runs one schedule of 3 pairs × 2 rounds instead of the historical bundle of schedules. Image-incapable models are rejected before any schedule is produced, so they cannot spend qualification tokens only to fail on screenshot evidence they cannot see. High-confidence evidence remains available for deep review whenever a release warrants it, and it can be produced with the same runner and gate by selecting the profile explicitly. Any profile change invalidates only the precommitment that seals it: evidence stays bound to the exact sealed profile, and re-qualification is the documented path when the profile changes.
