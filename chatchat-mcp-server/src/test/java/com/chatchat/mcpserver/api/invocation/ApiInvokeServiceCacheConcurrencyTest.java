package com.chatchat.mcpserver.api.invocation;

import com.chatchat.mcpserver.api.registry.ApiServiceConfig;

import com.chatchat.mcpserver.audit.InvocationAuditService;
import com.chatchat.mcpserver.cache.api.ApiResponseCacheService;
import com.chatchat.mcpserver.cache.config.McpCacheProperties;
import com.chatchat.mcpserver.cache.rocksdb.McpRocksDbStore;
import com.chatchat.mcpserver.mcp.McpInvocationContext;
import com.chatchat.mcpserver.ops.http.HttpEndpointConfig;
import com.chatchat.mcpserver.ops.http.HttpEndpointConfigService;
import com.chatchat.mcpserver.template.TemplateParameterValidator;
import com.chatchat.tools.livedata.LivedataSessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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

class ApiInvokeServiceCacheConcurrencyTest {

    @Test
    void concurrentApiInvocationsHitMaintainedCacheAfterOneUpstreamRequest() throws Exception {
        AtomicInteger upstreamCalls = new AtomicInteger();
        CountDownLatch upstreamStarted = new CountDownLatch(1);
        CountDownLatch releaseUpstream = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/market", exchange -> {
            upstreamCalls.incrementAndGet();
            upstreamStarted.countDown();
            try {
                try {
                    if (!releaseUpstream.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("upstream release timed out");
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("upstream interrupted", ex);
                }
                byte[] body = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } finally {
                exchange.close();
            }
        });
        server.start();

        int requestCount = 16;
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        try {
            ApiInvokeService service = service(server.getAddress().getPort());
            ApiServiceConfig config = config();
            List<Future<ApiInvokeResult>> futures = java.util.stream.IntStream.range(0, requestCount)
                .mapToObj(index -> executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try (McpInvocationContext.Scope ignored =
                             McpInvocationContext.open(context("tenant-api", "user-" + index))) {
                        return service.invoke(config, Map.of("market", "SH"));
                    }
                }))
                .toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(upstreamStarted.await(5, TimeUnit.SECONDS)).isTrue();
            releaseUpstream.countDown();

            List<ApiInvokeResult> results = futures.stream()
                .map(future -> {
                    try {
                        return future.get(10, TimeUnit.SECONDS);
                    } catch (Exception ex) {
                        throw new IllegalStateException(ex);
                    }
                })
                .toList();

            assertThat(upstreamCalls).hasValue(1);
            assertThat(results).allSatisfy(result -> {
                assertThat(result.success()).isTrue();
                assertThat(result.statusCode()).isEqualTo(200);
            });
            assertThat(results.stream().filter(ApiInvokeResult::cacheHit).count())
                .isEqualTo(requestCount - 1L);
        } finally {
            releaseUpstream.countDown();
            executor.shutdownNow();
            server.stop(0);
        }
    }

    private ApiInvokeService service(int port) {
        ObjectMapper objectMapper = new ObjectMapper();
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
        ApiResponseCacheService cacheService =
            new ApiResponseCacheService(new McpCacheProperties(), store, objectMapper);
        HttpEndpointConfig gateway = new HttpEndpointConfig();
        gateway.setId("maintained-api-gateway");
        gateway.setName("Maintained API gateway");
        gateway.setEnabled(true);
        gateway.setMethod("GET");
        gateway.setUrlTemplate("http://localhost:" + port + "/market");
        gateway.setTimeoutMs(5000);
        HttpEndpointConfigService gatewayService = mock(HttpEndpointConfigService.class);
        when(gatewayService.getById("maintained-api-gateway")).thenReturn(gateway);
        @SuppressWarnings("unchecked")
        ObjectProvider<LivedataSessionService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return new ApiInvokeService(
            objectMapper,
            mock(InvocationAuditService.class),
            cacheService,
            provider,
            new TemplateParameterValidator(objectMapper),
            gatewayService
        );
    }

    private ApiServiceConfig config() {
        ApiServiceConfig config = new ApiServiceConfig();
        config.setId("maintained-api-service");
        config.setToolName("maintained_api_query");
        config.setGatewayId("maintained-api-gateway");
        config.setInputSchemaJson("""
            {
              "type": "object",
              "properties": {
                "market": { "type": "string" }
              },
              "required": ["market"],
              "additionalProperties": false
            }
            """);
        config.setCacheEnabled(true);
        config.setCacheTtlSeconds(300);
        config.prePersist();
        return config;
    }

    private McpInvocationContext.Context context(String tenantId, String userId) {
        return new McpInvocationContext.Context(
            "test", "local", "test", "request", "client", userId, userId,
            tenantId, "USER", "workspace", "test", "trace", "api_service",
            "data", "read", ""
        );
    }
}
