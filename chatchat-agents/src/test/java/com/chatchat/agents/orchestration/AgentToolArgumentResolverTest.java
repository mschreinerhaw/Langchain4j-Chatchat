package com.chatchat.agents.orchestration;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentToolArgumentResolverTest {

    private final AgentToolArgumentResolver resolver = new AgentToolArgumentResolver(new AgentToolNameResolver(), 5);

    @Test
    void documentSearchUsesOpenRecallByDefaultAndPreservesOriginalQuery() {
        Map<String, Object> arguments = Map.of(
            "query", "跨交易日 任务依赖 执行判断 调度方案",
            "document_ids", List.of("20260617_c489d851")
        );

        Map<String, Object> result = resolver.applyToolDefaults(
            "mcp_chatchat_mcp_server_document_search",
            arguments,
            List.of("20260617_c489d851"),
            List.of(),
            "跨交易日任务依赖执行判断与调度方案 说的是什么?",
            5
        );

        assertThat(result)
            .doesNotContainKey("document_ids")
            .doesNotContainKey("documentIds")
            .doesNotContainKey("fileIds")
            .doesNotContainKey("file_ids")
            .doesNotContainKey("selectedDocumentIds")
            .doesNotContainKey("documentVisibilityEnforced")
            .doesNotContainKey("tags");
        assertThat(result.get("query").toString())
            .contains("跨交易日任务依赖执行判断与调度方案")
            .contains("跨交易日 任务依赖 执行判断 调度方案");
    }

    @Test
    void documentSearchInjectsBoundDocumentIdsOnlyForStrictScope() {
        Map<String, Object> result = resolver.applyToolDefaults(
            "document_search",
            Map.of("query", "跨交易日任务依赖执行判断与调度方案", "strict_document_scope", true),
            List.of("20260617_c489d851"),
            List.of(),
            "跨交易日任务依赖执行判断与调度方案",
            5
        );

        assertThat(result)
            .containsEntry("document_ids", List.of("20260617_c489d851"))
            .containsEntry("selectedDocumentIds", List.of("20260617_c489d851"))
            .containsEntry("documentVisibilityEnforced", true)
            .containsEntry("strict_document_scope", true);
    }

    @Test
    void linuxGatewayBindsLogicalContextFromUserQuery() {
        Map<String, Object> result = resolver.applyToolDefaults(
            "mcp_chatchat_mcp_server_linux_command_execute",
            Map.of("query", "check prod hive status", "template", "SERVICE_STATUS"),
            List.of(),
            List.of(),
            "check prod hive status",
            5
        );

        assertThat(result).doesNotContainKey("query");
        assertThat(result)
            .containsEntry("template", "SERVICE_STATUS")
            .containsEntry("reason", "check prod hive status");
        assertThat(result.get("executionContext"))
            .isInstanceOfSatisfying(Map.class, context -> assertThat(context)
                .containsEntry("env", "prod")
                .containsEntry("service", "hive"));
        assertThat(result.get("parameters"))
            .isInstanceOfSatisfying(Map.class, parameters -> assertThat(parameters)
                .containsEntry("serviceName", "hive-server2"));
    }

    @Test
    void linuxGatewayRejectsConcreteTargetsAndRawCommands() {
        Map<String, Object> hostResult = resolver.applyToolDefaults(
            "mcp_chatchat_mcp_server_linux_command_execute",
            Map.of("host", "10.10.1.23", "template", "SERVICE_STATUS"),
            List.of(),
            List.of(),
            "check prod hive status",
            5
        );
        Map<String, Object> commandResult = resolver.applyToolDefaults(
            "mcp_chatchat_mcp_server_linux_command_execute",
            Map.of("command", "systemctl status hive-server2"),
            List.of(),
            List.of(),
            "check prod hive status",
            5
        );

        assertThat(hostResult)
            .containsEntry("__runtimeParamBindingStatus", "DENIED")
            .containsEntry("__runtimeParamBindingCode", "MCP_PARAM_BINDING_DENIED");
        assertThat(hostResult.get("__runtimeParamBindingError").toString()).contains("host");
        assertThat(commandResult)
            .containsEntry("__runtimeParamBindingStatus", "DENIED")
            .containsEntry("__runtimeParamBindingCode", "MCP_PARAM_BINDING_DENIED");
        assertThat(commandResult.get("__runtimeParamBindingError").toString()).contains("command");
    }

    @Test
    void templateQueryBindsFiltersFromUserQuery() {
        Map<String, Object> result = resolver.applyToolDefaults(
            "mcp_chatchat_mcp_server_template_query",
            Map.of(
                "targetKind", "host",
                "confidence", 0.9,
                "filters", Map.of(),
                "trace", trace(),
                "query", "check prod hive status"
            ),
            List.of(),
            List.of(),
            "check prod hive status",
            5
        );

        assertThat(result)
            .containsEntry("assetType", "ssh_host")
            .containsEntry("targetKind", "host")
            .containsEntry("filtersSchemaVersion", "target_filters.v1")
            .containsEntry("limit", 10);
        assertThat(result.get("filters"))
            .isInstanceOfSatisfying(Map.class, filters -> assertThat(filters)
                .containsEntry("env", "prod")
                .containsEntry("service", "hive")
                .containsEntry("intent", "check prod hive status"));
    }

    @Test
    void templateQueryUsesSqlDatasourceForDatabaseStatusIntent() {
        Map<String, Object> result = resolver.applyToolDefaults(
            "mcp_chatchat_mcp_server_template_query",
            Map.of(
                "targetKind", "database",
                "confidence", 0.9,
                "filters", Map.of("assetName", "tdh-scheduler-db", "env", "DEV", "intent", "database status"),
                "trace", trace()
            ),
            List.of(),
            List.of(),
            "analyze database status",
            5
        );

        assertThat(result)
            .containsEntry("assetType", "sql_datasource")
            .containsEntry("targetKind", "database")
            .containsEntry("filtersSchemaVersion", "target_filters.v1")
            .containsEntry("limit", 10);
        assertThat(result.get("filters"))
            .isInstanceOfSatisfying(Map.class, filters -> assertThat(filters)
                .containsEntry("assetName", "tdh-scheduler-db")
                .containsEntry("env", "DEV")
                .containsEntry("intent", "database status"));
    }

    @Test
    void templateQueryBindsRoutingCandidateSetFinalDecision() {
        Map<String, Object> result = resolver.applyToolDefaults(
            "mcp_chatchat_mcp_server_template_query",
            Map.of(
                "candidates", List.of(
                    Map.of("targetKind", "database", "confidence", 0.81),
                    Map.of("targetKind", "http", "confidence", 0.74)
                ),
                "finalDecision", "database",
                "filters", Map.of("assetName", "tdh-scheduler-db", "env", "DEV", "intent", "database status"),
                "trace", trace()
            ),
            List.of(),
            List.of(),
            "analyze database status",
            5
        );

        assertThat(result)
            .containsEntry("assetType", "sql_datasource")
            .containsEntry("targetKind", "database")
            .containsEntry("finalDecision", "database")
            .containsEntry("confidence", 0.81)
            .containsEntry("filtersSchemaVersion", "target_filters.v1");
    }

    @Test
    void templateQueryRejectsCandidateSetWithoutFinalDecision() {
        Map<String, Object> result = resolver.applyToolDefaults(
            "mcp_chatchat_mcp_server_template_query",
            Map.of(
                "candidates", List.of(
                    Map.of("targetKind", "database", "confidence", 0.81),
                    Map.of("targetKind", "http", "confidence", 0.74)
                ),
                "filters", Map.of("intent", "database status"),
                "trace", trace()
            ),
            List.of(),
            List.of(),
            "analyze database status",
            5
        );

        assertThat(result)
            .containsEntry("__runtimeParamBindingStatus", "DENIED")
            .containsEntry("__runtimeParamBindingCode", "MCP_PARAM_BINDING_DENIED");
        assertThat(result.get("__runtimeParamBindingError").toString())
            .contains("requires explicit finalDecision/targetKind/assetType");
    }

    @Test
    void templateQueryRejectsMissingTargetKindInsteadOfGuessingFromKeywords() {
        Map<String, Object> result = resolver.applyToolDefaults(
            "mcp_chatchat_mcp_server_template_query",
            Map.of(
                "confidence", 0.9,
                "filters", Map.of("assetName", "tdh-scheduler", "env", "DEV", "intent", "database status"),
                "trace", trace()
            ),
            List.of(),
            List.of(),
            "analyze database status",
            5
        );

        assertThat(result)
            .containsEntry("__runtimeParamBindingStatus", "DENIED")
            .containsEntry("__runtimeParamBindingCode", "MCP_PARAM_BINDING_DENIED");
        assertThat(result.get("__runtimeParamBindingError").toString()).contains("finalDecision");
    }

    @Test
    void templateQueryRejectsInvalidTargetKind() {
        Map<String, Object> result = resolver.applyToolDefaults(
            "mcp_chatchat_mcp_server_template_query",
            Map.of(
                "targetKind", "databse",
                "confidence", 0.9,
                "filters", Map.of("intent", "database status"),
                "trace", trace()
            ),
            List.of(),
            List.of(),
            "analyze database status",
            5
        );

        assertThat(result)
            .containsEntry("__runtimeParamBindingStatus", "DENIED")
            .containsEntry("__runtimeParamBindingCode", "MCP_PARAM_BINDING_DENIED");
        assertThat(result.get("__runtimeParamBindingError").toString())
            .contains("Unsupported targetKind", "databse");
    }

    @Test
    void templateQueryRejectsTargetKindAssetTypeConflict() {
        Map<String, Object> result = resolver.applyToolDefaults(
            "mcp_chatchat_mcp_server_template_query",
            Map.of(
                "targetKind", "database",
                "confidence", 0.9,
                "assetType", "ssh_host",
                "filters", Map.of("intent", "database status"),
                "trace", trace()
            ),
            List.of(),
            List.of(),
            "analyze database status",
            5
        );

        assertThat(result)
            .containsEntry("__runtimeParamBindingStatus", "DENIED")
            .containsEntry("__runtimeParamBindingCode", "MCP_PARAM_BINDING_DENIED");
        assertThat(result.get("__runtimeParamBindingError").toString())
            .contains("maps to assetType=sql_datasource");
    }

    @Test
    void templateQueryMarksLowConfidenceForReview() {
        Map<String, Object> result = resolver.applyToolDefaults(
            "mcp_chatchat_mcp_server_template_query",
            Map.of(
                "targetKind", "database",
                "confidence", 0.5,
                "filters", Map.of("intent", "database status"),
                "trace", trace()
            ),
            List.of(),
            List.of(),
            "analyze database status",
            5
        );

        assertThat(result)
            .containsEntry("__runtimeParamBindingStatus", "REVIEW_REQUIRED")
            .containsEntry("__runtimeParamBindingCode", "MCP_ROUTING_REVIEW_REQUIRED");
        assertThat(result.get("__runtimeParamBindingError").toString()).contains("confidence below routing threshold");
    }

    @Test
    void sqlGatewayRenamesTemplateIdAndBindsLogicalContext() {
        Map<String, Object> result = resolver.applyToolDefaults(
            "mcp_chatchat_mcp_server_sql_query_execute",
            Map.of(
                "templateId", "MYSQL_SHOW_STATUS",
                "executionContext", Map.of("assetName", "248测试数据库", "env", "DEV"),
                "parameters", Map.of()
            ),
            List.of(),
            List.of(),
            "查询248测试数据库状态",
            5
        );

        assertThat(result)
            .containsEntry("template", "MYSQL_SHOW_STATUS")
            .containsEntry("purpose", "查询248测试数据库状态")
            .doesNotContainKey("templateId");
        assertThat(result.get("executionContext"))
            .isInstanceOfSatisfying(Map.class, context -> assertThat(context)
                .containsEntry("assetName", "248测试数据库")
                .containsEntry("env", "DEV"));
    }

    private Map<String, Object> trace() {
        return Map.of("plannerVersion", "v1.0", "model", "unit-test");
    }
}
