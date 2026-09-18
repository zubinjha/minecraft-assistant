package dev.zubinjha.minecraftassistant.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.zubinjha.minecraftassistant.core.CancellationToken;
import dev.zubinjha.minecraftassistant.core.ToolExecutionResult;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class McpToolTest {
    private final ObjectMapper json = new ObjectMapper();
    private final Executor directExecutor = Runnable::run;

    @Test
    void mapsSchemaArgumentsAndTextResults() {
        McpSchema.Tool remote = McpSchema.Tool.builder("minecraft_wiki_search", Map.of(
                "type", "object",
                "properties", Map.of("query", Map.of("type", "string")),
                "required", List.of("query")
        )).description("Search the Minecraft Wiki").build();
        AtomicReference<McpSchema.CallToolRequest> captured = new AtomicReference<>();
        McpTool tool = new McpTool(
                remote,
                "https://example.com/mcp",
                json,
                directExecutor,
                request -> {
                    captured.set(request);
                    return new McpSchema.CallToolResult(
                            List.of(McpSchema.TextContent.builder("Recovery Compass page").build()),
                            false,
                            null,
                            Map.of()
                    );
                }
        );

        ToolExecutionResult result = tool.execute(
                json.createObjectNode().put("query", "recovery compass"),
                CancellationToken.NONE
        ).toCompletableFuture().join();

        assertEquals("recovery compass", captured.get().arguments().get("query"));
        assertEquals("Recovery Compass page", result.content());
        assertEquals("mcp", result.provenance().get("source"));
        assertTrue(tool.definition().inputSchema().path("required").isArray());
    }
}
