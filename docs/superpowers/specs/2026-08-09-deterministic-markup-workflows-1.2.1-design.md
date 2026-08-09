# Harness 1.2.1 Deterministic Gates and Markup-Only Agent Workflows Design

## Objective

Release `libgdx-ui-harness` 1.2.1 with release qualification limited to deterministic,
harness-owned contracts, while making libgdx-ui-markup the only UI-construction mechanism in the
Agentic Palisade workflow. Retain the harness as a general-purpose automation library for arbitrary
application-owned Scene2D stages.

## Scope

This release removes real-model Agentic Palisade repeatability from Maven publication blocking,
keeps its synthetic contract/preflight checks in CI, moves both experimental arms to a shared
markup-only candidate construction path, updates the unpublished production fixture to markup
0.4.1, and adds explicit agent-runtime 1.0.0/2.0.0 compatibility lanes. It does not add markup to
any published harness module and does not publish fixtures or benchmarks.

## Published architecture

The published graph remains:

```text
harness-mcp -> harness-protocol -> harness-core <- harness-scene2d <- harness-lwjgl3
harness-agent-runtime -> harness-core + agent-runtime-core
```

The harness continues to observe and control an application-owned `Stage`. Programmatic Scene2D,
markup-built Scene2D, and other builders remain valid library inputs. Markup is mandatory only in
the repository's agentic UI-authoring workflow and markup-specific interoperability fixture.

`harness-agent-runtime` retains agent-runtime 1.0.0 as its published 1.x compatibility floor.
Changing the exposed dependency major in the POM is deferred to a harness major release. A second
lane must prove that Gradle conflict resolution to agent-runtime 2.0.0 compiles and executes the
adapter contract successfully.

## Deterministic release gate

The required publication gate is the conjunction of:

1. Core semantic, locator, actionability, wait, bounds, trace, and diagnostic tests.
2. Closed protocol/MCP schema goldens and transport hardening tests.
3. Render-thread ownership, real Scene2D input, completed-frame capture, and lifecycle tests under
   Xvfb.
4. Programmatic Scene2D reference fixture, preserving proof that the public harness is builder
   independent.
5. Markup 0.4.1 production-MCP fixture using declared semantics and an independent authoritative
   application value.
6. Fixed Playwright parity: one real run per scenario and system on CI/release, with the existing
   fail-closed scenario/artifact checks.
7. Java API compatibility, Javadocs, dependency verification, lock integrity, and Central bundle
   validation.

Any failure blocks publication.

## Markup-specific interoperability fixture

The unpublished fixture depends on published markup 0.4.1. Its sign-in UI is constructed entirely
from markup. It uses `HarnessSemanticSink` during the build and
`MarkupRuntimeSource.registerAuthoritative` against an independent fixture model. The MCP E2E
must prove strict role/name lookup, real checkbox action, wait, screenshot, correlated `EQUAL`, and
a separately induced `MISMATCH`. It must not use `MarkupRuntimeSource.register` or widget-mirror
mode as domain-correctness evidence.

## Markup-only Agentic Palisade workflow

Both experimental arms receive the identical markup artifact, template, XML/CSS resources,
instructions, build access, and dependency cache. The harness remains the only treatment
difference:

- baseline build: template-owned `MarkupBuilder` with `NoopSink`;
- harness build: the same template-owned builder with treatment-injected `HarnessSemanticSink`.

The candidate no longer owns arbitrary Stage construction. The treatment-neutral template owns
the Stage, parses required bounded XML/CSS resources, builds the actor tree, and installs it. The
candidate supplies the markup resources plus bounded controller/state bindings required by the
benchmark contract. Custom controls must use markup's registered tag extension mechanism rather
than a parallel programmatic UI tree.

Template tests fail when required markup resources are absent, invalid, unbounded, or not built
through the shared path. Treatment symmetry preflight records the exact markup coordinate and
digest for both arms and rejects any difference.

## Agentic benchmark release policy

