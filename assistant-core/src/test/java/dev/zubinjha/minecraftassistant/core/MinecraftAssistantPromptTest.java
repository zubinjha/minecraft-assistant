package dev.zubinjha.minecraftassistant.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class MinecraftAssistantPromptTest {
    @Test
    void requiresAnswersToStandAloneWithoutConversationHistory() {
        assertTrue(MinecraftAssistantPrompt.DEFAULT.contains(
                "Make every answer understandable without the earlier conversation."
        ));
        assertTrue(MinecraftAssistantPrompt.DEFAULT.contains(
                "briefly name the subject and any constraint needed to understand the answer"
        ));
    }
}
