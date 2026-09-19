package dev.zubinjha.minecraftassistant.fabric;

import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

public final class RecipeCardScreen extends Screen {
    private static final int PANEL_WIDTH = 210;
    private static final int PANEL_HEIGHT = 170;
    private static final int SLOT_SIZE = 22;
    private final RecipeCardData recipe;

    RecipeCardScreen(RecipeCardData recipe) {
        super(Component.literal(recipe.method().displayName() + ": ").append(recipe.title()));
        this.recipe = recipe;
    }

    @Override
    protected void init() {
        int left = (width - PANEL_WIDTH) / 2;
        int top = (height - PANEL_HEIGHT) / 2;
        addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
                .bounds(left + 55, top + 140, 100, 20)
                .build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, 0x88000000);
        int left = (width - PANEL_WIDTH) / 2;
        int top = (height - PANEL_HEIGHT) / 2;
        graphics.fill(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xFF1F1F1F);
        graphics.fill(left + 2, top + 2, left + PANEL_WIDTH - 2, top + PANEL_HEIGHT - 2, 0xFF373737);

        graphics.centeredText(font, recipe.title(), width / 2, top + 9, 0xFFFFFFFF);
        graphics.centeredText(font, recipe.method().displayName(), width / 2, top + 22, 0xFFAAAAAA);

