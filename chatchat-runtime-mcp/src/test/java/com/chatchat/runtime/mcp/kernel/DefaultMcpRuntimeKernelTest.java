package com.chatchat.runtime.mcp.kernel;

import com.chatchat.common.mcp.audit.McpContractAuditReport;
import com.chatchat.common.mcp.audit.McpContractFinding;
import com.chatchat.common.mcp.audit.McpContractSeverity;
import com.chatchat.common.mcp.audit.McpContractSource;
import com.chatchat.common.mcp.contract.McpTemplateBindingEvidence;
import com.chatchat.common.mcp.audit.McpRuntimeContractService;
import com.chatchat.common.mcp.service.McpResultRepairResult;
import com.chatchat.common.mcp.service.McpServiceDirectory;
import com.chatchat.common.mcp.service.McpServiceCall;
import com.chatchat.common.mcp.service.McpServiceResult;
import com.chatchat.common.mcp.service.McpServiceResultStatus;
import com.chatchat.common.mcp.service.McpToolDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

class DefaultMcpRuntimeKernelTest {

    @Test
    void rejectsInvocationWhenContractPreflightFails() {
        McpServiceDirectory directory = mock(McpServiceDirectory.class);
        McpRuntimeContractService contracts = mock(McpRuntimeContractService.class);
        McpContractFinding finding = new McpContractFinding(McpContractSeverity.ERROR,
            "MCP_OUTPUT_SCHEMA_MISSING", "docker", "docker_ps", "generic",
            McpContractSource.OUTPUT_SCHEMA, "type", "missing", "absent", "PUBLISH_OUTPUT_SCHEMA");
        when(contracts.audit(any())).thenReturn(
            new McpContractAuditReport(null, false, List.of(), List.of(finding), Map.of("ERROR", 1L), 0));
        DefaultMcpRuntimeKernel kernel = new DefaultMcpRuntimeKernel(directory, contracts);

        McpServiceResult result = kernel.execute(call());

        assertThat(result.status()).isEqualTo(McpServiceResultStatus.REJECTED);
        assertThat(result.errorCode()).isEqualTo("MCP_CONTRACT_PREFLIGHT_FAILED");
        assertThat(result.errorMessage()).contains("MCP_OUTPUT_SCHEMA_MISSING");
        assertThat(result.metadata()).containsEntry("preflightFindingCodes", List.of("MCP_OUTPUT_SCHEMA_MISSING"));
        verify(directory, never()).invoke(any());
    }

    @Test
    void repairsMissingNormalizedDataAndPreservesRawResult() {
        McpServiceDirectory directory = mock(McpServiceDirectory.class);
        McpRuntimeContractService contracts = mock(McpRuntimeContractService.class);
        when(contracts.audit(any())).thenReturn(report(true));
        Map<String, Object> raw = Map.of("content", List.of(Map.of("type", "text", "text", "container-a Up")));
        when(directory.invoke(any())).thenReturn(new McpServiceResult(null, "request-1", "docker", "docker_ps",
            McpServiceResultStatus.SUCCESS, null, raw, null, null, false, null, Map.of(), 0));
        when(directory.repair(any())).thenReturn(new McpResultRepairResult(null, "request-1", "docker", "docker_ps",
            McpServiceResultStatus.REPAIRED, Map.of("text", "container-a Up"), raw,
            Map.of("rawPreserved", true), "normalized"));
        DefaultMcpRuntimeKernel kernel = new DefaultMcpRuntimeKernel(directory, contracts);

        McpServiceResult result = kernel.execute(call());

        assertThat(result.status()).isEqualTo(McpServiceResultStatus.REPAIRED);
        assertThat(result.data()).isEqualTo(Map.of("text", "container-a Up"));
        assertThat(result.rawData()).isSameAs(raw);
        assertThat(result.metadata()).containsKeys("kernelProtocolVersion", "preflightAudit", "postflightAudit", "automaticRepair");
    }

