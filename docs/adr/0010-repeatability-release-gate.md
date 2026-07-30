# ADR 0010: Digest-bound repeatability release gate

## Status

Accepted

## Context

The retained Agentic Palisade batch has three matched pairs and is directional
historical evidence. A release must not select its best candidate or aggregate
away a failed semantic, capture, human, structural, or cost channel. A decision
also cannot be committed into the source commit whose identity it records.

## Decision

Release qualification uses a sealed
`agentic-palisade/repeatability-manifest-v1` with at least five fresh matched
pairs per fully identified environment stratum. Each arm records distinct
workspace, session, process, run, and output identities and matched frozen
inputs, seed, and limits. Candidate repetitions independently require:

- 25/25 semantic assertions and identical canonical transition identities;
- a finite settling policy ending in at least three unchanged completed frames;
- five identical PNG digests for each canonical observation and the same digest
  across candidate repetitions in a stratum, with no default dynamic mask;
- independent automated visual, structural usability, and blinded human gates;
- two or more reviewers, median fidelity at least 5, and no majority unusable;
- finite wall-time, input-token, edit, build, and launch ceilings.

The decision is the conjunction of every channel in every required run. It
contains pair-level raw costs and deltas, medians and ranges, and the
predeclared `paired-randomization-test-v1` method. Its scope text explicitly
limits conclusions to observed pairs and strata.

Exact A/C/D/E/F retained capture families are committed as immutable controls.
A's bottom sequence remains unstable despite three equal trailing frames.
C/D/E/F remain capture-stable but human-unusable, demonstrating that stability
cannot substitute for quality.

Evidence lives on a separately signed annotated tag named
`release-evidence-<candidate-commit>`. The release workflow verifies both signed
tags, extracts the evidence commit, regenerates the decision, and requires
byte-for-byte agreement before building or publishing artifacts.

## Consequences

- A tag cannot publish from one successful or cheapest repetition.
- Changed source, thresholds, environments, ratings, mappings, or raw evidence
  require a new sealed qualification identity.
- Evidence may be produced after the candidate is frozen without a circular
  commit-hash dependency.
- Hash equality is claimed only for the recorded environment strata; it is not
  a universal determinism or population claim.

## Verification

```bash
python3 benchmarks/agentic-palisade/scripts/test-release-gate.py
python3 scripts/validate-workflows.py
./gradlew check javadoc --warning-mode=fail
```
