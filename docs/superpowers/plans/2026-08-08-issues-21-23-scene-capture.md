# Issues 21–23 Scene2D Ownership and Capture Efficiency Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close GitHub issues #21 (session-bound render-thread enforcement), #22 (snapshots only for active scenario/navigation consumers), and #23 (no internal base64 PNG round trips) with typed errors, additive runner APIs, unchanged JSON wire shape, and a documented byte-clone/ownership budget.

**Architecture:** `Scene2dSession` gains a `requireOwnerThread(operation)` guard on every method that reads or mutates Stage, actors, adapters, semantic metadata, or completed-frame state; failures are `HarnessException` with a new `RENDER_THREAD_VIOLATION` code that maps to a new `render-thread-violation` protocol wire code. `Scene2dSession.completedFrame` builds a snapshot only when the scenario or navigation runner reports active runs (`hasActiveRuns()` added to both runners). Protocol PNG result models (`Screenshot`, `InspectCompare`, `TypographyDiagnostic`, `LayoutDiagnostic`) hold owned `byte[]` with Jackson `@JsonProperty` renames so the base64 wire keys stay identical; the MCP handler publishes bytes directly and no longer serializes an unused full inline result on the screenshot path.

**Tech Stack:** Java 25, Gradle wrapper (`--no-daemon --console=plain --warning-mode=fail`), JUnit 5, Jackson 2.x records support, libGDX 1.14.2 Scene2D/LWJGL3, `com.sun.management.ThreadMXBean` for the allocation guard, `xvfb-run` for real LWJGL3 smoke tests on headless Linux.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-08-08-issues-8-26-release-design.md` (approved 2026-08-08), cluster 4 ("Scene2D ownership and capture efficiency"). Live issue bodies #21, #22, #23 remain authoritative.
- This branch (`fix/issues-21-23-scene-capture`) was cut from `origin/main` @ `c5fe5df`, before the #8–#13 cluster merges. When rebased onto merged main, renumber `docs/adr/0031-render-thread-ownership.md` to the next free ADR number and keep its title.
- JDK 25 baseline, `--release 25`, `-Xlint:all`, warnings fail the build; no preview/incubator APIs.
- Real LWJGL3 tests on Linux require Xvfb: run them as `xvfb-run -a ./gradlew …`.
- No sleeps for synchronization: use latches, barriers, injected clocks, and deadlines only.
- Public protocol JSON wire shape is invariant: keys `pngBase64`, `currentPngBase64`, `heatmapPngBase64` keep their exact names and base64 values; `errors.json` golden covers every `ProtocolError.Code` (the contract test asserts full enum coverage), so any new code requires a new golden entry.
- `ProtocolError.Code.fromCore` maps by `ErrorCode.name()`: a new core `ErrorCode` REQUIRES a matching `ProtocolError.Code` constant or runtime `IllegalArgumentException`.
- New public API is additive only: `Scene2dScenarioRunner.hasActiveRuns()`, `Scene2dNavigationRunner.hasActiveRuns()`, and the `byte[]`-typed protocol record accessors replace the `String`-typed ones (wire-compatible, sanctioned by the design: "Internal byte ownership may change while serialized screenshot JSON stays compatible").
- Every task ends with a commit that leaves the tree compiling and its focused tests green. Do not run formatters, linters, or the full suite mid-task; Task 9 runs the repository gate.
- Clone/ownership budget for PNG bytes (documented in Task 1): one defensive clone at each ownership boundary (`CapturedImage` constructor and accessor — existing; `Screenshot` constructor and accessor — new), zero base64 conversion on the MCP publication path, and base64 encoding only when Jackson actually serializes an inline JSON representation.

---

### Task 1 (#23): Byte-owned `Result.Screenshot` with unchanged base64 wire key

**Files:**
- Modify: `harness-protocol/src/main/java/dev/gdx/uiharness/protocol/HarnessResponse.java:371-418` (the `Screenshot` record, imports at 39-42)
- Modify: `harness-mcp/src/main/java/dev/gdx/uiharness/mcp/HarnessToolHandler.java:345-352` (screenshot branch), remove `java.util.Base64` import (line 24) only if no other branch uses it (it is still used at lines 384-496 until Task 2 — leave the import until Task 2)
- Modify: `harness-protocol/src/test/java/dev/gdx/uiharness/protocol/ProtocolJsonContractTest.java:421-437` (rewrite `screenshotPayloadAboveGenericStringLimitRoundTrips`)

**Interfaces:**
- Consumes: `dev.gdx.uiharness.core.capture.CapturedImage` (unchanged).
- Produces: `HarnessResponse.Result.Screenshot(byte[] pngBytes, String sha256, long frame, long revision, int width, int height, double scaleX, double scaleY)` with `pngBytes()` accessor; JSON serializes as `{"pngBase64":"…","sha256":"…","frame":…,"revision":…,"width":…,"height":…,"scaleX":…,"scaleY":…}`; `MAX_PNG_BYTES` unchanged; `MAX_BASE64_LENGTH` stays until Task 2 deletes it. Consumed by Tasks 2 and 3.

- [ ] **Step 1: Write the failing test**

Add `import static org.junit.jupiter.api.Assertions.assertArrayEquals;` to the file's static imports (the class already imports `assertInstanceOf`/`assertTrue`/`Base64`). Rewrite `screenshotPayloadAboveGenericStringLimitRoundTrips` in `ProtocolJsonContractTest.java` to construct the record with bytes and assert the base64 wire key:

```java
@Test void screenshotPayloadAboveGenericStringLimitRoundTrips() throws Exception {
    byte[] pngBytes = new byte[32 * 1_024];
    for (int index = 0; index < pngBytes.length; index++) {
        pngBytes[index] = (byte) (index % 251);
    }
    HarnessResponse source = new HarnessResponse.Success(ProtocolVersion.V1, "r", "s",
            new HarnessResponse.Result.Screenshot(pngBytes, "0".repeat(64),
                    1, 1, 100, 100, 1, 1));

    byte[] encoded = ProtocolJson.encode(source);
    HarnessResponse decoded = ProtocolJson.mapper().readValue(encoded, HarnessResponse.class);
    HarnessResponse.Result.Screenshot screenshot = assertInstanceOf(
            HarnessResponse.Result.Screenshot.class,
            assertInstanceOf(HarnessResponse.Success.class, decoded).result());

    assertArrayEquals(pngBytes, screenshot.pngBytes());
    String wire = ProtocolJson.mapper().writeValueAsString(screenshot);
    assertTrue(wire.contains("\"pngBase64\":\"" + Base64.getEncoder().encodeToString(pngBytes) + "\""));
    assertTrue(Base64.getEncoder().encodeToString(pngBytes).length() > ProtocolJson.MAX_STRING_LENGTH);
    assertFalse(wire.contains("@class"));
}
```

Also add (same file) a defensive-ownership test that fails against the old String record:

```java
@Test void screenshotDefensivelyOwnsItsBytes() {
    byte[] supplied = {1, 2, 3, 4, 5};
    HarnessResponse.Result.Screenshot screenshot =
            new HarnessResponse.Result.Screenshot(supplied, "0".repeat(64), 1, 1, 5, 1, 1, 1);
    supplied[0] = 99;
    assertArrayEquals(new byte[] {1, 2, 3, 4, 5}, screenshot.pngBytes());
    screenshot.pngBytes()[0] = 99;
    assertArrayEquals(new byte[] {1, 2, 3, 4, 5}, screenshot.pngBytes());
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :harness-protocol:test --tests 'dev.gdx.uiharness.protocol.ProtocolJsonContractTest' --no-daemon --console=plain --warning-mode=fail`
Expected: FAIL — compilation error: `Screenshot` has no constructor accepting `byte[]` / no `pngBytes()` method. A Java compilation failure is a valid red for a public-model change.

- [ ] **Step 3: Implement the byte-owned record**

In `HarnessResponse.java`:

- Add import `import com.fasterxml.jackson.annotation.JsonProperty;` (keep the existing `java.util.Base64` import until Task 2 removes the constructor decoders).
- Replace the `Screenshot` record (lines 371-418) with:

```java
        /** Bounded owned PNG bytes and capture metadata; the JSON wire keeps the pngBase64 key. */
        record Screenshot(
                @JsonProperty("pngBase64") byte[] pngBytes,
                String sha256,
                long frame,
                long revision,
                int width,
                int height,
                double scaleX,
                double scaleY) implements Result {
            /**
             * Maximum PNG bytes whose base64 form leaves room for the response envelope within
             * {@link ProtocolJson#MAX_RESPONSE_BYTES}.
             */
            public static final int MAX_PNG_BYTES =
                    ((ProtocolJson.MAX_RESPONSE_BYTES - 4_096) / 4) * 3;

            /** Validates owned PNG bytes and capture metadata. */
            public Screenshot {
                Objects.requireNonNull(pngBytes, "pngBytes");
                if (pngBytes.length == 0 || pngBytes.length > MAX_PNG_BYTES) {
                    throw new IllegalArgumentException(
                            "pngBytes exceeds protocol screenshot limit");
                }
                pngBytes = pngBytes.clone();
                ProtocolJson.requireText(sha256, "sha256");
                if (frame < 0 || revision < 0 || width <= 0 || height <= 0) {
                    throw new IllegalArgumentException("invalid screenshot metadata");
                }
                if (!Double.isFinite(scaleX) || scaleX <= 0
                        || !Double.isFinite(scaleY) || scaleY <= 0) {
                    throw new IllegalArgumentException("invalid screenshot scale");
                }
            }

            /** Returns a defensive copy of the owned PNG bytes. */
            @Override public byte[] pngBytes() {
                return pngBytes.clone();
            }

            static Screenshot fromCore(CapturedImage image) {
                byte[] pngBytes = image.pngBytes();
                if (pngBytes.length > MAX_PNG_BYTES) {
                    throw new HarnessException(ErrorCode.LIMIT_EXCEEDED,
                            "Captured PNG exceeds protocol response byte limit",
                            ErrorEvidence.ofDetails(Map.of(
                                    "limit", "response-byte-limit",
                                    "maximumBytes", Integer.toString(MAX_PNG_BYTES),
                                    "actualBytes", Integer.toString(pngBytes.length))));
                }
                return new Screenshot(pngBytes, image.sha256(), image.frame(),
                        image.revision(), image.width(), image.height(),
                        image.scale().x(), image.scale().y());
            }
        }
```

Do NOT delete `MAX_BASE64_LENGTH` in this task: `InspectCompare` still references it until Task 2. The `Screenshot` record no longer uses it; Task 2 deletes it once its last user is gone.

In `HarnessToolHandler.java`, replace the screenshot branch (lines 345-352):

```java
        if (result instanceof HarnessResponse.Result.Screenshot screenshot) {
            ArtifactReference reference = artifacts.publish(
                    "image/png", screenshot.pngBytes());
            LinkedHashMap<String, Object> content = content("screenshot-result");
            content.put("artifact", artifactMap(reference));
            content.put("frame", screenshot.frame());
            content.put("revision", screenshot.revision());
            content.put("width", screenshot.width());
            content.put("height", screenshot.height());
            content.put("scaleX", screenshot.scaleX());
            content.put("scaleY", screenshot.scaleY());
            return Map.copyOf(content);
        }
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :harness-protocol:test --tests 'dev.gdx.uiharness.protocol.ProtocolJsonContractTest' --no-daemon --console=plain --warning-mode=fail`
Expected: PASS — the golden `results.json` screenshot entry round-trips canonically (wire shape unchanged), the rewritten payload test passes, and defensive ownership holds.

Then run the MCP contract suite to prove the byte-for-byte publication test still passes:
Run: `./gradlew :harness-mcp:test --tests 'dev.gdx.uiharness.mcp.HarnessMcpServerContractTest' --no-daemon --console=plain --warning-mode=fail`
Expected: PASS — `screenshotAndLargeResultsUseInjectedOpaqueArtifactReferences` still sees `[1, 2, 3]` published.

- [ ] **Step 5: Commit**

```bash
git add harness-protocol/src/main/java/dev/gdx/uiharness/protocol/HarnessResponse.java harness-protocol/src/test/java/dev/gdx/uiharness/protocol/ProtocolJsonContractTest.java harness-mcp/src/main/java/dev/gdx/uiharness/mcp/HarnessToolHandler.java
git commit -m "fix(protocol): own screenshot PNG bytes with unchanged base64 wire shape"
```

---

### Task 2 (#23): Byte-owned compare, typography, and layout PNG evidence

**Files:**
- Modify: `harness-protocol/src/main/java/dev/gdx/uiharness/protocol/HarnessResponse.java:427-703` (`InspectCompare`, `TypographyDiagnostic`, `LayoutDiagnostic` records and their `fromCore` conversions)
- Modify: `harness-mcp/src/main/java/dev/gdx/uiharness/mcp/HarnessToolHandler.java:379-410,433-456,491-515` (compare/typography/layout branches)
- Modify: `harness-protocol/src/test/java/dev/gdx/uiharness/protocol/ProtocolJsonContractTest.java` (new wire-shape test)

**Interfaces:**
- Consumes: Task 1 `Screenshot.MAX_PNG_BYTES` (unchanged), `CurrentVisualData`, `HeatmapData`, `CurrentCaptureData` metadata records (unchanged).
- Produces: `InspectCompare(…, @JsonProperty("currentPngBase64") byte[] currentPngBytes, @JsonProperty("heatmapPngBase64") byte[] heatmapPngBytes)`, `TypographyDiagnostic(…, @JsonProperty("currentPngBase64") byte[] currentPngBytes)`, `LayoutDiagnostic(…, @JsonProperty("currentPngBase64") byte[] currentPngBytes)`; accessors `currentPngBytes()`, `heatmapPngBytes()` return defensive copies; constructor validates the SHA-256 digest directly on the bytes with no decode. Consumed by Task 3.

- [ ] **Step 1: Write the failing test**

Add to `ProtocolJsonContractTest.java` the imports `import java.security.MessageDigest;` and `import java.util.HexFormat;` (the file already imports `Base64`, `JsonNode`, and `assertInstanceOf`; `assertArrayEquals` was added in Task 1). Then add the test:

```java
@Test void pngEvidenceResultsKeepBase64WireKeys() throws Exception {
    byte[] current = {1, 2, 3, 4};
    byte[] heatmap = {5, 6, 7};
    String currentBase64 = Base64.getEncoder().encodeToString(current);
    String heatmapBase64 = Base64.getEncoder().encodeToString(heatmap);
    String currentSha = sha256Hex(current);
    String heatmapSha = sha256Hex(heatmap);
    HarnessResponse.SnapshotData snapshotData =
            new HarnessResponse.SnapshotData(1, 1, "root", List.of());
    String capturedAt = "2026-08-08T00:00:00Z";

    HarnessResponse.Result.InspectCompare comparison = new HarnessResponse.Result.InspectCompare(
            "converged", "pixel-exact-v1",
            new HarnessResponse.ReferenceVisualData(
                    "ref-1", "app", "source", "viewport", "0".repeat(64),
                    100, 100, 1.0, 1.0, capturedAt, snapshotData),
            new HarnessResponse.CurrentVisualData(
                    "session", "app", "viewport", currentSha,
                    1, 1, 100, 100, 1.0, 1.0, capturedAt, snapshotData),
            new HarnessResponse.MetricsData(0, 0.0, 0),
            List.of(), List.of(), List.of(),
            new HarnessResponse.HeatmapData(heatmapSha, 100, 100),
            List.of(), 1, 0,
            current, heatmap);
    JsonNode comparisonJson = ProtocolJson.mapper().valueToTree(comparison);
    assertEquals(currentBase64, comparisonJson.path("currentPngBase64").asText());
    assertEquals(heatmapBase64, comparisonJson.path("heatmapPngBase64").asText());
    assertEquals("converged", comparisonJson.path("status").asText());

    HarnessResponse.Result.TypographyDiagnostic typography =
            new HarnessResponse.Result.TypographyDiagnostic(
                    "pixel-sharp", "title-reference",
                    new HarnessResponse.CurrentCaptureData(
                            currentSha, 1, 1, 100, 100, 1.0, 1.0),
                    List.of(), List.of(), 0, current);
    JsonNode typographyJson = ProtocolJson.mapper().valueToTree(typography);
    assertEquals(currentBase64, typographyJson.path("currentPngBase64").asText());

    HarnessResponse.Result.LayoutDiagnostic layout = new HarnessResponse.Result.LayoutDiagnostic(
            "conformant", "layout-reference",
            new HarnessResponse.CurrentCaptureData(
                    currentSha, 1, 1, 100, 100, 1.0, 1.0),
            List.of(), null, null, List.of(), 0, current);
    JsonNode layoutJson = ProtocolJson.mapper().valueToTree(layout);
    assertEquals(currentBase64, layoutJson.path("currentPngBase64").asText());

    assertArrayEquals(current, comparison.currentPngBytes());
    assertArrayEquals(heatmap, comparison.heatmapPngBytes());
}
```

Add a local helper (the private `sha256(byte[])` in `HarnessResponse` is not visible here):

```java
private static String sha256Hex(byte[] content) throws Exception {
    return HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(content));
}
```

The constructor arguments above match the verified record components (`HarnessResponse.java:1005-1265`): `SnapshotData(revision, frame, rootId, nodes, contract)` with the 4-arg compatibility constructor; `ReferenceVisualData(referenceId, applicationId, sourceSessionId, viewportId, sha256, width, height, scaleX, scaleY, capturedAt, snapshot)`; `CurrentVisualData(sessionId, applicationId, viewportId, sha256, revision, frame, width, height, scaleX, scaleY, capturedAt, snapshot)` — its compact constructor requires `snapshot != null` and `revision/frame` matching the snapshot, satisfied here; `MetricsData(differingPixels, meanAbsoluteError, maximumChannelDelta)`; `HeatmapData(sha256, width, height)`. The test must FAIL to compile against the String-typed records.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :harness-protocol:test --tests 'dev.gdx.uiharness.protocol.ProtocolJsonContractTest' --no-daemon --console=plain --warning-mode=fail`
Expected: FAIL — compilation error: `InspectCompare`/`TypographyDiagnostic`/`LayoutDiagnostic` accept `String`, not `byte[]`.

