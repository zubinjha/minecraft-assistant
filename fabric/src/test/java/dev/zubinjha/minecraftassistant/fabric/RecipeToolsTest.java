package dev.zubinjha.minecraftassistant.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.zubinjha.minecraftassistant.core.CancellationToken;
import dev.zubinjha.minecraftassistant.core.ToolExecutionResult;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

final class RecipeToolsTest {
    @Test
    void showProcessResolvesAllStepsInOrderAndOverridesCollectedCards() {
        RecipeCardData unrelated = crafting("minecraft:chest");
        RecipeCardData glass = cooking("minecraft:glass");
        RecipeCardData panes = crafting("minecraft:glass_pane");
        RecordingLookup lookup = new RecordingLookup()
                .result("minecraft:glass", RecipeMethod.SMELTING, new RecipeLookupResult.Found(glass))
                .result("minecraft:glass_pane", RecipeMethod.CRAFTING, new RecipeLookupResult.Found(panes));
        RecipePresentationCollector collector = new RecipePresentationCollector();
        collector.add(unrelated);

        ToolExecutionResult result = execute(
                new ShowProcessTool(Runnable::run, lookup, collector),
                processArguments(
                        step("minecraft:glass", RecipeMethod.SMELTING),
                        step("minecraft:glass_pane", RecipeMethod.CRAFTING)
                )
        );

        assertTrue(result.content().contains("2-step production process"));
        assertEquals(List.of(
                "minecraft:glass|smelting",
                "minecraft:glass_pane|crafting"
        ), lookup.requests);
        RecipePresentation.Sequence sequence = assertInstanceOf(
                RecipePresentation.Sequence.class,
                collector.snapshot().orElseThrow()
        );
        assertEquals(List.of(glass, panes), sequence.cards());
    }

    @Test
    void partialFailureDoesNotPublishAPartialSequence() {
        RecipeCardData unrelated = crafting("minecraft:chest");
        RecipeCardData glass = cooking("minecraft:glass");
        RecordingLookup lookup = new RecordingLookup()
                .result("minecraft:glass", RecipeMethod.SMELTING, new RecipeLookupResult.Found(glass))
                .result(
                        "minecraft:glass_pane",
                        RecipeMethod.CRAFTING,
                        new RecipeLookupResult.Missing("unsupported display")
                );
        RecipePresentationCollector collector = new RecipePresentationCollector();
        collector.add(unrelated);

        ToolExecutionResult result = execute(
                new ShowProcessTool(Runnable::run, lookup, collector),
                processArguments(
                        step("minecraft:glass", RecipeMethod.SMELTING),
                        step("minecraft:glass_pane", RecipeMethod.CRAFTING)
                )
        );

        assertTrue(result.content().contains("step 2"));
        RecipePresentation.Single fallback = assertInstanceOf(
                RecipePresentation.Single.class,
                collector.snapshot().orElseThrow()
        );
        assertEquals(unrelated, fallback.card());
    }

    @Test
    void ambiguityNamesTheFailingStepAndCandidates() {
        RecipeCardData glass = cooking("minecraft:glass");
        RecordingLookup lookup = new RecordingLookup()
                .result("minecraft:glass", RecipeMethod.SMELTING, new RecipeLookupResult.Found(glass))
                .result(
                        "minecraft:stone_bricks",
                        RecipeMethod.CRAFTING,
                        new RecipeLookupResult.Ambiguous(List.of(
                                new RecipeLookupResult.Candidate(
                                        "minecraft:stone_bricks", RecipeMethod.CRAFTING
                                ),
                                new RecipeLookupResult.Candidate(
                                        "minecraft:stone_bricks_from_stone_stonecutting",
                                        RecipeMethod.STONECUTTING
                                )
                        ))
                );
        RecipePresentationCollector collector = new RecipePresentationCollector();

        ToolExecutionResult result = execute(
                new ShowProcessTool(Runnable::run, lookup, collector),
                processArguments(
                        step("minecraft:glass", RecipeMethod.SMELTING),
                        step("minecraft:stone_bricks", RecipeMethod.CRAFTING)
                )
        );

        assertTrue(result.content().contains("step 2 is ambiguous"));
        assertTrue(result.content().contains("minecraft:stone_bricks (crafting)"));
        assertTrue(collector.snapshot().isEmpty());
    }

