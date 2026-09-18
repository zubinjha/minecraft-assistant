package dev.zubinjha.minecraftassistant.codex;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.zubinjha.minecraftassistant.core.AssistantException;
import dev.zubinjha.minecraftassistant.core.ErrorCode;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

final class CodexJsonRpcClient implements AutoCloseable {
    private static final int MAX_STDERR_LINES = 20;

    private final Process process;
    private final ObjectMapper json;
    private final BufferedWriter writer;
    private final ExecutorService readers;
    private final AtomicLong requestIds = new AtomicLong();
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Deque<String> recentStderr = new ArrayDeque<>();
    private volatile Consumer<JsonNode> inboundHandler = ignored -> { };
    private volatile Consumer<Throwable> failureHandler = ignored -> { };

    CodexJsonRpcClient(Process process, ObjectMapper json) {
        this.process = Objects.requireNonNull(process, "process");
        this.json = Objects.requireNonNull(json, "json");
        this.writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.readers = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "minecraft-assistant-codex-io");
            thread.setDaemon(true);
            return thread;
        });
        readers.submit(this::readStdout);
        readers.submit(this::readStderr);
    }

    void setInboundHandler(Consumer<JsonNode> handler) {
        inboundHandler = Objects.requireNonNull(handler, "handler");
    }

    void setFailureHandler(Consumer<Throwable> handler) {
        failureHandler = Objects.requireNonNull(handler, "handler");
    }

    CompletableFuture<JsonNode> request(String method, ObjectNode params) {
        long id = requestIds.incrementAndGet();
        CompletableFuture<JsonNode> response = new CompletableFuture<>();
        pending.put(id, response);

        ObjectNode message = json.createObjectNode();
        message.put("method", method);
        message.put("id", id);
        message.set("params", params);
        try {
            send(message);
        } catch (RuntimeException failure) {
            pending.remove(id);
            response.completeExceptionally(failure);
        }
        return response;
    }

    void notify(String method, ObjectNode params) {
        ObjectNode message = json.createObjectNode();
        message.put("method", method);
        message.set("params", params);
        send(message);
    }

    void respond(JsonNode id, ObjectNode result) {
        ObjectNode message = json.createObjectNode();
        message.set("id", id.deepCopy());
        message.set("result", result);
        send(message);
    }

    private void send(JsonNode message) {
        if (closed.get()) {
            throw new AssistantException(ErrorCode.PROTOCOL_ERROR, "Codex app-server is closed");
        }
        try {
            String line = json.writeValueAsString(message);
            synchronized (writeLock) {
                writer.write(line);
                writer.newLine();
                writer.flush();
            }
        } catch (IOException failure) {
            throw protocolFailure("Could not write to Codex app-server", failure);
        }
    }

    private void readStdout() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    handleLine(line);
                }
            }
            if (!closed.get()) {
                failAll(protocolFailure("Codex app-server closed its output", null));
            }
        } catch (IOException failure) {
            if (!closed.get()) {
                failAll(protocolFailure("Could not read from Codex app-server", failure));
            }
        }
    }

    private void handleLine(String line) {
        JsonNode message;
        try {
            message = json.readTree(line);
        } catch (JsonProcessingException failure) {
            failAll(protocolFailure("Codex app-server emitted invalid JSON", failure));
            return;
        }

        JsonNode id = message.get("id");
        if (id != null && id.canConvertToLong() && (message.has("result") || message.has("error"))) {
            CompletableFuture<JsonNode> response = pending.remove(id.longValue());
            if (response != null) {
                if (message.has("error")) {
                    response.completeExceptionally(new AssistantException(
                            ErrorCode.PROTOCOL_ERROR,
                            "Codex app-server request failed: " + sanitizedProtocolError(message.path("error"))
                    ));
                } else {
                    response.complete(message.path("result"));
                }
            }
            return;
        }

        try {
            inboundHandler.accept(message);
        } catch (RuntimeException failure) {
            failureHandler.accept(failure);
        }
    }

    private void readStderr() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                synchronized (recentStderr) {
                    recentStderr.addLast(line);
                    while (recentStderr.size() > MAX_STDERR_LINES) {
                        recentStderr.removeFirst();
                    }
                }
            }
        } catch (IOException ignored) {
            // Stderr is diagnostic only; stdout drives the protocol.
        }
    }

    private void failAll(Throwable failure) {
        pending.values().forEach(future -> future.completeExceptionally(failure));
        pending.clear();
        failureHandler.accept(failure);
    }

    private static String sanitizedProtocolError(JsonNode error) {
        String message = error.path("message").asText("unknown protocol error");
        return message.length() <= 300 ? message : message.substring(0, 300);
    }

    private static AssistantException protocolFailure(String message, Throwable cause) {
        return cause == null
                ? new AssistantException(ErrorCode.PROTOCOL_ERROR, message)
                : new AssistantException(ErrorCode.PROTOCOL_ERROR, message, cause);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            writer.close();
        } catch (IOException ignored) {
            // Process teardown below is authoritative.
        }
        process.destroy();
        try {
            if (!process.waitFor(1, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        readers.shutdownNow();
        failAll(new AssistantException(ErrorCode.CANCELLED, "Codex app-server was closed"));
    }
}
