package dev.zubinjha.minecraftassistant.fabric;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.MapExtendingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.display.FurnaceRecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.item.crafting.display.SmithingRecipeDisplay;
import net.minecraft.world.item.crafting.display.StonecutterRecipeDisplay;
import net.minecraft.client.Minecraft;

final class RecipeCardResolver {
    private final Minecraft minecraft;

    RecipeCardResolver(Minecraft minecraft) {
        this.minecraft = minecraft;
    }

    RecipeLookupResult resolve(String rawRecipeId, Optional<RecipeMethod> requestedMethod) {
        Identifier requested = Identifier.tryParse(rawRecipeId);
        if (requested == null) {
            return new RecipeLookupResult.Missing("recipe_id was not a valid namespaced identifier");
        }
        if (minecraft.level == null) {
            return new RecipeLookupResult.Missing("recipe data is unavailable outside a world");
        }

        ContextMap context = SlotDisplayContext.fromLevel(minecraft.level);
        OptionalInt displayIndex = clientDisplayIndex(requested);
        if (displayIndex.isPresent()) {
            return resolveClientDisplay(displayIndex.getAsInt(), context, requestedMethod);
        }
        if (minecraft.getSingleplayerServer() != null) {
            ResourceKey<Recipe<?>> key = ResourceKey.create(Registries.RECIPE, requested);
            Optional<RecipeHolder<?>> exact = minecraft.getSingleplayerServer()
                    .getRecipeManager()
                    .byKey(key);
            if (exact.isPresent()) {
                List<RecipeCardData> exactCards = fromRecipe(exact.get(), context);
                if (!exactCards.isEmpty()) {
                    RecipeLookupResult exactResult = select(exactCards, requestedMethod);
                    if (!(exactResult instanceof RecipeLookupResult.Missing)) {
                        return exactResult;
                    }
                }
            }

            List<RecipeCardData> matches = new ArrayList<>();
            for (RecipeHolder<?> holder : minecraft.getSingleplayerServer()
                    .getRecipeManager()
                    .getRecipes()) {
                matches.addAll(fromRecipeMatchingResult(holder, requested, context));
            }
            return select(matches, requestedMethod);
        }

        List<RecipeCardData> matches = new ArrayList<>();
        if (minecraft.player != null) {
            for (var collection : minecraft.player.getRecipeBook().getCollections()) {
                for (RecipeDisplayEntry entry : collection.getRecipes()) {
                    String selector = "minecraft_assistant:display/" + entry.id().index();
                    if (displayResultMatches(entry.display(), requested, context)) {
                        fromDisplay(selector, entry.display(), context, Optional.empty())
                                .ifPresent(matches::add);
                    }
                }
            }
        }
        return select(matches, requestedMethod);
    }

    private RecipeLookupResult resolveClientDisplay(
            int requestedIndex,
            ContextMap context,
            Optional<RecipeMethod> requestedMethod
    ) {
        if (minecraft.player == null) {
            return new RecipeLookupResult.Missing("client recipe displays are unavailable");
        }
        List<RecipeCardData> matches = new ArrayList<>();
        for (var collection : minecraft.player.getRecipeBook().getCollections()) {
            for (RecipeDisplayEntry entry : collection.getRecipes()) {
                if (entry.id().index() == requestedIndex) {
                    String selector = "minecraft_assistant:display/" + entry.id().index();
                    fromDisplay(selector, entry.display(), context, Optional.empty()).ifPresent(matches::add);
                }
            }
        }
        return select(matches, requestedMethod);
    }

    private List<RecipeCardData> fromRecipe(RecipeHolder<?> holder, ContextMap context) {
        String recipeId = holder.id().identifier().toString();
        if (holder.value() instanceof MapExtendingRecipe) {
            return List.of(mapExtendingCard(recipeId));
        }

        Optional<RecipeMethod> methodHint = methodFromType(holder.value().getType());
        List<RecipeCardData> cards = new ArrayList<>();
        for (RecipeDisplay display : holder.value().display()) {
            fromDisplay(recipeId, display, context, methodHint).ifPresent(cards::add);
        }
        return cards;
    }

