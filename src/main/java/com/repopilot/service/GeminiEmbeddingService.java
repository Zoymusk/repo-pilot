package com.repopilot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class GeminiEmbeddingService {

    private static final Logger logger = LoggerFactory.getLogger(GeminiEmbeddingService.class);
    private static final String EMBEDDING_ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/%s:batchEmbedContents";
    private static final String DOCUMENT_TASK_TYPE = "RETRIEVAL_DOCUMENT";
    private static final String QUERY_TASK_TYPE = "RETRIEVAL_QUERY";
    private static final int MAX_BATCH_SIZE = 50;
    private static final int MAX_RATE_LIMIT_RETRIES = 5;
    private static final int MAX_NETWORK_ATTEMPTS = 4;
    private static final Duration INITIAL_NETWORK_RETRY_DELAY = Duration.ofSeconds(1);
    private static final Duration BATCH_PAUSE = Duration.ofSeconds(1);
    private static final Duration MAX_RETRY_DELAY = Duration.ofSeconds(60);
    private static final Duration DAILY_QUOTA_RETRY_THRESHOLD = Duration.ofSeconds(90);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String embeddingModel;
    private final String apiKey;
    private final Map<CacheKey, List<Double>> documentEmbeddingCache = new ConcurrentHashMap<>();

    @Autowired
    public GeminiEmbeddingService(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            @Value("${GEMINI_EMBEDDING_MODEL:gemini-embedding-001}") String embeddingModel,
            @Value("${GEMINI_API_KEY:}") String apiKey
    ) {
        this(GeminiRestClientFactory.build(restClientBuilder), objectMapper, embeddingModel, apiKey);
    }

    GeminiEmbeddingService(RestClient restClient, ObjectMapper objectMapper, String embeddingModel, String apiKey) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.embeddingModel = normalizeModelName(embeddingModel);
        this.apiKey = apiKey;
    }

    public synchronized List<List<Double>> embedDocuments(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("GEMINI_API_KEY environment variable is required");
        }

        List<CacheKey> keys = texts.stream().map(this::cacheKey).toList();
        Map<CacheKey, String> missing = new LinkedHashMap<>();
        for (int index = 0; index < keys.size(); index++) {
            if (!documentEmbeddingCache.containsKey(keys.get(index))) {
                missing.putIfAbsent(keys.get(index), texts.get(index));
            }
        }
        if (!missing.isEmpty()) {
            embed(List.copyOf(missing.values()), DOCUMENT_TASK_TYPE);
        }
        return keys.stream().map(documentEmbeddingCache::get).toList();
    }

    public List<Double> embedQuestion(String question) {
        return embed(List.of(question), QUERY_TASK_TYPE).get(0);
    }

    private List<List<Double>> embed(List<String> texts, String taskType) {
        if (texts.isEmpty()) {
            return List.of();
        }

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("GEMINI_API_KEY environment variable is required");
        }

        List<List<Double>> embeddings = new ArrayList<>(texts.size());
        int totalBatches = (texts.size() + MAX_BATCH_SIZE - 1) / MAX_BATCH_SIZE;
        int batchNumber = 0;
        for (int start = 0; start < texts.size(); start += MAX_BATCH_SIZE) {
            List<String> batch = texts.subList(start, Math.min(start + MAX_BATCH_SIZE, texts.size()));
            batchNumber++;
            long batchStartNanos = System.nanoTime();
            if (DOCUMENT_TASK_TYPE.equals(taskType)) {
                logger.info(
                        "Starting embedding batch {} of {} with {} texts",
                        batchNumber,
                        totalBatches,
                        batch.size()
                );
            }
            try {
                BatchEmbeddingResponse response = embedBatch(batch, taskType, apiKey);
                if (response == null || response.embeddings() == null || response.embeddings().size() != batch.size()) {
                    throw new IllegalStateException("Gemini returned an incomplete embeddings response");
                }
                for (int index = 0; index < response.embeddings().size(); index++) {
                    Embedding embedding = response.embeddings().get(index);
                    if (embedding.values() == null || embedding.values().isEmpty()) {
                        throw new IllegalStateException("Gemini returned an empty embedding");
                    }
                    embeddings.add(embedding.values());
                    if (DOCUMENT_TASK_TYPE.equals(taskType)) {
                        documentEmbeddingCache.put(cacheKey(batch.get(index)), List.copyOf(embedding.values()));
                    }
                }
                if (DOCUMENT_TASK_TYPE.equals(taskType)) {
                    logger.info(
                            "Completed embedding batch {} of {} with {} texts in {} ms",
                            batchNumber,
                            totalBatches,
                            batch.size(),
                            Duration.ofNanos(System.nanoTime() - batchStartNanos).toMillis()
                    );
                }
            } catch (RuntimeException exception) {
                logger.error(
                        "Embedding batch {} of {} failed with {} texts",
                        batchNumber,
                        totalBatches,
                        batch.size(),
                        exception
                );
                throw exception;
            }
            if (start + batch.size() < texts.size()) {
                pause(BATCH_PAUSE);
            }
        }
        return List.copyOf(embeddings);
    }

    private BatchEmbeddingResponse embedBatch(List<String> batch, String taskType, String apiKey) {
        BatchEmbeddingRequest request = new BatchEmbeddingRequest(batch.stream()
                .map(text -> new EmbeddingRequest(
                        "models/" + embeddingModel,
                        new Content(List.of(new Part(text))),
                        taskType
                ))
                .toList());

        int rateLimitRetries = 0;
        int networkAttempts = 0;
        for (;;) {
            try {
                return restClient.post()
                        .uri(EMBEDDING_ENDPOINT.formatted(embeddingModel))
                        .header("x-goog-api-key", apiKey)
                        .body(request)
                        .retrieve()
                        .body(BatchEmbeddingResponse.class);
            } catch (HttpClientErrorException exception) {
                if (exception.getStatusCode().value() != 429) {
                    throw exception;
                }
                Duration requestedDelay = retryDelay(exception);
                if (requestedDelay.compareTo(DAILY_QUOTA_RETRY_THRESHOLD) > 0) {
                    throw new DailyEmbeddingQuotaExceededException(requestedDelay, exception);
                }
                if (rateLimitRetries >= MAX_RATE_LIMIT_RETRIES) {
                    throw exception;
                }
                rateLimitRetries++;
                Duration wait = cappedRetryDelay(requestedDelay);
                logger.info(
                        "Gemini returned HTTP 429; retry delay is {} seconds, waiting {} seconds (maximum 60 seconds)",
                        requestedDelay.toMillis() / 1000.0,
                        wait.toMillis() / 1000.0
                );
                pause(wait);
            } catch (ResourceAccessException exception) {
                networkAttempts++;
                if (networkAttempts >= MAX_NETWORK_ATTEMPTS) {
                    logger.error("Gemini embedding request failed after {} network attempts", networkAttempts, exception);
                    throw exception;
                }
                Duration wait = INITIAL_NETWORK_RETRY_DELAY.multipliedBy(1L << (networkAttempts - 1));
                logger.warn(
                        "Gemini embedding request encountered a network error; retrying attempt {} of {} after {} seconds",
                        networkAttempts + 1,
                        MAX_NETWORK_ATTEMPTS,
                        wait.toSeconds(),
                        exception
                );
                pause(wait);
            }
        }
    }

    private CacheKey cacheKey(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return new CacheKey(embeddingModel, HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable for embedding cache keys", exception);
        }
    }

    static Duration cappedRetryDelay(Duration requestedDelay) {
        return requestedDelay.compareTo(MAX_RETRY_DELAY) > 0 ? MAX_RETRY_DELAY : requestedDelay;
    }

    static String normalizeModelName(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return "gemini-embedding-001";
        }
        String normalized = modelName.trim();
        return normalized.startsWith("models/") ? normalized.substring("models/".length()) : normalized;
    }

    private static String formatQuotaResetDelay(Duration delay) {
        long seconds = delay.toSeconds();
        long hours = seconds / 3_600;
        long minutes = (seconds % 3_600) / 60;
        long remainingSeconds = seconds % 60;
        if (hours > 0) {
            return minutes > 0
                    ? hours + unit(hours, "hour") + " and " + minutes + unit(minutes, "minute")
                    : hours + unit(hours, "hour");
        }
        if (minutes > 0) {
            return remainingSeconds > 0
                    ? minutes + unit(minutes, "minute") + " and " + remainingSeconds + unit(remainingSeconds, "second")
                    : minutes + unit(minutes, "minute");
        }
        return seconds + unit(seconds, "second");
    }

    private static String unit(long value, String name) {
        return " " + name + (value == 1 ? "" : "s");
    }

    private Duration retryDelay(HttpClientErrorException exception) {
        try {
            JsonNode root = objectMapper.readTree(exception.getResponseBodyAsString());
            if (root == null) {
                throw new IllegalStateException("Gemini returned HTTP 429 without a retry delay", exception);
            }
            JsonNode retryDelay = root.path("error").path("details").findValue("retryDelay");
            if (retryDelay == null || !retryDelay.isTextual()) {
                throw new IllegalStateException("Gemini returned HTTP 429 without a retry delay", exception);
            }
            String value = retryDelay.asText();
            if (!value.matches("\\d+(\\.\\d{1,9})?s")) {
                throw new IllegalStateException("Gemini returned an invalid retry delay: " + value, exception);
            }
            String seconds = value.substring(0, value.length() - 1);
            return Duration.ofNanos(new java.math.BigDecimal(seconds)
                    .movePointRight(9)
                    .longValueExact());
        } catch (IOException | ArithmeticException parseException) {
            throw new IllegalStateException("Could not read Gemini's retry delay from its HTTP 429 response", parseException);
        }
    }

    void pause(Duration duration) {
        long millis = duration.toMillis();
        if (duration.compareTo(Duration.ofMillis(millis)) > 0) {
            millis++;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to retry Gemini embedding request", exception);
        }
    }

    public static class DailyEmbeddingQuotaExceededException extends RuntimeException {
        public DailyEmbeddingQuotaExceededException(Duration resetDelay, Throwable cause) {
            super("Gemini's daily embedding quota is used up. It should reset in "
                    + formatQuotaResetDelay(resetDelay) + ".", cause);
        }
    }

    private record CacheKey(String model, String textHash) {
    }

    private record BatchEmbeddingRequest(List<EmbeddingRequest> requests) {
    }

    private record EmbeddingRequest(String model, Content content, String taskType) {
    }

    private record Content(List<Part> parts) {
    }

    private record Part(String text) {
    }

    private record BatchEmbeddingResponse(List<Embedding> embeddings) {
    }

    private record Embedding(List<Double> values) {
    }
}
