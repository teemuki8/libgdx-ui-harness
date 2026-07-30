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

## Public candidate evidence contract

`CandidateUi.snapshotState()` is the only treatment-neutral state evidence channel. Its
`CandidateState.values()` map must contain a `stateAction` member conforming to
`state-action/v1.0` on every completed frame. The evaluator consumes this public member
directly and does not accept candidate-specific aliases, infer omitted defaults, or translate
another nesting shape. In the harness treatment, `ui_snapshot` exposes the same member as
`candidateContract`; the library-owned semantic `contract` remains a separate result member.

The `stateAction` object has these required members:

- `schemaVersion`: exactly `state-action/v1.0`;
- `stateId`: a stable identity for the complete observable state;
- `revision` and `frame`: non-negative integers;
- `controls`: all 19 controls in the normative corpus order, including the conditionally hidden
  control;
- `focusOrder`: the currently visible focusable control IDs in actual TAB order;
- `conditions`: the corpus-declared rival-target relationship; and
- `viewports`: the configuration viewport, its dimensions, scroll values and currently visible
  control IDs.

`focusedControlId` is present only while a declared control owns keyboard focus. `transition`
is present after an observable action outcome and remains bound to the resulting state until the
next action outcome.

Every control object has exactly these members: `id`, `role`, `kind`, `accessibleName`,
`options`, `defaultValue`, `currentValue`, `visible`, `enabled`, `actionable`, `focusable`,
`focused`, `validationRule`, and `validationStatus`. Control IDs, option order, labels, default
values, validation constraints and current values come from `corpus/spec.json` and the live UI.
The closed `kind` values are `button`, `checkbox`, `number`, `range`, `select`, and `text`.
Use the matching accessible role name, such as `button`, `checkbox`, `slider`, `combobox`, or
`textbox`.

All domain values are typed objects:

```json
{"type":"null"}
{"type":"boolean","booleanValue":true}
{"type":"integer","integerValue":305419896}
{"type":"decimal","decimalValue":"1.0"}
{"type":"text","textValue":"conquest"}
```

An option is `{"value":<typed-value>,"label":"<public label>"}`. A validation rule contains
`format` and, when applicable, typed `minimum`, `maximum`, and `step`. A validation status is
`{"valid":true,"messages":[]}` or contains bounded user-visible messages when invalid.

The rival-target condition is represented as:

```json
{
  "controllerId":"victoryCondition",
  "equalsValue":{"type":"text","textValue":"rival-target"},
  "dependentId":"rivalTargetCount",
  "visibleWhenEqual":true,
  "actionableWhenEqual":true,
  "restoreFocusTo":"victoryCondition"
}
```

The viewport object has exactly `id`, `width`, `height`, `scrollX`, `scrollY`, `maxScrollX`,
`maxScrollY`, and `visibleControlIds`. At the bottom observation, `scrollY` equals
`maxScrollY`; at the initial observation it is zero.

A transition object has `actionId`, `accepted`, optional `rejectionReason`,
`resultingStateId`, `resultingRevision`, `validation`, `kind`, optional `clipboardText`, and
`acceptedPayload`. The closed kinds are `none`, `dismissed`, and `confirmation`. Values inside
`acceptedPayload` use the same typed representation. COPY SEED reports `clipboardText`.
RANDOM SEED reports typed `previousSeed` and `seed` payload entries. CANCEL and Escape use
`dismissed` with an empty payload. START BATTLE uses `confirmation` and the normalized corpus
payload. An invalid START BATTLE transition is rejected, includes a reason, and has no accepted
payload.

Missing, mistyped, duplicated, reordered, or unknown-major fields make the scenario
contract-incompatible. Omitting a scenario makes it scenario-unexecuted. A schema-compatible
observation that disagrees with the corpus is an assertion failure.

`CandidateState.values()` must also contain `structuralUsability` for every approved capture
state. This is the public `structural-observation/v1` object used by the independent structural
channel. It contains:

- `schemaVersion` with the exact value `structural-observation/v1`;
- non-negative `semanticRevision` and `layoutRevision`;
- `frameEdgeClipped` and the current `scrollY`;
- lowercase SHA-256 identities `semanticSha256`, `layoutSha256`, and `regionSha256` derived
  deterministically from the candidate's current semantic, layout, and visible-region
  observations;
- `panelBounds` in top-left framebuffer pixels; and
- bounded `controls` attributed by the stable corpus control IDs.

Each rectangle is `{"x":0,"y":0,"width":0,"height":0}` in top-left framebuffer pixels.
Each structural control contains exactly `controlId`, `role`, optional `labelControlId`,
optional `labelledControlId`, `enabled`, `focusable`, `hitBounds`, `visualBounds`, `occluded`,
`fontPixels`, `rasterResidual`, `contrastRatio`, `glyphClipped`, `hierarchyRole`,
`parentControlId`, optional `scrollOwnerId`, optional `clipOwnerId`, and `visibleBounds`.
Report actual completed-frame measurements, not desired values. Stable unchanged captures must
retain the same semantic, layout, and region hashes and revisions. The structural release
thresholds are at least 12 font pixels, at most 0.5 raster residual, at least 4.5 contrast,
at least 34 × 34 hit bounds, no glyph or frame-edge clipping, no occlusion, correct label/control
association, form-row ownership under `form`, and scroll/clip ownership under `scroll`.

## Outcomes

Two outcome families are reported separately:

1. **Behavioral conformance:** schema-valid public controls and states; labels, option values, defaults, ordering, focus behavior, validation boundaries, conditional visibility, deterministic seed actions, dismissal behavior, and accepted payload.
2. **Visual parity:** comparison of implementation captures against each approved reference at its exact state and viewport.

A run is conforming only when all mandatory behavioral checks pass. Visual measurements supplement that gate and must not be used to excuse a behavioral mismatch. Results must identify the corpus schema version and reference digests used.

## Experimental precommitment

Before any treatment work begins, precommit exactly three matched pairs (six runs total). Every run in all three pairs uses the identical model and reasoning setting `openai-codex/gpt-5.6-sol:medium`, an identical 45-minute maximum, and exactly three refinement rounds.

Within each matched pair, use the same corpus version, task starting point, execution environment, and evaluation procedure. Harness availability and its accompanying instructions are the only treatment difference: one run receives the libGDX UI harness and instructions for using it, while its matched run receives neither. Prompts, tools, inputs, time limits, refinement opportunities, evaluator exposure, and human intervention must otherwise remain identical.

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
