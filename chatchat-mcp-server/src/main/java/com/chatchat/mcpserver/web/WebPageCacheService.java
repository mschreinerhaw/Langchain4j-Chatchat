package com.chatchat.mcpserver.web;

import com.chatchat.mcpserver.cache.McpRocksDbStore;
import com.chatchat.tools.web.WebToolCache;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class WebPageCacheService implements WebToolCache {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final McpRocksDbStore rocksDbStore;
    private final ObjectMapper objectMapper;
    private final Map<String, byte[]> memoryCache = new ConcurrentHashMap<>();

    public WebPageCacheService(McpRocksDbStore rocksDbStore, ObjectMapper objectMapper) {
        this.rocksDbStore = rocksDbStore;
        this.objectMapper = objectMapper;
    }

    /**
     * Reads a cache value.
     *
     * @param namespace the namespace value
     * @param cacheKey the cache key value
     * @return the operation result
     */
    @Override
    public CacheLookup get(String namespace, String cacheKey) {
        String key = key(namespace, cacheKey);
        try {
            byte[] bytes = rocksDbStore.isUsable() ? rocksDbStore.get(key) : memoryCache.get(key);
            if (bytes == null || bytes.length == 0) {
                return CacheLookup.miss();
            }
            Map<String, Object> envelope = objectMapper.readValue(bytes, MAP_TYPE);
            long cachedAt = longValue(envelope.get("cachedAt"));
            long ttlSeconds = longValue(envelope.get("ttlSeconds"));
            long ageSeconds = Math.max(0L, (System.currentTimeMillis() - cachedAt) / 1000L);
            if (ttlSeconds > 0 && ageSeconds > ttlSeconds) {
                return CacheLookup.expired(ageSeconds);
            }
            Object data = envelope.get("data");
            if (data instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) map;
                return CacheLookup.hit(new LinkedHashMap<>(typed), ageSeconds);
            }
        } catch (Exception ex) {
            log.debug("Web page cache read failed namespace={} key={}: {}", namespace, cacheKey, ex.getMessage());
        }
        return CacheLookup.miss();
    }

    /**
     * Stores a cache value.
     *
     * @param namespace the namespace value
     * @param cacheKey the cache key value
     * @param data the data value
     * @param ttlSeconds the ttl seconds value
     */
    @Override
    public void put(String namespace, String cacheKey, Map<String, Object> data, long ttlSeconds) {
        if (data == null || data.isEmpty()) {
            return;
        }
        String key = key(namespace, cacheKey);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("cachedAt", System.currentTimeMillis());
        envelope.put("ttlSeconds", Math.max(0L, ttlSeconds));
        envelope.put("data", data);
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(envelope);
            if (rocksDbStore.isUsable()) {
                rocksDbStore.put(key, bytes);
            } else {
                memoryCache.put(key, bytes);
            }
        } catch (Exception ex) {
            log.debug("Web page cache write failed namespace={} key={}: {}", namespace, cacheKey, ex.getMessage());
        }
    }

    /**
     * Hashes a cache identity.
     *
     * @param value the value value
     * @return the operation result
     */
    private String key(String namespace, String cacheKey) {
        return "web:" + normalize(namespace) + ":" + hash(cacheKey);
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? "default" : value.trim().toLowerCase();
    }

    private long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? 0L : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

}
