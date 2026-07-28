# libGDX UI Harness — Agent Instructions

## Mission

Build a standalone Java library that gives coding agents Playwright-grade semantic inspection and deterministic control of libGDX Scene2D/Scene2D.UI applications. Optimize for semantic reliability, actionable diagnostics, and reproducibility before feature count.

## Scope and invariants

- V1 targets LWJGL3 desktop and Scene2D/Scene2D.UI.
- The architecture is layered: semantic core, Scene2D adapter, LWJGL3 capture, transport-neutral protocol, MCP adapter.
- No `Actor`, `Stage`, libGDX collection, or backend type may cross the adapter boundary into protocol models.
- All Stage/Actor reads and mutations run on the libGDX render thread.
- Locators are lazy and re-resolve before each action or assertion.
- Actions use real libGDX input dispatch; never invoke UI listeners directly.
- Time-sensitive behavior uses an injected monotonic clock and deterministic frame advancement.
- Public protocol data is immutable, versioned, bounded, and serializable.
- Avoid preview and incubator Java APIs even though the project targets Java 25.

## Required workflow skills

Read and follow applicable installed skills before acting:

- `using-superpowers` at conversation start.
- `brainstorming` before changing behavior or architecture.
- `writing-plans` before multi-step implementation.
- `test-driven-development` for every feature and bug fix.
- `systematic-debugging` for failures or unexpected behavior.
- `karpathy-guidelines` for surgical, assumption-aware changes.
- `verification-before-completion` before any completion claim.
- `requesting-code-review` after significant implementation.
- `finishing-a-development-branch` when a branch is ready to integrate.
- `game-development` when changes depend on game-loop, input, or rendering semantics.

External skill candidates found for later evaluation; do not install or trust them without source review:

- `pluginagentmarketplace/custom-plugin-java@java-testing`
- `pluginagentmarketplace/custom-plugin-java@java-gradle`
- `alexandru/skills@kotlin-java-library`

## Engineering rules

- Use Gradle Wrapper; do not rely on a machine-installed Gradle.
- Compile and test with JDK 25. Treat warnings as failures in project code.
- Follow red-green-refactor. A production behavior change starts with a failing behavioral test.
- Prefer records, sealed types, and explicit value objects for protocol models; keep hot render-loop paths allocation-aware.
- No sleeps for synchronization. Wait on observable state with a monotonic deadline.
- No global mutable singleton harness. Lifecycle and ownership must be explicit.
- Preserve strict locator errors: zero and multiple matches are distinct failures.
- Error responses must retain locator, candidates, last observed actionability state, elapsed time, and trace ID.
- Bound tree depth, result count, strings, screenshots, trace size, and request duration at trust boundaries.
- Never expose secrets, arbitrary filesystem reads, arbitrary reflection, or unrestricted method invocation through MCP.

## Verification expectations

Run the narrowest proof first, then the affected suite:

1. Pure model/locator unit tests.
2. Scene2D render-thread fixture tests.
3. Protocol and MCP schema contract tests.
4. A real LWJGL3 smoke scenario for rendering/input changes.
5. Compatibility and parity benchmarks when public semantics change.

A change is not complete because it compiles. Exercise the changed path and record the exact command and result.

## Documentation

- Architecture decisions with lasting consequences require an ADR.
- Specifications and plans must contain measurable acceptance criteria and exact verification commands.
- Keep public API examples compilable.
- Documentation under `docs/` follows `docs/AGENTS.md`.
