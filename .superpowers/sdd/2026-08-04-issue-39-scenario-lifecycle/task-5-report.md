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
