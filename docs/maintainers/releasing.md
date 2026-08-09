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
6. Review the
   [Maven Central compliance checklist](maven-central-compliance.md), including
   the maintainer attestations that automation cannot make.
7. Install `Xvfb` and verify that the executable is on `PATH` for native fixture tests.

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

Real-model Agentic Palisade qualification is useful optional evidence, but it is not a Maven
publication gate. Run it manually or on a schedule using the benchmark guide; do not attach its
availability, model revision, or reviewer outcome to artifact publication.

```bash
git switch main
git pull --ff-only
git tag --sign vX.Y.Z --message "libGDX UI Harness X.Y.Z"
git tag --verify vX.Y.Z
git push origin vX.Y.Z
```

The release tag must be annotated and PGP-signed by the configured key and point to the exact
candidate commit. Never move or reuse a release tag.

## Automated publication

Pushing the tag starts `.github/workflows/release.yml`. The workflow:

1. imports only the configured trusted public key into an isolated temporary GnuPG home;
2. verifies the primary fingerprint, signed semantic-version tag, and tag-to-commit binding;
3. runs the deterministic clean checks, API compatibility gates, parity/benchmark contract tests,
   and Javadocs under JDK 25 and Xvfb;
4. builds and signs the deterministic six-module Central bundle;
5. rejects missing artifacts, signatures, or unpublished-module leakage;
6. uploads a user-managed Maven Central deployment;
7. waits for Central state `VALIDATED`;
8. publishes the validated deployment;
9. waits for Central state `PUBLISHED`.

Publication credentials are scoped only to the steps that require them. Central authorization is written to a mode-0600 temporary curl configuration and deleted on every exit path.

## Confirm the release

After the workflow succeeds:

1. confirm the GitHub Actions release job is green;
2. confirm the deployment is `PUBLISHED` in the Maven Central Portal;
3. verify all six module coordinates and their POM, main JAR, sources JAR, Javadoc JAR, and signatures are available;
4. create the corresponding GitHub release from the immutable signed tag, using the matching file in `docs/releases/` as its notes;
5. update installation examples only after Maven Central resolves the released coordinates.

A green build before upload does not prove publication. The release is complete only after Central reports `PUBLISHED` and the public coordinates resolve.
