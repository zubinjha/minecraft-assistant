package dev.zubinjha.minecraftassistant.fabric;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BannerPatternTags;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.block.entity.BannerPattern;
import net.minecraft.world.level.block.entity.BannerPatternLayers;

final class NativeProductionResolver {
    private static final ProductionCardData.Slot EMPTY = new ProductionCardData.Slot(List.of(), false);
    private final Minecraft minecraft;

    NativeProductionResolver(Minecraft minecraft) {
        this.minecraft = minecraft;
    }

    NativeProductionResult brewing(String targetValue, String containerValue, String viaValue) {
        if (minecraft.level == null) {
            return missingWorld();
        }
        Identifier targetId = Identifier.tryParse(targetValue);
        if (targetId == null) {
            return new NativeProductionResult.Missing("target_potion_id must be a namespaced potion ID");
        }
        Registry<Potion> potions = minecraft.level.registryAccess().lookupOrThrow(Registries.POTION);
        Optional<Holder.Reference<Potion>> target = potions.get(targetId);
        if (target.isEmpty()) {
            return new NativeProductionResult.Missing("unknown potion: " + targetId);
        }
        Item targetContainer = switch (containerValue.toLowerCase(Locale.ROOT)) {
            case "", "potion", "regular" -> Items.POTION;
            case "splash", "splash_potion" -> Items.SPLASH_POTION;
            case "lingering", "lingering_potion" -> Items.LINGERING_POTION;
            default -> null;
        };
        if (targetContainer == null) {
            return new NativeProductionResult.Missing("container must be potion, splash, or lingering");
        }
        Identifier via = viaValue.isBlank() ? null : Identifier.tryParse(viaValue);
        if (!viaValue.isBlank() && via == null) {
            return new NativeProductionResult.Missing("via_potion_id must be a namespaced potion ID");
        }

        PotionBrewing rules = minecraft.level.potionBrewing();
        List<ItemStack> ingredients = BuiltInRegistries.ITEM.stream()
                .map(Item::getDefaultInstance)
                .filter(rules::isIngredient)
                .sorted(Comparator.comparing(stack -> BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()))
                .toList();
        ItemStack start = PotionContents.createItemStack(Items.POTION, Potions.WATER);
        ArrayDeque<BrewPath> queue = new ArrayDeque<>();
        queue.add(new BrewPath(start, List.of()));
        Map<String, Integer> depths = new HashMap<>();
        depths.put(brewKey(start), 0);
        List<BrewPath> matches = new ArrayList<>();
        int shortest = Integer.MAX_VALUE;
        int examined = 0;
        while (!queue.isEmpty() && examined++ < 1_000) {
            BrewPath path = queue.removeFirst();
            int depth = path.steps().size();
            if (depth > shortest || depth >= ShowProcessTool.MAX_STEPS) {
                continue;
            }
            if (matches(path.stack(), targetContainer, target.get()) && depth > 0) {
                if (via == null || pathIncludes(path, via)) {
                    shortest = depth;
                    matches.add(path);
                    if (matches.size() >= 8) {
                        break;
                    }
                }
                continue;
            }
            for (ItemStack ingredient : ingredients) {
                if (!rules.hasMix(path.stack(), ingredient)) {
                    continue;
                }
                ItemStack mixed = rules.mix(ingredient, path.stack());
                String key = brewKey(mixed);
                int nextDepth = depth + 1;
                Integer known = depths.get(key);
                if (known != null && known < nextDepth) {
                    continue;
                }
                depths.put(key, nextDepth);
                List<BrewStep> steps = new ArrayList<>(path.steps());
                steps.add(new BrewStep(path.stack().copy(), ingredient.copy(), mixed.copy()));
                queue.addLast(new BrewPath(mixed, List.copyOf(steps)));
            }
        }
        List<BrewPath> unique = deduplicatePaths(matches);
        if (unique.isEmpty()) {
            return new NativeProductionResult.Missing("no native brewing path was found");
        }
        if (unique.size() > 1) {
            return new NativeProductionResult.Ambiguous(unique.stream()
                    .map(NativeProductionResolver::describePath)
                    .toList());
        }
        List<ProductionCardData> cards = new ArrayList<>();
        int index = 0;
        for (BrewStep step : unique.getFirst().steps()) {
            cards.add(new ProductionCardData.Brewing(
                    "minecraft_assistant:brewing/" + (++index) + "/" + targetId.getPath(),
                    slot(step.input()),
                    slot(step.ingredient()),
                    slot(new ItemStack(Items.BLAZE_POWDER)),
                    slot(step.output()),
                    slot(new ItemStack(Items.BREWING_STAND))
            ));
        }
        return new NativeProductionResult.Found(cards);
    }

