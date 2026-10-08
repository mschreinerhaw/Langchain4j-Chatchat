package com.chatchat.mcpserver.datacapability.execution;

import com.chatchat.mcpserver.datacapability.definition.*;
import com.chatchat.mcpserver.template.TemplateParameterValidator;
import com.chatchat.mcpserver.mcp.McpInvocationContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

@Service @RequiredArgsConstructor
public class CapabilityExecutionService {
    private final CapabilityService capabilities;
    private final CapabilityExecutionRepository repository;
    private final TemplateParameterValidator parameters;
    private final ObjectMapper json;
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(32), task -> { Thread t = new Thread(task, "data-capability-query"); t.setDaemon(true); return t; });
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "data-capability-deadline"); thread.setDaemon(true); return thread;
    });

    public CapabilityExecution invoke(String code, Map<String, Object> input, boolean preview, boolean async) {
        CapabilityDefinition d = capabilities.get(code);
        if (!preview && (!d.enabled() || !d.apiPublished())) throw new IllegalArgumentException("Capability API is not published or enabled");
        return execute(d, input, preview, async);
    }
    public CapabilityExecution execute(CapabilityDefinition d, Map<String, Object> input, boolean preview, boolean async) {
        capabilities.validate(d);
        CapabilityExecution record = new CapabilityExecution();
        record.setId(UUID.randomUUID().toString()); record.setCapabilityCode(d.code());
        record.setStatus("QUEUED"); record.setStartedAt(Instant.now()); record.setPreview(preview);
        repository.saveAndFlush(record);
        Map<String, Object> snapshot = input == null ? Map.of() : new LinkedHashMap<>(input);
        Future<?> task;
        McpInvocationContext.Context caller = McpInvocationContext.current();
        try { task = workers.submit(() -> {
            try (var scope = McpInvocationContext.open(caller)) { run(record, d, snapshot); }
        }); }
        catch (RejectedExecutionException ex) { fail(record, "Execution queue is full"); return snapshot(record); }
        ScheduledFuture<?> deadline = deadlines.schedule(() -> {
            synchronized (record) {
                if (record.getFinishedAt() != null) return;
                record.setStatus("TIMED_OUT"); record.setError("Capability execution deadline exceeded"); finish(record);
            }
            task.cancel(true);
        }, d.timeoutSeconds(), TimeUnit.SECONDS);
        if (!async) {
            try { task.get(d.timeoutSeconds(), TimeUnit.SECONDS); }
            catch (TimeoutException ex) {
                synchronized (record) {
                    if (record.getFinishedAt() == null) {
                        record.setStatus("TIMED_OUT"); record.setError("Capability execution deadline exceeded"); finish(record);
                    }
                }
                task.cancel(true);
            } catch (InterruptedException ex) {
                task.cancel(true); fail(record, "Execution interrupted"); Thread.currentThread().interrupt();
            } catch (ExecutionException | CancellationException ex) {
                // The worker or the deadline records the terminal state before completion.
            } finally { deadline.cancel(false); }
        }
        // Return the worker's detached state. An HTTP OpenEntityManagerInView context may
        // still cache the initial QUEUED row even after the worker committed its result.
        return snapshot(record);
    }
    private void run(CapabilityExecution record, CapabilityDefinition d, Map<String, Object> input) {
        try {
            synchronized (record) {
                if (record.getFinishedAt() != null) return;
                record.setStatus("RUNNING"); repository.saveAndFlush(record);
            }
            Map<String, Object> validated = parameters.validateDeclaredOnly(d.code(), json.writeValueAsString(d.inputSchema()), input, Map.of());
            CapabilityAdapter.QueryResult output = capabilities.adapter(d.type()).execute(d, validated);
            List<Map<String, Object>> rows = output.rows().stream().limit(d.maxRows()).map(row -> map(row, d.resultMapping())).toList();
            String resultJson = json.writeValueAsString(Map.of("rows", rows, "rowCount", rows.size(),
                "truncated", output.truncated() || output.rows().size() > d.maxRows(), "metadata", output.metadata()));
            synchronized (record) {
                if (record.getFinishedAt() != null) return;
                record.setResultJson(resultJson); record.setStatus("SUCCEEDED"); finish(record);
            }
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            fail(record, ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        }
    }
    private Map<String, Object> map(Map<String, Object> row, Map<String, String> mapping) {
        if (mapping.isEmpty()) return row;
        Map<String, Object> result = new LinkedHashMap<>();
        mapping.forEach((target, source) -> result.put(target, row.get(source)));
        return result;
    }
    private void fail(CapabilityExecution record, String error) {
        synchronized (record) {
            if (record.getFinishedAt() != null) return;
            record.setStatus("FAILED"); record.setError(error.substring(0, Math.min(error.length(), 2000))); finish(record);
        }
    }
    private void finish(CapabilityExecution record) {
        record.setFinishedAt(Instant.now());
        record.setDurationMs(java.time.Duration.between(record.getStartedAt(), record.getFinishedAt()).toMillis());
        repository.saveAndFlush(record);
    }
    public CapabilityExecution get(String id) {
        return repository.findById(id).orElseThrow(() -> new IllegalArgumentException("Execution not found"));
    }
    private CapabilityExecution snapshot(CapabilityExecution record) {
        synchronized (record) {
            CapabilityExecution copy = new CapabilityExecution();
            copy.setId(record.getId()); copy.setCapabilityCode(record.getCapabilityCode()); copy.setStatus(record.getStatus());
            copy.setPreview(record.isPreview()); copy.setStartedAt(record.getStartedAt()); copy.setFinishedAt(record.getFinishedAt());
            copy.setDurationMs(record.getDurationMs()); copy.setError(record.getError()); copy.setResultJson(record.getResultJson());
            return copy;
        }
    }
    public List<CapabilityExecution> history(String code) { return repository.findTop50ByCapabilityCodeOrderByStartedAtDesc(code); }
    @PreDestroy public void stop() { deadlines.shutdownNow(); workers.shutdownNow(); }
}
