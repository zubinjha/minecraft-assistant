package dev.zubinjha.minecraftassistant.fabric;

import dev.zubinjha.minecraftassistant.core.Agent;
import dev.zubinjha.minecraftassistant.core.AgentEvent;
import dev.zubinjha.minecraftassistant.core.AgentOptions;
import dev.zubinjha.minecraftassistant.core.AssistantException;
import dev.zubinjha.minecraftassistant.core.AssistantRequest;
import dev.zubinjha.minecraftassistant.core.AssistantResult;
import dev.zubinjha.minecraftassistant.core.CancellationSource;
import dev.zubinjha.minecraftassistant.core.CancellationToken;
import dev.zubinjha.minecraftassistant.core.ConversationMemory;
import dev.zubinjha.minecraftassistant.core.ErrorCode;
import dev.zubinjha.minecraftassistant.core.MinecraftAssistantPrompt;
import dev.zubinjha.minecraftassistant.core.Tool;
import dev.zubinjha.minecraftassistant.core.ToolRegistry;
import dev.zubinjha.minecraftassistant.mcp.McpToolSource;
import dev.zubinjha.minecraftassistant.openrouter.OpenRouterModel;
import dev.zubinjha.minecraftassistant.openrouter.OpenRouterProvider;
import dev.zubinjha.minecraftassistant.openrouter.OpenRouterSettings;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;

public final class MinecraftAssistantRuntime implements AutoCloseable {
    private static final int MAX_HISTORY_MESSAGES = 20;
    private static final String FABRIC_SYSTEM_PROMPT = MinecraftAssistantPrompt.DEFAULT + """

            When the player asks how to craft an item, call show_recipe once with the exact
            namespaced recipe ID. For standard recipes this is usually minecraft:<output_item>,
            such as minecraft:wooden_pickaxe. If the tool confirms a card is ready, keep the
            written answer brief and mention the Show Recipe button.
            """;

    private final Minecraft minecraft;
    private final ExecutorService worker;
    private final ScheduledExecutorService scheduler;
    private final ConversationMemory memory = new ConversationMemory(MAX_HISTORY_MESSAGES);
    private final RecipeCardResolver recipeCards;
    private final AtomicReference<CancellationSource> activeRequest = new AtomicReference<>();
    private final AtomicReference<CompletableFuture<McpToolSource>> wikiConnection = new AtomicReference<>();
    private final AtomicReference<String> lastAnswer = new AtomicReference<>("");
    private final AtomicReference<String> lastFailure = new AtomicReference<>("");
    private final AtomicReference<String> lastRecipeId = new AtomicReference<>("");
    private volatile AssistantConfig config;

    public MinecraftAssistantRuntime(Minecraft minecraft, AssistantConfig config) {
        this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
        this.config = Objects.requireNonNull(config, "config");
        this.recipeCards = new RecipeCardResolver(minecraft);
        this.worker = Executors.newCachedThreadPool(runnable -> daemonThread(runnable, "minecraft-assistant-worker"));
        this.scheduler = Executors.newScheduledThreadPool(2,
                runnable -> daemonThread(runnable, "minecraft-assistant-scheduler"));
    }

    public AssistantConfig config() {
        return config;
    }

    public void updateConfig(AssistantConfig updated) {
        AssistantConfig previous = config;
        config = Objects.requireNonNull(updated, "updated");
        if (!previous.wikiEndpoint().equals(updated.wikiEndpoint())) {
            closeWikiConnection();
        }
    }

