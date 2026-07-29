# Benchmark Results Issue Publication Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Research the measured Agentic Palisade results and publish one evidence-grounded GitHub roadmap plus eight independently actionable product and benchmark issues.

**Architecture:** Treat the tracked Task 8 report and retained generated batch as evidence, candidate code/sessions as root-cause research inputs, and GitHub issues as the durable execution interface. Publish focused issues first, then link them from an outcome-first umbrella roadmap; distinguish observations from hypotheses in every body.

**Tech Stack:** GitHub Issues and labels through `gh`, Markdown, Java 25/libGDX Scene2D harness source, JSON benchmark/evaluator evidence.

## Global Constraints

- Do not claim statistical significance or general harness superiority from three matched pairs.
- Preserve the user-approved interpretation that only A's first rank and A-over-B preference are meaningful; B–F ordering is deterministic tie-breaking.
- Cite `.superpowers/sdd/palisade-agent-benchmark-plan/task-8-report.md` as durable evidence and retained `build/reports/agentic-palisade/20260729T181047Z` paths as local forensic coordinates.
- Mark causal statements as hypotheses unless candidate source, session events, or evaluator evidence establishes them.
- Every focused issue must contain Evidence, Problem, Root-cause hypotheses, Goal, Non-goals, Implementation surface, Acceptance criteria, Verification, and Dependencies.
- Create no more than the approved umbrella plus eight focused issues.

---

### Task 1: Research visual, semantic, workflow, and cost failures

**Files:**
- Read: `.superpowers/sdd/palisade-agent-benchmark-plan/task-8-report.md`
- Read: `build/reports/agentic-palisade/20260729T181047Z-final-report`
- Read: `build/reports/agentic-palisade/20260729T181047Z/runs/*/evaluation/evaluation.json`
- Read: `build/reports/agentic-palisade/20260729T181047Z/runs/*/run-record.json`
- Read: `build/reports/agentic-palisade/20260729T181047Z/runs/*/sessions/*.jsonl`
- Read: `build/reports/agentic-palisade/20260729T181047Z/runs/*/repository/benchmarks/agentic-palisade/template/src/**`
- Read: `harness-core/src/**`, `harness-scene2d/src/**`, `harness-transport/src/**`

**Interfaces:**
- Consumes: frozen benchmark identities, evaluator assertions, session/tool telemetry, final candidates, current public harness APIs.
- Produces: an evidence matrix for A/C/F harness runs and matched B/E/D baselines, with each finding classified as observed fact, source-established cause, or hypothesis.

- [ ] **Step 1: Build the evidence matrix**

Record per candidate: treatment, human result, 25-assertion outcome, visual defects, launches/builds/edits/failures/tokens/time, capture use, and repeated-capture stability.

- [ ] **Step 2: Trace semantic failures**

Map failed assertion groups—control metadata, initial state, visibility, scrolling, conditional controls, seed validation, and transitions—to candidate implementation and current harness API capabilities. Record whether each gap is candidate misuse, missing guidance, missing harness capability, or evaluator ambiguity.

- [ ] **Step 3: Trace visual failures**

Compare reference/A/C/E/D/F initial, bottom, and 1280 captures. Inspect typography setup, viewport/device scale, table sizing/padding, scroll pane clipping, coordinate conversion, skin styles, and repeat timing in final source and sessions.

- [ ] **Step 4: Trace workflow and cost failures**

Determine why all harness runs launched repeatedly but none invoked screenshot capture. Identify repeated unproductive loops and which existing tool responses did or did not report convergence evidence.

- [ ] **Step 5: Verify research coverage**

Require at least one cited observation for every approved focused issue and reject any issue whose acceptance criteria cannot be tied to a reproduced benchmark failure or explicit release outcome.

### Task 2: Define and create the issue label taxonomy

**Files:**
- External: GitHub repository labels

**Interfaces:**
- Consumes: existing default labels and approved design taxonomy.
- Produces: stable labels used by all nine issues.

- [ ] **Step 1: Confirm existing labels**

