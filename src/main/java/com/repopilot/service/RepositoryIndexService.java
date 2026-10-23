package com.repopilot.service;

import com.repopilot.service.InMemoryVectorStore.SearchResult;
import com.repopilot.service.MethodChunkerService.MethodChunk;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

@Service
public class RepositoryIndexService {

    private final GitHubRepositoryService repositoryService;
    private final GeminiEmbeddingService embeddingService;
    private final InMemoryVectorStore vectorStore;

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
        List<MethodChunk> chunks = repositoryService.chunks(repositoryUrl);
        List<List<Double>> embeddings = embeddingService.embedDocuments(
                chunks.stream().map(MethodChunk::content).toList()
        );
        vectorStore.replace(chunks, embeddings);
        return chunks.size();
    }

    public List<SearchResult> search(String question) {
        return vectorStore.search(embeddingService.embedQuestion(question));
    }
}
