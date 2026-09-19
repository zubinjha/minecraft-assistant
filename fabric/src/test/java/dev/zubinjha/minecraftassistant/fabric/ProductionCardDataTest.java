package dev.zubinjha.minecraftassistant.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

final class ProductionCardDataTest {
    private static final ProductionCardData.Slot EMPTY_SLOT = ProductionCardData.Slot.of(ItemStack.EMPTY);

    @Test
    void parsesSupportedMethodNames() {
        assertEquals(Optional.of(ProductionMethod.CAMPFIRE_COOKING), ProductionMethod.parse("campfire-cooking"));
        assertEquals(Optional.of(ProductionMethod.SMITHING), ProductionMethod.parse(" SMITHING "));
        assertEquals(Optional.of(ProductionMethod.BREWING), ProductionMethod.parse("brewing"));
        assertEquals(Optional.empty(), ProductionMethod.parse("unsupported"));
    }

    @Test
    void selectionRequiresMethodWhenOutputHasDifferentProductionMethods() {
        ProductionCardData crafting = crafting("minecraft:iron_ingot");
        ProductionCardData smelting = cooking(
                "minecraft:iron_ingot_from_smelting_raw_iron",
                ProductionMethod.SMELTING
        );

        RecipeLookupResult.Ambiguous choices = assertInstanceOf(
                RecipeLookupResult.Ambiguous.class,
                RecipeCardResolver.select(List.of(crafting, smelting), Optional.empty())
        );
        assertEquals(2, choices.candidates().size());

        RecipeLookupResult.Found selected = assertInstanceOf(
                RecipeLookupResult.Found.class,
                RecipeCardResolver.select(List.of(crafting, smelting), Optional.of(ProductionMethod.SMELTING))
        );
        assertSame(smelting, selected.card());
    }

    @Test
    void selectionDoesNotChooseArbitrarilyBetweenSameMethodVariants() {
        ProductionCardData rawOre = cooking(
                "minecraft:iron_ingot_from_smelting_raw_iron",
                ProductionMethod.SMELTING
        );
        ProductionCardData oreBlock = cooking(
                "minecraft:iron_ingot_from_smelting_iron_ore",
                ProductionMethod.SMELTING
        );

        assertInstanceOf(
                RecipeLookupResult.Ambiguous.class,
                RecipeCardResolver.select(List.of(rawOre, oreBlock), Optional.of(ProductionMethod.SMELTING))
        );
        assertInstanceOf(
                RecipeLookupResult.Missing.class,
                RecipeCardResolver.select(List.of(rawOre), Optional.of(ProductionMethod.BLASTING))
        );
    }

    @Test
    void hoverTextNamesTheResultAndMethod() {
        assertEquals(
                "Show Block of Diamond crafting recipe",
                MinecraftAssistantRuntime.recipeHoverText(
                        Component.literal("Block of Diamond"),
                        ProductionMethod.CRAFTING
                ).getString()
        );
    }

    @Test
    void slotsCycleAlternativesOncePerSecond() {
        assertEquals(0, ProductionCardData.Slot.displayIndex(2, 0));
        assertEquals(1, ProductionCardData.Slot.displayIndex(2, 1_000));
        assertEquals(0, ProductionCardData.Slot.displayIndex(2, 2_000));
        assertEquals(0, ProductionCardData.Slot.displayIndex(0, 5_000));
    }

    @Test
    void anyFuelUsesAnAccurateGenericDescription() {
        ProductionCardData.Slot fuel = new ProductionCardData.Slot(List.of(), true);

        assertEquals("any valid fuel", fuel.description());
    }

    @Test
    void cookingCardRetainsMethodTimeAndExperience() {
        ProductionCardData.Cooking card = cooking("minecraft:glass", ProductionMethod.SMELTING);

        assertEquals(ProductionMethod.SMELTING, card.method());
        assertEquals(200, card.durationTicks());
        assertEquals(0.1F, card.experience());
        assertSame(EMPTY_SLOT, card.station());
    }

    private static ProductionCardData crafting(String id) {
        return new ProductionCardData.Crafting(
                id,
                1,
                1,
                List.of(EMPTY_SLOT),
                EMPTY_SLOT,
                false
        );
    }

    private static ProductionCardData.Cooking cooking(String id, ProductionMethod method) {
        return new ProductionCardData.Cooking(
                id,
                method,
                EMPTY_SLOT,
                new ProductionCardData.Slot(List.of(), true),
                EMPTY_SLOT,
                EMPTY_SLOT,
                200,
                0.1F
        );
    }
}
