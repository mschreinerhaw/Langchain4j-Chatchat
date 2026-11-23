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

    @Test void validatesTheFirstPageEvenWhenTheProducerClaimsItIsTerminal() {
        var result = McpStableRecordCursor.drain(request, page(0, 3, 4, "v1", null), json,
            ignored -> { throw new AssertionError("Terminal page must not dispatch"); });
        assertThat(result.status()).isEqualTo(McpServiceResultStatus.PARTIAL);
        assertThat(result.metadata().get("dataSupplyCursor")).isEqualTo(
            Map.of("status", "CURSOR_RANGE_INVALID", "acceptedRows", 0));
    }

    @Test void rejectsRepeatedRowsEvenWhenTheProducerAdvancesTheOffsetAndToken() {
        var first = page(0, 2, null, "v1", "next");
        var repeated = page(2, 2, null, "v1", "different-token");
        var duplicate = new McpServiceResult(repeated.schemaVersion(), repeated.requestId(), repeated.serviceId(),
            repeated.toolName(), repeated.status(), first.data(), null, null, null, false, null, Map.of(),
            repeated.resultKind(), repeated.resultSchemaRef(), repeated.provenance(), repeated.pagination(), 0);
        var result = McpStableRecordCursor.drain(request, first, json, ignored -> duplicate);
        assertThat(result.status()).isEqualTo(McpServiceResultStatus.PARTIAL);
        assertThat(result.metadata().get("dataSupplyCursor")).isEqualTo(
            Map.of("status", "CURSOR_PAGE_REPEATED", "acceptedRows", 2));
    }

    @Test void drainsAllAssetPagesBeforeDeclaringTheCatalogComplete() {
        var result = McpStableRecordCursor.drain(request, assetPage(0, 64, "64", "v1"), json, continuation -> {
            assertThat(continuation.context()).isEqualTo(request.context());
            int offset = Integer.parseInt(continuation.pagination().pageToken());
            return offset == 64 ? assetPage(64, 64, "128", "v1") : assetPage(128, 22, null, "v1");
        }, "assets");
        var data = (Map<?, ?>) result.data();
        assertThat(data.get("catalogComplete")).isEqualTo(true);
        assertThat((List<?>) data.get("assets")).hasSize(150);
        assertThat(data.get("returnedCount")).isEqualTo(150);

        var changed = McpStableRecordCursor.drain(request, assetPage(0, 64, "64", "v1"), json,
            ignored -> assetPage(64, 64, "128", "v2"), "assets");
        assertThat(((Map<?, ?>) changed.data()).get("catalogComplete")).isEqualTo(false);
    }

    private McpServiceResult assetPage(int offset, int size, String next, String version) {
        var base = page(offset, size, 150, version, next);
        return new McpServiceResult(null, base.requestId(), base.serviceId(), base.toolName(), base.status(),
            Map.of("assets", ((Map<?, ?>) base.data()).get("records"), "catalogMode", "AUTHORIZED_CATALOG",
                "catalogVersion", version, "authorizedAssetCount", 150),
            null, null, null, false, null, Map.of(), base.resultKind(), base.resultSchemaRef(),
            base.provenance(), base.pagination(), 0);
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
