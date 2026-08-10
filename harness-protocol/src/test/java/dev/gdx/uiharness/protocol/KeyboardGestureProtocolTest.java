package dev.gdx.uiharness.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gdx.uiharness.core.gesture.KeyboardGestureRequest;
import dev.gdx.uiharness.core.gesture.KeyboardGestureResult;
import dev.gdx.uiharness.core.gesture.KeyboardGestureResult.CleanupStatus;
import dev.gdx.uiharness.core.gesture.KeyboardGestureResult.FailureCategory;
import dev.gdx.uiharness.core.gesture.KeyboardGestureResult.TerminalOutcome;
import dev.gdx.uiharness.core.time.Deadline;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class KeyboardGestureProtocolTest {
    @Test void closedCommandRoundTripsAllFourStepVariants() throws Exception {
        Command.KeyboardGesture command = new Command.KeyboardGesture(1, List.of(
                new Command.KeyboardGestureStep.KeyDown(59),
                new Command.KeyboardGestureStep.WaitFrames(2),
                new Command.KeyboardGestureStep.WaitTicks(3),
                new Command.KeyboardGestureStep.KeyUp(59)));

        String json = ProtocolJson.mapper().writeValueAsString(command);
        Command.KeyboardGesture decoded = ProtocolJson.mapper().readValue(
                json, Command.KeyboardGesture.class);

        assertEquals(command, decoded);
        assertEquals(List.of(
                new KeyboardGestureRequest.KeyDown(59),
                new KeyboardGestureRequest.WaitFrames(2),
                new KeyboardGestureRequest.WaitTicks(3),
                new KeyboardGestureRequest.KeyUp(59)), decoded.toCore().steps());
        assertTrue(json.contains("\"type\":\"keyboard-gesture\""));
        assertTrue(json.contains("\"kind\":\"wait-ticks\""));
    }

    @Test void decodeRejectsUnknownMembersKindsAndEveryCoreBoundBeforeRouting() {
        List<String> invalidCommands = List.of(
                "{\"type\":\"unknown\"}",
                gesture("\"extra\":true,", validSteps()),
                gesture("", "[{\"kind\":\"unknown\",\"count\":1}]"),
                gesture("", "[{\"kind\":\"key-down\",\"keycode\":29,\"extra\":1},"
                        + "{\"kind\":\"key-up\",\"keycode\":29}]"),
                gestureWithVersion(2, "\"steps\":" + validSteps()),
                gesture("", "[{\"kind\":\"key-down\",\"keycode\":29}]"),
                gesture("", repeatedSteps(65)),
                gesture("", "[{\"kind\":\"key-down\",\"keycode\":-1},"
                        + "{\"kind\":\"key-up\",\"keycode\":-1}]"),
                gesture("", "[{\"kind\":\"key-down\",\"keycode\":256},"
                        + "{\"kind\":\"key-up\",\"keycode\":256}]"),
                gesture("", waitSteps(0)),
                gesture("", waitSteps(10_001)),
                gesture("", "[{\"kind\":\"key-up\",\"keycode\":29},"
                        + "{\"kind\":\"key-down\",\"keycode\":29}]"),
                gesture("", cumulativeWaitSteps()));

        RecordingCoordinator coordinator = new RecordingCoordinator(rejectedResult());
        for (String command : invalidCommands) {
            assertThrows(RuntimeException.class, () -> decodeRequest(command), command);
        }
        assertEquals(0, coordinator.calls.get());
    }

    @Test void routingRequiresCapabilityAndCoordinatorAndPreservesStructuredFailure() {
        RecordingCoordinator coordinator = new RecordingCoordinator(rejectedResult());
        Command.KeyboardGesture command = command();

        HarnessResponse.Failure noCapability = assertInstanceOf(HarnessResponse.Failure.class,
                service(coordinator, List.of()).execute(request(command))
                        .toCompletableFuture().join());
        assertEquals(ProtocolError.Code.UNSUPPORTED_CAPABILITY, noCapability.error().code());
        assertEquals(0, coordinator.calls.get());

        HarnessResponse.Failure noCoordinator = assertInstanceOf(HarnessResponse.Failure.class,
                service(null, List.of("ui_keyboard_gesture")).execute(request(command))
                        .toCompletableFuture().join());
        assertEquals(ProtocolError.Code.UNSUPPORTED_CAPABILITY, noCoordinator.error().code());

        HarnessResponse.Success success = assertInstanceOf(HarnessResponse.Success.class,
                service(coordinator, List.of("ui_keyboard_gesture")).execute(request(command))
                        .toCompletableFuture().join());
        HarnessResponse.Result.KeyboardGesture result = assertInstanceOf(
                HarnessResponse.Result.KeyboardGesture.class, success.result());
        assertEquals("rejected", result.gesture().outcome());
        assertEquals("invalid-runtime-state", result.gesture().failure());
        assertEquals("request-gesture", coordinator.requestId);
        assertEquals(command.toCore(), coordinator.request);
        assertTrue(coordinator.deadline.remaining().toMillis() <= 500);
        assertEquals(1, coordinator.calls.get());
    }

    @Test void resultProjectionRejectsUnknownClosedWireValues() {
        HarnessResponse.KeyboardTickData tick = new HarnessResponse.KeyboardTickData(
                3, 3, 4, 7, 2, 10L, 12L, null, null, 16_000_000);
        assertThrows(IllegalArgumentException.class,
                () -> new HarnessResponse.KeyboardCleanupData(29, "mystery"));
        assertThrows(IllegalArgumentException.class,
                () -> new HarnessResponse.KeyboardGestureStepData(
                        0, "unknown", "completed", 29, null,
                        1, 1, 2, 1, List.of(29), null));
        assertThrows(IllegalArgumentException.class,
                () -> new HarnessResponse.KeyboardGestureStepData(
                        0, "key-down", "completed", 29, 1,
                        1, 1, 2, 1, List.of(29), null));
        assertThrows(IllegalArgumentException.class,
                () -> new HarnessResponse.KeyboardGestureStepData(
                        0, "wait-frames", "completed", 29, 1,
                        1, 1, 2, 1, List.of(29), null));
        assertThrows(IllegalArgumentException.class,
                () -> new HarnessResponse.KeyboardGestureStepData(
                        0, "key-up", "completed", 256, null,
                        1, 1, 2, 1, List.of(), null));
        assertThrows(IllegalArgumentException.class,
                () -> new HarnessResponse.KeyboardGestureStepData(
                        0, "wait-ticks", "completed", null, 2,
                        1, 1, 2, 1, List.of(29), tick));
        assertThrows(IllegalArgumentException.class,
                () -> new HarnessResponse.KeyboardGestureStepData(
                        0, "key-down", "completed", 29, null,
                        1, 1, 2, 1, List.of(29, 29), null));
        assertThrows(IllegalArgumentException.class,
                () -> new HarnessResponse.KeyboardGestureData(
                        1, "unknown", 2, 0, 0,
                        1, 1, 1, 1, 1, List.of(), 0,
                        "invalid-runtime-state", List.of(), "not-required",
                        List.of(), null));
        assertThrows(IllegalArgumentException.class,
                () -> new HarnessResponse.KeyboardGestureData(
                        1, "cancelled", 2, 0, 0,
                        1, 1, 1, 1, 1, List.of(), 0,
                        "cancelled", List.of(29, 29), "not-required",
                        List.of(), null));
    }

    @Test void everyHistoricalSessionConstructorKeepsAnEmptyGestureCoordinator() {
        HarnessProtocolService.Session base = baseSession(List.of());
        var emptyRegistry = Optional.<dev.gdx.uiharness.core.scenario.ScenarioRegistry>empty();
        var emptyScenario = Optional.<HarnessProtocolService.ScenarioCoordinator>empty();
        var emptyNavigation = Optional.<HarnessProtocolService.NavigationCoordinator>empty();
        var emptyLayout = Optional.<HarnessProtocolService.LayoutValidationCoordinator>empty();
        var emptyMatrix = Optional.<HarnessProtocolService.MatrixCoordinator>empty();
        var emptySemantic = Optional.<HarnessProtocolService.SemanticCompareCoordinator>empty();
        var emptyRuntime = Optional.<HarnessProtocolService.RuntimeCompareCoordinator>empty();

        List<HarnessProtocolService.Session> historical = List.of(
                new HarnessProtocolService.Session(base.harness(), base.locators(), base.waits(),
                        base.capture(), base.capabilities(), base.traces()),
                new HarnessProtocolService.Session(base.harness(), base.locators(), base.waits(),
                        base.capture(), base.capabilities(), base.traces(),
                        emptyRegistry, emptyScenario),
                new HarnessProtocolService.Session(base.harness(), base.locators(), base.waits(),
                        base.capture(), base.capabilities(), base.traces(),
                        emptyRegistry, emptyScenario, emptyNavigation),
                new HarnessProtocolService.Session(base.harness(), base.locators(), base.waits(),
                        base.capture(), base.capabilities(), base.traces(), emptyRegistry,
                        emptyScenario, emptyNavigation, emptyLayout),
                new HarnessProtocolService.Session(base.harness(), base.locators(), base.waits(),
                        base.capture(), base.capabilities(), base.traces(), emptyRegistry,
                        emptyScenario, emptyNavigation, emptyLayout, emptyMatrix),
                new HarnessProtocolService.Session(base.harness(), base.locators(), base.waits(),
                        base.capture(), base.capabilities(), base.traces(), emptyRegistry,
                        emptyScenario, emptyNavigation, emptyLayout, emptyMatrix, emptySemantic),
                new HarnessProtocolService.Session(base.harness(), base.locators(), base.waits(),
                        base.capture(), base.capabilities(), base.traces(), emptyRegistry,
                        emptyScenario, emptyNavigation, emptyLayout, emptyMatrix, emptySemantic,
                        emptyRuntime));

        assertTrue(historical.stream().allMatch(session ->
                session.keyboardGestureCoordinator().isEmpty()));
    }

    private static Command.KeyboardGesture command() {
        return new Command.KeyboardGesture(1, List.of(
                new Command.KeyboardGestureStep.KeyDown(29),
                new Command.KeyboardGestureStep.KeyUp(29)));
    }

    private static HarnessRequest request(Command command) {
        return new HarnessRequest(
                ProtocolVersion.V1, "game", "request-gesture", 500, command);
    }

    private static HarnessRequest decodeRequest(String command) {
        String json = "{\"version\":{\"major\":1,\"minor\":0},\"sessionId\":\"game\","
                + "\"requestId\":\"request-gesture\",\"deadlineMillis\":500,"
                + "\"command\":" + command + "}";
        return ProtocolJson.decode(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String gesture(String prefix, String steps) {
        return gestureWithVersion(1, prefix + "\"steps\":" + steps);
    }

    private static String gestureWithVersion(int version, String members) {
        return "{\"type\":\"keyboard-gesture\",\"schemaVersion\":" + version + ","
                + members + "}";
    }

    private static String validSteps() {
        return "[{\"kind\":\"key-down\",\"keycode\":29},"
                + "{\"kind\":\"key-up\",\"keycode\":29}]";
    }

    private static String waitSteps(int count) {
        return "[{\"kind\":\"key-down\",\"keycode\":29},"
                + "{\"kind\":\"wait-frames\",\"count\":" + count + "},"
                + "{\"kind\":\"key-up\",\"keycode\":29}]";
    }

    private static String cumulativeWaitSteps() {
        return "[{\"kind\":\"key-down\",\"keycode\":29},"
                + "{\"kind\":\"wait-frames\",\"count\":10000},"
                + "{\"kind\":\"wait-frames\",\"count\":1},"
                + "{\"kind\":\"key-up\",\"keycode\":29}]";
    }

    private static String repeatedSteps(int count) {
        StringBuilder steps = new StringBuilder("[");
        for (int index = 0; index < count; index++) {
            if (index > 0) {
                steps.append(',');
            }
            steps.append(index % 2 == 0
                    ? "{\"kind\":\"key-down\",\"keycode\":29}"
                    : "{\"kind\":\"key-up\",\"keycode\":29}");
        }
        return steps.append(']').toString();
    }

    private static KeyboardGestureResult rejectedResult() {
        return new KeyboardGestureResult(
                1, TerminalOutcome.REJECTED, 2, 0, 0,
                7, 9, 7, 9, 10, List.of(), OptionalInt.of(0),
                Optional.of(FailureCategory.INVALID_RUNTIME_STATE), List.of(),
                CleanupStatus.NOT_REQUIRED, List.of(), Optional.empty());
    }

    private static HarnessProtocolService service(
            RecordingCoordinator coordinator, List<String> capabilities) {
        HarnessProtocolService.Session base = baseSession(capabilities);
        HarnessProtocolService.Session session = new HarnessProtocolService.Session(
                base.harness(), base.locators(), base.waits(), base.capture(),
                base.capabilities(), base.traces(), base.scenarioRegistry(),
                base.scenarioCoordinator(), base.navigationCoordinator(),
                base.layoutValidationCoordinator(), base.matrixCoordinator(),
                base.semanticCompareCoordinator(), base.runtimeCompareCoordinator(),
                Optional.ofNullable(coordinator));
        return new HarnessProtocolService(
                Map.of("game", session), Map.of(), System::nanoTime, Runnable::run);
    }

    private static HarnessProtocolService.Session baseSession(List<String> capabilities) {
        var waits = new dev.gdx.uiharness.core.wait.WaitEngine(
                () -> null, new dev.gdx.uiharness.core.locator.StrictResolution(),
                System::nanoTime, listener -> () -> {});
        return new HarnessProtocolService.Session(
                new NoopHarness(), new dev.gdx.uiharness.core.locator.StrictResolution(),
                waits, new NoopCapture(), new CapabilitySet(capabilities),
                HarnessProtocolService.TraceController.unsupported());
    }

    private static final class RecordingCoordinator
            implements HarnessProtocolService.KeyboardGestureCoordinator {
        private final KeyboardGestureResult result;
        private final AtomicInteger calls = new AtomicInteger();
        private String requestId;
        private KeyboardGestureRequest request;
        private Deadline deadline;

        RecordingCoordinator(KeyboardGestureResult result) {
            this.result = result;
        }

        @Override public CompletionStage<KeyboardGestureResult> execute(
                String requestId, KeyboardGestureRequest request, Deadline deadline) {
            calls.incrementAndGet();
            this.requestId = requestId;
            this.request = request;
            this.deadline = deadline;
            return CompletableFuture.completedFuture(result);
        }
    }

    private static final class NoopHarness implements dev.gdx.uiharness.core.action.Harness {
        @Override public CompletionStage<dev.gdx.uiharness.core.action.ActionResult> perform(
                dev.gdx.uiharness.core.locator.Locator locator,
                dev.gdx.uiharness.core.action.Action action, Deadline deadline) {
            throw new AssertionError("actions are not expected");
        }

        @Override public CompletionStage<dev.gdx.uiharness.core.model.SemanticSnapshot> snapshot(
                Deadline deadline) {
            throw new AssertionError("snapshots are not expected");
        }
    }

    private static final class NoopCapture implements dev.gdx.uiharness.core.capture.ScreenCapture {
        @Override public CompletionStage<dev.gdx.uiharness.core.capture.CapturedImage> capture(
                dev.gdx.uiharness.core.capture.CaptureRequest request, Deadline deadline) {
            throw new AssertionError("captures are not expected");
        }

        @Override public void close() {}
    }
}
