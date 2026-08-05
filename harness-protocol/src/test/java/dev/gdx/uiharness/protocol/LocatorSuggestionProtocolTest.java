package dev.gdx.uiharness.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import dev.gdx.uiharness.core.error.ErrorCode;
import dev.gdx.uiharness.core.error.HarnessException;
import dev.gdx.uiharness.core.locator.Locator;
import dev.gdx.uiharness.core.locator.LocatorFilter;
import dev.gdx.uiharness.core.locator.Stability;
import dev.gdx.uiharness.core.locator.StrictResolution;
import dev.gdx.uiharness.core.locator.TextMatch;
import dev.gdx.uiharness.core.model.Bounds;
import dev.gdx.uiharness.core.model.Role;
import dev.gdx.uiharness.core.model.SemanticNode;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import dev.gdx.uiharness.core.model.SemanticState;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

final class LocatorSuggestionProtocolTest {
    @Test void locatorSpecRoundTripsEveryCoreLocatorKind() throws Exception {
        List<Locator> locators = List.of(
                Locator.role(Role.BUTTON),
                Locator.text(TextMatch.exact("Save")),
                Locator.label(TextMatch.substring("close")),
                Locator.testId("pause-resume"),
                Locator.actorName(TextMatch.caseInsensitiveExact("resume")),
                Locator.actorType(TextMatch.regex("Text.*")),
                Locator.role(Role.BUTTON).withName(TextMatch.exact("Save")),
                Locator.role(Role.RADIO_BUTTON).child(Locator.testId("option-1")),
                Locator.role(Role.GROUP).descendant(Locator.label(TextMatch.exact("x"))),
                Locator.role(Role.BUTTON).parent(Locator.role(Role.DIALOG)),
                Locator.role(Role.BUTTON).sibling(Locator.role(Role.BUTTON)),
                Locator.role(Role.BUTTON).filter(LocatorFilter.has(Locator.testId("icon"))),
                Locator.role(Role.BUTTON).filter(LocatorFilter.hasText(TextMatch.exact("ok"))),
                Locator.role(Role.BUTTON)
                        .filter(LocatorFilter.state(LocatorFilter.State.ENABLED, true)),
                Locator.role(Role.BUTTON).atIndex(2));
        for (Locator locator : locators) {
            Command.LocatorSpec spec = Command.LocatorSpec.fromCore(locator);
            String json = ProtocolJson.mapper().writeValueAsString(spec);
            Command.LocatorSpec decoded =
                    ProtocolJson.mapper().readValue(json, Command.LocatorSpec.class);
            assertEquals(locator, decoded.toCore(), "round trip failed for " + locator);
        }
    }

    @Test void protocolErrorSerializesSuggestionsUnderTheClosedLocatorSchema() throws Exception {
        LocatorSuggestionSpec suggestion = new LocatorSuggestionSpec(
                Command.LocatorSpec.fromCore(
                        Locator.role(Role.BUTTON).withName(TextMatch.exact("Resume"))),
                Stability.STABLE,
                "role and accessible name",
                "pause-resume",
                List.of(new DistinguishingPropertySpec("testId", "pause-resume")));
        ProtocolError error = new ProtocolError(
                ProtocolError.Code.NOT_FOUND,
                "No semantic node matches the locator",
                "request-1",
                "game",
                null,
                0,
                null,
                null,
                List.of(),
                Map.of(),
                null,
                List.of(suggestion));

        String json = ProtocolJson.mapper().writeValueAsString(error);
        assertTrue(json.contains("\"kind\":\"filter\""));
        assertTrue(json.contains("\"kind\":\"role\""));
        assertTrue(json.contains("\"stability\":\"STABLE\""));
        assertTrue(json.contains("pause-resume"));

        ProtocolError decoded = ProtocolJson.mapper().readValue(json, ProtocolError.class);
        assertEquals(List.of(suggestion), decoded.suggestions());
    }

    @Test void protocolErrorRejectsUnknownSuggestionFields() throws Exception {
        ProtocolError error = new ProtocolError(
                ProtocolError.Code.NOT_FOUND,
                "No semantic node matches the locator",
                "request-1",
                "game",
                null,
                0,
                null,
                null,
                List.of(),
                Map.of(),
                null,
                List.of(new LocatorSuggestionSpec(
                        new Command.LocatorSpec.TestId("pause-resume"),
                        Stability.STABLE,
                        "unique test identifier",
                        "pause-resume",
                        List.of())));
        JsonNode tree = ProtocolJson.mapper().readTree(
                ProtocolJson.mapper().writeValueAsString(error));
        ((com.fasterxml.jackson.databind.node.ObjectNode) tree.get("suggestions").get(0))
                .put("bogusField", "value");

        assertThrows(JsonProcessingException.class,
                () -> ProtocolJson.mapper()
                        .readValue(ProtocolJson.mapper().writeValueAsString(tree),
                                ProtocolError.class));
    }

