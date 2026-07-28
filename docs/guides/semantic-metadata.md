# Semantic metadata

A `Scene2dSession` extracts an immutable semantic snapshot from one application-owned Stage. Built-in adapters infer common Scene2D.UI widget semantics. Application metadata supplies stable user-facing meaning without custom Actor subclasses and overrides inferred role, name, text, label, test ID, or property values.

## Explicit metadata

Obtain the session-owned facade on the render thread:

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
