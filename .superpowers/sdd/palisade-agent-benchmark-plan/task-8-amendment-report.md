# Task 8 amendment: broker-authenticated preflight and transient run auth

## Status and authorization

Amendment identity: `agentic-palisade/task-8-auth-broker-amendment-v1`.

The user authorized this transparent Task 5/8 protocol amendment after the first immutable six-run attempt aborted before any model request. The authorization requires one exact-model, no-tools, no-session preflight through a clean isolated profile before any measured output directory, run ID, or workspace is allocated. Only a successful preflight permits the six measured launches. The aborted batch remains a separate infrastructure failure and must never enter evaluation, blinding, or analysis.

No corpus, reference, template, treatment, evaluator, or blinding semantics changed. Both the benchmark manifest and every input manifest bind the amendment identity.

## Root cause and preserved infrastructure abort

The original Task 5 runner correctly removed inherited credentials, but each fresh OMP profile therefore lacked both local provider credentials and the global auth-broker configuration. All six OMP processes exited with `No API key found for openai-codex` before creating a session or issuing a model request. The runner retained those outcomes as designed, but allocating all six workspaces before authenticating made an operational prerequisite consume the immutable batch.

Preserved batch: `build/reports/agentic-palisade/20260729T173223Z`

- `benchmark-manifest.json`: `6f3ad3151382c77400e9f7bc514bda4b5252075636a12ca29b24c89d5c80d098`
- `2245e577-eab5-4114-938d-2c843729940f/run-record.json`: `873bf293a2348ef2f20674a2b9468e583e86def82323e67c7e08b5d163a82a8f`
- `2db74d04-6ec6-4ec4-97e1-237aeb235034/run-record.json`: `806aef1d2177df56926de8aade82bba42cfc5b17d4d2b25007f2d4e0b322f610`
- `6c0b2256-a988-4199-ac9d-58a390eb7d82/run-record.json`: `3d8ebc31db715ad2152da6ee73b5405ed122bbf378b3e8ed0923421ce36e92d8`
- `89c37673-9cf5-4c67-93d9-4e08369f81c8/run-record.json`: `d1b428402090eb7f8192b9f9fa42087b46ffa867529691e8bd3b7a7e6d8bb3c8`
- `b25a0dee-4660-4c1b-859a-db41d5ee67ea/run-record.json`: `6df894459ffea7e0e4bbda2f5571ffad1beccea275cb5731650857e3b5212266`
- `f9bf1038-3d77-4edc-8ada-9b337442feeb/run-record.json`: `8afdcfe2ce156d82a1821c5113b6e3e6be8eb33e9f47126fcefd9e9ed4587b68`

The amendment did not modify this directory. Its six records still validate against the Draft 2020-12 run-record schema.

## Amended protocol and credential lifecycle

For a measured invocation, the runner now performs these steps in order:

1. Validate the exact model, deadline, pair count, fixed loopback broker URL, unused output path, and measured/dry-run mode.
2. Run runner-owned `omp auth-broker token` in an allowlisted environment that retains only HOME/XDG lookup coordinates and safe runtime variables, capturing the bearer only in memory. Its stderr is discarded and its stdout is never logged, hashed, persisted, placed in argv, or placed in an environment.
3. Create a clean temporary HOME/XDG profile and a one-use loopback relay. The profile's canonical OMP config contains only the relay URL and a fresh relay capability, never the real broker bearer or a provider credential.
4. Launch exactly `openai-codex/gpt-5.6-sol:medium` with `--thinking medium --print --no-session --no-tools`, disabled extensions/skills/rules/LSP/title generation, and the harmless prompt `Reply with exactly AUTHENTICATED.`
5. The relay accepts one constant-time-authenticated request, forwards it only to `http://127.0.0.1:9000` while injecting the runner-owned bearer, bounds request/response bodies, strips hop-by-hop and caller authorization headers, and obtains the broker response.
6. Before returning that response to OMP, the relay zeroes and unlinks the temporary canonical config and shuts down. Thus OMP receives authenticated provider configuration in process only after the filesystem capability has been retired, and no candidate tool exists during preflight.
7. The runner boundedly quiesces the entire preflight process group. A surviving descendant is terminated, reported as an infrastructure failure, and prevents measured allocation.
8. Only a successful broker exchange, zero OMP exit, quiescent process group, and nonempty JSON output permit output-directory creation and six UUID allocation. Failure exits `2` with no measured output directory.
9. Each measured OMP launch gets an independent relay and the same retire-before-response lifecycle. The measured command line and environment contain no auth config argument, bearer, provider credential, global database/history path, or relay capability. The runner never copies the main agent database or history.

