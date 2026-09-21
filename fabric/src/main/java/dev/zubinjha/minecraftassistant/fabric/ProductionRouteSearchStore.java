package dev.zubinjha.minecraftassistant.fabric;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class ProductionRouteSearchStore {
    private static final int MAX_SEARCHES = 8;

    private final Map<String, Search> searches = new LinkedHashMap<>();
    private long nextSearch;
    private long nextRoute;

    synchronized Search put(List<RecipeRouteFinder.Candidate> candidates) {
        return put(candidates, true);
    }

    synchronized Search put(List<RecipeRouteFinder.Candidate> candidates, boolean quantityRequested) {
        String searchId = "s" + Long.toString(++nextSearch, Character.MAX_RADIX);
        List<Route> routes = new ArrayList<>(candidates.size());
        for (RecipeRouteFinder.Candidate candidate : candidates) {
            routes.add(new Route(
                    "p" + Long.toString(++nextRoute, Character.MAX_RADIX),
                    candidate
            ));
        }
        Search search = new Search(searchId, routes, quantityRequested);
        searches.put(searchId, search);
        while (searches.size() > MAX_SEARCHES) {
            searches.remove(searches.keySet().iterator().next());
        }
        return search;
    }

    synchronized Optional<Search> get(String searchId) {
        return Optional.ofNullable(searches.get(searchId));
    }

    record Search(String id, List<Route> routes, boolean quantityRequested) {
        Search {
            routes = List.copyOf(routes);
        }

        Optional<Route> route(String routeId) {
            return routes.stream().filter(route -> route.id().equals(routeId)).findFirst();
        }
    }

    record Route(String id, RecipeRouteFinder.Candidate candidate) {
    }
}
