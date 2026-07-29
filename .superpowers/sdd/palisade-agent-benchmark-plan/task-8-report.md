# Task 8 report: measured Agentic Palisade benchmark

## Status

Complete. The amended immutable batch produced six successful agent processes, each candidate was evaluated exactly once, the A–F package was leakage-scanned, the human response was locked before unblinding, and the final report was generated.

The retained pre-amendment authentication abort at `build/reports/agentic-palisade/20260729T173223Z` remains separate infrastructure evidence and was not evaluated or analyzed.

## Protocol and environment

- Measured root: `build/reports/agentic-palisade/20260729T181047Z`
- Protocol amendment: `agentic-palisade/task-8-auth-broker-amendment-v1`
- Model: `openai-codex/gpt-5.6-sol:medium`
- Reasoning: medium
- Design: three matched pairs; baseline and harness launched concurrently
- Limit: 45 minutes per run, three feedback rounds
- Environment identity retained or directly derivable from generated benchmark evidence: isolated X11 displays `:220` through `:225`; Gradle wrapper 9.6.1; libGDX/LWJGL3 fixture dependencies 1.14.2.
- Post-run workstation observations, not hash-bound fields in the immutable benchmark manifest: Nobara Linux kernel `7.1.3-200.nobara.fc44.x86_64`; AMD Ryzen 9 3950X (16 cores / 32 threads); NVIDIA GeForce RTX 4080 SUPER, driver 595.84; Red Hat OpenJDK 25.0.3+9; OMP 17.1.7.
- External qualification observation, also outside the immutable benchmark report: GitHub Actions run `30481881576` initially encountered an external CodeQL HTTP 429; its failed-job rerun exited successfully.

Frozen identities from `benchmark-manifest.json`:

- corpus: `bf1923272490955641ea51b4102e9a1c2b1eb761972d0aee6b835e505f455f97`
- prompt: `a20c0dc5a56ed81db56209e8588927863cb2574f724a9bdb7e02064289b8bd2e`
- protocol: `b72cbe110c8198226681926f8f011e4e2d24cc81cae6f87b9da8c96a871cfa2f`
- candidate template: `adeb771bfdc639c311e402e10874be8d9f1aeb3198750be5a2ceba280b8ffa00`
- harness overlay: `685bfd091462dc6c8ac0b9d6b4fc7f3a4e2a82de0d2d2c866fbfcce0df6c5445`

## Execution and evidence

The amended measured command exited zero after 25 minutes:

```text
{"status":"complete","runs":6,"successful":6,"output":".../build/reports/agentic-palisade/20260729T181047Z"}
```

Each run retains its input manifest, immutable repository/workspace, OMP stdout/stderr, exported session, round evidence and hashes, cache/profile isolation, final run record and sidecar. Trusted evaluation directories contain one `evaluation.json`, its SHA-256 sidecar, and hash-bound evidence/captures. All evaluator processes exited zero and reported `complete`.

The sealed public package is `build/reports/agentic-palisade/20260729T181047Z-blind-review`. Its manifest SHA-256 is `5c85f7a75e508664227d5575db49aab8073596c1c9e96785f3a1007a8dddff40`. The response SHA-256 is `a8775d7a52fe78775a1d67b3f2cd767595bda0d9be389ee93d22a84c670bd52c`. The verified unblinded output is `build/reports/agentic-palisade/20260729T181047Z-final-report`.

## Unblinded mapping and results

| Pair | Baseline | Harness | Functional baseline | Functional harness | Human fidelity baseline | Human fidelity harness |
|---|---|---|---:|---:|---:|---:|
| 1 | B | A | 5/25 | 1/25 | 1 | 5 |
| 2 | E | C | 0/25 | 1/25 | 1 | 1 |
| 3 | D | F | 4/25 | 1/25 | 1 | 1 |

Human judgment: A was “miles better than every other candidate,” although it still had blurry text and padding defects. B through F were all unusable and not meaningfully distinguishable. The response schema requires a unique total rank and a preference in every pair, so the user explicitly approved an alphabetical deterministic tie-break for B–F. Consequently, only A's first-place rank, fidelity 5, and A-over-B preference carry qualitative ordering information. C-over-E, D-over-F, and ranks 2–6 must not be interpreted as observed preferences.

Functional evaluator results did not reproduce A's visual advantage. Baseline passed counts were 5, 0, and 4 (median 4); harness passed exactly 1/25 in every pair. Paired harness-minus-baseline deltas were -4, +1, and -3 assertions.

All harness agents exercised the instrumented application path: 9–11 launches, 7–11 builds, and 14–17 edits. Baseline agents recorded zero launches, zero or one build, and 5–10 edits. No agent in either arm used the screenshot command; the final visual captures were produced later by the frozen evaluator. Harness agents had fewer failed operations in every pair, but used substantially more work:

- wall time by pairs 1–3: +482s, +407s, +754s;
- input tokens by pairs 1–3: +116,510, +67,389, +164,941;
- edits by pairs 1–3: +10, +7, +7;
- launches by pairs 1–3: +11, +9, +11.

Automated visual metrics were mixed for A versus B. Harness candidates C and F improved most SSIM, edge, bounds, and RGB metrics relative to their matched baselines, yet both were rated unusable. This divergence is evidence that the objective metrics captured raster similarity, not product-level usability.

## Interpretation

The benchmark does not establish that the harness is generally better than baseline. With only three pairs, results are directional. The strongest supported findings are:

1. The harness changed agent behavior decisively: harness agents built and launched the UI repeatedly, while baseline agents never launched it.
2. That behavioral access can produce a major visual win: A was the only usable-looking candidate and was a harness run.
3. The result was not reliable: two of three harness candidates were unusable, and every harness candidate passed only 1/25 hidden functional assertions.
4. Current harness-driven iteration is expensive: every harness run consumed more time, input tokens, reads, edits, builds, and launches.
5. Raster similarity is insufficient as a usability proxy. C and F scored well on several automated metrics without surviving human inspection.
6. The immediate product gap is not access to execution; it is directing agents toward stable semantic correctness, text sharpness, spacing, and required state transitions while using the available inspect/capture loop.

The project goal therefore remains unmet at this revision. The benchmark did expose a promising ceiling (A) and a concrete reliability problem: make A-like outcomes repeatable without the current functional regressions and cost multiplier.

## Verification

Observed commands and outcomes:

```text
./gradlew -p benchmarks/agentic-palisade/evaluator installDist --no-daemon --console=plain
BUILD SUCCESSFUL

six trusted evaluator CLI invocations
all exit 0; statuses complete; passed counts 5, 1, 0, 1, 4, 1

python3 benchmarks/agentic-palisade/scripts/build-blind-review.py ...
{"status":"built","candidates":6,...}

python3 benchmarks/agentic-palisade/scripts/unblind-report.py lock ...
{"status":"locked",...}

python3 benchmarks/agentic-palisade/scripts/unblind-report.py unblind ...
{"status":"unblinded",...}
```
