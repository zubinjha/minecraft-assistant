package dev.zubinjha.minecraftassistant.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ConfigStoreTest {
    @TempDir
    Path directory;

    @Test
    void savesAndLoadsSettingsAndCredentials() throws Exception {
        ConfigStore store = new ConfigStore(directory);
        AssistantConfig expected = new AssistantConfig(
                "sk-or-test",
                "openai/gpt-5-mini",
                "low",
                "https://example.com/mcp"
        );

        store.save(expected);

        assertEquals(expected, store.load());
    }
}
