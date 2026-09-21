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
import java.util.concurrent.Executor;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

final class RecipeCardRequestTool implements Tool {
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "show_recipe",
            "Prepare a native Minecraft recipe card using the game's authoritative recipe data. "
                    + "For quantity questions, include target_quantity and let the native planner calculate totals. "
                    + "Do not use this for ordinary factual, acquisition, strategy, or mechanics answers.",
            schema()
    );

    private final Executor clientExecutor;
    private final RecipeLookup resolver;
    private final ProductionPresentationCollector presentations;
    private final ProductionQuantityPlanner quantityPlanner = new ProductionQuantityPlanner();

    RecipeCardRequestTool(
            Minecraft minecraft,
            RecipeLookup resolver,
            ProductionPresentationCollector presentations
    ) {
        this(minecraft::execute, resolver, presentations);
    }

    RecipeCardRequestTool(
            Executor clientExecutor,
            RecipeLookup resolver,
            ProductionPresentationCollector presentations
    ) {
        this.clientExecutor = clientExecutor;
        this.resolver = resolver;
        this.presentations = presentations;
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
        Optional<ProductionMethod> method = ProductionMethod.parse(methodValue);
        if (!methodValue.isEmpty() && method.isEmpty()) {
            return CompletableFuture.completedFuture(ToolExecutionResult.text(
                    "No recipe card was created: method was unsupported. Use one of: "
                            + supportedMethods() + "."
            ));
        }
        ProductionQuantityRequest.ParseResult quantity = ProductionQuantityRequest.parseOptional(arguments);
        if (!quantity.valid()) {
            return CompletableFuture.completedFuture(ToolExecutionResult.text(
                    "No recipe card was created: " + quantity.error() + "."
            ));
        }

        CompletableFuture<ToolExecutionResult> result = new CompletableFuture<>();
        clientExecutor.execute(() -> {
            try {
                cancellation.throwIfCancelled();
                RecipeLookupResult lookup = resolver.resolve(parsed.toString(), method);
                switch (lookup) {
                    case RecipeLookupResult.Found found -> {
                        ProductionCardData card = found.card();
                        if (quantity.request().isPresent()) {
                            ProductionQuantityPlanner.Result planned = quantityPlanner.plan(
                                    java.util.List.of(card), quantity.request().get(), Optional.empty(), cancellation
                            );
                            if (planned instanceof ProductionQuantityPlanner.Result.Failure failure) {
                                result.complete(ToolExecutionResult.text(
                                        "No quantity plan was created: " + failure.reason()
                                                + ". Do not calculate totals yourself."
                                ));
                                return;
                            }
                            ProductionPlan plan =
                                    ((ProductionQuantityPlanner.Result.Success) planned).plan();
                            presentations.setPlanned(plan);
                            result.complete(ToolExecutionResult.terminal(
                                    "The native quantity plan is ready. The mod will display its authoritative summary.",
                                    plan.summary()
                            ));
                            return;
                        }
                        presentations.add(card);
                        String action = presentations.snapshot()
                                .map(MinecraftAssistantRuntime::presentationButtonLabel)
                                .orElse("Show Recipe");
                        result.complete(ToolExecutionResult.text(
                                "A native " + card.method().recipeLabel() + " card is ready for "
                                        + card.recipeId() + ". Ingredients: " + card.ingredientSummary()
                                        + ". Briefly tell the player to use the " + action + " button."
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
        for (ProductionMethod value : ProductionMethod.values()) {
            if (value.isRecipe()) {
                methods.add(value.toolValue());
            }
        }
        method.set("enum", methods);
        properties.set("method", method);
        properties.set("target_quantity", targetQuantitySchema(json));
        schema.set("properties", properties);
        schema.set("required", json.arrayNode().add("recipe_id"));
        schema.put("additionalProperties", false);
        return schema;
    }

    static ObjectNode targetQuantitySchema(JsonNodeFactory json) {
        ObjectNode quantity = json.objectNode();
        quantity.put("type", "object");
        ObjectNode properties = json.objectNode();
        properties.set("total_items", json.objectNode().put("type", "integer").put("minimum", 0)
                .put("maximum", ProductionQuantityRequest.MAX_TARGET_ITEMS)
                .put("description", "Exact total item count stated by the player. Never combine a positive value "
                        + "with positive stacks or loose_items."));
        properties.set("stacks", json.objectNode().put("type", "integer").put("minimum", 0)
                .put("maximum", ProductionQuantityRequest.MAX_TARGET_ITEMS)
                .put("description", "Number of target-item stacks explicitly stated by the player"));
        properties.set("loose_items", json.objectNode().put("type", "integer").put("minimum", 0)
                .put("maximum", ProductionQuantityRequest.MAX_TARGET_ITEMS)
                .put("description", "Loose items explicitly stated in addition to stacks"));
        quantity.set("properties", properties);
        quantity.put("additionalProperties", false);
        return quantity;
    }

    private static String supportedMethods() {
        return java.util.Arrays.stream(ProductionMethod.values())
                .filter(ProductionMethod::isRecipe)
                .map(ProductionMethod::toolValue)
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
