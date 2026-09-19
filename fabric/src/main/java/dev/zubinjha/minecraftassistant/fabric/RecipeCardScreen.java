package dev.zubinjha.minecraftassistant.fabric;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

public final class RecipeCardScreen extends Screen {
    private static final int PANEL_WIDTH = 190;
    private static final int PANEL_HEIGHT = 142;
    private static final int SLOT_SIZE = 22;
    private final RecipeCardData recipe;

    RecipeCardScreen(RecipeCardData recipe) {
        super(Component.literal("Recipe: ").append(recipe.title()));
        this.recipe = recipe;
    }

    @Override
    protected void init() {
        int left = (width - PANEL_WIDTH) / 2;
        int top = (height - PANEL_HEIGHT) / 2;
        addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
                .bounds(left + 45, top + 112, 100, 20)
                .build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, 0x88000000);
        int left = (width - PANEL_WIDTH) / 2;
        int top = (height - PANEL_HEIGHT) / 2;
        graphics.fill(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xFF1F1F1F);
        graphics.fill(left + 2, top + 2, left + PANEL_WIDTH - 2, top + PANEL_HEIGHT - 2, 0xFF373737);

        graphics.centeredText(font, recipe.title(), width / 2, top + 10, 0xFFFFFFFF);

        int gridX = left + 14;
        int gridY = top + 30;
        int offsetX = ((3 - recipe.gridWidth()) / 2) * SLOT_SIZE;
        int offsetY = ((3 - recipe.gridHeight()) / 2) * SLOT_SIZE;
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                drawSlot(graphics, gridX + column * SLOT_SIZE, gridY + row * SLOT_SIZE);
            }
        }

        for (int index = 0; index < recipe.ingredients().size(); index++) {
            int row = index / recipe.gridWidth();
            int column = index % recipe.gridWidth();
            int x = gridX + offsetX + column * SLOT_SIZE;
            int y = gridY + offsetY + row * SLOT_SIZE;
            drawItem(graphics, recipe.ingredients().get(index), x, y, mouseX, mouseY);
        }

        graphics.text(font, "→", left + 91, top + 55, 0xFFFFFFFF);
        int resultX = left + 118;
        int resultY = top + 52;
        drawSlot(graphics, resultX, resultY);
        drawItem(graphics, recipe.result(), resultX, resultY, mouseX, mouseY);
        graphics.text(
                font,
                recipe.shapeless() ? "Shapeless recipe" : "Crafting recipe",
                left + 14,
                top + 99,
                0xFFAAAAAA
        );
        super.extractRenderState(graphics, mouseX, mouseY, delta);
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

    private void drawSlot(GuiGraphicsExtractor graphics, int x, int y) {
        graphics.fill(x, y, x + 20, y + 20, 0xFF8B8B8B);
        graphics.fill(x + 1, y + 1, x + 19, y + 19, 0xFF373737);
    }

    private void drawItem(
            GuiGraphicsExtractor graphics,
            ItemStack stack,
            int x,
            int y,
            int mouseX,
            int mouseY
    ) {
        if (stack.isEmpty()) {
            return;
        }
        graphics.item(stack, x + 2, y + 2);
        graphics.itemDecorations(font, stack, x + 2, y + 2);
        if (mouseX >= x && mouseX < x + 20 && mouseY >= y && mouseY < y + 20) {
            graphics.setTooltipForNextFrame(font, stack, mouseX, mouseY);
        }
    }
}
