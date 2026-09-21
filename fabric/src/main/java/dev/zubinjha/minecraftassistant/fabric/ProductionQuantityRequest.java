package dev.zubinjha.minecraftassistant.fabric;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import net.minecraft.world.item.ItemStack;

record ProductionQuantityRequest(long totalItems, long stacks, long looseItems) {
    static final long MAX_TARGET_ITEMS = 1_000_000_000L;

    ProductionQuantityRequest(long stacks, long looseItems) {
        this(0, stacks, looseItems);
    }

    ProductionQuantityRequest {
        if (totalItems < 0 || stacks < 0 || looseItems < 0) {
            throw new IllegalArgumentException("quantity values must be non-negative");
        }
        if (totalItems > 0 && (stacks > 0 || looseItems > 0)) {
            throw new IllegalArgumentException(
                    "total_items cannot be combined with positive stacks or loose_items"
            );
        }
        if (totalItems == 0 && stacks == 0 && looseItems == 0) {
            throw new IllegalArgumentException("at least one quantity value must be positive");
        }
        if (totalItems > MAX_TARGET_ITEMS) {
            throw new IllegalArgumentException("the requested quantity is too large");
        }
    }

    long totalFor(ItemStack target) {
        if (target.isEmpty()) {
            throw new IllegalArgumentException("the target recipe has no output item");
        }
        if (totalItems > 0) {
            return totalItems;
        }
        try {
            long total = Math.addExact(Math.multiplyExact(stacks, target.getMaxStackSize()), looseItems);
            if (total <= 0 || total > MAX_TARGET_ITEMS) {
                throw new IllegalArgumentException("the requested quantity is too large");
            }
            return total;
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("the requested quantity is too large", overflow);
        }
    }

    static ParseResult parseOptional(JsonNode arguments) {
        JsonNode raw = arguments.get("target_quantity");
        if (raw == null || raw.isNull()) {
            return new ParseResult(Optional.empty(), null);
        }
        if (!raw.isObject()) {
            return new ParseResult(Optional.empty(), "target_quantity must be an object");
        }
        JsonNode totalItemsNode = raw.get("total_items");
        JsonNode stacksNode = raw.get("stacks");
        JsonNode looseItemsNode = raw.get("loose_items");
        if ((totalItemsNode != null && !totalItemsNode.isIntegralNumber())
                || (stacksNode != null && !stacksNode.isIntegralNumber())
                || (looseItemsNode != null && !looseItemsNode.isIntegralNumber())) {
            return new ParseResult(Optional.empty(), "target_quantity values must be whole numbers");
        }
        if ((totalItemsNode != null && !totalItemsNode.canConvertToLong())
                || (stacksNode != null && !stacksNode.canConvertToLong())
                || (looseItemsNode != null && !looseItemsNode.canConvertToLong())) {
            return new ParseResult(Optional.empty(), "the requested quantity is too large");
        }
        long totalItems = totalItemsNode == null ? 0 : totalItemsNode.longValue();
        long stacks = stacksNode == null ? 0 : stacksNode.longValue();
        long looseItems = looseItemsNode == null ? 0 : looseItemsNode.longValue();
        try {
            return new ParseResult(Optional.of(new ProductionQuantityRequest(totalItems, stacks, looseItems)), null);
        } catch (IllegalArgumentException invalid) {
            return new ParseResult(Optional.empty(), invalid.getMessage());
        }
    }

    record ParseResult(Optional<ProductionQuantityRequest> request, String error) {
        boolean valid() {
            return error == null;
        }
    }
}
