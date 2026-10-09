package com.chatchat.enterprise.service;

import com.chatchat.common.mcp.capability.McpDynamicCapabilityRoute;
import com.chatchat.common.tool.ToolWorkflowContract;
import com.chatchat.common.tool.ToolWorkflowContractSnapshot;
import com.chatchat.common.tool.ToolWorkflowRole;
import com.chatchat.enterprise.entity.mcp.McpToolAsset;
import com.chatchat.enterprise.entity.mcp.McpArgumentBindingPolicy;
import com.chatchat.enterprise.entity.mcp.McpToolRuntimeCapability;
import com.chatchat.enterprise.repository.mcp.McpArgumentBindingPolicyRepository;
import com.chatchat.enterprise.repository.mcp.McpToolRuntimeCapabilityRepository;
import com.chatchat.enterprise.entity.mcp.McpToolWorkflowContract;
import com.chatchat.enterprise.repository.mcp.McpToolAssetRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.datasource.url=jdbc:h2:mem:tool_contract_catalog;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.jpa.show-sql=false"
})
@ContextConfiguration(classes = DatabaseToolWorkflowContractCatalogTest.Config.class)
class DatabaseToolWorkflowContractCatalogTest {

    @Test
    void rediscoveryDoesNotReactivateAdministrativelyDisabledTool() {
        catalog.synchronizeDiscovery("service-a", "Service A", "manual_tool", "remote-tool",
            "first", Map.of(), Map.of(), Map.of(), true);
        McpToolAsset tool = tools.findByLocalToolName("manual_tool").orElseThrow();
        tool.setEnabled(false);
        tool.setStatus("offline");
        tools.saveAndFlush(tool);

        catalog.synchronizeDiscovery("service-a", "Service A", "manual_tool", "remote-tool",
            "updated", Map.of(), Map.of(), Map.of(), true);

        McpToolAsset stored = tools.findByLocalToolName("manual_tool").orElseThrow();
        assertThat(stored.isEnabled()).isFalse();
        assertThat(stored.getStatus()).isEqualTo("offline");
    }

    @Autowired
    private DatabaseToolWorkflowContractCatalog catalog;

    @Autowired
    private McpToolAssetRepository tools;

    @Autowired
    private McpArgumentBindingPolicyRepository argumentBindingPolicies;

    @Autowired
    private McpToolRuntimeCapabilityRepository runtimeCapabilities;

    @Test
    void activeToolSnapshotIncludesDatabaseRuntimeCapabilities() {
        String before = catalog.runtimePolicyFingerprint();
        McpToolRuntimeCapability capability = new McpToolRuntimeCapability();
        capability.setRemoteToolName("opaque_executor");
        capability.setTemplateExecution(true);
        capability.setBatchExecution(true);
        runtimeCapabilities.saveAndFlush(capability);

        ToolWorkflowContractSnapshot active = catalog.synchronizeDiscovery(
            "service-a", "Service A", "opaque_executor_local", "opaque_executor", "executor",
            Map.of(), Map.of(), Map.of(), true).orElseThrow();

        assertThat(active.extensions().get("capabilities"))
            .isEqualTo(List.of("template_execution", "batch_execution"));
        assertThat(catalog.runtimePolicyFingerprint()).isNotEqualTo(before);
    }

    @Test
    void activeToolSnapshotIncludesDatabaseFieldPolicy() {
        McpArgumentBindingPolicy policy = new McpArgumentBindingPolicy();
        policy.setPolicyKey("default");
        policy.setPolicyJson("{\"logicalContextKeys\":[\"region\"]}");
        argumentBindingPolicies.saveAndFlush(policy);

        ToolWorkflowContractSnapshot active = catalog.synchronizeDiscovery(
            "service-a", "Service A", "opaque_policy_tool", "remote-policy", "policy",
            Map.of(), Map.of(), Map.of(), true).orElseThrow();

        assertThat(active.extensions().get("argumentBindingPolicy"))
            .isEqualTo(Map.of("logicalContextKeys", List.of("region")));
        assertThat(catalog.findActive("service-a", "opaque_policy_tool", "remote-policy")
            .orElseThrow().extensions().get("argumentBindingPolicy"))
            .isEqualTo(Map.of("logicalContextKeys", List.of("region")));
    }

