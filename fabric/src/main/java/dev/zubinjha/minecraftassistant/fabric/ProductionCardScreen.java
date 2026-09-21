package dev.zubinjha.minecraftassistant.fabric;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

public final class ProductionCardScreen extends Screen {
    private static final int SCREEN_MARGIN = 8;
    private static final int SHELL_WIDTH = 196;
    private static final int SINGLE_HEIGHT = 160;
    private static final int SEQUENCE_HEIGHT = 180;
    private static final int MULTI_HEIGHT = 222;
    private static final int PLAN_SINGLE_HEIGHT = 144;
    private static final int PLAN_STEPS_HEIGHT = 160;
    private static final int COMPARISON_SINGLE_HEIGHT = 162;
    private static final int COMPARISON_STEPS_HEIGHT = 180;
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
    private final List<Button> routeButtons = new ArrayList<>();
    private int selectedIndex;
    private int selectedRouteIndex;
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
        selectedIndex = selectedIndex(selectedIndex, index, activeCards().size());
        updateNavigation();
    }

    void selectRoute(int index) {
        if (!(presentation instanceof ProductionPresentation.Comparison comparison)
                || index < 0 || index >= comparison.routes().size() || index == selectedRouteIndex) {
            return;
        }
        selectedRouteIndex = index;
        selectedIndex = 0;
        rebuildWidgets();
    }

    static int selectedIndex(int current, int requested, int cardCount) {
        return requested >= 0 && requested < cardCount ? requested : current;
    }

    static boolean showsStepSelector(int operationCount) {
        return operationCount > 1;
    }

    static ShellGeometry shellGeometry(int screenWidth, int screenHeight, boolean multipleCards) {
        return shellGeometry(screenWidth, screenHeight, multipleCards, false);
    }

    static ShellGeometry shellGeometry(
            int screenWidth,
            int screenHeight,
            boolean multipleCards,
            boolean comparison
    ) {
        int shellHeight = multipleCards ? MULTI_HEIGHT : SINGLE_HEIGHT;
        int left = Math.max(SCREEN_MARGIN, (screenWidth - SHELL_WIDTH) / 2);
        int top = Math.max(SCREEN_MARGIN, (screenHeight - shellHeight) / 2);
        int panelY = top + (multipleCards ? 110 : 37);
        return new ShellGeometry(left, top, SHELL_WIDTH, shellHeight, left + 10, panelY);
    }

    static ShellGeometry planShellGeometry(
            int screenWidth,
            int screenHeight,
            boolean comparison,
            boolean multipleSteps
    ) {
        int shellHeight = comparison
                ? multipleSteps ? COMPARISON_STEPS_HEIGHT : COMPARISON_SINGLE_HEIGHT
                : multipleSteps ? PLAN_STEPS_HEIGHT : PLAN_SINGLE_HEIGHT;
        int left = Math.max(SCREEN_MARGIN, (screenWidth - SHELL_WIDTH) / 2);
        int top = Math.max(SCREEN_MARGIN, (screenHeight - shellHeight) / 2);
        int panelOffset = comparison
                ? multipleSteps ? 62 : 42
                : multipleSteps ? 40 : 22;
        return new ShellGeometry(left, top, SHELL_WIDTH, shellHeight, left + 10, top + panelOffset);
    }

    static ShellGeometry sequenceShellGeometry(int screenWidth, int screenHeight) {
        int left = Math.max(SCREEN_MARGIN, (screenWidth - SHELL_WIDTH) / 2);
        int top = Math.max(SCREEN_MARGIN, (screenHeight - SEQUENCE_HEIGHT) / 2);
        return new ShellGeometry(left, top, SHELL_WIDTH, SEQUENCE_HEIGHT, left + 10, top + 64);
    }

    static String cookingDetails(int durationTicks, float experience) {
        return String.format(
                Locale.ROOT,
                "Cooking time: %.1f seconds\nExperience: %.1f",
                durationTicks / 20.0,
                experience
        );
    }

    private ShellGeometry currentShellGeometry() {
        if (isPlan()) {
            return planShellGeometry(width, height, isComparison(), showsStepSelector(activeCards().size()));
        }
        if (presentation instanceof ProductionPresentation.Sequence) {
            return sequenceShellGeometry(width, height);
        }
        return shellGeometry(width, height, isMultiple(), false);
    }

    @Override
    protected void init() {
        tabButtons.clear();
        routeButtons.clear();
        ShellGeometry shell = currentShellGeometry();
        int footerY = shell.top() + shell.height() - 28;

        if (presentation instanceof ProductionPresentation.Comparison comparison) {
            int count = comparison.routes().size();
            int gap = 3;
            int tabWidth = (shell.width() - 12 - (count - 1) * gap) / count;
            int startX = shell.left() + 6;
            for (int index = 0; index < count; index++) {
                int routeIndex = index;
                ProductionPresentation.Route route = comparison.routes().get(index);
                Button tab = Button.builder(
                                Component.literal(font.plainSubstrByWidth(
                                        routeButtonLabel(route, index == selectedRouteIndex), tabWidth - 6)),
                                button -> selectRoute(routeIndex)
                        )
                        .tooltip(Tooltip.create(routeTooltip(route)))
                        .bounds(startX + index * (tabWidth + gap), shell.top() + 19, tabWidth, 18)
                        .build();
                routeButtons.add(tab);
                addRenderableWidget(tab);
            }
        }

        if (showsStepSelector(activeCards().size())) {
            int count = activeCards().size();
            int tabWidth = 22;
            int gap = 3;
            int totalWidth = count * tabWidth + (count - 1) * gap;
            int startX = shell.left() + (shell.width() - totalWidth) / 2;
            for (int index = 0; index < count; index++) {
                int cardIndex = index;
                Button.Builder builder = Button.builder(Component.literal(Integer.toString(index + 1)), button ->
                        selectCard(cardIndex));
                if (isPlan()) {
                    builder.tooltip(Tooltip.create(operationTooltipComponent(
                            activeQuantityPlan().orElseThrow(),
                            activeQuantityPlan().orElseThrow().operations().get(index)
                    )));
                } else if (presentation instanceof ProductionPresentation.Sequence) {
                    builder.tooltip(Tooltip.create(Component.literal(
                            ProductionStepLabel.describe(index, activeCards())
                    )));
                }
                int tabY = isPlan()
                        ? shell.top() + (isComparison() ? 42 : 20)
                        : presentation instanceof ProductionPresentation.Sequence
                        ? shell.top() + 32
                        : shell.top() + 51;
                Button tab = builder.bounds(startX + index * (tabWidth + gap), tabY, tabWidth, 16).build();
                tabButtons.add(tab);
                addRenderableWidget(tab);
            }
        }

        addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
                .bounds(shell.left() + 48, footerY, 100, 20)
                .build());
        updateNavigation();
    }

    private void updateNavigation() {
        for (int index = 0; index < routeButtons.size(); index++) {
            Button route = routeButtons.get(index);
            route.active = true;
            ProductionPresentation.Route value = comparison().routes().get(index);
            int availableWidth = route.getWidth() - 6;
            route.setMessage(Component.literal(font.plainSubstrByWidth(
                    routeButtonLabel(value, index == selectedRouteIndex), availableWidth
            )));
        }
        for (int index = 0; index < tabButtons.size(); index++) {
            Button tab = tabButtons.get(index);
            tab.active = true;
            tab.setMessage(Component.literal(index == selectedIndex
                    ? "[" + (index + 1) + "]"
                    : Integer.toString(index + 1)));
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, 0x88000000);
        ShellGeometry shell = currentShellGeometry();
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

        ProductionCardData recipe = activeCards().get(selectedIndex);
        Optional<ProductionPlan> quantityPlan = activeQuantityPlan();
        if (quantityPlan.isPresent()) {
            graphics.centeredText(font, presentation.targetTitle(), width / 2, shell.top() + 6, 0xFFFFFFFF);
        } else if (presentation instanceof ProductionPresentation.Sequence sequence) {
            graphics.centeredText(font, presentation.targetTitle(), width / 2, shell.top() + 6, 0xFFFFFFFF);
            graphics.centeredText(
                    font,
                    sequence.cards().size() + " steps",
                    width / 2,
                    shell.top() + 18,
                    0xFFAAAAAA
            );
            String stepLabel = ProductionStepLabel.describe(selectedIndex, activeCards());
            String visibleLabel = font.plainSubstrByWidth(stepLabel, shell.width() - 16);
            graphics.centeredText(font, visibleLabel, width / 2, shell.top() + 51, 0xFFFFFFFF);
        } else if (isMultiple()) {
            String countLabel = isComparison()
                    ? comparison().routes().size() + " routes"
                    : activeCards().size() + (presentation.recipeOnly() ? " recipes" : " guides");
            graphics.centeredText(font, presentation.targetTitle(), width / 2, shell.top() + 6, 0xFFFFFFFF);
            graphics.centeredText(font, countLabel, width / 2, shell.top() + 18, 0xFFAAAAAA);
            int flowY = shell.top() + (isComparison() ? 52 : 31);
            drawPresentationFlow(graphics, shell, flowY, mouseX, mouseY);
            if (!isComparison()) {
                int titleY = shell.top() + 72;
                graphics.centeredText(font, recipe.title(), width / 2, titleY, 0xFFFFFFFF);
            }
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

    static String planHeadline(ProductionPlan plan) {
        StringBuilder result = new StringBuilder();
        List<ProductionPlan.Material> materials = plan.orderedRootMaterials();
        int shown = Math.min(3, materials.size());
        for (int index = 0; index < shown; index++) {
            if (index > 0) {
                result.append(" + ");
            }
            ProductionPlan.Material material = materials.get(index);
            result.append(ProductionPlan.count(material.count())).append(' ')
                    .append(ProductionPlan.quantityName(material.name(), material.count()));
        }
        if (materials.size() > shown) {
            result.append(" + ").append(materials.size() - shown).append(" materials");
        }
        result.append(" → ").append(ProductionPlan.count(plan.requestedCount()))
                .append(' ').append(ProductionPlan.quantityName(plan.targetName(), plan.requestedCount()));
        if (plan.producedCount() > plan.requestedCount()) {
            result.append(" (makes ").append(ProductionPlan.count(plan.producedCount())).append(')');
        }
        return result.toString();
    }

    private static List<Component> planTooltip(ProductionPlan plan) {
        List<Component> tooltip = new ArrayList<>();
        tooltip.add(Component.literal("Required materials").withStyle(ChatFormatting.WHITE));
        for (ProductionPlan.Material material : plan.rootMaterials()) {
            tooltip.add(Component.literal(ProductionPlan.count(material.count()) + " " + material.name())
                    .withStyle(ChatFormatting.GRAY));
        }
        tooltip.add(Component.literal("Requested: " + ProductionPlan.count(plan.requestedCount())
                + " " + plan.targetName()).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("Produced: " + ProductionPlan.count(plan.producedCount())
                + " " + plan.targetName()).withStyle(ChatFormatting.GRAY));
        for (ProductionPlan.Material leftover : plan.leftovers()) {
            String label = leftover.itemId().equals(plan.targetItemId()) ? "Extra: " : "Left over: ";
            tooltip.add(Component.literal(label + ProductionPlan.count(leftover.count()) + " " + leftover.name())
                    .withStyle(ChatFormatting.YELLOW));
        }
        if (plan.genericFuelOmitted()) {
            tooltip.add(Component.literal("Generic fuel is not included").withStyle(ChatFormatting.GRAY));
        }
        return tooltip;
    }

    private static List<Component> operationTooltip(ProductionPlan plan, ProductionPlan.Operation operation) {
        List<Component> tooltip = new ArrayList<>();
        tooltip.add(Component.literal(operation.method().displayName()).withStyle(ChatFormatting.WHITE));
        tooltip.add(Component.literal(stepDetails(operation)).withStyle(ChatFormatting.GRAY));
        for (ProductionPlan.Material input : operation.inputs()) {
            tooltip.add(Component.literal(ProductionPlan.count(input.count()) + " " + input.name() + " in")
                    .withStyle(ChatFormatting.GRAY));
        }
        tooltip.add(Component.literal(ProductionPlan.count(operation.outputProduced()) + " "
                + operation.output().name() + " out").withStyle(ChatFormatting.GRAY));
        if (operation.surplus() > 0) {
            tooltip.add(Component.literal(ProductionPlan.count(operation.surplus()) + " surplus at this operation")
                    .withStyle(ChatFormatting.YELLOW));
        }
        if (plan.genericFuelOmitted()
                && switch (operation.method()) {
                    case SMELTING, BLASTING, SMOKING -> true;
                    default -> false;
                }) {
            tooltip.add(Component.literal("Generic fuel excluded").withStyle(ChatFormatting.GRAY));
        }
        return tooltip;
    }

    private static Component operationTooltipComponent(
            ProductionPlan plan,
            ProductionPlan.Operation operation
    ) {
        return tooltipComponent(operationTooltip(plan, operation));
    }

    private static Component tooltipComponent(List<Component> lines) {
        return Component.literal(lines.stream().map(Component::getString)
                .reduce((left, right) -> left + "\n" + right).orElse(""));
    }

    private void drawPresentationFlow(
            GuiGraphicsExtractor graphics,
            ShellGeometry shell,
            int y,
            int mouseX,
            int mouseY
    ) {
        List<ProductionCardData> cards = activeCards();
        int count = cards.size();
        int separatorWidth = 13;
        int totalWidth = count * SLOT_SIZE + (count - 1) * separatorWidth;
        int x = shell.left() + (shell.width() - totalWidth) / 2;
        for (int index = 0; index < count; index++) {
            ItemStack result = cards.get(index).result().primary();
            if (!result.isEmpty()) {
                graphics.item(result, x, y);
                Optional<ProductionPlan> quantityPlan = activeQuantityPlan();
                if (quantityPlan.isPresent()) {
                    long scaled = quantityPlan.get().operations().get(index).outputProduced();
                    String label = compactCount(scaled);
                    graphics.text(font, label, x + 17 - font.width(label), y + 9, 0xFFFFFFFF, true);
                } else {
                    graphics.itemDecorations(font, result, x, y);
                }
                if (inside(mouseX, mouseY, x, y, SLOT_SIZE, SLOT_SIZE)) {
                    if (quantityPlan.isPresent()) {
                        graphics.setComponentTooltipForNextFrame(
                                font,
                                quantityTooltip(result, quantityPlan.get(), index),
                                mouseX,
                                mouseY
                        );
                    } else {
                        graphics.setTooltipForNextFrame(font, result, mouseX, mouseY);
                    }
                }
            }
            x += SLOT_SIZE;
            if (index < count - 1) {
                graphics.text(
                        font,
                        isComparison() ? "→" : "·",
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

    static String stepDetails(ProductionPlan.Operation step) {
        return ProductionPlan.count(step.batches()) + " craft" + (step.batches() == 1 ? "" : "s")
                + " · " + ProductionPlan.count(step.consumedInput()) + " in > "
                + ProductionPlan.count(step.outputProduced()) + " out"
                + (step.surplus() > 0 ? " · " + ProductionPlan.count(step.surplus()) + " surplus" : "");
    }

    static String compactCount(long count) {
        if (count < 1_000) {
            return Long.toString(count);
        }
        if (count < 1_000_000) {
            return String.format(Locale.ROOT, count < 10_000 ? "%.1fk" : "%.0fk", count / 1_000.0);
        }
        return String.format(Locale.ROOT, count < 10_000_000 ? "%.1fM" : "%.0fM", count / 1_000_000.0);
    }

    private static List<Component> quantityTooltip(
            ItemStack result,
            ProductionPlan plan,
            int stepIndex
    ) {
        ProductionPlan.Operation step = plan.operations().get(stepIndex);
        List<Component> tooltip = new ArrayList<>();
        tooltip.add(result.getHoverName());
        tooltip.add(Component.literal("Batches: " + ProductionPlan.count(step.batches()))
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("Consumed inputs: " + ProductionPlan.count(step.consumedInput()))
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("Produced: " + ProductionPlan.count(step.outputProduced()))
                .withStyle(ChatFormatting.GRAY));
        if (step.surplus() > 0) {
            tooltip.add(Component.literal("Surplus: " + ProductionPlan.count(step.surplus()))
                    .withStyle(ChatFormatting.YELLOW));
        }
        for (ProductionPlan.Material material : step.inputs()) {
            String alternatives = material.alternatives().size() > 1 ? " (compatible alternatives)" : "";
            tooltip.add(Component.literal(ProductionPlan.count(material.count()) + " "
                    + material.name() + alternatives).withStyle(ChatFormatting.GRAY));
        }
        if (plan.genericFuelOmitted()
                && presentationStepUsesGenericFuel(plan, stepIndex)) {
            tooltip.add(Component.literal("Generic fuel excluded").withStyle(ChatFormatting.GRAY));
        }
        return tooltip;
    }

    private static boolean presentationStepUsesGenericFuel(ProductionPlan plan, int stepIndex) {
        return plan.genericFuelOmitted() && plan.operations().get(stepIndex).method() != ProductionMethod.CRAFTING
                && switch (plan.operations().get(stepIndex).method()) {
                    case SMELTING, BLASTING, SMOKING -> true;
                    default -> false;
                };
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
        return isPlan() || activeCards().size() > 1;
    }

    private boolean isPlan() {
        return presentation instanceof ProductionPresentation.Plan || isComparison();
    }

    private boolean isComparison() {
        return presentation instanceof ProductionPresentation.Comparison;
    }

    private ProductionPresentation.Comparison comparison() {
        return (ProductionPresentation.Comparison) presentation;
    }

    private List<ProductionCardData> activeCards() {
        if (presentation instanceof ProductionPresentation.Comparison comparison) {
            return comparison.routes().get(selectedRouteIndex).cards();
        }
        return presentation.cards();
    }

    private Optional<ProductionPlan> activeQuantityPlan() {
        if (presentation instanceof ProductionPresentation.Comparison comparison) {
            return Optional.of(comparison.routes().get(selectedRouteIndex).plan());
        }
        if (presentation instanceof ProductionPresentation.Plan plan) {
            return Optional.of(plan.plan());
        }
        return Optional.empty();
    }

    static String routeTabLabel(ProductionPresentation.Route route) {
        ProductionPlan plan = route.plan();
        ProductionPlan.Material source = plan.sourceMaterial().orElse(plan.orderedRootMaterials().getFirst());
        String additional = plan.rootMaterials().size() > 1 ? " +" + (plan.rootMaterials().size() - 1) : "";
        return compactRouteLabel(route.label()) + " · " + ProductionPlan.count(source.count()) + additional;
    }

    private static String compactRouteLabel(String label) {
        return switch (label) {
            case "Stonecutting" -> "Cutter";
            case "Smelting" -> "Furnace";
            case "Blasting" -> "Blast";
            case "Smoking" -> "Smoker";
            case "Campfire Cooking" -> "Campfire";
            default -> label;
        };
    }

    static String routeButtonLabel(ProductionPresentation.Route route, boolean selected) {
        String label = routeTabLabel(route);
        return selected ? "[" + label + "]" : label;
    }

    private static Component routeTooltip(ProductionPresentation.Route route) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(route.label()).withStyle(ChatFormatting.WHITE));
        lines.addAll(planTooltip(route.plan()));
        lines.add(Component.literal(route.plan().operations().size() + " step"
                + (route.plan().operations().size() == 1 ? "" : "s")).withStyle(ChatFormatting.GRAY));
        return tooltipComponent(lines);
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
