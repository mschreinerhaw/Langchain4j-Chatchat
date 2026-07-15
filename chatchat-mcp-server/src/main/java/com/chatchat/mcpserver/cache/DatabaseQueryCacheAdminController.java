package com.chatchat.mcpserver.cache;

import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.common.response.ApiResponse;
import com.chatchat.mcpserver.database.DatabaseQueryConfig;
import com.chatchat.mcpserver.database.DatabaseQueryConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/cache/database-query")
@RequiredArgsConstructor
public class DatabaseQueryCacheAdminController {

    private final DatabaseQueryCacheConfigService configService;
    private final DatabaseQueryConfigService queryConfigService;
    private final DatabaseQueryCacheService cacheService;
    private final McpRocksDbStore rocksDbStore;
    private final RedisCacheConfigService redisConfigService;
    private final RedisCacheStore redisCacheStore;
    private final ObjectMapper objectMapper;

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
        boolean redisAvailable = redisCacheStore.isConnected();
        boolean templateCachingEnabled = configService.current().isEnabled() && queryConfigService.listAll().stream()
            .anyMatch(config -> config.isEnabled() && config.isCacheEnabled());
        return ApiResponse.success(new DatabaseQueryCacheStatsView(
            rocksDbStore.isUsable() || redisAvailable,
            rocksDbStore.isUsable(),
            redisAvailable,
            templateCachingEnabled,
            stats.entries(),
            stats.expiredEntries(),
            stats.bytes(),
            stats.measuredAt()
        ));
    }

    @GetMapping("/templates")
    public ApiResponse<List<DatabaseQueryCacheTemplateView>> templates() {
        return ApiResponse.success(queryConfigService.listAll().stream()
            .map(this::toTemplateView)
            .toList());
    }

    @GetMapping("/storage/redis")
    public ApiResponse<RedisCacheConfigView> redisConfig() {
        return ApiResponse.success(toRedisView(redisConfigService.current()));
    }

    @PutMapping("/storage/redis")
    public ApiResponse<RedisCacheConfigView> saveRedisConfig(@RequestBody RedisCacheConfigRequest request) {
        if (!Boolean.TRUE.equals(request.enabled()) && queryConfigService.listAll().stream().anyMatch(config ->
            config.isEnabled() && config.isCacheEnabled() && "REDIS".equalsIgnoreCase(config.getCacheStorage()))) {
            throw new IllegalArgumentException("Redis is still selected by enabled database query templates; reassign them before disabling Redis");
        }
        RedisCacheConfig saved = redisConfigService.save(fromRedisRequest(request, true), true);
        redisCacheStore.reload();
        return ApiResponse.success(toRedisView(saved), "Redis cache storage config saved");
    }

    @PostMapping("/storage/redis/test")
    public ApiResponse<Map<String, Object>> testRedisConfig(@RequestBody RedisCacheConfigRequest request) {
        RedisCacheConfig candidate = fromRedisRequest(request, true);
        String pong = redisCacheStore.test(candidate);
        return ApiResponse.success(Map.of("success", true, "response", pong), "Redis connection succeeded");
    }

    @PutMapping("/templates/{id}")
    public ApiResponse<DatabaseQueryCacheTemplateView> saveTemplate(
        @org.springframework.web.bind.annotation.PathVariable("id") String id,
        @RequestBody DatabaseQueryCacheTemplateRequest request
    ) {
        DatabaseQueryConfig current = queryConfigService.getById(id);
        boolean cacheEnabled = request.cacheEnabled() != null && request.cacheEnabled();
        if (cacheEnabled && !current.isEnabled()) {
            throw new IllegalArgumentException("disabled database query template cannot enable cache");
        }
        int cacheTtlSeconds = Math.max(1, Math.min(
            request.cacheTtlSeconds() == null ? 300 : request.cacheTtlSeconds(),
            86400
        ));
        String cacheStorage = request.cacheStorage() == null || request.cacheStorage().isBlank()
            ? "ROCKSDB"
            : request.cacheStorage().trim().toUpperCase();
        if (cacheEnabled && "REDIS".equals(cacheStorage) && !redisConfigService.current().isEnabled()) {
            throw new IllegalArgumentException("Redis cache storage must be enabled before assigning templates");
        }
        if (cacheEnabled && "REDIS".equals(cacheStorage) && !redisCacheStore.isConnected()) {
            throw new IllegalArgumentException("Redis cache storage connection is not available");
        }
        if (current.isCacheEnabled() == cacheEnabled
            && current.getCacheTtlSeconds() == cacheTtlSeconds
            && current.getCacheStorage().equalsIgnoreCase(cacheStorage)) {
            return ApiResponse.success(toTemplateView(current));
        }
        cacheService.evictTemplate(current);
        DatabaseQueryConfig saved = queryConfigService.updateCachePolicy(
            id,
            cacheEnabled,
            cacheTtlSeconds,
            cacheStorage
        );
        return ApiResponse.success(toTemplateView(saved), "Database query template cache policy saved");
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

    private DatabaseQueryCacheTemplateView toTemplateView(DatabaseQueryConfig config) {
        return new DatabaseQueryCacheTemplateView(
            config.getId(),
            config.getToolName(),
            config.getTitle(),
            config.getBusinessGroup(),
            config.getBusinessGroupName(),
            config.getDatabaseType(),
            config.getDatasourceId(),
            sqlCount(config),
            config.isCacheEnabled(),
            config.getCacheTtlSeconds(),
            config.getCacheStorage(),
            config.isEnabled()
        );
    }

    private RedisCacheConfig fromRedisRequest(RedisCacheConfigRequest request, boolean useStoredPassword) {
        RedisCacheConfig current = redisConfigService.current();
        RedisCacheConfig config = new RedisCacheConfig();
        config.setEnabled(request.enabled() != null && request.enabled());
        config.setMode(request.mode());
        config.setNodesJson(ModelProtocolJson.compact(request.nodes() == null ? List.of() : request.nodes()));
        config.setMasterName(request.masterName());
        config.setDatabaseIndex(request.databaseIndex() == null ? 0 : request.databaseIndex());
        config.setUsername(request.username());
        config.setPassword(useStoredPassword && (request.password() == null || request.password().isBlank())
            ? current.getPassword()
            : request.password());
        config.setSentinelUsername(request.sentinelUsername());
        config.setSentinelPassword(useStoredPassword && (request.sentinelPassword() == null || request.sentinelPassword().isBlank())
            ? current.getSentinelPassword()
            : request.sentinelPassword());
        config.setSsl(request.ssl() != null && request.ssl());
        config.setTimeoutMillis(request.timeoutMillis() == null ? 3000 : request.timeoutMillis());
        config.setMaxRedirects(request.maxRedirects() == null ? 5 : request.maxRedirects());
        return config;
    }

    private RedisCacheConfigView toRedisView(RedisCacheConfig config) {
        return new RedisCacheConfigView(
            config.isEnabled(),
            config.getMode(),
            redisConfigService.nodes(config),
            config.getMasterName(),
            config.getDatabaseIndex(),
            config.getUsername(),
            config.getPassword() != null && !config.getPassword().isBlank(),
            config.getSentinelUsername(),
            config.getSentinelPassword() != null && !config.getSentinelPassword().isBlank(),
            config.isSsl(),
            config.getTimeoutMillis(),
            config.getMaxRedirects(),
            redisCacheStore.isConnected(),
            config.getUpdatedAt() == null ? null : config.getUpdatedAt().toEpochMilli()
        );
    }

    private int sqlCount(DatabaseQueryConfig config) {
        if (config.getSqlStepsJson() == null || config.getSqlStepsJson().isBlank()) {
            return config.getSqlTemplate() == null || config.getSqlTemplate().isBlank() ? 0 : 1;
        }
        try {
            return objectMapper.readTree(config.getSqlStepsJson()).size();
        } catch (Exception ignored) {
            return 1;
        }
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
        boolean rocksDbAvailable,
        boolean redisAvailable,
        boolean cacheEnabled,
        int entries,
        int expiredEntries,
        long bytes,
        long measuredAt
    ) {
    }

    public record DatabaseQueryCacheTemplateRequest(Boolean cacheEnabled, Integer cacheTtlSeconds, String cacheStorage) {
    }

    public record DatabaseQueryCacheTemplateView(
        String id,
        String toolName,
        String title,
        String category,
        String categoryName,
        String databaseType,
        String datasourceId,
        int sqlCount,
        boolean cacheEnabled,
        int cacheTtlSeconds,
        String cacheStorage,
        boolean enabled
    ) {
    }

    public record RedisCacheConfigRequest(
        Boolean enabled,
        String mode,
        List<String> nodes,
        String masterName,
        Integer databaseIndex,
        String username,
        String password,
        String sentinelUsername,
        String sentinelPassword,
        Boolean ssl,
        Integer timeoutMillis,
        Integer maxRedirects
    ) {
    }

    public record RedisCacheConfigView(
        boolean enabled,
        String mode,
        List<String> nodes,
        String masterName,
        int databaseIndex,
        String username,
        boolean passwordConfigured,
        String sentinelUsername,
        boolean sentinelPasswordConfigured,
        boolean ssl,
        int timeoutMillis,
        int maxRedirects,
        boolean available,
        Long updatedAt
    ) {
    }
}
