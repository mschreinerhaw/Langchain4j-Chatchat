package com.chatchat.embeddings.store;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.embedding.Embedding;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory vector store implementation for development/testing
 * For production, use specialized vector databases like Milvus, Weaviate, or Pinecone
 */
@Slf4j
@Component
public class InMemoryVectorStore implements VectorStore {

    private static class VectorEntry {
        String id;
        Document document;
        Embedding embedding;

        VectorEntry(String id, Document document, Embedding embedding) {
            this.id = id;
            this.document = document;
            this.embedding = embedding;
        }
    }

    private final Map<String, VectorEntry> store = new ConcurrentHashMap<>();
    private int totalEmbeddings = 0;

    @Override
    public void add(List<Document> documents, List<Embedding> embeddings) {
        log.info("Adding {} documents to in-memory vector store", documents.size());

        for (int i = 0; i < documents.size(); i++) {
            String id = resolveDocumentId(documents.get(i));
            boolean exists = store.containsKey(id);
            store.put(id, new VectorEntry(id, documents.get(i), embeddings.get(i)));
            if (!exists) {
                totalEmbeddings++;
            }
        }
    }

    @Override
    public List<Document> search(Embedding embedding, int maxResults) {
        log.debug("Searching for similar documents, max results: {}", maxResults);

        return store.values().stream()
            .map(entry -> new SimilarityScore(entry.document, cosineSimilarity(embedding, entry.embedding)))
            .sorted(Comparator.comparingDouble(SimilarityScore::score).reversed())
            .limit(maxResults)
            .map(SimilarityScore::document)
            .collect(Collectors.toList());
    }

    @Override
    public List<Document> search(Embedding embedding, int maxResults, String filter) {
        log.debug("Searching with filter: {}", filter);

        // Simple filter implementation - in production use proper query DSL
        return search(embedding, maxResults).stream()
            .filter(doc -> doc.text().toLowerCase().contains(filter.toLowerCase()))
            .collect(Collectors.toList());
    }

    @Override
    public void delete(List<String> documentIds) {
        log.info("Deleting {} documents", documentIds.size());
        documentIds.forEach(store::remove);
    }

    @Override
    public void clear() {
        log.warn("Clearing entire vector store");
        store.clear();
        totalEmbeddings = 0;
    }

    @Override
    public StoreStats getStats() {
        return new StoreStats(
            store.size(),
            totalEmbeddings,
            estimateStorageSize()
        );
    }

    /**
     * Calculate cosine similarity between two embeddings
     */
    private double cosineSimilarity(Embedding e1, Embedding e2) {
        float[] v1 = e1.vector();
        float[] v2 = e2.vector();

        if (v1.length != v2.length) {
            return 0.0;
        }

        double dotProduct = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;

        for (int i = 0; i < v1.length; i++) {
            dotProduct += v1[i] * v2[i];
            norm1 += v1[i] * v1[i];
            norm2 += v2[i] * v2[i];
        }

        if (norm1 == 0.0 || norm2 == 0.0) {
            return 0.0;
        }

        return dotProduct / (Math.sqrt(norm1) * Math.sqrt(norm2));
    }

    /**
     * Estimate storage size in bytes
     */
    private long estimateStorageSize() {
        return store.values().stream()
            .mapToLong(entry -> entry.document.text().getBytes().length +
                               (entry.embedding.vector().length * 4L))
            .sum();
    }

    private String resolveDocumentId(Document document) {
        String explicitId = document.metadata() != null
            ? document.metadata().getString("doc_id")
            : null;
        if (explicitId != null && !explicitId.isBlank()) {
            return explicitId;
        }
        return UUID.nameUUIDFromBytes(document.text().getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    /**
     * Helper record for similarity scoring
     */
    private record SimilarityScore(Document document, double score) {}
}
