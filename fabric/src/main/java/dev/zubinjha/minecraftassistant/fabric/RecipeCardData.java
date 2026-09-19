package dev.zubinjha.minecraftassistant.fabric;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

public record RecipeCardData(
        String recipeId,
        Component title,
        int gridWidth,
        int gridHeight,
        List<ItemStack> ingredients,
        ItemStack result,
        boolean shapeless
) {
    public RecipeCardData {
        ingredients = ingredients.stream().map(ItemStack::copy).toList();
        result = result.copy();
    }

    public String ingredientSummary() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ItemStack ingredient : ingredients) {
            if (!ingredient.isEmpty()) {
                counts.merge(ingredient.getHoverName().getString(), ingredient.count(), Integer::sum);
            }
        }
        return counts.entrySet().stream()
                .map(entry -> entry.getValue() + "× " + entry.getKey())
                .collect(Collectors.joining(", "));
    }
}
