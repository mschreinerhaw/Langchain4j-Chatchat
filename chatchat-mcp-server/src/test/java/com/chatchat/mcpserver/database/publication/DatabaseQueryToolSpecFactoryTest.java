package com.chatchat.mcpserver.database.publication;

import com.chatchat.mcpserver.database.definition.DatabaseQueryConfig;
import com.chatchat.mcpserver.database.execution.DatabaseQueryInvokeService;

import com.chatchat.mcpserver.tool.AgentRuntimeGovernanceFactory;
import com.chatchat.mcpserver.tool.McpToolConcurrencyManager;
import com.chatchat.mcpserver.tool.StandardToolExecutionResultFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatabaseQueryToolSpecFactoryTest {

    @Test
    void toolPurposeDescriptionIsFullyChineseAndIncludesBusinessContext() {
        DatabaseQueryConfig config = new DatabaseQueryConfig();
        config.setId("query-1");
        config.setToolName("query_fund_nav_check");
        config.setTitle("基金净值一致性核验");
        config.setDescription("查询基金在不同渠道的净值差异记录。");
        config.setBusinessGroup("fund_nav");
        config.setBusinessGroupName("数据核验");
        config.setBusinessGroupDescription("用于跨渠道基金净值一致性核验。");
        config.setCapabilityCategory("data_validation");
        config.setDomain("finance");
        config.setBusinessScope("基金产品净值一致性核验。");
        config.setImplementationSteps("比较各渠道净值并返回差异。");

        AgentRuntimeGovernanceFactory governanceFactory = mock(AgentRuntimeGovernanceFactory.class);
        McpToolConcurrencyManager concurrencyManager = mock(McpToolConcurrencyManager.class);
        when(governanceFactory.metaForDatabaseQuery(any())).thenReturn(Map.of());
        when(concurrencyManager.limitMeta(anyString(), anyString())).thenReturn(Map.of());
        DatabaseQueryToolSpecFactory factory = new DatabaseQueryToolSpecFactory(
            mock(DatabaseQueryInvokeService.class),
            new ObjectMapper(),
            governanceFactory,
            concurrencyManager,
            mock(StandardToolExecutionResultFactory.class),
            new DatabaseQueryMcpNamingPolicy()
        );

        McpServerFeatures.SyncToolSpecification spec = factory.toToolSpecification(config);

        assertThat(spec.tool().description())
            .contains("查询基金在不同渠道的净值差异记录")
            .contains("业务领域：金融")
            .contains("能力分类：数据核验")
            .contains("data_validation")
            .contains("分类用途：用于跨渠道基金净值一致性核验")
            .contains("适用范围：基金产品净值一致性核验")
            .contains("实现步骤：比较各渠道净值并返回差异")
            .doesNotContain("Domain:", "Capability category:", "Business group:", "Group context:");
        assertThat(spec.tool().name()).isEqualTo("data_validation_fund_nav_check");
        assertThat(spec.tool().title()).isEqualTo("【数据核验】基金净值一致性核验");
        assertThat(spec.tool().meta())
            .containsEntry("category", "data_validation")
            .containsEntry("domain", "finance")
            .containsEntry("businessScope", "基金产品净值一致性核验。");
        Map<?, ?> applicability = (Map<?, ?>) spec.tool().meta().get("applicability");
        assertThat(applicability.get("scopeLabel").toString()).contains("专项数据能力");
        assertThat(applicability.get("summary").toString())
            .contains("按照已声明的参数契约执行")
            .doesNotContain("Execute", "Business data capability");
    }
}
