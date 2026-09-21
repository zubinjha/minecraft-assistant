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

final class ShowRoutesTool implements Tool {
    static final int MAX_ROUTES = 3;

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "show_routes",
            "Select and display one to three routes returned by find_production_routes. Order route_ids with the "
                    + "best or most relevant route first. Normally select one; use multiple only for an explicit "
                    + "comparison or a meaningful tradeoff. Respect current and prior workstation constraints.",
            schema()
    );

    private final ProductionRouteSearchStore searches;
    private final ProductionPresentationCollector presentations;

    ShowRoutesTool(ProductionRouteSearchStore searches, ProductionPresentationCollector presentations) {
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
            return completed("No route guide was created: provide a valid search_id and one to "
                    + MAX_ROUTES + " route_ids.");
        }
        Set<String> uniqueIds = new HashSet<>();
        List<String> routeIds = new ArrayList<>();
        for (JsonNode rawRouteId : rawRouteIds) {
            String routeId = rawRouteId.asText("").trim();
            if (routeId.isEmpty() || !uniqueIds.add(routeId)) {
                return completed("No route guide was created: route_ids must be non-empty and unique.");
            }
            routeIds.add(routeId);
        }

        ProductionRouteSearchStore.Search search = searches.get(searchId).orElse(null);
        if (search == null) {
            return completed("No route guide was created: that route search is unavailable. Call "
                    + "find_production_routes again.");
        }
        List<ProductionPresentation.Route> selected = new ArrayList<>();
        for (String routeId : routeIds) {
            ProductionRouteSearchStore.Route stored = search.route(routeId).orElse(null);
            if (stored == null) {
                return completed("No route guide was created: route_id " + routeId
                        + " was not returned by search " + searchId + ".");
            }
            RecipeRouteFinder.Candidate candidate = stored.candidate();
            selected.add(new ProductionPresentation.Route(
                    stored.id(),
                    candidate.plan().operations().getLast().method().displayName(),
                    candidate.plan()
            ));
        }

        ProductionPresentation presentation;
        if (selected.size() == 1) {
            ProductionPresentation.Route route = selected.getFirst();
            presentations.setPlanned(route.plan());
            presentation = new ProductionPresentation.Plan(route.plan());
        } else {
            presentations.setComparison(selected);
            presentation = new ProductionPresentation.Comparison(selected);
        }
        String answer = presentation.authoritativeSummary().orElseThrow();
        return CompletableFuture.completedFuture(ToolExecutionResult.terminal(
                "The selected native route guide is ready. The mod will display its authoritative summary.",
                answer
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
                .put("description", "Opaque search_id returned by find_production_routes"));
        properties.set("route_ids", json.objectNode()
                .put("type", "array")
                .put("minItems", 1)
                .put("maxItems", MAX_ROUTES)
                .put("uniqueItems", true)
                .put("description", "Relevant route IDs, best or most relevant first")
                .set("items", json.objectNode().put("type", "string")));
        schema.set("properties", properties);
        schema.set("required", json.arrayNode().add("search_id").add("route_ids"));
        schema.put("additionalProperties", false);
        return schema;
    }
}
