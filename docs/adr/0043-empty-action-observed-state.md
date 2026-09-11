# ADR 0043 — Preserve empty observed action state

Date: 2026-09-11

Status: Accepted in source; not yet a published release.

## Context

`Command.ActionSpec.Fill` already accepts an empty value. The Scene2D dispatcher clears a
TextField through the application's real End/backspace input, and the completed-frame snapshot
reports an empty string. `ActionResult` also accepts it. The protocol response constructor
incorrectly required nonblank `observedState`, so an already successful clear became
`INTERNAL_ERROR` while converting its result. The MCP output schema also required one character.

The font workbench exposed this with `ui_action` on its `font-path` field and `value: ""`.
The independent native markup fixture reproduces the same failure without the font workbench.

## Decision

Action response `observedState` accepts empty and whitespace strings. It remains required,
nonnull and limited by `ProtocolJson.MAX_STRING_LENGTH` to 16,384 UTF-16 code units. Its MCP
output schema changes only the string's `minLength` from one to zero; the maximum, required
field and string type remain. The published tool-catalog golden records this correction.

No new command, version or capability is introduced. Existing successful nonempty results are
unchanged. Consumers must accept an explicitly empty observed value after clearing a field;
missing and null values are still invalid. No fabricated placeholder replaces actual state.

This correction changes response validation only. The application still owns Stage, input,
render-thread scheduling and frame completion. Actor access, End/backspace dispatch, bounded
deadlines and revision validation are unchanged; the response exposes no widget or mutable object.

## Verification

Three tests first failed for the expected reasons: protocol blank-state rejection, MCP schema
minimum length, and `INTERNAL_ERROR` from the real LWJGL3 markup application's stdio MCP server.
Protocol roundtrips and schema validation now cover empty, whitespace, the existing maximum,
oversized and null values. The schema also rejects a missing observed state.

The native regression fills `Before clearing`, clears the field, clears it again, then types
`Recovered`. Each request validates its explicit observed state, advancing revisions, a fresh
semantic query and `EQUAL` comparison with application-owned runtime state. It retrieves empty
and recovered screenshots through opaque artifact receipts, and confirms clean process exit.

The exact isolated commands, original-resolution captures, red failures and current results are
retained in [the empty-fill verification report](../evidence/empty-fill/README.md).
