package dev.zubinjha.minecraftassistant.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

final class ProductionPresentationTest {
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
}
