# ADR 0017: Bounded scenario lifecycle

- Status: Accepted
- Date: 2026-08-04

## Context

Repeatable matrix, navigation, and CI work needs a declared known-state entry point. The harness
must preserve application ownership of the Stage, render loop, input processor, process, and
resources. Accepting caller-provided reset code or process-launch instructions would make scenario
execution neither reproducible nor safe.

Some applications need only direct inspection and control. Scenario registration and process
restart coordination therefore cannot become mandatory dependencies of a harness session.

## Decision

Scenario definitions and requests use schema version 1. Applications register immutable bounded
definitions and lifecycle hooks explicitly. In-process hooks execute through the Scene2D runner on
the render thread, and readiness is accepted only from completed frames under an injected monotonic
deadline. A scenario duration or request deadline may be at most `PT10M`, inclusively.

Configuration is limited to 256 identifier keys and bounded string values. It is defensively copied
and sorted by key before execution and hashing, so equivalent inputs have one canonical form. A
start result retains the definition version, configuration digest, seed, application/process/session
identities, start and ready frame/revision pairs, selected profile, elapsed time, setup attempts,
cleanup status, and the closed terminal failure category when present.

Restart-required display and backend changes remain behind the optional host-owned
`RegisteredLaunchCoordinator`. Its public contract accepts the already validated bounded
`ScenarioRequest`, including its registered profile ID, and returns either a closed failure or the
replacement context's terminal `ScenarioResult` with an opaque bounded reconnect identity. The
host privately owns commands, launch, transport, and reconnect. The replaced process reports the
handoff outcome only; it does not execute the pending scenario and cannot invent replacement
identities.

Protocol V1 adds the closed `scenario-list` and `scenario-start` command/result variants. MCP adds
`ui_scenarios` and `ui_scenario_start`. A start request accepts only a scenario ID, seed, bounded
configuration, registered profile ID, and explicit deadline. It never accepts code, commands,
filesystem paths, environment variables, class names, launch arguments, or reflection targets.
Unknown scenario, incompatible scenario, and unsupported profile selections are closed terminal
rejections rather than transport extensions.

Scenario registration and coordination are optional session capabilities. Listing reports an
explicit unavailable catalog when no registry is registered, and start returns an explicit
unavailable terminal outcome when either required boundary is absent. Existing six-argument
`HarnessProtocolService.Session` construction remains supported without scenario dependencies.

## Consequences

- Applications retain ownership of lifecycle hooks and resources. A coordinator accepting a
  restart handoff owns scenario execution in a distinct replacement process/session context.
- Callers can select only host-registered identities and bounded deterministic data; launch and
  transport inputs never enter protocol or MCP.
- Identical configuration maps have stable ordering and digest inputs independent of caller map
  iteration order.
- Scenario and registered restart handoff attempts have finite, serializable terminal outcome sets.
- Sessions that do not register scenarios retain their existing inspection and control behavior.
- Protocol V1 and the MCP catalog grow additively by two allowlisted operations.

## Verification

```bash
./gradlew :harness-protocol:test :harness-mcp:test --no-daemon --console=plain --warning-mode=fail
```
