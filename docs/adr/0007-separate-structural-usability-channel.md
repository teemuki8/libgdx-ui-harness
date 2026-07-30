# ADR 0007: Separate Structural-Usability Evaluation Channel

- Status: Accepted
- Date: 2026-07-30

## Context

The Agentic Palisade benchmark records objective raster comparisons and blinded
human fidelity reviews. Global raster similarity does not establish control
legibility, affordance, hierarchy, internal clipping, responsive composition,
or settled scroll stability. Combining those properties into the raster or
human result would hide which contract failed.

Structural evidence also crosses a trust boundary. A result is meaningful only
when the evaluator can bind it to the exact reference, candidate capture,
canonical state, viewport, dimensions, device scale, semantic revision, layout
revision, and five-frame capture family.

## Decision

The evaluation record and final report expose three independent visual
channels:

- `automatedVisual` retains the existing raster measurements unchanged;
- `structuralUsability` contains versioned pass, fail, incomplete, stale, or
  unstable outcomes and per-signal diagnostics;
- `humanVisual` remains the sealed blinded review result.

No combined score or cross-channel weighting is emitted.

`structural-usability/v1` evaluates six independently reported signals:
legibility, affordance, hierarchy, clipping, responsive composition, and
scroll stability. Diagnostics contain stable control identities,
expected-versus-observed values, coordinate space, and units. Responsive
claims are limited to the frozen 1920 by 1080 and 1280 by 720 viewports at
device scale 1.

Candidate observations use `structural-observation/v1`. The evaluator, rather
than the candidate, binds reference and capture hashes, canonical state,
viewport, dimensions, device scale, and frame order. Missing or mismatched
identity, attribution, revision, or five-frame evidence cannot pass.

Structural outcomes enter the blind package without treatment, run-arm, source
path, or human-result fields. The sealed unblinding join associates the
unchanged structural outcomes with run identities; it does not rewrite them.
Historical evaluation records without the new field deserialize as an
unavailable empty structural channel.

## Consequences

Consumers can reconcile favorable raster measurements with structural or human
failures as measurements of different properties. A structural failure is
actionable without invalidating unchanged raster history or treating a human
review as universally authoritative.

New complete evaluator runs publish one structural outcome for each frozen
reference. Candidate implementations that do not provide the versioned
observation remain evaluable, but their structural result is `INCOMPLETE`.

## Verification

Run:

```text
./gradlew check javadoc --warning-mode=fail
python3 benchmarks/agentic-palisade/scripts/test-blinding.py
python3 benchmarks/agentic-palisade/scripts/test-qualification.py
```

The evaluator tests mutate each structural predicate independently, including
an internal one-framebuffer-pixel cut with no global frame-edge clipping and a
changed post-settle frame. The blinding and qualification tests prove channel
separation, six-run identity continuity, leakage rejection, and sealed
unblinding without a combined score.
