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
6. Complete the repeatability qualification for the exact candidate commit.

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

Confirm that Maven local contains only the five publishable modules:

- `harness-core`
- `harness-scene2d`
- `harness-lwjgl3`
- `harness-protocol`
- `harness-mcp`

`harness-fixtures` and `benchmarks` must not be published.

## Create the release

Replace `X.Y.Z` with the release version. The workflow accepts semantic versions, including an optional prerelease suffix.

First generate the sealed decision from the precommitted manifest after all
matched runs and blind reviews are complete:

```bash
python3 benchmarks/agentic-palisade/scripts/release-gate.py create-decision \
  --manifest manifest.json --output decision.json --evidence-root .
```

Set `candidateSourceSha256` to the output of
`git archive CANDIDATE_COMMIT | sha256sum`; the release workflow recomputes that
exact archive digest. The manifest's `artifacts` map must list every retained
raw artifact path and SHA-256. Verification rejects a missing, extra-path, or
changed referenced artifact.

Commit `manifest.json`, `decision.json`, and the digest-bound retained raw
artifacts on a dedicated evidence commit. Sign an annotated evidence tag whose
name contains the exact candidate commit:

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
6. builds and signs the deterministic five-module Central bundle;
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
3. verify all five module coordinates and their POM, main JAR, sources JAR, Javadoc JAR, and signatures are available;
4. create the corresponding GitHub release from the immutable signed tag, using the matching file in `docs/releases/` as its notes;
5. update installation examples only after Maven Central resolves the released coordinates.

A green build before upload does not prove publication. The release is complete only after Central reports `PUBLISHED` and the public coordinates resolve.
