package dev.zubinjha.minecraftassistant.mcp;

import dev.zubinjha.minecraftassistant.core.AssistantException;
import dev.zubinjha.minecraftassistant.core.ErrorCode;
import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

record McpEndpoint(String baseUrl, String path, URI uri) {
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "::1");

    McpEndpoint {
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(uri, "uri");
    }

    static McpEndpoint parse(String value) {
        URI uri;
        try {
            uri = URI.create(Objects.requireNonNull(value, "value")).normalize();
        } catch (IllegalArgumentException failure) {
            throw new AssistantException(ErrorCode.CONFIGURATION, "Invalid MCP endpoint URL", failure);
        }

        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null || uri.getRawAuthority() == null) {
            throw new AssistantException(ErrorCode.CONFIGURATION, "MCP endpoint must be an absolute HTTP URL");
        }
        if (uri.getUserInfo() != null || uri.getFragment() != null || uri.getQuery() != null) {
            throw new AssistantException(
                    ErrorCode.CONFIGURATION,
                    "MCP endpoint must not contain credentials, a query, or a fragment"
            );
        }

        String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if (!normalizedScheme.equals("https")
                && !(normalizedScheme.equals("http") && LOOPBACK_HOSTS.contains(normalizedHost))) {
            throw new AssistantException(
                    ErrorCode.CONFIGURATION,
                    "MCP endpoint must use HTTPS; HTTP is allowed only for loopback addresses"
            );
        }

        String path = uri.getRawPath();
        if (path == null || path.isBlank() || path.equals("/")) {
            throw new AssistantException(ErrorCode.CONFIGURATION, "MCP endpoint must include a path");
        }
        String baseUrl = normalizedScheme + "://" + uri.getRawAuthority();
        return new McpEndpoint(baseUrl, path, uri);
    }
}
