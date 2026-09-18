package dev.zubinjha.minecraftassistant.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.zubinjha.minecraftassistant.core.AssistantException;
import dev.zubinjha.minecraftassistant.core.CancellationToken;
import dev.zubinjha.minecraftassistant.core.ErrorCode;
import dev.zubinjha.minecraftassistant.core.Tool;
import dev.zubinjha.minecraftassistant.core.ToolSource;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

public final class McpToolSource implements ToolSource {
    public static final String DEFAULT_MINECRAFT_WIKI_ENDPOINT =
            "https://minecraft-wiki-mcp.goett.top/mcp";

    private final McpSyncClient client;
    private final List<Tool> tools;
    private final AtomicBoolean closed = new AtomicBoolean();

    private McpToolSource(McpSyncClient client, List<Tool> tools) {
        this.client = client;
        this.tools = List.copyOf(tools);
    }

    public static CompletionStage<McpToolSource> connect(
            String endpointValue,
            Duration connectTimeout,
            Duration requestTimeout,
            int maxResponseBytes,
            Executor executor,
            CancellationToken cancellation
    ) {
        Objects.requireNonNull(connectTimeout, "connectTimeout");
        Objects.requireNonNull(requestTimeout, "requestTimeout");
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(cancellation, "cancellation");
        if (maxResponseBytes < 1) {
            throw new IllegalArgumentException("maxResponseBytes must be positive");
        }
        McpEndpoint endpoint = McpEndpoint.parse(endpointValue);

        return CompletableFuture.supplyAsync(() -> {
            cancellation.throwIfCancelled();
            HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                    .builder(endpoint.baseUrl())
                    .endpoint(endpoint.path())
                    .connectTimeout(connectTimeout)
                    .maxResponseSize(maxResponseBytes)
                    .build();
            McpSyncClient client = McpClient.sync(transport)
                    .clientInfo(McpSchema.Implementation.builder("minecraft-assistant", "0.1.0").build())
                    .initializationTimeout(connectTimeout)
                    .requestTimeout(requestTimeout)
                    .build();
            try {
                client.initialize();
                cancellation.throwIfCancelled();
                List<McpSchema.Tool> remoteTools = listAllTools(client);
                ObjectMapper json = new ObjectMapper();
                List<Tool> mapped = remoteTools.stream()
                        .map(tool -> (Tool) new McpTool(
                                tool,
                                endpoint.uri().toString(),
                                json,
                                executor,
                                client::callTool
                        ))
                        .toList();
                return new McpToolSource(client, mapped);
            } catch (RuntimeException failure) {
                client.close();
                if (failure instanceof AssistantException) {
                    throw failure;
                }
                throw new AssistantException(
                        ErrorCode.TOOL_FAILURE,
                        "Could not connect to the MCP endpoint",
                        failure
                );
            }
        }, executor);
    }

    public static CompletionStage<McpToolSource> connectMinecraftWiki(
            String endpoint,
            Executor executor,
            CancellationToken cancellation
    ) {
        return connect(
                endpoint,
                Duration.ofSeconds(10),
                Duration.ofSeconds(20),
                2 * 1024 * 1024,
                executor,
                cancellation
        );
    }

    @Override
    public CompletionStage<List<Tool>> loadTools(CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        if (closed.get()) {
            return CompletableFuture.failedFuture(new AssistantException(
                    ErrorCode.TOOL_FAILURE,
                    "MCP tool source is closed"
            ));
        }
        return CompletableFuture.completedFuture(tools);
    }

    public List<Tool> tools() {
        return tools;
    }

    private static List<McpSchema.Tool> listAllTools(McpSyncClient client) {
        List<McpSchema.Tool> allTools = new ArrayList<>();
        String cursor = null;
        do {
            McpSchema.ListToolsResult page = cursor == null ? client.listTools() : client.listTools(cursor);
            allTools.addAll(page.tools());
            cursor = page.nextCursor();
        } while (cursor != null);
        return List.copyOf(allTools);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            client.closeGracefully();
        }
    }
}
