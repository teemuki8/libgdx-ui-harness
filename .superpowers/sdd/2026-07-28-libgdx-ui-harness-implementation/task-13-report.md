# Task 13 Report

## Status

Complete. The JDK 25 release candidate gate, warning-free generated Javadocs, Maven-local publication inspection, clean offline build, five real MCP workflows, exact nine-tool schema contract, and strict reaggregation of the current 400 parity observations pass. Linux local native verification used `DISPLAY=:0` because this workstation has no `xvfb-run`; CI installs and uses Xvfb.

## Commit

`e3ad21b` — `ci: gate and publish the UI harness`

## Files

- `.github/workflows/ci.yml`, `.github/workflows/release.yml`
- `build.gradle.kts`, `gradle/libs.versions.toml`, `gradle/verification-metadata.xml`
- root/settings and all seven project `gradle.lockfile` files
- publishable module build files for Scene2D, protocol, and MCP dependency scopes
- `harness-fixtures/.../PublicApiExampleTest.java`, `FixtureHarness.java`
- ADRs 0001/0002, getting-started, agent-tool, semantic-metadata guides, and V1 release notes
- focused deferred regressions and source fixes in core trace/locator, protocol JSON/command, benchmark parser, Scene2D tests, and mapper consumers

## RED and GREEN

- RED: `./gradlew :harness-fixtures:test --tests '*PublicApiExampleTest'` failed compilation only because `FixtureHarness` did not exist (`artifact://906`).
- GREEN: the exact documented method compiles and runs (`artifact://908`); a mechanical comparison confirmed the dedented test method equals the getting-started code block.
- RED: mutable canonical mapper and malformed-regex decode focused tests both failed for their intended contract (`artifact://910`).
- GREEN: `ProtocolJson.mapper()` now returns an isolated configuration copy and regex syntax is compiled during DTO construction, so decode maps it to `invalid-request` (`artifact://912`).
- RED: drive-qualified ZIP test initially passed for the wrong missing-manifest reason; after requiring the exact unsafe-entry message it failed (`artifact://918`).
- GREEN: `C:/...` entries are rejected at archive entry validation (`artifact://924`).
- Direct candidate evidence-map defensive-copy test passes (`artifact://914`). Parser duplicate/delay negatives now use otherwise-valid nonempty steps and assert the intended root cause (`artifact://924`).

## Commands and Results

- `DISPLAY=:0 ./gradlew clean check --write-locks --warning-mode=fail` exposed and led to source removal of pre-existing unused Scene2D imports; subsequent `check --write-locks` passed (`artifact://928`).
- `./gradlew javadoc --rerun-tasks --warning-mode=fail` — PASS with no warning output after splitting locator/filter auxiliary classes, replacing the deprecated Jackson builder call, and making the benchmark exception evidence transient (`artifact://950`). Generated Javadocs apply full doclint except the missing-comment category; `-Werror` remains active for emitted warnings.
- `DISPLAY=:0 ./gradlew --write-verification-metadata sha256 check javadoc publishToMavenLocal dependencies --configuration japicmp --warning-mode=fail` — PASS and generated complete SHA-256 verification metadata (`artifact://952`).
- `DISPLAY=:0 ./gradlew clean check javadoc publishToMavenLocal --warning-mode=fail` — PASS, 84 tasks (`artifact://956`).
- `DISPLAY=:0 ./gradlew --offline clean check --warning-mode=fail` — PASS from clean state before and after final source changes; final run 52 tasks (`artifact://976`).
- `DISPLAY=:0 ./gradlew :harness-fixtures:test :harness-lwjgl3:test --rerun-tasks --warning-mode=fail` — PASS (`artifact://965`).
- `DISPLAY=:0 ./gradlew :harness-fixtures:test --tests '*ReferenceApplicationSmokeTest' --rerun-tasks --warning-mode=fail` — PASS, five consecutive real stdio MCP workflows (`artifact://963`).
- `DISPLAY=:0 ./gradlew :benchmarks:run --args='--runs 20 --output build/reports/parity-round1 --aggregate-only' --warning-mode=fail` — PASS, `harness=200/200 playwright=200/200 raw=400` (`artifact://967`). Task 13 changed no behavior/corpus, so current Task 12 raw evidence was strictly reaggregated rather than rerun.
- `./gradlew :harness-mcp:test --tests '*HarnessToolCatalogTest' --warning-mode=fail` — PASS (`artifact://961`).
- Self-baseline japicmp binary/source gate for all five modules — PASS, no changes (`artifact://959`). CI conditionally obtains the latest release and skips only when no release exists.
- Ruby Psych parsed both workflow YAML files. A PCRE audit found no third-party `uses:` reference lacking a full 40-character SHA.
- Release fail-closed proof without secrets rejected the build and named all four required values (`artifact://970`).

