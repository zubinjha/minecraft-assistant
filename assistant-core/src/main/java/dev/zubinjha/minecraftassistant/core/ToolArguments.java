package dev.zubinjha.minecraftassistant.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public final class ToolArguments {
    private ToolArguments() {
    }

    public static List<String> validate(JsonNode schema, JsonNode value) {
        List<String> errors = new ArrayList<>();
        validateAt("$", schema, value, errors);
        return List.copyOf(errors);
    }

    private static void validateAt(String path, JsonNode schema, JsonNode value, List<String> errors) {
        if (!schema.isObject()) {
            return;
        }

        JsonNode type = schema.get("type");
        if (type != null && type.isTextual() && !matchesType(type.textValue(), value)) {
            errors.add(path + " must be " + type.textValue());
            return;
        }

        if (value.isObject()) {
            JsonNode required = schema.get("required");
            if (required != null && required.isArray()) {
                for (JsonNode requiredName : required) {
                    if (requiredName.isTextual() && !value.has(requiredName.textValue())) {
                        errors.add(path + "." + requiredName.textValue() + " is required");
                    }
                }
            }

            JsonNode properties = schema.get("properties");
            if (properties != null && properties.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = properties.properties().iterator();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    if (value.has(field.getKey())) {
                        validateAt(path + "." + field.getKey(), field.getValue(), value.get(field.getKey()), errors);
                    }
                }
            }
        }

        if (value.isArray()) {
            JsonNode itemSchema = schema.get("items");
            if (itemSchema != null) {
                for (int index = 0; index < value.size(); index++) {
                    validateAt(path + "[" + index + "]", itemSchema, value.get(index), errors);
                }
            }
        }
    }

    private static boolean matchesType(String type, JsonNode value) {
        return switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "null" -> value.isNull();
            default -> true;
        };
    }
}
