# ADR 0031: Request boundary and render lifecycle hardening

## Status

Accepted

## Date

2026-08-08

## Context

Six externally observable weaknesses sat on the request boundary and render lifecycle:

- **Unbounded frame materialization.** The stdio transport parsed requests with
  `BufferedReader.readLine()`, so a hostile or broken client could grow heap without limit
  before JSON parsing, and a malformed or oversized frame terminated the whole server instead
  of producing a bounded parse error.
- **Backtracking regex evaluation.** Locator regex mode used `java.util.regex.Pattern`, whose
  backtracking engine can take exponential time on pathological patterns (for example
  `(a+)+$` against a long run of `a` characters), blocking the request thread and the render
  loop with no bound.
- **Unbounded and unclassified admission.** MCP tool calls reached protocol dispatch without
  any concurrency cap, and nothing distinguished mutating calls from read-only calls, so a
  client could flood the harness and mutating requests could race each other.
- **Two scheduler monitors.** `RenderThreadScheduler` guarded command state and lifecycle
  state under different monitors with nested acquisitions, a fragile protocol that is hard to
  audit for deadlock.
- **Overlapping scenario ownership.** A scenario start could be submitted while another run
  owned the same session, and the matrix runner could skip lease release when an assertion
  stage completed exceptionally, leaking the lease into the next case.
- **Frame-dependent deadlines.** Deadline checks ran only on completed frames, so a paused
  application never timed out actions or captures, and the MCP SDK's default outer request
  timeout (120 seconds) could abort a legitimate long scenario deadline before the request's
  own validated deadline had any effect.

## Decision

Harden the boundary from the outside in, preserving the layered design: core owns linear-time
text matching and the injected deadline-scheduling contract; Scene2D and LWJGL3 own the
render-thread state machines; MCP owns byte framing and request admission.

### 1. Linear-time locator regex (RE2/J 1.8)

`TextMatch` regex mode (`Mode.REGEX`) compiles with `com.google.re2j.Pattern` 1.8 at
construction and evaluates with `pattern.matcher(normalize(candidate)).find()` — linear-time
search semantics. Exact, case-insensitive-exact, and substring modes are unchanged. The public
factory `TextMatch.regex(String)` and the matching entry point are unchanged; only the engine
behind `Mode.REGEX` changes. Patterns remain bounded to `MAX_PATTERN_LENGTH = 16,384`
characters.

