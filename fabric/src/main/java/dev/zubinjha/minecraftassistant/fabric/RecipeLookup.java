package dev.zubinjha.minecraftassistant.fabric;

import java.util.Optional;

@FunctionalInterface
interface RecipeLookup {
    RecipeLookupResult resolve(String recipeId, Optional<RecipeMethod> method);
}