    @Test
    void routingProtocolIsPersistedInPublishedContract() {
        Map<String, Object> routing = Map.of("targetKindToAssetType", Map.of("graph", "graph_asset"));
        ToolWorkflowContractSnapshot active = catalog.synchronizeDiscovery(
            "service-a", "Service A", "opaque_graph_tool", "remote-graph", "graph",
            Map.of(), Map.of(), Map.of("routingProtocol", routing), true).orElseThrow();

        assertThat(active.extensions()).containsEntry("routingProtocol", routing);
        assertThat(catalog.findActive("service-a", "opaque_graph_tool", "remote-graph")
            .orElseThrow().extensions()).containsEntry("routingProtocol", routing);
    }

    @Test
    void publishedToolCarriesRuntimeRolesWithoutNameInference() {
        ToolWorkflowContractSnapshot active = catalog.synchronizeDiscovery(
            "service-a", "Service A", "opaque_graph_executor", "opaque-remote", "graph",
            Map.of(), Map.of(), Map.of("runtimeRoles", List.of("GRAPH_EXECUTE")), true).orElseThrow();

        assertThat(active.extensions()).containsEntry("runtimeRoles", List.of("GRAPH_EXECUTE"));
        assertThat(catalog.findActive("service-a", "opaque_graph_executor", "opaque-remote")
            .orElseThrow().extensions()).containsEntry("runtimeRoles", List.of("GRAPH_EXECUTE"));
    }

    @Test
    void stagesPublishesVersionsAndRollsBackWithoutToolNameSemantics() {
        Map<String, Object> schemaV1 = Map.of("type", "object", "required", List.of("asset"));
        Map<String, Object> metadata = Map.of(ToolWorkflowContract.METADATA_KEY, Map.of(
            "schemaVersion", ToolWorkflowContract.SCHEMA_VERSION,
            "workflowRole", "template_execution",
            "protocolFamily", "vendor-neutral"
        ));

        assertThat(catalog.synchronizeDiscovery("service-a", "Service A", "random_7f31", "opaque-x",
            "first", schemaV1, Map.of("type", "object"), metadata, false)).isEmpty();
        McpToolAsset tool = tools.findByLocalToolName("random_7f31").orElseThrow();
        McpToolWorkflowContract version1 = catalog.listContracts(tool.getId()).get(0);
        assertThat(version1.getStatus()).isEqualTo("DRAFT");

        ToolWorkflowContractSnapshot activeV1 = catalog.publish(tool.getId(), 1, "release-admin", 0L);
        assertThat(activeV1.workflowRole()).isEqualTo(ToolWorkflowRole.TEMPLATE_EXECUTION);
        assertThat(activeV1.inputSchema()).isEqualTo(schemaV1);

        Map<String, Object> schemaV2 = Map.of("type", "object", "required", List.of("target"));
        assertThat(catalog.synchronizeDiscovery(
            "service-a", "Service A", "random_7f31", "opaque-x", "second",
            schemaV2, Map.of("type", "array"), metadata, false)).isEmpty();
        assertThat(catalog.findActive("service-a", "random_7f31", "opaque-x").orElseThrow().version())
            .isEqualTo(1);

        assertThatThrownBy(() -> catalog.publish(tool.getId(), 2, "stale-admin", 0L))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("publication conflict");
        ToolWorkflowContractSnapshot activeV2 = catalog.publish(tool.getId(), 2, "release-admin", 1L);
        assertThat(activeV2.version()).isEqualTo(2);
        assertThat(activeV2.inputSchema()).isEqualTo(schemaV2);
        assertThat(catalog.listContracts(tool.getId()))
            .filteredOn(item -> "ACTIVE".equals(item.getStatus()))
            .hasSize(1);

        ToolWorkflowContractSnapshot rolledBack = catalog.publish(tool.getId(), 1, "release-admin", 2L);
        assertThat(rolledBack.version()).isEqualTo(1);
        assertThat(rolledBack.inputSchema()).isEqualTo(schemaV1);
        assertThat(catalog.listContracts(tool.getId()))
            .filteredOn(item -> "ACTIVE".equals(item.getStatus()))
            .hasSize(1);
    }

