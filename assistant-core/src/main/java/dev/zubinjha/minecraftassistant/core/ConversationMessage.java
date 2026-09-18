package dev.zubinjha.minecraftassistant.core;

import java.util.List;
import java.util.Objects;

public sealed interface ConversationMessage {
    record System(String text) implements ConversationMessage {
        public System {
            text = requireText(text, "text");
        }
    }

    record User(String text) implements ConversationMessage {
        public User {
            text = requireText(text, "text");
        }
    }

    record Assistant(String text, List<ToolCall> toolCalls) implements ConversationMessage {
        public Assistant {
            text = Objects.requireNonNullElse(text, "");
            toolCalls = List.copyOf(toolCalls);
            if (text.isBlank() && toolCalls.isEmpty()) {
                throw new IllegalArgumentException("assistant message must contain text or tool calls");
            }
        }
    }

    record ToolResult(String callId, String toolName, String content, boolean success)
            implements ConversationMessage {
        public ToolResult {
            callId = requireText(callId, "callId");
            toolName = requireText(toolName, "toolName");
            content = Objects.requireNonNull(content, "content");
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
