# ADR 0030: Markup-declared fixture screen against published libgdx-ui-markup

## Status

Accepted

## Context

The harness proves its value against the imperative `ReferenceScreen`, whose semantics are
wired by hand (`tag()` calls, `bind`, typography and layout metadata). libgdx-ui-markup 0.2.0
publishes the alternative authoring path: the same UI declared in markup produces
`testId`/`role`/`accessibleName`/runtime bindings by construction. The harness repository
should prove that promise against its own production MCP surface, and its fixture process
should exercise the repository's project modules rather than published harness jars.

## Decision

Add a second, additively selectable fixture screen to the reference process.

1. **Screen selection by flag.** `ReferenceUiApplication` accepts an optional final `markup`
   launch argument that selects `MarkupSigninScreen` instead of `ReferenceScreen`. All existing
   launches (no flag) are unchanged.
2. **Markup as the single source of truth.** `MarkupSigninScreen` embeds the canonical sign-in
   markup and CSS, builds the scene with `MarkupBuilder` and the `DefaultSkin`, and applies the
   harness `Semantics` facade through the published `HarnessSemanticSink` during
   `attachSemantics`. `data-runtime-entity` actors are registered against the fixture's
   `AgentRuntime` through the published `MarkupRuntimeSource`, carrying the shared
   `reference-ui-frame` correlation token so `ui_runtime_compare` proves frames.
3. **Published adapter, project harness.** `libgdx-ui-markup` 0.2.0 (core, harness adapter,
   runtime adapter) is a fixture-only dependency. The published transitive copies of
   `harness-core`/`harness-scene2d` are excluded so the fixture resolves those from the
   repository's project modules.
4. **Screen-provided reference controls.** The typography and layout references were built
   from hardcoded `ReferenceScreen` actor ids. `FixtureScreen` now declares
   `typographyControlIds()` and `layoutControlIds()`, and `FixtureControl` builds its
   references from the active screen's declared ids; `MarkupSigninScreen` marks its title label
   with the same typography/layout metadata pattern as the reference screen.
5. **Lifecycle order.** The markup runtime registrations close before `FixtureControl` closes
   the shared `AgentRuntime`, mirroring the preview's source-then-runtime order.

## Consequences

- The harness's own suite now drives a fully markup-declared screen through the production
  MCP: role/name locators resolve to markup-declared test ids, real input changes widget
  state, `ui_runtime_compare` returns EQUAL on the markup `data-runtime-entity` binding, and a
  screenshot is captured (`MarkupFixtureEndToEndTest`).
- Dependency locking and verification metadata gain the three published markup artifacts;
  `gradle.lockfile` and `gradle/verification-metadata.xml` must be updated together when the
  markup version bumps.
- The reference surface stays stable: the reference screen's typography and layout control ids
  are unchanged, and existing fixture tests pass unmodified.
