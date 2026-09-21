package dev.zubinjha.minecraftassistant.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

final class ProductionPresentationTest {
    @Test
    void ordinaryAnswersDoNotCreateAPreviewWithoutANativeTool() {
        assertTrue(new ProductionPresentationCollector().snapshot().isEmpty());
    }

    @Test
    void individualCardsRemainOrderedAndDeduplicated() {
        ProductionCardData first = crafting("minecraft:glass");
        ProductionCardData second = crafting("minecraft:glass_pane");
        ProductionPresentationCollector collector = new ProductionPresentationCollector();

        collector.add(first);
        collector.add(second);
        collector.add(first);

        ProductionPresentation.Collection collection = assertInstanceOf(
                ProductionPresentation.Collection.class,
                collector.snapshot().orElseThrow()
        );
        assertEquals(List.of(first, second), collection.cards());
    }

    @Test
    void explicitSequencePreservesEveryStepAndTakesPrecedence() {
        ProductionCardData unrelated = crafting("minecraft:chest");
        ProductionCardData first = cooking("minecraft:glass");
        ProductionCardData second = crafting("minecraft:glass_pane");
        ProductionPresentationCollector collector = new ProductionPresentationCollector();
        collector.add(unrelated);

        collector.setSequence(List.of(first, second, first));

        ProductionPresentation.Sequence sequence = assertInstanceOf(
                ProductionPresentation.Sequence.class,
                collector.snapshot().orElseThrow()
        );
        assertEquals(List.of(first, second, first), sequence.cards());
    }

    @Test
    void recipeSequenceCannotBeOverwrittenByALaterNativeSequence() {
        ProductionCardData first = cooking("minecraft:glass");
        ProductionCardData second = crafting("minecraft:glass_pane");
        ProductionCardData brewing = brewing("minecraft_assistant:brewing/awkward");
        ProductionPresentationCollector collector = new ProductionPresentationCollector();

        collector.setSequence(List.of(first, second));
        collector.setSequence(List.of(brewing, brewing));

        ProductionPresentation.Sequence sequence = assertInstanceOf(
                ProductionPresentation.Sequence.class,
                collector.snapshot().orElseThrow()
        );
        assertEquals(List.of(first, second), sequence.cards());
    }

    @Test
    void completeQuantityPlanTakesPrecedenceAndIsImmutable() {
        ProductionCardData first = cooking("minecraft:stone");
        ProductionCardData second = crafting("minecraft:stone_brick_slab");
        ProductionPlan plan = quantityPlan();
        ProductionPresentationCollector collector = new ProductionPresentationCollector();
        collector.add(crafting("minecraft:chest"));
        collector.setSequence(List.of(first, second));

        collector.setPlanned(plan);

        ProductionPresentation.Plan planned = assertInstanceOf(
                ProductionPresentation.Plan.class,
                collector.snapshot().orElseThrow()
        );
        assertEquals(plan, planned.plan());
        assertThrows(UnsupportedOperationException.class, () ->
                planned.plan().operations().add(plan.operations().getFirst()));
    }

    @Test
    void routeComparisonIsImmutableAndGeneratesAuthoritativeSummary() {
        ProductionPlan efficientPlan = routePlan(ProductionMethod.STONECUTTING, 200);
        ProductionPlan familiarPlan = routePlan(ProductionMethod.CRAFTING, 300);
        ProductionPresentation.Route efficient = new ProductionPresentation.Route(
                "p1", "Stonecutting", efficientPlan
        );
        ProductionPresentation.Route familiar = new ProductionPresentation.Route(
                "p2", "Crafting", familiarPlan
        );
        ProductionPresentation.Comparison comparison = new ProductionPresentation.Comparison(
                List.of(efficient, familiar)
        );

        assertEquals(
                "Best: 200 Cobblestone → 200 Stone Brick Stairs via Stonecutting. "
                        + "Crafting route: 300 Cobblestone → 200 Stone Brick Stairs via Crafting.",
                comparison.authoritativeSummary().orElseThrow()
        );
        assertEquals("Compare 2 Routes", MinecraftAssistantRuntime.presentationButtonLabel(comparison));
        assertEquals("Cutter · 200", ProductionCardScreen.routeTabLabel(efficient));
        assertEquals("[Cutter · 200]", ProductionCardScreen.routeButtonLabel(efficient, true));
        assertThrows(UnsupportedOperationException.class, () -> comparison.routes().add(efficient));
        assertThrows(IllegalArgumentException.class, () -> new ProductionPresentation.Comparison(
                List.of(efficient, efficient)
        ));
    }

