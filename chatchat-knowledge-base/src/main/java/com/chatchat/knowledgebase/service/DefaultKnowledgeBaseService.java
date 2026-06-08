package com.chatchat.knowledgebase.service;

import com.chatchat.knowledgebase.embedding.service.EmbeddingService;
import com.chatchat.knowledgebase.embedding.store.VectorStore;
import dev.langchain4j.data.document.Document;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default implementation of KnowledgeBaseService
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultKnowledgeBaseService implements KnowledgeBaseService {

    private final EmbeddingService embeddingService;
    private final VectorStore vectorStore;

    // Simple in-memory metadata store (replace with database in production)
    private final Map<String, KnowledgeBaseMetadata> metadataStore = new ConcurrentHashMap<>();

    /**
     * Metadata storage class
     */
    private static class KnowledgeBaseMetadata {
        String id;
        String name;
        String description;
        Set<String> documentIds;
        long createdAt;
        long updatedAt;

        KnowledgeBaseMetadata(String id, String name, String description) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.documentIds = Collections.synchronizedSet(new HashSet<>());
            this.createdAt = Instant.now().toEpochMilli();
            this.updatedAt = this.createdAt;
        }
    }

    @Override
    public void createKnowledgeBase(String name, String description) {
        log.info("Creating knowledge base: {}", name);

        String id = UUID.randomUUID().toString();
        metadataStore.put(id, new KnowledgeBaseMetadata(id, name, description));

        log.info("Knowledge base created with ID: {}", id);
    }

    @Override
    public void addDocuments(String knowledgeBaseId, List<Document> documents) {
        log.info("Adding {} documents to knowledge base: {}", documents.size(), knowledgeBaseId);

        KnowledgeBaseMetadata metadata = metadataStore.get(knowledgeBaseId);
        if (metadata == null) {
            log.warn("Knowledge base not found: {}", knowledgeBaseId);
            return;
        }

        // Embed and store documents
        embeddingService.embedAndStore(documents);

        // Track document IDs
        documents.forEach(doc -> metadata.documentIds.add(resolveDocumentId(doc)));
        metadata.updatedAt = Instant.now().toEpochMilli();

        log.info("Documents added successfully");
    }

    @Override
    public List<Document> search(String knowledgeBaseId, String query, int maxResults) {
        log.debug("Searching knowledge base: {} for query: {}", knowledgeBaseId, query);

        KnowledgeBaseMetadata metadata = metadataStore.get(knowledgeBaseId);
        if (metadata == null) {
            log.warn("Knowledge base not found: {}", knowledgeBaseId);
            return Collections.emptyList();
        }

        return embeddingService.search(query, maxResults);
    }

    @Override
    public List<Document> hybridSearch(String knowledgeBaseId, String query, int maxResults) {
        log.debug("Performing hybrid search in KB: {} for query: {}", knowledgeBaseId, query);

        // In production, implement BM25 + semantic search
        // For now, fall back to semantic search
        return search(knowledgeBaseId, query, maxResults);
    }

    @Override
    public void deleteKnowledgeBase(String knowledgeBaseId) {
        log.info("Deleting knowledge base: {}", knowledgeBaseId);

        KnowledgeBaseMetadata metadata = metadataStore.get(knowledgeBaseId);
        if (metadata != null) {
            vectorStore.delete(new ArrayList<>(metadata.documentIds));
            metadataStore.remove(knowledgeBaseId);
        }
    }

    @Override
    public KnowledgeBaseMeta getMetadata(String knowledgeBaseId) {
        KnowledgeBaseMetadata metadata = metadataStore.get(knowledgeBaseId);

        if (metadata == null) {
            return null;
        }

        return new KnowledgeBaseMeta(
            metadata.id,
            metadata.name,
            metadata.description,
            metadata.documentIds.size(),
            metadata.createdAt,
            metadata.updatedAt
        );
    }

    private String resolveDocumentId(Document document) {
        String explicitId = document.metadata() != null
            ? document.metadata().getString("doc_id")
            : null;
        if (explicitId != null && !explicitId.isBlank()) {
            return explicitId;
        }
        // Deterministic fallback keeps IDs stable across repeated ingests.
        return UUID.nameUUIDFromBytes(document.text().getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }
}
