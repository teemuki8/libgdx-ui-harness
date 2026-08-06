# ADR 0026: Agent runtime adapter for authoritative runtime values

## Status

Accepted

## Context

The ADR 0025 `RuntimeObservationSource` SPI is deliberately provider-agnostic: it accepts any read-only observation source without requiring a runtime library, so the harness remains fully usable without one. The reference application previously satisfied that SPI by reading values back off the Stage, which only proves what the UI renders and cannot prove the state of the application runtime itself. Displayed and runtime values could therefore agree while the underlying simulation was wrong, weakening `ui_runtime_compare` EQUAL from a correctness claim to a rendering coincidence. `io.github.teemuki8:agent-runtime-core:1.0.0` is the authoritative runtime provider: it drives the simulation, exposes typed per-frame entity state through `latestFrame()`, and records the exact frame and epoch each UI interaction correlated with.

## Decision

Add a new publishable `harness-agent-runtime` module that adapts `agent-runtime-core` into the ADR 0025 SPI.

`AgentRuntimeObservationSource` implements `RuntimeObservationSource` over a running `AgentRuntime`, resolving the binding's entity ID to an `EntitySnapshot` from `AgentRuntime.latestFrame()` and reading the bound property through `RuntimeValue`. A canonical bounded `RuntimeValueRenderer` converts the sealed `RuntimeValue` hierarchy (null, boolean, integer, decimal, string, enum, vector2, list, object) into the SPI's string value and value-format pair, so every source renders runtime values identically.

Frame correlation is strict. The source records one `UiFrameCorrelation` per render frame through `UiCorrelationRegistry`, and a `ui_runtime_compare` observation is only produced when the binding's correlation token and the runtime frame both match the binding and the value's frame. There is no harness-clock fallback: if the correlation cannot be proven, the source reports nothing rather than guessing a frame.

The observation `revision` mirrors the harness-proven frame as a documented limitation, because `UiFrameCorrelation` carries only a frame identifier and `agent-runtime-core` exposes no independent revision counter; consumers must treat `revision` as the correlated frame, never as a runtime-internal tick.

Because the new module is publishable, it inherits the generated `apiCompatibility` JApiCmp task, which is skipped until a released baseline jar exists to compare against.

## Consequences

`ui_runtime_compare` EQUAL now proves a genuine runtime value: the displayed value equals the value the application runtime actually computed on a provably correlated frame. Applications using the adapter must record one `UiFrameCorrelation` per render frame, and a missing correlation yields an empty observation that the comparator reports as UNAVAILABLE, making STALE unreachable through this source. The module is a sixth publishable artifact in the same Central group and under the same licensing, and the `revision` limitation is documented on the source so consumers never treat it as a runtime tick.
