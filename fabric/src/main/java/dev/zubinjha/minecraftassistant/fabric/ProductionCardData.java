package dev.zubinjha.minecraftassistant.fabric;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

public sealed interface ProductionCardData permits ProductionCardData.Crafting,
        ProductionCardData.Cooking, ProductionCardData.Stonecutting, ProductionCardData.Smithing,
        ProductionCardData.Brewing, ProductionCardData.Loom, ProductionCardData.Cartography,
        ProductionCardData.Enchanting, ProductionCardData.Anvil, ProductionCardData.Grindstone {

    String recipeId();

    ProductionMethod method();

    Slot result();

    String ingredientSummary();

    default Component title() {
        ItemStack resultStack = result().primary();
        return resultStack.isEmpty() ? Component.literal("Recipe") : resultStack.getHoverName();
    }

    record Slot(List<ItemStack> alternatives, boolean anyFuel) {
        public Slot {
            alternatives = alternatives.stream().map(ItemStack::copy).toList();
        }

        static Slot of(ItemStack stack) {
            return new Slot(stack.isEmpty() ? List.of() : List.of(stack), false);
        }

        ItemStack primary() {
            return alternatives.isEmpty() ? ItemStack.EMPTY : alternatives.getFirst();
        }

        ItemStack displayed(long nowMillis) {
            if (alternatives.isEmpty()) {
                return ItemStack.EMPTY;
            }
            return alternatives.get(displayIndex(alternatives.size(), nowMillis));
        }

        static int displayIndex(int alternativeCount, long nowMillis) {
            if (alternativeCount <= 0) {
                return 0;
            }
            return (int) ((nowMillis / 1_000L) % alternativeCount);
        }

        String description() {
            if (anyFuel) {
                return "any valid fuel";
            }
            ItemStack first = primary();
            if (first.isEmpty()) {
                return "empty";
            }
            String name = first.getHoverName().getString();
            return alternatives.size() > 1 ? name + " (or alternatives)" : name;
        }
    }

    record Crafting(
            String recipeId,
            int gridWidth,
            int gridHeight,
            List<Slot> ingredients,
            Slot result,
            boolean shapeless
    ) implements ProductionCardData {
        public Crafting {
            ingredients = List.copyOf(ingredients);
        }

        @Override
        public ProductionMethod method() {
            return ProductionMethod.CRAFTING;
        }

        @Override
        public String ingredientSummary() {
            Map<String, Integer> counts = new LinkedHashMap<>();
            List<String> alternativeSlots = new ArrayList<>();
            for (Slot ingredient : ingredients) {
                ItemStack first = ingredient.primary();
                if (!first.isEmpty()) {
                    counts.merge(first.getHoverName().getString(), first.getCount(), Integer::sum);
                    if (ingredient.alternatives().size() > 1) {
                        alternativeSlots.add(first.getHoverName().getString());
                    }
                }
            }
            String summary = counts.entrySet().stream()
                    .map(entry -> entry.getValue() + "× " + entry.getKey())
                    .collect(Collectors.joining(", "));
            return alternativeSlots.isEmpty() ? summary : summary + "; compatible alternatives are shown in the card";
        }
    }

    record Cooking(
            String recipeId,
            ProductionMethod method,
            Slot input,
            Slot fuel,
            Slot result,
            Slot station,
            int durationTicks,
            float experience
    ) implements ProductionCardData {
        public Cooking {
            if (method != ProductionMethod.SMELTING
                    && method != ProductionMethod.BLASTING
                    && method != ProductionMethod.SMOKING
                    && method != ProductionMethod.CAMPFIRE_COOKING) {
                throw new IllegalArgumentException("Cooking card requires a cooking method");
            }
        }

        @Override
        public String ingredientSummary() {
            if (fuel.alternatives().isEmpty() && !fuel.anyFuel()) {
                return input.description();
            }
            return input.description() + " with " + fuel.description();
        }
    }

    record Stonecutting(
            String recipeId,
            Slot input,
            Slot result,
            Slot station
    ) implements ProductionCardData {
        @Override
        public ProductionMethod method() {
            return ProductionMethod.STONECUTTING;
        }

        @Override
        public String ingredientSummary() {
            return input.description();
        }
    }

    record Smithing(
            String recipeId,
            Slot template,
            Slot base,
            Slot addition,
            Slot result,
            Slot station
    ) implements ProductionCardData {
        @Override
        public ProductionMethod method() {
            return ProductionMethod.SMITHING;
        }

        @Override
        public String ingredientSummary() {
            return template.description() + ", " + base.description() + ", " + addition.description();
        }
    }

    record Brewing(
            String recipeId,
            Slot input,
            Slot ingredient,
            Slot fuel,
            Slot result,
            Slot station
    ) implements ProductionCardData {
        @Override
        public ProductionMethod method() {
            return ProductionMethod.BREWING;
        }

        @Override
        public String ingredientSummary() {
            return input.description() + ", " + ingredient.description() + ", and blaze powder fuel";
        }
    }

    record Loom(
            String recipeId,
            Slot banner,
            Slot dye,
            Slot patternItem,
            Slot result,
            Slot station,
            Component patternName
    ) implements ProductionCardData {
        @Override
        public ProductionMethod method() {
            return ProductionMethod.LOOM;
        }

        @Override
        public String ingredientSummary() {
            String pattern = patternItem.primary().isEmpty() ? "no pattern item" : patternItem.description();
            return banner.description() + ", " + dye.description() + ", " + pattern;
        }
    }

    record Cartography(
            String recipeId,
            String operation,
            Slot input,
            Slot addition,
            Slot result,
            Slot station
    ) implements ProductionCardData {
        @Override
        public ProductionMethod method() {
            return ProductionMethod.CARTOGRAPHY;
        }

        @Override
        public Component title() {
            return Component.literal(switch (operation) {
                case "scale" -> "Scale Map";
                case "clone" -> "Clone Map";
                case "lock" -> "Lock Map";
                default -> "Cartography";
            });
        }

        @Override
        public String ingredientSummary() {
            return input.description() + " and " + addition.description();
        }
    }

    record Enchanting(
            String recipeId,
            Slot item,
            Slot lapis,
            Slot result,
            Slot station,
            Component targetEnchantment,
            String details
    ) implements ProductionCardData {
        @Override
        public ProductionMethod method() {
            return ProductionMethod.ENCHANTING;
        }

        @Override
        public Component title() {
            return Component.literal("Enchant ").append(item.primary().getHoverName());
        }

        @Override
        public String ingredientSummary() {
            return item.description() + " and lapis lazuli";
        }
    }

    record Anvil(
            String recipeId,
            String operation,
            Slot base,
            Slot addition,
            Slot result,
            Slot station,
            String details
    ) implements ProductionCardData {
        @Override
        public ProductionMethod method() {
            return ProductionMethod.ANVIL;
        }

        @Override
        public Component title() {
            return Component.literal("Anvil: ").append(result.primary().getHoverName());
        }

        @Override
        public String ingredientSummary() {
            return addition.primary().isEmpty() ? base.description() : base.description() + " and " + addition.description();
        }
    }

    record Grindstone(
            String recipeId,
            String operation,
            Slot input,
            Slot addition,
            Slot result,
            Slot station,
            String details
    ) implements ProductionCardData {
        @Override
        public ProductionMethod method() {
            return ProductionMethod.GRINDSTONE;
        }

        @Override
        public Component title() {
            return Component.literal(operation.equals("repair") ? "Repair " : "Disenchant ")
                    .append(input.primary().getHoverName());
        }

        @Override
        public String ingredientSummary() {
            return addition.primary().isEmpty() ? input.description() : input.description() + " and " + addition.description();
        }
    }
}
