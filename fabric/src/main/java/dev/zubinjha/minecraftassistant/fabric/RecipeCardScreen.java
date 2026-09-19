package dev.zubinjha.minecraftassistant.fabric;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

public final class RecipeCardScreen extends Screen {
    private static final int SCREEN_MARGIN = 8;
    private static final int SHELL_WIDTH = 196;
    private static final int SINGLE_HEIGHT = 150;
    private static final int MULTI_HEIGHT = 212;
    private static final int NATIVE_TEXTURE_SIZE = 256;
    private static final int SLOT_SIZE = 16;
    private static final Identifier SLOT_SPRITE = Identifier.withDefaultNamespace("container/slot");
    private static final Identifier POPUP_BACKGROUND = Identifier.withDefaultNamespace("popup/background");
    private static final Identifier STONECUTTER_SELECTED = Identifier.withDefaultNamespace(
            "container/stonecutter/recipe_selected"
    );
    private static final Identifier CAMPFIRE_PROGRESS = Identifier.withDefaultNamespace(
            "container/furnace/burn_progress"
    );

    private final RecipePresentation presentation;
    private final List<Button> tabButtons = new ArrayList<>();
    private int selectedIndex;
    private Button previousButton;
    private Button nextButton;
    private RecipeCardData.Slot frozenSlot;
    private int frozenAlternativeIndex;
    private boolean slotHoveredThisFrame;

    RecipeCardScreen(RecipeCardData recipe) {
        this(new RecipePresentation.Single(recipe));
    }

    RecipeCardScreen(RecipePresentation presentation) {
        super(presentation.targetTitle());
        this.presentation = presentation;
    }

    int selectedIndex() {
        return selectedIndex;
    }

    void selectCard(int index) {
        selectedIndex = selectedIndex(selectedIndex, index, presentation.cards().size());
        updateNavigation();
    }

    static int selectedIndex(int current, int requested, int cardCount) {
        return requested >= 0 && requested < cardCount ? requested : current;
    }

    static ShellGeometry shellGeometry(int screenWidth, int screenHeight, boolean multipleCards) {
        int shellHeight = multipleCards ? MULTI_HEIGHT : SINGLE_HEIGHT;
        int left = Math.max(SCREEN_MARGIN, (screenWidth - SHELL_WIDTH) / 2);
        int top = Math.max(SCREEN_MARGIN, (screenHeight - shellHeight) / 2);
        int panelY = top + (multipleCards ? 88 : 27);
        return new ShellGeometry(left, top, SHELL_WIDTH, shellHeight, left + 10, panelY);
    }

    static String cookingDetails(int durationTicks, float experience) {
        return String.format(
                Locale.ROOT,
                "Cooking time: %.1f seconds\nExperience: %.1f",
                durationTicks / 20.0,
                experience
        );
    }

