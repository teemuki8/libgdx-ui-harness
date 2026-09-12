# Agent tools and safe operation

The MCP server exposes exactly twenty-seven bounded tools. `tools/list` is the authority; unknown tools and unknown input fields are rejected. Except for `ui_sessions`, every tool requires `sessionId`. `deadlineMillis` is optional, defaults to 30,000 ms, and when supplied must be 1 through 120,000 ms; `ui_assert`, `ui_keyboard_gesture`, and `ui_input_gesture` require it up to 120,000 ms, while `ui_scenario_start` requires it up to 600,000 ms. Deadlines include adapter work and backend queue time. The server's outer request timeout is 630,000 ms (the scenario maximum plus a 30-second translation allowance), so a full scenario deadline is never aborted by the SDK transport timeout; the per-request deadline remains the authoritative bound.

`sessionId` is the single envelope field documented by this preamble and omitted from the per-tool rows; the per-tool rows name every other required input and any optional tool-specific input. Each row is `none` or a comma-separated list of `required`/`optional` field tokens, and a schema-parity test fails when a required input appears on either side without the other.

| Tool | Purpose | Tool-specific input | Result |
|---|---|---|---|
| `ui_sessions` | List active sessions | none | bounded session IDs and capability names |
| `ui_artifact_read` | Read one verified bounded region of a session-owned opaque receipt | required `reference`, required `offset`, required `maxBytes` | immutable receipt metadata, offsets, EOF, and base64 data |
| `ui_snapshot` | Capture a compact semantic snapshot | none | revision, frame, root ID, node count, optional `state-action/v1` identity/contract and full-snapshot artifact |
| `ui_query` | Evaluate a lazy locator | required `locator` | match count, bounded node summaries/evidence, optional artifact |
| `ui_action` | Perform one allowlisted action | required `action`, required `locator` | before/after revisions, observed state, evidence, optional artifact |
| `ui_input_gesture` | Run one atomic keyboard and relative mouse timeline through production input | required `schemaVersion`, required `steps`, required `deadlineMillis` | terminal input, tick, failure, and cleanup evidence |
| `ui_keyboard_gesture` | Run one atomic keyboard timeline through real input dispatch | required `schemaVersion`, required `steps`, required `deadlineMillis` | terminal step, tick, held-key, failure, and cleanup evidence |
| `ui_assert` | Assert a semantic condition on a resolved locator with typed outcome | required `schemaVersion`, required `locator`, required `assertion`, required `deadlineMillis` | assertion outcome and evidence |
| `ui_wait` | Wait on semantics | required `condition`, required `locator` | final revision/frame, matches/evidence, optional artifact |
| `ui_screenshot` | Capture completed-frame PNG evidence | optional `locator`, required `maxWidth`, required `maxHeight`, required `maxPixels`, required `maxPngBytes` | opaque artifact receipt plus frame/revision/dimensions/scales |
| `ui_inspect_compare` | Inspect, capture, and compare one current full frame | required `referenceId`, required `policyId`, required `policyVersion`, required `viewportId`, required `maxIterations`, required `maxDurationMillis`, required `maxWidth`, required `maxHeight`, required `maxPixels`, required `maxPngBytes` | explicit convergence status, bounded semantic/spatial differences, current PNG and heatmap artifacts, and full immutable evidence artifact |
| `ui_typography_diagnose` | Capture and diagnose visible registered text controls | required `referenceId`, required `viewportId`, required `maxDurationMillis`, required `maxResults`, required `maxWidth`, required `maxHeight`, required `maxPixels`, required `maxPngBytes` | actor-attributed typography status and reports, current PNG artifact, and immutable diagnostic evidence artifact |
| `ui_layout_diagnose` | Capture and diagnose selected controls after layout quiescence | required `referenceId`, required `viewportId`, required `maxDurationMillis`, required `maxResults`, required `maxWidth`, required `maxHeight`, required `maxPixels`, required `maxPngBytes` | actor-attributed layout status and summaries, quiescence proof, current PNG artifact, and immutable full evidence artifact |
| `ui_trace_start` | Start bounded trace collection | required `maxDurationMillis`, required `maxBytes` | trace ID |
| `ui_trace_stop` | Stop and finalize the active trace | none | trace ID/reference, event count, bytes |
| `ui_scenarios` | List registered bounded scenarios | none | bounded scenario list |
| `ui_scenario_start` | Start one bounded scenario; one active lease per session | required `scenarioId`, required `seed`, required `configuration`, required `profileId`, required `deadlineMillis` | scenario start outcome |
| `ui_navigation_inspect` | Run a bounded navigation path through real input dispatch | required `spec` | bounded navigation path with observed focus steps |
| `ui_navigation_validate` | Validate a navigation path without executing it | required `spec` | validation result |
| `ui_validate_layout` | Validate whole-stage or subtree layout invariants from one completed frame | required `spec` | status and bounded findings |
| `ui_matrix_run` | Run one scenario/assertion set across a bounded display matrix | required `spec` | run ID |
| `ui_matrix_results` | Retrieve one retained matrix run report | required `runId` | bounded report |
| `ui_runtime_compare` | Compare a bound node's displayed value against its runtime observation | required `maxDurationMillis`, required `locator` | typed comparison with correlation |
| `ui_runtime_observe` | Observe one explicit registered runtime entity property on a correlated completed frame | required `entityId`, required `propertyId`, required `correlationToken`, required `maxDurationMillis` | typed `AVAILABLE` or `UNAVAILABLE` observation |
| `ui_trace_query` | Query compact state transitions from a retained trace | required `spec` | bounded transitions |
| `ui_semantic_compare` | Compare a registered semantic baseline against the current snapshot | required `spec` | matched status and bounded differences |
| `ui_capabilities` | Discover one session's supported operations | none | bounded capability names, exact operation schemas/examples, diagnostic registry, and recovery policy |

