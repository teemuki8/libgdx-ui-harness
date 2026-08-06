# Benchmark Profiles Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add sealed benchmark profiles to the agentic-palisade qualification: `low-confidence` becomes the release gate (3 pairs, 2 rounds, 1 repetition schedule, lowered thresholds, model must support image input); `high-confidence` preserves the current strict requirements as a supported-but-optional profile.

**Architecture:** The precommitment seals a `profile` field; `release-gate.py` resolves all thresholds from a `PROFILES` table keyed by that sealed value; `run-benchmark.py` accepts `--profile` (default `low-confidence`), validates the model's image capability before any schedule is produced, and records the profile. The CI workflow is unchanged (it reads the sealed profile).

**Tech Stack:** Python 3 stdlib, JSON Schema, unittest suites run directly (`python3 benchmarks/agentic-palisade/scripts/test-*.py` — pytest is broken against Python 3.14 in this environment).

## Global Constraints

- Spec: `docs/superpowers/specs/2026-08-06-benchmark-profiles-design.md` (Approved 2026-08-06, includes the model image-capability requirement).
- The profile is read from the **sealed precommitment** only; no CLI override at verify time.
- Shared invariants in BOTH profiles (never profile-tunable): all-runs conjunction, fail-fast, seal fixity, retained negative controls (A/C/D/E/F).
- `scenarioAssertionGroups` stays 25 in the precommitment; the profile changes the pass-rate threshold applied to evidence, not the precommitted count.
- Both profiles require image-capable models (`modelImagesRequired: true`). Authoritative image capability: `omp models` images column. Verified: `deepseek/deepseek-v4-flash` and `deepseek/deepseek-v4-pro` = `no`; gitlab-duo claude models and `openai-codex/gpt-5.6-sol:medium` = `yes`.
- TDD: every behavior change starts with a failing test.
- The benchmark test suites run via their `unittest` main guards: `python3 benchmarks/agentic-palisade/scripts/test-runner.py` and `test-release-gate.py`. Do NOT use pytest.

---

### Task 1: Schema — sealed `profile` field

**Files:**
- Modify: `benchmarks/agentic-palisade/scripts/schemas/repeatability-precommitment.schema.json`
- Modify: `benchmarks/agentic-palisade/scripts/test-release-gate.py`

**Interfaces:**
- Produces: precommitment requires `profile` in `["low-confidence", "high-confidence"]`; consumed by Tasks 2–3.

- [ ] **Step 1: Write the failing test**

In `test-release-gate.py`, extend the schema tests (the file already loads the schema via `json.loads((SCRIPT.parent / 'schemas' / ...).read_text())` and validates with `jsonschema.Draft202012Validator`):
```python
def test_precommitment_schema_requires_profile(self):
    validator = load_schema("repeatability-precommitment.schema.json")
    without_profile = sealed_fixture_without("profile")
    self.assertFalse(validator.is_valid(without_profile))
    with_profile = sealed_fixture_with("profile", "low-confidence")
    self.assertTrue(validator.is_valid(with_profile))
    bad_profile = sealed_fixture_with("profile", "medium-confidence")
    self.assertFalse(validator.is_valid(bad_profile))
```

- [ ] **Step 2: Run to verify failure**

Run: `python3 benchmarks/agentic-palisade/scripts/test-release-gate.py`
Expected: FAIL — schema accepts the profile-less precommitment.

- [ ] **Step 3: Implement the schema**

Add to the precommitment schema top-level properties and required list:
```json
"profile": {"type": "string", "enum": ["low-confidence", "high-confidence"]}
```
Add `"profile"` to the top-level `required` array. Update the existing test fixtures that construct precommitments to include `"profile": "high-confidence"` (the current strict requirements are the high-confidence bundle), matching Task 2's table.

- [ ] **Step 4: Run to verify pass**

Run: `python3 benchmarks/agentic-palisade/scripts/test-release-gate.py`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add benchmarks/agentic-palisade/scripts/schemas/repeatability-precommitment.schema.json benchmarks/agentic-palisade/scripts/test-release-gate.py
git commit -m "feat(benchmark): seal profile in the repeatability precommitment"
```

---

### Task 2: Gate — PROFILES threshold table

**Files:**
- Modify: `benchmarks/agentic-palisade/scripts/release-gate.py`
- Modify: `benchmarks/agentic-palisade/scripts/test-release-gate.py`

**Interfaces:**
- Consumes: Task 1 `profile` field.
- Produces: `PROFILES` dict at module level with keys `"low-confidence"` and `"high-confidence"`; the gate resolves every threshold from the sealed profile. Consumed by Task 3 (runner) for schedule defaults and the image-capability flag.

- [ ] **Step 1: Write the failing test**

In `test-release-gate.py`, add cases that run the full `verify()` with a low-confidence precommitment/manifest where the high-confidence checks would fail but the low-confidence ones pass:
```python
def test_low_confidence_profile_applies_lowered_thresholds(self):
    # fixture with profile low-confidence, 1 reviewer, fidelity 3, semantic pass-rate 0.6,
    # 3 PNG digests, 3 pairs, 2 rounds, 1 repetition -> verify() reports no failures
