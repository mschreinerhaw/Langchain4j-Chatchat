package com.chatchat.e2e;

import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.mcpserver.config.ChatChatMcpServerProperties;
import com.chatchat.mcpserver.news.NewsRuntimeClient;
import com.chatchat.mcpserver.news.RemoteNewsMcpToolProvider;
import com.chatchat.mcpserver.tool.McpToolConcurrencyManager;
import com.chatchat.runtime.market.storage.FinancialAssetCatalogService;
import com.chatchat.runtime.market.storage.FinancialDataStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Cross-module regression for the production incident where web timeouts exhausted database capacity. */
class ProductionWebSearchTimeoutIsolationE2E {

    @Test
    void timeoutStormCancelsTheRuntimeChainAvoidsDatabaseWorkAndRecoversWithoutRestart() throws Exception {
        NewsRuntimeClient news = mock(NewsRuntimeClient.class);
        FinancialAssetCatalogService market = mock(FinancialAssetCatalogService.class);
        FinancialDataStore store = mock(FinancialDataStore.class);
        when(store.assetSearchQuery(any(), anyInt())).thenAnswer(invocation -> invocation.getArgument(0));
        AtomicBoolean healthy = new AtomicBoolean();
        AtomicInteger started = new AtomicInteger();
        AtomicInteger stopped = new AtomicInteger();
        when(news.invoke(eq("web_search"), any())).thenAnswer(invocation -> {
            if (healthy.get()) {
                return ToolOutput.success(Map.of("results", List.of(Map.of(
                    "title", "recovered", "url", "https://example.test/recovered"))));
            }
            started.incrementAndGet();
            try {
                Thread.sleep(TimeUnit.MINUTES.toMillis(1));
                throw new AssertionError("timeout did not interrupt News Runtime invocation");
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new java.util.concurrent.CancellationException("runtime request cancelled");
            } finally {
                stopped.incrementAndGet();
            }
        });
        RemoteNewsMcpToolProvider provider = new RemoteNewsMcpToolProvider(news, market, store);

        ChatChatMcpServerProperties properties = new ChatChatMcpServerProperties();
        properties.getConcurrency().setGlobal(
            new ChatChatMcpServerProperties.LimitProperties(2, 2, 1, 1, "global"));
        ChatChatMcpServerProperties.LimitProperties limit =
            new ChatChatMcpServerProperties.LimitProperties(2, 2, 1, 1, "http");
        limit.setFailureThreshold(100);
        properties.getConcurrency().setTools(new LinkedHashMap<>(Map.of("web_search", limit)));

        try (ManagerResource resource = new ManagerResource(
            new McpToolConcurrencyManager(properties, new ObjectMapper()))) {
            ExecutorService callers = Executors.newFixedThreadPool(128);
            List<CompletableFuture<McpSchema.CallToolResult>> storm = new ArrayList<>();
            try {
                for (int index = 0; index < 512; index++) {
                    storm.add(CompletableFuture.supplyAsync(() -> invoke(resource.manager(), provider), callers));
                }
                List<McpSchema.CallToolResult> failures = storm.stream().map(CompletableFuture::join).toList();
                assertThat(failures).allSatisfy(result -> assertThat(result.isError()).isTrue());
            } finally {
                callers.shutdownNow();
            }
            awaitEqual(started, stopped, 2, TimeUnit.SECONDS);

            verify(market, never()).search(any(), anyInt());
            verify(store, never()).query(any(), any(), any(), any(), anyInt(), any());

            healthy.set(true);
            McpSchema.CallToolResult recovered = invoke(resource.manager(), provider);
            assertThat(recovered.isError()).isFalse();
        }
    }

    @Test
    void nonCooperativeZombieStormStaysBoundedAndRecoversAfterDownstreamFinallyReturns() throws Exception {
        ChatChatMcpServerProperties properties = new ChatChatMcpServerProperties();
        properties.getConcurrency().setGlobal(
            new ChatChatMcpServerProperties.LimitProperties(4, 4, 1, 1, "global"));
        ChatChatMcpServerProperties.LimitProperties limit =
            new ChatChatMcpServerProperties.LimitProperties(4, 4, 1, 1, "http");
        limit.setFailureThreshold(1_000);
        properties.getConcurrency().setTools(new LinkedHashMap<>(Map.of("web_search", limit)));

        CountDownLatch releaseZombies = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximumActive = new AtomicInteger();
        AtomicInteger stopped = new AtomicInteger();
        ExecutorService callers = Executors.newFixedThreadPool(128);
        try (ManagerResource resource = new ManagerResource(
            new McpToolConcurrencyManager(properties, new ObjectMapper()))) {
            List<CompletableFuture<McpSchema.CallToolResult>> storm = new ArrayList<>();
            for (int index = 0; index < 512; index++) {
                storm.add(CompletableFuture.supplyAsync(() -> resource.manager().execute(
                    "web_search", "http", Map.of(), () -> {
                        int current = active.incrementAndGet();
                        maximumActive.accumulateAndGet(current, Math::max);
                        try {
                            while (releaseZombies.getCount() > 0) {
                                try {
                                    releaseZombies.await();
                                } catch (InterruptedException ignored) {
                                    // Deliberately emulate a broken third-party driver that ignores cancellation.
                                }
                            }
                            return successResult("zombie released");
                        } finally {
                            active.decrementAndGet();
                            stopped.incrementAndGet();
                        }
                    }), callers));
            }
            List<McpSchema.CallToolResult> failures = storm.stream().map(CompletableFuture::join).toList();
            assertThat(failures).allSatisfy(result -> assertThat(result.isError()).isTrue());
            assertThat(maximumActive).hasValue(4);
            assertThat(active).hasValue(4);

            releaseZombies.countDown();
            awaitValue(active, 0, 2, TimeUnit.SECONDS);
            assertThat(stopped).hasValue(4);
            assertThat(resource.manager().execute("web_search", "http", Map.of(),
                () -> successResult("recovered")).isError()).isFalse();
        } finally {
            releaseZombies.countDown();
            callers.shutdownNow();
        }
    }

    private McpSchema.CallToolResult invoke(McpToolConcurrencyManager manager,
                                            RemoteNewsMcpToolProvider provider) {
        ToolInput input = ToolInput.builder().parameters(Map.of("query", "latest one-hour announcements")).build();
        return manager.execute("web_search", "http", Map.of(), () -> {
            ToolOutput output = provider.findExecutor("web_search").orElseThrow().execute(input);
            return McpSchema.CallToolResult.builder()
                .addTextContent(output.isSuccess() ? "ok" : output.getErrorMessage())
                .structuredContent(output.getData())
                .isError(!output.isSuccess())
                .build();
        });
    }

    private void awaitEqual(AtomicInteger expected, AtomicInteger actual, long timeout, TimeUnit unit)
        throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (actual.get() != expected.get() && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        assertThat(actual.get()).isEqualTo(expected.get());
    }

    private void awaitValue(AtomicInteger actual, int expected, long timeout, TimeUnit unit)
        throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (actual.get() != expected && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        assertThat(actual).hasValue(expected);
    }

    private McpSchema.CallToolResult successResult(String text) {
        return McpSchema.CallToolResult.builder()
            .addTextContent(text)
            .structuredContent(Map.of("success", true))
            .isError(false)
            .build();
    }

    private record ManagerResource(McpToolConcurrencyManager manager) implements AutoCloseable {
        @Override public void close() { manager.close(); }
    }
}
