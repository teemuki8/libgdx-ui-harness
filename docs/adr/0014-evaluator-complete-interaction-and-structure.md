# ADR 0014: Evaluator-complete interaction and structural semantics

- Status: Accepted
- Date: 2026-07-30

## Context

The public Agentic Palisade protocol left three evaluator inputs ambiguous:

- structural controls used kind-like roles (`select`, `text`, and `number`) while `stateAction`
  and candidate observations used accessible roles (`combobox`, `textbox`, and `spinbutton`);
- optional label IDs were evaluated as mandatory reciprocal IDs even for self-labelled buttons
  and checkboxes; and
- the protocol did not distinguish a semantically hidden control from a visible control placed
  outside the viewport by scrolling.

The evaluator's keyboard traversal and editing sequence was also not public. Candidates could
therefore publish schema-compatible state while implementing a different initial-focus or input
model from the one exercised by the evaluator. Exact panel and target-control geometry was
evaluator-owned but undisclosed even though it was a mandatory structural predicate.

The transport initially requested the semantic tree and evaluator-complete contract as two
independent render-thread commands. A render-loop drain beginning between those submissions could
place the reads on adjacent frames, causing a valid `ui_snapshot` request to fail the strict
revision and frame identity check nondeterministically.

## Decision

Both public evidence channels use the closed accessible-role vocabulary `button`, `checkbox`,
`slider`, `combobox`, `textbox`, and `spinbutton`.

Buttons and checkboxes may be intrinsically labelled by omitting both label IDs. Other roles
require an external `labelControlId` and a reciprocal `labelledControlId` equal to the control's
own ID. A partial or non-reciprocal association fails.

Structural observations contain controls whose matching `stateAction.visible` value is true.
They omit semantically hidden controls, but retain visible controls scrolled outside the viewport
and report their measured, potentially negative coordinates.

The protocol specifies the fresh unfocused scenario start, TAB traversal derived from
`focusOrder`, select interaction, Ctrl+A seed replacement, typed values, action activation,
conditional traversal, and Escape dismissal used by the evaluator. All actions continue to use
real Scene2D input dispatch.

The protocol also publishes the exact panel, target-control, and visible target bounds required
for each frozen reference identity.

`ContractProvider.snapshotWith` represents one correlated semantic snapshot and state/action
contract. Render-thread adapters override it with one scheduled read and one revision/frame pair.
The default implementation preserves compatibility for providers whose evidence source is already
atomic. `SnapshotEvidence` rejects mixed revision or frame identities at construction.

## Consequences

- Role and label evidence have one deterministic meaning across the public contract and evaluator.
- Hidden conditional controls no longer create impossible focusability and hit-target failures.
- Candidate authors can reproduce every evaluator input sequence without access to evaluator
  internals.
- Exact structural geometry failures distinguish candidate divergence from missing evaluator
  information.
- Application-owned render loops cannot split one correlated snapshot across completed frames.
- Strict mixed-frame rejection remains in force instead of being relaxed to hide scheduling races.
- Existing retained qualifications remain diagnostic evidence and cannot qualify a release that
  uses the corrected contract.

## Verification

```bash
./gradlew -p benchmarks/agentic-palisade/evaluator test --warning-mode=fail
python3 benchmarks/agentic-palisade/scripts/test-treatment-symmetry.py
./gradlew :harness-protocol:test --tests \
  dev.gdx.uiharness.protocol.HarnessProtocolServiceTest.combinedSnapshotProviderKeepsSemanticAndContractEvidenceAtomic
./gradlew check javadoc --warning-mode=fail
```
