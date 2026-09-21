package dev.zubinjha.minecraftassistant.fabric;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.zubinjha.minecraftassistant.core.CancellationToken;
import dev.zubinjha.minecraftassistant.core.Tool;
import dev.zubinjha.minecraftassistant.core.ToolDefinition;
import dev.zubinjha.minecraftassistant.core.ToolExecutionResult;
import dev.zubinjha.minecraftassistant.core.ToolRegistry;
import dev.zubinjha.minecraftassistant.mediawiki.MinecraftWikiToolSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

final class BenchmarkTools {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, ToolDefinition> DEFINITIONS = productionDefinitions();

    private final BenchmarkSuite.Case scenario;
    private final List<Invocation> invocations = new ArrayList<>();

    BenchmarkTools(BenchmarkSuite.Case scenario) {
        this.scenario = scenario;
    }

    ToolRegistry registry() {
        List<Tool> tools = DEFINITIONS.values().stream().map(this::fixtureTool).toList();
        return new ToolRegistry(tools);
    }

    List<Invocation> invocations() {
        return List.copyOf(invocations);
    }

    static List<ToolDefinition> productionDefinitionsList() {
        return List.copyOf(DEFINITIONS.values());
    }

    private Tool fixtureTool(ToolDefinition definition) {
        return new Tool() {
            @Override
            public ToolDefinition definition() {
                return definition;
            }

            @Override
            public CompletionStage<ToolExecutionResult> execute(
                    JsonNode arguments,
                    CancellationToken cancellation
            ) {
                cancellation.throwIfCancelled();
                ToolExecutionResult result = executeFixture(definition.name(), arguments);
                synchronized (invocations) {
                    invocations.add(new Invocation(
                            definition.name(), arguments.deepCopy(), result.content(), result.terminalAnswer().orElse("")
                    ));
                }
                return CompletableFuture.completedFuture(result);
            }
        };
    }

    private ToolExecutionResult executeFixture(String name, JsonNode arguments) {
        if (name.startsWith("minecraft_wiki_")) {
            return wiki(name, arguments);
        }
        return switch (name) {
            case "prepare_production" -> prepare(arguments);
            case "choose_production_routes" -> choose(arguments);
            case "show_brewing", "show_loom", "show_cartography", "show_enchanting", "show_anvil",
                    "show_grindstone" -> nativeGuide(name, arguments);
            default -> ToolExecutionResult.text("Fixture unavailable for " + name + ".");
        };
    }

    private ToolExecutionResult wiki(String name, JsonNode arguments) {
        String title = wikiTitle(scenario.fixture());
        String source = "https://minecraft.wiki/w/" + title.replace(' ', '_');
        ObjectNode result = JSON.createObjectNode();
        switch (name) {
            case "minecraft_wiki_search" -> {
                ArrayNode results = result.putArray("results");
                results.addObject().put("title", title).put("snippet", wikiText(scenario.fixture()))
                        .put("source_url", source);
            }
            case "minecraft_wiki_get_page" -> result.put("title", title)
                    .put("lead_text", wikiText(scenario.fixture())).put("source_url", source)
                    .putArray("sections");
            case "minecraft_wiki_get_section" -> result.put("title", title).put("section", 1)
                    .put("text", wikiText(scenario.fixture())).put("source_url", source);
            case "minecraft_wiki_resolve_redirect" -> result.put("requested_title", arguments.path("title").asText())
                    .put("resolved_title", title).put("source_url", source);
            case "minecraft_wiki_get_categories" -> result.put("title", title).putArray("categories")
                    .add("Minecraft");
            case "minecraft_wiki_get_category_members" -> result.put("category", "Minecraft")
                    .putArray("members").add(title);
            default -> throw new IllegalArgumentException("Unknown Wiki fixture tool " + name);
        }
        return new ToolExecutionResult(result.toString(), Map.of("source_url", source));
    }

