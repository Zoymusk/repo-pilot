package com.repopilot.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InMemoryVectorStoreTest {

    @Test
    void calculatesCosineSimilarity() {
        assertEquals(1.0, InMemoryVectorStore.cosineSimilarity(List.of(1.0, 2.0), List.of(2.0, 4.0)), 1.0e-10);
        assertEquals(0.0, InMemoryVectorStore.cosineSimilarity(List.of(1.0, 0.0), List.of(0.0, 1.0)), 1.0e-10);
        assertEquals(-1.0, InMemoryVectorStore.cosineSimilarity(List.of(1.0, 0.0), List.of(-1.0, 0.0)), 1.0e-10);
    }

    @Test
    void limitsResultsAndReturnsAtMostTwoChunksPerFile() {
        InMemoryVectorStore store = new InMemoryVectorStore(3);
        List<MethodChunkerService.MethodChunk> chunks = List.of(
                chunk("A.java", "first"),
                chunk("A.java", "second"),
                chunk("A.java", "third"),
                chunk("B.java", "fourth")
        );
        store.replace(chunks, List.of(
                List.of(1.0, 0.0),
                List.of(0.9, 0.1),
                List.of(0.8, 0.2),
                List.of(0.7, 0.3)
        ));

        List<InMemoryVectorStore.SearchResult> results = store.search(List.of(1.0, 0.0));

        assertEquals(3, results.size());
        assertEquals(List.of("first", "second", "fourth"),
                results.stream().map(result -> result.chunk().methodName()).toList());
    }

    @Test
    void defaultsToTwelveResults() {
        InMemoryVectorStore store = new InMemoryVectorStore();
        List<MethodChunkerService.MethodChunk> chunks = new ArrayList<>();
        List<List<Double>> embeddings = new ArrayList<>();
        for (int index = 0; index < 14; index++) {
            chunks.add(chunk("File" + index + ".java", "method" + index));
            embeddings.add(List.of(1.0, index + 1.0));
        }
        store.replace(chunks, embeddings);

        assertEquals(12, store.search(List.of(1.0, 0.0)).size());
    }

    @Test
    void rejectsNonPositiveResultLimit() {
        assertThrows(IllegalArgumentException.class, () -> new InMemoryVectorStore(0));
    }

    private MethodChunkerService.MethodChunk chunk(String path, String method) {
        return new MethodChunkerService.MethodChunk("Example", method, path, 1, 1, method);
    }
}
