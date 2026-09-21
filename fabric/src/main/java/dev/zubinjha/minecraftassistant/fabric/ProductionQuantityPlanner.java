package dev.zubinjha.minecraftassistant.fabric;

import dev.zubinjha.minecraftassistant.core.CancellationToken;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

final class ProductionQuantityPlanner {
    Result plan(List<ProductionCardData> cards, ProductionQuantityRequest request,
                Optional<String> requestedSourceId) {
        return plan(cards, request, requestedSourceId, CancellationToken.NONE);
    }

    Result plan(List<ProductionCardData> cards, ProductionQuantityRequest request,
                Optional<String> requestedSourceId, CancellationToken cancellation) {
        return plan(cards, request, requestedSourceId.map(Set::of).orElseGet(Set::of), cancellation);
    }

    Result plan(List<ProductionCardData> cards, ProductionQuantityRequest request,
                Set<String> requestedSourceIds, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        if (cards.isEmpty() || cards.size() > ShowProcessTool.MAX_STEPS
                || cards.stream().anyMatch(card -> !card.method().isRecipe())) {
            return new Result.Failure("only one to six supported recipe operations can be quantity-planned");
        }
        ItemStack target = cards.getLast().result().primary();
        final long requested;
        try {
            requested = request.totalFor(target);
        } catch (IllegalArgumentException invalid) {
            return new Result.Failure(invalid.getMessage());
        }
        try {
            return new Result.Success(buildPlan(cards, target, requested, requestedSourceIds, cancellation));
        } catch (InvalidPlan invalid) {
            return new Result.Failure(invalid.getMessage());
        } catch (ArithmeticException overflow) {
            return new Result.Failure("the quantity calculation overflowed its supported range");
        }
    }

    private static ProductionPlan buildPlan(
            List<ProductionCardData> cards,
            ItemStack target,
            long requested,
            Set<String> requestedSourceIds,
            CancellationToken cancellation
    ) {
        Map<String, Node> byOutput = new LinkedHashMap<>();
        for (ProductionCardData card : cards) {
            cancellation.throwIfCancelled();
            ItemStack output = card.result().primary();
            if (output.isEmpty() || output.getCount() <= 0) {
                throw new InvalidPlan("operation " + card.recipeId() + " has no countable output");
            }
            String outputId = itemId(output);
            if (byOutput.putIfAbsent(outputId, new Node(card, outputId, output.copy())) != null) {
                throw new InvalidPlan("multiple selected operations produce " + outputId);
            }
        }

        boolean[] omittedFuel = {false};
        for (Node node : byOutput.values()) {
            node.inputs = selectedInputs(node.card, byOutput, requestedSourceIds, omittedFuel);
        }
        Node targetNode = byOutput.get(itemId(target));
        if (targetNode == null || targetNode.card != cards.getLast()) {
            throw new InvalidPlan("the final operation does not produce the requested target");
        }

        List<Node> topological = new ArrayList<>();
        Map<String, VisitState> states = new HashMap<>();
        visit(targetNode, byOutput, states, topological, cancellation);
        if (topological.size() != byOutput.size()) {
            throw new InvalidPlan("the selected operations include a disconnected branch");
        }

        Map<String, Long> demand = new LinkedHashMap<>();
        demand.put(targetNode.outputId, requested);
        Map<String, OperationTotals> totals = new HashMap<>();
        Map<String, MaterialPrototype> prototypes = new LinkedHashMap<>();
        prototypes.put(targetNode.outputId, prototype(targetNode.output, List.of(targetNode.outputId)));
        for (Node node : topological.reversed()) {
            cancellation.throwIfCancelled();
            long required = demand.getOrDefault(node.outputId, 0L);
            if (required <= 0) {
                throw new InvalidPlan("operation " + node.card.recipeId() + " is not required by the plan");
            }
            long batches = ceilDiv(required, node.output.getCount());
            long produced = Math.multiplyExact(batches, node.output.getCount());
            Map<String, MutableMaterial> operationInputs = new LinkedHashMap<>();
            for (SelectedInput input : node.inputs) {
                long amount = Math.multiplyExact(batches, input.stack.getCount());
                demand.merge(input.itemId, amount, Math::addExact);
                prototypes.putIfAbsent(input.itemId, prototype(input.stack, input.alternatives));
                merge(operationInputs, input.stack, amount, input.alternatives);
            }
            long cookingTicks = node.card instanceof ProductionCardData.Cooking cooking
                    ? cooking.durationTicks() : 0;
            totals.put(node.outputId, new OperationTotals(
                    batches,
                    immutableMaterials(operationInputs),
                    material(node.output, produced, List.of(node.outputId)),
                    produced - required,
                    cookingTicks
            ));
        }

        List<ProductionPlan.Material> roots = new ArrayList<>();
        for (Map.Entry<String, Long> entry : demand.entrySet()) {
            if (!byOutput.containsKey(entry.getKey())) {
                MaterialPrototype prototype = prototypes.get(entry.getKey());
                if (prototype == null) {
                    throw new InvalidPlan("no item information is available for " + entry.getKey());
                }
                roots.add(prototype.material(entry.getValue()));
            }
        }
        if (roots.isEmpty()) {
            throw new InvalidPlan("the plan has no external materials");
        }
        if (!requestedSourceIds.isEmpty()
                && roots.stream().noneMatch(material -> requestedSourceIds.contains(material.itemId()))) {
            throw new InvalidPlan("the selected operations do not consume any requested starting material");
        }

        Map<String, Long> inventory = new LinkedHashMap<>();
        Map<String, MaterialPrototype> inventoryPrototypes = new LinkedHashMap<>(prototypes);
        for (ProductionPlan.Material root : roots) {
            inventory.merge(root.itemId(), root.count(), Math::addExact);
        }
        List<ProductionPlan.Operation> operations = new ArrayList<>();
        for (Node node : topological) {
            cancellation.throwIfCancelled();
            OperationTotals operation = totals.get(node.outputId);
            for (ProductionPlan.Material input : operation.inputs) {
                long available = inventory.getOrDefault(input.itemId(), 0L);
                if (available < input.count()) {
                    throw new InvalidPlan("forward verification is missing " + input.name());
                }
                inventory.put(input.itemId(), available - input.count());
            }
            inventory.merge(node.outputId, operation.output.count(), Math::addExact);
            inventoryPrototypes.putIfAbsent(node.outputId, prototype(node.output, List.of(node.outputId)));
            operations.add(new ProductionPlan.Operation(
                    node.card, operation.batches, operation.inputs, operation.output,
                    operation.surplus, operation.cookingTicks
            ));
        }

        long produced = totals.get(targetNode.outputId).output.count();
        long targetAvailable = inventory.getOrDefault(targetNode.outputId, 0L);
        if (targetAvailable != produced || produced < requested) {
            throw new InvalidPlan("forward verification did not produce the requested target");
        }
        inventory.put(targetNode.outputId, targetAvailable - requested);
        List<ProductionPlan.Material> leftovers = new ArrayList<>();
        for (Map.Entry<String, Long> entry : inventory.entrySet()) {
            if (entry.getValue() > 0) {
                leftovers.add(inventoryPrototypes.get(entry.getKey()).material(entry.getValue()));
            }
        }

        String sourceId = roots.stream()
                .map(ProductionPlan.Material::itemId)
                .filter(requestedSourceIds::contains)
                .findFirst()
                .orElseGet(() -> roots.getFirst().itemId());
        String sourceName = roots.stream().filter(material -> material.itemId().equals(sourceId))
                .map(ProductionPlan.Material::name).findFirst().orElse("");
        return new ProductionPlan(
                requested, produced, targetNode.outputId, target.getHoverName().getString(),
                sourceId, sourceName, operations, roots, leftovers, omittedFuel[0]
        );
    }