Run `gh label list --limit 100 --json name,description,color` and preserve default label meanings.

- [ ] **Step 2: Create area labels**

Create or update:

```text
area:harness          1D76DB  Core semantic harness and adapters
area:agent-workflow   5319E7  Agent-facing tools, guidance, and iteration loops
area:visual-fidelity  C5DEF5  Typography, layout, rendering, and perceptual fidelity
area:benchmark        0E8A16  Benchmark runner, evaluator, analysis, and release gates
```

- [ ] **Step 3: Create priority labels**

```text
priority:critical     B60205  Blocks the core product claim or trustworthy release qualification
priority:high         D93F0B  Material reliability or correctness improvement
priority:medium       FBCA04  Important follow-up that does not block initial reliability work
```

- [ ] **Step 4: Create type labels**

```text
type:feature          A2EEEF  New user-visible capability
type:research         D4C5F9  Investigation with a concrete decision or artifact
type:quality          006B75  Correctness, determinism, diagnostics, or quality gate
```

- [ ] **Step 5: Verify labels**

Read labels back through `gh label list` and require exact name, description, and color values.

### Task 3: Draft and publish the semantic and workflow issues

**Files:**
- External: GitHub issues

**Interfaces:**
- Consumes: Task 1 evidence matrix and Task 2 labels.
- Produces: issue URLs for semantic completeness and guided visual iteration.

- [ ] **Step 1: Publish semantic contract issue**

Title: `Make harness state and action contracts evaluator-complete`

Require a versioned semantic snapshot containing ordered control IDs, roles/kinds, accessible labels, options, defaults/current values, visibility/actionability, focus order/current focus, validation state/messages, conditional relationships, viewport/scroll state, and transition outcomes. Acceptance requires deterministic coverage of all 25 frozen assertion classes without candidate-specific evaluator knowledge and fail-closed diagnostics for missing fields.

Labels: `enhancement`, `area:harness`, `priority:critical`, `type:feature`, `type:quality`.

- [ ] **Step 2: Publish guided loop issue**

Title: `Make inspect–capture–compare the default agent UI iteration loop`

Require one bounded workflow/tool that records semantic inspection, full-frame capture, reference/current comparison, attributed differences, and convergence status. Acceptance requires a benchmark fixture proving an agent-facing invocation cannot report visual completion without a current capture and that stale captures fail closed.

Labels: `enhancement`, `area:agent-workflow`, `area:harness`, `priority:critical`, `type:feature`.

### Task 4: Draft and publish the visual fidelity issues

**Files:**
- External: GitHub issues

**Interfaces:**
- Consumes: Task 1 source-established typography/layout findings.
- Produces: typography and layout diagnostic issue URLs.

- [ ] **Step 1: Publish typography issue**

Title: `Add pixel-sharp typography and HiDPI diagnostics`

Acceptance must cover effective device scale, font source/size, glyph filtering, integer/fractional transforms, baseline alignment, weight, letter spacing, and raster residual; reproduce A's blur at 1920 and 1280 and provide a diagnostic that identifies the responsible transform/font setting.

Labels: `enhancement`, `area:visual-fidelity`, `area:harness`, `priority:high`, `type:quality`.

- [ ] **Step 2: Publish layout issue**

Title: `Diagnose layout, padding, scroll clipping, and viewport drift`

Acceptance must detect C/D/E/F overflow, wrong row geometry, content leaking across scroll boundaries, header/body coordinate drift, non-responsive 1280 layout, and A's unstable bottom scroll position; diagnostics must name the actor/control and expected versus observed bounds.

Labels: `bug`, `area:visual-fidelity`, `area:harness`, `priority:critical`, `type:quality`.

### Task 5: Draft and publish evaluator and analysis issues

**Files:**
- External: GitHub issues

**Interfaces:**
- Consumes: retained captures, automated metrics, human result, and session/evaluator evidence.
- Produces: usability evaluator and failure taxonomy issue URLs.

- [ ] **Step 1: Publish usability-sensitive evaluator issue**

