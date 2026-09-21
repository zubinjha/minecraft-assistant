package dev.zubinjha.minecraftassistant.core;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record ToolExecutionResult(
        String content,
        Map<String, String> provenance,
        Optional<String> terminalAnswer
) {
    public ToolExecutionResult(String content, Map<String, String> provenance) {
        this(content, provenance, Optional.empty());
    }

    public ToolExecutionResult {
        content = Objects.requireNonNull(content, "content");
        provenance = Map.copyOf(provenance);
        terminalAnswer = Objects.requireNonNull(terminalAnswer, "terminalAnswer")
                .map(String::trim)
                .filter(answer -> !answer.isEmpty());
    }

    public static ToolExecutionResult text(String content) {
        return new ToolExecutionResult(content, Map.of());
    }

    public static ToolExecutionResult terminal(String content, String answer) {
        return new ToolExecutionResult(content, Map.of(), Optional.of(answer));
    }
}
