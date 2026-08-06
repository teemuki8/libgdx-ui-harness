package dev.gdx.uiharness.agentruntime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gdx.uiharness.core.model.RuntimeBinding;
import dev.gdx.uiharness.core.runtime.RuntimeObservation;
import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityId;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityType;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValues;
import io.github.teemuki8.libgdx.agent.runtime.core.SessionId;
import io.github.teemuki8.libgdx.agent.runtime.core.UiFrameCorrelation;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class AgentRuntimeObservationSourceTest {
    private static final String UI_SESSION = "ui-session";
    private static final String CORRELATION_TOKEN = "corr-token";

    private AgentRuntime runtime;

    @BeforeEach
    void setUp() {
        runtime = AgentRuntime.builder().sessionId(new SessionId("test")).build();
        runtime.start();
    }

    @AfterEach
    void tearDown() {
        runtime.close();
    }

    @Test void observesProvenRuntimeValue() {
        registerUser("Ada");
        advanceFrame();
        recordCorrelation(42, CORRELATION_TOKEN);

        Optional<RuntimeObservation> observation = observe("user", "name", CORRELATION_TOKEN);

        assertTrue(observation.isPresent());
        assertEquals(
                new RuntimeObservation("user", "name", 42, 42, "Ada", null),
                observation.orElseThrow());
    }

    @Test void unmatchedCorrelationTokenIsEmpty() {
        registerUser("Ada");
        advanceFrame();
        recordCorrelation(42, CORRELATION_TOKEN);

        assertTrue(observe("user", "name", "other-token").isEmpty());
    }

    @Test void uncorrelatedFrameIsEmpty() {
        registerUser("Ada");
        advanceFrame();
        recordCorrelation(42, CORRELATION_TOKEN);
        advanceFrame();

        assertTrue(observe("user", "name", CORRELATION_TOKEN).isEmpty());
    }

    @Test void missingEntityOrPropertyIsEmpty() {
        advanceFrame();
        recordCorrelation(42, CORRELATION_TOKEN);

        assertTrue(observe("missing", "name", CORRELATION_TOKEN).isEmpty());
        assertTrue(observe("user", "missing", CORRELATION_TOKEN).isEmpty());
    }

    @Test void runtimeWithoutFramesIsEmpty() {
        assertTrue(observe("user", "name", CORRELATION_TOKEN).isEmpty());
    }

    private void registerUser(String value) {
        runtime.entities().register(
                EntityId.of("user"),
                EntityType.of("user"),
                () -> "User",
                inspector -> inspector.property("name", () -> RuntimeValues.string(value)));
    }

    private void advanceFrame() {
        runtime.beginFrame(16_000_000L);
        runtime.endFrame();
    }

    private void recordCorrelation(long harnessFrame, String token) {
        runtime.uiCorrelations().recordFrame(new UiFrameCorrelation(
                runtime.currentEpoch(),
                runtime.latestFrame().orElseThrow().frameId(),
                UI_SESSION,
                Optional.of(Long.toString(harnessFrame)),
                Optional.of(token)));
    }

    private Optional<RuntimeObservation> observe(
            String entityId, String propertyId, String correlationId) {
        AgentRuntimeObservationSource source =
                new AgentRuntimeObservationSource(runtime, UI_SESSION);
        return source.observe(new RuntimeBinding(entityId, propertyId, null, null, correlationId));
    }
}
