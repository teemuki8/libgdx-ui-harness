# Issues 21–23 Scene2D Ownership and Capture Efficiency Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close GitHub issues #21 (session-bound render-thread enforcement), #22 (snapshots only for active scenario/navigation consumers), and #23 (no internal base64 PNG round trips) with typed errors, atomic additive runner APIs, preserved String-typed public API and unchanged JSON wire shape, and a documented single-copy PNG ownership invariant.

**Architecture:** `Scene2dSession` gains a `requireOwnerThread(operation)` guard on every method that reads or mutates Stage, actors, adapters, semantic metadata, or completed-frame state; failures are `HarnessException` with a new `RENDER_THREAD_VIOLATION` code that maps to a new `render-thread-violation` protocol wire code. `Scene2dSession.completedFrame` delegates to new atomic runner overloads `completedFrame(Supplier<SemanticSnapshot>, long, long)` that decide under each runner's lifecycle lock whether a completed frame is consumed, building the shared snapshot at most once per frame only when a runner consumed it (no `hasActiveRuns()`-style check-then-act race). Protocol PNG result models (`Screenshot`, `InspectCompare`, `TypographyDiagnostic`, `LayoutDiagnostic`) own immutable bytes via ownership transfer while preserving the legacy String constructors and accessors; Jackson `@JsonProperty` renames keep the base64 wire keys identical; the MCP handler publishes bytes directly, and an injected counting mapper proves the screenshot branch never serializes an inline result.

