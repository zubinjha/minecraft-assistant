package dev.zubinjha.minecraftassistant.fabric;

import java.util.concurrent.CompletionException;
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

            context.runOnClient(client -> client.getConnection().sendCommand(
                    "mcai recipe \"minecraft:wooden_pickaxe\" crafting"
            ));
            context.waitForScreen(RecipeCardScreen.class);
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
                        dev.zubinjha.minecraftassistant.mcp.McpToolSource.DEFAULT_MINECRAFT_WIKI_ENDPOINT
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
                        .openRecipe(recipeId, RecipeMethod.CRAFTING.toolValue()));
                context.waitForScreen(RecipeCardScreen.class);
                context.takeScreenshot("minecraft-assistant-live-recipe-card");
            }
        }
    }
}
