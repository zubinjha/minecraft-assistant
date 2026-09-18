package dev.zubinjha.minecraftassistant.core;

import java.util.concurrent.CompletionStage;

public interface Assistant {
    CompletionStage<AssistantResult> ask(AssistantRequest request, CancellationToken cancellation);
}
