package dev.zubinjha.minecraftassistant.mediawiki;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.zubinjha.minecraftassistant.core.AssistantException;
import dev.zubinjha.minecraftassistant.core.Agent;
import dev.zubinjha.minecraftassistant.core.AgentEventListener;
import dev.zubinjha.minecraftassistant.core.AgentOptions;
import dev.zubinjha.minecraftassistant.core.AssistantRequest;
import dev.zubinjha.minecraftassistant.core.CancellationSource;
import dev.zubinjha.minecraftassistant.core.CancellationToken;
import dev.zubinjha.minecraftassistant.core.ErrorCode;
import dev.zubinjha.minecraftassistant.core.ModelResponse;
import dev.zubinjha.minecraftassistant.core.Tool;
import dev.zubinjha.minecraftassistant.core.ToolExecutionResult;
import dev.zubinjha.minecraftassistant.core.ToolRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class MinecraftWikiToolSourceTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger retryRequests = new AtomicInteger();
    private final AtomicInteger outageRequests = new AtomicInteger();
    private final AtomicReference<String> lastSearch = new AtomicReference<>();
    private final CountDownLatch slowRequestStarted = new CountDownLatch(1);
    private ExecutorService executor;
    private HttpServer server;
    private MinecraftWikiToolSource source;

    @BeforeEach
    void startServer() throws IOException {
        executor = Executors.newCachedThreadPool();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/api.php", this::respond);
        server.start();
        source = new MinecraftWikiToolSource(endpoint(), executor);
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void exposesTheSixCompatibleWikiToolsWithoutContactingTheServer() {
        assertEquals(0, requests.get());
        assertEquals(
                java.util.List.of(
                        "minecraft_wiki_search",
                        "minecraft_wiki_get_page",
                        "minecraft_wiki_get_section",
                        "minecraft_wiki_get_categories",
                        "minecraft_wiki_get_category_members",
                        "minecraft_wiki_resolve_redirect"
                ),
                source.tools().stream().map(tool -> tool.definition().name()).toList()
        );
        assertEquals(0, requests.get());
    }

    @Test
    void ordinaryProviderAnswersDoNotContactTheWiki() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            Agent agent = new Agent(
                    (request, cancellation) -> java.util.concurrent.CompletableFuture.completedFuture(
                            ModelResponse.text("Use a crafting table.")
                    ),
                    new ToolRegistry(source.tools()),
                    AgentOptions.DEFAULT,
                    scheduler,
                    AgentEventListener.NONE
            );

            String answer = agent.ask(new AssistantRequest(
                    "test", "low", "Be concise.", java.util.List.of(), "hello"
            ), CancellationToken.NONE).toCompletableFuture().join().text();

            assertEquals("Use a crafting table.", answer);
            assertEquals(0, requests.get());
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void searchEscapesArgumentsAndCachesIdenticalRequests() throws Exception {
        ObjectNode arguments = JSON.createObjectNode().put("query", "heart of the sea & treasure").put("limit", 3);

        JsonNode first = invoke("minecraft_wiki_search", arguments);
        JsonNode second = invoke("minecraft_wiki_search", arguments);

        assertEquals("heart of the sea & treasure", lastSearch.get());
        assertEquals("Heart of the Sea", first.path("results").get(0).path("title").asText());
        assertEquals("Find & use it", first.path("results").get(0).path("snippet").asText());
        assertEquals(first, second);
        assertEquals(1, requests.get());
    }

    @Test
    void pageSectionsCanonicalUrlsAndRedirectsStayConsistent() throws Exception {
        JsonNode page = invoke("minecraft_wiki_get_page", JSON.createObjectNode()
                .put("title", "Recovery compass")
                .put("include_all_content", false));
        assertEquals("Recovery Compass", page.path("title").asText());
        assertEquals("Lead text.", page.path("lead_text").asText());
        assertEquals("Usage", page.path("sections").get(0).path("title").asText());
        assertEquals("https://minecraft.wiki/w/Recovery_Compass?action=history", page.path("history_url").asText());

        JsonNode section = invoke("minecraft_wiki_get_section", JSON.createObjectNode()
                .put("title", "Recovery Compass")
                .put("section", 1));
        assertEquals("Usage", section.path("section_title").asText());
        assertTrue(section.path("text").asText().contains("points to the last death"));

        JsonNode redirect = invoke("minecraft_wiki_resolve_redirect", JSON.createObjectNode()
                .put("title", "Recovery compass"));
        assertTrue(redirect.path("is_redirect").asBoolean());
        assertEquals("Recovery Compass", redirect.path("resolved_title").asText());
    }

    @Test
    void categoriesAndMembersUseCompactJson() throws Exception {
        JsonNode pageCategories = invoke("minecraft_wiki_get_categories", JSON.createObjectNode()
                .put("title", "Recovery Compass"));
        assertEquals("Items", pageCategories.path("categories").get(0).asText());

        JsonNode categoryNames = invoke("minecraft_wiki_get_categories", JSON.createObjectNode()
                .put("prefix", "Rec")
                .put("limit", 2));
        assertEquals("Recipes", categoryNames.path("categories").get(0).asText());

        JsonNode members = invoke("minecraft_wiki_get_category_members", JSON.createObjectNode()
                .put("category", "Items")
                .put("limit", 2));
        assertEquals("Recovery Compass", members.path("members").get(0).asText());
    }

    @Test
    void truncatesOversizedExtractsBelowTheHarnessLimit() throws Exception {
        JsonNode page = invoke("minecraft_wiki_get_page", JSON.createObjectNode()
                .put("title", "Long Page")
                .put("include_all_content", true));

        assertTrue(page.path("truncated").asBoolean());
        assertTrue(page.path("text").asText().length() < 64 * 1024);
        assertTrue(page.path("text").asText().endsWith("[Content truncated]"));
    }

    @Test
    void retriesBoundedRetryAfterOnlyOnce() {
        MediaWikiClient client = new MediaWikiClient(endpoint(), executor);

        JsonNode response = client.request(Map.of("action", "query", "test", "retry"), CancellationToken.NONE)
                .toCompletableFuture().join();

        assertEquals(true, response.path("ok").asBoolean());
        assertEquals(2, retryRequests.get());
    }

    @Test
    void rejectsMalformedOversizedAndApiErrorResponses() {
        MediaWikiClient client = new MediaWikiClient(endpoint(), executor);

        assertFailure(ErrorCode.PROTOCOL_ERROR,
                () -> client.request(Map.of("test", "malformed"), CancellationToken.NONE)
                        .toCompletableFuture().join());
        assertFailure(ErrorCode.TOOL_FAILURE,
                () -> client.request(Map.of("test", "huge"), CancellationToken.NONE)
                        .toCompletableFuture().join());
        assertFailure(ErrorCode.TOOL_FAILURE,
                () -> client.request(Map.of("test", "api-error"), CancellationToken.NONE)
                        .toCompletableFuture().join());
        assertFailure(ErrorCode.TOOL_FAILURE,
                () -> client.request(Map.of("test", "outage"), CancellationToken.NONE)
                        .toCompletableFuture().join());
        assertEquals(2, outageRequests.get());
    }

    @Test
    void requestTimeoutsAndCancellationAreReportedWithoutHanging() throws Exception {
        MediaWikiClient shortClient = new MediaWikiClient(endpoint(), executor, Duration.ofMillis(75), 2 * 1024 * 1024);
        assertFailure(ErrorCode.TOOL_FAILURE,
                () -> shortClient.request(Map.of("test", "slow"), CancellationToken.NONE)
                        .toCompletableFuture().join());

        CancellationSource cancellation = new CancellationSource();
        var pending = new MediaWikiClient(endpoint(), executor).request(Map.of("test", "slow"), cancellation)
                .toCompletableFuture();
        assertTrue(slowRequestStarted.await(1, TimeUnit.SECONDS));
        cancellation.cancel();
        assertFailure(ErrorCode.CANCELLED, pending::join);
    }

    private JsonNode invoke(String toolName, ObjectNode arguments) throws Exception {
        Tool tool = source.tools().stream()
                .filter(candidate -> candidate.definition().name().equals(toolName))
                .findFirst()
                .orElseThrow();
        ToolExecutionResult result = tool.execute(arguments, CancellationToken.NONE).toCompletableFuture().join();
        assertFalse(result.content().isBlank());
        assertTrue(result.provenance().containsKey("source_url"));
        return JSON.readTree(result.content());
    }

    private String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/api.php";
    }

    private void respond(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
        String test = query.getOrDefault("test", "");
        if (test.equals("slow")) {
            slowRequestStarted.countDown();
            try {
                Thread.sleep(500);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        if (test.equals("retry") && retryRequests.incrementAndGet() == 1) {
            exchange.getResponseHeaders().add("Retry-After", "0");
            write(exchange, 429, "{}");
            return;
        }
        if (test.equals("retry")) {
            write(exchange, 200, "{\"ok\":true}");
            return;
        }
        if (test.equals("malformed")) {
            write(exchange, 200, "not-json");
            return;
        }
        if (test.equals("huge")) {
            write(exchange, 200, "{\"padding\":\"" + "x".repeat(2 * 1024 * 1024) + "\"}");
            return;
        }
        if (test.equals("api-error")) {
            write(exchange, 200, "{\"error\":{\"info\":\"fixture error\"}}");
            return;
        }
        if (test.equals("outage")) {
            outageRequests.incrementAndGet();
            exchange.getResponseHeaders().add("Retry-After", "0");
            write(exchange, 503, "{}");
            return;
        }

        String list = query.getOrDefault("list", "");
        String prop = query.getOrDefault("prop", "");
        if (list.equals("search")) {
            lastSearch.set(query.get("srsearch"));
            write(exchange, 200, "{\"query\":{\"search\":[{\"title\":\"Heart of the Sea\","
                    + "\"snippet\":\"Find &amp; <span>use</span> it\"}]}}");
        } else if (list.equals("allcategories")) {
            write(exchange, 200, "{\"query\":{\"allcategories\":[{\"category\":\"Recipes\"}]}}");
        } else if (list.equals("categorymembers")) {
            write(exchange, 200, "{\"query\":{\"categorymembers\":[{\"title\":\"Recovery Compass\"}]}}");
        } else if (prop.equals("categories|info")) {
            write(exchange, 200, "{\"query\":{\"pages\":[{\"title\":\"Recovery Compass\","
                    + "\"fullurl\":\"https://minecraft.wiki/w/Recovery_Compass\","
                    + "\"categories\":[{\"title\":\"Category:Items\"}]}]}}");
        } else if (prop.equals("extracts|info")) {
            String extract = query.getOrDefault("titles", "").equals("Long Page")
                    ? "x".repeat(60 * 1024)
                    : "Lead text.\\n\\n== Usage ==\\nA recovery compass points to the last death."
                            + "\\n\\n=== Details ===\\nNested details.\\n\\n== History ==\\nHistory text.";
            ObjectNode page = JSON.createObjectNode();
            page.put("title", query.getOrDefault("titles", "Recovery Compass").equals("Long Page")
                    ? "Long Page" : "Recovery Compass");
            page.put("fullurl", page.path("title").asText().equals("Long Page")
                    ? "https://minecraft.wiki/w/Long_Page"
                    : "https://minecraft.wiki/w/Recovery_Compass");
            page.put("extract", extract.replace("\\n", "\n"));
            ObjectNode root = JSON.createObjectNode();
            root.putObject("query").putArray("pages").add(page);
            write(exchange, 200, root.toString());
        } else if (prop.equals("info")) {
            write(exchange, 200, "{\"query\":{\"redirects\":[{\"from\":\"Recovery compass\","
                    + "\"to\":\"Recovery Compass\"}],\"pages\":[{\"title\":\"Recovery Compass\","
                    + "\"fullurl\":\"https://minecraft.wiki/w/Recovery_Compass\"}]}}");
        } else if (query.getOrDefault("meta", "").equals("siteinfo")) {
            write(exchange, 200, "{\"query\":{\"general\":{\"sitename\":\"Minecraft Wiki\"}}}");
        } else {
            write(exchange, 200, "{\"ok\":true}");
        }
    }

    private static Map<String, String> query(String rawQuery) {
        Map<String, String> values = new HashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return values;
        }
        for (String pair : rawQuery.split("&")) {
            String[] parts = pair.split("=", 2);
            values.put(
                    URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(parts.length > 1 ? parts[1] : "", StandardCharsets.UTF_8)
            );
        }
        return values;
    }

    private static void write(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void assertFailure(ErrorCode expected, Runnable operation) {
        CompletionException wrapper = assertThrows(CompletionException.class, operation::run);
        AssistantException failure = assertInstanceOf(AssistantException.class, wrapper.getCause());
        assertEquals(expected, failure.code());
    }
}
