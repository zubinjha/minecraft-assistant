package dev.zubinjha.minecraftassistant.openrouter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.zubinjha.minecraftassistant.core.AssistantException;
import dev.zubinjha.minecraftassistant.core.ConversationMessage;
import dev.zubinjha.minecraftassistant.core.ErrorCode;
import dev.zubinjha.minecraftassistant.core.ModelRequest;
import dev.zubinjha.minecraftassistant.core.ModelResponse;
import dev.zubinjha.minecraftassistant.core.ToolCall;
import dev.zubinjha.minecraftassistant.core.ToolDefinition;
import java.util.ArrayList;
import java.util.List;

final class OpenRouterProtocol {
    private OpenRouterProtocol() {
    }

    static ObjectNode request(ObjectMapper json, ModelRequest request) {
        ObjectNode root = json.createObjectNode();
        root.put("model", request.model());
        root.put("reasoning_effort", request.reasoningEffort());
        root.put("max_completion_tokens", 500);
        root.put("parallel_tool_calls", true);

        ArrayNode messages = root.putArray("messages");
        for (ConversationMessage message : request.messages()) {
            messages.add(encodeMessage(json, message));
        }

        if (!request.tools().isEmpty()) {
            root.put("tool_choice", "auto");
            ArrayNode tools = root.putArray("tools");
            for (ToolDefinition tool : request.tools()) {
                ObjectNode function = tools.addObject().put("type", "function").putObject("function");
                function.put("name", tool.name());
                function.put("description", tool.description());
                function.set("parameters", tool.inputSchema().deepCopy());
            }
        }
        return root;
    }

    private static ObjectNode encodeMessage(ObjectMapper json, ConversationMessage message) {
        ObjectNode encoded = json.createObjectNode();
        if (message instanceof ConversationMessage.System system) {
            return encoded.put("role", "system").put("content", system.text());
        }
        if (message instanceof ConversationMessage.User user) {
            return encoded.put("role", "user").put("content", user.text());
        }
        if (message instanceof ConversationMessage.Assistant assistant) {
            encoded.put("role", "assistant");
            if (assistant.text().isBlank()) {
                encoded.putNull("content");
            } else {
                encoded.put("content", assistant.text());
            }
            if (!assistant.toolCalls().isEmpty()) {
                ArrayNode calls = encoded.putArray("tool_calls");
                for (ToolCall call : assistant.toolCalls()) {
                    ObjectNode function = calls.addObject()
                            .put("id", call.callId())
                            .put("type", "function")
                            .putObject("function");
                    function.put("name", call.name());
                    function.put("arguments", compact(json, call.arguments()));
                }
            }
            return encoded;
        }
        ConversationMessage.ToolResult tool = (ConversationMessage.ToolResult) message;
        return encoded.put("role", "tool")
                .put("tool_call_id", tool.callId())
                .put("name", tool.toolName())
                .put("content", tool.content());
    }

    static ModelResponse response(ObjectMapper json, JsonNode root) {
        JsonNode choice = root.path("choices").path(0);
        JsonNode message = choice.path("message");
        if (!choice.isObject() || !message.isObject()) {
            throw protocolFailure("OpenRouter returned no completion choice");
        }

        String content = message.path("content").isTextual() ? message.path("content").textValue() : "";
        List<ToolCall> calls = new ArrayList<>();
        JsonNode toolCalls = message.path("tool_calls");
        if (toolCalls.isArray()) {
            for (JsonNode item : toolCalls) {
                String callId = requiredText(item, "id");
                JsonNode function = item.path("function");
                String name = requiredText(function, "name");
                String arguments = requiredText(function, "arguments");
                try {
                    calls.add(new ToolCall(callId, name, json.readTree(arguments)));
                } catch (JsonProcessingException failure) {
                    throw new AssistantException(
                            ErrorCode.PROTOCOL_ERROR,
                            "OpenRouter returned malformed tool arguments",
                            failure
                    );
                }
            }
        }

        String finishReason = choice.path("finish_reason").asText("unknown");
        JsonNode usage = root.has("usage") ? root.path("usage").deepCopy() : json.createObjectNode();
        return new ModelResponse(content, calls, finishReason, usage);
    }

    static List<OpenRouterModel> models(JsonNode root) {
        JsonNode data = root.path("data");
        if (!data.isArray()) {
            throw protocolFailure("OpenRouter returned an invalid model list");
        }
        List<OpenRouterModel> models = new ArrayList<>();
        for (JsonNode item : data) {
            String id = item.path("id").asText("");
            if (id.isBlank()) {
                continue;
            }
            List<String> parameters = new ArrayList<>();
            item.path("supported_parameters").forEach(value -> {
                if (value.isTextual()) {
                    parameters.add(value.textValue());
                }
            });
            JsonNode pricing = item.path("pricing");
            models.add(new OpenRouterModel(
                    id,
                    item.path("name").asText(id),
                    item.path("context_length").asInt(0),
                    parameters,
                    pricing.path("prompt").asText(""),
                    pricing.path("completion").asText("")
            ));
        }
        return List.copyOf(models);
    }

    private static String compact(ObjectMapper json, JsonNode value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new AssistantException(ErrorCode.PROTOCOL_ERROR, "Could not encode tool arguments", failure);
        }
    }

    private static String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText("");
        if (value.isBlank()) {
            throw protocolFailure("OpenRouter response is missing " + field);
        }
        return value;
    }

    private static AssistantException protocolFailure(String message) {
        return new AssistantException(ErrorCode.PROTOCOL_ERROR, message);
    }
}
