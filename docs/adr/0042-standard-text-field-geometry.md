# ADR 0042 — Read-only standard TextField geometry

Date: 2026-09-11

Status: Accepted in source; not yet a published release.

## Context

ADR 0039 correctly rejects visible semantic text without exact intrinsic geometry. That makes
`clipped-text` and `text-collision` unavailable for ordinary editable fields even when their
rendering is otherwise supported. A Label's geometry cannot establish a TextField's geometry:
the field selects padding by focus/disabled state, scrolls to its cursor and renders only a slice.
Calling `TextField.draw` during capture would also change focus, blinking and scroll state.

## Decision

`Scene2dTextFieldGeometry` supports the exact `TextField` and `BitmapFont` classes on the exact
libGDX runtime version **1.14.2**. A fixed adapter-owned lookup reads five fields:
`displayText`, `glyphPositions`, `fontOffset`, `textHeight`, and `renderOffset`.
No class, field, method, lookup handle or reflection selector is accepted from callers or added to
protocol/MCP data. Only immutable bounds cross the Scene2D boundary.

The render-thread capture copies at most 4,096 display characters and 4,097 glyph positions,
then calculates the next draw's visible range without changing the Actor. Literal bracket
escaping reproduces field text with a markup-enabled font without toggling the shared font's
markup flag. Glyph geometry includes ascent, the font cache's integer-position setting and
fractional translations from non-transforming parent Groups before snapping. Ancestor traversal
is limited to 128; invalid numeric metrics fail closed. Existing Label extraction is unchanged.

The implementation follows the reviewed [libGDX 1.14.2 TextField source](https://github.com/libgdx/libgdx/blob/1.14.2/gdx/src/com/badlogic/gdx/scenes/scene2d/ui/TextField.java)
and `BitmapFontCache` from the verified `gdx-1.14.2-sources.jar`. These are Apache-2.0 sources.
Its copied scroll arithmetic is isolated from UI mutation and checked against actual draw output.

The runtime version check precedes lookup, so a future libGDX version with similarly named fields
is not silently accepted. [`MethodHandles.privateLookupIn`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/invoke/MethodHandles.html#privateLookupIn(java.lang.Class,java.lang.invoke.MethodHandles.Lookup))
can deny access across a closed named-module boundary. Lookup failure leaves field evidence
unavailable; there is no public-access approximation, automatic module opening or draw fallback.
A classpath deployment on the supported version needs no extra module-opening flags.

## Supported boundary and consequences

Standard fields support fitted or horizontally scrolled text, left/center/right alignment,
focused/disabled background padding, cursor changes, flipped fonts and positive bitmap scales.
Only the visible text slice is qualified: the intentionally hidden horizontal remainder is not a
clipping defect. Layout and glyph ink still participate in actor, viewport and ancestor clip checks.

TextArea, custom TextField/BitmapFont subclasses, nonidentity field rotation/scale, incompatible
non-transforming parent transforms, unsupported runtime versions, inaccessible internals,
invalid metrics and oversized text have no exact field evidence. Visible nonempty semantic text
then receives the existing node-located `CHECK_UNAVAILABLE` findings. No result schema changes.
Empty fields' message/placeholder text is outside the semantic text coverage contract; it is not
newly qualified. A semantic text override that differs from the actual field value is likewise
not accepted as evidence of the rendered text.

This bounded private-access exception belongs solely to the standard field geometry adapter.
The Label adapter continues to use public state. It does not authorize arbitrary reflection or
change render-thread ownership, input dispatch, protocol permissions or application lifecycle.

## Verification

`Scene2dTextFieldGeometryTest` compares copied ink bounds with the vertices left by real
`TextField.draw`/`BitmapFontCache` execution across 144 font/focus/alignment/cursor combinations,
plus independent cache snapping and nested fractional parent translations. It also proves capture
preserves the application's font cache/color/markup, cursor, selection, scroll, focus and blink
flags and does not draw backgrounds. Unknown widgets, transforms, invalid metrics and oversized
text are explicitly unavailable.

`Scene2dLayoutValidatorTest` types through production `Stage.keyTyped`, checks fitted/scrolled
fields and detects a vertically clipped field. `LayoutValidationProductionFixtureTest` fills the
native reference field through `ui_action`, validates both intrinsic checks through
`ui_validate_layout`, moves the cursor through real Home/End input gestures, and retains fitted
and scrolled screenshots. The same whole-stage request still reports unavailable List geometry.

This native subtree check also exposed an existing MCP diagnostics bug: a nullable locator
union was treated as a direct `kind` union and rejected valid locators before dispatch.
`SchemaDiagnostics` now unwraps exactly one null/nonnull pair and validates the selected
branch through the existing bounded path. `SchemaDiagnosticsTest` checks valid subtree and
explicit-null requests, unknown locator kinds/properties, and nullable primitive type/range
failures. No schema or protocol capability is expanded.

Run the affected gates from the harness root with the workspace isolation wrapper:

```bash
/home/tjaaskel/git/libgdx-agent-bootstrap/scripts/isolated-run.sh \
  env -u WAYLAND_DISPLAY XDG_SESSION_TYPE=x11 LIBGL_ALWAYS_SOFTWARE=1 \
  __GLX_VENDOR_LIBRARY_NAME=mesa xvfb-run -a ./gradlew \
  :harness-scene2d:test --tests '*Scene2dTextFieldGeometryTest' \
  --tests '*Scene2dLayoutValidatorTest' \
  :harness-fixtures:test --tests '*LayoutValidationProductionFixtureTest' \
  --no-daemon --console=plain --warning-mode=fail
```

The complete source gate is the same isolated invocation with
`clean check javadoc --continue --no-daemon --console=plain --warning-mode=fail`.
Native artifacts are written to `harness-fixtures/build/text-field-evidence/`.
The [retained local verification report](../evidence/text-field-geometry/README.md) records
the full gate, observed native reference clipping and original-resolution screenshots.