- [ ] **Step 3: Implement byte-owned evidence**

In `HarnessResponse.java`:

- `InspectCompare`: rename components `String currentPngBase64, String heatmapPngBase64` to:
  ```java
  @JsonProperty("currentPngBase64") byte[] currentPngBytes,
  @JsonProperty("heatmapPngBase64") byte[] heatmapPngBytes
  ```
  In the compact constructor, replace the base64 length/decode/hash validation (lines ~480-515) with direct byte validation and clone:
  ```java
  if ((current == null) != (currentPngBytes == null)) {
      throw new IllegalArgumentException(
              "current metadata and PNG evidence must appear together");
  }
  if ((heatmap == null) != (heatmapPngBytes == null)) {
      throw new IllegalArgumentException(
              "heatmap metadata and PNG evidence must appear together");
  }
  // …keep the existing status/spatial/diagnostic checks unchanged…
  if (currentPngBytes != null) {
      if (currentPngBytes.length > Screenshot.MAX_PNG_BYTES) {
          throw new IllegalArgumentException(
                  "comparison PNG exceeds protocol response limit");
      }
      if (!sha256(currentPngBytes).equals(current.sha256())) {
          throw new IllegalArgumentException(
                  "comparison PNG hash does not match current metadata");
      }
      currentPngBytes = currentPngBytes.clone();
  }
  if (heatmapPngBytes != null) {
      if (heatmapPngBytes.length > Screenshot.MAX_PNG_BYTES) {
          throw new IllegalArgumentException(
                  "heatmap PNG exceeds protocol response limit");
      }
      if (!sha256(heatmapPngBytes).equals(heatmap.sha256())) {
          throw new IllegalArgumentException(
                  "heatmap PNG hash does not match metadata");
      }
      heatmapPngBytes = heatmapPngBytes.clone();
  }
  ```
  Add accessor overrides:
  ```java
  /** Returns a defensive copy of the owned current PNG bytes, or null. */
  @Override public byte[] currentPngBytes() {
      return currentPngBytes == null ? null : currentPngBytes.clone();
  }
  /** Returns a defensive copy of the owned heatmap PNG bytes, or null. */
  @Override public byte[] heatmapPngBytes() {
      return heatmapPngBytes == null ? null : heatmapPngBytes.clone();
  }
  ```
  Update the 10-arg compatibility constructor to `byte[] currentPngBytes` and pass it through; update `fromCore` to pass raw byte arrays:
  ```java
  static InspectCompare fromCore(VisualComparisonResult result) {
      Objects.requireNonNull(result, "result");
      byte[] png = result.current() == null ? null
              : result.current().image().pngBytes();
      byte[] heatmapPng = result.heatmap() == null ? null
              : result.heatmap().pngBytes();
      return new InspectCompare(
              wire(result.status().name()), result.policy().wireName(),
              result.reference() == null ? null
                      : ReferenceVisualData.fromCore(result.reference()),
              result.current() == null ? null
                      : CurrentVisualData.fromCore(result.current()),
              result.metrics() == null ? null
                      : MetricsData.fromCore(result.metrics()),
              result.differences().stream()
                      .map(DifferenceData::fromCore).toList(),
              result.regions().stream().map(RegionData::fromCore).toList(),
              result.heatmap() == null ? null
                      : HeatmapData.fromCore(result.heatmap()),
              result.diagnostics().stream()
                      .map(ComparisonDiagnosticData::fromCore).toList(),
              result.iterations(), result.elapsed().toMillis(), png,
              heatmapPng);
  }
  ```
