package com.chatchat.chat.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "chatchat.chat.detail-store", name = "type", havingValue = "rocksdb", matchIfMissing = true)
public class RocksDbChatMessageDetailStore implements ChatMessageDetailStore {

    private final ChatDetailStoreProperties properties;
    private final ObjectMapper objectMapper;
    private final ChatMessageTextStore textStore;
    private Options options;
    private RocksDB db;

    /**
     * Creates a new RocksDbChatMessageDetailStore instance.
     *
     * @param properties the properties value
     * @param objectMapper the object mapper value
     */
    public RocksDbChatMessageDetailStore(ChatDetailStoreProperties properties,
                                         ObjectMapper objectMapper,
                                         ChatMessageTextStore textStore) {
        this.properties = properties;
        this.textStore = textStore;
        this.objectMapper = objectMapper.copy();
        this.objectMapper.getFactory().setStreamReadConstraints(
            this.objectMapper.getFactory().streamReadConstraints().rebuild()
                .maxStringLength(properties.getMaxStringLength())
                .build()
        );
        if (externalTextEnabled() && !textStore.isEnabled()) {
            throw new IllegalStateException("OpenSearch chat detail text store is enabled but unavailable");
        }
    }

    /**
     * Opens the open.
     */
    @PostConstruct
    public void open() {
        try {
            RocksDB.loadLibrary();
            Files.createDirectories(Path.of(properties.getPath()).toAbsolutePath());
            this.options = new Options().setCreateIfMissing(properties.isCreateIfMissing());
            this.db = RocksDB.open(options, properties.getPath());
            log.info("RocksDB chat detail store opened at {}", properties.getPath());
        } catch (IOException | RocksDBException ex) {
            throw new IllegalStateException("Failed to open RocksDB chat detail store", ex);
        }
    }

    /**
     * Stores the put.
     *
     * @param detail the detail value
     * @return the operation result
     */
    @Override
    public String put(ChatMessageDetail detail) {
        ensureOpen();
        Instant createdAt = detail.getCreatedAt() == null ? Instant.now() : detail.getCreatedAt();
        detail.setCreatedAt(createdAt);
        String key = ChatMessageKeyBuilder.build(
            detail.getTenantId(),
            detail.getSessionId(),
            createdAt,
            detail.getMessageId()
        );
        try {
            if (!externalTextEnabled()) {
                db.put(bytes(key), objectMapper.writeValueAsBytes(detail));
                return key;
            }
            String documentId = documentId(key);
            textStore.put(documentId, detail);
            try {
                db.put(bytes(key), objectMapper.writeValueAsBytes(new DetailReference(2, documentId)));
            } catch (IOException | RocksDBException ex) {
                deleteExternalQuietly(documentId);
                throw ex;
            }
            return key;
        } catch (IOException | RocksDBException ex) {
            throw new IllegalStateException("Failed to write chat message detail", ex);
        }
    }

    /**
     * Returns the get.
     *
     * @param key the key value
     * @return the get
     */
    @Override
    public Optional<ChatMessageDetail> get(String key) {
        ensureOpen();
        try {
            byte[] value = db.get(bytes(key));
            if (value == null) {
                return Optional.empty();
            }
            String documentId = referenceDocumentId(value);
            if (documentId != null) {
                return textStore.get(documentId);
            }
            ChatMessageDetail detail = objectMapper.readValue(value, ChatMessageDetail.class);
            migrateLegacyDetail(key, detail);
            return Optional.of(detail);
        } catch (IOException | RocksDBException ex) {
            throw new IllegalStateException("Failed to read chat message detail", ex);
        }
    }

    /**
     * Deletes the delete.
     *
     * @param key the key value
     */
    @Override
    public void delete(String key) {
        ensureOpen();
        String documentId = null;
        try {
            byte[] value = db.get(bytes(key));
            documentId = value == null ? null : referenceDocumentId(value);
            db.delete(bytes(key));
        } catch (IOException | RocksDBException ex) {
            throw new IllegalStateException("Failed to delete chat message detail", ex);
        }
        if (documentId != null) {
            deleteExternalQuietly(documentId);
        }
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
     * Ensures the open.
     */
    private void ensureOpen() {
        if (db == null) {
            throw new IllegalStateException("RocksDB chat detail store is not open");
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

    private boolean externalTextEnabled() {
        return properties.getExternalText() != null && properties.getExternalText().isEnabled();
    }

    private String referenceDocumentId(byte[] value) throws IOException {
        var root = objectMapper.readTree(value);
        if (root == null || root.path("formatVersion").asInt() != 2) {
            return null;
        }
        String documentId = root.path("textDocumentId").asText("");
        return documentId.isBlank() ? null : documentId;
    }

    private void migrateLegacyDetail(String key, ChatMessageDetail detail) {
        if (!externalTextEnabled()
            || properties.getExternalText() == null
            || !properties.getExternalText().isMigrateLegacyOnRead()) {
            return;
        }
        String documentId = documentId(key);
        try {
            textStore.put(documentId, detail);
            db.put(bytes(key), objectMapper.writeValueAsBytes(new DetailReference(2, documentId)));
            log.info("Migrated legacy RocksDB chat detail to OpenSearch key={} documentId={}", key, documentId);
        } catch (IOException | RocksDBException | RuntimeException ex) {
            deleteExternalQuietly(documentId);
            log.warn("Failed to migrate legacy RocksDB chat detail key={} documentId={} error={}",
                key, documentId, ex.getMessage());
        }
    }

    private String documentId(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes(key));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private void deleteExternalQuietly(String documentId) {
        try {
            textStore.delete(documentId);
        } catch (RuntimeException ex) {
            log.warn("Failed to delete OpenSearch chat detail documentId={} error={}", documentId, ex.getMessage());
        }
    }

    private record DetailReference(int formatVersion, String textDocumentId) {
    }
}
