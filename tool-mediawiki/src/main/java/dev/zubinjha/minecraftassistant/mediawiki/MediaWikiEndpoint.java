package dev.zubinjha.minecraftassistant.mediawiki;

import dev.zubinjha.minecraftassistant.core.AssistantException;
import dev.zubinjha.minecraftassistant.core.ErrorCode;
import java.net.URI;

final class MediaWikiEndpoint {
    private MediaWikiEndpoint() {
    }

    static URI parse(String value) {
        final URI uri;
        try {
            uri = URI.create(value == null ? "" : value.trim());
        } catch (IllegalArgumentException failure) {
            throw new AssistantException(ErrorCode.CONFIGURATION, "Invalid MediaWiki API URL", failure);
        }
        if (!uri.isAbsolute() || uri.getHost() == null) {
            throw new AssistantException(ErrorCode.CONFIGURATION, "MediaWiki API URL must be absolute");
        }
        if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new AssistantException(
                    ErrorCode.CONFIGURATION,
                    "MediaWiki API URL must not contain credentials, a query, or a fragment"
            );
        }
        String scheme = uri.getScheme();
        boolean loopback = uri.getHost().equalsIgnoreCase("localhost")
                || uri.getHost().equals("127.0.0.1")
                || uri.getHost().equals("::1");
        if (!scheme.equalsIgnoreCase("https") && !(scheme.equalsIgnoreCase("http") && loopback)) {
            throw new AssistantException(
                    ErrorCode.CONFIGURATION,
                    "MediaWiki API URL must use HTTPS; HTTP is allowed only for loopback addresses"
            );
        }
        if (uri.getPath() == null || uri.getPath().isBlank()) {
            throw new AssistantException(ErrorCode.CONFIGURATION, "MediaWiki API URL must include api.php");
        }
        return uri;
    }
}
