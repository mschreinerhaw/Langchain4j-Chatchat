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
import java.time.Instant;
import java.util.Optional;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "chatchat.chat.detail-store", name = "type", havingValue = "rocksdb", matchIfMissing = true)
public class RocksDbChatMessageDetailStore implements ChatMessageDetailStore {

    private final ChatDetailStoreProperties properties;
    private final ObjectMapper objectMapper;
    private Options options;
    private RocksDB db;

    /**
     * Creates a new RocksDbChatMessageDetailStore instance.
     *
     * @param properties the properties value
     * @param objectMapper the object mapper value
     */
    public RocksDbChatMessageDetailStore(ChatDetailStoreProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
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
            db.put(bytes(key), objectMapper.writeValueAsBytes(detail));
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
            return Optional.of(objectMapper.readValue(value, ChatMessageDetail.class));
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
        try {
            db.delete(bytes(key));
        } catch (RocksDBException ex) {
            throw new IllegalStateException("Failed to delete chat message detail", ex);
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
}
