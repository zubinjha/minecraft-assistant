package dev.zubinjha.minecraftassistant.fabric;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.zubinjha.minecraftassistant.core.CancellationToken;
import dev.zubinjha.minecraftassistant.core.Tool;
import dev.zubinjha.minecraftassistant.core.ToolDefinition;
import dev.zubinjha.minecraftassistant.core.ToolExecutionResult;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

final class RecipeCardRequestTool implements Tool {
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "show_recipe",
            "Prepare a native Minecraft recipe card using the game's authoritative recipe data.",
            schema()
    );

    private final Minecraft minecraft;
    private final RecipeCardResolver resolver;
    private final AtomicReference<RecipeCardData> requestedCard;

    RecipeCardRequestTool(
            Minecraft minecraft,
            RecipeCardResolver resolver,
            AtomicReference<RecipeCardData> requestedCard
    ) {
        this.minecraft = minecraft;
        this.resolver = resolver;
        this.requestedCard = requestedCard;
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public CompletionStage<ToolExecutionResult> execute(
            JsonNode arguments,
            CancellationToken cancellation
    ) {
        String recipeId = arguments.path("recipe_id").asText("").trim();
        Identifier parsed = Identifier.tryParse(recipeId);
        if (parsed == null) {
            return CompletableFuture.completedFuture(ToolExecutionResult.text(
                    "No recipe card was created: recipe_id was not a valid namespaced identifier."
            ));
        }
        String methodValue = arguments.path("method").asText("").trim();
        Optional<RecipeMethod> method = RecipeMethod.parse(methodValue);
        if (!methodValue.isEmpty() && method.isEmpty()) {
            return CompletableFuture.completedFuture(ToolExecutionResult.text(
                    "No recipe card was created: method was unsupported. Use one of: "
                            + supportedMethods() + "."
            ));
        }

        CompletableFuture<ToolExecutionResult> result = new CompletableFuture<>();
        minecraft.execute(() -> {
            try {
                cancellation.throwIfCancelled();
                RecipeLookupResult lookup = resolver.resolve(parsed.toString(), method);
                switch (lookup) {
                    case RecipeLookupResult.Found found -> {
                        RecipeCardData card = found.card();
                        requestedCard.set(card);
                        result.complete(ToolExecutionResult.text(
                                "A native " + card.method().recipeLabel() + " card is ready for "
                                        + card.recipeId() + ". Ingredients: " + card.ingredientSummary()
                                        + ". Briefly tell the player to use the Show Recipe button."
                        ));
                    }
                    case RecipeLookupResult.Ambiguous ambiguous -> result.complete(ToolExecutionResult.text(
                            "Several native recipes match. Call show_recipe again with one exact recipe_id "
                                    + "and its method: " + candidates(ambiguous) + "."
                    ));
                    case RecipeLookupResult.Missing missing -> result.complete(ToolExecutionResult.text(
                            "The native card renderer could not display " + parsed + ": " + missing.reason()
                                    + ". This does not mean the recipe or method is unavailable. "
                                    + "Do not claim that a recipe card is available."
                    ));
                }
            } catch (RuntimeException failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    private static ObjectNode schema() {
        JsonNodeFactory json = JsonNodeFactory.instance;
        ObjectNode schema = json.objectNode();
        schema.put("type", "object");
        ObjectNode properties = json.objectNode();
        properties.set("recipe_id", json.objectNode()
                .put("type", "string")
                .put("description", "Exact namespaced recipe ID or output item ID, for example minecraft:wooden_pickaxe"));
        var method = json.objectNode()
                .put("type", "string")
                .put("description", "Production method matching the player's question; omit only when unambiguous");
        var methods = json.arrayNode();
        for (RecipeMethod value : RecipeMethod.values()) {
            methods.add(value.toolValue());
        }
        method.set("enum", methods);
        properties.set("method", method);
        schema.set("properties", properties);
        schema.set("required", json.arrayNode().add("recipe_id"));
        schema.put("additionalProperties", false);
        return schema;
    }

    private static String supportedMethods() {
        return java.util.Arrays.stream(RecipeMethod.values())
                .map(RecipeMethod::toolValue)
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static String candidates(RecipeLookupResult.Ambiguous ambiguous) {
        String joined = ambiguous.candidates().stream()
                .limit(8)
                .map(candidate -> candidate.recipeId() + " (" + candidate.method().toolValue() + ")")
                .collect(java.util.stream.Collectors.joining(", "));
        return ambiguous.candidates().size() > 8 ? joined + ", and more" : joined;
    }
}
