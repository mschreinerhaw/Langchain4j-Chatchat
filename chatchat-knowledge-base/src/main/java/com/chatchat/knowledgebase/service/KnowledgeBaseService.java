package com.chatchat.knowledgebase.service;

import dev.langchain4j.data.document.Document;
import java.util.List;

/**
 * Interface for knowledge base management
 */
public interface KnowledgeBaseService {

    /**
     * Create a new knowledge base
     */
    void createKnowledgeBase(String name, String description);

    /**
     * Add documents to knowledge base
     */
    void addDocuments(String knowledgeBaseId, List<Document> documents);

    /**
     * Search knowledge base
     */
    List<Document> search(String knowledgeBaseId, String query, int maxResults);

    /**
     * Hybrid search (BM25 + semantic)
     */
    List<Document> hybridSearch(String knowledgeBaseId, String query, int maxResults);

    /**
     * Delete knowledge base
     */
    void deleteKnowledgeBase(String knowledgeBaseId);

    /**
     * Get knowledge base metadata
     */
    KnowledgeBaseMeta getMetadata(String knowledgeBaseId);

    /**
     * Metadata record
     */
    record KnowledgeBaseMeta(
        String id,
        String name,
        String description,
        int documentCount,
        long createdAt,
        long updatedAt
    ) {}
}