    private ToolExecutionResult prepare(JsonNode arguments) {
        String expectedTarget = switch (scenario.id()) {
            case "recipe-recovery-compass" -> "recovery_compass";
            case "recipe-glass" -> "glass";
            case "production-torches", "followup-show-torches" -> "torch";
            case "quantity-stairs" -> "oak_stairs";
            case "comparison-slabs", "followup-no-stonecutter" -> "stone_brick_slab";
            case "production-iron-sword" -> "iron_sword";
            case "quantity-glass-panes" -> "glass_pane";
            default -> "";
        };
        String target = arguments.path("target_item_id").asText("");
        if (!target.endsWith(expectedTarget)) {
            return ToolExecutionResult.text("No production guide was created: target item did not match the request.");
        }
        return switch (scenario.fixture()) {
            case "comparison-slabs" -> ToolExecutionResult.text("""
                    {"status":"choice_required","search_id":"slab-routes","quantity_requested":true,
                    "routes":[
                      {"route_id":"stonecutter","root_materials":[{"item_id":"minecraft:cobblestone","count":160}],"methods":["smelting","crafting","stonecutting"]},
                      {"route_id":"crafting","root_materials":[{"item_id":"minecraft:cobblestone","count":164}],"methods":["smelting","crafting"]}
                    ]}
                    """);
            case "quantity-stairs" -> quantity(arguments, 128,
                    "Calculated: 48 Oak Logs → 128 Oak Stairs via Crafting.");
            case "quantity-panes" -> quantity(arguments, 16,
                    "Calculated: 6 Sand → 16 Glass Panes via Smelting and Crafting.");
            case "quantity-slabs-crafting" -> {
                if (!contains(arguments.path("unavailable_methods"), "stonecutting")) {
                    yield ToolExecutionResult.text("No guide was created: the follow-up said stonecutting is unavailable.");
                }
                yield quantity(arguments, 320,
                        "Calculated: 164 Cobblestone → 324 Stone Brick Slabs via Crafting (4 extra slabs; 2 bricks left).");
            }
            case "process" -> ToolExecutionResult.text(
                    "The native production guide is ready with all connected steps. Briefly tell the player to use the Show Steps button."
            );
            default -> ToolExecutionResult.text(
                    "The native recipe is ready. Briefly tell the player to use the Show Recipe button."
            );
        };
    }

    private ToolExecutionResult quantity(JsonNode arguments, int expected, String answer) {
        JsonNode quantity = arguments.path("target_quantity");
        int actual = quantity.path("total_items").asInt(0);
        if (actual == 0 && quantity.path("stacks").asInt(0) > 0) {
            actual = quantity.path("stacks").asInt() * 64 + quantity.path("loose_items").asInt(0);
        }
        if (actual != expected) {
            return ToolExecutionResult.text("No production guide was created: target quantity did not match the request.");
        }
        return ToolExecutionResult.terminal("The verified quantity plan is ready.", answer);
    }

    private ToolExecutionResult choose(JsonNode arguments) {
        if (!"slab-routes".equals(arguments.path("search_id").asText())) {
            return ToolExecutionResult.text("No guide was created: unknown route search.");
        }
        JsonNode routes = arguments.path("route_ids");
        if (!contains(routes, "stonecutter") || !contains(routes, "crafting")) {
            return ToolExecutionResult.text("No comparison was created: select both requested routes.");
        }
        return ToolExecutionResult.terminal(
                "The verified route comparison is ready.",
                "Best: 160 Cobblestone → 320 Stone Brick Slabs via Stonecutter. Crafting: 164 Cobblestone → 324 slabs (4 extra)."
        );
    }