Dry runs and the fixed no-model qualification fixture do not call the broker or model. The measured CLI requires the explicit fixed precommitment `--auth-broker-url http://127.0.0.1:9000`.

## Threat analysis

- **Bearer disclosure:** the real broker bearer exists only in runner memory and the runner-owned relay object. It is never formatted into OMP config, argv, environment, manifests, logs, candidate files, or hashes.
- **Provider credential disclosure:** the relay response goes directly to OMP after the relay capability has been removed. The runner never reads, records, or hashes provider credentials. OMP may retain its own encrypted broker snapshot inside the isolated profile cache; the retired relay capability and real bearer are absent, and no global key/database/history is mounted or copied.
- **Candidate race:** the relay zeros/unlinks config and stops before writing the broker response back to OMP. A model cannot produce a tool call until after that response enables the model request. The fixture's tool child inspects its argv/environment, its parent's cmdline/environment, and the canonical config location; it sees zero config bytes and neither bearer nor secret path.
- **Relay abuse:** each relay binds only loopback on an ephemeral port, uses a fresh high-entropy one-use capability, accepts one request, uses constant-time comparison, forwards only to the fixed loopback broker, and retires before OMP can begin model execution. Shutdown tracks and boundedly joins active handler threads so no handler can outlive recorded cleanup while retaining forwarded authorization state.
- **Failure containment:** missing bearer, rejected broker request, timeout, nonzero preflight, empty output, surviving preflight descendant, relay/handler shutdown failure, or a pre-existing output path fails closed. No measured directory, UUID, workspace, manifest, or run record is allocated on auth-preflight failure.
- **Retained abort separation:** the `20260729T173223Z` batch remains immutable infrastructure evidence and is not a retry input or analysis row.

## Red-green evidence

Initial auth regressions failed against the prior runner as expected:

- missing-broker invocation was rejected as an unknown CLI option rather than aborting through an auth preflight;
- the measured output/manifest contract lacked the amendment identity;
- measured fake OMP launched without authenticated config;
- a tool child could still open the parent's inherited pipe FD, even though the already-drained pipe contained no bytes;
- an extensionless `/proc/self/fd/N` overlay was not loaded by OMP auth initialization.

The final design uses OMP's canonical per-profile config only for startup, removes it before returning the broker response, and passes no config path in argv. Independent review additionally required a sanitized token-helper environment, process-group quiescence before preflight acceptance, active relay-handler joins, a hermetic in-test broker, and relay-side enforcement of the fixed upstream endpoint; focused regressions were red before those fixes. Final focused green:

```text
python3 -m unittest scripts/test-runner.py scripts/test-telemetry.py scripts/test-treatment-symmetry.py
..........................
Ran 26 tests in 6.569s
OK
```

The regressions cover missing-broker abort before output allocation, successful exact-model preflight shape, sanitized broker-token lookup, fixed broker endpoint enforcement, preflight descendant rejection/cleanup, bounded active relay-handler shutdown, amendment manifest binding, six authenticated measured launches through a hermetic local broker, absence of bearer/secret paths from argv/environment/manifests/logs/candidate-readable files, and zero candidate-visible config bytes after relay retirement.

Dry-run proof, with no broker or model call:

```text
python3 scripts/run-benchmark.py \
  --output /tmp/agentic-palisade-task8-dry-run-post-review \
  --model openai-codex/gpt-5.6-sol:medium \
  --max-time 45m \
  --pairs 3 \
  --auth-broker-url http://127.0.0.1:9000 \
  --dry-run
{"status": "prepared", "runs": 6, "output": "/tmp/agentic-palisade-task8-dry-run-post-review"}
```

Schema proof: all six preserved abort records validated with `jsonschema.Draft202012Validator` plus format checking: `validated=6 errors=0`.

## Harmless exact-model auth smoke

