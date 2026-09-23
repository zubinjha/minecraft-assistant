package dev.zubinjha.minecraftassistant.fabric;

import dev.zubinjha.minecraftassistant.mediawiki.MinecraftWikiToolSource;
import java.util.Objects;

public record AssistantConfig(
        String apiKey,
        String model,
        String reasoningEffort,
        String wikiApiUrl
) {
    public static final String DEFAULT_MODEL = "openai/gpt-6-luna";

    public AssistantConfig {
        apiKey = Objects.requireNonNullElse(apiKey, "").trim();
        model = defaultIfBlank(model, DEFAULT_MODEL);
        reasoningEffort = defaultIfBlank(reasoningEffort, "low");
        wikiApiUrl = defaultIfBlank(wikiApiUrl, MinecraftWikiToolSource.DEFAULT_API_URL);
    }

    public static AssistantConfig defaults() {
        return new AssistantConfig("", DEFAULT_MODEL, "low", MinecraftWikiToolSource.DEFAULT_API_URL);
    }

    public boolean isConfigured() {
        return !apiKey.isBlank();
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
