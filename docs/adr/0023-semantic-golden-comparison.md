# ADR 0023: Versioned semantic golden comparison

## Status

Accepted

## Context

Pixel comparisons are inappropriate for many semantic regressions, while raw semantic snapshots contain volatile snapshot-local node IDs. Callers need durable baselines that express user-observable intent and a deterministic comparison that works without screenshots or framebuffer access.

## Decision

Add the versioned `SemanticBaseline`/`BaselineNode` models, the in-memory `SemanticBaselineCatalog`, and the pure `SemanticComparator` in `harness-core`, plus the closed `semantic-compare` protocol operation and `ui_semantic_compare` MCP tool.

Baselines are versioned (unknown major versions fail closed) with partial node expectations: omitted optional properties are unconstrained by default, and strict-node mode requires complete coverage. Matching uses stable hierarchy-aware keys — unique test ID first, then role plus accessible name with parent context — never snapshot-local node IDs or Actor identity. Duplicate or insufficient identities produce an explicit ambiguous result, never heuristic pairing.

The comparator classifies added, removed, changed, and ambiguous nodes with deterministic stable ordering. Changed nodes report bounded property paths and before/after values. Named `PositionalTolerance` values apply only in their explicit coordinate space and units and never hide role, name, identity, or state mismatches. Allowlisted volatile properties can be excluded, but identity fields (`role`, `accessibleName`, `testId`, `label`) can never be excluded; every applied exclusion is reported in the result. Comparison never invokes raster, capture, or visual-policy code.

The protocol session gains an optional `SemanticCompareCoordinator` boundary with backward-compatible constructors. The closed request names a registered baseline identifier plus strict-node mode, tolerances, exclusions, and bounds; the response carries match status, bounded differences, compared node count, truncation, and applied exclusions. Baselines are registered in memory by bounded identifier; no filesystem path or arbitrary external source is accepted.

## Consequences

CI gains durable semantic regression baselines independent of raster comparison, with explicit identity, tolerance, exclusion, and ambiguity semantics. Adding a baseline property or changing identity-key precedence requires protocol golden updates, schema review, and the exact MCP catalog update.

## Amendment (2026-08-08): registration and digest identity

Baselines are immutable and digest-addressed. `SemanticBaseline` carries a canonical SHA-256
`digest` computed by `BaselineDigest` over the complete versioned baseline (version, id,
strict-node flag, and the full `BaselineNode` tree). `SemanticBaselineCatalog.register`
validates the claimed digest against the recomputed canonical value and rejects any
conflicting replacement under an existing identifier; identical content is an idempotent
no-op. An unknown or misspelled identifier returns a typed `not-found` result and the harness
never learns a baseline from the current observation. The production fixture pre-loads its
committed `reference-ui/reference-baseline.json` resource before serving requests. The
request's `strictNodes` flag no longer mutates the registered baseline; strictness is a
property of the registered immutable baseline.
