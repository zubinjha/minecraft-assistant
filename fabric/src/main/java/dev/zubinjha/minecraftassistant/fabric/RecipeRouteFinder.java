package dev.zubinjha.minecraftassistant.fabric;

import dev.zubinjha.minecraftassistant.core.CancellationToken;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.world.item.ItemStack;

final class RecipeRouteFinder {
    static final int MAX_CANDIDATES = 8;
    private static final int MAX_EXPANDED_GRAPHS = 64;
    private final ProductionQuantityPlanner planner;

    RecipeRouteFinder(ProductionQuantityPlanner planner) {
        this.planner = planner;
    }

    Result find(
            List<ProductionCardData> catalog,
            String sourceItemId,
            String targetItemId,
            Optional<ProductionMethod> finalMethod,
            ProductionQuantityRequest quantity,
            int maximumSteps
    ) {
        return find(catalog, sourceItemId, targetItemId, finalMethod, quantity, maximumSteps,
                CancellationToken.NONE);
    }

    Result find(
            List<ProductionCardData> catalog,
            String sourceItemId,
            String targetItemId,
            Optional<ProductionMethod> finalMethod,
            ProductionQuantityRequest quantity,
            int maximumSteps,
            CancellationToken cancellation
    ) {
        return find(
                catalog,
                Set.of(sourceItemId),
                Set.of(),
                Set.of(),
                Set.of(),
                targetItemId,
                finalMethod,
                quantity,
                maximumSteps,
                cancellation
        );
    }

    Result find(
            List<ProductionCardData> catalog,
            Set<String> startingItemIds,
            Set<String> unavailableItemIds,
            Set<ProductionMethod> unavailableMethods,
            Set<ProductionMethod> requestedMethods,
            String targetItemId,
            Optional<ProductionMethod> finalMethod,
            ProductionQuantityRequest quantity,
            int maximumSteps,
            CancellationToken cancellation
    ) {
        cancellation.throwIfCancelled();
        Set<String> normalizedStarts = Set.copyOf(new LinkedHashSet<>(startingItemIds));
        Map<String, List<ProductionCardData>> byOutput = new HashMap<>();
        for (ProductionCardData card : catalog) {
            cancellation.throwIfCancelled();
            if (!card.method().isRecipe() || card.result().primary().isEmpty()
                    || unavailableMethods.contains(card.method())) {
                continue;
            }
            byOutput.computeIfAbsent(ProductionQuantityPlanner.itemId(card.result().primary()), ignored ->
                    new ArrayList<>()).add(card);
        }
        byOutput.values().forEach(cards -> cards.sort(Comparator.comparing(ProductionCardData::recipeId)
                .thenComparing(card -> card.method().toolValue())));

        List<List<ProductionCardData>> routes = new ArrayList<>();
        if (normalizedStarts.isEmpty()) {
            for (ProductionCardData card : byOutput.getOrDefault(targetItemId, List.of())) {
                if (finalMethod.isEmpty() || card.method() == finalMethod.get()) {
                    routes.add(List.of(card));
                }
            }
        } else {
            searchBackward(
                    targetItemId,
                    normalizedStarts,
                    finalMethod,
                    byOutput,
                    maximumSteps,
                    new ArrayList<>(),
                    new HashSet<>(),
                    routes,
                    cancellation
            );
        }
        List<List<ProductionCardData>> graphs = new ArrayList<>();
        Set<String> expandedSignatures = new HashSet<>();
        for (List<ProductionCardData> route : routes) {
            expandBranches(route, normalizedStarts, byOutput, maximumSteps, graphs,
                    expandedSignatures, cancellation);
        }
        Map<String, Candidate> exactCandidates = new LinkedHashMap<>();
        for (List<ProductionCardData> route : graphs) {
            cancellation.throwIfCancelled();
            ProductionQuantityPlanner.Result result = planner.plan(
                    route, quantity, normalizedStarts, cancellation
            );
            if (result instanceof ProductionQuantityPlanner.Result.Success success
                    && success.plan().rootMaterials().stream()
                    .noneMatch(material -> unavailableItemIds.contains(material.itemId()))
                    && (requestedMethods.isEmpty() || success.plan().operations().stream()
                    .map(ProductionPlan.Operation::method)
                    .anyMatch(requestedMethods::contains))) {
                List<ProductionCardData> orderedCards = success.plan().cards();
                String key = orderedCards.stream().map(card -> card.recipeId() + "|" + card.method().toolValue())
                        .reduce((left, right) -> left + ">" + right).orElseThrow();
                exactCandidates.putIfAbsent(key, new Candidate(
                        orderedCards, success.plan(), key, normalizedStarts
                ));
            }
        }
        List<Candidate> candidates = selectCandidates(exactCandidates.values());
        if (candidates.isEmpty()) {
            String sourceDescription = normalizedStarts.isEmpty()
                    ? "available native ingredients"
                    : String.join(", ", normalizedStarts);
            return new Result.Missing("no supported production route connects "
                    + sourceDescription + " to " + targetItemId
                    + finalMethod.map(method -> " using final method " + method.toolValue()).orElse(""));
        }
        return new Result.Candidates(candidates);
    }

