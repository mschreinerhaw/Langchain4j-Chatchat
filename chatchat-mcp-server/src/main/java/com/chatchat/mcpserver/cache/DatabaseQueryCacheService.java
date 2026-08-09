package com.chatchat.mcpserver.cache;

import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.mcpserver.database.DatabaseQueryConfig;
import com.chatchat.mcpserver.mcp.McpInvocationContext;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class DatabaseQueryCacheService {

    private static final String KEY_PREFIX = "db-query-cache:";
    private static final String KEY_SCHEMA_VERSION = "template-key.v3";

    private final McpCacheProperties properties;
    private final McpRocksDbStore rocksDbStore;
    private final RedisCacheStore redisCacheStore;
    private final ObjectMapper objectMapper;
    private final DatabaseQueryCacheConfigService configService;
    private final Map<String, CompletableFuture<Void>> inFlightLoads = new ConcurrentHashMap<>();
    private final Map<String, Object> hitLocks = new ConcurrentHashMap<>();

    public ToolOutput getOrLoad(DatabaseQueryConfig config,
                                Map<String, Object> parameters,
                                Supplier<ToolOutput> loader) {
        if (loader == null) {
            throw new IllegalArgumentException("Database query cache loader is required");
        }
        Optional<ToolOutput> cached = get(config, parameters);
        if (cached.isPresent()) {
            return cached.get();
        }
        DatabaseQueryCacheConfig cacheConfig = configService.current();
        if (!isUsable(config, cacheConfig)) {
            return loader.get();
        }
        String key = key(config, parameters);
        CompletableFuture<Void> ownLoad = new CompletableFuture<>();
        CompletableFuture<Void> activeLoad = inFlightLoads.putIfAbsent(key, ownLoad);
        if (activeLoad != null) {
            try {
                activeLoad.join();
            } catch (CompletionException ignored) {
                // The failed leader did not publish a cache entry. This caller may retry normally.
            }
            return get(config, parameters).orElseGet(loader);
        }
        try {
            // A previous leader may have completed between the initial cache read and
            // this request acquiring leadership. Recheck before invoking the loader.
            Optional<ToolOutput> filledByPreviousLoad = get(config, parameters);
            if (filledByPreviousLoad.isPresent()) {
                ownLoad.complete(null);
                return filledByPreviousLoad.get();
            }
            ToolOutput loaded = loader.get();
            put(config, parameters, loaded);
            ownLoad.complete(null);
            return loaded;
        } catch (RuntimeException ex) {
            ownLoad.completeExceptionally(ex);
            throw ex;
        } finally {
            inFlightLoads.remove(key, ownLoad);
        }
    }

    public Optional<ToolOutput> get(DatabaseQueryConfig config, Map<String, Object> parameters) {
        DatabaseQueryCacheConfig cacheConfig = configService.current();
        if (!isUsable(config, cacheConfig)) {
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
            DatabaseQueryCacheEntry updated = recordHit(config, parameters, key, entry, now);
            ToolOutput output = updated.result();
            if (output.getMetadata() == null) {
                output.setMetadata(new LinkedHashMap<>());
            }
            output.getMetadata().put("cacheHit", true);
            output.getMetadata().put("cacheStorage", config.getCacheStorage());
            output.getMetadata().put("cacheTemplateId", config.getId());
            output.getMetadata().put("cacheKeySchemaVersion", KEY_SCHEMA_VERSION);
            output.getMetadata().put("cacheAgeSeconds", Math.max(0L, (now - updated.createdAt()) / 1000L));
            output.getMetadata().put("cacheHitCount", updated.hitCount());
            output.getMetadata().put("cacheLastHitAt", updated.lastHitAt());
            return Optional.of(output);
        } catch (Exception ex) {
            log.warn("Failed to read database query cache for {}: {}", config.getToolName(), ex.getMessage());
            return Optional.empty();
        }
    }

    public void put(DatabaseQueryConfig config, Map<String, Object> parameters, ToolOutput result) {
        DatabaseQueryCacheConfig cacheConfig = configService.current();
        if (!isUsable(config, cacheConfig) || result == null) {
            return;
        }
        if (!shouldCache(cacheConfig, result)) {
            return;
        }
        long now = System.currentTimeMillis();
        long expiresAt = now + Math.max(1, config.getCacheTtlSeconds()) * 1000L;
        ToolOutput cached = copyForCache(result);
        cached.getMetadata().put("cacheHit", false);
        cached.getMetadata().put("cacheStorage", config.getCacheStorage());
        cached.getMetadata().put("cacheTemplateId", config.getId());
        cached.getMetadata().put("cacheKeySchemaVersion", KEY_SCHEMA_VERSION);
        cached.getMetadata().put("cacheHitCount", 0L);
        cached.getMetadata().put("cacheLastHitAt", 0L);
        DatabaseQueryCacheEntry.Descriptor descriptor = descriptor(config, parameters);
        DatabaseQueryCacheEntry entry = new DatabaseQueryCacheEntry(cached, now, expiresAt,
            descriptor, 0L, 0L);
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
        String prefix = templatePrefix(config);
        String legacyPrefix = KEY_PREFIX + sanitize(config.getToolName()) + ":";
        return evictEntries(rocksDbStore.scan(prefix, 100000), false)
            + evictEntries(safeRedisScan(prefix, 100000), true)
            + evictEntries(rocksDbStore.scan(legacyPrefix, 100000), false)
            + evictEntries(safeRedisScan(legacyPrefix, 100000), true);
    }

    public CacheStats stats() {
        CacheOverview overview = overview(1);
        return new CacheStats(overview.available(), overview.entries(), overview.expiredEntries(),
            overview.hitCount(), overview.bytes(), overview.measuredAt());
    }

    public CacheOverview overview(int requestedLimit) {
        if (!rocksDbStore.isUsable() && !redisCacheStore.isUsable()) {
            return new CacheOverview(false, List.of(), 0, 0, 0, 0, false, System.currentTimeMillis());
        }
        long now = System.currentTimeMillis();
        int displayLimit = Math.max(1, Math.min(requestedLimit, 1000));
        int expiredEntries = 0;
        long hitCount = 0L;
        long bytes = 0L;
        int aggregationLimit = 100000;
        List<McpRocksDbStore.KeyValue> allEntries = new ArrayList<>();
        List<McpRocksDbStore.KeyValue> rocksEntries = rocksDbStore.scan(KEY_PREFIX, aggregationLimit);
        List<McpRocksDbStore.KeyValue> redisEntries = safeRedisScan(KEY_PREFIX, aggregationLimit);
        boolean truncated = rocksEntries.size() == aggregationLimit || redisEntries.size() == aggregationLimit;
        allEntries.addAll(rocksEntries);
        allEntries.addAll(redisEntries);
        List<CacheItem> items = new ArrayList<>();
        for (McpRocksDbStore.KeyValue entry : rocksEntries) {
            CacheItem item = cacheItem("ROCKSDB", entry, now);
            if (item != null) items.add(item);
        }
        for (McpRocksDbStore.KeyValue entry : redisEntries) {
            CacheItem item = cacheItem("REDIS", entry, now);
            if (item != null) items.add(item);
        }
        for (McpRocksDbStore.KeyValue entry : allEntries) {
            bytes += entry.key().length + entry.value().length;
            try {
                DatabaseQueryCacheEntry cacheEntry = objectMapper.readValue(entry.value(), DatabaseQueryCacheEntry.class);
                if (cacheEntry.isExpired(now)) {
                    expiredEntries += 1;
                }
                hitCount += Math.max(0L, cacheEntry.hitCount());
            } catch (Exception ex) {
                expiredEntries += 1;
            }
        }
        items.sort(Comparator.comparingLong(CacheItem::lastHitAt).reversed()
            .thenComparing(Comparator.comparingLong(CacheItem::createdAt).reversed()));
        List<CacheItem> visible = items.size() <= displayLimit
            ? List.copyOf(items) : List.copyOf(items.subList(0, displayLimit));
        return new CacheOverview(true, visible, allEntries.size(), expiredEntries,
            hitCount, bytes, truncated, now);
    }

    private CacheItem cacheItem(String storage, McpRocksDbStore.KeyValue value, long now) {
        try {
            DatabaseQueryCacheEntry entry = objectMapper.readValue(value.value(), DatabaseQueryCacheEntry.class);
            DatabaseQueryCacheEntry.Descriptor descriptor = entry.descriptor() == null
                ? new DatabaseQueryCacheEntry.Descriptor("", "", "legacy-entry", "legacy-entry",
                    "Legacy cache entry", "", Map.of())
                : entry.descriptor();
            return new CacheItem(new String(value.key(), StandardCharsets.UTF_8), storage,
                descriptor.tenantId(), descriptor.userId(), descriptor.templateId(), descriptor.toolName(),
                descriptor.title(), descriptor.datasourceId(), descriptor.parameters(), entry.createdAt(),
                entry.expiresAt(), entry.isExpired(now), entry.hitCount(), entry.lastHitAt(),
                value.key().length + value.value().length);
        } catch (Exception ex) {
            log.warn("Failed to list database query cache entry: {}", ex.getMessage());
            return null;
        }
    }

    private DatabaseQueryCacheEntry recordHit(DatabaseQueryConfig config, Map<String, Object> parameters, String key,
                                               DatabaseQueryCacheEntry original, long now) {
        Object lock = hitLocks.computeIfAbsent(key, ignored -> new Object());
        synchronized (lock) {
            try {
                byte[] latestPayload = useRedis(config) ? redisCacheStore.get(key) : rocksDbStore.get(key);
                DatabaseQueryCacheEntry latest = latestPayload == null ? original
                    : objectMapper.readValue(latestPayload, DatabaseQueryCacheEntry.class);
                ToolOutput result = copyForCache(latest.result());
                long nextHitCount = Math.max(0L, latest.hitCount()) + 1L;
                result.getMetadata().put("cacheHitCount", nextHitCount);
                result.getMetadata().put("cacheLastHitAt", now);
                DatabaseQueryCacheEntry.Descriptor descriptor = latest.descriptor() == null
                    ? descriptor(config, parameters) : latest.descriptor();
                DatabaseQueryCacheEntry updated = new DatabaseQueryCacheEntry(result, latest.createdAt(),
                    latest.expiresAt(), descriptor, nextHitCount, now);
                byte[] payload = objectMapper.writeValueAsBytes(updated);
                if (useRedis(config)) {
                    long remainingTtl = Math.max(1L, (updated.expiresAt() - now + 999L) / 1000L);
                    redisCacheStore.put(key, payload, remainingTtl);
                } else {
                    rocksDbStore.put(key, payload);
                }
                return updated;
            } catch (Exception ex) {
                log.warn("Failed to update database query cache hit counter for {}: {}",
                    config.getToolName(), ex.getMessage());
                return new DatabaseQueryCacheEntry(copyForCache(original.result()), original.createdAt(),
                    original.expiresAt(), original.descriptor(), Math.max(0L, original.hitCount()) + 1L, now);
            }
        }
    }

    private boolean isUsable(DatabaseQueryConfig config, DatabaseQueryCacheConfig cacheConfig) {
        return cacheConfig != null
            && cacheConfig.isEnabled()
            && config != null
            && config.isEnabled()
            && config.isCacheEnabled()
            && config.getId() != null
            && !config.getId().isBlank()
            && config.getCacheTtlSeconds() > 0
            && (useRedis(config) ? redisCacheStore.isUsable() : rocksDbStore.isUsable());
    }

    private boolean useRedis(DatabaseQueryConfig config) {
        return config != null && "REDIS".equalsIgnoreCase(config.getCacheStorage());
    }

    private void delete(DatabaseQueryConfig config, String key) throws RocksDBException {
        if (useRedis(config)) redisCacheStore.delete(key);
        else rocksDbStore.delete(key);
        hitLocks.remove(key);
    }

    private void delete(boolean redis, byte[] key) throws RocksDBException {
        if (redis) redisCacheStore.delete(key);
        else rocksDbStore.delete(key);
        hitLocks.remove(new String(key, StandardCharsets.UTF_8));
    }

    private List<McpRocksDbStore.KeyValue> safeRedisScan(String prefix, int limit) {
        try {
            return redisCacheStore.scan(prefix, limit);
        } catch (Exception ex) {
            log.warn("Redis database query cache scan failed: {}", ex.getMessage());
            return List.of();
        }
    }

    String key(DatabaseQueryConfig config, Map<String, Object> parameters) {
        try {
            Map<String, Object> identity = new LinkedHashMap<>();
            identity.put("schemaVersion", KEY_SCHEMA_VERSION);
            identity.put("templateId", config.getId());
            identity.put("templateRevision", config.getUpdatedAt() == null ? null : config.getUpdatedAt().toEpochMilli());
            identity.put("datasourceId", config.getDatasourceId());
            identity.put("tenantId", invocationTenant());
            identity.put("parameters", normalize(parameters == null ? Map.of() : parameters));
            if ("TEMPLATE_ID_PARAMS_DATASOURCE_USER".equals(configService.current().getKeyStrategy())) {
                identity.put("user", invocationUser());
            }
            String canonicalJson = ModelProtocolJson.compact(identity);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonicalJson.getBytes(StandardCharsets.UTF_8));
            return templatePrefix(config) + HexFormat.of().formatHex(hash);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to build database query cache key", ex);
        }
    }

    String templatePrefix(DatabaseQueryConfig config) {
        return KEY_PREFIX + "v3:template:" + sanitize(config == null ? null : config.getId()) + ":";
    }

    private String invocationTenant() {
        McpInvocationContext.Context context = McpInvocationContext.current();
        if (context == null || context.tenantId() == null || context.tenantId().isBlank()) {
            return "tenant-unspecified";
        }
        return context.tenantId().trim();
    }

    private String invocationUser() {
        McpInvocationContext.Context context = McpInvocationContext.current();
        if (context == null) {
            return "anonymous";
        }
        if (context.userId() != null && !context.userId().isBlank()) {
            return context.userId().trim();
        }
        if (context.username() != null && !context.username().isBlank()) {
            return context.username().trim();
        }
        return "anonymous";
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizedParameters(Map<String, Object> parameters) {
        Object normalized = normalize(parameters == null ? Map.of() : parameters);
        return normalized instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private boolean userScoped(DatabaseQueryConfig config) {
        return "TEMPLATE_ID_PARAMS_DATASOURCE_USER".equals(configService.current().getKeyStrategy());
    }

    private DatabaseQueryCacheEntry.Descriptor descriptor(DatabaseQueryConfig config,
                                                           Map<String, Object> parameters) {
        return new DatabaseQueryCacheEntry.Descriptor(invocationTenant(),
            userScoped(config) ? invocationUser() : "", config.getId(), config.getToolName(),
            config.getTitle(), config.getDatasourceId(), normalizedParameters(parameters));
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
        long hitCount,
        long bytes,
        long measuredAt
    ) {
    }

    public record CacheItem(String key, String storage, String tenantId, String userId,
                            String templateId, String toolName, String title, String datasourceId,
                            Map<String, Object> parameters, long createdAt, long expiresAt,
                            boolean expired, long hitCount, long lastHitAt, long bytes) { }

    public record CacheOverview(boolean available, List<CacheItem> items, int entries,
                                int expiredEntries, long hitCount, long bytes,
                                boolean truncated, long measuredAt) { }
}
