package dev.zubinjha.minecraftassistant.mediawiki;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.zubinjha.minecraftassistant.core.AssistantException;
import dev.zubinjha.minecraftassistant.core.CancellationToken;
import dev.zubinjha.minecraftassistant.core.ErrorCode;
import dev.zubinjha.minecraftassistant.core.Tool;
import dev.zubinjha.minecraftassistant.core.ToolDefinition;
import dev.zubinjha.minecraftassistant.core.ToolExecutionResult;
import dev.zubinjha.minecraftassistant.core.ToolSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

public final class MinecraftWikiToolSource implements ToolSource {
    public static final String DEFAULT_API_URL = "https://minecraft.wiki/api.php";
    private static final int MAX_TOOL_TEXT = 48 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern TAG = Pattern.compile("<[^>]+>");

    private final MediaWikiClient client;
    private final List<Tool> tools;

    public MinecraftWikiToolSource(String apiUrl, Executor executor) {
        this.client = new MediaWikiClient(apiUrl, Objects.requireNonNull(executor, "executor"));
        this.tools = List.of(
                tool("minecraft_wiki_search", searchSchema(), this::search,
                        "Search Minecraft Wiki for simple item, block, entity, structure, or mechanic names."),
                tool("minecraft_wiki_get_page", pageSchema(), this::getPage,
                        "Get a Wiki page lead and section list, or its full plaintext content. Redirects resolve automatically."),
                tool("minecraft_wiki_get_section", sectionSchema(), this::getSection,
                        "Get one page section by the index returned from minecraft_wiki_get_page."),
                tool("minecraft_wiki_get_categories", categoriesSchema(), this::getCategories,
                        "Get a page's categories or browse category names by prefix."),
                tool("minecraft_wiki_get_category_members", categoryMembersSchema(), this::getCategoryMembers,
                        "List pages in a Minecraft Wiki category."),
                tool("minecraft_wiki_resolve_redirect", redirectSchema(), this::resolveRedirect,
                        "Resolve a possible Minecraft Wiki redirect to its canonical page title.")
        );
    }

    public static MinecraftWikiToolSource createDefault(Executor executor) {
        return new MinecraftWikiToolSource(DEFAULT_API_URL, executor);
    }

    public List<Tool> tools() {
        return tools;
    }

    public CompletionStage<String> healthCheck(CancellationToken cancellation) {
        return client.request(Map.of(
                "action", "query",
                "meta", "siteinfo",
                "siprop", "general"
        ), cancellation).thenApply(response -> response.path("query")
                .path("general")
                .path("sitename")
                .asText("Minecraft Wiki"));
    }

    @Override
    public CompletionStage<List<Tool>> loadTools(CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        return CompletableFuture.completedFuture(tools);
    }

    @Override
    public void close() {
        // Java HttpClient has no close lifecycle.
    }

    private CompletionStage<ToolExecutionResult> search(JsonNode arguments, CancellationToken cancellation) {
        String query = arguments.path("query").asText().trim();
        int limit = bounded(arguments.path("limit").asInt(10), 1, 50);
        return client.request(Map.of(
                "action", "query",
                "list", "search",
                "srsearch", query,
                "srlimit", Integer.toString(limit),
                "srprop", "snippet"
        ), cancellation).thenApply(response -> {
            ObjectNode result = JSON.createObjectNode();
            ArrayNode results = result.putArray("results");
            for (JsonNode hit : response.path("query").path("search")) {
                String title = hit.path("title").asText();
                ObjectNode item = results.addObject();
                item.put("title", title);
                item.put("snippet", plainSnippet(hit.path("snippet").asText()));
                String sourceUrl = pageUrl(title);
                item.put("source_url", sourceUrl);
                item.put("history_url", historyUrl(sourceUrl));
            }
            return output(result, "https://minecraft.wiki/w/Special:Search");
        });
    }

    private CompletionStage<ToolExecutionResult> getPage(JsonNode arguments, CancellationToken cancellation) {
        String title = arguments.path("title").asText().trim();
        boolean all = arguments.path("include_all_content").asBoolean(false);
        return loadPage(title, cancellation).thenApply(page -> {
            ObjectNode result = basePage(page);
            if (all) {
                putText(result, "text", page.text());
            } else {
                putText(result, "lead_text", page.lead());
            }
            result.set("sections", sections(page));
            return output(result, page.sourceUrl());
        });
    }

    private CompletionStage<ToolExecutionResult> getSection(JsonNode arguments, CancellationToken cancellation) {
        String title = arguments.path("title").asText().trim();
        int requested = arguments.path("section").asInt();
        return loadPage(title, cancellation).thenApply(page -> {
            final WikiPage.Section section;
            try {
                section = page.section(requested);
            } catch (IllegalArgumentException failure) {
                throw new AssistantException(ErrorCode.TOOL_FAILURE, failure.getMessage());
            }
            ObjectNode result = basePage(page);
            result.put("section_index", section.index());
            result.put("section_title", section.title());
            putText(result, "text", section.content());
            return output(result, page.sourceUrl() + (requested == 0 ? "" : "#" + anchor(section.title())));
        });
    }

