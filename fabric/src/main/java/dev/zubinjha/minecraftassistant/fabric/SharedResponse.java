package dev.zubinjha.minecraftassistant.fabric;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.resources.Identifier;

record SharedResponse(String answer, Optional<URI> source, Optional<Recipe> recipe) {
    SharedResponse {
        answer = Objects.requireNonNull(answer, "answer").trim();
        if (answer.isBlank()) {
            throw new IllegalArgumentException("answer cannot be blank");
        }
        source = Objects.requireNonNull(source, "source");
        recipe = Objects.requireNonNull(recipe, "recipe");
        source.ifPresent(uri -> {
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("https") && !scheme.equalsIgnoreCase("http"))) {
                throw new IllegalArgumentException("source must use http or https");
            }
        });
    }

    static SharedResponse from(String displayedAnswer, ProductionPresentation presentation) {
        String text = Objects.requireNonNull(displayedAnswer, "displayedAnswer").trim();
        Optional<URI> source = sourceFrom(text);
        String answer = source
                .map(ignored -> text.substring(0, text.lastIndexOf("Source:")).trim())
                .orElse(text);
        Optional<Recipe> recipe = presentation == null || presentation.cards().isEmpty()
                ? Optional.empty()
                : Recipe.from(presentation.cards().getLast());
        return new SharedResponse(answer, source, recipe);
    }

    static Optional<URI> sourceFrom(String text) {
        int marker = text.lastIndexOf("Source:");
        if (marker < 0) {
            return Optional.empty();
        }
        String rawSource = text.substring(marker + "Source:".length()).trim();
        try {
            URI uri = URI.create(rawSource);
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("https") && !scheme.equalsIgnoreCase("http"))) {
                return Optional.empty();
            }
            return Optional.of(uri);
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    record Recipe(String recipeId, ProductionMethod method) {
        Recipe {
            recipeId = Objects.requireNonNull(recipeId, "recipeId");
            method = Objects.requireNonNull(method, "method");
            if (Identifier.tryParse(recipeId) == null) {
                throw new IllegalArgumentException("recipeId must be a valid namespaced identifier");
            }
            if (!method.isRecipe()) {
                throw new IllegalArgumentException("method must describe a standard recipe");
            }
        }

        static Optional<Recipe> from(ProductionCardData card) {
            if (!card.method().isRecipe() || Identifier.tryParse(card.recipeId()) == null) {
                return Optional.empty();
            }
            return Optional.of(new Recipe(card.recipeId(), card.method()));
        }

        String metadata() {
            return "Recipe: " + recipeId + " (" + method.toolValue().toLowerCase(Locale.ROOT) + ")";
        }
    }
}
