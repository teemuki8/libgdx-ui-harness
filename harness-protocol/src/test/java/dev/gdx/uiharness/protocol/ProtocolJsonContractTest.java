package dev.gdx.uiharness.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
                Command.Assert.class, Command.Wait.class, Command.Screenshot.class,
                Command.TraceStart.class, Command.TraceStop.class, Command.ScenarioList.class,
                Command.ScenarioStart.class), variants);
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
                HarnessResponse.Result.Assertion.class,
                HarnessResponse.Result.Wait.class,
                HarnessResponse.Result.Screenshot.class,
                HarnessResponse.Result.TraceStarted.class,
                HarnessResponse.Result.TraceStopped.class,
                HarnessResponse.Result.ScenarioList.class,
                HarnessResponse.Result.ScenarioStart.class), variants);
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

    @Test void scenarioStartFailuresUseClosedDistinctWireOutcomes() throws Exception {
        HarnessResponse.Success deadline = new HarnessResponse.Success(
                ProtocolVersion.V1, "request", "game",
                new HarnessResponse.Result.ScenarioStart(
                        new HarnessResponse.ScenarioStartOutcome.Failed("deadline")));
        HarnessResponse.Success cancelled = new HarnessResponse.Success(
                ProtocolVersion.V1, "request", "game",
                new HarnessResponse.Result.ScenarioStart(
                        new HarnessResponse.ScenarioStartOutcome.Failed("cancelled")));

        assertEquals(
                "{\"status\":\"ok\",\"version\":{\"major\":1,\"minor\":0},"
                        + "\"requestId\":\"request\",\"sessionId\":\"game\","
                        + "\"result\":{\"type\":\"scenario-start\","
                        + "\"outcome\":{\"kind\":\"failed\",\"reason\":\"deadline\"}}}",
                ProtocolJson.mapper().writeValueAsString(deadline));
        assertEquals("cancelled",
                ProtocolJson.mapper().valueToTree(cancelled).at("/result/outcome/reason").asText());
        assertThrows(IllegalArgumentException.class,
                () -> new HarnessResponse.ScenarioStartOutcome.Failed("deadline-exceeded"));
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

    @Test void everyAssertionVariantRoundTripsWithClosedKebabCaseDiscriminators()
            throws Exception {
        List<String> variants = List.of(
                "{\"kind\":\"visible\"}",
                "{\"kind\":\"hidden\"}",
                "{\"kind\":\"enabled\"}",
                "{\"kind\":\"disabled\"}",
                "{\"kind\":\"focused\"}",
                "{\"kind\":\"checked\"}",
                "{\"kind\":\"text-equals\",\"expected\":\"Ready\"}",
                "{\"kind\":\"text-contains\",\"expected\":\"ead\"}",
                "{\"kind\":\"count-equals\",\"expected\":2}",
                "{\"kind\":\"bounds-inside-viewport\",\"viewport\":"
                        + "{\"x\":0.0,\"y\":0.0,\"width\":800.0,\"height\":600.0}}",
                "{\"kind\":\"does-not-overlap\",\"other\":"
                        + "{\"kind\":\"test-id\",\"testId\":\"dialog\"}}",
                "{\"kind\":\"stable-for-frames\",\"frames\":3,"
                        + "\"properties\":[\"bounds\",\"accessible-name\"]}",
                "{\"kind\":\"accessible-name-exists\"}");

        for (String assertion : variants) {
            String json = requestWithCommand(
                    "{\"type\":\"assert\",\"schemaVersion\":1,"
                            + "\"locator\":{\"kind\":\"test-id\",\"testId\":\"save\"},"
                            + "\"assertion\":" + assertion + "}");
            HarnessRequest decoded =
                    ProtocolJson.mapper().readValue(json, HarnessRequest.class);
            assertInstanceOf(Command.Assert.class, decoded.command());
            assertEquals(json, ProtocolJson.mapper().writeValueAsString(decoded));
        }
    }

    @Test void assertionUnionRejectsUnknownVariantsFieldsAndVersionsRecursively() {
        List<String> invalidCommands = List.of(
                "{\"type\":\"assert\",\"schemaVersion\":2,"
                        + "\"locator\":{\"kind\":\"test-id\",\"testId\":\"save\"},"
                        + "\"assertion\":{\"kind\":\"visible\"}}",
                "{\"type\":\"assert\",\"schemaVersion\":1,"
                        + "\"locator\":{\"kind\":\"test-id\",\"testId\":\"save\"},"
                        + "\"assertion\":{\"kind\":\"future\"}}",
                "{\"type\":\"assert\",\"schemaVersion\":1,"
                        + "\"locator\":{\"kind\":\"test-id\",\"testId\":\"save\"},"
                        + "\"assertion\":{\"kind\":\"visible\",\"surprise\":true}}",
                "{\"type\":\"assert\",\"schemaVersion\":1,"
                        + "\"locator\":{\"kind\":\"filter\","
                        + "\"locator\":{\"kind\":\"role\",\"role\":\"button\",\"surprise\":true},"
                        + "\"filter\":{\"kind\":\"has\",\"locator\":"
                        + "{\"kind\":\"test-id\",\"testId\":\"child\"}}},"
                        + "\"assertion\":{\"kind\":\"visible\"}}",
                "{\"type\":\"assert\",\"schemaVersion\":1,"
                        + "\"locator\":{\"kind\":\"test-id\",\"testId\":\"save\"},"
                        + "\"assertion\":{\"kind\":\"does-not-overlap\",\"other\":"
                        + "{\"kind\":\"filter\",\"locator\":{\"kind\":\"role\",\"role\":\"dialog\"},"
                        + "\"filter\":{\"kind\":\"has\",\"locator\":"
                        + "{\"kind\":\"test-id\",\"testId\":\"child\",\"surprise\":true}}}}}");
        for (String command : invalidCommands) {
            assertThrows(JsonProcessingException.class,
                    () -> ProtocolJson.mapper().readValue(
                            requestWithCommand(command), HarnessRequest.class));
        }
    }

    @Test void assertionEvidencePreservesBoundsTruncationAndOptionalTrace() throws Exception {
        Command.LocatorSpec locator = new Command.LocatorSpec.TestId("save");
        Command.AssertionSpec assertion = new Command.AssertionSpec.TextEquals("Ready");
        HarnessResponse.Result.Assertion bounded = new HarnessResponse.Result.Assertion(
                1, "failed", locator, assertion, "save", "Ready", "Loading",
                "retryable", 7, 9, 500,
                List.of(Map.of("nodeId", "candidate")), true, "trace-7");
        JsonNode json = ProtocolJson.mapper().valueToTree(bounded);
        assertEquals("trace-7", json.path("traceId").asText());
        assertTrue(json.path("truncated").asBoolean());
        assertEquals(1, json.path("candidates").size());

        List<Map<String, String>> oversized = java.util.stream.IntStream.rangeClosed(0, 1_000)
                .mapToObj(index -> Map.of("nodeId", "candidate-" + index)).toList();
        assertThrows(IllegalArgumentException.class, () -> new HarnessResponse.Result.Assertion(
                1, "failed", locator, assertion, "save", "Ready", "Loading",
                "retryable", 7, 9, 500, oversized, true, null));
        assertThrows(IllegalArgumentException.class, () -> new HarnessResponse.Result.Assertion(
                2, "failed", locator, assertion, "save", "Ready", "Loading",
                "retryable", 7, 9, 500, List.of(), false, null));
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

    @Test void scenarioStartRejectsUnknownExecutionFieldsAndOverLimitConfiguration() {
        for (String field : List.of("command", "path", "environment", "class", "launchArguments")) {
            String json = requestWithCommand(
                    "{\"type\":\"scenario-start\","
                            + "\"scenarioId\":\"known\",\"seed\":7,\"configuration\":{},"
                            + "\"profileId\":\"desktop\",\"" + field + "\":\"attack\"}");
            assertThrows(JsonProcessingException.class,
                    () -> ProtocolJson.mapper().readValue(json, HarnessRequest.class), field);
        }
        Map<String, String> oversized = new HashMap<>();
        for (int index = 0; index <= 256; index++) {
            oversized.put("key-" + index, "value");
        }
        assertThrows(IllegalArgumentException.class,
                () -> new Command.ScenarioStart("known", 7, oversized, "desktop"));
    }

    @Test void scenarioDeadlineMaximumIsInclusiveAndFailureEvidenceMayPrecedeReadiness()
            throws Exception {
        Command.ScenarioStart command =
                new Command.ScenarioStart("known", 7, Map.of(), "desktop");
        new HarnessRequest(
                ProtocolVersion.V1, "game", "request", 600_000, command);
        assertThrows(IllegalArgumentException.class, () -> new HarnessRequest(
                ProtocolVersion.V1, "game", "request", 600_001, command));

        HarnessResponse.ScenarioResultData failed = new HarnessResponse.ScenarioResultData(
                1, "known", "v1", "digest", 7, "game", "process", "game",
                10, 20, 0, 0, "desktop", "unavailable", 25, 1, true,
                HarnessResponse.ScenarioFailureData.READINESS_DEADLINE);
        assertEquals("readiness-deadline", failed.failure().wireName());
        assertTrue(ProtocolJson.mapper().writeValueAsString(failed)
                .contains("\"failure\":\"readiness-deadline\""));
        assertEquals(
                HarnessResponse.ScenarioFailureData.READINESS_REJECTED,
                HarnessResponse.ScenarioFailureData.fromWireName("readiness-rejected"));
        assertEquals("\"readiness-rejected\"", ProtocolJson.mapper().writeValueAsString(
                HarnessResponse.ScenarioFailureData.READINESS_REJECTED));
    }
    @Test void scenarioResultRejectsUnknownTerminalFailure() {
        String unknownFailure = "{\"schemaVersion\":1,\"scenarioId\":\"known\","
                + "\"definitionVersion\":\"v1\",\"configurationDigest\":\"digest\","
                + "\"seed\":7,\"applicationId\":\"game\",\"processId\":\"process\","
                + "\"sessionId\":\"game\",\"startFrame\":10,\"startRevision\":20,"
                + "\"readyFrame\":0,\"readyRevision\":0,\"profileId\":\"desktop\","
                + "\"startStateIdentity\":\"unavailable\",\"elapsedMillis\":25,"
                + "\"setupAttempts\":1,\"cleanupCompleted\":true,"
                + "\"failure\":\"future-failure\"}";

        assertThrows(JsonProcessingException.class, () -> ProtocolJson.mapper().readValue(
                unknownFailure, HarnessResponse.ScenarioResultData.class));
    }


    @Test void mapperCallersCannotMutateCanonicalConfiguration() {
        var callerMapper = ProtocolJson.mapper();
        callerMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        assertTrue(ProtocolJson.mapper()
                .isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES));
    }

    @Test void decodeRejectsMalformedRegularExpressions() {
        String malformedRegex = requestWithCommand(
                "{\"type\":\"query\",\"locator\":{\"kind\":\"text\",\"field\":\"text\","
                        + "\"match\":{\"mode\":\"regex\",\"source\":\"[\"}}}");

        ProtocolJson.ProtocolJsonException failure = assertThrows(
                ProtocolJson.ProtocolJsonException.class,
                () -> ProtocolJson.decode(
                        malformedRegex.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        assertEquals("invalid-request", failure.code());
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
                Collections.nCopies(1_000, Map.of("candidate", maximum)), details, null,
                List.of());
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

    @Test void rejectsOversizedQueryAndWaitEvidenceKeysAndValues() {
        String oversized = "x".repeat(ProtocolJson.MAX_STRING_LENGTH + 1);
        String query = successWithResult(
                "{\"type\":\"query\",\"matches\":[],\"evidence\":[{\"key\":\""
                        + oversized + "\"}]}");
        String wait = successWithResult(
                "{\"type\":\"wait\",\"revision\":1,\"frame\":1,\"matches\":[],"
                        + "\"evidence\":[{\"" + oversized + "\":\"value\"}]}");

        assertThrows(JsonProcessingException.class,
                () -> ProtocolJson.mapper().readValue(query, HarnessResponse.class));
        assertThrows(JsonProcessingException.class,
                () -> ProtocolJson.mapper().readValue(wait, HarnessResponse.class));
    }

    @Test void rejectsOversizedSiblingActionEvidenceAndNodeProperties() throws Exception {
        String oversized = "x".repeat(ProtocolJson.MAX_STRING_LENGTH + 1);
        String action = successWithResult(
                "{\"type\":\"action\",\"beforeRevision\":1,\"afterRevision\":2,"
                        + "\"observedState\":\"done\",\"evidence\":{\"key\":\""
                        + oversized + "\"}}");
        ObjectNode snapshot = (ObjectNode) resource("contracts/v1/results.json")
                .get(2).get("value").deepCopy();
        ObjectNode properties = (ObjectNode) snapshot.at(
                "/result/snapshot/nodes/0/properties");
        properties.put(oversized, "value");

        assertThrows(JsonProcessingException.class,
                () -> ProtocolJson.mapper().readValue(action, HarnessResponse.class));
        assertThrows(JsonProcessingException.class,
                () -> ProtocolJson.mapper().treeToValue(snapshot, HarnessResponse.class));
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

    private static String successWithResult(String result) {
        return "{\"status\":\"ok\",\"version\":{\"major\":1,\"minor\":0},"
                + "\"requestId\":\"r\",\"sessionId\":\"s\",\"result\":" + result + "}";
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
