package dev.zubinjha.minecraftassistant.openrouter;

import java.net.URI;
import java.util.Objects;

public record OpenRouterSettings(
        String apiKey,
        URI baseUri,
        String applicationName,
        URI applicationUri
) {
    public static final URI DEFAULT_BASE_URI = URI.create("https://openrouter.ai/api/v1/");

    public OpenRouterSettings {
        apiKey = requireText(apiKey, "apiKey");
        baseUri = normalizeBaseUri(baseUri);
        applicationName = requireText(applicationName, "applicationName");
        Objects.requireNonNull(applicationUri, "applicationUri");
        validateEndpoint(baseUri);
        if (!"https".equalsIgnoreCase(applicationUri.getScheme())) {
            throw new IllegalArgumentException("applicationUri must use HTTPS");
        }
    }

    public static OpenRouterSettings defaults(String apiKey) {
        return new OpenRouterSettings(
                apiKey,
                DEFAULT_BASE_URI,
                "Minecraft Assistant",
                URI.create("https://github.com/zubinjha/minecraft-assistant")
        );
    }

    URI chatCompletionsUri() {
        return baseUri.resolve("chat/completions");
    }

    URI modelsUri() {
        return baseUri.resolve("models");
    }

    private static URI normalizeBaseUri(URI value) {
        Objects.requireNonNull(value, "baseUri");
        String text = value.toString();
        return text.endsWith("/") ? value : URI.create(text + "/");
    }

    private static void validateEndpoint(URI uri) {
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if ("https".equalsIgnoreCase(scheme)) {
            return;
        }
        if ("http".equalsIgnoreCase(scheme)
                && ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host))) {
            return;
        }
        throw new IllegalArgumentException("OpenRouter endpoint must use HTTPS or loopback HTTP");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
