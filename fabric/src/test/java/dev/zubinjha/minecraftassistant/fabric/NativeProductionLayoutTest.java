package dev.zubinjha.minecraftassistant.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class NativeProductionLayoutTest {
    @Test
    void mapsEveryNativeWorkstationTexture() {
        assertLayout(ProductionMethod.CRAFTING, NativeProductionLayout.Kind.CRAFTING, "crafting_table");
        assertLayout(ProductionMethod.SMELTING, NativeProductionLayout.Kind.COOKING, "furnace");
        assertLayout(ProductionMethod.BLASTING, NativeProductionLayout.Kind.COOKING, "blast_furnace");
        assertLayout(ProductionMethod.SMOKING, NativeProductionLayout.Kind.COOKING, "smoker");
        assertLayout(ProductionMethod.STONECUTTING, NativeProductionLayout.Kind.STONECUTTING, "stonecutter");
        assertLayout(ProductionMethod.SMITHING, NativeProductionLayout.Kind.SMITHING, "smithing");
        assertLayout(ProductionMethod.BREWING, NativeProductionLayout.Kind.BREWING, "brewing_stand");
        assertLayout(ProductionMethod.LOOM, NativeProductionLayout.Kind.LOOM, "loom");
        assertLayout(ProductionMethod.CARTOGRAPHY, NativeProductionLayout.Kind.CARTOGRAPHY, "cartography_table");
        assertLayout(ProductionMethod.ENCHANTING, NativeProductionLayout.Kind.ENCHANTING, "enchanting_table");
        assertLayout(ProductionMethod.ANVIL, NativeProductionLayout.Kind.ANVIL, "anvil");
        assertLayout(ProductionMethod.GRINDSTONE, NativeProductionLayout.Kind.GRINDSTONE, "grindstone");

        NativeProductionLayout campfire = NativeProductionLayout.forMethod(ProductionMethod.CAMPFIRE_COOKING);
        assertEquals(NativeProductionLayout.Kind.CAMPFIRE, campfire.kind());
        assertFalse(campfire.hasNativeTexture());
        assertNull(campfire.litProgressSprite());
        assertNull(campfire.burnProgressSprite());
    }

    @Test
    void mapsCookingMethodsToTheirOwnNativeProgressSprites() {
        assertCookingSprites(ProductionMethod.SMELTING, "furnace");
        assertCookingSprites(ProductionMethod.BLASTING, "blast_furnace");
        assertCookingSprites(ProductionMethod.SMOKING, "smoker");
    }

    @Test
    void usesVanillaSlotCoordinatesAndCompactCrop() {
        assertEquals(176, NativeProductionLayout.PANEL_WIDTH);
        assertEquals(84, NativeProductionLayout.PANEL_HEIGHT);
        assertEquals(new NativeProductionLayout.Point(30, 17), NativeProductionLayout.CRAFTING_GRID);
        assertEquals(new NativeProductionLayout.Point(124, 35), NativeProductionLayout.CRAFTING_RESULT);
        assertEquals(new NativeProductionLayout.Point(56, 17), NativeProductionLayout.COOKING_INPUT);
        assertEquals(new NativeProductionLayout.Point(56, 53), NativeProductionLayout.COOKING_FUEL);
        assertEquals(new NativeProductionLayout.Point(116, 35), NativeProductionLayout.COOKING_RESULT);
        assertEquals(new NativeProductionLayout.Point(20, 33), NativeProductionLayout.STONECUTTER_INPUT);
        assertEquals(new NativeProductionLayout.Point(143, 33), NativeProductionLayout.STONECUTTER_RESULT);
        assertEquals(new NativeProductionLayout.Point(8, 48), NativeProductionLayout.SMITHING_TEMPLATE);
        assertEquals(new NativeProductionLayout.Point(26, 48), NativeProductionLayout.SMITHING_BASE);
        assertEquals(new NativeProductionLayout.Point(44, 48), NativeProductionLayout.SMITHING_ADDITION);
        assertEquals(new NativeProductionLayout.Point(98, 48), NativeProductionLayout.SMITHING_RESULT);
        assertEquals(new NativeProductionLayout.Point(17, 17), NativeProductionLayout.BREWING_FUEL);
        assertEquals(new NativeProductionLayout.Point(79, 17), NativeProductionLayout.BREWING_INGREDIENT);
        assertEquals(new NativeProductionLayout.Point(56, 51), NativeProductionLayout.BREWING_INPUT);
        assertEquals(new NativeProductionLayout.Point(102, 51), NativeProductionLayout.BREWING_RESULT);
        assertEquals(new NativeProductionLayout.Point(13, 26), NativeProductionLayout.LOOM_BANNER);
        assertEquals(new NativeProductionLayout.Point(143, 58), NativeProductionLayout.LOOM_RESULT);
        assertEquals(new NativeProductionLayout.Point(15, 15), NativeProductionLayout.CARTOGRAPHY_INPUT);
        assertEquals(new NativeProductionLayout.Point(145, 39), NativeProductionLayout.CARTOGRAPHY_RESULT);
        assertEquals(new NativeProductionLayout.Point(15, 47), NativeProductionLayout.ENCHANTING_ITEM);
        assertEquals(new NativeProductionLayout.Point(27, 47), NativeProductionLayout.ANVIL_BASE);
        assertEquals(new NativeProductionLayout.Point(134, 47), NativeProductionLayout.ANVIL_RESULT);
        assertEquals(new NativeProductionLayout.Point(59, 18), NativeProductionLayout.ANVIL_NAME_FIELD);
        assertEquals(110, NativeProductionLayout.ANVIL_NAME_FIELD_WIDTH);
        assertEquals(18, NativeProductionLayout.ANVIL_NAME_FIELD_HEIGHT);
        assertEquals(new NativeProductionLayout.Point(49, 19), NativeProductionLayout.GRINDSTONE_INPUT);
        assertEquals(new NativeProductionLayout.Point(129, 34), NativeProductionLayout.GRINDSTONE_RESULT);
    }

    @Test
    void responsiveShellFitsSmallAndNormalGuiSizes() {
        ProductionCardScreen.ShellGeometry smallSingle = ProductionCardScreen.shellGeometry(320, 240, false);
        ProductionCardScreen.ShellGeometry smallMulti = ProductionCardScreen.shellGeometry(320, 240, true);
        ProductionCardScreen.ShellGeometry normalMulti = ProductionCardScreen.shellGeometry(854, 480, true);

        assertTrue(smallSingle.fitsWithin(320, 240));
        assertTrue(smallMulti.fitsWithin(320, 240));
        assertTrue(normalMulti.fitsWithin(854, 480));
        assertEquals(176, smallMulti.width() - 20);
        assertEquals(84, smallMulti.height() - 128);
    }

    @Test
    void cookingMetadataIsFormattedForATooltipRatherThanVisibleLabels() {
        assertEquals(
                "Cooking time: 10.0 seconds\nExperience: 0.1",
                ProductionCardScreen.cookingDetails(200, 0.1F)
        );
    }

    @Test
    void usesNativeSpritesForDynamicWorkstationStates() {
        assertEquals("minecraft:container/loom/pattern_selected",
                ProductionCardScreen.LOOM_PATTERN_SELECTED.toString());
        assertEquals("minecraft:container/cartography_table/map",
                ProductionCardScreen.CARTOGRAPHY_MAP.toString());
        assertEquals("minecraft:container/cartography_table/scaled_map",
                ProductionCardScreen.CARTOGRAPHY_SCALED_MAP.toString());
        assertEquals("minecraft:container/cartography_table/duplicated_map",
                ProductionCardScreen.CARTOGRAPHY_DUPLICATED_MAP.toString());
        assertEquals("minecraft:container/cartography_table/locked",
                ProductionCardScreen.CARTOGRAPHY_LOCKED.toString());
        assertEquals("minecraft:container/enchanting_table/enchantment_slot_disabled",
                ProductionCardScreen.ENCHANTMENT_SLOT_DISABLED.toString());
    }

    private static void assertLayout(
            ProductionMethod method,
            NativeProductionLayout.Kind kind,
            String textureName
    ) {
        NativeProductionLayout layout = NativeProductionLayout.forMethod(method);
        assertEquals(kind, layout.kind());
        assertTrue(layout.hasNativeTexture());
        assertEquals(
                "minecraft:textures/gui/container/" + textureName + ".png",
                layout.texture().toString()
        );
    }

    private static void assertCookingSprites(ProductionMethod method, String name) {
        NativeProductionLayout layout = NativeProductionLayout.forMethod(method);
        assertEquals("minecraft:container/" + name + "/lit_progress", layout.litProgressSprite().toString());
        assertEquals("minecraft:container/" + name + "/burn_progress", layout.burnProgressSprite().toString());
    }
}
