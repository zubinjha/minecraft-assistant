package dev.zubinjha.minecraftassistant.fabric;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.zubinjha.minecraftassistant.core.CancellationToken;
import dev.zubinjha.minecraftassistant.core.Tool;
import dev.zubinjha.minecraftassistant.core.ToolDefinition;
import dev.zubinjha.minecraftassistant.core.ToolExecutionResult;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

final class FindProductionRoutesTool implements Tool {
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "find_production_routes",
            "Discover and exactly calculate native Minecraft recipe routes before choosing what to display. "
                    + "This call is discovery only: provide source, target, and the player's quantity, but do not "
                    + "choose or infer a production method here. Use only for quantity-based production planning, "
                    + "not ordinary factual, acquisition, strategy, or mechanics answers.",
            schema()
    );

    private final Executor clientExecutor;
    private final RecipeLookup recipes;
    private final ProductionRouteSearchStore searches;
    private final RecipeRouteFinder routeFinder = new RecipeRouteFinder(new ProductionQuantityPlanner());

    FindProductionRoutesTool(
            Minecraft minecraft,
            RecipeLookup recipes,
            ProductionRouteSearchStore searches
    ) {
        this(minecraft::execute, recipes, searches);
    }

    FindProductionRoutesTool(
            Executor clientExecutor,
            RecipeLookup recipes,
            ProductionRouteSearchStore searches
    ) {
        this.clientExecutor = clientExecutor;
        this.recipes = recipes;
        this.searches = searches;
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public CompletionStage<ToolExecutionResult> execute(JsonNode arguments, CancellationToken cancellation) {
        String sourceItemId = arguments.path("source_item_id").asText("").trim();
        String targetItemId = arguments.path("target_item_id").asText("").trim();
        Identifier parsedSource = Identifier.tryParse(sourceItemId);
        Identifier parsedTarget = Identifier.tryParse(targetItemId);
        if (parsedSource == null || parsedTarget == null) {
            return completed("No routes were discovered: source_item_id and target_item_id must be valid IDs.");
        }
        String normalizedSourceItemId = parsedSource.toString();
        String normalizedTargetItemId = parsedTarget.toString();
        ProductionQuantityRequest.ParseResult quantity = ProductionQuantityRequest.parseOptional(arguments);
        if (!quantity.valid() || quantity.request().isEmpty()) {
            String reason = quantity.valid() ? "target_quantity is required" : quantity.error();
            return completed("No routes were discovered: " + reason + ".");
        }

        CompletableFuture<ToolExecutionResult> result = new CompletableFuture<>();
        clientExecutor.execute(() -> {
            try {
                cancellation.throwIfCancelled();
                RecipeRouteFinder.Result found = routeFinder.find(
                        recipes.allRecipes(),
                        normalizedSourceItemId,
                        normalizedTargetItemId,
                        Optional.empty(),
                        quantity.request().orElseThrow(),
                        ShowProcessTool.MAX_STEPS,
                        cancellation
                );
                switch (found) {
                    case RecipeRouteFinder.Result.Candidates candidates -> {
                        ProductionRouteSearchStore.Search search = searches.put(candidates.routes());
                        result.complete(ToolExecutionResult.text(resultJson(
                                search, normalizedSourceItemId, normalizedTargetItemId
                        )));
                    }
                    case RecipeRouteFinder.Result.Missing missing -> result.complete(ToolExecutionResult.text(
                            "No routes were discovered: " + missing.reason()
                                    + ". Do not calculate or invent a route yourself."
                    ));
                }
            } catch (RuntimeException failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    private static String resultJson(
            ProductionRouteSearchStore.Search search,
            String sourceItemId,
            String targetItemId
    ) {
        JsonNodeFactory json = JsonNodeFactory.instance;
        ObjectNode root = json.objectNode();
        root.put("search_id", search.id());
        root.put("source_item_id", sourceItemId);
        root.put("target_item_id", targetItemId);
        ArrayNode routes = root.putArray("routes");
        for (ProductionRouteSearchStore.Route stored : search.routes()) {
            ProductionPlan plan = stored.candidate().plan();
            ObjectNode route = routes.addObject();
            route.put("route_id", stored.id());
            route.put("label", plan.operations().getLast().method().displayName());
            route.put("requested_count", plan.requestedCount());
            route.put("produced_count", plan.producedCount());
            route.put("source_count", plan.sourceMaterial().map(ProductionPlan.Material::count).orElse(0L));
            route.put("additional_consumables", plan.additionalConsumables());
            route.put("operation_count", plan.operations().size());
            route.put("cooking_ticks", plan.cookingTicks());
            ArrayNode methods = route.putArray("methods");
            stored.candidate().cards().forEach(card -> methods.add(card.method().toolValue()));
            ArrayNode workstations = route.putArray("workstations");
            stored.candidate().cards().forEach(card -> workstations.add(card.method().displayName()));
            ArrayNode materials = route.putArray("external_materials");
            plan.rootMaterials().forEach(material -> materials.addObject()
                    .put("item_id", material.itemId())
                    .put("name", material.name())
                    .put("count", material.count()));
            ArrayNode leftovers = route.putArray("leftovers");
            plan.leftovers().forEach(material -> leftovers.addObject()
                    .put("item_id", material.itemId())
                    .put("name", material.name())
                    .put("count", material.count()));
        }
        root.put("instruction", "Normally choose the one route_id that best fits the request and recent "
                + "conversation, then call show_routes. Choose two or three only for an explicit comparison or "
                + "a meaningful material, workstation, or time tradeoff. Respect prior workstation constraints. "
                + "Do not repeat the arithmetic.");
        return root.toString();
    }

    private static CompletionStage<ToolExecutionResult> completed(String text) {
        return CompletableFuture.completedFuture(ToolExecutionResult.text(text));
    }

    private static ObjectNode schema() {
        JsonNodeFactory json = JsonNodeFactory.instance;
        ObjectNode schema = json.objectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.set("source_item_id", json.objectNode().put("type", "string")
                .put("description", "Namespaced item ID the player is starting from"));
        properties.set("target_item_id", json.objectNode().put("type", "string")
                .put("description", "Namespaced item ID the player wants to produce"));
        properties.set("target_quantity", RecipeCardRequestTool.targetQuantitySchema(json));
        schema.set("required", json.arrayNode()
                .add("source_item_id").add("target_item_id").add("target_quantity"));
        schema.put("additionalProperties", false);
        return schema;
    }
}
