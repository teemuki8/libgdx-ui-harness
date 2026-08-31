# Semantic metadata

A `Scene2dSession` extracts an immutable semantic snapshot from one application-owned Stage. Built-in adapters infer common Scene2D.UI widget semantics. Application metadata supplies stable user-facing meaning without custom Actor subclasses and overrides inferred role, name, text, label, test ID, or property values.

## Explicit metadata

Obtain the session-owned facade on the render thread:

`session.semantics()`, `session.adapters()`, and every Stage-reading session method (`snapshot`, `stateActionContract`, `typography`, `layout`, `completedFrame`) reject calls from any other thread with the typed `render-thread-violation` error; caller-thread work must be routed through `RenderThreadScheduler`.

```java
Scene2dSession session = new Scene2dSession(stage);
Semantics semantics = session.semantics();
```

The facade exposes these exact operations:

```java
semantics.setRole(actor, Role.BUTTON);
semantics.setAccessibleName(actor, "Save settings");
semantics.setText(actor, "Save");
semantics.setLabel(actor, "Profile action");
semantics.setTestId(actor, "profile-save");
semantics.setProperty(actor, "validation", "ready");
semantics.clear(actor);
```

Use an accessible name that describes the control to a user. Use visible text and labels as displayed, and reserve `testId` for a stable automation identifier when user-facing semantics are insufficient. Actor names and types are diagnostic fallbacks, not preferred contracts. Custom property keys and values are bounded strings; a node may have at most 256 properties. Never store secrets, tokens, filesystem paths, or unbounded application data in semantics because metadata can enter snapshots, errors, and traces.

Metadata is keyed by Actor identity using weak references, so it does not keep removed actors alive. `clear(actor)` removes explicit metadata for that Actor. Closing the session clears all metadata and rejects later access; it does not dispose the Stage.

## Custom actor adapters

For a reusable custom widget, register an inference adapter by Actor class:

```java
session.adapters().register(InventorySlot.class, (slot, target) -> target
    .role(Role.BUTTON)
    .accessibleName(slot.itemName())
    .selected(slot.isSelected())
    .property("quantity", Integer.toString(slot.quantity())));
```

`ActorSemanticAdapter.Target` permits role, accessible name, text, label, test ID, enabled, checked, selected, expanded, editable, focusable, and bounded custom properties. Registration replaces the adapter for that exact registered class. Resolution chooses the closest registered superclass. Explicit `Semantics` values still override inferred adapter values.

Adapters run while the Stage is traversed on its owning render thread. Keep them deterministic, side-effect free, bounded, and fast. They must not mutate Actors, schedule work, perform I/O, block, reflect over remote input, or retain the supplied Actor/target. The target validates contributions before publication and the resulting node is immutable.

## Snapshot contract

Each snapshot carries monotonically increasing revision and frame numbers. A node can contain parent/ordered-child IDs, role, accessible name, normalized text, label, test ID, actor name/type, supported widget state, focus, local/stage/screen bounds, effective alpha, clipping/viewport state, z-order, hit-test state, and bounded properties.

Node IDs are snapshot-local and may change after actor replacement. Store locators, not node IDs or Actor references. Locator preference is:

1. role plus accessible name;
2. associated label;
3. visible text;
4. explicit test ID;
5. actor name or type;
6. structural/property filters, with index last.

Strict actions require exactly one fresh match. Zero matches yield `not-found`; multiple matches yield `strictness-violation`; both carry bounded candidate summaries. Metadata should make those diagnostics more discriminating rather than force callers to choose an arbitrary index.

## Layout qualification metadata

Alignment and spacing cohorts are explicit metadata, not inferred style. Assign both properties to
each participating actor:

```java
semantics.setProperty(valueLabel, "layout-group", "hud-values");
semantics.setProperty(valueLabel, "layout-axis", "vertical");
```

`layout-group` must be nonblank. `layout-axis` must be exactly `horizontal` or `vertical`.
Participants qualify only when they are visible siblings with the same parent, group value, and
axis value. Horizontal cohorts compare vertical centers and x-axis gaps; vertical cohorts compare
horizontal centers and y-axis gaps. Alignment requires at least two qualifying actors; spacing
requires at least three. If an enabled group check has no qualifying cohort, validation emits an
error-severity `CHECK_UNAVAILABLE` rather than comparing unrelated siblings or reporting a pass.

Target-size qualification is role-aware: it applies only to actors exposed as `button`,
`checkbox`, `text-field`, `select`, or `slider`. `obscured` is opt-in and ignores
ancestor/descendant composition. These roles and properties describe diagnostic intent; the
harness does not generate component styles, spacing, or layout.

Intrinsic `clipped-text` and `text-collision` evidence is not semantic metadata and cannot be
supplied by setting properties. During `ui_validate_layout`, the Scene2D adapter reads each real,
visible `Label`'s public glyph layout, ink geometry, actor bounds, root viewport, and ancestor
`ScrollPane` actor areas on the owning render thread, then publishes immutable bounded evidence.
Callers must not read Actors or fonts from their own thread. A `Label` placement is accepted only
when its effective wrap/ellipsis state is publicly observable or both possibilities produce the
same exact origin. Otherwise intrinsic text evidence is hard unavailable: each requested
intrinsic check emits error-severity `CHECK_UNAVAILABLE`, and the normal error gate fails.

## Evaluator-complete state and action contracts

`SemanticSnapshot` remains the locator-oriented observation. Applications that need an
independent evaluator can additionally publish the versioned `state-action/v1` contract.
Domain facts are explicit because adapters cannot reliably infer stable IDs, defaults, option
meaning, validation rules, or conditional behavior:

```java
semantics.setControl(seedField, new ControlMetadata(
    "seed", 13, 13, ControlKind.TEXT, List.of(),
    ContractValue.text("generatedUint32"),
    new ValidationRule(
        "uint32-decimal", ContractValue.integer(0),
        ContractValue.integer(4_294_967_295L), ContractValue.integer(1)),
    new ValidationStatus(true, List.of())));
semantics.setViewport(formScrollPane, "configuration");
semantics.addCondition(new ConditionalRule(
    "victoryCondition", ContractValue.text("rival-target"),
    "rivalTargetCount", true, true, "victoryCondition"));
```

Built-in adapters supply current typed values for standard Scene2D.UI widgets. Use
`setCurrentValue` for a custom widget or when its domain value differs from displayed text.
After a real input-dispatch action and the resulting completed frame, attach its normalized
outcome with `setTransition`; rejected transitions must include a reason and cannot include an
accepted payload.

Capture through `Scene2dSession.stateActionContract(revision, frame)` only on the render thread.
The contract contains ordered controls and focus traversal, effective visibility, enabledness and
actionability, focus, validation, conditions, viewport scroll state, a content-derived state ID,
and the optional last transition. Unknown major versions, duplicate IDs, and broken references
fail closed with a JSON-path diagnostic. A newer minor version remains readable only while all
required V1 fields retain their meaning.