    @Override
    protected void init() {
        tabButtons.clear();
        previousButton = null;
        nextButton = null;
        ShellGeometry shell = shellGeometry(width, height, isMultiple());
        int footerY = shell.top() + shell.height() - 28;

        if (isMultiple()) {
            int count = presentation.cards().size();
            int tabWidth = 22;
            int gap = 3;
            int totalWidth = count * tabWidth + (count - 1) * gap;
            int startX = shell.left() + (shell.width() - totalWidth) / 2;
            for (int index = 0; index < count; index++) {
                int cardIndex = index;
                Button tab = Button.builder(Component.literal(Integer.toString(index + 1)), button ->
                                selectCard(cardIndex))
                        .bounds(startX + index * (tabWidth + gap), shell.top() + 51, tabWidth, 16)
                        .build();
                tabButtons.add(tab);
                addRenderableWidget(tab);
            }
        }

        if (presentation instanceof RecipePresentation.Sequence) {
            previousButton = addRenderableWidget(Button.builder(Component.literal("Previous"), button ->
                            selectCard(selectedIndex - 1))
                    .bounds(shell.left() + 6, footerY, 58, 20)
                    .build());
            nextButton = addRenderableWidget(Button.builder(Component.literal("Next"), button ->
                            selectCard(selectedIndex + 1))
                    .bounds(shell.left() + 69, footerY, 58, 20)
                    .build());
            addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
                    .bounds(shell.left() + 132, footerY, 58, 20)
                    .build());
        } else {
            addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
                    .bounds(shell.left() + 48, footerY, 100, 20)
                    .build());
        }
        updateNavigation();
    }

    private void updateNavigation() {
        for (int index = 0; index < tabButtons.size(); index++) {
            Button tab = tabButtons.get(index);
            tab.active = index != selectedIndex;
            tab.setMessage(Component.literal(index == selectedIndex
                    ? "[" + (index + 1) + "]"
                    : Integer.toString(index + 1)));
        }
        if (previousButton != null) {
            previousButton.active = selectedIndex > 0;
        }
        if (nextButton != null) {
            nextButton.active = selectedIndex < presentation.cards().size() - 1;
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, 0x88000000);
        ShellGeometry shell = shellGeometry(width, height, isMultiple());
        graphics.fill(
                shell.left(),
                shell.top(),
                shell.left() + shell.width(),
                shell.top() + shell.height(),
                0xE0101010
        );
        graphics.fill(
                shell.left() + 1,
                shell.top() + 1,
                shell.left() + shell.width() - 1,
                shell.top() + shell.height() - 1,
                0xE0282828
        );

        RecipeCardData recipe = presentation.displayedCard(selectedIndex);
        if (isMultiple()) {
            String countLabel = presentation instanceof RecipePresentation.Sequence
                    ? presentation.cards().size() + " steps"
                    : presentation.cards().size() + " recipes";
            graphics.centeredText(font, presentation.targetTitle(), width / 2, shell.top() + 6, 0xFFFFFFFF);
            graphics.centeredText(font, countLabel, width / 2, shell.top() + 18, 0xFFAAAAAA);
            drawPresentationFlow(graphics, shell, shell.top() + 31, mouseX, mouseY);
            graphics.centeredText(font, recipe.title(), width / 2, shell.top() + 72, 0xFFFFFFFF);
        } else {
            graphics.centeredText(font, recipe.title(), width / 2, shell.top() + 7, 0xFFFFFFFF);
        }

        slotHoveredThisFrame = false;
        drawNativePanel(graphics, recipe, shell.panelX(), shell.panelY(), mouseX, mouseY);
        if (!slotHoveredThisFrame) {
            frozenSlot = null;
        }
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    private void drawPresentationFlow(
            GuiGraphicsExtractor graphics,
            ShellGeometry shell,
            int y,
            int mouseX,
            int mouseY
    ) {
        int count = presentation.cards().size();
        int separatorWidth = 13;
        int totalWidth = count * SLOT_SIZE + (count - 1) * separatorWidth;
        int x = shell.left() + (shell.width() - totalWidth) / 2;
        for (int index = 0; index < count; index++) {
            ItemStack result = presentation.cards().get(index).result().primary();
            if (!result.isEmpty()) {
                graphics.item(result, x, y);
                graphics.itemDecorations(font, result, x, y);
                if (inside(mouseX, mouseY, x, y, SLOT_SIZE, SLOT_SIZE)) {
                    graphics.setTooltipForNextFrame(font, result, mouseX, mouseY);
                }
            }
            x += SLOT_SIZE;
            if (index < count - 1) {
                graphics.text(
                        font,
                        presentation instanceof RecipePresentation.Sequence ? "→" : "·",
                        x + 3,
                        y + 4,
                        0xFFAAAAAA
                );
                x += separatorWidth;
            }
        }
    }

    private void drawNativePanel(
            GuiGraphicsExtractor graphics,
            RecipeCardData recipe,
            int panelX,
            int panelY,
            int mouseX,
            int mouseY
    ) {
        NativeRecipeLayout layout = NativeRecipeLayout.forMethod(recipe.method());
        if (layout.hasNativeTexture()) {
            graphics.blit(
                    RenderPipelines.GUI_TEXTURED,
                    layout.texture(),
                    panelX,
                    panelY,
                    0.0F,
                    0.0F,
                    NativeRecipeLayout.PANEL_WIDTH,
                    NativeRecipeLayout.PANEL_HEIGHT,
                    NATIVE_TEXTURE_SIZE,
                    NATIVE_TEXTURE_SIZE
            );
        } else {
            graphics.blitSprite(
                    RenderPipelines.GUI_TEXTURED,
                    POPUP_BACKGROUND,
                    panelX,
                    panelY,
                    NativeRecipeLayout.PANEL_WIDTH,
                    NativeRecipeLayout.PANEL_HEIGHT
            );
        }

        drawWorkstationTitle(graphics, recipe, layout, panelX, panelY);
        switch (recipe) {
            case RecipeCardData.Crafting crafting -> drawCrafting(
                    graphics, crafting, panelX, panelY, mouseX, mouseY
            );
            case RecipeCardData.Cooking cooking when layout.kind() == NativeRecipeLayout.Kind.CAMPFIRE ->
                    drawCampfire(graphics, cooking, panelX, panelY, mouseX, mouseY);
            case RecipeCardData.Cooking cooking -> drawCooking(
                    graphics, cooking, layout, panelX, panelY, mouseX, mouseY
            );
            case RecipeCardData.Stonecutting stonecutting -> drawStonecutting(
                    graphics, stonecutting, panelX, panelY, mouseX, mouseY
            );
            case RecipeCardData.Smithing smithing -> drawSmithing(
                    graphics, smithing, panelX, panelY, mouseX, mouseY
            );
        }
    }

    private void drawWorkstationTitle(
            GuiGraphicsExtractor graphics,
            RecipeCardData recipe,
            NativeRecipeLayout layout,
            int panelX,
            int panelY
    ) {
        Component name = workstationName(recipe);
        int color = layout.kind() == NativeRecipeLayout.Kind.CAMPFIRE ? 0xFFFFFFFF : 0xFF404040;
        int y = panelY + (layout.kind() == NativeRecipeLayout.Kind.SMITHING ? 15 : 6);
        int x = switch (layout.kind()) {
            case CRAFTING -> panelX + 29;
            case COOKING -> panelX + (NativeRecipeLayout.PANEL_WIDTH - font.width(name)) / 2;
            case STONECUTTING -> panelX + 8;
            case SMITHING -> panelX + 44;
            case CAMPFIRE -> panelX + 8;
        };
        graphics.text(font, name, x, y, color, false);
        if (recipe instanceof RecipeCardData.Crafting crafting && crafting.shapeless()) {
            String label = "Shapeless";
            graphics.text(
                    font,
                    label,
                    panelX + NativeRecipeLayout.PANEL_WIDTH - 8 - font.width(label),
                    panelY + 6,
                    0xFF606060,
                    false
            );
        }
    }

    private void drawCrafting(
            GuiGraphicsExtractor graphics,
            RecipeCardData.Crafting crafting,
            int panelX,
            int panelY,
            int mouseX,
            int mouseY
    ) {
        NativeRecipeLayout.Point origin = NativeRecipeLayout.CRAFTING_GRID;
        int offsetX = ((3 - crafting.gridWidth()) / 2) * 18;
        int offsetY = ((3 - crafting.gridHeight()) / 2) * 18;
        for (int index = 0; index < crafting.ingredients().size(); index++) {
            int row = index / crafting.gridWidth();
            int column = index % crafting.gridWidth();
            drawRecipeItem(
                    graphics,
                    crafting.ingredients().get(index),
                    panelX + origin.x() + offsetX + column * 18,
                    panelY + origin.y() + offsetY + row * 18,
                    mouseX,
                    mouseY,
                    null
            );
        }
        drawAt(graphics, crafting.result(), NativeRecipeLayout.CRAFTING_RESULT, panelX, panelY, mouseX, mouseY, null);
    }

    private void drawCooking(
            GuiGraphicsExtractor graphics,
            RecipeCardData.Cooking cooking,
            NativeRecipeLayout layout,
            int panelX,
            int panelY,
            int mouseX,
            int mouseY
    ) {
        drawAt(graphics, cooking.input(), NativeRecipeLayout.COOKING_INPUT, panelX, panelY, mouseX, mouseY, null);
        drawAt(graphics, cooking.result(), NativeRecipeLayout.COOKING_RESULT, panelX, panelY, mouseX, mouseY, null);
        graphics.blitSprite(
                RenderPipelines.GUI_TEXTURED,
                layout.litProgressSprite(),
                panelX + NativeRecipeLayout.COOKING_FLAME.x(),
                panelY + NativeRecipeLayout.COOKING_FLAME.y(),
                14,
                14
        );
        graphics.blitSprite(
                RenderPipelines.GUI_TEXTURED,
                layout.burnProgressSprite(),
                panelX + NativeRecipeLayout.COOKING_PROGRESS.x(),
                panelY + NativeRecipeLayout.COOKING_PROGRESS.y(),
                24,
                16
        );

        int fuelX = panelX + NativeRecipeLayout.COOKING_FUEL.x();
        int fuelY = panelY + NativeRecipeLayout.COOKING_FUEL.y();
        int flameX = panelX + NativeRecipeLayout.COOKING_FLAME.x();
        int flameY = panelY + NativeRecipeLayout.COOKING_FLAME.y();
        if (inside(mouseX, mouseY, fuelX, fuelY, SLOT_SIZE, SLOT_SIZE)
                || inside(mouseX, mouseY, flameX, flameY, 14, 14)) {
            graphics.setTooltipForNextFrame(font, Component.literal("Any valid fuel"), mouseX, mouseY);
        }
        int progressX = panelX + NativeRecipeLayout.COOKING_PROGRESS.x();
        int progressY = panelY + NativeRecipeLayout.COOKING_PROGRESS.y();
        if (inside(mouseX, mouseY, progressX, progressY, 24, 16)) {
            graphics.setComponentTooltipForNextFrame(font, cookingDetailTooltip(cooking), mouseX, mouseY);
        }
    }

    private void drawStonecutting(
            GuiGraphicsExtractor graphics,
            RecipeCardData.Stonecutting stonecutting,
            int panelX,
            int panelY,
            int mouseX,
            int mouseY
    ) {
        drawAt(graphics, stonecutting.input(), NativeRecipeLayout.STONECUTTER_INPUT, panelX, panelY, mouseX, mouseY, null);
        graphics.blitSprite(
                RenderPipelines.GUI_TEXTURED,
                STONECUTTER_SELECTED,
                panelX + NativeRecipeLayout.STONECUTTER_CHOICE_BACKGROUND.x(),
                panelY + NativeRecipeLayout.STONECUTTER_CHOICE_BACKGROUND.y(),
                16,
                18
        );
        drawAt(graphics, stonecutting.result(), NativeRecipeLayout.STONECUTTER_CHOICE, panelX, panelY, mouseX, mouseY, null);
        drawAt(graphics, stonecutting.result(), NativeRecipeLayout.STONECUTTER_RESULT, panelX, panelY, mouseX, mouseY, null);
    }

    private void drawSmithing(
            GuiGraphicsExtractor graphics,
            RecipeCardData.Smithing smithing,
            int panelX,
            int panelY,
            int mouseX,
            int mouseY
    ) {
        drawAt(graphics, smithing.template(), NativeRecipeLayout.SMITHING_TEMPLATE, panelX, panelY, mouseX, mouseY, "Template");
        drawAt(graphics, smithing.base(), NativeRecipeLayout.SMITHING_BASE, panelX, panelY, mouseX, mouseY, "Base");
        drawAt(graphics, smithing.addition(), NativeRecipeLayout.SMITHING_ADDITION, panelX, panelY, mouseX, mouseY, "Addition");
        drawAt(graphics, smithing.result(), NativeRecipeLayout.SMITHING_RESULT, panelX, panelY, mouseX, mouseY, "Result");
    }

    private void drawCampfire(
            GuiGraphicsExtractor graphics,
            RecipeCardData.Cooking cooking,
            int panelX,
            int panelY,
            int mouseX,
            int mouseY
    ) {
        drawStandaloneSlot(graphics, panelX, panelY, NativeRecipeLayout.CAMPFIRE_INPUT);
        drawStandaloneSlot(graphics, panelX, panelY, NativeRecipeLayout.CAMPFIRE_RESULT);
        drawAt(graphics, cooking.input(), NativeRecipeLayout.CAMPFIRE_INPUT, panelX, panelY, mouseX, mouseY, "Input");
        drawAt(graphics, cooking.station(), NativeRecipeLayout.CAMPFIRE_STATION, panelX, panelY, mouseX, mouseY, "Campfire");
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, CAMPFIRE_PROGRESS, panelX + 98, panelY + 36, 24, 16);
        drawAt(graphics, cooking.result(), NativeRecipeLayout.CAMPFIRE_RESULT, panelX, panelY, mouseX, mouseY, "Result");
        if (inside(mouseX, mouseY, panelX + 98, panelY + 36, 24, 16)) {
            graphics.setComponentTooltipForNextFrame(font, cookingDetailTooltip(cooking), mouseX, mouseY);
        }
    }

    private void drawStandaloneSlot(
            GuiGraphicsExtractor graphics,
            int panelX,
            int panelY,
            NativeRecipeLayout.Point point
    ) {
        graphics.blitSprite(
                RenderPipelines.GUI_TEXTURED,
                SLOT_SPRITE,
                panelX + point.x() - 1,
                panelY + point.y() - 1,
                18,
                18
        );
    }

    private void drawAt(
            GuiGraphicsExtractor graphics,
            RecipeCardData.Slot slot,
            NativeRecipeLayout.Point point,
            int panelX,
            int panelY,
            int mouseX,
            int mouseY,
            String role
    ) {
        drawRecipeItem(graphics, slot, panelX + point.x(), panelY + point.y(), mouseX, mouseY, role);
    }

    private void drawRecipeItem(
            GuiGraphicsExtractor graphics,
            RecipeCardData.Slot slot,
            int x,
            int y,
            int mouseX,
            int mouseY,
            String role
    ) {
        boolean hovered = inside(mouseX, mouseY, x, y, SLOT_SIZE, SLOT_SIZE);
        ItemStack stack;
        if (hovered && slot.alternatives().size() > 1) {
            slotHoveredThisFrame = true;
            if (frozenSlot != slot) {
                frozenSlot = slot;
                frozenAlternativeIndex = RecipeCardData.Slot.displayIndex(
                        slot.alternatives().size(), System.currentTimeMillis()
                );
            }
            stack = slot.alternatives().get(frozenAlternativeIndex);
        } else {
            stack = slot.displayed(System.currentTimeMillis());
            if (hovered) {
                slotHoveredThisFrame = true;
            }
        }

        if (!stack.isEmpty()) {
            graphics.item(stack, x, y);
            graphics.itemDecorations(font, stack, x, y);
        }
        if (slot.alternatives().size() > 1) {
            graphics.text(font, "+", x + 10, y, 0xFFFFFF55);
        }
        if (hovered && !stack.isEmpty()) {
            if (role == null) {
                graphics.setTooltipForNextFrame(font, stack, mouseX, mouseY);
            } else {
                List<Component> tooltip = new ArrayList<>();
                tooltip.add(Component.literal(role).withStyle(ChatFormatting.GRAY));
                tooltip.addAll(Screen.getTooltipFromItem(minecraft, stack));
                graphics.setComponentTooltipForNextFrame(font, tooltip, mouseX, mouseY);
            }
        }
    }

    private static List<Component> cookingDetailTooltip(RecipeCardData.Cooking cooking) {
        return cookingDetails(cooking.durationTicks(), cooking.experience()).lines()
                .map(Component::literal)
                .map(Component.class::cast)
                .toList();
    }

    private static Component workstationName(RecipeCardData recipe) {
        RecipeCardData.Slot station = station(recipe);
        if (station != null && !station.primary().isEmpty()) {
            return station.primary().getHoverName();
        }
        return Component.literal(recipe.method().displayName());
    }

    private static RecipeCardData.Slot station(RecipeCardData recipe) {
        return switch (recipe) {
            case RecipeCardData.Cooking cooking -> cooking.station();
            case RecipeCardData.Stonecutting stonecutting -> stonecutting.station();
            case RecipeCardData.Smithing smithing -> smithing.station();
            case RecipeCardData.Crafting ignored -> null;
        };
    }

    private boolean isMultiple() {
        return presentation.cards().size() > 1;
    }

    private static boolean inside(int mouseX, int mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.gui.setScreen(null);
        }
    }

    record ShellGeometry(int left, int top, int width, int height, int panelX, int panelY) {
        boolean fitsWithin(int screenWidth, int screenHeight) {
            return left >= SCREEN_MARGIN
                    && top >= SCREEN_MARGIN
                    && left + width <= screenWidth - SCREEN_MARGIN
                    && top + height <= screenHeight - SCREEN_MARGIN;
        }
    }
}
