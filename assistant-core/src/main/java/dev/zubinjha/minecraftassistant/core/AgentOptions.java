package dev.zubinjha.minecraftassistant.core;

import java.time.Duration;
import java.util.Objects;

public record AgentOptions(
        int maxProviderTurns,
        int maxToolCalls,
        Duration providerTimeout,
        Duration toolTimeout,
        Duration totalTimeout,
        int maxToolResultChars
) {
    public static final AgentOptions DEFAULT = new AgentOptions(
            8,
            12,
            Duration.ofSeconds(60),
            Duration.ofSeconds(20),
            Duration.ofSeconds(120),
            65_536
    );

    public AgentOptions {
        if (maxProviderTurns < 1 || maxToolCalls < 0 || maxToolResultChars < 1) {
            throw new IllegalArgumentException("agent limits must be positive");
        }
        requirePositive(providerTimeout, "providerTimeout");
        requirePositive(toolTimeout, "toolTimeout");
        requirePositive(totalTimeout, "totalTimeout");
    }

    private static void requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
