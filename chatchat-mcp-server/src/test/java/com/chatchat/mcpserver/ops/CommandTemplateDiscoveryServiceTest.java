package com.chatchat.mcpserver.ops;

import com.chatchat.mcpserver.routing.TargetKindRegistry;
import com.chatchat.mcpserver.search.LuceneMcpSearchService;
import com.chatchat.mcpserver.sql.SqlDatasourceConfig;
import com.chatchat.mcpserver.sql.SqlDatasourceConfigService;
import com.chatchat.mcpserver.sql.SqlTemplateConfig;
import com.chatchat.mcpserver.sql.SqlTemplateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CommandTemplateDiscoveryServiceTest {

    @Test
    void queriesTemplatesByExactAssetAllowlistWithoutRawCommand() {
        CommandTemplateService templateService = mock(CommandTemplateService.class);
        SshHostConfigService hostService = mock(SshHostConfigService.class);
        CommandTemplateDiscoveryService service = service(templateService, hostService);
        when(templateService.listEnabled()).thenReturn(List.of(
            template("CHECK_SYSTEM_OVERVIEW", "uptime"),
            template("CHECK_DISK", "df -h")
        ));
        SshHostConfig host = host("host-1", "docker_service", "DEV", "[\"CHECK_SYSTEM_OVERVIEW\"]");
        when(hostService.listEnabled()).thenReturn(List.of(host));

        Map<String, Object> result = service.query(Map.of(
            "targetKind", "host",
            "confidence", 0.9,
            "filters", Map.of(
                "assetName", "docker_service",
                "env", "DEV",
                "intent", "system load"
            ),
            "trace", trace(),
            "limit", 10
        ));
        List<?> templates = (List<?>) result.get("templates");
        Map<?, ?> first = (Map<?, ?>) templates.get(0);

        assertThat(result)
            .containsEntry("schemaVersion", CommandTemplateDiscoveryService.RESULT_SCHEMA_VERSION)
            .containsEntry("returnedCount", 1);
        assertThat(first.get("templateId")).isEqualTo("CHECK_SYSTEM_OVERVIEW");
        Map<?, ?> selectionPolicy = (Map<?, ?>) result.get("templateSelectionPolicy");
        assertThat(selectionPolicy.get("mustUseReturnedTemplateId")).isEqualTo(true);
        assertThat(selectionPolicy.get("doNotInventTemplateNames")).isEqualTo(true);
        assertThat(result).doesNotContainKey("data");
        assertThat(result.toString()).doesNotContain("commandTemplate", "uptime", "df -h");
    }

    @Test
    void ranksTemplatesByConfiguredSynonymsAndIntentSignalsInsteadOfRepositoryOrder() {
        CommandTemplateService templateService = mock(CommandTemplateService.class);
        SshHostConfigService hostService = mock(SshHostConfigService.class);
        TemplateDiscoveryProperties properties = new TemplateDiscoveryProperties();
        properties.setIntentSynonyms(Map.of(
            "container", List.of("containers", "\u5bb9\u5668"),
            "count", List.of("number", "\u591a\u5c11", "\u6570\u91cf"),
            "running", List.of("active", "\u8fd0\u884c")
        ));
        CommandTemplateDiscoveryService service = service(templateService, hostService, properties);
        when(templateService.listEnabled()).thenReturn(List.of(
            template(
                "DOCKER_STATS",
                "docker stats --no-stream",
                "Docker resource stats",
                "Read Docker container resource usage.",
                "[\"docker\",\"container\",\"stats\"]"
            ),
            template(
                "LIST_DOCKER_CONTAINERS",
                "docker ps",
                "Running container count",
                "Read currently running container count.",
                "[\"docker\",\"container count\",\"running containers\"]"
            )
        ));
        when(hostService.listEnabled()).thenReturn(List.of());

        Map<String, Object> result = service.query(Map.of(
            "targetKind", "host",
            "confidence", 0.9,
            "filters", Map.of("intent", "\u5f53\u524d\u670d\u52a1\u6709\u591a\u5c11\u4e2a\u5bb9\u5668\u5728\u8fd0\u884c"),
            "trace", trace(),
            "limit", 10
        ));

        List<?> templates = (List<?>) result.get("templates");
        Map<?, ?> first = (Map<?, ?>) templates.get(0);
        Map<?, ?> second = (Map<?, ?>) templates.get(1);

        assertThat(first.get("templateId")).isEqualTo("LIST_DOCKER_CONTAINERS");
        assertThat((Integer) first.get("relevanceScore")).isGreaterThan((Integer) second.get("relevanceScore"));
        assertThat(first.get("matchReasons").toString()).contains("container", "count", "running");
        Map<?, ?> selectionPolicy = (Map<?, ?>) result.get("templateSelectionPolicy");
        assertThat(selectionPolicy.get("orderedBy").toString()).contains("decisionScore");
        assertThat(selectionPolicy.get("intentSynonymSource").toString())
            .contains("chatchat.mcp.template-discovery.intent-synonyms");
    }

    @Test
    void rejectsConcreteTargetAndRawCommandFields() {
        CommandTemplateDiscoveryService service = new CommandTemplateDiscoveryService(
            mock(CommandTemplateService.class),
            mock(SshHostConfigService.class),
            mock(SqlTemplateService.class),
            mock(SqlDatasourceConfigService.class),
            mock(HttpEndpointConfigService.class),
            new ObjectMapper(),
            new TemplateDiscoveryProperties()
        );

        assertThatThrownBy(() -> service.query(Map.of(
            "filters", Map.of(
                "assetName", "docker_service",
                "command", "uptime"
            )
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not allowed in template_query");
    }

    @Test
    void queriesSqlTemplatesWithoutRawSql() {
        SqlTemplateService sqlTemplateService = mock(SqlTemplateService.class);
        SqlDatasourceConfigService datasourceService = mock(SqlDatasourceConfigService.class);
        CommandTemplateDiscoveryService service = service(
            mock(CommandTemplateService.class),
            mock(SshHostConfigService.class),
            sqlTemplateService,
            datasourceService,
            mock(HttpEndpointConfigService.class)
        );
        SqlDatasourceConfig datasource = datasource("ds-1", "orders_db", "DEV");
        when(datasourceService.listEnabled()).thenReturn(List.of(datasource));
        when(sqlTemplateService.listEnabled()).thenReturn(List.of(sqlTemplate("CHECK_TABLE_COUNT", "SELECT COUNT(*) FROM {{table}}")));

        Map<String, Object> result = service.query(Map.of(
            "targetKind", "database",
            "confidence", 0.9,
            "filters", Map.of("assetName", "orders_db", "intent", "count"),
            "trace", trace(),
            "limit", 10
        ));

        List<?> templates = (List<?>) result.get("templates");
        assertThat(templates).hasSize(1);
        assertThat(((Map<?, ?>) templates.get(0)).get("templateId")).isEqualTo("CHECK_TABLE_COUNT");
        assertThat(result.toString()).doesNotContain("sqlTemplate", "SELECT COUNT");
    }

    @Test
    void queriesSqlTemplatesWhenAssetNameIncludesChineseIntentText() {
        SqlTemplateService sqlTemplateService = mock(SqlTemplateService.class);
        SqlDatasourceConfigService datasourceService = mock(SqlDatasourceConfigService.class);
        CommandTemplateDiscoveryService service = service(
            mock(CommandTemplateService.class),
            mock(SshHostConfigService.class),
            sqlTemplateService,
            datasourceService,
            mock(HttpEndpointConfigService.class)
        );
        SqlDatasourceConfig datasource = datasource("ds-248", "248测试数据库", "DEV");
        datasource.setDatabaseType("mysql");
        datasource.setAllowedTemplatesJson("[\"MYSQL_SHOW_STATUS\",\"MYSQL_DATABASE_SIZE\"]");
        SqlTemplateConfig status = sqlTemplate(
            "MYSQL_SHOW_STATUS",
            "SHOW STATUS",
            "MySQL status variables",
            "Show MySQL server status counters for health and performance inspection.",
            "mysql",
            "maintenance_instance",
            "[\"status\",\"instance\",\"health\",\"performance\",\"performance_issue\"]"
        );
        SqlTemplateConfig size = sqlTemplate(
            "MYSQL_DATABASE_SIZE",
            "SELECT table_schema AS db FROM information_schema.tables",
            "MySQL database size",
            "Summarize MySQL database size by schema.",
            "mysql",
            "maintenance_storage",
            "[\"database size\",\"storage\",\"space\",\"storage_check\"]"
        );
        when(datasourceService.listEnabled()).thenReturn(List.of(datasource));
        when(sqlTemplateService.listEnabled()).thenReturn(List.of(size, status));

        Map<String, Object> result = service.query(Map.of(
            "targetKind", "database",
            "confidence", 0.9,
            "filters", Map.of(
                "assetName", "248测试数据库 数据库状态分析",
                "env", "DEV",
                "intent", "数据库状态分析"
            ),
            "trace", trace(),
            "limit", 10
        ));

        List<?> templates = (List<?>) result.get("templates");
        Map<?, ?> selectedTemplate = (Map<?, ?>) templates.get(0);
        Map<?, ?> queryIr = (Map<?, ?>) result.get("queryIr");
        Map<?, ?> irAsset = (Map<?, ?>) queryIr.get("asset");
        Map<?, ?> irIntent = (Map<?, ?>) queryIr.get("intent");
        Map<?, ?> selectedAsset = (Map<?, ?>) irAsset.get("selected");
        assertThat(result).containsEntry("returnedCount", 1);
        assertThat(selectedTemplate.get("templateId")).isEqualTo("MYSQL_SHOW_STATUS");
        assertThat(selectedTemplate.get("matchReasons").toString()).contains("status");
        assertThat(selectedTemplate.get("mcpDecision").toString())
            .contains("mcp_template_ranking_v2_no_vector", "0.40*intentMatch");
        assertThat(selectedTemplate.get("rankingFeatures").toString())
            .contains("intentMatch", "lexicalScore", "typeMatch", "safetyScore", "featureList", "weightedScore");
        assertThat(irIntent.get("type")).isEqualTo("db_status");
        assertThat(irIntent.get("tags").toString()).contains("status", "health", "instance");
        assertThat(selectedAsset.get("name")).isEqualTo("248测试数据库");
        assertThat(selectedAsset.get("match").toString()).contains("contains");
        assertThat(result.get("resolutionTrace").toString())
            .contains("asset_resolution", "intent_normalization", "template_retrieval");
    }

    @Test
    void routesSqlDatasourceFromExplicitTargetKind() {
        CommandTemplateService commandTemplateService = mock(CommandTemplateService.class);
        SqlTemplateService sqlTemplateService = mock(SqlTemplateService.class);
        SqlDatasourceConfigService datasourceService = mock(SqlDatasourceConfigService.class);
        CommandTemplateDiscoveryService service = service(
            commandTemplateService,
            mock(SshHostConfigService.class),
            sqlTemplateService,
            datasourceService,
            mock(HttpEndpointConfigService.class)
        );
        SqlDatasourceConfig datasource = datasource("ds-tdh", "tdh-scheduler-db", "DEV");
        datasource.setDatabaseType("mysql");
        SqlTemplateConfig mysqlStatus = sqlTemplate(
            "MYSQL_SHOW_STATUS",
            "SHOW STATUS",
            "MySQL status variables",
            "Show MySQL server status counters for health inspection.",
            "mysql",
            "maintenance_instance",
            "[\"db_status\",\"status\",\"health\",\"instance\"]"
        );
        CommandTemplateConfig cpu = template("CHECK_CPU", "top -b -n 1", "CPU status", "Read CPU status.", "[\"cpu\",\"status\"]");
        when(datasourceService.listEnabled()).thenReturn(List.of(datasource));
        when(sqlTemplateService.listEnabled()).thenReturn(List.of(mysqlStatus));
        when(commandTemplateService.listEnabled()).thenReturn(List.of(cpu));

        Map<String, Object> result = service.query(Map.of(
            "targetKind", "database",
            "confidence", 0.9,
            "filters", Map.of("assetName", "tdh-scheduler-db", "env", "DEV", "intent", "database status"),
            "trace", trace(),
            "limit", 5
        ));

        List<?> templates = (List<?>) result.get("templates");
        Map<?, ?> first = (Map<?, ?>) templates.get(0);
        assertThat(result).containsEntry("assetType", "sql_datasource");
        assertThat(result)
            .containsEntry("targetKind", "database")
            .containsEntry("filtersSchemaVersion", TargetKindRegistry.FILTERS_SCHEMA_VERSION);
        assertThat(first.get("templateId")).isEqualTo("MYSQL_SHOW_STATUS");
        assertThat(first.get("templateId")).isNotEqualTo("CHECK_CPU");
    }

    @Test
    void rejectsTemplateQueryWithoutTargetKindOrAssetType() {
        CommandTemplateDiscoveryService service = service(
            mock(CommandTemplateService.class),
            mock(SshHostConfigService.class),
            mock(SqlTemplateService.class),
            mock(SqlDatasourceConfigService.class),
            mock(HttpEndpointConfigService.class)
        );

        assertThatThrownBy(() -> service.query(Map.of(
            "filters", Map.of("assetName", "tdh-scheduler", "env", "DEV", "intent", "database status"),
            "limit", 5
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("targetKind");
    }

    @Test
    void rejectsInvalidTargetKindWithStructuredException() {
        CommandTemplateDiscoveryService service = service(
            mock(CommandTemplateService.class),
            mock(SshHostConfigService.class),
            mock(SqlTemplateService.class),
            mock(SqlDatasourceConfigService.class),
            mock(HttpEndpointConfigService.class)
        );

        assertThatThrownBy(() -> service.query(Map.of(
            "targetKind", "databse",
            "confidence", 0.9,
            "filters", Map.of("intent", "database status"),
            "trace", trace(),
            "limit", 5
        )))
            .isInstanceOf(TargetKindRegistry.TargetKindException.class)
            .hasMessageContaining("Unsupported targetKind");
    }

    @Test
    void rejectsDocumentTargetKindForTemplateQuery() {
        CommandTemplateDiscoveryService service = service(
            mock(CommandTemplateService.class),
            mock(SshHostConfigService.class),
            mock(SqlTemplateService.class),
            mock(SqlDatasourceConfigService.class),
            mock(HttpEndpointConfigService.class)
        );

        assertThatThrownBy(() -> service.query(Map.of(
            "targetKind", "document",
            "confidence", 0.9,
            "filters", Map.of("intent", "database status"),
            "trace", trace(),
            "limit", 5
        )))
            .isInstanceOf(TargetKindRegistry.TargetKindException.class)
            .hasMessageContaining("not allowed for template_query");
    }

    @Test
    void rejectsTargetKindAssetTypeConflict() {
        CommandTemplateDiscoveryService service = service(
            mock(CommandTemplateService.class),
            mock(SshHostConfigService.class),
            mock(SqlTemplateService.class),
            mock(SqlDatasourceConfigService.class),
            mock(HttpEndpointConfigService.class)
        );

        assertThatThrownBy(() -> service.query(Map.of(
            "targetKind", "database",
            "confidence", 0.9,
            "assetType", "ssh_host",
            "filters", Map.of("intent", "database status"),
            "trace", trace(),
            "limit", 5
        )))
            .isInstanceOf(TargetKindRegistry.TargetKindException.class)
            .hasMessageContaining("maps to assetType=sql_datasource");
    }

    @Test
    void fallsBackToAuthorizedTemplatesWhenIntentRankingReturnsNoMatch() {
        SqlTemplateService sqlTemplateService = mock(SqlTemplateService.class);
        SqlDatasourceConfigService datasourceService = mock(SqlDatasourceConfigService.class);
        CommandTemplateDiscoveryService service = service(
            mock(CommandTemplateService.class),
            mock(SshHostConfigService.class),
            sqlTemplateService,
            datasourceService,
            mock(HttpEndpointConfigService.class)
        );
        SqlDatasourceConfig datasource = datasource("ds-248", "248测试数据库", "DEV");
        datasource.setDatabaseType("mysql");
        datasource.setAllowedTemplatesJson("[\"MYSQL_DATABASE_SIZE\"]");
        when(datasourceService.listEnabled()).thenReturn(List.of(datasource));
        when(sqlTemplateService.listEnabled()).thenReturn(List.of(sqlTemplate(
            "MYSQL_DATABASE_SIZE",
            "SELECT table_schema AS db FROM information_schema.tables",
            "MySQL database size",
            "Summarize MySQL database size by schema.",
            "mysql",
            "maintenance_storage",
            "[\"database size\",\"storage\",\"space\",\"storage_check\"]"
        )));

        Map<String, Object> result = service.query(Map.of(
            "targetKind", "database",
            "confidence", 0.9,
            "filters", Map.of(
                "assetName", "248测试数据库",
                "intent", "备份恢复"
            ),
            "trace", trace(),
            "limit", 10
        ));

        List<?> templates = (List<?>) result.get("templates");
        assertThat(result).containsEntry("returnedCount", 1);
        assertThat(((Map<?, ?>) templates.get(0)).get("templateId")).isEqualTo("MYSQL_DATABASE_SIZE");
        assertThat(result.get("resolutionTrace").toString()).contains("fallbackUsed=true");
    }

    @Test
    void fallsBackToRegisteredSqlTemplatesWhenLuceneReturnsNoHits() {
        SqlTemplateService sqlTemplateService = mock(SqlTemplateService.class);
        SqlDatasourceConfigService datasourceService = mock(SqlDatasourceConfigService.class);
        LuceneMcpSearchService lucene = mock(LuceneMcpSearchService.class);
        CommandTemplateDiscoveryService service = new CommandTemplateDiscoveryService(
            mock(CommandTemplateService.class),
            mock(SshHostConfigService.class),
            sqlTemplateService,
            datasourceService,
            mock(HttpEndpointConfigService.class),
            new ObjectMapper(),
            new TemplateDiscoveryProperties(),
            lucene
        );
        SqlDatasourceConfig datasource = datasource("ds-248", "\u0032\u0034\u0038\u6d4b\u8bd5\u6570\u636e\u5e93", "DEV");
        datasource.setDatabaseType("mysql");
        datasource.setAllowedTemplatesJson("[\"MYSQL_SHOW_STATUS\",\"MYSQL_INNODB_STATUS\",\"MYSQL_SHOW_PROCESSLIST\"]");
        when(datasourceService.listEnabled()).thenReturn(List.of(datasource));
        when(sqlTemplateService.listEnabled()).thenReturn(List.of(
            sqlTemplate(
                "MYSQL_SHOW_STATUS",
                "SHOW STATUS",
                "MySQL status variables",
                "Show MySQL server status counters for health and performance inspection.",
                "mysql",
                "maintenance_instance",
                "[\"db_status\",\"status\",\"health\",\"instance\"]"
            ),
            sqlTemplate(
                "MYSQL_INNODB_STATUS",
                "SHOW ENGINE INNODB STATUS",
                "MySQL InnoDB engine status",
                "Show InnoDB engine status for lock and deadlock troubleshooting.",
                "mysql",
                "maintenance_lock",
                "[\"lock\",\"deadlock\",\"blocking\"]"
            )
        ));
        when(lucene.enabled()).thenReturn(true);
        when(lucene.searchTemplates(anyList(), any())).thenReturn(List.of());

        Map<String, Object> result = service.query(Map.of(
            "candidates", List.of(Map.of("targetKind", "database", "confidence", 0.9)),
            "finalDecision", "database",
            "filters", Map.of(
                "assetName", "\u0032\u0034\u0038\u6d4b\u8bd5\u6570\u636e\u5e93",
                "env", "DEV",
                "intent", "database health status with connection count locks slow query buffer hit replication lag"
            ),
            "trace", trace(),
            "limit", 5
        ));

        List<?> templates = (List<?>) result.get("templates");
        List<String> templateIds = templates.stream()
            .map(item -> String.valueOf(((Map<?, ?>) item).get("templateId")))
            .toList();
        assertThat(result).containsEntry("returnedCount", 2);
        assertThat(templateIds).contains("MYSQL_SHOW_STATUS", "MYSQL_INNODB_STATUS");
        assertThat(result.get("resolutionTrace").toString())
            .contains("template_retrieval", "returnedCount=2", "lucene_empty_registry_fallback", "signalMissDoesNotDeny=true");
    }

    @Test
    void lucenePartialHitDoesNotExcludeAuthorizedInnoDbTemplate() {
        SqlTemplateService sqlTemplateService = mock(SqlTemplateService.class);
        SqlDatasourceConfigService datasourceService = mock(SqlDatasourceConfigService.class);
        LuceneMcpSearchService lucene = mock(LuceneMcpSearchService.class);
        CommandTemplateDiscoveryService service = new CommandTemplateDiscoveryService(
            mock(CommandTemplateService.class),
            mock(SshHostConfigService.class),
            sqlTemplateService,
            datasourceService,
            mock(HttpEndpointConfigService.class),
            new ObjectMapper(),
            new TemplateDiscoveryProperties(),
            lucene
        );
        SqlDatasourceConfig datasource = datasource("ds-248", "\u0032\u0034\u0038\u6d4b\u8bd5\u6570\u636e\u5e93", "DEV");
        datasource.setDatabaseType("mysql");
        datasource.setAllowedTemplatesJson("[\"MYSQL_SHOW_STATUS\",\"MYSQL_INNODB_STATUS\"]");
        when(datasourceService.listEnabled()).thenReturn(List.of(datasource));
        when(sqlTemplateService.listEnabled()).thenReturn(List.of(
            sqlTemplate(
                "MYSQL_SHOW_STATUS",
                "SHOW STATUS",
                "MySQL status variables",
                "Show MySQL server status counters for health and performance inspection.",
                "mysql",
                "maintenance_instance",
                "[\"status\",\"health\",\"instance\"]"
            ),
            sqlTemplate(
                "MYSQL_INNODB_STATUS",
                "SHOW ENGINE INNODB STATUS",
                "MySQL InnoDB engine status",
                "Show InnoDB engine status for lock and deadlock troubleshooting.",
                "mysql",
                "maintenance_lock",
                "[\"innodb\",\"lock\",\"deadlock\",\"transaction\",\"lock_check\"]"
            )
        ));
        when(lucene.enabled()).thenReturn(true);
        when(lucene.searchTemplates(anyList(), any())).thenReturn(List.of(
            new LuceneMcpSearchService.SearchHit("MYSQL_SHOW_STATUS", "template", 2.0f, List.of("forced_partial_hit"))
        ));

        Map<String, Object> result = service.query(Map.of(
            "candidates", List.of(Map.of("targetKind", "database", "confidence", 0.95)),
            "finalDecision", "database",
            "filters", Map.of(
                "assetName", "\u0032\u0034\u0038\u6d4b\u8bd5\u6570\u636e\u5e93",
                "env", "DEV",
                "intent", "InnoDB\u72b6\u6001"
            ),
            "trace", trace(),
            "limit", 5
        ));

        List<?> templates = (List<?>) result.get("templates");
        Map<?, ?> first = (Map<?, ?>) templates.get(0);
        List<String> templateIds = templates.stream()
            .map(item -> String.valueOf(((Map<?, ?>) item).get("templateId")))
            .toList();
        assertThat(templateIds).contains("MYSQL_INNODB_STATUS", "MYSQL_SHOW_STATUS");
        assertThat(first.get("templateId")).isEqualTo("MYSQL_INNODB_STATUS");
        assertThat(first.get("rankingFeatures").toString())
            .contains("featureList", "intentMatch", "lexicalScore", "weightedScore");
        assertThat(result.get("resolutionTrace").toString())
            .contains("lucene_scored", "signalMissDoesNotDeny=true");
    }

    @Test
    void queriesHttpTemplatesWithoutConcreteUrl() {
        HttpEndpointConfigService httpService = mock(HttpEndpointConfigService.class);
        CommandTemplateDiscoveryService service = service(
            mock(CommandTemplateService.class),
            mock(SshHostConfigService.class),
            mock(SqlTemplateService.class),
            mock(SqlDatasourceConfigService.class),
            httpService
        );
        HttpEndpointConfig endpoint = httpEndpoint("http_order_status", "DEV");
        when(httpService.listEnabled()).thenReturn(List.of(endpoint));

        Map<String, Object> result = service.query(Map.of(
            "targetKind", "http",
            "confidence", 0.9,
            "filters", Map.of("template", "http_order_status", "intent", "order status"),
            "trace", trace(),
            "limit", 10
        ));

        List<?> templates = (List<?>) result.get("templates");
        assertThat(templates).hasSize(1);
        assertThat(((Map<?, ?>) templates.get(0)).get("templateId")).isEqualTo("http_order_status");
        assertThat(result.toString()).doesNotContain("urlTemplate", "https://orders.internal", "bodyTemplate");
    }

    private CommandTemplateDiscoveryService service(CommandTemplateService templateService,
                                                    SshHostConfigService hostService) {
        return service(templateService, hostService, new TemplateDiscoveryProperties());
    }

    private CommandTemplateDiscoveryService service(CommandTemplateService templateService,
                                                    SshHostConfigService hostService,
                                                    TemplateDiscoveryProperties properties) {
        return service(
            templateService,
            hostService,
            mock(SqlTemplateService.class),
            mock(SqlDatasourceConfigService.class),
            mock(HttpEndpointConfigService.class),
            properties
        );
    }

    private CommandTemplateDiscoveryService service(CommandTemplateService templateService,
                                                    SshHostConfigService hostService,
                                                    SqlTemplateService sqlTemplateService,
                                                    SqlDatasourceConfigService datasourceService,
                                                    HttpEndpointConfigService httpService) {
        return service(templateService, hostService, sqlTemplateService, datasourceService, httpService, new TemplateDiscoveryProperties());
    }

    private CommandTemplateDiscoveryService service(CommandTemplateService templateService,
                                                    SshHostConfigService hostService,
                                                    SqlTemplateService sqlTemplateService,
                                                    SqlDatasourceConfigService datasourceService,
                                                    HttpEndpointConfigService httpService,
                                                    TemplateDiscoveryProperties properties) {
        return new CommandTemplateDiscoveryService(
            templateService,
            hostService,
            sqlTemplateService,
            datasourceService,
            httpService,
            new ObjectMapper(),
            properties
        );
    }

    private CommandTemplateConfig template(String code, String command) {
        return template(code, command, code, "Read-only system diagnostic template", null);
    }

    private Map<String, Object> trace() {
        return Map.of("plannerVersion", "v1.0", "model", "unit-test");
    }

    private CommandTemplateConfig template(String code,
                                           String command,
                                           String title,
                                           String description,
                                           String intentSignalsJson) {
        CommandTemplateConfig template = new CommandTemplateConfig();
        template.setId(code);
        template.setCode(code);
        template.setTitle(title);
        template.setDescription(description);
        template.setCommandTemplate(command);
        template.setParameterSchemaJson("{\"type\":\"object\",\"properties\":{},\"required\":[]}");
        template.setRiskLevel("LOW");
        template.setCategory("system_diagnostic");
        template.setIntentSignalsJson(intentSignalsJson);
        template.setEnabled(true);
        return template;
    }

    private SshHostConfig host(String id, String name, String env, String allowedCommandsJson) {
        SshHostConfig host = new SshHostConfig();
        host.setId(id);
        host.setName(name);
        host.setTitle(name);
        host.setToolName("ssh_" + name);
        host.setEnvironment(env);
        host.setAllowedCommandsJson(allowedCommandsJson);
        host.setEnabled(true);
        return host;
    }

    private SqlTemplateConfig sqlTemplate(String code, String sql) {
        return sqlTemplate(code, sql, code, "Read-only SQL diagnostic template", "generic", "sql_diagnostic", "[\"count\",\"table\"]");
    }

    private SqlTemplateConfig sqlTemplate(String code,
                                          String sql,
                                          String title,
                                          String description,
                                          String databaseType,
                                          String category,
                                          String intentSignalsJson) {
        SqlTemplateConfig template = new SqlTemplateConfig();
        template.setId(code);
        template.setCode(code);
        template.setTitle(title);
        template.setDescription(description);
        template.setSqlTemplate(sql);
        template.setParameterSchemaJson("{\"type\":\"object\",\"properties\":{},\"required\":[]}");
        template.setRiskLevel("MEDIUM");
        template.setCategory(category);
        template.setDatabaseType(databaseType);
        template.setIntentSignalsJson(intentSignalsJson);
        template.setEnabled(true);
        return template;
    }

    private SqlDatasourceConfig datasource(String id, String name, String env) {
        SqlDatasourceConfig datasource = new SqlDatasourceConfig();
        datasource.setId(id);
        datasource.setName(name);
        datasource.setTitle(name);
        datasource.setToolName("sql_" + name);
        datasource.setEnvironment(env);
        datasource.setEnabled(true);
        return datasource;
    }

    private HttpEndpointConfig httpEndpoint(String toolName, String env) {
        HttpEndpointConfig endpoint = new HttpEndpointConfig();
        endpoint.setId("http-1");
        endpoint.setName(toolName);
        endpoint.setToolName(toolName);
        endpoint.setTitle("Order status");
        endpoint.setDescription("Read order status by id");
        endpoint.setMethod("GET");
        endpoint.setUrlTemplate("https://orders.internal/status/{{orderId}}");
        endpoint.setInputSchemaJson("{\"type\":\"object\",\"properties\":{\"orderId\":{\"type\":\"string\"}},\"required\":[\"orderId\"]}");
        endpoint.setEnvironment(env);
        endpoint.setCategory("business_api");
        endpoint.setTags("order,status");
        endpoint.setEnabled(true);
        return endpoint;
    }
}
