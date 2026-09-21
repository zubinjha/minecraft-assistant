package dev.zubinjha.minecraftassistant.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.zubinjha.minecraftassistant.core.CancellationToken;
import dev.zubinjha.minecraftassistant.core.ToolExecutionResult;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

final class ProductionRouteSearchStoreTest {
    @Test
    void candidateSelectionGroupsMethodSignaturesRanksAndCapsResults() {
        List<RecipeRouteFinder.Candidate> exact = new ArrayList<>();
        exact.add(candidate("slow-crafting", List.of(ProductionMethod.CRAFTING), 999));
        exact.add(candidate("crafting", List.of(ProductionMethod.CRAFTING), 100));
        exact.add(candidate("smelting", List.of(ProductionMethod.SMELTING), 90));
        exact.add(candidate("blasting", List.of(ProductionMethod.BLASTING), 80));
        exact.add(candidate("smoking", List.of(ProductionMethod.SMOKING), 70));
        exact.add(candidate("campfire", List.of(ProductionMethod.CAMPFIRE_COOKING), 60));
        exact.add(candidate("stonecutting", List.of(ProductionMethod.STONECUTTING), 50));
        exact.add(candidate("smithing", List.of(ProductionMethod.SMITHING), 40));
        exact.add(candidate("smelt-craft", List.of(ProductionMethod.SMELTING, ProductionMethod.CRAFTING), 30));
        exact.add(candidate("smelt-cut", List.of(ProductionMethod.SMELTING, ProductionMethod.STONECUTTING), 20));

        List<RecipeRouteFinder.Candidate> selected = RecipeRouteFinder.selectCandidates(exact);

        assertEquals(RecipeRouteFinder.MAX_CANDIDATES, selected.size());
        assertEquals(20, selected.getFirst().plan().sourceMaterial().orElseThrow().count());
        assertTrue(selected.stream().noneMatch(candidate -> candidate.stableKey().equals("slow-crafting")));
        assertEquals(selected.size(), selected.stream().map(RecipeRouteFinder.Candidate::methodSignature)
                .distinct().count());
    }

    @Test
    void dominanceFilteringRemovesInferiorSameCapabilityDetours() {
        RecipeRouteFinder.Candidate direct = candidate(
                "direct", List.of(ProductionMethod.CRAFTING, ProductionMethod.CRAFTING), 48
        );
        RecipeRouteFinder.Candidate detour = candidate(
                "detour",
                List.of(ProductionMethod.CRAFTING, ProductionMethod.CRAFTING, ProductionMethod.CRAFTING),
                64
        );

        List<RecipeRouteFinder.Candidate> selected = RecipeRouteFinder.selectCandidates(
                List.of(detour, direct)
        );

        assertEquals(List.of("direct"), selected.stream().map(RecipeRouteFinder.Candidate::stableKey).toList());
    }

    @Test
    void dominanceFilteringPreservesMeaningfullyDifferentWorkstations() {
        RecipeRouteFinder.Candidate crafting = candidate(
                "crafting", List.of(ProductionMethod.CRAFTING), 164
        );
        RecipeRouteFinder.Candidate stonecutting = candidate(
                "stonecutting", List.of(ProductionMethod.STONECUTTING), 160
        );

        List<RecipeRouteFinder.Candidate> selected = RecipeRouteFinder.selectCandidates(
                List.of(crafting, stonecutting)
        );

        assertEquals(List.of("stonecutting", "crafting"),
                selected.stream().map(RecipeRouteFinder.Candidate::stableKey).toList());
    }

    @Test
    void groupingAndDominancePreserveIncomparableRootMaterials() {
        RecipeRouteFinder.Candidate ordinary = candidate(
                "ordinary", List.of(ProductionMethod.CRAFTING), 48
        );
        RecipeRouteFinder.Candidate withExtraRoot = candidateWithExtraRoot(
                "extra", List.of(ProductionMethod.CRAFTING), 48
        );

        List<RecipeRouteFinder.Candidate> selected = RecipeRouteFinder.selectCandidates(
                List.of(ordinary, withExtraRoot)
        );

        assertEquals(List.of("ordinary", "extra"),
                selected.stream().map(RecipeRouteFinder.Candidate::stableKey).toList());
    }

