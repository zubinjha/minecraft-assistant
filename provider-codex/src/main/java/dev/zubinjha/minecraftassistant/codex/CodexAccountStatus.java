package dev.zubinjha.minecraftassistant.codex;

import java.util.Objects;

public record CodexAccountStatus(
        boolean authenticated,
        String authenticationMode,
        String planType,
        boolean requiresOpenAiAuthentication
) {
    public CodexAccountStatus {
        authenticationMode = Objects.requireNonNullElse(authenticationMode, "none");
        planType = Objects.requireNonNullElse(planType, "unknown");
    }
}
