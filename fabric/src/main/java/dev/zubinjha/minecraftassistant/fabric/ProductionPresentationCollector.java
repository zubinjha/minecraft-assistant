package dev.zubinjha.minecraftassistant.fabric;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class ProductionPresentationCollector {
    private final Map<String, ProductionCardData> individualCards = new LinkedHashMap<>();
    private final List<List<ProductionCardData>> segments = new ArrayList<>();
    private final Set<String> nativeSequenceKeys = new LinkedHashSet<>();
    private ProductionPresentation.Sequence recipeSequence;

    synchronized void add(ProductionCardData card) {
        if (individualCards.putIfAbsent(key(card), card) == null) {
            segments.add(List.of(card));
        }
    }

    synchronized void setSequence(List<ProductionCardData> cards) {
        ProductionPresentation.Sequence candidate = new ProductionPresentation.Sequence(cards);
        if (candidate.recipeOnly()) {
            recipeSequence = candidate;
            return;
        }
        String sequenceKey = candidate.cards().stream().map(ProductionPresentationCollector::key)
                .reduce((left, right) -> left + ">" + right)
                .orElseThrow();
        if (nativeSequenceKeys.add(sequenceKey)) {
            segments.add(candidate.cards());
        }
    }

    synchronized Optional<ProductionPresentation> snapshot() {
        if (recipeSequence != null) {
            return Optional.of(recipeSequence);
        }
        if (segments.size() == 1 && segments.getFirst().size() > 1) {
            return Optional.of(new ProductionPresentation.Sequence(segments.getFirst()));
        }
        List<ProductionCardData> cards = segments.stream().flatMap(List::stream).toList();
        return switch (cards.size()) {
            case 0 -> Optional.empty();
            case 1 -> Optional.of(new ProductionPresentation.Single(cards.getFirst()));
            default -> Optional.of(new ProductionPresentation.Collection(cards));
        };
    }

    private static String key(ProductionCardData card) {
        return card.recipeId() + "|" + card.method().toolValue();
    }
}
