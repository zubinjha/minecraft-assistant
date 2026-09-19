package dev.zubinjha.minecraftassistant.fabric;

import dev.zubinjha.minecraftassistant.mcp.McpToolSource;
import java.util.Objects;

public record AssistantConfig(
        String apiKey,
        String model,
        String reasoningEffort,
        String wikiEndpoint
) {
    public static final String DEFAULT_MODEL = "openai/gpt-5-mini";

    public AssistantConfig {
        apiKey = Objects.requireNonNullElse(apiKey, "").trim();
        model = defaultIfBlank(model, DEFAULT_MODEL);
        reasoningEffort = defaultIfBlank(reasoningEffort, "low");
        wikiEndpoint = defaultIfBlank(wikiEndpoint, McpToolSource.DEFAULT_MINECRAFT_WIKI_ENDPOINT);
    }

    public static AssistantConfig defaults() {
        return new AssistantConfig("", DEFAULT_MODEL, "low", McpToolSource.DEFAULT_MINECRAFT_WIKI_ENDPOINT);
    }

    public boolean isConfigured() {
        return !apiKey.isBlank();
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
