package dev.gdx.uiharness.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gdx.uiharness.core.action.Action;
import dev.gdx.uiharness.core.action.ActionResult;
import dev.gdx.uiharness.core.action.Harness;
import dev.gdx.uiharness.core.capture.CaptureRequest;
import dev.gdx.uiharness.core.capture.CapturedImage;
import dev.gdx.uiharness.core.capture.ScreenCapture;
import dev.gdx.uiharness.core.layout.LayoutControlReference;
import dev.gdx.uiharness.core.layout.LayoutEvidence;
import dev.gdx.uiharness.core.layout.LayoutPadding;
import dev.gdx.uiharness.core.layout.LayoutQuiescenceResult;
import dev.gdx.uiharness.core.layout.LayoutReference;
import dev.gdx.uiharness.core.locator.Locator;
import dev.gdx.uiharness.core.locator.LocatorEngine;
import dev.gdx.uiharness.core.locator.StrictResolution;
import dev.gdx.uiharness.core.model.Bounds;
import dev.gdx.uiharness.core.model.Role;
import dev.gdx.uiharness.core.model.SemanticNode;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import dev.gdx.uiharness.core.model.SemanticState;
import dev.gdx.uiharness.core.time.Deadline;
import dev.gdx.uiharness.core.time.MonotonicClock;
import dev.gdx.uiharness.core.typography.CoordinateBounds;
import dev.gdx.uiharness.core.typography.CoordinateSpace;
import dev.gdx.uiharness.core.typography.EvidenceValue;
import dev.gdx.uiharness.core.typography.TypographyControlReference;
import dev.gdx.uiharness.core.typography.TypographyReference;
import dev.gdx.uiharness.core.typography.UnavailableReason;
import dev.gdx.uiharness.core.visual.VisualComparator;
import dev.gdx.uiharness.core.visual.VisualHeatmap;
import dev.gdx.uiharness.core.visual.VisualMetrics;
import dev.gdx.uiharness.core.visual.VisualPolicy;
import dev.gdx.uiharness.core.visual.VisualReference;
import dev.gdx.uiharness.core.wait.FrameSignal;
import dev.gdx.uiharness.core.wait.WaitEngine;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

/**
 * Task 3 (#23): proves the internal execution envelope attaches the exact compare/typography/
 * layout capture bytes while the public records, string components, and wire JSON stay unchanged.
 */
final class ExecutionAttachmentTest {
    private static final MonotonicClock CLOCK = () -> 10L;

    /**
     * RED: drives every capture-backed diagnostic command through the real
     * {@link HarnessProtocolService} routing and requires the internal attachments.
     */
    @Test
    void compareTypographyAndLayoutAttachmentsMatchTheirPublicStrings() {
        byte[] current = {1, 2, 3, 4};
        byte[] heatmap = {5, 6, 7};
        byte[] typographyCurrent = {8, 9, 10, 11};
        byte[] layoutCurrent = {12, 13};
        HarnessProtocolService service = serviceWithDiagnostics(
                current, heatmap, typographyCurrent, layoutCurrent);

        HarnessProtocolService.Execution compare = execution(service,
                new Command.InspectCompare("reference", "pixel-exact", 1, "main", 1,
                        1_000, 8, 8, 64, 1_024));
        HarnessResponse.Result.InspectCompare compareResult = assertInstanceOf(
                HarnessResponse.Result.InspectCompare.class,
                assertInstanceOf(HarnessResponse.Success.class, compare.response()).result());
        assertEquals(2, compare.captures().size(),
                "inspect-compare must attach both current and heatmap evidence");
        assertEquals(Set.of(HarnessProtocolService.COMPARE_CURRENT_CAPTURE,
                        HarnessProtocolService.COMPARE_HEATMAP_CAPTURE),
                compare.captures().keySet());
        assertAttachment(compare, HarnessProtocolService.COMPARE_CURRENT_CAPTURE,
                current, compareResult.currentPngBase64());
        assertAttachment(compare, HarnessProtocolService.COMPARE_HEATMAP_CAPTURE,
                heatmap, compareResult.heatmapPngBase64());

        HarnessProtocolService.Execution typography = execution(service,
                new Command.TypographyDiagnose("reference-title", "main", 1_000, 8,
                        8, 8, 64, 1_024));
        HarnessResponse.Result.TypographyDiagnostic typographyResult = assertInstanceOf(
                HarnessResponse.Result.TypographyDiagnostic.class,
                assertInstanceOf(HarnessResponse.Success.class, typography.response()).result());
        assertEquals(Set.of(HarnessProtocolService.TYPOGRAPHY_CURRENT_CAPTURE),
                typography.captures().keySet());
        assertAttachment(typography, HarnessProtocolService.TYPOGRAPHY_CURRENT_CAPTURE,
                typographyCurrent, typographyResult.currentPngBase64());

        HarnessProtocolService.Execution layout = execution(service,
                new Command.LayoutDiagnose("reference-layout", "main", 1_000, 8,
                        8, 8, 64, 1_024));
        HarnessResponse.Result.LayoutDiagnostic layoutResult = assertInstanceOf(
                HarnessResponse.Result.LayoutDiagnostic.class,
                assertInstanceOf(HarnessResponse.Success.class, layout.response()).result());
        assertEquals(Set.of(HarnessProtocolService.LAYOUT_CURRENT_CAPTURE),
                layout.captures().keySet());
        assertAttachment(layout, HarnessProtocolService.LAYOUT_CURRENT_CAPTURE,
                layoutCurrent, layoutResult.currentPngBase64());

        assertEquals(4, HarnessProtocolService.Execution.MAX_ATTACHMENTS,
                "the internal envelope must bound at most the four documented capture keys");
    }