    private ToolExecutionResult nativeGuide(String name, JsonNode arguments) {
        boolean valid = switch (name) {
            case "show_brewing" -> arguments.path("target_potion_id").asText().contains("slowness");
            case "show_cartography" -> "lock".equals(arguments.path("operation").asText());
            case "show_anvil" -> "apply_book".equals(arguments.path("operation").asText())
                    && arguments.path("base_item_id").asText().endsWith("diamond_sword")
                    && arguments.path("enchantment_id").asText().contains("sharpness")
                    && arguments.path("enchantment_level").asInt() == 5;
            default -> true;
        };
        if (!valid) {
            return ToolExecutionResult.text("No native guide was created: arguments did not match the request.");
        }
        String label = switch (name) {
            case "show_brewing" -> "Show Brewing";
            case "show_cartography" -> "Show Cartography";
            case "show_anvil" -> "Show Anvil";
            default -> "Show Guide";
        };
        return ToolExecutionResult.text(
                "The native production guide is ready. Briefly tell the player to use the " + label + " button."
        );
    }

    private static boolean contains(JsonNode array, String text) {
        if (!array.isArray()) {
            return false;
        }
        for (JsonNode item : array) {
            if (item.asText().contains(text)) {
                return true;
            }
        }
        return false;
    }

    private static String wikiTitle(String fixture) {
        return switch (fixture) {
            case "mending" -> "Mending";
            case "nether-ratio" -> "The Nether";
            case "heart-of-the-sea" -> "Heart of the Sea";
            case "blaze" -> "Blaze";
            case "map" -> "Map";
            case "bed" -> "Bed";
            case "strategy" -> "Tutorial:Defeating the Ender Dragon";
            default -> "Minecraft";
        };
    }

    private static String wikiText(String fixture) {
        return switch (fixture) {
            case "mending" -> "Mending consumes picked-up experience to restore durability at two durability per experience point. One damaged item with Mending equipped in the main hand, offhand, or armor slots is selected at random; experience goes to the player if the selected item is not damaged.";
            case "nether-ratio" -> "Horizontal coordinates use an 8:1 ratio: moving one block in the Nether corresponds to eight blocks in the Overworld. The vertical Y coordinate is not scaled.";
            case "heart-of-the-sea" -> "A heart of the sea is obtained from buried treasure chests. Buried treasure maps commonly found in shipwrecks and ocean ruins can lead to those chests.";
            case "blaze" -> "A blaze killed by a player or tamed wolf drops 0–1 blaze rods. Looting increases the maximum by one per level, up to 0–4 with Looting III.";
            case "map" -> "In Java Edition, a map can be zoomed out in a crafting table by surrounding it with eight paper. A cartography table zooms it with only one paper. Maps can be zoomed out at most four times and locked maps cannot be zoomed.";
            case "bed" -> "Attempting to use a bed in the Nether makes it explode and set nearby blocks on fire. The explosion is stronger than TNT and can seriously injure or kill an unprotected player.";
            case "strategy" -> "Useful Ender Dragon supplies include arrows, blocks, a water bucket, a pickaxe, slow falling potions, healing, spare gear, and bottles for dragon's breath. A carved pumpkin is optional against endermen.";
            default -> "No fixture text is available.";
        };
    }

    private static Map<String, ToolDefinition> productionDefinitions() {
        Map<String, ToolDefinition> definitions = new LinkedHashMap<>();
        MinecraftWikiToolSource wiki = new MinecraftWikiToolSource("http://localhost:1/api.php", Runnable::run);
        wiki.tools().forEach(tool -> definitions.put(tool.definition().name(), tool.definition()));
        ToolDefinition prepare = PrepareProductionTool.benchmarkDefinition();
        ToolDefinition choose = ChooseProductionRoutesTool.benchmarkDefinition();
        definitions.put(prepare.name(), prepare);
        definitions.put(choose.name(), choose);
        NativeGuideTool.benchmarkDefinitions().forEach(definition -> definitions.put(definition.name(), definition));
        return Map.copyOf(definitions);
    }

    record Invocation(String name, JsonNode arguments, String result, String terminalAnswer) {
    }
}
