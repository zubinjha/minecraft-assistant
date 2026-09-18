package dev.zubinjha.minecraftassistant.core;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CancellationSource implements CancellationToken {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final CopyOnWriteArrayList<Runnable> callbacks = new CopyOnWriteArrayList<>();

    @Override
    public boolean isCancelled() {
        return cancelled.get();
    }

    @Override
    public AutoCloseable onCancel(Runnable callback) {
        Objects.requireNonNull(callback, "callback");
        if (cancelled.get()) {
            callback.run();
            return () -> { };
        }
        callbacks.add(callback);
        if (cancelled.get() && callbacks.remove(callback)) {
            callback.run();
        }
        return () -> callbacks.remove(callback);
    }

    public boolean cancel() {
        if (!cancelled.compareAndSet(false, true)) {
            return false;
        }
        for (Runnable callback : callbacks) {
            callback.run();
        }
        callbacks.clear();
        return true;
    }
}
