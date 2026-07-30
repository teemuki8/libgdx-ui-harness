# ADR 0008: Digest-Bound Trace and Failure Taxonomy

- Status: Accepted
- Date: 2026-07-30

## Context

The Agentic Palisade benchmark previously derived screenshot and launcher
counts from literal shell-command text. Requests stored in environment-like
arguments or redirected NDJSON were invisible, and a launcher-created PNG
could not be distinguished reliably from a successful harness screenshot.
Run, session, tool, capture, evaluator, and review identities also lived in
separate records.

Associating those records by timestamps or paths could misclassify rejected
requests as successes, turn one evaluator serialization omission into many
independent product defects, or expose treatment identity before blinded
ratings were sealed.

## Decision

`agentic-palisade/trace-taxonomy-v1` is the closed public taxonomy. Its
top-level families are `capture`, `semantic`, `rendering`, and
`workflow-loop`. Each attribution is classified as `observed`,
`source-established-cause`, or `hypothesis`; a hypothesis is never serialized
as an established cause.

OMP exports are normalized into `agentic-palisade/trace-event-v1` request and
result events. Each event ID is the SHA-256 digest of every other event field.
Batch, run, session, sequence, parent, operation, request, payload, result, and
artifact identities are explicit when applicable. Validation rejects duplicate
IDs, unknown parents, cycles, cross-run edges, and changed digests.

Capture lifecycle states are `requested`, `schema-rejected`,
`execution-failed`, `succeeded`, `artifact-created`, `inspected`,
`compared-current`, and `stale`. Launcher capture is a separate channel.
Success requires an observed harness screenshot or inspect/compare result;
generic non-error shell completion is insufficient.

An unproductive loop requires at least three consecutive events with the same
operation, intent digest, and error class, with no declared semantic, visual,
artifact, or evaluator progress. The classification is an observation, not a
claim that diagnostics caused retries.

The run record retains the complete bounded trace. The blind package retains
opaque event and payload digests, lifecycle results, and evaluation joins, but
removes batch, run, session, source-path, treatment, token, and human
identities. The ratings lock and artifact digests are validated before the
final report attaches run, treatment, and human associations.

Functional, automated visual, structural usability, human visual,
telemetry/cost, and trace taxonomy remain separate final channels. Summaries
are directional for exactly three matched pairs and contain no combined score,
significance claim, population inference, or causal language from association.
Equal low-fidelity unusable candidates are excluded from ordinal correlation;
their unique ranks remain tie-breaks only.

## Consequences

The retained A, C, and F payload forms can be recomputed as eight attempted and
schema-rejected screenshot requests with zero established harness screenshot
successes, while launcher captures remain separate. One missing
`visibleControls` evaluator checkpoint retains its downstream assertion IDs as
one causal serialization chain. Rendering categories retain metric and
capture/reference identities without implying a human-rating cause.

Historical or failed runs with no readable OMP export publish explicit
unavailable trace provenance and empty family arrays rather than guessed zero
observations.

## Verification

Run:

```text
python3 benchmarks/agentic-palisade/scripts/test-trace-taxonomy.py
python3 benchmarks/agentic-palisade/scripts/test-telemetry.py
python3 benchmarks/agentic-palisade/scripts/test-runner.py
python3 benchmarks/agentic-palisade/scripts/test-blinding.py
python3 benchmarks/agentic-palisade/scripts/test-qualification.py
./gradlew check javadoc --warning-mode=fail
```

The tests cover environment and referenced-NDJSON replay, lifecycle negative
controls, the retained F response counts, graph mutations, evaluator early
exit, rendering identity joins, productive-loop control, pre-unblinding
treatment leakage, six-run channel continuity, sealed association, and
independent tamper rejection.
