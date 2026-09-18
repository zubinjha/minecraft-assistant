package dev.zubinjha.minecraftassistant.core;

import java.util.List;
import java.util.Objects;

public record AssistantRequest(
        String model,
        String reasoningEffort,
        String systemPrompt,
        List<ConversationMessage> history,
        String question
) {
    public AssistantRequest {
        model = requireText(model, "model");
        reasoningEffort = requireText(reasoningEffort, "reasoningEffort");
        systemPrompt = requireText(systemPrompt, "systemPrompt");
        history = List.copyOf(history);
        if (history.stream().anyMatch(message -> !(message instanceof ConversationMessage.User)
                && !(message instanceof ConversationMessage.Assistant))) {
            throw new IllegalArgumentException("history may contain only user and assistant messages");
        }
        question = requireText(question, "question");
    }

    public AssistantRequest(String model, String reasoningEffort, String systemPrompt, String question) {
        this(model, reasoningEffort, systemPrompt, List.of(), question);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