    private static void expandBranches(
            List<ProductionCardData> seed,
            Set<String> startingItemIds,
            Map<String, List<ProductionCardData>> byOutput,
            int maximumSteps,
            List<List<ProductionCardData>> completed,
            Set<String> visited,
            CancellationToken cancellation
    ) {
        cancellation.throwIfCancelled();
        if (completed.size() >= MAX_EXPANDED_GRAPHS) {
            return;
        }
        List<ProductionCardData> graph = distinctByOutput(seed);
        String signature = graph.stream().map(ProductionCardData::recipeId).sorted()
                .reduce((left, right) -> left + ">" + right).orElse("");
        if (!visited.add(signature)) {
            return;
        }
        Map<String, ProductionCardData> selectedByOutput = new HashMap<>();
        graph.forEach(card -> selectedByOutput.put(
                ProductionQuantityPlanner.itemId(card.result().primary()), card
        ));

        for (ProductionCardData consumer : List.copyOf(graph)) {
            for (ProductionCardData.Slot ingredient : ProductionQuantityPlanner.consumableIngredients(consumer)) {
                boolean alreadySatisfied = ingredient.alternatives().stream().anyMatch(alternative -> {
                    String id = ProductionQuantityPlanner.itemId(alternative);
                    return startingItemIds.contains(id) || selectedByOutput.containsKey(id);
                });
                if (alreadySatisfied) {
                    continue;
                }
                List<List<ProductionCardData>> branchPaths = new ArrayList<>();
                for (ItemStack alternative : ingredient.alternatives()) {
                    searchBackward(
                            ProductionQuantityPlanner.itemId(alternative),
                            startingItemIds,
                            Optional.empty(),
                            byOutput,
                            maximumSteps,
                            new ArrayList<>(),
                            new HashSet<>(),
                            branchPaths,
                            cancellation
                    );
                }
                if (branchPaths.isEmpty()) {
                    continue;
                }
                for (List<ProductionCardData> branch : branchPaths) {
                    List<ProductionCardData> merged = mergeGraph(graph, branch);
                    if (merged.size() <= maximumSteps) {
                        expandBranches(merged, startingItemIds, byOutput, maximumSteps,
                                completed, visited, cancellation);
                    }
                }
                return;
            }
        }
        completed.add(graph);
    }

    private static List<ProductionCardData> mergeGraph(
            List<ProductionCardData> graph,
            List<ProductionCardData> branch
    ) {
        Map<String, ProductionCardData> merged = new LinkedHashMap<>();
        for (ProductionCardData card : graph) {
            merged.put(ProductionQuantityPlanner.itemId(card.result().primary()), card);
        }
        for (ProductionCardData card : branch) {
            String outputId = ProductionQuantityPlanner.itemId(card.result().primary());
            ProductionCardData existing = merged.get(outputId);
            if (existing == null || existing.recipeId().equals(card.recipeId())) {
                merged.putIfAbsent(outputId, card);
            }
        }
        ProductionCardData target = graph.getLast();
        List<ProductionCardData> cards = new ArrayList<>(merged.values());
        cards.remove(target);
        cards.add(target);
        return List.copyOf(cards);
    }

    private static List<ProductionCardData> distinctByOutput(List<ProductionCardData> cards) {
        Map<String, ProductionCardData> distinct = new LinkedHashMap<>();
        for (ProductionCardData card : cards) {
            distinct.putIfAbsent(ProductionQuantityPlanner.itemId(card.result().primary()), card);
        }
        ProductionCardData target = cards.getLast();
        List<ProductionCardData> result = new ArrayList<>(distinct.values());
        result.remove(target);
        result.add(target);
        return List.copyOf(result);
    }

    static List<Candidate> selectCandidates(java.util.Collection<Candidate> exactCandidates) {
        Map<String, Candidate> byEquivalentRoute = new LinkedHashMap<>();
        exactCandidates.stream().sorted(CANDIDATE_ORDER).forEach(candidate ->
                byEquivalentRoute.putIfAbsent(candidate.groupingSignature(), candidate));
        List<Candidate> grouped = byEquivalentRoute.values().stream()
                .sorted(CANDIDATE_ORDER)
                .toList();
        return grouped.stream()
                .filter(candidate -> grouped.stream().noneMatch(other -> other != candidate
                        && dominates(other, candidate)))
                .limit(MAX_CANDIDATES)
                .toList();
    }