## Locator and action inputs

Locator schemas are closed recursive unions. Supported locator kinds are role, text/label, test ID, actor name/type, relation, filter, and index. Text match modes are exact, case-insensitive exact, substring, and regex. Regex mode compiles with the linear-time RE2/J engine: supported syntax includes literals, character classes and escapes, Unicode classes, groups, alternation, anchors, and greedy/lazy quantifiers; backreferences, lookahead/lookbehind, and atomic or possessive groups are rejected at construction as `invalid-request` rather than evaluated with backtracking. Relations are child, descendant, parent, and sibling. Filters support accessible name, `has`, `hasText`, and semantic state. Indexes are zero-based and intentionally reported as structurally fragile. Prefer `role` plus an accessible-name filter; never treat snapshot-local node IDs as durable handles.

`ui_action` accepts only click, hover, focus, fill, press, scroll, drag, and pointer. Pointer phases are down, move, and up. An action may request `force`, but force never bypasses strict locator resolution, render-thread confinement, request bounds, or input dispatch through the application's configured processor.

`fill` accepts an empty `value` to clear a text field through real input. Its successful action
result keeps `observedState` as an explicit string, including `""` for an empty field. Observed
state is nonnull and bounded to 16,384 UTF-16 code units in the protocol; empty or whitespace
text is not an internal failure. Revisions still prove a completed post-action frame.

## Keyboard gestures

Capability `ui_keyboard_gesture` enables one atomic, session-scoped keyboard timeline. Capability
`ui_keyboard_gesture_v2` additionally reports the 256-step schema-version-2 bound. Capability
`ui_keyboard_gesture_ticks` separately reports that the application installed an exact
controlled-tick coordinator. Capability registration does not prove that the controller is
currently paused or within its provider limits; every gesture containing `wait-ticks` preflights
that state before the first key callback. A request with any structural or tick-preflight failure
dispatches no input.

