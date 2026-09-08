package com.chatchat.common.mcp.service;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpServiceContractTest {
    @Test
    void preservesRawResultAlongsideNormalizedData() {
        Map<String, Object> raw = Map.of("stdout", "container-a\ncontainer-b");
        McpServiceResult result = new McpServiceResult(null, "request-1", "docker", "ps",
            McpServiceResultStatus.SUCCESS, Map.of("containers", 2), raw,
            null, null, false, null, Map.of(), 0);

        assertThat(result.schemaVersion()).isEqualTo(McpServiceResult.SCHEMA_VERSION);
        assertThat(result.rawData()).isSameAs(raw);
        assertThat(result.successful()).isTrue();
    }

    @Test
    void queryMatchesLocalAndRemoteToolNames() {
        McpToolDescriptor tool = new McpToolDescriptor("docker", "docker_ps", "ps", "", "diagnostic",
            Map.of(), Map.of(), Map.of(), Map.of());

        assertThat(new McpToolQuery("docker", "DIAGNOSTIC", Set.of("ps")).matches(tool)).isTrue();
        assertThat(new McpToolQuery("other", null, Set.of()).matches(tool)).isFalse();
    }

    @Test
    void rejectsUnknownProtocolVersions() {
        assertThatThrownBy(() -> new McpServiceCall("v999", null, "docker", "ps", Map.of(), Map.of(), 0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void carriesDeclaredResultSemanticsProvenanceAndCursorWithoutChangingToolIntent() {
        McpResultProvenance provenance = new McpResultProvenance("warehouse/orders", "snapshot-42",
            "2026-09-08T10:00:00Z", "sha256:arguments", Map.of("from", 1, "to", 50),
            Map.of("tenant", "tenant-a"));
        McpPaginationResult pagination = new McpPaginationResult("cursor-2", true, 50, 50L);
        McpServiceResult result = new McpServiceResult(null, "request-2", "records", "read",
            McpServiceResultStatus.SUCCESS, java.util.List.of(Map.of("id", 1)), Map.of("rows", 1),
            null, null, false, null, Map.of(), McpResultKind.RAW_RECORDS, "orders.v3",
            provenance, pagination, 0);
        McpServiceCall next = new McpServiceCall(null, "request-3", "records", "read",
            Map.of(), Map.of(), new McpPaginationRequest("cursor-2", 50), 0);

        assertThat(result.resultKind()).isEqualTo(McpResultKind.RAW_RECORDS);
        assertThat(result.resultSchemaRef()).isEqualTo("orders.v3");
        assertThat(result.provenance()).isEqualTo(provenance);
        assertThat(result.pagination()).isEqualTo(pagination);
        assertThat(next.pagination().pageToken()).isEqualTo("cursor-2");
        assertThat(next.arguments()).isEmpty();
    }

    @Test
    void treatsLegitimateEmptyResultAsSuccessfulAndNonRetryableProtocolOutcome() {
        McpServiceResult result = new McpServiceResult(null, "request-empty", "records", "read",
            McpServiceResultStatus.EMPTY_RESULT, java.util.List.of(), java.util.List.of(), null,
            null, false, null, Map.of(), McpResultKind.EMPTY, null, null, null, 0);

        assertThat(result.successful()).isTrue();
        assertThat(result.retryable()).isFalse();
    }

    @Test
    void augmentsOnlyProtocolPaginationFieldsForDeclaredPagedTools() {
        Map<String, Object> schema = McpPaginationRequest.augmentInputSchema(Map.of(
            "type", "object", "properties", Map.of("query", Map.of("type", "string"))));

        assertThat(schema.toString()).contains("query", "pageToken", "pageSize", "minimum=1");
    }
}
