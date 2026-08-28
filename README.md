# libGDX UI Harness

[![CI](https://github.com/teemuki8/libgdx-ui-harness/actions/workflows/ci.yml/badge.svg)](https://github.com/teemuki8/libgdx-ui-harness/actions/workflows/ci.yml)
[![Java 25](https://img.shields.io/badge/Java-25-007396?logo=openjdk)](https://openjdk.org/projects/jdk/25/)
[![libGDX 1.14.2](https://img.shields.io/badge/libGDX-1.14.2-e74a45)](https://libgdx.com/)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

A semantic UI automation library for libGDX Scene2D. It gives coding agents and Java tests a precise way to inspect, locate, operate, wait for, capture, and trace a live desktop game UI—without treating pixels as the primary interface.

The design brings Playwright-style principles to libGDX: lazy strict locators, actionability checks, automatic waits, immutable semantic snapshots, completed-frame screenshots, and causal traces. Live `Stage`, `Actor`, input, and framebuffer work remains confined to the application's render thread.

> **Release:** `1.3.0` is the current release (see [v1.3.0 notes](docs/releases/v1.3.0.md)). Published modules require Java 25.

## Why use it?

- **Semantic discovery:** inspect roles, accessible names, labels, text, test IDs, widget state, hierarchy, z-order, and bounds.
- **Reliable locators:** resolve fresh state for every operation; zero or multiple matches fail with typed, bounded evidence.
- **Faithful input:** click, hover, focus, fill, press, scroll, drag, and pointer actions travel through libGDX input dispatch.
- **Deterministic synchronization:** monotonic deadlines and frame/state signals replace sleeps and arbitrary delays.
- **Useful diagnostics:** errors have stable codes; screenshots and replayable traces retain causal evidence.
- **Agent-ready access:** a bounded stdio MCP server exposes exactly twenty-four typed tools with closed schemas.

## Quick start

### 1. Requirements

- JDK 25
- A desktop libGDX 1.14.2 application
- Scene2D / Scene2D.UI on the LWJGL3 backend

### 2. Add the published modules

Add only the layers your application uses:

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("io.github.teemuki8:harness-lwjgl3:1.3.0")
    implementation("io.github.teemuki8:harness-mcp:1.3.0")
}
```

`harness-lwjgl3` brings in the Scene2D and core APIs. `harness-mcp` brings in the transport-neutral protocol and core APIs.

### 3. Use the Java API

```java
try (FixtureHarness fixture = FixtureHarness.start()) {
    Harness ui = fixture.harness();

    ui.perform(
        Locator.role(Role.BUTTON).withName(TextMatch.exact("Save")),
        Action.click(),
        Deadline.after(fixture.clock(), Duration.ofSeconds(2))
    ).toCompletableFuture().join();

    assertEquals("saved", fixture.state());
}
```

`FixtureHarness` is the repository's test fixture. A production game instead creates a `Scene2dSession` for its application-owned `Stage`, binds the render scheduler, frame signal, revision/frame suppliers, and configured `InputProcessor`, then publishes that session through the protocol service. The harness does not replace or dispose the game's stage, input processor, or loop.

See [Getting started](docs/guides/getting-started.md) for the compiled example and production lifecycle.

### Markup-first agentic UIs

Repository-owned agentic workflows construct UI exclusively with
[`libgdx-ui-markup`](https://github.com/teemuki8/libgdx-ui-markup). The XML source declares stable
`testId`, `role`, and `accessibleName` metadata, and the markup harness adapter publishes those
semantics while building the one Scene2D actor tree. This removes a parallel imperative semantic
tree and makes the same source authoritative for construction and automation.

The six published harness modules intentionally remain independent of markup so they can validate
existing Scene2D applications. New bootstrap projects, fixtures, and agentic benchmark candidates
use the markup-only construction path.

## MCP tools

The server uses stdio and exposes a deliberately small tool surface:

| Tool | Purpose |
|---|---|
| `ui_sessions` | List active harness sessions |
| `ui_snapshot` | Capture a compact immutable semantic snapshot |
| `ui_query` | Evaluate a lazy locator |
| `ui_action` | Perform one allowlisted input action |
| `ui_keyboard_gesture` | Hold bounded keys across completed frames or exact controlled ticks, then release them |
| `ui_assert` | Assert a semantic condition on a resolved locator with typed outcome |
| `ui_wait` | Wait for a semantic condition |
| `ui_screenshot` | Capture bounded completed-frame PNG evidence |
| `ui_inspect_compare` | Inspect, capture, and compare one provenance-bound full frame |
| `ui_typography_diagnose` | Attribute font, HiDPI, transform, baseline, and raster differences to text controls |
| `ui_layout_diagnose` | Attribute layout, clipping, scroll, viewport, and coordinate-space differences to selected controls |
| `ui_trace_start` | Begin bounded causal trace collection |
| `ui_trace_stop` | Finalize a replayable trace archive |
| `ui_scenarios` | List registered bounded scenarios |
| `ui_scenario_start` | Start one bounded scenario |
| `ui_navigation_inspect` | Run a bounded navigation path through real input dispatch |
| `ui_navigation_validate` | Validate a navigation path without executing it |
| `ui_validate_layout` | Validate whole-stage or subtree layout invariants from one completed frame |
| `ui_matrix_run` | Run one scenario/assertion set across a bounded display matrix |
| `ui_matrix_results` | Retrieve one retained matrix run report |
| `ui_runtime_compare` | Compare a bound node's displayed value against its runtime observation |
| `ui_trace_query` | Query compact state transitions from a retained trace |
| `ui_semantic_compare` | Compare a registered semantic baseline against the current snapshot |
| `ui_capabilities` | Discover operations supported by a session |

Capability `ui_keyboard_gesture` means the session can dispatch cleanup-safe key-down, frame-wait,
and key-up timelines through its configured input processor. Such sessions additionally advertise
`ui_keyboard_gesture_v2` for the additive 256-step schema version 2; schema version 1 remains
limited to 64 steps. Capability `ui_keyboard_gesture_ticks` separately means an exact
application-owned tick coordinator is installed; each request still preflights its current paused
state and limits before dispatching input. Focus a widget with `ui_action` before starting a
gesture that depends on keyboard focus.

All remote requests, responses, recursive locators, strings, regular expressions, screenshots, traces, and artifacts are bounded. The default server accepts no scripts, reflection targets, arbitrary commands, caller-selected file paths, or unauthenticated network listener.

See [Agent tools and safe operation](docs/guides/agent-tools.md) for schemas, limits, locator/action inputs, failure handling, and trace safety.

## Modules

| Module | Responsibility | Published |
|---|---|---|
| `harness-core` | JDK-only semantic models, locators, actionability, waits, limits, and traces | Yes |
| `harness-scene2d` | Scene2D extraction, metadata, render scheduling, and input dispatch | Yes |
| `harness-lwjgl3` | Completed-frame synchronization and framebuffer capture | Yes |
| `harness-protocol` | Versioned bounded JSON commands, results, and errors | Yes |
| `harness-mcp` | Typed MCP SDK adapter and stdio server | Yes |
| `harness-agent-runtime` | Optional ADR 0025 runtime-value source adapter over the published AgentRuntime | Yes |
| `harness-fixtures` | Controlled real Scene2D reference application | No |
| `benchmarks` | Fixed libGDX-versus-Playwright parity corpus and runners | No |

Dependency direction is intentionally layered:

```text
harness-mcp -> harness-protocol -> harness-core <- harness-scene2d <- harness-lwjgl3
harness-agent-runtime -> harness-core
```

## Supported scope

V1 supports LWJGL3 desktop applications using Scene2D / Scene2D.UI. It does **not** claim support for Android, iOS, GWT/HTML, RoboVM, arbitrary SpriteBatch or 3D semantics, OS-level black-box automation, computer-vision discovery, remote code execution, a visual trace viewer, or full accessibility certification.

Semantic roles and accessible names are automation contracts. Node IDs are snapshot-local; keep locators, never cached `Actor` references or node IDs, across waits.

## Build and verify

```bash
./gradlew clean check javadoc --warning-mode=fail
```

The GitHub Actions matrix uses JDK 25 on the latest Ubuntu, Windows, and macOS runners. Linux performs full native checks under Xvfb. Hosted Windows and macOS run every backend-neutral check and compile the native adapter and fixture; those headless runners expose no usable WGL/NSGL context, so Linux provides the real hosted native runtime qualification.

The parity benchmark runs a fixed ten-scenario corpus against the real libGDX MCP harness and pinned Playwright/Chromium reference:

```bash
npm ci --prefix benchmarks/playwright
npx --prefix benchmarks/playwright playwright install chromium
xvfb-run -a ./gradlew :benchmarks:run --args='--runs 20 --output build/reports/parity' --warning-mode=fail
```

See [Benchmark methodology](benchmarks/README.md) for thresholds, evidence, and reproducibility rules.

## Documentation

- [Getting started](docs/guides/getting-started.md)
- [Agent tools and safe operation](docs/guides/agent-tools.md)
- [Semantic metadata](docs/guides/semantic-metadata.md)
- [Layered semantic harness ADR](docs/adr/0001-layered-semantic-harness.md)
- [Java 25 baseline ADR](docs/adr/0002-java-25-baseline.md)
- [V1 release notes](docs/releases/v1.0.0.md)

## License

Licensed under the [Apache License 2.0](LICENSE).
