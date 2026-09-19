package dev.zubinjha.minecraftassistant.fabric;

import net.minecraft.resources.Identifier;

record NativeRecipeLayout(
        Kind kind,
        Identifier texture,
        Identifier litProgressSprite,
        Identifier burnProgressSprite
) {
    static final int PANEL_WIDTH = 176;
    static final int PANEL_HEIGHT = 84;

    static final Point CRAFTING_GRID = new Point(30, 17);
    static final Point CRAFTING_RESULT = new Point(124, 35);

    static final Point COOKING_INPUT = new Point(56, 17);
    static final Point COOKING_FUEL = new Point(56, 53);
    static final Point COOKING_RESULT = new Point(116, 35);
    static final Point COOKING_FLAME = new Point(56, 36);
    static final Point COOKING_PROGRESS = new Point(79, 34);

    static final Point STONECUTTER_INPUT = new Point(20, 33);
    static final Point STONECUTTER_CHOICE = new Point(52, 16);
    static final Point STONECUTTER_CHOICE_BACKGROUND = new Point(52, 15);
    static final Point STONECUTTER_RESULT = new Point(143, 33);

    static final Point SMITHING_TEMPLATE = new Point(8, 48);
    static final Point SMITHING_BASE = new Point(26, 48);
    static final Point SMITHING_ADDITION = new Point(44, 48);
    static final Point SMITHING_RESULT = new Point(98, 48);

    static final Point CAMPFIRE_INPUT = new Point(42, 36);
    static final Point CAMPFIRE_STATION = new Point(80, 36);
    static final Point CAMPFIRE_RESULT = new Point(126, 36);

    static NativeRecipeLayout forMethod(RecipeMethod method) {
        return switch (method) {
            case CRAFTING -> nativePanel(Kind.CRAFTING, "crafting_table");
            case SMELTING -> cooking("furnace");
            case BLASTING -> cooking("blast_furnace");
            case SMOKING -> cooking("smoker");
            case CAMPFIRE_COOKING -> new NativeRecipeLayout(Kind.CAMPFIRE, null, null, null);
            case STONECUTTING -> nativePanel(Kind.STONECUTTING, "stonecutter");
            case SMITHING -> nativePanel(Kind.SMITHING, "smithing");
        };
    }

    boolean hasNativeTexture() {
        return texture != null;
    }

    private static NativeRecipeLayout nativePanel(Kind kind, String name) {
        return new NativeRecipeLayout(
                kind,
                Identifier.withDefaultNamespace("textures/gui/container/" + name + ".png"),
                null,
                null
        );
    }

    private static NativeRecipeLayout cooking(String name) {
        return new NativeRecipeLayout(
                Kind.COOKING,
                Identifier.withDefaultNamespace("textures/gui/container/" + name + ".png"),
                Identifier.withDefaultNamespace("container/" + name + "/lit_progress"),
                Identifier.withDefaultNamespace("container/" + name + "/burn_progress")
        );
    }

    enum Kind {
        CRAFTING,
        COOKING,
        STONECUTTING,
        SMITHING,
        CAMPFIRE
    }

    record Point(int x, int y) {
    }
}
