package dev.gdx.uiharness.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gdx.uiharness.core.navigation.NavigationInput;
import dev.gdx.uiharness.core.navigation.NavigationPath;
import dev.gdx.uiharness.core.navigation.NavigationReason;
import dev.gdx.uiharness.core.navigation.NavigationResult;
import dev.gdx.uiharness.core.navigation.NavigationStep;
import dev.gdx.uiharness.core.time.Deadline;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class NavigationProtocolTest {
    private final Command.NavigationSpec spec = new Command.NavigationSpec(
            "navigation", 7, Map.of("locale", "en"), "desktop",
            "app", "process", "session",
            List.of("tab", "tab"), null, true,
            16, 16, 262144, 262144, 5_000);

    @Test void navigationCommandsRoundTripThroughTheClosedSchema() throws Exception {
        Command.NavigationInspect inspect = new Command.NavigationInspect(spec);
        Command.NavigationValidate validate = new Command.NavigationValidate(spec);

        String inspectJson = ProtocolJson.mapper().writeValueAsString(inspect);
        String validateJson = ProtocolJson.mapper().writeValueAsString(validate);

        assertEquals(inspect, ProtocolJson.mapper().readValue(inspectJson,
                Command.NavigationInspect.class));
        assertEquals(validate, ProtocolJson.mapper().readValue(validateJson,
                Command.NavigationValidate.class));
        assertTrue(inspectJson.contains("\"type\":\"navigation-inspect\""));
        assertTrue(validateJson.contains("\"type\":\"navigation-validate\""));
        assertTrue(inspectJson.contains("\"inputs\":[\"tab\",\"tab\"]"));
    }

    @Test void navigationCommandsRejectUnknownInputsBoundsAndIdentifiers() {
        assertThrows(IllegalArgumentException.class, () -> new Command.NavigationSpec(
                "navigation", 7, Map.of(), "desktop", "app", "process", "session",
                List.of("teleport"), null, true, 16, 16, 262144, 262144, 5_000));
        assertThrows(IllegalArgumentException.class, () -> new Command.NavigationSpec(
                "navigation", 7, Map.of(), "desktop", "app", "process", "session",
                List.of(), null, true, 0, 16, 262144, 262144, 5_000));
        assertThrows(IllegalArgumentException.class, () -> new Command.NavigationSpec(
                "navigation", 7, Map.of(), "desktop", "app", "process", "session",
                List.of(), null, true, 16, 0, 262144, 262144, 5_000));
        assertThrows(IllegalArgumentException.class, () -> new Command.NavigationSpec(
                "navigation", 7, Map.of(), "desktop", "app", "process", "session",
                List.of(), null, true, 16, 16, 1, 262144, 5_000));
        assertThrows(IllegalArgumentException.class, () -> new Command.NavigationSpec(
                "navigation", 7, Map.of(), "desktop", "app", "process", "session",
                List.of(), null, true, 16, 16, 262144, 262144, 0));
    }

    @Test void navigationResultRoundTripsWithClosedStepEvidence() throws Exception {
        NavigationResult result = new NavigationResult(
                1,
                new NavigationPath(1, "test-id:first", List.of(new NavigationStep(
                        NavigationInput.TAB, 10, 20, 11, 21,
                        "test-id:first", "test-id:second", null)),
                        NavigationReason.COMPLETE),
                List.of("test-id:first", "test-id:second"),
                List.of(),
                false);

        HarnessResponse.Result.Navigation response =
                new HarnessResponse.Result.Navigation(result);
        String json = ProtocolJson.mapper().writeValueAsString(response);
        HarnessResponse.Result.Navigation decoded = ProtocolJson.mapper().readValue(
                json, HarnessResponse.Result.Navigation.class);

        assertEquals(result, decoded.result());
        assertTrue(json.contains("\"reason\":\"COMPLETE\""));
        assertTrue(json.contains("\"input\":\"TAB\""));
        assertTrue(json.contains("test-id:second"));
    }

    @Test void navigationRequiresCoordinatorAndRejectsUnknownScenario() {
        RecordingCoordinator coordinator = new RecordingCoordinator();
        HarnessProtocolService service = service(null);

        HarnessResponse.Failure unavailable = assertInstanceOf(HarnessResponse.Failure.class,
                service.execute(request(new Command.NavigationInspect(spec)))
                        .toCompletableFuture().join());
        assertEquals(ProtocolError.Code.UNSUPPORTED_CAPABILITY, unavailable.error().code());

        HarnessProtocolService wired = service(coordinator);
        HarnessResponse.Failure unknown = assertInstanceOf(HarnessResponse.Failure.class,
                wired.execute(request(new Command.NavigationInspect(new Command.NavigationSpec(
                        "missing", 7, Map.of(), "desktop", "app", "process", "session",
                        List.of("tab"), null, true, 16, 16, 262144, 262144, 5_000))))
                        .toCompletableFuture().join());
        assertEquals("unknown-scenario", unknown.error().details().get("reason"));
        assertEquals(0, coordinator.calls.get());
    }

    @Test void navigationRoutesInspectAndValidateToTheCoordinator() {
        RecordingCoordinator coordinator = new RecordingCoordinator();
        HarnessProtocolService service = service(coordinator);

        HarnessResponse.Success inspected = assertInstanceOf(HarnessResponse.Success.class,
                service.execute(request(new Command.NavigationInspect(spec)))
                        .toCompletableFuture().join());
        assertEquals("test-id:second", assertInstanceOf(HarnessResponse.Result.Navigation.class,
                inspected.result()).result().path().steps().get(0).afterIdentity());
        assertEquals(1, coordinator.inspectCalls.get());

        HarnessResponse.Success validated = assertInstanceOf(HarnessResponse.Success.class,
                service.execute(request(new Command.NavigationValidate(spec)))
                        .toCompletableFuture().join());
        assertInstanceOf(HarnessResponse.Result.Navigation.class, validated.result());
        assertEquals(1, coordinator.validateCalls.get());
        assertTrue(coordinator.lastValidateDeadline != null);
    }

    private static HarnessRequest request(Command command) {
        return new HarnessRequest(ProtocolVersion.V1, "session", "request-nav", 500, command);
    }

    private static HarnessProtocolService service(RecordingCoordinator coordinator) {
        var waits = new dev.gdx.uiharness.core.wait.WaitEngine(
                () -> null, new dev.gdx.uiharness.core.locator.StrictResolution(),
                System::nanoTime, listener -> () -> {});
        var capabilities = new CapabilitySet(List.of("ui_navigation_inspect",
                "ui_navigation_validate"));
        var registry = new dev.gdx.uiharness.core.scenario.ScenarioRegistry();
        registry.register(
                new dev.gdx.uiharness.core.scenario.ScenarioDefinition(
                        1, "navigation", "1", "app", List.of("desktop"),
                        1, Duration.ofMinutes(1)),
                new dev.gdx.uiharness.core.scenario.ScenarioLifecycle() {
                    @Override public void setup(
                            dev.gdx.uiharness.core.scenario.ScenarioRequest request) {}
                    @Override public void reset(
                            dev.gdx.uiharness.core.scenario.ScenarioRequest request) {}
                    @Override public boolean ready(
                            dev.gdx.uiharness.core.scenario.ScenarioRequest request) {
                        return true;
                    }
                    @Override public String startStateIdentity(
                            dev.gdx.uiharness.core.scenario.ScenarioRequest request,
                            dev.gdx.uiharness.core.model.SemanticSnapshot snapshot) {
                        return "ready";
                    }
                    @Override public void cleanup(
                            dev.gdx.uiharness.core.scenario.ScenarioRequest request) {}
                });
        HarnessProtocolService.Session session = new HarnessProtocolService.Session(
                new NoopHarness(), new dev.gdx.uiharness.core.locator.StrictResolution(),
                waits, new NoopCapture(), capabilities,
                HarnessProtocolService.TraceController.unsupported(),
                java.util.Optional.of(registry),
                java.util.Optional.empty(),
                coordinator == null ? java.util.Optional.empty()
                        : java.util.Optional.of(coordinator));
        return new HarnessProtocolService(
                Map.of("session", session), Map.of(), System::nanoTime, Runnable::run);
    }

    private static final class RecordingCoordinator
            implements HarnessProtocolService.NavigationCoordinator {
        final AtomicReference<Command.NavigationSpec> lastSpec = new AtomicReference<>();
        final AtomicReference<Deadline> lastValidateDeadline = new AtomicReference<>();
        final java.util.concurrent.atomic.AtomicInteger inspectCalls =
                new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger validateCalls =
                new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();

        @Override public CompletionStage<NavigationResult> inspect(
                Command.NavigationSpec spec, Deadline deadline) {
            calls.incrementAndGet();
            inspectCalls.incrementAndGet();
            lastSpec.set(spec);
            return CompletableFuture.completedFuture(navigated());
        }

        @Override public CompletionStage<NavigationResult> validate(
                Command.NavigationSpec spec, Deadline deadline) {
            calls.incrementAndGet();
            validateCalls.incrementAndGet();
            lastSpec.set(spec);
            lastValidateDeadline.set(deadline);
            return CompletableFuture.completedFuture(navigated());
        }

        private static NavigationResult navigated() {
            return new NavigationResult(
                    1,
                    new NavigationPath(1, "test-id:first", List.of(new NavigationStep(
                            NavigationInput.TAB, 10, 20, 11, 21,
                            "test-id:first", "test-id:second", null)),
                            NavigationReason.COMPLETE),
                    List.of("test-id:first", "test-id:second"),
                    List.of(),
                    false);
        }
    }

    private static final class NoopHarness implements dev.gdx.uiharness.core.action.Harness {
        @Override public CompletionStage<dev.gdx.uiharness.core.action.ActionResult> perform(
                dev.gdx.uiharness.core.locator.Locator locator,
                dev.gdx.uiharness.core.action.Action action,
                dev.gdx.uiharness.core.time.Deadline deadline) {
            throw new AssertionError("actions are not expected");
        }

        @Override public CompletionStage<dev.gdx.uiharness.core.model.SemanticSnapshot> snapshot(
                dev.gdx.uiharness.core.time.Deadline deadline) {
            throw new AssertionError("snapshots are not expected");
        }
    }

    private static final class NoopCapture
            implements dev.gdx.uiharness.core.capture.ScreenCapture {
        @Override public CompletionStage<dev.gdx.uiharness.core.capture.CapturedImage> capture(
                dev.gdx.uiharness.core.capture.CaptureRequest request,
                dev.gdx.uiharness.core.time.Deadline deadline) {
            throw new AssertionError("capture is not expected");
        }

        @Override public void close() {}
    }
}
