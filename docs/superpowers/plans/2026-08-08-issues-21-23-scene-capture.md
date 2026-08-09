# Issues 21–23 Scene2D Ownership and Capture Efficiency Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close GitHub issues #21 (session-bound render-thread enforcement), #22 (snapshots only for active scenario/navigation consumers), and #23 (no internal base64 PNG round trips) with typed errors, atomic additive runner APIs, preserved String-typed public API and unchanged JSON wire shape, and a documented single-copy PNG ownership invariant.

**Architecture:** `Scene2dSession` gains a `requireOwnerThread(operation)` guard on every method that reads or mutates Stage, actors, adapters, semantic metadata, or completed-frame state; failures are `HarnessException` with a new `RENDER_THREAD_VIOLATION` code that maps to a new `render-thread-violation` protocol wire code. `Scene2dSession.completedFrame` delegates to new runner overloads `completedFrame(Supplier<SemanticSnapshot>, long, long)` that atomically RESERVE a frame under each runner's lifecycle lock (recipient snapshot plus a reservation counter), run the snapshot supplier outside the lock, and deliver to the reserved recipients — a terminal transition cannot invalidate an already-reserved frame, and a run starting after the reservation observes the next frame. The public protocol PNG result records are untouched; raw captured bytes travel in an internal `HarnessProtocolService.Execution` envelope (public `HarnessResponse` plus bounded immutable `BinaryAttachment` values) from the service to the MCP handler, which streams each attachment to the publisher through a read-only buffer — no byte[] accessor, no mutable array escape, no base64 decode, no byte-typed public API, exactly two documented boundary snapshots per payload.

**Tech Stack:** Java 25, Gradle wrapper (`--no-daemon --console=plain --warning-mode=fail`), JUnit 5, Jackson 2.22.1 (records unchanged), libGDX 1.14.2 Scene2D/LWJGL3, `xvfb-run` for real LWJGL3 smoke tests on headless Linux.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-08-08-issues-8-26-release-design.md` (approved 2026-08-08), cluster 4 ("Scene2D ownership and capture efficiency"). Live issue bodies #21, #22, #23 remain authoritative.
- **Prerequisite — rebase before Task 1:** this branch (`fix/issues-21-23-scene-capture`) was cut from `origin/main` @ `c5fe5df`, before clusters #8–#13, #14–#16, and #17–#20/#24–#25 merge. Immediately before starting Task 1, rebase the branch onto the freshly merged `origin/main` (`git fetch origin && git rebase origin/main`), resolve any conflicts keeping this cluster's intent, and re-run the affected module tests once to confirm the new base is green. Every later task runs on the rebased base.
- **ADR numbering (computed at rebase):** define `N` = 1 + the largest numeric suffix among `docs/adr/*.md` files present on the rebased branch (on the pre-rebase base `N = 0031`). Every occurrence of "ADR 0031" in this plan (header, file paths, commit messages, PR body) means "ADR `N`"; fill the exact number from the rebased `docs/adr/` directory and use it consistently everywhere. Never use the pre-rebase value after the rebase has added ADRs.
- JDK 25 baseline, `--release 25`, `-Xlint:all`, warnings fail the build; no preview/incubator APIs.
- Real LWJGL3 tests on Linux require Xvfb: run them as `xvfb-run -a ./gradlew …`.
- No sleeps for synchronization: use latches, barriers, injected clocks, deadlines, and observable-state drains only.
- Public protocol JSON wire shape is invariant: keys `pngBase64`, `currentPngBase64`, `heatmapPngBase64` keep their exact names and base64 values; `errors.json` golden covers every `ProtocolError.Code` (the contract test asserts full enum coverage), so any new code requires a new golden entry.
- `ProtocolError.Code.fromCore` maps by `ErrorCode.name()`: a new core `ErrorCode` REQUIRES a matching `ProtocolError.Code` constant or runtime `IllegalArgumentException`.
- **Public Java API is preserved verbatim (binary and source compatible) for v1.2.0:** `Result.Screenshot`, `InspectCompare`, `TypographyDiagnostic`, and `LayoutDiagnostic` keep their exact String record components, constructors (including the `InspectCompare` 10-argument compatibility constructor), accessors (`pngBase64()` / `currentPngBase64()` / `heatmapPngBase64()`), generated equality/hashCode/toString, and binary descriptors. No record component changes, no byte-typed constructors or accessors are added to the public records, and no `@JsonProperty` changes are made.
- **PNG byte publication architecture (no internal round trip, no public mutable bytes):** the MCP publication path routes from an internal, documented-not-supported `HarnessProtocolService.Execution` envelope that carries the public `HarnessResponse` plus a bounded `Map<String, BinaryAttachment>` of raw capture attachments (keys `SCREENSHOT_CAPTURE`, `COMPARE_CURRENT_CAPTURE`, `COMPARE_HEATMAP_CAPTURE`, `TYPOGRAPHY_CURRENT_CAPTURE`, `LAYOUT_CURRENT_CAPTURE`). `BinaryAttachment` is an immutable value object backed by a read-only `ByteBuffer`, with no `byte[]` accessor, a fresh read-only view per read, a stream `writeTo` bridge, and `length()`/`sha256()` accessors with content-based `equals`/`hashCode`. The public protocol records never expose bytes.
- **BinaryAttachment factories and bounds:** both factory families enforce `1 <= length <= MAX_PNG_BYTES` BEFORE copying or retaining, rejecting empty and over-limit payloads with `IllegalArgumentException`. `BinaryAttachment.of(byte[])` clones the caller array into a locally owned copy FIRST and digests only that owned clone (structurally immune to caller mutation races). The package-private trusted factory `BinaryAttachment.takeCaptured(CapturedImage)` (with a `VisualHeatmap` overload for compare heatmaps) accepts NO caller-supplied buffer: it obtains the read-only internal view itself via the immutable owner's `pngView()` (a fresh read-only `ByteBuffer` over the record's private constructor-cloned bytes, `hasArray() == false`) and retains a `duplicate()` read-only slice — a caller cannot supply a read-only alias over mutable storage, there is no full copy and no mutable array escape, and the digest is tied to the immutable `CapturedImage`/`VisualHeatmap`. `Execution`'s constructor defensively owns its attachments (`Map.copyOf` over immutable values) and enforces `Execution.MAX_ATTACHMENTS` (4) with a typed `IllegalArgumentException`; per-attachment size is guaranteed by the factories.
- **PNG ownership contract (exactly two documented boundary snapshots, zero internal copies, no `pngBytes()` accessor clone):** (1) `CapturedImage` (harness-core) performs the single defensive snapshot at the capture trust boundary in its constructor; (2) the MCP publish boundary performs exactly one bounded copy — the default `ArtifactReference.Publisher.publish(String, ByteBuffer)` overload wraps the read-only buffer into the existing byte[] overload, and publishers MAY override the ByteBuffer overload for zero-copy streaming. Between construction and publication the captured bytes are referenced through read-only views, never copied and never exposed as a mutable array: the service reads `CapturedImage.pngView()` once per execution to build both the public base64 String and the attachment, and the handler streams the attachment without cloning or decoding. The only base64 conversion in the system is the public String encode in `fromCore` (the wire representation); it is never decoded internally. The legacy `CapturedImage.pngBytes()` accessor remains for existing callers but is not on the publication path.
- New public API is additive only: `CapturedImage.pngView()`, the internal `Execution` envelope, `BinaryAttachment`, the default `Publisher.publish(String, ByteBuffer)` overload, and `executeWithAttachments` on `HarnessProtocolService`, the `completedFrame(Supplier<SemanticSnapshot>, long, long)` overloads on both runners, and the `ErrorCode.RENDER_THREAD_VIOLATION` wire code.
- Every task ends with a commit that leaves the tree compiling and its focused tests green. Do not run formatters, linters, or the full suite mid-task; Task 9 runs the repository gate.

---

### Task 1 (#23): Internal execution envelope with raw screenshot attachment

**Files:**
- Modify: `harness-core/src/main/java/dev/gdx/uiharness/core/capture/CapturedImage.java` — add `pngView()` (fresh read-only `ByteBuffer` over the private constructor-cloned bytes; the existing `pngBytes()` accessor stays for legacy callers)
- Create: `harness-protocol/src/main/java/dev/gdx/uiharness/protocol/BinaryAttachment.java` — bounded immutable byte payload backed by a read-only `ByteBuffer` (documented internal)
- Modify: `harness-protocol/src/main/java/dev/gdx/uiharness/protocol/HarnessProtocolService.java` — add the internal `Execution` record, attachment key constants, `executeWithAttachments`, and the internal routing that carries raw captures
- Modify: `harness-protocol/src/main/java/dev/gdx/uiharness/protocol/HarnessResponse.java:405-418` — add an internal `Screenshot.fromCore(CapturedImage, ByteBuffer)` overload; **no change to the public `Screenshot` record**
- Modify: `harness-protocol/src/test/java/dev/gdx/uiharness/protocol/HarnessProtocolServiceTest.java` — attachment tests
- Create: `harness-protocol/src/test/java/dev/gdx/uiharness/protocol/BinaryAttachmentTest.java` — immutability, mutation, bounds, read-only, and equality tests

**Interfaces:**
- Consumes: `dev.gdx.uiharness.core.capture.CapturedImage` with the new `pngView()` (its constructor remains the single defensive snapshot at the capture trust boundary).
- Produces:
  ```java
  /** Internal: public response plus bounded immutable evidence attachments for direct artifact publication. Not part of the supported public API. */
  public record Execution(HarnessResponse response, Map<String, BinaryAttachment> captures) {
      public static final int MAX_ATTACHMENTS = 4;
      public Execution { ... defensively owns (Map.copyOf), enforces the count bound ... }
  }
  ```
  with the public internal key constants `SCREENSHOT_CAPTURE = "screenshot-capture"`, `COMPARE_CURRENT_CAPTURE = "compare-current-capture"`, `COMPARE_HEATMAP_CAPTURE = "compare-heatmap-capture"`, `TYPOGRAPHY_CURRENT_CAPTURE = "typography-current-capture"`, `LAYOUT_CURRENT_CAPTURE = "layout-current-capture"`; `HarnessProtocolService.executeWithAttachments(HarnessRequest)` → `CompletionStage<Execution>`; internal `static Screenshot fromCore(CapturedImage image, ByteBuffer raw)` in `HarnessResponse.Result.Screenshot` that encodes the wire String from the read-only view (no input copy). `BinaryAttachment` provides `public static BinaryAttachment of(byte[])` (bounds-checked, clones-then-hashes), package-private `static BinaryAttachment takeCaptured(CapturedImage)` and `takeCaptured(VisualHeatmap)` (bounds-checked; reads the owner-provided `pngView()` itself and retains a duplicate read-only slice — no caller-supplied buffer, no copy), `int length()`, `String sha256()`, `ByteBuffer asByteBuffer()` (fresh read-only view), `void writeTo(OutputStream)` (copy-free, bounded chunks), and content-based `equals`/`hashCode`. Consumed by Tasks 2 and 3.

- [ ] **Step 1: Write the failing attachment and immutability tests**

Create `harness-protocol/src/test/java/dev/gdx/uiharness/protocol/BinaryAttachmentTest.java`:

```java
package dev.gdx.uiharness.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

