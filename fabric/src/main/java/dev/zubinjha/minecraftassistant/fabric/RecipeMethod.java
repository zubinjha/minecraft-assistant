package dev.zubinjha.minecraftassistant.fabric;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

enum RecipeMethod {
    CRAFTING("crafting", "crafting recipe"),
    SMELTING("smelting", "smelting recipe"),
    BLASTING("blasting", "blasting recipe"),
    SMOKING("smoking", "smoking recipe"),
    CAMPFIRE_COOKING("campfire_cooking", "campfire cooking recipe"),
    STONECUTTING("stonecutting", "stonecutting recipe"),
    SMITHING("smithing", "smithing recipe");

    private final String toolValue;
    private final String recipeLabel;

    RecipeMethod(String toolValue, String recipeLabel) {
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
        };
    }

    static Optional<RecipeMethod> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return Arrays.stream(values())
                .filter(method -> method.toolValue.equals(normalized))
                .findFirst();
    }
}
