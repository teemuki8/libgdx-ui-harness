# Agent tools and safe operation

The MCP server exposes exactly twenty-four bounded tools. `tools/list` is the authority; unknown tools and unknown input fields are rejected. Except for `ui_sessions`, every tool requires `sessionId`. `deadlineMillis` is optional, defaults to 30,000 ms, and when supplied must be 1 through 120,000 ms; `ui_assert` and `ui_keyboard_gesture` require it up to 120,000 ms, while `ui_scenario_start` requires it up to 600,000 ms. Deadlines include adapter work and backend queue time. The server's outer request timeout is 630,000 ms (the scenario maximum plus a 30-second translation allowance), so a full scenario deadline is never aborted by the SDK transport timeout; the per-request deadline remains the authoritative bound.

`sessionId` is the single envelope field documented by this preamble and omitted from the per-tool rows; the per-tool rows name every other required input and any optional tool-specific input. Each row is `none` or a comma-separated list of `required`/`optional` field tokens, and a schema-parity test fails when a required input appears on either side without the other.

| Tool | Purpose | Tool-specific input | Result |
|---|---|---|---|
| `ui_sessions` | List active sessions | none | bounded session IDs and capability names |
| `ui_snapshot` | Capture a compact semantic snapshot | none | revision, frame, root ID, node count, optional `state-action/v1` identity/contract and full-snapshot artifact |
| `ui_query` | Evaluate a lazy locator | required `locator` | match count, bounded node summaries/evidence, optional artifact |
| `ui_action` | Perform one allowlisted action | required `action`, required `locator` | before/after revisions, observed state, evidence, optional artifact |
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
| `ui_trace_query` | Query compact state transitions from a retained trace | required `spec` | bounded transitions |
| `ui_semantic_compare` | Compare a registered semantic baseline against the current snapshot | required `spec` | matched status and bounded differences |
| `ui_capabilities` | Discover one session's supported operations | none | bounded capability names, exact operation schemas/examples, diagnostic registry, and recovery policy |

## Locator and action inputs

Locator schemas are closed recursive unions. Supported locator kinds are role, text/label, test ID, actor name/type, relation, filter, and index. Text match modes are exact, case-insensitive exact, substring, and regex. Regex mode compiles with the linear-time RE2/J engine: supported syntax includes literals, character classes and escapes, Unicode classes, groups, alternation, anchors, and greedy/lazy quantifiers; backreferences, lookahead/lookbehind, and atomic or possessive groups are rejected at construction as `invalid-request` rather than evaluated with backtracking. Relations are child, descendant, parent, and sibling. Filters support accessible name, `has`, `hasText`, and semantic state. Indexes are zero-based and intentionally reported as structurally fragile. Prefer `role` plus an accessible-name filter; never treat snapshot-local node IDs as durable handles.

`ui_action` accepts only click, hover, focus, fill, press, scroll, drag, and pointer. Pointer phases are down, move, and up. An action may request `force`, but force never bypasses strict locator resolution, render-thread confinement, request bounds, or input dispatch through the application's configured processor.

## Hard bounds

The transport reads newline-delimited frames with a strict UTF-8 decoder and rejects a request above 1,048,576 bytes before any JSON token is parsed. An oversized or malformed-UTF-8 frame that ends at a newline yields one JSON-RPC parse error (`-32700`, `id: null`) and the connection continues; rejected frame content is never echoed. An in-limit frame left unterminated at end of input yields one parse error, after which the server terminates normally. A response above 16,777,216 encoded bytes is rejected. Ordinary strings are at most 16,384 UTF-16 code units, identifiers are at most 256 characters, JSON nesting is at most 64, and numeric tokens are at most 128 characters, and the same constraints are enforced on every stdio message before dispatch. Locator schemas limit recursive locator depth to 32 and decoded locator nodes to 4,096. Regular-expression syntax is compiled during decode with the linear-time RE2/J engine, so a malformed or unsupported pattern (backreferences, lookahead/lookbehind, atomic or possessive groups) is an `invalid-request`, not an internal routing error.

Admission is bounded before dispatch: at most 8 concurrent admitted requests globally and 4 per session (including queued mutations), with at most 16 queued mutations per session. Read-only requests start immediately and may overlap; per-session mutations run strictly in submission order and never overlap. Requests without a `sessionId` use a distinct admission scope that no client session name can share. Excess requests fail immediately with the `limit-exceeded` diagnostic and never reach the harness.

Core semantic defaults are 10,000 nodes, depth 128, 1,000 matches, 16,384-character strings, 1,048,576 encoded snapshot bytes, and a 30-second operation deadline. A node has at most 256 custom properties. Screenshot maxima are 8,192 by 8,192 pixels, 33,554,432 total pixels, and 67,108,864 PNG bytes. MCP trace inputs permit at most 3,600,000 ms and 67,108,864 bytes; the core recorder's conservative defaults are 10 minutes, 64 MiB uncompressed evidence, and 100,000 events. Lower application limits may reject a request before these schema maxima.

## Artifacts and traces

The application supplies an `ArtifactReference.Publisher` to `HarnessMcpServer.open`; the server never writes payload bytes itself. Structured results at or below 64 KiB are inlined in the response; larger structured results — and every screenshot and diagnostic PNG/JSON evidence payload — are published as opaque artifacts through the injected publisher. Without a publisher, a call that needs publishing fails with an `artifact-unavailable` error. Every artifact receipt contains a reference, media type, byte length, and lowercase SHA-256 digest. The reference is generated by the application's publisher and must be opaque (no filesystem path shape); there is no path argument in any tool. The MCP boundary recomputes the SHA-256, byte length, and expected media type from the exact payload before accepting any publisher receipt; the immutable captured bytes define those expected receipt claims, so a lying or mutating publisher cannot redefine the receipt's digest, length, or media type. A mismatched receipt fails closed with an `artifact-unavailable` error; opaque-reference storage and readback integrity remain the publisher's responsibility. Verify the receipt before retaining or opening evidence. Keep artifacts in a session-owned restricted directory, prevent symlink substitution, and delete them when the session owner closes.

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
