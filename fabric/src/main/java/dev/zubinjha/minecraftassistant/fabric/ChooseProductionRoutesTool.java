package dev.zubinjha.minecraftassistant.fabric;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.zubinjha.minecraftassistant.core.CancellationToken;
import dev.zubinjha.minecraftassistant.core.Tool;
import dev.zubinjha.minecraftassistant.core.ToolDefinition;
import dev.zubinjha.minecraftassistant.core.ToolExecutionResult;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

final class ChooseProductionRoutesTool implements Tool {
    static final int MAX_ROUTES = 3;
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "choose_production_routes",
            "Choose opaque route candidates returned by prepare_production using the player's request and recent "
                    + "conversation. Normally choose one. For quantity requests, choose two or three only when the "
                    + "player requests a comparison or alternatives have a meaningful material, workstation, or "
                    + "time tradeoff. Never provide recipe IDs, steps, arithmetic, or presentation instructions.",
            schema()
    );

    private final ProductionRouteSearchStore searches;
    private final ProductionPresentationCollector presentations;

    ChooseProductionRoutesTool(
            ProductionRouteSearchStore searches,
            ProductionPresentationCollector presentations
    ) {
        this.searches = searches;
        this.presentations = presentations;
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public CompletionStage<ToolExecutionResult> execute(JsonNode arguments, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        String searchId = arguments.path("search_id").asText("").trim();
        JsonNode rawRouteIds = arguments.get("route_ids");
        if (searchId.isEmpty() || rawRouteIds == null || !rawRouteIds.isArray()
                || rawRouteIds.isEmpty() || rawRouteIds.size() > MAX_ROUTES) {
            return completed("No production guide was created: provide one to " + MAX_ROUTES
                    + " route_ids from a valid search_id.");
        }
        Set<String> uniqueIds = new HashSet<>();
        List<String> routeIds = new ArrayList<>();
        for (JsonNode rawRouteId : rawRouteIds) {
            String routeId = rawRouteId.asText("").trim();
            if (routeId.isEmpty() || !uniqueIds.add(routeId)) {
                return completed("No production guide was created: route_ids must be non-empty and unique.");
            }
            routeIds.add(routeId);
        }

        ProductionRouteSearchStore.Search search = searches.get(searchId).orElse(null);
        if (search == null) {
            return completed("No production guide was created: that route search is unavailable. Call "
                    + "prepare_production again.");
        }
        List<ProductionRouteSearchStore.Route> selected = new ArrayList<>();
        for (String routeId : routeIds) {
            ProductionRouteSearchStore.Route stored = search.route(routeId).orElse(null);
            if (stored == null) {
                return completed("No production guide was created: route_id " + routeId
                        + " was not returned by search " + searchId + ".");
            }
            selected.add(stored);
        }
        return CompletableFuture.completedFuture(ProductionGuidePublisher.publishSelected(
                selected, search.quantityRequested(), presentations
        ));
    }

    private static CompletionStage<ToolExecutionResult> completed(String text) {
        return CompletableFuture.completedFuture(ToolExecutionResult.text(text));
    }

    private static ObjectNode schema() {
        JsonNodeFactory json = JsonNodeFactory.instance;
        ObjectNode schema = json.objectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.set("search_id", json.objectNode().put("type", "string")
                .put("description", "Opaque search_id returned by prepare_production"));
        ObjectNode routeIds = json.objectNode().put("type", "array").put("minItems", 1)
                .put("maxItems", MAX_ROUTES).put("uniqueItems", true)
                .put("description", "Opaque route IDs in preferred order");
        routeIds.set("items", json.objectNode().put("type", "string"));
        properties.set("route_ids", routeIds);
        schema.set("required", json.arrayNode().add("search_id").add("route_ids"));
        schema.put("additionalProperties", false);
        return schema;
    }
}
