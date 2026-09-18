package dev.zubinjha.minecraftassistant.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.concurrent.CompletionStage;

public interface Tool {
    ToolDefinition definition();

    CompletionStage<ToolExecutionResult> execute(JsonNode arguments, CancellationToken cancellation);
}
