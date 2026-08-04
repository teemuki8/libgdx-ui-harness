# Remaining Issues Delivery Design

## Purpose

Complete the repository's remaining focused feature issues, #31 through #39, through independently reviewable pull requests. Preserve the harness invariants: render-thread ownership, lazy strict locators, real input dispatch, deterministic completed-frame observation, immutable bounded protocol data, and closed allowlisted MCP schemas.

Issue #9 remains a tracking issue rather than a new implementation surface. It closes only if its existing #1 through #8 evidence proves every terminal criterion. Otherwise it remains open with the exact unmet criterion recorded.

## Scope

The delivery includes the observable contracts already defined by the live issues:

1. #39: bounded deterministic scenario registration, listing, start/reset, readiness, lifecycle correlation, and optional restart-required profiles.
2. #31: declarative assertions through Java, protocol, and `ui_assert`, including frame-based stability and bounded failure evidence.
3. #35: deterministic navigation inspection and validation through real configured input dispatch.
4. #32: bounded full-stage and locator-subtree layout validation, including navigation-based keyboard reachability.
5. #33: bounded scenario-backed display matrix execution and compact result retrieval.
6. #34: versioned semantic baseline comparison independent of raster capture.
7. #36: bounded ranked locator suggestions attached to strict lookup failures without fallback execution.
8. #37: bounded semantic state-transition projections queried from retained causal traces.
9. #38: explicit typed UI-to-runtime entity/property bindings and optional same-frame runtime comparison.

Each issue retains its published acceptance criteria and verification command as its authoritative contract. This design does not add adjacent functionality.

## Delivery alternatives

### Selected: dependency-ordered focused pull requests

Create one pull request per focused issue. Merge each prerequisite before branching its dependents. This keeps issue closure, review evidence, rollback, and exact-head CI unambiguous.

### Rejected: stacked dependency pull requests

Stacked pull requests could expose more work concurrently, but every prerequisite merge would require dependent rebases, repeated exact-head review, and repeated CI. The shared protocol and MCP catalog files make this additional complexity likely to produce review noise rather than useful concurrency.

### Rejected: grouped feature pull requests

Grouping lifecycle, navigation, layout, and matrix work would reduce GitHub operations but create large mixed-contract diffs. It would weaken acceptance-criterion traceability and make defect isolation and rollback harder.

## Dependency and merge order

The merge order is:

1. #39 scenario lifecycle registry.
2. #31 declarative assertions.
3. #35 navigation and focus testing, consuming #39.
4. #32 layout validation, consuming #35 for keyboard reachability.
5. #33 display matrix runner, consuming #39 and #31.
6. #34 semantic golden comparison.
7. #36 strict-failure locator suggestions.
8. #37 state-transition query.
9. #38 runtime entity binding.

Issues #34, #36, #37, and #38 are behaviorally independent of the preceding dependency chain. They still merge sequentially because each extends shared protocol, capability, MCP catalog, and handler surfaces. Every issue branch starts from the then-current remote default branch.

## Module boundaries

### Semantic core

`harness-core` owns immutable, bounded, transport-neutral request, result, reason, evidence, and pure evaluation models. It does not depend on Scene2D, LWJGL3, MCP, application runtime objects, or filesystem access.

### Scene2D adapter

`harness-scene2d` owns Stage and Actor observation, metadata attachment, render-thread scheduling, completed-frame coordination, and real libGDX input dispatch. No Scene2D type crosses into core or protocol models.

### LWJGL3 boundary

`harness-lwjgl3` owns only behavior that needs the desktop backend: observed window/framebuffer/HiDPI state, registered restart coordination, and real graphical fixtures. In-process scenario and semantic contracts remain usable without this module.

### Protocol

`harness-protocol` owns closed versioned commands, responses, validation, serialization, stable ordering, bounds, and error mapping. Unknown variants, unknown fields, unsupported versions, and trust-boundary limit violations fail closed.

