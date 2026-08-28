package com.chatchat.agents.orchestration.tool;

import com.chatchat.agents.orchestration.tool.AgentToolArgumentResolver;
import com.chatchat.agents.orchestration.tool.AgentToolNameResolver;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.agents.runtime.toolcall.TemplateInvocationBridge;
import com.chatchat.common.interaction.InteractionToolTrace;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolParameter;
import com.chatchat.common.tool.ToolWorkflowRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentToolArgumentResolverTest {

    private final AgentToolArgumentResolver resolver = new AgentToolArgumentResolver(new AgentToolNameResolver(), 5);

    @Test
    void injectsUniqueObservedAssetIntoFallbackTemplateDiscovery() {
        InteractionToolTrace assetDiscovery = InteractionToolTrace.builder()
            .toolName("mcp_chatchat_mcp_server_ssh_asset_query")
            .success(true)
            .output("""
                {"assets":[{"asset":{"id":"worker11-id","name":"CDH DataNode 节点 worker11",
                "environment":"DEV","toolName":"ssh_cdh_worker11_datanode"}}]}
                """)
            .build();

        Map<String, Object> result = resolver.enforceObservedAssetContinuity(
            "mcp_chatchat_mcp_server_ssh_template_query",
            Map.of("filters", Map.of("intent", "DataNode health")),
            List.of(assetDiscovery)
        );

        Map<?, ?> filters = (Map<?, ?>) result.get("filters");
        assertThat(filters.get("assetName")).isEqualTo("CDH DataNode 节点 worker11");
        assertThat(filters.get("env")).isEqualTo("DEV");
    }

    @Test
    void canonicalizesVerifiedDiscoveryQueryAliasForFallbackContinuation() {
        InteractionToolTrace assetDiscovery = InteractionToolTrace.builder()
            .toolName("mcp_chatchat_mcp_server_ssh_asset_query")
            .success(true)
            .output("""
                {"filters":{"assetName":"worker11"},"assets":[{"asset":{"id":"worker11-id",
                "name":"CDH DataNode Node worker11","environment":"DEV",
                "toolName":"ssh_cdh_worker11_datanode"}}]}
                """)
            .build();

        Map<String, Object> result = resolver.enforceObservedAssetContinuity(
            "mcp_chatchat_mcp_server_ssh_template_query",
            Map.of("filters", Map.of("assetName", "worker11", "intent", "health")),
            List.of(assetDiscovery)
        );

        assertThat(result).doesNotContainKey("__runtimeParamBindingStatus");
        Map<?, ?> filters = (Map<?, ?>) result.get("filters");
        assertThat(filters.get("assetName")).isEqualTo("CDH DataNode Node worker11");
        assertThat(filters.get("env")).isEqualTo("DEV");
    }

    @Test
    void resolvesPublishedAssetReferencesForFallbackTemplateDiscovery() {
        InteractionToolTrace assetDiscovery = InteractionToolTrace.builder()
            .toolName("mcp_chatchat_mcp_server_database_asset_search")
            .success(true)
            .output("""
                {"assets":[{"asset":{"id":"oracle-risk-dev","name":"Risk Control Oracle",
                "environment":"DEV","toolName":"oracle_risk_control"}}]}
                """)
            .build();

        Map<String, Object> result = resolver.enforceObservedAssetContinuity(
            "mcp_chatchat_mcp_server_database_ops_template_search",
            Map.of("filters", Map.of(
                "assetName", "$.assets[0].asset.name",
                "env", "$.assets[0].asset.environment",
                "intent", "database health"
            )),
            List.of(assetDiscovery)
        );

        assertThat(result).doesNotContainKey("__runtimeParamBindingStatus");
        Map<?, ?> filters = (Map<?, ?>) result.get("filters");
        assertThat(filters.get("assetName")).isEqualTo("Risk Control Oracle");
        assertThat(filters.get("env")).isEqualTo("DEV");
    }

    @Test
    void resolvesPublishedAssetReferencesForFallbackExecutor() {
        InteractionToolTrace assetDiscovery = InteractionToolTrace.builder()
            .toolName("mcp_chatchat_mcp_server_database_asset_search")
            .success(true)
            .output("""
                {"assets":[{"asset":{"id":"oracle-risk-dev","name":"Risk Control Oracle",
                "environment":"DEV","toolName":"oracle_risk_control"}}]}
                """)
            .build();

        Map<String, Object> result = resolver.enforceObservedAssetContinuity(
            "mcp_chatchat_mcp_server_sql_query_execute",
            Map.of("executionContext", Map.of(
                "assetId", "$.assets[0].asset.id",
                "assetName", "$.assets[0].asset.name",
                "env", "$.assets[0].asset.environment"
            )),
            List.of(assetDiscovery)
        );

        assertThat(result).doesNotContainKey("__runtimeParamBindingStatus");
        Map<?, ?> context = (Map<?, ?>) result.get("executionContext");
        assertThat(context.get("assetId")).isEqualTo("oracle-risk-dev");
        assertThat(context.get("assetName")).isEqualTo("Risk Control Oracle");
        assertThat(context.get("assetToolName")).isEqualTo("oracle_risk_control");
        assertThat(context.get("env")).isEqualTo("DEV");
    }

    @Test
    void rejectsUnpublishedAssetReferenceForFallbackContinuation() {
        InteractionToolTrace assetDiscovery = InteractionToolTrace.builder()
            .toolName("mcp_chatchat_mcp_server_database_asset_search")
            .success(true)
            .output("""
                {"assets":[{"asset":{"id":"oracle-risk-dev","name":"Risk Control Oracle",
                "environment":"DEV"}}]}
                """)
            .build();

        Map<String, Object> result = resolver.enforceObservedAssetContinuity(
            "mcp_chatchat_mcp_server_database_ops_template_search",
            Map.of("filters", Map.of("assetName", "$.assets[1].asset.name")),
            List.of(assetDiscovery)
        );

        assertThat(result)
            .containsEntry("__runtimeParamBindingStatus", "DENIED")
            .containsEntry("__runtimeParamBindingCode", "ASSET_CONTEXT_MISMATCH");
    }

    @Test
    void deniesFallbackExecutorWhenTemplateDiscoveryDriftsFromUniqueObservedAsset() {
        InteractionToolTrace assetDiscovery = InteractionToolTrace.builder()
            .toolName("mcp_chatchat_mcp_server_ssh_asset_query")
            .success(true)
            .output("""
                {"assets":[{"asset":{"id":"worker11-id","name":"CDH DataNode 节点 worker11",
                "environment":"DEV","toolName":"ssh_cdh_worker11_datanode"}}]}
                """)
            .build();
        InteractionToolTrace wrongTemplateDiscovery = InteractionToolTrace.builder()
            .toolName("mcp_chatchat_mcp_server_ssh_template_query")
            .success(true)
            .output("""
                {"queryIr":{"asset":{"selected":{"id":"adp-id","name":"ADP 平台开发数据库",
                "environment":"DEV"}}},"templates":[{"templateId":"CHECK_HOSTNAME",
                "parameterContract":{"executionTool":"linux_command_execute"},
                "parameterSchema":{"type":"object","properties":{},"required":[]}}]}
                """)
            .build();
        List<InteractionToolTrace> traces = List.of(assetDiscovery, wrongTemplateDiscovery);
        Map<String, Object> compiled = resolver.applyObservedTemplateContract(
            "mcp_chatchat_mcp_server_linux_command_execute",
            Map.of("parameters", Map.of()), traces);

        Map<String, Object> result = resolver.enforceObservedAssetContinuity(
            "mcp_chatchat_mcp_server_linux_command_execute", compiled, traces);

        assertThat(result)
            .containsEntry("__runtimeParamBindingStatus", "DENIED")
            .containsEntry("__runtimeParamBindingCode", "ASSET_CONTEXT_MISMATCH");
        assertThat(result.get("__runtimeParamBindingError").toString())
            .contains("ADP 平台开发数据库", "CDH DataNode 节点 worker11");
    }

    @Test
    void mandatoryFallbackTransportsPublishedDependencyEvidence() {
        ToolRegistry registry = mock(ToolRegistry.class);
        ToolMetadata metadata = ToolMetadata.builder()
            .metadata(Map.of(
                "mcpToolMeta", Map.of(
                    "inputAdapterContract", Map.of(
                        "contractVersion", "runtime_dependency_evidence.v1",
                        "dependencyEvidenceParameter", "sourceEvidence"
                    )
                )
            ))
            .build();
        when(registry.getToolMetadata("mcp_chatchat_mcp_server_enterprise_metadata_search"))
            .thenReturn(metadata);
        AgentToolArgumentResolver resolverWithRegistry =
            new AgentToolArgumentResolver(new AgentToolNameResolver(), 5, registry);
        InteractionToolTrace sqlMetadata = InteractionToolTrace.builder()
            .toolName("mcp_chatchat_mcp_server_sql_metadata_search")
            .success(true)
            .output("""
                {"success":true,"results":[{"location":{"tableName":"customer_info"},"columns":[{"name":"cust_id"}]}]}
                """)
            .build();

        Map<String, Object> result = resolverWithRegistry.applyPublishedDependencyEvidenceContract(
            "mcp_chatchat_mcp_server_enterprise_metadata_search",
            Map.of("query", "设计客户信息表"),
            List.of(sqlMetadata)
        );

        assertThat(result).containsKey("sourceEvidence");
        assertThat(result.get("sourceEvidence"))
            .isInstanceOfSatisfying(List.class, evidence -> {
                assertThat(evidence).hasSize(1);
                assertThat(((Map<?, ?>) evidence.get(0)).get("output"))
                    .isInstanceOf(Map.class);
            });
    }

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
            Map.of("query", "check hive status in prod environment", "template", "SERVICE_STATUS"),
            List.of(),
            List.of(),
            "check hive status in prod environment",
            5
        );

        assertThat(result).doesNotContainKey("query");
        assertThat(result)
            .containsEntry("template", "SERVICE_STATUS")
            .containsEntry("reason", "check hive status in prod environment");
        assertThat(result.get("executionContext"))
            .isInstanceOfSatisfying(Map.class, context -> assertThat(context)
                .containsEntry("env", "PROD")
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
                "query", "check hive status in prod environment"
            ),
            List.of(),
            List.of(),
            "check hive status in prod environment",
            5
        );

        assertThat(result)
            .containsEntry("assetType", "ssh_host")
            .containsEntry("targetKind", "host")
            .containsEntry("filtersSchemaVersion", "target_filters.v1")
            .containsEntry("limit", 10);
        assertThat(result.get("filters"))
            .isInstanceOfSatisfying(Map.class, filters -> assertThat(filters)
                .containsEntry("env", "PROD")
                .containsEntry("service", "hive")
                .containsEntry("intent", "check hive status in prod environment"));
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
    void sqlDatasourceAssetQueryWrapsDirectQueryFallbackAsDiscoveryContract() {
        Map<String, Object> result = resolver.applyToolDefaults(
            "mcp_chatchat_mcp_server_sql_datasource_asset_query",
            Map.of("query", "Analyze 248 test database table t_ad_dict_entr_supn"),
            List.of(),
            List.of(),
            "Analyze 248 test database table t_ad_dict_entr_supn",
            5
        );

        assertThat(result)
            .doesNotContainKey("__runtimeParamBindingStatus")
            .doesNotContainKey("query")
            .containsEntry("targetKind", "database")
            .containsEntry("finalDecision", "database")
            .containsEntry("assetType", "sql_datasource")
            .containsEntry("confidence", 0.9)
            .containsEntry("filtersSchemaVersion", "target_filters.v1")
            .containsEntry("limit", 10);
        assertThat(result.get("filters"))
            .isInstanceOfSatisfying(Map.class, filters -> assertThat(filters)
                .containsEntry("intent", "Analyze 248 test database table t_ad_dict_entr_supn")
                .doesNotContainKey("env"));
        assertThat(result.get("trace"))
            .isInstanceOfSatisfying(Map.class, trace -> assertThat(trace)
                .containsEntry("source", "agent_tool_argument_resolver")
                .containsEntry("toolName", "mcp_chatchat_mcp_server_sql_datasource_asset_query"));
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
    @SuppressWarnings("unchecked")
    void templateDiscoveryRepairsUnsupportedFiltersFromPublishedContract() {
        String toolName = "mcp_example_database_ops_template_search";
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata(toolName)).thenReturn(ToolMetadata.builder()
            .id(toolName)
            .categories(List.of("mcp"))
            .metadata(Map.of(
                "remoteToolName", "database_ops_template_search",
                "mcpToolMeta", Map.of(
                    "routingProtocol", Map.of(
                        "allowedFilterFields", List.of("env", "intent", "queryterms", "retrievalsignals")
                    )
                )
            ))
            .build());
        AgentToolArgumentResolver contractResolver =
            new AgentToolArgumentResolver(new AgentToolNameResolver(), 5, toolRegistry);

        Map<String, Object> result = contractResolver.applyToolDefaults(
            toolName,
            Map.of(
                "confidence", 0.95,
                "filters", Map.of(
                    "env", "DEV",
                    "intent", "database health diagnosis",
                    "templateIds", List.of("INSTANCE_STATUS", "TABLESPACE_USAGE")
                ),
                "trace", trace()
            ),
            List.of(),
            List.of(),
            "analyze the DEV database and host resources",
            5
        );

        Map<String, Object> filters = (Map<String, Object>) result.get("filters");
        assertThat(filters)
            .containsEntry("env", "DEV")
            .containsEntry("intent", "database health diagnosis")
            .doesNotContainKey("templateIds");
        assertThat((List<String>) filters.get("retrievalSignals"))
            .contains("templateIds:INSTANCE_STATUS", "INSTANCE_STATUS",
                "templateIds:TABLESPACE_USAGE", "TABLESPACE_USAGE");
        assertThat(result).doesNotContainKey("__runtimeParamBindingStatus");
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

    @Test
    @SuppressWarnings("unchecked")
    void hydratesLegacyAgentExecutorFromObservedTemplateContract() {
        InteractionToolTrace discovery = InteractionToolTrace.builder()
            .toolName("mcp_chatchat_mcp_server_database_ops_template_search")
            .success(true)
            .output("""
                {
                  "queryIr": {
                    "asset": {
                      "selected": {
                        "name": "风控oracle服务器",
                        "environment": "DEV"
                      }
                    }
                  },
                  "templates": [
                    {
                      "templateId": "ORACLE_INSTANCE_STATUS",
                      "parameterContract": {
                        "executionTool": "sql_query_execute"
                      },
                      "parameterSchema": {
                        "type": "object",
                        "properties": {},
                        "required": []
                      }
                    }
                  ]
                }
                """)
            .build();

        Map<String, Object> result = resolver.applyObservedTemplateContract(
            "mcp_chatchat_mcp_server_sql_query_execute",
            Map.of("executionContext", Map.of("service", "oracle"), "purpose", "分析数据库状态"),
            List.of(discovery)
        );

        assertThat(result)
            .containsEntry("template", "ORACLE_INSTANCE_STATUS")
            .containsEntry("parameters", Map.of());
        assertThat((Map<String, Object>) result.get("executionContext"))
            .containsEntry("service", "oracle")
            .containsEntry("assetName", "风控oracle服务器")
            .containsEntry("env", "DEV");
    }

    @Test
    @SuppressWarnings("unchecked")
    void replacesInventedSshTemplateAndTargetWithObservedContract() {
        InteractionToolTrace discovery = InteractionToolTrace.builder()
            .toolName("mcp_chatchat_mcp_server_ssh_template_query")
            .success(true)
            .output("""
                {
                  "queryIr": {
                    "asset": {
                      "selected": {
                        "name": "LiveData 调度器节点（CDH）",
                        "environment": "DEV"
                      }
                    }
                  },
                  "templates": [{
                    "templateId": "CHECK_JAVA_PROCESS",
                    "parameterContract": {"executionTool": "linux_command_execute"},
                    "parameterSchema": {
                      "type": "object",
                      "properties": {},
                      "required": []
                    }
                  }]
                }
                """)
            .build();

        Map<String, Object> result = resolver.applyObservedTemplateContract(
            "mcp_chatchat_mcp_server_linux_command_execute",
            Map.of(
                "template", "CHECK_IO_STATUS",
                "parameters", Map.of(),
                "executionContext", Map.of("assetName", "Docker 数据库模拟服务器", "env", "DEV")
            ),
            List.of(discovery)
        );

        assertThat(result).containsEntry("template", "CHECK_JAVA_PROCESS");
        assertThat((Map<String, Object>) result.get("executionContext"))
            .containsEntry("assetName", "LiveData 调度器节点（CDH）")
            .containsEntry("env", "DEV");
    }

    @Test
    void preservesTemplateWhenItExistsInObservedContract() {
        InteractionToolTrace discovery = InteractionToolTrace.builder()
            .toolName("mcp_chatchat_mcp_server_ssh_template_query")
            .success(true)
            .output("""
                {
                  "templates": [
                    {
                      "templateId": "CHECK_CPU",
                      "parameterContract": {"executionTool": "linux_command_execute"},
                      "parameterSchema": {"type": "object", "properties": {}, "required": []}
                    },
                    {
                      "templateId": "CHECK_JAVA_PROCESS",
                      "parameterContract": {"executionTool": "linux_command_execute"},
                      "parameterSchema": {"type": "object", "properties": {}, "required": []}
                    }
                  ]
                }
                """)
            .build();

        Map<String, Object> result = resolver.applyObservedTemplateContract(
            "mcp_chatchat_mcp_server_linux_command_execute",
            Map.of("template", "CHECK_JAVA_PROCESS", "parameters", Map.of()),
            List.of(discovery)
        );

        assertThat(result).containsEntry("template", "CHECK_JAVA_PROCESS");
    }

    @Test
    void doesNotUseDiscoveryTemplateDeclaredForAnotherExecutor() {
        InteractionToolTrace discovery = InteractionToolTrace.builder()
            .success(true)
            .output("""
                {"templates":[{"templateId":"CHECK_HOST","parameterContract":{"executionTool":"linux_command_execute"}}]}
                """)
            .build();

        Map<String, Object> result = resolver.applyObservedTemplateContract(
            "mcp_chatchat_mcp_server_sql_query_execute",
            Map.of("purpose", "分析数据库状态"),
            List.of(discovery)
        );

        assertThat(result).doesNotContainKeys("template", "templateId");
    }

    @Test
    void compilesApiGatewayTemplateIntoTemplateIdField() {
        InteractionToolTrace discovery = InteractionToolTrace.builder()
            .success(true)
            .toolName("mcp_chatchat_mcp_server_api_template_query")
            .output("""
                {
                  "templates":[{
                    "templateId":"customer_profile_query",
                    "parameterContract":{"executionTool":"api_template_execute"},
                    "parameterSchema":{
                      "type":"object",
                      "properties":{"customerId":{"type":"string"}},
                      "required":["customerId"]
                    }
                  }]
                }
                """)
            .build();

        Map<String, Object> result = resolver.applyObservedTemplateContract(
            "mcp_chatchat_mcp_server_api_template_execute",
            Map.of("parameters", Map.of("customerId", "C-1001")),
            List.of(discovery)
        );

        assertThat(result)
            .containsEntry("templateId", "customer_profile_query")
            .containsEntry("parameters", Map.of())
            .containsEntry("__runtimeParamBindingStatus", "DENIED")
            .containsEntry("__runtimeParamBindingCode", "INVALID_TOOL_ARGUMENTS")
            .doesNotContainKey("template");
    }

    @Test
    void rejectsObservedTemplateWhenRequiredBusinessParameterIsMissing() {
        InteractionToolTrace discovery = InteractionToolTrace.builder()
            .success(true)
            .output("""
                {
                  "templates":[{
                    "templateId":"TABLE_DETAIL",
                    "parameterContract":{"executionTool":"sql_query_execute"},
                    "parameterSchema":{
                      "type":"object",
                      "properties":{"tableName":{"type":"string"}},
                      "required":["tableName"]
                    }
                  }]
                }
                """)
            .build();

        Map<String, Object> result = resolver.applyObservedTemplateContract(
            "mcp_chatchat_mcp_server_sql_query_execute",
            Map.of("parameters", Map.of()),
            List.of(discovery)
        );

        assertThat(result)
            .containsEntry("template", "TABLE_DETAIL")
            .containsEntry("__runtimeParamBindingStatus", "DENIED")
            .containsEntry("__runtimeParamBindingCode", "INVALID_TOOL_ARGUMENTS");
        assertThat(result.get("__runtimeParamBindingError").toString())
            .contains("TEMPLATE_PARAMETER_PROTOCOL_REQUIRED")
            .contains("tableName");
        assertThat(result.get("templateResolutionEvent").toString())
            .contains("TEMPLATE_PARAMETERS_MISSING", "tableName", "REQUEST_PARAMETERS");
    }

    @Test
    void consumesCanonicalSearchResultWithoutDependingOnPublisherShape() {
        InteractionToolTrace discovery = InteractionToolTrace.builder()
            .success(true)
            .toolName("mcp_vendor_template_query")
            .output("""
                {"schemaVersion":"knowledge_search_result.v1","searchResult":{
                  "status":"FOUND","hits":[{"rank":1,"score":1.0,"document":{
                    "schemaVersion":"template_knowledge.v1","templateId":"STANDARD_HEALTH_CHECK",
                    "executorTool":"sql_query_execute","parameterSchema":{
                      "type":"object","properties":{},"required":[]}}}]}}
                """)
            .build();

        Map<String, Object> result = resolver.applyObservedTemplateContract(
            "mcp_vendor_sql_query_execute", Map.of("parameters", Map.of()), List.of(discovery));

        assertThat(result)
            .containsEntry("template", "STANDARD_HEALTH_CHECK")
            .containsEntry("parameters", Map.of())
            .doesNotContainKeys("__runtimeParamBindingStatus", "__runtimeParamBindingError");
    }

    @Test
    void executesOnlyReviewedNewlyRegisteredNoParameterTemplate() {
        String dynamicTemplateId = "tenant_market_snapshot_" + System.nanoTime();
        InteractionToolTrace discovery = InteractionToolTrace.builder()
            .toolName("mcp_vendor_business_template_query")
            .success(true)
            .output("""
                {"runtimeTemplateSelection":{"selectedTemplateIds":["%s"]},"templates":[
                  {"templateId":"requires_security_code",
                   "parameterContract":{"executionTool":"sql_query_execute"},
                   "parameterSchema":{"type":"object","properties":{"security_code":{"type":"string"}},
                                      "required":["security_code"]}},
                  {"templateId":"%s",
                   "parameterContract":{"executionTool":"sql_query_execute"},
                   "parameterSchema":{"type":"object","properties":{},"required":[]}}
                ]}
                """.formatted(dynamicTemplateId, dynamicTemplateId))
            .build();

        Map<String, Object> result = resolver.applyObservedTemplateContract(
            "mcp_vendor_sql_query_execute",
            Map.of("purpose", "analyze latest tenant market snapshot"),
            List.of(discovery)
        );

        assertThat(result)
            .containsEntry("template", dynamicTemplateId)
            .containsEntry("parameters", Map.of())
            .doesNotContainKeys("__runtimeParamBindingStatus", "__runtimeParamBindingError");
    }

    @Test
    void survivesLargePoisonedDiscoverySetAndSelectsOnlyExecutableNewTemplate() throws Exception {
        List<Map<String, Object>> templates = new ArrayList<>();
        for (int index = 0; index < 256; index++) {
            String required = "unavailable_parameter_" + index;
            templates.add(Map.of(
                "templateId", "incompatible_candidate_" + index,
                "parameterContract", Map.of("executionTool", "sql_query_execute"),
                "parameterSchema", Map.of(
                    "type", "object",
                    "properties", Map.of(required, Map.of("type", "string")),
                    "required", List.of(required)
                )
            ));
        }
        String dynamicTemplateId = "tenant_extreme_snapshot_" + System.nanoTime();
        templates.add(Map.of(
            "templateId", dynamicTemplateId,
            "parameterContract", Map.of("executionTool", "sql_query_execute"),
            "parameterSchema", Map.of("type", "object", "properties", Map.of(), "required", List.of())
        ));
        Map<String, Object> discoveryPayload = new LinkedHashMap<>();
        discoveryPayload.put("returnedCount", templates.size());
        discoveryPayload.put("templates", templates);
        discoveryPayload.put("runtimeTemplateSelection",
            Map.of("selectedTemplateIds", List.of(dynamicTemplateId)));
        InteractionToolTrace discovery = InteractionToolTrace.builder()
            .toolName("mcp_extreme_tenant_capability_query")
            .success(true)
            .output(new ObjectMapper().writeValueAsString(discoveryPayload))
            .build();

        Map<String, Object> result = resolver.applyObservedTemplateContract(
            "mcp_extreme_tenant_sql_query_execute",
            Map.of("purpose", "execute the only contract-compatible newly registered template"),
            List.of(discovery)
        );

        assertThat(result)
            .containsEntry("template", dynamicTemplateId)
            .containsEntry("parameters", Map.of())
            .doesNotContainKeys("__runtimeParamBindingStatus", "__runtimeParamBindingError");
    }

    @Test
    void compilesEveryReviewedBatchChildWithoutTemplateNameKnowledge() {
        String firstId = "tenant_created_snapshot_a_" + System.nanoTime();
        String secondId = "tenant_created_snapshot_b_" + System.nanoTime();
        InteractionToolTrace discovery = InteractionToolTrace.builder()
            .toolName("mcp_vendor_business_template_query")
            .success(true)
            .output("""
                {"runtimeTemplateSelection":{"selectedTemplateIds":["%s","%s"]},"templates":[
                  {"templateId":"%s","parameterContract":{"executionTool":"sql_query_execute"},
                   "parameterSchema":{"type":"object","properties":{},"required":[]}},
                  {"templateId":"%s","parameterContract":{"executionTool":"sql_query_execute"},
                   "parameterSchema":{"type":"object","properties":{},"required":[]}}
                ]}
                """.formatted(firstId, secondId, firstId, secondId))
            .build();
        Map<String, Object> batch = Map.of(
            "purpose", "run newly registered tenant templates",
            "calls", List.of(
                Map.of("callId", "a", "toolName", "sql_query_execute",
                    "arguments", Map.of("templateId", firstId, "parameters", Map.of())),
                Map.of("callId", "b", "toolName", "sql_query_execute",
                    "arguments", Map.of("templateId", secondId, "parameters", Map.of()))
            )
        );

        Map<String, Object> result = resolver.applyObservedTemplateContract(
            "mcp_vendor_sql_query_execute", batch, List.of(discovery));

        assertThat(result).doesNotContainKeys("__runtimeParamBindingStatus", "__runtimeParamBindingError");
        assertThat(result.get("calls")).isInstanceOfSatisfying(List.class, calls -> {
            assertThat(calls).hasSize(2);
            assertThat(calls.toString()).contains(firstId, secondId);
        });
    }

    @Test
    void acceptsEvidenceProtocolForNewRequiredParameterTemplate() {
        String dynamicTemplateId = "tenant_security_history_" + System.nanoTime();
        String query = "analyze security 600001 latest history";
        InteractionToolTrace discovery = InteractionToolTrace.builder()
            .toolName("mcp_vendor_business_template_query")
            .success(true)
            .output("""
                {"templates":[{"templateId":"%s",
                  "parameterContract":{"executionTool":"sql_query_execute"},
                  "parameterSchema":{"type":"object","properties":{"security_code":{"type":"string"}},
                                     "required":["security_code"]}}]}
                """.formatted(dynamicTemplateId))
            .build();
        Map<String, Object> protocol = Map.of(
            "protocol_version", TemplateInvocationBridge.PROTOCOL_VERSION,
            "template_id", dynamicTemplateId,
            "arguments", Map.of("security_code", Map.of(
                "value", "600001", "source", "user_query", "evidence", "security 600001")),
            "unresolved_parameters", List.of()
        );

        Map<String, Object> result = resolver.applyObservedTemplateContract(
            "mcp_vendor_sql_query_execute",
            Map.of("template", dynamicTemplateId, "purpose", query, "parameterProtocol", protocol),
            List.of(discovery),
            query
        );

        assertThat(result)
            .containsEntry("template", dynamicTemplateId)
            .doesNotContainKeys("__runtimeParamBindingStatus", "__runtimeParamBindingError");
        assertThat(result.get("parameters"))
            .isInstanceOfSatisfying(Map.class, parameters ->
                assertThat(parameters).containsEntry("security_code", "600001"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void compilesSqlInvocationFromStructuredProjectionInsideTruncatedTraceSummary() {
        String templateId = "tenant_etf_snapshot_" + System.nanoTime();
        InteractionToolTrace discovery = InteractionToolTrace.builder()
            .toolName("mcp_vendor_database_query_template_query")
            .success(true)
            .output("""
                {
                  "schemaVersion":"tool_result_summary.v1",
                  "summaryTruncated":true,
                  "preview":"human-readable preview is intentionally not parsed",
                  "routingProjection":{
                    "sourceSchemaVersion":"template_query_result.v1",
                    "templates":[{
                      "templateId":"%s",
                      "parameterSchema":{"type":"object","properties":{},"required":[]},
                      "sqlExecutionBinding":{
                        "toolName":"sql_query_execute",
                        "templateId":"%s",
                        "executionContext":{"assetName":"tenant-market-runtime","env":"DEV"}
                      }
                    }]
                  }
                }
                """.formatted(templateId, templateId))
            .build();

        Map<String, Object> result = resolver.applyObservedTemplateContract(
            "mcp_vendor_sql_query_execute",
            Map.of("purpose", "analyze latest tenant ETF observations"),
            List.of(discovery));

        assertThat(result)
            .containsEntry("template", templateId)
            .containsEntry("parameters", Map.of())
            .doesNotContainKeys("__runtimeParamBindingStatus", "__runtimeParamBindingError");
        assertThat((Map<String, Object>) result.get("executionContext"))
            .containsEntry("assetName", "tenant-market-runtime")
            .containsEntry("env", "DEV");
    }

    @Test
    @SuppressWarnings("unchecked")
    void compilesSshInvocationWithSelectedAssetInsideTruncatedTraceProjection() {
        InteractionToolTrace discovery = InteractionToolTrace.builder()
            .toolName("mcp_vendor_ssh_template_query")
            .success(true)
            .output("""
                {
                  "schemaVersion":"tool_result_summary.v1",
                  "summaryTruncated":true,
                  "routingProjection":{
                    "queryIr":{"asset":{"selected":{"name":"runtime-host-7","environment":"PROD"}}},
                    "templates":[{
                      "templateId":"CHECK_RUNTIME_LOAD",
                      "parameterSchema":{"type":"object","properties":{},"required":[]},
                      "parameterContract":{"executionTool":"linux_command_execute"}
                    }]
                  }
                }
                """)
            .build();

        Map<String, Object> result = resolver.applyObservedTemplateContract(
            "mcp_vendor_linux_command_execute",
            Map.of("purpose", "inspect runtime host load"),
            List.of(discovery));

        assertThat(result)
            .containsEntry("template", "CHECK_RUNTIME_LOAD")
            .containsEntry("parameters", Map.of())
            .doesNotContainKeys("__runtimeParamBindingStatus", "__runtimeParamBindingError");
        assertThat((Map<String, Object>) result.get("executionContext"))
            .containsEntry("assetName", "runtime-host-7")
            .containsEntry("env", "PROD");
    }

    @Test
    void compilesApiInvocationInsideTruncatedTraceProjection() {
        InteractionToolTrace discovery = InteractionToolTrace.builder()
            .toolName("mcp_vendor_api_template_query")
            .success(true)
            .output("""
                {
                  "schemaVersion":"tool_result_summary.v1",
                  "summaryTruncated":true,
                  "routingProjection":{"templates":[{
                    "templateId":"tenant_order_status",
                    "parameterSchema":{"type":"object","properties":{},"required":[]},
                    "parameterContract":{"executionTool":"api_template_execute"}
                  }]}
                }
                """)
            .build();

        Map<String, Object> result = resolver.applyObservedTemplateContract(
            "mcp_vendor_api_template_execute",
            Map.of("purpose", "query order service status"),
            List.of(discovery));

        assertThat(result)
            .containsEntry("templateId", "tenant_order_status")
            .containsEntry("parameters", Map.of())
            .doesNotContainKeys("template", "__runtimeParamBindingStatus", "__runtimeParamBindingError");
    }

    @Test
    void mandatoryRecoveryCompilesTemplateBeforeRequiredInputPreflight() {
        InteractionToolTrace discovery = InteractionToolTrace.builder()
            .toolName("mcp_runtime_api_template_query")
            .success(true)
            .output("""
                {
                  "schemaVersion":"tool_result_summary.v1",
                  "summaryTruncated":true,
                  "routingProjection":{"templates":[{
                    "templateId":"runtime_customer_snapshot",
                    "parameterSchema":{"type":"object","properties":{},"required":[]},
                    "parameterContract":{"executionTool":"api_template_execute"}
                  }]}
                }
                """)
            .build();

        Map<String, Object> result = resolver.applyDeterministicDependencyContracts(
            "mcp_runtime_api_template_execute",
            Map.of("purpose", "run the configured customer workflow"),
            List.of(discovery),
            "run the configured customer workflow"
        );

        assertThat(result)
            .containsEntry("templateId", "runtime_customer_snapshot")
            .containsEntry("parameters", Map.of())
            .doesNotContainKeys("__runtimeParamBindingStatus", "__runtimeParamBindingError");
    }

    @Test
    void mandatoryRecoveryProjectsReviewedCandidateAndSchemaDrivenFileAlias() {
        String discoveryTool = "mcp_runtime_any_discovery";
        String executionTool = "mcp_runtime_python_template_execute";
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getWorkflowRole(discoveryTool)).thenReturn(ToolWorkflowRole.TEMPLATE_DISCOVERY);
        when(registry.getWorkflowRole(executionTool)).thenReturn(ToolWorkflowRole.TEMPLATE_EXECUTION);
        when(registry.getToolMetadata(executionTool)).thenReturn(ToolMetadata.builder()
            .id(executionTool)
            .parameters(List.of(ToolParameter.builder()
                .name("templateId").type("string").required(true).build()))
            .build());
        AgentToolArgumentResolver contractResolver =
            new AgentToolArgumentResolver(new AgentToolNameResolver(), 5, registry);
        InteractionToolTrace discovery = InteractionToolTrace.builder()
            .toolName(discoveryTool)
            .success(true)
            .output("""
                {
                  "executionTool":"python_template_execute",
                  "candidates":[{
                    "templateId":"template-log",
                    "parameterSchema":{
                      "type":"object",
                      "properties":{"source_file":{"type":"FILE"}},
                      "required":["source_file"],
                      "additionalProperties":false
                    }
                  }]
                }
                """)
            .build();

        Map<String, Object> result = contractResolver.applyDeterministicDependencyContracts(
            executionTool,
            Map.of(
                "templateId", "${step1.templates[0].templateId}",
                "parameters", Map.of("logFileName", "1787187818764.log")),
            List.of(discovery),
            "分析日志文件 1787187818764.log"
        );

        assertThat(result)
            .containsEntry("templateId", "template-log")
            .containsEntry("parameters", Map.of("source_file", "1787187818764.log"))
            .doesNotContainKeys("template", "__runtimeParamBindingStatus", "__runtimeParamBindingError");
    }

    @Test
    void mandatoryRecoveryUsesRuntimeDiscoveredFileBindingWithoutModelProtocol() {
        String discoveryTool = "mcp_runtime_python_analysis_query";
        String executionTool = "mcp_runtime_python_template_execute";
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getWorkflowRole(discoveryTool)).thenReturn(ToolWorkflowRole.TEMPLATE_DISCOVERY);
        when(registry.getWorkflowRole(executionTool)).thenReturn(ToolWorkflowRole.TEMPLATE_EXECUTION);
        when(registry.getToolMetadata(executionTool)).thenReturn(ToolMetadata.builder()
            .id(executionTool)
            .parameters(List.of(ToolParameter.builder()
                .name("templateId").type("string").required(true).build()))
            .build());
        AgentToolArgumentResolver contractResolver =
            new AgentToolArgumentResolver(new AgentToolNameResolver(), 5, registry);
        InteractionToolTrace discovery = InteractionToolTrace.builder()
            .toolName(discoveryTool)
            .success(true)
            .output("""
                {
                  "executionTool":"python_template_execute",
                  "candidates":[{
                    "templateId":"template-log",
                    "parameterSchema":{
                      "type":"object",
                      "properties":{"source_file":{"type":"FILE"},"limit":{"type":"integer","default":100}},
                      "required":["source_file"]
                    },
                    "executionArguments":{
                      "templateId":"template-log",
                      "parameters":{"source_file":"file-only"}
                    }
                  }]
                }
                """)
            .build();

        Map<String, Object> result = contractResolver.applyDeterministicDependencyContracts(
            executionTool,
            Map.of("purpose", "日志分析", "parameters", Map.of("source_file", "VALUE_REQUIRED_FROM_USER")),
            List.of(discovery),
            "帮我执行 log_analysis.py 分析日志文件内容并给出建议"
        );

        assertThat(result)
            .containsEntry("templateId", "template-log")
            .containsEntry("parameters", Map.of("source_file", "file-only", "limit", 100))
            .doesNotContainKeys("__runtimeParamBindingStatus", "__runtimeParamBindingError");
    }

    @Test
    void repeatedExecutionCompilationKeepsUserQueryBackedRequiredParameter() {
        String discoveryTool = "mcp_runtime_any_discovery";
        String executionTool = "mcp_runtime_python_template_execute";
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getWorkflowRole(discoveryTool)).thenReturn(ToolWorkflowRole.TEMPLATE_DISCOVERY);
        when(registry.getWorkflowRole(executionTool)).thenReturn(ToolWorkflowRole.TEMPLATE_EXECUTION);
        when(registry.getToolMetadata(executionTool)).thenReturn(ToolMetadata.builder()
            .id(executionTool)
            .parameters(List.of(ToolParameter.builder()
                .name("templateId").type("string").required(true).build()))
            .build());
        AgentToolArgumentResolver contractResolver =
            new AgentToolArgumentResolver(new AgentToolNameResolver(), 5, registry);
        InteractionToolTrace discovery = InteractionToolTrace.builder()
            .toolName(discoveryTool)
            .success(true)
            .output("""
                {
                  "executionTool":"python_template_execute",
                  "candidates":[{
                    "templateId":"template-log",
                    "parameterSchema":{
                      "type":"object",
                      "properties":{
                        "source_file":{"type":"FILE"},
                        "limit":{"type":"integer","default":100}
                      },
                      "required":["source_file"]
                    }
                  }]
                }
                """)
            .build();
        String userQuery = "帮我执行 log_analysis.py 程序 分析 1787187818764.log 日志文件";

        Map<String, Object> firstPass = contractResolver.applyDeterministicDependencyContracts(
            executionTool,
            Map.of("purpose", "log_analysis", "parameters",
                Map.of("source_file", "1787187818764.log")),
            List.of(discovery),
            userQuery
        );
        Map<String, Object> executionPass = contractResolver.applyObservedTemplateContract(
            executionTool, firstPass, List.of(discovery), userQuery);

        assertThat(firstPass)
            .containsEntry("parameters", Map.of("source_file", "1787187818764.log", "limit", 100));
        assertThat(executionPass)
            .containsEntry("templateId", "template-log")
            .containsEntry("parameters", Map.of("source_file", "1787187818764.log", "limit", 100))
            .doesNotContainKeys("__runtimeParamBindingStatus", "__runtimeParamBindingError");
    }

    @Test
    @SuppressWarnings("unchecked")
    void mandatoryRecoveryUsesPublishedTemplateExecutionRoleWhenOptionalMcpMetadataIsAbsent() {
        String discoveryTool = "mcp_runtime_dynamic_discovery";
        String executionTool = "mcp_runtime_dynamic_executor";
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getWorkflowRole(discoveryTool)).thenReturn(ToolWorkflowRole.TEMPLATE_DISCOVERY);
        when(registry.getWorkflowRole(executionTool)).thenReturn(ToolWorkflowRole.TEMPLATE_EXECUTION);
        when(registry.getToolMetadata(executionTool)).thenReturn(ToolMetadata.builder()
            .id(executionTool)
            .parameters(List.of(ToolParameter.builder()
                .name("executionContext").type("object").required(true).build()))
            .build());
        AgentToolArgumentResolver contractResolver =
            new AgentToolArgumentResolver(new AgentToolNameResolver(), 5, registry);
        InteractionToolTrace discovery = InteractionToolTrace.builder()
            .toolName(discoveryTool)
            .success(true)
            .output("""
                {"queryIr":{"asset":{"selected":{"id":"asset-unrelated","name":"unrelated-first-candidate","environment":"PROD","toolName":"http_unrelated"}}},
                 "runtimeTemplateSelection":{"selectedTemplateIds":["dynamic-a","dynamic-b"]},
                 "candidates":[
                  {"templateId":"dynamic-a","parameterContract":{"executionTool":"dynamic_executor"},
                   "parameterSchema":{"type":"object","properties":{},"required":[]},
                   "executionBinding":{"toolName":"dynamic_executor","executionContext":{"assetId":"id-a","assetName":"asset-a","env":"DEV"}}},
                  {"templateId":"dynamic-b","parameterContract":{"executionTool":"dynamic_executor"},
                   "parameterSchema":{"type":"object","properties":{},"required":[]},
                   "executionBinding":{"toolName":"dynamic_executor","executionContext":{"assetId":"id-b","assetName":"asset-b","env":"TEST"}}}
                ]}
                """)
            .build();

        Map<String, Object> result = contractResolver.applyDeterministicDependencyContracts(
            executionTool, Map.of(
                "template", "PENDING_DISCOVERY",
                "executionContext", Map.of("assetId", "asset-unrelated", "assetName", "unrelated-first-candidate"),
                "purpose", "execute admitted dynamic templates"),
            List.of(discovery), "execute admitted dynamic templates");

        assertThat(result.get("calls")).isInstanceOfSatisfying(List.class, calls -> {
            assertThat(calls).hasSize(2);
            Map<String, Object> first = (Map<String, Object>) calls.get(0);
            Map<String, Object> second = (Map<String, Object>) calls.get(1);
            Map<String, Object> firstArguments = (Map<String, Object>) first.get("arguments");
            Map<String, Object> secondArguments = (Map<String, Object>) second.get("arguments");
            assertThat(firstArguments.get("executionContext"))
                .isEqualTo(Map.of("assetId", "id-a", "assetName", "asset-a", "env", "DEV"));
            assertThat(secondArguments.get("executionContext"))
                .isEqualTo(Map.of("assetId", "id-b", "assetName", "asset-b", "env", "TEST"));
            assertThat(calls.toString()).doesNotContain("PENDING_DISCOVERY", "unrelated-first-candidate");
        });
        assertThat(result).doesNotContainKeys("__runtimeParamBindingStatus", "__runtimeParamBindingError");
    }

    @Test
    void mandatoryRecoveryCompilesOnlyReviewedAdmittedTemplatesIntoBatch() {
        String firstId = "runtime_health_a_" + System.nanoTime();
        String secondId = "runtime_health_b_" + System.nanoTime();
        InteractionToolTrace discovery = InteractionToolTrace.builder()
            .toolName("mcp_runtime_database_template_query")
            .success(true)
            .output("""
                {"runtimeTemplateSelection":{"selectedTemplateIds":["%s","%s"]},"templates":[
                  {"templateId":"%s","parameterContract":{"executionTool":"sql_query_execute"},
                   "parameterSchema":{"type":"object","properties":{},"required":[]}},
                  {"templateId":"%s","parameterContract":{"executionTool":"sql_query_execute"},
                   "parameterSchema":{"type":"object","properties":{},"required":[]}},
                  {"templateId":"runtime_script","parameterContract":{"executionTool":"sql_script_execute"},
                   "parameterSchema":{"type":"object","properties":{},"required":[]}}
                ]}
                """.formatted(firstId, secondId, firstId, secondId))
            .build();

        Map<String, Object> result = resolver.applyDeterministicDependencyContracts(
            "mcp_runtime_sql_query_execute",
            Map.of("purpose", "run the admitted health checks"),
            List.of(discovery),
            "run the admitted health checks"
        );

        assertThat(result)
            .containsEntry("executionMode", "SEQUENTIAL")
            .containsEntry("stopOnFailure", false)
            .doesNotContainKeys("template", "templateId", "__runtimeParamBindingStatus");
        assertThat(result.get("calls")).isInstanceOfSatisfying(List.class, calls -> {
            assertThat(calls).hasSize(2);
            assertThat(calls.toString()).contains(firstId, secondId).doesNotContain("runtime_script");
        });
    }

    @Test
    void mandatoryRecoveryDoesNotExecuteAnUnreviewedBusinessGroupWholesale() {
        String firstId = "unreviewed_scale_" + System.nanoTime();
        String secondId = "unreviewed_margin_" + System.nanoTime();
        InteractionToolTrace discovery = InteractionToolTrace.builder()
            .toolName("mcp_runtime_business_template_query")
            .success(true)
            .output("""
                {"templates":[
                  {"templateId":"%s","parameterContract":{"executionTool":"sql_query_execute"},
                   "parameterSchema":{"type":"object","properties":{},"required":[]}},
                  {"templateId":"%s","parameterContract":{"executionTool":"sql_query_execute"},
                   "parameterSchema":{"type":"object","properties":{},"required":[]}}
                ]}
                """.formatted(firstId, secondId))
            .build();

        Map<String, Object> result = resolver.applyDeterministicDependencyContracts(
            "mcp_runtime_sql_query_execute", Map.of("purpose", "分析ETF资金流向"),
            List.of(discovery), "分析ETF资金流向");

        assertThat(result).doesNotContainKey("calls");
        assertThat(result).containsEntry("__runtimeParamBindingStatus", "DENIED");
    }

    @Test
    void bindsUniqueObservedTemplateForAnyConventionalTemplateExecutorWhenBatchChildOmitsId() {
        InteractionToolTrace discovery = InteractionToolTrace.builder()
            .toolName("mcp_vendor_runtime_capability_query")
            .success(true)
            .output("""
                {
                  "executionTool":"custom_template_execute",
                  "candidates":[{
                    "templateId":"template-log",
                    "parameterSchema":{"type":"object","properties":{},"required":[]}
                  }]
                }
                """)
            .build();
        Map<String, Object> batch = Map.of(
            "calls", List.of(Map.of(
                "callId", "log-analysis",
                "toolName", "custom_template_execute",
                "arguments", Map.of("parameters", Map.of())
            ))
        );

        Map<String, Object> result = resolver.applyObservedTemplateContract(
            "mcp_vendor_custom_template_execute", batch, List.of(discovery));

        assertThat(result).doesNotContainKeys("__runtimeParamBindingStatus", "__runtimeParamBindingError");
        assertThat(result.get("calls")).isInstanceOfSatisfying(List.class, calls ->
            assertThat(calls.toString()).contains("templateId=template-log"));
    }

    @Test
    void keepsGenericBatchFailClosedWhenTemplateIdIsMissingAndDiscoveryHasMultipleCandidates() {
        InteractionToolTrace discovery = InteractionToolTrace.builder()
            .toolName("mcp_vendor_runtime_capability_query")
            .success(true)
            .output("""
                {
                  "executionTool":"custom_template_execute",
                  "runtimeTemplateSelection":{"selectedTemplateIds":["template-a","template-b"]},
                  "candidates":[
                    {"templateId":"template-a","parameterSchema":{"type":"object","properties":{},"required":[]}},
                    {"templateId":"template-b","parameterSchema":{"type":"object","properties":{},"required":[]}}
                  ]
                }
                """)
            .build();
        Map<String, Object> batch = Map.of(
            "calls", List.of(Map.of(
                "callId", "ambiguous",
                "toolName", "custom_template_execute",
                "arguments", Map.of("parameters", Map.of())
            ))
        );

        Map<String, Object> result = resolver.applyObservedTemplateContract(
            "mcp_vendor_custom_template_execute", batch, List.of(discovery));

        assertThat(result)
            .containsEntry("__runtimeParamBindingStatus", "DENIED")
            .containsEntry("__runtimeParamBindingCode", "INVALID_TOOL_ARGUMENTS");
        assertThat(result.get("__runtimeParamBindingError").toString())
            .contains("template null was not returned");
    }

    @Test
    @SuppressWarnings("unchecked")
    void mandatoryRecoveryResolvesJsonPathTemplateAndCarriesCanonicalAssetIdentity() {
        String discoveryTool = "mcp_runtime_server_capability_query";
        String executionTool = "mcp_runtime_linux_command_execute";
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getWorkflowRole(discoveryTool)).thenReturn(ToolWorkflowRole.TEMPLATE_DISCOVERY);
        when(registry.getWorkflowRole(executionTool)).thenReturn(ToolWorkflowRole.TEMPLATE_EXECUTION);
        when(registry.getToolMetadata(executionTool)).thenReturn(ToolMetadata.builder()
            .id(executionTool)
            .parameters(List.of(
                ToolParameter.builder().name("template").type("string").required(true).build(),
                ToolParameter.builder().name("executionContext").type("object").required(true).build()))
            .build());
        AgentToolArgumentResolver contractResolver =
            new AgentToolArgumentResolver(new AgentToolNameResolver(), 5, registry);
        InteractionToolTrace discovery = InteractionToolTrace.builder()
            .toolName(discoveryTool)
            .success(true)
            .output("""
                {
                  "routingProjection": {
                    "runtimeTemplateSelection":{"selectedTemplateIds":["CHECK_IMAGES","CHECK_CONTAINERS"]},
                    "queryIr": {"asset":{"selected":{
                      "id":"asset-docker-1",
                      "name":"Docker database host",
                      "environment":"DEV",
                      "toolName":"ssh_container_service"
                    }}},
                    "templates":[
                      {"templateId":"CHECK_IMAGES","parameterContract":{"executionTool":"linux_command_execute"},
                       "parameterSchema":{"type":"object","properties":{},"required":[],"additionalProperties":false}},
                      {"templateId":"CHECK_CONTAINERS","parameterContract":{"executionTool":"linux_command_execute"},
                       "parameterSchema":{"type":"object","properties":{},"required":[],"additionalProperties":false}}
                    ]
                  }
                }
                """)
            .build();

        Map<String, Object> result = contractResolver.applyDeterministicDependencyContracts(
            executionTool,
            Map.of(
                "template", "$.templates[0].templateId",
                "executionContext", Map.of("env", "DEV", "targetType", "host"),
                "parameters", Map.of("command", "docker images")),
            List.of(discovery),
            "inspect the discovered host"
        );

        assertThat(result).containsEntry("executionMode", "SEQUENTIAL");
        assertThat(result.toString()).doesNotContain("$.templates", "docker images");
        assertThat(result.get("calls")).isInstanceOfSatisfying(List.class, calls -> {
            assertThat(calls).hasSize(2);
            for (Object rawCall : calls) {
                Map<String, Object> call = (Map<String, Object>) rawCall;
                Map<String, Object> arguments = (Map<String, Object>) call.get("arguments");
                assertThat(arguments.get("template").toString()).isIn("CHECK_IMAGES", "CHECK_CONTAINERS");
                assertThat(arguments.get("parameters")).isEqualTo(Map.of());
                assertThat(arguments.get("executionContext")).isEqualTo(Map.of(
                    "env", "DEV",
                    "targetType", "host",
                    "assetId", "asset-docker-1",
                    "assetName", "Docker database host",
                    "assetToolName", "ssh_container_service"));
            }
        });
    }

    @Test
    void mandatoryRecoveryRejectsExecutorWithoutCompatibleDiscoveredTemplate() {
        InteractionToolTrace discovery = InteractionToolTrace.builder()
            .toolName("mcp_runtime_database_template_search")
            .success(true)
            .output("""
                {"templates":[{
                  "templateId":"runtime_scalar_query",
                  "parameterContract":{"executionTool":"sql_query_execute"},
                  "parameterSchema":{"type":"object","properties":{},"required":[]}
                }]}
                """)
            .build();

        Map<String, Object> result = resolver.applyDeterministicDependencyContracts(
            "mcp_runtime_sql_script_execute",
            Map.of("purpose", "run the configured workflow"),
            List.of(discovery),
            "run the configured workflow"
        );

        assertThat(result)
            .containsEntry("__runtimeParamBindingStatus", "DENIED")
            .containsEntry("__runtimeParamBindingCode", "NO_COMPATIBLE_TEMPLATE_EXECUTOR");
        assertThat(result.get("__runtimeParamBindingError").toString())
            .contains("sql_script_execute", "sql_query_execute");
    }

    @Test
    void mandatoryRecoveryDoesNotApplyTemplateContractToIndependentMetadataTool() {
        String metadataTool = "mcp_runtime_sql_metadata_search";
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getToolMetadata(metadataTool)).thenReturn(ToolMetadata.builder()
            .id(metadataTool)
            .metadata(Map.of("mcpToolMeta", Map.of(
                "doesNotExecuteSql", true,
                "applicability", Map.of("scopeLabel", "sql_datasource:schema_discovery")
            )))
            .build());
        AgentToolArgumentResolver contractResolver =
            new AgentToolArgumentResolver(new AgentToolNameResolver(), 5, registry);
        InteractionToolTrace discovery = InteractionToolTrace.builder()
            .toolName("mcp_runtime_database_template_search")
            .success(true)
            .output("""
                {"templates":[{
                  "templateId":"ORACLE_SESSION_OVERVIEW",
                  "parameterContract":{"executionTool":"sql_query_execute"},
                  "parameterSchema":{"type":"object","properties":{},"required":[]}
                }]}
                """)
            .build();

        Map<String, Object> result = contractResolver.applyDeterministicDependencyContracts(
            metadataTool,
            Map.of("query", "Oracle risk-control schema"),
            List.of(discovery),
            "inspect Oracle risk-control metadata"
        );

        assertThat(result)
            .containsEntry("query", "Oracle risk-control schema")
            .doesNotContainKeys("__runtimeParamBindingStatus", "__runtimeParamBindingCode",
                "__runtimeParamBindingError", "template", "calls");
    }


    @Test
    void notificationToolBindsMessageDefaultsFromUserQuery() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        String toolName = "mcp_chatchat_mcp_server_notify_ops";
        when(toolRegistry.getToolMetadata(toolName)).thenReturn(ToolMetadata.builder()
            .category("notification")
            .operationType("notify")
            .metadata(Map.of("notificationTool", true))
            .build());
        AgentToolArgumentResolver notificationResolver = new AgentToolArgumentResolver(
            new AgentToolNameResolver(),
            5,
            toolRegistry
        );

        Map<String, Object> result = notificationResolver.applyToolDefaults(
            toolName,
            Map.of("query", "行情数据发生较大波动，请发送告警"),
            List.of(),
            List.of(),
            "行情数据发生较大波动，请发送告警",
            5
        );

        assertThat(result)
            .containsEntry("title", "Agent 告警通知")
            .containsEntry("content", "行情数据发生较大波动，请发送告警")
            .containsEntry("level", "WARNING")
            .doesNotContainKey("query");
    }

    private Map<String, Object> trace() {
        return Map.of("plannerVersion", "v1.0", "model", "unit-test");
    }
}
