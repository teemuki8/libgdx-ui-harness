# ADR 0033: One-time release gate exception for 1.2.0

## Status

Accepted (2026-08-09); the exception applies to release 1.2.0 only.

## Context

The release procedure requires an exact-candidate, sealed low-confidence Agentic
Palisade decision before publication. The 1.2.0 candidate at commit
`a3e0d0e2cec8edbb3cb672ebbf1fbb7406fd55ac` passed the complete repository gate
and all merged pull-request checks. Three independently sealed replacement
qualification schedules then completed all six planned runs each.

The final release source commit is a descendant that adds the exception record,
marker, version-bound workflow guard, and its security validation without
changing a published library module. That descendant was not the measured
candidate. This exception explicitly covers both the failed repeatability
channel and that release-source identity difference.

The first two completed schedules did not clear the per-candidate semantic floor:
their harness arms scored 12/25, 16/25, 18/25 and 9/25, 15/25, 15/25. The third
schedule cleared that floor at 17/25, 16/25, and 17/25. Its blinded harness-arm
fidelity ratings were 5, 6, and 3, with no unusable verdict. It still cannot pass
ADR 0010: each of the three canonical observations produced a different PNG
digest in every candidate repetition. The result demonstrates usable individual
runs, but not exact cross-repetition reconstruction.

The maintainer explicitly authorized publishing 1.2.0 with that repeatability
failure retained as release evidence. This exception does not reinterpret the
measurements or weaken the default gate.

## Decision

Release 1.2.0 proceeds under a one-time maintainer gate exception:

- The tagged release source commit contains `.release-gate-exception`, so the
  workflow skips only the sealed repeatability-decision verification step and
  only when the tag name is exactly `v1.2.0`.
- The marker records this decision, date, authorization, and retained evidence
  location. Removing it restores the default gate.
- The signed `release-evidence-<release-source-commit>` tag remains mandatory;
  its name uses the final release commit required by the workflow, not the
  measured parent candidate.
- Signed release-tag verification, JDK 25 checks, Javadocs, the six-module Maven
  Central bundle, Central validation, publication, and public-coordinate checks
  remain mandatory.
- The exception applies only to 1.2.0. The marker must be removed before any
  later release candidate is tagged.

## Consequences

1.2.0 is published without proving exact cross-repetition reconstruction by the
qualified model. Consumers receive the completed request-boundary, semantic,
trace, capture, lifecycle, fixture, and documentation fixes after all repository
and pull-request gates passed. The retained schedules and blind review preserve
the failed repeatability signal rather than presenting it as a passing decision.
Future releases remain subject to ADR 0010 unless a new, explicit maintainer
decision says otherwise.

## Evidence

The schedule identities, semantic scores, blind-review outcome, exact capture
digests, and operator retention location are recorded in
`docs/maintainers/qualification-evidence-2026-08-09-v1.2.0.md`. The third
schedule's sealed precommitment SHA-256 is
`6b33594759f9fd50f5005b1e752907ac3e3f9cc0b61b629a3f05272c0e01565c`.
