package dev.zubinjha.minecraftassistant.fabric;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

final class SharedResponseState {
    private final AtomicReference<SharedResponse> latest = new AtomicReference<>();

    Optional<SharedResponse> latest() {
        return Optional.ofNullable(latest.get());
    }

    void update(SharedResponse response) {
        latest.set(Objects.requireNonNull(response, "response"));
    }

    void clear() {
        latest.set(null);
    }
}
