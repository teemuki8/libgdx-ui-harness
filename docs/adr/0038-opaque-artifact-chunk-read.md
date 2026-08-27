# ADR 0038: Opaque artifact chunk retrieval

## Status

Accepted — 2026-08-27.

## Context

Harness tools publish screenshots and large evidence through application-owned stores and return opaque receipts. Clients cannot retrieve those bytes through MCP, so local tests have reached behind the protocol into server filesystem state. Returning paths would create arbitrary-read and portability risks.

## Decision

Add read-only `ui_artifact_read` backed by an optional application-supplied `ArtifactReference.Reader`. Reads require session ID and opaque reference, use bounded offset/chunk size, and return base64 plus immutable receipt metadata. Reader implementations enforce session ownership, expiry, integrity and stream cleanup. Publisher-only construction remains source-compatible and yields unavailable.

No listing, path input, deletion or unbounded download is added.

## Consequences

Clients can reconstruct and verify artifacts without path leakage. Large artifacts require several bounded calls. Applications that want retrieval must install a reader tied to the same store as the publisher.
