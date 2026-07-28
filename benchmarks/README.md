# Playwright semantic parity benchmark

This benchmark compares the production stdio MCP harness against a pinned Playwright implementation of the same ten semantic scenarios. It executes real hidden LWJGL3 processes and real Chromium contexts; it does not load precomputed outcomes. The Playwright page is created with `page.setContent`, so no HTTP server or non-loopback listener is opened.

## Pinned environment

The checked-in npm lock pins `@playwright/test`, `playwright`, and `playwright-core` to 1.61.1 with integrity hashes. That release pins Chromium revision 1228. The measured workstation used:

- Java 25.0.3
- Node v24.18.0 and npm 12.0.1
- Linux 7.1.3-200.nobara.fc44.x86_64 amd64
- Chromium in headed mode on the existing `DISPLAY=:0`
- no `xvfb-run` installation

Install only the locked npm graph:

```bash
npm ci --prefix benchmarks/playwright
```

Playwright's browser binary must be the revision pinned by that package. If the cache does not already contain it, install that exact revision with:

```bash
npm exec --prefix benchmarks/playwright -- playwright install chromium
```

## Reproduce

From the repository root, run the focused formula and threshold tests, reinstall the exact npm graph, and execute all 10 scenarios 20 times per system:

```bash
./gradlew :benchmarks:test --tests '*StatisticsTest'
npm ci --prefix benchmarks/playwright
DISPLAY=:0 ./gradlew :benchmarks:run --args='--runs 20 --output build/reports/parity'
```

On a host that actually provides `xvfb-run`, the equivalent isolated-display command is:

```bash
xvfb-run -a ./gradlew :benchmarks:run --args='--runs 20 --output build/reports/parity'
```

The runner refuses to mix a new execution with existing raw JSON. Choose a fresh output directory or remove only the prior benchmark-owned output first.

## Corpus and symmetry

`corpus/scenarios.json` is a strict schema-versioned, ordered definition of exactly:

1. sign-in
2. ambiguous locator recovery
3. delayed enablement
4. moving target
5. obscured target
6. scroll-and-select
7. modal dialog
8. actor replacement
9. screenshot diagnosis
10. intentional failure trace

Both interpreters consume the same ordered steps and use the same exact label, text, test-ID, and role-plus-accessible-name locators. Both use a fixed 96 ms logical delay. The Scene2D reference expresses it as six deterministic 16 ms stage steps; the local page uses the same fixed duration for enablement, movement, and interception. Each system uses a 500 ms semantic action deadline and starts a trace before scenario steps. Every expected failure declares an explicit shared category: the harness must return the exact `strictness-violation` code and exact `details.matchCount` evidence, while Playwright must throw the declared error class with the `strict mode violation` message category. A timeout or unrelated exception fails the scenario. The intentional-failure scenario is complete only when that exact failure diagnostic, screenshot, and trace all exist.

Median tool calls are descriptive only. The harness number counts production MCP `tools/call` requests. The Playwright number counts equivalent semantic step/assertion calls; setup and browser/context lifecycle are excluded from both.

## Atomic raw records and derived output

A run is fsynced to a temporary file and atomically renamed under `raw/<system>/` before the next run begins. Aggregation reads those files back strictly and rejects malformed, duplicate, missing, or unexpected identities. It also correlates each claim to the deterministic system/scenario/run artifact path, requires exact byte size plus ZIP/PNG signature, requires every claimed trace and screenshot, and rejects unclaimed extras. For 20 runs, it requires exactly 200 harness records and 200 Playwright records. The Playwright child is supervised with concurrent bounded stdout/stderr drains; on its 20-minute deadline the complete process tree is killed before drain threads are joined, so an open child pipe cannot bypass the timeout.

The output directory contains:

- `raw/harness/*.json` and `raw/playwright/*.json`: one immutable observation per scenario/run
- `raw-results.json`: ordered 400-record document derived from the raw files
- `aggregate.csv`: per-system and per-scenario counts, rates, medians, repeatability, and trace bytes
- `verdict.json`: machine-readable environment, Wilson intervals, fixed thresholds, failures, and PASS/FAIL
- `traces/harness/*.zip` and `traces/playwright/*.zip`: the actual 200 traces per system
- `evidence/`: requested screenshots and any unexpected-failure screenshots

Each raw record includes completion, timeout/flaky classification, semantic call count, actionable-evidence status, elapsed duration, screenshot bytes, trace bytes, repeatability key, full diagnostics, and error detail. Repeatability compares normalized semantic outcomes and error codes, not unstable JSON member order or elapsed time.

## Fixed verdict

For aggregate harness result $H$ and Playwright result $P$, V1 passes only when all of these hold:

- $H_{completion} \ge P_{completion}$
- $H_{actionable} \ge P_{actionable}$
- $H_{timeout/flaky} \le U_{Wilson95}(P_{timeout/flaky})$

`Statistics.wilsonInterval` uses the two-sided 95% Wilson score interval with $z=1.959963984540054$. Thus the allowed addition to Playwright's observed timeout/flaky rate is exactly its Wilson upper bound minus its observed rate. Median calls never change the verdict. Any threshold failure exits 1; malformed corpus/raw data, missing/corrupt/mismatched artifacts, child-process failure, or output publication failure exits 2.

To re-aggregate already persisted raw records without executing either system:

```bash
DISPLAY=:0 ./gradlew :benchmarks:run --args='--runs 20 --output build/reports/parity --aggregate-only'
```

This path is also the fail-closed threshold check: copy a completed output to a scratch directory, change one raw harness observation to a genuine injected failed observation while keeping its failure fields internally consistent, then run `--aggregate-only` against the scratch copy. The unchanged threshold must emit `PARITY_VERDICT FAIL` and exit nonzero.
