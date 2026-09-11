# Empty Fill response verification

Date: 2026-09-11. Linux/X11, JDK 25, LWJGL3 and Mesa software rendering.
This is local source qualification; no release or publication is claimed.

An empty `ui_action` Fill already cleared the widget, then failed because the protocol rejected
its empty observed state. [ADR 0043](../../adr/0043-empty-action-observed-state.md) records the
bounded response/schema correction. Input dispatch and application-owned rendering are unchanged.

The [focused red log](focused-red.log) records three expected failures. Their retained JUnit
diagnostics establish [protocol nonblank rejection](protocol-red.xml), [schema minLength 1
rejection](schema-red.xml), and [native stdio MCP INTERNAL_ERROR](native-red.xml). The first
native request fills a nonempty value successfully; the following clear produces the failure.

The [focused green log](focused-green.log) records 81 passing tests: 28 protocol contracts,
20 MCP catalog/schema contracts, 31 Scene2D action tests and two native markup MCP scenarios.
Touched Checkstyle tasks also pass. The protocol and schema tests preserve null, missing-field
and maximum-length guards while allowing empty and whitespace observed values.

The final [full clean check and Javadoc gate](full-gate.log) passed in 2m 15s with all 81 Gradle
tasks executed: 1,067 tests passed, zero failed and one existing ACL-provider test was skipped.
It includes the native fixtures, protocol/MCP contracts, parity benchmarks, Checkstyle,
published-license verification and Javadoc. [verification.json](verification.json) records the
module counts, exact skipped test and evidence SHA-256 values. Existing javac warnings remain;
`--warning-mode=fail` is not a claim that javac ran with `-Werror` or that the build is warning-free.

## Commands

All invocations run from the harness worktree through this isolation prefix:

```bash
/home/tjaaskel/git/libgdx-agent-bootstrap/scripts/isolated-run.sh \
  env -u WAYLAND_DISPLAY XDG_SESSION_TYPE=x11 LIBGL_ALWAYS_SOFTWARE=1 \
  __GLX_VENDOR_LIBRARY_NAME=mesa xvfb-run -a ./gradlew
```

The failing regression selectors were:

```text
:harness-protocol:test --tests '*ProtocolJsonContractTest.actionObservedStateAllowsEmptyAndWhitespaceWithTheExistingStringBound'
:harness-mcp:test --tests '*HarnessToolCatalogTest.actionObservedStateSchemaAllowsEmptyWithoutRemovingItsBound'
:harness-fixtures:test --tests '*MarkupFixtureEndToEndTest.clearingMarkupTextFieldReturnsEmptyStateAndAllowsFurtherInputThroughProductionMcp'
--continue --no-daemon --console=plain --warning-mode=fail
```

The focused green selectors were:

```text
:harness-protocol:test --tests '*ProtocolJsonContractTest'
:harness-mcp:test --tests '*HarnessToolCatalogTest'
:harness-scene2d:test --tests '*Scene2dActionEndToEndTest'
:harness-fixtures:test --tests '*MarkupFixtureEndToEndTest'
:harness-protocol:checkstyleMain :harness-protocol:checkstyleTest
:harness-mcp:checkstyleMain :harness-mcp:checkstyleTest :harness-fixtures:checkstyleTest
--continue --no-daemon --console=plain --warning-mode=fail
```

The complete source gate uses the same prefix with:

```text
clean check javadoc --continue --no-daemon --console=plain --warning-mode=fail
```

## Native evidence

The existing markup fixture and its authored visual treatment are reused. Four real MCP actions
fill `Before clearing`, clear to `""`, clear again, then type `Recovered`. Each retained
`step-N.json` reports advancing revisions and an explicit string `observedState`. Each
`runtime-N.json` reports `EQUAL` against independently owned application model state. Fresh
semantic queries also verify each value, including both empty states; the process exits cleanly.

The empty and recovered PNGs are obtained with `ui_screenshot` and bounded `ui_artifact_read`,
whose existing fixture client verifies receipt length and SHA-256. These captures show field
content and preservation of the existing fixture appearance. They do not qualify the fixture's
whole-screen layout or constitute a new visual-direction approval.

Both current captures were inspected at their original 1280×720 resolution:
[empty field](step-1.png) and [recovered input](step-3.png). The cleared field contains no text;
the recovered field visibly reads `Recovered` with the existing input cursor and treatment.

Independent source review found no blocker in the response bound, schema/golden change or real
MCP regression. Logs retain diagnostic content with trailing whitespace removed.
