package dev.zubinjha.minecraftassistant.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

final class ConversationMemoryTest {
    @Test
    void keepsOnlyTheMostRecentCompleteExchanges() {
        ConversationMemory memory = new ConversationMemory(4);

        memory.addExchange("question 1", "answer 1");
        memory.addExchange("question 2", "answer 2");
        memory.addExchange("question 3", "answer 3");

        assertEquals(4, memory.size());
        assertEquals(List.of(
                new ConversationMessage.User("question 2"),
                new ConversationMessage.Assistant("answer 2", List.of()),
                new ConversationMessage.User("question 3"),
                new ConversationMessage.Assistant("answer 3", List.of())
        ), memory.snapshot());
    }

    @Test
    void clearRemovesAllContext() {
        ConversationMemory memory = new ConversationMemory(20);
        memory.addExchange("question", "answer");

        memory.clear();

        assertEquals(List.of(), memory.snapshot());
    }

    @Test
    void requiresSpaceForACompleteExchange() {
        assertThrows(IllegalArgumentException.class, () -> new ConversationMemory(1));
    }
}
