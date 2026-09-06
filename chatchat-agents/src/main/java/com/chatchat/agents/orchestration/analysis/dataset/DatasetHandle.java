package com.chatchat.agents.orchestration.analysis.dataset;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.Optional;

/**
 * Runtime data-plane handle used by analysis graphs.
 *
 * <p>A handle keeps record transport separate from model context. Implementations may be backed by
 * memory, a cursor, a spill store, or a remote analytical engine. Offsets are zero based; evidence
 * references exposed to models remain one based.</p>
 */
public interface DatasetHandle extends AutoCloseable {

    long recordCount();

    /** Whether {@link #recordCount()} is an exact count rather than a lower bound. */
    default boolean recordCountExact() {
        return true;
    }

    default java.util.OptionalLong estimatedSizeBytes() {
        return java.util.OptionalLong.empty();
    }

    /** Reads a bounded page without materializing the complete dataset. */
    Page readPage(long offset, int limit);

    /**
     * Executes a model-selected, provider-supported operation close to the data. The handle must
     * reject operations it cannot execute; Runtime never invents an aggregation or formula.
     */
    default Optional<OperationResult> execute(OperationRequest request) {
        return Optional.empty();
    }

    /**
     * Scans the handle with synchronous backpressure. Consumers must not retain mutable page data.
     */
    default void scan(int pageSize, Consumer<Page> consumer) {
        if (pageSize < 1) throw new IllegalArgumentException("pageSize must be positive");
        long offset = 0;
        while (true) {
            Page page = readPage(offset, pageSize);
            if (page.rows().isEmpty()) break;
            consumer.accept(page);
            offset += page.rows().size();
            if (!page.hasMore()) break;
        }
    }

    /** Materialization is a compatibility escape hatch and must always carry an explicit limit. */
    default List<Map<String, Object>> materialize(int maximumRows) {
        if (maximumRows < 0) throw new IllegalArgumentException("maximumRows must not be negative");
        if (maximumRows == 0) return List.of();
        var rows = new java.util.ArrayList<Map<String, Object>>();
        long offset = 0;
        int pageSize = Math.min(1_000, maximumRows);
        while (rows.size() < maximumRows) {
            Page page = readPage(offset, Math.min(pageSize, maximumRows - rows.size()));
            if (page.rows().isEmpty()) break;
            int remaining = maximumRows - rows.size();
            rows.addAll(page.rows().subList(0, Math.min(remaining, page.rows().size())));
            offset += page.rows().size();
            if (!page.hasMore()) break;
        }
        return List.copyOf(rows);
    }

    /** Read-only compatibility view that pages lazily and never copies the complete dataset. */
    default List<Map<String, Object>> asListView() {
        if (recordCount() > Integer.MAX_VALUE)
            throw new IllegalStateException("Dataset exceeds Java List address space");
        return new HandleListView(this);
    }

    /** Content hash computed page by page without constructing one giant JSON value. */
    default String contentSha256() {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            scan(1_000, page -> page.rows().forEach(row -> {
                digest.update(com.chatchat.agents.protocol.ModelProtocolJson.compact(row)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }));
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Stable description for audit and capacity diagnostics. */
    default Map<String, Object> descriptor() {
        return Map.of("kind", getClass().getSimpleName(), "recordCount", recordCount(),
            "recordCountExact", recordCountExact());
    }

    @Override
    default void close() {
        // Most handles have no owned resource. Cursor handles override this method.
    }

    record Page(long offset, List<Map<String, Object>> rows, boolean hasMore) {
        public Page {
            if (offset < 0) throw new IllegalArgumentException("offset must not be negative");
            rows = rows == null ? List.of() : rows.stream()
                .map(row -> java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(row)))
                .toList();
        }
    }

    record OperationRequest(String operation, Map<String, Object> specification) {
        public OperationRequest {
            if (operation == null || operation.isBlank())
                throw new IllegalArgumentException("operation is required");
            specification = specification == null ? Map.of() : Map.copyOf(specification);
        }
    }

    record OperationResult(Map<String, Object> value, Map<String, Object> lineage) {
        public OperationResult {
            value = value == null ? Map.of() : Map.copyOf(value);
            lineage = lineage == null ? Map.of() : Map.copyOf(lineage);
        }
    }

    final class HandleListView extends java.util.AbstractList<Map<String, Object>> {
        private final DatasetHandle source;
        private Page cached;
        HandleListView(DatasetHandle source) {
            this.source = source;
            this.cached = new Page(0, List.of(), source.recordCount() > 0);
        }
        public DatasetHandle source() { return source; }
        @Override public Map<String, Object> get(int index) {
            if (index < 0 || index >= size()) throw new IndexOutOfBoundsException(index);
            long end = cached.offset() + cached.rows().size();
            if (index < cached.offset() || index >= end) cached = source.readPage(index, 1_000);
            return cached.rows().get(Math.toIntExact(index - cached.offset()));
        }
        @Override public int size() { return Math.toIntExact(source.recordCount()); }
    }
}
