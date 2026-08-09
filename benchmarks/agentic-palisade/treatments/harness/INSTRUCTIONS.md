# Palisade candidate task

Implement `benchmark.palisade.SkirmishConfigurationUi` in the supplied candidate template. It must implement `CandidateUi` and reproduce the public Skirmish Configuration workflow described by `PROTOCOL.md` and `corpus/spec.json` at the approved viewports. Treat the committed protocol, corpus specification, and reference PNG observations as the complete product evidence.

Do not inspect a reference checkout, evaluator internals, non-public fixtures, capture infrastructure, or another treatment run. Do not change the protocol or corpus. Work within the candidate template, retain the fixed candidate contract, and use exactly three implement-observe-refine rounds.

Implement the public `CandidateState.values().stateAction` evidence contract on every completed frame. A visually working UI with omitted, partial, aliased, or differently nested functional evidence is incomplete. Structural evidence is measured independently from the completed Stage and framebuffer; candidate-authored structural claims do not substitute for those measurements.
Keep finite command files and other trace inputs outside Gradle's `build/` directory so a later `clean` cannot remove evidence referenced by the session trace.

Construct the complete UI only through the supplied `ui/skirmish.xml` and
`ui/skirmish.css` resources. Use `CandidateUi.bind(BuiltUi)` only to attach
bounded controller/state behavior to markup-declared actors. Do not construct a
parallel programmatic Stage or actor tree, add actors outside the shared markup
builder, or replace the fixed markup dependency and resource paths.

The runner installs the repository-owned Gradle Wrapper at `../../../gradlew`.
Executing that exact path is an authorized exception to the prohibition on
inspecting parent directories. Do not use a system Gradle installation, another
wrapper, or a network dependency source. Each treatment appendix gives the exact
compile, test, and finite launch commands for that arm; the harness overlay and
bridge are the only approved command difference.

## Treatment appendix

The generated harness overlay adds the digest-bound harness candidate supplied
for this qualification (`harness-lwjgl3` and `harness-mcp` plus their
transitive modules), adds the treatment bridge sources, and selects
`HarnessCli`. The isolated candidate Maven repository and exact version are
fixed before the run; do not substitute Maven Central or another local build.
Compile and test the candidate plus treatment bridge with the overlay:

```text
../../../gradlew -p . --init-script ../treatments/harness/build-overlay.gradle.kts classes --no-daemon --console=plain --warning-mode=fail
../../../gradlew -p . --init-script ../treatments/harness/build-overlay.gradle.kts test --no-daemon --console=plain --warning-mode=fail
```

For each finite launch, write the bounded MCP-equivalent requests to
`commands.ndjson` and run at one of the two closed corpus viewports:

```text
PALISADE_VIEWPORT=desktop-1920x1080 ../../../gradlew -p . --init-script ../treatments/harness/build-overlay.gradle.kts run --no-daemon --console=plain --warning-mode=fail < commands.ndjson
```

Omit the `PALISADE_VIEWPORT` assignment for the default `desktop-1280x720` launch.

Send one JSON object per line on standard input. Every object has exactly `operation` and `arguments`; the stable session is `candidate-ui`. The ten fixed operations are:

- `ui_sessions` with `{}`;
- `ui_capabilities`, `ui_snapshot`, and `ui_trace_stop` with `{"sessionId":"candidate-ui"}`;
- `ui_query` with a bounded semantic `locator`;
- `ui_action` with a locator and one allowlisted action;
- `ui_wait` with a locator and `present` or `visible`;
- `ui_screenshot` with the required pixel and PNG bounds;
- `ui_inspect_compare` with any canonical reference (`initial-1920x1080`,
  `bottom-1920x1080`, or `initial-1280x720`), policy `pixel-exact` version 1,
  the reference's exact viewport, and explicit iteration, duration, pixel, and PNG bounds;
- `ui_trace_start` with bounded duration and byte limits.

Locators and actions use the published V1 `kind` discriminator. For example, a role/name locator composes `{"kind":"role","role":"button"}` with a `{"kind":"name","match":{"mode":"exact","source":"START BATTLE"}}` filter. The CLI rejects extra envelope fields, unknown operations, class names, scripts, arbitrary commands, and artifact paths.

For each refinement round, implement or refine the candidate, then use semantic snapshot/query, action, event-driven wait, screenshot, and trace start/stop as needed. Use the first round to correct control identity, visibility, and bounds from semantic differences and attributed regions. Use the second to correct values, padding, clipping, and the bottom state after navigating there with real input. Use the third to inspect the bounded heatmap and typography diagnostics, correcting remaining native glyph-size or raster-scaling residuals. Re-run the affected canonical reference after every correction; a 1920x1080 reference requires the approved `PALISADE_VIEWPORT` launch value above. Inspect the emitted JSON, bounded artifacts, and process logs; compare only against the public protocol, corpus, and approved reference observations. Each opaque `artifact:<sha256>` reference corresponds to an immutable file under the generated `build/harness-artifacts/run-*/published/<sha256>` directory, which remains available after clean EOF. End standard input to close the application cleanly, and preserve the complete run directory on failure.