    @Test
    void routeSelectionPreservesModelOrderAndReturnsTerminalComparison() {
        RecipeRouteFinder.Candidate efficient = candidate(
                "stonecutting", List.of(ProductionMethod.STONECUTTING), 200
        );
        RecipeRouteFinder.Candidate familiar = candidate(
                "crafting", List.of(ProductionMethod.CRAFTING), 300
        );
        ProductionRouteSearchStore searches = new ProductionRouteSearchStore();
        ProductionRouteSearchStore.Search search = searches.put(List.of(efficient, familiar));
        ProductionPresentationCollector collector = new ProductionPresentationCollector();
        ObjectNode arguments = JsonNodeFactory.instance.objectNode().put("search_id", search.id());
        arguments.putArray("route_ids")
                .add(search.routes().getFirst().id())
                .add(search.routes().get(1).id());

        ToolExecutionResult result = new ShowRoutesTool(searches, collector)
                .execute(arguments, CancellationToken.NONE).toCompletableFuture().join();

        ProductionPresentation.Comparison comparison = assertInstanceOf(
                ProductionPresentation.Comparison.class, collector.snapshot().orElseThrow()
        );
        assertEquals(List.of(200L, 300L), comparison.routes().stream()
                .map(route -> route.plan().sourceMaterial().orElseThrow().count()).toList());
        assertTrue(result.terminalAnswer().orElseThrow().startsWith("Best: 200 Cobblestone"));
    }

    @Test
    void routeSelectionRejectsUnknownDuplicateAndStaleIds() {
        ProductionRouteSearchStore searches = new ProductionRouteSearchStore();
        ProductionRouteSearchStore.Search search = searches.put(List.of(candidate(
                "crafting", List.of(ProductionMethod.CRAFTING), 300
        )));
        ShowRoutesTool tool = new ShowRoutesTool(searches, new ProductionPresentationCollector());

        ObjectNode duplicate = JsonNodeFactory.instance.objectNode().put("search_id", search.id());
        duplicate.putArray("route_ids")
                .add(search.routes().getFirst().id())
                .add(search.routes().getFirst().id());
        ObjectNode unknown = JsonNodeFactory.instance.objectNode().put("search_id", search.id());
        unknown.putArray("route_ids").add("missing");
        ObjectNode stale = JsonNodeFactory.instance.objectNode().put("search_id", "missing");
        stale.putArray("route_ids").add(search.routes().getFirst().id());
        ObjectNode tooMany = JsonNodeFactory.instance.objectNode().put("search_id", search.id());
        tooMany.putArray("route_ids").add("one").add("two").add("three").add("four");

        assertTrue(execute(tool, duplicate).content().contains("unique"));
        assertTrue(execute(tool, unknown).content().contains("was not returned"));
        assertTrue(execute(tool, stale).content().contains("unavailable"));
        assertTrue(execute(tool, tooMany).content().contains("one to 3"));
    }

    @Test
    void oldRequestScopedSearchesAreBoundedAndExpire() {
        ProductionRouteSearchStore searches = new ProductionRouteSearchStore();
        String first = searches.put(List.of(candidate(
                "first", List.of(ProductionMethod.CRAFTING), 100
        ))).id();
        for (int index = 0; index < 8; index++) {
            searches.put(List.of(candidate(
                    "route-" + index, List.of(ProductionMethod.CRAFTING), 100 + index
            )));
        }

        assertTrue(searches.get(first).isEmpty());
    }

    @Test
    void comparisonRejectsMismatchedTargetsAndQuantities() {
        RecipeRouteFinder.Candidate first = candidate("one", List.of(ProductionMethod.CRAFTING), 100);
        ProductionPlan mismatched = plan(List.of(ProductionMethod.CRAFTING), 101, 201);
        ProductionPresentation.Route firstRoute = route("p1", first);
        ProductionPresentation.Route secondRoute = new ProductionPresentation.Route(
                "p2", "Crafting", mismatched
        );

        assertThrows(IllegalArgumentException.class, () -> new ProductionPresentation.Comparison(
                List.of(firstRoute, secondRoute)
        ));
    }