Gestures have no locator. If input depends on widget focus, first complete a strict `ui_action`
whose action kind is `focus`, then start the gesture. `key-down` and `key-up` call only the named
methods on the application's configured libGDX `InputProcessor`; unlike the existing `press`
action, they never synthesize `keyTyped`, repeat events, or global `Gdx.input` state.

This frame example keeps libGDX keycode 29 held while the application publishes three later
completed UI frames:

```json
{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"ui_keyboard_gesture","arguments":{"sessionId":"game","schemaVersion":1,"steps":[{"kind":"key-down","keycode":29},{"kind":"wait-frames","count":3},{"kind":"key-up","keycode":29}],"deadlineMillis":5000}}}
```

This tick example keeps the same key held while the application-owned controller advances exactly
three configured ticks:

```json
{"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"ui_keyboard_gesture","arguments":{"sessionId":"game","schemaVersion":1,"steps":[{"kind":"key-down","keycode":29},{"kind":"wait-ticks","count":3},{"kind":"key-up","keycode":29}],"deadlineMillis":5000}}}
```

`wait-frames(N)` observes exactly `N` distinct completed frame publications after the preceding
transition; it never advances the render loop. `wait-ticks(N)` advances exactly `N` controlled
simulation ticks without implicitly pausing or resuming the application and without approximating
ticks from frames or wall time. The result's `configuredDeltaNanos` is the integration's explicit
configured request evidence. Agent-runtime 1.0.0 does not independently acknowledge that delta.
Successful tick evidence retains one unchanged execution epoch, exact start/final tick counts,
first/final runtime frames, and UI-frame identities only when both endpoint correlations are
proven.

Schema version 1 remains unchanged at 2 through 64 steps. Schema version 2 accepts 2 through 256
steps under the same `ui_keyboard_gesture` tool name; it does not create a cross-request
transaction. Both versions accept keycodes 0 through 255 and at most 16 simultaneously held keys.
Each frame or tick wait is 1 through 10,000; cumulative frame waits and cumulative tick waits are
independently capped at 10,000. Every wait requires at least one held key, a key cannot be pressed
twice or released before it is held, and the complete sequence must release every owned key. The
required MCP deadline is 1 through 120,000 ms. An installed tick provider may impose a lower tick
ceiling. A complete request is preflighted before dispatch and retains one gesture/coordinator
lease through every step and any abnormal reverse-order cleanup.

Only `completed` is an MCP success. Rejected, failed, timed-out, cancelled, and session-closed
outcomes remain structured `keyboard-gesture-result` content marked as an MCP error. They retain
the failure step/category, completed step evidence, held-key set, cleanup status, and bounded
cleanup attempts. On abnormal termination, the runner releases remaining keys in reverse press
order through the same input processor and render scheduler under a fresh 1,000 ms cleanup
deadline. The per-session mutation lane and gesture lease remain held until cleanup terminates, so
a cancelled transport call cannot overlap a later mutation or silently abandon a pressed key.

## Hard bounds

The transport reads newline-delimited frames with a strict UTF-8 decoder and rejects a request above 1,048,576 bytes before any JSON token is parsed. An oversized or malformed-UTF-8 frame that ends at a newline yields one JSON-RPC parse error (`-32700`, `id: null`) and the connection continues; rejected frame content is never echoed. An in-limit frame left unterminated at end of input yields one parse error, after which the server terminates normally. A response above 16,777,216 encoded bytes is rejected. Ordinary strings are at most 16,384 UTF-16 code units, identifiers are at most 256 characters, JSON nesting is at most 64, and numeric tokens are at most 128 characters, and the same constraints are enforced on every stdio message before dispatch. Locator schemas limit recursive locator depth to 32 and decoded locator nodes to 4,096. Regular-expression syntax is compiled during decode with the linear-time RE2/J engine, so a malformed or unsupported pattern (backreferences, lookahead/lookbehind, atomic or possessive groups) is an `invalid-request`, not an internal routing error.

