package com.chatchat.mcpserver.cache;

import com.chatchat.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/cache/database-query")
@RequiredArgsConstructor
public class DatabaseQueryCacheAdminController {

    private final DatabaseQueryCacheConfigService configService;
    private final DatabaseQueryCacheService cacheService;
    private final McpRocksDbStore rocksDbStore;

    @GetMapping("/config")
    public ApiResponse<DatabaseQueryCacheConfigView> config() {
        return ApiResponse.success(toView(configService.current()));
    }

    @PutMapping("/config")
    public ApiResponse<DatabaseQueryCacheConfigView> save(@RequestBody DatabaseQueryCacheConfigRequest request) {
        DatabaseQueryCacheConfig config = new DatabaseQueryCacheConfig();
        config.setEnabled(request.enabled() != null && request.enabled());
        config.setDefaultTtlSeconds(request.defaultTtlSeconds() == null ? 300 : request.defaultTtlSeconds());
        config.setMaxRows(request.maxRows() == null ? 1000 : request.maxRows());
        config.setMaxEntryKb(request.maxEntryKb() == null ? 512 : request.maxEntryKb());
        config.setKeyStrategy(request.keyStrategy());
        config.setCacheEmptyResults(request.cacheEmptyResults() != null && request.cacheEmptyResults());
        config.setCacheErrorResults(request.cacheErrorResults() != null && request.cacheErrorResults());
        return ApiResponse.success(toView(configService.save(config)), "Database query cache config saved");
    }

    @GetMapping("/stats")
    public ApiResponse<DatabaseQueryCacheStatsView> stats() {
        DatabaseQueryCacheService.CacheStats stats = cacheService.stats();
        DatabaseQueryCacheConfig config = configService.current();
        return ApiResponse.success(new DatabaseQueryCacheStatsView(
            rocksDbStore.isUsable(),
            config.isEnabled(),
            stats.entries(),
            stats.expiredEntries(),
            stats.bytes(),
            stats.measuredAt()
        ));
    }

    @PostMapping("/evict")
    public ApiResponse<Map<String, Object>> evict() {
        int removed = cacheService.evictAll();
        return ApiResponse.success(Map.of("removed", removed), "Database query cache evicted");
    }

    @PostMapping("/cleanup-expired")
    public ApiResponse<Map<String, Object>> cleanupExpired() {
        int removed = cacheService.cleanupExpiredEntries();
        return ApiResponse.success(Map.of("removed", removed), "Expired database query cache entries cleaned");
    }

    private DatabaseQueryCacheConfigView toView(DatabaseQueryCacheConfig config) {
        return new DatabaseQueryCacheConfigView(
            config.isEnabled(),
            config.getDefaultTtlSeconds(),
            config.getMaxRows(),
            config.getMaxEntryKb(),
            config.getKeyStrategy(),
            config.isCacheEmptyResults(),
            config.isCacheErrorResults(),
            config.getUpdatedAt() == null ? null : config.getUpdatedAt().toEpochMilli()
        );
    }

    public record DatabaseQueryCacheConfigRequest(
        Boolean enabled,
        Integer defaultTtlSeconds,
        Integer maxRows,
        Integer maxEntryKb,
        String keyStrategy,
        Boolean cacheEmptyResults,
        Boolean cacheErrorResults
    ) {
    }

    public record DatabaseQueryCacheConfigView(
        boolean enabled,
        int defaultTtlSeconds,
        int maxRows,
        int maxEntryKb,
        String keyStrategy,
        boolean cacheEmptyResults,
        boolean cacheErrorResults,
        Long updatedAt
    ) {
    }

    public record DatabaseQueryCacheStatsView(
        boolean storeAvailable,
        boolean cacheEnabled,
        int entries,
        int expiredEntries,
        long bytes,
        long measuredAt
    ) {
    }
}
