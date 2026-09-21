package dev.zubinjha.minecraftassistant.fabric;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.zubinjha.minecraftassistant.core.CancellationToken;
import dev.zubinjha.minecraftassistant.core.Tool;
import dev.zubinjha.minecraftassistant.core.ToolDefinition;
import dev.zubinjha.minecraftassistant.core.ToolExecutionResult;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

final class PrepareProductionTool implements Tool {
    static final int MAX_OPERATIONS = 6;
    private static final int MAX_ITEM_CONSTRAINTS = 12;
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "prepare_production",
            "Prepare a native Minecraft production guide from semantic intent. Provide the target item, any "
                    + "materials the player has, starts from, or names as the basis of a required-material "
                    + "calculation, optional unavailable "
                    + "items or workstation constraints, and an optional requested quantity. The mod resolves "
                    + "recipes, dependencies, ordering, quantities, presentation, and rendering. Never provide "
                    + "recipe IDs or steps. Use for make, craft, smelt, produce, or convert questions when a native "
                    + "guide would materially help; use Wiki tools for acquisition, mechanics, or factual research. "
                    + "Call this once for the player's final desired item. It recursively includes producible "
                    + "ingredients and branches, so never call it separately for intermediate items or individual "
                    + "steps of the same guide. Inventory counts are not a target quantity unless the player asks "
                    + "how many results those materials can make. For an interchangeable material family, use one "
                    + "concrete representative item ID only when the variant cannot change the route or result.",
            schema()
    );

    private final Executor clientExecutor;
    private final RecipeLookup recipes;
    private final ProductionRouteSearchStore searches;
    private final ProductionPresentationCollector presentations;
    private final RecipeRouteFinder routeFinder = new RecipeRouteFinder(new ProductionQuantityPlanner());

    PrepareProductionTool(
            Minecraft minecraft,
            RecipeLookup recipes,
            ProductionRouteSearchStore searches,
            ProductionPresentationCollector presentations
    ) {
        this(minecraft::execute, recipes, searches, presentations);
    }

    PrepareProductionTool(
            Executor clientExecutor,
            RecipeLookup recipes,
            ProductionRouteSearchStore searches,
            ProductionPresentationCollector presentations
    ) {
        this.clientExecutor = clientExecutor;
        this.recipes = recipes;
        this.searches = searches;
        this.presentations = presentations;
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public CompletionStage<ToolExecutionResult> execute(JsonNode arguments, CancellationToken cancellation) {
        String targetItemId = normalizeId(arguments.path("target_item_id").asText(""));
        if (targetItemId == null) {
            return completed("No production guide was created: target_item_id must be a valid item ID.");
        }
        ParseSet startingItems = parseItemIds(arguments.get("starting_item_ids"), "starting_item_ids");
        ParseSet unavailableItems = parseItemIds(arguments.get("unavailable_item_ids"), "unavailable_item_ids");
        if (startingItems.error != null || unavailableItems.error != null) {
            return completed("No production guide was created: "
                    + (startingItems.error != null ? startingItems.error : unavailableItems.error) + ".");
        }
        Set<String> overlap = new LinkedHashSet<>(startingItems.values);
        overlap.retainAll(unavailableItems.values);
        if (!overlap.isEmpty()) {
            return completed("No production guide was created: an item cannot be both available and unavailable: "
                    + String.join(", ", overlap) + ".");
        }
        ParseMethods unavailableMethods = parseMethods(arguments.get("unavailable_methods"),
                "unavailable_methods");
        ParseMethods requestedMethods = parseMethods(arguments.get("workstation_methods"), "workstation_methods");
        if (unavailableMethods.error != null || requestedMethods.error != null) {
            return completed("No production guide was created: "
                    + (unavailableMethods.error != null ? unavailableMethods.error : requestedMethods.error) + ".");
        }
        Set<ProductionMethod> methodOverlap = new LinkedHashSet<>(unavailableMethods.values);
        methodOverlap.retainAll(requestedMethods.values);
        if (!methodOverlap.isEmpty()) {
            return completed("No production guide was created: a workstation method cannot be both required and "
                    + "unavailable.");
        }
        Optional<ProductionMethod> finalMethod = Optional.empty();
        if (arguments.hasNonNull("final_method")) {
            String raw = arguments.path("final_method").asText("");
            finalMethod = ProductionMethod.parse(raw).filter(ProductionMethod::isRecipe);
            if (finalMethod.isEmpty()) {
                return completed("No production guide was created: final_method is not a supported recipe method.");
            }
        }
        ProductionQuantityRequest.ParseResult parsedQuantity = ProductionQuantityRequest.parseOptional(arguments);
        if (!parsedQuantity.valid()) {
            return completed("No production guide was created: " + parsedQuantity.error() + ".");
        }
        boolean quantityRequested = parsedQuantity.request().isPresent();
        ProductionQuantityRequest calculationQuantity = parsedQuantity.request()
                .orElseGet(() -> new ProductionQuantityRequest(1, 0, 0));

        CompletableFuture<ToolExecutionResult> result = new CompletableFuture<>();
        Optional<ProductionMethod> requestedFinalMethod = finalMethod;
        clientExecutor.execute(() -> {
            try {
                cancellation.throwIfCancelled();
                RecipeRouteFinder.Result found = routeFinder.find(
                        recipes.allRecipes(),
                        startingItems.values,
                        unavailableItems.values,
                        unavailableMethods.values,
                        requestedMethods.values,
                        targetItemId,
                        requestedFinalMethod,
                        calculationQuantity,
                        MAX_OPERATIONS,
                        cancellation
                );
                switch (found) {
                    case RecipeRouteFinder.Result.Candidates candidates -> {
                        List<RecipeRouteFinder.Candidate> matching = candidates.routes();
                        if (matching.isEmpty()) {
                            result.complete(ToolExecutionResult.text("No production guide was created: no exact "
                                    + "route satisfies the requested workstation constraints."));
                        } else if (matching.size() == 1) {
                            result.complete(ProductionGuidePublisher.publishOne(
                                    matching.getFirst(), quantityRequested, presentations
                            ));
                        } else {
                            ProductionRouteSearchStore.Search search = searches.put(matching, quantityRequested);
                            result.complete(ToolExecutionResult.text(resultJson(search, targetItemId)));
                        }
                    }
                    case RecipeRouteFinder.Result.Missing missing -> result.complete(ToolExecutionResult.text(
                            "No production guide was created: " + missing.reason()
                                    + ". Answer conservatively; do not invent a recipe or quantity."
                    ));
                }
            } catch (RuntimeException failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    private static String resultJson(ProductionRouteSearchStore.Search search, String targetItemId) {
        JsonNodeFactory json = JsonNodeFactory.instance;
        ObjectNode root = json.objectNode();
        root.put("status", "choice_required");
        root.put("search_id", search.id());
        root.put("target_item_id", targetItemId);
        root.put("quantity_requested", search.quantityRequested());
        ArrayNode routes = root.putArray("routes");
        for (ProductionRouteSearchStore.Route stored : search.routes()) {
            RecipeRouteFinder.Candidate candidate = stored.candidate();
            ProductionPlan plan = candidate.plan();
            ObjectNode route = routes.addObject();
            route.put("route_id", stored.id());
            route.put("operation_count", candidate.cards().size());
            route.put("cooking_ticks", plan.cookingTicks());
            ArrayNode methods = route.putArray("methods");
            candidate.cards().forEach(card -> methods.add(card.method().toolValue()));
            ArrayNode requirements = route.putArray("root_requirements");
            plan.rootMaterials().forEach(material -> {
                ObjectNode requirement = requirements.addObject();
                requirement.put("item_id", material.itemId());
                requirement.put("name", material.name());
                if (search.quantityRequested()) {
                    requirement.put("count", material.count());
                }
            });
            if (search.quantityRequested()) {
                route.put("requested_count", plan.requestedCount());
                route.put("produced_count", plan.producedCount());
            }
        }
        root.put("instruction", search.quantityRequested()
                ? "Choose one route normally. Choose two or three only if the player requested a comparison or "
                        + "the alternatives have a meaningful material, workstation, or time tradeoff. Call "
                        + "choose_production_routes with opaque IDs in preferred order."
                : "Choose the single route that best fits the request and recent conversation, then call "
                        + "choose_production_routes. Do not supply recipe IDs, steps, quantities, or presentation "
                        + "instructions.");
        return root.toString();
    }

    private static ParseSet parseItemIds(JsonNode raw, String field) {
        if (raw == null || raw.isNull()) {
            return new ParseSet(Set.of(), null);
        }
        if (!raw.isArray() || raw.size() > MAX_ITEM_CONSTRAINTS) {
            return new ParseSet(Set.of(), field + " must be an array of at most " + MAX_ITEM_CONSTRAINTS + " IDs");
        }
        Set<String> values = new LinkedHashSet<>();
        for (JsonNode value : raw) {
            String normalized = normalizeId(value.asText(""));
            if (normalized == null) {
                return new ParseSet(Set.of(), field + " contains an invalid item ID");
            }
            values.add(normalized);
        }
        return new ParseSet(Set.copyOf(values), null);
    }

    private static ParseMethods parseMethods(JsonNode raw, String field) {
        if (raw == null || raw.isNull()) {
            return new ParseMethods(Set.of(), null);
        }
        if (!raw.isArray()) {
            return new ParseMethods(Set.of(), field + " must be an array");
        }
        Set<ProductionMethod> values = new LinkedHashSet<>();
        for (JsonNode value : raw) {
            Optional<ProductionMethod> method = ProductionMethod.parse(value.asText(""))
                    .filter(ProductionMethod::isRecipe);
            if (method.isEmpty()) {
                return new ParseMethods(Set.of(), field + " contains an unsupported method");
            }
            values.add(method.get());
        }
        return new ParseMethods(Set.copyOf(values), null);
    }

    private static String normalizeId(String raw) {
        Identifier parsed = Identifier.tryParse(raw == null ? "" : raw.trim());
        return parsed == null ? null : parsed.toString();
    }

    private static CompletionStage<ToolExecutionResult> completed(String text) {
        return CompletableFuture.completedFuture(ToolExecutionResult.text(text));
    }

    private static ObjectNode schema() {
        JsonNodeFactory json = JsonNodeFactory.instance;
        ObjectNode schema = json.objectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.set("target_item_id", json.objectNode().put("type", "string")
                .put("description", "Namespaced ID of the item the player wants to produce"));
        properties.set("starting_item_ids", stringArray(json,
                "Only material items explicitly named by the player as owned, starting inputs, or required-material "
                        + "questions; never infer a starting item and do not put workstation blocks here"));
        properties.set("unavailable_item_ids", stringArray(json,
                "Items the player explicitly says they do not already have; the mod may still produce them"));
        properties.set("workstation_methods", methodArray(json,
                "Methods or workstations the player requests, prefers, or wants compared; candidate routes must "
                        + "use at least one"));
        properties.set("unavailable_methods", methodArray(json,
                "Recipe methods or workstations the player cannot use"));
        ObjectNode finalMethod = json.objectNode().put("type", "string")
                .put("description", "Optional method explicitly requested for the final operation");
        finalMethod.set("enum", recipeMethodValues(json));
        properties.set("final_method", finalMethod);
        properties.set("target_quantity", RecipeCardRequestTool.targetQuantitySchema(json));
        schema.set("required", json.arrayNode().add("target_item_id"));
        schema.put("additionalProperties", false);
        return schema;
    }

    private static ObjectNode stringArray(JsonNodeFactory json, String description) {
        ObjectNode array = json.objectNode().put("type", "array").put("maxItems", MAX_ITEM_CONSTRAINTS)
                .put("uniqueItems", true).put("description", description);
        array.set("items", json.objectNode().put("type", "string"));
        return array;
    }

    private static ObjectNode methodArray(JsonNodeFactory json, String description) {
        ObjectNode array = json.objectNode().put("type", "array").put("uniqueItems", true)
                .put("description", description);
        ObjectNode items = json.objectNode().put("type", "string");
        items.set("enum", recipeMethodValues(json));
        array.set("items", items);
        return array;
    }

    private static ArrayNode recipeMethodValues(JsonNodeFactory json) {
        ArrayNode values = json.arrayNode();
        for (ProductionMethod method : ProductionMethod.values()) {
            if (method.isRecipe()) {
                values.add(method.toolValue());
            }
        }
        return values;
    }

    private record ParseSet(Set<String> values, String error) {
    }

    private record ParseMethods(Set<ProductionMethod> values, String error) {
    }
}
