package com.chatchat.chat.interaction.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.chatchat.common.tool.ToolWorkflowContractCatalog;
import com.chatchat.common.tool.ToolWorkflowContractSnapshot;
import com.chatchat.common.tool.ToolWorkflowRole;
import com.chatchat.enterprise.entity.mcp.CapabilityRegistryEntry;
import com.chatchat.enterprise.repository.mcp.CapabilityRegistryRepository;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatabaseRuntimeSemanticPolicySourceTest {
    @Test
    void readsCurrentPolicyFromDatabase() throws Exception {
        RuntimeSemanticPolicyRepository repository = mock(RuntimeSemanticPolicyRepository.class);
        RuntimeSemanticPolicyEntity row = new RuntimeSemanticPolicyEntity();
        row.setPolicyKey("default");
        row.setPolicyJson(new ObjectMapper().writeValueAsString(Map.of(
            "toolRules", java.util.List.of(Map.of("role", "CUSTOM", "mode", "SUFFIX", "value", "custom_query")))));
        when(repository.findById("default")).thenReturn(Optional.of(row));

        var source = new DatabaseRuntimeSemanticPolicySource(repository, new ObjectMapper());
        assertThat(source.snapshot().hasRole("tenant_custom_query", "CUSTOM")).isTrue();
        row.setPolicyJson("{");
        assertThatThrownBy(source::snapshot).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void publishedCapabilityRolesTakePrecedenceOverLegacySuffixRules() throws Exception {
        RuntimeSemanticPolicyRepository repository = mock(RuntimeSemanticPolicyRepository.class);
        RuntimeSemanticPolicyEntity row = new RuntimeSemanticPolicyEntity();
        row.setPolicyKey("default");
        row.setPolicyJson(new ObjectMapper().writeValueAsString(Map.of(
            "schemaVersion", "runtime-semantic-policy.v1", "policyVersion", "2",
            "toolRules", List.of(Map.of("role", "SQL_EXECUTE", "mode", "SUFFIX",
                "value", "sql_query_execute")))));
        when(repository.findById("default")).thenReturn(Optional.of(row));
        CapabilityRegistryEntry entry = new CapabilityRegistryEntry();
        entry.setCapabilityId("opaque-id");
        entry.setServiceId("vendor");
        entry.setToolName("vendor_sql_query_execute");
        entry.setStatus("PUBLISHED");
        CapabilityRegistryRepository capabilities = mock(CapabilityRegistryRepository.class);
        when(capabilities.findAll()).thenReturn(List.of(entry));
        RuntimeSemanticLegacyToolRepository legacyTools = mock(RuntimeSemanticLegacyToolRepository.class);
        RuntimeSemanticLegacyTool legacy = new RuntimeSemanticLegacyTool();
        legacy.setToolName("legacy_sql_query_execute");
        when(legacyTools.findAll()).thenReturn(List.of(legacy));
        ToolWorkflowContractCatalog contracts = mock(ToolWorkflowContractCatalog.class);
        when(contracts.findActive("vendor", "vendor_sql_query_execute", null))
            .thenReturn(Optional.of(new ToolWorkflowContractSnapshot("tool-id", 1,
                "tool_workflow_contract.v1", ToolWorkflowRole.TEMPLATE_EXECUTION,
                "vendor.protocol.v1", "json", "checksum", Map.of(), Map.of(),
                Map.of("runtimeRoles", List.of("GRAPH_EXECUTE")))));

        var policy = new DatabaseRuntimeSemanticPolicySource(repository, new ObjectMapper(),
            capabilities, contracts, legacyTools).snapshot();
        assertThat(policy.hasRole("vendor_sql_query_execute", "GRAPH_EXECUTE")).isTrue();
        assertThat(policy.hasRole("vendor_sql_query_execute", "SQL_EXECUTE")).isFalse();
        assertThat(policy.hasRole("legacy_sql_query_execute", "SQL_EXECUTE")).isTrue();
        assertThat(policy.hasRole("unknown_sql_query_execute", "SQL_EXECUTE")).isFalse();
    }
}
