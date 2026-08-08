# ADR 0022: Bounded display matrix lifecycle

## Status

Accepted

## Context

A scenario that passes at one display configuration can fail at another resolution, aspect ratio, UI scale, device pixel ratio, HiDPI mode, locale, or font set. Manual correlation is slow and non-reproducible. The matrix runner must start each case from a registered known state, fan the same assertion set across every case, keep requested and observed display parameters distinct, bound the Cartesian product before any case starts, and return compact terminal reports without embedding screenshots.

## Decision

Add the pure matrix planning models in `harness-core`, the `Lwjgl3MatrixRunner` execution adapter, the closed `matrix-run`/`matrix-results` protocol operations, and the `ui_matrix_run`/`ui_matrix_results` MCP tools.

`MatrixPlanner` expands the immutable `MatrixDefinition` (windows, UI scales, device pixel ratios, HiDPI modes, locales, font sets, carried #31 assertions) into a deterministic Cartesian product in window, scale, DPR, HiDPI, locale, font-set order. The product is computed overflow-safely and rejected before execution when it exceeds the case bound. Width and height are authoritative; aspect ratio is derived. Empty font-set lists collapse to one implicit default. UI scale, device pixel ratio, and HiDPI mode remain distinct requested axes.

`Lwjgl3MatrixRunner` executes cases sequentially: each case acquires the registered scenario through the #39 lease API, evaluates every carried assertion through the shared wait engine on externally pumped completed frames, records exact observed display parameters through an application-owned observer, and releases the scenario. Started, failed, unstarted, and cancelled cases have distinct terminal statuses with bounded evidence; reports are compact (`MatrixCaseSummary` carries no assertions), immutable, and never embed screenshots.

The protocol session gains an optional `MatrixCoordinator` boundary with backward-compatible constructors. `matrix-run` completes with a bounded run identifier; `matrix-results` returns the compact retained report.

## Consequences

CI can run one scenario and assertion set across a bounded display/locale/font matrix with deterministic order, exact provenance, and compact terminal reports. Adding a display dimension or changing bounds requires protocol golden updates, schema review, and the exact MCP catalog update.

## Amendment (2026-08-08): application, observation, and restart coordination

Every case is applied to the real application/window state before scenario acquisition and
verified before any assertion runs. A host-owned allowlisted `MatrixCaseApplicator` applies
and observes each requested dimension (window, UI scale, device pixel ratio, HiDPI mode,
locale, font set, restart profile); a requested dimension that cannot be applied produces the
closed `UNSUPPORTED` terminal status with bounded evidence, and a requested/observed mismatch
produces the distinct `MISAPPLIED` terminal status with no passing assertion result. The
observed restart profile comes from host-owned active state and is never echoed from the
request; a request naming an unowned profile is rejected as `UNSUPPORTED`. Observed settings
are captured for the same case and frame window as the assertions, the original display state
is restored deterministically after every started case (including misapplied ones and
application failures), and the Cartesian product remains preflight-bounded. `MatrixCaseResult`
now carries observed locale, font-set, and restart-profile identities. A restoration
failure is never suppressed: it upgrades the case terminal to `FAILED` with restoration
evidence (or is aggregated onto an application failure without losing the primary), and every
restore call re-attempts the full host-owned window and locale state independently so an
incomplete restoration is retried on the next case rather than latched into a permanent
no-op. Every case application is bounded by the request's run deadline: the applicator
refuses to start once it is expired and bounds every window wait to the remaining time, so no
application continues beyond the request bound; restoration remains mandatory after expiry
under a separately bounded cleanup deadline and never reuses the expired request deadline.
