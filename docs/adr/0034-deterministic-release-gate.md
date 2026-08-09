# ADR 0034 — Deterministic Maven publication gates

## Status

Accepted.

## Context

The Agentic Palisade benchmark measures a model workflow. Its outcome depends on model service
availability, model revisions, reviewer judgement, and paid execution. Those inputs are useful
product evidence, but they cannot make an immutable library artifact reproducibly releasable.
Making the empirical result mandatory also required release-only evidence tags and exceptions,
which obscured the actual source and artifact checks.

The repository already has deterministic gates for Java APIs, semantic parity, the synthetic
Agentic Palisade pipeline, treatment symmetry, native rendering/input fixtures, Javadocs, artifact
contents, signatures, and Maven Central validation.

## Decision

Maven publication is blocked only by deterministic evidence produced from the tagged source. The
release workflow runs the complete Java/Gradle gate under Xvfb, API compatibility checks, parity
and benchmark contract tests reached through `check`, Javadocs, signed bundle validation, and the
Central `VALIDATED` and `PUBLISHED` states.

Real-model Agentic Palisade runs remain supported as manual or scheduled product qualification.
Their retained reports may inform roadmap and release decisions, but publication never fetches an
evidence tag, executes `release-gate.py verify`, or recognizes an exception marker.

## Consequences

- A release can be reproduced from its signed source tag and configured signing credentials.
- CI remains strict about benchmark structure without requiring a model service or human reviewer.
- Empirical model regressions are visible evidence, not an unreliable artifact-integrity gate.
- ADR 0033 remains the historical record for 1.2.0 and is superseded for future releases.
