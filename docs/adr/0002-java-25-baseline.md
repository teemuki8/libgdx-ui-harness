# ADR 0002: Java 25 and Gradle 9.6.1 baseline

- Status: Accepted
- Date: 2026-07-28

## Context

The desktop harness and MCP server need a supported toolchain, predictable bytecode, and inexpensive independent request handling. Gradle supports running on Java 25 from 9.1 onward, while this repository's wrapper is pinned with a SHA-256 checksum to Gradle 9.6.1.

## Decision

Build, test, document, and publish with JDK 25 and `--release 25`. Published code may use stable Java 25 APIs but must not use preview or incubator APIs. Java compilation enables `-Xlint:all`; the release gate uses `--warning-mode=fail`; Javadocs use doclint plus `-Werror`. Independent MCP work may use virtual threads, but virtual-thread scheduling does not alter request ordering, deadlines, cancellation, protocol fields, or render-thread confinement.

The wrapper distribution, all direct dependency versions, npm dependencies, Gradle dependency locks, and Gradle verification checksums are committed. Linux runs the full clean check under Xvfb. Windows runs all backend-neutral checks and compiles the LWJGL3 adapter and native fixture because GitHub-hosted Windows runners expose no usable OpenGL driver. macOS runs backend-neutral checks in Gradle workers and qualifies the platform-native LWJGL3 path in a dedicated first-thread subprocess.

## Consequences

- Consumers of published artifacts require Java 25.
- Lower bytecode targets and mobile/web runtimes are not implied by V1 and require a separately measured compatibility decision.
- Toolchain warnings are fixed at their source. Native-access flags are narrowly applied to tests that load libGDX/LWJGL natives; warnings are not globally hidden.
- A release is rejected if it is not an annotated signed semantic-version tag, if dependency verification or locks fail, if Javadocs warn, or if Maven artifacts cannot be staged and validated.
