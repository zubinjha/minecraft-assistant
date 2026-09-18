package dev.zubinjha.minecraftassistant.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.zubinjha.minecraftassistant.core.AgentOptions;
import dev.zubinjha.minecraftassistant.core.Assistant;
import dev.zubinjha.minecraftassistant.core.AssistantException;
import dev.zubinjha.minecraftassistant.core.AssistantRequest;
import dev.zubinjha.minecraftassistant.core.AssistantResult;
import dev.zubinjha.minecraftassistant.core.CancellationSource;
import dev.zubinjha.minecraftassistant.core.CancellationToken;
import dev.zubinjha.minecraftassistant.core.ConversationMessage;
import dev.zubinjha.minecraftassistant.core.ErrorCode;
import dev.zubinjha.minecraftassistant.core.Tool;
import dev.zubinjha.minecraftassistant.core.ToolArguments;
import dev.zubinjha.minecraftassistant.core.ToolCall;
import dev.zubinjha.minecraftassistant.core.ToolExecutionResult;
import dev.zubinjha.minecraftassistant.core.ToolRegistry;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class CodexAppServerAssistant implements Assistant, AutoCloseable {
    private final ObjectMapper json;
    private final CodexAppServerSettings settings;
    private final ToolRegistry tools;
    private final Executor executor;
    private final ScheduledExecutorService scheduler;
    private final CodexJsonRpcClient rpc;
    private final AtomicBoolean active = new AtomicBoolean();
    private volatile RunContext currentRun;

    private CodexAppServerAssistant(
            ObjectMapper json,
            CodexAppServerSettings settings,
            ToolRegistry tools,
            Executor executor,
            ScheduledExecutorService scheduler,
            CodexJsonRpcClient rpc
    ) {
        this.json = json;
        this.settings = settings;
        this.tools = tools;
        this.executor = executor;
        this.scheduler = scheduler;
        this.rpc = rpc;
        rpc.setInboundHandler(this::handleInbound);
        rpc.setFailureHandler(this::handleTransportFailure);
    }

    public static CompletionStage<CodexAppServerAssistant> start(
            CodexAppServerSettings settings,
            ToolRegistry tools,
            Executor executor,
            ScheduledExecutorService scheduler
    ) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(tools, "tools");
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(scheduler, "scheduler");

        Process process;
        try {
            process = new ProcessBuilder(settings.command())
                    .directory(settings.workingDirectory().toFile())
                    .start();
        } catch (IOException failure) {
            return CompletableFuture.failedFuture(new AssistantException(
                    ErrorCode.CONFIGURATION,
                    "Could not start Codex app-server. Make sure the Codex CLI is installed.",
                    failure
            ));
        }

        ObjectMapper json = new ObjectMapper();
        CodexJsonRpcClient rpc = new CodexJsonRpcClient(process, json);
        CodexAppServerAssistant assistant = new CodexAppServerAssistant(
                json, settings, tools, executor, scheduler, rpc
        );

        CompletableFuture<CodexAppServerAssistant> started = rpc
                .request("initialize", CodexProtocol.initialize(json))
                .orTimeout(settings.startupTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .thenApply(ignored -> {
                    rpc.notify("initialized", json.createObjectNode());
                    return assistant;
                });
        started.whenComplete((ignored, failure) -> {
            if (failure != null) {
                assistant.close();
            }
        });
        return started;
    }

    public CompletionStage<CodexAccountStatus> accountStatus() {
        return rpc.request("account/read", CodexProtocol.accountRead(json)).thenApply(result -> {
            JsonNode account = result.path("account");
            boolean authenticated = account.isObject() && !account.isNull();
            return new CodexAccountStatus(
                    authenticated,
                    authenticated ? CodexProtocol.text(account, "type", "unknown") : "none",
                    authenticated ? CodexProtocol.text(account, "planType", "unknown") : "unknown",
                    result.path("requiresOpenaiAuth").asBoolean(true)
            );
        });
    }

    public CompletionStage<List<CodexModel>> listModels() {
        return rpc.request("model/list", CodexProtocol.modelList(json)).thenApply(result -> {
            List<CodexModel> models = new ArrayList<>();
            for (JsonNode entry : result.path("data")) {
                List<String> efforts = new ArrayList<>();
                for (JsonNode effort : entry.path("supportedReasoningEfforts")) {
                    String value = CodexProtocol.text(effort, "reasoningEffort", "");
                    if (!value.isBlank()) {
                        efforts.add(value);
                    }
                }
                models.add(new CodexModel(
                        CodexProtocol.text(entry, "id", ""),
                        CodexProtocol.text(entry, "displayName", ""),
                        efforts,
                        CodexProtocol.text(entry, "defaultReasoningEffort", "medium"),
                        entry.path("hidden").asBoolean(false),
                        entry.path("isDefault").asBoolean(false)
                ));
            }
            return List.copyOf(models);
        });
    }

    @Override
    public CompletionStage<AssistantResult> ask(AssistantRequest request, CancellationToken cancellation) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellation, "cancellation");
        if (cancellation.isCancelled()) {
            return CompletableFuture.failedFuture(cancelled());
        }
        if (!active.compareAndSet(false, true)) {
            return CompletableFuture.failedFuture(new AssistantException(
                    ErrorCode.CONFIGURATION,
                    "Codex app-server adapter currently supports one active request"
            ));
        }

        RunContext run = new RunContext(request);
        currentRun = run;
        run.externalCancellation = cancellation.onCancel(() -> cancelRun(run, cancelled()));
        run.totalTimeout = scheduler.schedule(
                () -> cancelRun(run, new AssistantException(
                        ErrorCode.REQUEST_TIMEOUT,
                        "The assistant request exceeded " + settings.limits().totalTimeout()
                )),
                settings.limits().totalTimeout().toMillis(),
                TimeUnit.MILLISECONDS
        );

        rpc.request(
                "thread/start",
                CodexProtocol.threadStart(json, request, settings.workingDirectory(), tools.definitions())
        ).thenCompose(threadResult -> {
            run.threadId = CodexProtocol.text(threadResult.path("thread"), "id", "");
            if (run.threadId.isBlank()) {
                return CompletableFuture.failedFuture(new AssistantException(
                        ErrorCode.PROTOCOL_ERROR,
                        "Codex app-server did not return a thread ID"
                ));
            }
            return rpc.request("turn/start", CodexProtocol.turnStart(json, run.threadId, request));
        }).whenComplete((turnResult, failure) -> {
            if (failure != null) {
                completeRun(run, null, unwrap(failure));
                return;
            }
            run.turnId = CodexProtocol.text(turnResult.path("turn"), "id", "");
            if (run.turnId.isBlank()) {
                completeRun(run, null, new AssistantException(
                        ErrorCode.PROTOCOL_ERROR,
                        "Codex app-server did not return a turn ID"
                ));
            }
        });
        return run.completion;
    }

    private void handleInbound(JsonNode message) {
        String method = CodexProtocol.text(message, "method", "");
        JsonNode params = message.path("params");
        if (message.has("id") && method.equals("item/tool/call")) {
            handleDynamicToolCall(message.get("id"), params);
            return;
        }

        RunContext run = currentRun;
        if (run == null || run.completion.isDone()) {
            return;
        }
        String threadId = CodexProtocol.text(params, "threadId", "");
        if (!threadId.isBlank() && run.threadId != null && !threadId.equals(run.threadId)) {
            return;
        }

        if (method.equals("item/completed")) {
            JsonNode item = params.path("item");
            if (CodexProtocol.text(item, "type", "").equals("agentMessage")) {
                String phase = CodexProtocol.text(item, "phase", "");
                String text = CodexProtocol.text(item, "text", "");
                if (!text.isBlank() && (phase.isBlank() || phase.equals("final_answer"))) {
                    run.finalText = text;
                }
            }
            return;
        }

        if (method.equals("turn/completed")) {
            JsonNode turn = params.path("turn");
            String status = CodexProtocol.text(turn, "status", "failed");
            if (!status.equals("completed")) {
                String detail = CodexProtocol.text(turn.path("error"), "message", status);
                completeRun(run, null, new AssistantException(
                        status.equals("interrupted") ? ErrorCode.CANCELLED : ErrorCode.PROVIDER_FAILURE,
                        "Codex turn ended with status " + status + ": " + detail
                ));
            } else if (run.finalText == null || run.finalText.isBlank()) {
                completeRun(run, null, new AssistantException(
                        ErrorCode.EMPTY_RESPONSE,
                        "Codex completed without a final answer"
                ));
            } else {
                List<ConversationMessage> conversation;
                synchronized (run.conversation) {
                    run.conversation.add(new ConversationMessage.Assistant(run.finalText, List.of()));
                    conversation = List.copyOf(run.conversation);
                }
                completeRun(run, new AssistantResult(
                        run.finalText,
                        conversation,
                        1,
                        run.toolCalls.get()
                ), null);
            }
        }
    }

    private void handleDynamicToolCall(JsonNode requestId, JsonNode params) {
        RunContext run = currentRun;
        if (run == null || run.completion.isDone()) {
            rpc.respond(requestId, CodexProtocol.dynamicToolResponse(
                    json, false, "No active assistant request"
            ));
            return;
        }

        String toolName = CodexProtocol.text(params, "tool", "");
        String callId = CodexProtocol.text(params, "callId", "unknown-call");
        JsonNode arguments = params.path("arguments");
        int callNumber = run.toolCalls.incrementAndGet();
        if (callNumber > settings.limits().maxToolCalls()) {
            rpc.respond(requestId, CodexProtocol.dynamicToolResponse(
                    json, false, "Tool call limit exceeded"
            ));
            return;
        }

        ToolCall call = new ToolCall(callId, toolName, arguments);
        synchronized (run.conversation) {
            run.conversation.add(new ConversationMessage.Assistant("", List.of(call)));
        }
        Tool tool = tools.find(toolName).orElse(null);
        if (tool == null) {
            finishDynamicToolCall(run, requestId, call, false, "Unknown tool: " + toolName);
            return;
        }
        List<String> validationErrors = ToolArguments.validate(tool.definition().inputSchema(), arguments);
        if (!validationErrors.isEmpty()) {
            finishDynamicToolCall(
                    run,
                    requestId,
                    call,
                    false,
                    "Invalid arguments: " + String.join("; ", validationErrors)
            );
            return;
        }

        CompletableFuture<ToolExecutionResult> execution = CompletableFuture
                .supplyAsync(() -> tool.execute(arguments, run.cancellation), executor)
                .thenCompose(CompletionStage::toCompletableFuture)
                .orTimeout(settings.limits().toolTimeout().toMillis(), TimeUnit.MILLISECONDS);
        execution.whenComplete((result, failure) -> {
            if (failure != null) {
                finishDynamicToolCall(run, requestId, call, false, safeFailureMessage(unwrap(failure)));
            } else if (result.content().length() > settings.limits().maxToolResultChars()) {
                finishDynamicToolCall(run, requestId, call, false, "Tool result exceeded the size limit");
            } else {
                finishDynamicToolCall(run, requestId, call, true, result.content());
            }
        });
    }

    private void finishDynamicToolCall(
            RunContext run,
            JsonNode requestId,
            ToolCall call,
            boolean success,
            String content
    ) {
        synchronized (run.conversation) {
            run.conversation.add(new ConversationMessage.ToolResult(
                    call.callId(), call.name(), content, success
            ));
        }
        rpc.respond(requestId, CodexProtocol.dynamicToolResponse(json, success, content));
    }

    private void cancelRun(RunContext run, AssistantException failure) {
        if (run.completion.completeExceptionally(failure)) {
            run.cancellation.cancel();
            if (run.threadId != null && run.turnId != null) {
                rpc.request("turn/interrupt", CodexProtocol.interrupt(json, run.threadId, run.turnId));
            }
            cleanupRun(run);
        }
    }

    private void completeRun(RunContext run, AssistantResult result, Throwable failure) {
        boolean completed = failure == null
                ? run.completion.complete(result)
                : run.completion.completeExceptionally(failure);
        if (completed) {
            cleanupRun(run);
        }
    }

    private void cleanupRun(RunContext run) {
        if (run.totalTimeout != null) {
            run.totalTimeout.cancel(false);
        }
        closeQuietly(run.externalCancellation);
        if (currentRun == run) {
            currentRun = null;
        }
        active.set(false);
    }

    private void handleTransportFailure(Throwable failure) {
        RunContext run = currentRun;
        if (run != null) {
            completeRun(run, null, failure);
        }
    }

    private static AssistantException cancelled() {
        return new AssistantException(ErrorCode.CANCELLED, "The request was cancelled");
    }

    private static String safeFailureMessage(Throwable failure) {
        if (failure instanceof AssistantException && failure.getMessage() != null) {
            return failure.getMessage();
        }
        String name = failure.getClass().getSimpleName();
        return name.isBlank() ? "unexpected tool error" : name;
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Cancellation listener cleanup is best effort.
        }
    }

    @Override
    public void close() {
        RunContext run = currentRun;
        if (run != null) {
            cancelRun(run, new AssistantException(ErrorCode.CANCELLED, "Codex adapter was closed"));
        }
        rpc.close();
    }

    private static final class RunContext {
        private final CompletableFuture<AssistantResult> completion = new CompletableFuture<>();
        private final CancellationSource cancellation = new CancellationSource();
        private final List<ConversationMessage> conversation = Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger toolCalls = new AtomicInteger();
        private volatile String threadId;
        private volatile String turnId;
        private volatile String finalText;
        private volatile AutoCloseable externalCancellation;
        private volatile ScheduledFuture<?> totalTimeout;

        private RunContext(AssistantRequest request) {
            conversation.add(new ConversationMessage.System(request.systemPrompt()));
            conversation.addAll(request.history());
            conversation.add(new ConversationMessage.User(request.question()));
        }
    }
}
