package com.chatchat.mcpserver.ops;

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
            "assetType", "ssh_host",
            "filters", Map.of(
                "assetName", "docker_service",
                "env", "DEV",
                "intent", "system load"
            ),
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
            "assetType", "ssh_host",
            "filters", Map.of("intent", "\u5f53\u524d\u670d\u52a1\u6709\u591a\u5c11\u4e2a\u5bb9\u5668\u5728\u8fd0\u884c"),
            "limit", 10
        ));

        List<?> templates = (List<?>) result.get("templates");
        Map<?, ?> first = (Map<?, ?>) templates.get(0);
        Map<?, ?> second = (Map<?, ?>) templates.get(1);

        assertThat(first.get("templateId")).isEqualTo("LIST_DOCKER_CONTAINERS");
        assertThat((Integer) first.get("relevanceScore")).isGreaterThan((Integer) second.get("relevanceScore"));
        assertThat(first.get("matchReasons").toString()).contains("container", "count", "running");
        Map<?, ?> selectionPolicy = (Map<?, ?>) result.get("templateSelectionPolicy");
        assertThat(selectionPolicy.get("orderedBy").toString()).contains("relevanceScore");
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
            "assetType", "sql_datasource",
            "filters", Map.of("assetName", "orders_db", "intent", "count"),
            "limit", 10
        ));

        List<?> templates = (List<?>) result.get("templates");
        assertThat(templates).hasSize(1);
        assertThat(((Map<?, ?>) templates.get(0)).get("templateId")).isEqualTo("CHECK_TABLE_COUNT");
        assertThat(result.toString()).doesNotContain("sqlTemplate", "SELECT COUNT");
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
            "assetType", "http_endpoint",
            "filters", Map.of("template", "http_order_status", "intent", "order status"),
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
        SqlTemplateConfig template = new SqlTemplateConfig();
        template.setId(code);
        template.setCode(code);
        template.setTitle(code);
        template.setDescription("Read-only SQL diagnostic template");
        template.setSqlTemplate(sql);
        template.setParameterSchemaJson("{\"type\":\"object\",\"properties\":{},\"required\":[]}");
        template.setRiskLevel("MEDIUM");
        template.setCategory("sql_diagnostic");
        template.setIntentSignalsJson("[\"count\",\"table\"]");
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
