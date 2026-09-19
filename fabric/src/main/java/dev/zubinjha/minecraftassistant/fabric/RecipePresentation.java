package dev.zubinjha.minecraftassistant.fabric;

import java.util.List;
import java.util.Objects;
import net.minecraft.network.chat.Component;

sealed interface RecipePresentation permits RecipePresentation.Single,
        RecipePresentation.Sequence, RecipePresentation.Collection {

    List<RecipeCardData> cards();

    default RecipeCardData displayedCard(int index) {
        return cards().get(index);
    }

    default Component targetTitle() {
        return switch (this) {
            case Single single -> single.card().title();
            case Sequence sequence -> sequence.cards().getLast().title();
            case Collection ignored -> Component.literal("Recipes");
        };
    }

    record Single(RecipeCardData card) implements RecipePresentation {
        public Single {
            Objects.requireNonNull(card, "card");
        }

        @Override
        public List<RecipeCardData> cards() {
            return List.of(card);
        }
    }

    record Sequence(List<RecipeCardData> cards) implements RecipePresentation {
        public Sequence {
            cards = validatedCards(cards, "sequence");
            if (cards.size() < 2) {
                throw new IllegalArgumentException("A recipe sequence requires at least two cards");
            }
        }
    }

    record Collection(List<RecipeCardData> cards) implements RecipePresentation {
        public Collection {
            cards = validatedCards(cards, "collection");
            if (cards.size() < 2) {
                throw new IllegalArgumentException("A recipe collection requires at least two cards");
            }
        }
    }

    private static List<RecipeCardData> validatedCards(List<RecipeCardData> cards, String kind) {
        Objects.requireNonNull(cards, kind + " cards");
        if (cards.size() > ShowProcessTool.MAX_STEPS) {
            throw new IllegalArgumentException("A recipe " + kind + " supports at most "
                    + ShowProcessTool.MAX_STEPS + " cards");
        }
        List<RecipeCardData> copy = List.copyOf(cards);
        if (copy.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Recipe cards cannot contain null values");
        }
        return copy;
    }
}