    private static List<SelectedInput> selectedInputs(
            ProductionCardData card,
            Map<String, Node> byOutput,
            Set<String> requestedSourceIds,
            boolean[] omittedFuel
    ) {
        List<SelectedInput> selected = new ArrayList<>();
        for (ProductionCardData.Slot slot : ingredients(card)) {
            if (slot.anyFuel()) {
                omittedFuel[0] = true;
                continue;
            }
            ItemStack stack = selectAlternative(slot, byOutput, requestedSourceIds);
            if (stack.isEmpty()) {
                continue;
            }
            if (hasCraftingRemainder(stack)) {
                throw new InvalidPlan("operation " + card.recipeId()
                        + " uses a crafting remainder and cannot be calculated safely");
            }
            selected.add(new SelectedInput(
                    itemId(stack), stack.copy(),
                    slot.alternatives().stream().map(ProductionQuantityPlanner::itemId).distinct().toList()
            ));
        }
        if (selected.isEmpty()) {
            throw new InvalidPlan("operation " + card.recipeId() + " has no countable ingredients");
        }
        return List.copyOf(selected);
    }

    private static ItemStack selectAlternative(
            ProductionCardData.Slot slot,
            Map<String, Node> byOutput,
            Set<String> requestedSourceIds
    ) {
        List<ItemStack> producedMatches = slot.alternatives().stream()
                .filter(alternative -> byOutput.containsKey(itemId(alternative))).toList();
        List<String> distinctProduced = producedMatches.stream()
                .map(ProductionQuantityPlanner::itemId).distinct().toList();
        if (distinctProduced.size() > 1) {
            throw new InvalidPlan("an ingredient can consume multiple selected intermediate outputs");
        }
        if (!producedMatches.isEmpty()) {
            return producedMatches.getFirst();
        }
        Optional<ItemStack> source = slot.alternatives().stream()
                .filter(alternative -> requestedSourceIds.contains(itemId(alternative)))
                .findFirst();
        if (source.isPresent()) {
            return source.get();
        }
        return slot.primary();
    }

