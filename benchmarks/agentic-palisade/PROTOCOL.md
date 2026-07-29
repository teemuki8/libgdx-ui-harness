# Agentic Palisade benchmark protocol

## Approved hypothesis

An implementation agent given only this public protocol and the frozen `agentic-palisade/v1` corpus can reproduce the observable Palisade **Skirmish Configuration** workflow in libGDX with useful behavioral and visual parity. The benchmark tests that constrained reconstruction claim; it does not test access to, translation of, or recollection of the reference implementation.

## Public treatment input

Treatment work may use only this file and the committed `corpus/` directory. The corpus consists of a strict public behavior document, its JSON Schema, and three metadata-stripped black-box PNG observations. The capture utility is for the designated reference operator only and is not treatment input.

The ordered control contract is:

1. `map` — **Map**
2. `playerRealm` — **Player realm**
3. `majorRivalCount` — **Major rival count**
4. `pettyRealmDensity` — **Petty realm density**
5. `startingResources` — **Starting resources**
6. `victoryCondition` — **Victory condition**
7. `rivalTargetCount` — **Rival target count**, present only when `victoryCondition` is `rival-target`
8. `aiDifficulty` — **AI difficulty**
9. `simulationSpeed` — **Simulation speed**
10. `richHarvest` — **High income**
11. `scarceGold` — **Low income**
12. `costlyCavalry` — **Higher cavalry cost**
13. `cavalryRush` — **Faster cavalry**
14. `roadBoom` — **Faster road construction**
15. `seed` — **Seed**
16. `copySeed` — **COPY SEED**
17. `randomSeed` — **RANDOM SEED**
18. `cancel` — **CANCEL**
19. `startBattle` — **START BATTLE**

`corpus/spec.json` is normative for option values and labels, defaults, focus order, numeric and seed validation, conditional visibility, deterministic random and clipboard fixtures, cancel/Escape equivalence, and the accepted configuration payload. Array order is significant. A decimal seed is valid exactly when it represents an integer from 0 through 4,294,967,295 inclusive. An invalid configuration cannot be started.

## Approved states and observations

The public visual surface is bounded to:

- initial configuration at 1920 × 1080;
- the same configuration scrolled to its bottom at 1920 × 1080; and
- initial configuration at 1280 × 720.

All viewports use device scale factor 1. The fixed seed `305419896` makes the bottom observation and transition fixtures repeatable. Every reference is bound by filename, dimensions, byte length, and SHA-256 digest in the spec. References are observations, not reusable art assets.

The behavioral surface additionally includes scrolling, copying the current seed, deterministic random-seed replacement, CANCEL, Escape, and START BATTLE. START BATTLE is evaluated through the normalized `confirmation` payload in the spec; it does not expand the visual scope beyond the configuration workflow.

## Outcomes

Two outcome families are reported separately:

1. **Behavioral conformance:** schema-valid public controls and states; labels, option values, defaults, ordering, focus behavior, validation boundaries, conditional visibility, deterministic seed actions, dismissal behavior, and accepted payload.
2. **Visual parity:** comparison of implementation captures against each approved reference at its exact state and viewport.

A run is conforming only when all mandatory behavioral checks pass. Visual measurements supplement that gate and must not be used to excuse a behavioral mismatch. Results must identify the corpus schema version and reference digests used.

## Blinding

Roles are separated:

- The reference operator may run `capture-reference.mjs` against an explicitly supplied live full-edition checkout solely to produce black-box observations and hashes.
- Treatment agents receive only the public protocol and frozen corpus. They must not inspect the live reference checkout, the capture execution environment, evaluator internals, or non-public evaluation fixtures.
- Evaluator authors may use non-public checks, but those checks and their expected outputs are not treatment input.
- Reference and treatment screenshots are labeled before scoring; comparison tooling must not alter either side based on knowledge of which implementation produced it.

The committed corpus must contain no reference checkout location, reference source files, source excerpts, evaluator internals, or non-public fixtures.

## Failure retention

On any capture, build, launch, behavioral, or visual failure, retain the complete run directory: inputs, process logs, stdout/stderr, screenshots, structured results, hashes, and trace artifacts. Record the failed phase and exit status. Never replace a failed observation with a prior successful artifact or delete evidence before triage. Retry results are additional runs and do not overwrite the original failure.

## Interpretation limits

The benchmark supports conclusions only about reconstruction of the declared Skirmish Configuration states, transitions, payload, and viewports for this corpus version. It does not establish parity for gameplay, other menus, other resolutions, responsiveness outside the two approved viewports, performance, audio, platform integration, or internal architecture. A visual score is not evidence of behavioral equivalence, and a behavioral pass is not evidence of pixel identity. The corpus is one fixed observation of a live product and may be superseded only by a new version with new digests.
