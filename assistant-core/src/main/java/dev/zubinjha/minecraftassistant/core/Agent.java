package dev.zubinjha.minecraftassistant.core;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class Agent implements Assistant {
    private final LlmProvider provider;
    private final ToolRegistry tools;
    private final AgentOptions options;
    private final ScheduledExecutorService scheduler;
    private final AgentEventListener listener;

    public Agent(
            LlmProvider provider,
            ToolRegistry tools,
            AgentOptions options,
            ScheduledExecutorService scheduler,
            AgentEventListener listener
    ) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.tools = Objects.requireNonNull(tools, "tools");
        this.options = Objects.requireNonNull(options, "options");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    @Override
    public CompletionStage<AssistantResult> ask(AssistantRequest request, CancellationToken cancellation) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellation, "cancellation");

        if (cancellation.isCancelled()) {
            return CompletableFuture.failedFuture(cancelled());
        }

        CancellationSource runCancellation = new CancellationSource();
        CompletableFuture<AssistantResult> result = new CompletableFuture<>();
        AutoCloseable externalRegistration = cancellation.onCancel(() -> {
            result.completeExceptionally(cancelled());
            runCancellation.cancel();
        });
        ScheduledFuture<?> totalTimeout = scheduler.schedule(() -> {
            result.completeExceptionally(new AssistantException(
                    ErrorCode.REQUEST_TIMEOUT,
                    "The assistant request exceeded " + options.totalTimeout()
            ));
            runCancellation.cancel();
        }, options.totalTimeout().toMillis(), TimeUnit.MILLISECONDS);

        State state = new State(request);
        emit(AgentEvent.Type.REQUEST_STARTED, state, "", "");
        loop(state, runCancellation).whenComplete((answer, failure) -> {
            totalTimeout.cancel(false);
            closeQuietly(externalRegistration);
            if (failure == null) {
                result.complete(answer);
            } else {
                Throwable cause = unwrap(failure);
                emit(AgentEvent.Type.REQUEST_FAILED, state, "", cause.getMessage());
                result.completeExceptionally(cause);
            }
        });
        return result;
    }

    private CompletionStage<AssistantResult> loop(State state, CancellationToken cancellation) {
        try {
            cancellation.throwIfCancelled();
            if (state.providerTurns >= options.maxProviderTurns()) {
                throw new AssistantException(
                        ErrorCode.AGENT_LIMIT,
                        "The model exceeded the maximum of " + options.maxProviderTurns() + " turns"
                );
            }
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }

        state.providerTurns++;
        emit(AgentEvent.Type.PROVIDER_STARTED, state, "", "");
        ModelRequest modelRequest = new ModelRequest(
                state.request.model(),
                state.request.reasoningEffort(),
                List.copyOf(state.messages),
                tools.definitions()
        );

        CompletionStage<ModelResponse> providerStage;
        try {
            providerStage = provider.generate(modelRequest, cancellation);
        } catch (RuntimeException failure) {
            providerStage = CompletableFuture.failedFuture(new AssistantException(
                    ErrorCode.PROVIDER_FAILURE,
                    "The provider failed before returning a response",
                    failure
            ));
        }

        return withTimeout(
                providerStage,
                options.providerTimeout(),
                ErrorCode.PROVIDER_TIMEOUT,
                "The provider call exceeded " + options.providerTimeout()
        ).thenCompose(response -> handleResponse(state, response, cancellation));
    }

    private CompletionStage<AssistantResult> handleResponse(
            State state,
            ModelResponse response,
            CancellationToken cancellation
    ) {
        emit(AgentEvent.Type.PROVIDER_COMPLETED, state, "", response.finishReason());
        if (response.toolCalls().isEmpty()) {
            if (response.text().isBlank()) {
                return CompletableFuture.failedFuture(new AssistantException(
                        ErrorCode.EMPTY_RESPONSE,
                        "The provider returned neither text nor tool calls"
                ));
            }
            state.messages.add(new ConversationMessage.Assistant(response.text(), List.of()));
            emit(AgentEvent.Type.ANSWER_COMPLETED, state, "", "");
            return CompletableFuture.completedFuture(new AssistantResult(
                    response.text(),
                    state.messages,
                    state.providerTurns,
                    state.toolCalls
            ));
        }

        if (state.toolCalls + response.toolCalls().size() > options.maxToolCalls()) {
            return CompletableFuture.failedFuture(new AssistantException(
                    ErrorCode.AGENT_LIMIT,
                    "The model exceeded the maximum of " + options.maxToolCalls() + " tool calls"
            ));
        }

        state.messages.add(new ConversationMessage.Assistant(response.text(), response.toolCalls()));
        CompletionStage<Void> chain = CompletableFuture.completedFuture(null);
        for (ToolCall call : response.toolCalls()) {
            chain = chain.thenCompose(ignored -> executeTool(state, call, cancellation)
                    .thenAccept(state.messages::add));
        }
        return chain.thenCompose(ignored -> loop(state, cancellation));
    }

    private CompletionStage<ConversationMessage.ToolResult> executeTool(
            State state,
            ToolCall call,
            CancellationToken cancellation
    ) {
        state.toolCalls++;
        emit(AgentEvent.Type.TOOL_STARTED, state, call.name(), "");
        Tool tool = tools.find(call.name()).orElse(null);
        if (tool == null) {
            return completedToolFailure(state, call, "Unknown tool: " + call.name());
        }

        List<String> validationErrors = ToolArguments.validate(tool.definition().inputSchema(), call.arguments());
        if (!validationErrors.isEmpty()) {
            return completedToolFailure(state, call, "Invalid arguments: " + String.join("; ", validationErrors));
        }

        CompletionStage<ToolExecutionResult> execution;
        try {
            execution = tool.execute(call.arguments(), cancellation);
        } catch (RuntimeException failure) {
            execution = CompletableFuture.failedFuture(failure);
        }

        return withTimeout(
                execution,
                options.toolTimeout(),
                ErrorCode.TOOL_TIMEOUT,
                "Tool call exceeded " + options.toolTimeout()
        ).handle((toolResult, failure) -> {
            if (failure != null) {
                Throwable cause = unwrap(failure);
                if (cause instanceof AssistantException assistantFailure
                        && assistantFailure.code() == ErrorCode.CANCELLED) {
                    throw new CompletionException(cause);
                }
                emit(AgentEvent.Type.TOOL_FAILED, state, call.name(), safeFailureMessage(cause));
                return new ConversationMessage.ToolResult(
                        call.callId(),
                        call.name(),
                        "Tool execution failed: " + safeFailureMessage(cause),
                        false
                );
            }

            if (toolResult.content().length() > options.maxToolResultChars()) {
                emit(AgentEvent.Type.TOOL_FAILED, state, call.name(), "result too large");
                return new ConversationMessage.ToolResult(
                        call.callId(),
                        call.name(),
                        "Tool execution failed: result exceeded the configured size limit",
                        false
                );
            }
            emit(AgentEvent.Type.TOOL_COMPLETED, state, call.name(), "");
            return new ConversationMessage.ToolResult(
                    call.callId(),
                    call.name(),
                    toolResult.content(),
                    true
            );
        });
    }

    private CompletionStage<ConversationMessage.ToolResult> completedToolFailure(
            State state,
            ToolCall call,
            String message
    ) {
        emit(AgentEvent.Type.TOOL_FAILED, state, call.name(), message);
        return CompletableFuture.completedFuture(new ConversationMessage.ToolResult(
                call.callId(), call.name(), message, false
        ));
    }

    private <T> CompletionStage<T> withTimeout(
            CompletionStage<T> source,
            Duration timeout,
            ErrorCode code,
            String message
    ) {
        CompletableFuture<T> output = new CompletableFuture<>();
        CompletableFuture<T> sourceFuture = source.toCompletableFuture();
        ScheduledFuture<?> timer = scheduler.schedule(() -> {
            if (output.completeExceptionally(new AssistantException(code, message))) {
                sourceFuture.cancel(true);
            }
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);
        source.whenComplete((value, failure) -> {
            timer.cancel(false);
            if (failure == null) {
                output.complete(value);
            } else {
                output.completeExceptionally(unwrap(failure));
            }
        });
        return output;
    }

    private void emit(AgentEvent.Type type, State state, String toolName, String detail) {
        try {
            listener.onEvent(new AgentEvent(type, state.providerTurns, toolName, detail));
        } catch (RuntimeException ignored) {
            // Observability must not break an assistant request.
        }
    }

    private static AssistantException cancelled() {
        return new AssistantException(ErrorCode.CANCELLED, "The request was cancelled");
    }

    private static String safeFailureMessage(Throwable failure) {
        if (failure instanceof AssistantException && failure.getMessage() != null) {
            return failure.getMessage();
        }
        String simpleName = failure.getClass().getSimpleName();
        return simpleName.isBlank() ? "unexpected error" : simpleName;
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Cancellation registrations are best-effort cleanup.
        }
    }

    private static final class State {
        private final AssistantRequest request;
        private final List<ConversationMessage> messages = new ArrayList<>();
        private int providerTurns;
        private int toolCalls;

        private State(AssistantRequest request) {
            this.request = request;
            messages.add(new ConversationMessage.System(request.systemPrompt()));
            messages.addAll(request.history());
            messages.add(new ConversationMessage.User(request.question()));
        }
    }
}
