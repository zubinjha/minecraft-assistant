package dev.zubinjha.minecraftassistant.fabric;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

public sealed interface RecipeCardData permits RecipeCardData.Crafting,
        RecipeCardData.Cooking, RecipeCardData.Stonecutting, RecipeCardData.Smithing {

    String recipeId();

    RecipeMethod method();

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
    ) implements RecipeCardData {
        public Crafting {
            ingredients = List.copyOf(ingredients);
        }

        @Override
        public RecipeMethod method() {
            return RecipeMethod.CRAFTING;
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
            RecipeMethod method,
            Slot input,
            Slot fuel,
            Slot result,
            Slot station,
            int durationTicks,
            float experience
    ) implements RecipeCardData {
        public Cooking {
            if (method == RecipeMethod.CRAFTING
                    || method == RecipeMethod.STONECUTTING
                    || method == RecipeMethod.SMITHING) {
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
    ) implements RecipeCardData {
        @Override
        public RecipeMethod method() {
            return RecipeMethod.STONECUTTING;
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
    ) implements RecipeCardData {
        @Override
        public RecipeMethod method() {
            return RecipeMethod.SMITHING;
        }

        @Override
        public String ingredientSummary() {
            return template.description() + ", " + base.description() + ", " + addition.description();
        }
    }
}