- `TypographyDiagnostic`: rename `String currentPngBase64` to `@JsonProperty("currentPngBase64") byte[] currentPngBytes`; replace the decode/validation block with:
  ```java
  if (currentPngBytes != null) {
      if (currentPngBytes.length > Screenshot.MAX_PNG_BYTES
              || !sha256(currentPngBytes).equals(current.sha256())) {
          throw new IllegalArgumentException(
                  "typography PNG does not match bounded current metadata");
      }
      currentPngBytes = currentPngBytes.clone();
  }
  ```
  Add the defensive accessor override `currentPngBytes()`. Update `fromCore` to pass `result.current() == null ? null : result.current().pngBytes()`.
- `LayoutDiagnostic`: identical treatment (validation message "layout PNG does not match bounded current metadata").
- Remove the now-unused `java.util.Base64` import from `HarnessResponse.java`.

In `HarnessToolHandler.java`, replace the three PNG branches' decode+clone with direct publish:

```java
            if (comparison.current() != null) {
                if (comparison.currentPngBytes() == null) {
                    throw new IllegalArgumentException(
                            "accepted current evidence is missing PNG bytes");
                }
                ArtifactReference current = artifacts.publish(
                        "image/png", comparison.currentPngBytes());
                // …the sha256 receipt verification and content.put calls stay unchanged…
            }
            if (comparison.heatmap() != null) {
                if (comparison.heatmapPngBytes() == null) {
                    throw new IllegalArgumentException(
                            "accepted heatmap evidence is missing PNG bytes");
                }
                ArtifactReference heatmap = artifacts.publish(
                        "image/png", comparison.heatmapPngBytes());
                // …receipt verification stays unchanged…
            }
```
Apply the same substitution for the typography branch (`typography.currentPngBytes()`) and the layout branch (`layout.currentPngBytes()`). Delete `java.util.Base64` from the imports once all decode sites are gone.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :harness-protocol:test --tests 'dev.gdx.uiharness.protocol.ProtocolJsonContractTest' --no-daemon --console=plain --warning-mode=fail`
Expected: PASS — new wire-shape test and all existing contract tests pass.

Run: `./gradlew :harness-mcp:test --no-daemon --console=plain --warning-mode=fail`
Expected: PASS — `typographyFailurePublishesBoundedEvidenceArtifact`, `layoutFailurePublishesBoundedEvidenceArtifact`, and every other MCP contract test pass with the byte-typed records.

- [ ] **Step 5: Commit**

```bash
git add harness-protocol/src/main/java/dev/gdx/uiharness/protocol/HarnessResponse.java harness-protocol/src/test/java/dev/gdx/uiharness/protocol/ProtocolJsonContractTest.java harness-mcp/src/main/java/dev/gdx/uiharness/mcp/HarnessToolHandler.java
git commit -m "fix(protocol): own compare/typography/layout PNG bytes with unchanged base64 wire keys"
```

---

### Task 3 (#23): No eager full-result serialization on the screenshot path + allocation guard

**Files:**
- Modify: `harness-mcp/src/main/java/dev/gdx/uiharness/mcp/HarnessToolHandler.java:253-254` (remove eager `encodeResult`), and every branch that uses `encoded` (Sessions, Snapshot, Query, Action, Assertion, Wait, InspectCompare, TypographyDiagnostic, LayoutDiagnostic)
- Create: `harness-mcp/src/test/java/dev/gdx/uiharness/mcp/HarnessToolHandlerScreenshotPublicationTest.java`

**Interfaces:**
- Consumes: Task 1/2 byte-owned `Screenshot` with `pngBytes()` and `MAX_PNG_BYTES`.
- Produces: `structured(HarnessResponse.Result)` computes `encodeResult(result)` lazily per branch; the screenshot branch performs zero full-result serialization; the publication path performs no base64 conversion.

- [ ] **Step 1: Write the failing allocation test**

Create `harness-mcp/src/test/java/dev/gdx/uiharness/mcp/HarnessToolHandlerScreenshotPublicationTest.java`:

```java
package dev.gdx.uiharness.mcp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gdx.uiharness.core.action.Action;
import dev.gdx.uiharness.core.action.ActionResult;
import dev.gdx.uiharness.core.action.Harness;
import dev.gdx.uiharness.core.capture.CaptureRequest;
import dev.gdx.uiharness.core.capture.CapturedImage;
import dev.gdx.uiharness.core.capture.ScreenCapture;
import dev.gdx.uiharness.core.locator.Locator;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import dev.gdx.uiharness.core.time.Deadline;
import dev.gdx.uiharness.core.time.MonotonicClock;
import dev.gdx.uiharness.protocol.CapabilitySet;
import dev.gdx.uiharness.protocol.HarnessProtocolService;
import dev.gdx.uiharness.protocol.HarnessResponse;
import io.modelcontextprotocol.spec.McpSchema;
import java.lang.management.ManagementFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class HarnessToolHandlerScreenshotPublicationTest {
    private static final MonotonicClock CLOCK = System::nanoTime;
    private static final int MAX_PNG_BYTES = HarnessResponse.Result.Screenshot.MAX_PNG_BYTES;

    @Test void maxSizeScreenshotPublishesExactBytesWithoutBase64RoundTripAllocation() {
        byte[] payload = deterministicPng(MAX_PNG_BYTES);
        String sha = sha256(payload);
        CapturedImage image = new CapturedImage(payload, sha, 1, 1, 8_192, 8_192,
                new CapturedImage.Scale(1, 1));
        RecordingPublisher artifacts = new RecordingPublisher();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                HarnessToolHandler handler = new HarnessToolHandler(
                        serviceWith(image), artifacts, executor, 1_024)) {
            // Warm up the JIT so the measurement reflects steady-state allocation.
            publish(handler);

            com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean)
                    ManagementFactory.getThreadMXBean();
            long threadId = Thread.currentThread().getId();
            long before = mx.getThreadAllocatedBytes(threadId);
            McpSchema.CallToolResult result = publish(handler);
            long allocated = mx.getThreadAllocatedBytes(threadId) - before;

            assertFalse(result.isError());
            assertArrayEquals(payload, artifacts.lastBytes,
                    "published bytes must equal the captured PNG bytes exactly");
            assertEquals(sha, artifacts.lastReference.sha256(),
                    "digest receipt must match the captured bytes");
            assertEquals((long) MAX_PNG_BYTES, artifacts.lastReference.byteLength(),
                    "length receipt must match the captured bytes");
            assertTrue(allocated < 4L * MAX_PNG_BYTES,
                    "screenshot publication allocated " + allocated
                            + " bytes; the base64 round trip would exceed 4x the payload");
        }
    }

    private static McpSchema.CallToolResult publish(HarnessToolHandler handler) {
        return handler.handle(McpSchema.CallToolRequest.builder("ui_screenshot")
                .arguments(Map.of(
                        "sessionId", "game",
                        "maxWidth", 8_192, "maxHeight", 8_192,
                        "maxPixels", 33_554_432L,
                        "maxPngBytes", MAX_PNG_BYTES))
                .build()).block(Duration.ofSeconds(60));
    }

    private static HarnessProtocolService serviceWith(CapturedImage image) {
        ScreenCapture capture = new ScreenCapture() {
            @Override public CompletionStage<CapturedImage> capture(
                    CaptureRequest request, Deadline deadline) {
                return CompletableFuture.completedFuture(image);
            }

            @Override public void close() {}
        };
        Harness harness = new Harness() {
            @Override public CompletionStage<ActionResult> perform(
                    Locator locator, Action action, Deadline deadline) {
                return CompletableFuture.completedFuture(
                        new ActionResult(1, 1, "unused", Map.of()));
            }

            @Override public CompletionStage<SemanticSnapshot> snapshot(Deadline deadline) {
                return CompletableFuture.completedFuture(
                        new SemanticSnapshot(1, 1, "root", Map.of()));
            }
        };
        return new HarnessProtocolService(
                Map.of("game", new HarnessProtocolService.Session(
                        harness, null, null, capture,
                        new CapabilitySet(List.of("screenshot")), null)),
                Map.of(), Map.of(), CLOCK, Runnable::run);
    }

    private static byte[] deterministicPng(int size) {
        byte[] bytes = new byte[size];
        for (int index = 0; index < size; index++) {
            bytes[index] = (byte) (index % 251);
        }
        return bytes;
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 unavailable", impossible);
        }
    }

    private static final class RecordingPublisher implements ArtifactReference.Publisher {
        private final AtomicInteger counter = new AtomicInteger();
        private byte[] lastBytes;
        private ArtifactReference lastReference;

        @Override public ArtifactReference publish(String mediaType, byte[] content) {
            lastBytes = content.clone();
            lastReference = new ArtifactReference(
                    "artifact:" + counter.incrementAndGet(), mediaType,
                    content.length, sha256(content));
            return lastReference;
        }
    }
}
```

If `HarnessProtocolService.Session`'s 6-argument compatibility constructor rejects the `null` locator/waits/traces values during validation, construct it through the full 13-argument record constructor with `Optional.empty()` for the seven coordinator fields; the `Runnable::run` protocol executor is what keeps the pipeline on the calling thread.

- [ ] **Step 2: Run to verify failure**

Run: `xvfb-run -a ./gradlew :harness-mcp:test --tests 'dev.gdx.uiharness.mcp.HarnessToolHandlerScreenshotPublicationTest' --no-daemon --console=plain --warning-mode=fail`
Expected: FAIL on the allocation assertion — the current code eagerly runs `encodeResult(result)` (serializing the full base64 payload) and then publishes a defensive copy; measured allocation is roughly 7–8x `MAX_PNG_BYTES`. (If the assertion passes spuriously, the service wiring is not running on the measuring thread — fix the executor wiring before proceeding.)

- [ ] **Step 3: Implement lazy full-result encoding**

In `HarnessToolHandler.structured(...)`:
- Delete line 254 `byte[] encoded = encodeResult(result);` from the method top.
- Add `byte[] encoded = encodeResult(result);` as the first statement of ONLY the branches that consume it: `Sessions`, `Snapshot`, `Query`, `Action`, `Assertion`, `Wait`, `InspectCompare`, `TypographyDiagnostic`, `LayoutDiagnostic`. The `Screenshot`, `Capabilities`, `ScenarioList`, `ScenarioStart`, `LayoutValidation`, and matrix branches must NOT compute `encoded`.

- [ ] **Step 4: Run to verify pass**

Run: `xvfb-run -a ./gradlew :harness-mcp:test --tests 'dev.gdx.uiharness.mcp.HarnessToolHandlerScreenshotPublicationTest' --no-daemon --console=plain --warning-mode=fail`
Expected: PASS — allocation below `4 * MAX_PNG_BYTES`, published bytes/digest/length receipts equal the captured payload.

Run: `xvfb-run -a ./gradlew :harness-mcp:test --no-daemon --console=plain --warning-mode=fail`
Expected: PASS — the offload tests (`screenshotAndLargeResultsUseInjectedOpaqueArtifactReferences`, large-result offload) still publish and offload identically.

- [ ] **Step 5: Commit**

```bash
git add harness-mcp/src/main/java/dev/gdx/uiharness/mcp/HarnessToolHandler.java harness-mcp/src/test/java/dev/gdx/uiharness/mcp/HarnessToolHandlerScreenshotPublicationTest.java
git commit -m "perf(mcp): publish screenshot bytes directly without eager full-result serialization"
```

---

### Task 4 (#21): Typed `render-thread-violation` error code and threading ADR

**Files:**
- Modify: `harness-core/src/main/java/dev/gdx/uiharness/core/error/ErrorCode.java` (add enum constant after `RENDER_THREAD_FAILURE`)
- Modify: `harness-protocol/src/main/java/dev/gdx/uiharness/protocol/ProtocolError.java` (add `Code.RENDER_THREAD_VIOLATION("render-thread-violation")`)
- Modify: `harness-protocol/src/test/resources/contracts/v1/errors.json` (add golden entry)
- Create: `docs/adr/0031-render-thread-ownership.md`

**Interfaces:**
- Produces: `ErrorCode.RENDER_THREAD_VIOLATION` (core), `ProtocolError.Code.RENDER_THREAD_VIOLATION` with wire name `render-thread-violation`, and a golden `errors.json` entry. Consumed by Task 5.

- [ ] **Step 1: Write the failing golden**

Add to `harness-protocol/src/test/resources/contracts/v1/errors.json` (same JSON array, exact field order matching the `ProtocolError` record component order: code, message, requestId, sessionId, locator, elapsedMillis, lastSnapshotRevision, traceReference, candidates, details, traceId, suggestions — null fields are omitted by the mapper's `NON_NULL` inclusion):

```json
{"name":"render-thread-violation","value":{"code":"render-thread-violation","message":"Scene2D session access requires the owning render thread","requestId":"req-error","sessionId":"game","elapsedMillis":0,"candidates":[],"details":{"operation":"snapshot"},"suggestions":[]}}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :harness-protocol:test --tests 'dev.gdx.uiharness.protocol.ProtocolJsonContractTest' --no-daemon --console=plain --warning-mode=fail`
Expected: FAIL — `fromWireName("render-thread-violation")` rejects the unknown wire name while deserializing the golden; the enum-coverage assertion also fails.

- [ ] **Step 3: Implement the code**

In `ErrorCode.java`, after `RENDER_THREAD_FAILURE`:

```java
    /** A Scene2D session method was invoked from a thread other than its owning render thread. */
    RENDER_THREAD_VIOLATION,
