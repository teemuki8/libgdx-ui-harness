# Task 5 fix report: hard-unavailable qualification

## Delivered

- The bounded core finding sink now tracks the highest severity it observes independently of retained capacity. Result status therefore reflects every produced finding while the public findings list remains bounded and deterministic.
- Subtree evidence filtering now preserves globally unavailable intrinsic text evidence instead of converting it to available empty evidence.
- Added a core regression with `maxFindings=1` and `failOn=ERROR`: a below-target-size warning occupies the retained slot before unavailable clipped-text evidence is reported. The result retains one warning, remains truncated, and fails.
- Added a real Scene2D selected-subtree regression containing an ambiguous wrapped `Label` and only intrinsic text checks. The result reports error-severity `CHECK_UNAVAILABLE` and fails.

## TDD evidence

### RED

Before production changes:

```text
LayoutValidatorTest > unavailableCheckFailsEvenWhenFindingCapacityIsAlreadyFull() FAILED
    org.opentest4j.AssertionFailedError at LayoutValidatorTest.java:379

Scene2dLayoutValidatorTest > ambiguousLabelEvidenceRemainsUnavailableInSelectedSubtree() FAILED
    org.opentest4j.AssertionFailedError at Scene2dLayoutValidatorTest.java:290
```

Both failures were the reproduced false `PASS` statuses.

### GREEN

```text
./gradlew :harness-core:test :harness-scene2d:test \
  --tests '*LayoutValidatorTest*' \
  --tests '*Scene2dLayoutValidatorTest*'

BUILD SUCCESSFUL
```

```text
./gradlew :harness-mcp:test \
  --tests '*HarnessMcpServerContractTest*' \
  --tests '*HarnessToolCatalogTest*'

BUILD SUCCESSFUL
```

## Scope

No result or protocol schema changed. Finding retention, truncation reporting, and deterministic output ordering remain bounded. Existing documentation already states the corrected hard-unavailable behavior precisely, so no documentation wording changed.
