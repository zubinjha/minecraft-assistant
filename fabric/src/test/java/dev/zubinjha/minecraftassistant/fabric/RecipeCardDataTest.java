package dev.zubinjha.minecraftassistant.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

final class RecipeCardDataTest {
    private static final RecipeCardData.Slot EMPTY_SLOT = RecipeCardData.Slot.of(ItemStack.EMPTY);

    @Test
    void parsesSupportedMethodNames() {
        assertEquals(Optional.of(RecipeMethod.CAMPFIRE_COOKING), RecipeMethod.parse("campfire-cooking"));
        assertEquals(Optional.of(RecipeMethod.SMITHING), RecipeMethod.parse(" SMITHING "));
        assertEquals(Optional.empty(), RecipeMethod.parse("brewing"));
    }

    @Test
    void selectionRequiresMethodWhenOutputHasDifferentProductionMethods() {
        RecipeCardData crafting = crafting("minecraft:iron_ingot");
        RecipeCardData smelting = cooking(
                "minecraft:iron_ingot_from_smelting_raw_iron",
                RecipeMethod.SMELTING
        );

        RecipeLookupResult.Ambiguous choices = assertInstanceOf(
                RecipeLookupResult.Ambiguous.class,
                RecipeCardResolver.select(List.of(crafting, smelting), Optional.empty())
        );
        assertEquals(2, choices.candidates().size());

        RecipeLookupResult.Found selected = assertInstanceOf(
                RecipeLookupResult.Found.class,
                RecipeCardResolver.select(List.of(crafting, smelting), Optional.of(RecipeMethod.SMELTING))
        );
        assertSame(smelting, selected.card());
    }

    @Test
    void selectionDoesNotChooseArbitrarilyBetweenSameMethodVariants() {
        RecipeCardData rawOre = cooking(
                "minecraft:iron_ingot_from_smelting_raw_iron",
                RecipeMethod.SMELTING
        );
        RecipeCardData oreBlock = cooking(
                "minecraft:iron_ingot_from_smelting_iron_ore",
                RecipeMethod.SMELTING
        );

        assertInstanceOf(
                RecipeLookupResult.Ambiguous.class,
                RecipeCardResolver.select(List.of(rawOre, oreBlock), Optional.of(RecipeMethod.SMELTING))
        );
        assertInstanceOf(
                RecipeLookupResult.Missing.class,
                RecipeCardResolver.select(List.of(rawOre), Optional.of(RecipeMethod.BLASTING))
        );
    }

    @Test
    void hoverTextNamesTheResultAndMethod() {
        assertEquals(
                "Show Block of Diamond crafting recipe",
                MinecraftAssistantRuntime.recipeHoverText(
                        Component.literal("Block of Diamond"),
                        RecipeMethod.CRAFTING
                ).getString()
        );
    }

    @Test
    void slotsCycleAlternativesOncePerSecond() {
        assertEquals(0, RecipeCardData.Slot.displayIndex(2, 0));
        assertEquals(1, RecipeCardData.Slot.displayIndex(2, 1_000));
        assertEquals(0, RecipeCardData.Slot.displayIndex(2, 2_000));
        assertEquals(0, RecipeCardData.Slot.displayIndex(0, 5_000));
    }

    @Test
    void anyFuelUsesAnAccurateGenericDescription() {
        RecipeCardData.Slot fuel = new RecipeCardData.Slot(List.of(), true);

        assertEquals("any valid fuel", fuel.description());
    }

    @Test
    void cookingCardRetainsMethodTimeAndExperience() {
        RecipeCardData.Cooking card = cooking("minecraft:glass", RecipeMethod.SMELTING);

        assertEquals(RecipeMethod.SMELTING, card.method());
        assertEquals(200, card.durationTicks());
        assertEquals(0.1F, card.experience());
        assertSame(EMPTY_SLOT, card.station());
    }

    private static RecipeCardData crafting(String id) {
        return new RecipeCardData.Crafting(
                id,
                1,
                1,
                List.of(EMPTY_SLOT),
                EMPTY_SLOT,
                false
        );
    }

    private static RecipeCardData.Cooking cooking(String id, RecipeMethod method) {
        return new RecipeCardData.Cooking(
                id,
                method,
                EMPTY_SLOT,
                new RecipeCardData.Slot(List.of(), true),
                EMPTY_SLOT,
                EMPTY_SLOT,
                200,
                0.1F
        );
    }
}
