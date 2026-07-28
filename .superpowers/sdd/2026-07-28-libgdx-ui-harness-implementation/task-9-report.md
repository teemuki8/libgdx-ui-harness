# Task 9 Report — Typed MCP server adapter

## Status

COMPLETE. The MCP SDK 2.0 adapter exposes exactly the nine approved stdio tools, translates each call to one `HarnessRequest`, returns compact typed structured content, publishes large payloads through an injected opaque artifact contract, propagates cancellation to the protocol stage, and owns Java 25 virtual-thread connection/handler execution.

## Implementation commit

`5e7b37b` — `feat(mcp): expose typed UI harness tools`

## Files

- `harness-mcp/build.gradle.kts`
- `harness-mcp/src/main/java/dev/gdx/uiharness/mcp/ArtifactReference.java`
- `harness-mcp/src/main/java/dev/gdx/uiharness/mcp/HarnessMcpServer.java`
- `harness-mcp/src/main/java/dev/gdx/uiharness/mcp/HarnessToolCatalog.java`
- `harness-mcp/src/main/java/dev/gdx/uiharness/mcp/HarnessToolHandler.java`
- `harness-mcp/src/main/java/dev/gdx/uiharness/mcp/Main.java`
- `harness-mcp/src/test/java/dev/gdx/uiharness/mcp/HarnessMcpServerContractTest.java`
- `harness-mcp/src/test/java/dev/gdx/uiharness/mcp/HarnessToolCatalogTest.java`
- `harness-mcp/src/test/resources/mcp/tool-catalog-v1.json`

## MCP SDK 2.0 source/Javadocs inspection

Resolved `io.modelcontextprotocol.sdk:mcp:2.0.0`, then inspected the official published `mcp-core-2.0.0-sources.jar` before implementation. Relevant APIs reviewed were `McpServer.AsyncSpecification`, `McpAsyncServer` tool input/output validation, `McpSchema.Tool`, `McpSchema.CallToolRequest`, `McpSchema.CallToolResult.structuredContent`, `McpServerTransportProvider`, `McpServerTransport`, `McpServerSession`, `McpJsonDefaults`, and the SDK stdio transport lifecycle.

## RED

Command:

```text
./gradlew :harness-mcp:test --tests '*HarnessToolCatalogTest' --tests '*HarnessMcpServerContractTest'
```

Result: failed in `compileTestJava` because `HarnessToolCatalog`, `HarnessToolHandler`, `HarnessMcpServer`, and `ArtifactReference` did not exist (`artifact://454`).

A later security regression test for Windows and relative filesystem artifact references was also observed RED before the reference validator was tightened (`artifact://472`).

## Verification commands and results

```text
./gradlew :harness-mcp:test --tests '*HarnessToolCatalogTest' --tests '*HarnessMcpServerContractTest'
```

Result: PASS (`artifact://481`). `HarnessToolCatalogTest`: 4 tests, 0 failures. `HarnessMcpServerContractTest`: 7 tests, 0 failures.

```text
./gradlew :harness-mcp:test :harness-mcp:installDist
```

Result: PASS (`artifact://488`), 11 Gradle tasks successful.

```text
printf '%s\n' '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"smoke","version":"1.0.0"}}}' | timeout 10 harness-mcp/build/install/harness-mcp/bin/harness-mcp
```

Result: PASS in 0.80 seconds. The installed process returned JSON-RPC id `1`, protocol version `2025-11-25`, server info `libgdx-ui-harness`/`1.0.0`, advertised MCP tool capability, and exited after stdin closed (`artifact://488` build and final smoke output in session).

## Behavioral coverage

- Golden catalog contains exactly `ui_sessions`, `ui_snapshot`, `ui_query`, `ui_action`, `ui_wait`, `ui_screenshot`, `ui_trace_start`, `ui_trace_stop`, and `ui_capabilities`.
- All input objects reject additional properties; recursive locator and action unions use explicit tags, enums, and protocol-aligned numeric/string bounds.
- Malformed, oversized, and arbitrary path/method/script/command/code/class inputs are rejected before protocol dispatch.
- Action integration proves one tool call invokes one protocol request and returns `kind: action-result`.
- Capability discovery and compact snapshot summaries are covered.
- Screenshots and threshold-exceeding structured results use injected `ArtifactReference.Publisher` output; no filesystem path or placeholder store is used.
- Cancellation of the MCP publisher cancels the `CompletionStage` returned by the protocol invocation.
- In-memory stdio initialize/list/action round-trip and stdin-close lifecycle are covered.
- Handler and stdio connection execution use Java 25 virtual-thread executors.

## Self-review

### Schema drift

Compared tool enums and limits against `Command`, `HarnessRequest`, `HarnessResponse`, `ProtocolJson`, `Role`, and `LocatorFilter.State`. Tightened role values to the exact protocol wire enum, screenshot dimensions/bytes/scales to protocol limits, trace bounds to protocol limits, string/identifier lengths to protocol limits, and artifact JSON byte length to the protocol response ceiling. The checked-in golden fixture detects future schema drift.

### Security

The server creates only a stdio transport. There is no HTTP/socket listener, path parameter, arbitrary method/script/code/command parameter, reflection, process execution, or dynamic class loading. `ArtifactReference` rejects file URIs and Unix, Windows, home-relative, and dot-relative filesystem paths. Large data crosses the adapter only as cloned bytes passed to the injected publisher and an opaque returned reference.

### Lifecycle

The custom SDK transport processes its single stdio connection on a virtual thread, serializes writes, flushes each JSON-RPC message, processes each input line before accepting EOF, and completes termination on stdin close. Server close is idempotent and closes the SDK server, handler scheduler/executor, and transport executor. Handler cancellation is verified against a pending protocol future.

### Protocol JSON mapper note

The adapter does not mutate the shared `ProtocolJson.mapper()`; it creates one private mapper copy for DTO conversion/encoding. The existing shared mapper mutability noted in the ledger remains unchanged for final-review triage.

### Scope

No trace persistence or artifact storage implementation was added; Task 10 supplies the artifact publisher/storage. No production network exposure was added.
