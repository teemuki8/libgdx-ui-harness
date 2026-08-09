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

Use the treatment-neutral candidate launcher already present in the template.
Compile and test with the authorized wrapper:

```text
../../../gradlew -p . classes --no-daemon --console=plain --warning-mode=fail
../../../gradlew -p . test --no-daemon --console=plain --warning-mode=fail
```

For each finite launch, write the bounded commands to `commands.ndjson`, choose
a fresh evidence directory, and run:

```text
../../../gradlew -p . run --args='--commands commands.ndjson --evidence evidence' --no-daemon --console=plain --warning-mode=fail
```

The local workflow is:

1. Implement or refine the candidate UI.
2. Compile, test, and launch it with the exact commands above.
3. Inspect process logs, `results.ndjson`, and the completed-frame PNGs under `captures/`.
4. Compare only against the public protocol, corpus, and approved reference observations, then refine the code.

Use `capture`, `resize`, `key`, and `pointer` commands to observe the declared states and transitions. End each finite run with `close`; preserve the complete evidence directory when a build, launch, interaction, or capture fails.
