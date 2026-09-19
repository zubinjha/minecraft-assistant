package dev.zubinjha.minecraftassistant.fabric;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;

@SuppressWarnings("UnstableApiUsage")
public final class MinecraftAssistantClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder()
                .adjustSettings(settings -> settings.setGameMode(
                        WorldCreationUiState.SelectedGameMode.CREATIVE
                ))
                .create()) {
            singleplayer.getConnection().waitForChunksRender();

            AtomicReference<List<ProductionCardData>> nativeCards = new AtomicReference<>();
            context.runOnClient(client -> nativeCards.set(verifyNativeProduction(client)));
            for (ProductionCardData card : nativeCards.get()) {
                context.runOnClient(client -> client.gui.setScreen(new ProductionCardScreen(card)));
                context.waitForScreen(ProductionCardScreen.class);
                context.takeScreenshot("minecraft-assistant-" + card.method().toolValue() + "-guide");
                context.runOnClient(client -> client.gui.setScreen(null));
            }

            context.runOnClient(client -> client.getConnection().sendCommand(
                    "mcai recipe \"minecraft:wooden_pickaxe\" crafting"
            ));
            context.waitForScreen(ProductionCardScreen.class);
            context.takeScreenshot("minecraft-assistant-recipe-card");
            context.runOnClient(client -> client.gui.setScreen(null));

            context.runOnClient(client -> client.getConnection().sendCommand("ask config"));
            context.waitForScreen(AssistantConfigScreen.class);
            context.takeScreenshot("minecraft-assistant-config");

            String apiKey = System.getenv("MINECRAFT_ASSISTANT_OPENROUTER_API_KEY");
            if (apiKey != null && !apiKey.isBlank()) {
                AssistantConfig config = new AssistantConfig(
                        apiKey,
                        AssistantConfig.DEFAULT_MODEL,
                        "low",
                        dev.zubinjha.minecraftassistant.mediawiki.MinecraftWikiToolSource.DEFAULT_API_URL
                );
                context.runOnClient(client -> {
                    try {
                        new ConfigStore().save(config);
                    } catch (java.io.IOException failure) {
                        throw new CompletionException(failure);
                    }
                    MinecraftAssistantClient.runtimeForTest().updateConfig(config);
                    client.gui.setScreen(null);
                    client.getConnection().sendCommand(
                            "ask how do I craft a recovery compass?"
                    );
                });
                context.waitFor(client -> MinecraftAssistantClient.runtimeForTest().requestFinishedForTest(), 2400);
                String failure = MinecraftAssistantClient.runtimeForTest().lastFailureForTest();
                if (!failure.isBlank()) {
                    throw new AssertionError("Live assistant request failed: " + failure);
                }
                String answer = MinecraftAssistantClient.runtimeForTest().lastAnswerForTest();
                if (!answer.toLowerCase(java.util.Locale.ROOT).contains("show recipe")) {
                    throw new AssertionError("Live assistant answer did not mention the recipe card");
                }
                String recipeId = MinecraftAssistantClient.runtimeForTest().lastRecipeIdForTest();
                if (recipeId.isBlank()) {
                    throw new AssertionError("Live assistant answer did not prepare a recipe card");
                }
                context.takeScreenshot("minecraft-assistant-live-answer");
                context.runOnClient(client -> MinecraftAssistantClient.runtimeForTest()
                        .openRecipe(recipeId, ProductionMethod.CRAFTING.toolValue()));
                context.waitForScreen(ProductionCardScreen.class);
                context.takeScreenshot("minecraft-assistant-live-recipe-card");
            }
        }
    }

    private static List<ProductionCardData> verifyNativeProduction(net.minecraft.client.Minecraft client) {
        NativeProductionResolver resolver = new NativeProductionResolver(client);

        NativeProductionResult.Found brewing = found(resolver.brewing(
                "minecraft:swiftness", "splash", ""
        ), "splash swiftness brewing");
        if (brewing.cards().size() < 2) {
            throw new AssertionError("Expected a multi-step splash potion chain");
        }
        if (!(resolver.brewing("minecraft:harming", "potion", "")
                instanceof NativeProductionResult.Ambiguous)) {
            throw new AssertionError("Alternate potion bases should produce an ambiguous harming path");
        }
        found(resolver.brewing(
                "minecraft:harming", "potion", "minecraft:poison"
        ), "disambiguated harming potion");

        ArrayNode layers = JsonNodeFactory.instance.arrayNode();
        layers.addObject().put("pattern_id", "minecraft:stripe_bottom").put("dye_color", "red");
        layers.addObject().put("pattern_id", "minecraft:stripe_top").put("dye_color", "blue");
        layers.addObject().put("pattern_id", "minecraft:creeper").put("dye_color", "green");
        NativeProductionResult.Found loom = found(resolver.loom("white", layers), "three-layer banner");
        if (loom.cards().size() != 3
                || ((ProductionCardData.Loom) loom.cards().getLast()).patternItem().primary().isEmpty()) {
            throw new AssertionError("Expected one loom guide per banner layer");
        }

        found(resolver.cartography("scale"), "map scaling");
        found(resolver.cartography("clone"), "map cloning");
        NativeProductionResult.Found cartography = found(resolver.cartography("lock"), "map locking");
        NativeProductionResult.Found enchanting = found(resolver.enchanting(
                "minecraft:diamond_sword", "minecraft:sharpness"
        ), "enchanting eligibility");
        if (!((ProductionCardData.Enchanting) enchanting.cards().getFirst()).details().contains("not guaranteed")) {
            throw new AssertionError("Enchanting guides must not guarantee a requested offer");
        }
        if (!(resolver.enchanting("minecraft:diamond_sword", "minecraft:mending")
                instanceof NativeProductionResult.Missing)) {
            throw new AssertionError("Mending must not be presented as an enchanting-table offer");
        }
        NativeProductionResult.Found anvil = found(resolver.anvil(
                "apply_book",
                "minecraft:diamond_sword",
                "",
                "minecraft:sharpness",
                3,
                ""
        ), "enchanted-book anvil");
        if (!((ProductionCardData.Anvil) anvil.cards().getFirst()).details().contains("exact XP cost")) {
            throw new AssertionError("Anvil guides must keep unknown costs variable");
        }
        NativeProductionResult.Found grindstone = found(resolver.grindstone(
                "disenchant",
                "minecraft:diamond_sword",
                "",
                JsonNodeFactory.instance.arrayNode().add("minecraft:sharpness")
        ), "grindstone disenchanting");
        NativeProductionResult curseOnly = resolver.grindstone(
                "disenchant",
                "minecraft:diamond_boots",
                "",
                JsonNodeFactory.instance.arrayNode().add("minecraft:binding_curse")
        );
        if (!(curseOnly instanceof NativeProductionResult.Missing)) {
            throw new AssertionError("A grindstone guide must never present curses as removable");
        }
        return List.of(
                brewing.cards().getFirst(),
                loom.cards().getLast(),
                cartography.cards().getFirst(),
                enchanting.cards().getFirst(),
                anvil.cards().getFirst(),
                grindstone.cards().getFirst()
        );
    }

    private static NativeProductionResult.Found found(NativeProductionResult result, String label) {
        if (result instanceof NativeProductionResult.Found found) {
            return found;
        }
        throw new AssertionError("Native " + label + " guide failed: " + result);
    }
}
