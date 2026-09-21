package dev.zubinjha.minecraftassistant.fabric;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

@SuppressWarnings("UnstableApiUsage")
public final class MinecraftAssistantClientGameTest implements FabricClientGameTest {
    private static final String PREVIEW_ENV = "MINECRAFT_ASSISTANT_UI_PREVIEW";
    private static final String PREVIEW_OUTPUT_ENV = "MINECRAFT_ASSISTANT_UI_PREVIEW_OUTPUT";

    @Override
    public void runTest(ClientGameTestContext context) {
        if (Boolean.parseBoolean(System.getenv(PREVIEW_ENV))) {
            runUiPreview(context);
            return;
        }
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

            AtomicReference<List<ProductionPresentation>> quantityPresentations = new AtomicReference<>();
            context.runOnClient(client -> quantityPresentations.set(verifyQuantityPlanning(client)));
            int quantityScreenshot = 1;
            for (ProductionPresentation presentation : quantityPresentations.get()) {
                int screenshotIndex = quantityScreenshot++;
                context.runOnClient(client -> client.gui.setScreen(new ProductionCardScreen(presentation)));
                context.waitForScreen(ProductionCardScreen.class);
                context.takeScreenshot("minecraft-assistant-quantity-plan-" + screenshotIndex);
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

    private static void runUiPreview(ClientGameTestContext context) {
        Path output = previewOutput();
        resetPreviewOutput(output);
        List<String> screenshots = new ArrayList<>();
        try (TestSingleplayerContext singleplayer = context.worldBuilder()
                .adjustSettings(settings -> {
                    settings.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
                    settings.getNormalPresetList().stream()
                            .filter(entry -> entry.preset().is(WorldPresets.FLAT))
                            .findFirst()
                            .ifPresent(settings::setWorldType);
                })
                .create()) {
            singleplayer.getConnection().waitForChunksRender();

            AtomicReference<List<ProductionCardData>> nativeCards = new AtomicReference<>();
            AtomicReference<List<ProductionPresentation>> quantityPresentations = new AtomicReference<>();
            AtomicReference<ProductionPresentation> oneStep = new AtomicReference<>();
            AtomicReference<ProductionPresentation> comparison = new AtomicReference<>();
            AtomicReference<ProductionPresentation> sixStep = new AtomicReference<>();
            AtomicReference<ProductionPresentation> torchProcess = new AtomicReference<>();
            context.runOnClient(client -> {
                nativeCards.set(verifyNativeProduction(client));
                List<ProductionPresentation> quantities = verifyQuantityPlanning(client);
                quantityPresentations.set(quantities);
                oneStep.set(oneStepPlan(client));
                comparison.set(routeComparison(quantities));
                sixStep.set(sixStepPreview(client));
                torchProcess.set(torchProcess(client));
            });

            captureChatPreview(context, output, screenshots);
            capture(context, output, screenshots, "single-recipe",
                    new ProductionPresentation.Single(quantityPresentations.get().get(2).cards().getLast()), 0);
            capture(context, output, screenshots, "one-step-plan", oneStep.get(), 0);
            capture(context, output, screenshots, "two-step-plan", quantityPresentations.get().get(2), 1);
            capture(context, output, screenshots, "branching-pickaxe-plan", quantityPresentations.get().get(3), 2);
            capture(context, output, screenshots, "rounded-leftovers-plan", quantityPresentations.get().getFirst(), 2);
            capture(context, output, screenshots, "route-comparison", comparison.get(), 0);
            capture(context, output, screenshots, "six-step-plan", sixStep.get(), 5);
            for (int step = 0; step < 4; step++) {
                capture(context, output, screenshots, "branching-logs-to-torches-step-" + (step + 1),
                        torchProcess.get(), step);
            }

            for (ProductionCardData card : nativeCards.get()) {
                capture(context, output, screenshots,
                        "workstation-" + card.method().toolValue(), new ProductionPresentation.Single(card), 0);
            }

            AtomicReference<ProductionCardData> cooking = new AtomicReference<>();
            context.runOnClient(client -> cooking.set(recipe(
                    new RecipeCardResolver(client).resolve("minecraft:glass", Optional.of(ProductionMethod.SMELTING)),
                    "glass smelting preview"
            )));
            capture(context, output, screenshots, "workstation-smelting",
                    new ProductionPresentation.Single(cooking.get()), 0);

            context.runOnClient(client -> {
                client.getWindow().setWindowed(640, 540);
                client.getWindow().setGuiScale(3);
            });
            capture(context, output, screenshots, "small-window-six-step", sixStep.get(), 5);
        }
        writePreviewIndex(output, screenshots);
    }

    private static void captureChatPreview(
            ClientGameTestContext context,
            Path output,
            List<String> screenshots
    ) {
        String name = "chat-answer";
        context.runOnClient(client -> {
            client.gui.setScreen(null);
            client.gui.chatListener().handleSystemMessage(
                    Component.literal("You: ").withStyle(ChatFormatting.AQUA)
                            .append(Component.literal("where do I find a heart of the sea?")
                                    .withStyle(ChatFormatting.WHITE)), false
            );
            client.gui.chatListener().handleSystemMessage(
                    Component.literal("Assistant: ").withStyle(ChatFormatting.GREEN)
                            .append(Component.literal(
                                    "Find one in buried treasure chests. Use a buried treasure map from a shipwreck "
                                            + "or ocean ruin to locate one."
                            ).withStyle(ChatFormatting.WHITE)), false
            );
            client.gui.chatListener().handleSystemMessage(
                    Component.literal("[Minecraft Wiki source]")
                            .withStyle(ChatFormatting.AQUA, ChatFormatting.UNDERLINE), false
            );
        });
        context.takeScreenshot(name);
        Path copied = copyLatestScreenshot(name, output);
        verifyScreenshot(copied);
        screenshots.add(copied.getFileName().toString());
    }

    private static void capture(
            ClientGameTestContext context,
            Path output,
            List<String> screenshots,
            String name,
            ProductionPresentation presentation,
            int selectedStep
    ) {
        context.runOnClient(client -> {
            ProductionCardScreen screen = new ProductionCardScreen(presentation);
            client.gui.setScreen(screen);
            if (selectedStep > 0) {
                screen.selectCard(selectedStep);
            }
        });
        context.waitForScreen(ProductionCardScreen.class);
        context.takeScreenshot(name);
        Path copied = copyLatestScreenshot(name, output);
        verifyScreenshot(copied);
        screenshots.add(copied.getFileName().toString());
        context.runOnClient(client -> client.gui.setScreen(null));
    }

    private static ProductionPresentation oneStepPlan(net.minecraft.client.Minecraft client) {
        RecipeCardResolver resolver = new RecipeCardResolver(client);
        ProductionCardData card = recipe(
                resolver.resolve("minecraft:redstone_block", Optional.of(ProductionMethod.CRAFTING)),
                "redstone block preview"
        );
        ProductionPlan plan = quantityPlan(new ProductionQuantityPlanner().plan(
                List.of(card), new ProductionQuantityRequest(0, 4), Optional.of("minecraft:redstone")
        ), "one-step preview");
        return new ProductionPresentation.Plan(plan);
    }

    private static ProductionPresentation torchProcess(net.minecraft.client.Minecraft client) {
        RecipeCardResolver resolver = new RecipeCardResolver(client);
        RecipeRouteFinder.Result result = new RecipeRouteFinder(new ProductionQuantityPlanner()).find(
                resolver.allRecipes(),
                Set.of("minecraft:oak_log"),
                Set.of("minecraft:coal"),
                Set.of(),
                Set.of(ProductionMethod.SMELTING),
                "minecraft:torch",
                Optional.empty(),
                new ProductionQuantityRequest(1, 0, 0),
                PrepareProductionTool.MAX_OPERATIONS,
                dev.zubinjha.minecraftassistant.core.CancellationToken.NONE
        );
        RecipeRouteFinder.Result.Candidates candidates = result instanceof RecipeRouteFinder.Result.Candidates found
                ? found
                : null;
        if (candidates == null || candidates.routes().isEmpty()) {
            throw new AssertionError("Native semantic logs-to-torches process was not resolved: " + result);
        }
        List<ProductionCardData> cards = candidates.routes().getFirst().cards();
        if (cards.size() != 4 || !ProductionQuantityPlanner.itemId(cards.getLast().result().primary())
                .equals("minecraft:torch")) {
            throw new AssertionError("Unexpected logs-to-torches process: " + cards);
        }
        return new ProductionPresentation.Sequence(cards);
    }

    private static ProductionPresentation routeComparison(List<ProductionPresentation> quantities) {
        ProductionPlan crafting = ((ProductionPresentation.Plan) quantities.getFirst()).plan();
        ProductionPlan stonecutting = ((ProductionPresentation.Plan) quantities.get(1)).plan();
        return new ProductionPresentation.Comparison(List.of(
                new ProductionPresentation.Route("preview-stonecutting", "Stonecutting", stonecutting),
                new ProductionPresentation.Route("preview-crafting", "Crafting", crafting)
        ));
    }

    private static ProductionPresentation sixStepPreview(net.minecraft.client.Minecraft client) {
        RecipeCardResolver resolver = new RecipeCardResolver(client);
        List<ProductionCardData> cards = List.of(
                recipe(resolver.resolve("minecraft:oak_planks", Optional.of(ProductionMethod.CRAFTING)),
                        "preview oak planks"),
                recipe(resolver.resolve("minecraft:stick", Optional.of(ProductionMethod.CRAFTING)),
                        "preview sticks"),
                recipe(resolver.resolve("minecraft:crafting_table", Optional.of(ProductionMethod.CRAFTING)),
                        "preview crafting table"),
                recipe(resolver.resolve("minecraft:chest", Optional.of(ProductionMethod.CRAFTING)),
                        "preview chest"),
                recipe(resolver.resolve("minecraft:oak_slab", Optional.of(ProductionMethod.CRAFTING)),
                        "preview oak slab"),
                recipe(resolver.resolve("minecraft:oak_stairs", Optional.of(ProductionMethod.CRAFTING)),
                        "preview oak stairs")
        );
        ProductionPlan.Material root = previewMaterial(
                ProductionQuantityPlanner.consumableIngredients(cards.getFirst()).getFirst(), 12
        );
        List<ProductionPlan.Operation> operations = new ArrayList<>();
        for (int index = 0; index < cards.size(); index++) {
            ProductionCardData card = cards.get(index);
            ProductionPlan.Material input = index == 0
                    ? root
                    : previewMaterial(cards.get(index - 1).result(), 8L + index);
            ProductionPlan.Material output = previewMaterial(card.result(), 8L + index);
            operations.add(new ProductionPlan.Operation(card, index + 1L, List.of(input), output, 0, 0));
        }
        ProductionPlan.Material target = operations.getLast().output();
        return new ProductionPresentation.Plan(new ProductionPlan(
                target.count(), target.count(), target.itemId(), target.name(), root.itemId(), root.name(),
                operations, List.of(root), List.of(), false
        ));
    }

    private static ProductionPlan.Material previewMaterial(ProductionCardData.Slot slot, long count) {
        net.minecraft.world.item.ItemStack stack = slot.primary();
        return new ProductionPlan.Material(
                ProductionQuantityPlanner.itemId(stack), stack.getHoverName().getString(), count,
                slot.alternatives().stream().map(ProductionQuantityPlanner::itemId).distinct().toList(), stack
        );
    }

    private static Path previewOutput() {
        String configured = System.getenv(PREVIEW_OUTPUT_ENV);
        if (configured == null || configured.isBlank()) {
            throw new AssertionError(PREVIEW_OUTPUT_ENV + " is required in preview mode");
        }
        Path output = Path.of(configured).toAbsolutePath().normalize();
        if (!output.getFileName().toString().equals("ui-previews")) {
            throw new AssertionError("Refusing to write previews outside a ui-previews directory: " + output);
        }
        return output;
    }

    private static void resetPreviewOutput(Path output) {
        try {
            Files.createDirectories(output);
            try (var files = Files.list(output)) {
                for (Path file : files.toList()) {
                    if (Files.isRegularFile(file)) {
                        Files.delete(file);
                    }
                }
            }
        } catch (IOException failure) {
            throw new AssertionError("Could not prepare UI preview output", failure);
        }
    }

    private static Path copyLatestScreenshot(String name, Path output) {
        try (var files = Files.list(Path.of("screenshots"))) {
            Path source = files.filter(path -> path.getFileName().toString().endsWith("_" + name + ".png"))
                    .max(Path::compareTo)
                    .orElseThrow(() -> new AssertionError("Preview screenshot was not written: " + name));
            Path destination = output.resolve(name + ".png");
            return Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            throw new AssertionError("Could not export UI preview " + name, failure);
        }
    }

    private static void verifyScreenshot(Path screenshot) {
        try {
            BufferedImage image = ImageIO.read(screenshot.toFile());
            if (image == null || image.getWidth() < 320 || image.getHeight() < 180) {
                throw new AssertionError("Invalid UI preview dimensions: " + screenshot);
            }
            Set<Integer> colors = new HashSet<>();
            int xStep = Math.max(1, image.getWidth() / 16);
            int yStep = Math.max(1, image.getHeight() / 12);
            for (int y = 0; y < image.getHeight(); y += yStep) {
                for (int x = 0; x < image.getWidth(); x += xStep) {
                    colors.add(image.getRGB(x, y));
                }
            }
            if (colors.size() < 8) {
                throw new AssertionError("UI preview appears blank: " + screenshot);
            }
        } catch (IOException failure) {
            throw new AssertionError("Could not inspect UI preview " + screenshot, failure);
        }
    }

    private static void writePreviewIndex(Path output, List<String> screenshots) {
        StringBuilder html = new StringBuilder("<!doctype html><meta charset=\"utf-8\"><title>Minecraft "
                + "Assistant UI previews</title><style>body{font:16px system-ui;background:#181818;color:#eee;"
                + "margin:24px}section{margin:0 0 32px}img{max-width:100%;border:1px solid #555}</style>"
                + "<h1>Minecraft Assistant UI previews</h1>");
        for (String screenshot : screenshots) {
            String title = screenshot.substring(0, screenshot.length() - 4).replace('-', ' ');
            html.append("<section><h2>").append(title).append("</h2><img src=\"")
                    .append(screenshot).append("\" alt=\"").append(title).append("\"></section>");
        }
        try {
            Files.writeString(output.resolve("index.html"), html.toString());
        } catch (IOException failure) {
            throw new AssertionError("Could not write UI preview index", failure);
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

    private static List<ProductionPresentation> verifyQuantityPlanning(net.minecraft.client.Minecraft client) {
        RecipeCardResolver resolver = new RecipeCardResolver(client);
        ProductionQuantityPlanner planner = new ProductionQuantityPlanner();
        ProductionQuantityRequest target = new ProductionQuantityRequest(5, 0);

        List<ProductionCardData> craftingCards = List.of(
                recipe(resolver.resolve("minecraft:stone", Optional.of(ProductionMethod.SMELTING)), "stone smelting"),
                recipe(resolver.resolve("minecraft:stone_bricks", Optional.of(ProductionMethod.CRAFTING)),
                        "stone brick crafting"),
                recipe(resolver.resolve("minecraft:stone_brick_slab", Optional.of(ProductionMethod.CRAFTING)),
                        "stone brick slab crafting")
        );
        ProductionPlan craftingPlan = quantityPlan(planner.plan(
                craftingCards, target, Optional.of("minecraft:cobblestone")
        ), "crafting route");
        if (craftingPlan.sourceMaterial().orElseThrow().count() != 164
                || craftingPlan.producedCount() != 324
                || craftingPlan.leftovers().stream().noneMatch(material ->
                        material.itemId().equals("minecraft:stone_bricks") && material.count() == 2)
                || craftingPlan.producedCount() - craftingPlan.requestedCount() != 4) {
            throw new AssertionError("Unexpected real-registry crafting totals: " + craftingPlan);
        }

        RecipeRouteFinder.Result.Candidates automaticRoutes =
                (RecipeRouteFinder.Result.Candidates) new RecipeRouteFinder(planner)
                .find(
                        resolver.allRecipes(),
                        "minecraft:cobblestone",
                        "minecraft:stone_brick_slab",
                        Optional.empty(),
                        target,
                        ShowProcessTool.MAX_STEPS
                );
        RecipeRouteFinder.Candidate automatic = automaticRoutes.routes().getFirst();
        if (automatic.cards().getLast().method() != ProductionMethod.STONECUTTING
                || automatic.plan().sourceMaterial().orElseThrow().count() != 160
                || automatic.plan().producedCount() != 320) {
            throw new AssertionError("Unexpected real-registry automatic route: " + automaticRoutes);
        }

        List<ProductionCardData> stairCards = List.of(
                recipe(resolver.resolve("minecraft:oak_planks", Optional.of(ProductionMethod.CRAFTING)),
                        "oak planks crafting"),
                recipe(resolver.resolve("minecraft:oak_stairs", Optional.of(ProductionMethod.CRAFTING)),
                        "oak stairs crafting")
        );
        ProductionPlan stairPlan = quantityPlan(planner.plan(
                stairCards, new ProductionQuantityRequest(0, 128), Optional.of("minecraft:oak_log")
        ), "oak stairs route");
        if (stairPlan.sourceMaterial().orElseThrow().count() != 48
                || stairPlan.operations().getFirst().outputProduced() != 192
                || stairPlan.producedCount() != 128) {
            throw new AssertionError("Unexpected real-registry oak stair totals: " + stairPlan);
        }
        RecipeRouteFinder.Result.Candidates oakRoutes =
                (RecipeRouteFinder.Result.Candidates) new RecipeRouteFinder(planner).find(
                        resolver.allRecipes(),
                        "minecraft:oak_log",
                        "minecraft:oak_stairs",
                        Optional.empty(),
                        new ProductionQuantityRequest(0, 128),
                        ShowProcessTool.MAX_STEPS
                );
        if (oakRoutes.routes().stream().noneMatch(candidate ->
                candidate.plan().sourceMaterial().orElseThrow().count() == 48)
                || oakRoutes.routes().stream().anyMatch(candidate ->
                candidate.plan().sourceMaterial().orElseThrow().count() == 64)) {
            throw new AssertionError("Dominance filtering retained an inferior oak detour: " + oakRoutes);
        }

        List<ProductionCardData> pickaxeCards = List.of(
                stairCards.getFirst(),
                recipe(resolver.resolve("minecraft:stick", Optional.of(ProductionMethod.CRAFTING)),
                        "stick crafting"),
                recipe(resolver.resolve("minecraft:wooden_pickaxe", Optional.of(ProductionMethod.CRAFTING)),
                        "wooden pickaxe crafting")
        );
        ProductionPlan pickaxePlan = quantityPlan(planner.plan(
                pickaxeCards, new ProductionQuantityRequest(0, 2), Optional.of("minecraft:oak_log")
        ), "wooden pickaxe route");
        if (pickaxePlan.sourceMaterial().orElseThrow().count() != 2
                || pickaxePlan.operations().size() != 3
                || pickaxePlan.producedCount() != 2) {
            throw new AssertionError("Unexpected pooled wooden pickaxe totals: " + pickaxePlan);
        }
        return List.of(
                new ProductionPresentation.Plan(craftingPlan),
                new ProductionPresentation.Plan(automatic.plan()),
                new ProductionPresentation.Plan(stairPlan),
                new ProductionPresentation.Plan(pickaxePlan)
        );
    }

    private static ProductionCardData recipe(RecipeLookupResult result, String label) {
        if (result instanceof RecipeLookupResult.Found found) {
            return found.card();
        }
        throw new AssertionError("Native " + label + " recipe failed: " + result);
    }

    private static ProductionPlan quantityPlan(ProductionQuantityPlanner.Result result, String label) {
        if (result instanceof ProductionQuantityPlanner.Result.Success success) {
            return success.plan();
        }
        throw new AssertionError("Native " + label + " quantity plan failed: " + result);
    }

    private static NativeProductionResult.Found found(NativeProductionResult result, String label) {
        if (result instanceof NativeProductionResult.Found found) {
            return found;
        }
        throw new AssertionError("Native " + label + " guide failed: " + result);
    }
}