    public void ask(String question) {
        String trimmed = question == null ? "" : question.trim();
        if (trimmed.isEmpty()) {
            addChat(Component.literal("Usage: /ask <question>").withStyle(ChatFormatting.YELLOW));
            return;
        }

        AssistantConfig snapshot = config;
        if (!snapshot.isConfigured()) {
            addChat(Component.literal("Minecraft Assistant needs an OpenRouter connection. Use /ask config.")
                    .withStyle(ChatFormatting.YELLOW));
            return;
        }

        CancellationSource cancellation = new CancellationSource();
        if (!activeRequest.compareAndSet(null, cancellation)) {
            addChat(Component.literal("An assistant request is already running. Use /ask stop first.")
                    .withStyle(ChatFormatting.YELLOW));
            return;
        }

        long started = System.nanoTime();
        lastAnswer.set("");
        lastFailure.set("");
        lastRecipeId.set("");
        AtomicBoolean searchingShown = new AtomicBoolean();
        AtomicReference<RecipeCardData> requestedCard = new AtomicReference<>();
        addChat(Component.literal("You: ").withStyle(ChatFormatting.AQUA)
                .append(Component.literal(trimmed).withStyle(ChatFormatting.WHITE)));
        addChat(Component.literal("Minecraft Assistant: Thinking…").withStyle(ChatFormatting.GRAY));

        wiki(snapshot, cancellation).thenCompose(wiki -> {
            OpenRouterProvider provider = new OpenRouterProvider(OpenRouterSettings.defaults(snapshot.apiKey()));
            List<Tool> tools = new ArrayList<>(wiki.tools());
            tools.add(new RecipeCardRequestTool(minecraft, recipeCards, requestedCard));
            Agent agent = new Agent(
                    provider,
                    new ToolRegistry(tools),
                    AgentOptions.DEFAULT,
                    scheduler,
                    event -> {
                        if (event.type() == AgentEvent.Type.TOOL_STARTED
                                && !event.toolName().equals("show_recipe")
                                && searchingShown.compareAndSet(false, true)) {
                            addChat(Component.literal("Minecraft Assistant: Searching the Wiki…")
                                    .withStyle(ChatFormatting.GRAY));
                        }
                    }
            );
            AssistantRequest request = new AssistantRequest(
                    snapshot.model(),
                    snapshot.reasoningEffort(),
                    FABRIC_SYSTEM_PROMPT,
                    memory.snapshot(),
                    trimmed
            );
            return agent.ask(request, cancellation);
        }).whenComplete((result, failure) -> {
            activeRequest.compareAndSet(cancellation, null);
            if (failure == null) {
                lastAnswer.set(result.text());
                RecipeCardData card = requestedCard.get();
                if (card != null) {
                    lastRecipeId.set(card.recipeId());
                }
                memory.addExchange(trimmed, result.text());
                showAnswer(result, started, card);
            } else {
                showFailure(unwrap(failure));
            }
        });
    }

    public boolean cancelActive() {
        CancellationSource cancellation = activeRequest.getAndSet(null);
        if (cancellation == null) {
            return false;
        }
        cancellation.cancel();
        addChat(Component.literal("Minecraft Assistant: Request cancelled.").withStyle(ChatFormatting.GRAY));
        return true;
    }

    public void clearMemory() {
        memory.clear();
        addChat(Component.literal("Minecraft Assistant: Conversation cleared.").withStyle(ChatFormatting.GRAY));
    }

    public CompletionStage<String> testConnection(AssistantConfig candidate) {
        if (!candidate.isConfigured()) {
            return CompletableFuture.failedFuture(new AssistantException(
                    ErrorCode.CONFIGURATION,
                    "Enter an OpenRouter API key first"
            ));
        }
        OpenRouterProvider provider = new OpenRouterProvider(OpenRouterSettings.defaults(candidate.apiKey()));
        return provider.listToolModels(CancellationToken.NONE).thenApply(models -> {
            OpenRouterModel selected = models.stream()
                    .filter(model -> model.id().equals(candidate.model()))
                    .findFirst()
                    .orElse(null);
            if (selected == null) {
                throw new AssistantException(
                        ErrorCode.CONFIGURATION,
                        "Connected, but that model is unavailable or lacks tool support"
                );
            }
            return "Connected to OpenRouter · " + models.size() + " tool-capable models";
        });
    }

    boolean requestFinishedForTest() {
        return activeRequest.get() == null && (!lastAnswer.get().isBlank() || !lastFailure.get().isBlank());
    }

    String lastAnswerForTest() {
        return lastAnswer.get();
    }

    String lastFailureForTest() {
        return lastFailure.get();
    }

    String lastRecipeIdForTest() {
        return lastRecipeId.get();
    }