    @Test
    void bootstrapsAnExistingCatalogRowAsActiveForZeroDowntimeMigration() {
        McpToolAsset legacy = new McpToolAsset();
        legacy.setLocalToolName("legacy_capability_query");
        legacy.setServiceId("legacy-service");
        legacy.setRemoteToolName("remote-anything");
        tools.saveAndFlush(legacy);

        ToolWorkflowContractSnapshot migrated = catalog.synchronizeDiscovery(
            "legacy-service", "Legacy", "legacy_capability_query", "remote-anything",
            "legacy", Map.of(), Map.of(), Map.of(
                "kind", "dynamic_authorized_template_discovery",
                "parentToolName", "published-parent"), false).orElseThrow();

        assertThat(migrated.version()).isEqualTo(1);
        assertThat(catalog.listContracts(legacy.getId()).get(0).getPublishedBy())
            .isEqualTo("system-legacy-migration");
        assertThat(migrated.extensions()).containsEntry("parentToolName", "published-parent");
    }

    @Test
    void trustedServicePublishesLargeContractAndRecoversAnExistingDraft() {
        String largePublisherMetadata = "x".repeat(96 * 1024);
        Map<String, Object> metadata = Map.of(ToolWorkflowContract.METADATA_KEY, Map.of(
            "schemaVersion", ToolWorkflowContract.SCHEMA_VERSION,
            "workflowRole", "template_execution",
            "protocolFamily", "ssh-template",
            "publisherMetadata", largePublisherMetadata
        ));

        assertThat(catalog.synchronizeDiscovery(
            "trusted-service", "Trusted", "opaque_large_tool", "opaque-remote", "large",
            Map.of("type", "object"), Map.of(), metadata, false)).isEmpty();

        ToolWorkflowContractSnapshot active = catalog.synchronizeDiscovery(
            "trusted-service", "Trusted", "opaque_large_tool", "opaque-remote", "large",
            Map.of("type", "object"), Map.of(), metadata, true).orElseThrow();

        assertThat(active.version()).isEqualTo(1);
        assertThat(active.workflowRole()).isEqualTo(ToolWorkflowRole.TEMPLATE_EXECUTION);
        assertThat(active.extensions().get("publisherMetadata")).isEqualTo(largePublisherMetadata);
        McpToolWorkflowContract stored = catalog.listContracts(active.toolId()).get(0);
        assertThat(stored.getStatus()).isEqualTo("ACTIVE");
        assertThat(stored.getPublishedBy()).isEqualTo("system-trusted-service-discovery");
    }

    @Test
    void persistsParentDelegationAsPartOfTheActiveRuntimeContract() {
        Map<String, Object> route = McpDynamicCapabilityRoute.parentDelegation(
            "api_service_query", "_templateQueryChildToolName").toMetadata();
        Map<String, Object> metadata = Map.of(
            ToolWorkflowContract.METADATA_KEY, Map.of(
                "schemaVersion", ToolWorkflowContract.SCHEMA_VERSION,
                "workflowRole", "template_discovery",
                "protocolFamily", "api-template"
            ),
            McpDynamicCapabilityRoute.METADATA_KEY, route
        );

        ToolWorkflowContractSnapshot active = catalog.synchronizeDiscovery(
            "api-service", "API Service", "customer_service_template_query", "remote-child",
            "Business template implementation", Map.of("type", "object"), Map.of(), metadata, true
        ).orElseThrow();

        assertThat(active.extensions())
            .containsEntry(McpDynamicCapabilityRoute.METADATA_KEY, route);
        assertThat(active.extensions()).containsEntry("protocolFamily", "api-template");

        ToolWorkflowContractSnapshot unchanged = catalog.synchronizeDiscovery(
            "api-service", "API Service", "customer_service_template_query", "remote-child",
            "Business template implementation", Map.of("type", "object"), Map.of(), metadata, true
        ).orElseThrow();
        assertThat(unchanged.version()).isEqualTo(active.version());
        assertThat(catalog.listContracts(active.toolId())).hasSize(1);
    }

    @SpringBootConfiguration
    @EntityScan("com.chatchat.enterprise.entity")
    @EnableJpaRepositories("com.chatchat.enterprise.repository")
    @Import(DatabaseToolWorkflowContractCatalog.class)
    static class Config {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }
}
