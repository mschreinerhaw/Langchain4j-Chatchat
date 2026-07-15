package com.chatchat.mcpserver.cache;

import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.mcpserver.database.DatabaseQueryConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rocksdb.RocksDBException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class DatabaseQueryCacheService {

    private static final String KEY_PREFIX = "db-query-cache:";

    private final McpCacheProperties properties;
    private final McpRocksDbStore rocksDbStore;
    private final RedisCacheStore redisCacheStore;
    private final ObjectMapper objectMapper;
    private final DatabaseQueryCacheConfigService configService;

    public Optional<ToolOutput> get(DatabaseQueryConfig config, Map<String, Object> parameters) {
        if (!isUsable(config)) {
            return Optional.empty();
        }
        String key = key(config, parameters);
        try {
            byte[] raw = useRedis(config) ? redisCacheStore.get(key) : rocksDbStore.get(key);
            if (raw == null) {
                return Optional.empty();
            }
            DatabaseQueryCacheEntry entry = objectMapper.readValue(raw, DatabaseQueryCacheEntry.class);
            long now = System.currentTimeMillis();
            if (entry.isExpired(now)) {
                delete(config, key);
                return Optional.empty();
            }
            ToolOutput output = entry.result();
            if (output.getMetadata() == null) {
                output.setMetadata(new LinkedHashMap<>());
            }
            output.getMetadata().put("cacheHit", true);
            output.getMetadata().put("cacheStorage", config.getCacheStorage());
            output.getMetadata().put("cacheAgeSeconds", Math.max(0L, (now - entry.createdAt()) / 1000L));
            return Optional.of(output);
        } catch (Exception ex) {
            log.warn("Failed to read database query cache for {}: {}", config.getToolName(), ex.getMessage());
            return Optional.empty();
        }
    }

    public void put(DatabaseQueryConfig config, Map<String, Object> parameters, ToolOutput result) {
        if (!isUsable(config) || result == null) {
            return;
        }
        DatabaseQueryCacheConfig cacheConfig = configService.current();
        if (!shouldCache(cacheConfig, result)) {
            return;
        }
        long now = System.currentTimeMillis();
        long expiresAt = now + Math.max(1, config.getCacheTtlSeconds()) * 1000L;
        ToolOutput cached = copyForCache(result);
        cached.getMetadata().put("cacheHit", false);
        cached.getMetadata().put("cacheStorage", config.getCacheStorage());
        DatabaseQueryCacheEntry entry = new DatabaseQueryCacheEntry(cached, now, expiresAt);
        try {
            byte[] payload = objectMapper.writeValueAsBytes(entry);
            if (payload.length > Math.max(1, cacheConfig.getMaxEntryKb()) * 1024L) {
                return;
            }
            String key = key(config, parameters);
            if (useRedis(config)) {
                redisCacheStore.put(key, payload, config.getCacheTtlSeconds());
            } else {
                rocksDbStore.put(key, payload);
            }
        } catch (Exception ex) {
            log.warn("Failed to write database query cache for {}: {}", config.getToolName(), ex.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${chatchat.mcp.cache.cleanup-interval-ms:60000}")
    public void cleanupExpired() {
        cleanupExpiredEntries();
    }

    public int cleanupExpiredEntries() {
        return cleanupExpiredEntries(rocksDbStore.scan(KEY_PREFIX, Math.max(1, properties.getCleanupBatchSize())), false)
            + cleanupExpiredEntries(safeRedisScan(KEY_PREFIX, Math.max(1, properties.getCleanupBatchSize())), true);
    }

    private int cleanupExpiredEntries(List<McpRocksDbStore.KeyValue> entries, boolean redis) {
        long now = System.currentTimeMillis();
        int scanned = 0;
        int removed = 0;
        try {
            for (McpRocksDbStore.KeyValue entry : entries) {
                scanned += 1;
                try {
                    DatabaseQueryCacheEntry cacheEntry = objectMapper.readValue(entry.value(), DatabaseQueryCacheEntry.class);
                    if (cacheEntry.isExpired(now)) {
                        delete(redis, entry.key());
                        removed += 1;
                    }
                } catch (Exception ex) {
                    delete(redis, entry.key());
                    removed += 1;
                }
            }
        } catch (Exception ex) {
            log.warn("Database query cache cleanup failed: {}", ex.getMessage());
        }
        if (removed > 0) {
            log.debug("Database query cache cleanup scanned {}, removed {}", scanned, removed);
        }
        return removed;
    }

    public int evictAll() {
        return evictEntries(rocksDbStore.scan(KEY_PREFIX, 100000), false)
            + evictEntries(safeRedisScan(KEY_PREFIX, 100000), true);
    }

    private int evictEntries(List<McpRocksDbStore.KeyValue> entries, boolean redis) {
        int removed = 0;
        try {
            for (McpRocksDbStore.KeyValue entry : entries) {
                delete(redis, entry.key());
                removed += 1;
            }
        } catch (Exception ex) {
            log.warn("Database query cache eviction failed: {}", ex.getMessage());
        }
        return removed;
    }

    public int evictTemplate(DatabaseQueryConfig config) {
        if (config == null) {
            return 0;
        }
        String prefix = KEY_PREFIX + sanitize(config.getToolName()) + ":";
        return evictEntries(rocksDbStore.scan(prefix, 100000), false)
            + evictEntries(safeRedisScan(prefix, 100000), true);
    }

    public CacheStats stats() {
        if (!rocksDbStore.isUsable() && !redisCacheStore.isUsable()) {
            return new CacheStats(false, 0, 0, 0, 0);
        }
        long now = System.currentTimeMillis();
        int entries = 0;
        int expiredEntries = 0;
        long bytes = 0L;
        List<McpRocksDbStore.KeyValue> allEntries = new ArrayList<>();
        allEntries.addAll(rocksDbStore.scan(KEY_PREFIX, 100000));
        allEntries.addAll(safeRedisScan(KEY_PREFIX, 100000));
        for (McpRocksDbStore.KeyValue entry : allEntries) {
            entries += 1;
            bytes += entry.key().length + entry.value().length;
            try {
                DatabaseQueryCacheEntry cacheEntry = objectMapper.readValue(entry.value(), DatabaseQueryCacheEntry.class);
                if (cacheEntry.isExpired(now)) {
                    expiredEntries += 1;
                }
            } catch (Exception ex) {
                expiredEntries += 1;
            }
        }
        return new CacheStats(true, entries, expiredEntries, bytes, now);
    }

    private boolean isUsable(DatabaseQueryConfig config) {
        return config != null
            && config.isEnabled()
            && config.isCacheEnabled()
            && config.getCacheTtlSeconds() > 0
            && (useRedis(config) ? redisCacheStore.isUsable() : rocksDbStore.isUsable());
    }

    private boolean useRedis(DatabaseQueryConfig config) {
        return config != null && "REDIS".equalsIgnoreCase(config.getCacheStorage());
    }

    private void delete(DatabaseQueryConfig config, String key) throws RocksDBException {
        if (useRedis(config)) redisCacheStore.delete(key);
        else rocksDbStore.delete(key);
    }

    private void delete(boolean redis, byte[] key) throws RocksDBException {
        if (redis) redisCacheStore.delete(key);
        else rocksDbStore.delete(key);
    }

    private List<McpRocksDbStore.KeyValue> safeRedisScan(String prefix, int limit) {
        try {
            return redisCacheStore.scan(prefix, limit);
        } catch (Exception ex) {
            log.warn("Redis database query cache scan failed: {}", ex.getMessage());
            return List.of();
        }
    }

    private String key(DatabaseQueryConfig config, Map<String, Object> parameters) {
        try {
            Map<String, Object> identity = new LinkedHashMap<>();
            identity.put("id", config.getId());
            identity.put("toolName", config.getToolName());
            identity.put("datasourceId", config.getDatasourceId());
            identity.put("sqlTemplate", config.getSqlTemplate());
            identity.put("sqlSteps", config.getSqlStepsJson());
            identity.put("updatedAt", config.getUpdatedAt() == null ? null : config.getUpdatedAt().toEpochMilli());
            identity.put("parameters", normalize(parameters == null ? Map.of() : parameters));
            if ("SQL_PARAMS_DATASOURCE_USER".equals(configService.current().getKeyStrategy())) {
                identity.put("user", "admin");
            }
            String canonicalJson = ModelProtocolJson.compact(identity);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonicalJson.getBytes(StandardCharsets.UTF_8));
            return KEY_PREFIX + sanitize(config.getToolName()) + ":" + HexFormat.of().formatHex(hash);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to build database query cache key", ex);
        }
    }

    private ToolOutput copyForCache(ToolOutput source) {
        ToolOutput copy = ToolOutput.builder()
            .success(source.isSuccess())
            .data(source.getData())
            .message(source.getMessage())
            .errorMessage(source.getErrorMessage())
            .exceptionType(source.getExceptionType())
            .errorDetails(source.getErrorDetails())
            .executionTimeMs(source.getExecutionTimeMs())
            .tokenUsage(source.getTokenUsage() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source.getTokenUsage()))
            .metadata(source.getMetadata() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source.getMetadata()))
            .build();
        return copy;
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
            return "database_query";
        }
        return value.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private boolean shouldCache(DatabaseQueryCacheConfig cacheConfig, ToolOutput result) {
        if (result.isSuccess()) {
            long rowCount = rowCount(result.getData());
            if (rowCount == 0 && !cacheConfig.isCacheEmptyResults()) {
                return false;
            }
            return rowCount <= Math.max(1, cacheConfig.getMaxRows());
        }
        return cacheConfig.isCacheErrorResults();
    }

    @SuppressWarnings("unchecked")
    private long rowCount(Object data) {
        if (data instanceof Map<?, ?> map) {
            Object resultSets = map.containsKey("resultSets") ? map.get("resultSets") : map.get("results");
            if (resultSets instanceof List<?> list) {
                long total = 0L;
                for (Object item : list) {
                    total += rowCount(item);
                }
                return total;
            }
            Object rowCount = map.get("rowCount");
            if (rowCount instanceof Number number) {
                return number.longValue();
            }
            Object rows = map.get("rows");
            if (rows instanceof List<?> list) {
                return list.size();
            }
            return map.isEmpty() ? 0 : 1;
        }
        if (data instanceof List<?> list) {
            return list.size();
        }
        return data == null ? 0 : 1;
    }

    public record CacheStats(
        boolean available,
        int entries,
        int expiredEntries,
        long bytes,
        long measuredAt
    ) {
    }
}
