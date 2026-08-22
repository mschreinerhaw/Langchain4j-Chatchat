package com.chatchat.enterprise.service;

import com.chatchat.common.tool.ToolWorkflowContract;
import com.chatchat.common.tool.ToolWorkflowContractSnapshot;
import com.chatchat.common.tool.ToolWorkflowRole;
import com.chatchat.enterprise.entity.McpToolAsset;
import com.chatchat.enterprise.entity.McpToolWorkflowContract;
import com.chatchat.enterprise.repository.McpToolAssetRepository;
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

    @Autowired
    private DatabaseToolWorkflowContractCatalog catalog;

    @Autowired
    private McpToolAssetRepository tools;

    @Test
    void stagesPublishesVersionsAndRollsBackWithoutToolNameSemantics() {
        Map<String, Object> schemaV1 = Map.of("type", "object", "required", List.of("asset"));
        Map<String, Object> metadata = Map.of(ToolWorkflowContract.METADATA_KEY, Map.of(
            "schemaVersion", ToolWorkflowContract.SCHEMA_VERSION,
            "workflowRole", "template_execution",
            "protocolFamily", "vendor-neutral"
        ));

        assertThat(catalog.synchronizeDiscovery("service-a", "Service A", "random_7f31", "opaque-x",
            "first", schemaV1, Map.of("type", "object"), metadata)).isEmpty();
        McpToolAsset tool = tools.findByLocalToolName("random_7f31").orElseThrow();
        McpToolWorkflowContract version1 = catalog.listContracts(tool.getId()).get(0);
        assertThat(version1.getStatus()).isEqualTo("DRAFT");

        ToolWorkflowContractSnapshot activeV1 = catalog.publish(tool.getId(), 1, "release-admin", 0L);
        assertThat(activeV1.workflowRole()).isEqualTo(ToolWorkflowRole.TEMPLATE_EXECUTION);
        assertThat(activeV1.inputSchema()).isEqualTo(schemaV1);

        Map<String, Object> schemaV2 = Map.of("type", "object", "required", List.of("target"));
        assertThat(catalog.synchronizeDiscovery(
            "service-a", "Service A", "random_7f31", "opaque-x", "second",
            schemaV2, Map.of("type", "array"), metadata)).isEmpty();
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
                "parentToolName", "published-parent")).orElseThrow();

        assertThat(migrated.version()).isEqualTo(1);
        assertThat(catalog.listContracts(legacy.getId()).get(0).getPublishedBy())
            .isEqualTo("system-legacy-migration");
        assertThat(migrated.extensions()).containsEntry("parentToolName", "published-parent");
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
