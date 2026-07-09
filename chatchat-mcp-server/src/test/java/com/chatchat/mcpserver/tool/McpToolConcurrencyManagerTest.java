package com.chatchat.mcpserver.tool;

import com.chatchat.mcpserver.config.ChatChatMcpServerProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolConcurrencyManagerTest {

    private McpToolConcurrencyManager manager;

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.close();
        }
    }

    @Test
    void zeroTimeoutSecondsWaitsForToolCompletion() {
        ChatChatMcpServerProperties properties = new ChatChatMcpServerProperties();
        ChatChatMcpServerProperties.LimitProperties webSearchLimit =
            new ChatChatMcpServerProperties.LimitProperties(1, 1, 1, 0, "http");
        properties.getConcurrency().setTools(new LinkedHashMap<>(Map.of("web_search", webSearchLimit)));
        manager = new McpToolConcurrencyManager(properties, new ObjectMapper());

        McpSchema.CallToolResult result = manager.execute("web_search", "http", Map.of(), () -> {
            sleep(1200);
            return McpSchema.CallToolResult.builder()
                .addTextContent("ok")
                .structuredContent(Map.of("success", true))
                .isError(false)
                .build();
        });

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).hasSize(1);
        assertThat(manager.limitMeta("web_search", "http"))
            .containsEntry("timeout_seconds", 0L);
    }

    @Test
    void zeroQueueTimeoutSecondsWaitsForConcurrencySlot() throws Exception {
        ChatChatMcpServerProperties properties = new ChatChatMcpServerProperties();
        ChatChatMcpServerProperties.LimitProperties webSearchLimit =
            new ChatChatMcpServerProperties.LimitProperties(1, 1, 0, 0, "http");
        properties.getConcurrency().setTools(new LinkedHashMap<>(Map.of("web_search", webSearchLimit)));
        manager = new McpToolConcurrencyManager(properties, new ObjectMapper());

        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CompletableFuture<McpSchema.CallToolResult> first = CompletableFuture.supplyAsync(() ->
            manager.execute("web_search", "http", Map.of(), () -> {
                firstStarted.countDown();
                await(releaseFirst);
                return successResult("first");
            }));
        assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<McpSchema.CallToolResult> second = CompletableFuture.supplyAsync(() ->
            manager.execute("web_search", "http", Map.of(), () -> successResult("second")));
        sleep(200);
        assertThat(second).isNotDone();

        releaseFirst.countDown();
        assertThat(first.get(1, TimeUnit.SECONDS).isError()).isFalse();
        assertThat(second.get(1, TimeUnit.SECONDS).isError()).isFalse();
        assertThat(manager.limitMeta("web_search", "http"))
            .containsEntry("queue_timeout_seconds", 0L);
    }

    @Test
    void negativeMaxOutputCharsDisablesOutputTrimming() {
        ChatChatMcpServerProperties properties = new ChatChatMcpServerProperties();
        ChatChatMcpServerProperties.LimitProperties webSearchLimit =
            new ChatChatMcpServerProperties.LimitProperties(1, 1, 1, 0, "http");
        webSearchLimit.setMaxOutputChars(-1);
        properties.getConcurrency().setTools(new LinkedHashMap<>(Map.of("web_search", webSearchLimit)));
        manager = new McpToolConcurrencyManager(properties, new ObjectMapper());

        String longText = "x".repeat(2000);
        McpSchema.CallToolResult result = manager.execute("web_search", "http", Map.of(), () ->
            McpSchema.CallToolResult.builder()
                .addTextContent(longText)
                .structuredContent(Map.of("payload", longText))
                .isError(false)
                .build());

        assertThat(result.content()).hasSize(1);
        McpSchema.TextContent text = (McpSchema.TextContent) result.content().get(0);
        assertThat(text.text()).isEqualTo(longText);
        assertThat(((Map<?, ?>) result.structuredContent()).get("payload")).isEqualTo(longText);
        assertThat(manager.limitMeta("web_search", "http").get("max_output_chars")).isEqualTo(-1);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", ex);
        }
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", ex);
        }
    }

    private McpSchema.CallToolResult successResult(String text) {
        return McpSchema.CallToolResult.builder()
            .addTextContent(text)
            .structuredContent(Map.of("success", true))
            .isError(false)
            .build();
    }
}
