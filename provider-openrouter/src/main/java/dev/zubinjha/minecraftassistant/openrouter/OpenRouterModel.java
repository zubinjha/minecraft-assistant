package dev.zubinjha.minecraftassistant.openrouter;

import java.util.List;
import java.util.Objects;

public record OpenRouterModel(
        String id,
        String name,
        int contextLength,
        List<String> supportedParameters,
        String promptPrice,
        String completionPrice
) {
    public OpenRouterModel {
        id = requireText(id, "id");
        name = Objects.requireNonNullElse(name, id);
        contextLength = Math.max(0, contextLength);
        supportedParameters = List.copyOf(supportedParameters);
        promptPrice = Objects.requireNonNullElse(promptPrice, "");
        completionPrice = Objects.requireNonNullElse(completionPrice, "");
    }

    public boolean supportsTools() {
        return supportedParameters.contains("tools");
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
