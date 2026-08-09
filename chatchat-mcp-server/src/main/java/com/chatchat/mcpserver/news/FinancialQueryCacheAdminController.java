package com.chatchat.mcpserver.news;

import com.chatchat.common.response.ApiResponse;
import com.chatchat.mcpserver.cache.RedisCacheConfigService;
import com.chatchat.mcpserver.cache.RedisCacheStore;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/cache/financial-query")
@RequiredArgsConstructor
public class FinancialQueryCacheAdminController {
    private final FinancialQueryCacheConfigService configService;
    private final FinancialQueryCacheService cacheService;
    private final RedisCacheConfigService redisConfigService;
    private final RedisCacheStore redisStore;

    @GetMapping("/config")
    public ApiResponse<ConfigView> config() {
        return ApiResponse.success(view(configService.current()));
    }

    @PutMapping("/config")
    public ApiResponse<ConfigView> save(@RequestBody ConfigRequest request) {
        String storage = "REDIS".equalsIgnoreCase(request.storage()) ? "REDIS" : "ROCKSDB";
        if (Boolean.TRUE.equals(request.enabled()) && "REDIS".equals(storage)) {
            if (!redisConfigService.current().isEnabled()) {
                throw new IllegalArgumentException("Redis cache storage must be enabled first");
            }
            if (!redisStore.isConnected()) {
                throw new IllegalArgumentException("Redis cache storage connection is not available");
            }
        }
        FinancialQueryCacheConfig config = new FinancialQueryCacheConfig();
        config.setEnabled(Boolean.TRUE.equals(request.enabled()));
        config.setStorage(storage);
        config.setTtlSeconds(request.ttlSeconds() == null ? 1800L : request.ttlSeconds());
        config.setFallbackToRocksDb(!Boolean.FALSE.equals(request.fallbackToRocksDb()));
        config.setMaxEntryKb(request.maxEntryKb() == null ? 2048 : request.maxEntryKb());
        config.setSingleFlightGraceMs(request.singleFlightGraceMs() == null ? 500L : request.singleFlightGraceMs());
        return ApiResponse.success(view(configService.save(config)), "Financial query cache policy saved");
    }

    @GetMapping("/entries")
    public ApiResponse<FinancialQueryCacheService.CacheOverview> entries(
        @RequestParam(value = "limit", defaultValue = "200") int limit) {
        return ApiResponse.success(cacheService.overview(limit));
    }

    @PostMapping("/cleanup-expired")
    public ApiResponse<Map<String, Object>> cleanupExpired() {
        int removed = cacheService.cleanupExpired();
        return ApiResponse.success(Map.of("removed", removed), "Expired financial query cache entries cleaned");
    }

    @PostMapping("/evict")
    public ApiResponse<Map<String, Object>> evict() {
        int removed = cacheService.evictAll();
        return ApiResponse.success(Map.of("removed", removed), "Financial query cache evicted");
    }

    private ConfigView view(FinancialQueryCacheConfig config) {
        return new ConfigView(config.isEnabled(), config.getStorage(), config.getTtlSeconds(),
            config.isFallbackToRocksDb(), config.getMaxEntryKb(), config.getSingleFlightGraceMs(),
            config.getUpdatedAt() == null ? null : config.getUpdatedAt().toEpochMilli());
    }

    public record ConfigRequest(Boolean enabled, String storage, Long ttlSeconds,
                                Boolean fallbackToRocksDb, Integer maxEntryKb,
                                Long singleFlightGraceMs) { }
    public record ConfigView(boolean enabled, String storage, long ttlSeconds,
                             boolean fallbackToRocksDb, int maxEntryKb,
                             long singleFlightGraceMs, Long updatedAt) { }
}