    @Test
    void acceptsRuntimeOwnedDynamicTemplateBindingWithoutStaticTemplateMetadata() {
        McpServiceDirectory directory = mock(McpServiceDirectory.class);
        McpRuntimeContractService contracts = mock(McpRuntimeContractService.class);
        when(contracts.audit(any())).thenReturn(report(true));
        when(directory.invoke(any())).thenReturn(new McpServiceResult(null, "request-1", "python",
            "python_template_execute", McpServiceResultStatus.SUCCESS, Map.of("status", "ok"),
            Map.of("structuredContent", Map.of("status", "ok")), null, null, false, null, Map.of(), 0));
        DefaultMcpRuntimeKernel kernel = new DefaultMcpRuntimeKernel(directory, contracts);
        McpTemplateBindingEvidence binding = new McpTemplateBindingEvidence(
            McpTemplateBindingEvidence.SCHEMA_VERSION, "plan_binding_from_template_discovery",
            "template-123", "python_template_execute");
        McpServiceCall call = new McpServiceCall(null, "request-1", "python", "python_template_execute",
            Map.of("templateId", "template-123"),
            Map.of("templateId", "template-123", McpTemplateBindingEvidence.CONTEXT_KEY, binding.toMap()), 0);

        McpServiceResult result = kernel.execute(call);

        assertThat(result.successful()).isTrue();
        assertThat(result.metadata().get("templateBindingValidation")).asString()
            .contains("runtimeEvidenceValidated=true", "plan_binding_from_template_discovery");
        verify(directory).invoke(call);
    }

    @Test
    void rejectsMalformedRuntimeTemplateBindingInsteadOfSilentlyDroppingIt() {
        McpServiceDirectory directory = mock(McpServiceDirectory.class);
        McpRuntimeContractService contracts = mock(McpRuntimeContractService.class);
        DefaultMcpRuntimeKernel kernel = new DefaultMcpRuntimeKernel(directory, contracts);
        McpServiceCall call = new McpServiceCall(null, "request-1", "python",
            "python_template_execute", Map.of("templateId", "template-123"),
            Map.of("templateId", "template-123", McpTemplateBindingEvidence.CONTEXT_KEY,
                Map.of("schemaVersion", McpTemplateBindingEvidence.SCHEMA_VERSION,
                    "source", "plan_preflight")), 0);

        McpServiceResult result = kernel.execute(call);

        assertThat(result.status()).isEqualTo(McpServiceResultStatus.REJECTED);
        assertThat(result.errorCode()).isEqualTo("MCP_TEMPLATE_BINDING_INVALID");
        assertThat(result.metadata())
            .containsEntry("resourceFailureCategory", "CONTEXT_LOST")
            .containsKey(McpTemplateBindingEvidence.INVALID_REASON_KEY);
        verify(directory, never()).invoke(any());
        verify(contracts, never()).audit(any());
    }

    @Test
    void rejectsExecutorToolContractDriftBeforeProviderInvocation() {
        McpServiceDirectory directory = mock(McpServiceDirectory.class);
        McpRuntimeContractService contracts = mock(McpRuntimeContractService.class);
        when(directory.tools(any())).thenReturn(List.of(new McpToolDescriptor(
            "python", "python_template_execute", "python_template_execute", "", "template_execution",
            Map.of(), Map.of(), Map.of(), Map.of(
                "workflowContractVersion", "version-4",
                "workflowContractChecksum", "sha256:new"))));
        DefaultMcpRuntimeKernel kernel = new DefaultMcpRuntimeKernel(directory, contracts);
        McpTemplateBindingEvidence binding = new McpTemplateBindingEvidence(
            McpTemplateBindingEvidence.SCHEMA_VERSION, "plan_preflight", "template-123",
            "python_template_execute", "asset-1", "version-3", "sha256:old");
        McpServiceCall call = new McpServiceCall(null, "request-1", "python",
            "python_template_execute", Map.of("templateId", "template-123"),
            Map.of("templateId", "template-123",
                McpTemplateBindingEvidence.CONTEXT_KEY, binding.toMap()), 0);

        McpServiceResult result = kernel.execute(call);

        assertThat(result.status()).isEqualTo(McpServiceResultStatus.REJECTED);
        assertThat(result.errorCode()).isEqualTo("RESOURCE_VERSION_MISMATCH");
        assertThat(result.recoveryAction()).isEqualTo("REDISCOVER_TEMPLATE_AND_RECOMPILE_PLAN");
        assertThat(result.metadata())
            .containsEntry("resourceFailureCategory", "VERSION_MISMATCH")
            .containsEntry("snapshottedToolContractVersion", "version-3")
            .containsEntry("currentToolContractVersion", "version-4");
        verify(directory, never()).invoke(any());
        verify(contracts, never()).audit(any());
    }

