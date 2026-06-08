package com.chatchat.knowledgebase.embedding.store;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.embedding.Embedding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for InMemoryVectorStore
 */
public class InMemoryVectorStoreTest {

    private InMemoryVectorStore vectorStore;

    @BeforeEach
    void setUp() {
        vectorStore = new InMemoryVectorStore();
    }

    @Test
    void testAddDocuments() {
        List<Document> documents = List.of(
            Document.from("Test document 1"),
            Document.from("Test document 2")
        );

        List<Embedding> embeddings = List.of(
            new Embedding(new float[]{0.1f, 0.2f, 0.3f}),
            new Embedding(new float[]{0.4f, 0.5f, 0.6f})
        );

        vectorStore.add(documents, embeddings);

        VectorStore.StoreStats stats = vectorStore.getStats();
        assertEquals(2, stats.totalDocuments());
        assertEquals(2, stats.totalEmbeddings());
    }

    @Test
    void testSearch() {
        // Add test documents
        List<Document> documents = List.of(
            Document.from("Machine learning is great"),
            Document.from("Deep learning models"),
            Document.from("Natural language processing")
        );

        List<Embedding> embeddings = List.of(
            new Embedding(new float[]{0.1f, 0.2f, 0.3f}),
            new Embedding(new float[]{0.1f, 0.2f, 0.3f}),
            new Embedding(new float[]{0.7f, 0.8f, 0.9f})
        );

        vectorStore.add(documents, embeddings);

        // Search with similar embedding
        List<Document> results = vectorStore.search(
            new Embedding(new float[]{0.1f, 0.2f, 0.3f}),
            2
        );

        assertNotNull(results);
        assertTrue(results.size() <= 2);
    }

    @Test
    void testClear() {
        List<Document> documents = List.of(Document.from("Test"));
        List<Embedding> embeddings = List.of(new Embedding(new float[]{0.1f, 0.2f}));

        vectorStore.add(documents, embeddings);
        VectorStore.StoreStats statsBefore = vectorStore.getStats();
        assertEquals(1, statsBefore.totalDocuments());

        vectorStore.clear();

        VectorStore.StoreStats statsAfter = vectorStore.getStats();
        assertEquals(0, statsAfter.totalDocuments());
    }

    @Test
    void testDelete() {
        List<Document> documents = List.of(Document.from("Test"));
        List<Embedding> embeddings = List.of(new Embedding(new float[]{0.1f, 0.2f}));

        vectorStore.add(documents, embeddings);
        vectorStore.delete(documents.stream()
            .map(Document::text)
            .map(text -> UUID.nameUUIDFromBytes(text.getBytes(StandardCharsets.UTF_8)).toString())
            .toList());

        VectorStore.StoreStats stats = vectorStore.getStats();
        assertEquals(0, stats.totalDocuments());
    }
}