Agentic Palisade remains a manual or scheduled product-effectiveness benchmark. Its real-model
runs, visual fidelity, cross-run PNG repeatability, token costs, and blind reviews are retained as
evidence but cannot block an otherwise valid library release. The release workflow removes the
sealed repeatability decision and one-time exception mechanism.

Backend-neutral corpus, symmetry, telemetry, evaluator, blinding, runner, and synthetic-pipeline
tests remain required CI checks because they prove that the benchmark itself is honest and usable.
Documentation clearly separates deterministic library qualification from empirical agent/model
effectiveness.

## Compatibility lanes

- `minimumEcosystemTest`: published agent-runtime 1.0.0 compatibility floor.
- `currentEcosystemTest`: published markup 0.4.1 and agent-runtime 2.0.0 with the current local
  harness modules.

Both lanes use exact locked versions and dependency verification. The current lane exercises the
complete markup MCP fixture. Neither lane uses dynamic versions or disables verification.

## Diagnostics and failure policy

Deterministic gate failures preserve their typed harness evidence. Compatibility failures name
the exact dependency lane and resolved coordinate. The benchmark preflight fails before model work
when markup digests, shared inputs, build access, or treatment identities differ. No workflow uses
sleeps as synchronization or silently falls back to programmatic UI construction.

## Documentation and versioning

README, getting-started guidance, benchmark protocol, treatment instructions, release guide, and
release notes identify 1.2.1 as current. Agentic Palisade documentation calls itself non-blocking
effectiveness evidence. Historical release-gate ADRs remain as history and receive a superseding
ADR rather than being rewritten.

The six published 1.2.1 coordinates remain unchanged in name. Fixtures and benchmarks remain
unpublished.

## Acceptance criteria

- No published harness POM depends on markup.
- Programmatic and markup-built real Scene2D fixtures both pass.
- The markup fixture uses 0.4.1 and authoritative independent state.
- Agent-runtime 1.0.0 and 2.0.0 compatibility lanes both pass.
- Both Agentic Palisade arms construct their UI through the identical shared markup path.
- Treatment symmetry rejects differing markup artifacts or markup construction inputs.
- The release workflow contains no real-model/repeatability publication gate or exception marker.
- Fixed Playwright parity, full Xvfb checks, API compatibility, and Central validation remain
  mandatory.
- The v1.2.1 Git tag/GitHub release and all six Maven Central coordinates resolve from the reviewed
  release commit.

## Exact verification

```bash
python3 benchmarks/agentic-palisade/scripts/test-corpus.py
python3 benchmarks/agentic-palisade/scripts/test-treatment-symmetry.py
python3 benchmarks/agentic-palisade/scripts/test-runner.py
./gradlew -p benchmarks/agentic-palisade/template test --warning-mode=fail
./gradlew -PreleaseVersion=1.2.1-candidate \
  -Dmaven.repo.local=build/candidate-maven publishToMavenLocal --warning-mode=fail
xvfb-run -a python3 benchmarks/agentic-palisade/scripts/treatment-preflight.py \
  --output build/agentic-preflight \
  --candidate-maven-repository build/candidate-maven \
  --candidate-version 1.2.1-candidate \
  --gradle-user-home build/agentic-gradle-home --seed-dependencies
xvfb-run -a ./gradlew :harness-fixtures:test \
  --tests '*MarkupFixtureEndToEndTest' --warning-mode=fail
xvfb-run -a ./gradlew minimumEcosystemTest currentEcosystemTest --warning-mode=fail
npm ci --prefix benchmarks/playwright
npx --prefix benchmarks/playwright playwright install chromium
xvfb-run -a ./gradlew :benchmarks:run \
  --args='--runs 1 --output build/reports/parity-release' --warning-mode=fail
xvfb-run -a ./gradlew clean check javadoc apiCompatibility centralBundle \
  -Prelease=true -PreleaseVersion=1.2.1 --warning-mode=fail
```

After publication, the bootstrap release consumes harness 1.2.1 from Maven Central and runs its
complete full-stack E2E before bootstrap v1.2.0 is tagged.