Admission is bounded before dispatch: at most 8 concurrent admitted requests globally and 4 per session (including queued mutations), with at most 16 queued mutations per session. Read-only requests start immediately and may overlap; per-session mutations run strictly in submission order and never overlap. Requests without a `sessionId` use a distinct admission scope that no client session name can share. Excess requests fail immediately with the `limit-exceeded` diagnostic and never reach the harness.

Core semantic defaults are 10,000 nodes, depth 128, 1,000 matches, 16,384-character strings, 1,048,576 encoded snapshot bytes, and a 30-second operation deadline. A node has at most 256 custom properties. Screenshot maxima are 8,192 by 8,192 pixels, 33,554,432 total pixels, and 67,108,864 PNG bytes. MCP trace inputs permit at most 3,600,000 ms and 67,108,864 bytes; the core recorder's conservative defaults are 10 minutes, 64 MiB uncompressed evidence, and 100,000 events. Lower application limits may reject a request before these schema maxima.

## Artifacts and traces

The application supplies an `ArtifactReference.Publisher` to `HarnessMcpServer.open`; the server never writes payload bytes itself. Structured results at or below 64 KiB are inlined in the response; larger structured results — and every screenshot and diagnostic PNG/JSON evidence payload — are published as opaque artifacts through the injected publisher. Without a publisher, a call that needs publishing fails with an `artifact-unavailable` error. Every artifact receipt contains a reference, media type, byte length, and lowercase SHA-256 digest. The reference is generated by the application's publisher and must be opaque (no filesystem path shape); there is no path argument in any tool. The MCP boundary recomputes the SHA-256, byte length, and expected media type before accepting a publisher receipt.

Screenshot media type, dimensions, length, and SHA-256 establish artifact integrity only. They do
not establish visual correctness or subjective approval.

Applications that allow clients to retrieve receipts install an `ArtifactReference.Reader` through the source-compatible `HarnessMcpServer.open(protocol, publisher, reader, input, output)` overload. `ArtifactStoreReader` binds reads to the same application-owned `ArtifactStore`, so session ownership, expiry, store quotas, complete-payload SHA-256 verification, and tracked stream cleanup remain authoritative. `ui_artifact_read` accepts only the opaque receipt plus an offset and a 1–65,536 byte maximum; it exposes no path, URI, root, listing, glob, or deletion capability. An offset equal to the total length returns empty data with EOF. Unknown, expired, and wrong-session receipts share one fixed not-found diagnostic; publisher-only server construction returns a typed read-unavailable diagnostic.

When a session registers a state/action contract provider, `ui_snapshot` also reports
`contractSchemaVersion`, `stateId`, and `controlCount`. The complete bounded contract is inline
below the threshold and otherwise moves to the same immutable artifact channel. Consumers must
reject unknown contract major versions and must not reinterpret absent or mistyped required
fields as failed application assertions.

`ui_inspect_compare` accepts only server-registered reference, policy, and viewport identities.
It always requests a new full-frame capture; launcher-generated PNGs and earlier screenshot
artifacts cannot satisfy the operation. The result keeps reference, current capture, comparison,
and policy evidence separate. A `converged` result means the accepted current capture met the
named policy with no blocking semantic difference. `stale`, `incomplete`, and `not-converged`
remain distinct results. The current PNG and complete JSON evidence are immutable artifacts.
Missing or invalid capture fields are reported together with their ranges, observed values, and
a minimal valid request.

Comparison results contain at most 256 top-left-origin framebuffer regions. A region reports its
category, optional stable control ID, bounds, differing-pixel count, and mean absolute error.
Text, value, bounds, padding, visibility, and clipping differences are attributed only when both
snapshots contain trustworthy semantic identity; remaining changed tiles are `raster-residual`.
The full-frame heatmap is a digest-verified PNG published through the same opaque artifact channel.
Use attributed regions to correct structure first, then the heatmap to localize residual pixels,
and pair text residuals with `ui_typography_diagnose` to distinguish native glyph-size errors from
bitmap scaling, filtering, or rasterization errors.

