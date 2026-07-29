# Agentic Palisade candidate execution

Work only in the supplied candidate workspace. Read `INSTRUCTIONS.md`, `PROTOCOL.md`, and `corpus/spec.json`; the corpus files and declared reference PNGs are the complete product evidence. Do not inspect parent directories, another run, evaluator code or fixtures, credentials, session data, caches, or hidden benchmark artifacts. Do not use network resources or ask for human intervention.

Implement the initial candidate first. After that initial implementation, perform exactly three implement-observe-refine cycles. At the start of each cycle invoke the fixed gate exactly once, in this exact order:

```text
benchmark-feedback 1
benchmark-feedback 2
benchmark-feedback 3
```

For each cycle, use only the gate output, the public corpus, the treatment instructions, your candidate's bounded build/launch output, and artifacts produced by this workspace. Repair the candidate after inspecting that evidence. Preserve failed builds, launches, commands, and captures as evidence; never replace them with an earlier successful artifact. Do not repeat, skip, reorder, or invoke a fourth gate. The gate is the machine-readable refinement-round marker.

Stay within the fixed candidate contract. Do not modify `INSTRUCTIONS.md`, `PROTOCOL.md`, `corpus/`, benchmark gate files, manifests, or telemetry. Finish without waiting for follow-up input.
