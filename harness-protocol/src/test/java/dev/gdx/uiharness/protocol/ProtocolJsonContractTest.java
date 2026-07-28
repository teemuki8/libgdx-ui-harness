package dev.gdx.uiharness.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class ProtocolJsonContractTest {
    @Test void everyV1CommandGoldenRoundTripsCanonically() throws Exception {
        JsonNode contracts = resource("contracts/v1/requests.json");
        Set<Class<?>> variants = new HashSet<>();

        for (JsonNode contract : contracts) {
            JsonNode golden = contract.get("value");
            HarnessRequest request = ProtocolJson.mapper().treeToValue(golden, HarnessRequest.class);
            variants.add(request.command().getClass());
            assertEquals(ProtocolVersion.V1, request.version(), contract.get("name").asText());
            assertEquals(golden.toString(), ProtocolJson.mapper().writeValueAsString(request),
                    contract.get("name").asText());
        }

        assertEquals(Set.of(Command.Sessions.class, Command.Capabilities.class,
                Command.Snapshot.class, Command.Query.class, Command.Action.class,
                Command.Wait.class, Command.Screenshot.class, Command.TraceStart.class,
                Command.TraceStop.class), variants);
        String encoded = ProtocolJson.mapper().writeValueAsString(
                ProtocolJson.mapper().treeToValue(contracts.get(4).get("value"),
                        HarnessRequest.class));
        assertFalse(encoded.contains("@class"));
        assertFalse(encoded.contains("dev.gdx"));
    }

    @Test void everyV1ResultGoldenRoundTripsCanonically() throws Exception {
        JsonNode contracts = resource("contracts/v1/results.json");
        Set<Class<?>> variants = new HashSet<>();

        for (JsonNode contract : contracts) {
            JsonNode golden = contract.get("value");
            HarnessResponse response = ProtocolJson.mapper().treeToValue(golden,
                    HarnessResponse.class);
            HarnessResponse.Success success = assertInstanceOf(
                    HarnessResponse.Success.class, response);
            variants.add(success.result().getClass());
            assertEquals(golden.toString(), ProtocolJson.mapper().writeValueAsString(response),
                    contract.get("name").asText());
        }

        assertEquals(Set.of(HarnessResponse.Result.Sessions.class,
                HarnessResponse.Result.Capabilities.class,
                HarnessResponse.Result.Snapshot.class,
                HarnessResponse.Result.Query.class,
                HarnessResponse.Result.Action.class,
                HarnessResponse.Result.Wait.class,
                HarnessResponse.Result.Screenshot.class,
                HarnessResponse.Result.TraceStarted.class,
                HarnessResponse.Result.TraceStopped.class), variants);
    }

    @Test void everyV1ErrorGoldenRoundTripsCanonically() throws Exception {
        JsonNode contracts = resource("contracts/v1/errors.json");
        Set<ProtocolError.Code> codes = new HashSet<>();

        for (JsonNode contract : contracts) {
            JsonNode golden = contract.get("value");
            ProtocolError error = ProtocolJson.mapper().treeToValue(golden, ProtocolError.class);
            codes.add(error.code());
            assertEquals(golden.toString(), ProtocolJson.mapper().writeValueAsString(error),
                    contract.get("name").asText());
        }

        assertEquals(Set.of(ProtocolError.Code.values()), codes);
    }

    @Test void everyNestedLocatorFilterAndActionUnionUsesStableNames() throws Exception {
        Command.TextMatchSpec exact = new Command.TextMatchSpec("exact", "Save");
        List<Command.LocatorSpec> locators = List.of(
                new Command.LocatorSpec.Role("button"),
                new Command.LocatorSpec.Text("text", exact),
                new Command.LocatorSpec.TestId("save"),
                new Command.LocatorSpec.Actor("type", exact),
                new Command.LocatorSpec.Relation(
                        new Command.LocatorSpec.Role("group"),
                        new Command.LocatorSpec.Role("button"), "descendant"),
                new Command.LocatorSpec.Filter(new Command.LocatorSpec.Role("button"),
                        new Command.FilterSpec.Name(exact)),
                new Command.LocatorSpec.Filter(new Command.LocatorSpec.Role("group"),
                        new Command.FilterSpec.Has(new Command.LocatorSpec.Role("button"))),
                new Command.LocatorSpec.Filter(new Command.LocatorSpec.Role("group"),
                        new Command.FilterSpec.HasText(exact)),
                new Command.LocatorSpec.Filter(new Command.LocatorSpec.Role("button"),
                        new Command.FilterSpec.State("visible", true)),
                new Command.LocatorSpec.Index(new Command.LocatorSpec.Role("button"), 0));
        List<Command.ActionSpec> actions = List.of(
                new Command.ActionSpec.Click(0, 0, false),
                new Command.ActionSpec.Hover(false),
                new Command.ActionSpec.Focus(false),
                new Command.ActionSpec.Fill("value", false),
                new Command.ActionSpec.Press(13, false),
                new Command.ActionSpec.Scroll(1, -1, false),
                new Command.ActionSpec.Drag(2, 3, 0, 0, false),
                new Command.ActionSpec.Pointer("down", 1, 1, 0, 0, false));

        for (Command.LocatorSpec locator : locators) {
            HarnessRequest source = new HarnessRequest(ProtocolVersion.V1, "s", "locator", 10,
                    new Command.Query(locator));
            HarnessRequest decoded = ProtocolJson.mapper().readValue(
                    ProtocolJson.mapper().writeValueAsBytes(source), HarnessRequest.class);
            Command.Query query = assertInstanceOf(Command.Query.class, decoded.command());
            assertEquals(locator.getClass(), query.locator().getClass());
            assertNotTypeMetadata(ProtocolJson.mapper().writeValueAsString(source));
            query.locator().toCore();
        }
        for (Command.ActionSpec action : actions) {
            HarnessRequest source = new HarnessRequest(ProtocolVersion.V1, "s", "action", 10,
                    new Command.Action(new Command.LocatorSpec.Role("button"), action));
            HarnessRequest decoded = ProtocolJson.mapper().readValue(
                    ProtocolJson.mapper().writeValueAsBytes(source), HarnessRequest.class);
            Command.Action command = assertInstanceOf(Command.Action.class, decoded.command());
            assertEquals(action.getClass(), command.action().getClass());
            assertNotTypeMetadata(ProtocolJson.mapper().writeValueAsString(source));
            command.action().toCore();
        }
    }

    @Test void rejectsUnknownNestedResponseAndErrorUnionNames() {
        String unknownLocator = requestWithCommand(
                "{\"type\":\"query\",\"locator\":{\"kind\":\"class-name\",\"name\":\"x\"}}");
        String unknownAction = requestWithCommand(
                "{\"type\":\"action\",\"locator\":{\"kind\":\"role\",\"role\":\"button\"},"
                        + "\"action\":{\"kind\":\"java-class\"}}");
        String unknownResponse = "{\"status\":\"subclass\",\"version\":{\"major\":1,\"minor\":0},"
                + "\"requestId\":\"r\",\"sessionId\":\"s\"}";
        String unknownResult = "{\"status\":\"ok\",\"version\":{\"major\":1,\"minor\":0},"
                + "\"requestId\":\"r\",\"sessionId\":\"s\","
                + "\"result\":{\"type\":\"java-object\"}}";
        String unknownError = "{\"code\":\"throwable\",\"message\":\"bad\",\"requestId\":\"r\","
                + "\"sessionId\":\"s\",\"elapsedMillis\":0,\"candidates\":[],\"details\":{}}";

        assertThrows(JsonProcessingException.class,
                () -> ProtocolJson.mapper().readValue(unknownLocator, HarnessRequest.class));
        assertThrows(JsonProcessingException.class,
                () -> ProtocolJson.mapper().readValue(unknownAction, HarnessRequest.class));
        assertThrows(JsonProcessingException.class,
                () -> ProtocolJson.mapper().readValue(unknownResponse, HarnessResponse.class));
        assertThrows(JsonProcessingException.class,
                () -> ProtocolJson.mapper().readValue(unknownResult, HarnessResponse.class));
        assertThrows(JsonProcessingException.class,
                () -> ProtocolJson.mapper().readValue(unknownError, ProtocolError.class));
    }

    private static void assertNotTypeMetadata(String json) {
        assertFalse(json.contains("@class"));
        assertFalse(json.contains("dev.gdx"));
        assertFalse(json.contains("java.lang"));
    }

    @Test void rejectsUnknownFieldsAtEveryLevel() {
        String top = "{\"version\":{\"major\":1,\"minor\":0},\"sessionId\":\"s\","
                + "\"requestId\":\"r\",\"deadlineMillis\":1,"
                + "\"command\":{\"type\":\"snapshot\"},\"surprise\":true}";
        String nested = "{\"version\":{\"major\":1,\"minor\":0},\"sessionId\":\"s\","
                + "\"requestId\":\"r\",\"deadlineMillis\":1,"
                + "\"command\":{\"type\":\"snapshot\",\"surprise\":true}}";

        assertThrows(JsonProcessingException.class,
                () -> ProtocolJson.mapper().readValue(top, HarnessRequest.class));
        assertThrows(JsonProcessingException.class,
                () -> ProtocolJson.mapper().readValue(nested, HarnessRequest.class));
    }

    @Test void rejectsUnknownAndMalformedUnionMembers() {
        String unknown = requestWithCommand("{\"type\":\"java.lang.Runtime\"}");
        String malformed = requestWithCommand("{\"type\":\"query\"}");

        assertThrows(JsonProcessingException.class,
                () -> ProtocolJson.mapper().readValue(unknown, HarnessRequest.class));
        assertThrows(JsonProcessingException.class,
                () -> ProtocolJson.mapper().readValue(malformed, HarnessRequest.class));
    }

    @Test void rejectsInvalidDeadlineAndIdentifiers() {
        assertThrows(JsonProcessingException.class, () -> ProtocolJson.mapper().readValue(
                request(0, "s", "r"), HarnessRequest.class));
        assertThrows(JsonProcessingException.class, () -> ProtocolJson.mapper().readValue(
                request(HarnessRequest.MAX_DEADLINE_MILLIS + 1, "s", "r"),
                HarnessRequest.class));
        assertThrows(JsonProcessingException.class, () -> ProtocolJson.mapper().readValue(
                request(1, " ", "r"), HarnessRequest.class));
    }

    @Test void boundsRawBytesBeforeDeserialization() {
        byte[] oversized = new byte[ProtocolJson.MAX_REQUEST_BYTES + 1];

        ProtocolJson.ProtocolJsonException failure = assertThrows(
                ProtocolJson.ProtocolJsonException.class, () -> ProtocolJson.decode(oversized));

        assertEquals("limit-exceeded", failure.code());
    }

    @Test void boundsEncodedResponseBytes() {
        String maximum = "x".repeat(ProtocolJson.MAX_STRING_LENGTH);
        Map<String, String> details = new HashMap<>();
        for (int index = 0; index < 32; index++) {
            details.put("detail-" + index, maximum);
        }
        ProtocolError error = new ProtocolError(ProtocolError.Code.LIMIT_EXCEEDED,
                "too large", "r", "s", null, 0, null, null,
                Collections.nCopies(1_000, Map.of("candidate", maximum)), details, null);
        HarnessResponse response = new HarnessResponse.Failure(
                ProtocolVersion.V1, "r", "s", error);

        ProtocolJson.ProtocolJsonException failure = assertThrows(
                ProtocolJson.ProtocolJsonException.class, () -> ProtocolJson.encode(response));

        assertEquals("limit-exceeded", failure.code());
    }

    @Test void screenshotPayloadAboveGenericStringLimitRoundTrips() throws Exception {
        String pngBase64 = Base64.getEncoder().encodeToString(new byte[32 * 1_024]);
        HarnessResponse source = new HarnessResponse.Success(ProtocolVersion.V1, "r", "s",
                new HarnessResponse.Result.Screenshot(pngBase64, "0".repeat(64),
                        1, 1, 100, 100, 1, 1));

        byte[] encoded = ProtocolJson.encode(source);
        HarnessResponse decoded = ProtocolJson.mapper().readValue(
                encoded, HarnessResponse.class);
        HarnessResponse.Success success = assertInstanceOf(
                HarnessResponse.Success.class, decoded);
        HarnessResponse.Result.Screenshot screenshot = assertInstanceOf(
                HarnessResponse.Result.Screenshot.class, success.result());

        assertTrue(pngBase64.length() > ProtocolJson.MAX_STRING_LENGTH);
        assertEquals(pngBase64, screenshot.pngBase64());
    }

    @Test void enforcesNestingStringAndNumberConstraints() {
        String deeplyNested = requestWithCommand("{\"type\":\"query\",\"locator\":"
                + "{\"kind\":\"index\",\"index\":0,\"locator\":".repeat(70)
                + "{\"kind\":\"role\",\"role\":\"button\"}"
                + "}".repeat(70) + "}");
        String longString = request(1, "s", "x".repeat(ProtocolJson.MAX_STRING_LENGTH + 1));
        String longNumber = requestWithDeadline("9".repeat(ProtocolJson.MAX_NUMBER_LENGTH + 1));

        assertThrows(JsonProcessingException.class,
                () -> ProtocolJson.mapper().readValue(deeplyNested, HarnessRequest.class));
        assertThrows(JsonProcessingException.class,
                () -> ProtocolJson.mapper().readValue(longString, HarnessRequest.class));
        assertThrows(JsonProcessingException.class,
                () -> ProtocolJson.mapper().readValue(longNumber, HarnessRequest.class));
    }

    @Test void boundedCodecRoundTripsUtf8Request() throws Exception {
        byte[] json = ProtocolJson.encode(ProtocolJson.mapper().treeToValue(
                resource("contracts/v1/requests.json").get(0).get("value"),
                HarnessRequest.class));

        HarnessRequest decoded = ProtocolJson.decode(json);

        assertEquals("req-sessions", decoded.requestId());
        assertTrue(json.length < ProtocolJson.MAX_REQUEST_BYTES);
    }

    private static JsonNode resource(String name) throws IOException {
        try (InputStream stream = ProtocolJsonContractTest.class.getClassLoader()
                .getResourceAsStream(name)) {
            if (stream == null) {
                throw new IOException("missing resource " + name);
            }
            return ProtocolJson.mapper().readTree(stream);
        }
    }

    private static String requestWithCommand(String command) {
        return "{\"version\":{\"major\":1,\"minor\":0},\"sessionId\":\"s\","
                + "\"requestId\":\"r\",\"deadlineMillis\":1,\"command\":"
                + command + "}";
    }

    private static String request(long deadline, String sessionId, String requestId) {
        return "{\"version\":{\"major\":1,\"minor\":0},\"sessionId\":\""
                + sessionId + "\",\"requestId\":\"" + requestId
                + "\",\"deadlineMillis\":" + deadline
                + ",\"command\":{\"type\":\"snapshot\"}}";
    }

    private static String requestWithDeadline(String deadline) {
        return "{\"version\":{\"major\":1,\"minor\":0},\"sessionId\":\"s\","
                + "\"requestId\":\"r\",\"deadlineMillis\":" + deadline
                + ",\"command\":{\"type\":\"snapshot\"}}";
    }
}