    private static void visit(
            Node node,
            Map<String, Node> byOutput,
            Map<String, VisitState> states,
            List<Node> ordered,
            CancellationToken cancellation
    ) {
        cancellation.throwIfCancelled();
        VisitState state = states.get(node.outputId);
        if (state == VisitState.VISITING) {
            throw new InvalidPlan("the selected operations contain a cycle");
        }
        if (state == VisitState.VISITED) {
            return;
        }
        states.put(node.outputId, VisitState.VISITING);
        for (SelectedInput input : node.inputs) {
            Node dependency = byOutput.get(input.itemId);
            if (dependency != null) {
                visit(dependency, byOutput, states, ordered, cancellation);
            }
        }
        states.put(node.outputId, VisitState.VISITED);
        ordered.add(node);
    }

    private static List<ProductionCardData.Slot> ingredients(ProductionCardData card) {
        return switch (card) {
            case ProductionCardData.Crafting crafting -> crafting.ingredients();
            case ProductionCardData.Cooking cooking -> List.of(cooking.input(), cooking.fuel());
            case ProductionCardData.Stonecutting stonecutting -> List.of(stonecutting.input());
            case ProductionCardData.Smithing smithing -> List.of(
                    smithing.template(), smithing.base(), smithing.addition()
            );
            default -> List.of();
        };
    }

    static List<ProductionCardData.Slot> consumableIngredients(ProductionCardData card) {
        return ingredients(card).stream().filter(slot -> !slot.anyFuel()).toList();
    }

    private static boolean hasCraftingRemainder(ItemStack stack) {
        var remainder = stack.getItem().getCraftingRemainder();
        return remainder != null && !remainder.create().isEmpty();
    }

    private static void merge(Map<String, MutableMaterial> materials, ItemStack selected,
                              long amount, List<String> alternatives) {
        String id = itemId(selected);
        MutableMaterial value = materials.computeIfAbsent(id, ignored -> new MutableMaterial(
                id, selected.getHoverName().getString(), alternatives, selected.copy()
        ));
        value.count = Math.addExact(value.count, amount);
    }

    private static List<ProductionPlan.Material> immutableMaterials(Map<String, MutableMaterial> materials) {
        return materials.values().stream().map(MutableMaterial::material).toList();
    }

    private static ProductionPlan.Material material(ItemStack stack, long amount, List<String> alternatives) {
        return new ProductionPlan.Material(
                itemId(stack), stack.getHoverName().getString(), amount, alternatives, normalized(stack)
        );
    }

    private static MaterialPrototype prototype(ItemStack stack, List<String> alternatives) {
        return new MaterialPrototype(
                itemId(stack), stack.getHoverName().getString(), alternatives, normalized(stack)
        );
    }

    private static ItemStack normalized(ItemStack stack) {
        ItemStack copy = stack.copy();
        copy.setCount(1);
        return copy;
    }

    static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static long ceilDiv(long numerator, long denominator) {
        return Math.addExact(numerator, denominator - 1) / denominator;
    }

    sealed interface Result permits Result.Success, Result.Failure {
        record Success(ProductionPlan plan) implements Result {
        }

        record Failure(String reason) implements Result {
        }
    }

    private enum VisitState { VISITING, VISITED }

    private static final class Node {
        private final ProductionCardData card;
        private final String outputId;
        private final ItemStack output;
        private List<SelectedInput> inputs = List.of();

        private Node(ProductionCardData card, String outputId, ItemStack output) {
            this.card = card;
            this.outputId = outputId;
            this.output = output;
        }
    }

    private record SelectedInput(String itemId, ItemStack stack, List<String> alternatives) { }

    private record OperationTotals(long batches, List<ProductionPlan.Material> inputs,
                                   ProductionPlan.Material output, long surplus, long cookingTicks) { }

    private record MaterialPrototype(String itemId, String name, List<String> alternatives, ItemStack stack) {
        private ProductionPlan.Material material(long count) {
            return new ProductionPlan.Material(itemId, name, count, alternatives, stack);
        }
    }

    private static final class MutableMaterial {
        private final String itemId;
        private final String name;
        private final List<String> alternatives;
        private final ItemStack stack;
        private long count;

        private MutableMaterial(String itemId, String name, List<String> alternatives, ItemStack stack) {
            this.itemId = itemId;
            this.name = name;
            this.alternatives = List.copyOf(alternatives);
            this.stack = stack.copy();
        }

        private ProductionPlan.Material material() {
            return new ProductionPlan.Material(itemId, name, count, alternatives, stack);
        }
    }

    private static final class InvalidPlan extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private InvalidPlan(String message) { super(message); }
    }
}
