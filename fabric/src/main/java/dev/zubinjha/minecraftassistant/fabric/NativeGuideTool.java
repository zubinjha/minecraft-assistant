package dev.zubinjha.minecraftassistant.fabric;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.zubinjha.minecraftassistant.core.CancellationToken;
import dev.zubinjha.minecraftassistant.core.Tool;
import dev.zubinjha.minecraftassistant.core.ToolDefinition;
import dev.zubinjha.minecraftassistant.core.ToolExecutionResult;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import net.minecraft.client.Minecraft;

final class NativeGuideTool implements Tool {
    enum Kind {
        BREWING("show_brewing", "Prepare an exact native brewing guide from Minecraft's potion rules."),
        LOOM("show_loom", "Prepare one or more native loom steps for an exact banner design."),
        CARTOGRAPHY("show_cartography", "Prepare a native cartography guide for scaling, cloning, or locking a map."),
        ENCHANTING("show_enchanting", "Prepare a conservative native enchanting-table eligibility guide."),
        ANVIL("show_anvil", "Prepare a conservative native anvil guide for specified inputs."),
        GRINDSTONE("show_grindstone", "Prepare a conservative native grindstone guide for specified inputs.");

        private final String toolName;
        private final String description;

        Kind(String toolName, String description) {
            this.toolName = toolName;
            this.description = description;
        }
    }

    private final Kind kind;
    private final Executor clientExecutor;
    private final NativeProductionResolver resolver;
    private final ProductionPresentationCollector presentations;
    private final ToolDefinition definition;

    NativeGuideTool(
            Kind kind,
            Minecraft minecraft,
            NativeProductionResolver resolver,
            ProductionPresentationCollector presentations
    ) {
        this(kind, minecraft::execute, resolver, presentations);
    }

    NativeGuideTool(
            Kind kind,
            Executor clientExecutor,
            NativeProductionResolver resolver,
            ProductionPresentationCollector presentations
    ) {
        this.kind = kind;
        this.clientExecutor = clientExecutor;
        this.resolver = resolver;
        this.presentations = presentations;
        this.definition = new ToolDefinition(kind.toolName, kind.description, schema(kind));
    }

