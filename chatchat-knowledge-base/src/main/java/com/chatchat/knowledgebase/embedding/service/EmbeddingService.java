package com.chatchat.knowledgebase.embedding.service;

import com.chatchat.knowledgebase.embedding.store.VectorStore;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Service for managing embeddings and vector operations
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingService {

    private final EmbeddingModel embeddingModel;
    private final VectorStore vectorStore;

    /**
     * Embed and store documents
     */
    public void embedAndStore(List<Document> documents) {
        log.info("Embedding and storing {} documents", documents.size());

        var embeddings = documents.stream()
            .map(doc -> embeddingModel.embed(doc.text()).content())
            .toList();

        vectorStore.add(documents, embeddings);
        log.info("Successfully stored {} documents with embeddings", documents.size());
    }

    /**
     * Search for similar documents
     */
    public List<Document> search(String query, int maxResults) {
        log.debug("Searching for: {}", query);
        var embedding = embeddingModel.embed(query).content();
        return vectorStore.search(embedding, maxResults);
    }

    /**
     * Get embedding statistics
     */
    public VectorStore.StoreStats getStats() {
        return vectorStore.getStats();
    }
}