    NativeProductionResult loom(String baseColorValue, JsonNode rawLayers) {
        if (minecraft.level == null) {
            return missingWorld();
        }
        DyeColor baseColor = dyeColor(baseColorValue);
        if (baseColor == null) {
            return new NativeProductionResult.Missing("unknown base banner color: " + baseColorValue);
        }
        if (!rawLayers.isArray() || rawLayers.isEmpty() || rawLayers.size() > 6) {
            return new NativeProductionResult.Missing("layers must contain between one and six banner layers");
        }
        Registry<BannerPattern> patterns = minecraft.level.registryAccess().lookupOrThrow(Registries.BANNER_PATTERN);
        ItemStack current = new ItemStack(Items.BANNER.pick(baseColor));
        BannerPatternLayers.Builder builder = new BannerPatternLayers.Builder();
        List<ProductionCardData> cards = new ArrayList<>();
        for (int index = 0; index < rawLayers.size(); index++) {
            JsonNode rawLayer = rawLayers.get(index);
            Identifier patternId = Identifier.tryParse(rawLayer.path("pattern_id").asText(""));
            DyeColor dye = dyeColor(rawLayer.path("dye_color").asText(""));
            if (patternId == null || dye == null) {
                return new NativeProductionResult.Missing("loom layer " + (index + 1)
                        + " has an invalid pattern_id or dye_color");
            }
            Optional<Holder.Reference<BannerPattern>> pattern = patterns.get(patternId);
            if (pattern.isEmpty()) {
                return new NativeProductionResult.Missing("unknown banner pattern at layer " + (index + 1)
                        + ": " + patternId);
            }
            ItemStack patternItem = pattern.get().is(BannerPatternTags.NO_ITEM_REQUIRED)
                    ? ItemStack.EMPTY
                    : patternItem(pattern.get());
            if (!pattern.get().is(BannerPatternTags.NO_ITEM_REQUIRED) && patternItem.isEmpty()) {
                return new NativeProductionResult.Missing("the required pattern item is unavailable for " + patternId);
            }
            ItemStack input = current.copy();
            builder.add(pattern.get(), dye);
            ItemStack output = current.copy();
            output.set(DataComponents.BANNER_PATTERNS, builder.build());
            BannerPatternLayers.Layer layer = new BannerPatternLayers.Layer(pattern.get(), dye);
            cards.add(new ProductionCardData.Loom(
                    "minecraft_assistant:loom/" + (index + 1) + "/" + patternId.getPath(),
                    slot(input),
                    slot(new ItemStack(Items.DYE.pick(dye))),
                    slot(patternItem),
                    slot(output),
                    slot(new ItemStack(Items.LOOM)),
                    layer.description()
            ));
            current = output;
        }
        return new NativeProductionResult.Found(cards);
    }

