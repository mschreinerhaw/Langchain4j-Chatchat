package com.chatchat.mcpserver.tool;

import com.chatchat.mcpserver.config.ChatChatMcpServerProperties;
import com.chatchat.mcpserver.config.ChatChatMcpServerProperties.LimitProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

@Slf4j
@Component
@RequiredArgsConstructor
public class McpToolConcurrencyManager {

    private static final String STATUS_BUSY = "BUSY";
    private static final String STATUS_TIMEOUT = "TIMEOUT";
    private static final String STATUS_CIRCUIT_OPEN = "CIRCUIT_OPEN";

    private final ChatChatMcpServerProperties properties;
    private final ObjectMapper objectMapper;
    private final Map<String, LimitState> states = new ConcurrentHashMap<>();
    private final Map<String, CircuitState> circuitStates = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool(new ToolThreadFactory());

    /**
     * Executes a tool call behind global, tool, asset and caller-level gates.
     *
     * @param toolName the tool name value
     * @param runtimeLevel the runtime level value
     * @param arguments the arguments value
     * @param supplier the supplier value
     * @return the operation result
     */
    public McpSchema.CallToolResult execute(String toolName, String runtimeLevel, Map<String, Object> arguments,
                                            Supplier<McpSchema.CallToolResult> supplier) {
        if (!properties.getConcurrency().isEnabled()) {
            return supplier.get();
        }

        LimitProperties toolLimit = limitFor(toolName, runtimeLevel);
        String normalizedLevel = firstText(toolLimit.getRuntimeLevel(), normalizeRuntimeLevel(toolName, runtimeLevel));
        CircuitState circuit = circuitState(assetKey(toolName, normalizedLevel));
        if (circuit.isOpen()) {
            return limitResult(STATUS_CIRCUIT_OPEN, toolName, normalizedLevel,
                "MCP tool circuit is open due to recent failures");
        }

        List<Permit> permits = new ArrayList<>();
        long startedAt = System.currentTimeMillis();
        AcquireResult acquired = acquire("global", "global", properties.getConcurrency().getGlobal(), permits);
        if (!acquired.success()) {
            release(permits);
            return limitResult(acquired.status(), toolName, normalizedLevel, acquired.message());
        }
        acquired = acquire("tool", normalizeKey(toolName), toolLimit, permits);
        if (!acquired.success()) {
            release(permits);
            return limitResult(acquired.status(), toolName, normalizedLevel, acquired.message());
        }
        acquired = acquire("asset", assetKey(toolName, normalizedLevel), toolLimit, permits);
        if (!acquired.success()) {
            release(permits);
            return limitResult(acquired.status(), toolName, normalizedLevel, acquired.message());
        }
        String userKey = callerKey(arguments, "userId", "user_id", "username", "operatorUserId");
        if (userKey != null) {
            acquired = acquire("user", userKey, properties.getConcurrency().getUser(), permits);
            if (!acquired.success()) {
                release(permits);
                return limitResult(acquired.status(), toolName, normalizedLevel, acquired.message());
            }
        }
        String agentKey = callerKey(arguments, "agentId", "agent_id", "sourceAgentId", "runtimeAgentId");
        if (agentKey != null) {
            acquired = acquire("agent", agentKey, properties.getConcurrency().getAgent(), permits);
            if (!acquired.success()) {
                release(permits);
                return limitResult(acquired.status(), toolName, normalizedLevel, acquired.message());
            }
        }

        Future<McpSchema.CallToolResult> future = executor.submit(() -> {
            try {
                McpSchema.CallToolResult result = executeWithRetry(supplier, toolLimit);
                if (Boolean.TRUE.equals(result.isError())) {
                    circuit.recordFailure(toolLimit);
                } else {
                    circuit.recordSuccess();
                }
                return enforceMaxOutput(toolName, normalizedLevel, result, toolLimit);
            } catch (Throwable throwable) {
                circuit.recordFailure(toolLimit);
                throw throwable;
            } finally {
                release(permits);
            }
        });

        try {
            long timeoutSeconds = toolLimit.getTimeoutSeconds();
            return timeoutSeconds <= 0
                ? future.get()
                : future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            circuit.recordFailure(toolLimit);
            log.warn("MCP tool execution timeout tool={} runtimeLevel={} durationMs={}",
                toolName, normalizedLevel, Math.max(0L, System.currentTimeMillis() - startedAt));
            return limitResult(STATUS_TIMEOUT, toolName, normalizedLevel,
                "MCP tool execution timed out after " + toolLimit.getTimeoutSeconds() + " seconds");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return limitResult(STATUS_TIMEOUT, toolName, normalizedLevel, "MCP tool execution was interrupted");
        } catch (CancellationException ex) {
            return limitResult(STATUS_TIMEOUT, toolName, normalizedLevel, "MCP tool execution was cancelled");
        } catch (ExecutionException ex) {
            circuit.recordFailure(toolLimit);
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            return limitResult("FAILED", toolName, normalizedLevel,
                cause.getClass().getSimpleName() + ": " + cause.getMessage());
        }
    }

