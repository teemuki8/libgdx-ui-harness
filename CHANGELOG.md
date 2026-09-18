# Changelog

## Unreleased

## 2.2.0 - 2026-09-19

### Added

- Added `Lwjgl3FramebufferCapture`: session-free bounded capture for tools and tests that render
  without a harness session - the whole back buffer or a bottom-left-origin region, encoded by the
  same encoder the session capture uses, carrying the image SHA-256 and enforcing pixel and byte
  ceilings before allocation.
- Added `RecordingTraceController`: bounded per-completed-frame observation recording for
  applications that own their render loop, publishing one verified archive on stop and answering
  transition queries from the retained observations.

### Fixed

- `ui_trace_query` accepts a transition whose cause is not observable. The projector reports an
  unknown cause by omitting it, while the output schema required `causeSequence`, so a real trace
  of adjacent frame observations failed output validation.

### Changed

- `Lwjgl3ScreenCapture` reads regions through a shared `BackBufferReadback`; captured bytes and
  hashes are unchanged.

### Added

- Added `Lwjgl3FramebufferCapture`, a session-free bounded capture for tools and tests that render
  without a harness session: the whole back buffer or a bottom-left-origin region, encoded as a
  conventional top-left PNG by the same encoder the session capture uses, carrying the image's
  SHA-256 and enforcing pixel and byte ceilings before allocation.

### Fixed

- `ui_trace_query` accepts a transition whose cause is not observable. The projector reports an
  unknown cause by omitting it, while the output schema required `causeSequence`, so a real trace
  of adjacent frame observations failed output validation with
  `required property \'causeSequence\' not found`.

### Changed

- `Lwjgl3ScreenCapture` reads regions through a shared `BackBufferReadback`; captured bytes and
  hashes are unchanged and the existing capture tests cover the extraction.

## 2.1.1 - 2026-09-12

### Fixed

- Derive intrinsic geometry for stock libGDX 1.14.2 TextField controls through five fixed,
  validated private-field accessors and public font metrics, without drawing or mutation.
  Unsupported versions, inaccessible fields and custom geometry remain unavailable.
- Preserve an empty `observedState` in successful action responses after clearing a field.
  The response schema accepts an empty observation while other bounded nonblank fields retain
  their validation. Real markup/runtime MCP fixtures cover clearing and recovery.

## 2.0.0 - 2026-08-31

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
