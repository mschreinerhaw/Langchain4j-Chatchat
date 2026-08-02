package com.chatchat.mcpserver.news;

import com.chatchat.mcpserver.cache.McpCacheProperties;
import com.chatchat.mcpserver.cache.McpRocksDbStore;
import com.chatchat.mcpserver.cache.RedisCacheStore;
import com.chatchat.mcpserver.mcp.McpInvocationContext;
import com.chatchat.runtime.market.config.MarketModuleProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Bounded, tenant-aware cache for governed financial query results. */
@Slf4j
@Service
public class FinancialQueryCacheService {
    static final String KEY_PREFIX = "financial-query-cache:v1:";

    private final MarketModuleProperties properties;
    private final McpCacheProperties cacheProperties;
    private final McpRocksDbStore rocksDb;
    private final RedisCacheStore redis;
    private final ObjectMapper mapper;
    private final Map<String, CompletableFuture<Map<String, Object>>> inFlight = new ConcurrentHashMap<>();

    public FinancialQueryCacheService(MarketModuleProperties properties,
                                      McpCacheProperties cacheProperties,
                                      McpRocksDbStore rocksDb,
                                      RedisCacheStore redis,
                                      ObjectMapper mapper) {
        this.properties = properties;
        this.cacheProperties = cacheProperties;
        this.rocksDb = rocksDb;
        this.redis = redis;
        this.mapper = mapper;
    }

    public Map<String, Object> getOrLoad(String dataset, Map<String, Object> filters,
                                         LocalDate startDate, LocalDate endDate, int limit,
                                         String historyMode, Supplier<Map<String, Object>> loader) {
        if (loader == null) throw new IllegalArgumentException("Financial query cache loader is required");
        MarketModuleProperties.QueryCache policy = policy();
        if (!policy.isEnabled() || policy.getTtlSeconds() <= 0L) return loader.get();
        if (!hasTenantContext()) {
            log.debug("Financial query cache bypassed because tenant context is unavailable");
            return loader.get();
        }
        String key = key(dataset, filters, startDate, endDate, limit, historyMode);
        Optional<Map<String, Object>> cached = get(key, policy);
        if (cached.isPresent()) return cached.get();

        CompletableFuture<Map<String, Object>> own = new CompletableFuture<>();
        CompletableFuture<Map<String, Object>> leader = inFlight.putIfAbsent(key, own);
        if (leader != null) {
            return leader.join();
        }
        try {
            Optional<Map<String, Object>> filled = get(key, policy);
            if (filled.isPresent()) {
                Map<String, Object> result = filled.get();
                own.complete(result);
                return result;
            }
            Map<String, Object> loaded = loader.get();
            String writtenStorage = put(key, loaded, policy);
            Map<String, Object> result = observed(loaded, false, writtenStorage, 0L, policy.getTtlSeconds());
            own.complete(result);
            return result;
        } catch (RuntimeException ex) {
            own.completeExceptionally(ex);
            throw ex;
        } finally {
            long graceMs = Math.max(0L, Math.min(policy.getSingleFlightGraceMs(), 5000L));
            if (graceMs == 0L) {
                inFlight.remove(key, own);
            } else {
                CompletableFuture.delayedExecutor(graceMs, TimeUnit.MILLISECONDS)
                    .execute(() -> inFlight.remove(key, own));
            }
        }
    }

    private Optional<Map<String, Object>> get(String key, MarketModuleProperties.QueryCache policy) {
        StorageRead read = read(key, policy);
        if (read.payload() == null) return Optional.empty();
        try {
            CacheEntry entry = mapper.readValue(read.payload(), CacheEntry.class);
            long now = System.currentTimeMillis();
            if (entry.expiresAt() <= now) {
                delete(read.storage(), key);
                return Optional.empty();
            }
            return Optional.of(observed(entry.result(), true, read.storage(),
                Math.max(0L, (now - entry.createdAt()) / 1000L), policy.getTtlSeconds()));
        } catch (Exception ex) {
            delete(read.storage(), key);
            log.warn("Financial query cache entry ignored key={} error={}", key, ex.getMessage());
            return Optional.empty();
        }
    }

    private StorageRead read(String key, MarketModuleProperties.QueryCache policy) {
        String preferred = effectiveStorage(policy);
        if ("REDIS".equals(preferred)) {
            try {
                byte[] value = redis.get(key);
                if (value != null) return new StorageRead("REDIS", value);
            } catch (Exception ex) {
                log.warn("Redis financial query cache unavailable; fallbackToRocksDb={} error={}",
                    policy.isFallbackToRocksDb(), ex.getMessage());
            }
            if (!policy.isFallbackToRocksDb()) return new StorageRead("REDIS", null);
        }
        try {
            return new StorageRead("ROCKSDB", rocksDb.isUsable() ? rocksDb.get(key) : null);
        } catch (Exception ex) {
            log.warn("RocksDB financial query cache read failed: {}", ex.getMessage());
            return new StorageRead("ROCKSDB", null);
        }
    }

