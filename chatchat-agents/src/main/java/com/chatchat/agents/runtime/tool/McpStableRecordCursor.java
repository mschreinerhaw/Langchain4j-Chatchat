package com.chatchat.agents.runtime.tool;

import com.chatchat.common.mcp.service.McpPaginationRequest;
import com.chatchat.common.mcp.service.McpResultKind;
import com.chatchat.common.mcp.service.McpResultProvenance;
import com.chatchat.common.mcp.service.McpServiceCall;
import com.chatchat.common.mcp.service.McpServiceResult;
import com.chatchat.common.mcp.service.McpServiceResultStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.Function;

/** Reads an explicitly published, versioned and read-only MCP records cursor. */
final class McpStableRecordCursor {
    static final String CONTRACT = "mcp.data-supply.records-cursor.v1";
    static final String METADATA_KEY = "dataSupplyCursor";
    private static final int MAX_PAGES = 128;
    private static final int MAX_ROWS = 1_000_000;
    private static final long MAX_BYTES = 32L * 1024 * 1024;
    private static final long MAX_CURSOR_TIME_MS = 90_000;

    private McpStableRecordCursor() {}

    static McpServiceResult drain(McpServiceCall firstCall, McpServiceResult first, ObjectMapper mapper,
                                  Function<McpServiceCall, McpServiceResult> dispatch) {
        return drain(firstCall, first, mapper, dispatch, "records");
    }

    static McpServiceResult drain(McpServiceCall firstCall, McpServiceResult first, ObjectMapper mapper,
                                  Function<McpServiceCall, McpServiceResult> dispatch, String collection) {
        if (!first.successful() || first.resultKind() != McpResultKind.RAW_RECORDS
                || first.pagination() == null
                || firstCall.pagination() != null && firstCall.pagination().pageToken() != null)
            return first;

        var initial = first.provenance();
        if (initial == null || initial.sourceRef() == null || initial.dataVersion() == null
                || !(first.data() instanceof Map<?, ?> root) || !(root.get(collection) instanceof List<?>))
            return rejected(first, collection, "CURSOR_CONTRACT_INVALID");
        var records = new ArrayList<Object>();
        var rawPages = new ArrayList<Object>();
        var seenTokens = new HashSet<String>();
        var seenPages = new HashSet<com.fasterxml.jackson.databind.JsonNode>();
        long bytes = 0;
        long total = -1;
        long deadline = System.currentTimeMillis() + MAX_CURSOR_TIME_MS;
        if (firstCall.deadlineAt() > 0) deadline = Math.min(deadline, firstCall.deadlineAt());
        var current = first;
        String failure = null;
        for (int page = 1; ; page++) {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException();
            if (!sameSource(first, current) || !(current.data() instanceof Map<?, ?> body)
                    || !(body.get(collection) instanceof List<?> rows)
                    || rows.stream().anyMatch(row -> !(row instanceof Map<?, ?>))
                    || Boolean.FALSE.equals(body.get("complete"))
                    || Boolean.TRUE.equals(body.get("possiblyTruncated"))
                    || Boolean.TRUE.equals(body.get("preview"))) {
                failure = "CURSOR_SOURCE_CHANGED_OR_PAGE_INVALID";
                break;
            }
            var range = current.provenance().rowRange();
            var pagination = current.pagination();
            long pageTotal = number(range.get("totalRows"));
            if (pagination == null || number(range.get("offset")) != records.size()
                    || number(range.get("count")) != rows.size()
                    || pagination.returnedCount() != null && pagination.returnedCount() != rows.size()
                    || pagination.hasMore() && rows.isEmpty()
                    || !pagination.hasMore() && pagination.nextPageToken() != null
                    || range.containsKey("totalRows") && pageTotal < 0
                    || page > 1 && pageTotal != total
                    || pageTotal >= 0 && (records.size() + rows.size() > pageTotal
                        || pagination.hasMore() != (records.size() + rows.size() < pageTotal))) {
                failure = "CURSOR_RANGE_INVALID";
                break;
            }
            total = pageTotal;
            String token = pagination.nextPageToken();
            if (pagination.hasMore() && (token.length() > 4096 || !seenTokens.add(token))) {
                failure = "CURSOR_TOKEN_REPEATED_OR_INVALID";
                break;
            }
            long pageBytes;
            try {
                pageBytes = mapper.writeValueAsBytes(current.data()).length;
                if (current.rawData() != null) pageBytes += mapper.writeValueAsBytes(current.rawData()).length;
            } catch (java.io.IOException invalid) {
                failure = "CURSOR_PAGE_ENCODING_FAILED";
                break;
            }
            if (pageBytes > MAX_BYTES - bytes || rows.size() > MAX_ROWS - records.size()) {
                failure = "DATA_SUPPLY_LIMIT";
                break;
            }
            if (!rows.isEmpty() && !seenPages.add(mapper.valueToTree(rows))) {
                failure = "CURSOR_PAGE_REPEATED";
                break;
            }
            bytes += pageBytes;
            records.addAll(rows);
            if (current.rawData() != null) rawPages.add(current.rawData());
            if (!pagination.hasMore()) break;
            if (page == MAX_PAGES || System.currentTimeMillis() >= deadline) {
                failure = "DATA_SUPPLY_LIMIT";
                break;
            }
            var arguments = new LinkedHashMap<>(firstCall.arguments());
            arguments.put("pageToken", token);
            try {
                current = dispatch.apply(new McpServiceCall(null, firstCall.requestId() + ":page:" + (page + 1),
                    firstCall.serviceId(), firstCall.toolName(), arguments, firstCall.context(),
                    new McpPaginationRequest(token, firstCall.pagination() == null ? null : firstCall.pagination().pageSize()),
                    deadline));
            } catch (CancellationException cancelled) {
                throw cancelled;
            } catch (RuntimeException unavailable) {
                if (Thread.currentThread().isInterrupted()) throw new CancellationException();
                failure = "CURSOR_PAGE_UNAVAILABLE";
                break;
            }
        }
        if (records.isEmpty() && failure != null)
            return rejected(first, collection, failure);
        var data = new LinkedHashMap<String, Object>();
        root.forEach((k, v) -> data.put(String.valueOf(k), v));
        data.put(collection, List.copyOf(records));
        if ("assets".equals(collection)) {
            data.put("returnedCount", records.size());
            data.put("catalogComplete", failure == null && total >= 0);
        }
        data.put("rowCount", records.size());
        data.remove("pagination");
        data.remove("hasMore");
        data.put("complete", failure == null);
        data.put("possiblyTruncated", failure != null);
        return result(first, data, List.copyOf(rawPages), records.size(), failure == null
            ? (total >= 0 ? "VERIFIED_TOTAL" : "OBSERVED_PAGINATION_END") : failure);
    }