    /**
     * Returns governance metadata for the current tool limit.
     *
     * @param toolName the tool name value
     * @param runtimeLevel the runtime level value
     * @return the operation result
     */
    public Map<String, Object> limitMeta(String toolName, String runtimeLevel) {
        LimitProperties limit = limitFor(toolName, runtimeLevel);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("tool_name", toolName);
        meta.put("max_concurrency", normalizePositive(limit.getMaxConcurrency(), 1));
        meta.put("timeout_seconds", Math.max(0, limit.getTimeoutSeconds()));
        meta.put("queue_size", Math.max(0, limit.getQueueSize()));
        meta.put("queue_timeout_seconds", Math.max(0, limit.getQueueTimeoutSeconds()));
        meta.put("runtime_level", firstText(limit.getRuntimeLevel(), normalizeRuntimeLevel(toolName, runtimeLevel)));
        meta.put("max_output_chars", Math.max(1_000, limit.getMaxOutputChars()));
        meta.put("retry_attempts", Math.max(0, limit.getRetryAttempts()));
        meta.put("failure_threshold", Math.max(1, limit.getFailureThreshold()));
        meta.put("circuit_open_seconds", Math.max(1, limit.getCircuitOpenSeconds()));
        return meta;
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }

    private AcquireResult acquire(String scope, String key, LimitProperties limit, List<Permit> permits) {
        LimitState state = state(scope + ":" + key, limit);
        int queueSize = Math.max(0, limit.getQueueSize());
        boolean acquired = state.semaphore.tryAcquire();
        if (!acquired && state.waiting.get() >= queueSize) {
            return new AcquireResult(false, STATUS_BUSY,
                "MCP " + scope + " concurrency queue is full for " + key);
        }
        if (!acquired) {
            state.waiting.incrementAndGet();
            try {
                long queueTimeoutSeconds = limit.getQueueTimeoutSeconds();
                if (queueTimeoutSeconds <= 0) {
                    state.semaphore.acquire();
                    acquired = true;
                } else {
                    acquired = state.semaphore.tryAcquire(queueTimeoutSeconds, TimeUnit.SECONDS);
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return new AcquireResult(false, STATUS_TIMEOUT,
                    "Interrupted while waiting for MCP " + scope + " concurrency slot");
            } finally {
                state.waiting.decrementAndGet();
            }
        }
        if (!acquired) {
            return new AcquireResult(false, STATUS_TIMEOUT,
                "Timed out waiting for MCP " + scope + " concurrency slot for " + key);
        }
        state.active.incrementAndGet();
        permits.add(new Permit(state));
        return new AcquireResult(true, "OK", "");
    }

    private LimitState state(String key, LimitProperties limit) {
        int maxConcurrency = normalizePositive(limit.getMaxConcurrency(), 1);
        return states.compute(key, (ignored, existing) -> {
            if (existing == null || existing.maxConcurrency != maxConcurrency) {
                return new LimitState(maxConcurrency);
            }
            return existing;
        });
    }

    private CircuitState circuitState(String key) {
        return circuitStates.computeIfAbsent(key, ignored -> new CircuitState());
    }

    private void release(List<Permit> permits) {
        for (int index = permits.size() - 1; index >= 0; index--) {
            Permit permit = permits.get(index);
            if (permit.state() == null || permit.released()) {
                continue;
            }
            permit.release();
        }
    }

    private LimitProperties limitFor(String toolName, String runtimeLevel) {
        LimitProperties merged = copy(properties.getConcurrency().getDefaults());
        String normalizedLevel = normalizeRuntimeLevel(toolName, runtimeLevel);
        LimitProperties runtime = properties.getConcurrency().getRuntimeLevels().get(normalizedLevel);
        if (runtime != null) {
            merge(merged, runtime);
        }
        LimitProperties tool = findToolLimit(toolName);
        if (tool != null) {
            merge(merged, tool);
        }
        merged.setRuntimeLevel(firstText(merged.getRuntimeLevel(), normalizedLevel));
        return merged;
    }

    private LimitProperties findToolLimit(String toolName) {
        if (toolName == null) {
            return null;
        }
        LimitProperties exact = properties.getConcurrency().getTools().get(toolName);
        if (exact != null) {
            return exact;
        }
        String normalized = toolName.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, LimitProperties> entry : properties.getConcurrency().getTools().entrySet()) {
            if (entry.getKey() != null && entry.getKey().toLowerCase(Locale.ROOT).equals(normalized)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private LimitProperties copy(LimitProperties source) {
        LimitProperties copy = new LimitProperties();
        merge(copy, source);
        return copy;
    }

    private void merge(LimitProperties target, LimitProperties source) {
        if (source == null) {
            return;
        }
        target.setMaxConcurrency(source.getMaxConcurrency());
        target.setQueueSize(source.getQueueSize());
        target.setQueueTimeoutSeconds(source.getQueueTimeoutSeconds());
        target.setTimeoutSeconds(source.getTimeoutSeconds());
        target.setRuntimeLevel(source.getRuntimeLevel());
        target.setMaxOutputChars(source.getMaxOutputChars());
        target.setRetryAttempts(source.getRetryAttempts());
        target.setFailureThreshold(source.getFailureThreshold());
        target.setCircuitOpenSeconds(source.getCircuitOpenSeconds());
    }

    private McpSchema.CallToolResult executeWithRetry(Supplier<McpSchema.CallToolResult> supplier,
                                                      LimitProperties limit) {
        int maxAttempts = Math.max(1, Math.max(0, limit.getRetryAttempts()) + 1);
        RuntimeException lastException = null;
        McpSchema.CallToolResult lastResult = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                lastResult = supplier.get();
                if (!Boolean.TRUE.equals(lastResult == null ? null : lastResult.isError()) || attempt == maxAttempts) {
                    return lastResult;
                }
            } catch (RuntimeException ex) {
                lastException = ex;
                if (attempt == maxAttempts) {
                    throw ex;
                }
            }
        }
        if (lastException != null) {
            throw lastException;
        }
        return lastResult;
    }

    private McpSchema.CallToolResult enforceMaxOutput(String toolName, String runtimeLevel,
                                                      McpSchema.CallToolResult result, LimitProperties limit) {
        if (result == null) {
            return limitResult("FAILED", toolName, runtimeLevel, "MCP tool returned null result");
        }
        int maxChars = Math.max(1_000, limit.getMaxOutputChars());
        List<McpSchema.Content> content = trimContent(result.content(), maxChars);
        Object structured = trimValue(result.structuredContent(), maxChars, new Counter());
        Map<String, Object> meta = new LinkedHashMap<>(result.meta() == null ? Map.of() : result.meta());
        meta.putIfAbsent("mcp_tool_limit", limitMeta(toolName, runtimeLevel));
        return McpSchema.CallToolResult.builder()
            .content(content)
            .structuredContent(structured)
            .isError(Boolean.TRUE.equals(result.isError()))
            .meta(meta)
            .build();
    }

    private List<McpSchema.Content> trimContent(List<McpSchema.Content> content, int maxChars) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        List<McpSchema.Content> trimmed = new ArrayList<>(content.size());
        Counter counter = new Counter();
        for (McpSchema.Content item : content) {
            if (item instanceof McpSchema.TextContent text) {
                trimmed.add(new McpSchema.TextContent(text.annotations(), trimText(text.text(), maxChars, counter), text.meta()));
            } else {
                trimmed.add(item);
            }
        }
        return trimmed;
    }

