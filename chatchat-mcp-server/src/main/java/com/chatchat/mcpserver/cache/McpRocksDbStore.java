package com.chatchat.mcpserver.cache;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@Slf4j
@Component
@RequiredArgsConstructor
public class McpRocksDbStore {

    private final McpCacheProperties properties;
    private RocksDB rocksDB;
    private Options options;

    static {
        RocksDB.loadLibrary();
    }

    @PostConstruct
    public void initialize() throws Exception {
        if (!properties.isEnabled()) {
            log.info("MCP RocksDB store is disabled");
            return;
        }
        Path path = Path.of(properties.getPath()).toAbsolutePath().normalize();
        Files.createDirectories(path);
        options = new Options().setCreateIfMissing(properties.isCreateIfMissing());
        rocksDB = RocksDB.open(options, path.toString());
        log.info("MCP RocksDB store opened at {}", path);
    }

    public boolean isUsable() {
        return properties.isEnabled() && rocksDB != null;
    }

    public byte[] get(String key) throws RocksDBException {
        ensureUsable();
        return rocksDB.get(bytes(key));
    }

    public void put(String key, byte[] value) throws RocksDBException {
        ensureUsable();
        rocksDB.put(bytes(key), value);
    }

    public void delete(String key) throws RocksDBException {
        ensureUsable();
        rocksDB.delete(bytes(key));
    }

    public void delete(byte[] key) throws RocksDBException {
        ensureUsable();
        rocksDB.delete(key);
    }

    public List<KeyValue> scan(String prefix, int limit) {
        if (!isUsable()) {
            return List.of();
        }
        List<KeyValue> entries = new ArrayList<>();
        scan(prefix, limit, entries::add);
        return entries;
    }

    public void scan(String prefix, int limit, Consumer<KeyValue> consumer) {
        if (!isUsable() || limit <= 0 || consumer == null) {
            return;
        }
        try (RocksIterator iterator = rocksDB.newIterator()) {
            iterator.seek(bytes(prefix));
            int count = 0;
            while (iterator.isValid() && startsWith(iterator.key(), prefix) && count < limit) {
                consumer.accept(new KeyValue(iterator.key().clone(), iterator.value().clone()));
                count++;
                iterator.next();
            }
        }
    }

    @PreDestroy
    public void close() {
        if (rocksDB != null) {
            rocksDB.close();
        }
        if (options != null) {
            options.close();
        }
    }

    private void ensureUsable() {
        if (!isUsable()) {
            throw new IllegalStateException("MCP RocksDB store is not available");
        }
    }

    private boolean startsWith(byte[] key, String prefix) {
        return new String(key, StandardCharsets.UTF_8).startsWith(prefix);
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    public record KeyValue(byte[] key, byte[] value) {
    }
}
