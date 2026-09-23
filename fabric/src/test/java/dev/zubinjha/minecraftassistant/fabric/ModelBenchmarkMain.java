package dev.zubinjha.minecraftassistant.fabric;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.zubinjha.minecraftassistant.core.Agent;
import dev.zubinjha.minecraftassistant.core.AgentEventListener;
import dev.zubinjha.minecraftassistant.core.AgentOptions;
import dev.zubinjha.minecraftassistant.core.AssistantRequest;
import dev.zubinjha.minecraftassistant.core.AssistantResult;
import dev.zubinjha.minecraftassistant.core.CancellationToken;
import dev.zubinjha.minecraftassistant.core.ConversationMessage;
import dev.zubinjha.minecraftassistant.core.LlmProvider;
import dev.zubinjha.minecraftassistant.core.ModelRequest;
import dev.zubinjha.minecraftassistant.core.ModelResponse;
import dev.zubinjha.minecraftassistant.core.ToolCall;
import dev.zubinjha.minecraftassistant.openrouter.OpenRouterModel;
import dev.zubinjha.minecraftassistant.openrouter.OpenRouterProvider;
import dev.zubinjha.minecraftassistant.openrouter.OpenRouterSettings;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class ModelBenchmarkMain {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter RUN_ID = DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);
    private static final double DEFAULT_BUDGET_USD = 2.0;
    private static final double MAX_BUDGET_USD = 5.0;

    private ModelBenchmarkMain() {
    }

    public static void main(String[] arguments) throws Exception {
        List<String> args = List.of(arguments);
        String command = args.isEmpty() || args.getFirst().startsWith("--") ? "run" : args.getFirst();
        List<String> options = args.isEmpty() || args.getFirst().startsWith("--") ? args : args.subList(1, args.size());
        switch (command) {
            case "run" -> run(parseOptions(options));
            case "report" -> report(reportDirectory(options));
            default -> throw new IllegalArgumentException("Use run or report, not " + command);
        }
    }

    private static void run(Map<String, String> options) throws Exception {
        BenchmarkSuite suite = BenchmarkSuite.load(BenchmarkSuite.DEFAULT_PATH);
        List<BenchmarkSuite.Model> models = selectedModels(suite, options.get("models"));
        List<BenchmarkSuite.Case> cases = selectedCases(suite, options.get("questions"));
        double budget = parseBudget(options.get("budget"));
        String apiKey = firstNonBlank(
                System.getenv("OPENROUTER_API_KEY"),
                System.getenv("MINECRAFT_ASSISTANT_OPENROUTER_API_KEY")
        );
        if (apiKey == null) {
            throw new IllegalStateException("Set OPENROUTER_API_KEY before running the live benchmark");
        }

        Path output = options.containsKey("resume")
                ? Path.of(options.get("resume"))
                : Path.of("benchmark-results", RUN_ID.format(Instant.now()));
        Files.createDirectories(output);
        Path rawPath = output.resolve("raw-results.json");
        ObjectNode raw = Files.exists(rawPath)
                ? (ObjectNode) JSON.readTree(rawPath.toFile())
                : newRawReport(suite, models, cases);
        raw.set("selected_models", JSON.valueToTree(models));
        raw.set("selected_cases", JSON.valueToTree(cases.stream().map(BenchmarkSuite.Case::id).toList()));

        OpenRouterProvider catalogProvider = new OpenRouterProvider(OpenRouterSettings.defaults(apiKey));
        List<OpenRouterModel> catalog = catalogProvider.listModels(CancellationToken.NONE)
                .toCompletableFuture().get(60, TimeUnit.SECONDS);
        validateModels(models, catalog);
        raw.set("pricing_snapshot", pricingSnapshot(models, catalog));
        writeJson(rawPath, raw);

        MeteredProvider provider = new MeteredProvider(new OpenRouterProvider(OpenRouterSettings.defaults(apiKey)));
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
        try {
            for (BenchmarkSuite.Model model : models) {
                if (modelComplete(raw, model, cases)) {
                    System.out.println("Skipping completed model " + model.key());
                    continue;
                }
                double projected = projectedRemainingCost(raw, model, cases);
                if (totalCost(raw) + projected > budget) {
                    System.out.printf(Locale.ROOT,
                            "Stopping before %s: projected total $%.4f exceeds $%.2f budget%n",
                            model.id(), totalCost(raw) + projected, budget);
                    break;
                }
                System.out.println("Benchmarking " + model.id() + " at " + model.reasoningEffort());
                for (BenchmarkSuite.Case scenario : cases) {
                    if (caseComplete(raw, model, scenario.id())) {
                        continue;
                    }
                    ObjectNode row = executeCase(provider, scheduler, model, scenario);
                    raw.withArray("runs").add(row);
                    raw.put("actual_cost_usd", totalCost(raw));
                    writeJson(rawPath, raw);
                    System.out.printf(Locale.ROOT, "  %-28s %5.2fs  $%.6f%n",
                            scenario.id(), row.path("latency_seconds").asDouble(), row.path("cost_usd").asDouble());
                    if (totalCost(raw) >= budget) {
                        System.out.println("Stopping: live benchmark reached its $" + budget + " budget.");
                        break;
                    }
                }
                if (totalCost(raw) >= budget) {
                    break;
                }
            }
        } finally {
            scheduler.shutdownNow();
            scheduler.awaitTermination(2, TimeUnit.SECONDS);
        }

        raw.put("actual_cost_usd", totalCost(raw));
        writeJson(rawPath, raw);
        boolean complete = models.stream().allMatch(model -> modelComplete(raw, model, cases));
        if (complete) {
            createReview(output, suite, raw);
        } else {
            System.out.println("Run is incomplete; resume it before generating the blind review.");
        }
        System.out.println("Raw results: " + rawPath.toAbsolutePath());
        if (complete) {
            System.out.println("Blind review: " + output.resolve("review.json").toAbsolutePath());
        }
    }

    private static ObjectNode executeCase(
            MeteredProvider provider,
            ScheduledExecutorService scheduler,
            BenchmarkSuite.Model model,
            BenchmarkSuite.Case scenario
    ) {
        BenchmarkTools tools = new BenchmarkTools(scenario);
        provider.beginCase();
        long started = System.nanoTime();
        ObjectNode row = JSON.createObjectNode();
        row.put("model_id", model.id());
        row.put("reasoning_effort", model.reasoningEffort());
        row.put("case_id", scenario.id());
        row.put("question", scenario.question());
        row.set("history", JSON.valueToTree(scenario.history()));
        try {
            Agent agent = new Agent(
                    provider,
                    tools.registry(),
                    AgentOptions.DEFAULT,
                    scheduler,
                    AgentEventListener.NONE
            );
            AssistantResult result = agent.ask(new AssistantRequest(
                    model.id(), model.reasoningEffort(), MinecraftAssistantRuntime.systemPromptForTest(),
                    scenario.conversationHistory(), scenario.question()
            ), CancellationToken.NONE).toCompletableFuture().get(130, TimeUnit.SECONDS);
            row.put("status", "completed");
            row.put("answer", result.text());
            row.put("provider_turns", result.providerTurns());
            row.put("tool_call_count", result.toolCalls());
            row.set("conversation", conversation(result.conversation()));
        } catch (Exception failure) {
            Throwable cause = unwrap(failure);
            row.put("status", "failed");
            row.put("answer", "");
            row.put("error", safeError(cause));
        }
        row.put("latency_seconds", rounded((System.nanoTime() - started) / 1_000_000_000.0, 3));
        row.set("tool_invocations", JSON.valueToTree(tools.invocations()));
        List<CapturedResponse> responses = provider.endCase();
        row.set("provider_responses", JSON.valueToTree(responses));
        row.put("cost_usd", rounded(responses.stream().mapToDouble(CapturedResponse::cost).sum(), 9));
        row.put("prompt_tokens", responses.stream().mapToLong(CapturedResponse::promptTokens).sum());
        row.put("completion_tokens", responses.stream().mapToLong(CapturedResponse::completionTokens).sum());
        return row;
    }

    private static ArrayNode conversation(List<ConversationMessage> messages) {
        ArrayNode result = JSON.createArrayNode();
        for (ConversationMessage message : messages) {
            ObjectNode row = result.addObject();
            switch (message) {
                case ConversationMessage.System system -> row.put("role", "system").put("text", system.text());
                case ConversationMessage.User user -> row.put("role", "user").put("text", user.text());
                case ConversationMessage.Assistant assistant -> {
                    row.put("role", "assistant").put("text", assistant.text());
                    ArrayNode calls = row.putArray("tool_calls");
                    for (ToolCall call : assistant.toolCalls()) {
                        calls.addObject().put("id", call.callId()).put("name", call.name())
                                .set("arguments", call.arguments());
                    }
                }
                case ConversationMessage.ToolResult tool -> row.put("role", "tool")
                        .put("name", tool.toolName()).put("text", tool.content()).put("success", tool.success());
            }
        }
        return result;
    }

    static ObjectNode newRawReport(
            BenchmarkSuite suite,
            List<BenchmarkSuite.Model> models,
            List<BenchmarkSuite.Case> cases
    ) {
        ObjectNode root = JSON.createObjectNode();
        root.put("suite_version", suite.version());
        root.put("suite_name", suite.name());
        root.put("started_at", Instant.now().toString());
        root.put("openrouter_base_url", OpenRouterSettings.DEFAULT_BASE_URI.toString());
        root.put("system_prompt", MinecraftAssistantRuntime.systemPromptForTest());
        root.set("selected_models", JSON.valueToTree(models));
        root.set("selected_cases", JSON.valueToTree(cases.stream().map(BenchmarkSuite.Case::id).toList()));
        root.putArray("runs");
        root.put("actual_cost_usd", 0.0);
        return root;
    }

    private static ArrayNode pricingSnapshot(
            List<BenchmarkSuite.Model> selected,
            List<OpenRouterModel> catalog
    ) {
        Map<String, OpenRouterModel> byId = new HashMap<>();
        catalog.forEach(model -> byId.put(model.id(), model));
        ArrayNode prices = JSON.createArrayNode();
        for (BenchmarkSuite.Model requested : selected) {
            OpenRouterModel model = byId.get(requested.id());
            prices.addObject().put("model_id", model.id())
                    .put("reasoning_effort", requested.reasoningEffort()).put("name", model.name())
                    .put("prompt_price_per_token", model.promptPrice())
                    .put("completion_price_per_token", model.completionPrice())
                    .set("supported_parameters", JSON.valueToTree(model.supportedParameters()));
        }
        return prices;
    }

    static void validateModels(List<BenchmarkSuite.Model> selected, List<OpenRouterModel> catalog) {
        Map<String, OpenRouterModel> byId = new HashMap<>();
        catalog.forEach(model -> byId.put(model.id(), model));
        for (BenchmarkSuite.Model requested : selected) {
            OpenRouterModel model = byId.get(requested.id());
            if (model == null) {
                throw new IllegalStateException("OpenRouter does not currently list " + requested.id());
            }
            if (!model.supportsTools()) {
                throw new IllegalStateException(requested.id() + " does not advertise tool support");
            }
            if (!model.supportedParameters().contains("reasoning_effort")
                    && !model.supportedParameters().contains("reasoning")) {
                throw new IllegalStateException(requested.id() + " does not advertise reasoning support");
            }
        }
    }

    static void createReview(Path output, BenchmarkSuite suite, ObjectNode raw) throws IOException {
        Path reviewPath = output.resolve("review.json");
        if (Files.exists(reviewPath) && reviewCoversSelectedModels(output, raw)) {
            JsonNode existing = JSON.readTree(reviewPath.toFile());
            for (JsonNode candidate : existing.path("candidates")) {
                for (JsonNode item : candidate.path("cases")) {
                    if (item.path("correctness").isIntegralNumber()
                            || item.path("tool_context").isIntegralNumber()
                            || item.path("chat_quality").isIntegralNumber()) {
                        return;
                    }
                }
            }
        }
        List<BenchmarkSuite.Model> models = new ArrayList<>();
        raw.path("selected_models").forEach(node -> models.add(new BenchmarkSuite.Model(
                node.path("id").asText(), node.path("reasoning_effort").asText("low")
        )));
        Collections.shuffle(models, new Random(raw.path("started_at").asText().hashCode()));
        ObjectNode mapping = JSON.createObjectNode();
        ObjectNode review = JSON.createObjectNode();
        review.put("suite_version", suite.version());
        review.put("instructions", "Score correctness 0-2, tool_context 0-2, chat_quality 0-1. Explain every deduction in notes.");
        ArrayNode candidates = review.putArray("candidates");
        for (int index = 0; index < models.size(); index++) {
            String label = String.valueOf((char) ('A' + index));
            BenchmarkSuite.Model model = models.get(index);
            mapping.set(label, JSON.createObjectNode().put("model_id", model.id())
                    .put("reasoning_effort", model.reasoningEffort()));
            ObjectNode candidate = candidates.addObject().put("label", label);
            ArrayNode reviews = candidate.putArray("cases");
            for (BenchmarkSuite.Case scenario : suite.cases()) {
                JsonNode run = findRun(raw, model.id(), model.reasoningEffort(), scenario.id());
                if (run == null) {
                    continue;
                }
                ObjectNode item = reviews.addObject();
                item.put("case_id", scenario.id()).put("category", scenario.category())
                        .put("question", scenario.question()).put("criteria", scenario.criteria())
                        .put("status", run.path("status").asText()).put("answer", run.path("answer").asText());
                item.set("tool_invocations", run.path("tool_invocations").deepCopy());
                item.putNull("correctness").putNull("tool_context").putNull("chat_quality");
                item.put("notes", "");
            }
        }
        writeJson(output.resolve("candidate-map.json"), mapping);
        writeJson(reviewPath, review);
    }

    private static boolean reviewCoversSelectedModels(Path output, ObjectNode raw) throws IOException {
        Path mappingPath = output.resolve("candidate-map.json");
        if (!Files.exists(mappingPath)) {
            return false;
        }
        JsonNode mapping = JSON.readTree(mappingPath.toFile());
        Set<String> mapped = new HashSet<>();
        mapping.forEach(node -> {
            if (node.isTextual()) {
                mapped.add(node.asText() + "@low");
            } else {
                mapped.add(node.path("model_id").asText() + "@"
                        + node.path("reasoning_effort").asText("low"));
            }
        });
        Set<String> selected = new HashSet<>();
        raw.path("selected_models").forEach(node -> selected.add(node.path("id").asText() + "@"
                + node.path("reasoning_effort").asText("low")));
        return mapped.equals(selected);
    }

    static void report(Path output) throws IOException {
        BenchmarkSuite suite = BenchmarkSuite.load(BenchmarkSuite.DEFAULT_PATH);
        ObjectNode raw = (ObjectNode) JSON.readTree(output.resolve("raw-results.json").toFile());
        JsonNode mapping = JSON.readTree(output.resolve("candidate-map.json").toFile());
        JsonNode review = JSON.readTree(output.resolve("review.json").toFile());
        ArrayNode rows = JSON.createArrayNode();
        for (JsonNode candidate : review.path("candidates")) {
            String label = candidate.path("label").asText();
            JsonNode mapped = mapping.path(label);
            String model = mapped.isTextual() ? mapped.asText() : mapped.path("model_id").asText();
            String reasoningEffort = mapped.isTextual()
                    ? "low"
                    : mapped.path("reasoning_effort").asText("low");
            int score = 0;
            int count = 0;
            for (JsonNode item : candidate.path("cases")) {
                score += requiredScore(item, "correctness", 2);
                score += requiredScore(item, "tool_context", 2);
                score += requiredScore(item, "chat_quality", 1);
                if (item.path("notes").asText().isBlank() && item.path("correctness").asInt() < 2
                        || item.path("notes").asText().isBlank() && item.path("tool_context").asInt() < 2
                        || item.path("notes").asText().isBlank() && item.path("chat_quality").asInt() < 1) {
                    throw new IllegalArgumentException("Case " + item.path("case_id").asText()
                            + " needs deduction notes");
                }
                count++;
            }
            if (count != suite.cases().size()) {
                throw new IllegalArgumentException(model + " has " + count + " reviewed cases, expected 20");
            }
            double cost = modelCost(raw, model, reasoningEffort);
            double promptsPerDollar = cost == 0.0 ? 0.0 : suite.cases().size() / cost;
            rows.addObject().put("model_id", model).put("reasoning_effort", reasoningEffort)
                    .put("score", score).put("questions", count)
                    .put("cost_usd", rounded(cost, 9)).put("prompts_per_dollar", rounded(promptsPerDollar, 2))
                    .put("average_latency_seconds", rounded(modelLatency(raw, model, reasoningEffort), 3));
        }
        List<JsonNode> sorted = new ArrayList<>();
        rows.forEach(sorted::add);
        sorted.sort((left, right) -> {
            int score = Integer.compare(right.path("score").asInt(), left.path("score").asInt());
            return score != 0 ? score : Double.compare(
                    right.path("prompts_per_dollar").asDouble(), left.path("prompts_per_dollar").asDouble());
        });
        JsonNode bestQuality = sorted.getFirst();
        JsonNode recommended = recommendedModel(sorted);

        ObjectNode summary = JSON.createObjectNode();
        summary.put("suite_version", suite.version()).put("generated_at", Instant.now().toString())
                .put("questions_per_model", suite.cases().size())
                .put("recommended_model", recommended.path("model_id").asText())
                .put("recommended_reasoning_effort", recommended.path("reasoning_effort").asText())
                .put("best_quality_model", bestQuality.path("model_id").asText())
                .put("best_quality_reasoning_effort", bestQuality.path("reasoning_effort").asText());
        ArrayNode summaryRows = summary.putArray("models");
        sorted.forEach(summaryRows::add);
        writeJson(output.resolve("summary.json"), summary);
        Files.writeString(output.resolve("summary.md"), markdownSummary(summary), StandardCharsets.UTF_8);
        System.out.println(markdownSummary(summary));
    }

    static JsonNode recommendedModel(List<JsonNode> scoreSortedRows) {
        int topScore = scoreSortedRows.getFirst().path("score").asInt();
        return scoreSortedRows.stream().filter(row -> row.path("score").asInt() >= topScore - 5)
                .max((left, right) -> Double.compare(
                        left.path("prompts_per_dollar").asDouble(), right.path("prompts_per_dollar").asDouble()))
                .orElse(scoreSortedRows.getFirst());
    }

    static String markdownSummary(JsonNode summary) {
        String recommended = summary.path("recommended_model").asText();
        String recommendedEffort = summary.path("recommended_reasoning_effort").asText();
        String bestQuality = summary.path("best_quality_model").asText();
        String bestQualityEffort = summary.path("best_quality_reasoning_effort").asText();
        StringBuilder markdown = new StringBuilder();
        markdown.append("| Provider | Model | Reasoning | Model ID to paste | Score | ~prompts/$ | Avg. latency |\n")
                .append("| --- | --- | --- | --- | ---: | ---: | ---: |\n");
        List<JsonNode> rows = new ArrayList<>();
        summary.path("models").forEach(rows::add);
        rows.sort(ModelBenchmarkMain::compareDocumentationOrder);
        for (JsonNode row : rows) {
            String id = row.path("model_id").asText();
            String effort = row.path("reasoning_effort").asText();
            String label = "[" + displayName(id) + "](https://openrouter.ai/" + id + ")";
            if (id.equals(recommended) && effort.equals(recommendedEffort)) {
                label += " **(Recommended)**";
            } else if (id.equals(bestQuality) && effort.equals(bestQualityEffort)) {
                label += " **(Best quality)**";
            }
            markdown.append("| ").append(providerName(id)).append(" | ").append(label)
                    .append(" | ").append(capitalize(effort)).append(" | `").append(id).append("` | ")
                    .append(row.path("score").asInt()).append("/100 | ~")
                    .append(formatTwoSignificant(row.path("prompts_per_dollar").asDouble())).append(" | ")
                    .append(String.format(Locale.ROOT, "%.2fs", row.path("average_latency_seconds").asDouble()))
                    .append(" |\n");
        }
        return markdown.toString();
    }

    private static int compareDocumentationOrder(JsonNode left, JsonNode right) {
        int model = Integer.compare(documentationRank(left.path("model_id").asText()),
                documentationRank(right.path("model_id").asText()));
        if (model != 0) {
            return model;
        }
        return Integer.compare(effortRank(left.path("reasoning_effort").asText()),
                effortRank(right.path("reasoning_effort").asText()));
    }

    private static int documentationRank(String id) {
        return switch (id) {
            case "openai/gpt-6-luna" -> 0;
            case "openai/gpt-6-sol" -> 1;
            case "openai/gpt-5.6-luna" -> 2;
            case "openai/gpt-5.6-sol" -> 3;
            case "openai/gpt-5-mini" -> 4;
            case "openai/gpt-5-nano" -> 5;
            case "anthropic/claude-haiku-4.5" -> 6;
            case "anthropic/claude-opus-5" -> 7;
            case "google/gemini-3.8-flash" -> 8;
            case "deepseek/deepseek-v4.1-flash" -> 9;
            case "inception/mercury-2.5" -> 10;
            default -> Integer.MAX_VALUE;
        };
    }

    private static int effortRank(String effort) {
        return switch (effort) {
            case "low" -> 0;
            case "medium" -> 1;
            case "high" -> 2;
            default -> 3;
        };
    }

    private static String providerName(String id) {
        return switch (id.substring(0, id.indexOf('/'))) {
            case "openai" -> "OpenAI";
            case "anthropic" -> "Anthropic";
            case "google" -> "Google";
            case "deepseek" -> "DeepSeek";
            case "inception" -> "Inception";
            default -> id.substring(0, id.indexOf('/'));
        };
    }

    private static String capitalize(String value) {
        return value.isEmpty() ? value : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static String displayName(String id) {
        return switch (id) {
            case "openai/gpt-6-luna" -> "GPT-6 Luna";
            case "openai/gpt-6-sol" -> "GPT-6 Sol";
            case "openai/gpt-5.6-luna" -> "GPT-5.6 Luna";
            case "openai/gpt-5.6-sol" -> "GPT-5.6 Sol";
            case "anthropic/claude-haiku-4.5" -> "Claude Haiku 4.5";
            case "anthropic/claude-opus-5" -> "Claude Opus 5";
            case "openai/gpt-5-mini" -> "GPT-5 Mini";
            case "openai/gpt-5-nano" -> "GPT-5 Nano";
            case "google/gemini-3.8-flash" -> "Gemini 3.8 Flash";
            case "deepseek/deepseek-v4.1-flash" -> "DeepSeek V4.1 Flash";
            case "inception/mercury-2.5" -> "Mercury 2.5";
            default -> id;
        };
    }

    private static int requiredScore(JsonNode item, String field, int maximum) {
        if (!item.has(field) || !item.get(field).isIntegralNumber()) {
            throw new IllegalArgumentException(item.path("case_id").asText() + " is missing " + field);
        }
        int value = item.path(field).asInt();
        if (value < 0 || value > maximum) {
            throw new IllegalArgumentException(field + " must be between 0 and " + maximum);
        }
        return value;
    }

    private static double modelCost(JsonNode raw, String model, String reasoningEffort) {
        double total = 0.0;
        for (JsonNode run : raw.path("runs")) {
            if (model.equals(run.path("model_id").asText())
                    && reasoningEffort.equals(run.path("reasoning_effort").asText("low"))) {
                total += run.path("cost_usd").asDouble();
            }
        }
        return total;
    }

    private static double modelLatency(JsonNode raw, String model, String reasoningEffort) {
        double total = 0.0;
        int count = 0;
        for (JsonNode run : raw.path("runs")) {
            if (model.equals(run.path("model_id").asText())
                    && reasoningEffort.equals(run.path("reasoning_effort").asText("low"))) {
                total += run.path("latency_seconds").asDouble();
                count++;
            }
        }
        return count == 0 ? 0.0 : total / count;
    }

    static String formatTwoSignificant(double value) {
        if (value == 0.0) {
            return "N/A";
        }
        BigDecimal rounded = BigDecimal.valueOf(value).round(new java.math.MathContext(2, RoundingMode.HALF_UP));
        return String.format(Locale.ROOT, "%,.0f", rounded.doubleValue());
    }

    private static List<BenchmarkSuite.Model> selectedModels(BenchmarkSuite suite, String selection) {
        if (selection == null || selection.isBlank()) {
            return suite.models();
        }
        Set<String> requested = Set.copyOf(split(selection));
        List<BenchmarkSuite.Model> selected = suite.models().stream()
                .filter(model -> requested.contains(model.id()) || requested.contains(model.key()))
                .toList();
        Set<String> matched = new HashSet<>();
        selected.forEach(model -> {
            if (requested.contains(model.id())) {
                matched.add(model.id());
            }
            if (requested.contains(model.key())) {
                matched.add(model.key());
            }
        });
        Set<String> unknown = new HashSet<>(requested);
        unknown.removeAll(matched);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Unknown benchmark model variants: " + unknown);
        }
        return selected;
    }

    private static List<BenchmarkSuite.Case> selectedCases(BenchmarkSuite suite, String selection) {
        if (selection == null || selection.isBlank()) {
            return suite.cases();
        }
        return split(selection).stream().map(suite::caseById).toList();
    }

    private static List<String> split(String value) {
        return List.of(value.split(",")).stream().map(String::trim).filter(item -> !item.isEmpty()).toList();
    }

    private static Map<String, String> parseOptions(List<String> arguments) {
        Map<String, String> options = new LinkedHashMap<>();
        for (String argument : arguments) {
            if (!argument.startsWith("--") || !argument.contains("=")) {
                throw new IllegalArgumentException("Options must use --name=value: " + argument);
            }
            int separator = argument.indexOf('=');
            options.put(argument.substring(2, separator), argument.substring(separator + 1));
        }
        Set<String> supported = Set.of("models", "questions", "resume", "budget");
        Set<String> unknown = new HashSet<>(options.keySet());
        unknown.removeAll(supported);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Unknown options: " + unknown);
        }
        return options;
    }

    private static Path reportDirectory(List<String> arguments) {
        if (arguments.size() != 1) {
            throw new IllegalArgumentException("report requires one benchmark-results directory");
        }
        return Path.of(arguments.getFirst());
    }

    private static double parseBudget(String value) {
        double budget = value == null ? DEFAULT_BUDGET_USD : Double.parseDouble(value);
        if (!Double.isFinite(budget) || budget <= 0.0 || budget > MAX_BUDGET_USD) {
            throw new IllegalArgumentException("Benchmark budget must be greater than zero and at most $5.00");
        }
        return budget;
    }

    static boolean modelComplete(
            ObjectNode raw,
            BenchmarkSuite.Model model,
            List<BenchmarkSuite.Case> cases
    ) {
        return cases.stream().allMatch(scenario -> caseComplete(raw, model, scenario.id()));
    }

    static boolean caseComplete(ObjectNode raw, BenchmarkSuite.Model model, String caseId) {
        return findRun(raw, model.id(), model.reasoningEffort(), caseId) != null;
    }

    private static JsonNode findRun(ObjectNode raw, String model, String reasoningEffort, String caseId) {
        for (JsonNode run : raw.path("runs")) {
            if (model.equals(run.path("model_id").asText())
                    && reasoningEffort.equals(run.path("reasoning_effort").asText("low"))
                    && caseId.equals(run.path("case_id").asText())) {
                return run;
            }
        }
        return null;
    }

    private static double totalCost(ObjectNode raw) {
        double total = 0.0;
        for (JsonNode run : raw.path("runs")) {
            total += run.path("cost_usd").asDouble();
        }
        return total;
    }

    static double projectedRemainingCost(
            ObjectNode raw,
            BenchmarkSuite.Model model,
            List<BenchmarkSuite.Case> cases
    ) {
        long remaining = cases.stream().filter(scenario -> !caseComplete(raw, model, scenario.id())).count();
        if (remaining == 0) {
            return 0.0;
        }
        double familyCost = 0.0;
        int familyCases = 0;
        double allCost = 0.0;
        int allCases = 0;
        for (JsonNode run : raw.path("runs")) {
            double cost = run.path("cost_usd").asDouble();
            allCost += cost;
            allCases++;
            if (model.id().equals(run.path("model_id").asText())) {
                familyCost += cost;
                familyCases++;
            }
        }
        double perCase = familyCases > 0 ? familyCost / familyCases
                : allCases > 0 ? allCost / allCases
                : 0.0005;
        return perCase * remaining;
    }

    private static void writeJson(Path path, JsonNode value) throws IOException {
        JSON.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), value);
    }

    private static double rounded(double value, int places) {
        return BigDecimal.valueOf(value).setScale(places, RoundingMode.HALF_UP).doubleValue();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String safeError(Throwable failure) {
        String message = failure.getMessage();
        return failure.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private static final class MeteredProvider implements LlmProvider {
        private final LlmProvider delegate;
        private final List<CapturedResponse> current = new ArrayList<>();

        private MeteredProvider(LlmProvider delegate) {
            this.delegate = delegate;
        }

        synchronized void beginCase() {
            current.clear();
        }

        synchronized List<CapturedResponse> endCase() {
            return List.copyOf(current);
        }

        @Override
        public CompletionStage<ModelResponse> generate(ModelRequest request, CancellationToken cancellation) {
            return delegate.generate(request, cancellation).thenApply(response -> {
                JsonNode usage = response.providerState();
                CapturedResponse captured = new CapturedResponse(
                        response.text(), response.finishReason(), response.toolCalls(), usage.deepCopy(),
                        usage.path("cost").asDouble(0.0),
                        usage.path("prompt_tokens").asLong(0), usage.path("completion_tokens").asLong(0)
                );
                synchronized (this) {
                    current.add(captured);
                }
                return response;
            });
        }
    }

    record CapturedResponse(
            String text,
            String finishReason,
            List<ToolCall> toolCalls,
            JsonNode usage,
            double cost,
            long promptTokens,
            long completionTokens
    ) {
    }
}
