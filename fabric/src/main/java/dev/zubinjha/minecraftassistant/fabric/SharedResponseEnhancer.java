package dev.zubinjha.minecraftassistant.fabric;

import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.network.chat.Style;

final class SharedResponseEnhancer {
    private SharedResponseEnhancer() {
    }

    static Optional<Component> enhancement(PlayerChatMessage message, String senderName) {
        if (message == null || !message.hasSignature() || !message.filterMask().isEmpty()) {
            return Optional.empty();
        }
        return enhancement(message.signedContent(), senderName);
    }

    static Optional<Component> enhancement(String signedContent, String senderName) {
        return SharedResponseMessages.parse(signedContent).map(metadata -> {
            MutableComponent actions = Component.empty();
            metadata.source().ifPresent(source -> actions.append(Component.literal("[Open Source]")
                    .withStyle(Style.EMPTY
                            .withColor(ChatFormatting.AQUA)
                            .withUnderlined(true)
                            .withClickEvent(new ClickEvent.OpenUrl(source))
                            .withHoverEvent(new HoverEvent.ShowText(Component.literal(
                                    "Shared by " + senderName + "\n" + source
                            ))))));
            metadata.recipe().ifPresent(recipe -> {
                if (!actions.getSiblings().isEmpty()) {
                    actions.append(Component.literal(" "));
                }
                actions.append(Component.literal("[View Final Recipe]")
                        .withStyle(Style.EMPTY
                                .withColor(ChatFormatting.GREEN)
                                .withUnderlined(true)
                                .withClickEvent(new ClickEvent.RunCommand(
                                        "/mcai recipe \"" + recipe.recipeId() + "\" "
                                                + recipe.method().toolValue()
                                ))
                                .withHoverEvent(new HoverEvent.ShowText(Component.literal(
                                        "Shared by " + senderName + "\n"
                                                + recipe.recipeId() + " · " + recipe.method().displayName()
                                )))));
            });
            return actions;
        });
    }
}
