package dev.zubinjha.minecraftassistant.core;

import java.util.Objects;

public record AgentEvent(Type type, int providerTurn, String toolName, String detail) {
    public AgentEvent {
        Objects.requireNonNull(type, "type");
        toolName = Objects.requireNonNullElse(toolName, "");
        detail = Objects.requireNonNullElse(detail, "");
    }

    public enum Type {
        REQUEST_STARTED,
        PROVIDER_STARTED,
        PROVIDER_COMPLETED,
        TOOL_STARTED,
        TOOL_COMPLETED,
        TOOL_FAILED,
        ANSWER_COMPLETED,
        REQUEST_FAILED
    }
}