```

In `ProtocolError.java`, in `Code`, after `RENDER_THREAD_FAILURE("render-thread-failure")`:

```java
        RENDER_THREAD_VIOLATION("render-thread-violation"),
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :harness-protocol:test --tests 'dev.gdx.uiharness.protocol.ProtocolJsonContractTest' --no-daemon --console=plain --warning-mode=fail`
Expected: PASS — the new golden round-trips canonically and the enum-coverage assertion holds.

- [ ] **Step 5: Write ADR 0031**

Create `docs/adr/0031-render-thread-ownership.md` with the exact header format used by every existing ADR (see `docs/adr/0017-bounded-scenario-lifecycle.md`):

```markdown
# ADR 0031: Render-thread session ownership

- Status: Accepted
- Date: 2026-08-08
```

Then `## Context`, `## Decision`, and `## Consequences` sections with the following content:

- Context: `Scene2dSession` captured `ownerThread` at construction but only `completedFrame` enforced it; off-thread `snapshot`, `stateActionContract`, `typography`, `layout`, metadata facade, and adapter-registry access could race or return nondeterministic Scene2D reads instead of failing fast. Issue #21.
- Decision: every `Scene2dSession` method that reads or mutates the Stage, actors, adapters, semantic metadata, or completed-frame state verifies `Thread.currentThread() == ownerThread` and fails immediately with `HarnessException(ErrorCode.RENDER_THREAD_VIOLATION)` carrying the operation name, owner thread name, and caller thread name. Non-owner work MUST route through `RenderThreadScheduler` (submit from any thread, drain on the owner). `isOpen()` and `close()` stay thread-agnostic. The protocol wire adds the stable `render-thread-violation` error code; MCP receives the existing typed failure translation unchanged. The success path stays allocation-light (one reference comparison).
- Consequences: off-thread misuse fails fast with actionable evidence instead of racing; scheduler-routed caller-thread waits remain supported; a new stable error code is visible end to end (core, protocol, MCP diagnostics); correct render-thread access has no measurable cost.

- [ ] **Step 6: Commit**

```bash
git add harness-core/src/main/java/dev/gdx/uiharness/core/error/ErrorCode.java harness-protocol/src/main/java/dev/gdx/uiharness/protocol/ProtocolError.java harness-protocol/src/test/resources/contracts/v1/errors.json docs/adr/0031-render-thread-ownership.md
git commit -m "feat(core): typed render-thread-violation error code and threading ADR"
```

---

### Task 5 (#21): Scene2dSession owner-thread guards and boundary fixture

**Files:**
- Modify: `harness-scene2d/src/main/java/dev/gdx/uiharness/scene2d/Scene2dSession.java:48-135`
- Modify: `harness-scene2d/src/test/java/dev/gdx/uiharness/scene2d/Scene2dScenarioRunnerTest.java:62-71` (update `completedStageFramesCannotBeReadOffTheRenderThread`)
- Create: `harness-scene2d/src/test/java/dev/gdx/uiharness/scene2d/Scene2dSessionTest.java`

