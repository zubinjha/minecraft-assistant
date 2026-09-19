package dev.zubinjha.minecraftassistant.fabric;

import java.util.List;

sealed interface NativeProductionResult permits NativeProductionResult.Found,
        NativeProductionResult.Ambiguous, NativeProductionResult.Missing {

    record Found(List<ProductionCardData> cards) implements NativeProductionResult {
        public Found {
            cards = List.copyOf(cards);
            if (cards.isEmpty() || cards.size() > ShowProcessTool.MAX_STEPS) {
                throw new IllegalArgumentException("Production guides require between one and six cards");
            }
        }
    }

    record Ambiguous(List<String> choices) implements NativeProductionResult {
        public Ambiguous {
            choices = List.copyOf(choices);
        }
    }

    record Missing(String reason) implements NativeProductionResult {
    }
}