    private String put(String key, Map<String, Object> result, MarketModuleProperties.QueryCache policy) {
        String preferred = effectiveStorage(policy);
        if (result == null) return preferred;
        long now = System.currentTimeMillis();
        try {
            byte[] payload = mapper.writeValueAsBytes(new CacheEntry(copy(result), now,
                now + Math.max(1L, policy.getTtlSeconds()) * 1000L));
            if (payload.length > Math.max(1, policy.getMaxEntryKb()) * 1024L) return preferred;
            if ("REDIS".equals(preferred)) {
                try {
                    redis.put(key, payload, policy.getTtlSeconds());
                    return "REDIS";
                } catch (Exception ex) {
                    log.warn("Redis financial query cache write failed; fallbackToRocksDb={} error={}",
                        policy.isFallbackToRocksDb(), ex.getMessage());
                    if (!policy.isFallbackToRocksDb()) return "REDIS";
                }
            }
            if (rocksDb.isUsable()) {
                rocksDb.put(key, payload);
                return "ROCKSDB";
            }
        } catch (Exception ex) {
            log.warn("Financial query cache write skipped: {}", ex.getMessage());
        }
        return preferred;
    }

    String key(String dataset, Map<String, Object> filters, LocalDate startDate,
               LocalDate endDate, int limit, String historyMode) {
        try {
            Map<String, Object> identity = new TreeMap<>();
            identity.put("tenantId", tenantId());
            identity.put("dataset", dataset == null ? "" : dataset.trim());
            identity.put("filters", normalize(filters == null ? Map.of() : filters));
            identity.put("startDate", startDate == null ? "" : startDate.toString());
            identity.put("endDate", endDate == null ? "" : endDate.toString());
            identity.put("limit", limit);
            identity.put("historyMode", historyMode == null ? "" : historyMode.trim().toLowerCase());
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                mapper.writeValueAsString(identity).getBytes(StandardCharsets.UTF_8));
            return KEY_PREFIX + HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to build financial query cache key", ex);
        }
    }

    @Scheduled(fixedDelayString = "${chatchat.mcp.cache.cleanup-interval-ms:60000}")
    public int cleanupExpired() {
        if (!rocksDb.isUsable()) return 0;
        int removed = 0;
        long now = System.currentTimeMillis();
        for (McpRocksDbStore.KeyValue item : rocksDb.scan(
            KEY_PREFIX, Math.max(1, cacheProperties.getCleanupBatchSize()))) {
            try {
                CacheEntry entry = mapper.readValue(item.value(), CacheEntry.class);
                if (entry.expiresAt() > now) continue;
            } catch (Exception ignored) { }
            try {
                rocksDb.delete(item.key());
                removed++;
            } catch (Exception ex) {
                log.warn("Financial query cache cleanup failed: {}", ex.getMessage());
            }
        }
        return removed;
    }

    private void delete(String storage, String key) {
        try {
            if ("REDIS".equals(storage)) redis.delete(key);
            else if (rocksDb.isUsable()) rocksDb.delete(key);
        } catch (Exception ignored) { }
    }

    private String effectiveStorage(MarketModuleProperties.QueryCache policy) {
        return "REDIS".equalsIgnoreCase(policy.getStorage()) ? "REDIS" : "ROCKSDB";
    }

    private MarketModuleProperties.QueryCache policy() {
        return properties.getQueryCache() == null
            ? new MarketModuleProperties.QueryCache() : properties.getQueryCache();
    }

    private String tenantId() {
        McpInvocationContext.Context context = McpInvocationContext.current();
        return context.tenantId().trim();
    }

    private boolean hasTenantContext() {
        McpInvocationContext.Context context = McpInvocationContext.current();
        return context != null && context.tenantId() != null && !context.tenantId().isBlank();
    }

    private Object normalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), normalize(item)));
            return sorted;
        }
        if (value instanceof Iterable<?> iterable) {
            java.util.ArrayList<Object> items = new java.util.ArrayList<>();
            iterable.forEach(item -> items.add(normalize(item)));
            return Collections.unmodifiableList(items);
        }
        return value;
    }

    private Map<String, Object> observed(Map<String, Object> source, boolean hit, String storage,
                                         long ageSeconds, long ttlSeconds) {
        Map<String, Object> result = copy(source);
        result.put("queryCacheHit", hit);
        result.put("queryCacheStorage", storage);
        result.put("queryCacheAgeSeconds", ageSeconds);
        result.put("queryCacheTtlSeconds", ttlSeconds);
        return java.util.Collections.unmodifiableMap(result);
    }

    private Map<String, Object> copy(Map<String, Object> source) {
        return source == null ? Map.of() : mapper.convertValue(source, new TypeReference<>() { });
    }

    private record CacheEntry(Map<String, Object> result, long createdAt, long expiresAt) { }
    private record StorageRead(String storage, byte[] payload) { }
}
