package dev.zubinjha.minecraftassistant.core;

import java.util.List;
import java.util.Objects;

public record AssistantResult(
        String text,
        List<ConversationMessage> conversation,
        int providerTurns,
        int toolCalls
) {
    public AssistantResult {
        Objects.requireNonNull(text, "text");
        conversation = List.copyOf(conversation);
        if (providerTurns < 0 || toolCalls < 0) {
            throw new IllegalArgumentException("turn and tool counts must be non-negative");
        }
    }
}
