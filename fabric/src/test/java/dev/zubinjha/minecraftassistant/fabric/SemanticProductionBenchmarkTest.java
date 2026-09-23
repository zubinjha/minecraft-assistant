package dev.zubinjha.minecraftassistant.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.zubinjha.minecraftassistant.core.Agent;
import dev.zubinjha.minecraftassistant.core.AgentEventListener;
import dev.zubinjha.minecraftassistant.core.AgentOptions;
import dev.zubinjha.minecraftassistant.core.AssistantRequest;
import dev.zubinjha.minecraftassistant.core.AssistantResult;
import dev.zubinjha.minecraftassistant.core.CancellationToken;
import dev.zubinjha.minecraftassistant.core.ConversationMessage;
import dev.zubinjha.minecraftassistant.core.LlmProvider;
import dev.zubinjha.minecraftassistant.core.ModelResponse;
import dev.zubinjha.minecraftassistant.core.Tool;
import dev.zubinjha.minecraftassistant.core.ToolDefinition;
import dev.zubinjha.minecraftassistant.core.ToolExecutionResult;
import dev.zubinjha.minecraftassistant.core.ToolRegistry;
import dev.zubinjha.minecraftassistant.openrouter.OpenRouterProvider;
import dev.zubinjha.minecraftassistant.openrouter.OpenRouterSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

final class SemanticProductionBenchmarkTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MODEL = "openai/gpt-6-luna";

    @Test
    @EnabledIfEnvironmentVariable(named = "OPENROUTER_API_KEY", matches = ".+")
    void lunaLowSelectsSemanticProductionToolsWithoutMinecraft() throws Exception {
        MeteredProvider provider = new MeteredProvider(new OpenRouterProvider(
                OpenRouterSettings.defaults(System.getenv("OPENROUTER_API_KEY"))
        ));
        List<Scenario> scenarios = List.of(
                new Scenario("recipe", "How do I craft a crafting table?", List.of(),
                        "minecraft:crafting_table", 1),
                new Scenario("process",
                        "I have oak logs and a furnace but no coal. How can I make torches?", List.of(),
                        "minecraft:torch", 1),
                new Scenario("process-follow-up", "But can you show me the crafting recipes?", List.of(
                        new ConversationMessage.User(
                                "I have a furnace but no coal and a stack of logs. Is there a way I can make torches?"
                        ),
                        new ConversationMessage.Assistant(
                                "Yes. Smelt a log using another log as fuel, craft sticks from planks, then combine "
                                        + "charcoal and a stick to make torches.", List.of()
                        )
                ), "minecraft:torch", 1),
                new Scenario("quantity", "How many oak logs do I need for 128 oak stairs?", List.of(),
                        "minecraft:oak_stairs", 1),
                new Scenario("comparison",
                        "Compare crafting and stonecutting routes from cobblestone for five stacks of stone brick slabs.",
                        List.of(),
                        "minecraft:stone_brick_slab", 2),
                new Scenario("follow-up", "I don't have a stonecutter.", List.of(
                        new ConversationMessage.User(
                                "How much cobblestone for five stacks of stone brick slabs?"
                        ),
                        new ConversationMessage.Assistant("A native plan is available.", List.of())
                ), "minecraft:stone_brick_slab", 1)
        );
        ArrayNode report = JSON.createArrayNode();

        for (Scenario scenario : scenarios) {
            RecordingSemanticTools tools = new RecordingSemanticTools();
            long startingCostMicros = provider.costMicros();
            long started = System.nanoTime();
            var scheduler = Executors.newSingleThreadScheduledExecutor();
            AssistantResult result;
            try {
                Agent agent = new Agent(
                        provider,
                        new ToolRegistry(List.of(tools.prepare(), tools.choose())),
                        AgentOptions.DEFAULT,
                        scheduler,
                        AgentEventListener.NONE
                );
                result = agent.ask(new AssistantRequest(
                        MODEL, "low", MinecraftAssistantRuntime.systemPromptForTest(),
                        scenario.history(), scenario.question()
                ), CancellationToken.NONE).toCompletableFuture().get(90, TimeUnit.SECONDS);
            } finally {
                scheduler.shutdownNow();
                scheduler.awaitTermination(2, TimeUnit.SECONDS);
            }
            double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
            long costMicros = provider.costMicros() - startingCostMicros;
            JsonNode prepare = tools.lastPrepare.get();
            System.out.println("Semantic benchmark " + scenario.name() + " prepare=" + prepare
                    + " choice=" + tools.lastChoice.get());
            assertEquals(scenario.expectedTarget(), prepare.path("target_item_id").asText(), scenario.name());
            assertTrue(!prepare.has("recipe_id") && !prepare.has("steps")
                    && !prepare.has("presentation_type"), scenario.name());
            assertEquals(scenario.expectedToolCalls(), result.toolCalls(), scenario.name());
            validateScenario(scenario.name(), prepare, tools.lastChoice.get());

            ObjectNode row = report.addObject();
            row.put("scenario", scenario.name());
            row.put("correct", true);
            row.put("tool_calls", result.toolCalls());
            row.put("provider_turns", result.providerTurns());
            row.put("latency_seconds", Math.round(seconds * 100.0) / 100.0);
            row.put("estimated_cost_usd", costMicros / 1_000_000.0);
        }

        Path output = Path.of("build", "reports", "semantic-production-benchmark.json");
        Files.createDirectories(output.getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        System.out.println("Semantic production Luna-low benchmark: " + report);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "OPENROUTER_API_KEY", matches = ".+")
    void lunaLowUsesOneRecursiveGuideForRepeatedRecipeFollowUps() throws Exception {
        MeteredProvider provider = new MeteredProvider(new OpenRouterProvider(
                OpenRouterSettings.defaults(System.getenv("OPENROUTER_API_KEY"))
        ));
        List<ConversationMessage> history = List.of(
                new ConversationMessage.User(
                        "I have a furnace but no coal and a stack of logs. Is there a way I can make torches?"
                ),
                new ConversationMessage.Assistant(
                        "Yes. Smelt a log using another log as fuel, craft sticks from planks, then combine "
                                + "charcoal and a stick to make torches.", List.of()
                )
        );

        for (int attempt = 1; attempt <= 5; attempt++) {
            RecordingSemanticTools tools = new RecordingSemanticTools();
            var scheduler = Executors.newSingleThreadScheduledExecutor();
            AssistantResult result;
            try {
                Agent agent = new Agent(
                        provider,
                        new ToolRegistry(List.of(tools.prepare(), tools.choose())),
                        AgentOptions.DEFAULT,
                        scheduler,
                        AgentEventListener.NONE
                );
                result = agent.ask(new AssistantRequest(
                        MODEL, "low", MinecraftAssistantRuntime.systemPromptForTest(), history,
                        "But can you show me the crafting recipes?"
                ), CancellationToken.NONE).toCompletableFuture().get(90, TimeUnit.SECONDS);
            } finally {
                scheduler.shutdownNow();
                scheduler.awaitTermination(2, TimeUnit.SECONDS);
            }

            JsonNode prepare = tools.lastPrepare.get();
            System.out.println("Semantic follow-up stress attempt " + attempt + " prepare=" + prepare);
            assertEquals(1, result.toolCalls(), "attempt " + attempt);
            assertEquals("minecraft:torch", prepare.path("target_item_id").asText(), "attempt " + attempt);
            assertTrue(containsSuffix(prepare.path("starting_item_ids"), "oak_log", "spruce_log", "birch_log",
                    "jungle_log", "acacia_log", "dark_oak_log", "mangrove_log", "cherry_log", "pale_oak_log"),
                    "attempt " + attempt);
            assertTrue(!prepare.has("target_quantity"), "attempt " + attempt);
        }
        System.out.println("Semantic follow-up stress estimated cost: $" + provider.costMicros() / 1_000_000.0);
    }

    private static void validateScenario(String name, JsonNode prepare, JsonNode choice) {
        switch (name) {
            case "recipe" -> assertTrue(prepare.path("starting_item_ids").isMissingNode()
                    || prepare.path("starting_item_ids").isEmpty());
            case "process", "process-follow-up" -> {
                assertTrue(containsSuffix(prepare.path("starting_item_ids"), "oak_log", "log"));
                assertTrue(containsSuffix(prepare.path("unavailable_item_ids"), "coal"));
                assertTrue(!containsSuffix(prepare.path("starting_item_ids"), "furnace"));
            }
            case "quantity" -> {
                assertTrue(containsSuffix(prepare.path("starting_item_ids"), "oak_log", "log"));
                assertEquals(128, prepare.path("target_quantity").path("total_items").asInt());
            }
            case "comparison" -> {
                assertEquals(5, prepare.path("target_quantity").path("stacks").asInt());
                assertTrue(containsSuffix(prepare.path("starting_item_ids"), "cobblestone"));
                assertTrue(choice != null && choice.path("route_ids").size() == 2);
            }
            case "follow-up" -> {
                JsonNode quantity = prepare.path("target_quantity");
                assertTrue(quantity.path("stacks").asInt() == 5
                        || quantity.path("total_items").asInt() == 320);
                assertTrue(containsSuffix(prepare.path("unavailable_methods"), "stonecutting"));
            }
            default -> throw new AssertionError("Unknown scenario " + name);
        }
    }

    private static boolean containsSuffix(JsonNode array, String... suffixes) {
        if (!array.isArray()) {
            return false;
        }
        for (JsonNode value : array) {
            String text = value.asText();
            for (String suffix : suffixes) {
                if (text.equals(suffix) || text.endsWith(":" + suffix)) {
                    return true;
                }
            }
        }
        return false;
    }

    private record Scenario(
            String name,
            String question,
            List<ConversationMessage> history,
            String expectedTarget,
            int expectedToolCalls
    ) {
    }

    private static final class MeteredProvider implements LlmProvider {
        private final LlmProvider delegate;
        private long costMicros;

        private MeteredProvider(LlmProvider delegate) {
            this.delegate = delegate;
        }

        @Override
        public synchronized CompletionStage<ModelResponse> generate(
                dev.zubinjha.minecraftassistant.core.ModelRequest request,
                CancellationToken cancellation
        ) {
            return delegate.generate(request, cancellation).thenApply(response -> {
                synchronized (this) {
                    double cost = response.providerState().path("cost").asDouble(0.0);
                    costMicros += Math.round(cost * 1_000_000.0);
                }
                return response;
            });
        }

        private synchronized long costMicros() {
            return costMicros;
        }
    }

    private static final class RecordingSemanticTools {
        private final AtomicReference<JsonNode> lastPrepare = new AtomicReference<>();
        private final AtomicReference<JsonNode> lastChoice = new AtomicReference<>();
        private final ToolDefinition prepareDefinition = new PrepareProductionTool(
                Runnable::run,
                (id, method) -> new RecipeLookupResult.Missing("benchmark"),
                new ProductionRouteSearchStore(),
                new ProductionPresentationCollector()
        ).definition();
        private final ToolDefinition chooseDefinition = new ChooseProductionRoutesTool(
                new ProductionRouteSearchStore(), new ProductionPresentationCollector()
        ).definition();

        private Tool prepare() {
            return new Tool() {
                @Override
                public ToolDefinition definition() {
                    return prepareDefinition;
                }

                @Override
                public CompletionStage<ToolExecutionResult> execute(
                        JsonNode arguments, CancellationToken cancellation
                ) {
                    lastPrepare.set(arguments.deepCopy());
                    String target = arguments.path("target_item_id").asText();
                    if (target.endsWith("stone_brick_slab")
                            && !containsSuffix(arguments.path("unavailable_methods"), "stonecutting")) {
                        return CompletableFuture.completedFuture(ToolExecutionResult.text("""
                                {"status":"choice_required","search_id":"s1","quantity_requested":true,
                                "routes":[
                                  {"route_id":"stonecutter","methods":["smelting","stonecutting"],
                                   "root_requirements":[{"item_id":"minecraft:cobblestone","count":160}]},
                                  {"route_id":"crafting","methods":["smelting","crafting"],
                                   "root_requirements":[{"item_id":"minecraft:cobblestone","count":164}]}
                                ],"instruction":"Choose relevant opaque route IDs."}
                                """));
                    }
                    if (arguments.has("target_quantity")) {
                        return CompletableFuture.completedFuture(ToolExecutionResult.terminal(
                                "The exact native plan is ready.", "Calculated native plan."
                        ));
                    }
                    return CompletableFuture.completedFuture(ToolExecutionResult.text(
                            target.endsWith("torch")
                                    ? "The ordered native production guide is ready with 4 steps."
                                    : "The native recipe guide is ready."
                    ));
                }
            };
        }

        private Tool choose() {
            return new Tool() {
                @Override
                public ToolDefinition definition() {
                    return chooseDefinition;
                }

                @Override
                public CompletionStage<ToolExecutionResult> execute(
                        JsonNode arguments, CancellationToken cancellation
                ) {
                    lastChoice.set(arguments.deepCopy());
                    return CompletableFuture.completedFuture(ToolExecutionResult.terminal(
                            "The selected route comparison is ready.", "Calculated route comparison."
                    ));
                }
            };
        }
    }
}
