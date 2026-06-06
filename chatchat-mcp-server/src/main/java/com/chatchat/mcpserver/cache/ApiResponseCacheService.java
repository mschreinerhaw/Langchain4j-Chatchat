package com.chatchat.mcpserver.cache;

import com.chatchat.mcpserver.api.ApiInvokeResult;
import com.chatchat.mcpserver.api.ApiServiceConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class ApiResponseCacheService {

    private static final String KEY_PREFIX = "cache:";

    private final McpCacheProperties properties;
    private final ObjectMapper objectMapper;
    private RocksDB rocksDB;
    private Options options;

    static {
        RocksDB.loadLibrary();
    }

    @PostConstruct
    public void initialize() throws Exception {
        if (!properties.isEnabled()) {
            log.info("MCP RocksDB cache is disabled");
            return;
        }
        Path path = Path.of(properties.getPath()).toAbsolutePath().normalize();
        Files.createDirectories(path);
        options = new Options().setCreateIfMissing(properties.isCreateIfMissing());
        rocksDB = RocksDB.open(options, path.toString());
        log.info("MCP RocksDB cache opened at {}", path);
    }

    public Optional<ApiInvokeResult> get(ApiServiceConfig config, Map<String, Object> arguments) {
        if (!isUsable(config)) {
            return Optional.empty();
        }
        String key = key(config, arguments);
        try {
            byte[] raw = rocksDB.get(bytes(key));
            if (raw == null) {
                return Optional.empty();
            }
            ApiResponseCacheEntry entry = objectMapper.readValue(raw, ApiResponseCacheEntry.class);
            long now = System.currentTimeMillis();
            if (entry.isExpired(now)) {
                rocksDB.delete(bytes(key));
                return Optional.empty();
            }
            return Optional.of(entry.result().withCacheHit(true));
        } catch (Exception ex) {
            log.warn("Failed to read MCP cache for {}: {}", config.getToolName(), ex.getMessage());
            return Optional.empty();
        }
    }

    public void put(ApiServiceConfig config, Map<String, Object> arguments, ApiInvokeResult result) {
        if (!isUsable(config) || result == null || !result.success()) {
            return;
        }
        long now = System.currentTimeMillis();
        long expiresAt = now + Math.max(1, config.getCacheTtlSeconds()) * 1000L;
        ApiResponseCacheEntry entry = new ApiResponseCacheEntry(result.withCacheHit(false), now, expiresAt);
        try {
            rocksDB.put(bytes(key(config, arguments)), objectMapper.writeValueAsBytes(entry));
        } catch (Exception ex) {
            log.warn("Failed to write MCP cache for {}: {}", config.getToolName(), ex.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${chatchat.mcp.cache.cleanup-interval-ms:60000}")
    public void cleanupExpired() {
        if (rocksDB == null) {
            return;
        }
        long now = System.currentTimeMillis();
        int scanned = 0;
        int removed = 0;
        try (RocksIterator iterator = rocksDB.newIterator()) {
            iterator.seek(bytes(KEY_PREFIX));
            while (iterator.isValid()
                && startsWith(iterator.key(), KEY_PREFIX)
                && scanned < Math.max(1, properties.getCleanupBatchSize())) {
                scanned += 1;
                byte[] key = iterator.key().clone();
                byte[] value = iterator.value().clone();
                iterator.next();
                try {
                    ApiResponseCacheEntry entry = objectMapper.readValue(value, ApiResponseCacheEntry.class);
                    if (entry.isExpired(now)) {
                        rocksDB.delete(key);
                        removed += 1;
                    }
                } catch (Exception ex) {
                    rocksDB.delete(key);
                    removed += 1;
                }
            }
        } catch (RocksDBException ex) {
            log.warn("MCP cache cleanup failed: {}", ex.getMessage());
        }
        if (removed > 0) {
            log.debug("MCP cache cleanup scanned {}, removed {}", scanned, removed);
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

    private boolean isUsable(ApiServiceConfig config) {
        return properties.isEnabled()
            && rocksDB != null
            && config != null
            && config.isCacheEnabled()
            && config.getCacheTtlSeconds() > 0;
    }

    private String key(ApiServiceConfig config, Map<String, Object> arguments) {
        try {
            Object canonical = normalize(arguments == null ? Map.of() : arguments);
            String canonicalJson = objectMapper.writeValueAsString(canonical);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonicalJson.getBytes(StandardCharsets.UTF_8));
            return KEY_PREFIX + sanitize(config.getToolName()) + ":" + HexFormat.of().formatHex(hash);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to build MCP cache key", ex);
        }
    }

    private Object normalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>(Comparator.naturalOrder());
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                sorted.put(String.valueOf(entry.getKey()), normalize(entry.getValue()));
            }
            return sorted;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> normalized = new ArrayList<>();
            for (Object item : iterable) {
                normalized.add(normalize(item));
            }
            return normalized;
        }
        return value;
    }

    private String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "api";
        }
        return value.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private boolean startsWith(byte[] key, String prefix) {
        return new String(key, StandardCharsets.UTF_8).startsWith(prefix);
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
