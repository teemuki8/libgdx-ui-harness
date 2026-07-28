# ADR 0001: Layered semantic harness

- Status: Accepted
- Date: 2026-07-28

## Context

Reliable agent automation needs stable semantic contracts, deterministic Scene2D access, native framebuffer capture, a bounded transport, and an MCP adapter. Combining those concerns would leak libGDX objects into portable models, make render-thread ownership unclear, and couple Java callers to remote transport details.

## Decision

Use five published layers with one-way dependencies:

1. `harness-core` owns immutable semantic models, locators, actions, monotonic deadlines, waits, errors, limits, and traces. It has no libGDX, JSON, MCP, or test-framework dependency.
2. `harness-scene2d` depends on core and adapts an application-owned `Stage`. Every Stage and Actor access is scheduled on the owning render thread. A session is non-owning and closing it never disposes the Stage.
3. `harness-lwjgl3` depends on Scene2D and owns completed-frame synchronization and bounded framebuffer capture. Native objects do not cross into core models.
4. `harness-protocol` depends on core and defines the strict, versioned, byte-bounded JSON command/result/error contract. It has no MCP dependency.
5. `harness-mcp` depends on protocol and maps only the nine approved tools to protocol requests. It adds no domain behavior and accepts no executable code, reflection target, class name, method name, or filesystem path.

`harness-fixtures` and `benchmarks` exercise the vertical stack but are never published. Public Java operations use lazy locators, resolve against fresh snapshots, and return immutable values or `CompletionStage` results. Remote responses use opaque artifact references for large screenshots, snapshots, and traces instead of embedding unbounded payloads.

## Consequences

- Consumers can use core without libGDX and protocol without MCP.
- Scene2D and native lifecycle rules remain explicit and testable.
- MCP schema changes are reviewed separately from semantic behavior.
- Cross-layer convenience APIs are rejected when they would reverse a dependency or expose a live Actor, Stage, framebuffer, JSON mapper singleton, or transport object through a lower layer.
- Five Maven publications are required and their POM scopes enforce the same boundaries.
