package dev.zubinjha.minecraftassistant.openrouter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.zubinjha.minecraftassistant.core.AssistantException;
import dev.zubinjha.minecraftassistant.core.CancellationToken;
import dev.zubinjha.minecraftassistant.core.ErrorCode;
import dev.zubinjha.minecraftassistant.core.LlmProvider;
import dev.zubinjha.minecraftassistant.core.ModelRequest;
import dev.zubinjha.minecraftassistant.core.ModelResponse;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

public final class OpenRouterProvider implements LlmProvider {
    private static final int MAX_RESPONSE_CHARS = 2 * 1024 * 1024;

    private final OpenRouterSettings settings;
    private final HttpClient client;
    private final ObjectMapper json;

    public OpenRouterProvider(OpenRouterSettings settings) {
        this(
                settings,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                new ObjectMapper()
        );
    }

    OpenRouterProvider(OpenRouterSettings settings, HttpClient client, ObjectMapper json) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.client = Objects.requireNonNull(client, "client");
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    public CompletionStage<ModelResponse> generate(ModelRequest request, CancellationToken cancellation) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellation, "cancellation");
        cancellation.throwIfCancelled();

        String body;
        try {
            body = json.writeValueAsString(OpenRouterProtocol.request(json, request));
        } catch (JsonProcessingException failure) {
            return CompletableFuture.failedFuture(new AssistantException(
                    ErrorCode.PROTOCOL_ERROR,
                    "Could not encode the OpenRouter request",
                    failure
            ));
        }

        HttpRequest httpRequest = authorizedRequest(settings.chatCompletionsUri())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return send(httpRequest, cancellation).thenApply(response ->
                OpenRouterProtocol.response(json, parseSuccess(response))
        );
    }

    public CompletionStage<List<OpenRouterModel>> listModels(CancellationToken cancellation) {
        Objects.requireNonNull(cancellation, "cancellation");
        cancellation.throwIfCancelled();
        HttpRequest request = authorizedRequest(settings.modelsUri()).GET().build();
        return send(request, cancellation).thenApply(response -> OpenRouterProtocol.models(parseSuccess(response)));
    }

    public CompletionStage<List<OpenRouterModel>> listToolModels(CancellationToken cancellation) {
        return listModels(cancellation).thenApply(models -> models.stream()
                .filter(OpenRouterModel::supportsTools)
                .sorted(Comparator.comparing(OpenRouterModel::name, String.CASE_INSENSITIVE_ORDER))
                .toList());
    }

    private HttpRequest.Builder authorizedRequest(java.net.URI uri) {
        return HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(70))
                .header("Authorization", "Bearer " + settings.apiKey())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("HTTP-Referer", settings.applicationUri().toString())
                .header("X-OpenRouter-Title", settings.applicationName());
    }

    private CompletionStage<HttpResponse<String>> send(HttpRequest request, CancellationToken cancellation) {
        CompletableFuture<HttpResponse<String>> requestFuture = client.sendAsync(
                request,
                HttpResponse.BodyHandlers.ofString()
        );
        AutoCloseable registration = cancellation.onCancel(() -> requestFuture.cancel(true));
        return requestFuture.handle((response, failure) -> {
            closeQuietly(registration);
            if (failure == null) {
                return response;
            }
            if (cancellation.isCancelled()) {
                throw new CompletionException(new AssistantException(ErrorCode.CANCELLED, "The request was cancelled"));
            }
            Throwable cause = unwrap(failure);
            throw new CompletionException(new AssistantException(
                    ErrorCode.PROVIDER_FAILURE,
                    "Could not reach OpenRouter",
                    cause
            ));
        });
    }

    private JsonNode parseSuccess(HttpResponse<String> response) {
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            String message = switch (status) {
                case 401, 403 -> "OpenRouter rejected the API key";
                case 402 -> "OpenRouter requires additional account credit";
                case 408, 504 -> "OpenRouter timed out";
                case 429 -> "OpenRouter rate limit reached";
                default -> "OpenRouter request failed with HTTP " + status;
            };
            throw new AssistantException(ErrorCode.PROVIDER_FAILURE, message);
        }
        String body = response.body();
        if (body.length() > MAX_RESPONSE_CHARS) {
            throw new AssistantException(ErrorCode.PROTOCOL_ERROR, "OpenRouter response was too large");
        }
        try {
            return json.readTree(body);
        } catch (JsonProcessingException failure) {
            throw new AssistantException(ErrorCode.PROTOCOL_ERROR, "OpenRouter returned invalid JSON", failure);
        }
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
            // Cancellation callback cleanup is best-effort.
        }
    }
}
