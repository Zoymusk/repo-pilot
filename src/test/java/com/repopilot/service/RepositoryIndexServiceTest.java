package com.repopilot.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RepositoryIndexServiceTest {

    @Test
    void rejectsConcurrentIndexRequestsForTheSameRepositoryAndReleasesLock() throws Exception {
        GitHubRepositoryService repositoryService = mock(GitHubRepositoryService.class);
        GeminiEmbeddingService embeddingService = mock(GeminiEmbeddingService.class);
        InMemoryVectorStore vectorStore = mock(InMemoryVectorStore.class);
        CountDownLatch indexingStarted = new CountDownLatch(1);
        CountDownLatch allowIndexToFinish = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(repositoryService.chunks(anyString(), nullable(String.class), anyInt())).thenAnswer(invocation -> {
            if (calls.getAndIncrement() == 0) {
                indexingStarted.countDown();
                if (!allowIndexToFinish.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to finish test indexing request");
                }
            }
            return List.of();
        });
        when(embeddingService.embedDocuments(List.of())).thenReturn(List.of());

        RepositoryIndexService indexService =
                new RepositoryIndexService(repositoryService, embeddingService, vectorStore);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        String repositoryUrl = "https://github.com/Owner/Repository";
        try {
            Future<Integer> firstIndex = executor.submit(() -> indexService.index(repositoryUrl, "src/main"));
            assertTrue(indexingStarted.await(5, TimeUnit.SECONDS));

            RepositoryIndexService.IndexingInProgressException exception = assertThrows(
                    RepositoryIndexService.IndexingInProgressException.class,
                    () -> indexService.index("https://github.com/owner/repository.git/", null)
            );
            assertTrue(exception.getMessage().contains("already in progress"));

            allowIndexToFinish.countDown();
            assertEquals(0, firstIndex.get(5, TimeUnit.SECONDS));
            assertEquals(0, indexService.index(repositoryUrl, "src/main"));
        } finally {
            allowIndexToFinish.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void reportsChunkLimitFailureWithoutStartingEmbedding() throws Exception {
        GitHubRepositoryService repositoryService = mock(GitHubRepositoryService.class);
        GeminiEmbeddingService embeddingService = mock(GeminiEmbeddingService.class);
        InMemoryVectorStore vectorStore = mock(InMemoryVectorStore.class);
        when(repositoryService.chunks(anyString(), nullable(String.class), anyInt()))
                .thenThrow(new MethodChunkerService.ChunkLimitExceededException("src/Large.java", 2_000));
        RepositoryIndexService indexService =
                new RepositoryIndexService(repositoryService, embeddingService, vectorStore);

        RepositoryIndexService.IndexLimitExceededException exception = assertThrows(
                RepositoryIndexService.IndexLimitExceededException.class,
                () -> indexService.index("https://github.com/owner/repository", "src/main")
        );

        assertTrue(exception.getMessage().contains("limited to 2000 chunks"));
        verifyNoInteractions(embeddingService, vectorStore);
    }
}
