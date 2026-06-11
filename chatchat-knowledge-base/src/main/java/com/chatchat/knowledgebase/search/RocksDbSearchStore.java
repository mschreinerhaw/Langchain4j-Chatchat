package com.chatchat.knowledgebase.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class RocksDbSearchStore {

    private static final byte[] EMPTY_VALUE = new byte[0];
    private static final String DOC_PREFIX = "doc:";
    private static final String META_PREFIX = "meta:";
    private static final String KEYWORD_INDEX_PREFIX = "idx:kw:";
    private static final String TAG_INDEX_PREFIX = "idx:tag:";
    private static final String COMPANY_INDEX_PREFIX = "idx:company:";
    private static final String INDUSTRY_INDEX_PREFIX = "idx:industry:";
    private static final String CATEGORY_PREFIX = "category:";

    private final SearchProperties properties;
    private final ObjectMapper objectMapper;
    private Options options;
    private RocksDB db;

    /**
     * Opens the open.
     */
    @PostConstruct
    public void open() {
        try {
            RocksDB.loadLibrary();
            Files.createDirectories(Path.of(properties.getStorePath()).toAbsolutePath());
            this.options = new Options().setCreateIfMissing(properties.isCreateIfMissing());
            this.db = RocksDB.open(options, properties.getStorePath());
            log.info("RocksDB search store opened at {}", properties.getStorePath());
        } catch (IOException | RocksDBException ex) {
            throw new IllegalStateException("Failed to open RocksDB search store", ex);
        }
    }

    /**
     * Stores the put.
     *
     * @param document the document value
     * @param indexData the index data value
     * @param oldIndexData the old index data value
     */
    public void put(SearchDocument document, SearchIndexData indexData, SearchIndexData oldIndexData) {
        ensureOpen();
        try {
            if (oldIndexData != null) {
                deleteIndexEntries(document.getDocId(), oldIndexData);
            }
            db.put(bytes(DOC_PREFIX + document.getDocId()), objectMapper.writeValueAsBytes(document));
            db.put(bytes(META_PREFIX + document.getDocId()), objectMapper.writeValueAsBytes(toMetadata(document)));
            if (isLatestVersion(document)) {
                putIndexEntries(document.getDocId(), indexData);
            }
        } catch (IOException | RocksDBException ex) {
            throw new IllegalStateException("Failed to write search document", ex);
        }
    }

    /**
     * Returns the get.
     *
     * @param docId the doc id value
     * @return the get
     */
    public Optional<SearchDocument> get(String docId) {
        ensureOpen();
        try {
            byte[] value = db.get(bytes(DOC_PREFIX + docId));
            if (value == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(value, SearchDocument.class));
        } catch (IOException | RocksDBException ex) {
            throw new IllegalStateException("Failed to read search document", ex);
        }
    }

    /**
     * Deletes the delete.
     *
     * @param document the document value
     * @param oldIndexData the old index data value
     */
    public void delete(SearchDocument document, SearchIndexData oldIndexData) {
        ensureOpen();
        if (document == null || document.getDocId() == null || document.getDocId().isBlank()) {
            return;
        }
        try {
            if (oldIndexData != null) {
                deleteIndexEntries(document.getDocId(), oldIndexData);
            }
            db.delete(bytes(DOC_PREFIX + document.getDocId()));
            db.delete(bytes(META_PREFIX + document.getDocId()));
        } catch (RocksDBException ex) {
            throw new IllegalStateException("Failed to delete search document", ex);
        }
    }

    /**
     * Finds the by keyword.
     *
     * @param keyword the keyword value
     * @return the matching by keyword
     */
    public List<String> findByKeyword(String keyword) {
        return findByIndexPrefix(KEYWORD_INDEX_PREFIX + keyword + ":");
    }

    /**
     * Finds the by tag.
     *
     * @param tag the tag value
     * @return the matching by tag
     */
    public List<String> findByTag(String tag) {
        return findByIndexPrefix(TAG_INDEX_PREFIX + tag + ":");
    }

    /**
     * Finds the by company.
     *
     * @param company the company value
     * @return the matching by company
     */
    public List<String> findByCompany(String company) {
        return findByIndexPrefix(COMPANY_INDEX_PREFIX + company + ":");
    }

    /**
     * Finds the by industry.
     *
     * @param industry the industry value
     * @return the matching by industry
     */
    public List<String> findByIndustry(String industry) {
        return findByIndexPrefix(INDUSTRY_INDEX_PREFIX + industry + ":");
    }

    /**
     * Lists the document ids.
     *
     * @param limit the limit value
     * @return the document ids list
     */
    public List<String> listDocumentIds(int limit) {
        ensureOpen();
        List<String> ids = new ArrayList<>();
        String prefix = DOC_PREFIX;
        try (RocksIterator iterator = db.newIterator()) {
            for (iterator.seek(bytes(prefix)); iterator.isValid() && startsWith(iterator.key(), prefix); iterator.next()) {
                ids.add(string(iterator.key()).substring(prefix.length()));
                if (limit > 0 && ids.size() >= limit) {
                    break;
                }
            }
        }
        return ids;
    }

    /**
     * Performs the count documents operation.
     *
     * @return the operation result
     */
    public int countDocuments() {
        ensureOpen();
        int count = 0;
        String prefix = DOC_PREFIX;
        try (RocksIterator iterator = db.newIterator()) {
            for (iterator.seek(bytes(prefix)); iterator.isValid() && startsWith(iterator.key(), prefix); iterator.next()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Stores the category.
     *
     * @param name the name value
     */
    public void putCategory(String name) {
        ensureOpen();
        try {
            db.put(bytes(CATEGORY_PREFIX + name), EMPTY_VALUE);
        } catch (RocksDBException ex) {
            throw new IllegalStateException("Failed to write library category", ex);
        }
    }

    /**
     * Lists the categories.
     *
     * @return the categories list
     */
    public List<String> listCategories() {
        ensureOpen();
        List<String> categories = new ArrayList<>();
        String prefix = CATEGORY_PREFIX;
        try (RocksIterator iterator = db.newIterator()) {
            for (iterator.seek(bytes(prefix)); iterator.isValid() && startsWith(iterator.key(), prefix); iterator.next()) {
                categories.add(string(iterator.key()).substring(prefix.length()));
            }
        }
        return categories;
    }

    /**
     * Closes the close.
     */
    @PreDestroy
    public void close() {
        if (db != null) {
            db.close();
        }
        if (options != null) {
            options.close();
        }
    }

    /**
     * Stores the index entries.
     *
     * @param docId the doc id value
     * @param indexData the index data value
     * @throws RocksDBException if the operation fails
     */
    private void putIndexEntries(String docId, SearchIndexData indexData) throws RocksDBException {
        putIndexEntries(KEYWORD_INDEX_PREFIX, indexData.keywords(), docId);
        putIndexEntries(TAG_INDEX_PREFIX, indexData.tags(), docId);
        putIndexEntries(COMPANY_INDEX_PREFIX, indexData.companies(), docId);
        putIndexEntries(INDUSTRY_INDEX_PREFIX, indexData.industries(), docId);
    }

    /**
     * Deletes the index entries.
     *
     * @param docId the doc id value
     * @param indexData the index data value
     * @throws RocksDBException if the operation fails
     */
    private void deleteIndexEntries(String docId, SearchIndexData indexData) throws RocksDBException {
        deleteIndexEntries(KEYWORD_INDEX_PREFIX, indexData.keywords(), docId);
        deleteIndexEntries(TAG_INDEX_PREFIX, indexData.tags(), docId);
        deleteIndexEntries(COMPANY_INDEX_PREFIX, indexData.companies(), docId);
        deleteIndexEntries(INDUSTRY_INDEX_PREFIX, indexData.industries(), docId);
    }

    /**
     * Stores the index entries.
     *
     * @param prefix the prefix value
     * @param terms the terms value
     * @param docId the doc id value
     * @throws RocksDBException if the operation fails
     */
    private void putIndexEntries(String prefix, List<String> terms, String docId) throws RocksDBException {
        for (String term : terms) {
            db.put(bytes(prefix + term + ":" + docId), EMPTY_VALUE);
        }
    }

    /**
     * Deletes the index entries.
     *
     * @param prefix the prefix value
     * @param terms the terms value
     * @param docId the doc id value
     * @throws RocksDBException if the operation fails
     */
    private void deleteIndexEntries(String prefix, List<String> terms, String docId) throws RocksDBException {
        for (String term : terms) {
            db.delete(bytes(prefix + term + ":" + docId));
        }
    }

    /**
     * Finds the by index prefix.
     *
     * @param prefix the prefix value
     * @return the matching by index prefix
     */
    private List<String> findByIndexPrefix(String prefix) {
        ensureOpen();
        Set<String> ids = new LinkedHashSet<>();
        try (RocksIterator iterator = db.newIterator()) {
            for (iterator.seek(bytes(prefix)); iterator.isValid() && startsWith(iterator.key(), prefix); iterator.next()) {
                ids.add(string(iterator.key()).substring(prefix.length()));
            }
        }
        return new ArrayList<>(ids);
    }

    /**
     * Converts the value to metadata.
     *
     * @param document the document value
     * @return the converted metadata
     */
    private SearchDocumentMetadata toMetadata(SearchDocument document) {
        return new SearchDocumentMetadata(
            document.getDocId(),
            document.getTitle(),
            document.getSource(),
            document.getDate(),
            document.getTags(),
            document.getCompanies(),
            document.getIndustries(),
            document.getFileName(),
            document.getFilePath(),
            document.getDocumentType(),
            document.getUploadedAt(),
            document.getUpdatedAt(),
            document.getVersionGroupId(),
            document.getVersion(),
            document.getLatestVersion()
        );
    }

    /**
     * Returns whether is latest version.
     *
     * @param document the document value
     * @return whether the condition is satisfied
     */
    private boolean isLatestVersion(SearchDocument document) {
        return !Boolean.FALSE.equals(document.getLatestVersion());
    }

    /**
     * Returns whether starts with.
     *
     * @param value the value value
     * @param prefix the prefix value
     * @return whether the condition is satisfied
     */
    private boolean startsWith(byte[] value, String prefix) {
        return string(value).startsWith(prefix);
    }

    /**
     * Ensures the open.
     */
    private void ensureOpen() {
        if (db == null) {
            throw new IllegalStateException("RocksDB search store is not open");
        }
    }

    /**
     * Performs the bytes operation.
     *
     * @param value the value value
     * @return the operation result
     */
    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Performs the string operation.
     *
     * @param value the value value
     * @return the operation result
     */
    private String string(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }

    private record SearchDocumentMetadata(
        String docId,
        String title,
        String source,
        String date,
        List<String> tags,
        List<String> companies,
        List<String> industries,
        String fileName,
        String filePath,
        String documentType,
        Long uploadedAt,
        Long updatedAt,
        String versionGroupId,
        Integer version,
        Boolean latestVersion
    ) {
    }
}
