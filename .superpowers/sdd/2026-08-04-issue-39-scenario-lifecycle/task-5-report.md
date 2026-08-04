# Task 5 fixture report

## Status

Complete. The real LWJGL3 fixture registers and executes scenario lifecycle hooks through the production `ScenarioRegistry`, `Scene2dScenarioRunner`, `HarnessProtocolService`, and MCP scenario tools.

## Files

- `harness-fixtures/src/main/java/dev/gdx/uiharness/fixtures/FixtureControl.java`
- `harness-fixtures/src/test/java/dev/gdx/uiharness/fixtures/HarnessMcpClient.java`
- `harness-fixtures/src/test/java/dev/gdx/uiharness/fixtures/ScenarioLifecycleFixtureTest.java`

## Red

`./gradlew :harness-fixtures:test --tests '*ScenarioLifecycleFixtureTest' --no-daemon --console=plain --warning-mode=fail`

Failed as expected because the real fixture returned an unavailable scenario catalog (`Expected scenario-list ... available:false`).

## Green

`./gradlew :harness-fixtures:test --tests '*ScenarioLifecycleFixtureTest' --no-daemon --console=plain --warning-mode=fail`

Passed: `BUILD SUCCESSFUL in 11s`, 16 actionable tasks (3 executed, 13 up-to-date).

## Commit

`3b1e2e2` — `Add real scenario lifecycle fixture`

## Self-review

- Confirmed the fixture uses only public production registry, runner, protocol, and MCP paths; no fixture-only start bypass was added.
- Confirmed completed-frame publication occurs after the real Stage draw and before framebuffer publication.
- Confirmed the focused test covers reset of mutable fields, readiness over two completed frames, repeated semantic identity, unknown and incompatible rejection, readiness deadline without a ready frame, cancellation followed by a clean successful run, and the allowlisted restart-required launch profile identity.
- Confirmed lifecycle, runner, deadline executor, and existing fixture resources close in dependency order.
- Ran `git diff --check`; no whitespace errors.

## Concerns

None.

## Fix round 1

### Red

`./gradlew :harness-fixtures:test --tests '*ScenarioLifecycleFixtureTest' --no-daemon --console=plain --warning-mode=fail`

Failed first at compilation because `cancelScenario` did not return observable
state, then failed at the replacement identity assertion because the fixture
scenario boundary had not invoked a `RegisteredLaunchCoordinator`.

### Green

`./gradlew :harness-fixtures:test --tests '*ScenarioLifecycleFixtureTest' --no-daemon --console=plain --warning-mode=fail`

Passed: `BUILD SUCCESSFUL in 9s`, 16 actionable tasks (2 executed,
14 up-to-date).

The fixture host coordinator now accepts only the registered profile ID and
returns closed replacement process/session identities that are used by the
production scenario runner. The deadline fixture withholds only runner
completed-frame notifications while framebuffer publication continues, and
the independently scheduled scenario maximum terminalizes with
`READINESS_DEADLINE`. Cancellation now propagates through the fixture host
mapping, and its cleanup hook is observed through the production semantic
query path before the next start.

## Final review fix wave

### Red

`./gradlew :harness-scene2d:test --tests '*Scene2dScenarioRunnerTest.readinessExceptionIsDistinctFromResetRejection' :harness-fixtures:test --tests '*ScenarioLifecycleFixtureTest' --no-daemon --console=plain --warning-mode=fail`

Failed at test compilation because `ScenarioFailure.READINESS_REJECTED` did not
exist. The same red change added an assertion for the missing `scenario-list`
and `scenario-start` capability advertisements.

`./gradlew :harness-protocol:test :harness-mcp:test --no-daemon --console=plain --warning-mode=fail`

Failed in `HarnessToolCatalogTest.goldenCatalogMatchesTypedSchemas` because the
MCP golden schema did not contain the new closed failure value.

### Green

`./gradlew :harness-scene2d:test --tests '*Scene2dScenarioRunnerTest' :harness-fixtures:test --tests '*ScenarioLifecycleFixtureTest' --no-daemon --console=plain --warning-mode=fail`

Passed: `BUILD SUCCESSFUL in 12s`, 18 actionable tasks (12 executed, 6
up-to-date).

`./gradlew :harness-protocol:test :harness-mcp:test --no-daemon --console=plain --warning-mode=fail`

Passed: `BUILD SUCCESSFUL in 10s`, 12 actionable tasks (4 executed, 8
up-to-date).

### Blocker

The fixture's registered restart remains blocked. The current
`RegisteredLaunchCoordinator` returns only replacement identity strings; it
has no interface for executing the pending `ScenarioRequest` in the
replacement process, and the protocol has no handoff outcome carrying a
host-owned reconnect handle. The existing MCP connection is the old process's
stdio, so spawning a child cannot transfer the request or return the child
result without a private host transport/proxy boundary. Relabeling the old
`Stage` and runner would continue to fabricate replacement evidence.

## Authorized restart handoff

### Red

`./gradlew :harness-lwjgl3:test --tests '*RegisteredLaunchCoordinatorTest' --no-daemon --console=plain --warning-mode=fail`

Failed as expected at compilation because the authorized `restart(ScenarioRequest)` handoff,
`HandoffResult`, and `HandoffFailure` API did not exist.

`./gradlew :harness-fixtures:test --tests '*ScenarioLifecycleFixtureTest' --no-daemon --console=plain --warning-mode=fail`

Failed first because the fixture still used the removed identity-only coordinator API, then failed
MCP output validation because the closed completed-result schema did not yet allow the reconnect
identity. After that schema was added, the cancellation/next-start assertion exposed that the host
had to await replacement-context cleanup before accepting another handoff.

### Green

`./gradlew :harness-lwjgl3:test --tests '*RegisteredLaunchCoordinatorTest' :harness-scene2d:test --tests '*Scene2dScenarioRunnerTest' :harness-protocol:test :harness-mcp:test :harness-fixtures:test --tests '*ScenarioLifecycleFixtureTest' --no-daemon --console=plain --warning-mode=fail`

Passed: `BUILD SUCCESSFUL in 9s`, 26 actionable tasks (2 executed, 24 up-to-date).

The coordinator now accepts only the validated bounded `ScenarioRequest` and returns the
replacement context's terminal `ScenarioResult` with an opaque bounded reconnect identity. The
real fixture creates a distinct Stage, Scene2D session, scheduler, registry, lifecycle, and runner
for every handoff. The old Stage retains its mutated fields, proving it did not execute the pending
request after handoff.
