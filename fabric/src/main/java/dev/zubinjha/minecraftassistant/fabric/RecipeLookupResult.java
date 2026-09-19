package dev.zubinjha.minecraftassistant.fabric;

import java.util.List;

sealed interface RecipeLookupResult permits RecipeLookupResult.Found,
        RecipeLookupResult.Ambiguous, RecipeLookupResult.Missing {

    record Found(ProductionCardData card) implements RecipeLookupResult {
    }

    record Ambiguous(List<Candidate> candidates) implements RecipeLookupResult {
        public Ambiguous {
            candidates = List.copyOf(candidates);
        }
    }

    record Missing(String reason) implements RecipeLookupResult {
    }

    record Candidate(String recipeId, ProductionMethod method) {
    }
}