    @Test
    void nativeSequenceCombinedWithIndependentGuideBecomesACollection() {
        ProductionCardData brewingOne = brewing("minecraft_assistant:brewing/1/swiftness");
        ProductionCardData brewingTwo = brewing("minecraft_assistant:brewing/2/swiftness");
        ProductionCardData map = cartography("minecraft_assistant:cartography/lock");
        ProductionPresentationCollector collector = new ProductionPresentationCollector();

        collector.setSequence(List.of(brewingOne, brewingTwo));
        collector.add(map);

        ProductionPresentation.Collection collection = assertInstanceOf(
                ProductionPresentation.Collection.class,
                collector.snapshot().orElseThrow()
        );
        assertEquals(List.of(brewingOne, brewingTwo, map), collection.cards());
        assertFalse(collection.recipeOnly());
    }

    @Test
    void presentationSnapshotsAreImmutable() {
        ProductionPresentation.Sequence sequence = new ProductionPresentation.Sequence(List.of(
                cooking("minecraft:glass"),
                crafting("minecraft:glass_pane")
        ));
        ProductionPresentationStore store = new ProductionPresentationStore(2);

        String token = store.put(sequence);
        ProductionPresentation cached = store.get(token).orElseThrow();

        assertSame(sequence, cached);
        assertThrows(UnsupportedOperationException.class, () -> cached.cards().add(
                crafting("minecraft:chest")
        ));
    }

    @Test
    void storeUsesOpaqueTokensAndEvictsTheOldestEntry() {
        ProductionPresentationStore store = new ProductionPresentationStore(2);
        String first = store.put(new ProductionPresentation.Single(crafting("minecraft:chest")));
        String second = store.put(new ProductionPresentation.Single(crafting("minecraft:glass")));
        String third = store.put(new ProductionPresentation.Single(crafting(
                "minecraft:glass_pane"
        )));

        assertTrue(first.matches("r[0-9a-z]+"));
        assertFalse(first.equals(second));
        assertEquals(2, store.size());
        assertTrue(store.get(first).isEmpty());
        assertTrue(store.get(second).isPresent());
        assertTrue(store.get(third).isPresent());
    }

    @Test
    void clearingStoreExpiresEveryPresentation() {
        ProductionPresentationStore store = new ProductionPresentationStore(2);
        String token = store.put(new ProductionPresentation.Single(crafting("minecraft:chest")));

        store.clear();

        assertEquals(0, store.size());
        assertTrue(store.get(token).isEmpty());
    }

