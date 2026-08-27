package com.chatchat.integration.mcp.service.routing;

import com.chatchat.integration.mcp.service.routing.StandardMcpResultRepairer;

import com.chatchat.common.mcp.service.McpResultRepairRequest;
import com.chatchat.common.mcp.service.McpResultRepairResult;
import com.chatchat.common.mcp.service.McpServiceResultStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StandardMcpResultRepairerTest {
    private final StandardMcpResultRepairer repairer = new StandardMcpResultRepairer(new ObjectMapper());

    @Test
    void envelopesPlainStdoutWithoutLosingIt() {
        String stdout = "CONTAINER ID  STATUS\nabc123        Up 2 hours";
        McpResultRepairResult result = repairer.repair(request(stdout, Map.of()));

        assertThat(result.status()).isEqualTo(McpServiceResultStatus.REPAIRED);
        assertThat(result.rawResult()).isEqualTo(stdout);
        assertThat(result.normalizedData()).isEqualTo(Map.of("contentType", "text/plain", "text", stdout));
        assertThat(result.diagnostics()).containsEntry("rawPreserved", true);
    }

    @Test
    void unwrapsStructuredContentButPreservesWholeRawObject() {
        Map<String, Object> raw = Map.of("structuredContent", Map.of("images", List.of("mysql:8")), "meta", "kept");
        McpResultRepairResult result = repairer.repair(request(raw, Map.of("required", List.of("images"))));

        assertThat(result.normalizedData()).isEqualTo(Map.of("images", List.of("mysql:8")));
        assertThat(result.rawResult()).isSameAs(raw);
        assertThat(result.status()).isEqualTo(McpServiceResultStatus.REPAIRED);
    }

    @Test
    void reportsSchemaGapsAsPartialInsteadOfDroppingData() {
        McpResultRepairResult result = repairer.repair(request("{\"count\":2}", Map.of("required", List.of("items"))));

        assertThat(result.status()).isEqualTo(McpServiceResultStatus.PARTIAL);
        assertThat(result.diagnostics()).containsEntry("missingRequired", List.of("items"));
    }

    private McpResultRepairRequest request(Object raw, Map<String, Object> schema) {
        return new McpResultRepairRequest(null, "request-1", "docker", "docker_ps", raw,
            "model parse failed", schema, Map.of());
    }
}
