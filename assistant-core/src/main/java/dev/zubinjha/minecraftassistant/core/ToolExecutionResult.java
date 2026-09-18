package dev.zubinjha.minecraftassistant.core;

import java.util.Map;
import java.util.Objects;

public record ToolExecutionResult(String content, Map<String, String> provenance) {
    public ToolExecutionResult {
        content = Objects.requireNonNull(content, "content");
        provenance = Map.copyOf(provenance);
    }

    public static ToolExecutionResult text(String content) {
        return new ToolExecutionResult(content, Map.of());
    }
}