    NativeProductionResult cartography(String operationValue) {
        String operation = operationValue.toLowerCase(Locale.ROOT);
        ItemStack addition;
        ItemStack result = new ItemStack(Items.FILLED_MAP);
        switch (operation) {
            case "scale" -> {
                addition = new ItemStack(Items.PAPER);
                result.set(DataComponents.MAP_POST_PROCESSING,
                        net.minecraft.world.item.component.MapPostProcessing.SCALE);
            }
            case "clone" -> {
                addition = new ItemStack(Items.MAP);
                result.setCount(2);
            }
            case "lock" -> {
                addition = new ItemStack(Items.GLASS_PANE);
                result.set(DataComponents.MAP_POST_PROCESSING,
                        net.minecraft.world.item.component.MapPostProcessing.LOCK);
            }
            default -> {
                return new NativeProductionResult.Missing("operation must be scale, clone, or lock");
            }
        }
        return found(new ProductionCardData.Cartography(
                "minecraft_assistant:cartography/" + operation,
                operation,
                slot(new ItemStack(Items.FILLED_MAP)),
                slot(addition),
                slot(result),
                slot(new ItemStack(Items.CARTOGRAPHY_TABLE))
        ));
    }

    NativeProductionResult enchanting(String itemValue, String enchantmentValue) {
        if (minecraft.level == null) {
            return missingWorld();
        }
        ItemStack item = item(itemValue);
        if (item.isEmpty()) {
            return new NativeProductionResult.Missing("unknown item: " + itemValue);
        }
        if (item.get(DataComponents.ENCHANTABLE) == null) {
            return new NativeProductionResult.Missing(itemValue + " cannot use an enchanting table");
        }
        Component targetName = Component.literal("Random offers");
        if (!enchantmentValue.isBlank()) {
            Optional<Holder.Reference<Enchantment>> target = enchantment(enchantmentValue);
            if (target.isEmpty()) {
                return new NativeProductionResult.Missing("unknown enchantment: " + enchantmentValue);
            }
            if (!target.get().is(EnchantmentTags.IN_ENCHANTING_TABLE)) {
                return new NativeProductionResult.Missing(enchantmentValue
                        + " is not available from the enchanting table; explain its actual source instead");
            }
            if (!target.get().value().canEnchant(item)) {
                return new NativeProductionResult.Missing(enchantmentValue + " is not compatible with " + itemValue);
            }
            targetName = target.get().value().description();
        }
        String details = enchantmentValue.isBlank()
                ? "Offers are random and depend on the item, player seed, XP level, lapis, and up to 15 bookshelves."
                : targetName.getString() + " is eligible, but an enchanting-table offer is not guaranteed.";
        return found(new ProductionCardData.Enchanting(
                "minecraft_assistant:enchanting/" + idPath(itemValue),
                slot(item),
                slot(new ItemStack(Items.LAPIS_LAZULI)),
                slot(item.copy()),
                slot(new ItemStack(Items.ENCHANTING_TABLE)),
                targetName,
                details
        ));
    }

