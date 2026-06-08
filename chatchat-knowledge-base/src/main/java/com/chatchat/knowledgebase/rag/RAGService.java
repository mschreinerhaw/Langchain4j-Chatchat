package com.chatchat.knowledgebase.rag;

import com.chatchat.knowledgebase.embedding.service.EmbeddingService;
import com.chatchat.knowledgebase.service.KnowledgeBaseService;
import dev.langchain4j.data.document.Document;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * RAG (Retrieval Augmented Generation) service
 * Orchestrates retrieval of relevant documents and context for LLM
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RAGService {

    private final KnowledgeBaseService knowledgeBaseService;
    private final EmbeddingService embeddingService;

    /**
     * Retrieve relevant documents for a query
     */
    public List<Document> retrieveContext(String knowledgeBaseId, String query, int maxResults) {
        log.debug("Retrieving context for query: {} from KB: {}", query, knowledgeBaseId);

        return knowledgeBaseService.search(knowledgeBaseId, query, maxResults);
    }

    /**
     * Perform hybrid search combining BM25 and semantic similarity
     */
    public List<Document> hybridRetrieve(String knowledgeBaseId, String query, int maxResults) {
        log.debug("Performing hybrid retrieval for query: {} from KB: {}", query, knowledgeBaseId);

        return knowledgeBaseService.hybridSearch(knowledgeBaseId, query, maxResults);
    }

    /**
     * Build prompt with retrieved context
     */
    public String buildContextualPrompt(String query, List<Document> contextDocs, String systemPrompt) {
        log.debug("Building contextual prompt with {} context documents", contextDocs.size());

        StringBuilder prompt = new StringBuilder();

        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            prompt.append("System: ").append(systemPrompt).append("\n\n");
        }

        if (!contextDocs.isEmpty()) {
            prompt.append("Context Documents:\n");
            for (int i = 0; i < contextDocs.size(); i++) {
                prompt.append("--- Document ").append(i + 1).append(" ---\n");
                prompt.append(contextDocs.get(i).text()).append("\n\n");
            }
        }

        prompt.append("Question: ").append(query);

        return prompt.toString();
    }

    /**
     * Get retrieval statistics
     */
    public RetrievalStats getStats(String knowledgeBaseId) {
        KnowledgeBaseService.KnowledgeBaseMeta kbMeta = knowledgeBaseService.getMetadata(knowledgeBaseId);
        com.chatchat.knowledgebase.embedding.store.VectorStore.StoreStats embeddingStats = embeddingService.getStats();

        return new RetrievalStats(
            kbMeta.documentCount(),
            embeddingStats.totalEmbeddings(),
            embeddingStats.storageSize()
        );
    }

    /**
     * Retrieval statistics record
     */
    public record RetrievalStats(
        int documentCount,
        int totalEmbeddings,
        long storageSize
    ) {}
}