def test_high_confidence_profile_still_strict(self):
    # the existing strict fixture (profile high-confidence) must keep passing
    # and a low-confidence-shaped fixture under profile high-confidence must fail
```
Read the existing fixture builders and the verify() signature first; adapt the fixture shapes to carry `profile`, the reviewer/fidelity fields, the semantic pass evidence, and the digest sets.

- [ ] **Step 2: Run to verify failure**

Run: `python3 benchmarks/agentic-palisade/scripts/test-release-gate.py`
Expected: FAIL — no profile resolution exists.

- [ ] **Step 3: Implement the PROFILES table and threshold resolution**

In `release-gate.py`, add after the constants:
```python
PROFILES = {
    "low-confidence": {
        "modelImagesRequired": True,
        "pairs": 3,
        "rounds": 2,
        "requiredRepetitions": 1,
        "semanticPassRate": 0.6,
        "pngDigests": 3,
        "settlingFrames": 3,
        "reviewers": 1,
        "medianFidelity": 3,
        "unusableMajority": True,
        "costInputTokens": 500_000,
        "costBuilds": 10,
        "costLaunches": 30,
        "wallSeconds": 40 * 60,
    },
    "high-confidence": {
        "modelImagesRequired": True,
        "pairs": 5,
        "rounds": 3,
        "requiredRepetitions": 2,
        "semanticPassRate": 1.0,
        "pngDigests": 5,
        "settlingFrames": 3,
        "reviewers": 2,
        "medianFidelity": 5,
        "unusableMajority": True,
        "costInputTokens": 1_000_000,
        "costBuilds": 100,
        "costLaunches": 100,
        "wallSeconds": None,  # per-arm sealed value, unchanged
    },
}
```
Replace the hard-coded thresholds in `verify()`:
- `scenarioAssertionGroups != 25` stays (precommitted count); add a per-repetition semantic pass-rate check `passed >= rate * 25` with `rate = profile["semanticPassRate"]`.
- Human channel: `len(reviewers) < profile["reviewers"]`, `_median(ratings) < profile["medianFidelity"]`, and the majority-unusable check gated by `profile["unusableMajority"]`.
- Cross-schedule digest equality (canonical states / transition hashes / capture sets): only when the number of non-cancelled repetitions is >= `profile["requiredRepetitions"]`.
- PNG digest count per observation: `profile["pngDigests"]`.
- Cost ceilings: compare against `profile["costBuilds"]`/`profile["costLaunches"]`/`profile["costInputTokens"]` (per-run); `wallSeconds: None` keeps the current per-arm behavior.
- Resolve `profile = PROFILES[precommitment.get("profile", "high-confidence")]` once at the top of `verify()` and fail with `"Unknown profile"` if absent from `PROFILES`.

- [ ] **Step 4: Run to verify pass**

Run: `python3 benchmarks/agentic-palisade/scripts/test-release-gate.py`
Expected: PASS (existing strict cases + the two new profile cases).

- [ ] **Step 5: Commit**

```bash
git add benchmarks/agentic-palisade/scripts/release-gate.py benchmarks/agentic-palisade/scripts/test-release-gate.py
git commit -m "feat(benchmark): profile-resolved gate thresholds"
```

---

### Task 3: Runner — `--profile` and model image capability

**Files:**
- Modify: `benchmarks/agentic-palisade/scripts/run-benchmark.py`
- Modify: `benchmarks/agentic-palisade/scripts/test-runner.py`

**Interfaces:**
- Consumes: Task 2 profile keys and `modelImagesRequired` (the runner duplicates the two profile names and the image-capability flag as a small local constant set — the runner must stay standalone; document the duplication).
- Produces: `--profile` (default `low-confidence`), recorded in the benchmark manifest as `"profile"`; schedule defaults for `--pairs` and the prepare default ceiling derived from the profile; image-capability validation of `--model` before any prepare/execute. Consumed by the manual precommitment builder (docs) and Task 4.

- [ ] **Step 1: Write the failing test**

In `test-runner.py`'s `ArgumentValidationTest`:
```python
def test_unknown_profile_rejected(self):
    arguments = self._arguments(profile="medium-confidence")
    with self.assertRaisesRegex(ValueError, "profile"):
        self.runner._validate_arguments(arguments, self.runner.parse_duration("40m"))
