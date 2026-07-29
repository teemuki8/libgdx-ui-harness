# Palisade candidate task

Implement `benchmark.palisade.SkirmishConfigurationUi` in the supplied candidate template. It must implement `CandidateUi` and reproduce the public Skirmish Configuration workflow described by `PROTOCOL.md` and `corpus/spec.json` at the approved viewports. Treat the committed protocol, corpus specification, and reference PNG observations as the complete product evidence.

Do not inspect a reference checkout, evaluator internals, non-public fixtures, capture infrastructure, or another treatment run. Do not change the protocol or corpus. Work within the candidate template, retain the fixed candidate contract, and use exactly three implement-observe-refine rounds.

## Treatment appendix

Use the treatment-neutral local workflow already present in the template:

1. Implement or refine the candidate UI.
2. Build and launch it with a finite bounded command file and an evidence directory.
3. Inspect process logs, `results.ndjson`, and the completed-frame PNGs under `captures/`.
4. Compare only against the public protocol, corpus, and approved reference observations, then refine the code.

Use `capture`, `resize`, `key`, and `pointer` commands to observe the declared states and transitions. End each finite run with `close`; preserve the complete evidence directory when a build, launch, interaction, or capture fails.
