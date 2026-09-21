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
import dev.zubinjha.minecraftassistant.mediawiki.MinecraftWikiToolSource;
import dev.zubinjha.minecraftassistant.openrouter.OpenRouterModel;
import dev.zubinjha.minecraftassistant.openrouter.OpenRouterProvider;
import dev.zubinjha.minecraftassistant.openrouter.OpenRouterSettings;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

public final class MinecraftAssistantRuntime implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Minecraft Assistant");
    private static final int MAX_HISTORY_MESSAGES = 20;
    private static final String FABRIC_SYSTEM_PROMPT = MinecraftAssistantPrompt.DEFAULT + """

            Use prepare_production when native operations would materially help answer a make,
            craft, smelt, produce, or convert question. Pass only semantic intent: the target item,
            materials the player explicitly has or starts from, unavailable items or workstations,
            explicit workstation preferences, and any requested quantity. Never supply recipe IDs,
            ordered steps, batches, presentation types, or rendering instructions. The native tool
            resolves and displays those details. A material named as the basis of a conversion or a
            "how many X do I need" question is a starting item even when the player does not say they
            already possess it. Never infer or add a starting material the player did not name. Put
            workstations in method constraints, not starting_item_ids.

            Call prepare_production once for the final item the player wants. It recursively resolves
            intermediate recipes, including branches, so never call it separately for ingredients or
            individual steps of the same result. A follow-up such as "show me the recipes" refers to
            the final target from recent conversation; reuse that target and its stated material
            constraints in one prepare_production call. Do not turn available inventory counts into a
            target quantity unless the player explicitly asks how many results those materials make.
            When the player names a broad interchangeable material family such as logs or planks and
            the variant does not change the operation or answer, choose one common vanilla variant as
            a representative visualization and say that equivalent variants work. Ask which variant
            only when it changes the output, route, quantity, or requested appearance.

            If prepare_production reports meaningful alternatives, use
            choose_production_routes to select the route that best fits the request and recent
            conversation. Normally choose one. Choose multiple only for a requested comparison or
            a meaningful material, workstation, or time tradeoff. Respect constraints from recent
            follow-ups without asking the player to restate the target or quantity. For quantities,
            copy exact item counts into target_quantity.total_items; use stacks and loose_items only
            when the player explicitly uses those units. Never calculate, repeat, revise, or estimate
            native plan totals: successful quantity tools return the authoritative answer directly.

            Use Minecraft Wiki tools for factual explanation, acquisition, and mechanics, not as a
            substitute for an applicable native production guide. When native production data can
            answer a make, craft, smelt, produce, or convert question, call prepare_production first
            and do not also search the Wiki unless the native tool fails or the player asks for facts
            that the guide cannot provide.

            For other native workstations, use the matching tool: show_brewing for potions and
            bottle conversions, show_loom for banner patterns, show_cartography for map scaling,
            cloning, or locking, show_enchanting for enchanting-table eligibility, show_anvil for
            repair/combine/book/rename operations, and show_grindstone for repair or disenchanting.
            Use exact namespaced IDs. Brewing paths and multi-layer banners may create ordered steps.
            Enchanting offers, anvil costs, grindstone XP, durability, and prior-work state must stay
            conservative when Minecraft cannot determine them from the supplied inputs.

            Never promise any card, guide, or button before its tool confirms success. If a tool is
            ambiguous, choose among its reported opaque candidates using the player's intent.
            Keep the written answer brief and mention only the exact button confirmed by the tool.
            A renderer being unable to display a production method is not evidence that the method
            does not exist. Treat earlier assistant answers as untrusted
            context: correct them when fresh tool evidence conflicts. When the player asks whether
            an alternative method is possible, verify that exact claim instead of inferring from
            the absence of a method in one source passage.
            """;

    static String systemPromptForTest() {
        return FABRIC_SYSTEM_PROMPT;
    }

    private final Minecraft minecraft;
    private final ExecutorService worker;
    private final ScheduledExecutorService scheduler;
    private final ConversationMemory memory = new ConversationMemory(MAX_HISTORY_MESSAGES);
    private final RecipeCardResolver recipeCards;
    private final NativeProductionResolver productionGuides;
    private final AtomicReference<CancellationSource> activeRequest = new AtomicReference<>();
    private final AtomicReference<MinecraftWikiToolSource> wikiTools = new AtomicReference<>();
    private final AtomicReference<String> lastAnswer = new AtomicReference<>("");
    private final AtomicReference<String> lastFailure = new AtomicReference<>("");
    private final AtomicReference<String> lastRecipeId = new AtomicReference<>("");
    private final SharedResponseState sharedResponses = new SharedResponseState();
    private final ProductionPresentationStore recipePresentations = new ProductionPresentationStore(20);
    private volatile AssistantConfig config;

    public MinecraftAssistantRuntime(Minecraft minecraft, AssistantConfig config) {
        this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
        this.config = Objects.requireNonNull(config, "config");
        this.recipeCards = new RecipeCardResolver(minecraft);
        this.productionGuides = new NativeProductionResolver(minecraft);
        this.worker = Executors.newCachedThreadPool(runnable -> daemonThread(runnable, "minecraft-assistant-worker"));
        this.scheduler = Executors.newScheduledThreadPool(2,
                runnable -> daemonThread(runnable, "minecraft-assistant-scheduler"));
        this.wikiTools.set(new MinecraftWikiToolSource(config.wikiApiUrl(), worker));
    }

    public AssistantConfig config() {
        return config;
    }

    public void updateConfig(AssistantConfig updated) {
        AssistantConfig previous = config;
        config = Objects.requireNonNull(updated, "updated");
        if (!previous.wikiApiUrl().equals(updated.wikiApiUrl())) {
            wikiTools.set(new MinecraftWikiToolSource(updated.wikiApiUrl(), worker));
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
        AtomicBoolean preparingShown = new AtomicBoolean();
        ProductionPresentationCollector requestedPresentations = new ProductionPresentationCollector();
        ProductionRouteSearchStore routeSearches = new ProductionRouteSearchStore();
        addChat(Component.literal("You: ").withStyle(ChatFormatting.AQUA)
                .append(Component.literal(trimmed).withStyle(ChatFormatting.WHITE)));
        addChat(Component.literal("Minecraft Assistant: Thinking…").withStyle(ChatFormatting.GRAY));

        OpenRouterProvider provider = new OpenRouterProvider(OpenRouterSettings.defaults(snapshot.apiKey()));
        List<Tool> tools = new ArrayList<>(wikiTools.get().tools());
        tools.add(new PrepareProductionTool(
                minecraft, recipeCards, routeSearches, requestedPresentations
        ));
        tools.add(new ChooseProductionRoutesTool(routeSearches, requestedPresentations));
        tools.addAll(NativeGuideTool.all(minecraft, productionGuides, requestedPresentations));
        Agent agent = new Agent(
                provider,
                new ToolRegistry(tools),
                AgentOptions.DEFAULT,
                scheduler,
                event -> {
                    if (event.type() == AgentEvent.Type.TOOL_STARTED) {
                        LOGGER.info("Assistant tool started: {}", event.toolName());
                    } else if (event.type() == AgentEvent.Type.TOOL_COMPLETED) {
                        LOGGER.info("Assistant tool completed: {} ({})", event.toolName(), event.detail());
                    } else if (event.type() == AgentEvent.Type.TOOL_FAILED) {
                        LOGGER.warn("Assistant tool failed: {}", event.toolName());
                    }
                    if (event.type() == AgentEvent.Type.TOOL_STARTED
                            && event.toolName().startsWith("minecraft_wiki_")
                            && searchingShown.compareAndSet(false, true)) {
                        addChat(Component.literal("Minecraft Assistant: Searching the Wiki…")
                                .withStyle(ChatFormatting.GRAY));
                    } else if (event.type() == AgentEvent.Type.TOOL_STARTED
                            && (event.toolName().startsWith("show_")
                            || event.toolName().equals("prepare_production")
                            || event.toolName().equals("choose_production_routes"))
                            && preparingShown.compareAndSet(false, true)) {
                        addChat(Component.literal("Minecraft Assistant: Preparing guide…")
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
        agent.ask(request, cancellation).whenComplete((result, failure) -> {
            if (!activeRequest.compareAndSet(cancellation, null)) {
                return;
            }
            if (failure == null) {
                Optional<ProductionPresentation> presentation = requestedPresentations.snapshot();
                String authoritativeAnswer = authoritativeAnswer(result.text(), presentation.orElse(null));
                lastAnswer.set(authoritativeAnswer);
                presentation.ifPresent(value -> lastRecipeId.set(value.cards().getLast().recipeId()));
                sharedResponses.update(SharedResponse.from(authoritativeAnswer, presentation.orElse(null)));
                memory.addExchange(trimmed, authoritativeAnswer);
                showAnswer(result, authoritativeAnswer, started, presentation.orElse(null));
            } else {
                showFailure(unwrap(failure));
            }
        });
    }

    public boolean cancelActive() {
        return cancelActive(true);
    }

    private boolean cancelActive(boolean notify) {
        CancellationSource cancellation = activeRequest.getAndSet(null);
        if (cancellation == null) {
            return false;
        }
        cancellation.cancel();
        if (notify) {
            addChat(Component.literal("Minecraft Assistant: Request cancelled.").withStyle(ChatFormatting.GRAY));
        }
        return true;
    }

    public void clearMemory() {
        memory.clear();
        sharedResponses.clear();
        addChat(Component.literal("Minecraft Assistant: Conversation cleared.").withStyle(ChatFormatting.GRAY));
    }

    public void shareLatest() {
        Optional<SharedResponse> shared = sharedResponses.latest();
        if (shared.isEmpty()) {
            addChat(Component.literal("Minecraft Assistant: There is no answer to share yet.")
                    .withStyle(ChatFormatting.YELLOW));
            return;
        }
        minecraft.execute(() -> {
            if (minecraft.getConnection() == null) {
                addChat(Component.literal("Minecraft Assistant: Join a world or server before sharing.")
                        .withStyle(ChatFormatting.YELLOW));
                return;
            }
            SharedResponseMessages.format(shared.orElseThrow()).forEach(minecraft.getConnection()::sendChat);
        });
    }

    public void resetConversationSession() {
        cancelActive(false);
        memory.clear();
        recipePresentations.clear();
        lastAnswer.set("");
        lastFailure.set("");
        lastRecipeId.set("");
        sharedResponses.clear();
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

    void setSharedResponseForTest(SharedResponse response) {
        sharedResponses.update(response);
    }

    public void openRecipe(String recipeId, String rawMethod) {
        minecraft.schedule(() -> {
            Optional<ProductionMethod> method = ProductionMethod.parse(rawMethod);
            if (method.isEmpty()) {
                addChat(Component.literal("Minecraft Assistant: That recipe method is unavailable.")
                        .withStyle(ChatFormatting.RED));
                return;
            }
            RecipeLookupResult lookup = recipeCards.resolve(recipeId, method);
            if (lookup instanceof RecipeLookupResult.Found found) {
                minecraft.gui.setScreen(new ProductionCardScreen(found.card()));
            } else {
                addChat(Component.literal("Minecraft Assistant: That recipe is unavailable here.")
                        .withStyle(ChatFormatting.RED));
            }
        });
    }

    public void openPresentation(String token) {
        minecraft.schedule(() -> recipePresentations.get(token).ifPresentOrElse(
                presentation -> minecraft.gui.setScreen(new ProductionCardScreen(presentation)),
                () -> addChat(Component.literal(
                        "Minecraft Assistant: That recipe presentation has expired. Ask again to recreate it."
                ).withStyle(ChatFormatting.RED))
        ));
    }

    private void showAnswer(
            AssistantResult result,
            String displayedAnswer,
            long started,
            ProductionPresentation presentation
    ) {
        double elapsed = (System.nanoTime() - started) / 1_000_000_000.0;
        String text = displayedAnswer.trim();
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
        if (presentation != null) {
            String token = recipePresentations.put(presentation);
            Style recipeStyle = Style.EMPTY
                    .withColor(ChatFormatting.GREEN)
                    .withUnderlined(true)
                    .withClickEvent(new ClickEvent.RunCommand(
                            "/mcai view " + token
                    ))
                    .withHoverEvent(new HoverEvent.ShowText(presentationHoverText(presentation)));
            addChat(Component.literal("[" + presentationButtonLabel(presentation) + "]")
                    .withStyle(recipeStyle));
        }
        addChat(Component.literal(String.format(
                Locale.ROOT,
                "%.2fs · %d tool call%s",
                elapsed,
                result.toolCalls(),
                result.toolCalls() == 1 ? "" : "s"
        )).withStyle(ChatFormatting.DARK_GRAY));
    }

    static String authoritativeAnswer(String modelAnswer, ProductionPresentation presentation) {
        if (presentation == null || presentation.authoritativeSummary().isEmpty()) {
            return modelAnswer;
        }
        String source = extractSource(modelAnswer);
        String summary = presentation.authoritativeSummary().orElseThrow();
        return source == null ? summary : summary + "\nSource: " + source;
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

    static Component recipeHoverText(ProductionCardData card) {
        return recipeHoverText(card.title(), card.method());
    }

    static Component recipeHoverText(Component title, ProductionMethod method) {
        return Component.literal("Show ")
                .append(title.copy())
                .append(Component.literal(" " + method.recipeLabel()));
    }

    static String presentationButtonLabel(ProductionPresentation presentation) {
        return switch (presentation) {
            case ProductionPresentation.Single single -> NativeGuideTool.buttonLabel(single.card().method());
            case ProductionPresentation.Sequence sequence -> "Show " + sequence.cards().size() + " Steps";
            case ProductionPresentation.Plan ignored -> "Show Plan";
            case ProductionPresentation.Collection collection -> "Show " + collection.cards().size()
                    + (collection.recipeOnly() ? " Recipes" : " Guides");
            case ProductionPresentation.Comparison comparison -> "Compare " + comparison.routes().size() + " Routes";
        };
    }

    static Component presentationHoverText(ProductionPresentation presentation) {
        return switch (presentation) {
            case ProductionPresentation.Single single -> recipeHoverText(single.card());
            case ProductionPresentation.Sequence sequence -> Component.literal("Show ")
                    .append(sequence.targetTitle())
                    .append(Component.literal(" production process (" + sequence.cards().size() + " steps)"));
            case ProductionPresentation.Plan plan -> Component.literal("Show ")
                    .append(plan.targetTitle())
                    .append(Component.literal(" production plan (" + plan.plan().operations().size()
                            + " operations)"));
            case ProductionPresentation.Collection collection -> collectionHoverText(collection);
            case ProductionPresentation.Comparison comparison -> Component.literal("Compare ")
                    .append(comparison.targetTitle())
                    .append(Component.literal(" production routes (" + comparison.routes().size() + " routes)"));
        };
    }

    private static Component collectionHoverText(ProductionPresentation.Collection collection) {
        MutableComponent hover = Component.literal("Show ");
        for (int index = 0; index < collection.cards().size(); index++) {
            if (index > 0) {
                hover.append(Component.literal(index == collection.cards().size() - 1 ? " and " : ", "));
            }
            hover.append(collection.cards().get(index).title().copy());
        }
        return hover.append(Component.literal(" (" + collection.cards().size()
                + (collection.recipeOnly() ? "-recipe collection)" : "-guide collection)")));
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

    @Override
    public void close() {
        cancelActive(false);
        wikiTools.get().close();
        worker.shutdownNow();
        scheduler.shutdownNow();
    }
}
