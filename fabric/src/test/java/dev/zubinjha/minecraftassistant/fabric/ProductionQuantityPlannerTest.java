package dev.zubinjha.minecraftassistant.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.zubinjha.minecraftassistant.core.Agent;
import dev.zubinjha.minecraftassistant.core.AgentEventListener;
import dev.zubinjha.minecraftassistant.core.AgentOptions;
import dev.zubinjha.minecraftassistant.core.AssistantRequest;
import dev.zubinjha.minecraftassistant.core.AssistantResult;
import dev.zubinjha.minecraftassistant.core.CancellationToken;
import dev.zubinjha.minecraftassistant.core.CancellationSource;
import dev.zubinjha.minecraftassistant.core.AssistantException;
import dev.zubinjha.minecraftassistant.core.ConversationMessage;
import dev.zubinjha.minecraftassistant.core.LlmProvider;
import dev.zubinjha.minecraftassistant.core.ModelResponse;
import dev.zubinjha.minecraftassistant.core.ToolCall;
import dev.zubinjha.minecraftassistant.core.ToolRegistry;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

final class ProductionQuantityPlannerTest {
    private final ProductionQuantityPlanner planner = new ProductionQuantityPlanner();

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        BuiltInRegistries.ITEM.listElements()
                .forEach(holder -> holder.bindComponents(DataComponents.COMMON_ITEM_COMPONENTS));
    }

    @Test
    void craftingRouteCalculatesEveryRoundedBatchAndLeftover() {
        ProductionPlan plan = success(planner.plan(
                craftingRoute(), new ProductionQuantityRequest(5, 0), Optional.of("minecraft:cobblestone")
        ));

        assertEquals(320, plan.requestedCount());
        assertEquals(324, plan.producedCount());
        assertEquals(164, plan.sourceMaterial().orElseThrow().count());
        assertEquals(List.of(164L, 41L, 54L), plan.operations().stream()
                .map(ProductionPlan.Operation::batches).toList());
        assertEquals(List.of(2L, 4L), plan.leftovers().stream()
                .map(ProductionPlan.Material::count).toList());
        assertEquals(
                "Calculated: 164 Cobblestone → 324 Stone Brick Slabs via Crafting. "
                        + "4 extra Stone Brick Slabs. Left over: 2 Stone Bricks.",
                plan.summary()
        );
    }

    @Test
    void stonecutterRouteNeedsExactlyHalfAsManySourceBlocks() {
        ProductionPlan plan = success(planner.plan(
                stonecutterRoute(), new ProductionQuantityRequest(5, 0), Optional.of("minecraft:cobblestone")
        ));

        assertEquals(160, plan.sourceMaterial().orElseThrow().count());
        assertEquals(320, plan.producedCount());
        assertTrue(plan.leftovers().isEmpty());
        assertEquals("Calculated: 160 Cobblestone → 320 Stone Brick Slabs via Stonecutting.",
                plan.summary());
    }

    @Test
    void logToStairsUsesActualTotalsAndReportsRoundedLeftovers() {
        List<ProductionCardData> route = oakStairsRoute();

        ProductionPlan bulk = success(planner.plan(
                route, new ProductionQuantityRequest(0, 128), Optional.of("minecraft:oak_log")
        ));
        ProductionPlan one = success(planner.plan(
                route, new ProductionQuantityRequest(0, 1), Optional.of("minecraft:oak_log")
        ));

        assertEquals(48, bulk.sourceMaterial().orElseThrow().count());
        assertEquals(List.of(192L, 128L), bulk.operations().stream()
                .map(ProductionPlan.Operation::outputProduced).toList());
        assertEquals("48 Oak Logs → 128 Oak Stairs", ProductionCardScreen.planHeadline(bulk));

        assertEquals(2, one.sourceMaterial().orElseThrow().count());
        assertEquals(4, one.producedCount());
        assertEquals(List.of(2L, 3L), one.leftovers().stream()
                .map(ProductionPlan.Material::count).toList());
        assertEquals("2 Oak Logs → 1 Oak Stairs (makes 4)", ProductionCardScreen.planHeadline(one));
    }

    @Test
    void sharedIntermediateDemandIsPooledBeforeRounding() {
        List<ProductionCardData> graph = new java.util.ArrayList<>(oakPlankRoute());
        graph.add(crafting("test:sticks", List.of(
                slot(Items.OAK_PLANKS), slot(Items.OAK_PLANKS)
        ), stack(Items.STICK, 4)));
        graph.add(crafting("test:wooden_pickaxe", List.of(
                slot(Items.OAK_PLANKS), slot(Items.OAK_PLANKS), slot(Items.OAK_PLANKS),
                slot(Items.STICK), slot(Items.STICK)
        ), stack(Items.WOODEN_PICKAXE, 1)));

        ProductionPlan plan = success(planner.plan(
                graph, new ProductionQuantityRequest(0, 2), Optional.of("minecraft:oak_log")
        ));

        assertEquals(2, plan.sourceMaterial().orElseThrow().count());
        assertEquals(List.of(8L, 4L, 2L), plan.operations().stream()
                .map(ProductionPlan.Operation::outputProduced).toList());
        assertTrue(plan.leftovers().isEmpty());
    }

    @Test
    void routeDiscoveryCompletesBranchingPlansFromTheDeclaredSource() {
        List<ProductionCardData> catalog = new java.util.ArrayList<>(oakPlankRoute());
        catalog.add(crafting("test:sticks", List.of(
                slot(Items.OAK_PLANKS), slot(Items.OAK_PLANKS)
        ), stack(Items.STICK, 4)));
        catalog.add(crafting("test:wooden_pickaxe", List.of(
                slot(Items.OAK_PLANKS), slot(Items.OAK_PLANKS), slot(Items.OAK_PLANKS),
                slot(Items.STICK), slot(Items.STICK)
        ), stack(Items.WOODEN_PICKAXE, 1)));

        RecipeRouteFinder.Result.Candidates found = assertInstanceOf(
                RecipeRouteFinder.Result.Candidates.class,
                new RecipeRouteFinder(planner).find(
                        catalog,
                        "minecraft:oak_log",
                        "minecraft:wooden_pickaxe",
                        Optional.empty(),
                        new ProductionQuantityRequest(0, 2),
                        6
                )
        );

        assertEquals(3, found.routes().getFirst().plan().operations().size());
        assertEquals(2, found.routes().getFirst().plan().sourceMaterial().orElseThrow().count());
    }

    @Test
    void semanticDiscoveryExpandsEveryBranchReachableFromSuppliedLogs() {
        List<ProductionCardData> catalog = new java.util.ArrayList<>();
        catalog.add(crafting("test:oak_planks", List.of(slot(Items.OAK_LOG)), stack(Items.OAK_PLANKS, 4)));
        catalog.add(crafting("test:sticks", List.of(
                slot(Items.OAK_PLANKS), slot(Items.OAK_PLANKS)
        ), stack(Items.STICK, 4)));
        catalog.add(new ProductionCardData.Cooking(
                "test:charcoal", ProductionMethod.SMELTING, slot(Items.OAK_LOG),
                new ProductionCardData.Slot(List.of(), true), slot(Items.CHARCOAL),
                slot(Items.FURNACE), 200, 0.15F
        ));
        catalog.add(crafting("test:torches", List.of(
                new ProductionCardData.Slot(List.of(
                        stack(Items.COAL, 1), stack(Items.CHARCOAL, 1)
                ), false),
                slot(Items.STICK)
        ), stack(Items.TORCH, 4)));

        RecipeRouteFinder.Result.Candidates found = assertInstanceOf(
                RecipeRouteFinder.Result.Candidates.class,
                new RecipeRouteFinder(planner).find(
                        catalog,
                        Set.of("minecraft:oak_log"),
                        Set.of("minecraft:coal"),
                        Set.of(),
                        Set.of(),
                        "minecraft:torch",
                        Optional.empty(),
                        new ProductionQuantityRequest(1, 0, 0),
                        6,
                        CancellationToken.NONE
                )
        );

        ProductionPlan plan = found.routes().getFirst().plan();
        assertEquals(4, plan.operations().size());
        assertEquals(Set.of(
                "minecraft:oak_planks", "minecraft:stick", "minecraft:charcoal", "minecraft:torch"
        ), plan.operations().stream().map(operation -> operation.output().itemId())
                .collect(java.util.stream.Collectors.toSet()));
        assertEquals("minecraft:torch", plan.operations().getLast().output().itemId());
        assertEquals(List.of("minecraft:oak_log"), plan.rootMaterials().stream()
                .map(ProductionPlan.Material::itemId).distinct().toList());
    }

    @Test
    void semanticDiscoveryExpandsOnlyBranchesReachableFromDeclaredMaterials() {
        List<ProductionCardData> catalog = new java.util.ArrayList<>();
        catalog.add(crafting("test:oak_planks", List.of(slot(Items.OAK_LOG)), stack(Items.OAK_PLANKS, 4)));
        catalog.add(crafting("test:sticks", List.of(
                slot(Items.OAK_PLANKS), slot(Items.OAK_PLANKS)
        ), stack(Items.STICK, 4)));
        catalog.add(new ProductionCardData.Cooking(
                "test:iron_ingot", ProductionMethod.SMELTING, slot(Items.RAW_IRON),
                new ProductionCardData.Slot(List.of(), true), slot(Items.IRON_INGOT),
                slot(Items.FURNACE), 200, 0.7F
        ));
        catalog.add(crafting("test:iron_sword", List.of(
                slot(Items.IRON_INGOT), slot(Items.IRON_INGOT), slot(Items.STICK)
        ), stack(Items.IRON_SWORD, 1)));
        RecipeRouteFinder finder = new RecipeRouteFinder(planner);

        RecipeRouteFinder.Result.Candidates both = assertInstanceOf(
                RecipeRouteFinder.Result.Candidates.class,
                finder.find(catalog, Set.of("minecraft:raw_iron", "minecraft:oak_log"), Set.of(), Set.of(), Set.of(),
                        "minecraft:iron_sword", Optional.empty(), new ProductionQuantityRequest(1, 0, 0), 6,
                        CancellationToken.NONE)
        );
        RecipeRouteFinder.Result.Candidates ironOnly = assertInstanceOf(
                RecipeRouteFinder.Result.Candidates.class,
                finder.find(catalog, Set.of("minecraft:raw_iron"), Set.of(), Set.of(), Set.of(),
                        "minecraft:iron_sword", Optional.empty(), new ProductionQuantityRequest(1, 0, 0), 6,
                        CancellationToken.NONE)
        );

        assertEquals(4, both.routes().getFirst().cards().size());
        assertEquals(Set.of("minecraft:raw_iron", "minecraft:oak_log"), both.routes().getFirst().plan()
                .rootMaterials().stream().map(ProductionPlan.Material::itemId)
                .collect(java.util.stream.Collectors.toSet()));
        assertEquals(2, ironOnly.routes().getFirst().cards().size());
        assertEquals(Set.of("minecraft:raw_iron", "minecraft:stick"), ironOnly.routes().getFirst().plan()
                .rootMaterials().stream().map(ProductionPlan.Material::itemId)
                .collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void independentIngredientsRemainMultipleRootMaterials() {
        ProductionCardData lever = crafting("test:lever", List.of(
                slot(Items.COBBLESTONE), slot(Items.STICK)
        ), stack(Items.LEVER, 1));

        ProductionPlan plan = success(planner.plan(
                List.of(lever), new ProductionQuantityRequest(0, 1), Optional.of("minecraft:cobblestone")
        ));

        assertEquals(List.of("minecraft:cobblestone", "minecraft:stick"), plan.rootMaterials().stream()
                .map(ProductionPlan.Material::itemId).toList());
        assertEquals("1 Cobblestone + 1 Stick → 1 Lever", ProductionCardScreen.planHeadline(plan));
    }

    @Test
    void rejectsCyclesAndMoreThanSixOperations() {
        ProductionCardData stone = crafting(
                "test:stone", List.of(slot(Items.STONE_BRICKS)), stack(Items.STONE, 1)
        );
        ProductionCardData bricks = crafting(
                "test:bricks", List.of(slot(Items.STONE)), stack(Items.STONE_BRICKS, 1)
        );
        List<Item> chainItems = List.of(
                Items.COBBLESTONE, Items.STONE, Items.STONE_BRICKS, Items.STONE_BRICK_SLAB,
                Items.STONE_BRICK_STAIRS, Items.STONE_BRICK_WALL, Items.SMOOTH_STONE, Items.SMOOTH_STONE_SLAB
        );
        List<ProductionCardData> tooLong = new java.util.ArrayList<>();
        for (int index = 1; index < chainItems.size(); index++) {
            tooLong.add(crafting("test:chain_" + index, List.of(slot(chainItems.get(index - 1))),
                    stack(chainItems.get(index), 1)));
        }

        ProductionQuantityPlanner.Result cycle = planner.plan(
                List.of(stone, bricks), new ProductionQuantityRequest(0, 1), Optional.empty()
        );
        ProductionQuantityPlanner.Result limit = planner.plan(
                tooLong, new ProductionQuantityRequest(0, 1), Optional.of("minecraft:cobblestone")
        );

        assertTrue(assertInstanceOf(ProductionQuantityPlanner.Result.Failure.class, cycle)
                .reason().contains("cycle"));
        assertTrue(assertInstanceOf(ProductionQuantityPlanner.Result.Failure.class, limit)
                .reason().contains("one to six"));
    }

    @Test
    void automaticSelectionPrefersLeastSourceAndMethodConstraintIsHonored() {
        List<ProductionCardData> catalog = new java.util.ArrayList<>();
        catalog.addAll(craftingRoute());
        catalog.addAll(stonecutterRoute());
        RecipeRouteFinder finder = new RecipeRouteFinder(planner);

        RecipeRouteFinder.Result.Candidates automatic = assertInstanceOf(
                RecipeRouteFinder.Result.Candidates.class,
                finder.find(catalog, "minecraft:cobblestone", "minecraft:stone_brick_slab",
                        Optional.empty(), new ProductionQuantityRequest(5, 0), 6)
        );
        RecipeRouteFinder.Result.Candidates crafting = assertInstanceOf(
                RecipeRouteFinder.Result.Candidates.class,
                finder.find(catalog, "minecraft:cobblestone", "minecraft:stone_brick_slab",
                        Optional.of(ProductionMethod.CRAFTING), new ProductionQuantityRequest(5, 0), 6)
        );

        assertEquals(2, automatic.routes().size());
        assertEquals(ProductionMethod.STONECUTTING, automatic.routes().getFirst().cards().getLast().method());
        assertEquals(160, automatic.routes().getFirst().plan().sourceMaterial().orElseThrow().count());
        assertEquals(ProductionMethod.CRAFTING, crafting.routes().getFirst().cards().getLast().method());
        assertEquals(164, crafting.routes().getFirst().plan().sourceMaterial().orElseThrow().count());
    }

    @Test
    void stackConversionUsesTheActualTargetMaximum() {
        ProductionCardData card = crafting(
                "test:ender_pearl", List.of(slot(Items.ENDER_EYE)), stack(Items.ENDER_PEARL, 1)
        );

        ProductionPlan plan = success(planner.plan(
                List.of(card), new ProductionQuantityRequest(2, 3), Optional.empty()
        ));

        assertEquals(35, plan.requestedCount());
        assertEquals(35, plan.operations().getFirst().batches());
    }

    @Test
    void alternativesAreRecordedWithoutInventingDifferentPrices() {
        ProductionCardData.Slot planks = new ProductionCardData.Slot(
                List.of(stack(Items.OAK_PLANKS, 1), stack(Items.SPRUCE_PLANKS, 1)), false
        );
        ProductionCardData card = crafting("test:sticks", List.of(planks, planks), stack(Items.STICK, 4));

        ProductionPlan plan = success(planner.plan(
                List.of(card), new ProductionQuantityRequest(0, 5), Optional.empty()
        ));

        assertEquals(2, plan.operations().getFirst().batches());
        assertEquals(8, plan.producedCount());
        assertEquals(4, plan.rootMaterials().getFirst().count());
        assertEquals(List.of("minecraft:oak_planks", "minecraft:spruce_planks"),
                plan.rootMaterials().getFirst().alternatives());
    }

    @Test
    void rejectsDisconnectedAndRemainderBasedPlans() {
        ProductionQuantityPlanner.Result disconnected = planner.plan(
                List.of(
                        crafting("test:stone", List.of(slot(Items.COBBLESTONE)), stack(Items.STONE, 1)),
                        crafting("test:glass", List.of(slot(Items.SAND)), stack(Items.GLASS, 1))
                ),
                new ProductionQuantityRequest(0, 1),
                Optional.of("minecraft:cobblestone")
        );
        ProductionQuantityPlanner.Result remainder = planner.plan(
                List.of(crafting("test:remainder", List.of(slot(Items.WATER_BUCKET)), stack(Items.CLAY, 1))),
                new ProductionQuantityRequest(0, 1),
                Optional.empty()
        );

        assertTrue(assertInstanceOf(ProductionQuantityPlanner.Result.Failure.class, disconnected)
                .reason().contains("disconnected"));
        assertTrue(assertInstanceOf(ProductionQuantityPlanner.Result.Failure.class, remainder)
                .reason().contains("crafting remainder"));
    }

    @Test
    void genericFuelIsExcludedFromMaterials() {
        ProductionPlan plan = success(planner.plan(
                List.of(cooking()), new ProductionQuantityRequest(0, 10), Optional.empty()
        ));

        assertTrue(plan.genericFuelOmitted());
        assertEquals(List.of("minecraft:cobblestone"), plan.rootMaterials().stream()
                .map(ProductionPlan.Material::itemId).toList());
    }

    @Test
    void rejectsOverflowAndRespondsToCancellation() {
        ProductionQuantityPlanner.Result overflow = planner.plan(
                List.of(cooking()),
                new ProductionQuantityRequest(Long.MAX_VALUE, 1),
                Optional.empty()
        );
        CancellationSource cancellation = new CancellationSource();
        cancellation.cancel();

        assertTrue(assertInstanceOf(ProductionQuantityPlanner.Result.Failure.class, overflow)
                .reason().contains("too large"));
        assertThrows(AssistantException.class, () -> planner.plan(
                craftingRoute(), new ProductionQuantityRequest(0, 1), Optional.empty(), cancellation
        ));
        assertThrows(AssistantException.class, () -> new RecipeRouteFinder(planner).find(
                craftingRoute(),
                "minecraft:cobblestone",
                "minecraft:stone_brick_slab",
                Optional.empty(),
                new ProductionQuantityRequest(0, 1),
                6,
                cancellation
        ));
    }

    @Test
    void quantityParserRejectsFractionsAndZeroTotals() {
        ObjectNode fractional = JsonNodeFactory.instance.objectNode();
        fractional.putObject("target_quantity").put("stacks", 1.5).put("loose_items", 0);
        ObjectNode zero = JsonNodeFactory.instance.objectNode();
        zero.putObject("target_quantity").put("total_items", 0).put("stacks", 0).put("loose_items", 0);
        ObjectNode beyondLong = JsonNodeFactory.instance.objectNode();
        beyondLong.putObject("target_quantity").put(
                "total_items", new java.math.BigInteger("18446744073709551816")
        );

        assertTrue(ProductionQuantityRequest.parseOptional(fractional).error().contains("whole numbers"));
        assertTrue(ProductionQuantityRequest.parseOptional(zero).error().contains("at least one quantity"));
        assertTrue(ProductionQuantityRequest.parseOptional(beyondLong).error().contains("too large"));
    }

    @Test
    void totalItemsCannotBeDoubleCountedWithPositiveStackFields() {
        ObjectNode exact = JsonNodeFactory.instance.objectNode();
        exact.putObject("target_quantity")
                .put("total_items", 200).put("stacks", 0).put("loose_items", 0);
        ObjectNode doubleCounted = JsonNodeFactory.instance.objectNode();
        doubleCounted.putObject("target_quantity")
                .put("total_items", 200).put("stacks", 3).put("loose_items", 8);

        ProductionQuantityRequest parsed = ProductionQuantityRequest.parseOptional(exact)
                .request().orElseThrow();

        assertEquals(200, parsed.totalFor(stack(Items.STONE_BRICK_STAIRS, 1)));
        assertTrue(ProductionQuantityRequest.parseOptional(doubleCounted).error()
                .contains("cannot be combined"));
    }

    @Test
    void showRecipePublishesOnlyTheCalculatedSingleCard() {
        ProductionCardData card = crafting(
                "test:redstone_block",
                java.util.Collections.nCopies(9, slot(Items.REDSTONE)),
                stack(Items.REDSTONE_BLOCK, 1)
        );
        ProductionPresentationCollector collector = new ProductionPresentationCollector();
        RecipeLookup lookup = (id, method) -> new RecipeLookupResult.Found(card);
        ObjectNode arguments = JsonNodeFactory.instance.objectNode()
                .put("recipe_id", "minecraft:redstone_block")
                .put("method", "crafting");
        arguments.putObject("target_quantity").put("stacks", 1).put("loose_items", 0);

        dev.zubinjha.minecraftassistant.core.ToolExecutionResult toolResult =
                new RecipeCardRequestTool(Runnable::run, lookup, collector)
                        .execute(arguments, CancellationToken.NONE).toCompletableFuture().join();

        assertTrue(toolResult.terminalAnswer().orElseThrow().startsWith("Calculated: 576 Redstone → 64 "));
        ProductionPresentation.Plan presentation = assertInstanceOf(
                ProductionPresentation.Plan.class, collector.snapshot().orElseThrow()
        );
        ProductionPlan plan = presentation.plan();
        assertEquals(64, plan.requestedCount());
        assertEquals(576, plan.rootMaterials().getFirst().count());
    }

    @Test
    void discoveryReturnsAlternativesAndSelectedRoutesPublishAComparison() {
        List<ProductionCardData> catalog = new java.util.ArrayList<>();
        catalog.addAll(craftingRoute());
        catalog.addAll(stonecutterRoute());
        RecipeLookup lookup = new RecipeLookup() {
            @Override
            public RecipeLookupResult resolve(String recipeId, Optional<ProductionMethod> method) {
                return new RecipeLookupResult.Missing("automatic test");
            }

            @Override
            public List<ProductionCardData> allRecipes() {
                return catalog;
            }
        };
        ProductionPresentationCollector collector = new ProductionPresentationCollector();
        ProductionRouteSearchStore searches = new ProductionRouteSearchStore();
        ObjectNode arguments = JsonNodeFactory.instance.objectNode()
                .put("source_item_id", "cobblestone")
                .put("target_item_id", "stone_brick_slab");
        arguments.putObject("target_quantity").put("stacks", 5).put("loose_items", 0);

        String discovery = new FindProductionRoutesTool(Runnable::run, lookup, searches)
                .execute(arguments, CancellationToken.NONE).toCompletableFuture().join().content();
        ProductionRouteSearchStore.Search search = searches.get("s1").orElseThrow();
        assertEquals(2, search.routes().size());
        assertTrue(discovery.contains("\"source_item_id\":\"minecraft:cobblestone\""));
        assertTrue(discovery.contains("\"workstations\""));
        assertTrue(discovery.contains("\"source_count\":160"));

        ObjectNode selection = JsonNodeFactory.instance.objectNode().put("search_id", search.id());
        selection.putArray("route_ids")
                .add(search.routes().get(0).id())
                .add(search.routes().get(1).id());
        dev.zubinjha.minecraftassistant.core.ToolExecutionResult toolResult =
                new ShowRoutesTool(searches, collector)
                        .execute(selection, CancellationToken.NONE).toCompletableFuture().join();

        assertTrue(toolResult.terminalAnswer().orElseThrow().startsWith("Best: 160 Cobblestone"));
        ProductionPresentation.Comparison presentation = assertInstanceOf(
                ProductionPresentation.Comparison.class, collector.snapshot().orElseThrow()
        );
        assertEquals(ProductionMethod.STONECUTTING,
                presentation.routes().getFirst().cards().getLast().method());
        assertEquals(ProductionMethod.CRAFTING,
                presentation.routes().get(1).cards().getLast().method());
    }

    @Test
    void semanticPreparationAutomaticallyPublishesAnUnambiguousBranchingProcess() {
        List<ProductionCardData> catalog = torchCatalog();
        RecipeLookup lookup = catalogLookup(catalog);
        ProductionPresentationCollector collector = new ProductionPresentationCollector();
        ObjectNode arguments = JsonNodeFactory.instance.objectNode().put("target_item_id", "torch");
        arguments.putArray("starting_item_ids").add("oak_log");

        var result = new PrepareProductionTool(
                Runnable::run, lookup, new ProductionRouteSearchStore(), collector
        ).execute(arguments, CancellationToken.NONE).toCompletableFuture().join();

        assertTrue(result.content().contains("4 steps"));
        ProductionPresentation.Sequence presentation = assertInstanceOf(
                ProductionPresentation.Sequence.class, collector.snapshot().orElseThrow()
        );
        assertEquals(4, presentation.cards().size());
        assertEquals(ProductionMethod.CRAFTING, presentation.cards().getLast().method());
    }

    @Test
    void semanticPreparationClassifiesOneNativeOperationAsARecipe() {
        ProductionCardData table = crafting(
                "test:crafting_table",
                java.util.Collections.nCopies(4, slot(Items.OAK_PLANKS)),
                stack(Items.CRAFTING_TABLE, 1)
        );
        ProductionPresentationCollector collector = new ProductionPresentationCollector();
        ObjectNode arguments = JsonNodeFactory.instance.objectNode()
                .put("target_item_id", "crafting_table");

        var result = new PrepareProductionTool(
                Runnable::run, catalogLookup(List.of(table)), new ProductionRouteSearchStore(), collector
        ).execute(arguments, CancellationToken.NONE).toCompletableFuture().join();

        assertTrue(result.content().contains("recipe guide"));
        assertInstanceOf(ProductionPresentation.Single.class, collector.snapshot().orElseThrow());
    }

    @Test
    void semanticToolsHideRecipeIdsStepsAndPresentationTypesFromTheModel() {
        String prepareSchema = new PrepareProductionTool(
                Runnable::run, catalogLookup(List.of()), new ProductionRouteSearchStore(),
                new ProductionPresentationCollector()
        ).definition().inputSchema().toString();
        String chooseSchema = new ChooseProductionRoutesTool(
                new ProductionRouteSearchStore(), new ProductionPresentationCollector()
        ).definition().inputSchema().toString();

        assertTrue(prepareSchema.contains("target_item_id"));
        assertTrue(prepareSchema.contains("starting_item_ids"));
        assertTrue(!prepareSchema.contains("recipe_id"));
        assertTrue(!prepareSchema.contains("steps"));
        assertTrue(!prepareSchema.contains("presentation"));
        assertTrue(!chooseSchema.contains("recipe_id"));
        assertTrue(!chooseSchema.contains("steps"));
    }

    @Test
    void semanticQuantityAlternativesRequireOnlyOpaqueRouteSelection() {
        List<ProductionCardData> catalog = new java.util.ArrayList<>();
        catalog.addAll(craftingRoute());
        catalog.addAll(stonecutterRoute());
        ProductionPresentationCollector collector = new ProductionPresentationCollector();
        ProductionRouteSearchStore searches = new ProductionRouteSearchStore();
        ObjectNode request = JsonNodeFactory.instance.objectNode()
                .put("target_item_id", "stone_brick_slab");
        request.putArray("starting_item_ids").add("cobblestone");
        request.putObject("target_quantity").put("stacks", 5).put("loose_items", 0);

        String discovery = new PrepareProductionTool(
                Runnable::run, catalogLookup(catalog), searches, collector
        ).execute(request, CancellationToken.NONE).toCompletableFuture().join().content();
        ProductionRouteSearchStore.Search search = searches.get("s1").orElseThrow();
        assertTrue(discovery.contains("\"status\":\"choice_required\""));
        assertEquals(2, search.routes().size());

        ObjectNode choice = JsonNodeFactory.instance.objectNode().put("search_id", search.id());
        choice.putArray("route_ids").add(search.routes().getFirst().id());
        var result = new ChooseProductionRoutesTool(searches, collector)
                .execute(choice, CancellationToken.NONE).toCompletableFuture().join();

        assertTrue(result.terminalAnswer().isPresent());
        assertInstanceOf(ProductionPresentation.Plan.class, collector.snapshot().orElseThrow());
    }

    @Test
    void scriptedAgentDiscoversSelectsAndTerminatesWithoutAThirdProviderTurn() throws Exception {
        List<ProductionCardData> catalog = new java.util.ArrayList<>();
        catalog.addAll(craftingRoute());
        catalog.addAll(stonecutterRoute());
        RecipeLookup lookup = new RecipeLookup() {
            @Override
            public RecipeLookupResult resolve(String recipeId, Optional<ProductionMethod> method) {
                return new RecipeLookupResult.Missing("not used");
            }

            @Override
            public List<ProductionCardData> allRecipes() {
                return catalog;
            }
        };
        ProductionPresentationCollector collector = new ProductionPresentationCollector();
        ProductionRouteSearchStore searches = new ProductionRouteSearchStore();
        ObjectMapper json = new ObjectMapper();
        AtomicInteger providerTurns = new AtomicInteger();
        LlmProvider provider = (request, cancellation) -> {
            int turn = providerTurns.incrementAndGet();
            if (turn == 1) {
                ObjectNode arguments = json.createObjectNode()
                        .put("source_item_id", "minecraft:cobblestone")
                        .put("target_item_id", "minecraft:stone_brick_slab");
                arguments.putObject("target_quantity").put("total_items", 320);
                return CompletableFuture.completedFuture(ModelResponse.tools(List.of(
                        new ToolCall("find-1", "find_production_routes", arguments)
                )));
            }
            if (turn == 2) {
                ConversationMessage.ToolResult discovered = (ConversationMessage.ToolResult)
                        request.messages().get(request.messages().size() - 1);
                try {
                    var payload = json.readTree(discovered.content());
                    ObjectNode arguments = json.createObjectNode()
                            .put("search_id", payload.path("search_id").asText());
                    var routeIds = arguments.putArray("route_ids");
                    payload.path("routes").forEach(route -> routeIds.add(route.path("route_id").asText()));
                    return CompletableFuture.completedFuture(ModelResponse.tools(List.of(
                            new ToolCall("show-1", "show_routes", arguments)
                    )));
                } catch (java.io.IOException failure) {
                    return CompletableFuture.failedFuture(failure);
                }
            }
            return CompletableFuture.failedFuture(new AssertionError("unexpected third provider turn"));
        };
        var scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            Agent agent = new Agent(
                    provider,
                    new ToolRegistry(List.of(
                            new FindProductionRoutesTool(Runnable::run, lookup, searches),
                            new ShowRoutesTool(searches, collector)
                    )),
                    AgentOptions.DEFAULT,
                    scheduler,
                    AgentEventListener.NONE
            );

            AssistantResult result = agent.ask(new AssistantRequest(
                    "test-model", "low", "Use tools.",
                    "Compare routes from cobblestone to 320 stone brick slabs."
            ), CancellationToken.NONE).toCompletableFuture().join();

            assertEquals(2, result.providerTurns());
            assertEquals(2, result.toolCalls());
            assertEquals(2, providerTurns.get());
            assertTrue(result.text().startsWith("Best: 160 Cobblestone"));
            assertInstanceOf(ProductionPresentation.Comparison.class, collector.snapshot().orElseThrow());
        } finally {
            scheduler.shutdownNow();
            scheduler.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void scriptedSemanticAgentUsesFollowUpContextWithoutRecipeAssembly() throws Exception {
        List<ProductionCardData> catalog = new java.util.ArrayList<>();
        catalog.addAll(craftingRoute());
        catalog.addAll(stonecutterRoute());
        RecipeLookup lookup = catalogLookup(catalog);
        ProductionPresentationCollector collector = new ProductionPresentationCollector();
        ProductionRouteSearchStore searches = new ProductionRouteSearchStore();
        AtomicInteger providerTurns = new AtomicInteger();
        LlmProvider provider = (request, cancellation) -> {
            providerTurns.incrementAndGet();
            assertTrue(request.messages().stream().anyMatch(message ->
                    message instanceof ConversationMessage.User user
                            && user.text().contains("five stacks")));
            ObjectNode arguments = JsonNodeFactory.instance.objectNode()
                    .put("target_item_id", "minecraft:stone_brick_slab");
            arguments.putArray("starting_item_ids").add("minecraft:cobblestone");
            arguments.putArray("unavailable_methods").add("stonecutting");
            arguments.putObject("target_quantity").put("stacks", 5).put("loose_items", 0);
            assertTrue(!arguments.has("recipe_id") && !arguments.has("steps"));
            return CompletableFuture.completedFuture(ModelResponse.tools(List.of(
                    new ToolCall("prepare-1", "prepare_production", arguments)
            )));
        };
        var scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            Agent agent = new Agent(
                    provider,
                    new ToolRegistry(List.of(
                            new PrepareProductionTool(Runnable::run, lookup, searches, collector),
                            new ChooseProductionRoutesTool(searches, collector)
                    )),
                    AgentOptions.DEFAULT,
                    scheduler,
                    AgentEventListener.NONE
            );

            AssistantResult result = agent.ask(new AssistantRequest(
                    "test-model", "low", "Use semantic production tools.",
                    List.of(
                            new ConversationMessage.User(
                                    "How much cobble for five stacks of stone brick slabs?"
                            ),
                            new ConversationMessage.Assistant("The native route is ready.", List.of())
                    ),
                    "I don't have a stonecutter."
            ), CancellationToken.NONE).toCompletableFuture().join();

            assertEquals(1, providerTurns.get());
            assertEquals(1, result.providerTurns());
            assertEquals(1, result.toolCalls());
            ProductionPresentation.Plan presentation = assertInstanceOf(
                    ProductionPresentation.Plan.class, collector.snapshot().orElseThrow()
            );
            assertEquals(ProductionMethod.CRAFTING,
                    presentation.cards().getLast().method());
            assertTrue(result.text().contains("164 Cobblestone"));
        } finally {
            scheduler.shutdownNow();
            scheduler.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void nonQuantityFollowUpCanRequestSeveralSemanticGuidesWithoutTerminalAnswerConflict() throws Exception {
        RecipeLookup lookup = catalogLookup(torchCatalog());
        ProductionPresentationCollector collector = new ProductionPresentationCollector();
        ProductionRouteSearchStore searches = new ProductionRouteSearchStore();
        AtomicInteger providerTurns = new AtomicInteger();
        LlmProvider provider = (request, cancellation) -> {
            int turn = providerTurns.incrementAndGet();
            if (turn == 1) {
                ObjectNode sticks = JsonNodeFactory.instance.objectNode()
                        .put("target_item_id", "minecraft:stick");
                sticks.putArray("starting_item_ids").add("minecraft:oak_log");
                ObjectNode torches = JsonNodeFactory.instance.objectNode()
                        .put("target_item_id", "minecraft:torch");
                torches.putArray("starting_item_ids").add("minecraft:oak_log");
                torches.putArray("unavailable_item_ids").add("minecraft:coal");
                return CompletableFuture.completedFuture(ModelResponse.tools(List.of(
                        new ToolCall("prepare-sticks", "prepare_production", sticks),
                        new ToolCall("prepare-torches", "prepare_production", torches)
                )));
            }
            if (turn == 2) {
                assertTrue(request.messages().stream().filter(ConversationMessage.ToolResult.class::isInstance)
                        .map(ConversationMessage.ToolResult.class::cast)
                        .allMatch(ConversationMessage.ToolResult::success));
                return CompletableFuture.completedFuture(ModelResponse.text(
                        "The complete native production guide is ready."
                ));
            }
            return CompletableFuture.failedFuture(new AssertionError("unexpected provider turn"));
        };
        var scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            Agent agent = new Agent(
                    provider,
                    new ToolRegistry(List.of(
                            new PrepareProductionTool(Runnable::run, lookup, searches, collector),
                            new ChooseProductionRoutesTool(searches, collector)
                    )),
                    AgentOptions.DEFAULT,
                    scheduler,
                    AgentEventListener.NONE
            );

            AssistantResult result = agent.ask(new AssistantRequest(
                    "test-model", "low", "Use semantic production tools.",
                    List.of(
                            new ConversationMessage.User(
                                    "I have a furnace but no coal and a stack of logs. Can I make torches?"
                            ),
                            new ConversationMessage.Assistant(
                                    "Yes. Make charcoal and sticks, then craft torches.", List.of()
                            )
                    ),
                    "But can you show me the crafting recipes?"
            ), CancellationToken.NONE).toCompletableFuture().join();

            assertEquals(2, providerTurns.get());
            assertEquals(2, result.providerTurns());
            assertEquals(2, result.toolCalls());
            ProductionPresentation.Sequence presentation = assertInstanceOf(
                    ProductionPresentation.Sequence.class, collector.snapshot().orElseThrow()
            );
            assertEquals(4, presentation.cards().size());
            assertEquals("test:torches", presentation.cards().getLast().recipeId());
        } finally {
            scheduler.shutdownNow();
            scheduler.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    private static List<ProductionCardData> craftingRoute() {
        return List.of(
                cooking(),
                crafting("test:stone_bricks", List.of(
                        slot(Items.STONE), slot(Items.STONE), slot(Items.STONE), slot(Items.STONE)
                ), stack(Items.STONE_BRICKS, 4)),
                crafting("test:stone_brick_slabs", List.of(
                        slot(Items.STONE_BRICKS), slot(Items.STONE_BRICKS), slot(Items.STONE_BRICKS)
                ), stack(Items.STONE_BRICK_SLAB, 6))
        );
    }

    private static List<ProductionCardData> torchCatalog() {
        List<ProductionCardData> catalog = new java.util.ArrayList<>();
        catalog.add(crafting("test:oak_planks", List.of(slot(Items.OAK_LOG)), stack(Items.OAK_PLANKS, 4)));
        catalog.add(crafting("test:sticks", List.of(
                slot(Items.OAK_PLANKS), slot(Items.OAK_PLANKS)
        ), stack(Items.STICK, 4)));
        catalog.add(new ProductionCardData.Cooking(
                "test:charcoal", ProductionMethod.SMELTING, slot(Items.OAK_LOG),
                new ProductionCardData.Slot(List.of(), true), slot(Items.CHARCOAL),
                slot(Items.FURNACE), 200, 0.15F
        ));
        catalog.add(crafting("test:torches", List.of(
                new ProductionCardData.Slot(List.of(
                        stack(Items.COAL, 1), stack(Items.CHARCOAL, 1)
                ), false), slot(Items.STICK)
        ), stack(Items.TORCH, 4)));
        return List.copyOf(catalog);
    }

    private static RecipeLookup catalogLookup(List<ProductionCardData> catalog) {
        return new RecipeLookup() {
            @Override
            public RecipeLookupResult resolve(String recipeId, Optional<ProductionMethod> method) {
                return new RecipeLookupResult.Missing("semantic tool test");
            }

            @Override
            public List<ProductionCardData> allRecipes() {
                return catalog;
            }
        };
    }

    private static List<ProductionCardData> stonecutterRoute() {
        return List.of(
                cooking(),
                new ProductionCardData.Stonecutting(
                        "test:stonecutting_slabs",
                        slot(Items.STONE),
                        ProductionCardData.Slot.of(stack(Items.STONE_BRICK_SLAB, 2)),
                        slot(Items.STONECUTTER)
                )
        );
    }

    private static List<ProductionCardData> oakPlankRoute() {
        return List.of(crafting(
                "test:oak_planks", List.of(slot(Items.OAK_LOG)), stack(Items.OAK_PLANKS, 4)
        ));
    }

    private static List<ProductionCardData> oakStairsRoute() {
        List<ProductionCardData> route = new java.util.ArrayList<>(oakPlankRoute());
        route.add(crafting("test:oak_stairs", java.util.Collections.nCopies(
                6, slot(Items.OAK_PLANKS)
        ), stack(Items.OAK_STAIRS, 4)));
        return List.copyOf(route);
    }

    private static ProductionCardData cooking() {
        return new ProductionCardData.Cooking(
                "test:stone",
                ProductionMethod.SMELTING,
                slot(Items.COBBLESTONE),
                new ProductionCardData.Slot(List.of(), true),
                slot(Items.STONE),
                slot(Items.FURNACE),
                200,
                0.1F
        );
    }

    private static ProductionCardData crafting(String id, List<ProductionCardData.Slot> inputs, ItemStack output) {
        return new ProductionCardData.Crafting(
                id,
                Math.min(3, inputs.size()),
                Math.max(1, (inputs.size() + 2) / 3),
                inputs,
                ProductionCardData.Slot.of(output),
                false
        );
    }

    private static ProductionCardData.Slot slot(Item item) {
        return ProductionCardData.Slot.of(stack(item, 1));
    }

    private static ItemStack stack(Item item, int count) {
        ItemStack stack = new ItemStack(item, count);
        String path = BuiltInRegistries.ITEM.getKey(item).getPath();
        String displayName = java.util.Arrays.stream(path.split("_"))
                .map(part -> Character.toUpperCase(part.charAt(0)) + part.substring(1))
                .collect(java.util.stream.Collectors.joining(" "));
        stack.set(DataComponents.ITEM_NAME, Component.literal(displayName));
        if (item == Items.ENDER_PEARL) {
            stack.set(DataComponents.MAX_STACK_SIZE, 16);
        }
        return stack;
    }

    private static ProductionPlan success(ProductionQuantityPlanner.Result result) {
        return assertInstanceOf(ProductionQuantityPlanner.Result.Success.class, result).plan();
    }
}