    @Test
    void labelsAndHoverTextDescribeEachPresentationType() {
        ProductionCardData glass = cooking("minecraft:glass");
        ProductionCardData panes = crafting("minecraft:glass_pane");
        ProductionPresentation single = new ProductionPresentation.Single(glass);
        ProductionPresentation sequence = new ProductionPresentation.Sequence(List.of(glass, panes));
        ProductionPresentation collection = new ProductionPresentation.Collection(List.of(glass, panes));

        assertEquals("Show Recipe", MinecraftAssistantRuntime.presentationButtonLabel(single));
        assertEquals("Show 2 Steps", MinecraftAssistantRuntime.presentationButtonLabel(sequence));
        assertEquals("Show 2 Recipes", MinecraftAssistantRuntime.presentationButtonLabel(collection));
        assertEquals(
                "Show Recipe production process (2 steps)",
                MinecraftAssistantRuntime.presentationHoverText(sequence).getString()
        );
        assertEquals(
                "Show Recipe and Recipe (2-recipe collection)",
                MinecraftAssistantRuntime.presentationHoverText(collection).getString()
        );

        ProductionPresentation brewingSingle = new ProductionPresentation.Single(
                brewing("minecraft_assistant:brewing/swiftness")
        );
        ProductionPresentation mixed = new ProductionPresentation.Collection(List.of(
                glass,
                brewing("minecraft_assistant:brewing/swiftness")
        ));
        assertEquals("Show Brewing", MinecraftAssistantRuntime.presentationButtonLabel(brewingSingle));
        assertEquals("Show 2 Guides", MinecraftAssistantRuntime.presentationButtonLabel(mixed));
        assertEquals(
                "Show Recipe and Recipe (2-guide collection)",
                MinecraftAssistantRuntime.presentationHoverText(mixed).getString()
        );
    }

    @Test
    void sequenceNavigationStopsAtItsBoundaries() {
        assertEquals(0, ProductionCardScreen.selectedIndex(0, -1, 2));
        assertEquals(1, ProductionCardScreen.selectedIndex(0, 1, 2));
        assertEquals(1, ProductionCardScreen.selectedIndex(1, 2, 2));
        assertFalse(ProductionCardScreen.showsStepSelector(1));
        assertTrue(ProductionCardScreen.showsStepSelector(2));

        ProductionCardScreen.ShellGeometry plan = ProductionCardScreen.planShellGeometry(
                320, 240, false, true
        );
        ProductionCardScreen.ShellGeometry comparison = ProductionCardScreen.planShellGeometry(
                320, 240, true, true
        );
        ProductionCardScreen.ShellGeometry highScale = ProductionCardScreen.planShellGeometry(
                213, 180, false, true
        );
        ProductionCardScreen.ShellGeometry sequence = ProductionCardScreen.sequenceShellGeometry(
                320, 240
        );
        ProductionCardScreen.ShellGeometry highScaleSequence = ProductionCardScreen.sequenceShellGeometry(
                213, 196
        );
        assertTrue(plan.fitsWithin(320, 240));
        assertTrue(comparison.fitsWithin(320, 240));
        assertTrue(highScale.fitsWithin(213, 180));
        assertTrue(sequence.fitsWithin(320, 240));
        assertTrue(highScaleSequence.fitsWithin(213, 196));
        assertTrue(plan.height() < ProductionCardScreen.shellGeometry(320, 240, true).height());
        assertTrue(sequence.height() < ProductionCardScreen.shellGeometry(320, 240, true).height());
    }

    @Test
    void authoritativeQuantitySummaryReplacesModelArithmeticButKeepsSource() {
        ProductionPresentation presentation = new ProductionPresentation.Plan(quantityPlan());

        String answer = MinecraftAssistantRuntime.authoritativeAnswer(
                "You need 999 cobblestone.\nSource: https://minecraft.wiki/w/Stone_Bricks",
                presentation
        );

        assertEquals(quantityPlan().summary()
                + "\nSource: https://minecraft.wiki/w/Stone_Bricks", answer);
        assertEquals("Show Plan", MinecraftAssistantRuntime.presentationButtonLabel(presentation));
        assertEquals("12 crafts · 36 in > 72 out · 8 surplus",
                ProductionCardScreen.stepDetails(quantityPlan().operations().getFirst()));
        assertEquals("1.2k", ProductionCardScreen.compactCount(1_234));
        assertEquals("12k", ProductionCardScreen.compactCount(12_345));
    }

    private static ProductionCardData crafting(String id) {
        ProductionCardData.Slot resultSlot = ProductionCardData.Slot.of(ItemStack.EMPTY);
        return new ProductionCardData.Crafting(id, 1, 1, List.of(resultSlot), resultSlot, false);
    }

