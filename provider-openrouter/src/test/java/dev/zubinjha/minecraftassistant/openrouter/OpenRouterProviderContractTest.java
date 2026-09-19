package dev.zubinjha.minecraftassistant.openrouter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.zubinjha.minecraftassistant.core.Agent;
import dev.zubinjha.minecraftassistant.core.AgentEventListener;
import dev.zubinjha.minecraftassistant.core.AgentOptions;
import dev.zubinjha.minecraftassistant.core.AssistantRequest;
import dev.zubinjha.minecraftassistant.core.AssistantResult;
import dev.zubinjha.minecraftassistant.core.CancellationToken;
import dev.zubinjha.minecraftassistant.core.Tool;
import dev.zubinjha.minecraftassistant.core.ToolDefinition;
import dev.zubinjha.minecraftassistant.core.ToolExecutionResult;
import dev.zubinjha.minecraftassistant.core.ToolRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class OpenRouterProviderContractTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicBoolean toolResultReceived = new AtomicBoolean();
    private HttpServer server;
    private ScheduledExecutorService scheduler;
    private OpenRouterProvider provider;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/chat/completions", this::completeChat);
        server.createContext("/api/v1/models", this::listModels);
        server.start();
        scheduler = Executors.newSingleThreadScheduledExecutor();
        provider = new OpenRouterProvider(new OpenRouterSettings(
                "test-key",
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/"),
                "Test",
                URI.create("https://example.com")
        ));
    }

    @AfterEach
    void stopServer() {
        scheduler.shutdownNow();
        server.stop(0);
    }

    @Test
    void completesARealHttpToolLoop() {
        Agent agent = new Agent(
                provider,
                new ToolRegistry(List.of(new EchoTool())),
                AgentOptions.DEFAULT,
                scheduler,
                AgentEventListener.NONE
        );

        AssistantResult result = agent.ask(new AssistantRequest(
                "test/tool-model",
                "low",
                "Use the tool",
                "Echo hello"
        ), CancellationToken.NONE).toCompletableFuture().join();

        assertEquals("hello", result.text());
        assertEquals(1, result.toolCalls());
        assertTrue(toolResultReceived.get());
    }

    @Test
    void filtersModelDiscoveryToToolCapableModels() {
        List<OpenRouterModel> models = provider.listToolModels(CancellationToken.NONE)
                .toCompletableFuture().join();

        assertEquals(1, models.size());
        assertEquals("test/tool-model", models.get(0).id());
    }

    private void completeChat(HttpExchange exchange) throws IOException {
        assertEquals("Bearer test-key", exchange.getRequestHeaders().getFirst("Authorization"));
        JsonNode request = json.readTree(exchange.getRequestBody());
        boolean hasToolResult = false;
        for (JsonNode message : request.path("messages")) {
            if ("tool".equals(message.path("role").asText())) {
                hasToolResult = true;
                toolResultReceived.set(true);
            }
        }

        ObjectNode response = json.createObjectNode();
        ObjectNode choice = response.putArray("choices").addObject();
        ObjectNode message = choice.putObject("message");
        message.put("role", "assistant");
        if (hasToolResult) {
            choice.put("finish_reason", "stop");
            message.put("content", "hello");
        } else {
            choice.put("finish_reason", "tool_calls");
            message.putNull("content");
            ObjectNode function = message.putArray("tool_calls").addObject()
                    .put("id", "call-1")
                    .put("type", "function")
                    .putObject("function");
            function.put("name", "test_echo");
            function.put("arguments", "{\"value\":\"hello\"}");
        }
        sendJson(exchange, response);
    }

    private void listModels(HttpExchange exchange) throws IOException {
        ObjectNode response = json.createObjectNode();
        response.putArray("data").addObject()
                .put("id", "test/tool-model")
                .put("name", "Tool Model")
                .putArray("supported_parameters").add("tools");
        response.withArray("data").addObject()
                .put("id", "test/plain-model")
                .put("name", "Plain Model")
                .putArray("supported_parameters");
        sendJson(exchange, response);
    }

    private void sendJson(HttpExchange exchange, JsonNode body) throws IOException {
        byte[] bytes = json.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private final class EchoTool implements Tool {
        @Override
        public ToolDefinition definition() {
            ObjectNode schema = json.createObjectNode().put("type", "object");
            schema.putObject("properties").putObject("value").put("type", "string");
            schema.putArray("required").add("value");
            return new ToolDefinition("test_echo", "Echo a value", schema);
        }

        @Override
        public CompletableFuture<ToolExecutionResult> execute(
                JsonNode arguments,
                CancellationToken cancellation
        ) {
            return CompletableFuture.completedFuture(
                    ToolExecutionResult.text(arguments.path("value").asText())
            );
        }
    }
}