    @Test
    void doesNotCompareChildTemplateSnapshotWithParentToolDescriptor() {
        McpServiceDirectory directory = mock(McpServiceDirectory.class);
        McpRuntimeContractService contracts = mock(McpRuntimeContractService.class);
        when(directory.tools(any())).thenReturn(List.of(new McpToolDescriptor(
            "api", "api_template_execute", "api_template_execute", "", "template_execution",
            Map.of(), Map.of(), Map.of(), Map.of(
                "workflowContractVersion", "parent-tool-v4",
                "workflowContractChecksum", "sha256:parent"))));
        when(directory.invoke(any())).thenReturn(new McpServiceResult(
            null, "request-1", "api", "api_template_execute", McpServiceResultStatus.SUCCESS,
            Map.of("ok", true), null, null, null, false, null, Map.of(), 0));
        when(contracts.audit(any())).thenReturn(new McpContractAuditReport(
            null, true, List.of(), List.of(), Map.of(), 0));
        DefaultMcpRuntimeKernel kernel = new DefaultMcpRuntimeKernel(directory, contracts);
        McpTemplateBindingEvidence binding = new McpTemplateBindingEvidence(
            McpTemplateBindingEvidence.SCHEMA_VERSION, "plan_preflight", "child-template-v1",
            "api_template_execute", null, "child-v7", "sha256:child", null, null);
        McpServiceCall call = new McpServiceCall(null, "request-1", "api",
            "api_template_execute", Map.of("templateId", "child-template-v1"),
            Map.of("templateId", "child-template-v1",
                McpTemplateBindingEvidence.CONTEXT_KEY, binding.toMap()), 0);

        McpServiceResult result = kernel.execute(call);

        assertThat(result.successful()).isTrue();
        verify(directory).invoke(call);
    }

    @Test
    void doesNotCompareWorkflowSnapshotWithGenericDescriptorProtocolVersion() {
        McpServiceDirectory directory = mock(McpServiceDirectory.class);
        McpRuntimeContractService contracts = mock(McpRuntimeContractService.class);
        when(directory.tools(any())).thenReturn(List.of(new McpToolDescriptor(
            "api", "api_template_execute", "api_template_execute", "", "template_execution",
            Map.of(), Map.of(), Map.of(), Map.of(
                "contractVersion", "mcp_tool_contract.v1",
                "contentHash", "generic-resource-hash"))));
        when(directory.invoke(any())).thenReturn(new McpServiceResult(
            null, "request-1", "api", "api_template_execute", McpServiceResultStatus.SUCCESS,
            Map.of("ok", true), null, null, null, false, null, Map.of(), 0));
        when(contracts.audit(any())).thenReturn(report(true));
        DefaultMcpRuntimeKernel kernel = new DefaultMcpRuntimeKernel(directory, contracts);
        McpTemplateBindingEvidence binding = new McpTemplateBindingEvidence(
            McpTemplateBindingEvidence.SCHEMA_VERSION, "plan_preflight", "template-1",
            "api_template_execute", null, "4", "workflow-checksum");
        McpServiceCall call = new McpServiceCall(null, "request-1", "api",
            "api_template_execute", Map.of("templateId", "template-1"),
            Map.of("templateId", "template-1",
                McpTemplateBindingEvidence.CONTEXT_KEY, binding.toMap()), 0);

        McpServiceResult result = kernel.execute(call);

        assertThat(result.successful()).isTrue();
        verify(directory).invoke(call);
    }

    @Test
    void reportsDegradedHealthWithoutFailingApplicationStartup() {
        McpServiceDirectory directory = mock(McpServiceDirectory.class);
        McpRuntimeContractService contracts = mock(McpRuntimeContractService.class);
        when(directory.services()).thenReturn(List.of());
        when(directory.tools(any())).thenReturn(List.of());
        when(contracts.contracts()).thenReturn(List.of());
        doThrow(new IllegalStateException("remote registry unavailable")).when(directory).refresh();
        DefaultMcpRuntimeKernel kernel = new DefaultMcpRuntimeKernel(directory, contracts);

        kernel.initialize();

        assertThat(kernel.kernelHealth().state().name()).isEqualTo("DEGRADED");
        assertThat(kernel.kernelHealth().lastFailure()).contains("remote registry unavailable");
    }

    private McpServiceCall call() {
        return new McpServiceCall(null, "request-1", "docker", "docker_ps", Map.of(), Map.of(), 0);
    }

    private McpContractAuditReport report(boolean compliant) {
        return new McpContractAuditReport(null, compliant, List.of(), List.of(), Map.of(), 0);
    }
}
