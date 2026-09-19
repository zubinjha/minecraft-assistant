package dev.zubinjha.minecraftassistant.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class NativeRecipeLayoutTest {
    @Test
    void mapsEveryNativeWorkstationTexture() {
        assertLayout(RecipeMethod.CRAFTING, NativeRecipeLayout.Kind.CRAFTING, "crafting_table");
        assertLayout(RecipeMethod.SMELTING, NativeRecipeLayout.Kind.COOKING, "furnace");
        assertLayout(RecipeMethod.BLASTING, NativeRecipeLayout.Kind.COOKING, "blast_furnace");
        assertLayout(RecipeMethod.SMOKING, NativeRecipeLayout.Kind.COOKING, "smoker");
        assertLayout(RecipeMethod.STONECUTTING, NativeRecipeLayout.Kind.STONECUTTING, "stonecutter");
        assertLayout(RecipeMethod.SMITHING, NativeRecipeLayout.Kind.SMITHING, "smithing");

        NativeRecipeLayout campfire = NativeRecipeLayout.forMethod(RecipeMethod.CAMPFIRE_COOKING);
        assertEquals(NativeRecipeLayout.Kind.CAMPFIRE, campfire.kind());
        assertFalse(campfire.hasNativeTexture());
        assertNull(campfire.litProgressSprite());
        assertNull(campfire.burnProgressSprite());
    }

    @Test
    void mapsCookingMethodsToTheirOwnNativeProgressSprites() {
        assertCookingSprites(RecipeMethod.SMELTING, "furnace");
        assertCookingSprites(RecipeMethod.BLASTING, "blast_furnace");
        assertCookingSprites(RecipeMethod.SMOKING, "smoker");
    }

    @Test
    void usesVanillaSlotCoordinatesAndCompactCrop() {
        assertEquals(176, NativeRecipeLayout.PANEL_WIDTH);
        assertEquals(84, NativeRecipeLayout.PANEL_HEIGHT);
        assertEquals(new NativeRecipeLayout.Point(30, 17), NativeRecipeLayout.CRAFTING_GRID);
        assertEquals(new NativeRecipeLayout.Point(124, 35), NativeRecipeLayout.CRAFTING_RESULT);
        assertEquals(new NativeRecipeLayout.Point(56, 17), NativeRecipeLayout.COOKING_INPUT);
        assertEquals(new NativeRecipeLayout.Point(56, 53), NativeRecipeLayout.COOKING_FUEL);
        assertEquals(new NativeRecipeLayout.Point(116, 35), NativeRecipeLayout.COOKING_RESULT);
        assertEquals(new NativeRecipeLayout.Point(20, 33), NativeRecipeLayout.STONECUTTER_INPUT);
        assertEquals(new NativeRecipeLayout.Point(143, 33), NativeRecipeLayout.STONECUTTER_RESULT);
        assertEquals(new NativeRecipeLayout.Point(8, 48), NativeRecipeLayout.SMITHING_TEMPLATE);
        assertEquals(new NativeRecipeLayout.Point(26, 48), NativeRecipeLayout.SMITHING_BASE);
        assertEquals(new NativeRecipeLayout.Point(44, 48), NativeRecipeLayout.SMITHING_ADDITION);
        assertEquals(new NativeRecipeLayout.Point(98, 48), NativeRecipeLayout.SMITHING_RESULT);
    }

    @Test
    void responsiveShellFitsSmallAndNormalGuiSizes() {
        RecipeCardScreen.ShellGeometry smallSingle = RecipeCardScreen.shellGeometry(320, 240, false);
        RecipeCardScreen.ShellGeometry smallMulti = RecipeCardScreen.shellGeometry(320, 240, true);
        RecipeCardScreen.ShellGeometry normalMulti = RecipeCardScreen.shellGeometry(854, 480, true);

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
                RecipeCardScreen.cookingDetails(200, 0.1F)
        );
    }

    private static void assertLayout(
            RecipeMethod method,
            NativeRecipeLayout.Kind kind,
            String textureName
    ) {
        NativeRecipeLayout layout = NativeRecipeLayout.forMethod(method);
        assertEquals(kind, layout.kind());
        assertTrue(layout.hasNativeTexture());
        assertEquals(
                "minecraft:textures/gui/container/" + textureName + ".png",
                layout.texture().toString()
        );
    }

    private static void assertCookingSprites(RecipeMethod method, String name) {
        NativeRecipeLayout layout = NativeRecipeLayout.forMethod(method);
        assertEquals("minecraft:container/" + name + "/lit_progress", layout.litProgressSprite().toString());
        assertEquals("minecraft:container/" + name + "/burn_progress", layout.burnProgressSprite().toString());
    }
}