    /** Invariant: the public execute path keeps records and exact string components unchanged. */
    @Test
    void publicExecuteKeepsDiagnosticRecordsAndStringComponentsUnchanged() {
        byte[] current = {1, 2, 3, 4};
        byte[] heatmap = {5, 6, 7};
        byte[] typographyCurrent = {8, 9, 10, 11};
        byte[] layoutCurrent = {12, 13};
        HarnessProtocolService service = serviceWithDiagnostics(
                current, heatmap, typographyCurrent, layoutCurrent);

        HarnessResponse.Result.InspectCompare compare = assertInstanceOf(
                HarnessResponse.Result.InspectCompare.class,
                assertInstanceOf(HarnessResponse.Success.class, await(service,
                        new Command.InspectCompare("reference", "pixel-exact", 1, "main", 1,
                                1_000, 8, 8, 64, 1_024))).result());
        assertArrayEquals(current, Base64.getDecoder().decode(compare.currentPngBase64()));
        assertArrayEquals(heatmap, Base64.getDecoder().decode(compare.heatmapPngBase64()));
        assertEquals(sha256Hex(current), compare.current().sha256());
        assertEquals(sha256Hex(heatmap), compare.heatmap().sha256());
        assertEquals("converged", compare.status());

        HarnessResponse.Result.TypographyDiagnostic typography = assertInstanceOf(
                HarnessResponse.Result.TypographyDiagnostic.class,
                assertInstanceOf(HarnessResponse.Success.class, await(service,
                        new Command.TypographyDiagnose("reference-title", "main", 1_000, 8,
                                8, 8, 64, 1_024))).result());
        assertArrayEquals(typographyCurrent,
                Base64.getDecoder().decode(typography.currentPngBase64()));
        assertEquals(sha256Hex(typographyCurrent), typography.current().sha256());

        HarnessResponse.Result.LayoutDiagnostic layout = assertInstanceOf(
                HarnessResponse.Result.LayoutDiagnostic.class,
                assertInstanceOf(HarnessResponse.Success.class, await(service,
                        new Command.LayoutDiagnose("reference-layout", "main", 1_000, 8,
                                8, 8, 64, 1_024))).result());
        assertArrayEquals(layoutCurrent, Base64.getDecoder().decode(layout.currentPngBase64()));
        assertEquals(sha256Hex(layoutCurrent), layout.current().sha256());

        HarnessResponse.Success success = assertInstanceOf(HarnessResponse.Success.class,
                await(service, new Command.InspectCompare(
                        "reference", "pixel-exact", 1, "main", 1, 1_000, 8, 8, 64, 1_024)));
        String json = new String(ProtocolJson.encode(success), StandardCharsets.UTF_8);
        assertTrue(json.contains(Base64.getEncoder().encodeToString(current)),
                "the wire JSON must carry the exact current PNG string component");
        assertTrue(json.contains(Base64.getEncoder().encodeToString(heatmap)),
                "the wire JSON must carry the exact heatmap PNG string component");
    }

