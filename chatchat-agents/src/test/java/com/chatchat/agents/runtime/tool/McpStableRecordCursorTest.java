package com.chatchat.agents.runtime.tool;

import com.chatchat.common.mcp.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class McpStableRecordCursorTest {
    private final ObjectMapper json = new ObjectMapper();
    private final McpServiceCall request = new McpServiceCall(null, "query", "service", "read",
        Map.of("pageSize", 1000, "subject", "authorized"), Map.of("tenantId", "tenant"), 0);

    @Test void drainsAllTenPagesInOrderWithTheSameAuthorityAndSourceVersion() {
        var requests = new ArrayList<McpServiceCall>();
        var result = McpStableRecordCursor.drain(request, page(0, 1000, 10_000, "v1", "1000"), json, continuation -> {
            requests.add(continuation);
            int offset = Integer.parseInt(continuation.pagination().pageToken());
            return page(offset, 1_000, 10_000, "v1", offset == 9_000 ? null : Integer.toString(offset + 1_000));
        });
        assertThat(result.status()).isEqualTo(McpServiceResultStatus.SUCCESS);
        assertThat((List<?>) ((Map<?, ?>) result.data()).get("records")).hasSize(10_000);
        assertThat(result.metadata().get("dataSupplyCursor")).isEqualTo(Map.of("status", "VERIFIED_TOTAL", "acceptedRows", 10_000));
        assertThat(requests).hasSize(9).allSatisfy(call -> {
            assertThat(call.context()).isEqualTo(request.context());
            assertThat(call.arguments()).containsEntry("subject", "authorized");
            assertThat(call.toolName()).isEqualTo(request.toolName());
        });
    }

    @Test void treatsEmptyTerminalPageAsObservedEndWithoutInventingAnExactTotal() {
        var result = McpStableRecordCursor.drain(request, page(0, 1000, null, "v1", "next"), json,
            ignored -> page(1000, 0, null, "v1", null));
        assertThat(result.status()).isEqualTo(McpServiceResultStatus.SUCCESS);
        assertThat(result.metadata().get("dataSupplyCursor")).isEqualTo(
            Map.of("status", "OBSERVED_PAGINATION_END", "acceptedRows", 1000));
    }

    @Test void changedVersionRepeatedCursorAndMissingPagesRemainPartial() {
        var first = page(0, 1000, null, "v1", "next");
        var wrongPages = List.of(page(1000, 1, null, "v2", null),
            page(0, 1000, null, "v1", "next"), page(1000, 0, null, "v1", "after-empty"),
            page(1000, 1, null, "v1", "next"));
        for (var invalid : wrongPages) {
            var result = McpStableRecordCursor.drain(request, first, json, ignored -> invalid);
            assertThat(result.status()).isEqualTo(McpServiceResultStatus.PARTIAL);
            assertThat(((Map<?, ?>) result.data()).get("complete")).isEqualTo(false);
        }
    }

    private McpServiceResult page(int offset, int size, Integer total, String version, String next) {
        var records = IntStream.range(offset, offset + size).mapToObj(n -> Map.of("id", n)).toList();
        var range = new java.util.LinkedHashMap<String, Object>(Map.of("offset", offset, "count", size));
        if (total != null) range.put("totalRows", total);
        return new McpServiceResult(null, "response", "service", "read", McpServiceResultStatus.SUCCESS,
            Map.of("records", records), null, null, null, false, null, Map.of(), McpResultKind.RAW_RECORDS,
            "records.v1", new McpResultProvenance("source", version, null, null, range, Map.of()),
            new McpPaginationResult(next, next != null, 1_000, (long) size), 0);
    }
}
