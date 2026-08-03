# ADR 0015: Evaluator-owned structural measurement

- Status: Accepted
- Date: 2026-08-03

## Context

The Agentic Palisade release evaluator accepted candidate-authored structural values as evidence.
A candidate could therefore declare expected bounds, contrast, clipping, hierarchy, and raster
quality without those values describing the completed Stage or framebuffer.

## Decision

The reserved benchmark template captures structural observations on the libGDX render thread
after the completed frame is drawn. It derives actor bounds, ownership, visibility, hit targets,
clipping, font size, contrast, glyph edges, and raster residual from the Stage and captured
framebuffer. Results are bounded to 256 actors and 64 measured controls.

Each capture result wraps the observation in `trusted-structural-measurement/v1` and records the
SHA-256 digest of the compiled reserved probe. The evaluator requires that digest to match the
probe compiled from its source-pinned template and binds it with the structural policy
implementation digest. Candidate state remains available for functional semantics but is not an
input to structural measurement.

Missing wrappers, schema mismatches, stale identities, unknown fields, and declaration-only
evidence fail closed.

## Consequences

- Structural release evidence describes the observed Actor tree and framebuffer rather than a
  candidate claim.
- The trusted probe adds bounded work only on the three requested capture frames.
- Candidate implementations must expose stable actor names and real Scene2D hierarchy for
  attribution; hard-coded structural result objects cannot qualify a defective UI.

## Verification

```bash
./gradlew -p benchmarks/agentic-palisade/evaluator test --warning-mode=fail
python3 benchmarks/agentic-palisade/scripts/test-release-gate.py
python3 benchmarks/agentic-palisade/scripts/test-qualification.py
./gradlew check javadoc --warning-mode=fail
```