    static List<Tool> all(
            Minecraft minecraft,
            NativeProductionResolver resolver,
            ProductionPresentationCollector presentations
    ) {
        List<Tool> tools = new ArrayList<>();
        for (Kind kind : Kind.values()) {
            tools.add(new NativeGuideTool(kind, minecraft, resolver, presentations));
        }
        return List.copyOf(tools);
    }

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public CompletionStage<ToolExecutionResult> execute(JsonNode arguments, CancellationToken cancellation) {
        CompletableFuture<ToolExecutionResult> result = new CompletableFuture<>();
        clientExecutor.execute(() -> {
            try {
                cancellation.throwIfCancelled();
                NativeProductionResult resolved = resolve(arguments);
                switch (resolved) {
                    case NativeProductionResult.Found found -> {
                        if (found.cards().size() == 1) {
                            presentations.add(found.cards().getFirst());
                        } else {
                            presentations.setSequence(found.cards());
                        }
                        String action = presentations.snapshot()
                                .map(MinecraftAssistantRuntime::presentationButtonLabel)
                                .orElseGet(() -> found.cards().size() == 1
                                        ? buttonLabel(found.cards().getFirst().method())
                                        : "Show " + found.cards().size() + " Steps");
                        result.complete(ToolExecutionResult.text(
                                "The native production guide is ready. Briefly tell the player to use the "
                                        + action + " button."
                        ));
                    }
                    case NativeProductionResult.Ambiguous ambiguous -> result.complete(ToolExecutionResult.text(
                            "No guide was created because multiple native paths match. Choose one and call "
                                    + kind.toolName + " again with via_potion_id. Choices: "
                                    + String.join("; ", ambiguous.choices())
                    ));
                    case NativeProductionResult.Missing missing -> result.complete(ToolExecutionResult.text(
                            "No native guide was created: " + missing.reason()
                                    + ". Do not promise a guide button."
                    ));
                }
            } catch (RuntimeException failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    private NativeProductionResult resolve(JsonNode arguments) {
        return switch (kind) {
            case BREWING -> resolver.brewing(
                    arguments.path("target_potion_id").asText("").trim(),
                    arguments.path("container").asText("potion").trim(),
                    arguments.path("via_potion_id").asText("").trim()
            );
            case LOOM -> resolver.loom(
                    arguments.path("base_color").asText("").trim(),
                    arguments.path("layers")
            );
            case CARTOGRAPHY -> resolver.cartography(arguments.path("operation").asText("").trim());
            case ENCHANTING -> resolver.enchanting(
                    arguments.path("item_id").asText("").trim(),
                    arguments.path("enchantment_id").asText("").trim()
            );
            case ANVIL -> resolver.anvil(
                    arguments.path("operation").asText("").trim(),
                    arguments.path("base_item_id").asText("").trim(),
                    arguments.path("addition_item_id").asText("").trim(),
                    arguments.path("enchantment_id").asText("").trim(),
                    arguments.path("enchantment_level").asInt(1),
                    arguments.path("new_name").asText("").trim()
            );
            case GRINDSTONE -> resolver.grindstone(
                    arguments.path("operation").asText("").trim(),
                    arguments.path("input_item_id").asText("").trim(),
                    arguments.path("second_item_id").asText("").trim(),
                    arguments.path("enchantment_ids")
            );
        };
    }

    static String buttonLabel(ProductionMethod method) {
        return switch (method) {
            case BREWING -> "Show Brewing";
            case LOOM -> "Show Loom";
            case CARTOGRAPHY -> "Show Cartography";
            case ENCHANTING -> "Show Enchanting";
            case ANVIL -> "Show Anvil";
            case GRINDSTONE -> "Show Grindstone";
            default -> "Show Recipe";
        };
    }

    private static ObjectNode schema(Kind kind) {
        ObjectNode schema = objectSchema();
        return switch (kind) {
            case BREWING -> brewingSchema(schema);
            case LOOM -> loomSchema(schema);
            case CARTOGRAPHY -> cartographySchema(schema);
            case ENCHANTING -> enchantingSchema(schema);
            case ANVIL -> anvilSchema(schema);
            case GRINDSTONE -> grindstoneSchema(schema);
        };
    }

    private static ObjectNode brewingSchema(ObjectNode schema) {
        property(schema, "target_potion_id", "string", "Exact namespaced target potion ID");
        enumProperty(schema, "container", "Output bottle type", "potion", "splash", "lingering")
                .put("default", "potion");
        property(schema, "via_potion_id", "string", "Optional potion ID used to disambiguate a brewing path");
        require(schema, "target_potion_id");
        return schema;
    }

    private static ObjectNode loomSchema(ObjectNode schema) {
        enumProperty(schema, "base_color", "Base banner color", dyeNames());
        ObjectNode layer = objectSchema();
        property(layer, "pattern_id", "string", "Exact namespaced banner-pattern ID");
        enumProperty(layer, "dye_color", "Dye color for this layer", dyeNames());
        require(layer, "pattern_id", "dye_color");
        ObjectNode layers = schema.withObject("properties").putObject("layers");
        layers.put("type", "array");
        layers.put("minItems", 1);
        layers.put("maxItems", 6);
        layers.set("items", layer);
        require(schema, "base_color", "layers");
        return schema;
    }

    private static ObjectNode cartographySchema(ObjectNode schema) {
        enumProperty(schema, "operation", "Cartography operation", "scale", "clone", "lock");
        require(schema, "operation");
        return schema;
    }

    private static ObjectNode enchantingSchema(ObjectNode schema) {
        property(schema, "item_id", "string", "Exact namespaced item ID");
        property(schema, "enchantment_id", "string", "Optional exact enchantment ID to check for eligibility");
        require(schema, "item_id");
        return schema;
    }

    private static ObjectNode anvilSchema(ObjectNode schema) {
        enumProperty(schema, "operation", "Anvil operation", "repair", "combine", "apply_book", "rename");
        property(schema, "base_item_id", "string", "Exact namespaced base item ID");
        property(schema, "addition_item_id", "string", "Repair material or second item ID");
        property(schema, "enchantment_id", "string", "Enchantment applied from an enchanted book");
        property(schema, "enchantment_level", "integer", "Requested enchantment level").put("minimum", 1);
        property(schema, "new_name", "string", "New item name for rename operations").put("maxLength", 50);
        require(schema, "operation", "base_item_id");
        return schema;
    }

    private static ObjectNode grindstoneSchema(ObjectNode schema) {
        enumProperty(schema, "operation", "Grindstone operation", "disenchant", "repair");
        property(schema, "input_item_id", "string", "Exact namespaced input item ID");
        property(schema, "second_item_id", "string", "Optional second matching item for repair");
        ObjectNode enchantments = schema.withObject("properties").putObject("enchantment_ids");
        enchantments.put("type", "array");
        enchantments.put("maxItems", 16);
        enchantments.putObject("items").put("type", "string");
        require(schema, "operation", "input_item_id");
        return schema;
    }

    private static ObjectNode objectSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        schema.putObject("properties");
        schema.put("additionalProperties", false);
        return schema;
    }

    private static ObjectNode property(ObjectNode schema, String name, String type, String description) {
        return schema.withObject("properties").putObject(name)
                .put("type", type)
                .put("description", description);
    }

    private static ObjectNode enumProperty(
            ObjectNode schema,
            String name,
            String description,
            String... values
    ) {
        ObjectNode property = property(schema, name, "string", description);
        ArrayNode choices = property.putArray("enum");
        for (String value : values) {
            choices.add(value);
        }
        return property;
    }

    private static void require(ObjectNode schema, String... names) {
        ArrayNode required = schema.withArray("required");
        for (String name : names) {
            required.add(name);
        }
    }

    private static String[] dyeNames() {
        return java.util.Arrays.stream(net.minecraft.world.item.DyeColor.values())
                .map(net.minecraft.world.item.DyeColor::getName)
                .toArray(String[]::new);
    }
}
