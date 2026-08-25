package com.chatchat.common.mcp.audit;

import com.chatchat.common.mcp.service.McpServiceDescriptor;
import com.chatchat.common.mcp.service.McpServiceResult;
import com.chatchat.common.mcp.service.McpServiceResultStatus;
import com.chatchat.common.mcp.service.McpToolDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class StandardMcpContractAuditorTest {
    private final StandardMcpContractAuditor auditor = new StandardMcpContractAuditor();

    @Test
    void auditsDatabaseServiceThroughDatabaseInterfaceContract() {
        McpToolDescriptor tool = tool("database", "database_query_execute", "database_query",
            Map.of("type", "object", "properties", Map.of("symbol", Map.of("type", "string"))),
            Map.of("type", "object"), Map.of("operationType", "read"),
            Map.of("contractVersion", "mcp_tool_contract.v1"));

        McpContractAuditReport report = auditor.audit(
            new McpContractAuditRequest("database", tool.localToolName(), null, Set.of("symbol"), null),
            List.of(service("database")), List.of(tool), contracts());

        assertThat(report.compliant()).isTrue();
        assertThat(report.evidence()).singleElement().satisfies(item -> {
            assertThat(item.domainCode()).isEqualTo("database");
            assertThat(item.domainContractId()).isEqualTo("runtime-os/database-mcp-service");
            assertThat(item.satisfiedPaths()).contains("INPUT_SCHEMA:type", "OUTPUT_SCHEMA:type");
        });
    }

    @Test
    void identifiesMissingLinuxTemplateAndLostRawExecutionEvidence() {
        McpToolDescriptor tool = tool("ops", "linux_command_execute", "ssh_asset",
            Map.of("type", "object", "properties", Map.of("template", Map.of("type", "string"))),
            Map.of("type", "object"), Map.of("operationType", "read"),
            Map.of("contractVersion", "mcp_tool_contract.v1", "contractMeta", Map.of("templates", List.of())));
        McpServiceResult result = new McpServiceResult(null, "r1", "ops", tool.localToolName(),
            McpServiceResultStatus.SUCCESS, Map.of("execution", Map.of("stdoutLength", 22)), null,
            null, null, false, null, Map.of(), 0);

        McpContractAuditReport report = auditor.audit(
            new McpContractAuditRequest("ops", tool.localToolName(), "CHECK_DOCKER_IMAGES", Set.of(), result),
            List.of(service("ops")), List.of(tool), contracts());

        assertThat(report.compliant()).isFalse();
        assertThat(report.findings()).extracting(McpContractFinding::code)
            .contains("MCP_TEMPLATE_ID_NOT_DISCOVERED", "MCP_RAW_RESULT_MISSING");
        assertThat(report.evidence()).singleElement().satisfies(item -> {
            assertThat(item.domainCode()).isEqualTo("linux");
            assertThat(item.normalizedResultAvailable()).isTrue();
            assertThat(item.rawResultAvailable()).isFalse();
        });
    }

    @Test
    void genericContractCoversFutureServiceWithoutChangingAuditor() {
        McpToolDescriptor tool = tool("future", "custom_action", "custom",
            Map.of(), Map.of(), Map.of(), Map.of());

        McpContractAuditReport report = auditor.audit(new McpContractAuditRequest("future", "custom_action", null, null, null),
            List.of(service("future")), List.of(tool), contracts());

        assertThat(report.evidence()).singleElement()
            .extracting(McpContractEvidence::domainCode).isEqualTo("generic");
        assertThat(report.findings()).extracting(McpContractFinding::recoveryAction)
            .contains("PUBLISH_INPUT_SCHEMA", "PUBLISH_OUTPUT_SCHEMA", "REVIEW_TOOL_GOVERNANCE");
    }

    private List<McpDomainServiceContract> contracts() {
        return List.of(new DatabaseMcpServiceContract(), new LinuxMcpServiceContract(), new GenericMcpServiceContract());
    }

    private McpServiceDescriptor service(String id) {
        return new McpServiceDescriptor(id, id, "test", "stdio", true, Map.of());
    }

    private McpToolDescriptor tool(String service, String name, String capability,
                                   Map<String, Object> input, Map<String, Object> output,
                                   Map<String, Object> governance, Map<String, Object> metadata) {
        return new McpToolDescriptor(service, name, name, "test", capability, input, output, governance, metadata);
    }
}
