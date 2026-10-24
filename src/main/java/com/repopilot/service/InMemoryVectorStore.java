package com.repopilot.service;

import com.repopilot.service.MethodChunkerService.MethodChunk;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class InMemoryVectorStore {

    private final AtomicReference<List<IndexedChunk>> indexedChunks = new AtomicReference<>(List.of());
    private final int searchResultLimit;

    public InMemoryVectorStore() {
        this(12);
    }

    @Autowired
    public InMemoryVectorStore(@Value("${repository.search.max-results:12}") int searchResultLimit) {
        if (searchResultLimit < 1) {
            throw new IllegalArgumentException("Repository search max-results must be positive");
        }
        this.searchResultLimit = searchResultLimit;
    }

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
        List<SearchResult> ranked = indexedChunks.get().stream()
                .map(indexed -> new SearchResult(indexed.chunk(), cosineSimilarity(queryEmbedding, indexed.embedding())))
                .sorted(Comparator.comparingDouble(SearchResult::score).reversed())
                .toList();
        Map<String, Integer> perFileCounts = new HashMap<>();
        List<SearchResult> results = new ArrayList<>(searchResultLimit);
        for (SearchResult result : ranked) {
            String filePath = result.chunk().filePath();
            int fileCount = perFileCounts.getOrDefault(filePath, 0);
            if (fileCount >= 2) {
                continue;
            }
            results.add(result);
            perFileCounts.put(filePath, fileCount + 1);
            if (results.size() == searchResultLimit) {
                break;
            }
        }
        return List.copyOf(results);
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
