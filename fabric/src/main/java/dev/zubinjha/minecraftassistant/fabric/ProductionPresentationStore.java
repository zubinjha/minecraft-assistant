package dev.zubinjha.minecraftassistant.fabric;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

final class ProductionPresentationStore {
    private final AtomicLong nextToken = new AtomicLong();
    private final Map<String, ProductionPresentation> entries;

    ProductionPresentationStore(int maximumEntries) {
        if (maximumEntries < 1) {
            throw new IllegalArgumentException("maximumEntries must be positive");
        }
        entries = new LinkedHashMap<>(maximumEntries + 1, 0.75F, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, ProductionPresentation> eldest) {
                return size() > maximumEntries;
            }
        };
    }

    synchronized String put(ProductionPresentation presentation) {
        String token = "r" + Long.toString(nextToken.incrementAndGet(), Character.MAX_RADIX);
        entries.put(token, presentation);
        return token;
    }

    synchronized Optional<ProductionPresentation> get(String token) {
        return Optional.ofNullable(entries.get(token));
    }

    synchronized int size() {
        return entries.size();
    }

    synchronized void clear() {
        entries.clear();
    }
}
