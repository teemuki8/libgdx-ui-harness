# ADR 0012: Independent evidence ingestion failures

- Status: Accepted; structural ingestion amended by ADR 0015
- Date: 2026-07-30

## Context

The first sealed 1.1.0 qualification exposed two failure-coupling defects. A malformed
`structuralUsability` object caused the candidate evaluator to abort, discarding otherwise valid
functional and visual outcomes. Separately, a command that referenced an NDJSON input later
removed by `gradle clean` caused the telemetry parser to discard provider usage, tool operations,
and every retained trace event.

These inputs belong to independent evidence channels. Their absence must remain visible and must
prevent an unsupported pass, but it must not erase evidence already observed in another channel.

## Decision

The evaluator converts a schema-invalid `structuralUsability` object into a structural
`INCOMPLETE` result. All six structural signals receive the bounded
`OBSERVATION_SCHEMA_INVALID` diagnostic at `$.structuralUsability`; functional and visual
evaluation continues. The public protocol explicitly requires
`schemaVersion: structural-observation/v1`.

The telemetry parser records an unavailable referenced NDJSON input in the trace taxonomy's
bounded `evidenceGaps` array. It continues parsing provider usage, tool calls, retained capture
attempts, and the sealed event graph. The gap is included in blinded trace output. Unsafe,
oversized, missing, and malformed payloads remain unavailable and are never inferred.

Treatment-neutral instructions require command files and other referenced trace inputs to remain
outside Gradle's `build/` directory so `clean` cannot remove them during a measured run.

## Consequences

- A malformed structural observation cannot receive a structural pass.
- Functional, visual, telemetry, and trace evidence remain independently reviewable.
- Missing referenced payloads are explicit rather than silently ignored.
- Existing failed qualification runs remain immutable historical evidence and cannot be reused.
- A fresh sealed qualification is required after this decision.

## Verification

```bash
./gradlew -p benchmarks/agentic-palisade/evaluator test --warning-mode=fail
python3 benchmarks/agentic-palisade/scripts/test-telemetry.py
python3 benchmarks/agentic-palisade/scripts/test-trace-taxonomy.py
python3 benchmarks/agentic-palisade/scripts/test-treatment-symmetry.py
python3 benchmarks/agentic-palisade/scripts/test-runner.py
./gradlew check javadoc --warning-mode=fail
```
