package com.chatchat.mcpserver.news;

import com.chatchat.mcpserver.cache.McpCacheProperties;
import com.chatchat.mcpserver.cache.McpRocksDbStore;
import com.chatchat.mcpserver.cache.RedisCacheStore;
import com.chatchat.mcpserver.mcp.McpInvocationContext;
import com.chatchat.runtime.market.config.MarketModuleProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FinancialQueryCacheServiceTest {

    @Test
    void defaultsToRocksDbForThirtyMinutesAndAvoidsDuplicateDatabaseReads() throws Exception {
        MarketModuleProperties properties = new MarketModuleProperties();
        McpRocksDbStore rocks = mock(McpRocksDbStore.class);
        RedisCacheStore redis = mock(RedisCacheStore.class);
        AtomicReference<byte[]> stored = new AtomicReference<>();
        when(rocks.isUsable()).thenReturn(true);
        when(rocks.get(anyString())).thenAnswer(invocation -> stored.get());
        doAnswer(invocation -> { stored.set(invocation.getArgument(1)); return null; })
            .when(rocks).put(anyString(), any(byte[].class));
        FinancialQueryCacheService cache = service(properties, rocks, redis);
        AtomicInteger loads = new AtomicInteger();

        Map<String, Object> first;
        Map<String, Object> second;
        Map<String, Object> third;
        try (McpInvocationContext.Scope ignored = McpInvocationContext.open(context("tenant-a"))) {
            first = cache.getOrLoad("runtime_dataset", Map.of("code", "000001"),
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 2), 20, "auto",
                () -> { loads.incrementAndGet(); return Map.of("rows", java.util.List.of(Map.of("value", 1))); });
            second = cache.getOrLoad("runtime_dataset", Map.of("code", "000001"),
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 2), 20, "auto",
                () -> { loads.incrementAndGet(); return Map.of("rows", java.util.List.of()); });
            third = cache.getOrLoad("runtime_dataset", Map.of("code", "000001"),
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 2), 20, "auto",
                () -> { loads.incrementAndGet(); return Map.of("rows", java.util.List.of()); });
        }

        assertThat(properties.getQueryCache().getStorage()).isEqualTo("ROCKSDB");
        assertThat(properties.getQueryCache().getTtlSeconds()).isEqualTo(1800L);
        assertThat(loads).hasValue(1);
        assertThat(first).containsEntry("queryCacheHit", false)
            .containsEntry("queryCacheStorage", "ROCKSDB")
            .containsEntry("queryCacheTtlSeconds", 1800L);
        assertThat(second).containsEntry("queryCacheHit", true)
            .containsEntry("queryCacheStorage", "ROCKSDB")
            .containsEntry("queryCacheHitCount", 1L);
        assertThat(third).containsEntry("queryCacheHit", true)
            .containsEntry("queryCacheHitCount", 2L);
    }

    @Test
    void selectedRedisFallsBackToRocksDbWhenRedisIsUnavailable() throws Exception {
        MarketModuleProperties properties = new MarketModuleProperties();
        properties.getQueryCache().setStorage("REDIS");
        McpRocksDbStore rocks = mock(McpRocksDbStore.class);
        RedisCacheStore redis = mock(RedisCacheStore.class);
        when(rocks.isUsable()).thenReturn(true);
        doThrow(new IllegalStateException("redis unavailable")).when(redis).get(anyString());
        doThrow(new IllegalStateException("redis unavailable"))
            .when(redis).put(anyString(), any(byte[].class), anyLong());
        FinancialQueryCacheService cache = service(properties, rocks, redis);

        Map<String, Object> result;
        try (McpInvocationContext.Scope ignored = McpInvocationContext.open(context("tenant-a"))) {
            result = cache.getOrLoad("dynamic_dataset", Map.of(), null, null, 20, "auto",
                () -> Map.of("rows", java.util.List.of(Map.of("value", 2))));
        }

        assertThat(result).containsEntry("queryCacheStorage", "ROCKSDB")
            .containsEntry("queryCacheHit", false);
        verify(rocks).put(anyString(), any(byte[].class));
    }

    @Test
    void cacheKeySeparatesTenantsAndCanonicalizesFilterOrder() {
        MarketModuleProperties properties = new MarketModuleProperties();
        FinancialQueryCacheService cache = service(properties,
            mock(McpRocksDbStore.class), mock(RedisCacheStore.class));
        String first;
        try (McpInvocationContext.Scope ignored = McpInvocationContext.open(context("tenant-a"))) {
            first = cache.key("dataset", Map.of("b", 2, "a", 1), null, null, 20, "auto");
            assertThat(cache.key("dataset", Map.of("a", 1, "b", 2), null, null, 20, "auto"))
                .isEqualTo(first);
        }
        try (McpInvocationContext.Scope ignored = McpInvocationContext.open(context("tenant-b"))) {
            assertThat(cache.key("dataset", Map.of("a", 1, "b", 2), null, null, 20, "auto"))
                .isNotEqualTo(first);
        }
    }

    @Test
    void concurrentOversizedQueriesUseSingleFlightEvenWhenResultCannotBePersisted() throws Exception {
        MarketModuleProperties properties = new MarketModuleProperties();
        properties.getQueryCache().setMaxEntryKb(1);
        McpRocksDbStore rocks = mock(McpRocksDbStore.class);
        AtomicReference<byte[]> stored = new AtomicReference<>();
        when(rocks.isUsable()).thenReturn(true);
        when(rocks.get(anyString())).thenAnswer(invocation -> stored.get());
        doAnswer(invocation -> { stored.set(invocation.getArgument(1)); return null; })
            .when(rocks).put(anyString(), any(byte[].class));
        FinancialQueryCacheService cache = service(properties, rocks, mock(RedisCacheStore.class));
        AtomicInteger databaseReads = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(128);
        CountDownLatch ready = new CountDownLatch(128);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<CompletableFuture<Map<String, Object>>> requests = new ArrayList<>();
            for (int i = 0; i < 128; i++) {
                requests.add(CompletableFuture.supplyAsync(() -> {
                    ready.countDown();
                    try { start.await(); } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(ex);
                    }
                    try (McpInvocationContext.Scope ignored = McpInvocationContext.open(context("tenant-a"))) {
                        return cache.getOrLoad("pressure_dataset", Map.of("symbol", "runtime-symbol"),
                            null, null, 100, "auto", () -> {
                                databaseReads.incrementAndGet();
                                try { Thread.sleep(75L); } catch (InterruptedException ex) {
                                    Thread.currentThread().interrupt();
                                    throw new IllegalStateException(ex);
                                }
                                return Map.of("rows", List.of(Map.of("payload", "x".repeat(4096))));
                            });
                    }
                }, executor));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);

            assertThat(databaseReads).hasValue(1);
            assertThat(stored).hasNullValue();
            assertThat(requests).allSatisfy(request -> assertThat(request.join().get("rows")).isNotNull());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void missingTenantContextBypassesCacheToPreventCrossTenantReuse() throws Exception {
        MarketModuleProperties properties = new MarketModuleProperties();
        McpRocksDbStore rocks = mock(McpRocksDbStore.class);
        FinancialQueryCacheService cache = service(properties, rocks, mock(RedisCacheStore.class));
        AtomicInteger loads = new AtomicInteger();

        cache.getOrLoad("dataset", Map.of(), null, null, 10, "auto",
            () -> Map.of("load", loads.incrementAndGet()));
        cache.getOrLoad("dataset", Map.of(), null, null, 10, "auto",
            () -> Map.of("load", loads.incrementAndGet()));

        assertThat(loads).hasValue(2);
        verify(rocks, org.mockito.Mockito.never()).put(anyString(), any(byte[].class));
    }

    @Test
    void explicitMcpTenantScopeCachesWhenWorkerThreadContextIsUnavailable() throws Exception {
        MarketModuleProperties properties = new MarketModuleProperties();
        McpRocksDbStore rocks = mock(McpRocksDbStore.class);
        AtomicReference<byte[]> stored = new AtomicReference<>();
        when(rocks.isUsable()).thenReturn(true);
        when(rocks.get(anyString())).thenAnswer(invocation -> stored.get());
        doAnswer(invocation -> { stored.set(invocation.getArgument(1)); return null; })
            .when(rocks).put(anyString(), any(byte[].class));
        FinancialQueryCacheService cache = service(properties, rocks, mock(RedisCacheStore.class));
        AtomicInteger loads = new AtomicInteger();

        Map<String, Object> first = cache.getOrLoad("fund_scale", Map.of("market", "ETF"),
            null, null, 100, "auto", "tenant-from-mcp-arguments",
            () -> Map.of("rows", List.of(Map.of("value", loads.incrementAndGet()))));
        Map<String, Object> second = cache.getOrLoad("fund_scale", Map.of("market", "ETF"),
            null, null, 100, "auto", "tenant-from-mcp-arguments",
            () -> Map.of("rows", List.of(Map.of("value", loads.incrementAndGet()))));

        assertThat(loads).hasValue(1);
        assertThat(first).containsEntry("queryCacheHit", false);
        assertThat(second).containsEntry("queryCacheHit", true)
            .containsEntry("queryCacheHitCount", 1L);
    }

    private FinancialQueryCacheService service(MarketModuleProperties properties,
                                                McpRocksDbStore rocks,
                                                RedisCacheStore redis) {
        return new FinancialQueryCacheService(properties, new McpCacheProperties(), rocks, redis,
            new ObjectMapper().findAndRegisterModules());
    }

    private McpInvocationContext.Context context(String tenant) {
        return new McpInvocationContext.Context("test", null, null, "request", null,
            "user", null, tenant, null, null, null, null, null, null, null, null);
    }
}
