package dev.zubinjha.minecraftassistant.fabric;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.network.chat.Component;

sealed interface ProductionPresentation permits ProductionPresentation.Single,
        ProductionPresentation.Sequence, ProductionPresentation.Plan,
        ProductionPresentation.Collection, ProductionPresentation.Comparison {

    List<ProductionCardData> cards();

    default ProductionCardData displayedCard(int index) {
        return cards().get(index);
    }

    default Component targetTitle() {
        return switch (this) {
            case Single single -> single.card().title();
            case Sequence sequence -> sequence.cards().getLast().title();
            case Plan plan -> Component.literal(ProductionPlan.quantityName(
                    plan.plan().targetName(), plan.plan().requestedCount()
            ));
            case Collection collection -> Component.literal(collection.recipeOnly() ? "Recipes" : "Production Guides");
            case Comparison comparison -> Component.literal(ProductionPlan.quantityName(
                    comparison.routes().getFirst().plan().targetName(),
                    comparison.routes().getFirst().plan().requestedCount()
            ));
        };
    }

    default boolean recipeOnly() {
        return cards().stream().allMatch(card -> card.method().isRecipe());
    }

    default Optional<String> authoritativeSummary() {
        return switch (this) {
            case Plan plan -> Optional.of(plan.plan().summary());
            case Comparison comparison -> Optional.of(comparison.summary());
            default -> Optional.empty();
        };
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

    record Plan(ProductionPlan plan) implements ProductionPresentation {
        public Plan {
            Objects.requireNonNull(plan, "plan");
        }

        @Override
        public List<ProductionCardData> cards() {
            return plan.cards();
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

    record Comparison(List<Route> routes) implements ProductionPresentation {
        public Comparison {
            Objects.requireNonNull(routes, "routes");
            routes = List.copyOf(routes);
            if (routes.size() < 2 || routes.size() > ShowRoutesTool.MAX_ROUTES) {
                throw new IllegalArgumentException("A route comparison requires between 2 and "
                        + ShowRoutesTool.MAX_ROUTES + " routes");
            }
            if (routes.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("Comparison routes cannot contain null values");
            }
            Route primary = routes.getFirst();
            if (routes.stream().map(Route::id).distinct().count() != routes.size()) {
                throw new IllegalArgumentException("Comparison route IDs must be unique");
            }
            if (routes.stream().anyMatch(route -> route.plan().requestedCount() != primary.plan().requestedCount()
                    || !route.plan().targetItemId().equals(primary.plan().targetItemId()))) {
                throw new IllegalArgumentException("Comparison routes must have the same target and quantity");
            }
        }

        @Override
        public List<ProductionCardData> cards() {
            return routes.getFirst().plan().cards();
        }

        String summary() {
            StringBuilder summary = new StringBuilder("Best: ")
                    .append(routes.getFirst().plan().routeSummary());
            for (int index = 1; index < routes.size(); index++) {
                Route route = routes.get(index);
                summary.append(' ').append(route.label()).append(" route: ")
                        .append(route.plan().routeSummary());
            }
            return summary.toString();
        }
    }

    record Route(String id, String label, ProductionPlan plan) {
        public Route {
            id = Objects.requireNonNull(id, "id");
            label = Objects.requireNonNull(label, "label");
            plan = Objects.requireNonNull(plan, "plan");
        }

        List<ProductionCardData> cards() {
            return plan.cards();
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
