package dev.zubinjha.minecraftassistant.mediawiki;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.zubinjha.minecraftassistant.core.AssistantException;
import dev.zubinjha.minecraftassistant.core.CancellationToken;
import dev.zubinjha.minecraftassistant.core.ErrorCode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class MediaWikiClient {
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final int DEFAULT_MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final int CACHE_ENTRIES = 100;
    private static final long CACHE_MILLIS = Duration.ofMinutes(10).toMillis();
    private static final String USER_AGENT =
            "MinecraftAssistant/0.1 (+https://github.com/zubinjha/minecraft-assistant)";

    private final URI endpoint;
    private final Executor executor;
    private final HttpClient http;
    private final Duration requestTimeout;
    private final int maxResponseBytes;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, CacheEntry> cache = new LinkedHashMap<>(16, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
            return size() > CACHE_ENTRIES;
        }
    };

    MediaWikiClient(String endpoint, Executor executor) {
        this(endpoint, executor, DEFAULT_REQUEST_TIMEOUT, DEFAULT_MAX_RESPONSE_BYTES);
    }

    MediaWikiClient(String endpoint, Executor executor, Duration requestTimeout, int maxResponseBytes) {
        this.endpoint = MediaWikiEndpoint.parse(endpoint);
        this.executor = executor;
        this.requestTimeout = requestTimeout;
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        if (maxResponseBytes < 1) {
            throw new IllegalArgumentException("maxResponseBytes must be positive");
        }
        this.maxResponseBytes = maxResponseBytes;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .executor(executor)
                .build();
    }

    CompletionStage<JsonNode> request(Map<String, String> input, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        Map<String, String> parameters = new LinkedHashMap<>(input);
        parameters.put("format", "json");
        parameters.put("formatversion", "2");
        URI uri = queryUri(parameters);
        String cached = cached(uri.toString());
        if (cached != null) {
            return CompletableFuture.completedFuture(parse(cached));
        }
        return request(uri, cancellation, true).thenApply(body -> {
            putCache(uri.toString(), body);
            return parse(body);
        });
    }

    private CompletionStage<String> request(URI uri, CancellationToken cancellation, boolean mayRetry) {
        try {
            cancellation.throwIfCancelled();
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .build();
        CompletableFuture<HttpResponse<InputStream>> sent = http.sendAsync(
                request,
                HttpResponse.BodyHandlers.ofInputStream()
        );
        AtomicReference<InputStream> responseBody = new AtomicReference<>();
        AutoCloseable cancellationRegistration = cancellation.onCancel(() -> {
            sent.cancel(true);
            InputStream body = responseBody.get();
            if (body != null) {
                closeQuietly(body);
            }
        });
        CompletionStage<String> responseStage = sent.thenCompose(response -> {
            responseBody.set(response.body());
            if (cancellation.isCancelled()) {
                closeQuietly(response.body());
                return CompletableFuture.failedFuture(new AssistantException(
                        ErrorCode.CANCELLED,
                        "The request was cancelled"
                ));
            }
            int status = response.statusCode();
            if (mayRetry && retryable(status)) {
                closeQuietly(response.body());
                long delayMillis = retryDelayMillis(response);
                return CompletableFuture.supplyAsync(
                        () -> null,
                        CompletableFuture.delayedExecutor(delayMillis, TimeUnit.MILLISECONDS, executor)
                ).thenCompose(ignored -> request(uri, cancellation, false));
            }
            if (status < 200 || status >= 300) {
                closeQuietly(response.body());
                return CompletableFuture.failedFuture(new AssistantException(
                        ErrorCode.TOOL_FAILURE,
                        "Minecraft Wiki returned HTTP " + status
                ));
            }
            return CompletableFuture.supplyAsync(() -> readBounded(response.body()), executor);
        }).exceptionallyCompose(failure -> {
            Throwable cause = unwrap(failure);
            if (cancellation.isCancelled()) {
                return CompletableFuture.failedFuture(new AssistantException(
                        ErrorCode.CANCELLED,
                        "The request was cancelled"
                ));
            }
            if (cause instanceof AssistantException) {
                return CompletableFuture.failedFuture(cause);
            }
            return CompletableFuture.failedFuture(new AssistantException(
                    ErrorCode.TOOL_FAILURE,
                    "Minecraft Wiki request failed",
                    cause
            ));
        });
        return responseStage.whenComplete((ignored, failure) -> closeQuietly(cancellationRegistration));
    }

    private URI queryUri(Map<String, String> parameters) {
        StringBuilder query = new StringBuilder();
        parameters.forEach((name, value) -> {
            if (!query.isEmpty()) {
                query.append('&');
            }
            query.append(encode(name)).append('=').append(encode(value));
        });
        return URI.create(endpoint + "?" + query);
    }

    private JsonNode parse(String body) {
        try {
            JsonNode parsed = json.readTree(body);
            if (parsed == null || !parsed.isObject()) {
                throw new AssistantException(ErrorCode.PROTOCOL_ERROR, "Minecraft Wiki returned invalid JSON");
            }
            JsonNode error = parsed.path("error");
            if (!error.isMissingNode()) {
                throw new AssistantException(
                        ErrorCode.TOOL_FAILURE,
                        "Minecraft Wiki API error: " + error.path("info").asText("unknown error")
                );
            }
            return parsed;
        } catch (IOException failure) {
            throw new AssistantException(ErrorCode.PROTOCOL_ERROR, "Minecraft Wiki returned invalid JSON", failure);
        }
    }

    private String readBounded(InputStream input) {
        try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > maxResponseBytes) {
                    throw new AssistantException(
                            ErrorCode.TOOL_FAILURE,
                            "Minecraft Wiki response exceeded " + maxResponseBytes + " bytes"
                    );
                }
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new AssistantException(ErrorCode.TOOL_FAILURE, "Could not read Minecraft Wiki response", failure);
        }
    }

    private synchronized String cached(String key) {
        CacheEntry entry = cache.get(key);
        if (entry == null) {
            return null;
        }
        if (System.currentTimeMillis() - entry.createdMillis() > CACHE_MILLIS) {
            cache.remove(key);
            return null;
        }
        return entry.body();
    }

    private synchronized void putCache(String key, String body) {
        cache.put(key, new CacheEntry(System.currentTimeMillis(), body));
    }

    private static boolean retryable(int status) {
        return status == 429 || status == 502 || status == 503 || status == 504;
    }

    private static long retryDelayMillis(HttpResponse<?> response) {
        String retryAfter = response.headers().firstValue("Retry-After").orElse("");
        try {
            return Math.min(2_000L, Math.max(0L, Long.parseLong(retryAfter) * 1_000L));
        } catch (NumberFormatException ignored) {
            return 250L;
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // Closing an error response is best effort.
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Removing a cancellation callback is best effort.
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

    private record CacheEntry(long createdMillis, String body) {
    }
}