    @Test
    void showProcessEnforcesTwoToSixSteps() {
        RecordingLookup lookup = new RecordingLookup();
        RecipePresentationCollector collector = new RecipePresentationCollector();
        ShowProcessTool tool = new ShowProcessTool(Runnable::run, lookup, collector);

        ToolExecutionResult tooShort = execute(tool, processArguments(
                step("minecraft:glass", RecipeMethod.SMELTING)
        ));
        ObjectNode tooLongArguments = JsonNodeFactory.instance.objectNode();
        ArrayNode tooLong = tooLongArguments.putArray("steps");
        for (int index = 0; index < 7; index++) {
            tooLong.add(step("minecraft:glass", RecipeMethod.SMELTING));
        }
        ToolExecutionResult tooLongResult = execute(tool, tooLongArguments);

        assertTrue(tooShort.content().contains("between 2 and 6"));
        assertTrue(tooLongResult.content().contains("between 2 and 6"));
        assertTrue(lookup.requests.isEmpty());
        assertTrue(collector.snapshot().isEmpty());
    }

    @Test
    void independentRecipeCallsProduceAnOrderedCollection() {
        RecipeCardData glass = cooking("minecraft:glass");
        RecipeCardData panes = crafting("minecraft:glass_pane");
        RecordingLookup lookup = new RecordingLookup()
                .result("minecraft:glass", RecipeMethod.SMELTING, new RecipeLookupResult.Found(glass))
                .result("minecraft:glass_pane", RecipeMethod.CRAFTING, new RecipeLookupResult.Found(panes));
        RecipePresentationCollector collector = new RecipePresentationCollector();
        RecipeCardRequestTool tool = new RecipeCardRequestTool(Runnable::run, lookup, collector);

        execute(tool, recipeArguments("minecraft:glass", RecipeMethod.SMELTING));
        execute(tool, recipeArguments("minecraft:glass_pane", RecipeMethod.CRAFTING));
        execute(tool, recipeArguments("minecraft:glass", RecipeMethod.SMELTING));

        RecipePresentation.Collection collection = assertInstanceOf(
                RecipePresentation.Collection.class,
                collector.snapshot().orElseThrow()
        );
        assertEquals(List.of(glass, panes), collection.cards());
    }

    private static ToolExecutionResult execute(
            dev.zubinjha.minecraftassistant.core.Tool tool,
            ObjectNode arguments
    ) {
        return tool.execute(arguments, CancellationToken.NONE).toCompletableFuture().join();
    }

    private static ObjectNode processArguments(ObjectNode... steps) {
        ObjectNode arguments = JsonNodeFactory.instance.objectNode();
        ArrayNode array = arguments.putArray("steps");
        for (ObjectNode step : steps) {
            array.add(step);
        }
        return arguments;
    }

    private static ObjectNode step(String recipeId, RecipeMethod method) {
        return JsonNodeFactory.instance.objectNode()
                .put("recipe_id", recipeId)
                .put("method", method.toolValue());
    }

    private static ObjectNode recipeArguments(String recipeId, RecipeMethod method) {
        return step(recipeId, method);
    }

    private static RecipeCardData crafting(String id) {
        RecipeCardData.Slot resultSlot = RecipeCardData.Slot.of(ItemStack.EMPTY);
        return new RecipeCardData.Crafting(id, 1, 1, List.of(resultSlot), resultSlot, false);
    }

    private static RecipeCardData cooking(String id) {
        RecipeCardData.Slot resultSlot = RecipeCardData.Slot.of(ItemStack.EMPTY);
        return new RecipeCardData.Cooking(
                id,
                RecipeMethod.SMELTING,
                RecipeCardData.Slot.of(ItemStack.EMPTY),
                new RecipeCardData.Slot(List.of(), true),
                resultSlot,
                RecipeCardData.Slot.of(ItemStack.EMPTY),
                200,
                0.1F
        );
    }

    private static final class RecordingLookup implements RecipeLookup {
        private final Map<String, RecipeLookupResult> results = new HashMap<>();
        private final List<String> requests = new ArrayList<>();

        RecordingLookup result(String id, RecipeMethod method, RecipeLookupResult result) {
            results.put(key(id, method), result);
            return this;
        }

        @Override
        public RecipeLookupResult resolve(String recipeId, Optional<RecipeMethod> method) {
            RecipeMethod requiredMethod = method.orElseThrow();
            requests.add(key(recipeId, requiredMethod));
            return results.getOrDefault(
                    key(recipeId, requiredMethod),
                    new RecipeLookupResult.Missing("not found")
            );
        }

        private static String key(String id, RecipeMethod method) {
            return id + "|" + method.toolValue();
        }
    }
}
