package com.chatchat.api.search;

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

    public void put(SearchDocument document, SearchIndexData indexData, SearchIndexData oldIndexData) {
        ensureOpen();
        try {
            if (oldIndexData != null) {
                deleteIndexEntries(document.getDocId(), oldIndexData);
            }
            db.put(bytes(DOC_PREFIX + document.getDocId()), objectMapper.writeValueAsBytes(document));
            db.put(bytes(META_PREFIX + document.getDocId()), objectMapper.writeValueAsBytes(toMetadata(document)));
            putIndexEntries(document.getDocId(), indexData);
        } catch (IOException | RocksDBException ex) {
            throw new IllegalStateException("Failed to write search document", ex);
        }
    }

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

    public List<String> findByKeyword(String keyword) {
        return findByIndexPrefix(KEYWORD_INDEX_PREFIX + keyword + ":");
    }

    public List<String> findByTag(String tag) {
        return findByIndexPrefix(TAG_INDEX_PREFIX + tag + ":");
    }

    public List<String> findByCompany(String company) {
        return findByIndexPrefix(COMPANY_INDEX_PREFIX + company + ":");
    }

    public List<String> findByIndustry(String industry) {
        return findByIndexPrefix(INDUSTRY_INDEX_PREFIX + industry + ":");
    }

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

    public void putCategory(String name) {
        ensureOpen();
        try {
            db.put(bytes(CATEGORY_PREFIX + name), EMPTY_VALUE);
        } catch (RocksDBException ex) {
            throw new IllegalStateException("Failed to write library category", ex);
        }
    }

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

    @PreDestroy
    public void close() {
        if (db != null) {
            db.close();
        }
        if (options != null) {
            options.close();
        }
    }

    private void putIndexEntries(String docId, SearchIndexData indexData) throws RocksDBException {
        putIndexEntries(KEYWORD_INDEX_PREFIX, indexData.keywords(), docId);
        putIndexEntries(TAG_INDEX_PREFIX, indexData.tags(), docId);
        putIndexEntries(COMPANY_INDEX_PREFIX, indexData.companies(), docId);
        putIndexEntries(INDUSTRY_INDEX_PREFIX, indexData.industries(), docId);
    }

    private void deleteIndexEntries(String docId, SearchIndexData indexData) throws RocksDBException {
        deleteIndexEntries(KEYWORD_INDEX_PREFIX, indexData.keywords(), docId);
        deleteIndexEntries(TAG_INDEX_PREFIX, indexData.tags(), docId);
        deleteIndexEntries(COMPANY_INDEX_PREFIX, indexData.companies(), docId);
        deleteIndexEntries(INDUSTRY_INDEX_PREFIX, indexData.industries(), docId);
    }

    private void putIndexEntries(String prefix, List<String> terms, String docId) throws RocksDBException {
        for (String term : terms) {
            db.put(bytes(prefix + term + ":" + docId), EMPTY_VALUE);
        }
    }

    private void deleteIndexEntries(String prefix, List<String> terms, String docId) throws RocksDBException {
        for (String term : terms) {
            db.delete(bytes(prefix + term + ":" + docId));
        }
    }

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
            document.getUpdatedAt()
        );
    }

    private boolean startsWith(byte[] value, String prefix) {
        return string(value).startsWith(prefix);
    }

    private void ensureOpen() {
        if (db == null) {
            throw new IllegalStateException("RocksDB search store is not open");
        }
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

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
        Long updatedAt
    ) {
    }
}
