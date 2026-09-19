package dev.zubinjha.minecraftassistant.fabric;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;

final class RecipeCardResolver {
    private final Minecraft minecraft;

    RecipeCardResolver(Minecraft minecraft) {
        this.minecraft = minecraft;
    }

    Optional<RecipeCardData> resolve(String rawRecipeId) {
        Identifier requested = Identifier.tryParse(rawRecipeId);
        if (requested == null || minecraft.level == null) {
            return Optional.empty();
        }

        ContextMap context = SlotDisplayContext.fromLevel(minecraft.level);
        if (minecraft.getSingleplayerServer() != null) {
            ResourceKey<Recipe<?>> key = ResourceKey.create(Registries.RECIPE, requested);
            Optional<RecipeHolder<?>> exact = minecraft.getSingleplayerServer()
                    .getRecipeManager()
                    .byKey(key);
            if (exact.isPresent()) {
                Optional<RecipeCardData> resolved = fromRecipe(exact.get(), context);
                if (resolved.isPresent()) {
                    return resolved;
                }
            }

            for (RecipeHolder<?> holder : minecraft.getSingleplayerServer()
                    .getRecipeManager()
                    .getRecipes()) {
                Optional<RecipeCardData> resolved = fromRecipe(holder, context);
                if (resolved.isPresent() && resultMatches(resolved.get(), requested)) {
                    return resolved;
                }
            }
        }

        if (minecraft.player != null) {
            for (var collection : minecraft.player.getRecipeBook().getCollections()) {
                for (RecipeDisplayEntry entry : collection.getRecipes()) {
                    Optional<RecipeCardData> resolved = fromDisplay(
                            rawRecipeId,
                            entry.display(),
                            context
                    );
                    if (resolved.isPresent() && resultMatches(resolved.get(), requested)) {
                        return resolved;
                    }
                }
            }
        }
        return Optional.empty();
    }

    private Optional<RecipeCardData> fromRecipe(RecipeHolder<?> holder, ContextMap context) {
        for (RecipeDisplay display : holder.value().display()) {
            Optional<RecipeCardData> resolved = fromDisplay(
                    holder.id().identifier().toString(),
                    display,
                    context
            );
            if (resolved.isPresent()) {
                return resolved;
            }
        }
        return Optional.empty();
    }

    private Optional<RecipeCardData> fromDisplay(
            String recipeId,
            RecipeDisplay display,
            ContextMap context
    ) {
        if (display instanceof ShapedCraftingRecipeDisplay shaped) {
            return create(
                    recipeId,
                    shaped.width(),
                    shaped.height(),
                    shaped.ingredients(),
                    shaped.result(),
                    false,
                    context
            );
        }
        if (display instanceof ShapelessCraftingRecipeDisplay shapeless) {
            int count = shapeless.ingredients().size();
            return create(
                    recipeId,
                    Math.min(3, Math.max(1, count)),
                    Math.max(1, (count + 2) / 3),
                    shapeless.ingredients(),
                    shapeless.result(),
                    true,
                    context
            );
        }
        return Optional.empty();
    }

    private Optional<RecipeCardData> create(
            String recipeId,
            int width,
            int height,
            List<SlotDisplay> ingredientDisplays,
            SlotDisplay resultDisplay,
            boolean shapeless,
            ContextMap context
    ) {
        ItemStack result = resultDisplay.resolveForFirstStack(context);
        if (result.isEmpty() || width > 3 || height > 3) {
            return Optional.empty();
        }
        List<ItemStack> ingredients = new ArrayList<>(ingredientDisplays.size());
        for (SlotDisplay ingredient : ingredientDisplays) {
            ingredients.add(ingredient.resolveForFirstStack(context));
        }
        return Optional.of(new RecipeCardData(
                recipeId,
                result.getHoverName(),
                width,
                height,
                ingredients,
                result,
                shapeless
        ));
    }

    private static boolean resultMatches(RecipeCardData data, Identifier requested) {
        return BuiltInRegistries.ITEM.getKey(data.result().getItem()).equals(requested);
    }
}
