# Standard TextField geometry verification

Date: 2026-09-11. Local source qualification on Linux/X11 with Mesa software rendering.

The full isolated `clean check javadoc --continue --no-daemon --console=plain --warning-mode=fail`
gate passed in 2m 1s: 81 Gradle tasks, 1,064 passed tests, zero failures and one skipped
ACL-provider-specific test. The skipped `TraceRecorderTest.aclOwnerOnlyEnforcedWhereAclIsTheSecureView`
requires a different filesystem provider. Existing javac warnings in baseline fixtures remain;
this result is not a claim that javac ran with `-Werror` or that the repository is warning-free.
See [verification.json](verification.json) and [full-gate.log](full-gate.log).

The wrapper and command are recorded in [ADR 0042](../../adr/0042-standard-text-field-geometry.md).
The gate includes Scene2D geometry, production native/MCP fixtures, protocol/MCP contracts,
Checkstyle, published-license verification and source Javadoc. Public API/schema shapes and
existing Label geometry are unchanged. Independent source review found no blocking issue.

The retained red logs demonstrate the actual ascent error, invalid-metric exception, independent
cache-rounding error and valid nullable-locator rejection before their respective fixes. The
final Scene2D tests compare actual draw-cache glyph vertices across 144 font/focus/alignment/cursor
states, fractional parent translations and independent cache snapping. They prove capture does
not draw or mutate the font cache, markup flag, cursor, selection or scroll.

Both native images were inspected at their original 1280×720 resolution:

- [Fitted input](field-0.png), [MCP result](field-0.json): the existing zero-padding reference
  field reports one pixel of left glyph overhang.
- [Scrolled input](field-1.png), [MCP result](field-1.json): its visible slice reports one pixel
  of left and right glyph overhang.

These are accurate `CLIPPED_TEXT` findings instead of `CHECK_UNAVAILABLE`. The fixture's existing
visual style is preserved; these screenshots are not a visual-approval or whole-screen PASS claim.
The separate padded-field fixtures prove passing fitted/scrolled checks, and reducing field height
still detects clipping. Native Home/End gestures also exercise production cursor movement.
Unsupported Lists continue to produce unavailable intrinsic evidence.

No artifacts were published and no release or other platform qualification is implied.