### MCP adapter

`harness-mcp` exposes only the issue-authorized allowlisted operations. Tool schemas, handler routing, examples, capability discovery, and operation catalog entries change together and are verified as an exact set.

### Fixtures

`harness-fixtures` provides the real application behavior needed to prove render-frame readiness, real input dispatch, display profiles, and cross-operation correlation. Fixtures do not introduce production-only escape hatches.

## Shared behavioral invariants

### Identity and correlation

Stable semantic identities never depend solely on snapshot-local node IDs or Actor object identity. Results preserve the relevant application, process, session, scenario, trace, semantic revision, and rendered frame identities. Equality or causality is reported only when the retained evidence proves it.

### Strictness

Single-actor operations retain distinct zero-match and multiple-match failures. Count assertions are explicit cardinality operations and do not weaken action strictness. Locator suggestions remain diagnostic and never trigger retry or fallback execution. Ambiguous semantic baselines cannot pass through heuristic pairing.

### Timing and input

Waiting uses the injected monotonic clock and observable completed rendered frames. There are no sleeps. Keyboard, controller, Escape/Back, and actions use the application's configured libGDX input path; listeners are never invoked directly.

### Bounds and security

Every public request and response has explicit limits for applicable counts, depth, strings, duration, frames, retries, pixels, artifacts, evidence, and encoded bytes. Truncation identifies what was omitted. MCP never accepts caller-supplied code, commands, classes, reflection targets, filesystem paths, environment variables, launch arguments, or unrestricted runtime queries.

### Determinism

Identical immutable observations and inputs produce stable result ordering. Scenario repetition either yields the declared stable start-state identity or reports nondeterminism. Matrix preflight computes and bounds the full Cartesian product before starting cases.

### Compatibility

Applications may continue using `Scene2dSession` without scenario registration, a runtime provider, or restart coordinator. Existing trace archive behavior and existing locator/action behavior remain compatible except for additive typed evidence and catalog capabilities defined by the relevant issue.

## Issue-specific design constraints

### #39 Scenario lifecycle

Applications register immutable named scenarios and optional host-owned launch profiles. In-process hooks run on the render thread. Start completes only after a readiness condition passes on a completed frame. Cancellation and every failure path produce a bounded terminal cleanup state. Callers select registered IDs only.

### #31 Declarative assertions

Actor assertions re-resolve a lazy locator on every evaluation. `hidden` does not treat a missing actor as hidden. `stable for N frames` records the compared property set and observes completed frames within a strict maximum. Every failure returns the locator, assertion, expected and observed evidence, frame/revision, elapsed duration, candidates when applicable, truncation, and trace identity.

### #35 Navigation

Traversal starts from a scenario-defined or explicitly observed focus state. Steps record input, before/after frame and revision, stable actor identity, modal boundary, and focus. Cycles, dead ends, modal escapes, focus loss, unreachable controls, and unsupported controller integration remain distinct results.

### #32 Layout validation

One immutable completed-frame observation feeds both full-stage and strict subtree validation. High-confidence checks may be enabled by default. Spacing, alignment, and application-specific target-size checks remain explicit opt-ins with reported thresholds. Keyboard reachability consumes #35's graph and reason codes rather than a second traversal engine.

### #33 Matrix execution

Every case starts from a registered #39 scenario and evaluates the same #31 assertions. Width and height are authoritative geometry inputs; aspect ratio is derived or a named tolerance constraint. UI scale, device pixel ratio, and HiDPI mode remain distinct requested and observed fields. Large evidence remains opaque and bound to the exact case provenance. Started, cancelled, failed, and unstarted cases have terminal states.

### #34 Semantic goldens

Comparison uses stable hierarchy-aware semantic keys and reports every applied key, omission policy, tolerance, and exclusion. Partial nodes constrain only supplied properties unless strict-node mode is selected. Numeric tolerances never hide role, name, identity, or state mismatches. The operation does not require screenshots or framebuffer access.

