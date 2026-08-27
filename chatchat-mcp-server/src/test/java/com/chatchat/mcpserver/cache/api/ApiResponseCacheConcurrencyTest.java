package com.chatchat.mcpserver.cache.api;

import com.chatchat.mcpserver.cache.config.McpCacheProperties;
import com.chatchat.mcpserver.cache.rocksdb.McpRocksDbStore;

import com.chatchat.mcpserver.api.invocation.ApiInvokeResult;
import com.chatchat.mcpserver.api.registry.ApiServiceConfig;
import com.chatchat.mcpserver.mcp.McpInvocationContext;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiResponseCacheConcurrencyTest {

    @Test
    void concurrentRequestsForMaintainedApiLoadOnceAndShareCachedResponse() throws Exception {
        CacheFixture fixture = fixture();
        int requestCount = 16;
        AtomicInteger upstreamCalls = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch loaderStarted = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        try {
            List<Future<ApiInvokeResult>> futures = java.util.stream.IntStream.range(0, requestCount)
                .mapToObj(index -> executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try (McpInvocationContext.Scope ignored =
                             McpInvocationContext.open(context("tenant-api", "user-" + index))) {
                        return fixture.service().getOrLoad(
                            fixture.config(),
                            Map.of("securityCode", "000001", "tradeDate", "2026-07-27"),
                            () -> {
                                upstreamCalls.incrementAndGet();
                                loaderStarted.countDown();
                                try {
                                    if (!releaseLoader.await(5, TimeUnit.SECONDS)) {
                                        throw new IllegalStateException("API loader release timed out");
                                    }
                                } catch (InterruptedException ex) {
                                    Thread.currentThread().interrupt();
                                    throw new IllegalStateException("API loader interrupted", ex);
                                }
                                return response("maintained-api-data");
                            }
                        );
                    }
                }))
                .toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(loaderStarted.await(5, TimeUnit.SECONDS)).isTrue();
            releaseLoader.countDown();

            List<ApiInvokeResult> results = futures.stream()
                .map(future -> {
                    try {
                        return future.get(5, TimeUnit.SECONDS);
                    } catch (Exception ex) {
                        throw new IllegalStateException(ex);
                    }
                })
                .toList();

            assertThat(upstreamCalls).hasValue(1);
            assertThat(results).allSatisfy(result -> {
                assertThat(result.success()).isTrue();
                assertThat(result.body()).isEqualTo(Map.of("value", "maintained-api-data"));
            });
            assertThat(results.stream().filter(ApiInvokeResult::cacheHit).count())
                .isEqualTo(requestCount - 1L);
        } finally {
            releaseLoader.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void tenantParametersAndApiRevisionCreateIndependentCacheNamespaces() throws Exception {
        CacheFixture fixture = fixture();
        AtomicInteger upstreamCalls = new AtomicInteger();

        ApiInvokeResult tenantAFirst = load(fixture, "tenant-a", Map.of("market", "SH"), upstreamCalls);
        ApiInvokeResult tenantAHit = load(fixture, "tenant-a", Map.of("market", "SH"), upstreamCalls);
        ApiInvokeResult tenantBFirst = load(fixture, "tenant-b", Map.of("market", "SH"), upstreamCalls);
        ApiInvokeResult tenantAOtherParameter = load(fixture, "tenant-a", Map.of("market", "SZ"), upstreamCalls);
        Thread.sleep(2L);
        fixture.config().preUpdate();
        ApiInvokeResult revisedApiFirst = load(fixture, "tenant-a", Map.of("market", "SH"), upstreamCalls);

        assertThat(upstreamCalls).hasValue(4);
        assertThat(tenantAFirst.cacheHit()).isFalse();
        assertThat(tenantAHit.cacheHit()).isTrue();
        assertThat(tenantBFirst.cacheHit()).isFalse();
        assertThat(tenantAOtherParameter.cacheHit()).isFalse();
        assertThat(revisedApiFirst.cacheHit()).isFalse();
    }

    private ApiInvokeResult load(CacheFixture fixture,
                                 String tenantId,
                                 Map<String, Object> arguments,
                                 AtomicInteger upstreamCalls) {
        try (McpInvocationContext.Scope ignored = McpInvocationContext.open(context(tenantId, "user"))) {
            return fixture.service().getOrLoad(fixture.config(), arguments, () -> {
                int sequence = upstreamCalls.incrementAndGet();
                return response(tenantId + "-" + sequence);
            });
        }
    }

    private CacheFixture fixture() {
        Map<String, byte[]> entries = new ConcurrentHashMap<>();
        McpRocksDbStore store = mock(McpRocksDbStore.class);
        when(store.isUsable()).thenReturn(true);
        try {
            when(store.get(anyString())).thenAnswer(invocation -> entries.get(invocation.getArgument(0)));
            doAnswer(invocation -> {
                entries.put(invocation.getArgument(0), invocation.getArgument(1));
                return null;
            }).when(store).put(anyString(), any(byte[].class));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        ApiResponseCacheService service = new ApiResponseCacheService(
            new McpCacheProperties(),
            store,
            new ObjectMapper()
        );
        ApiServiceConfig config = new ApiServiceConfig();
        config.setId("maintained-api-service");
        config.setToolName("maintained_api_query");
        config.setCacheEnabled(true);
        config.setCacheTtlSeconds(300);
        config.prePersist();
        return new CacheFixture(service, config);
    }

    private ApiInvokeResult response(String value) {
        return new ApiInvokeResult(true, 200, Map.of(), Map.of("value", value), null, null);
    }

    private McpInvocationContext.Context context(String tenantId, String userId) {
        return new McpInvocationContext.Context(
            "test", "local", "test", "request", "client", userId, userId,
            tenantId, "USER", "workspace", "test", "trace", "api_service",
            "data", "read", ""
        );
    }

    private record CacheFixture(ApiResponseCacheService service, ApiServiceConfig config) {
    }
}
