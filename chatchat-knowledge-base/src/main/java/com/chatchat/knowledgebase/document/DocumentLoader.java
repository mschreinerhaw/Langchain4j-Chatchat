package com.chatchat.knowledgebase.document;

import dev.langchain4j.data.document.Document;
import java.io.InputStream;
import java.util.List;

/**
 * Interface for document loading from various sources
 */
public interface DocumentLoader {

    /**
     * Get the supported file types
     */
    List<String> getSupportedTypes();

    /**
     * Load documents from input stream
     */
    List<Document> load(InputStream input, String fileName);

    /**
     * Load documents from file path
     */
    List<Document> loadFromFile(String filePath);
}
