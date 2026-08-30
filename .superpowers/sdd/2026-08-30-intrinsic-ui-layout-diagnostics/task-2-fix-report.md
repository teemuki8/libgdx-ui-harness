# Task 2 Fix Report: Zero-Area Intrinsic Ink

## Fix

- Added `zeroAreaTextInkDoesNotCollide`, a behavioral regression with zero-width ink strictly inside another visible text ink rectangle.
- Updated the shared rectangle overlap predicate to require positive width and height for both rectangles before testing their extents.
- Kept edge-touch, document-order, ancestry, related-actor, clipping, and evidence-availability behavior unchanged.

## TDD Evidence

### RED

Command:

```text
./gradlew :harness-core:test --tests '*LayoutValidatorTest.zeroAreaTextInkDoesNotCollide' --warning-mode=fail
```

Result before the production fix: `BUILD FAILED`. The focused test failed at the expected PASS-status assertion because the validator emitted the false hard `TEXT_COLLISION` finding.

### GREEN — Focused Regression

The same focused command completed with `BUILD SUCCESSFUL` after the production fix.

### GREEN — Full Validator Test Class

Command:

```text
./gradlew :harness-core:test --tests '*LayoutValidatorTest*' --warning-mode=fail
```

Result: `BUILD SUCCESSFUL`. Gradle's XML result reports 12 tests, 0 failures, 0 errors, and 0 skipped.

## Self-review

- The regression uses valid `Bounds`: the text actor remains positive-sized while its intrinsic ink has zero width and is positioned strictly inside the other ink rectangle.
- The fix is confined to the existing overlap predicate and uses the `Bounds` non-negative dimension invariant; no API, evidence format, severity, or finding-order changes were introduced.
- Requiring both dimensions to be positive correctly excludes both zero-width and zero-height rectangles from every geometric overlap check.
- The complete `LayoutValidatorTest` class remains green, covering all accepted Task 2 behavior.
