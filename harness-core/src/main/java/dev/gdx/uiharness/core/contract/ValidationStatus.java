package dev.gdx.uiharness.core.contract;

import java.util.List;
import java.util.Objects;

/** Current validation result and ordered user-facing messages. */
public record ValidationStatus(boolean valid, List<String> messages) {
    public ValidationStatus {
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        if (messages.size() > 64) {
            throw new IllegalArgumentException("validation messages exceeds 64 entries");
        }
        messages.forEach(message -> ContractSupport.text(message, "validation message"));
        if (valid && !messages.isEmpty()) {
            throw new IllegalArgumentException("valid status must not contain validation messages");
        }
    }
}
