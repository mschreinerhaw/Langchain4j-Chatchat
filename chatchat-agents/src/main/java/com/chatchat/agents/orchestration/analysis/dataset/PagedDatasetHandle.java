package com.chatchat.agents.orchestration.analysis.dataset;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Dataset handle backed by a bounded page protocol, such as a database cursor or remote store. */
public final class PagedDatasetHandle implements DatasetHandle {
    private final long recordCount;
    private final boolean recordCountExact;
    private final PageReader reader;
    private final OperationExecutor executor;
    private final Runnable closer;
    private final Map<String, Object> descriptor;

    public PagedDatasetHandle(long recordCount, boolean recordCountExact, PageReader reader,
                              OperationExecutor executor, Runnable closer,
                              Map<String, Object> descriptor) {
        if (recordCount < 0) throw new IllegalArgumentException("recordCount must not be negative");
        this.recordCount = recordCount;
        this.recordCountExact = recordCountExact;
        this.reader = Objects.requireNonNull(reader, "reader");
        this.executor = executor;
        this.closer = closer == null ? () -> { } : closer;
        this.descriptor = descriptor == null ? Map.of() : Map.copyOf(descriptor);
    }

    @Override public long recordCount() { return recordCount; }
    @Override public boolean recordCountExact() { return recordCountExact; }

    @Override
    public Page readPage(long offset, int limit) {
        if (offset < 0 || limit < 1) throw new IllegalArgumentException("Invalid dataset page");
        Page page = Objects.requireNonNull(reader.read(offset, limit), "page reader result");
        if (page.offset() != offset || page.rows().size() > limit)
            throw new IllegalStateException("Dataset page provider violated the bounded page contract");
        return page;
    }

    @Override
    public Optional<OperationResult> execute(OperationRequest request) {
        return executor == null ? Optional.empty() : Optional.ofNullable(executor.execute(request));
    }

    @Override
    public Map<String, Object> descriptor() {
        var value = new java.util.LinkedHashMap<String, Object>();
        value.put("kind", "PAGED");
        value.put("recordCount", recordCount);
        value.put("recordCountExact", recordCountExact);
        value.put("supportsPushdown", executor != null);
        value.putAll(descriptor);
        return Map.copyOf(value);
    }

    @Override public void close() { closer.run(); }

    @FunctionalInterface public interface PageReader { Page read(long offset, int limit); }
    @FunctionalInterface public interface OperationExecutor { OperationResult execute(OperationRequest request); }
}
