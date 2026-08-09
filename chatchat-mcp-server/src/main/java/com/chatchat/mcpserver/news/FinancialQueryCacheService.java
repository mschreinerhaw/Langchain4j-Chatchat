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
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.concurrent.atomic.AtomicLong;
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
    private final FinancialQueryCacheConfigService configService;
    private final Map<String, CompletableFuture<Map<String, Object>>> inFlight = new ConcurrentHashMap<>();
    private final Map<String, Object> hitLocks = new ConcurrentHashMap<>();
    private final AtomicLong bypassNoTenantCount = new AtomicLong();
    private final AtomicLong writeFailureCount = new AtomicLong();
    private final AtomicLong oversizedSkipCount = new AtomicLong();
    private volatile String lastWriteFailure = "";

    @Autowired
    public FinancialQueryCacheService(MarketModuleProperties properties,
                                      McpCacheProperties cacheProperties,
                                      McpRocksDbStore rocksDb,
                                      RedisCacheStore redis,
                                      ObjectMapper mapper,
                                      FinancialQueryCacheConfigService configService) {
        this.properties = properties;
        this.cacheProperties = cacheProperties;
        this.rocksDb = rocksDb;
        this.redis = redis;
        this.mapper = mapper;
        this.configService = configService;
    }

    FinancialQueryCacheService(MarketModuleProperties properties,
                               McpCacheProperties cacheProperties,
                               McpRocksDbStore rocksDb,
                               RedisCacheStore redis,
                               ObjectMapper mapper) {
        this(properties, cacheProperties, rocksDb, redis, mapper, null);
    }

    public Map<String, Object> getOrLoad(String dataset, Map<String, Object> filters,
                                         LocalDate startDate, LocalDate endDate, int limit,
                                         String historyMode, Supplier<Map<String, Object>> loader) {
        return getOrLoad(dataset, filters, startDate, endDate, limit, historyMode, tenantId(), loader);
    }

    public Map<String, Object> getOrLoad(String dataset, Map<String, Object> filters,
                                         LocalDate startDate, LocalDate endDate, int limit,
                                         String historyMode, String explicitTenantId,
                                         Supplier<Map<String, Object>> loader) {
        if (loader == null) throw new IllegalArgumentException("Financial query cache loader is required");
        MarketModuleProperties.QueryCache policy = policy();
        if (!policy.isEnabled() || policy.getTtlSeconds() <= 0L) return loader.get();
        String tenantScope = tenantId(explicitTenantId);
        if (tenantScope.isBlank()) {
            bypassNoTenantCount.incrementAndGet();
            log.debug("Financial query cache bypassed because tenant context is unavailable");
            return loader.get();
        }
        String key = key(dataset, filters, startDate, endDate, limit, historyMode, tenantScope);
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
            CacheDescriptor descriptor = new CacheDescriptor(tenantScope, dataset,
                copy(filters == null ? Map.of() : filters), text(startDate), text(endDate), limit,
                historyMode == null ? "" : historyMode);
            String writtenStorage = put(key, loaded, descriptor, policy);
            Map<String, Object> result = observed(loaded, false,
                writtenStorage == null ? effectiveStorage(policy) : writtenStorage,
                writtenStorage != null, 0L, policy.getTtlSeconds(), 0L, 0L);
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
            CacheEntry updated = recordHit(read.storage(), key, entry, now);
            return Optional.of(observed(updated.result(), true, read.storage(), true,
                Math.max(0L, (now - updated.createdAt()) / 1000L),
                Math.max(1L, (updated.expiresAt() - updated.createdAt()) / 1000L),
                updated.hitCount(), updated.lastHitAt()));
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

    private String put(String key, Map<String, Object> result, CacheDescriptor descriptor,
                       MarketModuleProperties.QueryCache policy) {
        String preferred = effectiveStorage(policy);
        if (result == null) return null;
        long now = System.currentTimeMillis();
        try {
            byte[] payload = mapper.writeValueAsBytes(new CacheEntry(copy(result), now,
                now + Math.max(1L, policy.getTtlSeconds()) * 1000L, descriptor, 0L, 0L));
            if (payload.length > Math.max(1, policy.getMaxEntryKb()) * 1024L) {
                oversizedSkipCount.incrementAndGet();
                lastWriteFailure = "cache entry exceeded maxEntryKb";
                return null;
            }
            if ("REDIS".equals(preferred)) {
                try {
                    redis.put(key, payload, policy.getTtlSeconds());
                    return "REDIS";
                } catch (Exception ex) {
                    log.warn("Redis financial query cache write failed; fallbackToRocksDb={} error={}",
                        policy.isFallbackToRocksDb(), ex.getMessage());
                    if (!policy.isFallbackToRocksDb()) {
                        recordWriteFailure("Redis write failed: " + ex.getMessage());
                        return null;
                    }
                }
            }
            if (rocksDb.isUsable()) {
                rocksDb.put(key, payload);
                return "ROCKSDB";
            }
            recordWriteFailure("RocksDB storage is unavailable");
        } catch (Exception ex) {
            recordWriteFailure(ex.getMessage());
            log.warn("Financial query cache write skipped: {}", ex.getMessage());
        }
        return null;
    }

    String key(String dataset, Map<String, Object> filters, LocalDate startDate,
               LocalDate endDate, int limit, String historyMode) {
        return key(dataset, filters, startDate, endDate, limit, historyMode, tenantId());
    }

    private String key(String dataset, Map<String, Object> filters, LocalDate startDate,
                       LocalDate endDate, int limit, String historyMode, String tenantScope) {
        try {
            Map<String, Object> identity = new TreeMap<>();
            identity.put("tenantId", tenantScope);
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
        int removed = 0;
        long now = System.currentTimeMillis();
        int limit = Math.max(1, cacheProperties.getCleanupBatchSize());
        if (rocksDb.isUsable()) removed += cleanupStorage("ROCKSDB", rocksDb.scan(KEY_PREFIX, limit), now);
        if (redis.isUsable()) removed += cleanupStorage("REDIS", redis.scan(KEY_PREFIX, limit), now);
        return removed;
    }

    public int evictAll() {
        int limit = Math.max(1, cacheProperties.getCleanupBatchSize());
        int removed = 0;
        if (rocksDb.isUsable()) removed += evictAllFromStorage("ROCKSDB", limit);
        if (redis.isUsable()) removed += evictAllFromStorage("REDIS", limit);
        return removed;
    }

    public CacheOverview overview(int requestedLimit) {
        MarketModuleProperties.QueryCache policy = policy();
        String selectedStorage = effectiveStorage(policy);
        boolean rocksDbAvailable = rocksDb.isUsable();
        boolean redisAvailable = redis.isConnected();
        boolean selectedStorageAvailable = "REDIS".equals(selectedStorage)
            ? redisAvailable || policy.isFallbackToRocksDb() && rocksDbAvailable
            : rocksDbAvailable;
        int displayLimit = Math.max(1, Math.min(requestedLimit, 1000));
        int aggregationLimit = 100_000;
        List<CacheItem> allItems = new java.util.ArrayList<>();
        boolean truncated = false;
        if (rocksDb.isUsable()) {
            List<McpRocksDbStore.KeyValue> values = rocksDb.scan(KEY_PREFIX, aggregationLimit);
            truncated = values.size() == aggregationLimit;
            collect(allItems, "ROCKSDB", values, aggregationLimit);
        }
        if (redisAvailable) {
            List<McpRocksDbStore.KeyValue> values = redis.scan(KEY_PREFIX, aggregationLimit);
            truncated = truncated || values.size() == aggregationLimit;
            collect(allItems, "REDIS", values, Integer.MAX_VALUE);
        }
        allItems.sort(java.util.Comparator.comparingLong(CacheItem::lastHitAt).reversed()
            .thenComparing(java.util.Comparator.comparingLong(CacheItem::createdAt).reversed()));
        long hits = allItems.stream().mapToLong(CacheItem::hitCount).sum();
        long bytes = allItems.stream().mapToLong(CacheItem::bytes).sum();
        long now = System.currentTimeMillis();
        long expired = allItems.stream().filter(item -> item.expiresAt() <= now).count();
        List<CacheItem> visibleItems = allItems.size() <= displayLimit
            ? List.copyOf(allItems) : List.copyOf(allItems.subList(0, displayLimit));
        return new CacheOverview(visibleItems, allItems.size(), expired, hits, bytes, truncated,
            selectedStorage, selectedStorageAvailable, rocksDbAvailable, redisAvailable,
            bypassNoTenantCount.get(), writeFailureCount.get(), oversizedSkipCount.get(),
            lastWriteFailure, now);
    }

    private void collect(List<CacheItem> target, String storage,
                         List<McpRocksDbStore.KeyValue> values, int limit) {
        for (McpRocksDbStore.KeyValue item : values) {
            if (target.size() >= limit) break;
            try {
                CacheEntry entry = mapper.readValue(item.value(), CacheEntry.class);
                CacheDescriptor descriptor = entry.descriptor() == null
                    ? new CacheDescriptor("", "legacy-entry", Map.of(), "", "", 0, "")
                    : entry.descriptor();
                target.add(new CacheItem(new String(item.key(), StandardCharsets.UTF_8), storage,
                    descriptor.tenantId(), descriptor.dataset(), descriptor.filters(),
                    descriptor.startDate(), descriptor.endDate(), descriptor.limit(), descriptor.historyMode(),
                    entry.createdAt(), entry.expiresAt(), entry.hitCount(), entry.lastHitAt(), item.value().length));
            } catch (Exception ex) {
                log.warn("Financial query cache entry listing failed: {}", ex.getMessage());
            }
        }
    }

    private int cleanupStorage(String storage, List<McpRocksDbStore.KeyValue> values, long now) {
        int removed = 0;
        for (McpRocksDbStore.KeyValue item : values) {
            boolean expired = true;
            try { expired = mapper.readValue(item.value(), CacheEntry.class).expiresAt() <= now; }
            catch (Exception ignored) { }
            if (!expired) continue;
            if (delete(storage, new String(item.key(), StandardCharsets.UTF_8))) removed++;
        }
        return removed;
    }

    private int evictStorage(String storage, List<McpRocksDbStore.KeyValue> values) {
        int removed = 0;
        for (McpRocksDbStore.KeyValue item : values) {
            if (delete(storage, new String(item.key(), StandardCharsets.UTF_8))) removed++;
        }
        return removed;
    }

    private int evictAllFromStorage(String storage, int batchSize) {
        int removed = 0;
        while (true) {
            List<McpRocksDbStore.KeyValue> batch = "REDIS".equals(storage)
                ? redis.scan(KEY_PREFIX, batchSize) : rocksDb.scan(KEY_PREFIX, batchSize);
            if (batch.isEmpty()) return removed;
            int batchRemoved = evictStorage(storage, batch);
            removed += batchRemoved;
            if (batchRemoved == 0 || batch.size() < batchSize) return removed;
        }
    }

    private CacheEntry recordHit(String storage, String key, CacheEntry original, long now) {
        Object lock = hitLocks.computeIfAbsent(key, ignored -> new Object());
        synchronized (lock) {
            try {
                byte[] latestPayload = "REDIS".equals(storage) ? redis.get(key) : rocksDb.get(key);
                CacheEntry latest = latestPayload == null ? original : mapper.readValue(latestPayload, CacheEntry.class);
                CacheEntry updated = new CacheEntry(latest.result(), latest.createdAt(), latest.expiresAt(),
                    latest.descriptor(), Math.max(0L, latest.hitCount()) + 1L, now);
                byte[] payload = mapper.writeValueAsBytes(updated);
                if ("REDIS".equals(storage)) {
                    redis.put(key, payload, Math.max(1L, (updated.expiresAt() - now + 999L) / 1000L));
                } else {
                    rocksDb.put(key, payload);
                }
                return updated;
            } catch (Exception ex) {
                log.warn("Financial query cache hit counter update failed: {}", ex.getMessage());
                return new CacheEntry(original.result(), original.createdAt(), original.expiresAt(),
                    original.descriptor(), Math.max(0L, original.hitCount()) + 1L, now);
            }
        }
    }

    private boolean delete(String storage, String key) {
        try {
            if ("REDIS".equals(storage)) redis.delete(key);
            else if (rocksDb.isUsable()) rocksDb.delete(key);
            else return false;
            hitLocks.remove(key);
            return true;
        } catch (Exception ex) {
            log.warn("Financial query cache delete failed storage={} error={}", storage, ex.getMessage());
            return false;
        }
    }

    private String effectiveStorage(MarketModuleProperties.QueryCache policy) {
        return "REDIS".equalsIgnoreCase(policy.getStorage()) ? "REDIS" : "ROCKSDB";
    }

    private void recordWriteFailure(String reason) {
        writeFailureCount.incrementAndGet();
        lastWriteFailure = reason == null || reason.isBlank() ? "unknown cache write failure" : reason;
    }

    private MarketModuleProperties.QueryCache policy() {
        if (configService != null) return configService.currentPolicy();
        return properties.getQueryCache() == null
            ? new MarketModuleProperties.QueryCache() : properties.getQueryCache();
    }

    private String tenantId() {
        McpInvocationContext.Context context = McpInvocationContext.current();
        return context == null || context.tenantId() == null ? "" : context.tenantId().trim();
    }

    private String tenantId(String explicitTenantId) {
        if (explicitTenantId != null && !explicitTenantId.isBlank()) return explicitTenantId.trim();
        return tenantId();
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
                                         boolean stored, long ageSeconds, long ttlSeconds,
                                         long hitCount, long lastHitAt) {
        Map<String, Object> result = copy(source);
        result.put("queryCacheHit", hit);
        result.put("queryCacheStored", stored);
        result.put("queryCacheStorage", storage);
        result.put("queryCacheAgeSeconds", ageSeconds);
        result.put("queryCacheTtlSeconds", ttlSeconds);
        result.put("queryCacheHitCount", hitCount);
        result.put("queryCacheLastHitAt", lastHitAt);
        return java.util.Collections.unmodifiableMap(result);
    }

    private Map<String, Object> copy(Map<String, Object> source) {
        return source == null ? Map.of() : mapper.convertValue(source, new TypeReference<>() { });
    }

    private String text(LocalDate date) { return date == null ? "" : date.toString(); }

    private record CacheEntry(Map<String, Object> result, long createdAt, long expiresAt,
                              CacheDescriptor descriptor, long hitCount, long lastHitAt) { }
    private record CacheDescriptor(String tenantId, String dataset, Map<String, Object> filters,
                                   String startDate, String endDate, int limit, String historyMode) { }
    private record StorageRead(String storage, byte[] payload) { }
    public record CacheItem(String key, String storage, String tenantId, String dataset,
                            Map<String, Object> filters, String startDate, String endDate, int limit,
                            String historyMode, long createdAt, long expiresAt, long hitCount,
                            long lastHitAt, long bytes) { }
    public record CacheOverview(List<CacheItem> items, int entries, long expiredEntries,
                                long hitCount, long bytes, boolean truncated, String selectedStorage,
                                boolean selectedStorageAvailable, boolean rocksDbAvailable,
                                boolean redisAvailable, long bypassNoTenantCount,
                                long writeFailureCount, long oversizedSkipCount,
                                String lastWriteFailure, long measuredAt) { }
}
