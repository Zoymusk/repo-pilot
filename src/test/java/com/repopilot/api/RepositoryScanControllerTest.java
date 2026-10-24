package com.repopilot.api;

import com.repopilot.service.GeminiAnswerService;
import com.repopilot.service.GitHubRepositoryService;
import com.repopilot.service.RepositoryAskService;
import com.repopilot.service.RepositoryIndexService;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RepositoryScanControllerTest {

    @Test
    void passesReceivedPathPrefixToIndexService() throws IOException, org.eclipse.jgit.api.errors.GitAPIException {
        GitHubRepositoryService repositoryService = mock(GitHubRepositoryService.class);
        RepositoryIndexService indexService = mock(RepositoryIndexService.class);
        RepositoryAskService askService = mock(RepositoryAskService.class);
        when(indexService.index("https://github.com/owner/repository", "src/main/java")).thenReturn(12);
        RepositoryScanController controller =
                new RepositoryScanController(repositoryService, indexService, askService);

        RepositoryScanController.RepositoryIndexResponse response = controller.index(
                new RepositoryScanController.RepositoryIndexRequest(
                        "https://github.com/owner/repository",
                        "src/main/java"
                )
        );

        assertEquals(12, response.indexedChunkCount());
        verify(indexService).index("https://github.com/owner/repository", "src/main/java");
    }

    @Test
    void mapsQuotaExhaustionToTooManyRequestsWithResetMessage() throws IOException, org.eclipse.jgit.api.errors.GitAPIException {
        RepositoryIndexService indexService = mock(RepositoryIndexService.class);
        when(indexService.index("https://github.com/owner/repository", null))
                .thenThrow(new com.repopilot.service.GeminiEmbeddingService.DailyEmbeddingQuotaExceededException(
                        java.time.Duration.ofMinutes(20),
                        new IllegalStateException("quota")
                ));
        RepositoryScanController controller = new RepositoryScanController(
                mock(GitHubRepositoryService.class),
                indexService,
                mock(RepositoryAskService.class)
        );

        org.springframework.web.server.ResponseStatusException exception =
                org.junit.jupiter.api.Assertions.assertThrows(
                        org.springframework.web.server.ResponseStatusException.class,
                        () -> controller.index(new RepositoryScanController.RepositoryIndexRequest(
                                "https://github.com/owner/repository",
                                null
                        ))
                );

        assertEquals(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, exception.getStatusCode());
        assertEquals(
                "Gemini's daily embedding quota is used up. It should reset in 20 minutes.",
                exception.getReason()
        );
    }
}
