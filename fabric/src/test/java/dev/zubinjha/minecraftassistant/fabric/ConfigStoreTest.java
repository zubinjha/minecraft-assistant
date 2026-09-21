package dev.zubinjha.minecraftassistant.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ConfigStoreTest {
    @TempDir
    Path directory;

    @Test
    void newInstallUsesTheBenchmarkedDefaultModel() {
        assertEquals("openai/gpt-5.6-luna", AssistantConfig.defaults().model());
        assertEquals("low", AssistantConfig.defaults().reasoningEffort());
    }

    @Test
    void savesAndLoadsSettingsAndCredentials() throws Exception {
        ConfigStore store = new ConfigStore(directory);
        AssistantConfig expected = new AssistantConfig(
                "sk-or-test",
                "openai/gpt-5-mini",
                "low",
                "https://example.com/api.php"
        );

        store.save(expected);

        assertEquals(expected, store.load());
    }

    @Test
    void ignoresLegacyMcpEndpointAndUsesTheOfficialApiDefault() throws Exception {
        Files.writeString(directory.resolve("minecraft-assistant.json"), """
                {
                  "wikiEndpoint": "https://old.example/mcp"
                }
                """);

        AssistantConfig loaded = new ConfigStore(directory).load();

        assertEquals(
                dev.zubinjha.minecraftassistant.mediawiki.MinecraftWikiToolSource.DEFAULT_API_URL,
                loaded.wikiApiUrl()
        );
    }
}