    @Test void protocolErrorRejectsOversizedSuggestionLists() {
        LocatorSuggestionSpec suggestion = new LocatorSuggestionSpec(
                new Command.LocatorSpec.TestId("pause-resume"),
                Stability.STABLE,
                "unique test identifier",
                "pause-resume",
                List.of());

        assertThrows(IllegalArgumentException.class,
                () -> new ProtocolError(
                        ProtocolError.Code.NOT_FOUND,
                        "No semantic node matches the locator",
                        "request-1",
                        "game",
                        null,
                        0,
                        null,
                        null,
                        List.of(),
                        Map.of(),
                        null,
                        Collections.nCopies(1_001, suggestion)));
    }

    @Test void translateCarriesCoreSuggestionsIntoTheProtocolError() {
        StrictResolution engine = new StrictResolution();
        HarnessException strictFailure = assertThrows(HarnessException.class,
                () -> engine.resolveStrict(twoButtons(), Locator.role(Role.BUTTON)));
        assertEquals(ErrorCode.STRICTNESS_VIOLATION, strictFailure.code());
        assertFalse(strictFailure.evidence().suggestions().isEmpty());

        RecordingHarness harness = new RecordingHarness();
        harness.snapshotFailure = strictFailure;
        HarnessProtocolService service = service(harness);
        HarnessResponse.Failure failure = assertInstanceOf(HarnessResponse.Failure.class,
                service.execute(request(new Command.Snapshot())).toCompletableFuture().join());

        ProtocolError error = failure.error();
        assertEquals(ProtocolError.Code.STRICTNESS_VIOLATION, error.code());
        assertEquals(2, error.suggestions().size());
        assertEquals("left-save", error.suggestions().getFirst().candidateIdentity());
        assertEquals("right-save", error.suggestions().getLast().candidateIdentity());
        assertEquals(Stability.STABLE, error.suggestions().getFirst().stability());
        assertEquals("unique test identifier",
                error.suggestions().getFirst().rationale());
        assertTrue(error.suggestions().getFirst().locator() instanceof Command.LocatorSpec.TestId);
    }

    private static HarnessProtocolService service(RecordingHarness harness) {
        HarnessProtocolService.TraceController traces =
                HarnessProtocolService.TraceController.unsupported();
        dev.gdx.uiharness.core.wait.FrameSignal frames = listener -> () -> {};
        var waits = new dev.gdx.uiharness.core.wait.WaitEngine(
                () -> twoButtons(), new StrictResolution(), System::nanoTime, frames);
        var capabilities = new CapabilitySet(List.of("snapshot"));
        HarnessProtocolService.Session session = new HarnessProtocolService.Session(
                harness, new StrictResolution(), waits, new RecordingCapture(),
                capabilities, traces);
        return new HarnessProtocolService(
                Map.of("game", session), Map.of(), System::nanoTime, Runnable::run);
    }

    private static HarnessRequest request(Command command) {
        return new HarnessRequest(ProtocolVersion.V1, "game", "request-1", 500, command);
    }

    private static SemanticSnapshot twoButtons() {
        Bounds bounds = new Bounds(0, 0, 10, 10);
        SemanticState state = new SemanticState(
                true, true, Optional.of(true), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), false, true, 1.0, false, true, true);
        SemanticNode root = new SemanticNode("root", null, List.of("left-save", "right-save"),
                Role.GROUP, "root", "", null, null, null, null, state, bounds, bounds,
                bounds, 0, Map.of());
        SemanticNode leftSave = new SemanticNode("left-save", "root", List.of(), Role.BUTTON,
                "Save", "Save", null, "left", null, "TextButton", state, bounds, bounds,
                bounds, 0, Map.of());
        SemanticNode rightSave = new SemanticNode("right-save", "root", List.of(), Role.BUTTON,
                "Save", "Save", null, "right", null, "TextButton", state, bounds, bounds,
                bounds, 0, Map.of());
        var byId = new LinkedHashMap<String, SemanticNode>();
        byId.put("right-save", rightSave);
        byId.put("left-save", leftSave);
        byId.put("root", root);
        return new SemanticSnapshot(31, 1, "root", byId);
    }

    private static final class RecordingHarness implements dev.gdx.uiharness.core.action.Harness {
        private RuntimeException snapshotFailure;

        @Override public CompletionStage<dev.gdx.uiharness.core.action.ActionResult> perform(
                Locator locator,
                dev.gdx.uiharness.core.action.Action action,
                dev.gdx.uiharness.core.time.Deadline deadline) {
            return CompletableFuture.completedFuture(
                    new dev.gdx.uiharness.core.action.ActionResult(
                            1, 2, "clicked", Map.of()));
        }

        @Override public CompletionStage<SemanticSnapshot> snapshot(
                dev.gdx.uiharness.core.time.Deadline deadline) {
            if (snapshotFailure != null) {
                return CompletableFuture.failedFuture(snapshotFailure);
            }
            return CompletableFuture.completedFuture(twoButtons());
        }
    }

    private static final class RecordingCapture implements dev.gdx.uiharness.core.capture.ScreenCapture {
        @Override public CompletionStage<dev.gdx.uiharness.core.capture.CapturedImage> capture(
                dev.gdx.uiharness.core.capture.CaptureRequest request,
                dev.gdx.uiharness.core.time.Deadline deadline) {
            throw new AssertionError("capture was not expected");
        }

        @Override public void close() {}
    }
}
