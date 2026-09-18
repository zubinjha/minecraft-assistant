package dev.zubinjha.minecraftassistant.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.zubinjha.minecraftassistant.core.AssistantRequest;
import dev.zubinjha.minecraftassistant.core.ConversationMessage;
import dev.zubinjha.minecraftassistant.core.ToolDefinition;
import java.nio.file.Path;
import java.util.List;

final class CodexProtocol {
    private CodexProtocol() {
    }

    static ObjectNode initialize(ObjectMapper json) {
        ObjectNode params = json.createObjectNode();
        params.putObject("clientInfo")
                .put("name", "minecraft-assistant")
                .put("title", "Minecraft Assistant")
                .put("version", "0.1.0");
        params.putObject("capabilities").put("experimentalApi", true);
        return params;
    }

    static ObjectNode accountRead(ObjectMapper json) {
        return json.createObjectNode().put("refreshToken", false);
    }

    static ObjectNode modelList(ObjectMapper json) {
        return json.createObjectNode().put("limit", 100).put("includeHidden", true);
    }

    static ObjectNode threadStart(
            ObjectMapper json,
            AssistantRequest request,
            Path workingDirectory,
            List<ToolDefinition> tools
    ) {
        ObjectNode params = json.createObjectNode();
        params.put("model", request.model());
        params.put("cwd", workingDirectory.toString());
        params.put("approvalPolicy", "never");
        params.put("sandbox", "read-only");
        params.put("ephemeral", true);
        params.put("allowProviderModelFallback", false);
        params.put("baseInstructions", request.systemPrompt());
        params.put("developerInstructions", """
                Answer only the user's Minecraft question. Do not inspect the filesystem, run shell
                commands, edit files, browse the web, or use built-in Codex tools. Use only the
                application-provided dynamic tools when factual retrieval is needed.
                """);
        ArrayNode dynamicTools = params.putArray("dynamicTools");
        for (ToolDefinition tool : tools) {
            ObjectNode encoded = dynamicTools.addObject();
            encoded.put("type", "function");
            encoded.put("name", tool.name());
            encoded.put("description", tool.description());
            encoded.set("inputSchema", tool.inputSchema().deepCopy());
        }
        return params;
    }

    static ObjectNode turnStart(ObjectMapper json, String threadId, AssistantRequest request) {
        ObjectNode params = json.createObjectNode();
        params.put("threadId", threadId);
        params.put("model", request.model());
        params.put("effort", request.reasoningEffort());
        params.put("summary", "concise");
        params.putArray("input").addObject()
                .put("type", "text")
                .put("text", conversationInput(request));
        return params;
    }

    private static String conversationInput(AssistantRequest request) {
        if (request.history().isEmpty()) {
            return request.question();
        }
        StringBuilder input = new StringBuilder("Recent conversation for follow-up context:\n");
        for (ConversationMessage message : request.history()) {
            if (message instanceof ConversationMessage.User user) {
                input.append("Player: ").append(user.text()).append('\n');
            } else if (message instanceof ConversationMessage.Assistant assistant) {
                input.append("Assistant: ").append(assistant.text()).append('\n');
            }
        }
        input.append("Current player question: ").append(request.question());
        return input.toString();
    }

    static ObjectNode interrupt(ObjectMapper json, String threadId, String turnId) {
        return json.createObjectNode().put("threadId", threadId).put("turnId", turnId);
    }

    static ObjectNode dynamicToolResponse(ObjectMapper json, boolean success, String content) {
        ObjectNode result = json.createObjectNode();
        result.put("success", success);
        result.putArray("contentItems").addObject()
                .put("type", "inputText")
                .put("text", content);
        return result;
    }

    static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.textValue() : fallback;
    }
}