final class BinaryAttachmentTest {
    @Test void ofDefensivelyCopiesAndTheValueStaysImmutable() {
        byte[] supplied = {1, 2, 3, 4, 5};
        BinaryAttachment attachment = BinaryAttachment.of(supplied);
        supplied[0] = 99;
        assertEquals(5, attachment.length());
        assertEquals(sha256(new byte[] {1, 2, 3, 4, 5}), attachment.sha256(),
                "the digest must be computed over the owned clone, immune to caller mutation");
        assertArrayEquals(new byte[] {1, 2, 3, 4, 5}, readAll(attachment));
    }

    @Test void ofRejectsEmptyAndOverLimitPayloadsAtTheFactory() {
        assertThrows(IllegalArgumentException.class, () -> BinaryAttachment.of(new byte[0]));
        byte[] exact = new byte[HarnessResponse.Result.Screenshot.MAX_PNG_BYTES];
        assertEquals(HarnessResponse.Result.Screenshot.MAX_PNG_BYTES,
                BinaryAttachment.of(exact).length(), "the exact maximum must be accepted");
        byte[] over = new byte[HarnessResponse.Result.Screenshot.MAX_PNG_BYTES + 1];
        assertThrows(IllegalArgumentException.class, () -> BinaryAttachment.of(over));
    }

    @Test void readOnlyBufferRejectsWritesAndNeverExposesTheArray() {
        BinaryAttachment attachment = BinaryAttachment.of(new byte[] {1, 2, 3});
        ByteBuffer view = attachment.asByteBuffer();
        assertTrue(view.isReadOnly());
        assertThrows(ReadOnlyBufferException.class, () -> view.put((byte) 9));
        assertFalse(view.hasArray(), "the read-only view must never expose the backing array");
        assertThrows(ReadOnlyBufferException.class, view::array);
    }

    @Test void writeToStreamsTheOwnedBytesWithoutMutation() throws Exception {
        BinaryAttachment attachment = BinaryAttachment.of(new byte[] {1, 2, 3, 4});
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        attachment.writeTo(sink);
        assertArrayEquals(new byte[] {1, 2, 3, 4}, sink.toByteArray());
        assertArrayEquals(new byte[] {1, 2, 3, 4}, readAll(attachment),
                "writeTo must not consume or mutate the attachment");
    }

    @Test void equalityAndHashCodeAreContentBased() {
        BinaryAttachment first = BinaryAttachment.of(new byte[] {1, 2, 3});
        BinaryAttachment second = BinaryAttachment.of(new byte[] {1, 2, 3});
        BinaryAttachment other = BinaryAttachment.of(new byte[] {3, 2, 1});
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertNotEquals(first, other);
    }

    @Test void takeCapturedRetainsAReadOnlySliceWithoutCopying() {
        byte[] payload = {9, 8, 7};
        CapturedImage image = new CapturedImage(payload, sha256(payload), 1, 1, 3, 1,
                new CapturedImage.Scale(1, 1));
        BinaryAttachment attachment = BinaryAttachment.takeCaptured(image);
        assertEquals(3, attachment.length());
        assertEquals(sha256(payload), attachment.sha256());
        assertArrayEquals(payload, readAll(attachment));
        assertTrue(attachment.asByteBuffer().isReadOnly());
    }

    @Test void takeCapturedDigestIsTiedToTheImmutableCapturedImage() {
        byte[] payload = {1, 2, 3};
        String expected = sha256(payload);
        CapturedImage image = new CapturedImage(payload, expected, 1, 1, 3, 1,
                new CapturedImage.Scale(1, 1));
        BinaryAttachment attachment = BinaryAttachment.takeCaptured(image);
        payload[0] = 99; // mutating the source array after construction cannot affect the owner
        assertEquals(expected, attachment.sha256(),
                "the digest must be tied to the immutable captured bytes, not the caller array");
        assertArrayEquals(new byte[] {1, 2, 3}, readAll(attachment));
    }

    private static byte[] readAll(BinaryAttachment attachment) {
        ByteBuffer view = attachment.asByteBuffer();
        byte[] bytes = new byte[view.remaining()];
        view.get(bytes);
        return bytes;
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 unavailable", impossible);
        }
    }
}
```

Note: `takeCaptured` is package-private, so `BinaryAttachmentTest` (same package) can call it; add the imports `assertFalse`, `HarnessResponse`, and `dev.gdx.uiharness.core.capture.CapturedImage` to the test file. The `view::array` method reference in the read-only test resolves to `ByteBuffer.array()`, which throws `ReadOnlyBufferException` on a read-only heap buffer.

Add to `HarnessProtocolServiceTest.java` (reuse the existing `service(RecordingHarness, RecordingCapture, TraceController)` helper and `RecordingCapture`):

```java
@Test void executeWithAttachmentsCarriesTheSingleDefensiveSnapshotForScreenshot() throws Exception {
    byte[] payload = {1, 2, 3, 4, 5};
    RecordingCapture capture = new RecordingCapture();
    capture.image = new CapturedImage(payload, sha256Hex(payload), 1, 1, 5, 1,
            new CapturedImage.Scale(1, 1));
    HarnessProtocolService service = service(new RecordingHarness(), capture, traces());
    HarnessProtocolService.Execution execution = service
            .executeWithAttachments(new HarnessRequest(ProtocolVersion.V1, "game", "req-1", 10,
                    new Command.Screenshot(8, 8, 64, 128)))
            .toCompletableFuture().join();

    HarnessResponse.Result.Screenshot screenshot = assertInstanceOf(
            HarnessResponse.Result.Screenshot.class,
            assertInstanceOf(HarnessResponse.Success.class, execution.response()).result());
    assertArrayEquals(payload, Base64.getDecoder().decode(screenshot.pngBase64()),
            "the public String wire representation must decode to the captured bytes");
    BinaryAttachment attachment =
            execution.captures().get(HarnessProtocolService.SCREENSHOT_CAPTURE);
    assertArrayEquals(payload, readAll(attachment.asByteBuffer()),
            "the internal capture attachment must equal the captured bytes exactly");
    assertEquals(sha256Hex(payload), attachment.sha256());
    assertEquals(payload.length, attachment.length());
}

@Test void executeKeepsItsExactPublicContractWithEmptyCaptures() {
    RecordingCapture capture = new RecordingCapture();
    HarnessProtocolService service = service(new RecordingHarness(), capture, traces());
    HarnessResponse response = service.execute(new HarnessRequest(
            ProtocolVersion.V1, "game", "req-1", 10,
            new Command.Screenshot(8, 8, 64, 128)))
            .toCompletableFuture().join();
    HarnessResponse.Result.Screenshot screenshot = assertInstanceOf(
            HarnessResponse.Result.Screenshot.class,
            assertInstanceOf(HarnessResponse.Success.class, response).result());
    assertEquals("AQID", screenshot.pngBase64(), "public execute must keep its exact output");
}

