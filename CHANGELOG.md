# Changelog

## Unreleased

### Added

- Added signed `x`/`y` layout-finding positions while retaining non-negative width and height, plus
  intrinsic Scene2D Label overflow (`CLIPPED_TEXT`) and visible glyph-ink collision
  (`TEXT_COLLISION`) diagnostics over real actor, viewport, and ancestor `ScrollPane` geometry.
- Added the closed `text-collision` request value and `TEXT_COLLISION` result reason without
  changing the result shape, protocol version, or dependency versions.

### Changed

- Requested checks with unavailable navigation, explicit cohort, clip, or exact intrinsic text
  evidence now emit error-severity `CHECK_UNAVAILABLE` and fail the normal error qualification
  gate instead of passing. Ambiguous public libGDX Label wrap/ellipsis placement is exact or hard
  unavailable; it is never inferred through reflection or draw capture.
- Reduced structural noise with ancestor-aware opt-in obscuration, canonical-role target-size
  checks, and visible same-parent alignment/spacing cohorts that require a shared nonblank
  `layout-group` and exact `layout-axis=horizontal|vertical` metadata.
- Kept render-thread Actor/font ownership and bounded immutable evidence unchanged. The harness
  remains style-neutral: it diagnoses observable invariants but does not generate styles or own
  subjective visual approval. Screenshot metadata and hashes prove artifact integrity only.
