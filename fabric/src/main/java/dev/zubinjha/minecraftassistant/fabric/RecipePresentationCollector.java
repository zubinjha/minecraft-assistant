package dev.zubinjha.minecraftassistant.fabric;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class RecipePresentationCollector {
    private final Map<String, RecipeCardData> individualCards = new LinkedHashMap<>();
    private RecipePresentation explicitPresentation;

    synchronized void add(RecipeCardData card) {
        individualCards.putIfAbsent(key(card), card);
    }

    synchronized void setSequence(List<RecipeCardData> cards) {
        explicitPresentation = new RecipePresentation.Sequence(cards);
    }

    synchronized Optional<RecipePresentation> snapshot() {
        if (explicitPresentation != null) {
            return Optional.of(explicitPresentation);
        }
        List<RecipeCardData> cards = List.copyOf(individualCards.values());
        return switch (cards.size()) {
            case 0 -> Optional.empty();
            case 1 -> Optional.of(new RecipePresentation.Single(cards.getFirst()));
            default -> Optional.of(new RecipePresentation.Collection(cards));
        };
    }

    private static String key(RecipeCardData card) {
        return card.recipeId() + "|" + card.method().toolValue();
    }
}
