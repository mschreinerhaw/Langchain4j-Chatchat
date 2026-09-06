package com.chatchat.agents.orchestration.analysis.dataset;

import java.util.List;
import java.util.Map;

/** Compatibility handle for tool results that are still delivered as an in-memory collection. */
public final class InMemoryDatasetHandle implements DatasetHandle {
    private final List<Map<String, Object>> records;
    private final String contentSha256;
    private final long estimatedSizeBytes;

    public InMemoryDatasetHandle(List<Map<String, Object>> records) {
        this.records = records == null ? List.of() : List.copyOf(records);
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            long bytes = 0;
            for (Map<String, Object> row : this.records) {
                byte[] encoded = com.chatchat.agents.protocol.ModelProtocolJson.compact(row)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                digest.update(encoded); digest.update((byte) '\n'); bytes += encoded.length + 1L;
            }
            this.contentSha256 = java.util.HexFormat.of().formatHex(digest.digest());
            this.estimatedSizeBytes = bytes;
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @Override
    public long recordCount() {
        return records.size();
    }

    @Override
    public Page readPage(long offset, int limit) {
        if (offset < 0 || limit < 1) throw new IllegalArgumentException("Invalid dataset page");
        if (offset >= records.size()) return new Page(offset, List.of(), false);
        int from = Math.toIntExact(offset);
        int to = Math.min(records.size(), from + limit);
        return new Page(offset, records.subList(from, to), to < records.size());
    }

    @Override
    public List<Map<String, Object>> materialize(int maximumRows) {
        if (maximumRows < 0) throw new IllegalArgumentException("maximumRows must not be negative");
        return records.subList(0, Math.min(maximumRows, records.size()));
    }

    @Override
    public Map<String, Object> descriptor() {
        return Map.of("kind", "IN_MEMORY", "recordCount", records.size(),
            "recordCountExact", true, "contentSha256", contentSha256,
            "estimatedSizeBytes", estimatedSizeBytes);
    }

    @Override public String contentSha256() { return contentSha256; }
    @Override public java.util.OptionalLong estimatedSizeBytes() {
        return java.util.OptionalLong.of(estimatedSizeBytes);
    }
}
