# Issues 8–26 Release Design

## Purpose

Resolve every GitHub issue open in `teemuki8/libgdx-ui-harness` on 2026-08-08 (#8 through #26), merge the fixes in five dependency-cluster pull requests, and publish libGDX UI Harness 1.2.0 through the repository's documented release process.

The live issue bodies and comments remain authoritative. This design fixes their complete observable contracts without adding unrelated behavior.

## Delivery strategy

Use five focused pull requests rather than nineteen issue-sized pull requests or one release-sized pull request. Each cluster owns a coherent set of shared files and invariants; each issue retains independent regression coverage and a separate `Fixes #N` closure line.

The merge order is:

1. request safety and lifecycle: #8–#13;
2. semantic truth: #14–#16;
3. trace and artifact trust boundaries: #17–#20 and #24–#25;
4. Scene2D ownership and capture efficiency: #21–#23;
5. schema/documentation parity: #26.

Each pull request starts from the newly merged `origin/main`. Later clusters therefore consume the reviewed public contracts and avoid stacked-branch rebases across shared protocol and MCP files.

## System invariants

All changes preserve these repository contracts:

- No `Actor`, `Stage`, libGDX collection, or backend type crosses an adapter boundary.
- Every Scene2D read and mutation executes on the libGDX render thread.
- Locators remain lazy and strict; zero and multiple matches remain different failures.
- Actions continue through real libGDX input dispatch rather than listener invocation.
- Deadlines use injected monotonic time and do not depend on another rendered frame.
- Public protocol data remains immutable, versioned, bounded, deterministic, and serializable.
- MCP inputs, outputs, diagnostics, retained accounting, archives, and artifact references are bounded at their trust boundaries.
- Hot render and capture paths avoid work and allocation when no consumer needs the result.
- Java 25 is the build baseline without preview or incubator APIs; project warnings fail the build.

## Pull request 1: request safety and lifecycle

### #8 — bounded text matching

Regex locator evaluation must not execute an attacker-controlled backtracking engine on the render thread. The accepted text-matching contract must either use a linear-time implementation or reject unsupported constructs before a locator reaches render-thread resolution. Literal, exact, and substring matching remain compatible. Pattern source, candidate strings, result counts, and diagnostics remain bounded.

Proof:

- a pathological pattern and input complete within a deterministic test budget without blocking render-thread progress;
- supported regex behavior remains correct;
- unsupported or over-limit patterns fail before resolution with a bounded typed error.

The lasting regex compatibility and security decision requires an ADR.

### #9 — bounded JSON-RPC stdio framing

The MCP server must count frame bytes before materializing a Java `String`, decode strict UTF-8, and apply hardened JSON constraints to the parser that actually handles MCP traffic. A frame one byte over the limit, an unterminated oversized frame, malformed UTF-8, excessive nesting, over-limit strings or numbers, and malformed JSON must return bounded parse errors without terminating the server. A valid request after a rejected frame must still succeed.

The transport framing contract and byte limit must be documented in an ADR or an amendment to the existing MCP boundary decision.

### #10 — admission and mutation serialization

The server must enforce explicit global and per-session in-flight limits before dispatch, bound queued work and output accounting, and reject excess work with a stable bounded response. Mutating operations for one session must execute in one serialized lane while read-only operations retain safe concurrency. Cancellation and server close must release permits on every terminal path.

Proof includes concurrent admission at the exact limits, limit-plus-one rejection, ordered same-session mutations, permitted read concurrency, cancellation, exceptional completion, and close.

The public concurrency and overload contract requires an ADR.

### #11 — scheduler lock ordering

`RenderThreadScheduler` cancellation, draining, and close must have one acyclic ownership protocol. No path may acquire a command monitor while holding the lifecycle monitor if another path can acquire them in the opposite order. Existing queued, dispatched, cancelled, failed, and closed terminal semantics remain unchanged.

A deterministic concurrency test must reproduce the former cycle using barriers or latches rather than sleeps, then prove both threads complete and each command reaches exactly one terminal state.

### #12 — frame-independent deadlines

Accepted deadlines must expire even when no frame is rendered. This applies to Scene2D actions awaiting completion, LWJGL3 frame fences and captures, MCP dispatch, and scenario requests whose allowed deadline exceeds the former outer timeout. Deadline wakeups use the injected monotonic clock or an owned scheduler, race safely with normal completion, and complete exactly once. Closing a session or server cancels owned wakeups.

Proof covers no-frame expiration, completion immediately before expiry, expiry racing a frame, scenario deadlines up to their published maximum, and cleanup after close.

The timing and outer-timeout contract requires an ADR.

### #13 — exclusive scenario leases

Only one scenario owns a session at a time. Ownership acquisition is atomic; competing starts return a typed busy result rather than both proceeding. The lease is released after success, cancellation, deadline, hook failure, assertion failure, reset failure, and session close. A stale completion from an old run cannot release or mutate a newer run's lease.

Proof covers every terminal path and a concurrent start race without sleeps.

The public lifecycle ownership contract requires an ADR.

## Pull request 2: semantic truth

### #14 — apply before matrix assertions

Every display-matrix case must be applied to the real application/window state before its assertions run. Requested width, height, UI scale, device pixel ratio, HiDPI mode, locale, font profile, and restart profile must each be either applied and observed or rejected as unsupported before assertion evaluation. Observed settings are captured for the same case and frame window as the assertions. A mismatch between requested and observed state produces a distinct terminal case status and no passing assertion result.

The full Cartesian product remains preflight-bounded. Every started, failed, cancelled, and unstarted case retains deterministic terminal evidence. Real LWJGL3 smoke coverage must demonstrate at least two materially different applied cases.

The matrix execution ADR must be amended for application, observation, and restart coordination.

### #15 — immutable registered semantic baselines

Semantic comparison may use only a baseline explicitly registered before comparison. An unknown or misspelled baseline ID must return a typed not-found result and must never learn from the current UI. Registration validates a canonical digest over the complete versioned baseline; the ID and digest identify immutable content, and conflicting replacement is rejected. The production fixture loads its reference baseline resource before serving requests.

Proof covers known comparison, unknown ID, misspelled ID, changed content under an existing identity, digest mismatch, and a deliberate UI regression that cannot pass by self-comparison.

The semantic baseline ADR must be amended for registration and digest identity.

### #16 — independent typed runtime comparison

The production fixture's runtime model exists independently of Scene2D actors. UI metadata can bind an actor to a property on an already registered runtime entity without deriving the provider from that actor. Comparison retains runtime value type or format identity through the observation boundary. Incompatible display/runtime types cannot report `EQUAL`; a deliberately desynchronized UI and model must report `MISMATCH` with bounded correlation evidence.

Existing applications that do not install a runtime provider remain supported and receive the current unavailable result. The runtime binding and agent-runtime adapter ADRs must be amended for type compatibility and independent ownership.

## Pull request 3: trace and artifact trust boundaries

### #17 — bounded recovery accounting

Recovery and diagnostic accounting keyed by caller-controlled session or fingerprint data must have explicit entry limits and monotonic expiry. Successful or terminal workflows and server close remove state. Eviction is deterministic and cannot allow an active key to bypass its attempt policy. Reported elapsed time represents the complete retained recovery workflow rather than only the latest call.

Proof covers maximum entries, limit-plus-one eviction/rejection semantics, expiry, terminal cleanup, close, and attempt enforcement across calls.

### #18 — cumulative trace decompression limits

Trace replay must enforce cumulative inflated-byte and event-count limits before allocating each decoded entry. It must also reject unreasonable per-entry compression ratios. Multiple individually valid entries whose sum exceeds the budget fail with a bounded typed error; integer overflow cannot bypass accounting. The archive stream and temporary resources close on every failure.

Proof includes a multi-entry compression bomb, exact-limit success, limit-plus-one failure, ratio failure, overflow-resistant accounting, and a valid archive after rejection.

### #19 — integrity-bound archives and receipts

A finalized trace manifest binds the canonical archive contents, event count, artifact identities, and byte counts with SHA-256 digests. Replay recomputes and verifies those bindings before reporting trusted evidence. The protocol `TraceStopped` result and MCP receipt expose the verified archive digest so downstream consumers can bind retained evidence to the exact bytes.

Tampering with events, artifacts, manifest metadata, or a receipt must be detected even when an attacker updates an unbound field consistently. Producer fixtures and benchmark bridges consume the new verified receipt.

The versioned trace archive and public protocol change requires an ADR and compatibility documentation.

### #20 — verify publisher receipts

Before accepting an artifact publisher receipt, the MCP boundary recomputes SHA-256 and byte length from the exact captured bytes supplied to the publisher and verifies the expected media type. A mismatched digest, size, or media type yields a bounded artifact-unavailable result; no unverified reference reaches the client. The rule applies uniformly to screenshots, compare images, typography images, layout images, trace archives, and generic large-result offload.

Proof includes each mismatch dimension and a valid publisher for every artifact family.

### #24 — symlink-safe trace finalization

Trace staging and finalization must create owner-only files, open with no-follow semantics, and verify stable file identity between creation, write, archive inclusion, and move. A substituted symlink or changed file identity aborts finalization without reading or archiving the target. Temporary files and directories are cleaned on all terminal paths without following attacker-controlled links.

Proof uses a controlled staging substitution and verifies that outside content is neither read nor modified.

### #25 — redact publisher failures

Publisher exception messages are untrusted and must not enter MCP content, structured diagnostics, traces returned to the client, or logs at ordinary user-visible levels. Clients receive a fixed bounded message plus an opaque trace ID. Restricted internal logging may retain the exception object under the repository's logging policy without serializing secrets into returned data.

Proof injects secret-like exception text and asserts its absence from every returned field while preserving the trace ID and stable error classification.

## Pull request 4: Scene2D ownership and capture efficiency

### #21 — session-bound render-thread enforcement

Every `Scene2dSession` method that reads or mutates `Stage`, actors, adapters, semantic metadata, or completed-frame state verifies the captured owner thread. Non-owner public operations continue to route through `RenderThreadScheduler`; accidental direct access fails immediately with a typed, actionable render-thread error. Correct render-thread access remains allocation-light.

Proof invokes every boundary method from the owner and a non-owner thread and exercises a caller-thread operation through the scheduler. The threading ADR must be amended if a new public error code is introduced.

### #22 — snapshots only for active consumers

A completed frame builds a semantic snapshot only when a scenario or navigation runner has an active subscription. Starting the first run enables snapshots before its first required observation; reaching the last terminal state disables them. LWJGL3 frame fences, captures, trace timing, and normal rendering remain independent and continue to advance without a runner snapshot.

Proof counts snapshot construction across idle frames, an active run, navigation, cancellation, and the return to idle. The real LWJGL3 smoke scenario verifies rendering and frame fences while idle.

### #23 — remove internal base64 round trips

Captured PNG data remains owned immutable bytes through core, protocol in-memory models, and MCP artifact publication. Defensive ownership is preserved without repeated clones. JSON serialization retains the existing base64 wire representation and existing byte/string bounds. MCP artifact paths do not serialize an unused full inline result before offloading.

Proof verifies byte-for-byte publication, unchanged protocol JSON shape, limit enforcement, defensive ownership, and absence of base64 conversion on the publication path through behavioral allocation or injected-codec evidence rather than source-text assertions.

## Pull request 5: schema/documentation parity

### #26 — runtime-compare required inputs

`docs/guides/agent-tools.md` must list `maxDurationMillis` alongside `locator` as a required tool-specific input for `ui_runtime_compare`; the guide preamble continues to state that all tools except `ui_sessions` require `sessionId`. The catalog's minimal example remains executable and includes every required field.

A catalog/docs parity test derives the documented required-input set from a stable machine-readable section or generated table and compares it with every catalog schema. It must fail when a required field is added to either side without the other. Documentation remains a consumer of the tool catalog rather than a second hand-maintained authority.

No ADR is required because the tool schema itself does not change.

## Error and compatibility policy

New failures use typed public error/status values when callers must distinguish remediation. User-controlled strings never become unbounded diagnostics. Every error retains the relevant locator or operation identity, candidates when applicable, last observed actionability state, elapsed time, trace ID, and explicit truncation metadata within existing limits.

The release is additive and hardening-oriented. Existing valid requests, JSON shapes, artifact references, runtime-provider absence, and non-scenario `Scene2dSession` use remain supported. Internal byte ownership may change while serialized screenshot JSON stays compatible. Any implementation that requires removing or changing an existing supported public API invalidates the 1.2.0 assumption and requires a revised design before tagging.

## Test and verification policy

Each issue follows red-green-refactor independently inside its cluster:

1. add the smallest behavioral regression test that fails for the reported defect;
2. run the focused Gradle test and record the expected failure;
3. implement the minimum production change;
4. rerun the focused test, then all affected module tests;
5. run real LWJGL3 smoke coverage for rendering, frame, input, display, or capture changes;
6. run protocol and MCP schema/golden tests for every public model or catalog change;
7. run the repository gate before each merge.

All Gradle commands use the wrapper, JDK 25, `--no-daemon --console=plain --warning-mode=fail`. Synchronization tests use observable barriers, latches, injected clocks, and deadlines rather than sleeps. Tests assert behavior, bounds, transitions, and errors rather than implementation text.

Before each cluster merge, run at minimum:

```bash
./gradlew clean check javadoc --no-daemon --console=plain --warning-mode=fail
python3 scripts/validate-workflows.py
```

Also run the exact focused tests and fixture smoke tasks named in the implementation plan. Review `git diff --check`, the complete PR patch, issue acceptance criteria, GitHub review threads, and CI checks on the exact head SHA.

## Pull request and merge policy

For each cluster:

1. create an isolated worktree and branch from current `origin/main`;
2. commit only that cluster's tests, implementation, directly required ADRs, public documentation, and generated schema golden changes;
3. open a ready pull request whose body lists every issue, root cause, acceptance evidence, exact commands, and `Fixes #N` lines;
4. review the remote base, head, commit list, files, full patch, compatibility, security, boundedness, threading, test quality, comments, and checks;
5. reproduce and fix every actionable finding test-first, then repeat review and CI on the new SHA;
6. merge only the reviewed SHA when all required checks pass and no actionable thread remains;
7. verify the PR is merged and each included issue is closed before creating the next cluster branch.

## Release 1.2.0

After all nineteen issues are closed and `main` contains exactly the five reviewed cluster changes:

1. create `docs/releases/v1.2.0.md` with user impact, compatibility, migration notes, fixed issues, public coordinates, and exact verification evidence;
2. confirm the latest `main` CI run is green;
3. run the complete local candidate gate from `docs/maintainers/releasing.md`;
4. prepare the immutable candidate Maven repository and low-confidence repeatability schedule for the exact candidate commit;
5. seal `precommitment.json`, execute only the prepared identities, retain every required raw artifact, and generate a passing digest-bound `decision.json`;
6. commit the evidence separately and push a signed annotated `release-evidence-<candidate>` tag;
7. push signed annotated tag `v1.2.0` at the exact qualified candidate;
8. wait for `.github/workflows/release.yml` to succeed and Maven Central to report `PUBLISHED`;
9. verify all six modules and their POM, main JAR, sources JAR, Javadoc JAR, and signatures resolve publicly;
10. create the GitHub release from immutable tag `v1.2.0` using `docs/releases/v1.2.0.md`, then verify it is published and marked latest;
11. fetch and reconcile local `main` with `origin/main` without discarding unrelated user work.

The release is incomplete if qualification is missing, the publication workflow is pending or failed, Central is not `PUBLISHED`, public coordinates do not resolve, the GitHub release is absent, any issue remains open, or local divergence is unexplained.

## Completion criteria

Delivery is complete only when:

- pull requests for all five clusters are merged from reviewed, green head SHAs;
- issues #8 through #26 are closed by those merges;
- every issue acceptance criterion has direct current-state test or smoke evidence;
- required ADRs, protocol goldens, MCP catalog data, examples, and user documentation agree;
- `main` passes the full JDK 25 release candidate gate;
- signed qualification and release tags bind the exact candidate;
- Maven Central reports `PUBLISHED` and all six module coordinate sets resolve;
- GitHub release v1.2.0 is published from the immutable tag with matching notes; and
- local `main` is reconciled and its worktree state is reported exactly.