## Maven Inspection

Maven local contains exactly `harness-core`, `harness-scene2d`, `harness-lwjgl3`, `harness-protocol`, and `harness-mcp`, each with nonempty main, sources, Javadoc, POM, and Gradle-module artifacts. No fixture or benchmark publication exists.

Every POM contains name, description, URL, Apache-2.0 license, developer, and SCM coordinates. Observed scopes/versions:

- core: no dependencies;
- scene2d: core `compile`, libGDX `1.14.2` `compile`;
- lwjgl3: scene2d `compile`, backend `1.14.2` `runtime`;
- protocol: core `compile`, Jackson `2.22.1` `compile` because its public mapper API returns `ObjectMapper`;
- MCP: protocol and MCP SDK `2.0.0` `compile`; Jackson `2.22.1` and SLF4J nop `2.0.17` `runtime`.

## Schema Inspection

The golden catalog contains exactly nine names, in order: `ui_sessions`, `ui_snapshot`, `ui_query`, `ui_action`, `ui_wait`, `ui_screenshot`, `ui_trace_start`, `ui_trace_stop`, and `ui_capabilities`. Closed schemas reject unknown fields and expose no path, code, script, command, class, method, or reflection parameters. Guides record envelope, locator/action unions, deadline, request/response/string/nesting/regex, semantic, screenshot, trace, and artifact limits.

## Workflow and Security Review

- CI: read-only default permissions; Linux/Windows/macOS JDK 25 clean checks; Linux Xvfb; explicit native smoke; real 1x parity smoke; offline/lock drift; dependency review; conditional latest-release API comparison; CodeQL alone receives `security-events: write`; failure-only diagnostic retention.
- Release: annotated PGP-signed semver tag verification and tag-to-commit binding; persisted checkout credentials disabled; Central user-token and in-memory PGP secrets required; deterministic five-module staging inspection requires JAR/sources/Javadocs/POM and signatures; fixture/benchmark exclusion; user-managed upload, `VALIDATED` gate, explicit publish, then `PUBLISHED` gate.
- Dependencies: fixed versions only, wrapper checksum, committed locks, SHA-256 verification metadata, immutable action SHAs, and offline proof.
- Protocol/artifacts: canonical mapper state is private, malformed regex fails at decode, archive drive/traversal names fail before lookup, and docs prohibit executable/path inputs and unsafe artifact use.

## Self-review

No secret value is logged, passed in curl arguments, inherited by third-party actions, or included in failure artifacts. Central authorization exists only in a mode-0600 temporary curl config removed by an EXIT trap. Workflow expressions do not interpolate untrusted values into release commands without semver validation. Module POM scopes match public signatures; fixtures/benchmarks remain unpublished. The release staging task cannot be exercised locally without real PGP/Central secrets and intentionally fails closed; live Central `VALIDATED`/`PUBLISHED` verification remains the signed-tag workflow's responsibility.

## Review Fix Round 1

Commit: `36e7701` — `fix: harden release trust and native launch`

The five merge-blocking release findings were resolved:

1. Tag verification now requires repository-configured armored public key secret
   `RELEASE_SIGNING_PUBLIC_KEY` and exact allowed primary fingerprint variable
   `RELEASE_SIGNING_FINGERPRINT`. The step imports into an isolated temporary
   `GNUPGHOME`, requires one primary key and an exact normalized 40/64-hex
   fingerprint, verifies the annotated semver tag and tag-to-commit binding,
   and deletes the keyring. No maintainer key or fingerprint is embedded.
2. Central and private signing secrets are no longer job-scoped. The private
   key exists only in the Gradle signing step; Central credentials exist only
   in the four trusted run steps that need release configuration or Central
   access. Checkout/setup/apt/artifact-upload actions inherit none.
3. Central Bearer headers are supplied through per-step mode-0600 temporary
   curl config files, never curl argv. EXIT traps remove the files on success,
   validation failure, curl failure, and timeout; tokens are unset after the
   config is written.
4. Both real LWJGL subprocess launchers share `ReferenceJvmCommand`. macOS adds
   `-XstartOnFirstThread` before the classpath/main class so GLFW construction
   occurs on the new process's first JVM thread; Linux/Windows commands remain
   unchanged.
5. Lock drift now names `settings-gradle.lockfile`, root `gradle.lockfile`, all
   subproject lockfiles, and verification metadata.

Round-1 RED/GREEN and validation:

- `:harness-fixtures:test --tests '*ReferenceJvmCommandTest'` initially failed
  compilation because the shared platform command did not exist
  (`artifact://992`). The focused mac/non-mac command contract and five-run
  reference smoke then passed together (`artifact://998`).
