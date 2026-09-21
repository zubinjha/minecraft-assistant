package dev.zubinjha.minecraftassistant.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FilterMask;
import net.minecraft.network.chat.MessageSignature;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

final class SharedResponseMessagesTest {
    @Test
    void shareStateKeepsTheLatestSuccessUntilExplicitlyCleared() {
        SharedResponseState state = new SharedResponseState();
        SharedResponse first = new SharedResponse("First answer.", Optional.empty(), Optional.empty());
        SharedResponse second = new SharedResponse("Second answer.", Optional.empty(), Optional.empty());

        assertTrue(state.latest().isEmpty());
        state.update(first);
        assertEquals(first, state.latest().orElseThrow());
        assertEquals(first, state.latest().orElseThrow());
        state.update(second);
        assertEquals(second, state.latest().orElseThrow());
        state.clear();
        assertTrue(state.latest().isEmpty());
    }

    @Test
    void formatsOneReadableMessageWithSourceAndRecipeMetadata() {
        SharedResponse response = new SharedResponse(
                "Use one compass and eight echo shards.",
                Optional.of(URI.create("https://minecraft.wiki/w/Recovery_Compass")),
                Optional.of(new SharedResponse.Recipe(
                        "minecraft:recovery_compass", ProductionMethod.CRAFTING
                ))
        );

        List<String> messages = SharedResponseMessages.format(response);

        assertEquals(List.of(
                "[Minecraft Assistant] Use one compass and eight echo shards."
                        + " | Source: https://minecraft.wiki/w/Recovery_Compass"
                        + " | Recipe: minecraft:recovery_compass (crafting)"
        ), messages);
    }

    @Test
    void normalizesWhitespaceAndSplitsLongAnswersIntoTwoBoundedMessages() {
        SharedResponse response = new SharedResponse(
                ("  A recovery compass points to your last death location.\n\n" + "Echo shards ".repeat(28)).trim(),
                Optional.empty(),
                Optional.empty()
        );

        List<String> messages = SharedResponseMessages.format(response);

        assertEquals(2, messages.size());
        assertTrue(messages.getFirst().startsWith("[Minecraft Assistant 1/2] "));
        assertTrue(messages.getLast().startsWith("[Minecraft Assistant 2/2] "));
        assertTrue(messages.stream().allMatch(message -> message.length() <= 256));
        assertFalse(String.join("", messages).contains("\n"));
        assertFalse(String.join("", messages).contains("  "));
    }

    @Test
    void truncatesUnusuallyDetailedAnswersAtTwoMessagesAndPreservesUnicode() {
        SharedResponse response = new SharedResponse(
                "😀".repeat(400), Optional.empty(), Optional.empty()
        );

        List<String> messages = SharedResponseMessages.format(response);
        String last = messages.getLast();

        assertEquals(2, messages.size());
        assertTrue(messages.stream().allMatch(message -> message.length() <= 256));
        assertTrue(last.endsWith("…"));
        messages.forEach(message -> {
            assertFalse(Character.isHighSurrogate(message.charAt(message.length() - 1)));
            assertFalse(Character.isLowSurrogate(message.charAt(0)));
        });
    }

    @Test
    void keepsMetadataTogetherOnTheFinalMessage() {
        SharedResponse response = new SharedResponse(
                "A useful standalone answer. ".repeat(15),
                Optional.of(URI.create("https://minecraft.wiki/w/Map")),
                Optional.of(new SharedResponse.Recipe("minecraft:map", ProductionMethod.CRAFTING))
        );

        List<String> messages = SharedResponseMessages.format(response);

        assertEquals(2, messages.size());
        assertFalse(messages.getFirst().contains("Source:"));
        assertFalse(messages.getFirst().contains("Recipe:"));
        assertTrue(messages.getLast().contains("Source: https://minecraft.wiki/w/Map"));
        assertTrue(messages.getLast().endsWith("Recipe: minecraft:map (crafting)"));
    }

    @Test
    void parsesOnlySafeMetadataFromExactSharePrefixes() {
        SharedResponseMessages.ParsedMetadata parsed = SharedResponseMessages.parse(
                "[Minecraft Assistant] Answer | Source: https://minecraft.wiki/w/Map"
                        + " | Recipe: minecraft:map (crafting)"
        ).orElseThrow();

        assertEquals(URI.create("https://minecraft.wiki/w/Map"), parsed.source().orElseThrow());
        assertEquals("minecraft:map", parsed.recipe().orElseThrow().recipeId());
        assertTrue(SharedResponseMessages.parse(
                "[Minecraft Assistant] Answer | Source: file:///tmp/private"
        ).isEmpty());
        assertTrue(SharedResponseMessages.parse(
                "[Minecraft Assistant] Answer | Recipe: minecraft:map (brewing)"
        ).isEmpty());
        assertTrue(SharedResponseMessages.parse(
                "Minecraft Assistant: Answer | Source: https://example.com"
        ).isEmpty());
    }

