package dev.zubinjha.minecraftassistant.core;

import java.util.Objects;

public class AssistantException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final ErrorCode code;

    public AssistantException(ErrorCode code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public AssistantException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public ErrorCode code() {
        return code;
    }
}
