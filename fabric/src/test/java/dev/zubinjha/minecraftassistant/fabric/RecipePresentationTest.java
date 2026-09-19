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

final class RecipePresentationTest {
    @Test
    void individualCardsRemainOrderedAndDeduplicated() {
        RecipeCardData first = crafting("minecraft:glass");
        RecipeCardData second = crafting("minecraft:glass_pane");
        RecipePresentationCollector collector = new RecipePresentationCollector();

        collector.add(first);
        collector.add(second);
        collector.add(first);

        RecipePresentation.Collection collection = assertInstanceOf(
                RecipePresentation.Collection.class,
                collector.snapshot().orElseThrow()
        );
        assertEquals(List.of(first, second), collection.cards());
    }

    @Test
    void explicitSequencePreservesEveryStepAndTakesPrecedence() {
        RecipeCardData unrelated = crafting("minecraft:chest");
        RecipeCardData first = cooking("minecraft:glass");
        RecipeCardData second = crafting("minecraft:glass_pane");
        RecipePresentationCollector collector = new RecipePresentationCollector();
        collector.add(unrelated);

        collector.setSequence(List.of(first, second, first));

        RecipePresentation.Sequence sequence = assertInstanceOf(
                RecipePresentation.Sequence.class,
                collector.snapshot().orElseThrow()
        );
        assertEquals(List.of(first, second, first), sequence.cards());
    }

    @Test
    void presentationSnapshotsAreImmutable() {
        RecipePresentation.Sequence sequence = new RecipePresentation.Sequence(List.of(
                cooking("minecraft:glass"),
                crafting("minecraft:glass_pane")
        ));
        RecipePresentationStore store = new RecipePresentationStore(2);

        String token = store.put(sequence);
        RecipePresentation cached = store.get(token).orElseThrow();

        assertSame(sequence, cached);
        assertThrows(UnsupportedOperationException.class, () -> cached.cards().add(
                crafting("minecraft:chest")
        ));
    }

    @Test
    void storeUsesOpaqueTokensAndEvictsTheOldestEntry() {
        RecipePresentationStore store = new RecipePresentationStore(2);
        String first = store.put(new RecipePresentation.Single(crafting("minecraft:chest")));
        String second = store.put(new RecipePresentation.Single(crafting("minecraft:glass")));
        String third = store.put(new RecipePresentation.Single(crafting(
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
    void labelsAndHoverTextDescribeEachPresentationType() {
        RecipeCardData glass = cooking("minecraft:glass");
        RecipeCardData panes = crafting("minecraft:glass_pane");
        RecipePresentation single = new RecipePresentation.Single(glass);
        RecipePresentation sequence = new RecipePresentation.Sequence(List.of(glass, panes));
        RecipePresentation collection = new RecipePresentation.Collection(List.of(glass, panes));

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
    }

    @Test
    void sequenceNavigationStopsAtItsBoundaries() {
        assertEquals(0, RecipeCardScreen.selectedIndex(0, -1, 2));
        assertEquals(1, RecipeCardScreen.selectedIndex(0, 1, 2));
        assertEquals(1, RecipeCardScreen.selectedIndex(1, 2, 2));
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
}