    private static McpServiceResult rejected(McpServiceResult first, String collection, String assurance) {
        var data = new LinkedHashMap<String, Object>();
        if ("assets".equals(collection) && first.data() instanceof Map<?, ?> root) {
            for (String key : List.of("schemaVersion", "catalogMode", "catalogVersion", "assetType", "stage", "authorizedAssetCount")) {
                if (root.get(key) != null) data.put(key, root.get(key));
            }
            data.put("catalogComplete", false);
            data.put("returnedCount", 0);
        }
        data.put(collection, List.of());
        data.put("rowCount", 0);
        data.put("complete", false);
        data.put("possiblyTruncated", true);
        return result(first, data, null, 0, assurance);
    }

    private static boolean sameSource(McpServiceResult first, McpServiceResult page) {
        return page != null && page.status() == McpServiceResultStatus.SUCCESS && page.provenance() != null
            && first.serviceId().equals(page.serviceId()) && first.toolName().equals(page.toolName())
            && first.resultKind() == page.resultKind() && Objects.equals(first.resultSchemaRef(), page.resultSchemaRef())
            && first.provenance().sourceRef().equals(page.provenance().sourceRef())
            && first.provenance().dataVersion().equals(page.provenance().dataVersion())
            && Objects.equals(first.provenance().asOf(), page.provenance().asOf())
            && Objects.equals(first.provenance().filterSummary(), page.provenance().filterSummary());
    }

    private static long number(Object value) {
        if (value instanceof Integer integer) return integer.longValue();
        if (value instanceof Long number) return number;
        if (value instanceof java.math.BigInteger integer && integer.bitLength() < 63) return integer.longValue();
        return -1;
    }

    private static McpServiceResult result(McpServiceResult first, Object data, Object raw,
                                           int rows, String assurance) {
        boolean complete = "VERIFIED_TOTAL".equals(assurance) || "OBSERVED_PAGINATION_END".equals(assurance);
        var metadata = new LinkedHashMap<>(first.metadata());
        metadata.remove(McpServiceResult.PAGINATION_KEY);
        metadata.put("dataSupplyCursor", Map.of("status", assurance, "acceptedRows", rows));
        var prior = first.provenance();
        var provenance = prior == null || rows == 0 ? prior : new McpResultProvenance(prior.sourceRef(),
            prior.dataVersion(), prior.asOf(), prior.inputFingerprint(), Map.of("offset", 0, "count", rows), prior.filterSummary());
        return new McpServiceResult(first.schemaVersion(), first.requestId(), first.serviceId(), first.toolName(),
            complete ? McpServiceResultStatus.SUCCESS : McpServiceResultStatus.PARTIAL,
            data, raw, null, null, false, null, metadata, first.resultKind(), first.resultSchemaRef(),
            provenance, null, first.completedAt());
    }
}