    @Test
    void extractsTheFinalStandardRecipeButNotNativeGuides() {
        ProductionCardData crafting = crafting("minecraft:torch");
        ProductionCardData cooking = cooking("minecraft:charcoal");
        ProductionPresentation sequence = new ProductionPresentation.Sequence(List.of(cooking, crafting));
        ProductionPresentation plan = new ProductionPresentation.Plan(plan(crafting));

        SharedResponse single = SharedResponse.from(
                "Torch recipe ready.", new ProductionPresentation.Single(crafting)
        );
        SharedResponse shared = SharedResponse.from("Torch guide ready.", sequence);
        SharedResponse planned = SharedResponse.from("Torch plan ready.", plan);
        SharedResponse nativeGuide = SharedResponse.from(
                "Brewing guide ready.",
                new ProductionPresentation.Single(brewing("minecraft_assistant:brewing/slowness"))
        );

        assertEquals("minecraft:torch", single.recipe().orElseThrow().recipeId());
        assertEquals("minecraft:torch", shared.recipe().orElseThrow().recipeId());
        assertEquals(ProductionMethod.CRAFTING, shared.recipe().orElseThrow().method());
        assertEquals("minecraft:torch", planned.recipe().orElseThrow().recipeId());
        assertTrue(nativeGuide.recipe().isEmpty());
    }

    @Test
    void extractsAndRemovesOnlyValidHttpSources() {
        SharedResponse valid = SharedResponse.from(
                "Maps can be zoomed.\nSource: https://minecraft.wiki/w/Map", null
        );
        SharedResponse invalid = SharedResponse.from(
                "Keep this text. Source: file:///tmp/private", null
        );

        assertEquals("Maps can be zoomed.", valid.answer());
        assertEquals(URI.create("https://minecraft.wiki/w/Map"), valid.source().orElseThrow());
        assertEquals("Keep this text. Source: file:///tmp/private", invalid.answer());
        assertTrue(invalid.source().isEmpty());
    }

    @Test
    void buildsClickableActionsButIgnoresUnsignedAndFilteredMessages() {
        String content = "[Minecraft Assistant] Answer | Source: https://minecraft.wiki/w/Map"
                + " | Recipe: minecraft:map (crafting)";
        PlayerChatMessage unsigned = PlayerChatMessage.unsigned(UUID.randomUUID(), content);
        PlayerChatMessage signed = withSignature(unsigned, FilterMask.PASS_THROUGH);
        FilterMask partial = new FilterMask(content.length());
        partial.setFiltered(0);
        PlayerChatMessage filtered = withSignature(unsigned, partial);

        assertTrue(SharedResponseEnhancer.enhancement(unsigned, "Alex").isEmpty());
        assertTrue(SharedResponseEnhancer.enhancement(filtered, "Alex").isEmpty());
        Component actions = SharedResponseEnhancer.enhancement(signed, "Alex").orElseThrow();

        assertEquals("[Open Source] [View Final Recipe]", actions.getString());
        ClickEvent.OpenUrl sourceClick = assertInstanceOf(
                ClickEvent.OpenUrl.class,
                actions.getSiblings().getFirst().getStyle().getClickEvent()
        );
        assertEquals(URI.create("https://minecraft.wiki/w/Map"), sourceClick.uri());
        ClickEvent.RunCommand recipeClick = assertInstanceOf(
                ClickEvent.RunCommand.class,
                actions.getSiblings().getLast().getStyle().getClickEvent()
        );
        assertEquals("/mcai recipe \"minecraft:map\" crafting", recipeClick.command());
    }

    private static PlayerChatMessage withSignature(PlayerChatMessage message, FilterMask filterMask) {
        return new PlayerChatMessage(
                message.link(),
                new MessageSignature(new byte[MessageSignature.BYTES]),
                message.signedBody(),
                message.unsignedContent(),
                filterMask
        );
    }

    private static ProductionCardData crafting(String id) {
        ProductionCardData.Slot empty = ProductionCardData.Slot.of(ItemStack.EMPTY);
        return new ProductionCardData.Crafting(id, 1, 1, List.of(empty), empty, false);
    }

    private static ProductionCardData cooking(String id) {
        ProductionCardData.Slot empty = ProductionCardData.Slot.of(ItemStack.EMPTY);
        return new ProductionCardData.Cooking(
                id,
                ProductionMethod.SMELTING,
                empty,
                new ProductionCardData.Slot(List.of(), true),
                empty,
                empty,
                200,
                0.1F
        );
    }

    private static ProductionCardData brewing(String id) {
        ProductionCardData.Slot empty = ProductionCardData.Slot.of(ItemStack.EMPTY);
        return new ProductionCardData.Brewing(id, empty, empty, empty, empty, empty);
    }

    private static ProductionPlan plan(ProductionCardData card) {
        ProductionPlan.Material input = new ProductionPlan.Material(
                "minecraft:charcoal", "Charcoal", 1, List.of("minecraft:charcoal"), ItemStack.EMPTY
        );
        ProductionPlan.Material output = new ProductionPlan.Material(
                "minecraft:torch", "Torch", 4, List.of("minecraft:torch"), ItemStack.EMPTY
        );
        ProductionPlan.Operation operation = new ProductionPlan.Operation(
                card, 1, List.of(input), output, 0, 0
        );
        return new ProductionPlan(
                4,
                4,
                "minecraft:torch",
                "Torch",
                "minecraft:charcoal",
                "Charcoal",
                List.of(operation),
                List.of(input),
                List.of(),
                false
        );
    }
}