**Tech Stack:** Java 25, Gradle wrapper (`--no-daemon --console=plain --warning-mode=fail`), JUnit 5, Jackson 2.22.1 records support (public `ObjectMapper(ObjectMapper)` copy constructor for the injected counting mapper), libGDX 1.14.2 Scene2D/LWJGL3, `xvfb-run` for real LWJGL3 smoke tests on headless Linux.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-08-08-issues-8-26-release-design.md` (approved 2026-08-08), cluster 4 ("Scene2D ownership and capture efficiency"). Live issue bodies #21, #22, #23 remain authoritative.
- **Prerequisite — rebase before Task 1:** this branch (`fix/issues-21-23-scene-capture`) was cut from `origin/main` @ `c5fe5df`, before clusters #8–#13, #14–#16, and #17–#20/#24–#25 merge. Immediately before starting Task 1, rebase the branch onto the freshly merged `origin/main` (`git fetch origin && git rebase origin/main`), resolve any conflicts keeping this cluster's intent, and re-run the affected module tests once to confirm the new base is green. Every later task runs on the rebased base.
- **ADR numbering (computed at rebase):** define `N` = 1 + the largest numeric suffix among `docs/adr/*.md` files present on the rebased branch (on the pre-rebase base `N = 0031`). Every occurrence of "ADR 0031" in this plan (header, file paths, commit messages, PR body) means "ADR `N`"; fill the exact number from the rebased `docs/adr/` directory and use it consistently everywhere. Never use the pre-rebase value after the rebase has added ADRs.
- JDK 25 baseline, `--release 25`, `-Xlint:all`, warnings fail the build; no preview/incubator APIs.
- Real LWJGL3 tests on Linux require Xvfb: run them as `xvfb-run -a ./gradlew …`.
- No sleeps for synchronization: use latches, barriers, injected clocks, deadlines, and observable-state drains only.
- Public protocol JSON wire shape is invariant: keys `pngBase64`, `currentPngBase64`, `heatmapPngBase64` keep their exact names and base64 values; `errors.json` golden covers every `ProtocolError.Code` (the contract test asserts full enum coverage), so any new code requires a new golden entry.
- `ProtocolError.Code.fromCore` maps by `ErrorCode.name()`: a new core `ErrorCode` REQUIRES a matching `ProtocolError.Code` constant or runtime `IllegalArgumentException`.
- **Public Java API is preserved (binary and source compatible) for v1.2.0:** the existing `String`-typed constructors of `Result.Screenshot`, `InspectCompare`, `TypographyDiagnostic`, and `LayoutDiagnostic` (including the `InspectCompare` 10-argument compatibility constructor) and the existing `pngBase64()` / `currentPngBase64()` / `heatmapPngBase64()` accessors must remain declared with identical signatures and behavior. The byte-owned representation is added beside them (new `byte[]` canonical constructors, new `pngBytes()` / `currentPngBytes()` / `heatmapPngBytes()` accessors, internal ownership transfer). Record `equals`/`hashCode` retain array-content semantics; `toString` may change representation (not a supported API).
- **PNG byte clone/ownership invariant (single defensive copy per payload):** exactly one defensive copy of a captured PNG payload exists in the ownership chain, created by the core `CapturedImage` accessor at the core→protocol hop (existing contract, unchanged). Protocol byte-owning models take exclusive ownership of the array supplied to their canonical constructor (javadoc: the caller transfers ownership and must not mutate or retain it); producers (`fromCore`, Jackson deserialization, the String compatibility constructors) always supply exclusively-owned arrays. Model byte accessors return the owned array by reference under a documented read-only, do-not-retain contract; the MCP publisher and Jackson serialization read it without copying. String compatibility accessors encode on demand and never mutate the owned array. The MCP publication path performs zero base64 conversions and zero payload copies.
- New public API is additive only: the `completedFrame(Supplier<SemanticSnapshot>, long, long)` overloads on both runners, the byte-typed constructors/accessors above, and the `ErrorCode.RENDER_THREAD_VIOLATION` wire code.
- Every task ends with a commit that leaves the tree compiling and its focused tests green. Do not run formatters, linters, or the full suite mid-task; Task 9 runs the repository gate.

---

### Task 1 (#23): Byte-owned `Result.Screenshot` with preserved String API and base64 wire key

**Files:**
- Modify: `harness-protocol/src/main/java/dev/gdx/uiharness/protocol/HarnessResponse.java:371-418` (the `Screenshot` record, imports at 39-42)
- Modify: `harness-mcp/src/main/java/dev/gdx/uiharness/mcp/HarnessToolHandler.java:345-352` (screenshot branch), remove `java.util.Base64` import (line 24) only if no other branch uses it (it is still used at lines 384-496 until Task 2 — leave the import until Task 2)
- Modify: `harness-protocol/src/test/java/dev/gdx/uiharness/protocol/ProtocolJsonContractTest.java:421-437` (rewrite `screenshotPayloadAboveGenericStringLimitRoundTrips`)

**Interfaces:**
- Consumes: `dev.gdx.uiharness.core.capture.CapturedImage` (unchanged; its `pngBytes()` accessor remains the single defensive-copy point).
- Produces: `Screenshot` with the existing public String API preserved verbatim — constructor `Screenshot(String pngBase64, String sha256, long frame, long revision, int width, int height, double scaleX, double scaleY)` and accessor `String pngBase64()` — plus the new canonical byte constructor `Screenshot(byte[] pngBytes, …)` (ownership transfer, no copy) and accessor `byte[] pngBytes()` (returns the owned array, read-only contract). Jackson serializes/deserializes the `pngBase64` wire key from the byte component. `MAX_PNG_BYTES` unchanged; `MAX_BASE64_LENGTH` stays (the String constructor validates against it). Consumed by Tasks 2 and 3.

- [ ] **Step 1: Write the failing tests**

Add `import static org.junit.jupiter.api.Assertions.assertArrayEquals;` and `import static org.junit.jupiter.api.Assertions.assertSame;` to the file's static imports (the class already imports `assertInstanceOf`/`assertTrue`/`Base64`). Rewrite `screenshotPayloadAboveGenericStringLimitRoundTrips` in `ProtocolJsonContractTest.java` to construct the record with bytes and assert the base64 wire key:

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

Add (same file) the ownership-transfer and String-compatibility tests that fail against the current String-only record:

```java
@Test void screenshotTransfersByteOwnershipWithoutRepeatedClones() {
    byte[] supplied = {1, 2, 3, 4, 5};
    HarnessResponse.Result.Screenshot screenshot =
            new HarnessResponse.Result.Screenshot(supplied, "0".repeat(64), 1, 1, 5, 1, 1, 1);
    assertSame(supplied, screenshot.pngBytes(),
            "the canonical byte constructor must take ownership without copying");
    assertArrayEquals(new byte[] {1, 2, 3, 4, 5}, screenshot.pngBytes());
}

@Test void screenshotStringConstructorAndAccessorRemainCompatible() {
    String base64 = Base64.getEncoder().encodeToString(new byte[] {1, 2, 3, 4, 5});
    HarnessResponse.Result.Screenshot screenshot =
            new HarnessResponse.Result.Screenshot(base64, "0".repeat(64), 1, 1, 5, 1, 1, 1);
    assertEquals(base64, screenshot.pngBase64(),
            "the legacy String constructor and accessor must keep working");
    assertArrayEquals(new byte[] {1, 2, 3, 4, 5}, screenshot.pngBytes());
    assertEquals(base64, screenshot.pngBase64(),
            "the String accessor must be stable across calls and never mutate the bytes");
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :harness-protocol:test --tests 'dev.gdx.uiharness.protocol.ProtocolJsonContractTest' --no-daemon --console=plain --warning-mode=fail`
Expected: FAIL — compilation error: `Screenshot` has no constructor accepting `byte[]` and no `pngBytes()` method. A Java compilation failure is a valid red for a public-model change.

- [ ] **Step 3: Implement the byte-owned record with preserved String API**

In `HarnessResponse.java`:

- Add import `import com.fasterxml.jackson.annotation.JsonProperty;` (keep the existing `java.util.Base64` import until Task 2 removes the constructor decoders).
- Replace the `Screenshot` record (lines 371-418) with:

```java
        /**
         * Bounded owned PNG bytes and capture metadata. The byte array passed to the canonical
         * constructor is transferred to this model: callers must not mutate or retain it. The
         * JSON wire keeps the pngBase64 key; the legacy String constructor and accessor remain
         * supported for source and binary compatibility.
         */
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
            private static final int MAX_BASE64_LENGTH = (MAX_PNG_BYTES / 3) * 4;

            /** Validates owned PNG bytes and capture metadata; takes ownership of the array. */
            public Screenshot {
                Objects.requireNonNull(pngBytes, "pngBytes");
                if (pngBytes.length == 0 || pngBytes.length > MAX_PNG_BYTES) {
                    throw new IllegalArgumentException(
                            "pngBytes exceeds protocol screenshot limit");
                }
                ProtocolJson.requireText(sha256, "sha256");
                if (frame < 0 || revision < 0 || width <= 0 || height <= 0) {
                    throw new IllegalArgumentException("invalid screenshot metadata");
                }
                if (!Double.isFinite(scaleX) || scaleX <= 0
                        || !Double.isFinite(scaleY) || scaleY <= 0) {
                    throw new IllegalArgumentException("invalid screenshot scale");
                }
            }

            /** Legacy String constructor; decodes the bounded base64 payload. */
            public Screenshot(
                    String pngBase64,
                    String sha256,
                    long frame,
                    long revision,
                    int width,
                    int height,
                    double scaleX,
                    double scaleY) {
                this(requireDecodedPng(pngBase64), sha256, frame, revision,
                        width, height, scaleX, scaleY);
            }

            /**
             * Returns the owned PNG bytes. The returned array is the model's exclusive storage:
             * treat it as read-only and do not retain it beyond the call.
             */
            @Override public byte[] pngBytes() {
                return pngBytes;
            }

            /** Legacy accessor; encodes on demand and never mutates the owned bytes. */
            public String pngBase64() {
                return Base64.getEncoder().encodeToString(pngBytes);
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

            private static byte[] requireDecodedPng(String pngBase64) {
                Objects.requireNonNull(pngBase64, "pngBase64");
                if (pngBase64.isBlank() || pngBase64.length() > MAX_BASE64_LENGTH) {
                    throw new IllegalArgumentException(
                            "pngBase64 exceeds protocol screenshot limit");
                }
                byte[] pngBytes;
                try {
                    pngBytes = Base64.getDecoder().decode(pngBase64);
                } catch (IllegalArgumentException invalid) {
                    throw new IllegalArgumentException(
                            "pngBase64 is not valid base64", invalid);
                }
                if (pngBytes.length == 0 || pngBytes.length > MAX_PNG_BYTES) {
                    throw new IllegalArgumentException(
                            "pngBase64 exceeds protocol screenshot limit");
                }
                return pngBytes;
            }
        }
