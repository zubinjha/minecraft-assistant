package dev.zubinjha.minecraftassistant.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.zubinjha.minecraftassistant.core.AssistantException;
import dev.zubinjha.minecraftassistant.core.CancellationToken;
import dev.zubinjha.minecraftassistant.core.ErrorCode;
import dev.zubinjha.minecraftassistant.core.Tool;
import dev.zubinjha.minecraftassistant.core.ToolDefinition;
import dev.zubinjha.minecraftassistant.core.ToolExecutionResult;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Function;

final class McpTool implements Tool {
    private static final TypeReference<Map<String, Object>> ARGUMENTS_TYPE = new TypeReference<>() { };

    private final ToolDefinition definition;
    private final String endpoint;
    private final ObjectMapper json;
    private final Executor executor;
    private final Function<McpSchema.CallToolRequest, McpSchema.CallToolResult> caller;

    McpTool(
            McpSchema.Tool tool,
            String endpoint,
            ObjectMapper json,
            Executor executor,
            Function<McpSchema.CallToolRequest, McpSchema.CallToolResult> caller
    ) {
        Objects.requireNonNull(tool, "tool");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.json = Objects.requireNonNull(json, "json");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.caller = Objects.requireNonNull(caller, "caller");
        JsonNode inputSchema = json.valueToTree(tool.inputSchema());
        this.definition = new ToolDefinition(
                tool.name(),
                tool.description() == null || tool.description().isBlank()
                        ? "MCP tool " + tool.name()
                        : tool.description(),
                inputSchema
        );
    }

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public CompletionStage<ToolExecutionResult> execute(JsonNode arguments, CancellationToken cancellation) {
        return CompletableFuture.supplyAsync(() -> {
            cancellation.throwIfCancelled();
            Map<String, Object> argumentMap = json.convertValue(arguments, ARGUMENTS_TYPE);
            McpSchema.CallToolResult result;
            try {
                result = caller.apply(McpSchema.CallToolRequest.builder(definition.name())
                        .arguments(argumentMap)
                        .build());
            } catch (RuntimeException failure) {
                throw new AssistantException(
                        ErrorCode.TOOL_FAILURE,
                        "MCP tool call failed: " + definition.name(),
                        failure
                );
            }
            cancellation.throwIfCancelled();

            String content = renderResult(result);
            if (Boolean.TRUE.equals(result.isError())) {
                throw new AssistantException(
                        ErrorCode.TOOL_FAILURE,
                        "MCP tool reported an error: " + abbreviate(content, 300)
                );
            }
            return new ToolExecutionResult(content, Map.of(
                    "source", "mcp",
                    "endpoint", endpoint,
                    "tool", definition.name()
            ));
        }, executor);
    }

    private String renderResult(McpSchema.CallToolResult result) {
        List<String> parts = new ArrayList<>();
        if (result.content() != null) {
            for (McpSchema.Content item : result.content()) {
                if (item instanceof McpSchema.TextContent text) {
                    parts.add(text.text());
                } else if (item instanceof McpSchema.ResourceLink link) {
                    String label = link.title() != null ? link.title() : link.name();
                    parts.add(label + ": " + link.uri());
                } else if (item instanceof McpSchema.EmbeddedResource embedded
                        && embedded.resource() instanceof McpSchema.TextResourceContents textResource) {
                    parts.add(textResource.text());
                }
            }
        }
        if (parts.isEmpty() && result.structuredContent() != null) {
            try {
                parts.add(json.writeValueAsString(result.structuredContent()));
            } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
                throw new AssistantException(
                        ErrorCode.TOOL_FAILURE,
                        "MCP tool returned unreadable structured content",
                        failure
                );
            }
        }
        return parts.isEmpty() ? "MCP tool returned no content" : String.join("\n\n", parts);
    }

    private static String abbreviate(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit) + "…";
    }
}