    NativeProductionResult anvil(
            String operationValue,
            String baseItemValue,
            String additionItemValue,
            String enchantmentValue,
            int enchantmentLevel,
            String newName
    ) {
        if (minecraft.level == null) {
            return missingWorld();
        }
        String operation = operationValue.toLowerCase(Locale.ROOT);
        ItemStack base = item(baseItemValue);
        if (base.isEmpty()) {
            return new NativeProductionResult.Missing("unknown base item: " + baseItemValue);
        }
        ItemStack addition = ItemStack.EMPTY;
        ItemStack result = base.copy();
        String details;
        switch (operation) {
            case "apply_book" -> {
                Optional<Holder.Reference<Enchantment>> enchantment = enchantment(enchantmentValue);
                if (enchantment.isEmpty()) {
                    return new NativeProductionResult.Missing("unknown enchantment: " + enchantmentValue);
                }
                int level = enchantmentLevel <= 0 ? 1 : enchantmentLevel;
                if (level > enchantment.get().value().getMaxLevel()
                        || !enchantment.get().value().canEnchant(base)) {
                    return new NativeProductionResult.Missing("that enchantment level is incompatible with the base item");
                }
                addition = EnchantmentHelper.createBook(new EnchantmentInstance(enchantment.get(), level));
                result.enchant(enchantment.get(), level);
                details = "The exact XP cost depends on prior anvil work and the base item's existing enchantments.";
            }
            case "rename" -> {
                if (newName.isBlank()) {
                    return new NativeProductionResult.Missing("new_name is required for rename");
                }
                result.set(DataComponents.CUSTOM_NAME, Component.literal(newName));
                details = "Renaming costs at least one level; prior-work state can change the exact cost.";
            }
            case "repair" -> {
                addition = item(additionItemValue);
                if (addition.isEmpty() || !base.isValidRepairItem(addition)) {
                    return new NativeProductionResult.Missing("the addition is not a valid repair material for the base item");
                }
                details = "Repair amount and XP cost depend on current damage, material count, and prior work.";
            }
            case "combine" -> {
                addition = item(additionItemValue);
                if (addition.isEmpty() || addition.getItem() != base.getItem()) {
                    return new NativeProductionResult.Missing("combine requires a second item of the same type");
                }
                details = "Durability, compatible enchantments, and prior-work penalties determine the exact result and cost.";
            }
            default -> {
                return new NativeProductionResult.Missing("operation must be repair, combine, apply_book, or rename");
            }
        }
        return found(new ProductionCardData.Anvil(
                "minecraft_assistant:anvil/" + operation + "/" + idPath(baseItemValue),
                operation,
                slot(base),
                slot(addition),
                slot(result),
                slot(new ItemStack(Items.ANVIL)),
                details
        ));
    }