```

`requireDecodedPng` returns a fresh array from the decoder, which the canonical constructor then owns — the String path performs exactly one decode and zero copies. Jackson deserialization likewise decodes the wire string directly into the byte component (default `byte[]` handling), so no production path clones the payload.

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
Expected: PASS — the golden `results.json` screenshot entry round-trips canonically (wire shape unchanged), the rewritten payload test passes, and the ownership-transfer and String-compatibility tests hold.

Then run the MCP contract suite to prove the byte-for-byte publication test still passes:
Run: `./gradlew :harness-mcp:test --tests 'dev.gdx.uiharness.mcp.HarnessMcpServerContractTest' --no-daemon --console=plain --warning-mode=fail`
Expected: PASS — `screenshotAndLargeResultsUseInjectedOpaqueArtifactReferences` still sees `[1, 2, 3]` published.

- [ ] **Step 5: Commit**

```bash
git add harness-protocol/src/main/java/dev/gdx/uiharness/protocol/HarnessResponse.java harness-protocol/src/test/java/dev/gdx/uiharness/protocol/ProtocolJsonContractTest.java harness-mcp/src/main/java/dev/gdx/uiharness/mcp/HarnessToolHandler.java
git commit -m "fix(protocol): own screenshot PNG bytes with preserved String API and base64 wire shape"
```

---

### Task 2 (#23): Byte-owned compare, typography, and layout PNG evidence

**Files:**
- Modify: `harness-protocol/src/main/java/dev/gdx/uiharness/protocol/HarnessResponse.java:427-703` (`InspectCompare`, `TypographyDiagnostic`, `LayoutDiagnostic` records and their `fromCore` conversions)
- Modify: `harness-mcp/src/main/java/dev/gdx/uiharness/mcp/HarnessToolHandler.java:379-410,433-456,491-515` (compare/typography/layout branches)
- Modify: `harness-protocol/src/test/java/dev/gdx/uiharness/protocol/ProtocolJsonContractTest.java` (new wire-shape test)

**Interfaces:**
- Consumes: Task 1 `Screenshot.MAX_PNG_BYTES` (unchanged), `CurrentVisualData`, `HeatmapData`, `CurrentCaptureData` metadata records (unchanged).
- Produces: `InspectCompare`, `TypographyDiagnostic`, and `LayoutDiagnostic` with the existing public String constructors (the 13-argument `(…, String currentPngBase64, String heatmapPngBase64)`, the 10-argument compatibility constructor, and the 7-argument typography/layout constructors) and accessors (`currentPngBase64()`, `heatmapPngBase64()`) preserved verbatim, plus new canonical byte constructors (`@JsonProperty("currentPngBase64") byte[] currentPngBytes`, `@JsonProperty("heatmapPngBase64") byte[] heatmapPngBytes`; ownership transfer) and byte accessors (`currentPngBytes()`, `heatmapPngBytes()`; read-only, do-not-retain). The canonical constructor validates the SHA-256 digest directly on the bytes with no decode; the String constructors decode once and delegate. Consumed by Task 3.

- [ ] **Step 1: Write the failing test**

Add to `ProtocolJsonContractTest.java` the imports `import java.security.MessageDigest;` and `import java.util.HexFormat;` (the file already imports `Base64`, `JsonNode`, and `assertInstanceOf`; `assertArrayEquals` was added in Task 1). Then add the wire-shape test:

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
    assertSame(current, comparison.currentPngBytes(),
            "the canonical byte constructor must transfer ownership without copying");
    assertSame(heatmap, comparison.heatmapPngBytes());

    // Legacy String constructors and accessors stay source- and binary-compatible.
    HarnessResponse.Result.InspectCompare legacyComparison =
            new HarnessResponse.Result.InspectCompare(
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
                    currentBase64, heatmapBase64);
    assertEquals(currentBase64, legacyComparison.currentPngBase64());
    assertEquals(heatmapBase64, legacyComparison.heatmapPngBase64());
    assertArrayEquals(current, legacyComparison.currentPngBytes());

    HarnessResponse.Result.TypographyDiagnostic legacyTypography =
            new HarnessResponse.Result.TypographyDiagnostic(
                    "pixel-sharp", "title-reference",
                    new HarnessResponse.CurrentCaptureData(
                            currentSha, 1, 1, 100, 100, 1.0, 1.0),
                    List.of(), List.of(), 0, currentBase64);
    assertEquals(currentBase64, legacyTypography.currentPngBase64());

    HarnessResponse.Result.LayoutDiagnostic legacyLayout =
            new HarnessResponse.Result.LayoutDiagnostic(
                    "conformant", "layout-reference",
                    new HarnessResponse.CurrentCaptureData(
                            currentSha, 1, 1, 100, 100, 1.0, 1.0),
                    List.of(), null, null, List.of(), 0, currentBase64);
    assertEquals(currentBase64, legacyLayout.currentPngBase64());
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

- [ ] **Step 3: Implement byte-owned evidence with preserved String API**

In `HarnessResponse.java`:

- `InspectCompare`: rename components `String currentPngBase64, String heatmapPngBase64` to:
  ```java
  @JsonProperty("currentPngBase64") byte[] currentPngBytes,
  @JsonProperty("heatmapPngBase64") byte[] heatmapPngBytes
  ```
  In the compact constructor, replace the base64 length/decode/hash validation (lines ~480-515) with direct byte validation (the canonical constructor takes ownership; no clone):
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
  }
  ```
  Add the legacy String constructor (same descriptor as the pre-change canonical constructor — binary compatible) and the ownership-transfer accessors:
  ```java
  /** Legacy String constructor; decodes the bounded base64 payloads and delegates. */
  public InspectCompare(
          String status,
          String policy,
          ReferenceVisualData reference,
          CurrentVisualData current,
          MetricsData metrics,
          List<DifferenceData> differences,
          List<RegionData> regions,
          HeatmapData heatmap,
          List<ComparisonDiagnosticData> diagnostics,
          int iterations,
          long elapsedMillis,
          String currentPngBase64,
          String heatmapPngBase64) {
      this(status, policy, reference, current, metrics, differences, regions, heatmap,
              diagnostics, iterations, elapsedMillis,
              requireDecodedPng(currentPngBase64, "comparison PNG"),
              requireDecodedPng(heatmapPngBase64, "heatmap PNG"));
  }

  /** Legacy String accessor; encodes on demand and never mutates the owned bytes. */
  public String currentPngBase64() {
      return currentPngBytes == null ? null
              : Base64.getEncoder().encodeToString(currentPngBytes);
  }

  /** Legacy String accessor; encodes on demand and never mutates the owned bytes. */
  public String heatmapPngBase64() {
      return heatmapPngBytes == null ? null
              : Base64.getEncoder().encodeToString(heatmapPngBytes);
  }

  /** Returns the owned current PNG bytes (read-only, do not retain), or null. */
  @Override public byte[] currentPngBytes() {
      return currentPngBytes;
  }

  /** Returns the owned heatmap PNG bytes (read-only, do not retain), or null. */
  @Override public byte[] heatmapPngBytes() {
      return heatmapPngBytes;
  }
  ```
  Update the 10-argument compatibility constructor to keep its `String currentPngBase64` parameter (same descriptor) and delegate through the new String constructor; update `fromCore` to pass raw byte arrays:
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
  }
  ```
  Add the legacy String constructor with the pre-change descriptor (decodes via `requireDecodedPng(currentPngBase64, "typography PNG")` and delegates), the legacy `public String currentPngBase64()` accessor (on-demand encode), and the ownership-transfer `@Override public byte[] currentPngBytes()` accessor. Update `fromCore` to pass `result.current() == null ? null : result.current().pngBytes()`.
- `LayoutDiagnostic`: identical treatment (validation message "layout PNG does not match bounded current metadata").
- Add one shared private helper next to the existing private `sha256(byte[])`:
  ```java
  private static byte[] requireDecodedPng(String pngBase64, String label) {
      if (pngBase64 == null) {
          return null;
      }
      if (pngBase64.length() > Screenshot.MAX_PNG_BYTES / 3 * 4) {
          throw new IllegalArgumentException(
                  label + " exceeds protocol response limit");
      }
      byte[] pngBytes;
      try {
          pngBytes = Base64.getDecoder().decode(pngBase64);
      } catch (IllegalArgumentException invalid) {
          throw new IllegalArgumentException(
                  label + " is not valid base64", invalid);
      }
      if (pngBytes.length > Screenshot.MAX_PNG_BYTES) {
          throw new IllegalArgumentException(
                  label + " exceeds protocol response limit");
      }
      return pngBytes;
  }
  ```
  `Screenshot.requireDecodedPng` may be folded into this shared helper (same shape, plus the non-null/non-empty screenshot checks). Remove the now-unused `MAX_BASE64_LENGTH` reference in the old InspectCompare validation and the `java.util.Base64` import only when every decode site is gone (the String accessors still use `Base64.getEncoder()`, so keep the import).

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
Apply the same substitution for the typography branch (`typography.currentPngBytes()`) and the layout branch (`layout.currentPngBytes()`). The `java.util.Base64` import in `HarnessToolHandler.java` is deleted once all decode sites are gone (Task 3 removes the last remaining reason if any).

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

### Task 3 (#23): No eager full-result serialization on the screenshot path + injected-codec guard

**Files:**
- Modify: `harness-mcp/src/main/java/dev/gdx/uiharness/mcp/HarnessToolHandler.java` (make the JSON mapper an instance collaborator with a package-private injection constructor; remove eager `encodeResult` at line 253-254 and compute `encoded` only in branches that consume it)
- Create: `harness-mcp/src/test/java/dev/gdx/uiharness/mcp/HarnessToolHandlerScreenshotPublicationTest.java`

**Interfaces:**
- Consumes: Task 1/2 byte-owned `Screenshot` with `pngBytes()` and `MAX_PNG_BYTES`.
- Produces: `HarnessToolHandler` with an instance `ObjectMapper` (default `ProtocolJson.mapper()`) injectable through a package-private constructor overload `HarnessToolHandler(Function, ArtifactReference.Publisher, ExecutorService, int, LongSupplier, ObjectMapper)`; `structured(HarnessResponse.Result)` computes `encodeResult(result)` lazily per branch; the screenshot branch performs zero full-result serializations and zero base64 conversions.

- [ ] **Step 1: Write the failing instrumentation test**

Create `harness-mcp/src/test/java/dev/gdx/uiharness/mcp/HarnessToolHandlerScreenshotPublicationTest.java`:

```java
package dev.gdx.uiharness.mcp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import dev.gdx.uiharness.protocol.ProtocolJson;
import dev.gdx.uiharness.protocol.ProtocolVersion;
import io.modelcontextprotocol.spec.McpSchema;
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
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