    private static boolean dominates(Candidate preferred, Candidate candidate) {
        ProductionPlan left = preferred.plan();
        ProductionPlan right = candidate.plan();
        if (left.requestedCount() != right.requestedCount()
                || !left.targetItemId().equals(right.targetItemId())
                || !preferred.capabilitySignature().equals(candidate.capabilitySignature())) {
            return false;
        }
        Map<String, Long> leftRoots = rootCounts(left);
        Map<String, Long> rightRoots = rootCounts(right);
        if (!leftRoots.keySet().equals(rightRoots.keySet())) {
            return false;
        }
        boolean strictlyBetter = false;
        for (String itemId : leftRoots.keySet()) {
            long leftCount = leftRoots.get(itemId);
            long rightCount = rightRoots.get(itemId);
            if (leftCount > rightCount) {
                return false;
            }
            strictlyBetter |= leftCount < rightCount;
        }
        if (left.operations().size() > right.operations().size()
                || left.cookingTicks() > right.cookingTicks()) {
            return false;
        }
        strictlyBetter |= left.operations().size() < right.operations().size();
        strictlyBetter |= left.cookingTicks() < right.cookingTicks();
        return strictlyBetter;
    }

    private static Map<String, Long> rootCounts(ProductionPlan plan) {
        Map<String, Long> result = new HashMap<>();
        for (ProductionPlan.Material material : plan.rootMaterials()) {
            result.merge(material.itemId(), material.count(), Math::addExact);
        }
        return result;
    }

    private static void searchBackward(
            String neededItemId,
            Set<String> sourceItemIds,
            Optional<ProductionMethod> finalMethod,
            Map<String, List<ProductionCardData>> byOutput,
            int remainingSteps,
            List<ProductionCardData> reverseRoute,
            Set<String> visitedOutputs,
            List<List<ProductionCardData>> routes,
            CancellationToken cancellation
    ) {
        cancellation.throwIfCancelled();
        if (remainingSteps == 0 || !visitedOutputs.add(neededItemId)) {
            return;
        }
        for (ProductionCardData card : byOutput.getOrDefault(neededItemId, List.of())) {
            if (reverseRoute.isEmpty() && finalMethod.isPresent() && card.method() != finalMethod.get()) {
                continue;
            }
            reverseRoute.add(card);
            for (ProductionCardData.Slot ingredient : ProductionQuantityPlanner.consumableIngredients(card)) {
                for (ItemStack alternative : ingredient.alternatives()) {
                    String inputId = ProductionQuantityPlanner.itemId(alternative);
                    if (sourceItemIds.contains(inputId)) {
                        List<ProductionCardData> forward = new ArrayList<>(reverseRoute.reversed());
                        routes.add(List.copyOf(forward));
                    } else {
                        searchBackward(
                                inputId,
                                sourceItemIds,
                                finalMethod,
                                byOutput,
                                remainingSteps - 1,
                                reverseRoute,
                                visitedOutputs,
                                routes,
                                cancellation
                        );
                    }
                }
            }
            reverseRoute.removeLast();
        }
        visitedOutputs.remove(neededItemId);
    }

    private static final Comparator<Candidate> CANDIDATE_ORDER = Comparator
            .comparingLong(Candidate::startingMaterialCount)
            .thenComparingLong(Candidate::additionalConsumables)
            .thenComparingInt(candidate -> candidate.cards().size())
            .thenComparingLong(candidate -> candidate.plan().cookingTicks())
            .thenComparing(Candidate::stableKey);

    sealed interface Result permits Result.Candidates, Result.Missing {
        record Candidates(List<Candidate> routes) implements Result {
            public Candidates {
                routes = List.copyOf(routes);
            }
        }

        record Missing(String reason) implements Result {
        }
    }

    record Candidate(
            List<ProductionCardData> cards,
            ProductionPlan plan,
            String stableKey,
            Set<String> startingItemIds
    ) {
        Candidate {
            cards = List.copyOf(cards);
            startingItemIds = Set.copyOf(startingItemIds);
        }

        Candidate(List<ProductionCardData> cards, ProductionPlan plan, String stableKey) {
            this(cards, plan, stableKey,
                    plan.sourceItemId().isBlank() ? Set.of() : Set.of(plan.sourceItemId()));
        }

        long startingMaterialCount() {
            return plan.rootMaterials().stream()
                    .filter(material -> startingItemIds.contains(material.itemId()))
                    .mapToLong(ProductionPlan.Material::count)
                    .sum();
        }

        long additionalConsumables() {
            return plan.rootMaterials().stream()
                    .filter(material -> !startingItemIds.contains(material.itemId()))
                    .mapToLong(ProductionPlan.Material::count)
                    .sum();
        }

        String methodSignature() {
            return plan.operations().stream().map(operation -> operation.method().toolValue())
                    .reduce((left, right) -> left + ">" + right).orElseThrow();
        }

        String capabilitySignature() {
            return plan.operations().stream()
                    .map(operation -> operation.method().toolValue())
                    .distinct()
                    .sorted()
                    .reduce((left, right) -> left + "+" + right)
                    .orElseThrow();
        }

        String groupingSignature() {
            String roots = plan.rootMaterials().stream()
                    .map(ProductionPlan.Material::itemId)
                    .distinct()
                    .sorted()
                    .reduce((left, right) -> left + "+" + right)
                    .orElseThrow();
            return methodSignature() + "|" + roots;
        }
    }
}