**Interfaces:**
- Consumes: `ErrorCode.RENDER_THREAD_VIOLATION` (Task 4), existing `RenderThreadScheduler`, `Scene2dScenarioRunner`, `Scene2dNavigationRunner`.
- Produces: `Scene2dSession.requireOwnerThread(String operation)`; every boundary method (`semantics()`, `adapters()`, `snapshot`, both `completedFrame` overloads, `stateActionContract`, `typography`, `layout`, `actorToken`) fails with `HarnessException(ErrorCode.RENDER_THREAD_VIOLATION)` off-thread; `isOpen()`/`close()` unchanged. Consumed by Task 6 (which edits `completedFrame` again).

- [ ] **Step 1: Write the failing tests**

Update `Scene2dScenarioRunnerTest.completedStageFramesCannotBeReadOffTheRenderThread` (lines 62-71) to assert the typed error. Add the two missing imports at the top of the file (`import dev.gdx.uiharness.core.error.ErrorCode;` and `import dev.gdx.uiharness.core.error.HarnessException;`; the static `assertInstanceOf`/`assertThrows`/`assertEquals` imports already exist):

```java
    @Test void completedStageFramesCannotBeReadOffTheRenderThread() {
        try (Fixture fixture = new Fixture();
                ExecutorService caller = Executors.newVirtualThreadPerTaskExecutor()) {
            var failure = assertThrows(
                    java.util.concurrent.CompletionException.class,
                    () -> java.util.concurrent.CompletableFuture
                            .runAsync(
                                    () -> fixture.session.completedFrame(fixture.runner, 1, 1),
                                    caller)
                            .join());

            HarnessException renderThread = assertInstanceOf(
                    HarnessException.class, failure.getCause());
            assertEquals(ErrorCode.RENDER_THREAD_VIOLATION, renderThread.code());
        }
    }
```

Create `harness-scene2d/src/test/java/dev/gdx/uiharness/scene2d/Scene2dSessionTest.java`:

```java
package dev.gdx.uiharness.scene2d;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import dev.gdx.uiharness.core.error.ErrorCode;
import dev.gdx.uiharness.core.error.HarnessException;
import dev.gdx.uiharness.core.scenario.ScenarioDefinition;
import dev.gdx.uiharness.core.scenario.ScenarioLifecycle;
import dev.gdx.uiharness.core.scenario.ScenarioRegistry;
import dev.gdx.uiharness.core.scenario.ScenarioRequest;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import dev.gdx.uiharness.core.time.Deadline;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class Scene2dSessionTest {
    @Test void everyBoundaryMethodSucceedsOnTheOwningThread() {
        try (BoundaryFixture fixture = new BoundaryFixture()) {
            assertDoesNotThrow(fixture.session::isOpen);
            assertDoesNotThrow(() -> fixture.session.semantics());
            assertDoesNotThrow(() -> fixture.session.adapters());
            assertDoesNotThrow(() -> fixture.session.snapshot(1, 1));
            assertDoesNotThrow(() -> fixture.session.stateActionContract(1, 1));
            assertDoesNotThrow(() -> fixture.session.typography(1, 1,
                    BoundaryFixture.typographyContext()));
            assertDoesNotThrow(() -> fixture.session.layout(1, 1,
                    BoundaryFixture.layoutContext()));
            assertDoesNotThrow(() -> fixture.session.actorToken(new Actor()));
            assertDoesNotThrow(() -> fixture.session.completedFrame(
                    fixture.scenarios, 1, 1));
            assertDoesNotThrow(() -> fixture.session.completedFrame(
                    fixture.scenarios, fixture.navigation, 1, 1));
            assertDoesNotThrow(fixture.session::close);
        }
    }

    @Test void everyBoundaryMethodFailsWithTypedRenderThreadErrorOffThread() throws Exception {
        try (BoundaryFixture fixture = new BoundaryFixture();
                ExecutorService caller = Executors.newVirtualThreadPerTaskExecutor()) {
            Actor actor = new Actor();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Runnable[] operations = {
                    () -> fixture.session.semantics(),
                    () -> fixture.session.adapters(),
                    () -> fixture.session.snapshot(1, 1),
                    () -> fixture.session.stateActionContract(1, 1),
                    () -> fixture.session.typography(1, 1,
                            BoundaryFixture.typographyContext()),
                    () -> fixture.session.layout(1, 1,
                            BoundaryFixture.layoutContext()),
                    () -> fixture.session.actorToken(actor),
                    () -> fixture.session.completedFrame(fixture.scenarios, 1, 1),
                    () -> fixture.session.completedFrame(
                            fixture.scenarios, fixture.navigation, 1, 1)};
            for (Runnable operation : operations) {
                failure.set(null);
                caller.submit(() -> {
                    try {
                        operation.run();
                    } catch (Throwable thrown) {
                        failure.set(thrown);
                    }
                }).get();
                HarnessException renderThread = assertInstanceOf(
                        HarnessException.class, failure.get());
                assertEquals(ErrorCode.RENDER_THREAD_VIOLATION, renderThread.code());
                assertTrue(renderThread.getMessage().contains("render thread"));
                assertTrue(renderThread.evidence().details().containsKey("operation"));
                assertTrue(renderThread.evidence().details().containsKey("ownerThread"));
                assertTrue(renderThread.evidence().details().containsKey("callerThread"));
            }
        }
    }

    @Test void callerThreadWaitsRouteThroughTheSchedulerAndRemainSupported() throws Exception {
        try (BoundaryFixture fixture = new BoundaryFixture();
                ExecutorService caller = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<SemanticSnapshot> routed = caller.submit(
                    () -> fixture.scheduler.submit(
                            () -> fixture.session.snapshot(1, 1),
                            Deadline.after(fixture.clock, Duration.ofSeconds(5)))
                            .toCompletableFuture().join());
            for (int index = 0; index < 4 && !routed.isDone(); index++) {
                fixture.scheduler.drain();
            }
            SemanticSnapshot snapshot = routed.get();
            assertEquals(1, snapshot.revision());
            assertEquals(1, snapshot.frame());
        }
    }
}
```

Add the fixture and helpers (same file):

```java
    private static final class BoundaryFixture implements AutoCloseable {
        final Stage stage = Scene2dTestSupport.stage();
        final ControlledStageClock clock = new ControlledStageClock(stage, Duration.ofMillis(10));
        final RenderThreadScheduler scheduler = new RenderThreadScheduler(16);
        final Scene2dSession session = new Scene2dSession(stage);
        final ScenarioRegistry registry = new ScenarioRegistry();
        final Scene2dScenarioRunner scenarios;
        final Scene2dNavigationRunner navigation;

        BoundaryFixture() {
            registry.register(
                    new ScenarioDefinition(ScenarioDefinition.SCHEMA_VERSION,
                            "boundary", "1.0.0", "app", List.of("desktop"), 1,
                            Duration.ofSeconds(2)),
                    new ScenarioLifecycle() {
                        @Override public void setup(ScenarioRequest request) {}
                        @Override public void reset(ScenarioRequest request) {}
                        @Override public boolean ready(ScenarioRequest request) { return true; }
                        @Override public String startStateIdentity(
                                ScenarioRequest request, SemanticSnapshot snapshot) {
                            return "ready";
                        }
                        @Override public void cleanup(ScenarioRequest request) {}
                    });
            scenarios = new Scene2dScenarioRunner(registry, scheduler, clock, delay -> () -> {});
            Scene2dInputDispatcher input =
                    new Scene2dInputDispatcher(stage, stage);
            navigation = new Scene2dNavigationRunner(
                    scenarios, session, input, scheduler, clock, delay -> () -> {},
                    clock::revision, clock::frame,
                    new Scene2dNavigationRunner.Scenario(
                            "boundary", 7, Map.of(), "desktop", "app", "process", "session"),
                    8);
        }

        static TypographyCaptureContext typographyContext() {
            return new TypographyCaptureContext(
                    "app", "main", "artifact:1", "0".repeat(64),
                    800, 600, 800, 600, Map.of());
        }

        static LayoutCaptureContext layoutContext() {
            return new LayoutCaptureContext(
                    "app", "main", "artifact:1", "0".repeat(64),
                    800, 600, 800, 600, 1, Set.of());
        }

        @Override public void close() {
            navigation.close();
            scenarios.close();
            session.close();
            scheduler.close();
            clock.close();
            stage.dispose();
        }
    }
```

