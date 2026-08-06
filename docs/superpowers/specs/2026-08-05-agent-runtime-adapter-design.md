# Agent-Runtime Adapter Design

Date: 2026-08-05
Status: Proposed
Modules: new `harness-agent-runtime`; touched `harness-fixtures`, `gradle/libs.versions.toml`, root `build.gradle.kts`, `settings.gradle.kts`
Primary source: `io.github.teemuki8:agent-runtime-core:1.0.0` (Maven Central, Apache-2.0, Java 25, no transitive dependencies, SCM `teemuki8/libgdx-agent-runtime`)

## Goal

Implement the optional production realization of the ADR 0025 `RuntimeObservationSource` SPI: a publishable adapter module that resolves harness `RuntimeBinding` values from a live `io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime` and proves frame correlation through the runtime's `UiCorrelationRegistry`. The reference application then reports genuine runtime values through `ui_runtime_compare` instead of reading them back off the Stage.

## Module and dependencies

- New Gradle module `harness-agent-runtime`, package `dev.gdx.uiharness.agentruntime`, Java 25 toolchain, checkstyle `maxWarnings = 0`, Javadoc `Werror` — identical conventions to existing modules.
- Dependencies: `api(project(":harness-core"))`, `api(io.github.teemuki8:agent-runtime-core:1.0.0)`.
- Version catalog entry `agent-runtime-core` added to `[libraries]`; dependency lockfiles and `gradle/verification-metadata.xml` updated so the resolved artifact is pinned and verified.
- Module is publishable: added to `publishableModules` in the root build. The generated `apiCompatibility` JApiCmp task for a module without a released baseline must be skipped (no baseline JAR exists yet); the guard is removed once version `1.0.0` of the adapter is released. All other publishable-module guarantees (license in every JAR, signed staging, `verifyCentralStaging` exclusion list) apply unchanged.

## Adapter API

Public surface (new module, no compatibility baseline required):

1. `AgentRuntimeObservationSource implements RuntimeObservationSource`
   - Constructor: `AgentRuntimeObservationSource(AgentRuntime runtime, String uiSessionId)`.
   - `available(RuntimeBinding)`: true when the runtime is started and the bound entity/property resolve in the latest completed frame.
   - `observe(RuntimeBinding) -> Optional<RuntimeObservation>`:
     1. `runtime.latestFrame()` empty ⇒ `Optional.empty()`.
     2. `frame.entity(EntityId.of(binding.entityId()))` empty ⇒ `Optional.empty()`.
     3. `entity.property(binding.propertyId())` empty ⇒ `Optional.empty()`.
     4. Strict correlation: the latest `UiFrameCorrelation` in `runtime.uiCorrelations().framesForUiSession(uiSessionId).items()` whose `correlationToken` equals `binding.correlationId()` and whose `runtimeFrameId` equals the frame the value was read from. No such record ⇒ `Optional.empty()` (the comparator reports UNAVAILABLE; the adapter cannot produce an observation with a frame it cannot prove, so STALE is unreachable through this source in V1).
     5. Value rendered canonically (below). Result `RuntimeObservation(entityId, propertyId, frame = parsed harness frame from `uiFrameId`, revision = frame (documented limitation), value, valueFormatId = binding.valueFormatId())`.
   - Threading: `observe` is called from the harness comparator on the render thread (the fixture wires it through `scheduler.submit`); `AgentRuntime.latestFrame()` is documented safe for concurrent reads.

2. `RuntimeValueRenderer` (public final utility, package `dev.gdx.uiharness.agentruntime`)
   - Canonical rendering per sealed `RuntimeValue` variant, deterministic and bounded:
     - `NullValue` → `""`
     - `BooleanValue` → `"true"` / `"false"`
     - `IntegerValue` → decimal string
     - `DecimalValue` → `toPlainString()` of the already scale-canonicalized value
     - `StringValue` → the string as-is
     - `EnumValue` → the symbol
     - `Vector2Value` → `"(x, y)"` where each component uses Java `Double.toString` of the component value
     - `ListValue` / `ObjectValue` → bounded deterministic rendering (max depth/size enforced; overflow truncates with an explicit marker)
   - The reference app displays the same canonical form, so `RuntimeComparator.typedEqual` (plain or `case-insensitive` equality) holds for matching values.

