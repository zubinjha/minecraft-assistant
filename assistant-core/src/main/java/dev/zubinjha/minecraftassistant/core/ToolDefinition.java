package dev.zubinjha.minecraftassistant.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;
import java.util.regex.Pattern;

public record ToolDefinition(String name, String description, JsonNode inputSchema) {
    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    public ToolDefinition {
        name = requireText(name, "name");
        description = requireText(description, "description");
        inputSchema = Objects.requireNonNull(inputSchema, "inputSchema");
        if (!VALID_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("invalid tool name: " + name);
        }
        if (!inputSchema.isObject()) {
            throw new IllegalArgumentException("inputSchema must be a JSON object");
        }
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
