package dev.zubinjha.minecraftassistant.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class ProductionStepLabelTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        BuiltInRegistries.ITEM.listElements()
                .forEach(holder -> holder.bindComponents(DataComponents.COMMON_ITEM_COMPONENTS));
    }

    @Test
    void cookingOmitsGenericFuel() {
        ProductionCardData.Cooking card = new ProductionCardData.Cooking(
                "minecraft:charcoal",
                ProductionMethod.SMELTING,
                slot(Items.OAK_LOG),
                new ProductionCardData.Slot(List.of(), true),
                slot(Items.CHARCOAL),
                slot(Items.FURNACE),
                200,
                0.15F
        );

        assertEquals("Step 1: Oak Log → Charcoal", ProductionStepLabel.describe(0, card));
    }

    @Test
    void repeatedCraftingSlotsAndAlternativesUseOneStablePrimaryName() {
        ProductionCardData.Slot planks = new ProductionCardData.Slot(
                List.of(stack(Items.OAK_PLANKS, 1), stack(Items.BIRCH_PLANKS, 1)),
                false
        );
        ProductionCardData.Crafting card = new ProductionCardData.Crafting(
                "minecraft:stick",
                1,
                2,
                List.of(planks, planks),
                slot(Items.STICK, 4),
                false
        );

        assertEquals(List.of("Oak Planks"), ProductionStepLabel.stableInputNames(card));
        assertEquals("Step 3: Oak Planks → Stick", ProductionStepLabel.describe(2, card));
    }

    @Test
    void multipleInputsRemainSemanticallySeparate() {
        ProductionCardData.Crafting card = new ProductionCardData.Crafting(
                "minecraft:torch",
                1,
                2,
                List.of(slot(Items.CHARCOAL), slot(Items.STICK)),
                slot(Items.TORCH, 4),
                false
        );

        assertEquals("Step 4: Charcoal + Stick → Torch", ProductionStepLabel.describe(3, card));
    }

    @Test
    void sequenceLabelsPreferAnAlternativeProducedByAnEarlierStep() {
        ProductionCardData.Cooking charcoal = new ProductionCardData.Cooking(
                "minecraft:charcoal",
                ProductionMethod.SMELTING,
                slot(Items.OAK_LOG),
                new ProductionCardData.Slot(List.of(), true),
                slot(Items.CHARCOAL),
                slot(Items.FURNACE),
                200,
                0.15F
        );
        ProductionCardData.Crafting torches = new ProductionCardData.Crafting(
                "minecraft:torch",
                1,
                2,
                List.of(
                        new ProductionCardData.Slot(
                                List.of(stack(Items.COAL, 1), stack(Items.CHARCOAL, 1)),
                                false
                        ),
                        slot(Items.STICK)
                ),
                slot(Items.TORCH, 4),
                false
        );

        assertEquals(
                "Step 2: Charcoal + Stick → Torch",
                ProductionStepLabel.describe(1, List.of(charcoal, torches))
        );
    }

    @Test
    void smithingAndSpecializedCardsIncludeTheirSemanticInputs() {
        ProductionCardData.Smithing smithing = new ProductionCardData.Smithing(
                "minecraft:netherite_sword_smithing",
                slot(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE),
                slot(Items.DIAMOND_SWORD),
                slot(Items.NETHERITE_INGOT),
                slot(Items.NETHERITE_SWORD),
                slot(Items.SMITHING_TABLE)
        );
        ProductionCardData.Brewing brewing = new ProductionCardData.Brewing(
                "minecraft_assistant:brewing/awkward",
                slot(Items.POTION),
                slot(Items.NETHER_WART),
                slot(Items.BLAZE_POWDER),
                slot(Items.POTION),
                slot(Items.BREWING_STAND)
        );

        assertEquals(
                "Step 1: Netherite Upgrade Smithing Template + Diamond Sword + Netherite Ingot → Netherite Sword",
                ProductionStepLabel.describe(0, smithing)
        );
        assertEquals("Step 2: Potion + Nether Wart → Potion",
                ProductionStepLabel.describe(1, brewing));
    }

    @Test
    void fullLabelRemainsAvailableForLongOperations() {
        ProductionCardData.Loom loom = new ProductionCardData.Loom(
                "minecraft_assistant:loom/long",
                slot(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE),
                slot(Items.DIAMOND_SWORD),
                slot(Items.FLOWER_BANNER_PATTERN),
                slot(Items.NETHERITE_SWORD),
                slot(Items.LOOM),
                Component.literal("Flower Charge")
        );

        assertEquals(
                "Step 6: Netherite Upgrade Smithing Template + Diamond Sword + Flower Banner Pattern → Netherite Sword",
                ProductionStepLabel.describe(5, loom)
        );
    }

    private static ProductionCardData.Slot slot(net.minecraft.world.item.Item item) {
        return slot(item, 1);
    }

    private static ProductionCardData.Slot slot(net.minecraft.world.item.Item item, int count) {
        return ProductionCardData.Slot.of(stack(item, count));
    }

    private static ItemStack stack(net.minecraft.world.item.Item item, int count) {
        ItemStack stack = new ItemStack(item, count);
        String path = BuiltInRegistries.ITEM.getKey(item).getPath();
        String displayName = java.util.Arrays.stream(path.split("_"))
                .map(part -> Character.toUpperCase(part.charAt(0)) + part.substring(1))
                .collect(java.util.stream.Collectors.joining(" "));
        stack.set(DataComponents.ITEM_NAME, Component.literal(displayName));
        return stack;
    }
}
