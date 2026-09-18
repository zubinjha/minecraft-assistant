package dev.zubinjha.minecraftassistant.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import java.util.List;
import java.util.Objects;

public record ModelResponse(
        String text,
        List<ToolCall> toolCalls,
        String finishReason,
        JsonNode providerState
) {
    public ModelResponse {
        text = Objects.requireNonNullElse(text, "");
        toolCalls = List.copyOf(toolCalls);
        finishReason = Objects.requireNonNullElse(finishReason, "unknown");
        providerState = Objects.requireNonNullElse(providerState, NullNode.getInstance());
    }

    public static ModelResponse text(String text) {
        return new ModelResponse(text, List.of(), "stop", NullNode.getInstance());
    }

    public static ModelResponse tools(List<ToolCall> calls) {
        return new ModelResponse("", calls, "tool_calls", NullNode.getInstance());
    }
}
