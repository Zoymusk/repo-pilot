package com.repopilot.service;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

@Service
public class GeminiEmbeddingService {

    private static final String EMBEDDING_ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-embedding-001:batchEmbedContents";
    private static final String DOCUMENT_TASK_TYPE = "RETRIEVAL_DOCUMENT";
    private static final String QUERY_TASK_TYPE = "RETRIEVAL_QUERY";
    private static final int MAX_BATCH_SIZE = 100;

    private final RestClient restClient;

    public GeminiEmbeddingService(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    public List<List<Double>> embedDocuments(List<String> texts) {
        return embed(texts, DOCUMENT_TASK_TYPE);
    }

    public List<Double> embedQuestion(String question) {
        return embed(List.of(question), QUERY_TASK_TYPE).get(0);
    }

    private List<List<Double>> embed(List<String> texts, String taskType) {
        if (texts.isEmpty()) {
            return List.of();
        }

        String apiKey = System.getenv("GEMINI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("GEMINI_API_KEY environment variable is required");
        }

        List<List<Double>> embeddings = new ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += MAX_BATCH_SIZE) {
            List<String> batch = texts.subList(start, Math.min(start + MAX_BATCH_SIZE, texts.size()));
            BatchEmbeddingResponse response = restClient.post()
                    .uri(EMBEDDING_ENDPOINT)
                    .header("x-goog-api-key", apiKey)
                    .body(new BatchEmbeddingRequest(batch.stream()
                            .map(text -> new EmbeddingRequest(
                                    "models/gemini-embedding-001",
                                    new Content(List.of(new Part(text))),
                                    taskType
                            ))
                            .toList()))
                    .retrieve()
                    .body(BatchEmbeddingResponse.class);
            if (response == null || response.embeddings() == null || response.embeddings().size() != batch.size()) {
                throw new IllegalStateException("Gemini returned an incomplete embeddings response");
            }
            for (Embedding embedding : response.embeddings()) {
                if (embedding.values() == null || embedding.values().isEmpty()) {
                    throw new IllegalStateException("Gemini returned an empty embedding");
                }
                embeddings.add(embedding.values());
            }
        }
        return List.copyOf(embeddings);
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
