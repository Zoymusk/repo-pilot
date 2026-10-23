package com.repopilot.service;

import com.repopilot.service.MethodChunkerService.MethodChunk;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class InMemoryVectorStore {

    private static final int SEARCH_RESULT_LIMIT = 5;

    private final AtomicReference<List<IndexedChunk>> indexedChunks = new AtomicReference<>(List.of());

    public void replace(List<MethodChunk> chunks, List<List<Double>> embeddings) {
        if (chunks.size() != embeddings.size()) {
            throw new IllegalArgumentException("Each chunk must have exactly one embedding");
        }

        List<IndexedChunk> replacement = new ArrayList<>(chunks.size());
        for (int index = 0; index < chunks.size(); index++) {
            replacement.add(new IndexedChunk(chunks.get(index), List.copyOf(embeddings.get(index))));
        }
        indexedChunks.set(List.copyOf(replacement));
    }

    public List<SearchResult> search(List<Double> queryEmbedding) {
        return indexedChunks.get().stream()
                .map(indexed -> new SearchResult(indexed.chunk(), cosineSimilarity(queryEmbedding, indexed.embedding())))
                .sorted(Comparator.comparingDouble(SearchResult::score).reversed())
                .limit(SEARCH_RESULT_LIMIT)
                .toList();
    }

    static double cosineSimilarity(List<Double> left, List<Double> right) {
        if (left.size() != right.size()) {
            throw new IllegalArgumentException("Vectors must have the same number of dimensions");
        }
        if (left.isEmpty()) {
            throw new IllegalArgumentException("Vectors must not be empty");
        }

        double dotProduct = 0.0;
        double leftMagnitudeSquared = 0.0;
        double rightMagnitudeSquared = 0.0;
        for (int index = 0; index < left.size(); index++) {
            double leftValue = left.get(index);
            double rightValue = right.get(index);
            dotProduct += leftValue * rightValue;
            leftMagnitudeSquared += leftValue * leftValue;
            rightMagnitudeSquared += rightValue * rightValue;
        }

        if (leftMagnitudeSquared == 0.0 || rightMagnitudeSquared == 0.0) {
            return 0.0;
        }
        return dotProduct / (Math.sqrt(leftMagnitudeSquared) * Math.sqrt(rightMagnitudeSquared));
    }

    private record IndexedChunk(MethodChunk chunk, List<Double> embedding) {
    }

    public record SearchResult(MethodChunk chunk, double score) {
    }
}
