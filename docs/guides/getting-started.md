# Getting started

## Requirements

Use JDK 25 and a desktop libGDX application. V1 supports Scene2D on LWJGL3; Android, iOS, GWT/HTML, RoboVM, non-Scene2D rendering, OS-level automation, and computer-vision discovery are not supported.

Add only the layers the application uses. For a Scene2D desktop harness and MCP endpoint:

```kotlin
dependencies {
    implementation("io.github.teemuki8:harness-lwjgl3:1.2.1")
    implementation("io.github.teemuki8:harness-mcp:1.2.1")
}
```

`harness-lwjgl3` brings in `harness-scene2d` and `harness-core`; `harness-mcp` brings in `harness-protocol` and core. The published modules require Java 25. Fixtures and benchmarks have no Maven publication. An optional published `harness-agent-runtime` module implements runtime-value comparison for the ADR 0025 SPI.

For new agentic UI construction, add `io.github.teemuki8:libgdx-ui-markup:0.4.1` and its
`libgdx-ui-markup-harness` adapter. Declare semantic identity in XML and pass a
`HarnessSemanticSink` into the markup builder. Bootstrap and benchmark workflows support this one
markup-only actor-construction path; controller code binds behavior to the resulting `BuiltUi`
instead of creating a second Stage or actor tree. The harness artifacts above remain markup-free
and can still validate existing Scene2D applications.

## The compiled Java flow

The following method is copied exactly from `PublicApiExampleTest`. `FixtureHarness` is an owned test fixture; it stands in for the application-specific startup that exposes its `Harness` and monotonic clock.

```java
@Test void documentedJavaFlowCompilesAndRuns() {
    try (FixtureHarness fixture = FixtureHarness.start()) {
        Harness ui = fixture.harness();
        ui.perform(Locator.role(Role.BUTTON).withName(TextMatch.exact("Save")),
            Action.click(), Deadline.after(fixture.clock(), Duration.ofSeconds(2)))
          .toCompletableFuture().join();
        assertEquals("saved", fixture.state());
    }
}
```

The source-of-truth test imports:

```java
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.gdx.uiharness.core.action.Action;
import dev.gdx.uiharness.core.action.Harness;
import dev.gdx.uiharness.core.locator.Locator;
import dev.gdx.uiharness.core.locator.TextMatch;
import dev.gdx.uiharness.core.model.Role;
import dev.gdx.uiharness.core.time.Deadline;
import java.time.Duration;
import org.junit.jupiter.api.Test;
```

Run it with:

```bash
./gradlew :harness-fixtures:test --tests '*PublicApiExampleTest'
```

## Production session lifecycle

Create one `Scene2dSession` for each application-owned `Stage`, on the owning render thread. Bind the `Scene2dHarness` to the same configured `InputProcessor`, render-thread scheduler, frame signal, monotonic revision supplier, and frame-number supplier used by the application. Do not replace the application's Stage, input processor, or game loop.

### Threading and frame wiring

Two snapshot consumers sit next to each other in the same wiring block but take different functional shapes, and one of them runs on the caller's thread:

- `WaitEngine` takes a no-arg `Supplier<SemanticSnapshot>`. Its supplier is invoked on the **calling** thread: over MCP that is the virtual thread that runs `ui_wait` (`HarnessProtocolService` routes waits onto the blocking executor). A supplier that reads the Stage directly now fails immediately with a typed `render-thread-violation` error carrying the operation, owner thread, and caller thread; route Stage access through the scheduler.
- `Lwjgl3ScreenCapture` takes a `SnapshotSource` whose method is `snapshot(long revision, long frame)`. A method reference shaped for one consumer does not compile for the other.

Route the wait supplier through the same `RenderThreadScheduler` the application already owns, blocking the calling virtual thread on the render-thread hop:

```java
WaitEngine waits = new WaitEngine(
    () -> scheduler.submit(
            () -> session.snapshot(revisions.getAsLong(), frameNumbers.getAsLong()),
            Deadline.after(clock, Duration.ofSeconds(30)))
          .toCompletableFuture().join(),
    locators, clock, fence);
```

`session` is the `Scene2dSession`, `scheduler` the `RenderThreadScheduler`, `revisions` and `frameNumbers` the monotonic revision and frame-number suppliers, `locators` a `LocatorEngine`, `clock` the monotonic clock, and `fence` the `Lwjgl3FrameFence`.

Complete the frame fence after every rendered frame, regardless of application state. `Lwjgl3FrameFence.completedFrame(revision, frame)` dispatches render-thread work and wakes the wait engine; an application that skips it while paused or on the title screen stalls `ui_wait` and `ui_screenshot` until their monotonic deadline with no diagnostic. Advance the fence on frames where the application draws nothing new as well, so the render-thread scheduler and wait engine keep draining.

`Scene2dSession.completedFrame(scenarioRunner, navigationRunner, revision, frame)` builds the per-frame semantic snapshot only while a scenario or navigation runner has active runs; an idle session skips that per-frame work while frame fences, captures, and on-demand `session.snapshot(...)` calls keep advancing. Invoke every `Scene2dSession` Stage-reading method from the owning render thread (the thread that constructed the session) or through `RenderThreadScheduler`.

