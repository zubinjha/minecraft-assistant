package dev.zubinjha.minecraftassistant.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class AgentTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private ScheduledExecutorService scheduler;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newScheduledThreadPool(2);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        scheduler.shutdownNow();
        scheduler.awaitTermination(2, TimeUnit.SECONDS);
    }

    @Test
    void returnsDirectProviderResponseWithoutTools() {
        ScriptedProvider provider = ScriptedProvider.sync(request -> ModelResponse.text("Hello from the model"));
        Agent agent = agent(provider, ToolRegistry.empty(), AgentOptions.DEFAULT);

        AssistantResult result = agent.ask(request("hello"), CancellationToken.NONE)
                .toCompletableFuture()
                .join();

        assertEquals("Hello from the model", result.text());
        assertEquals(1, result.providerTurns());
        assertEquals(0, result.toolCalls());
        assertEquals(3, result.conversation().size());
    }

    @Test
    void sendsRecentConversationBeforeTheCurrentQuestion() {
        List<ConversationMessage> history = List.of(
                new ConversationMessage.User("How do I make a recovery compass?"),
                new ConversationMessage.Assistant("Use a compass and eight echo shards.", List.of())
        );
        ScriptedProvider provider = ScriptedProvider.sync(request -> {
            assertEquals(4, request.messages().size());
            assertEquals(history.get(0), request.messages().get(1));
            assertEquals(history.get(1), request.messages().get(2));
            assertEquals(
                    new ConversationMessage.User("What goes in the center?"),
                    request.messages().get(3)
            );
            return ModelResponse.text("A compass.");
        });
        Agent agent = agent(provider, ToolRegistry.empty(), AgentOptions.DEFAULT);
        AssistantRequest request = new AssistantRequest(
                "test-model",
                "medium",
                "You are a test assistant.",
                history,
                "What goes in the center?"
        );

        AssistantResult result = agent.ask(request, CancellationToken.NONE)
                .toCompletableFuture()
                .join();

        assertEquals("A compass.", result.text());
    }

    @Test
    void executesToolAndReturnsItsResultToProvider() {
        ToolCall call = new ToolCall("call-1", "lookup", JSON.createObjectNode().put("query", "compass"));
        ScriptedProvider provider = ScriptedProvider.sync(
                request -> ModelResponse.tools(List.of(call)),
                request -> {
                    ConversationMessage.ToolResult toolResult = assertInstanceOf(
                            ConversationMessage.ToolResult.class,
                            request.messages().get(request.messages().size() - 1)
                    );
                    assertTrue(toolResult.success());
                    assertEquals("found: compass", toolResult.content());
                    return ModelResponse.text("Use eight echo shards around a compass.");
                }
        );
        Agent agent = agent(provider, new ToolRegistry(List.of(new LookupTool())), AgentOptions.DEFAULT);

        AssistantResult result = agent.ask(request("recipe"), CancellationToken.NONE)
                .toCompletableFuture()
                .join();

        assertEquals("Use eight echo shards around a compass.", result.text());
        assertEquals(2, result.providerTurns());
        assertEquals(1, result.toolCalls());
    }

    @Test
    void executesMultipleCallsInProviderOrder() {
        List<String> executionOrder = new ArrayList<>();
        Tool first = recordingTool("first", executionOrder);
        Tool second = recordingTool("second", executionOrder);
        ScriptedProvider provider = ScriptedProvider.sync(
                request -> ModelResponse.tools(List.of(
                        new ToolCall("call-1", "first", JSON.createObjectNode()),
                        new ToolCall("call-2", "second", JSON.createObjectNode())
                )),
                request -> ModelResponse.text("done")
        );
        Agent agent = agent(provider, new ToolRegistry(List.of(first, second)), AgentOptions.DEFAULT);

        AssistantResult result = agent.ask(request("run both"), CancellationToken.NONE)
                .toCompletableFuture()
                .join();

        assertEquals(List.of("first", "second"), executionOrder);
        assertEquals(2, result.toolCalls());
    }

    @Test
    void returnsUnknownToolAsStructuredFailure() {
        ScriptedProvider provider = ScriptedProvider.sync(
                request -> ModelResponse.tools(List.of(
                        new ToolCall("call-1", "missing", JSON.createObjectNode())
                )),
                request -> {
                    ConversationMessage.ToolResult result = assertInstanceOf(
                            ConversationMessage.ToolResult.class,
                            request.messages().get(request.messages().size() - 1)
                    );
                    assertFalse(result.success());
                    assertEquals("Unknown tool: missing", result.content());
                    return ModelResponse.text("I could not use that tool.");
                }
        );
        Agent agent = agent(provider, ToolRegistry.empty(), AgentOptions.DEFAULT);

        AssistantResult result = agent.ask(request("try it"), CancellationToken.NONE)
                .toCompletableFuture()
                .join();

        assertEquals("I could not use that tool.", result.text());
    }

    @Test
    void validatesArgumentsBeforeExecutingTool() {
        AtomicBoolean executed = new AtomicBoolean();
        Tool tool = new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("lookup", "Looks things up", objectSchema("query"));
            }

            @Override
            public CompletionStage<ToolExecutionResult> execute(
                    JsonNode arguments,
                    CancellationToken cancellation
            ) {
                executed.set(true);
                return CompletableFuture.completedFuture(ToolExecutionResult.text("unexpected"));
            }
        };
        ScriptedProvider provider = ScriptedProvider.sync(
                request -> ModelResponse.tools(List.of(
                        new ToolCall("call-1", "lookup", JSON.createObjectNode())
                )),
                request -> {
                    ConversationMessage.ToolResult result = assertInstanceOf(
                            ConversationMessage.ToolResult.class,
                            request.messages().get(request.messages().size() - 1)
                    );
                    assertFalse(result.success());
                    assertTrue(result.content().contains("$.query is required"));
                    return ModelResponse.text("invalid arguments handled");
                }
        );
        Agent agent = agent(provider, new ToolRegistry(List.of(tool)), AgentOptions.DEFAULT);

        agent.ask(request("invalid"), CancellationToken.NONE).toCompletableFuture().join();

        assertFalse(executed.get());
    }

    @Test
    void enforcesProviderTurnLimit() {
        ScriptedProvider provider = ScriptedProvider.sync(
                request -> ModelResponse.tools(List.of(
                        new ToolCall("call-1", "missing", JSON.createObjectNode())
                ))
        );
        AgentOptions options = new AgentOptions(
                1, 2, Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(2), 1_000
        );
        Agent agent = agent(provider, ToolRegistry.empty(), options);

        CompletionException failure = assertThrows(
                CompletionException.class,
                () -> agent.ask(request("loop"), CancellationToken.NONE).toCompletableFuture().join()
        );

        AssistantException assistantFailure = assertInstanceOf(AssistantException.class, failure.getCause());
        assertEquals(ErrorCode.AGENT_LIMIT, assistantFailure.code());
    }

    @Test
    void enforcesProviderTimeout() {
        ScriptedProvider provider = ScriptedProvider.async(request -> new CompletableFuture<>());
        AgentOptions options = new AgentOptions(
                2, 2, Duration.ofMillis(25), Duration.ofSeconds(1), Duration.ofSeconds(2), 1_000
        );
        Agent agent = agent(provider, ToolRegistry.empty(), options);

        CompletionException failure = assertThrows(
                CompletionException.class,
                () -> agent.ask(request("wait"), CancellationToken.NONE).toCompletableFuture().join()
        );

        AssistantException assistantFailure = assertInstanceOf(AssistantException.class, failure.getCause());
        assertEquals(ErrorCode.PROVIDER_TIMEOUT, assistantFailure.code());
    }

    @Test
    void cancellationCompletesRequestPromptly() {
        ScriptedProvider provider = ScriptedProvider.async(request -> new CompletableFuture<>());
        Agent agent = agent(provider, ToolRegistry.empty(), AgentOptions.DEFAULT);
        CancellationSource cancellation = new CancellationSource();

        CompletableFuture<AssistantResult> result = agent.ask(request("wait"), cancellation).toCompletableFuture();
        cancellation.cancel();

        CompletionException failure = assertThrows(CompletionException.class, result::join);
        AssistantException assistantFailure = assertInstanceOf(AssistantException.class, failure.getCause());
        assertEquals(ErrorCode.CANCELLED, assistantFailure.code());
    }

    @Test
    void rejectsOversizedToolResultsWithoutLeakingTheContent() {
        Tool largeTool = new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("large", "Returns too much", objectSchema());
            }

            @Override
            public CompletionStage<ToolExecutionResult> execute(
                    JsonNode arguments,
                    CancellationToken cancellation
            ) {
                return CompletableFuture.completedFuture(ToolExecutionResult.text("secret-result"));
            }
        };
        ScriptedProvider provider = ScriptedProvider.sync(
                request -> ModelResponse.tools(List.of(
                        new ToolCall("call-1", "large", JSON.createObjectNode())
                )),
                request -> {
                    ConversationMessage.ToolResult result = assertInstanceOf(
                            ConversationMessage.ToolResult.class,
                            request.messages().get(request.messages().size() - 1)
                    );
                    assertFalse(result.success());
                    assertFalse(result.content().contains("secret-result"));
                    return ModelResponse.text("handled");
                }
        );
        AgentOptions options = new AgentOptions(
                2, 2, Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(2), 5
        );
        Agent agent = agent(provider, new ToolRegistry(List.of(largeTool)), options);

        AssistantResult result = agent.ask(request("large"), CancellationToken.NONE)
                .toCompletableFuture()
                .join();

        assertEquals("handled", result.text());
    }

    private Agent agent(LlmProvider provider, ToolRegistry tools, AgentOptions options) {
        return new Agent(provider, tools, options, scheduler, AgentEventListener.NONE);
    }

    private static AssistantRequest request(String question) {
        return new AssistantRequest("test-model", "medium", "You are a test assistant.", question);
    }

    private static Tool recordingTool(String name, List<String> order) {
        return new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition(name, "Records execution order", objectSchema());
            }

            @Override
            public CompletionStage<ToolExecutionResult> execute(
                    JsonNode arguments,
                    CancellationToken cancellation
            ) {
                order.add(name);
                return CompletableFuture.completedFuture(ToolExecutionResult.text(name + " result"));
            }
        };
    }

    private static ObjectNode objectSchema(String... required) {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        for (String name : required) {
            properties.putObject(name).put("type", "string");
            schema.withArray("required").add(name);
        }
        return schema;
    }

    private static final class LookupTool implements Tool {
        @Override
        public ToolDefinition definition() {
            return new ToolDefinition("lookup", "Looks up a query", objectSchema("query"));
        }

        @Override
        public CompletionStage<ToolExecutionResult> execute(JsonNode arguments, CancellationToken cancellation) {
            return CompletableFuture.completedFuture(
                    new ToolExecutionResult("found: " + arguments.path("query").asText(), Map.of("source", "test"))
            );
        }
    }

    private static final class ScriptedProvider implements LlmProvider {
        private final Queue<Function<ModelRequest, CompletionStage<ModelResponse>>> steps = new ArrayDeque<>();

        private ScriptedProvider() {
        }

        private static ScriptedProvider sync(SyncStep... steps) {
            ScriptedProvider provider = new ScriptedProvider();
            for (SyncStep step : steps) {
                provider.steps.add(request -> CompletableFuture.completedFuture(step.apply(request)));
            }
            return provider;
        }

        private static ScriptedProvider async(AsyncStep step) {
            ScriptedProvider provider = new ScriptedProvider();
            provider.steps.add(step::apply);
            return provider;
        }

        @Override
        public CompletionStage<ModelResponse> generate(ModelRequest request, CancellationToken cancellation) {
            Function<ModelRequest, CompletionStage<ModelResponse>> next = steps.peek();
            if (next == null) {
                return CompletableFuture.failedFuture(new AssertionError("unexpected provider call"));
            }
            if (steps.size() > 1) {
                steps.remove();
            }
            return next.apply(request);
        }
    }

    @FunctionalInterface
    private interface SyncStep {
        ModelResponse apply(ModelRequest request);
    }

    @FunctionalInterface
    private interface AsyncStep {
        CompletionStage<ModelResponse> apply(ModelRequest request);
    }
}
