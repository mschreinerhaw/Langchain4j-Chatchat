package com.chatchat.knowledgebase.document;

import dev.langchain4j.data.document.Document;
import java.util.List;

/**
 * Interface for text splitting and chunking strategies
 */
public interface TextSplitter {

    /**
     * Split text into chunks
     */
    List<String> split(String text);

    /**
     * Split documents into chunks
     */
    List<Document> splitDocuments(List<Document> documents);

    /**
     * Get chunk configuration
     */
    ChunkConfig getConfig();

    /**
     * Chunk configuration
     */
    record ChunkConfig(
        int chunkSize,
        int overlapSize,
        String separator
    ) {}
}
