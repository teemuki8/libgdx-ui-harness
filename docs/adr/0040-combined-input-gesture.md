# ADR 0040: Versioned combined input gesture

Status: accepted local candidate. Date: 2026-09-10.

First-person automation needs one bounded move/aim/fire timeline. Keyboard v1/v2 have closed
step schemas and established evidence consumers. Add a separate schema-version-1
`ui_input_gesture`, core request/result, Scene2D runner, and protocol coordinator. Retain the
legacy constructors and schemas. `Session.withInputGestures` preserves existing session fields
and advertises the capability only with an installed coordinator.

Use an explicit render-thread `RelativePointerAdapter` to supply application-owned cursor
coordinates. The runner dispatches production `mouseMoved` and primary-pointer
`touchDown`/`touchUp`. It never modifies global `Gdx.input`, camera state, Stage ownership, or
the application loop. The adapter must preserve last-delivered coordinates until the
production processor computes its relative delta.

The runner retains the proven keyboard runner's deadline, frame, tick preflight, cancellation,
and reverse cleanup lifecycle. Independent request/result types prevent mouse evidence from
widening old wire unions. This duplicates the bounded lifecycle implementation; a later
internal extraction requires parity coverage and does not change either public schema.

Requests contain at most 256 steps, 16 held keys and five buttons, bounded movement deltas,
and bounded cumulative waits. Device-qualified control identities keep button zero distinct
from key zero. Full structural and tick preflight precedes input. Exact-tick request leases
remain application-owned. MCP serializes mutations for a session through terminal cleanup.

Proof: `InputGestureRequestTest`, `Scene2dInputGestureRunnerTest`,
`InputGestureProtocolTest`, catalog schema tests, and `InputGestureProductionFixtureTest`.
The native fixture verifies combined move/aim/fire through stdio MCP, exact ticks, independent
runtime observation, invalid full preflight, cancellation, and clean shutdown.

Local verification from the harness worktree:

```bash
/home/tjaaskel/git/libgdx-agent-bootstrap/scripts/isolated-run.sh xvfb-run -a ./gradlew :harness-core:test --tests '*InputGesture*' :harness-scene2d:test --tests '*InputGesture*' --tests '*KeyboardGesture*' :harness-protocol:test --tests '*InputGesture*' --tests '*KeyboardGesture*' :harness-mcp:test :harness-fixtures:test --tests '*InputGesture*' --tests '*KeyboardGesture*' :harness-core:javadoc :harness-scene2d:javadoc :harness-protocol:javadoc :harness-mcp:javadoc --no-daemon --console=plain
```

This focused proof is separate from the coordinated full-stack check, independent review, and
owner acceptance of the consuming game's presentation.
