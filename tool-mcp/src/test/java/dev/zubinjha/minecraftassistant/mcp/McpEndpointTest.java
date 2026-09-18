package dev.zubinjha.minecraftassistant.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.zubinjha.minecraftassistant.core.AssistantException;
import org.junit.jupiter.api.Test;

final class McpEndpointTest {
    @Test
    void acceptsHttpsEndpoint() {
        McpEndpoint endpoint = McpEndpoint.parse("https://example.com:8443/mcp");

        assertEquals("https://example.com:8443", endpoint.baseUrl());
        assertEquals("/mcp", endpoint.path());
    }

    @Test
    void acceptsLoopbackHttpEndpoint() {
        McpEndpoint endpoint = McpEndpoint.parse("http://127.0.0.1:8000/mcp");

        assertEquals("http://127.0.0.1:8000", endpoint.baseUrl());
    }

    @Test
    void rejectsRemotePlainHttpEndpoint() {
        assertThrows(AssistantException.class, () -> McpEndpoint.parse("http://example.com/mcp"));
    }

    @Test
    void rejectsCredentialsAndQueryParameters() {
        assertThrows(AssistantException.class, () -> McpEndpoint.parse("https://user@example.com/mcp"));
        assertThrows(AssistantException.class, () -> McpEndpoint.parse("https://example.com/mcp?token=secret"));
    }
}
