package dev.zubinjha.minecraftassistant.codex;

import dev.zubinjha.minecraftassistant.core.AgentOptions;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

public record CodexAppServerSettings(
        List<String> command,
        Path workingDirectory,
        Duration startupTimeout,
        AgentOptions limits
) {
    public CodexAppServerSettings {
        command = List.copyOf(command);
        if (command.isEmpty() || command.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("command must not be empty or contain blank elements");
        }
        workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory")
                .toAbsolutePath()
                .normalize();
        startupTimeout = Objects.requireNonNull(startupTimeout, "startupTimeout");
        limits = Objects.requireNonNull(limits, "limits");
        if (startupTimeout.isZero() || startupTimeout.isNegative()) {
            throw new IllegalArgumentException("startupTimeout must be positive");
        }
    }

    public static CodexAppServerSettings defaults() {
        return new CodexAppServerSettings(
                List.of("codex", "app-server", "--stdio"),
                Path.of(System.getProperty("java.io.tmpdir")),
                Duration.ofSeconds(15),
                AgentOptions.DEFAULT
        );
    }
}
