# Task 2 report

## Status

Complete. `Scene2dScenarioRunner` now runs setup, reset, readiness, identity, and cleanup through the render-thread scheduler, consumes completed `SemanticSnapshot` frames, returns one terminal `ScenarioResult`, performs cancellation cleanup, and detects repeated-input identity drift. `Scene2dSession.completedFrame(...)` enforces owner-render-thread capture before publishing a completed frame.

## Files

- Created `harness-scene2d/src/main/java/dev/gdx/uiharness/scene2d/Scene2dScenarioRunner.java`
- Modified `harness-scene2d/src/main/java/dev/gdx/uiharness/scene2d/Scene2dSession.java`
- Created `harness-scene2d/src/test/java/dev/gdx/uiharness/scene2d/Scene2dScenarioRunnerTest.java`
- `Scene2dTestSupport.java` required no change; its existing real-Stage fixture was sufficient.

## Red

Command:

`./gradlew :harness-scene2d:test --tests '*Scene2dScenarioRunnerTest' --no-daemon --console=plain --warning-mode=fail`

Expected failure: `:harness-scene2d:compileTestJava FAILED` because `Scene2dScenarioRunner` and `Scene2dSession.completedFrame(...)` did not exist. A second focused red run for owner-thread enforcement produced `5 tests completed, 1 failed` at `completedStageFramesCannotBeReadOffTheRenderThread` before the guard was added.

## Green

Command:

`./gradlew :harness-scene2d:test --tests '*Scene2dScenarioRunnerTest' --no-daemon --console=plain --warning-mode=fail`

Exact result: `BUILD SUCCESSFUL in 6s`, `10 actionable tasks: 2 executed, 8 up-to-date`. The focused XML result records `5` tests, `0` skipped, `0` failures, and `0` errors. A targeted source search found no `Thread.sleep`/`sleep(...)` path in the runner or its test.

## Commit

`9752060` — `Run scenarios on completed Scene2D frames`

## Self-review

- The runner is an explicit bounded phase machine (`QUEUED`, `STARTING`, `WAITING_FOR_FRAME`, `CANCELLING`, `CLEANING`, `TERMINAL`) and every terminal path is guarded against duplicate completion.
- Lifecycle hooks execute only from scheduler commands; completed Stage snapshots are captured only through the session's owner-thread-checked integration method.
- Readiness is evaluated only after a supplied completed semantic frame and uses the injected monotonic clock/deadline; no sleeps or wall clock are used.
- Results preserve schema/definition versions, canonical configuration SHA-256, seed, application/process/session/profile identities, frame/revision correlation, elapsed duration, setup attempts, cleanup status, and failure category.
- Configuration hashing consumes Task 1's canonically sorted immutable map and length-prefixes UTF-8 keys/values to avoid concatenation ambiguity.
- Cancellation schedules cleanup rather than interrupting render-thread work; repeatability is keyed by scenario, seed, canonical configuration digest, and profile.
- The implementation changes only the three Task 2 files needed; the existing test fixture needed no additional plumbing.

## Concerns

- Validation was intentionally limited to the requested focused `Scene2dScenarioRunnerTest`; no formatter, linter, or project-wide suite was run.
- Unknown-scenario, application-compatibility, and unsupported-profile result mapping are outside the Task 2 behavioral test set; the runner currently rejects those inputs synchronously through the Task 1 registry/definition contracts.


## Fix round 1

Observed every initial, completed-frame, cancellation, and deadline-cleanup render-thread
submission. A rejected submission now atomically terminalizes the run as
`DISPATCH_FAILED`, removes it from the active set, and records cleanup as incomplete rather
than implying render-thread cleanup ran. Added the caller-owned
`Scene2dScenarioDeadlineScheduler` boundary; each run schedules the earlier of its request
deadline and definition maximum duration, submits expiry cleanup to the render thread, and
invalidates the scheduled signal on every terminal path. The completed-frame deadline check
remains as a race-safe second trigger.

### Red

Command:

`./gradlew :harness-scene2d:test --tests '*Scene2dScenarioRunnerTest' --no-daemon --console=plain --warning-mode=fail`

Expected result: `:harness-scene2d:compileTestJava FAILED` with `7 errors` because
`Scene2dScenarioDeadlineScheduler`, the injected runner constructor,
`ScenarioFailure.DISPATCH_FAILED`, and its scheduling contract did not yet exist.

### Green

Command:

`./gradlew :harness-scene2d:test --tests '*Scene2dScenarioRunnerTest' --no-daemon --console=plain --warning-mode=fail`

Exact result: `BUILD SUCCESSFUL in 7s`, `10 actionable tasks: 5 executed, 5 up-to-date`.
The focused XML records `10` tests, `0` skipped, `0` failures, and `0` errors.
The focused tests cover rejected initial, completed-frame, and cancellation submissions,
deadline expiry without another frame, scheduled-deadline invalidation, and the original
lifecycle behavior.

### Concerns

- Validation remains intentionally limited to the requested focused
  `Scene2dScenarioRunnerTest`; no formatter, linter, or broad suite was run.
- The injected deadline scheduler is caller-owned by contract; the runner cancels per-run
  registrations but does not close the scheduler or its backing resources.