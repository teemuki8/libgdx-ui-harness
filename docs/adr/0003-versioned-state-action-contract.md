# ADR 0003: Versioned state and action contract

- Status: Accepted
- Date: 2026-07-30

## Context

The V1 semantic tree supports discovery, locators, actionability, and diagnostics, but it does
not define all domain state needed by an independent evaluator. In particular, adapter-specific
string properties do not distinguish typed defaults from current values, describe complete
options and validation, express conditional relationships, define focus traversal and viewport
state, or normalize action outcomes.

The Agentic Palisade qualification exposed the consequence: evaluators and candidates used
different aliases and nesting, so compatibility failures prevented later behavioral scenarios
from running. Extending the existing `SemanticNode` record would also break its released Java
constructor and conflate locator-oriented observations with evaluator-oriented domain contracts.

## Decision

Add an immutable `state-action/v1` contract in `harness-core` alongside the existing semantic
tree.

The contract has these properties:

1. A major/minor schema version is explicit. Unknown major versions fail closed; readers may
   accept a newer minor version only when every required V1 field remains valid.
2. Controls are ordered and keyed by application-supplied stable control IDs. Duplicate or
   missing IDs fail closed rather than falling back to snapshot-local node IDs.
3. Values are a closed typed union: null, boolean, integer, decimal, and text.
4. Control definitions include role, domain kind, accessible label, ordered options, distinct
   default/current values, effective visibility/enabledness/actionability, and typed validation.
5. Snapshot-level fields define ordered focus traversal, current focus, conditional
   relationships, and ordered viewport state.
6. A normalized transition result records acceptance or rejection, resulting state identity,
   validation, clipboard effects, dismissal or confirmation, and an accepted payload.
7. Scene2D applications attach domain definitions through the session-owned `Semantics` facade.
   Built-in adapters contribute observable current values and viewport geometry, but do not guess
   application defaults, stable IDs, conditions, or validation rules.
8. `harness-protocol` owns the strict JSON representation and publishes its JSON Schema.
   Snapshot responses expose either the contract inline or an immutable artifact reference under
   the existing `ui_snapshot` operation; no tenth MCP operation is added.
9. The benchmark bridge consumes only the published JSON contract. Candidate-specific aliases,
   reflection, source inspection, and hidden translation tables are forbidden.

## Consequences

- Existing locators and `SemanticSnapshot` callers remain source and binary compatible.
- Applications must provide explicit stable IDs and domain metadata before requesting an
  evaluator-complete contract.
- Contract construction may fail even when an ordinary semantic snapshot succeeds. Diagnostics
  identify every invalid JSON path and retain the schema version.
- The Scene2D adapter performs all metadata reads and contract assembly on the render thread.
- Protocol V1 gains additive response data and schema resources without exposing libGDX types.
- Any future incompatible field meaning requires a new contract major version and an ADR.