`ui_typography_diagnose` reports font and atlas identity, nominal/generated/effective size,
bitmap scale, texture filtering, available weight and spacing, window/viewport/framebuffer
identity, device scale, affine mappings, glyph runs, layout and ink bounds, origins, baselines,
alignment residuals, and per-control raster residual. Coordinates named `screen` and
`framebuffer` use a top-left origin; Scene2D `local` and `stage` coordinates retain their
bottom-left origin. Unsupported evidence is an explicit unavailable value with a reason.
Missing identity, mapping, reference, or required metadata fails closed rather than supplying
a default. `stale` and `not-stable` remain distinct from `not-pixel-sharp`.

`ui_layout_diagnose` reports stable actor, parent, layout, scroll, and clip-owner identities
with local, stage, screen, and framebuffer geometry. It waits for three consecutive completed
frames whose scroll position/range, viewport/content bounds, clip chain, layout digest, and
revision agree, then requires five identical post-settle samples. The gate is bounded by 120
frames and two monotonic seconds; missing, moving, non-invertible, or stale evidence fails
closed.

## Intrinsic layout qualification

`ui_validate_layout` validates one immutable Scene2D snapshot and the intrinsic evidence captured
with it. Finding `stageBounds.x` and `stageBounds.y` are signed stage coordinates; negative
positions are valid protocol data. Width and height remain non-negative. The Scene2D adapter reads
Actors, Labels, standard TextFields, fonts, glyph layouts, viewport bounds, and ScrollPane geometry only on the
session-owning render thread. Callers receive immutable bounded evidence and never read Actors or
fonts themselves. The request remains bounded by `maxDurationMillis`, `maxNodes`, and
`maxFindings`.

Unreleased coverage hardening ([ADR 0039](../adr/0039-complete-layout-coverage.md)) keeps the
existing result schema. Any truncated run is `INCOMPLETE`, unless an observed finding reaches
`failOn`, in which case it is `FAIL`. `PASS` therefore requires complete node and finding output.
For each requested intrinsic check, visible nonempty semantic text without exact geometry yields
an error-severity `CHECK_UNAVAILABLE` at that node's identity and bounds. A successful Label
capture elsewhere does not qualify text fields, selects, lists, or custom painted text.
Standard TextFields have their own bounded adapter under
[ADR 0042](../adr/0042-standard-text-field-geometry.md): on libGDX 1.14.2 it reads five fixed
internal fields and copies exact visible-slice geometry, including focus padding, scroll,
ascent and glyph-cache pixel rounding. It never draws or changes cursor, selection, scroll,
font markup or the application's font cache. The lookup is not protocol-selectable. A different
runtime version, denied JPMS private access, TextArea/custom field or font subclasses, unsupported
transforms, invalid metrics or more than 4,096 characters leaves field geometry unavailable.
Empty-field placeholders remain outside semantic text coverage. No module-opening flags or
approximate fallback are installed automatically.
The Scene2D extractor explicitly attributes a TextButton/CheckBox's owned Label geometry to
its parent only when their text agrees, preserving composed buttons without inferring coverage
from arbitrary descendant strings. Observed child Label ink remains checked even when its
semantic text is suppressed to keep locators unambiguous. Empty or hidden semantic text does
not itself request geometry. Text that has neither semantic metadata nor supported backend
evidence is outside this coverage contract; this is not a computer-vision audit.

The closed checks have these qualification rules:

- `clipped-text` requires real visible Label or supported TextField layout and ink bounds.
  The field's intentionally hidden horizontal text is excluded; its rendered slice is checked.
  It reports layout or ink outside the text actor, the real Stage viewport, or any effective ancestor `ScrollPane`
  actor area. The same Stage viewport evidence is retained for subtree validation; actor or
  subtree-root bounds are never substituted for it. It does not reuse or reinterpret the
  semantic node's container-clipped flag.
