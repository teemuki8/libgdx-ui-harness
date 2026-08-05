# ADR 0021: Whole-stage layout validation

## Status

Accepted

## Context

The actor-attributed `ui_layout_diagnose` operation explains selected layout differences, but callers still discover common defects one actor at a time, and CI lacks a bounded whole-stage quality gate. Validation must run from one immutable completed-frame observation, share one engine between full-stage and strict subtree modes, keep false-positive-prone checks as explicit opt-ins, and produce a deterministic pass/fail result usable as a CI gate.

## Decision

Add the pure `LayoutValidator` engine in `harness-core`, the `Scene2dLayoutValidator` adapter, the closed `layout-validate` protocol operation, and the `ui_validate_layout` MCP tool.

The core engine consumes one `SemanticSnapshot` plus optional navigation evidence and a bounded `LayoutValidationConfig`. High-confidence checks run by default: outside viewport, clipped text, interactive overlap, zero size, duplicate test identifiers, missing accessible names, keyboard reachability, and z-order obscuration. Below-target-size, inconsistent alignment, and inconsistent spacing remain explicit opt-ins with reported thresholds. Findings use closed reason codes and severities; the CI status fails at or above a configurable severity gate and reports truncation of findings or examined nodes as incomplete. Keyboard reachability consumes the #35 `NavigationResult`; without navigation evidence the check reports unavailable rather than guessing reachability from focusable flags.

The Scene2D adapter captures one atomic completed-frame snapshot on the render thread and strictly resolves a lazy subtree locator, rebuilding the subtree as an independent root so the same engine validates either mode. Strict zero-match and multiple-match subtree failures remain distinct. No `Actor`, `Stage`, or backend type crosses into protocol data.

The protocol session gains an optional `LayoutValidationCoordinator` boundary with backward-compatible constructors. The closed request carries the target mode, optional subtree locator, enabled checks, thresholds, severity gate, and hard bounds; the result carries status, findings, examined node count, truncation, and the applied configuration.

## Consequences

CI gains one deterministic bounded whole-stage layout gate that discovers invariant violations while `ui_layout_diagnose` continues to explain reference differences. False-positive-prone checks stay opt-in with reported thresholds. Adding a check or reason code requires protocol golden updates, schema review, and the exact MCP catalog update.
