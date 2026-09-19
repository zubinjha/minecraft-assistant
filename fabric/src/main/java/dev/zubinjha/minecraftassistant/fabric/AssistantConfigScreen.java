package dev.zubinjha.minecraftassistant.fabric;

import java.io.IOException;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

public final class AssistantConfigScreen extends Screen {
    private static final List<String> EFFORTS = List.of("low", "medium", "high");
    private final Screen parent;
    private final ConfigStore store;
    private final MinecraftAssistantRuntime runtime;
    private EditBox apiKey;
    private EditBox model;
    private String effort;
    private Component status = Component.literal("Credentials stay in this Minecraft instance.")
            .withStyle(ChatFormatting.GRAY);

    public AssistantConfigScreen(Screen parent, ConfigStore store, MinecraftAssistantRuntime runtime) {
        super(Component.literal("Minecraft Assistant"));
        this.parent = parent;
        this.store = store;
        this.runtime = runtime;
        this.effort = runtime.config().reasoningEffort();
    }

    @Override
    protected void init() {
        AssistantConfig current = runtime.config();
        int fieldWidth = Math.min(300, width - 40);
        int left = (width - fieldWidth) / 2;

        apiKey = new EditBox(font, left, 50, fieldWidth, 20, Component.literal("OpenRouter API key"));
        apiKey.setMaxLength(512);
        apiKey.setValue(current.apiKey());
        apiKey.setHint(Component.literal("sk-or-v1-…"));
        apiKey.addFormatter((value, cursor) -> FormattedCharSequence.forward(
                "•".repeat(value.length()),
                Style.EMPTY
        ));
        addRenderableWidget(apiKey);

        model = new EditBox(font, left, 90, fieldWidth, 20, Component.literal("Model"));
        model.setMaxLength(160);
        model.setValue(current.model());
        model.setHint(Component.literal(AssistantConfig.DEFAULT_MODEL));
        addRenderableWidget(model);

        addRenderableWidget(CycleButton.<String>builder(Component::literal, effort)
                .withValues(EFFORTS)
                .create(left, 120, fieldWidth, 20, Component.literal("Reasoning"),
                        (button, value) -> effort = value));

        int half = (fieldWidth - 6) / 2;
        addRenderableWidget(Button.builder(Component.literal("Test Connection"), button -> testConnection())
                .bounds(left, 150, half, 20)
                .build());
        addRenderableWidget(Button.builder(Component.literal("Save"), button -> save())
                .bounds(left + half + 6, 150, half, 20)
                .build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), button -> onClose())
                .bounds(left, 176, fieldWidth, 20)
                .build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(font, title, width / 2, 18, 0xFFFFFFFF);
        int left = apiKey == null ? 20 : apiKey.getX();
        graphics.text(font, "OpenRouter API key", left, 38, 0xFFAAAAAA);
        graphics.text(font, "Model", left, 78, 0xFFAAAAAA);
        graphics.centeredText(font, status, width / 2, 207, 0xFFFFFFFF);
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.gui.setScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private AssistantConfig candidate() {
        return new AssistantConfig(
                apiKey.getValue(),
                model.getValue(),
                effort,
                runtime.config().wikiEndpoint()
        );
    }

    private void testConnection() {
        status = Component.literal("Testing OpenRouter…").withStyle(ChatFormatting.GRAY);
        AssistantConfig candidate = candidate();
        runtime.testConnection(candidate).whenComplete((message, failure) ->
                Minecraft.getInstance().execute(() -> {
                    if (failure == null) {
                        status = Component.literal(message).withStyle(ChatFormatting.GREEN);
                    } else {
                        Throwable cause = failure instanceof java.util.concurrent.CompletionException
                                && failure.getCause() != null ? failure.getCause() : failure;
                        status = Component.literal(cause.getMessage() == null
                                ? "Connection failed" : cause.getMessage()).withStyle(ChatFormatting.RED);
                    }
                })
        );
    }

    private void save() {
        AssistantConfig updated = candidate();
        try {
            store.save(updated);
            runtime.updateConfig(updated);
            status = Component.literal("Saved. You can now use /ask.").withStyle(ChatFormatting.GREEN);
        } catch (IOException failure) {
            status = Component.literal("Could not save settings.").withStyle(ChatFormatting.RED);
        }
    }
}
