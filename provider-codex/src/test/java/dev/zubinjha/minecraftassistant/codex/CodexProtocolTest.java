package dev.zubinjha.minecraftassistant.codex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.zubinjha.minecraftassistant.core.AssistantRequest;
import dev.zubinjha.minecraftassistant.core.ConversationMessage;
import dev.zubinjha.minecraftassistant.core.ToolDefinition;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

final class CodexProtocolTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void initializeOptsIntoExperimentalDynamicTools() {
        ObjectNode params = CodexProtocol.initialize(json);

        assertEquals("minecraft-assistant", params.path("clientInfo").path("name").asText());
        assertTrue(params.path("capabilities").path("experimentalApi").asBoolean());
    }

    @Test
    void threadStartIsEphemeralReadOnlyAndEncodesDynamicTools() {
        ObjectNode schema = json.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties").putObject("query").put("type", "string");
        schema.putArray("required").add("query");
        ToolDefinition tool = new ToolDefinition("wiki_search", "Search the Wiki", schema);
        AssistantRequest request = new AssistantRequest(
                "gpt-5.6-luna", "medium", "Minecraft system prompt", "Find a compass"
        );

        ObjectNode params = CodexProtocol.threadStart(
                json, request, Path.of("/tmp"), List.of(tool)
        );

        assertEquals("gpt-5.6-luna", params.path("model").asText());
        assertEquals("never", params.path("approvalPolicy").asText());
        assertEquals("read-only", params.path("sandbox").asText());
        assertTrue(params.path("ephemeral").asBoolean());
        assertFalse(params.path("allowProviderModelFallback").asBoolean());
        assertEquals("wiki_search", params.path("dynamicTools").get(0).path("name").asText());
        assertEquals("query", params.path("dynamicTools").get(0)
                .path("inputSchema").path("required").get(0).asText());
    }

    @Test
    void turnStartPreservesRequestedModelAndEffort() {
        AssistantRequest request = new AssistantRequest(
                "gpt-5.6-luna", "medium", "prompt", "question"
        );

        ObjectNode params = CodexProtocol.turnStart(json, "thread-1", request);

        assertEquals("thread-1", params.path("threadId").asText());
        assertEquals("gpt-5.6-luna", params.path("model").asText());
        assertEquals("medium", params.path("effort").asText());
        assertEquals("question", params.path("input").get(0).path("text").asText());
    }

    @Test
    void turnStartIncludesRecentConversationForFollowUps() {
        AssistantRequest request = new AssistantRequest(
                "gpt-5.6-luna",
                "medium",
                "prompt",
                List.of(
                        new ConversationMessage.User("How do I craft a recovery compass?"),
                        new ConversationMessage.Assistant("Use eight echo shards.", List.of())
                ),
                "What goes in the middle?"
        );

        String input = CodexProtocol.turnStart(json, "thread-1", request)
                .path("input").get(0).path("text").asText();

        assertTrue(input.contains("Player: How do I craft a recovery compass?"));
        assertTrue(input.contains("Assistant: Use eight echo shards."));
        assertTrue(input.endsWith("Current player question: What goes in the middle?"));
    }

    @Test
    void dynamicToolResponseUsesAppServerContentShape() {
        ObjectNode response = CodexProtocol.dynamicToolResponse(json, true, "tool output");

        assertTrue(response.path("success").asBoolean());
        assertEquals("inputText", response.path("contentItems").get(0).path("type").asText());
        assertEquals("tool output", response.path("contentItems").get(0).path("text").asText());
    }
}
