# Combined input cookbook

This local candidate adds `ui_input_gesture` without changing published keyboard schemas.
Use the application's actual `InputProcessor` and render-thread cursor adapter:

```java
var runner = new Scene2dInputGestureRunner(
        sessionId, productionInput, scheduler, completedFrames,
        revisionSupplier, frameSupplier, deadlineScheduler,
        Optional.of(exactTicks), traceSink, relativePointer);
var enabledSession = session.withInputGestures(runner::execute);
```

The names in this wiring example are application-owned dependencies. If exact execution needs
a request-scoped loop lease, acquire it in the coordinator passed to `withInputGestures`, keep
it through the runner's terminal cleanup, and release it on every terminal path. Do not acquire
and release a loop lease around each individual tick step.

`RelativePointerAdapter` exposes `Position position()` and
`Position moveRelative(int deltaX, int deltaY)`. A captured-pointer implementation can return
`new Position(Math.addExact(lastX, deltaX), Math.addExact(lastY, deltaY))`; its production
`mouseMoved` computes the delta and then updates `lastX`/`lastY`. Physical callbacks use that
same method. Reset both coordinates on capture or focus changes. Button dispatch uses the
current adapter position. Return positions in libGDX input screen coordinates, including the
same Y-axis convention as physical mouse callbacks.

```json
{
  "sessionId": "game",
  "schemaVersion": 1,
  "deadlineMillis": 10000,
  "steps": [
    {"kind": "key-down", "keycode": 51},
    {"kind": "mouse-move", "deltaX": 120, "deltaY": -20},
    {"kind": "mouse-down", "button": 0},
    {"kind": "wait-ticks", "count": 30},
    {"kind": "mouse-up", "button": 0},
    {"kind": "key-up", "keycode": 51}
  ]
}
```

Stop admission and call `runner.stop()` before closing the scheduler, frame signal, or pointer
owner. Pump the render-thread scheduler until cleanup terminates. The application decides
how physical and automated input share ownership; avoid concurrently supplying the same held
controls from physical input. Harness requests do not inspect or rewrite backend key polling.

The native fixture demonstrates actual input callbacks and independently observes pointer
aim and firing ticks with `ui_runtime_observe`; see `InputGestureProductionFixtureTest`.

If the request deadline scheduler rejects admission, the runner returns
`deadline-scheduler-failure` before input. If the required cleanup alarm cannot be scheduled,
the runner terminates with failed cleanup and `scheduler-rejected` release attempts, retaining
the unresolved held controls in evidence. No untracked post-terminal cleanup is launched;
the application must treat the session as unusable and reset its own input state on disposal.

A started exact-tick step receives failed step evidence when cancelled before completion.
If cancellation arrives during the coordinator's synchronous `advance` invocation, terminal
cleanup waits for that admitted invocation to return and cancels its returned future. The
application coordinator must itself obey its deadline; the runner never publishes a terminal
result while that invocation can still begin authoritative work.