final class HarnessToolHandlerScreenshotPublicationTest {
    private static final MonotonicClock CLOCK = System::nanoTime;
    private static final int MAX_PNG_BYTES = HarnessResponse.Result.Screenshot.MAX_PNG_BYTES;

    @Test void maxSizeScreenshotPublishesExactBytesWithZeroInlineSerialization() {
        byte[] payload = deterministicPng(MAX_PNG_BYTES);
        String sha = sha256(payload);
        CapturedImage image = new CapturedImage(payload, sha, 1, 1, 8_192, 8_192,
                new CapturedImage.Scale(1, 1));
        RecordingPublisher artifacts = new RecordingPublisher();
        CountingMapper mapper = new CountingMapper();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                HarnessToolHandler handler = new HarnessToolHandler(
                        serviceWith(image), artifacts, executor, 1_024,
                        System::nanoTime, mapper)) {
            McpSchema.CallToolResult result = handler.handle(
                    McpSchema.CallToolRequest.builder("ui_screenshot")
                            .arguments(Map.of(
                                    "sessionId", "game",
                                    "maxWidth", 8_192, "maxHeight", 8_192,
                                    "maxPixels", 33_554_432L,
                                    "maxPngBytes", MAX_PNG_BYTES))
                            .build()).block(Duration.ofSeconds(60));

            assertFalse(result.isError());
            assertArrayEquals(payload, artifacts.lastBytes,
                    "published bytes must equal the captured PNG bytes exactly");
            assertEquals(sha, artifacts.lastReference.sha256(),
                    "digest receipt must match the captured bytes");
            assertEquals((long) MAX_PNG_BYTES, artifacts.lastReference.byteLength(),
                    "length receipt must match the captured bytes");
            assertEquals(0, mapper.writes.get(),
                    "the screenshot artifact branch must never serialize an inline result,"
                            + " hence never base64-encode the payload");
        }
    }

    @Test void countingMapperIsLiveOnSerializingBranches() {
        CountingMapper mapper = new CountingMapper();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                HarnessToolHandler handler = new HarnessToolHandler(
                        CompletableFuture::completedFuture, new RecordingPublisher(),
                        executor, 1_024, System::nanoTime, mapper)) {
            CompletableFuture<HarnessResponse> response = CompletableFuture.completedFuture(
                    new HarnessResponse.Success(
                            ProtocolVersion.V1, "mcp-1", "game",
                            new HarnessResponse.Result.Sessions(List.of(
                                    new HarnessResponse.SessionInfo("game",
                                            List.of("screenshot"))))));
            McpSchema.CallToolResult result = handler.handle(
                    McpSchema.CallToolRequest.builder("ui_sessions")
                            .arguments(Map.of()).build()).block(Duration.ofSeconds(10));
            assertFalse(result.isError());
            assertTrue(mapper.writes.get() > 0,
                    "a serializing branch must record writes, proving the counter is live");
        }
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

    /** Instruments every JSON write performed by the handler's mapper. */
    private static final class CountingMapper extends ObjectMapper {
        final AtomicLong writes = new AtomicLong();

        CountingMapper() {
            // Copy the canonical hardened configuration (base64-token stream constraint,
            // strict deserialization, deterministic ordering) from ProtocolJson.mapper().
            super(ProtocolJson.mapper());
        }

        @Override public void writeValue(JsonGenerator generator, Object value)
                throws java.io.IOException {
            writes.incrementAndGet();
            super.writeValue(generator, value);
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

`CountingMapper` inherits the hardened `ProtocolJson` configuration through Jackson's public `ObjectMapper(ObjectMapper)` copy constructor (Jackson 2.22.1), so the base64-token stream constraint still applies; its `writeValue` override counts every JSON write the handler performs (`writeValueAsBytes` and `convertValue` both flow through it). The `countingMapperIsLiveOnSerializingBranches` control test proves the counter fires when a branch legitimately serializes, so a zero count on the screenshot branch is meaningful.

- [ ] **Step 2: Run to verify failure**

Run: `xvfb-run -a ./gradlew :harness-mcp:test --tests 'dev.gdx.uiharness.mcp.HarnessToolHandlerScreenshotPublicationTest' --no-daemon --console=plain --warning-mode=fail`
Expected: FAIL — the max-size test reports `mapper.writes == 1` (the current code eagerly runs `encodeResult(result)` at the top of `structured()`, serializing the full base64 payload), and the injection constructor does not exist yet (compilation error). Either failure is a valid red.

- [ ] **Step 3: Implement the injectable mapper and lazy full-result encoding**

In `HarnessToolHandler.java`:
- Replace the static `COMMAND_MAPPER` usages with an instance field `private final ObjectMapper mapper;` (default `ProtocolJson.mapper()`), and add the package-private constructor overload:
  ```java
  HarnessToolHandler(Function<HarnessRequest, CompletionStage<HarnessResponse>> protocol,
          ArtifactReference.Publisher artifacts, ExecutorService executor,
          int artifactThresholdBytes, LongSupplier nanoClock, ObjectMapper mapper) {
      this.protocol = Objects.requireNonNull(protocol, "protocol");
      this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
      this.executor = Objects.requireNonNull(executor, "executor");
      this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
      this.mapper = Objects.requireNonNull(mapper, "mapper");
      if (artifactThresholdBytes <= 0) {
          throw new IllegalArgumentException("artifactThresholdBytes must be positive");
      }
      this.artifactThresholdBytes = artifactThresholdBytes;
      startedNanos = nanoClock.getAsLong();
      scheduler = Schedulers.fromExecutorService(executor);
  }
  ```
  Have the existing 5-argument constructor delegate to it with `ProtocolJson.mapper()`. Replace every `COMMAND_MAPPER.` reference in the file with `mapper.`.
- In `structured(...)`: delete line 254 `byte[] encoded = encodeResult(result);` from the method top; add `byte[] encoded = encodeResult(result);` as the first statement of ONLY the branches that consume it: `Sessions`, `Snapshot`, `Query`, `Action`, `Assertion`, `Wait`, `InspectCompare`, `TypographyDiagnostic`, `LayoutDiagnostic`. The `Screenshot`, `Capabilities`, `ScenarioList`, `ScenarioStart`, `LayoutValidation`, and matrix branches must NOT compute `encoded`.
- Delete the `java.util.Base64` import from `HarnessToolHandler.java` (Task 2 removed the last decode site).

- [ ] **Step 4: Run to verify pass**

Run: `xvfb-run -a ./gradlew :harness-mcp:test --tests 'dev.gdx.uiharness.mcp.HarnessToolHandlerScreenshotPublicationTest' --no-daemon --console=plain --warning-mode=fail`
Expected: PASS — the screenshot branch performs zero JSON writes (hence zero base64 encoding of the payload), the published bytes/digest/length receipts equal the captured payload, and the live-counter control test records writes on a serializing branch.

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
- Create: `docs/adr/NNNN-render-thread-ownership.md` where `NNNN` is the ADR number `N` computed in the Global Constraints (0031 on the pre-rebase base; recompute after the prerequisite rebase)

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

- [ ] **Step 5: Write ADR N**

Create `docs/adr/NNNN-render-thread-ownership.md` (NNNN = the computed ADR number `N`) with the exact header format used by every existing ADR (see `docs/adr/0017-bounded-scenario-lifecycle.md`):

```markdown
# ADR NNNN: Render-thread session ownership

- Status: Accepted
- Date: 2026-08-08
```

Then `## Context`, `## Decision`, and `## Consequences` sections with the following content:

- Context: `Scene2dSession` captured `ownerThread` at construction but only `completedFrame` enforced it; off-thread `snapshot`, `stateActionContract`, `typography`, `layout`, metadata facade, and adapter-registry access could race or return nondeterministic Scene2D reads instead of failing fast. Issue #21.
- Decision: every `Scene2dSession` method that reads or mutates the Stage, actors, adapters, semantic metadata, or completed-frame state verifies `Thread.currentThread() == ownerThread` and fails immediately with `HarnessException(ErrorCode.RENDER_THREAD_VIOLATION)` carrying the operation name, owner thread name, and caller thread name. Non-owner work MUST route through `RenderThreadScheduler` (submit from any thread, drain on the owner). `isOpen()` and `close()` stay thread-agnostic. The protocol wire adds the stable `render-thread-violation` error code; MCP receives the existing typed failure translation unchanged. The success path stays allocation-light (one reference comparison).
- Consequences: off-thread misuse fails fast with actionable evidence instead of racing; scheduler-routed caller-thread waits remain supported; a new stable error code is visible end to end (core, protocol, MCP diagnostics); correct render-thread access has no measurable cost.

- [ ] **Step 6: Commit**

```bash
git add harness-core/src/main/java/dev/gdx/uiharness/core/error/ErrorCode.java harness-protocol/src/main/java/dev/gdx/uiharness/protocol/ProtocolError.java harness-protocol/src/test/resources/contracts/v1/errors.json "docs/adr/NNNN-render-thread-ownership.md"
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

### Task 6 (#22): Atomically gate completed-frame snapshots on active runner subscriptions

**Files:**
- Modify: `harness-scene2d/src/main/java/dev/gdx/uiharness/scene2d/Scene2dScenarioRunner.java` (add `completedFrame(Supplier<SemanticSnapshot>, long, long)` beside the existing `completedFrame(SemanticSnapshot)` at line ~125)
- Modify: `harness-scene2d/src/main/java/dev/gdx/uiharness/scene2d/Scene2dNavigationRunner.java` (add `completedFrame(Supplier<SemanticSnapshot>, long, long)` beside the existing method at line ~119)
- Modify: `harness-scene2d/src/main/java/dev/gdx/uiharness/scene2d/Scene2dSession.java:65-86` (delegate both `completedFrame` overloads to the runners' atomic methods)
- Create: `harness-scene2d/src/test/java/dev/gdx/uiharness/scene2d/Scene2dSnapshotGatingTest.java`

**Interfaces:**
- Consumes: Task 5 `requireOwnerThread("completedFrame")`.
- Produces: an atomic gate on both runners:
  ```java
  /**
   * Atomically decides, under this runner's lifecycle lock, whether any run needs this completed
   * frame, and delivers it to exactly the runs active at the decision. The snapshot supplier runs
   * on the render thread after the decision and is invoked at most once per call.
   *
   * @return true when at least one active run consumed the frame
   */
  public boolean completedFrame(Supplier<SemanticSnapshot> snapshots, long revision, long frame)
  ```
  The decision (active-check) and the delivery (recipient snapshot) are one atomic step under the runner's lock, so a run starting after the decision gets the next frame and a run terminating before the decision is never an unnecessary recipient. `Scene2dSession.completedFrame` builds the shared snapshot at most once per frame and only when a runner consumed it.

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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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

    @Test void firstStartRaceNeverLosesTheRunFirstObservation() throws Exception {
        try (GatedFixture fixture = new GatedFixture();
                ExecutorService launcher = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch setupEntered = new CountDownLatch(1);
            CountDownLatch releaseSetup = new CountDownLatch(1);
            fixture.register(new ScenarioLifecycle() {
                @Override public void setup(ScenarioRequest request) {
                    setupEntered.countDown();
                    try {
                        releaseSetup.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("setup interrupted", interrupted);
                    }
                }
                @Override public void reset(ScenarioRequest request) {}
                @Override public boolean ready(ScenarioRequest request) { return true; }
                @Override public String startStateIdentity(
                        ScenarioRequest request, SemanticSnapshot snapshot) {
                    return "ready";
                }
                @Override public void cleanup(ScenarioRequest request) {}
            });
            // Launch on a second thread; the run is added to `active` synchronously at launch,
            // while begin() (and setup) only run during the next scheduler drain.
            CompletionStage<ScenarioResult> started = launcher.submit(() -> {
                CompletionStage<ScenarioResult> pending = fixture.start(Duration.ofSeconds(2));
                assertTrue(setupEntered.await(5, TimeUnit.SECONDS),
                        "the run's setup must reach the launch barrier");
                releaseSetup.countDown();
                return pending;
            }).get();

            // The frame decision races the launch boundary: the run is already active, so the
            // atomic gate consumes the frame; begin is held at the setup barrier, so the run's
            // first observation is exactly this frame and none is lost.
            fixture.completedFrame();
            ScenarioResult result = started.toCompletableFuture().join();
            assertTrue(result.startFrame() > 0,
                    "the run must observe the frame decided while it was active");
            assertTrue(fixture.rootReads() >= 1);
        }
    }

    @Test void lastTerminalRaceStopsTheStreamAtomicallyAfterTheTerminalState() {
        try (GatedFixture fixture = new GatedFixture()) {
            CompletionStage<Scene2dScenarioRunner.Lease> acquired =
                    fixture.acquire(Duration.ofSeconds(2));
            fixture.scheduler.drain();
            fixture.completedFrame();
            Scene2dScenarioRunner.Lease lease = acquired.toCompletableFuture().join();
            assertTrue(fixture.rootReads() > 0, "a READY lease is still an active run");

            // Terminal race: fire one frame while the run is READY, then release and drain.
            fixture.completedFrame();
            long beforeRelease = fixture.rootReads();
            CompletionStage<ScenarioResult> released = lease.release();
            fixture.scheduler.drain();
            released.toCompletableFuture().join();

            long afterTerminal = fixture.rootReads();
            fixture.completedFrame();
            assertEquals(afterTerminal, fixture.rootReads(),
                    "a frame decided after the last run's terminal state must build no snapshot");
            assertTrue(afterTerminal >= beforeRelease);
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

        CompletionStage<Scene2dScenarioRunner.Lease> acquire(Duration timeout) {
            return scenarios.acquire(
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

`firstStartRaceNeverLosesTheRunFirstObservation` holds the run in `setup` (which runs inside the scheduler drain on the owner thread) while a completed frame is decided: the atomic decision sees the run in `active` (it is added synchronously at `launch`), so the snapshot is built and delivered; after setup is released, the run reaches WAITING_FOR_FRAME and observes that frame — the run's `startFrame` is the frame that completed while it was active, proving a frame decided concurrently with launch is never lost. `lastTerminalRaceStopsTheStreamAtomicallyAfterTheTerminalState` fires a frame while the lease is READY (consumed — the run is still active), then releases to the terminal state; the frame decided after the terminal builds nothing, deterministically, because the decision happens under the runner's lifecycle lock with the active list. The `CountingStage.getRoot()` override counts exactly the `stage.getRoot()` calls made by `Scene2dSnapshotter.snapshot(Stage, …)` (one per built snapshot); `Scene2dSession`, `ControlledStageClock`, `Scene2dContractSnapshotter`, and `Scene2dTypographyExtractor` constructors only store the Stage, so the count is 0 at fixture construction. `GdxNativesLoader`/`NoopBatch`/`WidgetStyles` are package-visible test utilities already used by `Scene2dSnapshotterTest` and `Scene2dNavigationRunnerTest`.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :harness-scene2d:test --tests 'dev.gdx.uiharness.scene2d.Scene2dSnapshotGatingTest' --no-daemon --console=plain --warning-mode=fail`
Expected: FAIL — `idleFramesBuildNoRunnerSnapshots` reports 3 root reads instead of 0 (the current code snapshots every frame) and the runner overloads with a `Supplier` do not exist yet.

- [ ] **Step 3: Implement the atomic gate**

In `Scene2dScenarioRunner.java`, add beside the existing `completedFrame(SemanticSnapshot)`:

```java
    /**
     * Atomically decides, under this runner's lifecycle lock, whether any run needs this completed
     * frame and delivers it to exactly the runs active at the decision. The snapshot supplier runs
     * on the render thread after the decision and is invoked at most once per call.
     *
     * @return true when at least one active run consumed the frame
     */
    public boolean completedFrame(
            Supplier<SemanticSnapshot> snapshots, long revision, long frame) {
        Objects.requireNonNull(snapshots, "snapshots");
        Run[] runs;
        synchronized (lifecycle) {
            if (active.isEmpty()) {
                return false;
            }
            runs = active.toArray(Run[]::new);
        }
        SemanticSnapshot snapshot = snapshots.get();
        for (Run run : runs) {
            observeSubmission(run, scheduler.submit(() -> {
                run.observe(snapshot);
                return null;
            }, dispatchDeadline()));
        }
        return true;
    }
```

Add `import java.util.function.Supplier;` to the file. The existing `completedFrame(SemanticSnapshot)` method is unchanged (direct callers such as `Scene2dNavigationRunnerTest` and `Lwjgl3MatrixRunnerTest` keep using it).

In `Scene2dNavigationRunner.java`, the same method with its own `lifecycle`/`active`, delivering to `run.observe(snapshot)` via its own `observe(run, …)` helper.

In `Scene2dSession.java`, replace the two `completedFrame` overloads:

```java
    /** Captures and publishes one completed semantic frame while the runner has active runs. */
    public void completedFrame(Scene2dScenarioRunner runner, long revision, long frame) {
        requireOwnerThread("completedFrame");
        runner.completedFrame(() -> snapshot(revision, frame), revision, frame);
    }

    /**
     * Captures and publishes one shared completed semantic frame while either runner has active
     * runs; the snapshot is built at most once per frame and only when a runner consumed it.
     */
    public void completedFrame(
            Scene2dScenarioRunner scenarioRunner,
            Scene2dNavigationRunner navigationRunner,
            long revision,
            long frame) {
        requireOwnerThread("completedFrame");
        SemanticSnapshot[] shared = new SemanticSnapshot[1];
        Supplier<SemanticSnapshot> snapshots = () -> {
            if (shared[0] == null) {
                shared[0] = snapshot(revision, frame);
            }
            return shared[0];
        };
        scenarioRunner.completedFrame(snapshots, revision, frame);
        navigationRunner.completedFrame(snapshots, revision, frame);
    }
```

Add `import java.util.function.Supplier;` to `Scene2dSession.java`. The memoized supplier guarantees both runners observe the same snapshot instance (frame correlation) while the snapshot is built only when at least one runner's atomic decision consumed the frame.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :harness-scene2d:test --tests 'dev.gdx.uiharness.scene2d.Scene2dSnapshotGatingTest' --no-daemon --console=plain --warning-mode=fail`
Expected: PASS — idle frames build 0 snapshots; active run, cancellation, navigation, first-start race, last-terminal race, and return-to-idle behave as asserted; on-demand snapshots and frame numbers keep advancing.

Run: `./gradlew :harness-scene2d:test --no-daemon --console=plain --warning-mode=fail`
Expected: PASS — `Scene2dScenarioRunnerTest` and `Scene2dNavigationRunnerTest` still observe every frame because their runs are active when `completedFrame` fires; `FixtureControl.afterDraw()` and `ReplacementScenarioHost` continue through the session's gated overloads.

- [ ] **Step 5: Commit**

```bash
git add harness-scene2d/src/main/java/dev/gdx/uiharness/scene2d/Scene2dScenarioRunner.java harness-scene2d/src/main/java/dev/gdx/uiharness/scene2d/Scene2dNavigationRunner.java harness-scene2d/src/main/java/dev/gdx/uiharness/scene2d/Scene2dSession.java harness-scene2d/src/test/java/dev/gdx/uiharness/scene2d/Scene2dSnapshotGatingTest.java
git commit -m "perf(scene2d): atomically gate completed-frame snapshots on active runs"
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

- **#21 — session-bound render-thread enforcement.** `Scene2dSession` now rejects off-thread Stage/semantic/adapter access with `HarnessException(ErrorCode.RENDER_THREAD_VIOLATION)` carrying operation and thread names; caller-thread waits keep routing through `RenderThreadScheduler`. New core/protocol code `render-thread-violation`, golden `errors.json` entry, ADR NNNN (computed per the Global Constraints rule).
- **#22 — snapshots only for active consumers.** Each runner atomically decides under its lifecycle lock whether a completed frame is consumed (`completedFrame(Supplier<SemanticSnapshot>, long, long)`); the session builds the shared snapshot at most once per frame and only when a runner consumed it. Idle sessions skip per-frame work while fences, captures, and on-demand snapshots advance. First-start and last-terminal barrier race tests pin the atomic boundary; real LWJGL3 smoke covers idle rendering/fences.
- **#23 — no internal base64 round trips; preserved public Java API.** Protocol PNG results (`Screenshot`, `InspectCompare`, `TypographyDiagnostic`, `LayoutDiagnostic`) own immutable bytes with ownership transfer (single defensive copy per payload); the legacy String constructors and `pngBase64()`/`currentPngBase64()`/`heatmapPngBase64()` accessors remain source- and binary-compatible. The MCP handler publishes captured bytes directly; JSON keeps the base64 wire keys. Screenshot publication never serializes an inline result (injected counting-mapper guard proves zero JSON writes on that branch) and performs no base64 conversion.

Fixes #21
Fixes #22
Fixes #23
```

Include in the PR: root causes, the exact verification commands from Steps 1-2 with their output, and the compatibility statement that the wire JSON shape and existing scheduler-routed callers are unchanged.

- [ ] **Step 4: Review and merge checklist**

1. Review the remote base, head SHA, commit list, full patch, and each issue's acceptance criteria against current-state tests.
2. Confirm every commit is scoped to this cluster (tests, implementation, ADR `N`, guide docs, golden additions) and that the ADR file/header use the computed number `N` from the Global Constraints rule.
3. Reproduce and fix any actionable review finding test-first, then re-run the affected focused suite and CI on the new SHA.
4. Merge only the reviewed green SHA; verify the merge closes #21, #22, and #23.

---

## Self-Review

**1. Spec coverage (approved design, cluster 4, plus canonical issue bodies):**

- #21: off-thread Stage/session access fails immediately with a typed actionable error → Task 5 (`RENDER_THREAD_VIOLATION` + operation/thread evidence); correct scheduler-routed calls remain supported → Task 5 test 3 and unchanged `Scene2dHarness`/`Lwjgl3ScreenCapture` wiring; a render-thread fixture covers the boundary → `Scene2dSessionTest`; threading ADR documenting the new public error code → Task 4 ADR `N`; allocation-light success path → single reference comparison in `requireOwnerThread`.
- #22: idle sessions do not build runner snapshots → Task 6 counting test (atomic gate returns false; the supplier never runs); starting a runner enables the stream → Task 6 test 2 + first-start barrier race; stopping the last runner removes per-frame work → Task 6 tests 3-4 + last-terminal barrier race; frame correlation/fences remain correct → Task 6 test 6 + Task 7 real-LWJGL3 smoke; the gate is atomic (lifecycle check and delivery are one locked decision) → `completedFrame(Supplier<SemanticSnapshot>, long, long)` on both runners; proof counts snapshot construction across idle/active/navigation/cancellation/return-to-idle → `CountingStage.getRoot()` counts.
- #23: artifact publication performs no intermediate base64 round trip → Tasks 1-3 (handler publishes `pngBytes()` directly; protocol holds bytes; no `Base64` decode remains in `HarnessToolHandler`); digest/length receipts remain identical → Task 3 receipt assertions against the exact captured bytes; maximum-size coverage → Task 3 exercises `MAX_PNG_BYTES` payloads; unchanged protocol JSON shape → golden `results.json` round trip (Task 1) and wire-key test (Task 2); preserved public Java API → String constructors/accessors verified by Task 1/2 compatibility tests; single-copy ownership invariant → Global Constraints + `assertSame` ownership-transfer tests; "MCP artifact paths do not serialize an unused full inline result before offloading" → Task 3 lazy `encodeResult` + injected counting-mapper proves the screenshot branch performs zero JSON writes (hence zero base64 encoding).
- Error/compatibility policy: typed `render-thread-violation` code with operation identity and bounded details; existing valid requests, JSON shapes, artifact references, String-typed Java API, and non-scenario `Scene2dSession` use remain supported; the design's "internal byte ownership may change while serialized screenshot JSON stays compatible" clause is satisfied additively.

**2. Placeholder scan:** no TBD/TODO/“appropriate error handling”/“similar to Task N” patterns; every code step contains verbatim code; every acceptance criterion names an exact command and expected result. The ADR number is a computed value (`N` = next free number after the rebase) with an exact rule and consistent use, not a hardcoded stale constant.

**3. Type consistency:** `pngBytes()`/`currentPngBytes()`/`heatmapPngBytes()` byte accessors and the legacy `pngBase64()`/`currentPngBase64()`/`heatmapPngBase64()` String accessors are identical across Tasks 1-3; `MAX_PNG_BYTES` is the single byte bound; `RENDER_THREAD_VIOLATION` is spelled identically in `ErrorCode`, `ProtocolError.Code`, the golden wire name `render-thread-violation`, and the tests; `completedFrame(Supplier<SemanticSnapshot>, long, long)` has the same signature on both runners and is consumed only by `Scene2dSession.completedFrame`; the ADR number rule in Global Constraints, Task 4, Task 9, and this review use the same computed `N`.
