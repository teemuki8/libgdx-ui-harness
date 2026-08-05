# ADR 0019: Strict-locator suggestion diagnostics

## Status

Accepted

## Context

Strict locator failures correctly stop actions, but their bounded candidate evidence did not provide ready-to-use alternatives. Agents had to inspect a snapshot and reconstruct a locator after every zero-match or multiple-match failure. Suggestions must stay diagnostic, derive only from the bounded semantic evidence retained at failure time, reuse the existing closed locator schema, and never weaken strict execution or leak sensitive text.

## Decision

Attach bounded, deterministic locator suggestions to strict zero-match and multiple-match failures across the semantic core, protocol, and MCP layers.

`StrictResolution` computes suggestions through a pure `LocatorSuggestionEngine` that consumes the failed snapshot and candidate nodes already retained by the failure. Ranking prefers stable automation contracts in this order: unique test ID, role plus accessible name, associated label, role plus visible text, then explicitly fragile backend actor name/type and positional index fallbacks. Every candidate locator is re-evaluated against the same immutable snapshot and is emitted only when it uniquely selects exactly the intended candidate; redacted or ambiguous variants fall through to the next rank. Multiple-match failures include bounded distinguishing properties that separate each candidate from the others, and truncation past the suggestion limit is reported.

Redaction is a configurable `RedactionPolicy` applied to every semantic value before ranking, candidate summaries, distinctions, and message construction. The default identity policy preserves existing behavior; the policy identity is reported in failure details without exposing secrets. Redacted values that can no longer resolve are not advertised as suggestions.

The core `Locator` records and `LocatorFilter` records became public so `harness-protocol` can map the existing closed recursive locator schema in both directions (`LocatorSpec.fromCore`/`toCore`) without a new locator grammar. `ErrorEvidence` carries typed suggestions; `ProtocolError` and the MCP `DiagnosticEnvelope` gain the closed `LocatorSuggestionSpec` union with stability, rationale, candidate identity, and bounded distinctions. The existing protocol error codes, strict failure semantics, and operation catalog are unchanged; no new command or MCP operation is introduced.

## Consequences

Agents receive actionable, schema-valid locators on strict failures without any retry or fallback execution. Redaction can be configured before diagnostics are ranked or published. Public schema surface grew by the typed suggestion field; adding a ranking tier or changing evidence requires protocol golden updates, schema-version review, and the exact MCP catalog update.
