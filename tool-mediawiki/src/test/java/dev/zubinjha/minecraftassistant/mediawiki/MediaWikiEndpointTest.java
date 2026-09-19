package dev.zubinjha.minecraftassistant.mediawiki;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.zubinjha.minecraftassistant.core.AssistantException;
import org.junit.jupiter.api.Test;

final class MediaWikiEndpointTest {
    @Test
    void acceptsOfficialAndLoopbackApiUrls() {
        assertEquals(
                "https://minecraft.wiki/api.php",
                MediaWikiEndpoint.parse("https://minecraft.wiki/api.php").toString()
        );
        assertEquals(
                "http://127.0.0.1:8123/api.php",
                MediaWikiEndpoint.parse("http://127.0.0.1:8123/api.php").toString()
        );
    }

    @Test
    void rejectsUnsafeOrAmbiguousApiUrls() {
        assertThrows(AssistantException.class, () -> MediaWikiEndpoint.parse("http://minecraft.wiki/api.php"));
        assertThrows(AssistantException.class, () -> MediaWikiEndpoint.parse("https://user@minecraft.wiki/api.php"));
        assertThrows(AssistantException.class, () -> MediaWikiEndpoint.parse("https://minecraft.wiki/api.php?key=x"));
        assertThrows(AssistantException.class, () -> MediaWikiEndpoint.parse("https://minecraft.wiki"));
    }
}