The `callerThreadWaitsRouteThroughTheSchedulerAndRemainSupported` test drains the scheduler in a bounded loop until the routed future completes (the established pattern in this module's test fixtures); drain is idempotent when the queue is empty, so the loop is deterministic.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :harness-scene2d:test --tests 'dev.gdx.uiharness.scene2d.Scene2dScenarioRunnerTest' --no-daemon --console=plain --warning-mode=fail`
Expected: FAIL — `assertInstanceOf(HarnessException.class, …)` fails because the current cause is `IllegalStateException`.

Run: `./gradlew :harness-scene2d:test --tests 'dev.gdx.uiharness.scene2d.Scene2dSessionTest' --no-daemon --console=plain --warning-mode=fail`
Expected: FAIL — off-thread operations throw `IllegalStateException`/`RuntimeException` instead of `HarnessException(RENDER_THREAD_VIOLATION)`.

- [ ] **Step 3: Implement the guards**

In `Scene2dSession.java`:

- Add after `requireOpen()` (line ~129):

```java
    private void requireOwnerThread(String operation) {
        if (Thread.currentThread() != ownerThread) {
            throw new HarnessException(
                    ErrorCode.RENDER_THREAD_VIOLATION,
                    "Scene2D session " + operation + " requires the owning render thread;"
                            + " route caller-thread work through RenderThreadScheduler",
                    ErrorEvidence.ofDetails(Map.of(
                            "operation", operation,
                            "ownerThread", ownerThread.getName(),
                            "callerThread", Thread.currentThread().getName())));
        }
    }
```

- Insert `requireOwnerThread("…")` as the first statement of: `semantics()` ("semantics"), `adapters()` ("adapters"), `snapshot` ("snapshot"), both `completedFrame` overloads ("completedFrame", replacing the existing `IllegalStateException` blocks at lines 66-69 and 79-82), `stateActionContract` ("stateActionContract"), `typography` ("typography"), `layout` ("layout"), `actorToken` ("actorToken"). Keep `requireOpen()` where it already exists. Leave `isOpen()` and `close()` ungated.

The completedFrame methods become:

```java
    public void completedFrame(Scene2dScenarioRunner runner, long revision, long frame) {
        requireOwnerThread("completedFrame");
        Objects.requireNonNull(runner, "runner").completedFrame(snapshot(revision, frame));
    }

    public void completedFrame(
            Scene2dScenarioRunner scenarioRunner,
            Scene2dNavigationRunner navigationRunner,
            long revision,
            long frame) {
        requireOwnerThread("completedFrame");
        SemanticSnapshot snapshot = snapshot(revision, frame);
        Objects.requireNonNull(scenarioRunner, "scenarioRunner").completedFrame(snapshot);
        Objects.requireNonNull(navigationRunner, "navigationRunner").completedFrame(snapshot);
    }
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :harness-scene2d:test --tests 'dev.gdx.uiharness.scene2d.Scene2dSessionTest' --no-daemon --console=plain --warning-mode=fail` and `./gradlew :harness-scene2d:test --tests 'dev.gdx.uiharness.scene2d.Scene2dScenarioRunnerTest' --no-daemon --console=plain --warning-mode=fail`
Expected: both PASS.

Then the whole scene2d module (all existing tests construct and use the session on one thread, so the owner check is a no-op for them):
Run: `./gradlew :harness-scene2d:test --no-daemon --console=plain --warning-mode=fail`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add harness-scene2d/src/main/java/dev/gdx/uiharness/scene2d/Scene2dSession.java harness-scene2d/src/test/java/dev/gdx/uiharness/scene2d/Scene2dSessionTest.java harness-scene2d/src/test/java/dev/gdx/uiharness/scene2d/Scene2dScenarioRunnerTest.java
git commit -m "fix(scene2d): enforce render-thread ownership on every session boundary method"
```

---

### Task 6 (#22): Gate completed-frame snapshots on active runner subscriptions

**Files:**
- Modify: `harness-scene2d/src/main/java/dev/gdx/uiharness/scene2d/Scene2dScenarioRunner.java` (add `hasActiveRuns()` near `completedFrame`, line ~125)
- Modify: `harness-scene2d/src/main/java/dev/gdx/uiharness/scene2d/Scene2dNavigationRunner.java` (add `hasActiveRuns()` near `completedFrame`, line ~119)
- Modify: `harness-scene2d/src/main/java/dev/gdx/uiharness/scene2d/Scene2dSession.java:65-86` (gate both `completedFrame` overloads)
- Create: `harness-scene2d/src/test/java/dev/gdx/uiharness/scene2d/Scene2dSnapshotGatingTest.java`

**Interfaces:**
- Consumes: Task 5 `requireOwnerThread("completedFrame")`.
- Produces: `Scene2dScenarioRunner.hasActiveRuns()` and `Scene2dNavigationRunner.hasActiveRuns()` returning whether any run is in the runner's `active` list (a run is active from `launch`/`start` until its terminal `finished()` removal; a held READY lease remains active by design). `Scene2dSession.completedFrame` builds the snapshot only when at least one supplied runner reports active runs, and publishes the single shared snapshot to each runner otherwise unchanged.

- [ ] **Step 1: Write the failing counting tests**

Create `harness-scene2d/src/test/java/dev/gdx/uiharness/scene2d/Scene2dSnapshotGatingTest.java`:

```java
package dev.gdx.uiharness.scene2d;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.Input.Keys;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.utils.GdxNativesLoader;
import com.badlogic.gdx.utils.viewport.FitViewport;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import dev.gdx.uiharness.core.navigation.NavigationInput;
import dev.gdx.uiharness.core.navigation.NavigationRequest;
import dev.gdx.uiharness.core.navigation.NavigationResult;
import dev.gdx.uiharness.core.navigation.NavigationStep;
import dev.gdx.uiharness.core.scenario.ScenarioDefinition;
import dev.gdx.uiharness.core.scenario.ScenarioLifecycle;
import dev.gdx.uiharness.core.scenario.ScenarioRegistry;
import dev.gdx.uiharness.core.scenario.ScenarioRequest;
import dev.gdx.uiharness.core.scenario.ScenarioResult;
import dev.gdx.uiharness.core.time.Deadline;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

final class Scene2dSnapshotGatingTest {
    @Test void idleFramesBuildNoRunnerSnapshots() {
        try (GatedFixture fixture = new GatedFixture()) {
            fixture.completedFrame();
            fixture.completedFrame();
            fixture.completedFrame();
            assertEquals(0, fixture.rootReads(), "idle frames must not build runner snapshots");
            assertEquals(3, fixture.frame());
        }
    }

    @Test void startingARunEnablesSnapshotsThroughItsFirstObservation() {
        try (GatedFixture fixture = new GatedFixture()) {
            fixture.register(new RecordingLifecycle(new ArrayList<>(), false, "never"));
            CompletionStage<ScenarioResult> started = fixture.start(Duration.ofSeconds(2));
            fixture.scheduler.drain();

            fixture.completedFrame();
            assertEquals(1, fixture.rootReads(),
                    "the first frame after start must reach the waiting run");
            fixture.completedFrame();
            assertEquals(2, fixture.rootReads());
            assertFalse(started.toCompletableFuture().isDone());
        }
    }

    @Test void cancellingTheLastRunReturnsTheSessionToIdle() {
        try (GatedFixture fixture = new GatedFixture()) {
            fixture.register(new RecordingLifecycle(new ArrayList<>(), false, "never"));
            CompletionStage<ScenarioResult> started = fixture.start(Duration.ofSeconds(2));
            fixture.scheduler.drain();
            fixture.completedFrame();
            assertEquals(1, fixture.rootReads());

            assertTrue(started.toCompletableFuture().cancel(false));
            fixture.scheduler.drain();
            fixture.completedFrame();
            assertEquals(1, fixture.rootReads(),
                    "terminal runs must stop the per-frame snapshot stream");
        }
    }

    @Test void navigationRunsEnableTheSharedSnapshotStreamAndIdleDisablesIt() {
        try (GatedFixture fixture = new GatedFixture()) {
            fixture.completedFrame();
            assertEquals(0, fixture.rootReads());

            fixture.route(Keys.TAB, fixture.second);
            CompletionStage<NavigationResult> inspect =
                    fixture.navigation.inspect(fixture.request(List.of(NavigationInput.TAB)));
            fixture.nextFrame(); // scenario acquire observes the first completed frame
            assertTrue(fixture.rootReads() > 0,
                    "an active navigation run must enable snapshots");
            fixture.drainFrames(8);
            assertTrue(inspect.toCompletableFuture().isDone(),
                    "navigation must complete once its step is observed");

            long after = fixture.rootReads();
            fixture.completedFrame();
            assertEquals(after, fixture.rootReads(),
                    "after the last run finishes, completed frames build no snapshot");
        }
    }

    @Test void onDemandSnapshotsAndFrameCorrelationKeepWorkingWhileIdle() {
        try (GatedFixture fixture = new GatedFixture()) {
            fixture.completedFrame();
            fixture.completedFrame();
            SemanticSnapshot snapshot = fixture.session.snapshot(
                    fixture.clock.revision(), fixture.clock.frame());
            assertEquals(2, snapshot.frame());
            assertEquals(2, snapshot.revision());
        }
    }

    private static final class RecordingLifecycle implements ScenarioLifecycle {
        private final List<Thread> hookThreads;
        private final boolean readyImmediately;
        private final String identity;

        RecordingLifecycle(List<Thread> hookThreads, boolean readyImmediately, String identity) {
            this.hookThreads = hookThreads;
            this.readyImmediately = readyImmediately;
            this.identity = identity;
        }

        @Override public void setup(ScenarioRequest request) {
            hookThreads.add(Thread.currentThread());
        }

        @Override public void reset(ScenarioRequest request) {
            hookThreads.add(Thread.currentThread());
        }

        @Override public boolean ready(ScenarioRequest request) {
            return readyImmediately;
        }

        @Override public String startStateIdentity(
                ScenarioRequest request, SemanticSnapshot snapshot) {
            return identity;
        }

        @Override public void cleanup(ScenarioRequest request) {
            hookThreads.add(Thread.currentThread());
        }
    }

    private static final class GatedFixture implements AutoCloseable {
        private static final Duration STEP = Duration.ofMillis(10);
        final CountingStage stage;
        final ControlledStageClock clock;
        final RenderThreadScheduler scheduler;
        final Scene2dSession session;
        final ScenarioRegistry registry = new ScenarioRegistry();
        final Scene2dScenarioRunner scenarios;
        final Scene2dNavigationRunner navigation;
        final RoutingInput input = new RoutingInput();
        final AtomicInteger dispatches = new AtomicInteger();
        final TextButton first;
        final TextButton second;

        GatedFixture() {
            GdxNativesLoader.load();
            NoopBatch.installGraphics();
            stage = new CountingStage();
            clock = new ControlledStageClock(stage, STEP);
            scheduler = new RenderThreadScheduler(64);
            session = new Scene2dSession(stage);
            registry.register(
                    new ScenarioDefinition(ScenarioDefinition.SCHEMA_VERSION,
                            "gated-nav", "1.0.0", "app", List.of("desktop"), 1,
                            Duration.ofSeconds(2)),
                    new ScenarioLifecycle() {
                        @Override public void setup(ScenarioRequest request) {}
                        @Override public void reset(ScenarioRequest request) {
                            stage.setKeyboardFocus(first);
                        }
                        @Override public boolean ready(ScenarioRequest request) { return true; }
                        @Override public String startStateIdentity(
                                ScenarioRequest request, SemanticSnapshot snapshot) {
                            return "ready";
                        }
                        @Override public void cleanup(ScenarioRequest request) {}
                    });
            first = button("first", 50);
            second = button("second", 250);
            scenarios = new Scene2dScenarioRunner(registry, scheduler, clock, delay -> () -> {});
            navigation = new Scene2dNavigationRunner(
                    scenarios, session, new Scene2dInputDispatcher(stage, input),
                    scheduler, clock, delay -> () -> {}, clock::revision, clock::frame,
                    new Scene2dNavigationRunner.Scenario(
                            "gated-nav", 7, Map.of(), "desktop", "app", "process", "session"),
                    8);
        }

        TextButton button(String id, float x) {
            TextButton actor = new TextButton(id, WidgetStyles.textButton());
            actor.setBounds(x, 50, 160, 40);
            stage.addActor(actor);
            session.semantics().setTestId(actor, id);
            return actor;
        }

        void route(int key, com.badlogic.gdx.scenes.scene2d.Actor target) {
            input.routes.put(key, target);
        }

        void register(ScenarioLifecycle lifecycle) {
            registry.register(
                    new ScenarioDefinition(ScenarioDefinition.SCHEMA_VERSION,
                            "login-ready", "1.0.0", "test-app", List.of("desktop"),
                            1, Duration.ofSeconds(2)),
                    lifecycle);
        }

        CompletionStage<ScenarioResult> start(Duration timeout) {
            return scenarios.start(
                    new ScenarioRequest(ScenarioDefinition.SCHEMA_VERSION,
                            "login-ready", 42L, Map.of("locale", "en"), "desktop",
                            Deadline.after(clock, timeout)),
                    "test-app", "process-1", "session-1");
        }

        NavigationRequest request(List<NavigationInput> inputs) {
            List<String> known = List.of("test-id:first", "test-id:second");
            List<NavigationStep> configured = new ArrayList<>();
            for (int index = 0; index < inputs.size(); index++) {
                NavigationInput navigationInput = inputs.get(index);
                configured.add(new NavigationStep(navigationInput,
                        index + 1L, index + 1L, index + 2L, index + 2L,
                        "test-id:first", "test-id:first", null));
            }
            return new NavigationRequest(1, configured, known, null, null, true, false,
                    8, 16, 65536, 65536, Deadline.after(clock, Duration.ofSeconds(2)));
        }

        void completedFrame() {
            clock.advance(STEP);
            session.completedFrame(scenarios, navigation, clock.revision(), clock.frame());
            scheduler.drain();
        }

        void nextFrame() {
            completedFrame();
        }

        void drainFrames(int count) {
            for (int index = 0; index < count; index++) {
                completedFrame();
            }
        }

        long rootReads() {
            return stage.rootReads.get();
        }

        long frame() {
            return clock.frame();
        }

        @Override public void close() {
            navigation.close();
            scenarios.close();
            session.close();
            scheduler.close();
            clock.close();
            stage.dispose();
        }
    }

    /** Counts one Stage root traversal per built semantic snapshot. */
    private static final class CountingStage extends Stage {
        final AtomicLong rootReads = new AtomicLong();

        CountingStage() {
            super(new FitViewport(800, 600), new NoopBatch());
            getViewport().setScreenBounds(0, 0, 800, 600);
            getViewport().getCamera().position.set(400, 300, 0);
            getViewport().getCamera().update();
        }

        @Override public Group getRoot() {
            rootReads.incrementAndGet();
            return super.getRoot();
        }
    }

    private final class RoutingInput extends InputAdapter {
        final Map<Integer, com.badlogic.gdx.scenes.scene2d.Actor> routes = new HashMap<>();

        @Override public boolean keyDown(int keycode) {
            com.badlogic.gdx.scenes.scene2d.Actor target = routes.get(keycode);
            if (target != null || routes.containsKey(keycode)) {
                dispatches.incrementAndGet();
                stage.setKeyboardFocus(target);
            }
            return true;
        }
    }
}
```

The `CountingStage.getRoot()` override counts exactly the `stage.getRoot()` calls made by `Scene2dSnapshotter.snapshot(Stage, …)` (one per built snapshot); `Scene2dSession`, `ControlledStageClock`, `Scene2dContractSnapshotter`, and `Scene2dTypographyExtractor` constructors only store the Stage, so the count is 0 at fixture construction. `GdxNativesLoader`/`NoopBatch`/`WidgetStyles` are package-visible test utilities already used by `Scene2dSnapshotterTest` and `Scene2dNavigationRunnerTest`.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :harness-scene2d:test --tests 'dev.gdx.uiharness.scene2d.Scene2dSnapshotGatingTest' --no-daemon --console=plain --warning-mode=fail`
Expected: FAIL — `idleFramesBuildNoRunnerSnapshots` reports 3 root reads instead of 0 (the current code snapshots every frame).

- [ ] **Step 3: Implement the gates**

In `Scene2dScenarioRunner.java`, after `completedFrame`:

```java
    /** Returns whether any run currently requires completed-frame observations. */
    public boolean hasActiveRuns() {
        synchronized (lifecycle) {
            return !active.isEmpty();
        }
    }
```

In `Scene2dNavigationRunner.java`, the identical method with its own `lifecycle`/`active`.

In `Scene2dSession.java`, replace the two `completedFrame` overloads:

```java
    /** Captures and publishes one completed semantic frame while the runner has active runs. */
    public void completedFrame(Scene2dScenarioRunner runner, long revision, long frame) {
        requireOwnerThread("completedFrame");
        Objects.requireNonNull(runner, "runner");
        if (runner.hasActiveRuns()) {
            runner.completedFrame(snapshot(revision, frame));
        }
    }

    /**
     * Captures and publishes one shared completed semantic frame while either runner has active
     * runs; idle sessions build no runner snapshot while fences and on-demand snapshots advance.
     */
    public void completedFrame(
            Scene2dScenarioRunner scenarioRunner,
            Scene2dNavigationRunner navigationRunner,
            long revision,
            long frame) {
        requireOwnerThread("completedFrame");
        if (scenarioRunner.hasActiveRuns() || navigationRunner.hasActiveRuns()) {
            SemanticSnapshot snapshot = snapshot(revision, frame);
            scenarioRunner.completedFrame(snapshot);
            navigationRunner.completedFrame(snapshot);
        }
    }
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :harness-scene2d:test --tests 'dev.gdx.uiharness.scene2d.Scene2dSnapshotGatingTest' --no-daemon --console=plain --warning-mode=fail`
Expected: PASS — idle frames build 0 snapshots; an active run, cancellation, navigation, and return-to-idle behave as asserted; on-demand snapshots and frame numbers keep advancing.

Run: `./gradlew :harness-scene2d:test --no-daemon --console=plain --warning-mode=fail`
Expected: PASS — `Scene2dScenarioRunnerTest` and `Scene2dNavigationRunnerTest` still observe every frame because their runs are active when `completedFrame` fires.

- [ ] **Step 5: Commit**

```bash
git add harness-scene2d/src/main/java/dev/gdx/uiharness/scene2d/Scene2dScenarioRunner.java harness-scene2d/src/main/java/dev/gdx/uiharness/scene2d/Scene2dNavigationRunner.java harness-scene2d/src/main/java/dev/gdx/uiharness/scene2d/Scene2dSession.java harness-scene2d/src/test/java/dev/gdx/uiharness/scene2d/Scene2dSnapshotGatingTest.java
git commit -m "perf(scene2d): snapshot completed frames only while a runner is active"
```

---

### Task 7 (#22): Real LWJGL3 idle rendering and fence smoke

**Files:**
- Modify: `harness-lwjgl3/src/test/java/dev/gdx/uiharness/lwjgl3/Lwjgl3ScreenCaptureTest.java` (new test method)

**Interfaces:**
- Consumes: `Lwjgl3CaptureFixture` (real `Lwjgl3Application`, no runner attached), `Lwjgl3FrameFence.subscribe`, `Lwjgl3ScreenCapture.capture` (which routes `session.snapshot` on the render thread).
- Produces: smoke evidence that rendering, frame fences, and on-demand captures advance while no scenario/navigation runner is attached.

- [ ] **Step 1: Add the smoke test**

Add to `Lwjgl3ScreenCaptureTest` (uses the existing `@TestInstance(PER_CLASS)` fixture):

```java
    @Test void renderingAndFrameFencesAdvanceWhileNoRunnerIsAttached() {
        long start = fixture.latestFrame();
        CompletableFuture<Long> reached = new CompletableFuture<>();
        FrameSignal.Subscription subscription = fixture.fence().subscribe(frame -> {
            if (frame.frame() >= start + 8) {
                reached.complete(frame.frame());
            }
        });
        try {
            Long observedFrame = await(reached);
            assertTrue(observedFrame >= start + 8,
                    "frame fences must keep advancing while the session is idle");
            CapturedImage captured = fixture.captureFullWindow();
            assertTrue(captured.frame() >= observedFrame,
                    "on-demand capture must observe the same advancing frame stream");
        } finally {
            subscription.close();
        }
    }
```

Add the missing imports (`CompletableFuture`, `FrameSignal` already imported, `CapturedImage` already imported).

- [ ] **Step 2: Run to verify it passes on the gated code**

Run: `xvfb-run -a ./gradlew :harness-lwjgl3:test --tests 'dev.gdx.uiharness.lwjgl3.Lwjgl3ScreenCaptureTest' --no-daemon --console=plain --warning-mode=fail`
Expected: PASS — this is the required real-LWJGL3 smoke proof for #22 ("rendering and frame fences while idle"); it guards the idle path against regressions from Task 6.

- [ ] **Step 3: Commit**

```bash
git add harness-lwjgl3/src/test/java/dev/gdx/uiharness/lwjgl3/Lwjgl3ScreenCaptureTest.java
git commit -m "test(lwjgl3): smoke — rendering and frame fences advance while idle"
```

---

### Task 8: Documentation parity for the new contracts

**Files:**
- Modify: `docs/guides/getting-started.md` (Threading and frame wiring section, lines ~78-81)
- Modify: `docs/guides/semantic-metadata.md` (Explicit metadata section)

**Interfaces:**
- Documents Tasks 4-6 and the unchanged screenshot wire contract from Tasks 1-3.

- [ ] **Step 1: Update getting-started.md**

In the "Threading and frame wiring" section, replace the sentence "A supplier that reads the Stage directly is a silent render-thread confinement violation with no error." with:

"A supplier that reads the Stage directly now fails immediately with a typed `render-thread-violation` error carrying the operation, owner thread, and caller thread; route Stage access through the scheduler."

After the paragraph that begins "Complete the frame fence after every rendered frame…", add:

"`Scene2dSession.completedFrame(scenarioRunner, navigationRunner, revision, frame)` builds the per-frame semantic snapshot only while a scenario or navigation runner has active runs; an idle session skips that per-frame work while frame fences, captures, and on-demand `session.snapshot(...)` calls keep advancing. Invoke every `Scene2dSession` Stage-reading method from the owning render thread (the thread that constructed the session) or through `RenderThreadScheduler`."

- [ ] **Step 2: Update semantic-metadata.md**

In "Explicit metadata", after "Obtain the session-owned facade on the render thread:", append:

"`session.semantics()`, `session.adapters()`, and every Stage-reading session method (`snapshot`, `stateActionContract`, `typography`, `layout`, `completedFrame`) reject calls from any other thread with the typed `render-thread-violation` error; caller-thread work must be routed through `RenderThreadScheduler`."

- [ ] **Step 3: Verify no contradictions**

Run: `grep -rn "render-thread-violation\|pngBase64\|completedFrame" docs/guides/ | head -20`
Expected: the guide text matches the implemented contracts; the screenshot wire key `pngBase64` appears only in the MCP round-trip example, which is unchanged.

- [ ] **Step 4: Commit**

```bash
git add docs/guides/getting-started.md docs/guides/semantic-metadata.md
git commit -m "docs: render-thread-violation enforcement and idle runner snapshot gating"
```

---

### Task 9: Cluster verification and pull request

**Files:** none (verification and PR only).

- [ ] **Step 1: Run the full repository gate**

Run:
```bash
./gradlew clean check javadoc --no-daemon --console=plain --warning-mode=fail
python3 scripts/validate-workflows.py
git diff --check
```
Expected: BUILD SUCCESSFUL, PASS, and no whitespace errors.

- [ ] **Step 2: Run the exact focused suites named in this plan**

Run:
```bash
xvfb-run -a ./gradlew :harness-scene2d:test --tests 'dev.gdx.uiharness.scene2d.Scene2dSessionTest' --tests 'dev.gdx.uiharness.scene2d.Scene2dSnapshotGatingTest' --tests 'dev.gdx.uiharness.scene2d.Scene2dScenarioRunnerTest' --tests 'dev.gdx.uiharness.scene2d.Scene2dNavigationRunnerTest' --no-daemon --console=plain --warning-mode=fail
xvfb-run -a ./gradlew :harness-protocol:test --tests 'dev.gdx.uiharness.protocol.ProtocolJsonContractTest' --no-daemon --console=plain --warning-mode=fail
xvfb-run -a ./gradlew :harness-mcp:test --tests 'dev.gdx.uiharness.mcp.HarnessToolHandlerScreenshotPublicationTest' --tests 'dev.gdx.uiharness.mcp.HarnessMcpServerContractTest' --no-daemon --console=plain --warning-mode=fail
xvfb-run -a ./gradlew :harness-lwjgl3:test --tests 'dev.gdx.uiharness.lwjgl3.Lwjgl3ScreenCaptureTest' --no-daemon --console=plain --warning-mode=fail
```
Expected: all PASS.

- [ ] **Step 3: Open the pull request**

Base `origin/main`, head `fix/issues-21-23-scene-capture`. Body:

```markdown
## Scene2D ownership and capture efficiency (#21, #22, #23)

- **#21 — session-bound render-thread enforcement.** `Scene2dSession` now rejects off-thread Stage/semantic/adapter access with `HarnessException(ErrorCode.RENDER_THREAD_VIOLATION)` carrying operation and thread names; caller-thread waits keep routing through `RenderThreadScheduler`. New core/protocol code `render-thread-violation`, golden `errors.json` entry, ADR 0031.
- **#22 — snapshots only for active consumers.** `completedFrame` builds a semantic snapshot only while a scenario or navigation runner has active runs (`hasActiveRuns()` on both runners); idle sessions skip per-frame work while fences, captures, and on-demand snapshots advance. Real LWJGL3 smoke covers idle rendering/fences.
- **#23 — no internal base64 round trips.** Protocol PNG results (`Screenshot`, `InspectCompare`, `TypographyDiagnostic`, `LayoutDiagnostic`) own immutable bytes; the MCP handler publishes captured bytes directly; JSON keeps the base64 wire keys (`pngBase64`, `currentPngBase64`, `heatmapPngBase64`). Screenshot publication no longer serializes an unused inline result; allocation guard proves publication stays below 4x the payload.

Fixes #21
Fixes #22
Fixes #23
```

Include in the PR: root causes, the exact verification commands from Steps 1-2 with their output, and the compatibility statement that the wire JSON shape and existing scheduler-routed callers are unchanged.

- [ ] **Step 4: Review and merge checklist**

1. Review the remote base, head SHA, commit list, full patch, and each issue's acceptance criteria against current-state tests.
2. Confirm every commit is scoped to this cluster (tests, implementation, ADR 0031, guide docs, golden additions).
3. Reproduce and fix any actionable review finding test-first, then re-run the affected focused suite and CI on the new SHA.
4. Merge only the reviewed green SHA; verify the merge closes #21, #22, and #23.

---

## Self-Review

**1. Spec coverage (approved design, cluster 4, plus canonical issue bodies):**

- #21: off-thread Stage/session access fails immediately with a typed actionable error → Task 5 (`RENDER_THREAD_VIOLATION` + operation/thread evidence); correct scheduler-routed calls remain supported → Task 5 test 3 and unchanged `Scene2dHarness`/`Lwjgl3ScreenCapture` wiring; a render-thread fixture covers the boundary → `Scene2dSessionTest`; threading ADR amended with the new public error code → Task 4 ADR 0031; allocation-light success path → single reference comparison in `requireOwnerThread`.
- #22: idle sessions do not build runner snapshots → Task 6 counting test; starting a runner enables the stream → Task 6 test 2; stopping the last runner removes per-frame work → Task 6 tests 3-4; frame correlation/fences remain correct → Task 6 test 5 + Task 7 real-LWJGL3 smoke; proof counts snapshot construction across idle/active/navigation/cancellation/return-to-idle → `CountingStage.getRoot()` counts.
- #23: artifact publication performs no intermediate base64 round trip → Tasks 1-3 (handler publishes `pngBytes()` directly; protocol holds bytes; no Base64 decode anywhere in `HarnessToolHandler`); digest/length receipts remain identical → Task 3 receipt assertions against the exact captured bytes; allocation-focused coverage at maximum size → Task 3 ThreadMXBean guard at `MAX_PNG_BYTES`; unchanged protocol JSON shape → golden `results.json` round trip (Task 1) and wire-key test (Task 2); defensive ownership without repeated clones → clone budget in Global Constraints and Task 1 test; "MCP artifact paths do not serialize an unused full inline result before offloading" → Task 3 lazy `encodeResult` (screenshot branch serializes nothing).
- Error/compatibility policy: typed `render-thread-violation` code with operation identity and bounded details; existing valid requests, JSON shapes, artifact references, and non-scenario `Scene2dSession` use remain supported; the design's "internal byte ownership may change while serialized screenshot JSON stays compatible" clause is satisfied.

**2. Placeholder scan:** no TBD/TODO/“appropriate error handling”/“similar to Task N” patterns; every code step contains verbatim code; every acceptance criterion names an exact command and expected result.

**3. Type consistency:** `pngBytes()`/`currentPngBytes()`/`heatmapPngBytes()` names are identical across Tasks 1-3; `MAX_PNG_BYTES` is the single byte bound; `RENDER_THREAD_VIOLATION` is spelled identically in `ErrorCode`, `ProtocolError.Code`, the golden wire name `render-thread-violation`, and the tests; `hasActiveRuns()` is the same signature on both runners and is consumed only by `Scene2dSession.completedFrame`; the ADR number `0031` is consistent with the current `docs/adr` maximum (0030) and the renumbering caveat is stated.
