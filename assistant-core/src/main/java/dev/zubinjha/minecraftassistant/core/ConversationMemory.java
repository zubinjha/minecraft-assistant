package dev.zubinjha.minecraftassistant.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public final class ConversationMemory {
    private final int maxMessages;
    private final Deque<ConversationMessage> messages = new ArrayDeque<>();

    public ConversationMemory(int maxMessages) {
        if (maxMessages < 2) {
            throw new IllegalArgumentException("maxMessages must be at least 2");
        }
        this.maxMessages = maxMessages;
    }

    public synchronized void addExchange(String userText, String assistantText) {
        messages.addLast(new ConversationMessage.User(userText));
        messages.addLast(new ConversationMessage.Assistant(assistantText, List.of()));
        while (messages.size() > maxMessages) {
            messages.removeFirst();
            if (!messages.isEmpty()) {
                messages.removeFirst();
            }
        }
    }

    public synchronized List<ConversationMessage> snapshot() {
        return List.copyOf(new ArrayList<>(messages));
    }

    public synchronized int size() {
        return messages.size();
    }

    public synchronized void clear() {
        messages.clear();
    }
}
