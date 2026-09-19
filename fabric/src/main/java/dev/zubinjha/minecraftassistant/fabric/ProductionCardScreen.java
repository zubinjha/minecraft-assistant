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

public final class ProductionCardScreen extends Screen {
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
    private static final Identifier BREWING_PROGRESS = Identifier.withDefaultNamespace(
            "container/brewing_stand/brew_progress"
    );
    static final Identifier LOOM_PATTERN_SELECTED = Identifier.withDefaultNamespace(
            "container/loom/pattern_selected"
    );
    static final Identifier CARTOGRAPHY_MAP = Identifier.withDefaultNamespace(
            "container/cartography_table/map"
    );
    static final Identifier CARTOGRAPHY_SCALED_MAP = Identifier.withDefaultNamespace(
            "container/cartography_table/scaled_map"
    );
    static final Identifier CARTOGRAPHY_DUPLICATED_MAP = Identifier.withDefaultNamespace(
            "container/cartography_table/duplicated_map"
    );
    static final Identifier CARTOGRAPHY_LOCKED = Identifier.withDefaultNamespace(
            "container/cartography_table/locked"
    );
    static final Identifier ENCHANTMENT_SLOT_DISABLED = Identifier.withDefaultNamespace(
            "container/enchanting_table/enchantment_slot_disabled"
    );

    private final ProductionPresentation presentation;
    private final List<Button> tabButtons = new ArrayList<>();
    private int selectedIndex;
    private Button previousButton;
    private Button nextButton;
    private ProductionCardData.Slot frozenSlot;
    private int frozenAlternativeIndex;
    private boolean slotHoveredThisFrame;

    ProductionCardScreen(ProductionCardData recipe) {
        this(new ProductionPresentation.Single(recipe));
    }