def test_default_profile_is_low_confidence(self):
    arguments = self._arguments()   # no profile override
    self.assertEqual(arguments.profile, "low-confidence")
def test_image_incapable_model_rejected(self):
    arguments = self._arguments(model="deepseek/deepseek-v4-flash", profile="low-confidence")
    with self.assertRaisesRegex(ValueError, "image"):
        self.runner._validate_arguments(arguments, self.runner.parse_duration("40m"))
def test_image_capable_model_accepted(self):
    arguments = self._arguments(model="openai-codex/gpt-5.6-sol:medium", profile="low-confidence")
    self.runner._validate_arguments(arguments, self.runner.parse_duration("40m"))
```
Add `profile="low-confidence"` to the base `_arguments` Namespace. Capability map (module constant): `{"deepseek/deepseek-v4-flash": False, "deepseek/deepseek-v4-pro": False, "openai-codex/gpt-5.6-sol:medium": True, "gitlab-duo/claude-sonnet-4-5-20250929": True, "gitlab-duo/claude-haiku-4-5-20251001": True}`; unknown models fail closed.

- [ ] **Step 2: Run to verify failure**

Run: `python3 benchmarks/agentic-palisade/scripts/test-runner.py`
Expected: FAIL — no `--profile` argument, validation, or image capability check.

- [ ] **Step 3: Implement**

In `run-benchmark.py`:
- Add the capability map near `FIXED_MODEL`:
```python
MODEL_IMAGE_CAPABLE = {
    "deepseek/deepseek-v4-flash": False,
    "deepseek/deepseek-v4-pro": False,
    "openai-codex/gpt-5.6-sol:medium": True,
    "gitlab-duo/claude-sonnet-4-5-20250929": True,
    "gitlab-duo/claude-haiku-4-5-20251001": True,
}
```
- `parser.add_argument("--profile", default="low-confidence")` next to `--reasoning`; make `--pairs` optional (`type=int, default=None`).
- `_validate_arguments`:
  - `if arguments.profile not in ("low-confidence", "high-confidence"): raise ValueError(f"unknown benchmark profile: {arguments.profile}")`
  - image check: `if arguments.profile == "low-confidence" or arguments.profile == "high-confidence": if MODEL_IMAGE_CAPABLE.get(arguments.model) is False: raise ValueError(f"model {arguments.model} does not support image input required by profile {arguments.profile}")` and for unknown models (`.get(...) is None`): fail closed with `raise ValueError(f"model image capability unknown for {arguments.model}; add it to MODEL_IMAGE_CAPABLE")`.
  - pairs: `effective_pairs = arguments.pairs or (3 if arguments.profile == "low-confidence" else 5)`; validate against `required_pairs` as today.
- Manifest: add `"profile": arguments.profile` to the benchmark-manifest dict and the per-run input-manifest.
- Prepare ceiling default: when `--max-time` is the profile default (low-confidence 40m), keep it explicit in the command; the manifest records `maxTimeSeconds` as parsed.
- Execute consistency check: add `or manifest.get("profile") != arguments.profile` to the prepared-arguments check.

- [ ] **Step 4: Run to verify pass**

Run: `python3 benchmarks/agentic-palisade/scripts/test-runner.py`
Expected: PASS (existing tests + the four new ones).

- [ ] **Step 5: Commit**

```bash
git add benchmarks/agentic-palisade/scripts/run-benchmark.py benchmarks/agentic-palisade/scripts/test-runner.py
git commit -m "feat(benchmark): --profile parameter with image-capability validation"
```

---

### Task 4: ADR 0028 and documentation

**Files:**
- Create: `docs/adr/0028-benchmark-profiles.md`
- Modify: `docs/maintainers/releasing.md`, `benchmarks/README.md`

**Interfaces:**
- Documents Tasks 1–3.

- [ ] **Step 1: Write ADR 0028**

Follow the existing ADR format (Status/Accepted, Context, Decision, Consequences). Content: Context — the strict release gate (5 pairs, 3 rounds, 2+ repetition schedules, 25/25 semantic, 5 digests, 2 reviewers, fidelity 5) costs ~6 schedules of tokens and walls out fast iteration and mid-tier models; measured deepseek runs showed the corpus is model-speed-bound AND that deepseek models cannot read images (`omp models` images: no), a probable contributor to their failure to progress. Decision — sealed benchmark profiles: low-confidence is the release gate (3 pairs, 2 rounds, 1 repetition, >=60% semantic, 3 digests, 1 reviewer, fidelity >=3, tighter cost ceilings); high-confidence preserves the current strict requirements and stays supported but is not required between releases; both profiles require image-capable models, validated by the runner before any schedule is produced (fail-closed for unknown models); the profile is part of the precommitment seal; shared invariants (all-runs conjunction, fail-fast, seal fixity, retained negative controls) apply to both. Consequences — releases need ~1/6th the qualification tokens; image-incapable models are rejected early instead of burning schedules; high-confidence evidence remains available for deep review; any profile change invalidates only that precommitment.

- [ ] **Step 2: Update releasing.md precondition 6**

State that the release gate is the low-confidence profile (3 pairs, 2 rounds, 1 repetition schedule, >=60% assertion pass rate, 3 PNG digests, 1 blind reviewer at median fidelity >=3, tighter cost ceilings, image-capable model required), that `--profile` selects the profile (default low-confidence), and that the high-confidence profile is optional additional evidence, not a release gate.

- [ ] **Step 3: Update benchmarks/README.md**

Add a short section: `--profile` selects the sealed qualification profile; low-confidence (default) is the release gate with its thresholds; high-confidence preserves the historical strict requirements and is not a release gate; models must support image input (validated before prepare/execute; deepseek models do not).

- [ ] **Step 4: Commit**

```bash
git add docs/adr/0028-benchmark-profiles.md docs/maintainers/releasing.md benchmarks/README.md
git commit -m "docs(adr): benchmark profiles — low-confidence release gate, image-capable models"
```

---

### Task 5: Full verification

**Files:** none (verification only).

- [ ] **Step 1: Run both suites**

Run: `python3 benchmarks/agentic-palisade/scripts/test-runner.py` and `python3 benchmarks/agentic-palisade/scripts/test-release-gate.py`
Expected: both OK.

- [ ] **Step 2: Run the remaining benchmark suites**

Run: `python3 benchmarks/agentic-palisade/scripts/test-qualification.py`, `test-corpus.py`, `test-telemetry.py`, `test-blinding.py`, `test-treatment-symmetry.py`, `test-convergence-qualification.py`
Expected: all OK/PASS.

- [ ] **Step 3: Workflow validator + Gradle stats test**

Run: `python3 scripts/validate-workflows.py` (PASS) and `./gradlew :benchmarks:test --tests '*StatisticsTest'` (BUILD SUCCESSFUL).

- [ ] **Step 4: Prepare probes**

Rejection probe (image-incapable model):
```bash
python3 benchmarks/agentic-palisade/scripts/run-benchmark.py --output /tmp/profile-reject \
  --model deepseek/deepseek-v4-flash --reasoning high --max-time 40m --profile low-confidence \
  --pairs 3 --release-candidate --prepare-only \
  --candidate-maven-repository /tmp/x --candidate-version 1.1.0-candidate.test