    public void openRecipe(String recipeId) {
        minecraft.schedule(() -> recipeCards.resolve(recipeId).ifPresentOrElse(
                card -> minecraft.gui.setScreen(new RecipeCardScreen(card)),
                () -> addChat(Component.literal("Minecraft Assistant: That recipe is unavailable here.")
                        .withStyle(ChatFormatting.RED))
        ));
    }

    private CompletionStage<McpToolSource> wiki(AssistantConfig snapshot, CancellationToken cancellation) {
        CompletableFuture<McpToolSource> existing = wikiConnection.get();
        if (existing != null) {
            return existing;
        }
        CompletableFuture<McpToolSource> created = McpToolSource.connectMinecraftWiki(
                snapshot.wikiEndpoint(),
                worker,
                cancellation
        ).toCompletableFuture();
        if (!wikiConnection.compareAndSet(null, created)) {
            created.thenAccept(McpToolSource::close);
            return wikiConnection.get();
        }
        created.whenComplete((ignored, failure) -> {
            if (failure != null) {
                wikiConnection.compareAndSet(created, null);
            }
        });
        return created;
    }

    private void showAnswer(AssistantResult result, long started, RecipeCardData recipeCard) {
        double elapsed = (System.nanoTime() - started) / 1_000_000_000.0;
        String text = result.text().trim();
        String source = extractSource(text);
        String body = source == null ? text : text.substring(0, text.lastIndexOf("Source:")).trim();

        addChat(Component.literal("Assistant: ").withStyle(ChatFormatting.GREEN)
                .append(Component.literal(body).withStyle(ChatFormatting.WHITE)));
        if (source != null) {
            try {
                URI uri = URI.create(source);
                Style linkStyle = Style.EMPTY
                        .withColor(ChatFormatting.AQUA)
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent.OpenUrl(uri))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal(source)));
                addChat(Component.literal("[Minecraft Wiki source]").withStyle(linkStyle));
            } catch (IllegalArgumentException ignored) {
                // The answer remains useful if a provider emits a malformed source URL.
            }
        }
        if (recipeCard != null) {
            Style recipeStyle = Style.EMPTY
                    .withColor(ChatFormatting.GREEN)
                    .withUnderlined(true)
                    .withClickEvent(new ClickEvent.RunCommand(
                            "/mcai recipe " + recipeCard.recipeId()
                    ))
                    .withHoverEvent(new HoverEvent.ShowText(
                            Component.literal("Open the native recipe diagram")
                    ));
            addChat(Component.literal("[Show Recipe]").withStyle(recipeStyle));
        }
        addChat(Component.literal(String.format(
                Locale.ROOT,
                "%.2fs · %d tool call%s",
                elapsed,
                result.toolCalls(),
                result.toolCalls() == 1 ? "" : "s"
        )).withStyle(ChatFormatting.DARK_GRAY));
    }

    private void showFailure(Throwable failure) {
        if (failure instanceof AssistantException assistantFailure
                && assistantFailure.code() == ErrorCode.CANCELLED) {
            return;
        }
        String message = failure instanceof AssistantException && failure.getMessage() != null
                ? failure.getMessage()
                : "Unexpected assistant error";
        lastFailure.set(message);
        addChat(Component.literal("Minecraft Assistant: " + message).withStyle(ChatFormatting.RED));
    }

    private void addChat(Component message) {
        minecraft.execute(() -> minecraft.gui.hud.getChat().addClientSystemMessage(message));
    }

    private static String extractSource(String text) {
        int marker = text.lastIndexOf("Source:");
        if (marker < 0) {
            return null;
        }
        String source = text.substring(marker + "Source:".length()).trim();
        return source.startsWith("https://") || source.startsWith("http://") ? source : null;
    }

    private static Thread daemonThread(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private void closeWikiConnection() {
        CompletableFuture<McpToolSource> connection = wikiConnection.getAndSet(null);
        if (connection != null) {
            connection.thenAccept(McpToolSource::close);
        }
    }

    @Override
    public void close() {
        cancelActive();
        closeWikiConnection();
        worker.shutdownNow();
        scheduler.shutdownNow();
    }
}
