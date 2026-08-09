package com.chatchat.mcpserver.cache;

import com.chatchat.common.tool.ToolOutput;
import com.chatchat.mcpserver.database.DatabaseQueryConfig;
import com.chatchat.mcpserver.mcp.McpInvocationContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatabaseQueryCacheConcurrencyTest {

    @Test
    void missingTenantFailsClosedAndDoesNotCreateSharedFallbackCache() {
        CacheFixture fixture = fixture("TEMPLATE_ID_PARAMS_DATASOURCE", "ROCKSDB");
        AtomicInteger loads = new AtomicInteger();

        ToolOutput first = fixture.service().getOrLoad(fixture.template(), Map.of("market", "SH"),
            () -> result("load-" + loads.incrementAndGet()));
        ToolOutput second = fixture.service().getOrLoad(fixture.template(), Map.of("market", "SH"),
            () -> result("load-" + loads.incrementAndGet()));

        assertThat(loads).hasValue(2);
        assertThat(first.getMetadata()).containsEntry("cacheStored", false)
            .containsEntry("cacheBypassReason", "tenant_context_unavailable");
        assertThat(second.getMetadata()).containsEntry("cacheHit", false);
    }

    @Test
    void explicitMcpScopeCachesWithoutWorkerThreadContextAndSeparatesTenants() {
        CacheFixture fixture = fixture("TEMPLATE_ID_PARAMS_DATASOURCE", "ROCKSDB");
        AtomicInteger loads = new AtomicInteger();
        DatabaseQueryCacheService.CacheScope tenantA =
            new DatabaseQueryCacheService.CacheScope("tenant-a", "user-a");
        DatabaseQueryCacheService.CacheScope tenantB =
            new DatabaseQueryCacheService.CacheScope("tenant-b", "user-b");

        ToolOutput first = fixture.service().getOrLoad(fixture.template(), Map.of("market", "SH"), tenantA,
            () -> result("load-" + loads.incrementAndGet()));
        ToolOutput hit = fixture.service().getOrLoad(fixture.template(), Map.of("market", "SH"), tenantA,
            () -> result("load-" + loads.incrementAndGet()));
        ToolOutput otherTenant = fixture.service().getOrLoad(fixture.template(), Map.of("market", "SH"), tenantB,
            () -> result("load-" + loads.incrementAndGet()));

        assertThat(loads).hasValue(2);
        assertThat(first.getMetadata()).containsEntry("cacheHit", false);
        assertThat(hit.getMetadata()).containsEntry("cacheHit", true)
            .containsEntry("cacheHitCount", 1L);
        assertThat(otherTenant.getMetadata()).containsEntry("cacheHit", false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROCKSDB", "REDIS"})
    void concurrentRequestsForMaintainedTemplateLoadOnceAndHitTheSharedCache(String storage) throws Exception {
        CacheFixture fixture = fixture("TEMPLATE_ID_PARAMS_DATASOURCE", storage);
        int requestCount = 16;
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch loaderStarted = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        try {
            List<Future<ToolOutput>> futures = java.util.stream.IntStream.range(0, requestCount)
                .mapToObj(index -> executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try (McpInvocationContext.Scope ignored =
                             McpInvocationContext.open(context("tenant-cache", "user-" + index))) {
                        return fixture.service().getOrLoad(
                            fixture.template(),
                            Map.of("tradeDate", "2026-07-27", "market", "SH"),
                            () -> {
                                loads.incrementAndGet();
                                loaderStarted.countDown();
                                try {
                                    if (!releaseLoader.await(5, TimeUnit.SECONDS)) {
                                        throw new IllegalStateException("cache loader release timed out");
                                    }
                                } catch (InterruptedException ex) {
                                    Thread.currentThread().interrupt();
                                    throw new IllegalStateException("cache loader interrupted", ex);
                                }
                                return result("maintained-data");
                            }
                        );
                    }
                }))
                .toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(loaderStarted.await(5, TimeUnit.SECONDS)).isTrue();
            releaseLoader.countDown();

            List<ToolOutput> outputs = futures.stream()
                .map(future -> {
                    try {
                        return future.get(5, TimeUnit.SECONDS);
                    } catch (Exception ex) {
                        throw new IllegalStateException(ex);
                    }
                })
                .toList();

            assertThat(loads).hasValue(1);
            assertThat(outputs).hasSize(requestCount)
                .allSatisfy(output -> assertThat(output.getData()).isEqualTo(resultData("maintained-data")));
            assertThat(outputs.stream()
                .filter(output -> Boolean.TRUE.equals(output.getMetadata().get("cacheHit")))
                .count()).isEqualTo(requestCount - 1L);
            assertThat(outputs.stream()
                .filter(output -> Boolean.TRUE.equals(output.getMetadata().get("cacheHit")))
                .mapToLong(output -> ((Number) output.getMetadata().get("cacheHitCount")).longValue())
                .max()).hasValue(requestCount - 1L);
        } finally {
            releaseLoader.countDown();
            executor.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROCKSDB", "REDIS"})
    void tenantAndMaintainedParametersFormIndependentCacheNamespaces(String storage) {
        CacheFixture fixture = fixture("TEMPLATE_ID_PARAMS_DATASOURCE", storage);
        AtomicInteger loads = new AtomicInteger();

        ToolOutput tenantAFirst = load(fixture, "tenant-a", "user", Map.of("market", "SH"), loads);
        ToolOutput tenantAHit = load(fixture, "tenant-a", "another-user", Map.of("market", "SH"), loads);
        ToolOutput tenantBFirst = load(fixture, "tenant-b", "user", Map.of("market", "SH"), loads);
        ToolOutput tenantAOtherParameter = load(fixture, "tenant-a", "user", Map.of("market", "SZ"), loads);

        assertThat(loads).hasValue(3);
        assertThat(tenantAFirst.getMetadata()).containsEntry("cacheHit", false);
        assertThat(tenantAHit.getMetadata()).containsEntry("cacheHit", true);
        assertThat(tenantBFirst.getMetadata()).containsEntry("cacheHit", false);
        assertThat(tenantAOtherParameter.getMetadata()).containsEntry("cacheHit", false);
    }

    private ToolOutput load(CacheFixture fixture,
                            String tenantId,
                            String userId,
                            Map<String, Object> parameters,
                            AtomicInteger loads) {
        try (McpInvocationContext.Scope ignored = McpInvocationContext.open(context(tenantId, userId))) {
            return fixture.service().getOrLoad(fixture.template(), parameters, () -> {
                int sequence = loads.incrementAndGet();
                return result(tenantId + "-" + sequence);
            });
        }
    }

    private CacheFixture fixture(String keyStrategy, String storage) {
        DatabaseQueryCacheConfig policy = new DatabaseQueryCacheConfig();
        policy.setEnabled(true);
        policy.setKeyStrategy(keyStrategy);
        policy.setMaxRows(1000);
        policy.setMaxEntryKb(512);
        DatabaseQueryCacheConfigService configService = mock(DatabaseQueryCacheConfigService.class);
        when(configService.current()).thenReturn(policy);

        Map<String, byte[]> entries = new ConcurrentHashMap<>();
        McpRocksDbStore rocksDbStore = mock(McpRocksDbStore.class);
        when(rocksDbStore.isUsable()).thenReturn(true);
        try {
            when(rocksDbStore.get(anyString())).thenAnswer(invocation -> entries.get(invocation.getArgument(0)));
            doAnswer(invocation -> {
                entries.put(invocation.getArgument(0), invocation.getArgument(1));
                return null;
            }).when(rocksDbStore).put(anyString(), any(byte[].class));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        RedisCacheStore redisCacheStore = mock(RedisCacheStore.class);
        when(redisCacheStore.isUsable()).thenReturn(true);
        when(redisCacheStore.get(anyString())).thenAnswer(invocation -> entries.get(invocation.getArgument(0)));
        doAnswer(invocation -> {
            entries.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(redisCacheStore).put(anyString(), any(byte[].class), anyLong());
        DatabaseQueryCacheService service = new DatabaseQueryCacheService(
            new McpCacheProperties(),
            rocksDbStore,
            redisCacheStore,
            new ObjectMapper(),
            configService
        );
        DatabaseQueryConfig template = new DatabaseQueryConfig();
        template.setId("maintained-query-template");
        template.setToolName("maintained_query_tool");
        template.setTitle("Maintained query");
        template.setDatasourceId("maintained-datasource");
        template.setSqlTemplate("select value from maintained_data");
        template.setEnabled(true);
        template.setCacheEnabled(true);
        template.setCacheTtlSeconds(300);
        template.setCacheStorage(storage);
        template.prePersist();
        return new CacheFixture(service, template);
    }

    private ToolOutput result(String value) {
        ToolOutput output = ToolOutput.success(resultData(value), "query completed");
        output.getMetadata().put("cacheHit", false);
        return output;
    }

    private Map<String, Object> resultData(String value) {
        return Map.of(
            "columns", List.of("value"),
            "rows", List.of(Map.of("value", value)),
            "rowCount", 1
        );
    }

    private McpInvocationContext.Context context(String tenantId, String userId) {
        return new McpInvocationContext.Context(
            "test", "local", "test", "request", "client", userId, userId,
            tenantId, "USER", "workspace", "test", "trace", "database_query",
            "data", "read", ""
        );
    }

    private record CacheFixture(DatabaseQueryCacheService service, DatabaseQueryConfig template) {
    }
}
