package com.chatchat.knowledgebase.embedding.store;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.embedding.Embedding;
import java.util.List;

/**
 * Interface for vector store implementations
 * Abstracts different vector database backends (FAISS, Milvus, PostgreSQL pgvector, etc.)
 */
public interface VectorStore {

    /**
     * Add embedded documents to the store
     */
    void add(List<Document> documents, List<Embedding> embeddings);

    /**
     * Search for similar vectors
     */
    List<Document> search(Embedding embedding, int maxResults);

    /**
     * Search with filter criteria
     */
    List<Document> search(Embedding embedding, int maxResults, String filter);

    /**
     * Delete documents by IDs
     */
    void delete(List<String> documentIds);

    /**
     * Clear all vectors from the store
     */
    void clear();

    /**
     * Get store statistics
     */
    StoreStats getStats();

    /**
     * Store statistics
     */
    record StoreStats(
        int totalDocuments,
        int totalEmbeddings,
        long storageSize
    ) {}
}
