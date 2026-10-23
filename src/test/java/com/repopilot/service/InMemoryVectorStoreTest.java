package com.repopilot.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InMemoryVectorStoreTest {

    @Test
    void calculatesCosineSimilarity() {
        assertEquals(1.0, InMemoryVectorStore.cosineSimilarity(List.of(1.0, 2.0), List.of(2.0, 4.0)), 1.0e-10);
        assertEquals(0.0, InMemoryVectorStore.cosineSimilarity(List.of(1.0, 0.0), List.of(0.0, 1.0)), 1.0e-10);
        assertEquals(-1.0, InMemoryVectorStore.cosineSimilarity(List.of(1.0, 0.0), List.of(-1.0, 0.0)), 1.0e-10);
    }
}
