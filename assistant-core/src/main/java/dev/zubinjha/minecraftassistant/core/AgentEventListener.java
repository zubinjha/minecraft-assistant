package dev.zubinjha.minecraftassistant.core;

@FunctionalInterface
public interface AgentEventListener {
    AgentEventListener NONE = event -> { };

    void onEvent(AgentEvent event);
}
