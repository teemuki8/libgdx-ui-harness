# ADR 0004: Bound inspect, capture, compare, and convergence

- Status: Accepted
- Date: 2026-07-30

## Context

The screenshot operation proves that framebuffer readback succeeded, but it does not bind the
image to a named reference, the inspected semantic state, a comparison policy, or a convergence
decision. Agents can therefore mistake a schema-rejected attempt, launcher-created file, stale
capture, or visually incompatible viewport for accepted current evidence.

Issue 1 established stable state and control identities. The next layer must consume those
identities without moving image decoding into the semantic core, accepting caller-selected file
paths, or treating a raster score as a usability certificate.

## Decision

Add one `ui_inspect_compare` operation and a transport-neutral comparison contract.

1. A session owner registers a stable application ID, immutable reference catalog, comparator,
   named policy, and optional state/action contract provider. Remote callers select only
   allowlisted reference and policy IDs; they cannot import files or bytes.
2. One bounded invocation captures a full frame, then inspects the state belonging to that
   completed frame, compares it with the selected compatible reference, and returns exactly one
   status: `incomplete`, `stale`, `not-converged`, or `converged`.
3. Current evidence includes session/application identity, semantic revision/frame, viewport
   dimensions and scale, SHA-256, capture time, and an opaque artifact receipt. References include
   the same immutable identity and compatibility metadata.
4. The comparator lives outside `harness-core`. Core owns only immutable request/result models and
   the comparator/reference interfaces; the LWJGL3 layer supplies bounded PNG decoding and
   deterministic raster measurements.
5. Semantic differences use stable `testId` or state/action control identities when available.
   Remaining pixel differences are retained as an unattributed raster residual.
6. Exact viewport dimensions and scale are required by the initial policy. Resizing or reference
   substitution is never implicit. Additional policies require versioned IDs and separate review.
7. Schema-rejected attempts, launcher files, stale captures, incompatible references, and bound
   failures cannot produce `converged`. Telemetry records attempts, rejection, acceptance,
   inspection, comparison, staleness, and completion use as separate events.

## Consequences

- The MCP catalog grows from nine to ten tools and capability discovery gains `compare`.
- A comparison-enabled session has explicit reference ownership and lifecycle.
- PNG bytes remain bounded and never cross into public diagnostics except through opaque
  artifacts.
- Exact comparison is intentionally conservative. Typography, layout, clipping, richer
  attribution, and usability policy evolve in later focused issues without weakening provenance.
