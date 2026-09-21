package dev.zubinjha.minecraftassistant.fabric;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static com.mojang.brigadier.arguments.StringArgumentType.string;
import static com.mojang.brigadier.arguments.StringArgumentType.word;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public final class MinecraftAssistantClient implements ClientModInitializer {
    private static ConfigStore configStore;
    private static MinecraftAssistantRuntime runtime;

    @Override
    public void onInitializeClient() {
        configStore = new ConfigStore();
        runtime = new MinecraftAssistantRuntime(Minecraft.getInstance(), configStore.load());
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            registerAskCommand(dispatcher, "ask");
            registerMcaiFallback(dispatcher);
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> runtime.close());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
                runtime.resetConversationSession());
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receivedAt) -> {
            if (sender == null) {
                return;
            }
            SharedResponseEnhancer.enhancement(signedMessage, sender.name()).ifPresent(actions -> {
                Minecraft minecraft = Minecraft.getInstance();
                minecraft.execute(() -> minecraft.gui.hud.getChat().addClientSystemMessage(actions));
            });
        });
    }

    static MinecraftAssistantRuntime runtimeForTest() {
        return runtime;
    }

    private void registerAskCommand(
            CommandDispatcher<FabricClientCommandSource> dispatcher,
            String name
    ) {
        dispatcher.register(command(name));
    }

    private LiteralArgumentBuilder<FabricClientCommandSource> command(String name) {
        return literal(name)
                .executes(context -> help(context.getSource()))
                .then(literal("help").executes(context -> help(context.getSource())))
                .then(literal("config").executes(context -> {
                    Minecraft minecraft = Minecraft.getInstance();
                    minecraft.schedule(() -> minecraft.gui.setScreen(
                            new AssistantConfigScreen(
                                    minecraft.gui.screen(),
                                    configStore,
                                    runtime
                            )
                    ));
                    return 1;
                }))
                .then(literal("share").executes(context -> {
                    runtime.shareLatest();
                    return 1;
                }))
                .then(literal("stop").executes(context -> {
                    if (!runtime.cancelActive()) {
                        context.getSource().sendFeedback(Component.literal(
                                "There is no active assistant request."
                        ).withStyle(ChatFormatting.GRAY));
                    }
                    return 1;
                }))
                .then(literal("clear").executes(context -> {
                    runtime.clearMemory();
                    return 1;
                }))
                .then(argument("question", greedyString()).executes(context -> {
                    String question = getString(context, "question").trim();
                    runtime.ask(question);
                    return 1;
                }));
    }

    private void registerMcaiFallback(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(literal("mcai")
                .then(command("ask"))
                .then(literal("view")
                        .then(argument("token", word()).executes(context -> {
                            runtime.openPresentation(getString(context, "token"));
                            return 1;
                        })))
                .then(literal("recipe")
                        .then(argument("recipe_id", string())
                                .then(argument("method", word()).executes(context -> {
                                    runtime.openRecipe(
                                            getString(context, "recipe_id"),
                                            getString(context, "method")
                                    );
                                    return 1;
                                })))));
    }

    private static int help(FabricClientCommandSource source) {
        source.sendFeedback(Component.literal("Minecraft Assistant").withStyle(ChatFormatting.YELLOW));
        helpLine(source, "/ask <question>", "Ask a Minecraft question");
        helpLine(source, "/ask config", "Configure the provider and model");
        helpLine(source, "/ask share", "Share the latest answer in player chat");
        helpLine(source, "/ask stop", "Cancel the current request");
        helpLine(source, "/ask clear", "Forget conversation history");
        helpLine(source, "/ask help", "Show this help");
        source.sendFeedback(Component.literal(
                "Remembers the last 10 exchanges for the current world or server session."
        ).withStyle(ChatFormatting.GRAY));
        return 1;
    }

    private static void helpLine(FabricClientCommandSource source, String command, String description) {
        source.sendFeedback(Component.literal(command).withStyle(ChatFormatting.AQUA)
                .append(Component.literal(" — " + description).withStyle(ChatFormatting.GRAY)));
    }
}
