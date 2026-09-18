package dev.zubinjha.minecraftassistant.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

public record ToolCall(String callId, String name, JsonNode arguments) {
    public ToolCall {
        callId = requireText(callId, "callId");
        name = requireText(name, "name");
        arguments = Objects.requireNonNull(arguments, "arguments");
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
