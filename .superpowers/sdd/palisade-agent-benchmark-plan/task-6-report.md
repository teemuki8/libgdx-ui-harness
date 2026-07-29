# Task 6 report: blinded review packaging and sealed unblinding

## Status

Complete. The implementation validates six hash-bound matched runs and evaluations, assigns A-F with a private deterministic HMAC-SHA256 Fisher-Yates shuffle, byte-copies the three references and five repeated captures for every canonical candidate state, leakage-scans the public package, validates and atomically locks a complete response, and refuses unblinding until every public/private/input hash still matches. No measured agent, evaluator, or human review was executed.

## Commands and results

### Initial red

```text
python3 benchmarks/agentic-palisade/scripts/test-blinding.py
```

Failed at module loading with the expected missing `build-blind-review.py`.

A later leakage mutation added an otherwise unlabeled run UUID to public JSON. The focused regression failed because the initial scanner rejected the `runId` key but not a UUID value. The scanner now rejects UUID-shaped values as well as run/treatment, token, diagnostic, session, workspace, source-path, and treatment-label material.

### Final focused verification

```text
PYTHONDONTWRITEBYTECODE=1 \
python3 benchmarks/agentic-palisade/scripts/test-blinding.py
.......
Ran 7 tests in 1.482s
OK
```

The fixtures cover deterministic seeded mapping, A-F/pair balance, mode-0600 private material, all six immutable run/evaluation/hash inputs, byte-identical PNG copies and canonical dimensions, filename/JSON/UUID/PNG-metadata leakage mutations, incomplete/duplicate/out-of-range response rejection, invalid pair choices, comment bounds, pre-lock refusal, response/manifest lock tampering, correct paired unblinding, and absence of any composite field.

```text
python3 -c '<Draft202012Validator.check_schema for both Task 6 schemas>'
2 schemas valid
```

A generated complete response and generated final report were additionally validated against `human-ratings.schema.json` and `final-report.schema.json`: both conformed.

### Fix round 1: deterministic rebinding and complete public-byte seal

The review found that the private mapping was checked for internal consistency
but was not recomputed from its seed, and that candidate captures with
identical fixture bytes could not prove A-F binding. It also found that the
form/schema bytes were not manifest-bound and that the response treated the
documented optional comments as required.

Fixture PNGs now have unique bytes for every run, state, and repeat while
retaining canonical dimensions. New mutations swap private A/B entries, swap
actual packaged image bytes, and mix complete public capture/metric sets across
labels. Seal and unblind independently recompute the domain-separated
Fisher-Yates result from the private seed and canonical pair/arm run order,
require the exact A-F assignment, bind every public capture identity to its
mapped evaluation, and reject all three mutations. The manifest and lock now
bind both `review-form.json` and `human-ratings.schema.json`; mutations before
seal and after lock are rejected. An otherwise complete response with no
`comments` member validates, locks, and unblinds to empty/null qualitative
output.

```text
PYTHONDONTWRITEBYTECODE=1 \
python3 benchmarks/agentic-palisade/scripts/test-blinding.py
..........
Ran 10 tests in 12.071s
OK

python3 -c '<Draft202012Validator.check_schema for both Task 6 schemas>'
2 schemas valid
```


## Interfaces

- `scripts/build-blind-review.py --run-root <frozen-run-root> --review-dir <new-public-dir> --mapping <new-private-file> [--seed-file <private-seed>]` requires exactly three baseline/harness pairs. It verifies benchmark/corpus/reference identity, every run-record SHA-256 sidecar, every evaluation SHA-256 sidecar, final-candidate and corpus identities, all three canonical visual outcomes, and all 15 capture hashes/lengths/dimensions per candidate.
- An omitted seed uses `secrets.token_bytes(32)`. A supplied seed must contain at least 256 bits. Both paths use the same domain-separated HMAC-SHA256 Fisher-Yates algorithm with rejection sampling over canonical pair/arm-ordered run IDs. Seal and unblind recompute that assignment and require exact agreement with the private A-F mapping.
- The public package contains only `manifest.json`, `review-form.json`, `human-ratings.schema.json`, three sanitized reference names, and A-F candidate capture names. Its manifest binds the form/schema bytes and exposes canonical state/viewport/dimensions, SHA-256 identities, five repetitions per state, objective automated visual metrics, and blinded matched-pair membership; it contains no treatment/run/source-path/token/diagnostic fields.
- PNGs are copied byte-for-byte without decoding, resampling, or re-encoding. Source filenames and filesystem metadata are not copied. Inputs containing textual, EXIF, or timestamp PNG chunks fail closed so metadata cannot be removed by changing image bytes.
- `scripts/unblind-report.py lock --run-root <frozen-run-root> --review-dir <public-dir> --mapping <private-file> --ratings <public-dir>/human-ratings.json --lock <new-private-lock>` first requires all A-F fidelity values in 1-7, a bijection over ranking values 1-6, one valid preferred candidate in each displayed pair, optional bounded comments, exact keys, and the package-manifest digest. Only after the response validates does it consume the private mapping, recompute A-F, bind public captures to evaluations, and atomically publish a mode-0600 lock over manifest, form, schema, and response bytes.
- `scripts/unblind-report.py unblind --run-root <frozen-run-root> --review-dir <public-dir> --mapping <private-file> --ratings <public-dir>/human-ratings.json --lock <private-lock> --output <new-final-report.json>` revalidates the response, every locked public byte, deterministic mapping, label/capture binding, every frozen input hash, and every public image hash before an exclusive atomic report write.
- The final report keeps functional, automated visual, human visual, and telemetry/treatment channels separate. Each retains raw per-run data, arm medians/minimums/maximums, and harness-minus-baseline paired deltas. Qualitative comments are associated after unblinding. No combined score is defined or emitted.

## Self-review

- Only the five Task 6 implementation/test/schema files and this report were created; prior runner, evaluator, corpus, template, treatment, and protocol files were not modified.
- Public output construction is allowlisted rather than copied recursively. Source diagnostics, paths, run records, telemetry, and mapping material never enter the review directory. The scanner rejects symlinks and any unexpected non-JSON/non-PNG file.
- Every public reference/capture is independently checked before and after packaging. The mapping binds the benchmark manifest and an exact relative-path-to-hash inventory for corpus references, run records and sidecars, evaluations and sidecars, and all captures.
- Response uniqueness and matched-pair membership are enforced semantically in addition to the static schema because Draft 2020-12 cannot express uniqueness across object property values or membership in a sibling manifest.
- The review form omits a manifest digest to avoid a form/manifest hash cycle; the response obtains the digest from `manifest.json`, while the manifest independently hashes the form and schema.
- Existing-output refusal plus same-directory temporary writes, `fsync`, atomic replacement, and restrictive private modes prevent partial lock/mapping publication and silent overwrite.
- Report arithmetic uses raw values only: no weighting, normalization, hidden preference, or cross-channel aggregation exists.

## Commit

Implementation: `0917528` (`feat(benchmark): seal blinded review workflow`).
Review fix round 1: `1534d80` (`fix(benchmark): bind sealed review identities`).

## Concerns

- The packaging contract deliberately fails closed unless every evaluation retains five valid captures for each of the three canonical states. An evaluator result without canonical captures cannot be silently replaced, synthesized, or resampled by this task; qualification/measurement must preserve those required artifacts before packaging.
- No measured outputs or actual human response were available or used. All execution proof is fixture-based, as required.
