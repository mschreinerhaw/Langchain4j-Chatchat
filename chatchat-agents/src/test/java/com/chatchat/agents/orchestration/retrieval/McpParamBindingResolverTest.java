package com.chatchat.agents.orchestration.retrieval;

import com.chatchat.agents.orchestration.retrieval.McpParamBindingResolver;

import com.chatchat.agents.protocol.AgentProtocolCatalog;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolWorkflowContract;
import com.chatchat.common.tool.ToolWorkflowRole;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpParamBindingResolverTest {

    private final McpParamBindingResolver resolver = new McpParamBindingResolver();

    @Test
    void missingDatabaseFieldPolicyDeniesContractBinding() {
        ToolMetadata metadata = ToolMetadata.builder()
            .id("opaque_discovery")
            .metadata(Map.of(ToolWorkflowContract.METADATA_KEY,
                ToolWorkflowContract.declaration(ToolWorkflowRole.TEMPLATE_DISCOVERY,
                    "mcp.template-discovery.v1", "filters")))
            .build();

        assertThat(resolver.resolve("opaque_discovery", metadata, Map.of("filters", Map.of()), "search"))
            .containsEntry(McpParamBindingResolver.STATUS_KEY, "DENIED");
    }

    @Test
    void publishedFieldPolicyCanAddNewConcreteTargetWithoutCodeChange() {
        ToolMetadata original = declaredDiscoveryMetadata(ToolWorkflowRole.TEMPLATE_DISCOVERY);
        Map<String, Object> policy = new java.util.LinkedHashMap<>(bindingPolicy());
        policy.put("concreteTargetFields", List.of("tenantPhysicalTarget"));
        Map<String, Object> extra = new java.util.LinkedHashMap<>(original.getMetadata());
        extra.put("argumentBindingPolicy", policy);
        ToolMetadata updated = ToolMetadata.builder().id("opaque_discovery").categories(List.of("mcp"))
            .metadata(extra).build();

        assertThat(resolver.resolve("opaque_discovery", updated,
            Map.of("tenantPhysicalTarget", "opaque-id", "filters", Map.of("intent", "search"),
                "finalDecision", "database", "confidence", 0.91), "search"))
            .containsEntry(McpParamBindingResolver.STATUS_KEY, "DENIED");
    }

    @Test
    void explicitDiscoveryRoleWinsOverExecutorProtocolFamily() {
        String toolName = "mcp_runtime_capability_bridge";
        ToolMetadata metadata = ToolMetadata.builder()
            .id(toolName)
            .categories(List.of("mcp"))
            .metadata(Map.of(
                "remoteToolName", "runtime_capability_bridge",
                "argumentBindingPolicy", bindingPolicy(),
                "inputSchema", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "query", Map.of("type", "string"),
                        "filters", Map.of("type", "object"),
                        "limit", Map.of("type", "integer")),
                    "required", List.of()),
                "mcpToolMeta", Map.of(
                    "workflowContract", ToolWorkflowContract.declaration(
                        ToolWorkflowRole.TEMPLATE_DISCOVERY,
                        "mcp.ssh-template.v1",
                        "intent+filters"))))
            .build();

        Map<String, Object> result = resolver.resolve(
            toolName,
            metadata,
            Map.of("query", "inspect the named logical runtime target"),
            "inspect the named logical runtime target");

        assertThat(result)
            .doesNotContainKeys("reason", "template", "parameters")
            .containsKey("filters");
        assertThat(result.get("filters")).isInstanceOfSatisfying(Map.class, filters ->
            assertThat(filters).containsEntry("intent", "inspect the named logical runtime target"));
    }

    @Test
    void createsReplayableRuntimeTraceForAssetDiscoveryWithExplicitFilters() {
        Map<String, Object> result = resolver.resolve(
            "mcp_chatchat_mcp_server_database_asset_search",
            publishedDiscoveryMetadata(ToolWorkflowRole.ASSET_DISCOVERY,
                "database_asset_search",
                List.of("filters", "trace", "candidates", "finalDecision", "confidence", "assetType", "targetKind"),
                List.of("filters"),
                Map.of("forcedTargetKind", "database", "forcedAssetType", "sql_datasource")),
            Map.of(
                "filters", Map.of("env", "DEV", "databaseType", "oracle"),
                "finalDecision", "database",
                "confidence", 0.95
            ),
            "分析 DEV 环境 Oracle 数据库健康状态"
        );

        assertThat(result).doesNotContainKey(McpParamBindingResolver.STATUS_KEY);
        assertThat(result.get("trace")).isInstanceOfSatisfying(Map.class, trace -> assertThat(trace)
            .containsEntry("schemaVersion", AgentProtocolCatalog.ROUTING_TRACE)
            .containsEntry("source", "agent_runtime_param_binding")
            .containsEntry("finalDecision", "database")
            .containsEntry("decisionSource", "published_tool_contract"));
    }

    @Test
    void preservesPlannerRoutingTraceInsteadOfReplacingIt() {
        Map<String, Object> plannerTrace = Map.of("taskId", "task-1", "promptVersion", "v3");

        Map<String, Object> result = resolver.resolve(
            "mcp_chatchat_mcp_server_database_ops_template_search",
            declaredDiscoveryMetadata(ToolWorkflowRole.TEMPLATE_DISCOVERY),
            Map.of(
                "filters", Map.of("env", "DEV", "intent", "Oracle health"),
                "finalDecision", "database",
                "confidence", 0.95,
                "trace", plannerTrace
            ),
            "分析 DEV 环境 Oracle 数据库健康状态"
        );

        assertThat(result).doesNotContainKey(McpParamBindingResolver.STATUS_KEY);
        assertThat(result.get("trace")).isEqualTo(plannerTrace);
    }

    @Test
    void usesPublishedJmxContractWithoutRequiringModelRoutingFields() {
        Map<String, Object> result = resolver.resolve(
            "mcp_chatchat_mcp_server_jmx_template_query",
            publishedDiscoveryMetadata(
                ToolWorkflowRole.TEMPLATE_DISCOVERY,
                "jmx_template_query",
                List.of("filters", "filtersSchemaVersion", "trace", "candidates", "finalDecision",
                    "confidence", "assetType", "targetKind", "limit"),
                List.of("filters", "trace"),
                Map.of("forcedTargetKind", "java", "forcedAssetType", "jmx_endpoint")),
            Map.of("filters", Map.of("env", "DEV", "intent", "JVM health")),
            "检查 DEV JVM 健康状态"
        );

        assertThat(result)
            .doesNotContainKey(McpParamBindingResolver.STATUS_KEY)
            .containsEntry("targetKind", "java")
            .containsEntry("finalDecision", "java")
            .containsEntry("assetType", "jmx_endpoint");
        assertThat(result.get("trace")).isInstanceOf(Map.class);
    }

    @Test
    void usesPublishedMicroserviceContractWithoutNameBasedTargetInference() {
        Map<String, Object> result = resolver.resolve(
            "mcp_chatchat_mcp_server_microservice_asset_query",
            publishedDiscoveryMetadata(
                ToolWorkflowRole.ASSET_DISCOVERY,
                "microservice_asset_query",
                List.of("filters", "filtersSchemaVersion", "trace", "candidates", "finalDecision",
                    "confidence", "assetType", "targetKind", "limit"),
                List.of("filters", "trace"),
                Map.of("forcedTargetKind", "http", "forcedAssetType", "http_endpoint")),
            Map.of("filters", Map.of("env", "DEV", "service", "risk-gateway")),
            "查找 DEV 风控网关"
        );

        assertThat(result)
            .doesNotContainKey(McpParamBindingResolver.STATUS_KEY)
            .containsEntry("targetKind", "http")
            .containsEntry("assetType", "http_endpoint");
    }

    @Test
    void keepsApiDiscoveryInsideItsPublishedApiServiceSchema() {
        Map<String, Object> result = resolver.resolve(
            "mcp_chatchat_mcp_server_api_asset_query",
            publishedDiscoveryMetadata(
                ToolWorkflowRole.ASSET_DISCOVERY,
                "api_asset_query",
                List.of("filters", "filtersSchemaVersion", "limit"),
                List.of("filters"),
                Map.of("forcedTargetKind", "api_service", "forcedAssetType", "api_service")),
            Map.of(
                "filters", Map.of("businessGroup", "risk"),
                "finalDecision", "database",
                "confidence", 0.88,
                "candidates", List.of(Map.of("targetKind", "database", "confidence", 0.88))
            ),
            "查找风控 API"
        );

        assertThat(result)
            .doesNotContainKey(McpParamBindingResolver.STATUS_KEY)
            .containsKeys("filters", "filtersSchemaVersion", "limit")
            .doesNotContainKeys("targetKind", "finalDecision", "assetType", "confidence", "candidates", "trace");
    }

    @Test
    void letsDynamicTemplatePublicationOwnItsServerManagedScope() {
        Map<String, Object> result = resolver.resolve(
            "mcp_chatchat_mcp_server_tenant_authorized_template_query",
            publishedDiscoveryMetadata(
                ToolWorkflowRole.TEMPLATE_DISCOVERY,
                "tenant_authorized_template_query",
                List.of("assetType", "filters", "trace", "limit"),
                List.of(),
                Map.of()),
            Map.of("query", "customer snapshot"),
            "查询客户快照"
        );

        assertThat(result)
            .doesNotContainKey(McpParamBindingResolver.STATUS_KEY)
            .containsKeys("filters", "trace", "limit")
            .doesNotContainKeys("targetKind", "finalDecision", "confidence", "candidates");
        assertThat((Map<String, Object>) result.get("filters")).containsEntry("intent", "查询客户快照");
    }

    @Test
    void doesNotInferEnvironmentFromDatabaseAssetProperName() {
        Map<String, Object> result = resolver.resolve(
            "mcp_chatchat_mcp_server_sql_datasource_asset_query",
            declaredDiscoveryMetadata(ToolWorkflowRole.ASSET_DISCOVERY),
            Map.of(
                "finalDecision", "database",
                "confidence", 0.95,
                "filters", Map.of()
            ),
            "\u5206\u6790248\u6d4b\u8bd5\u6570\u636e\u5e93"
        );

        Map<?, ?> filters = (Map<?, ?>) result.get("filters");
        assertThat(filters.containsKey("env")).isFalse();
        assertThat(filters.get("queryTerms"))
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
            .contains("\u5206\u6790248\u6d4b\u8bd5\u6570\u636e\u5e93");
    }

    @Test
    void infersCanonicalEnvironmentOnlyFromExplicitEnvironmentExpression() {
        Map<String, Object> result = resolver.resolve(
            "mcp_chatchat_mcp_server_sql_datasource_asset_query",
            declaredDiscoveryMetadata(ToolWorkflowRole.ASSET_DISCOVERY),
            Map.of(
                "finalDecision", "database",
                "confidence", 0.95,
                "filters", Map.of()
            ),
            "\u5728TEST\u73af\u5883\u5206\u6790248\u6570\u636e\u5e93"
        );

        Map<?, ?> filters = (Map<?, ?>) result.get("filters");
        assertThat(filters.get("env")).isEqualTo("TEST");
    }

    @Test
    void removesProtocolFieldsFromTemplateDiscoveryFilters() {
        Map<String, Object> result = resolver.resolve(
            "mcp_chatchat_mcp_server_ssh_template_query",
            declaredDiscoveryMetadata(ToolWorkflowRole.TEMPLATE_DISCOVERY),
            Map.of(
                "candidates", List.of(Map.of("targetKind", "host", "confidence", 0.9)),
                "finalDecision", "host",
                "confidence", 0.9,
                "filters", Map.of(
                    "assetName", "TDH scheduler",
                    "env", "DEV",
                    "intent", "list java processes",
                    "trace", Map.of("plannerVersion", "v1.1"),
                    "finalDecision", "host",
                    "filtersSchemaVersion", "target_filters.v1"
                ),
                "trace", Map.of("plannerVersion", "v1.1")
            ),
            "list java processes"
        );

        Map<?, ?> filters = (Map<?, ?>) result.get("filters");
        assertThat(filters.get("assetName")).isEqualTo("TDH scheduler");
        assertThat(filters.get("env")).isEqualTo("DEV");
        assertThat(filters.get("intent")).isEqualTo("list java processes");
        assertThat(filters.containsKey("trace")).isFalse();
        assertThat(filters.containsKey("finalDecision")).isFalse();
        assertThat(filters.containsKey("filtersSchemaVersion")).isFalse();
        assertThat(result.get("trace")).isInstanceOf(Map.class);
    }

    @Test
    void preservesTemplateIntentForServerSideMetadataExpansion() {
        Map<String, Object> result = resolver.resolve(
            "mcp_chatchat_mcp_server_template_query",
            declaredDiscoveryMetadata(ToolWorkflowRole.TEMPLATE_DISCOVERY),
            Map.of(
                "finalDecision", "database",
                "confidence", 0.95,
                "filters", Map.of(
                    "assetName", "local_mysql",
                    "intent", "\u67e5\u8be2 user_info_file \u8868\u5143\u6570\u636e\u4fe1\u606f"
                )
            ),
            "\u67e5\u8be2 user_info_file \u8868\u5143\u6570\u636e\u4fe1\u606f"
        );

        Map<?, ?> filters = (Map<?, ?>) result.get("filters");
        assertThat(filters.get("intent")).isEqualTo("\u67e5\u8be2 user_info_file \u8868\u5143\u6570\u636e\u4fe1\u606f");
        assertThat(List.of("bilingualIntent", "intentAliases", "keywords", "intentZh", "intentEn")
            .stream().noneMatch(filters::containsKey)).isTrue();
    }

    @Test
    void preservesInnoDbIntentWithoutClientSideVocabulary() {
        Map<String, Object> result = resolver.resolve(
            "mcp_chatchat_mcp_server_template_query",
            declaredDiscoveryMetadata(ToolWorkflowRole.TEMPLATE_DISCOVERY),
            Map.of(
                "finalDecision", "database",
                "confidence", 0.95,
                "filters", Map.of(
                    "assetName", "local_mysql",
                    "intent", "\u5206\u6790InnoDB\u72b6\u6001\uff0c\u5305\u62ec\u9501\u7b49\u5f85\u3001\u6b7b\u9501\u548c\u7f13\u51b2\u6c60"
                )
            ),
            "\u5206\u6790InnoDB\u72b6\u6001\uff0c\u5305\u62ec\u9501\u7b49\u5f85\u3001\u6b7b\u9501\u548c\u7f13\u51b2\u6c60"
        );

        Map<?, ?> filters = (Map<?, ?>) result.get("filters");
        assertThat(filters.get("intent")).isEqualTo(
            "\u5206\u6790InnoDB\u72b6\u6001\uff0c\u5305\u62ec\u9501\u7b49\u5f85\u3001\u6b7b\u9501\u548c\u7f13\u51b2\u6c60");
        assertThat(List.of("bilingualIntent", "intentAliases", "keywords", "intentEn")
            .stream().noneMatch(filters::containsKey)).isTrue();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        "database_query_template_query", "trino_query_template_query",
        "neo4j_query_template_query", "opensearch_query_template_query",
        "elasticsearch_query_template_query"
    })
    void dedicatedBusinessQueryTemplateToolOverridesMismatchedPlannerTargetKind(String parentTool) {
        ToolMetadata metadata = publishedDiscoveryMetadata(
            ToolWorkflowRole.TEMPLATE_DISCOVERY, parentTool,
            List.of("filters", "trace", "candidates", "finalDecision", "confidence", "assetType", "targetKind", "limit"),
            List.of("filters"),
            Map.of("forcedTargetKind", "business_database_query", "forcedAssetType", "database_query"));
        Map<String, Object> result = resolver.resolve(
            "mcp_chatchat_mcp_server_" + parentTool,
            metadata,
            Map.of(
                "candidates", List.of(Map.of("targetKind", "database", "confidence", 0.9)),
                "finalDecision", "database",
                "filters", Map.of(
                    "intent", "\u5206\u6790\u884c\u60c5\u6570\u636e\u53d1\u751f\u8f83\u5927\u6ce2\u52a8\u65f6\u5f02\u5e38\u63d0\u9192\u6570\u636e"
                ),
                "trace", Map.of("plannerVersion", "v1.1")
            ),
            "\u5206\u6790\u884c\u60c5\u6570\u636e\u53d1\u751f\u8f83\u5927\u6ce2\u52a8\u65f6\u5f02\u5e38\u63d0\u9192\u6570\u636e"
        );

        assertThat(result)
            .containsEntry("targetKind", "business_database_query")
            .containsEntry("finalDecision", "business_database_query")
            .containsEntry("assetType", "database_query");
        assertThat((List<?>) result.get("candidates"))
            .singleElement()
            .satisfies(candidate -> {
                Map<?, ?> candidateMap = (Map<?, ?>) candidate;
                assertThat(candidateMap.get("targetKind")).isEqualTo("business_database_query");
                assertThat(candidateMap.get("confidence")).isEqualTo(0.9);
            });
    }

    @Test
    void publishedTargetMappingSupportsNewDomainWithoutToolNameBranch() {
        ToolMetadata metadata = publishedDiscoveryMetadata(
            ToolWorkflowRole.TEMPLATE_DISCOVERY, "opaque_discovery_capability",
            List.of("filters", "trace", "candidates", "finalDecision", "confidence", "assetType", "targetKind"),
            List.of("filters"),
            Map.of("targetKindToAssetType", Map.of(
                    "graph_query", "graph_query_asset", "document", "document_search"),
                "allowedTargetKinds", List.of("graph_query")));

        Map<String, Object> result = resolver.resolve(
            "mcp_tenant_opaque_discovery_capability", metadata,
            Map.of("filters", Map.of("intent", "find neighbors"),
                "finalDecision", "graph_query", "confidence", 0.91),
            "find neighbors");

        assertThat(result).doesNotContainKey(McpParamBindingResolver.STATUS_KEY)
            .containsEntry("targetKind", "graph_query")
            .containsEntry("assetType", "graph_query_asset");

        Map<String, Object> outsideScope = resolver.resolve(
            "mcp_tenant_opaque_discovery_capability", metadata,
            Map.of("filters", Map.of("intent", "find documents"),
                "finalDecision", "document", "confidence", 0.91),
            "find documents");
        assertThat(outsideScope).containsEntry(McpParamBindingResolver.STATUS_KEY, "DENIED");
    }

    @Test
    void missingPublishedTargetMappingDoesNotFallBackToBuiltInCatalog() {
        ToolMetadata metadata = publishedDiscoveryMetadata(
            ToolWorkflowRole.TEMPLATE_DISCOVERY, "opaque_discovery_capability",
            List.of("filters", "trace", "candidates", "finalDecision", "confidence", "assetType", "targetKind"),
            List.of("filters"), Map.of("allowedTargetKinds", List.of("database")));

        Map<String, Object> result = resolver.resolve(
            "mcp_tenant_opaque_discovery_capability", metadata,
            Map.of("filters", Map.of("intent", "health"),
                "finalDecision", "database", "confidence", 0.91), "health");

        assertThat(result).containsEntry(McpParamBindingResolver.STATUS_KEY, "DENIED");
        assertThat(result).doesNotContainKey("assetType");

        Map<String, Object> assetTypeOnly = resolver.resolve(
            "mcp_tenant_opaque_discovery_capability", metadata,
            Map.of("filters", Map.of("intent", "health"),
                "assetType", "sql_datasource", "confidence", 0.91), "health");
        assertThat(assetTypeOnly).containsEntry(McpParamBindingResolver.STATUS_KEY, "DENIED");
    }

    @Test
    void undeclaredToolNameDoesNotSelectBusinessBinding() {
        Map<String, Object> arguments = Map.of("templateId", "approved_template");
        assertThat(resolver.resolve("mcp_tenant_sql_query_execute", null, arguments, "query"))
            .isEqualTo(arguments);
    }

    @Test
    void protocolRegistrySupportsAdditionalExecutionFamily() {
        McpBindingPolicyRegistry policies = new McpBindingPolicyRegistry(Map.of(
            "mcp.graph-template.v1", McpBindingPolicyRegistry.Policy.SQL_EXECUTION));
        McpParamBindingResolver graphResolver = new McpParamBindingResolver(policies);
        ToolMetadata metadata = ToolMetadata.builder()
            .id("opaque_executor_42")
            .metadata(Map.of(
                "argumentBindingPolicy", bindingPolicy(),
                ToolWorkflowContract.METADATA_KEY,
                ToolWorkflowContract.declaration(ToolWorkflowRole.TEMPLATE_EXECUTION,
                    "mcp.graph-template.v1", "executionContext")))
            .build();

        assertThat(policies.resolve(metadata)).isEqualTo(McpBindingPolicyRegistry.Policy.SQL_EXECUTION);
        assertThat(graphResolver.resolve("opaque_executor_42", metadata,
            Map.of("templateId", "approved_template", "query", "find neighbors"), "find neighbors"))
            .containsEntry("template", "approved_template")
            .doesNotContainKey("query");
    }

    @Test
    void preservesPlannerSuppliedBilingualSignalsForServerRetrieval() {
        Map<String, Object> result = resolver.resolve(
            "mcp_tenant_opaque_discovery", declaredDiscoveryMetadata(ToolWorkflowRole.TEMPLATE_DISCOVERY),
            Map.of("filters", Map.of("intent", "find neighbors", "intentEn", "graph neighbors"),
                "finalDecision", "database", "confidence", 0.91), "find neighbors");

        assertThat(result).doesNotContainKey(McpParamBindingResolver.STATUS_KEY);
        assertThat(((Map<?, ?>) result.get("filters")).get("intentEn")).isEqualTo("graph neighbors");
    }

    @Test
    @SuppressWarnings("unchecked")
    void enrichesAssetDiscoveryRetrievalWithTopTwoIntentCandidatesAndOriginalQuery() {
        Map<String, Object> result = resolver.resolve(
            "mcp_chatchat_mcp_server_ssh_asset_query",
            declaredDiscoveryMetadata(ToolWorkflowRole.ASSET_DISCOVERY),
            Map.of(
                "candidates", List.of(Map.of("targetKind", "host", "confidence", 0.9)),
                "finalDecision", "host",
                "confidence", 0.9,
                "filters", Map.of(
                    "intent", "分析MySQL服务器管理进程信息",
                    "intentCandidates", List.of(
                        Map.of("intent", "Linux service status", "score", 0.61),
                        Map.of("intent", "MySQL服务器管理进程", "score", 0.92),
                        Map.of("intent", "mysqld process status", "score", 0.87)
                    )
                ),
                "trace", Map.of("plannerVersion", "v1.1")
            ),
            "分析MySQL服务器管理进程信息"
        );

        Map<?, ?> filters = (Map<?, ?>) result.get("filters");
        assertThat(strings(filters.get("queryTerms")))
            .containsExactly("MySQL服务器管理进程", "mysqld process status", "分析MySQL服务器管理进程信息");
        assertThat(strings(filters.get("retrievalSignals")))
            .containsExactly("MySQL服务器管理进程", "mysqld process status", "分析MySQL服务器管理进程信息");
        Map<String, Object> intentScoring = (Map<String, Object>) filters.get("intentScoring");
        assertThat(intentScoring)
            .containsEntry("strategy", "threshold_intent_ensemble_plus_original_query")
            .containsEntry("threshold", 0.75);
        assertThat(filters.containsKey("assetName")).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void keepsAllIntentCandidatesAboveThresholdAndIncludesExpandedQueries() {
        Map<String, Object> result = resolver.resolve(
            "mcp_chatchat_mcp_server_ssh_asset_query",
            declaredDiscoveryMetadata(ToolWorkflowRole.ASSET_DISCOVERY),
            Map.of(
                "candidates", List.of(Map.of("targetKind", "host", "confidence", 0.91)),
                "finalDecision", "host",
                "confidence", 0.91,
                "filters", Map.of(
                    "intent", "kafka消费慢是不是rocksdb写入导致的",
                    "intentCandidates", List.of(
                        Map.of("intent", "Kafka", "score", 0.96, "queries", List.of("consumer lag", "offset commit")),
                        Map.of("intent", "RocksDB", "score", 0.88, "expandedQueries", List.of("write stall", "compaction")),
                        Map.of("intent", "Flink", "score", 0.80, "keywords", List.of("checkpoint", "state backend")),
                        Map.of("intent", "Linux", "score", 0.11, "queries", List.of("iowait"))
                    )
                ),
                "trace", Map.of("plannerVersion", "v1.1")
            ),
            "kafka消费慢是不是rocksdb写入导致的"
        );

        Map<?, ?> filters = (Map<?, ?>) result.get("filters");
        assertThat(strings(filters.get("queryTerms")))
            .contains("Kafka", "consumer lag", "offset commit")
            .contains("RocksDB", "write stall", "compaction")
            .contains("Flink", "checkpoint", "state backend")
            .contains("kafka消费慢是不是rocksdb写入导致的")
            .doesNotContain("Linux", "iowait");
        Map<String, Object> intentScoring = (Map<String, Object>) filters.get("intentScoring");
        assertThat(intentScoring)
            .containsEntry("fallback", "original_query_only_when_no_candidate_reaches_threshold");
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsAllLowConfidenceIntentCandidatesInsteadOfPollutingRetrieval() {
        String query = "A股主要指数 2026年8月14日 行情数据 成交量";
        Map<String, Object> result = resolver.resolve(
            "mcp_chatchat_mcp_server_sql_datasource_asset_query",
            declaredDiscoveryMetadata(ToolWorkflowRole.ASSET_DISCOVERY),
            Map.of(
                "finalDecision", "database",
                "confidence", 0.95,
                "filters", Map.of(
                    "assetName", query,
                    "intentCandidates", List.of(
                        Map.of("intent", "债券托管量", "score", 0.42),
                        Map.of("intent", "融资融券", "score", 0.31)
                    )
                ),
                "trace", Map.of("plannerVersion", "v1.1")
            ),
            query
        );

        Map<String, Object> filters = (Map<String, Object>) result.get("filters");
        assertThat(filters).doesNotContainKey("assetName").containsEntry("intent", query);
        assertThat(strings(filters.get("queryTerms")))
            .containsExactly(query)
            .doesNotContain("债券托管量", "融资融券");
        assertThat((Map<String, Object>) filters.get("intentScoring"))
            .containsEntry("selectedTerms", List.of())
            .containsEntry("fallback", "original_query_only_when_no_candidate_reaches_threshold");
    }

    @Test
    @SuppressWarnings("unchecked")
    void explicitFiltersWinAndStaleContextEnvelopeIsRemoved() {
        Map<String, Object> result = resolver.resolve(
            "mcp_chatchat_mcp_server_sql_datasource_asset_query",
            declaredDiscoveryMetadata(ToolWorkflowRole.ASSET_DISCOVERY),
            Map.of(
                "finalDecision", "database",
                "confidence", 0.95,
                "executionContext", Map.of("asset_name", "stale-db", "environment", "TEST"),
                "filters", Map.of("assetName", "authoritative-db", "env", "PROD"),
                "trace", Map.of("plannerVersion", "v1.1")
            ),
            "查询数据库资产"
        );

        assertThat((Map<String, Object>) result.get("filters"))
            .containsEntry("assetName", "authoritative-db")
            .containsEntry("env", "PROD");
        assertThat(result).doesNotContainKeys("executionContext", "mcpExecutionContext");
    }

    private ToolMetadata declaredDiscoveryMetadata(ToolWorkflowRole role) {
        return publishedDiscoveryMetadata(role, "opaque_discovery",
            List.of("filters", "trace", "candidates", "finalDecision", "confidence", "assetType", "targetKind", "limit"),
            List.of("filters"),
            Map.of("allowedTargetKinds", List.of("host", "database", "http", "java", "business_database_query"),
                "targetKindToAssetType", Map.of(
                    "host", "ssh_host", "database", "sql_datasource", "http", "http_endpoint",
                    "java", "jmx_endpoint", "business_database_query", "database_query")));
    }

    private ToolMetadata publishedDiscoveryMetadata(ToolWorkflowRole role,
                                                     String remoteToolName,
                                                     List<String> fields,
                                                     List<String> required,
                                                     Map<String, Object> routingProtocol) {
        Map<String, Object> properties = new java.util.LinkedHashMap<>();
        fields.forEach(field -> properties.put(field, Map.of("type", "object")));
        return ToolMetadata.builder()
            .id("mcp_chatchat_mcp_server_" + remoteToolName)
            .categories(List.of("mcp"))
            .metadata(Map.of(
                "remoteToolName", remoteToolName,
                "argumentBindingPolicy", bindingPolicy(),
                "inputSchema", Map.of(
                    "type", "object",
                    "properties", properties,
                    "required", required),
                "mcpToolMeta", Map.of(
                    "routingProtocol", routingProtocol,
                    ToolWorkflowContract.METADATA_KEY,
                    ToolWorkflowContract.declaration(role, "mcp.template-discovery.v1", "filters", "template"))))
            .build();
    }

    private Map<String, Object> bindingPolicy() {
        return Map.of(
            "logicalContextKeys", List.of("env", "environment", "cluster", "namespace", "target",
                "targetType", "target_type", "assetName", "asset_name", "name", "hostSelector",
                "host_selector", "database", "databaseType", "dbType", "dialect", "databaseRole",
                "database_role", "service", "labels"),
            "concreteTargetFields", List.of("hostId", "host", "hostname", "ip", "ipAddress", "address",
                "datasourceId", "jdbcUrl", "url", "connectionString", "endpointId", "uri"),
            "rawExecutionFields", List.of("command", "rawCommand", "shell", "sql", "rawSql", "body", "bodyTemplate"),
            "targetKindFields", List.of("targetKind", "target_kind", "queryDomain", "query_domain", "domain",
                "resourceType", "resource_type", "resourceKind", "resource_kind"),
            "filterProtocolFields", List.of("trace", "routingTrace", "routing_trace", "candidates",
                "routingCandidates", "routing_candidates", "finalDecision", "final_decision", "selectedTargetKind",
                "selected_target_kind", "targetKind", "target_kind", "assetType", "asset_type", "confidence",
                "filtersSchemaVersion", "filters_schema_version", "mcpContext", "mcp_context", "tenantId",
                "tenant_id", "userId", "user_id", "requestId", "request_id", "conversationId",
                "conversation_id", "toolName", "tool_name", "remoteTool", "remote_tool"));
    }

    private List<String> strings(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }
}
