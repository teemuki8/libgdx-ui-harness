# ADR 0011: Public benchmark evidence contract

- Status: Accepted
- Date: 2026-07-30

## Context

The Agentic Palisade evaluator previously described the state/action behavior it expected but
accepted candidate-specific aliases in `CandidateState.values()`. The harness treatment also
exposed its library-owned semantic snapshot without exposing the treatment-neutral state returned
by `CandidateUi.snapshotState()`. A candidate could therefore render and respond correctly while
remaining incompatible with the hidden evaluator, and a harness-assisted agent could not inspect
the exact evidence channel that determined compatibility.

The release gate must compare treatments using one observable contract. Visual similarity cannot
substitute for evaluator-complete semantic evidence, and the evaluator must not infer missing
defaults, focus order, validation, transitions, or structural measurements.

## Decision

Agentic Palisade candidates publish two bounded objects in `CandidateState.values()` on every
completed frame:

1. `stateAction` conforms to `state-action/v1.0` and contains the complete ordered control,
   typed-value, focus, condition, viewport, validation, and latest-transition evidence.
2. `structuralUsability` conforms to `structural-observation/v1` for approved capture states and
   contains measured bounds, ownership, typography, contrast, clipping, occlusion, and stable
   semantic/layout/region identities.

The public benchmark protocol defines every required field and threshold. The evaluator consumes
only these objects and fails closed:

- an absent or malformed contract is contract-incompatible;
- an absent scenario is scenario-unexecuted; and
- a compatible observation that disagrees with the corpus is an assertion failure.

The evaluator derives action traversal from the declared `focusOrder`, then replaces the initial
declared order with the focus IDs observed after real Scene2D TAB dispatch before evaluating it.
No candidate alias or source-specific translation is accepted.

The harness CLI reads `CandidateUi.snapshotState()` on the render thread and includes the raw
candidate contract as `candidateContract` in successful `ui_snapshot` responses with status
`present`. Presence does not claim schema validity; the independent evaluator remains the
compatibility authority. The separate library-owned `contract` remains available for harness
operations and screenshot comparison.

The trusted template permits up to 4,096 bounded state nodes so the complete public contract fits
without weakening the existing string, depth, key, or collection limits.

## Consequences

- Baseline and harness candidates are evaluated through the same public evidence channel.
- Candidate authors receive the exact schema needed to satisfy the evaluator.
- Harness-assisted agents can inspect the evidence that the evaluator will consume without
  bypassing render-thread ownership.
- Existing retained qualifications remain historical evidence but cannot qualify a release that
  uses this protocol revision.
- A future incompatible evidence meaning requires a new contract major version and an ADR.

## Verification

```bash
python3 benchmarks/agentic-palisade/scripts/test-treatment-symmetry.py
./gradlew -p benchmarks/agentic-palisade/template test --warning-mode=fail
./gradlew -p benchmarks/agentic-palisade/evaluator test --warning-mode=fail
./gradlew check javadoc --warning-mode=fail
```
