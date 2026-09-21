package dev.zubinjha.minecraftassistant.fabric;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.world.item.ItemStack;

record ProductionPlan(
        long requestedCount,
        long producedCount,
        String targetItemId,
        String targetName,
        String sourceItemId,
        String sourceName,
        List<Operation> operations,
        List<Material> rootMaterials,
        List<Material> leftovers,
        boolean genericFuelOmitted
) {
    ProductionPlan {
        if (requestedCount <= 0 || producedCount < requestedCount) {
            throw new IllegalArgumentException("Invalid planned quantity");
        }
        targetItemId = Objects.requireNonNull(targetItemId, "targetItemId");
        targetName = Objects.requireNonNull(targetName, "targetName");
        sourceItemId = sourceItemId == null ? "" : sourceItemId;
        sourceName = sourceName == null ? "" : sourceName;
        operations = List.copyOf(operations);
        rootMaterials = List.copyOf(rootMaterials);
        leftovers = List.copyOf(leftovers);
        if (operations.isEmpty() || operations.size() > ShowProcessTool.MAX_STEPS) {
            throw new IllegalArgumentException("A production plan needs one to six operations");
        }
    }

    List<ProductionCardData> cards() {
        return operations.stream().map(Operation::card).toList();
    }

    String summary() {
        return "Calculated: " + routeSummary();
    }

    String routeSummary() {
        String inputs = orderedRootMaterials().stream()
                .map(material -> count(material.count()) + " " + material.name())
                .reduce((left, right) -> left + " + " + right)
                .orElse("Materials");
        String route = operations.getLast().method().displayName();
        StringBuilder summary = new StringBuilder(inputs).append(" → ")
                .append(count(producedCount)).append(' ').append(quantityName(targetName, producedCount))
                .append(" via ").append(route).append('.');
        if (producedCount > requestedCount) {
            summary.append(' ').append(count(producedCount - requestedCount))
                    .append(" extra ").append(quantityName(targetName, producedCount - requestedCount)).append('.');
        }
        List<Material> intermediate = leftovers.stream()
                .filter(material -> !material.itemId().equals(targetItemId) && material.count() > 0)
                .toList();
        if (!intermediate.isEmpty()) {
            summary.append(" Left over: ");
            for (int index = 0; index < intermediate.size(); index++) {
                if (index > 0) {
                    summary.append(index == intermediate.size() - 1 ? " and " : ", ");
                }
                Material material = intermediate.get(index);
                summary.append(count(material.count())).append(' ')
                        .append(quantityName(material.name(), material.count()));
            }
            summary.append('.');
        }
        return summary.toString();
    }

    List<Material> orderedRootMaterials() {
        List<Material> ordered = new ArrayList<>(rootMaterials);
        ordered.sort(Comparator
                .comparing((Material material) -> material.itemId().equals(sourceItemId) ? 0 : 1)
                .thenComparing(Material::itemId));
        return List.copyOf(ordered);
    }

    Optional<Material> sourceMaterial() {
        return rootMaterials.stream().filter(material -> material.itemId().equals(sourceItemId)).findFirst();
    }

    long additionalConsumables() {
        return rootMaterials.stream()
                .filter(material -> !material.itemId().equals(sourceItemId))
                .mapToLong(Material::count)
                .sum();
    }

    long cookingTicks() {
        return operations.stream().mapToLong(operation ->
                Math.multiplyExact(operation.batches(), operation.cookingTicks())).sum();
    }

    static String count(long value) {
        return NumberFormat.getIntegerInstance(Locale.US).format(value);
    }

    static String quantityName(String name, long count) {
        if (count == 1 || name.endsWith("s") || isMassNoun(name)) {
            return name;
        }
        return name + "s";
    }

    private static boolean isMassNoun(String name) {
        return name.endsWith("Stone") || name.endsWith("Cobblestone") || name.endsWith("Redstone")
                || name.endsWith("Glass") || name.endsWith("Sand") || name.endsWith("Charcoal")
                || name.endsWith("Coal") || name.endsWith("Dust") || name.endsWith("Powder");
    }

    record Operation(
            ProductionCardData card,
            long batches,
            List<Material> inputs,
            Material output,
            long surplus,
            long cookingTicks
    ) {
        Operation {
            card = Objects.requireNonNull(card, "card");
            if (batches <= 0 || surplus < 0 || cookingTicks < 0) {
                throw new IllegalArgumentException("Invalid production operation totals");
            }
            inputs = List.copyOf(inputs);
            output = Objects.requireNonNull(output, "output");
        }

        String recipeId() {
            return card.recipeId();
        }

        ProductionMethod method() {
            return card.method();
        }

        long consumedInput() {
            return inputs.stream().mapToLong(Material::count).sum();
        }

        long outputProduced() {
            return output.count();
        }
    }

    record Material(
            String itemId,
            String name,
            long count,
            List<String> alternatives,
            ItemStack stack
    ) {
        Material {
            itemId = Objects.requireNonNull(itemId, "itemId");
            name = Objects.requireNonNull(name, "name");
            if (count <= 0) {
                throw new IllegalArgumentException("Material count must be positive");
            }
            alternatives = List.copyOf(alternatives);
            stack = Objects.requireNonNull(stack, "stack").copy();
        }

        @Override
        public ItemStack stack() {
            return stack.copy();
        }
    }
}
