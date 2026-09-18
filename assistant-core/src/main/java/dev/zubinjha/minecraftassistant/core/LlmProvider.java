package dev.zubinjha.minecraftassistant.core;

import java.util.concurrent.CompletionStage;

public interface LlmProvider {
    CompletionStage<ModelResponse> generate(ModelRequest request, CancellationToken cancellation);
}
