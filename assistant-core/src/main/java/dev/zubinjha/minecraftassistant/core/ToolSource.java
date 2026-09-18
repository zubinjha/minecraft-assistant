package dev.zubinjha.minecraftassistant.core;

import java.util.List;
import java.util.concurrent.CompletionStage;

public interface ToolSource extends AutoCloseable {
    CompletionStage<List<Tool>> loadTools(CancellationToken cancellation);

    @Override
    void close();
}