    NativeProductionResult grindstone(
            String operationValue,
            String itemValue,
            String secondItemValue,
            JsonNode rawEnchantments
    ) {
        if (minecraft.level == null) {
            return missingWorld();
        }
        String operation = operationValue.toLowerCase(Locale.ROOT);
        ItemStack input = item(itemValue);
        if (input.isEmpty()) {
            return new NativeProductionResult.Missing("unknown input item: " + itemValue);
        }
        ItemStack addition = ItemStack.EMPTY;
        ItemStack result = input.copy();
        String details;
        if (operation.equals("disenchant")) {
            boolean supplied = rawEnchantments.isArray() && !rawEnchantments.isEmpty();
            boolean removable = false;
            if (supplied) {
                for (JsonNode raw : rawEnchantments) {
                    Optional<Holder.Reference<Enchantment>> enchantment = enchantment(raw.asText());
                    if (enchantment.isEmpty()) {
                        return new NativeProductionResult.Missing("unknown enchantment: " + raw.asText());
                    }
                    input.enchant(enchantment.get(), 1);
                    if (!enchantment.get().is(EnchantmentTags.CURSE)) {
                        removable = true;
                    }
                }
                if (!removable) {
                    return new NativeProductionResult.Missing(
                            "grindstones do not remove curses; no removable enchantment was supplied"
                    );
                }
                result = input.copy();
                ItemEnchantments.Mutable remaining = new ItemEnchantments.Mutable(result.getEnchantments());
                remaining.removeIf(holder -> !holder.is(EnchantmentTags.CURSE));
                result.set(DataComponents.ENCHANTMENTS, remaining.toImmutable());
            } else {
                input.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
                result.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, false);
            }
            details = "Removes non-curse enchantments. Curses remain, and returned XP varies.";
        } else if (operation.equals("repair")) {
            addition = secondItemValue.isBlank() ? input.copy() : item(secondItemValue);
            if (addition.isEmpty() || addition.getItem() != input.getItem()) {
                return new NativeProductionResult.Missing("repair requires two items of the same type");
            }
            details = "Combines durability without an XP cost; the exact durability depends on both input items.";
        } else {
            return new NativeProductionResult.Missing("operation must be disenchant or repair");
        }
        return found(new ProductionCardData.Grindstone(
                "minecraft_assistant:grindstone/" + operation + "/" + idPath(itemValue),
                operation,
                slot(input),
                slot(addition),
                slot(result),
                slot(new ItemStack(Items.GRINDSTONE)),
                details
        ));
    }

    private Optional<Holder.Reference<Enchantment>> enchantment(String value) {
        Identifier id = Identifier.tryParse(value);
        if (id == null || minecraft.level == null) {
            return Optional.empty();
        }
        return minecraft.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).get(id);
    }

    private ItemStack patternItem(Holder<BannerPattern> pattern) {
        for (Item item : BuiltInRegistries.ITEM) {
            HolderSet<BannerPattern> provided = item.components().get(DataComponents.PROVIDES_BANNER_PATTERNS);
            if (provided != null && provided.contains(pattern)) {
                return new ItemStack(item);
            }
        }
        return ItemStack.EMPTY;
    }

    private static List<BrewPath> deduplicatePaths(List<BrewPath> paths) {
        Map<String, BrewPath> unique = new LinkedHashMap<>();
        for (BrewPath path : paths) {
            unique.putIfAbsent(potionPath(path), path);
        }
        return List.copyOf(unique.values());
    }

    private static boolean pathIncludes(BrewPath path, Identifier via) {
        return path.steps().stream().anyMatch(step -> potionId(step.input()).equals(via)
                || potionId(step.output()).equals(via));
    }

    private static String describePath(BrewPath path) {
        return potionPath(path).replace(">", " -> ");
    }

    private static String potionPath(BrewPath path) {
        List<String> potionIds = new ArrayList<>();
        if (!path.steps().isEmpty()) {
            potionIds.add(potionId(path.steps().getFirst().input()).toString());
        }
        for (BrewStep step : path.steps()) {
            String output = potionId(step.output()).toString();
            if (potionIds.isEmpty() || !potionIds.getLast().equals(output)) {
                potionIds.add(output);
            }
        }
        return String.join(">", potionIds);
    }

    private static boolean matches(ItemStack stack, Item container, Holder<Potion> potion) {
        return stack.is(container) && potionId(stack).equals(potion.unwrapKey().orElseThrow().identifier());
    }

    private static String brewKey(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()) + "|" + potionId(stack);
    }

    private static Identifier potionId(ItemStack stack) {
        PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
        if (contents == null || contents.potion().isEmpty() || contents.potion().get().unwrapKey().isEmpty()) {
            return Identifier.withDefaultNamespace("empty");
        }
        return contents.potion().get().unwrapKey().get().identifier();
    }

    private static DyeColor dyeColor(String value) {
        String normalized = value.toLowerCase(Locale.ROOT).replace(' ', '_');
        for (DyeColor color : DyeColor.values()) {
            if (color.getName().equals(normalized)) {
                return color;
            }
        }
        return null;
    }

    private static ItemStack item(String value) {
        Identifier id = Identifier.tryParse(value);
        if (id == null) {
            return ItemStack.EMPTY;
        }
        return BuiltInRegistries.ITEM.getOptional(id)
                .map(ItemStack::new)
                .orElse(ItemStack.EMPTY);
    }

    private static ProductionCardData.Slot slot(ItemStack stack) {
        return stack.isEmpty() ? EMPTY : ProductionCardData.Slot.of(stack);
    }

    private static NativeProductionResult found(ProductionCardData card) {
        return new NativeProductionResult.Found(List.of(card));
    }

    private static NativeProductionResult.Missing missingWorld() {
        return new NativeProductionResult.Missing("native production data is unavailable outside a world");
    }

    private static String idPath(String value) {
        Identifier id = Identifier.tryParse(value);
        return id == null ? "unknown" : id.getPath();
    }

    private record BrewStep(ItemStack input, ItemStack ingredient, ItemStack output) {
    }

    private record BrewPath(ItemStack stack, List<BrewStep> steps) {
    }
}
