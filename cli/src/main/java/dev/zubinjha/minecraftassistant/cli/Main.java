package dev.zubinjha.minecraftassistant.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.zubinjha.minecraftassistant.codex.CodexAccountStatus;
import dev.zubinjha.minecraftassistant.codex.CodexAppServerAssistant;
import dev.zubinjha.minecraftassistant.codex.CodexAppServerSettings;
import dev.zubinjha.minecraftassistant.codex.CodexModel;
import dev.zubinjha.minecraftassistant.core.AssistantException;
import dev.zubinjha.minecraftassistant.core.AssistantRequest;
import dev.zubinjha.minecraftassistant.core.AssistantResult;
import dev.zubinjha.minecraftassistant.core.CancellationToken;
import dev.zubinjha.minecraftassistant.core.ConversationMemory;
import dev.zubinjha.minecraftassistant.core.ConversationMessage;
import dev.zubinjha.minecraftassistant.core.MinecraftAssistantPrompt;
import dev.zubinjha.minecraftassistant.core.Tool;
import dev.zubinjha.minecraftassistant.core.ToolDefinition;
import dev.zubinjha.minecraftassistant.core.ToolExecutionResult;
import dev.zubinjha.minecraftassistant.core.ToolRegistry;
import dev.zubinjha.minecraftassistant.mcp.McpToolSource;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class Main {
    private static final String DEFAULT_MODEL = "gpt-5.6-luna";
    private static final String DEFAULT_EFFORT = "medium";
    private static final int MAX_HISTORY_MESSAGES = 20;
    private static final String WIKI_MCP_ENVIRONMENT = "MINECRAFT_ASSISTANT_WIKI_MCP_URL";

    private Main() {
    }

    public static void main(String[] args) {
        int status = run(args, System.out, System.err);
        if (status != 0) {
            System.exit(status);
        }
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0) {
            args = new String[]{"chat"};
        }
        if (args[0].equals("help") || args[0].equals("--help")) {
            printUsage(out);
            return 0;
        }

        String command = args[0].toLowerCase(Locale.ROOT);
        if (!List.of("chat", "doctor", "models", "ask", "smoke", "wiki-smoke").contains(command)) {
            err.println("Unknown command: " + args[0]);
            printUsage(err);
            return 2;
        }

        AtomicBoolean smokeToolCalled = new AtomicBoolean();

        ExecutorService worker = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "minecraft-assistant-worker");
            thread.setDaemon(true);
            return thread;
        });
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "minecraft-assistant-scheduler");
            thread.setDaemon(true);
            return thread;
        });

        McpToolSource wiki = null;
        try {
            ToolRegistry tools;
            if (command.equals("smoke")) {
                tools = new ToolRegistry(List.of(new SmokeTool(smokeToolCalled)));
            } else if (usesWiki(command)) {
                wiki = McpToolSource.connectMinecraftWiki(
                        wikiEndpoint(),
                        worker,
                        CancellationToken.NONE
                ).toCompletableFuture().join();
                tools = new ToolRegistry(wiki.tools());
            } else {
                tools = ToolRegistry.empty();
            }

            CodexAppServerAssistant assistant = CodexAppServerAssistant
                    .start(CodexAppServerSettings.defaults(), tools, worker, scheduler)
                    .toCompletableFuture()
                    .join();
            try (assistant) {
                return switch (command) {
                    case "chat" -> chat(assistant, tools, out, err);
                    case "doctor" -> doctor(assistant, tools, out);
                    case "models" -> models(assistant, out);
                    case "ask" -> ask(assistant, Arrays.copyOfRange(args, 1, args.length), out, err);
                    case "smoke" -> smoke(assistant, smokeToolCalled, out, err);
                    case "wiki-smoke" -> wikiSmoke(assistant, out, err);
                    default -> throw new IllegalStateException("unhandled command: " + command);
                };
            }
        } catch (RuntimeException failure) {
            return reportFailure(unwrap(failure), err);
        } finally {
            if (wiki != null) {
                wiki.close();
            }
            worker.shutdownNow();
            scheduler.shutdownNow();
            awaitTermination(worker);
            awaitTermination(scheduler);
        }
    }

    private static int doctor(CodexAppServerAssistant assistant, ToolRegistry tools, PrintStream out) {
        CodexAccountStatus account = assistant.accountStatus().toCompletableFuture().join();
        List<CodexModel> models = assistant.listModels().toCompletableFuture().join();
        String requestedModel = selectedModel();
        CodexModel model = models.stream().filter(candidate -> candidate.id().equals(requestedModel))
                .findFirst().orElse(null);

        out.println("Codex app-server: reachable");
        out.println("Authentication: " + (account.authenticated()
                ? account.authenticationMode() + " (" + account.planType() + ")"
                : "not signed in"));
        out.println("Selected model: " + requestedModel + (model == null ? " (unavailable)" : " (available)"));
        out.println("Reasoning effort: " + selectedEffort());
        out.println("Minecraft Wiki MCP: reachable (" + tools.size() + " tools)");
        return account.authenticated() && model != null && tools.size() > 0 ? 0 : 1;
    }

    private static int models(CodexAppServerAssistant assistant, PrintStream out) {
        for (CodexModel model : assistant.listModels().toCompletableFuture().join()) {
            if (!model.hidden()) {
                out.printf(
                        "%s%s — efforts: %s%n",
                        model.id(),
                        model.defaultModel() ? " (default)" : "",
                        String.join(", ", model.supportedReasoningEfforts())
                );
            }
        }
        return 0;
    }

    private static int ask(
            CodexAppServerAssistant assistant,
            String[] words,
            PrintStream out,
            PrintStream err
    ) {
        if (words.length == 0 || String.join(" ", words).isBlank()) {
            err.println("Usage: assistant ask <question>");
            return 2;
        }
        String question = String.join(" ", words);
        out.println("Thinking…");
        AssistantResult result = assistant.ask(request(question), CancellationToken.NONE)
                .toCompletableFuture()
                .join();
        out.println();
        out.println(result.text());
        return 0;
    }

    private static int chat(
            CodexAppServerAssistant assistant,
            ToolRegistry tools,
            PrintStream out,
            PrintStream err
    ) {
        boolean color = System.console() != null && System.getenv("NO_COLOR") == null;
        ConversationMemory memory = new ConversationMemory(MAX_HISTORY_MESSAGES);
        printChatHeader(out, tools.size(), color);
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        while (true) {
            out.print(style(color, "\u001B[96m", "❯ "));
            out.flush();

            String line;
            try {
                line = input.readLine();
            } catch (IOException failure) {
                err.println("Could not read terminal input: " + failure.getMessage());
                return 1;
            }
            if (line == null) {
                out.println();
                return 0;
            }

            String question = line.trim();
            if (question.isEmpty()) {
                continue;
            }
            switch (question.toLowerCase(Locale.ROOT)) {
                case "/quit", "/exit", "/q" -> {
                    out.println("Goodbye.");
                    return 0;
                }
                case "/help" -> {
                    printChatHelp(out);
                    continue;
                }
                case "/status", "/doctor" -> {
                    doctor(assistant, tools, out);
                    out.println();
                    continue;
                }
                case "/models" -> {
                    models(assistant, out);
                    out.println();
                    continue;
                }
                case "/clear" -> {
                    memory.clear();
                    if (color) {
                        out.print("\u001B[2J\u001B[H");
                    } else {
                        out.println();
                    }
                    printChatHeader(out, tools.size(), color);
                    continue;
                }
                default -> {
                    if (question.startsWith("/")) {
                        err.println("Unknown command. Type /help for available commands.");
                        continue;
                    }
                }
            }

            long started = System.nanoTime();
            out.println(style(color, "\u001B[2m", "  Thinking…"));
            try {
                AssistantResult result = assistant.ask(
                        request(question, memory.snapshot()),
                        CancellationToken.NONE
                )
                        .toCompletableFuture()
                        .join();
                memory.addExchange(question, result.text());
                double elapsedSeconds = (System.nanoTime() - started) / 1_000_000_000.0;
                out.println();
                out.println(style(color, "\u001B[92m", "Assistant"));
                out.println(result.text());
                out.printf(
                        Locale.ROOT,
                        "%s%n%n",
                        style(color, "\u001B[2m", String.format(
                                Locale.ROOT,
                                "%.2fs · %d Wiki tool call%s",
                                elapsedSeconds,
                                result.toolCalls(),
                                result.toolCalls() == 1 ? "" : "s"
                        ))
                );
            } catch (RuntimeException failure) {
                reportFailure(unwrap(failure), err);
                out.println();
            }
        }
    }

    private static void printChatHeader(PrintStream out, int toolCount, boolean color) {
        out.println(style(color, "\u001B[1;92m", "Minecraft Assistant"));
        out.println(style(color, "\u001B[2m", selectedModel() + " · " + selectedEffort()
                + " reasoning · " + toolCount + " Wiki tools · 20-message memory"));
        out.println("Ask a Minecraft question, or type /help.");
        out.println();
    }

    private static void printChatHelp(PrintStream out) {
        out.println("Commands:");
        out.println("  /status  Check ChatGPT, model, and Wiki connections");
        out.println("  /models  List available models");
        out.println("  /clear   Clear the terminal and conversation memory");
        out.println("  /quit    Exit");
        out.println();
        out.println("The most recent 20 user and assistant messages are used for follow-ups.");
        out.println();
    }

    private static String style(boolean enabled, String code, String text) {
        return enabled ? code + text + "\u001B[0m" : text;
    }

    private static int smoke(
            CodexAppServerAssistant assistant,
            AtomicBoolean smokeToolCalled,
            PrintStream out,
            PrintStream err
    ) {
        String marker = "minecraft-assistant-smoke-ok";
        AssistantResult result = assistant.ask(request(
                "Call the test_echo tool exactly once with value '" + marker
                        + "', then reply with the exact tool result and nothing else."
        ), CancellationToken.NONE).toCompletableFuture().join();
        out.println(result.text());
        if (!smokeToolCalled.get()) {
            err.println("Smoke test failed: the model did not call the dynamic tool.");
            return 1;
        }
        if (!result.text().contains(marker)) {
            err.println("Smoke test failed: the final answer did not contain the tool result.");
            return 1;
        }
        out.println("Live ChatGPT tool-call smoke test passed.");
        return 0;
    }

    private static int wikiSmoke(
            CodexAppServerAssistant assistant,
            PrintStream out,
            PrintStream err
    ) {
        AssistantResult result = assistant.ask(request(
                "Use the Minecraft Wiki tools to answer: How do I obtain a Heart of the Sea? "
                        + "Keep the answer concise and include the relevant source link."
        ), CancellationToken.NONE).toCompletableFuture().join();
        out.println(result.text());
        if (result.toolCalls() == 0) {
            err.println("Wiki smoke test failed: the model did not call a Wiki tool.");
            return 1;
        }
        if (!result.text().contains("http://") && !result.text().contains("https://")) {
            err.println("Wiki smoke test failed: the final answer did not include a source link.");
            return 1;
        }
        out.println("Live Minecraft Wiki grounding smoke test passed ("
                + result.toolCalls() + " tool calls).");
        return 0;
    }

    private static AssistantRequest request(String question) {
        return request(question, List.of());
    }

    private static AssistantRequest request(
            String question,
            List<ConversationMessage> history
    ) {
        return new AssistantRequest(
                selectedModel(),
                selectedEffort(),
                MinecraftAssistantPrompt.DEFAULT,
                history,
                question
        );
    }

    private static String selectedModel() {
        return environmentOrDefault("MINECRAFT_ASSISTANT_MODEL", DEFAULT_MODEL);
    }

    private static String selectedEffort() {
        return environmentOrDefault("MINECRAFT_ASSISTANT_REASONING_EFFORT", DEFAULT_EFFORT);
    }

    private static String wikiEndpoint() {
        return environmentOrDefault(
                WIKI_MCP_ENVIRONMENT,
                McpToolSource.DEFAULT_MINECRAFT_WIKI_ENDPOINT
        );
    }

    private static boolean usesWiki(String command) {
        return command.equals("chat")
                || command.equals("doctor")
                || command.equals("ask")
                || command.equals("wiki-smoke");
    }

    private static String environmentOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int reportFailure(Throwable failure, PrintStream err) {
        if (failure instanceof AssistantException assistantFailure) {
            err.println("Assistant failed [" + assistantFailure.code() + "]: " + assistantFailure.getMessage());
        } else {
            err.println("Assistant failed: " + failure.getClass().getSimpleName());
        }
        return 1;
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static void awaitTermination(ExecutorService executor) {
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void printUsage(PrintStream out) {
        out.println("Minecraft Assistant CLI");
        out.println();
        out.println("Usage:");
        out.println("  assistant chat");
        out.println("  assistant doctor");
        out.println("  assistant models");
        out.println("  assistant ask <question>");
        out.println("  assistant smoke");
        out.println("  assistant wiki-smoke");
        out.println();
        out.println("Environment:");
        out.println("  MINECRAFT_ASSISTANT_MODEL (default: gpt-5.6-luna)");
        out.println("  MINECRAFT_ASSISTANT_REASONING_EFFORT (default: medium)");
        out.println("  " + WIKI_MCP_ENVIRONMENT + " (default: "
                + McpToolSource.DEFAULT_MINECRAFT_WIKI_ENDPOINT + ")");
    }

    private static final class SmokeTool implements Tool {
        private static final ObjectMapper JSON = new ObjectMapper();
        private final AtomicBoolean called;

        private SmokeTool(AtomicBoolean called) {
            this.called = called;
        }

        @Override
        public ToolDefinition definition() {
            ObjectNode schema = JSON.createObjectNode();
            schema.put("type", "object");
            schema.putObject("properties").putObject("value").put("type", "string");
            schema.putArray("required").add("value");
            return new ToolDefinition("test_echo", "Return the supplied test value", schema);
        }

        @Override
        public CompletableFuture<ToolExecutionResult> execute(
                JsonNode arguments,
                CancellationToken cancellation
        ) {
            cancellation.throwIfCancelled();
            called.set(true);
            return CompletableFuture.completedFuture(
                    ToolExecutionResult.text(arguments.path("value").asText())
            );
        }
    }
}
