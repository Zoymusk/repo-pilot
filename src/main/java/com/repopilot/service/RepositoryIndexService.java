package com.repopilot.service;

import com.repopilot.service.InMemoryVectorStore.SearchResult;
import com.repopilot.service.MethodChunkerService.MethodChunk;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RepositoryIndexService {

    private static final int MAX_INDEX_CHUNKS = 2_000;
    private static final Logger logger = LoggerFactory.getLogger(RepositoryIndexService.class);

    private final GitHubRepositoryService repositoryService;
    private final GeminiEmbeddingService embeddingService;
    private final InMemoryVectorStore vectorStore;
    private final Set<String> indexingRepositories = ConcurrentHashMap.newKeySet();

    public RepositoryIndexService(
            GitHubRepositoryService repositoryService,
            GeminiEmbeddingService embeddingService,
            InMemoryVectorStore vectorStore
    ) {
        this.repositoryService = repositoryService;
        this.embeddingService = embeddingService;
        this.vectorStore = vectorStore;
    }

    public int index(String repositoryUrl) throws IOException, GitAPIException {
        return index(repositoryUrl, null);
    }

    public int index(String repositoryUrl, String pathPrefix) throws IOException, GitAPIException {
        String repositoryKey = repositoryKey(repositoryUrl);
        if (!indexingRepositories.add(repositoryKey)) {
            throw new IndexingInProgressException(repositoryUrl);
        }
        try {
            logger.info("Starting repository indexing for {} with path prefix '{}'", repositoryUrl, pathPrefix);
            List<MethodChunk> chunks;
            try {
                chunks = repositoryService.chunks(repositoryUrl, pathPrefix, MAX_INDEX_CHUNKS);
            } catch (MethodChunkerService.ChunkLimitExceededException exception) {
                throw new IndexLimitExceededException(exception.getMessage(), exception);
            }
            List<List<Double>> embeddings = embeddingService.embedDocuments(
                    chunks.stream()
                            .map(chunk -> chunk.className() + "\n" + chunk.methodName() + "\n" + chunk.content())
                            .toList()
            );
            logger.info("Embedded {} chunks for {}; replacing the in-memory index", chunks.size(), repositoryUrl);
            vectorStore.replace(chunks, embeddings);
            logger.info("Repository indexing completed for {} with {} chunks", repositoryUrl, chunks.size());
            return chunks.size();
        } catch (IOException | GitAPIException | RuntimeException exception) {
            logger.error("Repository indexing failed for {} with path prefix '{}'", repositoryUrl, pathPrefix, exception);
            throw exception;
        } finally {
            indexingRepositories.remove(repositoryKey);
        }
    }

    public List<SearchResult> search(String question) {
        return vectorStore.search(embeddingService.embedQuestion(question));
    }

    private String repositoryKey(String repositoryUrl) {
        String key = repositoryUrl.trim().replaceAll("/+$", "").replaceFirst("(?i)\\.git$", "");
        return key.toLowerCase(Locale.ROOT);
    }

    public static class IndexingInProgressException extends RuntimeException {
        public IndexingInProgressException(String repositoryUrl) {
            super("An index request for repository " + repositoryUrl + " is already in progress.");
        }
    }

    public static class IndexLimitExceededException extends RuntimeException {
        public IndexLimitExceededException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