### #36 Locator suggestions

Suggestions use only the bounded redacted evidence retained by the strict failure. Ranking prefers unique test ID, role plus accessible name, and accessible label before explicitly fragile fallbacks. Multiple-match results include bounded distinguishing properties and unique candidate selectors when the evidence permits them.

### #37 Transition query

Compact summaries project retained trace evidence without reading an arbitrary archive path or inventing causality. Queries are bounded by retained trace identity, locator, allowlisted transition/property kind, and inclusive frame range. Unknown cause, trace gaps, identity ambiguity, and truncation remain explicit.

### #38 Runtime binding

Bindings are explicit session-owned metadata with weak Actor ownership and clear/replace behavior. Runtime observation is an optional read-only application SPI. Typed comparison reports normalizer/comparator identity, redaction, UI and runtime frames, and correlation status. Missing, unavailable, stale, mismatched-frame, ambiguous, unsupported-value, and unequal states remain distinct.

## Test and verification design

Every production behavior starts with the nearest behavioral test and an observed failure. Each pull request then proves:

1. the focused test is green;
2. affected core, Scene2D, protocol, and MCP suites are green;
3. the exact verification command published in the issue is green;
4. the required real LWJGL3 fixture is exercised for #31, #33, #35, #37, and #39, and for any other change that depends on rendering or input;
5. public schema tests reject unknown fields, variants, versions, and over-limit inputs;
6. deterministic repeat, strict failure, boundary, cancellation/deadline, truncation, and unavailable-path tests cover the issue's named cases;
7. Gradle runs through `./gradlew` with JDK 25, `--no-daemon --console=plain --warning-mode=fail`.

Tests assert observable contracts rather than implementation structure. No test weakens an existing warning, validator, bound, security rule, or locator invariant.

## Pull request review and merge gate

For each issue:

1. Create an isolated worktree and issue branch from the current `origin/main`. The unrelated local `main` commit `1e91cbf` is not included.
2. Commit only the issue's code, behavioral tests, required ADR, and directly affected public documentation.
3. Push and open a ready pull request with `Fixes #N`, impact, root cause, acceptance coverage, and exact validation results.
4. Review the remote pull request's base, head, commits, files, full patch, acceptance criteria, bounds, threading, security, compatibility, test quality, review threads, and CI checks.
5. Reproduce each actionable defect. Add or strengthen a behavioral regression test when applicable, make the smallest source fix, push, and repeat review and CI on the new exact head SHA.
6. Merge only when that exact reviewed SHA is mergeable, all required checks pass, and no actionable feedback remains.
7. Verify the pull request is merged and its focused issue is closed before branching the next dependent issue.

Self-approval is not treated as independent review. A clean review records that no findings were found.

## Tracker #9 disposition

After #31 through #39 are complete, review #9 independently. Its focused implementation dependencies are #1 through #8, which are already closed, but checklist state alone is insufficient. Recompute or read the retained machine evidence required by #9. Close #9 only if the all-runs conjunction and every separate evidence channel satisfy its published criteria. If evidence is missing or failing, leave #9 open and post the exact failing or unavailable criterion; do not fabricate a passing release decision and do not expand #31 through #39 to repair unrelated historical qualification evidence.

## Completion criteria

Delivery is complete when:

- nine issue-scoped pull requests are merged;
- issues #31 through #39 are closed by their merged pull requests;
- every merged head passed its required local and GitHub checks;
- every remote pull request received acceptance-criterion and defect review, with all verified findings fixed and re-reviewed;
- #9 is either closed from verified existing evidence or remains open with a precise evidence-backed blocker;
- local `main` is reconciled with `origin/main` without losing or including the intentional local commit `1e91cbf` in any issue pull request; and
- the final worktree state and any intentional local divergence are reported exactly.