Outside every measured run and candidate workspace, the final preflight helper was invoked once after the relay lifecycle was complete. It used a fresh temporary isolated HOME/profile, exact model `openai-codex/gpt-5.6-sol:medium`, medium reasoning, no tools, and no session. It completed in `26.08s`:

```text
AUTH_PREFLIGHT_OK
```

Earlier pipe/proc-FD diagnostics failed locally with `No API key found` before any provider/model request; they established that OMP auth initialization does not consume those extra overlay forms. Only the final canonical-profile smoke reached the exact model. No candidate was executed.

## Next exact command

Do not reuse the aborted directory. After confirming isolated displays `:220` through `:225` are ready, the next and only measured command is:

```text
python3 benchmarks/agentic-palisade/scripts/run-benchmark.py \
  --output build/reports/agentic-palisade/20260729T181047Z \
  --model openai-codex/gpt-5.6-sol:medium \
  --max-time 45m \
  --pairs 3 \
  --auth-broker-url http://127.0.0.1:9000
```

This command has not been executed.

## Consumer-boundary amendment gate

Independent review found that the runner bound the amendment identity into new
manifests, but downstream consumers did not yet require it. That omission could
allow the preserved pre-amendment infrastructure abort to reach a later stage
and fail on missing evaluation data rather than being excluded by identity.

The exact value
`agentic-palisade/task-8-auth-broker-amendment-v1` is now required:

- by the evaluator CLI before it resolves candidate or corpus inputs;
- by qualification before it reads run records or launches the evaluator;
- by blinded packaging before it reads any run record or evaluation; and
- by lock/unblind analysis through its mandatory blinded-input reload.

The evaluator's precommitted form is now:

```text
agentic-palisade-evaluator evaluate \
  --benchmark-manifest <amended-run-root>/benchmark-manifest.json \
  --candidate <immutable-candidate> \
  --corpus <amended-run-root>/corpus \
  --output <new-evaluation-directory> \
  --candidate-id <run-id>
```

Regressions were observed red first: the blinding test reached the deliberately
missing `evaluation.json` for both missing and wrong identities and for the real
`20260729T173223Z` abort; the qualification test failed because no gate existed;
and the evaluator test failed to compile because no manifest validator existed.
After the gates and fixture identity were added:

```text
python3 scripts/test-blinding.py
.............
Ran 13 tests in 16.874s
OK

python3 -m unittest \
  scripts.test-qualification.QualificationTest.test_protocol_amendment_gate_requires_exact_identity \
  scripts.test-qualification.QualificationTest.test_channel_continuity_rejects_a_drop_from_every_final_channel \
  scripts.test-qualification.QualificationTest.test_rejected_input_retention_detects_destructive_handling
...
Ran 3 tests in 0.001s
OK

./gradlew -p benchmarks/agentic-palisade/evaluator test
BUILD SUCCESSFUL in 6s
```

The valid amended six-run fixture passes the same loader. Missing, wrong, and
the real preserved-abort identity are rejected before evaluation reads. This
review round ran no model, measured agent, candidate, or evaluator scenario.

## Clean-checkout regression hardening

A second review found that the first boundary regression directly opened the
ignored local `build/reports/.../20260729T173223Z` evidence directory. That made
the suite depend on operator-local retained evidence despite the production gate
itself being portable.

The regression now synthesizes, entirely under its temporary directory, a
minimal six-run pre-amendment infrastructure-abort-class manifest. It has the
same benchmark schema, model, three symmetric pairs, and six run-record
identities, but no `protocolAmendment` and no evaluation files. A second
synthetic abort-class manifest carries a wrong amendment. Both are rejected by
the amendment gate before the loader attempts any run-record or evaluation
read. The valid amended six-run fixture continues through the full loader.

The test source contains no `build/reports`, retained batch ID, or repository
root dependency. The real abort path and immutable hashes remain report evidence
only. Post-change portable suite evidence:

```text
python3 -m unittest \
  scripts.test-blinding.BlindingTest.test_protocol_amendment_gate_precedes_evaluation_reads
.
Ran 1 test in 0.995s
OK

python3 scripts/test-blinding.py
.............
Ran 13 tests in 15.224s
OK
```

No model, measured agent, candidate, or evaluator scenario ran in this review
round.
