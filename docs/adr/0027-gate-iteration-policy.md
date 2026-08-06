# ADR 0027: Gate iteration policy

## Status

Accepted

## Context

The repeatability release gate hard-rejected any model other than `openai-codex/gpt-5.6-sol:medium` and any per-run wall ceiling other than 45 minutes: `run-benchmark.py` refused to prepare or execute a qualification for any other `--model` or `--max-time`, and the precommitment schema carried no model field at all. Qualification was therefore single-model and slow — every iteration, including experiments on faster or cheaper models, paid a fixed 45-minute ceiling per run, and a schedule that was going to fail could not stop early. The evidence guarantees themselves are independent of that policy and must survive unchanged: every scheduled run counts toward the all-runs conjunction, arms are paired and randomized by the predeclared matched-pair method, and the human blind-review gate still decides usability.

## Decision

The model becomes a sealed environment-stratum identity. The precommitment schema requires a non-empty `model` on every `environments[]` entry, the runner writes the requested model into the stratum it seals, and the gate verifies that every completed repetition's stratum declares one identical model. The gate scopes its claim to the qualified model: the decision names the model whose evidence it covers.

Schedules may seal `failFast: true`. When the precommitment declares fail-fast, the supervisor evaluates the sealed decision criteria after every completed run and cancels all remaining scheduled arms as soon as the all-runs conjunction is unrecoverable. Each cancelled arm is recorded in the manifest with its status and a reason naming the triggering run and classification; the gate accepts a cancelled arm only when the schedule declared fail-fast and that triggering failure is present in the manifest. Any other cancelled or missing run fails the gate exactly as a missing run.

The per-run wall ceiling becomes a sealed per-arm parameter instead of a fixed constant. `--max-time` accepts any value of at least 10 minutes and is sealed per arm into the existing `resourceLimits.wallSeconds` and `costCeilings.wallTimeMillis`; the prepare default becomes 40 minutes, derived from the retained ten-run distribution of measured durations (838–2125 s) with margin. Lower ceilings are permitted and encouraged for faster models, and a misjudged ceiling is cheap under fail-fast.

## Consequences

Any model can now qualify, so iteration against faster or cheaper models is cheap and each qualification is self-contained. A failing schedule costs one run: fail-fast cancels the remaining arms once a required run fails, and the gate accepts the cancellation only when the schedule precommitted the rule and the triggering failure is recorded. Passing schedules are unchanged: fail-fast is opt-in, and a schedule that never declares it behaves exactly as before. Historical evidence is model-scoped and not reusable across model changes: evidence qualified under one model does not carry over, and re-qualification is the documented path whenever the model changes.