@Test void executionBoundsAttachmentsAndDefensivelyOwnsTheMap() {
    Map<String, BinaryAttachment> supplied = new java.util.HashMap<>();
    supplied.put(HarnessProtocolService.SCREENSHOT_CAPTURE,
            BinaryAttachment.of(new byte[] {1, 2, 3}));
    HarnessProtocolService.Execution execution = new HarnessProtocolService.Execution(
            new HarnessResponse.Success(ProtocolVersion.V1, "r", "game",
                    new HarnessResponse.Result.Screenshot("AQID", "0".repeat(64),
                            1, 1, 3, 1, 1, 1)),
            supplied);
    supplied.put("extra", BinaryAttachment.of(new byte[] {9}));
    assertEquals(1, execution.captures().size(),
            "the Execution must own its attachment map defensively");

    Map<String, BinaryAttachment> tooMany = new java.util.HashMap<>();
    for (int index = 0; index < HarnessProtocolService.Execution.MAX_ATTACHMENTS + 1; index++) {
        tooMany.put("key-" + index, BinaryAttachment.of(new byte[] {(byte) index}));
    }
    assertThrows(IllegalArgumentException.class,
            () -> new HarnessProtocolService.Execution(
                    new HarnessResponse.Success(ProtocolVersion.V1, "r", "game",
                            new HarnessResponse.Result.Screenshot("AQID", "0".repeat(64),
                                    1, 1, 3, 1, 1, 1)),
                    tooMany));
    // Per-attachment size is enforced by BinaryAttachment.of/takeCaptured (1..MAX_PNG_BYTES),
    // covered in BinaryAttachmentTest.ofRejectsEmptyAndOverLimitPayloadsAtTheFactory.
}
```

Add the imports `assertArrayEquals`, `java.util.Base64`, `java.nio.ByteBuffer`, `java.util.Map`, and a local `sha256Hex`/`readAll(ByteBuffer)` helper pair (MessageDigest + HexFormat) if the file does not already have them; reuse the existing `traces()` helper that builds the `TraceController`. The `RecordingCapture.image` field is package-private, so assign it directly.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :harness-protocol:test --tests 'dev.gdx.uiharness.protocol.HarnessProtocolServiceTest' --no-daemon --console=plain --warning-mode=fail`
Expected: FAIL — compilation error: `executeWithAttachments` and `HarnessProtocolService.Execution` do not exist.

- [ ] **Step 3: Implement the envelope and single-snapshot routing**

In `HarnessResponse.java`, add the internal overload beside the existing `Screenshot.fromCore(CapturedImage)` (which now delegates):

```java
            static Screenshot fromCore(CapturedImage image) {
                return fromCore(image, image.pngView());
            }

            /** Internal: encodes the wire String from the read-only view (no input copy). */
            static Screenshot fromCore(CapturedImage image, ByteBuffer raw) {
                if (raw.remaining() > MAX_PNG_BYTES) {
                    throw new HarnessException(ErrorCode.LIMIT_EXCEEDED,
                            "Captured PNG exceeds protocol response byte limit",
                            ErrorEvidence.ofDetails(Map.of(
                                    "limit", "response-byte-limit",
                                    "maximumBytes", Integer.toString(MAX_PNG_BYTES),
                                    "actualBytes", Integer.toString(raw.remaining()))));
                }
                String pngBase64 = StandardCharsets.US_ASCII.decode(
                        Base64.getEncoder().encode(raw.duplicate())).toString();
                return new Screenshot(pngBase64, image.sha256(), image.frame(),
                        image.revision(), image.width(), image.height(),
                        image.scale().x(), image.scale().y());
            }
```

Add the import `java.nio.ByteBuffer` (and keep `java.nio.charset.StandardCharsets`) to `HarnessResponse.java`. `Base64.Encoder.encode(ByteBuffer)` encodes the view's remaining bytes into a freshly allocated encoded buffer — no input copy.

The public `Screenshot` record and all other public protocol records are untouched in this cluster.