    /** Invariant: the ten-argument InspectCompare compatibility constructor still works. */
    @Test
    void tenArgumentInspectCompareConstructorRemainsACompatiblePublicPath() {
        HarnessResponse.ComparisonDiagnosticData diagnostic =
                new HarnessResponse.ComparisonDiagnosticData(
                        "REFERENCE_NOT_FOUND", "$.referenceId",
                        "registered immutable reference", "missing");
        HarnessResponse.Result.InspectCompare compatibility = new HarnessResponse.Result
                .InspectCompare("incomplete", "pixel-exact", null, null, null, List.of(),
                List.of(diagnostic), 0, 0, null);
        HarnessResponse.Result.InspectCompare canonical = new HarnessResponse.Result
                .InspectCompare("incomplete", "pixel-exact", null, null, null, List.of(),
                List.of(), null, List.of(diagnostic), 0, 0, null, null);

        assertEquals("incomplete", compatibility.status());
        assertEquals("pixel-exact", compatibility.policy());
        assertEquals(List.of(), compatibility.regions());
        assertNull(compatibility.current());
        assertNull(compatibility.heatmap());
        assertNull(compatibility.currentPngBase64());
        assertNull(compatibility.heatmapPngBase64());
        assertEquals(canonical, compatibility,
                "the ten-argument compatibility constructor must equal the canonical record");
        String json = write(compatibility);
        assertTrue(json.contains("\"status\":\"incomplete\""));
        assertTrue(json.contains("\"policy\":\"pixel-exact\""));
        assertTrue(json.contains("\"code\":\"REFERENCE_NOT_FOUND\""));
    }

    private static HarnessProtocolService serviceWithDiagnostics(
            byte[] current, byte[] heatmap, byte[] typographyCurrent, byte[] layoutCurrent) {
        InspectCaptureCompareService comparison = compareService(
                image(current), new VisualHeatmap(heatmap, sha256Hex(heatmap), 2, 2));
        TypographyDiagnosticService typography = typographyService(image(typographyCurrent));
        LayoutDiagnosticService layout = layoutService(image(layoutCurrent));
        return new HarnessProtocolService(
                Map.of("game", session()),
                Map.of(),
                Map.of("game", comparison),
                Map.of("game", typography),
                Map.of("game", layout),
                CLOCK,
                Runnable::run);
    }

    private static InspectCaptureCompareService compareService(
            CapturedImage current, VisualHeatmap heatmap) {
        VisualReference reference = new VisualReference(
                "reference", "app", "golden", "main",
                new byte[] {1}, sha256Hex(new byte[] {1}), 2, 2,
                new CapturedImage.Scale(1, 1), Instant.EPOCH, snapshot(), null);
        VisualComparator comparator = (expected, currentEvidence, policy) ->
                new VisualComparator.Comparison(
                        new VisualMetrics(0, 0, 0), List.of(), List.of(), heatmap);
        return new InspectCaptureCompareService(
                "game", "app", "main", harness(), captureOf(current), null,
                id -> "reference".equals(id) ? Optional.of(reference) : Optional.empty(),
                List.of(VisualPolicy.pixelExactV1()), comparator, CLOCK,
                InstantSource.fixed(Instant.EPOCH));
    }

    private static TypographyDiagnosticService typographyService(CapturedImage current) {
        TypographyReference reference = new TypographyReference(
                "reference-title", "fixture-app", "main", "reference-artifact",
                new byte[] {1}, sha256Hex(new byte[] {1}), 2, 2,
                new CapturedImage.Scale(1, 1),
                List.of(new TypographyControlReference(
                        "title", "font.fnt", 15, 15, 1, 1, "Nearest", "Nearest", 1, 1,
                        EvidenceValue.unavailable(
                                UnavailableReason.UNSUPPORTED, "weight unavailable"),
                        EvidenceValue.unavailable(
                                UnavailableReason.UNSUPPORTED, "spacing unavailable"),
                        framebufferBounds(), 1, 1, 0.5, 1e-6, 0.5, "d".repeat(64))));
        return new TypographyDiagnosticService(
                "fixture-app", "main", captureOf(current),
                id -> Optional.of(reference),
                (registered, image, deadline) ->
                        CompletableFuture.completedFuture(List.of()),
                CLOCK);
    }

    private static LayoutDiagnosticService layoutService(CapturedImage current) {
        LayoutReference reference = new LayoutReference(
                "reference-layout", "fixture-app", "main", "reference-artifact",
                List.of(new LayoutControlReference(
                        "row", "parent", "form", "scroll", "scroll", "scrolling-row",
                        spaces(framebufferBounds()), framebufferBounds(),
                        new LayoutPadding(4, 4, 4, 4), 0, 0, null, "d".repeat(64))));
        LayoutQuiescenceResult unsettled = new LayoutQuiescenceResult(
                false, "not-stable", 1, Duration.ofSeconds(2), List.of());
        LayoutEvidence evidence = new LayoutEvidence(List.of(), unsettled, unsettled);
        return new LayoutDiagnosticService(
                "fixture-app", "main", captureOf(current),
                id -> Optional.of(reference),
                (registered, image, deadline) ->
                        CompletableFuture.completedFuture(evidence),
                CLOCK);
    }

