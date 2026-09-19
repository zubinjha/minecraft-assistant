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

        CompletableFuture<ToolExecutionResult> result = new CompletableFuture<>();
        minecraft.execute(() -> {
            try {
                cancellation.throwIfCancelled();
                resolver.resolve(parsed.toString()).ifPresentOrElse(card -> {
                    requestedCard.set(card);
                    result.complete(ToolExecutionResult.text(
                            "A native recipe card is ready for " + card.recipeId()
                                    + ". Ingredients: " + card.ingredientSummary()
                                    + ". Briefly tell the player to use the Show Recipe button."
                    ));
                }, () -> result.complete(ToolExecutionResult.text(
                        "The native card renderer could not display " + parsed
                                + ". This does not mean the recipe or crafting method is unavailable. "
                                + "Do not claim that a recipe card is available."
                )));
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
                .put("description", "Exact namespaced recipe ID, for example minecraft:wooden_pickaxe"));
        schema.set("properties", properties);
        schema.set("required", json.arrayNode().add("recipe_id"));
        schema.put("additionalProperties", false);
        return schema;
    }
}