    private CompletionStage<ToolExecutionResult> getCategories(JsonNode arguments, CancellationToken cancellation) {
        String title = arguments.path("title").asText("").trim();
        if (!title.isEmpty()) {
            return client.request(Map.of(
                    "action", "query",
                    "titles", title,
                    "prop", "categories|info",
                    "cllimit", "max",
                    "redirects", "1",
                    "inprop", "url"
            ), cancellation).thenApply(response -> {
                JsonNode page = firstPage(response);
                ensurePresent(page, title);
                ObjectNode result = JSON.createObjectNode();
                result.put("title", page.path("title").asText(title));
                String sourceUrl = page.path("fullurl").asText(pageUrl(title));
                result.put("source_url", sourceUrl);
                result.put("history_url", historyUrl(sourceUrl));
                ArrayNode categories = result.putArray("categories");
                page.path("categories").forEach(category ->
                        categories.add(category.path("title").asText().replaceFirst("^Category:", "")));
                return output(result, result.path("source_url").asText());
            });
        }
        String prefix = arguments.path("prefix").asText("").trim();
        int limit = bounded(arguments.path("limit").asInt(50), 1, 500);
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("action", "query");
        parameters.put("list", "allcategories");
        parameters.put("aclimit", Integer.toString(limit));
        if (!prefix.isEmpty()) {
            parameters.put("acprefix", prefix);
        }
        return client.request(parameters, cancellation).thenApply(response -> {
            ObjectNode result = JSON.createObjectNode();
            result.put("prefix", prefix);
            ArrayNode categories = result.putArray("categories");
            response.path("query").path("allcategories").forEach(category ->
                    categories.add(category.path("category").asText()));
            return output(result, "https://minecraft.wiki/w/Category:Categories");
        });
    }

    private CompletionStage<ToolExecutionResult> getCategoryMembers(
            JsonNode arguments,
            CancellationToken cancellation
    ) {
        String category = arguments.path("category").asText().trim().replaceFirst("^Category:", "");
        int limit = bounded(arguments.path("limit").asInt(100), 1, 500);
        return client.request(Map.of(
                "action", "query",
                "list", "categorymembers",
                "cmtitle", "Category:" + category,
                "cmlimit", Integer.toString(limit),
                "cmnamespace", "0"
        ), cancellation).thenApply(response -> {
            ObjectNode result = JSON.createObjectNode();
            result.put("category", category);
            ArrayNode members = result.putArray("members");
            response.path("query").path("categorymembers").forEach(member ->
                    members.add(member.path("title").asText()));
            return output(result, pageUrl("Category:" + category));
        });
    }

    private CompletionStage<ToolExecutionResult> resolveRedirect(
            JsonNode arguments,
            CancellationToken cancellation
    ) {
        String title = arguments.path("title").asText().trim();
        return client.request(Map.of(
                "action", "query",
                "titles", title,
                "redirects", "1",
                "prop", "info",
                "inprop", "url"
        ), cancellation).thenApply(response -> {
            JsonNode page = firstPage(response);
            ensurePresent(page, title);
            String resolved = page.path("title").asText(title);
            boolean redirected = response.path("query").path("redirects").isArray()
                    && !response.path("query").path("redirects").isEmpty();
            ObjectNode result = JSON.createObjectNode();
            result.put("original_title", title);
            result.put("resolved_title", resolved);
            result.put("is_redirect", redirected);
            String sourceUrl = page.path("fullurl").asText(pageUrl(resolved));
            result.put("source_url", sourceUrl);
            result.put("history_url", historyUrl(sourceUrl));
            return output(result, result.path("source_url").asText());
        });
    }

    private CompletionStage<WikiPage> loadPage(String title, CancellationToken cancellation) {
        return client.request(Map.of(
                "action", "query",
                "titles", title,
                "redirects", "1",
                "prop", "extracts|info",
                "explaintext", "1",
                "exsectionformat", "plain",
                "inprop", "url"
        ), cancellation).thenApply(response -> {
            JsonNode page = firstPage(response);
            ensurePresent(page, title);
            String resolved = page.path("title").asText(title);
            return WikiPage.parse(
                    resolved,
                    page.path("fullurl").asText(pageUrl(resolved)),
                    page.path("extract").asText("")
            );
        });
    }

    private static Tool tool(
            String name,
            ObjectNode schema,
            BiFunction<JsonNode, CancellationToken, CompletionStage<ToolExecutionResult>> operation,
            String description
    ) {
        ToolDefinition definition = new ToolDefinition(name, description, schema);
        return new Tool() {
            @Override
            public ToolDefinition definition() {
                return definition;
            }

            @Override
            public CompletionStage<ToolExecutionResult> execute(
                    JsonNode arguments,
                    CancellationToken cancellation
            ) {
                return operation.apply(arguments, cancellation);
            }
        };
    }

