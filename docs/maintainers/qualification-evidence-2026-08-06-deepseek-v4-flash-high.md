# Qualification Evidence: deepseek-v4-flash at high reasoning (2026-08-06)

Date: 2026-08-06
Status: Recorded; the model is not viable for the release-qualification corpus at practical per-run ceilings.
Raw data: `build/reports/agentic-palisade/qualification-2026-08-06-deepseek-v4-flash-high-15m/` and
`...-60m/` (retained per PROTOCOL failure-retention rules; not committed).

## Setup

- Candidate: `1.1.0-candidate.afefc67b683f` (six publishable modules via `publishToMavenLocal`).
- Model: `deepseek/deepseek-v4-flash`, reasoning `high` (the only faster DeepSeek tier; `max` is slower).
- Schedule: 5 pairs x 2 arms = 10 runs, 3 rounds each, release-candidate mode, arms in parallel on
  private Xvfb displays, auth preflight passed, fail-fast armed.
- Ceilings attempted: 15 minutes and 60 minutes.

## Measured results

15-minute ceiling (`...-15m/`, 10/10 run records):
- Every run `timed_out` at exactly 900 s (SIGTERM). 0 launches, 0 completed rounds anywhere.
- Tool cadence ~1 call / 18 s (~45-50 bash + 20-36 reads per run); the agents were still in
  round-1 exploration/build setup at the deadline.

60-minute ceiling (`...-60m/`, 10/10 run records):
- Every run `timed_out` at exactly 3600 s (SIGTERM).
- Best run: 25 builds, 19 launches, 0 of 3 rounds completed. One run completed round 1 of 3.
- 4 of 10 runs made 0 builds (never launched) despite 90-140 tool calls.
- Candidate build verified sound independently: the instructed
  `gradlew -p template --init-script ../treatments/harness/build-overlay.gradle.kts test`
  builds and passes in 14 s, so the zero-build runs were agent failure, not a broken candidate.

## Conclusions

- The harness-side machinery worked end to end with a non-Codex model: auth preflight, sealed
  per-run ceiling enforcement, classification (`timed_out`), fail-fast cancellation
  (`cancellations.json` written, exit 1), and complete run records with telemetry.
- deepseek-v4-flash at high reasoning is 5-10x slower end-to-end than the corpus baseline
  (openai-codex/gpt-5.6-sol:medium completed the same corpus in 14-35 min per run). At the
  observed pace a full 3-round run needs on the order of 180 minutes, and 40% of runs cannot
  reach the build phase even at 60 minutes.
- A raised ceiling does not recover iteration speed; the model choice is the bottleneck.

## Options recorded for the next attempt

- Switch the stratum model to a broker-served faster tier (claude-sonnet-4.5 / haiku via
  gitlab-duo) at a 30-45 minute ceiling.
- Keep deepseek-v4-flash at high reasoning with a ~180-minute ceiling (deepseek-specific
  evidence only; slowest option).
- Reduce per-run work (scenario count or rounds) so the model fits a practical run; requires a
  benchmark-design change (corpus/schedule), not a ceiling change.

Historical evidence remains model-scoped: this record does not invalidate the retained Codex
batch, and the Codex batch does not validate this model.
