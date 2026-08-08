package dev.gdx.uiharness.agentruntime;

import dev.gdx.uiharness.core.model.RuntimeBinding;
import dev.gdx.uiharness.core.runtime.RuntimeObservation;
import dev.gdx.uiharness.core.runtime.RuntimeObservationSource;
import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityId;
import io.github.teemuki8.libgdx.agent.runtime.core.FrameId;
import io.github.teemuki8.libgdx.agent.runtime.core.FrameSnapshot;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValue;
import io.github.teemuki8.libgdx.agent.runtime.core.UiFrameCorrelation;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;

/**
 * Read-only {@link RuntimeObservationSource} over a running {@link AgentRuntime}, resolving each
 * binding against the latest completed frame and proving the harness frame strictly through
 * recorded {@link UiFrameCorrelation}s. There is no harness-clock fallback: when the correlation
 * cannot be proven the source reports nothing rather than guessing a frame.
 *
 * <p>The observation {@code revision} mirrors the harness-proven frame as a documented
 * limitation: {@link UiFrameCorrelation} carries only a frame identifier and the runtime exposes
 * no independent revision counter, so consumers must treat {@code revision} as the correlated
 * frame, never as a runtime-internal tick.</p>
 */
public final class AgentRuntimeObservationSource implements RuntimeObservationSource {
    private static final int CORRELATION_PAGE_SIZE = 64;

    private final AgentRuntime runtime;
    private final String uiSessionId;

    /** Creates a source observing {@code runtime} frames correlated to {@code uiSessionId}. */
    public AgentRuntimeObservationSource(AgentRuntime runtime, String uiSessionId) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.uiSessionId = Objects.requireNonNull(uiSessionId, "uiSessionId");
    }

    @Override
    public boolean available(RuntimeBinding binding) {
        return runtime.latestFrame()
                .flatMap(frame -> frame.entity(EntityId.of(binding.entityId())))
                .flatMap(entity -> entity.property(binding.propertyId()))
                .isPresent();
    }

    @Override
    public Optional<RuntimeObservation> observe(RuntimeBinding binding) {
        Optional<FrameSnapshot> latest = runtime.latestFrame();
        if (latest.isEmpty()) {
            return Optional.empty();
        }
        FrameSnapshot frame = latest.orElseThrow();
        Optional<RuntimeValue> value = frame.entity(EntityId.of(binding.entityId()))
                .flatMap(entity -> entity.property(binding.propertyId()));
        if (value.isEmpty()) {
            return Optional.empty();
        }
        Optional<Long> harnessFrame =
                resolveHarnessFrame(frame.frameId(), binding.correlationId());
        if (harnessFrame.isEmpty()) {
            return Optional.empty();
        }
        long provenFrame = harnessFrame.orElseThrow();
        RuntimeValue runtimeValue = value.orElseThrow();
        return Optional.of(new RuntimeObservation(
                binding.entityId(),
                binding.propertyId(),
                provenFrame,
                provenFrame,
                RuntimeValueRenderer.render(runtimeValue),
                RuntimeValueRenderer.formatId(runtimeValue)));
    }

    /**
     * Resolves the proven harness frame for a runtime frame: the recorded correlation whose token
     * matches the binding and whose runtime frame matches the value's frame, preferring the
     * greatest runtime frame id. Empty when no correlation proves the frame, or the correlation
     * carries no harness frame, or the harness frame is not a non-negative long.
     */
    private Optional<Long> resolveHarnessFrame(FrameId runtimeFrameId, String correlationId) {
        return runtime.uiCorrelations()
                .framesForUiSession(uiSessionId, CORRELATION_PAGE_SIZE)
                .items()
                .stream()
                .filter(correlation -> correlation.correlationToken()
                        .equals(Optional.ofNullable(correlationId)))
                .filter(correlation -> correlation.runtimeFrameId().equals(runtimeFrameId))
                .max(Comparator.comparing(UiFrameCorrelation::runtimeFrameId))
                .flatMap(AgentRuntimeObservationSource::parseHarnessFrame);
    }

    private static Optional<Long> parseHarnessFrame(UiFrameCorrelation correlation) {
        if (correlation.uiFrameId().isEmpty()) {
            return Optional.empty();
        }
        try {
            long harnessFrame = Long.parseLong(correlation.uiFrameId().orElseThrow());
            if (harnessFrame < 0) {
                return Optional.empty();
            }
            return Optional.of(harnessFrame);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
