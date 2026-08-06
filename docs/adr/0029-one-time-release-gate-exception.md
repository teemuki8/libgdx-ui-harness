# ADR 0029: One-time release gate exception for 1.1.0

## Status

Accepted (2026-08-06); the exception applies to release 1.1.0 only.

## Context

The release procedure (`docs/maintainers/releasing.md`) requires a sealed
repeatability decision before publishing: `release-gate.py verify` must
regenerate the decision byte-identically inside the release workflow. Since
ADR 0028 the gate is the `low-confidence` profile (3 pairs, 2 rounds, >=60%
semantic pass rate, 1 blind reviewer at median fidelity >=3). No model has
passed it: deepseek-v4-flash cannot complete the schedule, gpt-5.6-luna cannot
fit the ceiling at high reasoning or clear the functional bar at medium
reasoning, and the hidden evaluator scores the best candidate at 6/25 with a
human median fidelity of 1 (recorded in
`qualification-evidence-2026-08-06-gpt-5.6-luna-medium-gate-attempt.md`).

Meanwhile the 1.1.0 code base carries a substantially enhanced MCP and API
surface over 1.0.0 (ADR 0025 runtime bindings, evaluator-complete contracts,
navigation/layout/display diagnostics, strict locator suggestions), verified
by the functional test suites and CI gates. The maintainer decided to publish
1.1.0 without the sealed repeatability decision, accepting the retained
qualification and blind-review evidence as the quality record instead.

## Decision

Release 1.1.0 proceeds under a one-time maintainer gate exception:

- `.github/workflows/release.yml` skips the "Verify sealed repeatability
  decision" step only while the tagged commit contains a
  `.release-gate-exception` marker file.
- The marker records the decision, date, author, and rationale; deleting it
  restores the step (the workflow default remains gated).
- The evidence tag `release-evidence-<commit>` is still created and signed, and
  every other release step (tag verification, JDK 25 checks, Javadocs,
  six-module Central bundle, Central validation and publication) is unchanged.
- The exception does not carry over: the marker must not survive into the next
  release, and future releases require a passing sealed decision again.

## Consequences

1.1.0 is published without the repeatability decision; the gate's quality
signal is replaced by the functional suite, the CI gates, and the retained
qualification/review evidence. Consumers see the enhanced MCP/API sooner. The
risk is that the published 1.1.0 is not yet demonstrated reconstructable by
any model on the benchmark corpus; the benchmark evidence and gate machinery
remain in place and the next release must clear the gate or record a new
exception decision.