Publish the session in a fixed `HarnessProtocolService.Session` registry before accepting MCP requests. Use stable, non-secret session IDs. Route MCP over its default stdio transport; do not expose an unauthenticated network listener. All queued requests include queue time in their deadline and each session serializes Stage work.

Shutdown in this order:

1. stop accepting MCP input and close `HarnessMcpServer`;
2. close the protocol-facing harness, waits, capture, and trace owners;
3. close `Scene2dHarness` and `Scene2dSession` on the render-thread lifecycle;
4. let the application dispose its own Stage and LWJGL3 application.

Closing a Scene2D session rejects later work with typed session-closed evidence but does not dispose the application-owned Stage. Never retain a snapshot node ID or Actor across a wait; locators are durable descriptions and re-resolve against a fresh snapshot.

## Artifact publishing

The application owns artifact persistence. `HarnessMcpServer.open(protocol, publisher, System.in, System.out)` takes an `ArtifactReference.Publisher`; the server never writes payload bytes itself. Structured results at or below 64 KiB are inlined in the MCP response; larger structured results, every screenshot, and every diagnostic PNG/JSON evidence payload are published through the injected publisher. Without a publisher (the `ArtifactReference.Publisher.unavailable()` default), a call that needs publishing fails with an `artifact-unavailable` error — a publisher-less server silently works for small structured results and fails for screenshots and large results.

A publisher stores the bytes (for example under the application's own build output) and returns an opaque `ArtifactReference(reference, mediaType, byteLength, sha256)` whose reference passes `ArtifactReference.requireOpaque`: no leading `/`, no `file:` scheme, no backslash, no drive- or relative-path shape. `harness-protocol` provides `FileArtifactStore` for a session-owned, quota-bounded store:

```java
ArtifactStore store = new FileArtifactStore(Path.of("build/artifacts"),
        new ArtifactStore.Limits(64L * 1024 * 1024, 256), Clock.systemUTC());
ArtifactReference.Publisher publisher = (mediaType, content) -> {
    ArtifactMediaType type = ArtifactMediaType.fromValue(mediaType);
    ArtifactId id = store.put(sessionId, type, content,
            Instant.now().plus(Duration.ofDays(1)));
    ArtifactStore.Metadata metadata = store.metadata(sessionId, id);
    return new ArtifactReference("artifact:" + id.value(), mediaType,
            metadata.size(), metadata.sha256());
};
HarnessMcpServer server = HarnessMcpServer.open(protocol, publisher, System.in, System.out);
```

## Driving the harness over MCP

MCP runs as JSON-RPC 2.0 over the process stdio transport. The handshake and tool calls below are the complete round trip an agent uses against one session; argument objects are exact. Note the locator `kind` discriminator is `"test-id"` with a `testId` field — the kind name differs from the field name.

```json
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"cave-flyer-client","version":"1.0"}}}
{"jsonrpc":"2.0","method":"notifications/initialized","params":{}}
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"ui_sessions","arguments":{}}}
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"ui_snapshot","arguments":{"sessionId":"cave-flyer-hud"}}}
{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"ui_query","arguments":{"sessionId":"cave-flyer-hud","locator":{"kind":"test-id","testId":"score-p1"}}}}
{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"ui_wait","arguments":{"sessionId":"cave-flyer-hud","locator":{"kind":"test-id","testId":"score-p1"},"condition":"present"}}}
{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"ui_screenshot","arguments":{"sessionId":"cave-flyer-hud","maxWidth":960,"maxHeight":540,"maxPixels":518400,"maxPngBytes":4194304}}}
```

`initialize` with protocol version `2025-11-25` is the MCP SDK 2.0.0 handshake. `ui_sessions` takes no arguments and returns the active session IDs; `ui_snapshot` and `ui_query` require `sessionId`; `ui_wait` requires `sessionId`, `locator`, and `condition` (`present` or `visible`) and takes an optional `deadlineMillis` (default 30,000, range 1 through 120,000); `ui_screenshot` requires `sessionId` plus `maxWidth`, `maxHeight`, `maxPixels`, and `maxPngBytes` and takes an optional `locator`. Every tool except `ui_sessions` requires `sessionId`. `ui_wait` is the tool that routes `WaitEngine` snapshots through the render thread (see Threading and frame wiring); the repository's fixture smoke tests lock that scheduler-routed wiring end to end, and the round trip above exercises the same production path.

## Safe operation

Prefer role plus accessible name, then label, visible text, test ID, and only then actor name/type or structural/index filters. Every operation needs a monotonic deadline. Keep default limits unless the application has measured reasons to lower them; never raise remote limits merely to make an oversized response pass. Screenshots and traces are opaque artifacts: validate their media type, byte length, and SHA-256 receipt, store them in a restricted evidence directory, and do not treat an artifact reference as a caller-selected filesystem path.