- `text-collision` reports overlapping visible glyph ink from distinct, non-ancestral supported
  text actors. `CLIPPED_TEXT` and `TEXT_COLLISION` findings have `ERROR` severity.
- `below-target-size` applies only to the canonical target roles `button`, `checkbox`,
  `text-field`, `select`, and `slider`; decorative labels and structural actors are not targets.
- `obscured` is opt-in and excludes ancestor/descendant composition. It compares unrelated
  visible actors only.
- `inconsistent-alignment` and `inconsistent-spacing` use only visible same-parent actors that
  share a nonblank `layout-group` and the exact same `layout-axis` value, either `horizontal` or
  `vertical`. Horizontal cohorts compare vertical centers and x-axis gaps; vertical cohorts
  compare horizontal centers and y-axis gaps. Alignment requires at least two actors and spacing
  at least three. A resulting `relatedActorId` names a resolvable peer node; the cohort's
  `layout-group` value remains descriptive evidence, not node identity.

Every enabled check is a requested qualification check, including defaults. When required
navigation, grouping, clip, or intrinsic text evidence is absent, the result contains an
error-severity `CHECK_UNAVAILABLE`; at the normal `failOn=error` gate the status is `FAIL`, never
`PASS`. More than 128 effective clip ancestors makes the complete intrinsic evidence unavailable
rather than publishing a truncated clip chain. For a libGDX `Label` whose effective
wrap-versus-ellipsis state cannot be determined from public state, placement is published only
when both possible states produce the same exact origin; otherwise all requested intrinsic text
checks are hard unavailable. Mirrored font scales publish normalized non-negative exact bounds
or likewise decline the complete intrinsic evidence. The Label adapter uses public state,
never infers a likely placement and never captures side effects from `Label.draw`. The fixed
TextField-only private-access exception is limited to ADR 0042 above.

These checks qualify observable invariants; they do not choose fonts, spacing, colors, component
styles, or layout remedies. The harness is a diagnostic engine, not a style generator, and visual
approval remains the caller's responsibility.

Start a trace before the operation under diagnosis and stop it in all success/failure cleanup paths. Trace ZIPs contain a strict manifest, newline-delimited causal events, and claimed optional evidence. Replay validates sequence, causal parents, session identity, semantic revision/frame progression, limits, archive signatures, duplicate names, traversal names, and Windows drive-qualified names. Replay does not execute commands and does not promise byte-identical GPU output.

## Failure handling

Read the structured error code and bounded evidence; do not parse logs.
Transport-neutral protocol failures retain the V1 codes `invalid-request`,
`unsupported-capability`, `session-not-found`, `session-closed`, `not-found`,
`strictness-violation`, `not-actionable`, `timeout`,
`render-thread-failure`, `capture-failure`, `limit-exceeded`,
`protocol-version-mismatch`, and `internal-error`.

The MCP agent boundary maps failures to `diagnostic-envelope/v1`. Its closed
registry contains `UNKNOWN_OPERATION`, `MISSING_ARGUMENT`,
`UNKNOWN_ARGUMENT`, `INVALID_ARGUMENT_TYPE`, `OUT_OF_RANGE`,
`INVALID_ENUM_VALUE`, `SCHEMA_CONFLICT`, `LOCATOR_NOT_FOUND`,
`LOCATOR_AMBIGUOUS`, `STALE_REVISION`, `STATE_NOT_READY`, `BUILD_FAILED`,
`LAUNCH_FAILED`, `DEADLINE_EXCEEDED`, `LIMIT_EXCEEDED`, `NO_PROGRESS`,
`LOOP_DETECTED`, `RECOVERY_BUDGET_EXHAUSTED`, and `INTERNAL_ERROR`.
Branch on `code`, not
message text. A transient response supplies the correction or state change,
consumed and remaining recovery budget, and a minimal valid example. A
terminal response has `retryable=false` and names the terminating rule.
Applying a correction does not erase the hard recovery total.