```
Expected: rejected with the image-capability error BEFORE any schedule output (exit 2).

Acceptance probe (vision model, low-confidence):
```bash
candidate="$(git rev-parse HEAD)"
./gradlew publishToMavenLocal -Dmaven.repo.local=/tmp/profile-repo -PreleaseVersion="1.1.0-candidate.${candidate:0:12}" --no-daemon -q
python3 benchmarks/agentic-palisade/scripts/run-benchmark.py --output /tmp/profile-prep \
  --model openai-codex/gpt-5.6-sol:medium --reasoning medium --max-time 40m --profile low-confidence \
  --pairs 3 --release-candidate --prepare-only \
  --candidate-maven-repository /tmp/profile-repo --candidate-version "1.1.0-candidate.${candidate:0:12}"
```
Expected: prepared, manifest `profile: low-confidence`, pairs 3, `maxTimeSeconds: 2400`.

- [ ] **Step 5: Commit any residuals and confirm clean tree**

Run: `git status --short`
Expected: clean (or commit only intended files).

---

## Self-Review Notes

- Spec coverage: schema seal → Task 1; threshold resolution → Task 2; runner parameter + image capability + manifest → Task 3; docs/ADR → Task 4; acceptance criteria 1–4 → Task 5 steps 1–4; criterion 5 → Task 4.
- Placeholder scan: every step carries concrete code or commands; the image capability map is fully enumerated in Task 3.
- Type consistency: `PROFILES["low-confidence"]` / `["high-confidence"]` keys match the schema enum; `profile` flows precommitment → gate → manifest → runner validation; `modelImagesRequired` is true in both profiles and drives the runner's capability check; `scenarioAssertionGroups` stays 25 with the pass-rate applied at evidence evaluation.
