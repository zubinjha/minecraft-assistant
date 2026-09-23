package dev.zubinjha.minecraftassistant.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.zubinjha.minecraftassistant.core.Agent;
import dev.zubinjha.minecraftassistant.core.AgentEventListener;
import dev.zubinjha.minecraftassistant.core.AgentOptions;
import dev.zubinjha.minecraftassistant.core.AssistantRequest;
import dev.zubinjha.minecraftassistant.core.CancellationToken;
import dev.zubinjha.minecraftassistant.core.LlmProvider;
import dev.zubinjha.minecraftassistant.core.ModelRequest;
import dev.zubinjha.minecraftassistant.core.ModelResponse;
import dev.zubinjha.minecraftassistant.core.ToolCall;
import dev.zubinjha.minecraftassistant.openrouter.OpenRouterModel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ModelBenchmarkTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    java.nio.file.Path directory;

    @Test
    void suiteHasTwentyUniqueFivePointCasesAndReasoningVariants() throws Exception {
        BenchmarkSuite suite = BenchmarkSuite.load(BenchmarkSuite.DEFAULT_PATH);

        assertEquals(20, suite.cases().size());
        assertEquals(100, suite.cases().size() * 5);
        assertEquals(20, new HashSet<>(suite.cases().stream().map(BenchmarkSuite.Case::id).toList()).size());
        assertEquals(17, suite.models().size());
        assertEquals(17, new HashSet<>(suite.models().stream().map(BenchmarkSuite.Model::key).toList()).size());
        assertTrue(suite.models().stream().allMatch(model -> Set.of("low", "high")
                .contains(model.reasoningEffort())));
        assertTrue(suite.models().stream().anyMatch(model -> model.key().equals("openai/gpt-6-luna@high")));
        assertTrue(suite.models().stream().anyMatch(model -> model.key().equals("openai/gpt-6-sol@high")));
        assertTrue(suite.models().stream().anyMatch(model -> model.key().equals("openai/gpt-5.6-luna@high")));
        assertTrue(suite.models().stream().anyMatch(model -> model.key().equals("openai/gpt-5.6-sol@high")));
        assertTrue(suite.models().stream().anyMatch(model -> model.key().equals("anthropic/claude-opus-5@high")));
        assertTrue(suite.models().stream().anyMatch(model -> model.key().equals("anthropic/claude-haiku-4.5@low")));
        assertTrue(suite.models().stream().anyMatch(model -> model.key().equals("anthropic/claude-haiku-4.5@high")));
        assertTrue(Files.readString(BenchmarkSuite.DEFAULT_PATH).contains("openai/gpt-6-luna"));
    }

    @Test
    void fixtureRegistryUsesTheCompleteProductionToolSurface() {
        Set<String> names = new HashSet<>(BenchmarkTools.productionDefinitionsList().stream()
                .map(definition -> definition.name()).toList());

        assertEquals(Set.of(
                "minecraft_wiki_search", "minecraft_wiki_get_page", "minecraft_wiki_get_section",
                "minecraft_wiki_get_categories", "minecraft_wiki_get_category_members",
                "minecraft_wiki_resolve_redirect", "prepare_production", "choose_production_routes",
                "show_brewing", "show_loom", "show_cartography", "show_enchanting", "show_anvil",
                "show_grindstone"
        ), names);
        assertTrue(BenchmarkTools.productionDefinitionsList().stream()
                .allMatch(definition -> definition.inputSchema().path("type").asText().equals("object")));
    }

    @Test
    void followUpCasesCarrySeededConversationHistory() throws Exception {
        BenchmarkSuite suite = BenchmarkSuite.load(BenchmarkSuite.DEFAULT_PATH);

        assertEquals(2, suite.caseById("followup-no-stonecutter").conversationHistory().size());
        assertEquals(2, suite.caseById("followup-show-torches").conversationHistory().size());
        assertEquals(2, suite.caseById("followup-map-crafting").conversationHistory().size());
        assertTrue(suite.cases().stream().filter(value -> !value.id().startsWith("followup-"))
                .allMatch(value -> value.conversationHistory().isEmpty()));
    }

    @Test
    void fixtureRoutesQuantityAndComparisonResultsDeterministically() throws Exception {
        BenchmarkSuite suite = BenchmarkSuite.load(BenchmarkSuite.DEFAULT_PATH);
        BenchmarkTools stairs = new BenchmarkTools(suite.caseById("quantity-stairs"));
        ObjectNode stairArgs = JSON.createObjectNode().put("target_item_id", "minecraft:oak_stairs");
        stairArgs.putObject("target_quantity").put("total_items", 128);

        var stairResult = stairs.registry().find("prepare_production").orElseThrow()
                .execute(stairArgs, CancellationToken.NONE).toCompletableFuture().get(1, TimeUnit.SECONDS);

        assertEquals("Calculated: 48 Oak Logs → 128 Oak Stairs via Crafting.",
                stairResult.terminalAnswer().orElseThrow());

        BenchmarkTools slabs = new BenchmarkTools(suite.caseById("comparison-slabs"));
        ObjectNode chooseArgs = JSON.createObjectNode().put("search_id", "slab-routes");
        chooseArgs.putArray("route_ids").add("stonecutter").add("crafting");
        var slabResult = slabs.registry().find("choose_production_routes").orElseThrow()
                .execute(chooseArgs, CancellationToken.NONE).toCompletableFuture().get(1, TimeUnit.SECONDS);

        assertTrue(slabResult.terminalAnswer().orElseThrow().contains("160 Cobblestone"));
    }

    @Test
    void catalogValidationRequiresTheExactModelWithToolsAndReasoning() throws Exception {
        BenchmarkSuite.Model requested = BenchmarkSuite.load(BenchmarkSuite.DEFAULT_PATH).models().getFirst();
        OpenRouterModel compatible = new OpenRouterModel(
                requested.id(), "Compatible", 128_000, List.of("tools", "reasoning_effort"), "0.1", "0.2"
        );
        OpenRouterModel missingReasoning = new OpenRouterModel(
                requested.id(), "Missing reasoning", 128_000, List.of("tools"), "0.1", "0.2"
        );
        OpenRouterModel genericReasoning = new OpenRouterModel(
                requested.id(), "Generic reasoning", 128_000, List.of("tools", "reasoning"), "0.1", "0.2"
        );

        assertDoesNotThrow(() -> ModelBenchmarkMain.validateModels(List.of(requested), List.of(compatible)));
        assertDoesNotThrow(() -> ModelBenchmarkMain.validateModels(List.of(requested), List.of(genericReasoning)));
        assertThrows(IllegalStateException.class,
                () -> ModelBenchmarkMain.validateModels(List.of(requested), List.of(missingReasoning)));
        assertThrows(IllegalStateException.class,
                () -> ModelBenchmarkMain.validateModels(List.of(requested), List.of()));
    }

    @Test
    void reviewIsAnonymousAndRawMetadataCannotContainCredentials() throws Exception {
        BenchmarkSuite suite = BenchmarkSuite.load(BenchmarkSuite.DEFAULT_PATH);
        BenchmarkSuite.Model model = suite.models().getFirst();
        BenchmarkSuite.Case scenario = suite.cases().getFirst();
        ObjectNode raw = ModelBenchmarkMain.newRawReport(suite, List.of(model), List.of(scenario));
        raw.withArray("runs").addObject().put("model_id", model.id()).put("case_id", scenario.id())
                .put("status", "completed").put("answer", "fixture").putArray("tool_invocations");

        ModelBenchmarkMain.createReview(directory, suite, raw);

        String review = Files.readString(directory.resolve("review.json"), StandardCharsets.UTF_8);
        assertFalse(review.contains(model.id()));
        assertFalse(review.contains("sk-test-secret"));
        assertTrue(Files.readString(directory.resolve("candidate-map.json")).contains(model.id()));
        assertFalse(raw.toString().contains("sk-test-secret"));
    }

    @Test
    void scoredReportAggregatesCostAndFormatsPromptsPerDollar() throws Exception {
        BenchmarkSuite suite = BenchmarkSuite.load(BenchmarkSuite.DEFAULT_PATH);
        BenchmarkSuite.Model model = suite.models().getFirst();
        ObjectNode raw = ModelBenchmarkMain.newRawReport(suite, List.of(model), suite.cases());
        for (BenchmarkSuite.Case scenario : suite.cases()) {
            raw.withArray("runs").addObject().put("model_id", model.id()).put("case_id", scenario.id())
                    .put("status", "completed").put("answer", "fixture")
                    .put("cost_usd", 0.001).put("latency_seconds", 2.0).putArray("tool_invocations");
        }
        JSON.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("raw-results.json").toFile(), raw);
        JSON.writerWithDefaultPrettyPrinter().writeValue(
                directory.resolve("candidate-map.json").toFile(), JSON.createObjectNode().set("A",
                        JSON.createObjectNode().put("model_id", model.id())
                                .put("reasoning_effort", model.reasoningEffort()))
        );
        ObjectNode review = JSON.createObjectNode();
        ArrayNode cases = review.putArray("candidates").addObject().put("label", "A").putArray("cases");
        for (BenchmarkSuite.Case scenario : suite.cases()) {
            cases.addObject().put("case_id", scenario.id()).put("correctness", 2)
                    .put("tool_context", 2).put("chat_quality", 1).put("notes", "");
        }
        JSON.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("review.json").toFile(), review);

        ModelBenchmarkMain.report(directory);

        JsonNode summary = JSON.readTree(directory.resolve("summary.json").toFile());
        assertEquals(100, summary.path("models").get(0).path("score").asInt());
        assertEquals(0.02, summary.path("models").get(0).path("cost_usd").asDouble(), 0.0000001);
        assertEquals(1000.0, summary.path("models").get(0).path("prompts_per_dollar").asDouble());
        assertEquals(model.id(), summary.path("recommended_model").asText());
        assertEquals(model.id(), summary.path("best_quality_model").asText());
        assertEquals("3,700", ModelBenchmarkMain.formatTwoSignificant(3685.03));
        assertTrue(Files.readString(directory.resolve("summary.md")).contains("| Avg. latency |"));
        assertTrue(Files.readString(directory.resolve("summary.md"))
                .contains("[GPT-6 Luna](https://openrouter.ai/openai/gpt-6-luna)"));
        assertTrue(Files.readString(directory.resolve("summary.md")).contains("2.00s"));
        assertTrue(ModelBenchmarkMain.modelComplete(raw, model, suite.cases()));
    }

    @Test
    void recommendationPrefersValueWithinFivePointsOfBestQuality() {
        ObjectNode bestQuality = JSON.createObjectNode()
                .put("model_id", "openai/gpt-6-sol").put("score", 99).put("prompts_per_dollar", 430);
        ObjectNode bestBalance = JSON.createObjectNode()
                .put("model_id", "openai/gpt-6-luna").put("score", 95).put("prompts_per_dollar", 8500);
        ObjectNode belowQualityBar = JSON.createObjectNode()
                .put("model_id", "fixture/cheap").put("score", 93).put("prompts_per_dollar", 100000);

        JsonNode recommended = ModelBenchmarkMain.recommendedModel(
                List.of(bestQuality, bestBalance, belowQualityBar));

        assertEquals("openai/gpt-6-luna", recommended.path("model_id").asText());
    }

    @Test
    void remainingCostUsesTheSameModelFamilyAcrossReasoningVariants() throws Exception {
        BenchmarkSuite suite = BenchmarkSuite.load(BenchmarkSuite.DEFAULT_PATH);
        BenchmarkSuite.Model low = suite.models().stream()
                .filter(model -> model.id().equals("anthropic/claude-opus-5"))
                .filter(model -> model.reasoningEffort().equals("low"))
                .findFirst().orElseThrow();
        BenchmarkSuite.Model high = suite.models().stream()
                .filter(model -> model.id().equals("anthropic/claude-opus-5"))
                .filter(model -> model.reasoningEffort().equals("high"))
                .findFirst().orElseThrow();
        ObjectNode raw = ModelBenchmarkMain.newRawReport(suite, List.of(low, high), suite.cases());
        for (BenchmarkSuite.Case scenario : suite.cases()) {
            raw.withArray("runs").addObject().put("model_id", low.id()).put("reasoning_effort", "low")
                    .put("case_id", scenario.id()).put("status", "completed").put("cost_usd", 0.05);
        }
        for (int index = 0; index < 7; index++) {
            raw.withArray("runs").addObject().put("model_id", high.id()).put("reasoning_effort", "high")
                    .put("case_id", suite.cases().get(index).id()).put("status", "completed").put("cost_usd", 0.06);
        }

        assertEquals(0.683, ModelBenchmarkMain.projectedRemainingCost(raw, high, suite.cases()), 0.001);
    }

    @Test
    void fakeProviderCompletesEveryCaseWithoutMinecraftOrNetwork() throws Exception {
        BenchmarkSuite suite = BenchmarkSuite.load(BenchmarkSuite.DEFAULT_PATH);
        var scheduler = Executors.newScheduledThreadPool(2);
        try {
            for (BenchmarkSuite.Case scenario : suite.cases()) {
                BenchmarkTools tools = new BenchmarkTools(scenario);
                Agent agent = new Agent(
                        new ScriptedProvider(scenario), tools.registry(), AgentOptions.DEFAULT,
                        scheduler, AgentEventListener.NONE
                );
                var result = agent.ask(new AssistantRequest(
                        "fixture/model", "low", MinecraftAssistantRuntime.systemPromptForTest(),
                        scenario.conversationHistory(), scenario.question()
                ), CancellationToken.NONE).toCompletableFuture().get(5, TimeUnit.SECONDS);

                assertFalse(result.text().isBlank(), scenario.id());
                if (scenario.expectedTools().isEmpty()) {
                    assertEquals(0, result.toolCalls(), scenario.id());
                } else {
                    assertTrue(result.toolCalls() >= 1, scenario.id());
                }
            }
        } finally {
            scheduler.shutdownNow();
        }
    }

    private static final class ScriptedProvider implements LlmProvider {
        private final BenchmarkSuite.Case scenario;
        private int turn;

        private ScriptedProvider(BenchmarkSuite.Case scenario) {
            this.scenario = scenario;
        }

        @Override
        public CompletionStage<ModelResponse> generate(ModelRequest request, CancellationToken cancellation) {
            cancellation.throwIfCancelled();
            turn++;
            if (scenario.expectedTools().isEmpty() || turn > scenario.expectedTools().size()) {
                return CompletableFuture.completedFuture(new ModelResponse(
                        "Concise fixture answer.", List.of(), "stop", JSON.createObjectNode()
                ));
            }
            String name = scenario.expectedTools().get(turn - 1);
            return CompletableFuture.completedFuture(new ModelResponse(
                    "", List.of(new ToolCall("call-" + turn, name, arguments(name, scenario))),
                    "tool_calls", JSON.createObjectNode()
            ));
        }
    }

    private static JsonNode arguments(String tool, BenchmarkSuite.Case scenario) {
        ObjectNode arguments = JSON.createObjectNode();
        if (tool.startsWith("minecraft_wiki_")) {
            if (tool.equals("minecraft_wiki_search")) {
                return arguments.put("query", scenario.question());
            }
            return arguments.put("title", "Map");
        }
        if (tool.equals("show_brewing")) {
            return arguments.put("target_potion_id", "minecraft:slowness").put("container", "potion");
        }
        if (tool.equals("show_cartography")) {
            return arguments.put("operation", "lock");
        }
        if (tool.equals("show_anvil")) {
            return arguments.put("operation", "apply_book").put("base_item_id", "minecraft:diamond_sword")
                    .put("addition_item_id", "minecraft:enchanted_book")
                    .put("enchantment_id", "minecraft:sharpness").put("enchantment_level", 5);
        }
        if (tool.equals("choose_production_routes")) {
            ArrayNode routes = arguments.putArray("route_ids");
            routes.add("stonecutter").add("crafting");
            return arguments.put("search_id", "slab-routes");
        }
        String target = switch (scenario.id()) {
            case "recipe-recovery-compass" -> "minecraft:recovery_compass";
            case "recipe-glass" -> "minecraft:glass";
            case "production-torches", "followup-show-torches" -> "minecraft:torch";
            case "quantity-stairs" -> "minecraft:oak_stairs";
            case "comparison-slabs", "followup-no-stonecutter" -> "minecraft:stone_brick_slab";
            case "production-iron-sword" -> "minecraft:iron_sword";
            case "quantity-glass-panes" -> "minecraft:glass_pane";
            default -> "minecraft:stone";
        };
        arguments.put("target_item_id", target);
        if (scenario.id().equals("quantity-stairs")) {
            arguments.putObject("target_quantity").put("total_items", 128);
        } else if (scenario.id().equals("quantity-glass-panes")) {
            arguments.putObject("target_quantity").put("total_items", 16);
        } else if (scenario.id().equals("comparison-slabs") || scenario.id().equals("followup-no-stonecutter")) {
            arguments.putObject("target_quantity").put("stacks", 5);
        }
        if (scenario.id().equals("followup-no-stonecutter")) {
            arguments.putArray("unavailable_methods").add("stonecutting");
        }
        return arguments;
    }
}
