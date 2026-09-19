package dev.zubinjha.minecraftassistant.openrouter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import org.junit.jupiter.api.Test;

final class OpenRouterSettingsTest {
    @Test
    void buildsDefaultApiUris() {
        OpenRouterSettings settings = OpenRouterSettings.defaults("secret");

        assertEquals(URI.create("https://openrouter.ai/api/v1/chat/completions"), settings.chatCompletionsUri());
        assertEquals(URI.create("https://openrouter.ai/api/v1/models"), settings.modelsUri());
    }

    @Test
    void permitsLoopbackHttpForTests() {
        OpenRouterSettings settings = new OpenRouterSettings(
                "secret",
                URI.create("http://127.0.0.1:1234/api/v1"),
                "Test",
                URI.create("https://example.com")
        );

        assertEquals(URI.create("http://127.0.0.1:1234/api/v1/models"), settings.modelsUri());
    }

    @Test
    void rejectsRemotePlainHttp() {
        assertThrows(IllegalArgumentException.class, () -> new OpenRouterSettings(
                "secret",
                URI.create("http://example.com/api/v1/"),
                "Test",
                URI.create("https://example.com")
        ));
    }
}