    private List<RecipeCardData> fromRecipeMatchingResult(
            RecipeHolder<?> holder,
            Identifier requested,
            ContextMap context
    ) {
        String recipeId = holder.id().identifier().toString();
        if (holder.value() instanceof MapExtendingRecipe) {
            return BuiltInRegistries.ITEM.getKey(Items.FILLED_MAP).equals(requested)
                    ? List.of(mapExtendingCard(recipeId))
                    : List.of();
        }
        Optional<RecipeMethod> methodHint = methodFromType(holder.value().getType());
        List<RecipeCardData> cards = new ArrayList<>();
        for (RecipeDisplay display : holder.value().display()) {
            if (displayResultMatches(display, requested, context)) {
                fromDisplay(recipeId, display, context, methodHint).ifPresent(cards::add);
            }
        }
        return cards;
    }

    private RecipeCardData mapExtendingCard(String recipeId) {
        List<RecipeCardData.Slot> ingredients = new ArrayList<>(9);
        for (int slot = 0; slot < 9; slot++) {
            ingredients.add(RecipeCardData.Slot.of(
                    new ItemStack(slot == 4 ? Items.FILLED_MAP : Items.PAPER)
            ));
        }
        return new RecipeCardData.Crafting(
                recipeId,
                3,
                3,
                ingredients,
                RecipeCardData.Slot.of(new ItemStack(Items.FILLED_MAP)),
                false
        );
    }

    private Optional<RecipeCardData> fromDisplay(
            String recipeId,
            RecipeDisplay display,
            ContextMap context,
            Optional<RecipeMethod> methodHint
    ) {
        if (display instanceof ShapedCraftingRecipeDisplay shaped) {
            return crafting(
                    recipeId,
                    shaped.width(),
                    shaped.height(),
                    shaped.ingredients(),
                    shaped.result(),
                    false,
                    context
            );
        }
        if (display instanceof ShapelessCraftingRecipeDisplay shapeless) {
            int count = shapeless.ingredients().size();
            return crafting(
                    recipeId,
                    Math.min(3, Math.max(1, count)),
                    Math.max(1, (count + 2) / 3),
                    shapeless.ingredients(),
                    shapeless.result(),
                    true,
                    context
            );
        }
        if (display instanceof FurnaceRecipeDisplay furnace) {
            RecipeCardData.Slot station = slot(furnace.craftingStation(), context);
            RecipeMethod method = methodHint
                    .filter(RecipeCardResolver::isCookingMethod)
                    .orElseGet(() -> methodFromStation(station));
            return cardWithResult(new RecipeCardData.Cooking(
                    recipeId,
                    method,
                    slot(furnace.ingredient(), context),
                    slot(furnace.fuel(), context),
                    slot(furnace.result(), context),
                    station,
                    furnace.duration(),
                    furnace.experience()
            ));
        }
        if (display instanceof StonecutterRecipeDisplay stonecutter) {
            return cardWithResult(new RecipeCardData.Stonecutting(
                    recipeId,
                    slot(stonecutter.input(), context),
                    slot(stonecutter.result(), context),
                    slot(stonecutter.craftingStation(), context)
            ));
        }
        if (display instanceof SmithingRecipeDisplay smithing) {
            return cardWithResult(new RecipeCardData.Smithing(
                    recipeId,
                    slot(smithing.template(), context),
                    slot(smithing.base(), context),
                    slot(smithing.addition(), context),
                    slot(smithing.result(), context),
                    slot(smithing.craftingStation(), context)
            ));
        }
        return Optional.empty();
    }

    private Optional<RecipeCardData> crafting(
            String recipeId,
            int width,
            int height,
            List<SlotDisplay> ingredientDisplays,
            SlotDisplay resultDisplay,
            boolean shapeless,
            ContextMap context
    ) {
        if (width > 3 || height > 3) {
            return Optional.empty();
        }
        List<RecipeCardData.Slot> ingredients = ingredientDisplays.stream()
                .map(display -> slot(display, context))
                .toList();
        return cardWithResult(new RecipeCardData.Crafting(
                recipeId,
                width,
                height,
                ingredients,
                slot(resultDisplay, context),
                shapeless
        ));
    }

    private RecipeCardData.Slot slot(SlotDisplay display, ContextMap context) {
        List<ItemStack> distinct = new ArrayList<>();
        for (ItemStack resolved : display.resolveForStacks(context)) {
            if (resolved.isEmpty()) {
                continue;
            }
            boolean duplicate = distinct.stream()
                    .anyMatch(existing -> ItemStack.isSameItemSameComponents(existing, resolved));
            if (!duplicate) {
                distinct.add(resolved.copy());
            }
        }
        return new RecipeCardData.Slot(distinct, display instanceof SlotDisplay.AnyFuel);
    }

