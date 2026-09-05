# ADR 0039 — Complete layout coverage before PASS

Status: accepted, unreleased source change. Date: 2026-09-05.

## Context

A node-limited layout run with a retained warning could report `PASS` while later nodes were
unexamined. A finding-limited run containing only warnings could also pass despite truncation.
Separately, the intrinsic capture's global availability flag did not prove per-widget coverage:
the validator skipped visible text nodes missing geometry. Real TextFields, SelectBoxes, Lists,
and custom semantic text therefore had no located coverage diagnostic.

TextButton and CheckBox deliberately expose their text on the parent for semantic locator
clarity; their real child Label suppresses duplicate semantic text. Treating any same-text child
as proof would infer rendering ownership and could hide unsupported custom painting.

## Decision

1. Preserve observed severity failure first. Otherwise every node or finding truncation produces
   `INCOMPLETE`; only a complete evaluation below the severity gate can return `PASS`.
2. When intrinsic capture is globally available, every visible nonempty semantic text node must
   have exact backend evidence for each requested intrinsic check. Missing evidence emits
   `CHECK_UNAVAILABLE` with `ERROR` severity at the node's identity and stage bounds. Global
   capture failure retains the existing root-located unavailable diagnostic.
3. Read the actual TextButton/CheckBox-owned Label on the render thread and attribute its copied
   geometry to the parent only when the parent's semantic text equals the Label text. Keep the
   child evidence too, so its own bounds are checked despite suppressed semantic text. Ancestral
   pairs are excluded from text collision as before. No arbitrary descendant-text inference is
   used. A collision with another actor may name either the label or its semantic owner.
4. Keep existing result fields, statuses, reason enums, bounds, and bounded evidence maps.
   Finding overflow still retains the highest observed severity even when the corresponding
   finding could not fit. No Actor crosses the adapter boundary and no draw or input side effect
   is introduced by validation.

## Compatibility and limits

The public Java and MCP result schemas are unchanged. Qualification outcomes deliberately become
stricter; 2.0.0 does not contain this hardening. Empty or hidden semantic text does not require
geometry. Unsupported text that is neither exposed semantically nor captured by a supported
backend adapter remains outside this contract. This change does not implement exact text-field,
list, select, or arbitrary custom renderer geometry, and does not certify aesthetic quality.

## Verification

`LayoutValidatorTest` covers node truncation after a warning, warning finding overflow, and known
failure precedence. `Scene2dLayoutValidatorTest` covers visible unsupported widgets, located
per-check evidence, child-Label ownership, empty/hidden text, and divergent parent semantics.
`LayoutValidationProductionFixtureTest` fills a real text field through production input and
asserts a located unavailable result over the real LWJGL3 process's MCP transport.

Run `xvfb-run -a ./gradlew :harness-core:test :harness-scene2d:test :harness-protocol:test
:harness-mcp:test :harness-fixtures:test --console=plain`, then the repository `check` gate.
