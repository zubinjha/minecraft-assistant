package dev.zubinjha.minecraftassistant.openrouter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.zubinjha.minecraftassistant.core.ConversationMessage;
import dev.zubinjha.minecraftassistant.core.ModelRequest;
import dev.zubinjha.minecraftassistant.core.ModelResponse;
import dev.zubinjha.minecraftassistant.core.ToolCall;
import dev.zubinjha.minecraftassistant.core.ToolDefinition;
import java.util.List;
import org.junit.jupiter.api.Test;

final class OpenRouterProtocolTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void encodesConversationAndTools() throws Exception {
        ObjectNode schema = json.createObjectNode().put("type", "object");
        schema.putObject("properties").putObject("query").put("type", "string");
        ObjectNode arguments = json.createObjectNode().put("query", "heart of the sea");
        ModelRequest request = new ModelRequest(
                "openai/gpt-5-mini",
                "low",
                List.of(
                        new ConversationMessage.System("Be concise"),
                        new ConversationMessage.User("Where is it?"),
                        new ConversationMessage.Assistant("", List.of(new ToolCall("call-1", "wiki_search", arguments))),
                        new ConversationMessage.ToolResult("call-1", "wiki_search", "Buried treasure", true)
                ),
                List.of(new ToolDefinition("wiki_search", "Search the Wiki", schema))
        );

        JsonNode encoded = OpenRouterProtocol.request(json, request);

        assertEquals("openai/gpt-5-mini", encoded.path("model").asText());
        assertEquals("low", encoded.path("reasoning_effort").asText());
        assertEquals(4, encoded.path("messages").size());
        assertEquals("call-1", encoded.path("messages").path(3).path("tool_call_id").asText());
        assertEquals("wiki_search", encoded.path("tools").path(0).path("function").path("name").asText());
        assertEquals("object", encoded.path("tools").path(0).path("function")
                .path("parameters").path("type").asText());
    }

    @Test
    void decodesTextAndToolCalls() throws Exception {
        JsonNode response = json.readTree("""
                {
                  "choices": [{
                    "finish_reason": "tool_calls",
                    "message": {
                      "content": null,
                      "tool_calls": [{
                        "id": "call-7",
                        "type": "function",
                        "function": {
                          "name": "wiki_search",
                          "arguments": "{\\"query\\":\\"mending\\"}"
                        }
                      }]
                    }
                  }],
                  "usage": {"total_tokens": 42}
                }
                """);

        ModelResponse decoded = OpenRouterProtocol.response(json, response);

        assertEquals("", decoded.text());
        assertEquals("tool_calls", decoded.finishReason());
        assertEquals(1, decoded.toolCalls().size());
        assertEquals("mending", decoded.toolCalls().get(0).arguments().path("query").asText());
        assertEquals(42, decoded.providerState().path("total_tokens").asInt());
    }

    @Test
    void discoversToolCapableModels() throws Exception {
        JsonNode response = json.readTree("""
                {
                  "data": [
                    {
                      "id": "vendor/tool-model",
                      "name": "Tool Model",
                      "context_length": 128000,
                      "supported_parameters": ["tools", "reasoning"],
                      "pricing": {"prompt": "0.000001", "completion": "0.000002"}
                    },
                    {
                      "id": "vendor/plain-model",
                      "name": "Plain Model",
                      "supported_parameters": []
                    }
                  ]
                }
                """);

        List<OpenRouterModel> models = OpenRouterProtocol.models(response);

        assertEquals(2, models.size());
        assertTrue(models.get(0).supportsTools());
        assertFalse(models.get(1).supportsTools());
        assertEquals(128000, models.get(0).contextLength());
    }
}
