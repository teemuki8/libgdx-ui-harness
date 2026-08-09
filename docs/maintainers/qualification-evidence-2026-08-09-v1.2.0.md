# Qualification Evidence: v1.2.0 exception decision (2026-08-09)

Date: 2026-08-09
Qualified implementation parent: `a3e0d0e2cec8edbb3cb672ebbf1fbb7406fd55ac`
Candidate version: `1.2.0-candidate.a3e0d0e2cec8`
Model: `openai-codex/gpt-5.6-sol:medium`
Profile: low-confidence (3 matched pairs, 2 arms, 3 refinement rounds)

## Retention

The operator retained the complete prepared schedules, run records, OMP session
exports, evaluator outputs, captures, and blind-review package outside the Git
repository under `/home/tjaaskel/release-evidence-v1.2.0-a3e0d0e/`. These paths
are workstation-local evidence locations, not consumer installation paths.

An earlier prepared schedule was replaced after infrastructure failures: the
isolated OMP native module was initially unavailable, then `/tmp` filled while
executing the replacement. Neither partial schedule is counted below.

## Completed sealed schedules

| schedule | precommitment SHA-256 | harness semantic scores | result |
|---|---|---|---|
| 1 | `2f283e172be82c35c3526b5f365c5b84a9ec5da748636d2cb9533d46f95d2bbd` | 12/25, 16/25, 18/25 | failed 15/25 per-run floor |
| 2 | `004d2857695862d34356432237b0aafcd0c23a2c4364ce89ac7d0626ff47ba41` | 9/25, 15/25, 15/25 | failed 15/25 per-run floor |
| 3 | `6b33594759f9fd50f5005b1e752907ac3e3f9cc0b61b629a3f05272c0e01565c` | 17/25, 16/25, 17/25 | passed semantic floor; failed repeatability |

All six scheduled runs in each counted schedule completed successfully. Schedule
3 was evaluated and blindly reviewed before unblinding. Its harness-arm fidelity
ratings were 5, 6, and 3 (median 5), all three harness arms met the semantic
floor, and no candidate was judged unusable.

## Decisive repeatability failure

ADR 0010 requires each canonical observation's stable PNG digest to be identical
across candidate repetitions in the environment stratum. Schedule 3 produced:

| harness run | initial 1920x1080 | bottom 1920x1080 | initial 1280x720 |
|---|---|---|---|
| `30688e79-2840-4930-90d8-70083f459956` | `74f693d419ae625d0bc32ccfe4bb6ec9062dd2cc34d86c0a9ad6cc323d69c97a` | `991823d2d0841de561f57ae3188bf2513008ccc88d5b1aab816487c4c7f61805` | `2138fa9db0c8b78666c023cb085d12bc79bf426aae1463e9f4cbc1f066e1e8fd` |
| `9637dd38-512a-4a4a-b3b4-c9657b820445` | `4379df478e3175a5f73b69abbfc06fd6f8212da434c98c7616d85992635be5f2` | `d32ab92431072adef8775bb435b8d0ab49dbd15fc9cff344843d9c524c5dc080` | `16f73c6389761504f05a4ca4ce993a3caca1fccd0734a1ee7ee17e211d1ee387` |
| `9bc8a88a-bceb-4d79-ad4e-9f6837ee5623` | `a6c3a22f21e238f0e72dad472cf8318c7221de11058257eec09fa8b49d782282` | `45fe1961bbfea82d37dad24f09299e7f900527b739e948ecbc71e08cf5612f79` | `c0fd25352459ec9e94c94ae178c7e1a1ef93577bab863729ebf5dbc4bf3c9d9b` |

Every observation has three distinct cross-repetition identities. Schedule 3
therefore does not produce, and must not be represented as, a passing sealed
repeatability decision.

## Release decision

The maintainer authorized the one-release exception recorded by ADR 0033. The
final tagged release source is a descendant of the qualified implementation
parent; it adds the exception evidence, marker, version-bound workflow guard,
and security validation without changing a published library module. The
exception explicitly covers that source-identity difference.

The release workflow skips only sealed-decision verification, and only when the
tag is exactly `v1.2.0` and its commit carries `.release-gate-exception`. The
mandatory signed evidence tag is named from that final tagged release commit.
Signed release-tag verification, the complete JDK 25 build, Javadocs, Maven
Central validation/publication, and public-coordinate verification remain
required.
