package dev.zubinjha.minecraftassistant.core;

import java.util.List;
import java.util.Objects;

public record ModelRequest(
        String model,
        String reasoningEffort,
        List<ConversationMessage> messages,
        List<ToolDefinition> tools
) {
    public ModelRequest {
        model = requireText(model, "model");
        reasoningEffort = requireText(reasoningEffort, "reasoningEffort");
        messages = List.copyOf(messages);
        tools = List.copyOf(tools);
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