In `harness-core/src/main/java/dev/gdx/uiharness/core/capture/CapturedImage.java`, add the zero-copy read-only view (the record's private `pngBytes` field was cloned in the constructor):

```java
    /** Returns a fresh read-only view of the owned PNG bytes; the backing array never escapes. */
    public ByteBuffer pngView() {
        return ByteBuffer.wrap(pngBytes).asReadOnlyBuffer();
    }
```

Add the import `java.nio.ByteBuffer` to the file. The existing `pngBytes()` accessor (defensive copy) remains for legacy callers and is NOT used on the new publication path.

Create `harness-protocol/src/main/java/dev/gdx/uiharness/protocol/BinaryAttachment.java`:

```java
package dev.gdx.uiharness.protocol;

import dev.gdx.uiharness.core.capture.CapturedImage;
import dev.gdx.uiharness.core.visual.VisualHeatmap;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Bounded immutable byte payload for internal artifact publication, backed by a read-only
 * {@link ByteBuffer}. There is no {@code byte[]} accessor and no mutable array can escape;
 * consumers read through fresh read-only views or the copy-free {@link #writeTo(OutputStream)}
 * bridge. Not part of the supported public API.
 */
public final class BinaryAttachment {
    /** Hard bound shared with the protocol screenshot limit. */
    static final int MAX_BYTES = HarnessResponse.Result.Screenshot.MAX_PNG_BYTES;

    private final ByteBuffer bytes; // read-only; position 0, limit = length
    private final String sha256;

    private BinaryAttachment(ByteBuffer bytes, String sha256) {
        this.bytes = bytes;
        this.sha256 = sha256;
    }

    /**
     * Defensively copies the supplied bytes. The caller array is cloned into a locally owned
     * array FIRST and the digest is computed over that owned clone only, so a concurrent caller
     * mutation can never desynchronize the digest from the stored content.
     */
    public static BinaryAttachment of(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        requireBounded(bytes.length);
        byte[] owned = bytes.clone();
        return new BinaryAttachment(ByteBuffer.wrap(owned).asReadOnlyBuffer(), sha256(owned));
    }

    /**
     * Trusted internal transfer from an immutable capture owner. Accepts NO caller-supplied
     * buffer: the factory reads the owner's read-only {@code pngView()} itself, so a caller can
     * never supply a read-only alias over mutable storage. Retains a duplicate slice without
     * copying; the backing array never escapes and can never be mutated through this value.
     */
    static BinaryAttachment takeCaptured(CapturedImage image) {
        Objects.requireNonNull(image, "image");
        return takeView(image.pngView());
    }

    /** Trusted internal transfer from an immutable heatmap owner (same provenance contract). */
    static BinaryAttachment takeCaptured(VisualHeatmap heatmap) {
        Objects.requireNonNull(heatmap, "heatmap");
        return takeView(heatmap.pngView());
    }

    private static BinaryAttachment takeView(ByteBuffer readOnly) {
        requireBounded(readOnly.remaining());
        ByteBuffer retained = readOnly.duplicate();
        return new BinaryAttachment(retained.asReadOnlyBuffer(), sha256(retained));
    }

    /** Returns the number of owned bytes. */
    public int length() {
        return bytes.remaining();
    }

    /** Returns the canonical lowercase SHA-256 of the owned bytes. */
    public String sha256() {
        return sha256;
    }

    /** Returns a fresh read-only view of the owned bytes. */
    public ByteBuffer asByteBuffer() {
        return bytes.duplicate();
    }

    /** Writes the owned bytes to the supplied sink in bounded chunks without a full copy. */
    public void writeTo(OutputStream sink) throws IOException {
        Objects.requireNonNull(sink, "sink");
        ByteBuffer local = bytes.duplicate();
        byte[] chunk = new byte[8_192];
        while (local.hasRemaining()) {
            int count = Math.min(chunk.length, local.remaining());
            local.get(chunk, 0, count);
            sink.write(chunk, 0, count);
        }
    }

    @Override public boolean equals(Object other) {
        return other instanceof BinaryAttachment that && Arrays.equals(readAll(bytes), readAll(that.bytes));
    }

    @Override public int hashCode() {
        return Arrays.hashCode(readAll(bytes));
    }

    private static void requireBounded(int length) {
        if (length < 1 || length > MAX_BYTES) {
            throw new IllegalArgumentException(
                    "attachment length must be between 1 and " + MAX_BYTES + ": " + length);
        }
    }

    private static byte[] readAll(ByteBuffer view) {
        ByteBuffer local = view.duplicate();
        byte[] bytes = new byte[local.remaining()];
        local.get(bytes);
        return bytes;
    }

    private static String sha256(ByteBuffer view) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            ByteBuffer local = view.duplicate();
            byte[] chunk = new byte[8_192];
            while (local.hasRemaining()) {
                int count = Math.min(chunk.length, local.remaining());
                local.get(chunk, 0, count);
                digest.update(chunk, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 unavailable", impossible);
        }
    }
}
```

`takeCaptured` is the only trusted zero-copy entry point and it takes the provenance-bearing immutable owner, not a buffer; `takeView` is private and reachable only from `takeCaptured`. `of` owns its private clone. All factories enforce the `1..MAX_PNG_BYTES` bound before copying or retaining, and `CapturedImage`/`VisualHeatmap` are immutable records whose `pngView()` returns `hasArray() == false` views over private constructor-cloned bytes.

In `HarnessProtocolService.java`:

- Add the internal envelope and keys as nested members of `HarnessProtocolService`:
  ```java
  /** Internal capture attachment key for screenshot results. */
  public static final String SCREENSHOT_CAPTURE = "screenshot-capture";
  /** Internal capture attachment key for inspect-compare current evidence. */
  public static final String COMPARE_CURRENT_CAPTURE = "compare-current-capture";
  /** Internal capture attachment key for inspect-compare heatmap evidence. */
  public static final String COMPARE_HEATMAP_CAPTURE = "compare-heatmap-capture";
  /** Internal capture attachment key for typography current evidence. */
  public static final String TYPOGRAPHY_CURRENT_CAPTURE = "typography-current-capture";
  /** Internal capture attachment key for layout current evidence. */
  public static final String LAYOUT_CURRENT_CAPTURE = "layout-current-capture";

  /**
   * Internal execution result: the public response plus bounded immutable evidence attachments for
   * direct artifact publication, so the MCP publication path never base64 round-trips the public
   * string representation. Not part of the supported public API.
   */
  public record Execution(HarnessResponse response, Map<String, BinaryAttachment> captures) {
      /** Maximum number of attachments per execution. */
      public static final int MAX_ATTACHMENTS = 4;

      public Execution {
          response = Objects.requireNonNull(response, "response");
          captures = Map.copyOf(Objects.requireNonNull(captures, "captures"));
          if (captures.size() > MAX_ATTACHMENTS) {
              throw new IllegalArgumentException(
                      "too many execution attachments: " + captures.size());
          }
          for (String key : captures.keySet()) {
              ProtocolJson.requireIdentifier(key, "attachment key");
          }
          // Per-attachment size is enforced by BinaryAttachment's factories (1..MAX_PNG_BYTES).
      }
  }
  ```
- Add an internal routed value so each command's mapping can attach captures:
  ```java
  private record RoutedValue(HarnessResponse.Result result,
          Map<String, BinaryAttachment> captures) {
      static RoutedValue plain(HarnessResponse.Result result) {
          return new RoutedValue(result, Map.of());
      }
  }
  ```
  Change `RoutedOperation`'s mapper type from `Function<? super T, ? extends HarnessResponse.Result>` to `Function<? super T, ? extends RoutedValue>`, and `RoutedOperation.completed` to wrap with `RoutedValue.plain`.
- Refactor the entry points, preserving the existing `ResponseFuture` cancellation semantics (the future returned by `execute` forwards cancellation to the internal execution future):
  ```java
  /** Executes one command with the exact public contract of the previous execute. */
  public CompletionStage<HarnessResponse> execute(HarnessRequest request) {
      return executeInternal(request).thenApply(Execution::response);
  }

  /** Internal: executes one command and returns the raw capture attachments for publication. */
  public CompletionStage<Execution> executeWithAttachments(HarnessRequest request) {
      return executeInternal(request);
  }

  private CompletionStage<Execution> executeInternal(HarnessRequest request) { ... }
  ```
  Adapt `ResponseFuture` to complete `HarnessProtocolService.Execution` (building `Success`/`Failure` exactly as today and attaching the routed value's captures), and have `executeInternal` return it. A successful `execute` response must be byte-for-byte identical to today.
- The screenshot branch of `route(...)` becomes the single-snapshot mapping (the array is transferred into the immutable attachment, so no second copy exists):
  ```java
  if (command instanceof Command.Screenshot screenshot) {
      return RoutedOperation.map(session.capture().capture(screenshot.toCore(), deadline),
              image -> {
                  return new RoutedValue(
                          HarnessResponse.Result.Screenshot.fromCore(image, image.pngView()),
                          Map.of(SCREENSHOT_CAPTURE, BinaryAttachment.takeCaptured(image)));
              });
  }
  ```
  All other branches use `RoutedValue.plain(...)` so `executeWithAttachments` returns empty captures for them.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :harness-protocol:test --tests 'dev.gdx.uiharness.protocol.HarnessProtocolServiceTest' --tests 'dev.gdx.uiharness.protocol.BinaryAttachmentTest' --no-daemon --console=plain --warning-mode=fail`
Expected: PASS — the attachment, immutability, mutation, oversize, and public-contract tests pass, and all existing routing/cancellation tests keep passing.

Run: `./gradlew :harness-protocol:test --no-daemon --console=plain --warning-mode=fail`
Expected: PASS — the golden `results.json` round-trip and every other protocol test are untouched by the internal routing change.

- [ ] **Step 5: Commit**

```bash
git add harness-protocol/src/main/java/dev/gdx/uiharness/protocol/HarnessProtocolService.java harness-protocol/src/main/java/dev/gdx/uiharness/protocol/HarnessResponse.java harness-protocol/src/main/java/dev/gdx/uiharness/protocol/BinaryAttachment.java harness-protocol/src/test/java/dev/gdx/uiharness/protocol/HarnessProtocolServiceTest.java harness-protocol/src/test/java/dev/gdx/uiharness/protocol/BinaryAttachmentTest.java
git commit -m "feat(protocol): internal execution envelope with bounded immutable BinaryAttachment"
```

---

### Task 2 (#23): MCP artifact publication routes from the internal capture attachment

**Files:**
- Modify: `harness-mcp/src/main/java/dev/gdx/uiharness/mcp/HarnessToolHandler.java` — protocol source type, constructors, `toMcpResult`/`structured` signature, screenshot branch, lazy `encoded`
- Modify: `harness-mcp/src/main/java/dev/gdx/uiharness/mcp/ArtifactReference.java` — add the default `publish(String, ByteBuffer)` overload to `Publisher` (keeps it a functional interface)
- Modify: `harness-mcp/src/test/java/dev/gdx/uiharness/mcp/HarnessMcpServerContractTest.java` — new observable no-round-trip test, max-size receipt test, update the `malformedScreenshotCanApplyReturnedCorrectionAndSucceedWithinBudget` lambda to `executeWithAttachments`

**Interfaces:**
- Consumes: Task 1 `HarnessProtocolService.Execution`, `BinaryAttachment`, `SCREENSHOT_CAPTURE`, `executeWithAttachments`.
- Produces: `HarnessToolHandler` whose production path publishes the raw capture attachment through the read-only buffer; the screenshot branch performs zero base64 conversions, zero byte[] exposure, and zero full-result serializations; `structured(HarnessResponse.Result, Map<String, BinaryAttachment>)` computes `encodeResult` lazily only in branches that need it; `ArtifactReference.Publisher` gains `default ArtifactReference publish(String mediaType, ByteBuffer content)` that copies once into the existing byte[] overload and may be overridden for zero-copy streaming.

- [ ] **Step 1: Write the failing observable tests**

Add to `HarnessMcpServerContractTest.java`:

```java
@Test void screenshotPublicationUsesTheInternalCaptureNotThePublicString() {
    // A deliberately invalid public base64 string cannot be decoded: successful publication
    // proves the artifact path uses the internal capture attachment, not the public String.
    HarnessResponse.Result.Screenshot screenshot = new HarnessResponse.Result.Screenshot(
            "not-valid-base64-!!!", "0".repeat(64), 1, 1, 3, 1, 1, 1);
    byte[] attachment = {7, 8, 9};
    RecordingArtifacts artifacts = new RecordingArtifacts();
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
            HarnessToolHandler handler = new HarnessToolHandler(
                    (HarnessToolHandler.ExecutionSource) request -> CompletableFuture.completedFuture(
                            new HarnessProtocolService.Execution(
                                    new HarnessResponse.Success(
                                            ProtocolVersion.V1, request.requestId(),
                                            request.sessionId(), screenshot),
                                    Map.of(HarnessProtocolService.SCREENSHOT_CAPTURE,
                                            BinaryAttachment.of(attachment)))),
                    artifacts, executor, 1_024, System::nanoTime)) {
        McpSchema.CallToolResult result = handler.handle(call("ui_screenshot", Map.of(
                "sessionId", "game", "maxWidth", 8, "maxHeight", 8,
                "maxPixels", 64, "maxPngBytes", 128))).block(Duration.ofSeconds(10));

        assertFalse(result.isError());
        assertEquals(List.of((byte) 7, (byte) 8, (byte) 9), boxed(artifacts.lastBytes),
                "publication must publish the internal capture attachment bytes");
    }
}

@Test void maxSizeScreenshotPublishesExactCaptureBytesWithMatchingReceipts() {
    byte[] payload = new byte[HarnessResponse.Result.Screenshot.MAX_PNG_BYTES];
    for (int index = 0; index < payload.length; index++) {
        payload[index] = (byte) (index % 251);
    }
    String sha = sha256Hex(payload);
    RecordingArtifacts artifacts = new RecordingArtifacts();
    try (HarnessToolHandler handler = new HarnessToolHandler(
            serviceWithCapture(payload, sha), artifacts)) {
        McpSchema.CallToolResult result = handler.handle(call("ui_screenshot", Map.of(
                "sessionId", "game", "maxWidth", 8_192, "maxHeight", 8_192,
                "maxPixels", 33_554_432L, "maxPngBytes", payload.length)))
                .block(Duration.ofSeconds(60));

        assertFalse(result.isError());
        assertArrayEquals(payload, artifacts.lastBytes,
                "published bytes must equal the captured PNG bytes exactly");
        assertEquals(sha, artifacts.lastReference.sha256(),
                "digest receipt must match the captured bytes");
        assertEquals((long) payload.length, artifacts.lastReference.byteLength(),
                "length receipt must match the captured bytes");
    }
}
```

`serviceWithCapture(payload, sha)` is the existing `service(new RecordingHarness())` pattern with a `ScreenCapture` returning `new CapturedImage(payload, sha, 1, 1, 8_192, 8_192, new CapturedImage.Scale(1, 1))`. `RecordingArtifacts` gains a `lastReference` field (the existing class records `lastBytes`). Add the import `dev.gdx.uiharness.protocol.BinaryAttachment`. Update the lambda in `malformedScreenshotCanApplyReturnedCorrectionAndSucceedWithinBudget` from `service(new RecordingHarness()).execute(request)` to `service(new RecordingHarness()).executeWithAttachments(request)` and construct the handler through the `ExecutionSource` constructor (same executor/threshold/nanoClock arguments).

- [ ] **Step 2: Run to verify failure**

Run: `xvfb-run -a ./gradlew :harness-mcp:test --tests 'dev.gdx.uiharness.mcp.HarnessMcpServerContractTest' --no-daemon --console=plain --warning-mode=fail`
Expected: FAIL — `HarnessToolHandler.ExecutionSource` and the `Execution` constructor do not exist (compilation error), and the current handler still decodes `pngBase64` on the screenshot branch.

- [ ] **Step 3: Implement the attachment-driven publication path**

In `ArtifactReference.java`, add the ByteBuffer overload to the `Publisher` functional interface (default methods keep the SAM contract):

```java
    @FunctionalInterface
    public interface Publisher {
        /** Publishes one immutable payload; implementations must not expose filesystem paths. */
        ArtifactReference publish(String mediaType, byte[] content);

        /**
         * Publishes one immutable payload from a read-only buffer. The default implementation
         * copies once into {@link #publish(String, byte[])}; publishers may override for
         * zero-copy streaming. Implementations must not retain the buffer beyond the call.
         */
        default ArtifactReference publish(String mediaType, ByteBuffer content) {
            byte[] copy = new byte[content.remaining()];
            content.get(copy);
            return publish(mediaType, copy);
        }
    }
```

In `HarnessToolHandler.java`:

- Add the internal source interface and change the protocol field:
  ```java
  /** Internal protocol source carrying raw capture attachments for direct publication. */
  @FunctionalInterface
  interface ExecutionSource {
      CompletionStage<HarnessProtocolService.Execution> apply(HarnessRequest request);
  }

  private final ExecutionSource protocol;
  ```
- The public constructor `(HarnessProtocolService, ArtifactReference.Publisher)` becomes `this(protocol::executeWithAttachments, artifacts, …)`.
- The existing package-private constructors taking `Function<HarnessRequest, CompletionStage<HarnessResponse>>` wrap into the execution path with empty captures:
  ```java
  HarnessToolHandler(Function<HarnessRequest, CompletionStage<HarnessResponse>> protocol,
          ArtifactReference.Publisher artifacts, ExecutorService executor,
          int artifactThresholdBytes, LongSupplier nanoClock) {
      this(request -> protocol.apply(request).thenApply(response ->
              new HarnessProtocolService.Execution(response, Map.of())),
          artifacts, executor, artifactThresholdBytes, nanoClock);
  }
  ```
- Add the package-private `ExecutionSource` constructor (source, publisher, executor, threshold, nanoClock) that stores the source and configures the scheduler — the distinct functional-interface type avoids constructor erasure clashes with the `Function`-based overloads.
- In `handle(...)`, change the invocation to unwrap the execution:
  ```java
  CompletionStage<HarnessProtocolService.Execution> stage;
  try {
      stage = Objects.requireNonNull(protocol.apply(request), "protocol stage");
  } catch (RuntimeException failure) { ... }
  return Mono.fromFuture(stage.toCompletableFuture())
          .map(execution -> toMcpResult(
                  execution.response(), execution.captures(),
                  call.name(), sequence, arguments))
          ...
  ```
- Change `toMcpResult(HarnessResponse response, Map<String, BinaryAttachment> captures, …)` to pass `captures` into `structured(success.result(), captures)`.
- Change `structured(HarnessResponse.Result result, Map<String, BinaryAttachment> captures)`:
  - Delete the eager `byte[] encoded = encodeResult(result);` from the method top.
  - Add `byte[] encoded = encodeResult(result);` as the first statement of ONLY the branches that consume it: `Sessions`, `Snapshot`, `Query`, `Action`, `Assertion`, `Wait`, `InspectCompare`, `TypographyDiagnostic`, `LayoutDiagnostic`. The `Screenshot`, `Capabilities`, `ScenarioList`, `ScenarioStart`, `LayoutValidation`, and matrix branches must NOT compute `encoded`.
  - Replace the screenshot branch with attachment publication through the read-only buffer (keep the record pattern binding for the metadata fields):
    ```java
    if (result instanceof HarnessResponse.Result.Screenshot screenshot) {
        BinaryAttachment png = requireCapture(captures, HarnessProtocolService.SCREENSHOT_CAPTURE);
        ArtifactReference reference = artifacts.publish("image/png", png.asByteBuffer());
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
  - Add the helper:
    ```java
    private static BinaryAttachment requireCapture(
            Map<String, BinaryAttachment> captures, String key) {
        BinaryAttachment attachment = captures.get(key);
        if (attachment == null) {
            throw new IllegalArgumentException("accepted screenshot evidence is missing PNG bytes");
        }
        return attachment;
    }
    ```
- Delete the now-unused `java.util.Base64` import from `HarnessToolHandler.java` (Task 3 removes the remaining branches).

- [ ] **Step 4: Run to verify pass**

Run: `xvfb-run -a ./gradlew :harness-mcp:test --tests 'dev.gdx.uiharness.mcp.HarnessMcpServerContractTest' --no-daemon --console=plain --warning-mode=fail`
Expected: PASS — the invalid-public-String test proves publication uses the attachment; the max-size receipts match; `screenshotAndLargeResultsUseInjectedOpaqueArtifactReferences` still publishes `[1, 2, 3]` via the service path; the corrected-screenshot recovery test passes through `executeWithAttachments`.

Run: `xvfb-run -a ./gradlew :harness-mcp:test --no-daemon --console=plain --warning-mode=fail`
Expected: PASS — offload tests and every other MCP contract test unchanged.

- [ ] **Step 5: Commit**

```bash
git add harness-mcp/src/main/java/dev/gdx/uiharness/mcp/HarnessToolHandler.java harness-mcp/src/main/java/dev/gdx/uiharness/mcp/ArtifactReference.java harness-mcp/src/test/java/dev/gdx/uiharness/mcp/HarnessMcpServerContractTest.java
git commit -m "feat(mcp): publish screenshot artifacts from the internal capture attachment"
```

---

### Task 3 (#23): Compare, typography, and layout attachments with preserved public records

**Files:**
- Modify: `harness-core/src/main/java/dev/gdx/uiharness/core/visual/VisualHeatmap.java` — add `pngView()` (fresh read-only `ByteBuffer` over the private constructor-cloned bytes, same pattern as `CapturedImage.pngView()`)
- Modify: `harness-protocol/src/main/java/dev/gdx/uiharness/protocol/HarnessResponse.java:534-703` — internal `fromCore` overloads taking `ByteBuffer` views for `InspectCompare`, `TypographyDiagnostic`, `LayoutDiagnostic` (existing public records unchanged)
- Modify: `harness-protocol/src/main/java/dev/gdx/uiharness/protocol/HarnessProtocolService.java` — routing for the three commands attaches via `BinaryAttachment.takeCaptured(owner)`
- Modify: `harness-mcp/src/main/java/dev/gdx/uiharness/mcp/HarnessToolHandler.java:379-515` — the three branches publish from `captures`
- Modify: `harness-protocol/src/test/java/dev/gdx/uiharness/protocol/HarnessProtocolServiceTest.java` — attachment consistency tests

**Interfaces:**
- Consumes: Task 1 keys `COMPARE_CURRENT_CAPTURE`, `COMPARE_HEATMAP_CAPTURE`, `TYPOGRAPHY_CURRENT_CAPTURE`, `LAYOUT_CURRENT_CAPTURE`, the `Execution` envelope, `CapturedImage.pngView()`, and the new `VisualHeatmap.pngView()`.
- Produces: internal `InspectCompare.fromCore(VisualComparisonResult, ByteBuffer currentRaw, ByteBuffer heatmapRaw)`, `TypographyDiagnostic.fromCore(..., ByteBuffer currentRaw)`, `LayoutDiagnostic.fromCore(..., ByteBuffer currentRaw)`; service routing attaches the immutable owners (`BinaryAttachment.takeCaptured(result.current().image())` / `takeCaptured(result.heatmap())` / `takeCaptured(result.current())`); the MCP branches stream those attachments.

- [ ] **Step 1: Write the failing attachment tests**

Add to `HarnessProtocolServiceTest.java` a test that drives `ui_inspect_compare`, `ui_typography_diagnose`, and `ui_layout_diagnose` through a service whose `InspectCaptureCompareService`/`TypographyDiagnosticService`/`LayoutDiagnosticService` return core results carrying `CapturedImage` payloads (wire the services following the existing `InspectCaptureCompareServiceTest`/`TypographyDiagnosticServiceTest`/`LayoutDiagnosticServiceTest` fixture patterns):

```java
@Test void compareTypographyAndLayoutAttachmentsMatchTheirPublicStrings() throws Exception {
    byte[] current = {1, 2, 3, 4};
    byte[] heatmap = {5, 6, 7};
    HarnessProtocolService service = serviceWithDiagnostics(current, heatmap);
    // For each command, assert:
    //   Base64.getDecoder().decode(publicResult.currentPngBase64())
    //       equals execution.captures().get(<matching key>) and equals the captured payload.
    // InspectCompare also attaches COMPARE_HEATMAP_CAPTURE == heatmap.
}
```

The assertion contract is identical for all three families: the public String decodes to the attachment array, and the attachment array equals the captured payload bytes. If wiring all three diagnostic services into the existing test file is disproportionately large, place this test in a new `ExecutionAttachmentTest.java` that reuses the three service-test fixture helpers; either location must exercise the real `HarnessProtocolService` routing.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :harness-protocol:test --tests 'dev.gdx.uiharness.protocol.HarnessProtocolServiceTest' --no-daemon --console=plain --warning-mode=fail` (and the new test class if created)
Expected: FAIL — the routing does not attach compare/typography/layout captures yet.

- [ ] **Step 3: Implement internal fromCore overloads and routing**

In `HarnessResponse.java`, for each of `InspectCompare`, `TypographyDiagnostic`, `LayoutDiagnostic`:
- Keep the existing `fromCore(...)` method delegating to a new internal overload that takes the raw arrays and encodes the public Strings from them (no second `pngBytes()` call):
  ```java
  static InspectCompare fromCore(VisualComparisonResult result) {
      return fromCore(result,
              result.current() == null ? null : result.current().image().pngView(),
              result.heatmap() == null ? null : result.heatmap().pngView());
  }

  /** Internal: encodes the wire Strings from the read-only views (no input copy). */
  static InspectCompare fromCore(VisualComparisonResult result,
          ByteBuffer currentRaw, ByteBuffer heatmapRaw) {
      Objects.requireNonNull(result, "result");
      String currentPng = currentRaw == null ? null
              : StandardCharsets.US_ASCII.decode(
                      Base64.getEncoder().encode(currentRaw.duplicate())).toString();
      String heatmapPng = heatmapRaw == null ? null
              : StandardCharsets.US_ASCII.decode(
                      Base64.getEncoder().encode(heatmapRaw.duplicate())).toString();
      return new InspectCompare(... same argument list as today, with currentPng/heatmapPng ...);
  }
  ```
  Apply the same shape to `TypographyDiagnostic` (single `currentRaw`) and `LayoutDiagnostic` (single `currentRaw`); add `java.nio.ByteBuffer` and `java.nio.charset.StandardCharsets` imports to `HarnessResponse.java`.
- In `HarnessProtocolService.java`, change the three command routes to attach the exact arrays used for the encode:
  ```java
  if (command instanceof Command.InspectCompare compare) {
      ...
      return RoutedOperation.map(comparison.execute(compare.toCore(), deadline), result -> {
          return new RoutedValue(
                  HarnessResponse.Result.InspectCompare.fromCore(result,
                          result.current() == null ? null : result.current().image().pngView(),
                          result.heatmap() == null ? null : result.heatmap().pngView()),
                  captures(COMPARE_CURRENT_CAPTURE,
                          result.current() == null ? null : result.current().image(),
                          COMPARE_HEATMAP_CAPTURE, result.heatmap()));
      });
  }
  ```
  with a private helper:
  ```java
  private static Map<String, BinaryAttachment> captures(
          String firstKey, CapturedImage first, String secondKey, VisualHeatmap second) {
      java.util.LinkedHashMap<String, BinaryAttachment> captures = new java.util.LinkedHashMap<>();
      if (first != null) {
          captures.put(firstKey, BinaryAttachment.takeCaptured(first));
      }
      if (second != null) {
          captures.put(secondKey, BinaryAttachment.takeCaptured(second));
      }
      return captures;
  }
  ```
  Typography and layout route with `TYPOGRAPHY_CURRENT_CAPTURE`/`LAYOUT_CURRENT_CAPTURE` and their `fromCore(result, current)` overloads. Both `TypographyDiagnosticResult` and `LayoutDiagnosticResult` carry `CapturedImage current` (verified in harness-core), so the routing reads `result.current() == null ? null : result.current().pngView()` for the encode and attaches `BinaryAttachment.takeCaptured(result.current())`.
- In `HarnessToolHandler.java`, replace the three decode+clone branches with read-only-buffer publication (keep the content fields and the sha256 receipt verification exactly as today):
  ```java
  if (comparison.current() != null) {
      ArtifactReference current = artifacts.publish("image/png", requireCapture(
              captures, HarnessProtocolService.COMPARE_CURRENT_CAPTURE).asByteBuffer());
      if (!current.sha256().equals(comparison.current().sha256())) { ... }
      content.put("currentArtifact", artifactMap(current));
      ... unchanged field puts ...
  }
  if (comparison.heatmap() != null) {
      ArtifactReference heatmap = artifacts.publish("image/png", requireCapture(
              captures, HarnessProtocolService.COMPARE_HEATMAP_CAPTURE).asByteBuffer());
      ... unchanged ...
  }
  ```
  and the same for typography (`TYPOGRAPHY_CURRENT_CAPTURE`) and layout (`LAYOUT_CURRENT_CAPTURE`). Delete the `java.util.Base64` import from `HarnessToolHandler.java` once the last decode site is gone.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :harness-protocol:test --tests 'dev.gdx.uiharness.protocol.HarnessProtocolServiceTest' --no-daemon --console=plain --warning-mode=fail`
Expected: PASS — the attachment-vs-public-String consistency tests hold for all three result families.

Run: `xvfb-run -a ./gradlew :harness-mcp:test --no-daemon --console=plain --warning-mode=fail`
Expected: PASS — `typographyFailurePublishesBoundedEvidenceArtifact` and `layoutFailurePublishesBoundedEvidenceArtifact` (incomplete results with no current capture) still publish their evidence artifacts, and every other MCP test passes with the attachment-driven branches.

- [ ] **Step 5: Commit**

```bash
git add harness-protocol/src/main/java/dev/gdx/uiharness/protocol/HarnessResponse.java harness-protocol/src/main/java/dev/gdx/uiharness/protocol/HarnessProtocolService.java harness-protocol/src/test/java/dev/gdx/uiharness/protocol/HarnessProtocolServiceTest.java harness-mcp/src/main/java/dev/gdx/uiharness/mcp/HarnessToolHandler.java
git commit -m "feat(protocol,mcp): publish compare/typography/layout PNG evidence from internal captures"
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
- Produces: a reservation-token gate on both runners:
  ```java
  /**
   * Atomically reserves this completed frame for every run active at the call: the recipient
   * snapshot and a reservation counter are taken under the lifecycle lock, the snapshot supplier
   * runs OUTSIDE the lock (at most once per call), and the delivery consumes the reservation even
   * if a terminal transition occurs meanwhile — a terminal cannot invalidate an already-reserved
   * frame, and a run starting after the reservation observes the next frame.
   *
   * @return true when at least one active run consumed the frame
   */
  public boolean completedFrame(Supplier<SemanticSnapshot> snapshots, long revision, long frame)
  ```
  The reservation (active-check + recipient snapshot + counter increment) is the atomic decision; the supplier and the submission enqueue are deliberately lock-free, so no lifecycle lock is held during the snapshot build and no deadlock is possible. `Scene2dSession.completedFrame` builds the shared snapshot at most once per frame and only when a runner consumed it.

- [ ] **Step 1: Write the failing counting and barrier tests**

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
import dev.gdx.uiharness.core.scenario.ScenarioFailure;
import dev.gdx.uiharness.core.scenario.ScenarioRegistry;
import dev.gdx.uiharness.core.scenario.ScenarioRequest;
import dev.gdx.uiharness.core.scenario.ScenarioResult;
import dev.gdx.uiharness.core.time.Deadline;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
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
            CountDownLatch launched = new CountDownLatch(1);
            fixture.register(new RecordingLifecycle(new ArrayList<>(), true, "ready"));
            CompletableFuture<CompletionStage<ScenarioResult>> startedHolder =
                    new CompletableFuture<>();
            launcher.submit(() -> {
                // The run is added to `active` synchronously at launch; begin() is queued.
                startedHolder.complete(fixture.start(Duration.ofSeconds(2)));
                launched.countDown();
            });

            // Owner thread: wait for the launch barrier (run active, begin still queued),
            // then decide the frame BEFORE any drain runs begin().
            assertTrue(launched.await(5, TimeUnit.SECONDS), "launch must reach the barrier");
            fixture.clock.advance(GatedFixture.STEP);
            fixture.session.completedFrame(fixture.scenarios, fixture.navigation,
                    fixture.clock.revision(), fixture.clock.frame());
            fixture.scheduler.drain();
            ScenarioResult result = startedHolder.join().toCompletableFuture().join();
            assertTrue(result.startFrame() > 0,
                    "the run must observe the frame decided while it was active");
            assertTrue(fixture.rootReads() >= 1);
        }
    }

    @Test void lastTerminalReservationWinsDespiteConcurrentTerminal() throws Exception {
        try (GatedFixture fixture = new GatedFixture();
                ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletionStage<Scene2dScenarioRunner.Lease> acquired =
                    fixture.acquire(Duration.ofSeconds(2));
            fixture.scheduler.drain();
            fixture.completedFrame();
            Scene2dScenarioRunner.Lease lease = acquired.toCompletableFuture().join();
            assertTrue(fixture.rootReads() > 0, "a READY lease is still an active run");

            // Barrier: a reserver thread runs completedFrame; its supplier signals entered and
            // then blocks (the lifecycle lock is NOT held during the supplier). A closer thread
            // signals attemptingClose immediately before close(); the owner then DRAINS so the
            // terminal completes and the last run is REMOVED from `active` WHILE the reservation
            // is still in flight; only then does the owner release the supplier. The reserved
            // delivery must still complete — this fails against a non-token design that re-reads
            // `active` after the supplier.
            CountDownLatch supplierEntered = new CountDownLatch(1);
            CountDownLatch releaseSupplier = new CountDownLatch(1);
            CountDownLatch attemptingClose = new CountDownLatch(1);
            AtomicInteger supplierCalls = new AtomicInteger();
            SemanticSnapshot snapshot = new SemanticSnapshot(1, 1, "root", Map.of());
            CompletableFuture<Boolean> reservation = new CompletableFuture<>();
            workers.submit(() -> {
                try {
                    reservation.complete(fixture.scenarios.completedFrame(() -> {
                        supplierCalls.incrementAndGet();
                        supplierEntered.countDown();
                        try {
                            releaseSupplier.await();
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError("supplier interrupted", interrupted);
                        }
                        return snapshot;
                    }, 1, 1));
                } catch (Throwable failure) {
                    reservation.completeExceptionally(failure);
                }
            });
            CompletableFuture<Void> closeDone = new CompletableFuture<>();
            CompletableFuture<ScenarioResult> terminalResult = new CompletableFuture<>();
            // releaseSupplier MUST be released on every path, so the try begins immediately
            // after the reserver is submitted and EVERY wait/assertion (including
            // supplierEntered) lives inside it; a failure can never strand the blocked
            // supplier or hang the worker executor. The joins after the try are all bounded.
            try {
                assertTrue(supplierEntered.await(5, TimeUnit.SECONDS),
                        "the reservation must enter the supplier");
                workers.submit(() -> {
                    attemptingClose.countDown();
                    fixture.scenarios.close();
                    closeDone.complete(null);
                    lease.completion().whenComplete((value, failure) ->
                            terminalResult.complete(value));
                });
                assertTrue(attemptingClose.await(5, TimeUnit.SECONDS),
                        "the closer must signal before calling close()");
                closeDone.get(5, TimeUnit.SECONDS); // the terminate command is now queued
                fixture.scheduler.drain(); // terminal executes; active empties DURING the reservation
            } finally {
                releaseSupplier.countDown();
            }
            assertTrue(reservation.get(5, TimeUnit.SECONDS),
                    "the reserved frame must be delivered even though the terminal removed the"
                            + " consumer before the enqueue");
            assertEquals(1, supplierCalls.get());
            assertEquals(ScenarioFailure.CANCELLED, terminalResult.get(5, TimeUnit.SECONDS)
                    .failure().orElseThrow(), "the lease must reach the terminal CANCELLED state");

            // Post-terminal gating: subsequent frames invoke no supplier.
            assertFalse(fixture.scenarios.completedFrame(() -> {
                supplierCalls.incrementAndGet();
                return snapshot;
            }, 2, 2));
            assertEquals(1, supplierCalls.get(),
                    "a frame decided after the last run's terminal state must not build");
        }
    }

    @Test void postTerminalFramesInvokeNoSupplier() {
        try (GatedFixture fixture = new GatedFixture()) {
            CompletionStage<Scene2dScenarioRunner.Lease> acquired =
                    fixture.acquire(Duration.ofSeconds(2));
            fixture.scheduler.drain();
            fixture.completedFrame();
            Scene2dScenarioRunner.Lease lease = acquired.toCompletableFuture().join();

            // Sequential post-terminal gating (not a race): release, drain, then decide.
            CompletionStage<ScenarioResult> released = lease.release();
            fixture.scheduler.drain();
            released.toCompletableFuture().join();

            AtomicInteger supplierCalls = new AtomicInteger();
            long before = fixture.rootReads();
            boolean consumed = fixture.scenarios.completedFrame(() -> {
                supplierCalls.incrementAndGet();
                return fixture.session.snapshot(1, 1);
            }, 1, 1);
            assertFalse(consumed);
            assertEquals(0, supplierCalls.get(),
                    "a post-terminal frame decision must never invoke the snapshot supplier");
            assertEquals(before, fixture.rootReads());
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

`firstStartRaceNeverLosesTheRunFirstObservation` coordinates launcher and owner with a `launched` latch and a `startedHolder` future, and the owner NEVER calls `Future.get` before draining: the owner awaits the launch barrier, decides the frame while the run is active-but-not-begun (begin is still queued), then drains — the queue order guarantees the run's first observation is exactly the decided frame. `lastTerminalReservationWinsDespiteConcurrentTerminal` is the actual concurrency test for the reservation token: the supplier signals entered and blocks WITHOUT holding the lifecycle lock, the closer signals `attemptingClose` before `close()`, the owner waits for `closeDone` (the terminate command is queued), drains so the terminal completes and the last run is removed from `active` WHILE the reservation is still in flight, and only then releases the supplier — the reserved delivery must still complete, which a non-token design (re-reading `active` after the supplier) would violate. `postTerminalFramesInvokeNoSupplier` is the sequential post-terminal gating check (not a race): after the terminal state, a frame decision returns false without ever invoking the supplier. The tests use latches, `closeDone`, and observable drains — no sleeps and no deadlock: the reserver blocks only on the owner's release latch, the owner waits on the closer's `attemptingClose`/`closeDone` signals which are independent of the blocked supplier, and the closer waits on nothing. The `try`/`finally` begins immediately after the reserver is submitted and wraps EVERY wait and assertion (including `supplierEntered`), with `releaseSupplier.countDown()` in the `finally` and bounded joins afterward, so no failure path can strand the blocked supplier or hang the worker executor. The `CountingStage.getRoot()` override counts exactly the `stage.getRoot()` calls made by `Scene2dSnapshotter.snapshot(Stage, …)` (one per built snapshot); `Scene2dSession`, `ControlledStageClock`, `Scene2dContractSnapshotter`, and `Scene2dTypographyExtractor` constructors only store the Stage, so the count is 0 at fixture construction. `GdxNativesLoader`/`NoopBatch`/`WidgetStyles` are package-visible test utilities already used by `Scene2dSnapshotterTest` and `Scene2dNavigationRunnerTest`.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :harness-scene2d:test --tests 'dev.gdx.uiharness.scene2d.Scene2dSnapshotGatingTest' --no-daemon --console=plain --warning-mode=fail`
Expected: FAIL — `idleFramesBuildNoRunnerSnapshots` reports 3 root reads instead of 0 (the current code snapshots every frame) and the runner overloads with a `Supplier` do not exist yet.

- [ ] **Step 3: Implement the atomic reservation gate**

In `Scene2dScenarioRunner.java`, add beside the existing `completedFrame(SemanticSnapshot)`:

```java
    /**
     * Atomically reserves this completed frame for every run active at the call: the recipient
     * snapshot and a reservation counter are taken under the lifecycle lock, the snapshot supplier
     * runs OUTSIDE the lock (at most once per call), and the delivery consumes the reservation even
     * if a terminal transition occurs meanwhile — a terminal cannot invalidate an already-reserved
     * frame, and a run starting after the reservation observes the next frame.
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
            pendingFrameReservations++;
        }
        try {
            SemanticSnapshot snapshot = snapshots.get();
            for (Run run : runs) {
                observeSubmission(run, scheduler.submit(() -> {
                    run.observe(snapshot);
                    return null;
                }, dispatchDeadline()));
            }
            return true;
        } finally {
            synchronized (lifecycle) {
                pendingFrameReservations--;
            }
        }
    }
```

Add the field `private int pendingFrameReservations;` (guarded by `lifecycle`) and `import java.util.function.Supplier;` to the file. The reservation counter documents the in-flight delivery and is released on every terminal path via the `finally` block; the supplier and the submission enqueue run without holding the lifecycle lock, so no lock is held during the snapshot build and no deadlock is possible. The existing `completedFrame(SemanticSnapshot)` method is unchanged (direct callers such as `Scene2dNavigationRunnerTest` and `Lwjgl3MatrixRunnerTest` keep using it).

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

Add `import java.util.function.Supplier;` to `Scene2dSession.java`. The memoized supplier guarantees both runners observe the same snapshot instance (frame correlation) while the snapshot is built only when at least one runner's atomic reservation consumed the frame.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :harness-scene2d:test --tests 'dev.gdx.uiharness.scene2d.Scene2dSnapshotGatingTest' --no-daemon --console=plain --warning-mode=fail`
Expected: PASS — idle frames build 0 snapshots; active run, cancellation, navigation, first-start race, last-terminal race, and return-to-idle behave as asserted; on-demand snapshots and frame numbers keep advancing.

Run: `./gradlew :harness-scene2d:test --no-daemon --console=plain --warning-mode=fail`
Expected: PASS — `Scene2dScenarioRunnerTest` and `Scene2dNavigationRunnerTest` still observe every frame because their runs are active when `completedFrame` fires; `FixtureControl.afterDraw()` and `ReplacementScenarioHost` continue through the session's gated overloads.

- [ ] **Step 5: Commit**

```bash
git add harness-scene2d/src/main/java/dev/gdx/uiharness/scene2d/Scene2dScenarioRunner.java harness-scene2d/src/main/java/dev/gdx/uiharness/scene2d/Scene2dNavigationRunner.java harness-scene2d/src/main/java/dev/gdx/uiharness/scene2d/Scene2dSession.java harness-scene2d/src/test/java/dev/gdx/uiharness/scene2d/Scene2dSnapshotGatingTest.java
git commit -m "perf(scene2d): atomically reserve completed-frame snapshots under the runner lifecycle lock"
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
xvfb-run -a ./gradlew :harness-protocol:test --tests 'dev.gdx.uiharness.protocol.ProtocolJsonContractTest' --tests 'dev.gdx.uiharness.protocol.HarnessProtocolServiceTest' --no-daemon --console=plain --warning-mode=fail
xvfb-run -a ./gradlew :harness-mcp:test --tests 'dev.gdx.uiharness.mcp.HarnessMcpServerContractTest' --no-daemon --console=plain --warning-mode=fail
xvfb-run -a ./gradlew :harness-lwjgl3:test --tests 'dev.gdx.uiharness.lwjgl3.Lwjgl3ScreenCaptureTest' --no-daemon --console=plain --warning-mode=fail
```
Expected: all PASS.

- [ ] **Step 3: Open the pull request**

Base `origin/main`, head `fix/issues-21-23-scene-capture`. Body:

```markdown
## Scene2D ownership and capture efficiency (#21, #22, #23)

- **#21 — session-bound render-thread enforcement.** `Scene2dSession` now rejects off-thread Stage/semantic/adapter access with `HarnessException(ErrorCode.RENDER_THREAD_VIOLATION)` carrying operation and thread names; caller-thread waits keep routing through `RenderThreadScheduler`. New core/protocol code `render-thread-violation`, golden `errors.json` entry, ADR NNNN (computed per the Global Constraints rule).
- **#22 — snapshots only for active consumers.** Each runner atomically reserves a completed frame under its lifecycle lock (`completedFrame(Supplier<SemanticSnapshot>, long, long)` captures the recipients and a reservation counter, runs the supplier outside the lock, and delivers to the reserved recipients — a terminal cannot invalidate an already-reserved frame); the session builds the shared snapshot at most once per frame and only when a runner consumed it. Idle sessions skip per-frame work while fences, captures, and on-demand snapshots advance. First-start barrier and the reservation-wins concurrency test (terminal removes the consumer while the supplier is blocked; the reserved delivery still completes) plus sequential post-terminal gating pin the contract; real LWJGL3 smoke covers idle rendering/fences.
- **#23 — no internal base64 round trips; public records untouched.** The public protocol records (`Screenshot`, `InspectCompare`, `TypographyDiagnostic`, `LayoutDiagnostic`) keep their exact String components, accessors, equality/hashCode, and binary descriptors. Raw captured bytes travel in an internal `HarnessProtocolService.Execution` envelope as bounded immutable `BinaryAttachment` values (no byte[] accessor; read-only `ByteBuffer` and stream bridge) from the service to the MCP handler, which streams them to the publisher — zero base64 decode, exactly two documented boundary snapshots per payload. Observable proof: a deliberately invalid public base64 string still publishes the attachment bytes; max-size receipts match; wire JSON goldens unchanged; immutability, mutation, and oversize tests cover `BinaryAttachment` and `Execution` bounds.

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
- #22: idle sessions do not build runner snapshots → Task 6 counting test (the atomic gate returns false; the supplier never runs); starting a runner enables the stream → Task 6 test 2 + first-start barrier; stopping the last runner removes per-frame work → Task 6 tests 3-4 (the reservation-wins concurrency test: the supplier blocks without holding the lock, the terminal completes and empties `active` mid-reservation, and the reserved delivery still completes; plus sequential post-terminal gating with the supplier never invoked); frame correlation/fences remain correct → Task 6 test 5 + Task 7 real-LWJGL3 smoke; the gate is a reservation token — the recipient snapshot and a reservation counter are taken under the lifecycle lock, the supplier runs outside it, and delivery consumes the reservation even if a terminal occurs → `completedFrame(Supplier<SemanticSnapshot>, long, long)` on both runners (the barrier test releases the blocked supplier in a `finally`, so a failed assertion or timed-out wait can never hang the worker executor); proof counts snapshot construction across idle/active/navigation/cancellation/return-to-idle → `CountingStage.getRoot()` counts.
- #23: artifact publication performs no intermediate base64 round trip → Tasks 1-3 (the MCP handler streams the internal `Execution` capture attachment via a read-only `ByteBuffer`; no `Base64` decode remains in `HarnessToolHandler`); the observable proof is the invalid-public-String test — a public `pngBase64` that cannot be decoded still publishes the attachment bytes (Task 2); digest/length receipts remain identical → Task 2 max-size receipt assertions against the exact captured bytes; unchanged wire JSON and public records → golden `results.json` round trip plus `executeKeepsItsExactPublicContractWithEmptyCaptures` (Task 1); public records keep their String components, accessors, generated equality/hashCode, and binary descriptors (no record changes anywhere in the cluster); bounded immutability → `BinaryAttachment` (no byte[] accessor, read-only view, stream bridge, content equality) with mutation/oversize tests plus `Execution` attachment count/size bounds (Task 1); exactly two documented boundary snapshots per payload (CapturedImage construction + the publish-boundary default `Publisher.publish(String, ByteBuffer)` copy, overridable for zero-copy) → Global Constraints ownership contract; "MCP artifact paths do not serialize an unused full inline result before offloading" → Task 2 lazy `encodeResult` (the screenshot branch never serializes).
- Error/compatibility policy: typed `render-thread-violation` code with operation identity and bounded details; existing valid requests, JSON shapes, artifact references, String-typed Java API, and non-scenario `Scene2dSession` use remain supported; the design's "internal byte ownership may change while serialized screenshot JSON stays compatible" clause is satisfied by the internal envelope, with no public API change.

**2. Placeholder scan:** no TBD/TODO/“appropriate error handling”/“similar to Task N” patterns; every code step contains verbatim code; every acceptance criterion names an exact command and expected result. The ADR number is a computed value (`N` = next free number after the rebase) with an exact rule and consistent use, not a hardcoded stale constant.

**3. Type consistency:** the public protocol records keep their exact String components and accessors (no byte accessors exist anywhere); `BinaryAttachment` is the single byte-carrying value across Tasks 1-3 (public factory `of`, package-private trusted factories `takeCaptured(CapturedImage)`/`takeCaptured(VisualHeatmap)`, read APIs `length`/`sha256`/`asByteBuffer`/`writeTo`, content equality); the internal keys `SCREENSHOT_CAPTURE`/`COMPARE_CURRENT_CAPTURE`/`COMPARE_HEATMAP_CAPTURE`/`TYPOGRAPHY_CURRENT_CAPTURE`/`LAYOUT_CURRENT_CAPTURE` are identical across Tasks 1-3; `Execution.MAX_ATTACHMENTS` bounds the attachment count, the factories bound per-attachment size, and `MAX_PNG_BYTES` remains the single wire bound; `RENDER_THREAD_VIOLATION` is spelled identically in `ErrorCode`, `ProtocolError.Code`, the golden wire name `render-thread-violation`, and the tests; `completedFrame(Supplier<SemanticSnapshot>, long, long)` has the same signature on both runners and is consumed only by `Scene2dSession.completedFrame`; the ADR number rule in Global Constraints, Task 4, Task 9, and this review use the same computed `N`; the handler's `ExecutionSource` is the one Execution-typed protocol seam (distinct functional interface, no erasure clash); the new `Publisher.publish(String, ByteBuffer)` default keeps the functional-interface contract.
