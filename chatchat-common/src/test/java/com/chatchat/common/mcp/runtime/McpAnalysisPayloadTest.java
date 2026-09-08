package com.chatchat.common.mcp.runtime;

import com.chatchat.common.mcp.service.McpResultKind;
import com.chatchat.common.mcp.service.McpServiceResult;
import com.chatchat.common.mcp.service.McpServiceResultStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpAnalysisPayloadTest {

    @Test
    void preservesTypedFailureForRuntimeAndUiConsumers() {
        McpServiceResult failure = new McpServiceResult(null, "request-1", "service-1", "tool-1",
            McpServiceResultStatus.FAILED, null,
            Map.of("schemaVersion", "mcp_transport_failure.v1", "errorCode", "MCP_TOOL_NOT_FOUND"),
            "MCP_TOOL_NOT_FOUND", "Published child tool is unavailable", true,
            "REFRESH_OR_DISCOVER", Map.of("failureStage", "MCP_TRANSPORT"),
            McpResultKind.UNDECLARED, null, null, null, 0);

        Map<String, Object> payload = McpAnalysisPayload.from(failure, null).toMap();

        assertThat(payload)
            .containsEntry("status", "FAILED")
            .containsEntry("errorCode", "MCP_TOOL_NOT_FOUND")
            .containsEntry("errorMessage", "Published child tool is unavailable")
            .containsEntry("retryable", true)
            .containsEntry("recoveryAction", "REFRESH_OR_DISCOVER");
        assertThat(payload.get("rawData")).isEqualTo(failure.rawData());
    }
}