        switch (recipe) {
            case RecipeCardData.Crafting crafting -> drawCrafting(
                    graphics, crafting, left, top, mouseX, mouseY
            );
            case RecipeCardData.Cooking cooking -> drawCooking(
                    graphics, cooking, left, top, mouseX, mouseY
            );
            case RecipeCardData.Stonecutting stonecutting -> drawStonecutting(
                    graphics, stonecutting, left, top, mouseX, mouseY
            );
            case RecipeCardData.Smithing smithing -> drawSmithing(
                    graphics, smithing, left, top, mouseX, mouseY
            );
        }
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    private void drawCrafting(
            GuiGraphicsExtractor graphics,
            RecipeCardData.Crafting crafting,
            int left,
            int top,
            int mouseX,
            int mouseY
    ) {
        int gridX = left + 16;
        int gridY = top + 39;
        int offsetX = ((3 - crafting.gridWidth()) / 2) * SLOT_SIZE;
        int offsetY = ((3 - crafting.gridHeight()) / 2) * SLOT_SIZE;
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                drawSlot(graphics, gridX + column * SLOT_SIZE, gridY + row * SLOT_SIZE);
            }
        }
        for (int index = 0; index < crafting.ingredients().size(); index++) {
            int row = index / crafting.gridWidth();
            int column = index % crafting.gridWidth();
            drawRecipeSlot(
                    graphics,
                    crafting.ingredients().get(index),
                    gridX + offsetX + column * SLOT_SIZE,
                    gridY + offsetY + row * SLOT_SIZE,
                    mouseX,
                    mouseY
            );
        }
        graphics.text(font, "→", left + 101, top + 64, 0xFFFFFFFF);
        drawSlot(graphics, left + 137, top + 61);
        drawRecipeSlot(graphics, crafting.result(), left + 137, top + 61, mouseX, mouseY);
        graphics.text(
                font,
                crafting.shapeless() ? "Shapeless crafting" : "Crafting recipe",
                left + 16,
                top + 113,
                0xFFAAAAAA
        );
    }

    private void drawCooking(
            GuiGraphicsExtractor graphics,
            RecipeCardData.Cooking cooking,
            int left,
            int top,
            int mouseX,
            int mouseY
    ) {
        int inputX = left + 47;
        int inputY = top + 48;
        int fuelY = top + 82;
        int resultX = left + 145;
        int resultY = top + 60;
        drawSlot(graphics, inputX, inputY);
        drawRecipeSlot(graphics, cooking.input(), inputX, inputY, mouseX, mouseY);
        if (!cooking.fuel().alternatives().isEmpty() || cooking.fuel().anyFuel()) {
            drawSlot(graphics, inputX, fuelY);
            drawRecipeSlot(graphics, cooking.fuel(), inputX, fuelY, mouseX, mouseY);
            graphics.text(font, "Fuel", left + 17, fuelY + 6, 0xFFAAAAAA);
        }
        graphics.text(font, "→", left + 105, top + 68, 0xFFFFFFFF);
        drawSlot(graphics, resultX, resultY);
        drawRecipeSlot(graphics, cooking.result(), resultX, resultY, mouseX, mouseY);
        drawStation(graphics, cooking.station(), left + 176, top + 38, mouseX, mouseY);

        String details = String.format(
                Locale.ROOT,
                "%.1fs · %.1f XP",
                cooking.durationTicks() / 20.0,
                cooking.experience()
        );
        graphics.centeredText(font, details, width / 2, top + 113, 0xFFAAAAAA);
    }

    private void drawStonecutting(
            GuiGraphicsExtractor graphics,
            RecipeCardData.Stonecutting stonecutting,
            int left,
            int top,
            int mouseX,
            int mouseY
    ) {
        int y = top + 66;
        drawSlot(graphics, left + 43, y);
        drawRecipeSlot(graphics, stonecutting.input(), left + 43, y, mouseX, mouseY);
        graphics.text(font, "→", left + 101, y + 4, 0xFFFFFFFF);
        drawSlot(graphics, left + 145, y);
        drawRecipeSlot(graphics, stonecutting.result(), left + 145, y, mouseX, mouseY);
        drawStation(graphics, stonecutting.station(), left + 176, top + 38, mouseX, mouseY);
        graphics.centeredText(font, "Stonecutter", width / 2, top + 113, 0xFFAAAAAA);
    }

    private void drawSmithing(
            GuiGraphicsExtractor graphics,
            RecipeCardData.Smithing smithing,
            int left,
            int top,
            int mouseX,
            int mouseY
    ) {
        RecipeCardData.Slot[] inputs = {smithing.template(), smithing.base(), smithing.addition()};
        String[] labels = {"Template", "Base", "Addition"};
        for (int index = 0; index < inputs.length; index++) {
            int x = left + 65;
            int y = top + 41 + index * 27;
            graphics.text(font, labels[index], left + 13, y + 6, 0xFFAAAAAA);
            drawSlot(graphics, x, y);
            drawRecipeSlot(graphics, inputs[index], x, y, mouseX, mouseY);
        }
        graphics.text(font, "→", left + 111, top + 70, 0xFFFFFFFF);
        drawSlot(graphics, left + 145, top + 66);
        drawRecipeSlot(graphics, smithing.result(), left + 145, top + 66, mouseX, mouseY);
        drawStation(graphics, smithing.station(), left + 176, top + 38, mouseX, mouseY);
    }

    private void drawStation(
            GuiGraphicsExtractor graphics,
            RecipeCardData.Slot station,
            int x,
            int y,
            int mouseX,
            int mouseY
    ) {
        if (!station.alternatives().isEmpty()) {
            drawRecipeSlot(graphics, station, x, y, mouseX, mouseY);
        }
    }

    private void drawSlot(GuiGraphicsExtractor graphics, int x, int y) {
        graphics.fill(x, y, x + 20, y + 20, 0xFF8B8B8B);
        graphics.fill(x + 1, y + 1, x + 19, y + 19, 0xFF373737);
    }

    private void drawRecipeSlot(
            GuiGraphicsExtractor graphics,
            RecipeCardData.Slot slot,
            int x,
            int y,
            int mouseX,
            int mouseY
    ) {
        ItemStack stack = slot.displayed(System.currentTimeMillis());
        if (!stack.isEmpty()) {
            graphics.item(stack, x + 2, y + 2);
            graphics.itemDecorations(font, stack, x + 2, y + 2);
        }
        if (mouseX >= x && mouseX < x + 20 && mouseY >= y && mouseY < y + 20) {
            if (slot.anyFuel()) {
                graphics.setTooltipForNextFrame(font, Component.literal("Any valid fuel"), mouseX, mouseY);
            } else if (!stack.isEmpty()) {
                graphics.setTooltipForNextFrame(font, stack, mouseX, mouseY);
            }
        }
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.gui.setScreen(null);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