Title: `Reject structurally unusable UIs that pass raster similarity metrics`

Acceptance must add structural signals for text legibility, control affordances, hierarchy, clipping/overflow, responsive composition, and scroll stability; C and F retained captures must fail the new gate while reference captures pass. Keep objective visual metrics and human judgment as separate channels.

Labels: `enhancement`, `area:benchmark`, `area:visual-fidelity`, `priority:high`, `type:quality`.

- [ ] **Step 2: Publish trace taxonomy issue**

Title: `Correlate agent traces with semantic, visual, and human outcomes`

Acceptance must define a machine-readable failure taxonomy, join immutable run/session/tool/capture/evaluator identities without treatment leakage before unblinding, and emit comparable per-run attribution for missing capture use, semantic omissions, rendering defects, and unproductive loops.

Labels: `enhancement`, `area:benchmark`, `area:agent-workflow`, `priority:medium`, `type:research`.

### Task 6: Draft and publish efficiency and repeatability issues

**Files:**
- External: GitHub issues

**Interfaces:**
- Consumes: Task 1 cost deltas and issue URLs from Tasks 3–5.
- Produces: convergence budget and release gate issue URLs.

- [ ] **Step 1: Publish convergence budget issue**

Title: `Add convergence signals and cost budgets to agent UI workflows`

Acceptance must report progress between iterations, reuse unchanged build/runtime state safely, cap unchanged inspect/build/launch cycles, and retain a terminal diagnostic. Re-run qualification must compare time, input tokens, edits, builds, and launches without collapsing semantic or visual quality channels.

Labels: `enhancement`, `area:agent-workflow`, `area:benchmark`, `priority:high`, `type:quality`.

- [ ] **Step 2: Publish repeatability gate issue**

Title: `Gate releases on repeatable A-like agentic UI outcomes`

Acceptance must define precommitted thresholds across semantic assertions, human-validated visual fidelity, capture repeatability, deterministic state transitions, and cost; require multiple independent matched runs; and forbid a single best-case candidate from satisfying the gate.

Labels: `enhancement`, `area:benchmark`, `area:harness`, `priority:critical`, `type:quality`.

### Task 7: Publish the umbrella roadmap

**Files:**
- External: GitHub issues

**Interfaces:**
- Consumes: eight focused issue URLs.
- Produces: one roadmap URL linking all work in dependency order.

- [ ] **Step 1: Create dependency-ordered checklist**

Title: `Roadmap: make A-like agentic UI outcomes repeatable`

Summarize the measured result without overstating n=3 evidence. Link semantic, guided loop, typography, and layout issues as parallel foundations; evaluator, taxonomy, and cost issues as measurement/optimization work; repeatability gate as the terminal release criterion.

- [ ] **Step 2: State roadmap completion criteria**

Require repeated harness runs to produce semantically correct, visually credible, stable outputs within a precommitted cost envelope, while benchmark channels remain separate and tamper-resistant.

Labels: `enhancement`, `documentation`, `area:harness`, `area:benchmark`, `priority:critical`.

### Task 8: Verify GitHub publication

**Files:**
- External: nine GitHub issues and label inventory

**Interfaces:**
- Consumes: published issue numbers and bodies.
- Produces: verified issue set ready for implementation.

- [ ] **Step 1: Read every issue from GitHub**

Use `issue://<number>` or `gh issue view <number> --json title,body,labels,url,state`. Confirm all required sections, labels, benchmark measurements, evidence paths, and dependency links.

- [ ] **Step 2: Check unsupported-claim boundaries**

Confirm each causal claim is source-established or marked as a hypothesis; confirm only A's human ordering is treated as meaningful.

- [ ] **Step 3: Check roadmap linkage**

Confirm the umbrella contains all eight focused issue URLs exactly once and that each focused issue links back to the umbrella or names its dependency relationship.

- [ ] **Step 4: Publish completion evidence**

Return the umbrella URL, eight focused URLs, labels created, research-derived priority order, and any residual uncertainty. Do not claim issue implementation is complete.
