package dev.zubinjha.minecraftassistant.codex;

import java.util.List;
import java.util.Objects;

public record CodexModel(
        String id,
        String displayName,
        List<String> supportedReasoningEfforts,
        String defaultReasoningEffort,
        boolean hidden,
        boolean defaultModel
) {
    public CodexModel {
        id = Objects.requireNonNull(id, "id");
        displayName = Objects.requireNonNullElse(displayName, id);
        supportedReasoningEfforts = List.copyOf(supportedReasoningEfforts);
        defaultReasoningEffort = Objects.requireNonNullElse(defaultReasoningEffort, "medium");
    }
}
