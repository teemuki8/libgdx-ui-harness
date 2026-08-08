# ADR 0009: Bounded Agent Recovery and Safe Reuse

- Status: Accepted
- Date: 2026-07-30

## Context

Individual harness operations were bounded, but an agent workflow could retry
schema failures, rebuild, relaunch, or inspect unchanged state without a
workflow-level ceiling. MCP schema failures also collapsed multiple detectable
field problems into generic text. A new request or process identity could make
the same failed intent look new, and an in-memory terminal result could be lost
when its owner exited.

The catalog already contains twelve operations because typography, layout, and
inspect/compare were added after the original nine-operation benchmark
observation. Removing those operations would break the stable V1 surface.

## Decision

`diagnostic-envelope/v1` is the agent-facing failure contract.
`diagnostic-code-registry/v1` closes the code set and fixes each code's default
transient or terminal disposition for the major version. Every field problem
includes its path, bounded observed value, applicable schema constraints,
admissible values, and a minimal valid example. Validation reports every
independently detectable problem in field-path order.

`ui_capabilities` advertises `operation-catalog/v1`, the complete twelve-tool
input/output schemas, one valid example for every tool and action variant, the
diagnostic registry, and `recovery-policy/v1`. Examples are tested against the
same schemas used for calls. Valid caller session identity is preserved in a
suggested correction.

The fixed recovery policy has finite positive schema, state, unchanged
inspect, unchanged build, unchanged launch, and monotonic wall-time ceilings.
Responses expose consumed and remaining counts before and after each decision.
Request and process IDs do not participate in normalized intent identity.
Productive semantic, visual, artifact, source, build, or runtime progress may
reset only the consecutive no-progress count; total iterations and hard cost
counts never reset.

Build reuse requires identical source, dependency, toolchain, build
configuration, and build-output digests and a successful build in the same
run. Runtime reuse additionally requires identical launch configuration,
healthy process and session, application identity, viewport policy, and
compatible revision. Missing identity rebuilds and relaunches. Changed runtime
identity relaunches. Every decision carries a stable reason.

Terminal decisions retain `terminal-recovery-record/v1`. Its digest covers the
terminal code, rule, counters, normalized last attempt, and elapsed time. An
explicit owner-scoped store writes the verified record atomically and
re-verifies it after process restart. No store path is accepted through MCP.

## Consequences

Agents can correct malformed calls without guessing, while repeated equivalent
failures terminate at an exact boundary. A successful corrected operation
reports the recovery already consumed for its session. Unknown operations,
protocol incompatibility, build/launch failures, elapsed deadlines, and
exhausted budgets are terminal and cannot be retried automatically.

The deterministic convergence qualification uses three frozen matched pairs
for actionable diagnostics versus generic-error control and three for safe
reuse versus disabled control. It reports wall time, input tokens, edits,
builds, and launches separately from semantic, transition, automated visual,
structural usability, capture-repeatability, and human-visual gates. Its causal
interpretation is limited to those controlled fixtures; it is not a population
estimate or a replacement for a new measured agent batch.

## Verification

Run:

```text
./gradlew :harness-protocol:test :harness-mcp:test --warning-mode=fail
python3 benchmarks/agentic-palisade/scripts/test-convergence-qualification.py
python3 benchmarks/agentic-palisade/scripts/convergence-qualification.py
./gradlew check javadoc --warning-mode=fail
```

The tests cover the closed registry, bounded immutable envelopes, all-errors
schema diagnostics, every advertised example, one-step screenshot correction,
request/process/key-order churn, exact schema/state/build/launch/deadline
boundaries, semantic progress, safe reuse and invalidation, and durable
digest-verified terminal retention.

## Amendment 2026-08-08: Bounded MCP Recovery Accounting

The MCP adapter's recovery accounting is globally bounded: session and
fingerprint keys live in access-ordered stores capped at 4,096 entries each
with a 10-minute monotonic TTL. A new key at capacity is deterministically
terminally rejected with `accounting-capacity/v1` (`RECOVERY_BUDGET_EXHAUSTED`,
consumed equals the limit, remaining zero) and never inserted, so an active
key is never evicted, a rejected key stays terminal across repeated attempts,
and flooding can never reset any tracked budget. `elapsedMillis` is measured
from the first transient attempt of the current workflow, never from server
construction, so an immediate success reports zero elapsed and never exceeds
`maxWallTimeMillis`. Successful operations, terminal termination, session
close, and server close remove the session's state; a later workflow starts
fresh. The fixed schema/state/build/launch/deadline ceilings and the digest-
bound terminal record are unchanged.
