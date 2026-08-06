# Releasing to Maven Central

This guide is for repository maintainers. Consumer-facing changes and compatibility information belong in `docs/releases/`.

## Preconditions

Before preparing a tag:

1. Verify the Maven Central namespace is already registered and approved.
2. Verify the protected GitHub environment `maven-central` is configured.
3. Verify the Maven artifact-signing public key is retrievable from a
   [Central-supported keyserver](https://central.sonatype.org/publish/requirements/gpg/#distributing-your-public-key)
   by both its full fingerprint and 16-hex long key ID.
4. Verify the latest `main` CI run is green.
5. Confirm the release notes describe the exact version being published.
6. Complete the repeatability qualification for the exact candidate commit
   under the qualified model. The release gate is the `low-confidence`
   profile: 3 pairs, 2 rounds, 1 repetition schedule, a >=60% assertion
   pass rate, 3 PNG digests per observation, and 1 blind reviewer at
   median fidelity >=3, with tighter cost ceilings than the historical
   requirements. The low-confidence profile does not require model image input, so image-incapable
   models can qualify; the high-confidence profile requires an image-capable model
   and the runner fails closed for models whose image support is unknown. `--profile` selects the sealed
   qualification profile and defaults to `low-confidence`; the
   `high-confidence` profile preserves the historical strict requirements
   (5 pairs, 3 rounds, 2+ repetition schedules, 25/25 semantic, 5 digests,
   2 reviewers at median fidelity 5) and is optional additional evidence,
   not a release gate. The model and the per-run wall ceiling are
   parameters of `run-benchmark.py`: `--model` selects the model whose
   evidence is being produced, and `--max-time` seals the per-arm ceiling
   with a 10-minute floor and a 40-minute prepare default. The schedule
   may seal `failFast: true`; under fail-fast the supervisor cancels the
   remaining arms once any required run fails, and each cancelled arm is
   recorded with its run ID and the triggering failure's classification
   reason. The gate accepts a cancelled arm only when the schedule
   declared fail-fast and the triggering failure is present; otherwise it
   treats the arm exactly as missing. Evidence is scoped to the qualified
   model: historical evidence is model-scoped and does not carry over to
   a different model.
7. Review the
   [Maven Central compliance checklist](maven-central-compliance.md), including
   the maintainer attestations that automation cannot make.
8. Install `Xvfb` and verify that the executable is on `PATH`. Measured
   graphical arms start one private `1920x1080x24` X server each and fail
   closed before execution when the server is unavailable.

Account enrollment, namespace ownership challenges, and recovery credentials are private administrative records. Do not commit them to this repository.

## GitHub environment contract

The `maven-central` environment must provide:

| Name | Kind | Purpose |
|---|---|---|
| `RELEASE_SIGNING_PUBLIC_KEY` | Secret | Armored public key authorized to sign release tags |
| `RELEASE_SIGNING_FINGERPRINT` | Variable | Exact 40- or 64-hex primary-key fingerprint |
| `MAVEN_CENTRAL_USERNAME` | Secret | Username from a Maven Central Portal user token |
| `MAVEN_CENTRAL_PASSWORD` | Secret | Password from the same Portal user token |
| `MAVEN_SIGNING_KEY` | Secret | Armored private key used to sign Maven artifacts |
| `MAVEN_SIGNING_PASSWORD` | Secret | Private-key passphrase |

A GitHub token is not a Maven Central user token. Never place secret values in repository files, workflow arguments, issue comments, logs, or retained artifacts. Rotate the public key and configured fingerprint together after independently verifying the new fingerprint.

## Verify the candidate locally

Run the complete release candidate gate:

```bash
./gradlew clean check javadoc publishToMavenLocal --warning-mode=fail
python3 scripts/validate-workflows.py
```

Confirm that Maven local contains only the six publishable modules:

- `harness-core`
- `harness-scene2d`
- `harness-lwjgl3`
- `harness-protocol`
- `harness-mcp`
- `harness-agent-runtime`

`harness-fixtures` and `benchmarks` must not be published.

## Create the release

Replace `X.Y.Z` with the release version. The workflow accepts semantic versions, including an optional prerelease suffix.

Before starting a run, create and retain `precommitment.json`. It must contain
the candidate/source identities, all policy and threshold hashes, environment
strata, and the complete matched-pair schedule. Compute
`precommitmentSha256` over canonical JSON with that field omitted. Every
recorded `startedAt` must be later than the precommitment's `sealedAt`.

Prepare the ten immutable arm identities without starting OMP:

```bash
candidate="$(git rev-parse HEAD)"
candidate_version="1.1.0-candidate.${candidate:0:12}"
candidate_repository="$(mktemp -d)"
./gradlew publishToMavenLocal \
  -Dmaven.repo.local="$candidate_repository" \
  -PreleaseVersion="$candidate_version" --warning-mode=fail

python3 benchmarks/agentic-palisade/scripts/run-benchmark.py \
  --output QUALIFICATION_ROOT \
  --model openai-codex/gpt-5.6-sol:medium \
  --max-time 45m --pairs 5 --release-candidate --prepare-only \
  --candidate-maven-repository "$candidate_repository" \
  --candidate-version "$candidate_version"
```

Build and seal `precommitment.json` from
`QUALIFICATION_ROOT/benchmark-manifest.json` before continuing. After
independently verifying the seal and schedule, execute those exact prepared
identities:

```bash
python3 benchmarks/agentic-palisade/scripts/run-benchmark.py \
  --output QUALIFICATION_ROOT \
  --model openai-codex/gpt-5.6-sol:medium \
  --max-time 45m --pairs 5 --release-candidate --execute-prepared \
  --auth-broker-url http://127.0.0.1:9000
```

`--execute-prepared` rejects changed manifests, protected inputs, candidate
templates, run identities, candidate Maven artifacts, or pre-existing outcome
files. The copied Maven repository digest is part of every harness-arm
treatment identity; the external temporary repository is not consulted during
execution. The historical three-pair benchmark remains fixed on published
`1.0.0` and does not accept release-candidate pair counts.

After all scheduled runs and blind reviews are complete, create
`manifest.json` with the outcomes and the exact `precommitmentSha256`, then
generate the sealed decision:

```bash
python3 benchmarks/agentic-palisade/scripts/release-gate.py create-decision \
  --precommitment precommitment.json --manifest manifest.json \
  --output decision.json --evidence-root .
```

Set `candidateSourceSha256` to the output of
`git archive CANDIDATE_COMMIT | sha256sum`; the release workflow recomputes that
exact archive digest. The manifest's `artifacts` map must list every retained
raw artifact path and SHA-256. Verification rejects a missing, extra-path, or
changed referenced artifact.

Commit `precommitment.json`, `manifest.json`, `decision.json`, and the
digest-bound retained raw artifacts on a dedicated evidence commit. Sign an
annotated evidence tag whose name contains the exact candidate commit:

```bash
candidate="$(git rev-parse main)"
git tag --sign "release-evidence-$candidate" EVIDENCE_COMMIT \
  --message "Repeatability evidence for $candidate"
git tag --verify "release-evidence-$candidate"
git push origin "release-evidence-$candidate"
```

The evidence commit is deliberately separate: results can bind the already
fixed candidate commit without creating a self-referential source hash. Never
move or reuse an evidence tag.

```bash
git switch main
git pull --ff-only
git tag --sign vX.Y.Z --message "libGDX UI Harness X.Y.Z"
git tag --verify vX.Y.Z
git push origin vX.Y.Z
```

Both tags must be annotated and PGP-signed by the configured key. The release
tag must point to the exact qualified candidate commit. Never move or reuse
either tag. If a candidate fails, fix the cause, perform a fresh qualification,
and create a new version.

## Automated publication

Pushing the tag starts `.github/workflows/release.yml`. The workflow:

1. imports only the configured trusted public key into an isolated temporary GnuPG home;
2. verifies the primary fingerprint, signed semantic-version tag, and tag-to-commit binding;
3. verifies the separately signed evidence tag for that exact commit;
4. regenerates the sealed repeatability decision and requires byte-identical
   agreement with its precommitted decision;
5. runs the clean checks and Javadocs under JDK 25;
6. builds and signs the deterministic six-module Central bundle;
7. rejects missing artifacts, signatures, or unpublished-module leakage;
8. uploads a user-managed Maven Central deployment;
9. waits for Central state `VALIDATED`;
10. publishes the validated deployment;
11. waits for Central state `PUBLISHED`.

Publication credentials are scoped only to the steps that require them. Central authorization is written to a mode-0600 temporary curl configuration and deleted on every exit path.

## Confirm the release

After the workflow succeeds:

1. confirm the GitHub Actions release job is green;
2. confirm the deployment is `PUBLISHED` in the Maven Central Portal;
3. verify all six module coordinates and their POM, main JAR, sources JAR, Javadoc JAR, and signatures are available;
4. create the corresponding GitHub release from the immutable signed tag, using the matching file in `docs/releases/` as its notes;
5. update installation examples only after Maven Central resolves the released coordinates.

A green build before upload does not prove publication. The release is complete only after Central reports `PUBLISHED` and the public coordinates resolve.
