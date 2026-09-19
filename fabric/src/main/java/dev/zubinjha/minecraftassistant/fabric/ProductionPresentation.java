package dev.zubinjha.minecraftassistant.fabric;

import java.util.List;
import java.util.Objects;
import net.minecraft.network.chat.Component;

sealed interface ProductionPresentation permits ProductionPresentation.Single,
        ProductionPresentation.Sequence, ProductionPresentation.Collection {

    List<ProductionCardData> cards();

    default ProductionCardData displayedCard(int index) {
        return cards().get(index);
    }

    default Component targetTitle() {
        return switch (this) {
            case Single single -> single.card().title();
            case Sequence sequence -> sequence.cards().getLast().title();
            case Collection collection -> Component.literal(collection.recipeOnly() ? "Recipes" : "Production Guides");
        };
    }

    default boolean recipeOnly() {
        return cards().stream().allMatch(card -> card.method().isRecipe());
    }

    record Single(ProductionCardData card) implements ProductionPresentation {
        public Single {
            Objects.requireNonNull(card, "card");
        }

        @Override
        public List<ProductionCardData> cards() {
            return List.of(card);
        }
    }

    record Sequence(List<ProductionCardData> cards) implements ProductionPresentation {
        public Sequence {
            cards = validatedCards(cards, "sequence");
            if (cards.size() < 2) {
                throw new IllegalArgumentException("A production sequence requires at least two cards");
            }
        }
    }

    record Collection(List<ProductionCardData> cards) implements ProductionPresentation {
        public Collection {
            cards = validatedCards(cards, "collection");
            if (cards.size() < 2) {
                throw new IllegalArgumentException("A production collection requires at least two cards");
            }
        }
    }

    private static List<ProductionCardData> validatedCards(List<ProductionCardData> cards, String kind) {
        Objects.requireNonNull(cards, kind + " cards");
        if (cards.size() > ShowProcessTool.MAX_STEPS) {
            throw new IllegalArgumentException("A production " + kind + " supports at most "
                    + ShowProcessTool.MAX_STEPS + " cards");
        }
        List<ProductionCardData> copy = List.copyOf(cards);
        if (copy.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Production cards cannot contain null values");
        }
        return copy;
    }
}
