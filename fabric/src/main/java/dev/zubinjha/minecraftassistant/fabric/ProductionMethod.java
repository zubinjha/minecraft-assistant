package dev.zubinjha.minecraftassistant.fabric;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

enum ProductionMethod {
    CRAFTING("crafting", "crafting recipe"),
    SMELTING("smelting", "smelting recipe"),
    BLASTING("blasting", "blasting recipe"),
    SMOKING("smoking", "smoking recipe"),
    CAMPFIRE_COOKING("campfire_cooking", "campfire cooking recipe"),
    STONECUTTING("stonecutting", "stonecutting recipe"),
    SMITHING("smithing", "smithing recipe"),
    BREWING("brewing", "brewing guide"),
    LOOM("loom", "loom guide"),
    CARTOGRAPHY("cartography", "cartography guide"),
    ENCHANTING("enchanting", "enchanting guide"),
    ANVIL("anvil", "anvil guide"),
    GRINDSTONE("grindstone", "grindstone guide");

    private final String toolValue;
    private final String recipeLabel;

    ProductionMethod(String toolValue, String recipeLabel) {
        this.toolValue = toolValue;
        this.recipeLabel = recipeLabel;
    }

    String toolValue() {
        return toolValue;
    }

    String recipeLabel() {
        return recipeLabel;
    }

    String displayName() {
        return switch (this) {
            case CRAFTING -> "Crafting";
            case SMELTING -> "Smelting";
            case BLASTING -> "Blasting";
            case SMOKING -> "Smoking";
            case CAMPFIRE_COOKING -> "Campfire Cooking";
            case STONECUTTING -> "Stonecutting";
            case SMITHING -> "Smithing";
            case BREWING -> "Brewing";
            case LOOM -> "Loom";
            case CARTOGRAPHY -> "Cartography";
            case ENCHANTING -> "Enchanting";
            case ANVIL -> "Anvil";
            case GRINDSTONE -> "Grindstone";
        };
    }

    boolean isRecipe() {
        return switch (this) {
            case CRAFTING, SMELTING, BLASTING, SMOKING, CAMPFIRE_COOKING, STONECUTTING, SMITHING -> true;
            case BREWING, LOOM, CARTOGRAPHY, ENCHANTING, ANVIL, GRINDSTONE -> false;
        };
    }

    static Optional<ProductionMethod> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return Arrays.stream(values())
                .filter(method -> method.toolValue.equals(normalized))
                .findFirst();
    }
}
