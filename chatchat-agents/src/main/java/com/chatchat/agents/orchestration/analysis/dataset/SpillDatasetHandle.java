package com.chatchat.agents.orchestration.analysis.dataset;

import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.agents.runtime.analysis.AnalysisEvidenceSpillStore;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Immutable page store used to release large in-memory analysis projections after ingestion. */
public final class SpillDatasetHandle implements DatasetHandle {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final long recordCount;
    private final int pageSize;
    private final AnalysisEvidenceSpillStore store;
    private final GovernanceIsolationScope scope;
    private final List<AnalysisEvidenceSpillStore.SpillReference> pages;
    private final String contentSha256;

    private SpillDatasetHandle(long recordCount, int pageSize, AnalysisEvidenceSpillStore store,
                               GovernanceIsolationScope scope,
                               List<AnalysisEvidenceSpillStore.SpillReference> pages,
                               String contentSha256) {
        this.recordCount = recordCount;
        this.pageSize = pageSize;
        this.store = store;
        this.scope = scope;
        this.pages = List.copyOf(pages);
        this.contentSha256 = contentSha256;
    }

    public static SpillDatasetHandle capture(String reference, DatasetHandle source,
                                             AnalysisEvidenceSpillStore store,
                                             GovernanceIsolationScope scope, int pageSize) {
        if (source == null || store == null || !store.isEnabled())
            throw new IllegalArgumentException("Enabled spill storage is required");
        if (pageSize < 1) throw new IllegalArgumentException("pageSize must be positive");
        List<AnalysisEvidenceSpillStore.SpillReference> pages = new ArrayList<>();
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
        source.scan(pageSize, page -> {
            page.rows().forEach(row -> {
                digest.update(ModelProtocolJson.compact(row).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            });
            try {
                String json = JSON.writeValueAsString(page.rows());
                String hash = ModelProtocolJson.sha256Hex(json);
                pages.add(store.spill(scope, reference + ":records:" + page.offset(), hash,
                    json.getBytes(StandardCharsets.UTF_8)));
            } catch (Exception failure) {
                throw new IllegalStateException("Failed to spill dataset page", failure);
            }
        });
        return new SpillDatasetHandle(source.recordCount(), pageSize, store, scope, pages,
            HexFormat.of().formatHex(digest.digest()));
    }

    @Override public long recordCount() { return recordCount; }

    @Override
    public Page readPage(long offset, int limit) {
        if (offset < 0 || limit < 1) throw new IllegalArgumentException("Invalid dataset page");
        if (offset >= recordCount) return new Page(offset, List.of(), false);
        List<Map<String, Object>> result = new ArrayList<>(limit);
        long cursor = offset;
        while (result.size() < limit && cursor < recordCount) {
            int pageIndex = Math.toIntExact(cursor / pageSize);
            int within = Math.toIntExact(cursor % pageSize);
            List<Map<String, Object>> stored = readStoredPage(pageIndex);
            int take = Math.min(limit - result.size(), stored.size() - within);
            if (take <= 0) {
                throw new IllegalStateException("Spilled dataset page does not cover declared record range");
            }
            result.addAll(stored.subList(within, within + take));
            cursor += take;
        }
        return new Page(offset, result, offset + result.size() < recordCount);
    }

    @Override public String contentSha256() { return contentSha256; }

    @Override
    public Map<String, Object> descriptor() {
        return Map.of("kind", "SPILL_PAGED", "recordCount", recordCount,
            "recordCountExact", true, "pageSize", pageSize, "pageCount", pages.size(),
            "contentSha256", contentSha256);
    }

    private List<Map<String, Object>> readStoredPage(int index) {
        if (index < 0 || index >= pages.size()) throw new IllegalArgumentException("Dataset page is unavailable");
        try {
            return JSON.readValue(store.read(scope, pages.get(index)), new TypeReference<>() {});
        } catch (Exception failure) {
            throw new IllegalStateException("Failed to read spilled dataset page", failure);
        }
    }
}
