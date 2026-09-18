package dev.zubinjha.minecraftassistant.core;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class ToolRegistry {
    private final Map<String, Tool> tools;

    public ToolRegistry(Collection<? extends Tool> tools) {
        Objects.requireNonNull(tools, "tools");
        Map<String, Tool> byName = new LinkedHashMap<>();
        for (Tool tool : tools) {
            Tool nonNullTool = Objects.requireNonNull(tool, "tool");
            String name = nonNullTool.definition().name();
            if (byName.putIfAbsent(name, nonNullTool) != null) {
                throw new IllegalArgumentException("duplicate tool name: " + name);
            }
        }
        this.tools = Map.copyOf(byName);
    }

    public static ToolRegistry empty() {
        return new ToolRegistry(List.of());
    }

    public Optional<Tool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public List<ToolDefinition> definitions() {
        return tools.values().stream().map(Tool::definition).toList();
    }

    public int size() {
        return tools.size();
    }
}