    private static ObjectNode searchSchema() {
        ObjectNode schema = objectSchema();
        property(schema, "query", "string", "Simple Minecraft Wiki search term").put("maxLength", 300);
        property(schema, "limit", "integer", "Maximum results, from 1 to 50")
                .put("minimum", 1).put("maximum", 50).put("default", 10);
        schema.putArray("required").add("query");
        return schema;
    }

    private static ObjectNode pageSchema() {
        ObjectNode schema = objectSchema();
        property(schema, "title", "string", "Exact Minecraft Wiki page title").put("maxLength", 300);
        property(schema, "include_all_content", "boolean", "Return full plaintext instead of the lead")
                .put("default", false);
        schema.putArray("required").add("title");
        return schema;
    }

    private static ObjectNode sectionSchema() {
        ObjectNode schema = objectSchema();
        property(schema, "title", "string", "Exact Minecraft Wiki page title").put("maxLength", 300);
        property(schema, "section", "integer", "Section index from minecraft_wiki_get_page")
                .put("minimum", 0);
        schema.putArray("required").add("title").add("section");
        return schema;
    }

    private static ObjectNode categoriesSchema() {
        ObjectNode schema = objectSchema();
        property(schema, "title", "string", "Page whose categories should be returned").put("maxLength", 300);
        property(schema, "prefix", "string", "Category-name prefix used when no title is supplied")
                .put("maxLength", 200);
        property(schema, "limit", "integer", "Maximum category names")
                .put("minimum", 1).put("maximum", 500).put("default", 50);
        return schema;
    }

    private static ObjectNode categoryMembersSchema() {
        ObjectNode schema = objectSchema();
        property(schema, "category", "string", "Category name without the Category: prefix")
                .put("maxLength", 300);
        property(schema, "limit", "integer", "Maximum page titles")
                .put("minimum", 1).put("maximum", 500).put("default", 100);
        schema.putArray("required").add("category");
        return schema;
    }

    private static ObjectNode redirectSchema() {
        ObjectNode schema = objectSchema();
        property(schema, "title", "string", "Page title that may redirect").put("maxLength", 300);
        schema.putArray("required").add("title");
        return schema;
    }

    private static ObjectNode objectSchema() {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties");
        schema.put("additionalProperties", false);
        return schema;
    }

    private static ObjectNode property(ObjectNode schema, String name, String type, String description) {
        ObjectNode property = schema.withObject("properties").putObject(name);
        property.put("type", type);
        property.put("description", description);
        return property;
    }

    private static ObjectNode basePage(WikiPage page) {
        ObjectNode result = JSON.createObjectNode();
        result.put("title", page.title());
        result.put("source_url", page.sourceUrl());
        result.put("history_url", page.historyUrl());
        return result;
    }

    private static ArrayNode sections(WikiPage page) {
        ArrayNode sections = JSON.createArrayNode();
        for (WikiPage.Section section : page.sections()) {
            ObjectNode item = sections.addObject();
            item.put("index", section.index());
            item.put("title", section.title());
            item.put("level", section.level());
        }
        return sections;
    }

    private static void putText(ObjectNode object, String field, String text) {
        if (text.length() <= MAX_TOOL_TEXT) {
            object.put(field, text);
            object.put("truncated", false);
            return;
        }
        object.put(field, text.substring(0, MAX_TOOL_TEXT) + "\n[Content truncated]");
        object.put("truncated", true);
    }

    private static ToolExecutionResult output(ObjectNode value, String sourceUrl) {
        return new ToolExecutionResult(value.toString(), Map.of("source_url", sourceUrl));
    }

    private static JsonNode firstPage(JsonNode response) {
        JsonNode pages = response.path("query").path("pages");
        if (!pages.isArray() || pages.isEmpty()) {
            throw new AssistantException(ErrorCode.TOOL_FAILURE, "Minecraft Wiki page was not found");
        }
        return pages.get(0);
    }

    private static void ensurePresent(JsonNode page, String requestedTitle) {
        if (page.path("missing").asBoolean(false)) {
            throw new AssistantException(
                    ErrorCode.TOOL_FAILURE,
                    "Minecraft Wiki page was not found: " + requestedTitle
            );
        }
    }

    private static int bounded(int value, int minimum, int maximum) {
        return Math.min(maximum, Math.max(minimum, value));
    }

    private static String pageUrl(String title) {
        return "https://minecraft.wiki/w/" + java.net.URLEncoder
                .encode(title.trim().replace(' ', '_'), java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("%2F", "/");
    }

    private static String historyUrl(String sourceUrl) {
        return sourceUrl + (sourceUrl.contains("?") ? "&" : "?") + "action=history";
    }

    private static String plainSnippet(String html) {
        return TAG.matcher(html)
                .replaceAll("")
                .replace("&quot;", "\"")
                .replace("&#039;", "'")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .trim();
    }

    private static String anchor(String title) {
        return title.trim().replace(' ', '_').replace("#", "%23");
    }
}