    private Object trimValue(Object value, int maxChars, Counter counter) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return trimText(text, maxChars, counter);
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (counter.value >= maxChars) {
                    copy.put("_truncated", true);
                    break;
                }
                copy.put(String.valueOf(entry.getKey()), trimValue(entry.getValue(), maxChars, counter));
            }
            return copy;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> copy = new ArrayList<>();
            for (Object item : iterable) {
                if (counter.value >= maxChars) {
                    copy.add(Map.of("_truncated", true));
                    break;
                }
                copy.add(trimValue(item, maxChars, counter));
            }
            return copy;
        }
        try {
            Object converted = objectMapper.convertValue(value, new TypeReference<Object>() {});
            if (!Objects.equals(converted, value)) {
                return trimValue(converted, maxChars, counter);
            }
        } catch (IllegalArgumentException ignored) {
            // Fall through to String conversion.
        }
        return trimText(String.valueOf(value), maxChars, counter);
    }

    private String trimText(String text, int maxChars, Counter counter) {
        if (text == null) {
            return null;
        }
        int remaining = maxChars - counter.value;
        if (remaining <= 0) {
            return "[truncated]";
        }
        if (text.length() <= remaining) {
            counter.value += text.length();
            return text;
        }
        counter.value = maxChars;
        return text.substring(0, Math.max(0, remaining)) + "...[truncated]";
    }

    private McpSchema.CallToolResult limitResult(String status, String toolName, String runtimeLevel, String message) {
        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("success", false);
        structured.put("status", status);
        structured.put("toolName", toolName);
        structured.put("runtimeLevel", runtimeLevel);
        structured.put("errorMessage", message);
        structured.put("timestamp", Instant.now().toString());
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("mcp_tool_limit", limitMeta(toolName, runtimeLevel));
        meta.put("status", status);
        return McpSchema.CallToolResult.builder()
            .addTextContent(message)
            .structuredContent(structured)
            .isError(true)
            .meta(meta)
            .build();
    }

    private String normalizeRuntimeLevel(String toolName, String runtimeLevel) {
        String value = firstText(runtimeLevel, "");
        if (!value.isBlank()) {
            return value.toLowerCase(Locale.ROOT);
        }
        String name = toolName == null ? "" : toolName.toLowerCase(Locale.ROOT);
        if (name.equals("linux_command_execute") || name.startsWith("ssh_")) {
            return "ssh";
        }
        if (name.equals("sql_query_execute") || name.equals("database_query_execute")
            || name.startsWith("sql_") || name.startsWith("db_query_")) {
            return "sql";
        }
        if (name.equals("http_request") || name.equals("http_request_execute")
            || name.startsWith("http_") || name.startsWith("livedata_")) {
            return "http";
        }
        if (name.contains("notification") || name.startsWith("notify_") || name.startsWith("mail_")
            || name.startsWith("sms_") || name.startsWith("wechat_") || name.startsWith("dingtalk_")) {
            return "notification";
        }
        return "tool";
    }

    private String assetKey(String toolName, String runtimeLevel) {
        return runtimeLevel + ":" + normalizeKey(toolName);
    }

    private String normalizeKey(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String callerKey(Map<String, Object> arguments, String... keys) {
        if (arguments == null || arguments.isEmpty()) {
            return null;
        }
        for (String key : keys) {
            Object value = arguments.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return normalizeKey(String.valueOf(value));
            }
        }
        return null;
    }

    private int normalizePositive(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private String firstText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private record AcquireResult(boolean success, String status, String message) {
    }

    private static final class LimitState {

        private final Semaphore semaphore;
        private final AtomicInteger waiting = new AtomicInteger();
        private final AtomicInteger active = new AtomicInteger();
        private final int maxConcurrency;

        private LimitState(int maxConcurrency) {
            this.maxConcurrency = maxConcurrency;
            this.semaphore = new Semaphore(maxConcurrency, true);
        }
    }

    private record Permit(LimitState state, AtomicInteger releasedFlag) {

        private Permit(LimitState state) {
            this(state, new AtomicInteger());
        }

        private boolean released() {
            return releasedFlag.get() > 0;
        }

        private void release() {
            if (releasedFlag.compareAndSet(0, 1)) {
                state.active.decrementAndGet();
                state.semaphore.release();
            }
        }
    }

    private static final class CircuitState {

        private final AtomicInteger failures = new AtomicInteger();
        private volatile long openUntilEpochMs;

        private boolean isOpen() {
            return System.currentTimeMillis() < openUntilEpochMs;
        }

        private void recordSuccess() {
            failures.set(0);
            openUntilEpochMs = 0L;
        }

        private void recordFailure(LimitProperties limit) {
            int threshold = Math.max(1, limit.getFailureThreshold());
            if (failures.incrementAndGet() >= threshold) {
                openUntilEpochMs = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(
                    Math.max(1, limit.getCircuitOpenSeconds()));
            }
        }
    }

    private static final class Counter {

        private int value;
    }

    private static final class ToolThreadFactory implements ThreadFactory {

        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "mcp-tool-exec-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