    private static ToolExecutionResult execute(ShowRoutesTool tool, ObjectNode arguments) {
        return tool.execute(arguments, CancellationToken.NONE).toCompletableFuture().join();
    }

    private static ProductionPresentation.Route route(String id, RecipeRouteFinder.Candidate candidate) {
        return new ProductionPresentation.Route(
                id,
                candidate.cards().getLast().method().displayName(),
                candidate.plan()
        );
    }

    private static RecipeRouteFinder.Candidate candidate(
            String key,
            List<ProductionMethod> methods,
            long sourceCount
    ) {
        List<ProductionCardData> cards = new ArrayList<>();
        for (int index = 0; index < methods.size(); index++) {
            cards.add(card(key + "-" + index, methods.get(index)));
        }
        return new RecipeRouteFinder.Candidate(cards, plan(methods, sourceCount, 200), key);
    }

    private static RecipeRouteFinder.Candidate candidateWithExtraRoot(
            String key,
            List<ProductionMethod> methods,
            long sourceCount
    ) {
        RecipeRouteFinder.Candidate base = candidate(key, methods, sourceCount);
        ProductionPlan plan = base.plan();
        ProductionPlan.Material extra = new ProductionPlan.Material(
                "minecraft:stick", "Stick", 1, List.of("minecraft:stick"), ItemStack.EMPTY
        );
        ProductionPlan expanded = new ProductionPlan(
                plan.requestedCount(),
                plan.producedCount(),
                plan.targetItemId(),
                plan.targetName(),
                plan.sourceItemId(),
                plan.sourceName(),
                plan.operations(),
                List.of(plan.rootMaterials().getFirst(), extra),
                plan.leftovers(),
                plan.genericFuelOmitted()
        );
        return new RecipeRouteFinder.Candidate(base.cards(), expanded, key);
    }

    private static ProductionPlan plan(
            List<ProductionMethod> methods,
            long sourceCount,
            long requestedCount
    ) {
        ProductionPlan.Material source = new ProductionPlan.Material(
                "minecraft:cobblestone", "Cobblestone", sourceCount,
                List.of("minecraft:cobblestone"), ItemStack.EMPTY
        );
        List<ProductionPlan.Operation> operations = new ArrayList<>();
        for (int index = 0; index < methods.size(); index++) {
            ProductionCardData card = card("test:step_" + index, methods.get(index));
            ProductionPlan.Material output = new ProductionPlan.Material(
                    "minecraft:stone_brick_stairs", "Stone Brick Stairs", requestedCount,
                    List.of("minecraft:stone_brick_stairs"), ItemStack.EMPTY
            );
            operations.add(new ProductionPlan.Operation(
                    card,
                    requestedCount,
                    index == 0 ? List.of(source) : List.of(source),
                    output,
                    0,
                    methods.get(index) == ProductionMethod.SMELTING ? 200 : 0
            ));
        }
        return new ProductionPlan(
                requestedCount,
                requestedCount,
                "minecraft:stone_brick_stairs",
                "Stone Brick Stairs",
                "minecraft:cobblestone",
                "Cobblestone",
                operations,
                List.of(source),
                List.of(),
                false
        );
    }

    private static ProductionCardData card(String id, ProductionMethod method) {
        ProductionCardData.Slot empty = ProductionCardData.Slot.of(ItemStack.EMPTY);
        return switch (method) {
            case CRAFTING -> new ProductionCardData.Crafting(id, 1, 1, List.of(empty), empty, false);
            case SMELTING, BLASTING, SMOKING, CAMPFIRE_COOKING -> new ProductionCardData.Cooking(
                    id, method, empty, new ProductionCardData.Slot(List.of(), true), empty, empty, 200, 0.0F
            );
            case STONECUTTING -> new ProductionCardData.Stonecutting(id, empty, empty, empty);
            case SMITHING -> new ProductionCardData.Smithing(id, empty, empty, empty, empty, empty);
            default -> throw new IllegalArgumentException("Unsupported test method: " + method);
        };
    }
}
