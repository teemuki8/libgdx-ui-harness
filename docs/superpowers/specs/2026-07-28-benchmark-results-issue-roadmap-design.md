# Benchmark Results Issue Roadmap Design

## Purpose

Convert the measured Agentic Palisade benchmark into an executable GitHub roadmap. The roadmap must preserve the distinction between observed evidence and root-cause hypotheses, prioritize product outcomes over implementation subsystems, and include benchmark work needed to measure progress honestly.

## Source evidence

The authoritative measured evidence is:

- `.superpowers/sdd/palisade-agent-benchmark-plan/task-8-report.md`;
- ignored local batch `build/reports/agentic-palisade/20260729T181047Z`;
- sealed review package `build/reports/agentic-palisade/20260729T181047Z-blind-review`;
- final unblinded report `build/reports/agentic-palisade/20260729T181047Z-final-report`.

The benchmark supports these conclusions:

1. Harness access changed agent behavior: harness runs built and launched repeatedly; baseline runs did not launch.
2. A harness run produced A, the only visually credible candidate.
3. The outcome was unreliable: C and F also used the harness but were unusable.
4. Every harness candidate passed only 1/25 hidden functional assertions.
5. No measured agent invoked screenshot capture.
6. Harness runs cost more time, tokens, edits, builds, reads, and launches.
7. Raster similarity metrics rated several unusable outputs favorably.
8. A still showed blurry text, padding drift, and unstable bottom-state scroll positioning.

With three matched pairs, findings are directional. Issues must not claim statistical significance or general superiority.

## Roadmap structure

Create one umbrella roadmap and eight focused outcome issues:

1. **Semantic state and action contract** — make the harness expose and verify enough control metadata, values, visibility, validation, focus, conditional behavior, and transitions for candidates to satisfy the frozen semantic contract.
2. **Guided inspect–capture–compare loop** — make visual observation an explicit, verifiable agent workflow rather than an optional tool that all measured agents ignored.
3. **Pixel-sharp typography and HiDPI diagnostics** — detect and explain font scaling, filtering, weight, letter-spacing, and device-scale errors.
4. **Layout, padding, scroll, and viewport diagnostics** — surface clipping, overflow, coordinate-space, responsive-layout, and repeatability defects with semantic attribution.
5. **Usability-sensitive visual evaluation** — augment raster metrics with structural and perceptual failure signals that reject C/F-like false positives.
6. **Convergence and cost budgets** — measure progress per iteration and stop unproductive build/launch loops while retaining evidence.
7. **Failure taxonomy and trace-to-outcome analysis** — turn sessions, tool calls, semantic snapshots, captures, evaluator assertions, and human outcomes into a comparable failure dataset.
8. **Repeatability release gate** — require multiple independent runs to meet semantic, visual, stability, and cost thresholds before claiming Playwright-level agentic precision.

The umbrella issue summarizes the evidence, orders the work, links every focused issue, and defines the overall success condition: A-like visual quality must become repeatable without 1/25 functional outcomes or the current cost multiplier.

## Issue documentation contract

Every focused issue must contain:

- **Evidence:** observed benchmark facts, candidate labels, exact measurements, and repository evidence paths. Candidate labels may be unblinded because the review is complete.
- **Problem:** the user-visible or measurement failure, not an assumed implementation defect.
- **Root-cause hypotheses:** plausible causes clearly labeled as hypotheses until code/session research establishes them.
- **Goal:** one measurable outcome.
- **Non-goals:** adjacent work deliberately excluded.
- **Implementation surface:** likely modules and public contracts, updated after code research.
- **Acceptance criteria:** observable behavior, boundary cases, and failure behavior.
- **Verification:** a focused deterministic scenario plus the benchmark gate when applicable.
- **Dependencies:** links to prerequisite or follow-up issues.

Issue bodies must be self-contained. Local ignored build paths are evidence coordinates for maintainers with the retained batch, while the tracked Task 8 report provides durable public context.

## Labels

Retain GitHub’s existing default labels and add a small namespaced set:

- Areas: `area:harness`, `area:agent-workflow`, `area:visual-fidelity`, `area:benchmark`
- Priority: `priority:critical`, `priority:high`, `priority:medium`
- Types: `type:feature`, `type:research`, `type:quality`

Apply existing `enhancement`, `bug`, and `documentation` labels only where their established meanings fit. The umbrella receives `enhancement`, `area:harness`, `area:benchmark`, and `priority:critical`.

## Dependency order

1. Semantic contract, guided visual loop, typography diagnostics, and layout diagnostics can begin independently.
2. Visual evaluator work consumes the typography/layout failure vocabulary but can prototype against retained captures.
3. Cost budgets and failure taxonomy consume workflow telemetry and can proceed after event contracts are understood.
4. The repeatability gate depends on the product diagnostics and evaluator thresholds.

GitHub issue links express these dependencies. The umbrella checklist records execution status; focused issues remain independently closable.

## Publication and verification

1. Research candidate implementations, session behavior, evaluator assertions, current harness APIs, and existing labels before finalizing issue bodies.
2. Create missing labels with stable descriptions and colors.
3. Create focused issues first so the umbrella can link their final numbers.
4. Create the umbrella issue last with a dependency-ordered checklist.
5. Read every published issue through GitHub and verify title, body, labels, cross-links, acceptance criteria, and absence of unsupported claims.
6. Record the created issue URLs in the final response.
