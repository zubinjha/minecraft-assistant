package dev.zubinjha.minecraftassistant.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dev.zubinjha.minecraftassistant.core.ToolDefinition;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

final class NativeGuideToolTest {
    @Test
    void exposesEveryNativeGuideWithTheExpectedSchema() {
        Map<String, ToolDefinition> tools = java.util.Arrays.stream(NativeGuideTool.Kind.values())
                .map(kind -> new NativeGuideTool(
                        kind,
                        Runnable::run,
                        null,
                        new ProductionPresentationCollector()
                ).definition())
                .collect(Collectors.toMap(ToolDefinition::name, definition -> definition));

        assertEquals(List.of(
                "show_anvil",
                "show_brewing",
                "show_cartography",
                "show_enchanting",
                "show_grindstone",
                "show_loom"
        ), tools.keySet().stream().sorted().toList());
        assertRequired(tools.get("show_brewing"), "target_potion_id");
        assertRequired(tools.get("show_loom"), "base_color", "layers");
        assertRequired(tools.get("show_cartography"), "operation");
        assertRequired(tools.get("show_enchanting"), "item_id");
        assertRequired(tools.get("show_anvil"), "operation", "base_item_id");
        assertRequired(tools.get("show_grindstone"), "operation", "input_item_id");
        assertEquals(6, tools.get("show_loom").inputSchema()
                .path("properties").path("layers").path("maxItems").asInt());
    }

    @Test
    void workstationMethodsUseSpecificChatActions() {
        assertEquals("Show Brewing", NativeGuideTool.buttonLabel(ProductionMethod.BREWING));
        assertEquals("Show Loom", NativeGuideTool.buttonLabel(ProductionMethod.LOOM));
        assertEquals("Show Cartography", NativeGuideTool.buttonLabel(ProductionMethod.CARTOGRAPHY));
        assertEquals("Show Enchanting", NativeGuideTool.buttonLabel(ProductionMethod.ENCHANTING));
        assertEquals("Show Anvil", NativeGuideTool.buttonLabel(ProductionMethod.ANVIL));
        assertEquals("Show Grindstone", NativeGuideTool.buttonLabel(ProductionMethod.GRINDSTONE));
        assertEquals("Show Recipe", NativeGuideTool.buttonLabel(ProductionMethod.CRAFTING));
    }

    private static void assertRequired(ToolDefinition definition, String... names) {
        JsonNode required = definition.inputSchema().path("required");
        for (String name : names) {
            assertTrue(required.valueStream().anyMatch(value -> value.asText().equals(name)), name);
        }
    }
}
