package dev.zubinjha.minecraftassistant.core;

public interface CancellationToken {
    CancellationToken NONE = new CancellationToken() {
        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public AutoCloseable onCancel(Runnable callback) {
            return () -> { };
        }
    };

    boolean isCancelled();

    AutoCloseable onCancel(Runnable callback);

    default void throwIfCancelled() {
        if (isCancelled()) {
            throw new AssistantException(ErrorCode.CANCELLED, "The request was cancelled");
        }
    }
}
