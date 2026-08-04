# Task 3 report

## Status

Complete. Added an optional host-implemented `RegisteredLaunchCoordinator` SPI, immutable bounded `LaunchProfile` metadata, and immutable successful `LaunchResult` replacement evidence. The public surface accepts only a registered profile ID and `Deadline`; launch commands, executable/class names, filesystem paths, environment, and launch arguments remain host-private.

## Files

- Created `harness-lwjgl3/src/main/java/dev/gdx/uiharness/lwjgl3/LaunchProfile.java`.
- Created `harness-lwjgl3/src/main/java/dev/gdx/uiharness/lwjgl3/RegisteredLaunchCoordinator.java`.
- Created `harness-lwjgl3/src/test/java/dev/gdx/uiharness/lwjgl3/RegisteredLaunchCoordinatorTest.java`.

## Red

Command:

`./gradlew :harness-lwjgl3:test --tests '*RegisteredLaunchCoordinatorTest' --no-daemon --console=plain --warning-mode=fail`

Expected result: `:harness-lwjgl3:compileTestJava FAILED` with 14 missing-symbol errors because `LaunchProfile`, `RegisteredLaunchCoordinator`, and `RegisteredLaunchCoordinator.LaunchResult` did not exist.

## Green

Command:

`./gradlew :harness-lwjgl3:test --tests '*RegisteredLaunchCoordinatorTest' --no-daemon --console=plain --warning-mode=fail`

Exact result: `BUILD SUCCESSFUL in 7s`, `7 actionable tasks: 4 executed, 3 up-to-date`. The focused XML result records 7 tests, 0 skipped, 0 failures, and 0 errors.

Coverage includes host allowlist handling for known and unknown profile IDs, application compatibility, distinct replacement process/session identities, expired deadlines, cancellation propagation, schema/identifier/PT10M bounds, and an exact public-record component allowlist that excludes caller-supplied launch details.

## Commit

`1f48646` — `Add registered LWJGL3 launch profiles`

## Self-review

- The coordinator remains a functional host SPI rather than a built-in launcher; it contains no process APIs, command execution, reflection, paths, environment, classes, or arguments.
- The coordinator does not create a scheduler or thread. It consumes the established `Deadline`; host/caller code retains scheduling and cancellation ownership.
- `LaunchProfile` carries only schema version, stable profile ID, and compatible application ID.
- `LaunchResult` carries only schema/profile/application correlation, previous and replacement process/session identities, and elapsed timing.
- Profile and result construction enforce schema version 1, nonblank bounded identifiers, and the established PT10M maximum timing. Successful result construction rejects unchanged process or session identities.
- Records are immutable by construction and expose no mutable collections.
- The tests use an in-memory host implementation solely to exercise the SPI contract; production code does not acquire launch authority.

## Concerns

- Validation was intentionally limited to the requested `RegisteredLaunchCoordinatorTest`; no formatter, linter, or broad suite was run.
- Unknown profile, incompatible application, expired deadline, restart rejection, and cancellation terminalization are intentionally owned by each host implementation and its returned `CompletionStage`; this optional SPI does not impose a built-in launcher or scheduler.

## Fix round 1

Added a closed production `LaunchOutcome` terminal type. Successful `LaunchResult` values
implement it and exclusively carry replacement process/session identities; the production
`LaunchFailure` enum distinguishes `UNKNOWN_PROFILE`, `INCOMPATIBLE_APPLICATION`, `DEADLINE`,
and `CANCELLED`. Focused tests now assert those production outcomes rather than test-specific
exception classes or future cancellation.

### Red

Command:

`./gradlew :harness-lwjgl3:test --tests '*RegisteredLaunchCoordinatorTest' --no-daemon --console=plain --warning-mode=fail`

Exact result: `BUILD FAILED in 6s`, `5 actionable tasks: 1 executed, 4 up-to-date`.
`:harness-lwjgl3:compileTestJava FAILED` with 8 missing-symbol errors for the not-yet-defined
`RegisteredLaunchCoordinator.LaunchFailure`.

### Green

Command:

`./gradlew :harness-lwjgl3:test --tests '*RegisteredLaunchCoordinatorTest' --no-daemon --console=plain --warning-mode=fail`

Exact result: `BUILD SUCCESSFUL in 6s`, `7 actionable tasks: 3 executed, 4 up-to-date`.
The focused XML result records 7 tests, 0 skipped, 0 failures, and 0 errors.

### Concerns

Validation remained intentionally limited to `RegisteredLaunchCoordinatorTest`; built-in
launching, broader suites, formatters, and linters were not run.
