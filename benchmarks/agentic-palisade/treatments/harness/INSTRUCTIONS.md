# Palisade candidate task

Implement `benchmark.palisade.SkirmishConfigurationUi` in the supplied candidate template. It must implement `CandidateUi` and reproduce the public Skirmish Configuration workflow described by `PROTOCOL.md` and `corpus/spec.json` at the approved viewports. Treat the committed protocol, corpus specification, and reference PNG observations as the complete product evidence.

Do not inspect a reference checkout, evaluator internals, non-public fixtures, capture infrastructure, or another treatment run. Do not change the protocol or corpus. Work within the candidate template, retain the fixed candidate contract, and use exactly three implement-observe-refine rounds.

## Treatment appendix

The generated harness overlay adds `io.github.teemuki8:harness-lwjgl3:1.0.0` and `io.github.teemuki8:harness-mcp:1.0.0`, adds the treatment bridge sources, and selects `HarnessCli`. Start it with the overlay from the candidate template:

```text
../../../gradlew -p . --init-script ../treatments/harness/build-overlay.gradle.kts run
```

Send one JSON object per line on standard input. Every object has exactly `operation` and `arguments`; the stable session is `candidate-ui`. The nine fixed operations are:

- `ui_sessions` with `{}`;
- `ui_capabilities`, `ui_snapshot`, and `ui_trace_stop` with `{"sessionId":"candidate-ui"}`;
- `ui_query` with a bounded semantic `locator`;
- `ui_action` with a locator and one allowlisted action;
- `ui_wait` with a locator and `present` or `visible`;
- `ui_screenshot` with the required pixel and PNG bounds;
- `ui_trace_start` with bounded duration and byte limits.

Locators and actions use the published V1 `kind` discriminator. For example, a role/name locator composes `{"kind":"role","role":"button"}` with a `{"kind":"name","match":{"mode":"exact","source":"START BATTLE"}}` filter. The CLI rejects extra envelope fields, unknown operations, class names, scripts, arbitrary commands, and artifact paths.

For each refinement round, implement or refine the candidate, then use semantic snapshot/query, action, event-driven wait, screenshot, and trace start/stop as needed. Inspect the emitted JSON, bounded artifacts, and process logs; compare only against the public protocol, corpus, and approved reference observations. Each opaque `artifact:<sha256>` reference corresponds to an immutable file under the generated `build/harness-artifacts/run-*/published/<sha256>` directory, which remains available after clean EOF. End standard input to close the application cleanly, and preserve the complete run directory on failure.
