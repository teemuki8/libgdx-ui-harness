# Final fix round 2 report

## Finding resolved

Alignment and spacing findings previously concatenated the complete valid `layout-group` value into `LayoutFinding.evidence`. A maximum-length 16,384-character group therefore exceeded the finding's 512-character evidence contract and threw instead of returning diagnostics.

Both checks now use one deterministic `layoutGroupEvidence` formatter. It reserves the fixed `layout-group ` prefix and check-specific suffix, truncates only the group segment, and backs off a UTF-16 high surrogate at the cut boundary. The final evidence remains within `LayoutFinding.MAX_EVIDENCE_LENGTH`, retains the group prefix and the alignment/spacing description, and does not change `relatedActorId`.

## TDD evidence

### RED

Added `LayoutValidatorTest.longUnicodeLayoutGroupEvidenceIsBoundedForAlignmentAndSpacing` with an exactly 16,384-character group ID containing supplementary Unicode code points. The three-node vertical cohort qualifies for both alignment and spacing deviations.

Command:

`./gradlew :harness-core:test --tests 'dev.gdx.uiharness.core.layout.LayoutValidatorTest.longUnicodeLayoutGroupEvidenceIsBoundedForAlignmentAndSpacing' --warning-mode=fail`

Pre-fix result: exit 1. `LayoutFinding` threw `IllegalArgumentException: finding evidence exceeds 512 characters` while constructing the alignment finding.

### GREEN

The regression now returns both findings. It verifies bounded and well-formed UTF-16 evidence, deterministic finding order/content, preserved fixed check/group evidence, and resolving peer IDs (`first` for alignment and `second` for spacing).

Focused full-class command:

`./gradlew :harness-core:test --tests '*LayoutValidatorTest*' --warning-mode=fail`

Result: exit 0, `BUILD SUCCESSFUL` (17 tests).

## Exact verification

1. Changed-contract command:

   `xvfb-run -a ./gradlew :harness-core:test :harness-scene2d:test :harness-protocol:test :harness-mcp:test --tests '*LayoutValidatorTest*' --tests '*Scene2dLayoutValidatorTest*' --tests '*Scene2dTypographyExtractorTest*' --tests '*LayoutValidationProtocolTest*' --tests '*HarnessMcpServerContractTest*' --tests '*HarnessToolCatalogTest*' --tests '*DocsCatalogParityTest*' --warning-mode=fail`

   Result: exit 0, `BUILD SUCCESSFUL`.

2. Real fixture smoke:

   `xvfb-run -a ./gradlew :harness-fixtures:test --tests '*ReferenceApplicationSmokeTest' --warning-mode=fail`

   Result: exit 0, `BUILD SUCCESSFUL`.

3. Full clean check after source changes:

   `xvfb-run -a ./gradlew clean check --warning-mode=fail`

   Result: exit 0, `BUILD SUCCESSFUL`; all 79 tasks executed.

The commands emitted only the repository's existing JDK removal/resource warnings and LWJGL runtime notices; Gradle `--warning-mode=fail` found no Gradle deprecation failure.

## Scope

Changed only the core finding length constant visibility, the two layout-group evidence construction sites and their shared formatter, the core regression, and this report. No dependency, version, publication, merge, or push action was performed.