3. Value format and comparator ids: `RuntimeBinding.valueFormatId()` passes through to the observation; `comparatorId` continues to drive `typedEqual` as today. No new comparison modes in V1.

## Correlation contract

Strict, registry-only (no harness clock inside the adapter):

- The application records one `UiFrameCorrelation(runtimeEpochId, runtimeFrameId, uiSessionId, uiFrameId = harness frame as string, correlationToken = binding.correlationId())` per render frame.
- The adapter never reads the harness clock; frame equality between systems is asserted only via the recorded bridge. Absence of a matching record is UNCORRELATED/UNAVAILABLE, never a clock-based guess.
- `revision = frame` is a documented limitation: `UiFrameCorrelation` carries only a frame id; the comparator correlates on `frame` alone, so EQUAL/STALE semantics are unaffected.

## Reference-app integration (harness-fixtures)

- `ReferenceUiApplication`/`ReferenceScreen` constructs one `AgentRuntime` via `LibGdxAgentRuntime.builder().sessionId(...)` with `start()` on the render thread, registers the `reference-ui-user` entity (`EntityType.of("user")`) declaring property `value` supplied by the username `TextField` text through `EntityInspector.property("value", () -> RuntimeValues.string(...))`.
- Each render frame records the correlation for session `reference-ui` with token `reference-ui-frame` (the token already used by the existing binding) and the harness frame number as `uiFrameId`.
- `FixtureControl` replaces the inline `RuntimeObservationSource` lambda with `AgentRuntimeObservationSource(runtime, "reference-ui")` in the runtime compare coordinator; the `RuntimeProductionFixtureTest` assertion (EQUAL with `displayedValue == runtimeValue == "Ada"`) now proves the value traveled runtime → snapshot → comparator rather than stage self-read.
- Resource lifecycle: `AgentRuntime.close()` on the control's shutdown path alongside the existing close chain.

## Acceptance criteria

Measurable, each with an exact verification command:

1. Module compiles warning-free: `./gradlew :harness-agent-runtime:compileJava` succeeds with `-Xlint:all` and checkstyle clean.
2. Renderer unit tests cover every `RuntimeValue` variant and the truncation marker: `./gradlew :harness-agent-runtime:test --tests 'dev.gdx.uiharness.agentruntime.RuntimeValueRendererTest'` passes.
3. Source unit tests drive a real headless `AgentRuntime` (core has no libGDX dependency): registered entity, advanced frame, recorded correlation ⇒ observation with the harness frame; no correlation ⇒ empty; older runtime frame ⇒ empty. `./gradlew :harness-agent-runtime:test --tests 'dev.gdx.uiharness.agentruntime.AgentRuntimeObservationSourceTest'` passes.
4. Production fixture proves the end-to-end path: `./gradlew :harness-fixtures:test --tests 'dev.gdx.uiharness.fixtures.RuntimeProductionFixtureTest'` passes with EQUAL/Ada/Ada through the adapter.
5. Full gate green including the new module: `./gradlew test checkstyleMain checkstyleTest` — BUILD SUCCESSFUL.
6. JApiCmp gate for the new module is skipped pre-baseline and the existing five modules still pass their baseline checks when invoked with `apiBaselineRepository`/`apiBaselineVersion`.
7. ADR 0026 documents the module, the strict correlation contract, and the `revision = frame` limitation.

## Out of scope (V1)

- Recording `UiFrameCorrelation` on the harness side of arbitrary applications (the app owns the bridge, as ADR 0025 requires).
- New `typedEqual` modes beyond `exact`/`case-insensitive`.
- `agent-runtime-protocol`/`agent-runtime-mcp` interop.
- Publishing automation changes beyond adding the module to `publishableModules` and the apiCompatibility baseline guard.