- `python3 scripts/validate-workflows.py` enforces trusted key import and exact
  fingerprint comparison, absence of job-scoped secrets and token-bearing curl
  argv, exact protected curl config cleanup/count, signing-key scope, complete
  lock pathspec, and immutable action SHAs. It passes, and Ruby Psych parses
  both workflow YAML files.
- `DISPLAY=:0 ./gradlew check --warning-mode=fail` passed all 45 check tasks
  locally (`artifact://1000`). No live key, Central secret, or macOS runner was
  available or simulated.

## Review Fix Round 2

Commit: `8887091` — `fix: isolate macOS native qualification`

The macOS matrix no longer attempts `:harness-lwjgl3:test` inside the Gradle
worker used by `clean check`; that worker cannot satisfy Cocoa's first-thread
rule. It runs
`./gradlew clean check -x :harness-lwjgl3:test --warning-mode=fail`, retaining
all other backend-neutral and module checks, then explicitly replaces the one
excluded test task with the dedicated
`ReferenceApplicationSmokeTest` subprocess qualification. Its shared
`ReferenceJvmCommand` puts `-XstartOnFirstThread` before the classpath and main
class on macOS. Five fresh real LWJGL3 processes perform input, semantic
query/wait, deterministic framebuffer screenshot capture, trace recording and
replay, clean exit, and temporary-resource deletion.

RED/GREEN and validation:

- The extended workflow validator initially failed with
  `macOS check must explicitly exclude incompatible inline LWJGL3 tests`.
- After the workflow change, the validator passed. It requires the exact
  exclusion, exact dedicated subprocess smoke command, and check-before-native
  ordering. Ruby Psych parsed both workflow files.
- `DISPLAY=:0 ./gradlew :harness-fixtures:test --tests
  '*ReferenceJvmCommandTest' --tests '*ReferenceApplicationSmokeTest'
  --rerun-tasks --warning-mode=fail` passed all 15 tasks
  (`artifact://1015`), including five complete workflows.
- No macOS runner was available locally; actual Cocoa first-thread execution is
  the explicit macOS CI qualification rather than an unverified local claim.

## Final Branch Review Fix

Commit: `9eea47f` — `fix: propagate cancellation and close failed traces`

The two final whole-branch integration blockers were closed without changing the
V1 schema, typed failure translation, response limits, tool catalog, or fixture
workflow:

1. `HarnessProtocolService.execute` now returns a cancellation-owning response
   future over the actual routed Harness, wait task, capture, or trace stage.
   Routing no longer hides those stages behind `thenApply`/`handle` dependents.
   Cancellation is serialized against source completion; an accepted source
   cancellation cancels the response, while a completion that already won is
   translated normally. Wait work uses an interruptible future and queued work
   does not start after cancellation.
2. Reactor disposal in `HarnessToolHandler` was confirmed to cancel that
   response future. The stdio cancellation contract now requires the routed
   action future to be cancelled, and the subsequent ping remains the next
   response, proving cancellation notifications write no tool response.
3. A real Scene2D Stage integration routes `ui_action` through the MCP handler,
   disposes it before render dispatch, drains later frames, and proves both that
   the exact Scene2D action stage is cancelled and that no input side effect or
   subscriber response occurs.
4. The fixture tracing harness now records a same-request, directly parented
   `COMMAND_FAILED` before rethrowing an action or post-action snapshot failure.
   Recorder failure is attached as suppressed evidence and cannot replace the
   original action failure. Its tracing future also forwards cancellation to
   the currently active delegate stage.

Focused RED evidence:

- Protocol action/capture forwarding and queued-wait prevention both failed at
  their intended assertions (`artifact://1033`).
- The real MCP-to-Scene2D queued action later remained live before the fix
  (`artifact://1039`).
- The failed reference action archive contained only `COMMAND_STARTED`, not the
  required failed child (`artifact://1041`).

GREEN and regression evidence:

- Full protocol service contract: PASS (`artifact://1043`).
- Real MCP-to-Scene2D cancellation/side-effect contract: PASS
  (`artifact://1045`).
- Failed action trace stop/replay, exact
  `COMMAND_STARTED`→`COMMAND_FAILED`, complete manifest, original timeout:
  PASS (`artifact://1049`).
- MCP handler and stdio cancellation contracts: PASS (`artifact://1051`).
- Five successful reference workflows and successful causal traces: PASS
  (`artifact://1053`).
- All affected protocol, MCP, Scene2D, and fixture module tests: PASS
  (`artifact://1055`, 23 tasks).

Race self-review covered cancellation before queue execution, cancellation
against already-completing sources, synchronous cancellation callbacks,
render-dispatch ownership, wait interruption, and trace recorder failure.
Source cancellation is attempted before response cancellation so a queued action
cannot observe a cancelled wrapper while remaining dispatchable; callbacks
arising synchronously from cancellation are deferred or ignored under the same
lifecycle lock.