    private static Optional<RecipeCardData> cardWithResult(RecipeCardData card) {
        return card.result().primary().isEmpty() ? Optional.empty() : Optional.of(card);
    }

    static RecipeLookupResult select(
            List<RecipeCardData> cards,
            Optional<RecipeMethod> requestedMethod
    ) {
        Map<String, RecipeCardData> unique = new LinkedHashMap<>();
        cards.stream()
                .filter(card -> requestedMethod.isEmpty() || card.method() == requestedMethod.get())
                .sorted(Comparator.comparing(RecipeCardData::recipeId)
                        .thenComparing(card -> card.method().toolValue()))
                .forEach(card -> unique.putIfAbsent(card.recipeId() + "|" + card.method(), card));

        if (unique.isEmpty()) {
            String suffix = requestedMethod
                    .map(method -> " for method " + method.toolValue())
                    .orElse("");
            return new RecipeLookupResult.Missing("no supported native recipe display was found" + suffix);
        }
        if (unique.size() == 1) {
            return new RecipeLookupResult.Found(unique.values().iterator().next());
        }
        List<RecipeLookupResult.Candidate> candidates = unique.values().stream()
                .map(card -> new RecipeLookupResult.Candidate(card.recipeId(), card.method()))
                .toList();
        return new RecipeLookupResult.Ambiguous(candidates);
    }

    private static Optional<RecipeMethod> methodFromType(RecipeType<?> type) {
        if (type == RecipeType.CRAFTING) {
            return Optional.of(RecipeMethod.CRAFTING);
        }
        if (type == RecipeType.SMELTING) {
            return Optional.of(RecipeMethod.SMELTING);
        }
        if (type == RecipeType.BLASTING) {
            return Optional.of(RecipeMethod.BLASTING);
        }
        if (type == RecipeType.SMOKING) {
            return Optional.of(RecipeMethod.SMOKING);
        }
        if (type == RecipeType.CAMPFIRE_COOKING) {
            return Optional.of(RecipeMethod.CAMPFIRE_COOKING);
        }
        if (type == RecipeType.STONECUTTING) {
            return Optional.of(RecipeMethod.STONECUTTING);
        }
        if (type == RecipeType.SMITHING) {
            return Optional.of(RecipeMethod.SMITHING);
        }
        return Optional.empty();
    }

    private static RecipeMethod methodFromStation(RecipeCardData.Slot station) {
        Identifier stationId = BuiltInRegistries.ITEM.getKey(station.primary().getItem());
        return switch (stationId.getPath()) {
            case "blast_furnace" -> RecipeMethod.BLASTING;
            case "smoker" -> RecipeMethod.SMOKING;
            case "campfire", "soul_campfire" -> RecipeMethod.CAMPFIRE_COOKING;
            default -> RecipeMethod.SMELTING;
        };
    }

    private static boolean isCookingMethod(RecipeMethod method) {
        return method == RecipeMethod.SMELTING
                || method == RecipeMethod.BLASTING
                || method == RecipeMethod.SMOKING
                || method == RecipeMethod.CAMPFIRE_COOKING;
    }

    private static boolean displayResultMatches(
            RecipeDisplay display,
            Identifier requested,
            ContextMap context
    ) {
        SlotDisplay result = switch (display) {
            case ShapedCraftingRecipeDisplay shaped -> shaped.result();
            case ShapelessCraftingRecipeDisplay shapeless -> shapeless.result();
            case FurnaceRecipeDisplay furnace -> furnace.result();
            case StonecutterRecipeDisplay stonecutter -> stonecutter.result();
            case SmithingRecipeDisplay smithing -> smithing.result();
            default -> null;
        };
        if (result == null) {
            return false;
        }
        ItemStack first = result.resolveForFirstStack(context);
        return !first.isEmpty() && BuiltInRegistries.ITEM.getKey(first.getItem()).equals(requested);
    }

    private static OptionalInt clientDisplayIndex(Identifier requested) {
        if (!requested.getNamespace().equals("minecraft_assistant")
                || !requested.getPath().startsWith("display/")) {
            return OptionalInt.empty();
        }
        try {
            return OptionalInt.of(Integer.parseInt(requested.getPath().substring("display/".length())));
        } catch (NumberFormatException ignored) {
            return OptionalInt.empty();
        }
    }
}