    ProductionCardScreen(ProductionPresentation presentation) {
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

        if (presentation instanceof ProductionPresentation.Sequence) {
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

        ProductionCardData recipe = presentation.displayedCard(selectedIndex);
        if (isMultiple()) {
            String countLabel = presentation instanceof ProductionPresentation.Sequence
                    ? presentation.cards().size() + " steps"
                    : presentation.cards().size() + (presentation.recipeOnly() ? " recipes" : " guides");
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
                        presentation instanceof ProductionPresentation.Sequence ? "→" : "·",
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
            ProductionCardData recipe,
            int panelX,
            int panelY,
            int mouseX,
            int mouseY
    ) {
        NativeProductionLayout layout = NativeProductionLayout.forMethod(recipe.method());
        if (layout.hasNativeTexture()) {
            graphics.blit(
                    RenderPipelines.GUI_TEXTURED,
                    layout.texture(),
                    panelX,
                    panelY,
                    0.0F,
                    0.0F,
                    NativeProductionLayout.PANEL_WIDTH,
                    NativeProductionLayout.PANEL_HEIGHT,
                    NATIVE_TEXTURE_SIZE,
                    NATIVE_TEXTURE_SIZE
            );
        } else {
            graphics.blitSprite(
                    RenderPipelines.GUI_TEXTURED,
                    POPUP_BACKGROUND,
                    panelX,
                    panelY,
                    NativeProductionLayout.PANEL_WIDTH,
                    NativeProductionLayout.PANEL_HEIGHT
            );
        }

        drawWorkstationTitle(graphics, recipe, layout, panelX, panelY);
        switch (recipe) {
            case ProductionCardData.Crafting crafting -> drawCrafting(
                    graphics, crafting, panelX, panelY, mouseX, mouseY
            );
            case ProductionCardData.Cooking cooking when layout.kind() == NativeProductionLayout.Kind.CAMPFIRE ->
                    drawCampfire(graphics, cooking, panelX, panelY, mouseX, mouseY);
            case ProductionCardData.Cooking cooking -> drawCooking(
                    graphics, cooking, layout, panelX, panelY, mouseX, mouseY
            );
            case ProductionCardData.Stonecutting stonecutting -> drawStonecutting(
                    graphics, stonecutting, panelX, panelY, mouseX, mouseY
            );
            case ProductionCardData.Smithing smithing -> drawSmithing(
                    graphics, smithing, panelX, panelY, mouseX, mouseY
            );
            case ProductionCardData.Brewing brewing -> drawBrewing(
                    graphics, brewing, panelX, panelY, mouseX, mouseY
            );
            case ProductionCardData.Loom loom -> drawLoom(
                    graphics, loom, panelX, panelY, mouseX, mouseY
            );
            case ProductionCardData.Cartography cartography -> drawCartography(
                    graphics, cartography, panelX, panelY, mouseX, mouseY
            );
            case ProductionCardData.Enchanting enchanting -> drawEnchanting(
                    graphics, enchanting, panelX, panelY, mouseX, mouseY
            );
            case ProductionCardData.Anvil anvil -> drawAnvil(
                    graphics, anvil, panelX, panelY, mouseX, mouseY
            );
            case ProductionCardData.Grindstone grindstone -> drawGrindstone(
                    graphics, grindstone, panelX, panelY, mouseX, mouseY
            );
        }
    }

    private void drawWorkstationTitle(
            GuiGraphicsExtractor graphics,
            ProductionCardData recipe,
            NativeProductionLayout layout,
            int panelX,
            int panelY
    ) {
        Component name = workstationName(recipe);
        int color = layout.kind() == NativeProductionLayout.Kind.CAMPFIRE ? 0xFFFFFFFF : 0xFF404040;
        int y = panelY + switch (layout.kind()) {
            case SMITHING -> 15;
            case CARTOGRAPHY -> 4;
            default -> 6;
        };
        int x = switch (layout.kind()) {
            case CRAFTING -> panelX + 29;
            case COOKING -> panelX + (NativeProductionLayout.PANEL_WIDTH - font.width(name)) / 2;
            case STONECUTTING -> panelX + 8;
            case SMITHING -> panelX + 44;
            case CAMPFIRE -> panelX + 8;
            case CARTOGRAPHY -> panelX + 8;
            case BREWING, LOOM, ENCHANTING, ANVIL, GRINDSTONE ->
                    panelX + (NativeProductionLayout.PANEL_WIDTH - font.width(name)) / 2;
        };
        graphics.text(font, name, x, y, color, false);
        if (recipe instanceof ProductionCardData.Crafting crafting && crafting.shapeless()) {
            String label = "Shapeless";
            graphics.text(
                    font,
                    label,
                    panelX + NativeProductionLayout.PANEL_WIDTH - 8 - font.width(label),
                    panelY + 6,
                    0xFF606060,
                    false
            );
        }
    }

    private void drawCrafting(
            GuiGraphicsExtractor graphics,
            ProductionCardData.Crafting crafting,
            int panelX,
            int panelY,
            int mouseX,
            int mouseY
    ) {
        NativeProductionLayout.Point origin = NativeProductionLayout.CRAFTING_GRID;
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
        drawAt(graphics, crafting.result(), NativeProductionLayout.CRAFTING_RESULT, panelX, panelY, mouseX, mouseY, null);
    }

    private void drawCooking(
            GuiGraphicsExtractor graphics,
            ProductionCardData.Cooking cooking,
            NativeProductionLayout layout,
            int panelX,
            int panelY,
            int mouseX,
            int mouseY
    ) {
        drawAt(graphics, cooking.input(), NativeProductionLayout.COOKING_INPUT, panelX, panelY, mouseX, mouseY, null);
        drawAt(graphics, cooking.result(), NativeProductionLayout.COOKING_RESULT, panelX, panelY, mouseX, mouseY, null);
        graphics.blitSprite(
                RenderPipelines.GUI_TEXTURED,
                layout.litProgressSprite(),
                panelX + NativeProductionLayout.COOKING_FLAME.x(),
                panelY + NativeProductionLayout.COOKING_FLAME.y(),
                14,
                14
        );
        graphics.blitSprite(
                RenderPipelines.GUI_TEXTURED,
                layout.burnProgressSprite(),
                panelX + NativeProductionLayout.COOKING_PROGRESS.x(),
                panelY + NativeProductionLayout.COOKING_PROGRESS.y(),
                24,
                16
        );

        int fuelX = panelX + NativeProductionLayout.COOKING_FUEL.x();
        int fuelY = panelY + NativeProductionLayout.COOKING_FUEL.y();
        int flameX = panelX + NativeProductionLayout.COOKING_FLAME.x();
        int flameY = panelY + NativeProductionLayout.COOKING_FLAME.y();
        if (inside(mouseX, mouseY, fuelX, fuelY, SLOT_SIZE, SLOT_SIZE)
                || inside(mouseX, mouseY, flameX, flameY, 14, 14)) {
            graphics.setTooltipForNextFrame(font, Component.literal("Any valid fuel"), mouseX, mouseY);
        }
        int progressX = panelX + NativeProductionLayout.COOKING_PROGRESS.x();
        int progressY = panelY + NativeProductionLayout.COOKING_PROGRESS.y();
        if (inside(mouseX, mouseY, progressX, progressY, 24, 16)) {
            graphics.setComponentTooltipForNextFrame(font, cookingDetailTooltip(cooking), mouseX, mouseY);
        }
    }

    private void drawStonecutting(
            GuiGraphicsExtractor graphics,
            ProductionCardData.Stonecutting stonecutting,
            int panelX,
            int panelY,
            int mouseX,
            int mouseY
    ) {
        drawAt(graphics, stonecutting.input(), NativeProductionLayout.STONECUTTER_INPUT, panelX, panelY, mouseX, mouseY, null);
        graphics.blitSprite(
                RenderPipelines.GUI_TEXTURED,
                STONECUTTER_SELECTED,
                panelX + NativeProductionLayout.STONECUTTER_CHOICE_BACKGROUND.x(),
                panelY + NativeProductionLayout.STONECUTTER_CHOICE_BACKGROUND.y(),
                16,
                18
        );
        drawAt(graphics, stonecutting.result(), NativeProductionLayout.STONECUTTER_CHOICE, panelX, panelY, mouseX, mouseY, null);
        drawAt(graphics, stonecutting.result(), NativeProductionLayout.STONECUTTER_RESULT, panelX, panelY, mouseX, mouseY, null);
    }

    private void drawSmithing(
            GuiGraphicsExtractor graphics,
            ProductionCardData.Smithing smithing,
            int panelX,
            int panelY,
            int mouseX,
            int mouseY
    ) {
        drawAt(graphics, smithing.template(), NativeProductionLayout.SMITHING_TEMPLATE, panelX, panelY, mouseX, mouseY, "Template");
        drawAt(graphics, smithing.base(), NativeProductionLayout.SMITHING_BASE, panelX, panelY, mouseX, mouseY, "Base");
        drawAt(graphics, smithing.addition(), NativeProductionLayout.SMITHING_ADDITION, panelX, panelY, mouseX, mouseY, "Addition");
        drawAt(graphics, smithing.result(), NativeProductionLayout.SMITHING_RESULT, panelX, panelY, mouseX, mouseY, "Result");
    }

    private void drawCampfire(
            GuiGraphicsExtractor graphics,
            ProductionCardData.Cooking cooking,
            int panelX,
            int panelY,
            int mouseX,
            int mouseY
    ) {
        drawStandaloneSlot(graphics, panelX, panelY, NativeProductionLayout.CAMPFIRE_INPUT);
        drawStandaloneSlot(graphics, panelX, panelY, NativeProductionLayout.CAMPFIRE_RESULT);
        drawAt(graphics, cooking.input(), NativeProductionLayout.CAMPFIRE_INPUT, panelX, panelY, mouseX, mouseY, "Input");
        drawAt(graphics, cooking.station(), NativeProductionLayout.CAMPFIRE_STATION, panelX, panelY, mouseX, mouseY, "Campfire");
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, CAMPFIRE_PROGRESS, panelX + 98, panelY + 36, 24, 16);
        drawAt(graphics, cooking.result(), NativeProductionLayout.CAMPFIRE_RESULT, panelX, panelY, mouseX, mouseY, "Result");
        if (inside(mouseX, mouseY, panelX + 98, panelY + 36, 24, 16)) {
            graphics.setComponentTooltipForNextFrame(font, cookingDetailTooltip(cooking), mouseX, mouseY);
        }
    }

    private void drawBrewing(
            GuiGraphicsExtractor graphics,
            ProductionCardData.Brewing brewing,
            int panelX,
            int panelY,
            int mouseX,
            int mouseY
    ) {
        drawAt(graphics, brewing.fuel(), NativeProductionLayout.BREWING_FUEL,
                panelX, panelY, mouseX, mouseY, "Fuel");
        drawAt(graphics, brewing.ingredient(), NativeProductionLayout.BREWING_INGREDIENT,
                panelX, panelY, mouseX, mouseY, "Ingredient");
        drawAt(graphics, brewing.input(), NativeProductionLayout.BREWING_INPUT,
                panelX, panelY, mouseX, mouseY, "Input");
        drawAt(graphics, brewing.result(), NativeProductionLayout.BREWING_RESULT,
                panelX, panelY, mouseX, mouseY, "Result");
        graphics.blitSprite(
                RenderPipelines.GUI_TEXTURED,
                BREWING_PROGRESS,
                panelX + 97,
                panelY + 17,
                9,
                28
        );
    }

    private void drawLoom(
            GuiGraphicsExtractor graphics,
            ProductionCardData.Loom loom,
            int panelX,
            int panelY,
            int mouseX,
            int mouseY
    ) {
        drawAt(graphics, loom.banner(), NativeProductionLayout.LOOM_BANNER,
                panelX, panelY, mouseX, mouseY, "Banner");
        drawAt(graphics, loom.dye(), NativeProductionLayout.LOOM_DYE,
                panelX, panelY, mouseX, mouseY, "Dye");
        drawAt(graphics, loom.patternItem(), NativeProductionLayout.LOOM_PATTERN_ITEM,
                panelX, panelY, mouseX, mouseY, "Pattern item (when required)");
        graphics.blitSprite(
                RenderPipelines.GUI_TEXTURED,
                LOOM_PATTERN_SELECTED,
                panelX + 60,
                panelY + 13,
                14,
                14
        );
        drawRecipeItem(
                graphics,
                loom.result(),
                panelX + 59,
                panelY + 12,
                mouseX,
                mouseY,
                "Selected pattern: " + loom.patternName().getString()
        );
        drawAt(graphics, loom.result(), NativeProductionLayout.LOOM_RESULT,
                panelX, panelY, mouseX, mouseY, "Result: " + loom.patternName().getString());
    }

    private void drawCartography(
            GuiGraphicsExtractor graphics,
            ProductionCardData.Cartography cartography,
            int panelX,
            int panelY,
            int mouseX,
            int mouseY
    ) {
        switch (cartography.operation()) {
            case "scale" -> graphics.blitSprite(
                    RenderPipelines.GUI_TEXTURED,
                    CARTOGRAPHY_SCALED_MAP,
                    panelX + 67,
                    panelY + 13,
                    66,
                    66
            );
            case "clone" -> graphics.blitSprite(
                    RenderPipelines.GUI_TEXTURED,
                    CARTOGRAPHY_DUPLICATED_MAP,
                    panelX + 83,
                    panelY + 13,
                    50,
                    66
            );
            case "lock" -> {
                graphics.blitSprite(
                        RenderPipelines.GUI_TEXTURED,
                        CARTOGRAPHY_MAP,
                        panelX + 67,
                        panelY + 13,
                        66,
                        66
                );
                graphics.blitSprite(
                        RenderPipelines.GUI_TEXTURED,
                        CARTOGRAPHY_LOCKED,
                        panelX + 118,
                        panelY + 60,
                        10,
                        14
                );
            }
            default -> { }
        }
        drawAt(graphics, cartography.input(), NativeProductionLayout.CARTOGRAPHY_INPUT,
                panelX, panelY, mouseX, mouseY, "Map");
        drawAt(graphics, cartography.addition(), NativeProductionLayout.CARTOGRAPHY_ADDITION,
                panelX, panelY, mouseX, mouseY, "Addition");
        drawAt(graphics, cartography.result(), NativeProductionLayout.CARTOGRAPHY_RESULT,
                panelX, panelY, mouseX, mouseY, "Result");
    }

    private void drawEnchanting(
            GuiGraphicsExtractor graphics,
            ProductionCardData.Enchanting enchanting,
            int panelX,
            int panelY,
            int mouseX,
            int mouseY
    ) {
        for (int index = 0; index < 3; index++) {
            graphics.blitSprite(
                    RenderPipelines.GUI_TEXTURED,
                    ENCHANTMENT_SLOT_DISABLED,
                    panelX + 60,
                    panelY + 14 + index * 19,
                    108,
                    19
            );
        }
        drawAt(graphics, enchanting.item(), NativeProductionLayout.ENCHANTING_ITEM,
                panelX, panelY, mouseX, mouseY, "Item");
        drawAt(graphics, enchanting.lapis(), NativeProductionLayout.ENCHANTING_LAPIS,
                panelX, panelY, mouseX, mouseY, "Lapis Lazuli");
        if (inside(mouseX, mouseY, panelX + 60, panelY + 14, 108, 57)) {
            graphics.setTooltipForNextFrame(font, Component.literal(enchanting.details()), mouseX, mouseY);
        }
    }

    private void drawAnvil(
            GuiGraphicsExtractor graphics,
            ProductionCardData.Anvil anvil,
            int panelX,
            int panelY,
            int mouseX,
            int mouseY
    ) {
        int nameX = panelX + NativeProductionLayout.ANVIL_NAME_FIELD.x();
        int nameY = panelY + NativeProductionLayout.ANVIL_NAME_FIELD.y();
        graphics.fill(
                nameX,
                nameY,
                nameX + NativeProductionLayout.ANVIL_NAME_FIELD_WIDTH,
                nameY + NativeProductionLayout.ANVIL_NAME_FIELD_HEIGHT,
                0xFF000000
        );
        String resultName = anvil.result().primary().getHoverName().getString();
        graphics.text(
                font,
                font.plainSubstrByWidth(resultName, NativeProductionLayout.ANVIL_NAME_FIELD_WIDTH - 6),
                nameX + 3,
                nameY + 2,
                0xFFFFFFFF,
                false
        );
        drawAt(graphics, anvil.base(), NativeProductionLayout.ANVIL_BASE,
                panelX, panelY, mouseX, mouseY, "Base");
        drawAt(graphics, anvil.addition(), NativeProductionLayout.ANVIL_ADDITION,
                panelX, panelY, mouseX, mouseY, "Addition");
        drawAt(graphics, anvil.result(), NativeProductionLayout.ANVIL_RESULT,
                panelX, panelY, mouseX, mouseY, "Result");
        if (inside(mouseX, mouseY, panelX + 95, panelY + 42, 28, 22)) {
            graphics.setTooltipForNextFrame(font, Component.literal(anvil.details()), mouseX, mouseY);
        }
    }

    private void drawGrindstone(
            GuiGraphicsExtractor graphics,
            ProductionCardData.Grindstone grindstone,
            int panelX,
            int panelY,
            int mouseX,
            int mouseY
    ) {
        drawAt(graphics, grindstone.input(), NativeProductionLayout.GRINDSTONE_INPUT,
                panelX, panelY, mouseX, mouseY, "Input");
        drawAt(graphics, grindstone.addition(), NativeProductionLayout.GRINDSTONE_ADDITION,
                panelX, panelY, mouseX, mouseY, "Second item (repair only)");
        drawAt(graphics, grindstone.result(), NativeProductionLayout.GRINDSTONE_RESULT,
                panelX, panelY, mouseX, mouseY, "Result");
        if (inside(mouseX, mouseY, panelX + 92, panelY + 28, 27, 27)) {
            graphics.setTooltipForNextFrame(font, Component.literal(grindstone.details()), mouseX, mouseY);
        }
    }

    private void drawStandaloneSlot(
            GuiGraphicsExtractor graphics,
            int panelX,
            int panelY,
            NativeProductionLayout.Point point
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
            ProductionCardData.Slot slot,
            NativeProductionLayout.Point point,
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
            ProductionCardData.Slot slot,
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
                frozenAlternativeIndex = ProductionCardData.Slot.displayIndex(
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

    private static List<Component> cookingDetailTooltip(ProductionCardData.Cooking cooking) {
        return cookingDetails(cooking.durationTicks(), cooking.experience()).lines()
                .map(Component::literal)
                .map(Component.class::cast)
                .toList();
    }

    private static Component workstationName(ProductionCardData recipe) {
        ProductionCardData.Slot station = station(recipe);
        if (station != null && !station.primary().isEmpty()) {
            return station.primary().getHoverName();
        }
        return Component.literal(recipe.method().displayName());
    }

    private static ProductionCardData.Slot station(ProductionCardData recipe) {
        return switch (recipe) {
            case ProductionCardData.Cooking cooking -> cooking.station();
            case ProductionCardData.Stonecutting stonecutting -> stonecutting.station();
            case ProductionCardData.Smithing smithing -> smithing.station();
            case ProductionCardData.Brewing brewing -> brewing.station();
            case ProductionCardData.Loom loom -> loom.station();
            case ProductionCardData.Cartography cartography -> cartography.station();
            case ProductionCardData.Enchanting enchanting -> enchanting.station();
            case ProductionCardData.Anvil anvil -> anvil.station();
            case ProductionCardData.Grindstone grindstone -> grindstone.station();
            case ProductionCardData.Crafting ignored -> null;
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
