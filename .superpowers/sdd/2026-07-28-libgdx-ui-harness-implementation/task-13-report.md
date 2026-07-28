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

No secret value is logged, persisted, passed on a command line, or included in failure artifacts. Workflow expressions do not interpolate untrusted values into release commands without semver validation. Module POM scopes match public signatures; fixtures/benchmarks remain unpublished. The release staging task cannot be exercised locally without real PGP/Central secrets and intentionally fails closed; live Central `VALIDATED`/`PUBLISHED` verification remains the signed-tag workflow's responsibility.
