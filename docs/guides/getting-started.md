# Getting started

## Requirements

Use JDK 25 and a desktop libGDX application. V1 supports Scene2D on LWJGL3; Android, iOS, GWT/HTML, RoboVM, non-Scene2D rendering, OS-level automation, and computer-vision discovery are not supported.

Add only the layers the application uses. For a Scene2D desktop harness and MCP endpoint:

```kotlin
dependencies {
    implementation("io.github.teemuki8:harness-lwjgl3:1.0.0")
    implementation("io.github.teemuki8:harness-mcp:1.0.0")
}
```

`harness-lwjgl3` brings in `harness-scene2d` and `harness-core`; `harness-mcp` brings in `harness-protocol` and core. The published modules require Java 25. Fixtures and benchmarks have no Maven publication.

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

Publish the session in a fixed `HarnessProtocolService.Session` registry before accepting MCP requests. Use stable, non-secret session IDs. Route MCP over its default stdio transport; do not expose an unauthenticated network listener. All queued requests include queue time in their deadline and each session serializes Stage work.

Shutdown in this order:

1. stop accepting MCP input and close `HarnessMcpServer`;
2. close the protocol-facing harness, waits, capture, and trace owners;
3. close `Scene2dHarness` and `Scene2dSession` on the render-thread lifecycle;
4. let the application dispose its own Stage and LWJGL3 application.

Closing a Scene2D session rejects later work with typed session-closed evidence but does not dispose the application-owned Stage. Never retain a snapshot node ID or Actor across a wait; locators are durable descriptions and re-resolve against a fresh snapshot.

## Safe operation

Prefer role plus accessible name, then label, visible text, test ID, and only then actor name/type or structural/index filters. Every operation needs a monotonic deadline. Keep default limits unless the application has measured reasons to lower them; never raise remote limits merely to make an oversized response pass. Screenshots and traces are opaque artifacts: validate their media type, byte length, and SHA-256 receipt, store them in a restricted evidence directory, and do not treat an artifact reference as a caller-selected filesystem path.