    private static HarnessProtocolService.Session session() {
        LocatorEngine locators = new StrictResolution();
        FrameSignal frames = listener -> () -> {};
        WaitEngine waits = new WaitEngine(() -> snapshot(), locators, CLOCK, frames);
        CapabilitySet capabilities = new CapabilitySet(List.of(
                "compare", "layout", "screenshot", "snapshot", "typography"));
        return new HarnessProtocolService.Session(
                harness(), locators, waits, new NoOpCapture(), capabilities,
                HarnessProtocolService.TraceController.unsupported());
    }

    private static Harness harness() {
        return new Harness() {
            @Override public CompletionStage<ActionResult> perform(
                    Locator locator, Action action, Deadline deadline) {
                return CompletableFuture.failedFuture(new UnsupportedOperationException());
            }

            @Override public CompletionStage<SemanticSnapshot> snapshot(Deadline deadline) {
                return CompletableFuture.completedFuture(ExecutionAttachmentTest.snapshot());
            }
        };
    }

    private static ScreenCapture captureOf(CapturedImage image) {
        return new ScreenCapture() {
            @Override public CompletionStage<CapturedImage> capture(
                    CaptureRequest request, Deadline deadline) {
                return CompletableFuture.completedFuture(image);
            }

            @Override public void close() {}
        };
    }

    private static CapturedImage image(byte[] payload) {
        return new CapturedImage(payload, sha256Hex(payload), 3, 2, 2, 2,
                new CapturedImage.Scale(1, 1));
    }

    private static SemanticSnapshot snapshot() {
        Bounds bounds = new Bounds(0, 0, 2, 2);
        SemanticState state = new SemanticState(
                true, true, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), false, false, 1.0, false, true, true);
        SemanticNode root = new SemanticNode(
                "root", null, List.of(), Role.GROUP, "Root", null, null,
                null, "root", "Group", state, bounds, bounds, bounds, 0, Map.of());
        return new SemanticSnapshot(2, 3, "root", Map.of("root", root));
    }

    private static CoordinateBounds framebufferBounds() {
        return new CoordinateBounds(CoordinateSpace.FRAMEBUFFER, 0, 0, 1, 1);
    }

    private static List<CoordinateBounds> spaces(CoordinateBounds value) {
        return List.of(
                new CoordinateBounds(CoordinateSpace.LOCAL,
                        value.x(), value.y(), value.width(), value.height()),
                new CoordinateBounds(CoordinateSpace.STAGE,
                        value.x(), value.y(), value.width(), value.height()),
                new CoordinateBounds(CoordinateSpace.SCREEN,
                        value.x(), value.y(), value.width(), value.height()),
                value);
    }

    private static void assertAttachment(
            HarnessProtocolService.Execution execution,
            String key,
            byte[] expected,
            String publicBase64) {
        BinaryAttachment attachment = execution.captures().get(key);
        assertNotNull(attachment, "missing internal capture attachment for " + key);
        assertEquals(sha256Hex(expected), attachment.sha256(),
                "digest receipt must match the captured bytes for " + key);
        assertArrayEquals(expected, readAll(attachment.asByteBuffer()),
                "attachment bytes must equal the captured payload exactly for " + key);
        assertArrayEquals(expected, Base64.getDecoder().decode(publicBase64),
                "the public String wire representation must decode to the captured bytes for "
                        + key);
    }

    private static HarnessProtocolService.Execution execution(
            HarnessProtocolService service, Command command) {
        return service.executeWithAttachments(request(command)).toCompletableFuture().join();
    }

    private static HarnessResponse await(HarnessProtocolService service, Command command) {
        return service.execute(request(command)).toCompletableFuture().join();
    }

    private static HarnessRequest request(Command command) {
        return new HarnessRequest(ProtocolVersion.V1, "game", "request-1", 500, command);
    }

    private static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 unavailable", impossible);
        }
    }

    private static byte[] readAll(ByteBuffer view) {
        ByteBuffer local = view.duplicate();
        byte[] bytes = new byte[local.remaining()];
        local.get(bytes);
        return bytes;
    }

    private static String write(Object value) {
        try {
            return ProtocolJson.mapper().writeValueAsString(value);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static final class NoOpCapture implements ScreenCapture {
        @Override public CompletionStage<CapturedImage> capture(
                CaptureRequest request, Deadline deadline) {
            return CompletableFuture.completedFuture(
                    new CapturedImage(new byte[] {1, 2, 3},
                            sha256Hex(new byte[] {1, 2, 3}), 3, 2, 1, 1,
                            new CapturedImage.Scale(1, 1)));
        }

        @Override public void close() {}
    }
}