Supported syntax (asserted by `StrictResolutionTest`): literals, character classes and
escapes, Unicode classes (`\p{L}` and similar), capturing/non-capturing/named groups,
alternation, anchors (`^`, `$`), and greedy and lazy quantifiers — the RE2 syntax surface
([RE2 syntax reference](https://github.com/google/re2/wiki/syntax)).

Unsupported constructs — backreferences, lookahead and lookbehind, atomic/possessive groups,
conditionals, and other backtracking-dependent forms — fail at construction with a bounded
`java.util.regex.PatternSyntaxException("invalid text pattern", null, -1)` (an
`IllegalArgumentException`), preserving the existing public failure type. The translation
never logs and never echoes the caller's pattern or candidate text, because RE2/J's own
messages embed the pattern. This is the issue's intended trade-off: a caller that previously
used a JDK-only construct now receives a bounded `invalid-request` instead of silent
backtracking evaluation.

### 2. Bounded strict-UTF-8 stdio framing

A new package-private `BoundedJsonRpcFramer` reads newline-delimited frames from stdio in
8,192-byte chunks. The candidate frame buffer is allocated lazily to exactly
`ProtocolJson.MAX_REQUEST_BYTES` (1,048,576) and never grows beyond it, so no heap grows with
a hostile frame. LF terminates a frame and one preceding CR is stripped. Frames decode with a
strict UTF-8 decoder (`onMalformedInput(REPORT)`, `onUnmappableCharacter(REPORT)`), so
malformed input is rejected rather than silently replaced. A frame past the cap is rejected
(`frame-too-large`), the remainder is drained to its terminating LF in constant memory, and
the next valid frame is still delivered. EOF yields `EndOfInput`, or `frame-too-large` after
an oversized frame, or `unterminated-frame` after an in-limit unterminated frame. Rejected
frame content is never echoed; only the rejection code is retained.

Every newline-terminated rejected or parse-failed frame produces exactly one bounded JSON-RPC
parse error (`-32700`, `id: null` per JSON-RPC 2.0) and the read loop continues. An in-limit
frame left unterminated at EOF produces one parse error as well, after which the next read
yields `EndOfInput` and the transport terminates normally. The parse error is
written synchronously on the read-loop thread, serialized with response writes through the
`output` monitor (matching the MCP SDK's own stdio pattern), so a failed parse-error write
deterministically terminates the transport exceptionally instead of being swallowed by a
racing EOF close. A genuine stream `IOException` still terminates the server fail-closed; a
rejected or malformed frame never does.

Jackson `StreamReadConstraints` on every stdio message match `ProtocolJson` exactly: nesting
depth at most 64, string length at most 16,384 UTF-16 code units, and number tokens at most
128 characters. Violations surface as the same single JSON-RPC parse error, and a following
valid request succeeds.

### 3. Bounded admission with typed access modes

`HarnessToolCatalog` classifies every allowlisted tool exactly once on the same immutable
`ToolDefinition` that builds the schemas: `AccessMode.READ_ONLY` (17 tools) or
`AccessMode.MUTATING` (6 tools: `ui_action`, `ui_trace_start`, `ui_trace_stop`,
`ui_scenario_start`, `ui_navigation_validate`, `ui_matrix_run`). Unknown names throw
`IllegalArgumentException`; the handler consumes `catalog.accessMode(name)` and has no second
tool-name set.

`RequestAdmission` bounds dispatch before it reaches the protocol service, with one monitor
guarding the global counter, each session's counter, and each session's bounded FIFO mutation
lane. Server defaults: `DEFAULT_GLOBAL_LIMIT = 8` concurrent admitted requests across all
sessions, `DEFAULT_PER_SESSION_LIMIT = 4` per session (running reads + running mutation +
queued mutations), and `DEFAULT_MAX_QUEUED_MUTATIONS = 16` FIFO lane depth per session.
Admission is decided synchronously: excess requests are rejected immediately with
`LimitExceededException` (a failed future) and never invoke the work supplier, so no virtual
thread blocks waiting for a permit. Read-only requests start immediately and may overlap
within the bounds; per-session mutations start strictly in submission order and never
overlap. Permits are released in a single `whenComplete` path after the work stage (including
result translation and output accounting) reaches a terminal state, with no leak on
exceptional completion, cancellation (in-flight or queued), synchronous throw, or `close()`.

Requests without a session identifier use a distinct typed scope —
`RequestAdmission.SessionKey.SESSIONLESS`, a record whose `sessionId` is `null` — so no client
string, including the literal `"catalog"`, can share per-session admission state with
sessionless calls. No magic string is reserved; the protocol-level `"catalog"` default
sessionId is untouched.

`close()` is idempotent: it rejects new admission and every queued mutation (releasing their
permits) and never interrupts running work — requests admitted before close complete or
cancel exactly once. Rejection maps to the stable terminal `DiagnosticCode.LIMIT_EXCEEDED`
envelope with a deterministic message naming the exceeded bound (`Global admission limit
exceeded (limit=8)`, per-session, queue, or `Admission closed`). The handler-level default
deadline remains 30,000 ms.

### 4. Single-monitor scheduler locking

`RenderThreadScheduler` guards every queue, batch, command-state, and lifecycle transition
under one `lifecycle` monitor. User `Callable` invocation and all `CompletableFuture`
completions happen outside the monitor; the monitor only claims and records state. The legal
transitions are `QUEUED -> DISPATCHED -> FINISHED`, `QUEUED -> CANCELLED`, and
`QUEUED -> FINISHED` (timeout, close, or rejection). A cancellation after `DISPATCHED` is
rejected without interrupting render-thread work (`mayInterruptIfRunning` is deliberately
ignored). `close()` fails queued commands and leaves dispatched commands untouched;
cancellation releases bounded capacity immediately. There is no nested reverse monitor
acquisition, so cancellation, drain, and close cannot deadlock.

### 5. Exclusive scenario lease with exactly-once release

`Scene2dScenarioRunner` owns at most one active run per runner/session. The decision is made
atomically under the runner's `lifecycle` monitor at launch: if `active` is non-empty, the
competing request is rejected with `ScenarioFailure.SESSION_BUSY` (wire
`"session-busy"` in `ScenarioFailureData`, added to the tool-catalog failure enum). The loser
gets no scheduler submit, no deadline arm, and no lifecycle hook execution (setupAttempts 0,
cleanupCompleted false). `start()` delivers the rejection as a normal terminal result with
`failure = SESSION_BUSY`; `acquire()` delivers it as an exceptional completion with
`AcquisitionException`. Cancellation still routes through the render-thread terminate path,
so cleanup is never bypassed.

Every render-thread terminal path funnels through `completeTerminal`, which calls the
identity-based `releaseIfOwner(run)` — removing the identical `Run` from `active` — before
publishing the terminal result, so a dependent acquisition (for example the matrix runner's
next case) observes the freed lease. The no-frame deadline path is the one exception: it
publishes first and releases the owner only after its deferred render-thread cleanup drains
(see section 6). A stale release of an earlier lease cannot clear its successor.
`Lwjgl3MatrixRunner` releases each case's lease on every terminal path, including
exceptionally completed assertion stages, via a `handle` on the assertion chain; a cleanup
failure on release fails the case with `CLEANUP_FAILED` evidence, the primary assertion
failure stays primary, and `composeWithSuffix` keeps the classification within the 512-character
bounded evidence.

### 6. Frame-independent deadlines and a caller-owned scheduler

A new core public contract, `dev.gdx.uiharness.core.time.DeadlineScheduler`
(`schedule(Duration, Runnable)` returning `Cancellation`), schedules deadline signals
independently of render progress. The scheduler is caller-owned: `FixtureControl`,
`Lwjgl3CaptureFixture`, and `HarnessBridge` own and close their executors; there is no global
singleton or unowned executor. `Scene2dHarness` arms once at the `AWAITING_FRAME` transition
for `deadline.remaining()`; `Lwjgl3FrameFence` arms once per queued command. Signals claim
terminal state under the request/fence monitors (a completed frame wins by the same monitor),
registration is canceled on completion, cancellation, and close, and a late signal observes
terminal state and no-ops. Arming decisions are captured under the monitor but scheduling and
the returned `Cancellation` are invoked outside it, so zero-delay signal inline firing and
cancel-under-monitor callbacks can never run continuations while holding a monitor.

When a scenario deadline fires, the signal thread publishes the terminal `READINESS_DEADLINE`
result atomically — a paused or stopped render loop can never leave the call hanging — and the
result reports `cleanupCompleted=false` because it is published before the render-owned
cleanup hook has run. The run retains the session's single active-owner slot, so competing
acquisitions keep terminating with `session-busy` until the deferred cleanup drains on the
render thread. That cleanup hook executes exactly once; if its submission is rejected, the
owner slot is still released without republishing the already-immutable result. No-frame
publication does not stop the render loop: the loop keeps rendering and advancing frames, and
snapshot/query operations stay served while the deferred cleanup is pending; only scenario
completed-frame evaluation is bypassed.

The MCP server's SDK outer request timeout becomes `OUTER_REQUEST_TIMEOUT =
MAX_SCENARIO_DEADLINE_MILLIS (600,000 ms) + 30 s` translation allowance = **630,000 ms**, with
a constructor invariant that the outer bound strictly exceeds the maximum scenario deadline.
Per-request deadlines remain authoritative: general commands accept 1 through 120,000 ms
(`MAX_DEADLINE_MILLIS`) and `ui_scenario_start` accepts 1 through 600,000 ms
(`MAX_SCENARIO_DEADLINE_MILLIS`); the default remains 30,000 ms. The outer SDK timeout is a
backstop, not the primary limiter, and it is not disabled.

## Alternatives considered

- **Keep `java.util.regex` with a caller timeout.** Rejected: a timeout only fails the caller;
  the backtracking evaluation still burns CPU and threads, and it does not bound the render
  loop's work. A linear-time engine removes the vulnerability class at the source.
- **`BufferedReader` with a maximum line length.** Rejected: it still materializes a String up
  to the cap with no constant-memory overflow recovery and no strict UTF-8 validation, and it
  does not recover framing after a rejected frame.
- **Blocking admission (semaphore/queue).** Rejected: virtual threads parked on permits queue
  unboundedly and admission becomes a hidden resource. Synchronous rejection with an
  immediate failed future is bounded and observable (`LIMIT_EXCEEDED`).
- **Reserve the literal `"catalog"` as the sessionless scope.** Rejected: a legitimate client
  session could literally be named `catalog` and would share counters and mutation lanes with
  sessionless calls, causing false rejections and false serialization. A typed key whose
  `sessionId` is `null` makes collision impossible by construction.
- **Keep two scheduler monitors.** Rejected: nested monitor acquisition ordering is fragile
  and hard to audit; one monitor with all completions outside it is simpler and deadlock-free
  by construction.
- **A global scenario lock.** Rejected: independent sessions would serialize against each
  other for no benefit. The per-runner lease keeps sessions independent and gives a competing
  caller deterministic `session-busy` feedback.
- **Keep checking deadlines only on completed frames.** Rejected: a paused application would
  never time out, and the render loop would carry pending work indefinitely. The injected
  scheduler is transport-neutral and never runs graphics work — it only fails pending state.
- **Keep the 120-second SDK outer timeout.** Rejected: it aborts a valid 600-second scenario
  deadline before the request's own validated deadline governs. Raising the outer bound to
  630 s with request-specific deadlines keeps the SDK timeout as a fail-safe while making
  per-request deadlines the authoritative limit.

## Consequences

- **Security.** The stdio trust boundary is now byte- and token-bounded before any
  materialization: frames over 1 MiB, malformed UTF-8, excessive nesting/string/number
  tokens, and unsupported regex constructs all produce bounded, non-echoing failures. Regex
  evaluation is linear-time, removing the exponential backtracking denial-of-service class.
  Rejected frame content and caller patterns are never echoed, and client-facing diagnostics
  carry no stack traces or filesystem paths.
- **Timing.** Deadline signals fire without rendered frames, so paused applications time out
  deterministically. The 630-second MCP outer bound is a backstop; each request's own
  validated deadline (≤ 120 s general, ≤ 600 s scenario) remains authoritative and is enforced
  frame-independently.
- **Compatibility.** Regex locators that used JDK-only constructs (backreferences,
  lookahead/lookbehind, atomic/possessive groups) now fail at construction with the same
  bounded `invalid-request` failure type instead of being evaluated with backtracking. The
  repository's existing locator call sites were audited and use only RE2-supported syntax.
- **Behavior.** Concurrent scenario starts now terminate with `session-busy` instead of
  overlapping; a scenario deadline that fires without completed frames publishes its terminal
  result with `cleanupCompleted=false` and keeps the session's lease busy (`session-busy`)
  until the deferred render-thread cleanup drains exactly once; concurrent MCP calls beyond
  the admission bounds fail immediately with `limit-exceeded`; malformed or oversized stdio
  frames produce one JSON-RPC parse error and the connection continues; sessionless calls
  never share admission state with any client session.
- **API.** `DeadlineScheduler` is a new public core contract, and deadline-scheduling
  ownership is explicit (no unowned global executor). Scheduler, admission, and framing
  internals remain package-private; the wire surface is unchanged apart from the added
  `session-busy` scenario failure value and the longer outer request timeout.

## Verification

```bash
./gradlew :harness-core:test --tests '*StrictResolutionTest*' --tests '*LocatorEngineTest*' :harness-scene2d:test --tests '*RenderThreadSchedulerTest*' --tests '*Scene2dScenarioRunnerTest*' --tests '*Scene2dActionEndToEndTest*' :harness-lwjgl3:test --tests '*Lwjgl3MatrixRunnerTest*' --tests '*Lwjgl3ScreenCaptureTest*' :harness-mcp:test --tests '*BoundedJsonRpcFramerTest*' --tests '*RequestAdmissionTest*' --tests '*HarnessMcpServerContractTest*' --tests '*HarnessToolCatalogTest*' --no-daemon --console=plain --warning-mode=fail
```

The production end-to-end fixture asserts the publication-before-cleanup contract through a
real registered scenario start:

```bash
xvfb-run -a ./gradlew :harness-fixtures:test --tests '*ScenarioLifecycleFixtureTest*' --no-daemon --console=plain --warning-mode=fail
```
