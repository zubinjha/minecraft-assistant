package dev.zubinjha.minecraftassistant.fabric;

import net.minecraft.resources.Identifier;

record NativeProductionLayout(
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

    static final Point BREWING_FUEL = new Point(17, 17);
    static final Point BREWING_INGREDIENT = new Point(79, 17);
    static final Point BREWING_INPUT = new Point(56, 51);
    static final Point BREWING_RESULT = new Point(102, 51);

    static final Point LOOM_BANNER = new Point(13, 26);
    static final Point LOOM_DYE = new Point(33, 26);
    static final Point LOOM_PATTERN_ITEM = new Point(23, 45);
    static final Point LOOM_RESULT = new Point(143, 58);

    static final Point CARTOGRAPHY_INPUT = new Point(15, 15);
    static final Point CARTOGRAPHY_ADDITION = new Point(15, 52);
    static final Point CARTOGRAPHY_RESULT = new Point(145, 39);

    static final Point ENCHANTING_ITEM = new Point(15, 47);
    static final Point ENCHANTING_LAPIS = new Point(35, 47);

    static final Point ANVIL_BASE = new Point(27, 47);
    static final Point ANVIL_ADDITION = new Point(76, 47);
    static final Point ANVIL_RESULT = new Point(134, 47);
    static final Point ANVIL_NAME_FIELD = new Point(59, 18);
    static final int ANVIL_NAME_FIELD_WIDTH = 110;
    static final int ANVIL_NAME_FIELD_HEIGHT = 18;

    static final Point GRINDSTONE_INPUT = new Point(49, 19);
    static final Point GRINDSTONE_ADDITION = new Point(49, 40);
    static final Point GRINDSTONE_RESULT = new Point(129, 34);

    static NativeProductionLayout forMethod(ProductionMethod method) {
        return switch (method) {
            case CRAFTING -> nativePanel(Kind.CRAFTING, "crafting_table");
            case SMELTING -> cooking("furnace");
            case BLASTING -> cooking("blast_furnace");
            case SMOKING -> cooking("smoker");
            case CAMPFIRE_COOKING -> new NativeProductionLayout(Kind.CAMPFIRE, null, null, null);
            case STONECUTTING -> nativePanel(Kind.STONECUTTING, "stonecutter");
            case SMITHING -> nativePanel(Kind.SMITHING, "smithing");
            case BREWING -> nativePanel(Kind.BREWING, "brewing_stand");
            case LOOM -> nativePanel(Kind.LOOM, "loom");
            case CARTOGRAPHY -> nativePanel(Kind.CARTOGRAPHY, "cartography_table");
            case ENCHANTING -> nativePanel(Kind.ENCHANTING, "enchanting_table");
            case ANVIL -> nativePanel(Kind.ANVIL, "anvil");
            case GRINDSTONE -> nativePanel(Kind.GRINDSTONE, "grindstone");
        };
    }

    boolean hasNativeTexture() {
        return texture != null;
    }

    private static NativeProductionLayout nativePanel(Kind kind, String name) {
        return new NativeProductionLayout(
                kind,
                Identifier.withDefaultNamespace("textures/gui/container/" + name + ".png"),
                null,
                null
        );
    }

    private static NativeProductionLayout cooking(String name) {
        return new NativeProductionLayout(
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
        CAMPFIRE,
        BREWING,
        LOOM,
        CARTOGRAPHY,
        ENCHANTING,
        ANVIL,
        GRINDSTONE
    }

    record Point(int x, int y) {
    }
}