Scenario start results are closed outcomes. A second `ui_scenario_start` while another
scenario owns the session's lease terminates immediately with `session-busy` and executes
no lifecycle hooks for the rejected start.

A scenario that times out before completing any rendered frame publishes its terminal result
on the deadline thread, so the result may report `cleanupCompleted=false`: the render-owned
cleanup hook is deferred and has not run yet. The session's lease stays busy — further
`ui_scenario_start` calls keep terminating with `session-busy` — until that deferred cleanup
drains on the render thread exactly once, after which the next acquisition proceeds. The
render loop itself keeps rendering and advancing frames while the cleanup is pending; only
scenario completed-frame evaluation is skipped.

Remote internal errors redact stack frames and filesystem paths; full local
detail belongs only in restricted traces. Never respond to an exhausted bound
by disabling limits or to `LOCATOR_AMBIGUOUS` by silently choosing the first
match.

The boundary never accepts executable code, scripts, class names, reflection targets, method names, arbitrary commands, or caller-selected filesystem paths. The supported server transport is stdio. Any non-loopback network exposure requires authentication and a separately reviewed deployment and is outside the default workflow.

## Explicit V1 non-goals

V1 does not support Android, iOS, GWT/HTML, or RoboVM runtimes; arbitrary
SpriteBatch, ShapeRenderer, 3D, or non-Scene2D semantics; OS-level black-box
desktop automation; computer-vision element discovery; remote code execution,
reflection, arbitrary method calls, or filesystem access; a visual trace-viewer
application; or a full accessibility conformance audit. Roles and accessible
names are automation contracts, not an accessibility certificate.

## Combined keyboard and relative pointer gestures

`ui_input_gesture` is an additive schema-version-1 tool. Sessions advertise it only after an
application-owned relative pointer adapter and input coordinator are installed. Existing
keyboard gesture schemas 1 and 2 retain their original closed unions and bounds.

The request has 1–256 steps: `key-down`/`key-up` (`keycode` 0–255),
`mouse-down`/`mouse-up` (`button` 0–4), `mouse-move` (`deltaX` and `deltaY` each −4096–4096),
and `wait-frames`/`wait-ticks` (`count` 1–10,000). Each wait dimension has a cumulative
10,000 bound. Every pressed key/button must be released, duplicate downs and unmatched ups
are rejected, and at most 16 keys and five mouse buttons may be held. A timeline must contain
at least one input step; mouse-only movement is valid. Waits need not hold a control, allowing
projectiles and camera changes to settle. All sequence validation and every exact-tick
preflight finish before dispatch begins.

Movement calls the production `InputProcessor.mouseMoved`, using coordinates returned by
`RelativePointerAdapter.moveRelative`. Button steps call production `touchDown`/`touchUp` at
`position()` with pointer zero. The adapter owns the cursor origin; it must not overwrite the
processor's last-delivered position before `mouseMoved` computes its delta. The application
resets cursor bookkeeping with focus/pause/capture lifecycle changes. No synthetic global
`Gdx.input` state is installed. All pointer adapter and processor calls run on the render thread.

The application wraps the runner in its request-scoped exact-tick lease when its loop requires
one; each `wait-ticks` uses the same `ExactTickCoordinator` as keyboard gestures. Cancellation,
timeout, callback failure, and session shutdown release owned controls in reverse press order
with a fresh one-second cleanup deadline. MCP retains its session mutation lane until this
cleanup completes. Result steps contain the original nested `step`, its status and correlated
frame/tick evidence; `heldInputs` and cleanup entries use device-qualified `{device,code}`
controls. A failed cleanup remains visible in terminal evidence.

See [combined input cookbook](combined-input.md) for application wiring and an atomic example.
