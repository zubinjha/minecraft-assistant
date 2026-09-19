package dev.zubinjha.minecraftassistant.fabric;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;

public final class ConfigStore {
    private static final Set<PosixFilePermission> OWNER_ONLY = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE
    );

    private final ObjectMapper json = new ObjectMapper();
    private final Path settingsPath;
    private final Path credentialsPath;

    public ConfigStore() {
        this(FabricLoader.getInstance().getConfigDir());
    }

    ConfigStore(Path configDirectory) {
        settingsPath = configDirectory.resolve("minecraft-assistant.json");
        credentialsPath = configDirectory.resolve("minecraft-assistant-credentials.json");
    }

    public AssistantConfig load() {
        AssistantConfig defaults = AssistantConfig.defaults();
        try {
            JsonNode settings = readObject(settingsPath);
            JsonNode credentials = readObject(credentialsPath);
            return new AssistantConfig(
                    credentials.path("openRouterApiKey").asText(defaults.apiKey()),
                    settings.path("model").asText(defaults.model()),
                    settings.path("reasoningEffort").asText(defaults.reasoningEffort()),
                    settings.path("wikiEndpoint").asText(defaults.wikiEndpoint())
            );
        } catch (IOException failure) {
            return defaults;
        }
    }

    public void save(AssistantConfig config) throws IOException {
        ObjectNode settings = json.createObjectNode();
        settings.put("model", config.model());
        settings.put("reasoningEffort", config.reasoningEffort());
        settings.put("wikiEndpoint", config.wikiEndpoint());
        writeAtomically(settingsPath, settings, false);

        ObjectNode credentials = json.createObjectNode();
        credentials.put("openRouterApiKey", config.apiKey());
        writeAtomically(credentialsPath, credentials, true);
    }

    private JsonNode readObject(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            return json.createObjectNode();
        }
        JsonNode value = json.readTree(path.toFile());
        return value != null && value.isObject() ? value : json.createObjectNode();
    }

    private void writeAtomically(Path path, JsonNode value, boolean restrictPermissions) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        json.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), value);
        if (restrictPermissions) {
            restrictToOwner(temporary);
        }
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
        if (restrictPermissions) {
            restrictToOwner(path);
        }
    }

    private static void restrictToOwner(Path path) {
        try {
            Files.setPosixFilePermissions(path, OWNER_ONLY);
        } catch (IOException | UnsupportedOperationException ignored) {
            // Windows and some filesystems do not expose POSIX permissions.
        }
    }
}
