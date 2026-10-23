package com.repopilot.api;

import com.repopilot.service.GitHubRepositoryService;
import com.repopilot.service.GeminiAnswerService.Answer;
import com.repopilot.service.GeminiAnswerService;
import com.repopilot.service.InMemoryVectorStore.SearchResult;
import com.repopilot.service.MethodChunkerService.MethodChunk;
import com.repopilot.service.RepositoryIndexService;
import com.repopilot.service.RepositoryAskService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClientException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/repositories")
public class RepositoryScanController {

    private static final Logger logger = LoggerFactory.getLogger(RepositoryScanController.class);

    private final GitHubRepositoryService repositoryService;
    private final RepositoryIndexService indexService;
    private final RepositoryAskService askService;

    public RepositoryScanController(
            GitHubRepositoryService repositoryService,
            RepositoryIndexService indexService,
            RepositoryAskService askService
    ) {
        this.repositoryService = repositoryService;
        this.indexService = indexService;
        this.askService = askService;
    }

    @PostMapping("/scan")
    public RepositoryScanResponse scan(@Valid @RequestBody RepositoryScanRequest request) {
        try {
            return repositoryService.scan(request.repositoryUrl());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        } catch (GitAPIException | IOException exception) {
            logger.error("Unable to clone or scan GitHub repository {}", request.repositoryUrl(), exception);
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Unable to clone or scan the GitHub repository",
                    exception
            );
        }
    }

    @PostMapping("/chunks")
    public RepositoryChunksResponse chunks(@Valid @RequestBody RepositoryScanRequest request) {
        try {
            List<MethodChunk> chunks = repositoryService.chunks(request.repositoryUrl());
            return new RepositoryChunksResponse(chunks, chunks.size());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        } catch (GitAPIException | IOException exception) {
            logger.error("Unable to clone or chunk GitHub repository {}", request.repositoryUrl(), exception);
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Unable to clone or chunk the GitHub repository",
                    exception
            );
        }
    }

    @PostMapping("/index")
    public RepositoryIndexResponse index(@Valid @RequestBody RepositoryScanRequest request) {
        try {
            return new RepositoryIndexResponse(indexService.index(request.repositoryUrl()));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage(), exception);
        } catch (GitAPIException | IOException | RestClientException exception) {
            logger.error("Unable to clone or index GitHub repository {}", request.repositoryUrl(), exception);
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Unable to clone or index the GitHub repository",
                    exception
            );
        }
    }

    @PostMapping("/search")
    public RepositorySearchResponse search(@Valid @RequestBody RepositorySearchRequest request) {
        try {
            List<RepositorySearchResult> results = indexService.search(request.question()).stream()
                    .map(result -> new RepositorySearchResult(
                            result.chunk().className(),
                            result.chunk().methodName(),
                            result.chunk().filePath(),
                            result.chunk().startLine(),
                            result.chunk().endLine(),
                            result.chunk().content(),
                            result.score()
                    ))
                    .toList();
            return new RepositorySearchResponse(results);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage(), exception);
        } catch (RestClientException exception) {
            logger.error("Unable to embed repository search question", exception);
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Unable to embed the search question",
                    exception
            );
        }
    }

    @PostMapping("/ask")
    public Answer ask(@Valid @RequestBody RepositoryAskRequest request) {
        try {
            return askService.ask(request.question(), request.issueDescription());
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage(), exception);
        } catch (GeminiAnswerService.GeminiModelBusyException exception) {
            logger.warn("Gemini models are unavailable while answering a repository question", exception);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage(), exception);
        } catch (GeminiAnswerService.InvalidGeminiResponseException | RestClientException exception) {
            logger.error("Unable to generate a repository answer", exception);
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Unable to generate a valid repository answer",
                    exception
            );
        }
    }

    public record RepositoryScanRequest(@NotBlank String repositoryUrl) {
    }

    public record RepositorySearchRequest(@NotBlank String question) {
    }

    public record RepositoryAskRequest(@NotBlank String question, String issueDescription) {
    }

    public record RepositoryScanResponse(List<String> javaFiles, int count) {
    }

    public record RepositoryChunksResponse(List<MethodChunk> chunks, int count) {
    }

    public record RepositoryIndexResponse(int indexedChunkCount) {
    }

    public record RepositorySearchResponse(List<RepositorySearchResult> results) {
    }

    public record RepositorySearchResult(
            String className,
            String methodName,
            String filePath,
            int startLine,
            int endLine,
            String content,
            double score
    ) {
    }
}
