package dev.zubinjha.minecraftassistant.fabric;

import java.util.Optional;
import java.util.List;

interface RecipeLookup {
    RecipeLookupResult resolve(String recipeId, Optional<ProductionMethod> method);

    default List<ProductionCardData> allRecipes() {
        return List.of();
    }
}
