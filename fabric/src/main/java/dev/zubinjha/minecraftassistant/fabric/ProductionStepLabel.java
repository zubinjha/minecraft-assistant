package dev.zubinjha.minecraftassistant.fabric;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.world.item.ItemStack;

final class ProductionStepLabel {
    private ProductionStepLabel() {
    }

    static String describe(int stepIndex, ProductionCardData card) {
        return describe(stepIndex, card, List.of());
    }

    static String describe(int stepIndex, List<ProductionCardData> sequence) {
        ProductionCardData card = sequence.get(stepIndex);
        List<ItemStack> earlierOutputs = sequence.subList(0, stepIndex).stream()
                .map(ProductionCardData::result)
                .map(ProductionCardData.Slot::primary)
                .filter(stack -> !stack.isEmpty())
                .toList();
        return describe(stepIndex, card, earlierOutputs);
    }

    private static String describe(
            int stepIndex,
            ProductionCardData card,
            List<ItemStack> preferredInputs
    ) {
        String inputs = stableInputNames(card, preferredInputs).stream()
                .reduce((left, right) -> left + " + " + right)
                .orElse(card.method().displayName());
        ItemStack output = card.result().primary();
        String outputName = output.isEmpty() ? card.title().getString() : output.getHoverName().getString();
        return "Step " + (stepIndex + 1) + ": " + inputs + " → " + outputName;
    }

    static List<String> stableInputNames(ProductionCardData card) {
        return stableInputNames(card, List.of());
    }

    private static List<String> stableInputNames(
            ProductionCardData card,
            List<ItemStack> preferredInputs
    ) {
        Set<String> names = new LinkedHashSet<>();
        for (ProductionCardData.Slot slot : inputSlots(card)) {
            ItemStack selected = preferredAlternative(slot, preferredInputs);
            if (!selected.isEmpty()) {
                names.add(selected.getHoverName().getString());
            }
        }
        return List.copyOf(names);
    }

    private static ItemStack preferredAlternative(
            ProductionCardData.Slot slot,
            List<ItemStack> preferredInputs
    ) {
        for (ItemStack preferred : preferredInputs) {
            for (ItemStack alternative : slot.alternatives()) {
                if (alternative.getItem() == preferred.getItem()) {
                    return alternative;
                }
            }
        }
        return slot.primary();
    }

    private static List<ProductionCardData.Slot> inputSlots(ProductionCardData card) {
        return switch (card) {
            case ProductionCardData.Crafting crafting -> crafting.ingredients();
            case ProductionCardData.Cooking cooking -> List.of(cooking.input());
            case ProductionCardData.Stonecutting stonecutting -> List.of(stonecutting.input());
            case ProductionCardData.Smithing smithing -> List.of(
                    smithing.template(), smithing.base(), smithing.addition()
            );
            case ProductionCardData.Brewing brewing -> List.of(brewing.input(), brewing.ingredient());
            case ProductionCardData.Loom loom -> List.of(loom.banner(), loom.dye(), loom.patternItem());
            case ProductionCardData.Cartography cartography -> List.of(
                    cartography.input(), cartography.addition()
            );
            case ProductionCardData.Enchanting enchanting -> List.of(enchanting.item(), enchanting.lapis());
            case ProductionCardData.Anvil anvil -> List.of(anvil.base(), anvil.addition());
            case ProductionCardData.Grindstone grindstone -> List.of(
                    grindstone.input(), grindstone.addition()
            );
        };
    }
}