    private static ProductionCardData cooking(String id) {
        ProductionCardData.Slot resultSlot = ProductionCardData.Slot.of(ItemStack.EMPTY);
        return new ProductionCardData.Cooking(
                id,
                ProductionMethod.SMELTING,
                ProductionCardData.Slot.of(ItemStack.EMPTY),
                new ProductionCardData.Slot(List.of(), true),
                resultSlot,
                ProductionCardData.Slot.of(ItemStack.EMPTY),
                200,
                0.1F
        );
    }

    private static ProductionCardData brewing(String id) {
        ProductionCardData.Slot empty = ProductionCardData.Slot.of(ItemStack.EMPTY);
        return new ProductionCardData.Brewing(id, empty, empty, empty, empty, empty);
    }

    private static ProductionCardData cartography(String id) {
        ProductionCardData.Slot empty = ProductionCardData.Slot.of(ItemStack.EMPTY);
        return new ProductionCardData.Cartography(id, "lock", empty, empty, empty, empty);
    }

    private static ProductionPlan quantityPlan() {
        ProductionCardData card = crafting("minecraft:stone_brick_slab");
        ProductionPlan.Material input = new ProductionPlan.Material(
                "minecraft:stone_bricks", "Stone Bricks", 36, List.of("minecraft:stone_bricks"),
                ItemStack.EMPTY
        );
        ProductionPlan.Material output = new ProductionPlan.Material(
                "minecraft:stone_brick_slab", "Stone Brick Slab", 72,
                List.of("minecraft:stone_brick_slab"), ItemStack.EMPTY
        );
        ProductionPlan.Operation step = new ProductionPlan.Operation(
                card,
                12,
                List.of(input),
                output,
                8,
                0
        );
        return new ProductionPlan(
                64,
                72,
                "minecraft:stone_brick_slab",
                "Stone Brick Slab",
                "minecraft:stone_bricks",
                "Stone Bricks",
                List.of(step),
                List.of(input),
                List.of(new ProductionPlan.Material(
                        "minecraft:stone_brick_slab", "Stone Brick Slab", 8,
                        List.of("minecraft:stone_brick_slab"), ItemStack.EMPTY
                )),
                false
        );
    }

    private static ProductionPlan routePlan(ProductionMethod method, long sourceCount) {
        ProductionPlan.Material source = new ProductionPlan.Material(
                "minecraft:cobblestone", "Cobblestone", sourceCount, List.of("minecraft:cobblestone"),
                ItemStack.EMPTY
        );
        ProductionCardData card = methodCard("minecraft:stone_brick_stairs", method);
        ProductionPlan.Material output = new ProductionPlan.Material(
                "minecraft:stone_brick_stairs", "Stone Brick Stairs", 200,
                List.of("minecraft:stone_brick_stairs"), ItemStack.EMPTY
        );
        ProductionPlan.Operation step = new ProductionPlan.Operation(
                card,
                200,
                List.of(source),
                output,
                0,
                method == ProductionMethod.SMELTING ? 200 : 0
        );
        return new ProductionPlan(
                200,
                200,
                "minecraft:stone_brick_stairs",
                "Stone Brick Stairs",
                "minecraft:cobblestone",
                "Cobblestone",
                List.of(step),
                List.of(source),
                List.of(),
                false
        );
    }

    private static ProductionCardData methodCard(String id, ProductionMethod method) {
        ProductionCardData.Slot empty = ProductionCardData.Slot.of(ItemStack.EMPTY);
        return switch (method) {
            case CRAFTING -> new ProductionCardData.Crafting(id, 1, 1, List.of(empty), empty, false);
            case STONECUTTING -> new ProductionCardData.Stonecutting(id, empty, empty, empty);
            case SMELTING, BLASTING, SMOKING, CAMPFIRE_COOKING -> new ProductionCardData.Cooking(
                    id, method, empty, new ProductionCardData.Slot(List.of(), true), empty, empty, 200, 0.0F
            );
            case SMITHING -> new ProductionCardData.Smithing(id, empty, empty, empty, empty, empty);
            default -> throw new IllegalArgumentException("Unsupported test method: " + method);
        };
    }
}
