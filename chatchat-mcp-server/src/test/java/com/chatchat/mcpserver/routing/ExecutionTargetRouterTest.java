package com.chatchat.mcpserver.routing;

import com.chatchat.mcpserver.database.DatabaseQueryConfig;
import com.chatchat.mcpserver.database.DatabaseQueryConfigService;
import com.chatchat.mcpserver.ops.HttpEndpointConfig;
import com.chatchat.mcpserver.ops.HttpEndpointConfigService;
import com.chatchat.mcpserver.ops.SshHostConfig;
import com.chatchat.mcpserver.ops.SshHostConfigService;
import com.chatchat.mcpserver.sql.SqlDatasourceConfig;
import com.chatchat.mcpserver.sql.SqlDatasourceConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExecutionTargetRouterTest {

    @Test
    void routesLinuxCommandByLogicalExecutionContext() {
        SshHostConfigService hostService = mock(SshHostConfigService.class);
        SqlDatasourceConfigService datasourceService = mock(SqlDatasourceConfigService.class);
        ExecutionTargetService targetService = mock(ExecutionTargetService.class);
        ExecutionTargetRouter router = router(hostService, datasourceService, targetService);
        when(targetService.listEnabledByAssetType(ExecutionTargetService.ASSET_TYPE_SSH_HOST)).thenReturn(List.of());
        when(hostService.listEnabled()).thenReturn(List.of(
            host("host-1", "dn-a-01", "ssh_dn_a_01", "PROD", "hdfs,datanode,cluster:hadoop-prod-01"),
            host("host-2", "broker-01", "ssh_broker_01", "PROD", "kafka,broker,cluster:kafka-prod-01")
        ));

        Map<String, Object> routed = router.routeLinuxCommand(Map.of(
            "template", "CHECK_DISK",
            "executionContext", Map.of(
                "env", "prod",
                "cluster", "hadoop-prod-01",
                "targetType", "datanode"
            )
        ));
        Map<?, ?> routedTarget = (Map<?, ?>) routed.get("routedTarget");
        Map<?, ?> routingTrace = (Map<?, ?>) routed.get("routingDecisionLog");
        List<?> tracedCandidates = (List<?>) routingTrace.get("candidates");
        Map<?, ?> selectedCandidate = (Map<?, ?>) tracedCandidates.stream()
            .filter(candidate -> Boolean.TRUE.equals(((Map<?, ?>) candidate).get("selected")))
            .findFirst()
            .orElseThrow();
        Map<?, ?> scoreBreakdown = (Map<?, ?>) selectedCandidate.get("scoreBreakdown");

        assertThat(routed)
            .containsEntry("hostId", "host-1")
            .containsEntry("template", "CHECK_DISK")
            .doesNotContainKey("executionContext");
        assertThat(routedTarget.containsKey("routingTrace")).isFalse();
        assertThat(routingTrace.get("schemaVersion")).isEqualTo(AssetMetadataFactory.ROUTING_TRACE_SCHEMA);
        assertThat(routingTrace.get("routingPolicyVersion")).isEqualTo(AssetMetadataFactory.ROUTING_POLICY_VERSION);
        assertThat(routingTrace.get("winner")).isEqualTo("host-1");
        assertThat(selectedCandidate.get("assetId")).isEqualTo("host-1");
        assertThat(selectedCandidate.get("selected")).isEqualTo(true);
        assertThat(scoreBreakdown.keySet().stream().map(String::valueOf).toList())
            .contains("envMatch", "labelMatch", "serviceAffinity", "capabilityMatch", "finalScore");
    }

    @Test
    void rejectsConcreteHostTargetInGatewayCall() {
        ExecutionTargetRouter router = new ExecutionTargetRouter(
            mock(SshHostConfigService.class),
            mock(SqlDatasourceConfigService.class),
            mock(HttpEndpointConfigService.class),
            mock(DatabaseQueryConfigService.class),
            mock(ExecutionTargetService.class),
            new ObjectMapper()
        );

        assertThatThrownBy(() -> router.routeLinuxCommand(Map.of(
            "template", "CHECK_DISK",
            "hostId", "host-1",
            "executionContext", Map.of("targetType", "datanode")
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Concrete execution target is not allowed");
    }

    @Test
    void rejectsEmptyLinuxExecutionContext() {
        ExecutionTargetRouter router = new ExecutionTargetRouter(
            mock(SshHostConfigService.class),
            mock(SqlDatasourceConfigService.class),
            mock(HttpEndpointConfigService.class),
            mock(DatabaseQueryConfigService.class),
            mock(ExecutionTargetService.class),
            new ObjectMapper()
        );

        assertThatThrownBy(() -> router.routeLinuxCommand(Map.of(
            "template", "CHECK_SYSTEM_OVERVIEW",
            "executionContext", Map.of()
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("INVALID_EXECUTION_CONTEXT")
            .hasMessageContaining("linux_command_execute");
    }

    @Test
    void rejectsBlankLinuxExecutionContext() {
        ExecutionTargetRouter router = new ExecutionTargetRouter(
            mock(SshHostConfigService.class),
            mock(SqlDatasourceConfigService.class),
            mock(HttpEndpointConfigService.class),
            mock(DatabaseQueryConfigService.class),
            mock(ExecutionTargetService.class),
            new ObjectMapper()
        );

        assertThatThrownBy(() -> router.routeLinuxCommand(Map.of(
            "template", "CHECK_SYSTEM_OVERVIEW",
            "executionContext", Map.of("env", " ")
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("INVALID_EXECUTION_CONTEXT");
    }

    @Test
    void rejectsEmptySqlHttpAndDatabaseExecutionContext() {
        ExecutionTargetRouter router = new ExecutionTargetRouter(
            mock(SshHostConfigService.class),
            mock(SqlDatasourceConfigService.class),
            mock(HttpEndpointConfigService.class),
            mock(DatabaseQueryConfigService.class),
            mock(ExecutionTargetService.class),
            new ObjectMapper()
        );

        assertThatThrownBy(() -> router.routeSqlQuery(Map.of(
            "sql", "select 1",
            "executionContext", Map.of()
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("INVALID_EXECUTION_CONTEXT")
            .hasMessageContaining("sql_query_execute");
        assertThatThrownBy(() -> router.routeHttpRequest(Map.of(
            "executionContext", Map.of()
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("INVALID_EXECUTION_CONTEXT")
            .hasMessageContaining("http_request_execute");
        assertThatThrownBy(() -> router.routeDatabaseQuery(Map.of(
            "executionContext", Map.of()
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("INVALID_EXECUTION_CONTEXT")
            .hasMessageContaining("database_query_execute");
    }

    @Test
    void rejectsAmbiguousLinuxRoute() {
        SshHostConfigService hostService = mock(SshHostConfigService.class);
        SqlDatasourceConfigService datasourceService = mock(SqlDatasourceConfigService.class);
        ExecutionTargetService targetService = mock(ExecutionTargetService.class);
        ExecutionTargetRouter router = router(hostService, datasourceService, targetService);
        when(targetService.listEnabledByAssetType(ExecutionTargetService.ASSET_TYPE_SSH_HOST)).thenReturn(List.of());
        when(hostService.listEnabled()).thenReturn(List.of(
            host("host-1", "dn-a-01", "ssh_dn_a_01", "PROD", "datanode"),
            host("host-2", "dn-a-02", "ssh_dn_a_02", "PROD", "datanode")
        ));

        assertThatThrownBy(() -> router.routeLinuxCommand(Map.of(
            "template", "CHECK_DISK",
            "executionContext", Map.of("env", "prod", "targetType", "datanode")
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Ambiguous SSH host execution context");
    }

    @Test
    void routesSqlQueryByLogicalDatasourceContext() {
        SshHostConfigService hostService = mock(SshHostConfigService.class);
        SqlDatasourceConfigService datasourceService = mock(SqlDatasourceConfigService.class);
        ExecutionTargetService targetService = mock(ExecutionTargetService.class);
        ExecutionTargetRouter router = router(hostService, datasourceService, targetService);
        when(targetService.listEnabledByAssetType(ExecutionTargetService.ASSET_TYPE_SQL_DATASOURCE)).thenReturn(List.of());
        when(datasourceService.listEnabled()).thenReturn(List.of(
            datasource("ds-1", "mysql-primary", "db_query_mysql_primary_prod", "PROD",
                "{\"databaseRole\":\"primary\",\"labels\":[\"mysql\",\"orders\"]}"),
            datasource("ds-2", "mysql-replica", "db_query_mysql_replica_prod", "PROD",
                "{\"databaseRole\":\"replica\",\"labels\":[\"mysql\",\"orders\"]}")
        ));

        Map<String, Object> routed = router.routeSqlQuery(Map.of(
            "sql", "select 1",
            "executionContext", Map.of(
                "env", "prod",
                "database", "mysql",
                "databaseRole", "primary"
            )
        ));

        assertThat(routed)
            .containsEntry("datasourceId", "ds-1")
            .containsEntry("sql", "select 1")
            .doesNotContainKey("executionContext");
    }

    @Test
    void routesLinuxCommandByAssetRegistrationProtocolLabels() {
        SshHostConfigService hostService = mock(SshHostConfigService.class);
        SqlDatasourceConfigService datasourceService = mock(SqlDatasourceConfigService.class);
        ExecutionTargetService targetService = mock(ExecutionTargetService.class);
        ExecutionTargetRouter router = router(hostService, datasourceService, targetService);
        when(targetService.listEnabledByAssetType(ExecutionTargetService.ASSET_TYPE_SSH_HOST)).thenReturn(List.of());
        SshHostConfig datanode = host("host-1", "dn-a-01", "ssh_dn_a_01", "PROD", null);
        datanode.setRoutingLabelsJson("[\"hadoop-prod-01\",\"datanode\"]");
        datanode.setCapabilitiesJson("[\"ssh\",\"linux_command_execute\"]");
        SshHostConfig broker = host("host-2", "broker-01", "ssh_broker_01", "PROD", null);
        broker.setRoutingLabelsJson("[\"kafka-prod-01\",\"broker\"]");
        when(hostService.listEnabled()).thenReturn(List.of(datanode, broker));

        Map<String, Object> routed = router.routeLinuxCommand(Map.of(
            "template", "CHECK_DISK",
            "executionContext", Map.of(
                "env", "prod",
                "cluster", "hadoop-prod-01",
                "targetType", "datanode"
            )
        ));

        assertThat(routed).containsEntry("hostId", "host-1");
    }

    @Test
    void routesLinuxCommandByExactAssetNameOnly() {
        SshHostConfigService hostService = mock(SshHostConfigService.class);
        SqlDatasourceConfigService datasourceService = mock(SqlDatasourceConfigService.class);
        ExecutionTargetService targetService = mock(ExecutionTargetService.class);
        ExecutionTargetRouter router = router(hostService, datasourceService, targetService);
        when(targetService.listEnabledByAssetType(ExecutionTargetService.ASSET_TYPE_SSH_HOST)).thenReturn(List.of());
        SshHostConfig docker = host("host-1", "docker_service", "ssh_docker_service", "DEV", null);
        docker.setTitle("docker_service_host");
        when(hostService.listEnabled()).thenReturn(List.of(docker));

        Map<String, Object> routed = router.routeLinuxCommand(Map.of(
            "template", "CHECK_SYSTEM_OVERVIEW",
            "executionContext", Map.of("assetName", "docker_service")
        ));

        assertThat(routed).containsEntry("hostId", "host-1");
        assertThatThrownBy(() -> router.routeLinuxCommand(Map.of(
            "template", "CHECK_SYSTEM_OVERVIEW",
            "executionContext", Map.of("service", "docker")
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("No SSH host matched execution context");
    }

    @Test
    void routesSqlQueryByAssetRegistrationProtocolLabels() {
        SshHostConfigService hostService = mock(SshHostConfigService.class);
        SqlDatasourceConfigService datasourceService = mock(SqlDatasourceConfigService.class);
        ExecutionTargetService targetService = mock(ExecutionTargetService.class);
        ExecutionTargetRouter router = router(hostService, datasourceService, targetService);
        when(targetService.listEnabledByAssetType(ExecutionTargetService.ASSET_TYPE_SQL_DATASOURCE)).thenReturn(List.of());
        SqlDatasourceConfig primary = datasource("ds-1", "orders-primary", "db_query_orders_primary_prod", "PROD", null);
        primary.setRoutingLabelsJson("[\"mysql\",\"primary\",\"orders\"]");
        primary.setCapabilitiesJson("[\"sql_query_execute\"]");
        SqlDatasourceConfig replica = datasource("ds-2", "orders-replica", "db_query_orders_replica_prod", "PROD", null);
        replica.setRoutingLabelsJson("[\"mysql\",\"replica\",\"orders\"]");
        when(datasourceService.listEnabled()).thenReturn(List.of(primary, replica));

        Map<String, Object> routed = router.routeSqlQuery(Map.of(
            "sql", "select 1",
            "executionContext", Map.of(
                "env", "prod",
                "database", "mysql",
                "databaseRole", "primary"
            )
        ));

        assertThat(routed).containsEntry("datasourceId", "ds-1");
    }

    @Test
    void targetRegistryRoutesBeforeAssetLabelFallback() {
        SshHostConfigService hostService = mock(SshHostConfigService.class);
        SqlDatasourceConfigService datasourceService = mock(SqlDatasourceConfigService.class);
        ExecutionTargetService targetService = mock(ExecutionTargetService.class);
        ExecutionTargetRouter router = router(hostService, datasourceService, targetService);
        when(targetService.listEnabledByAssetType(ExecutionTargetService.ASSET_TYPE_SSH_HOST)).thenReturn(List.of(
            target("datanode-group", "SSH_HOST", "PROD", "LABEL", "datanode-a", "[\"datanode\"]")
        ));
        when(hostService.listEnabled()).thenReturn(List.of(
            host("host-1", "dn-a-01", "ssh_dn_a_01", "PROD", "hdfs,datanode-a"),
            host("host-2", "dn-b-01", "ssh_dn_b_01", "PROD", "hdfs,datanode")
        ));

        Map<String, Object> routed = router.routeLinuxCommand(Map.of(
            "template", "CHECK_DISK",
            "executionContext", Map.of("env", "prod", "targetType", "datanode")
        ));

        assertThat(routed).containsEntry("hostId", "host-1");
    }

    @Test
    void routesHttpRequestByLogicalEndpointContext() {
        SshHostConfigService hostService = mock(SshHostConfigService.class);
        SqlDatasourceConfigService datasourceService = mock(SqlDatasourceConfigService.class);
        HttpEndpointConfigService httpService = mock(HttpEndpointConfigService.class);
        DatabaseQueryConfigService queryService = mock(DatabaseQueryConfigService.class);
        ExecutionTargetService targetService = mock(ExecutionTargetService.class);
        ExecutionTargetRouter router = router(hostService, datasourceService, httpService, queryService, targetService);
        when(targetService.listEnabledByAssetType(ExecutionTargetService.ASSET_TYPE_HTTP_ENDPOINT)).thenReturn(List.of());
        HttpEndpointConfig metrics = httpEndpoint("http-1", "metrics-api", "http_metrics_api", "PROD");
        metrics.setRoutingLabelsJson("[\"metrics\",\"ops-api\"]");
        HttpEndpointConfig billing = httpEndpoint("http-2", "billing-api", "http_billing_api", "PROD");
        billing.setRoutingLabelsJson("[\"billing\"]");
        when(httpService.listEnabled()).thenReturn(List.of(metrics, billing));

        ExecutionTargetRouter.RoutedHttpEndpoint routed = router.routeHttpRequest(Map.of(
            "executionContext", Map.of("env", "prod", "targetType", "metrics"),
            "parameters", Map.of("service", "namenode")
        ));

        assertThat(routed.endpoint().getId()).isEqualTo("http-1");
        assertThat(routed.arguments()).doesNotContainKey("executionContext");
    }

    @Test
    void routesDatabaseQueryByLogicalQueryContext() {
        SshHostConfigService hostService = mock(SshHostConfigService.class);
        SqlDatasourceConfigService datasourceService = mock(SqlDatasourceConfigService.class);
        HttpEndpointConfigService httpService = mock(HttpEndpointConfigService.class);
        DatabaseQueryConfigService queryService = mock(DatabaseQueryConfigService.class);
        ExecutionTargetService targetService = mock(ExecutionTargetService.class);
        ExecutionTargetRouter router = router(hostService, datasourceService, httpService, queryService, targetService);
        when(targetService.listEnabledByAssetType(ExecutionTargetService.ASSET_TYPE_DATABASE_QUERY)).thenReturn(List.of());
        DatabaseQueryConfig orders = databaseQuery("query-1", "orders_recent_query", "Orders recent");
        orders.setRoutingLabelsJson("[\"orders\",\"recent\"]");
        DatabaseQueryConfig users = databaseQuery("query-2", "users_recent_query", "Users recent");
        users.setRoutingLabelsJson("[\"users\",\"recent\"]");
        when(queryService.listEnabled()).thenReturn(List.of(orders, users));

        ExecutionTargetRouter.RoutedDatabaseQuery routed = router.routeDatabaseQuery(Map.of(
            "executionContext", Map.of("database", "orders", "targetType", "recent"),
            "parameters", Map.of("customerId", "c-1")
        ));

        assertThat(routed.query().getId()).isEqualTo("query-1");
        assertThat(routed.arguments()).doesNotContainKey("executionContext");
    }

    private ExecutionTargetRouter router(SshHostConfigService hostService,
                                         SqlDatasourceConfigService datasourceService,
                                         ExecutionTargetService targetService) {
        return router(
            hostService,
            datasourceService,
            mock(HttpEndpointConfigService.class),
            mock(DatabaseQueryConfigService.class),
            targetService
        );
    }

    private ExecutionTargetRouter router(SshHostConfigService hostService,
                                         SqlDatasourceConfigService datasourceService,
                                         HttpEndpointConfigService httpService,
                                         DatabaseQueryConfigService queryService,
                                         ExecutionTargetService targetService) {
        return new ExecutionTargetRouter(hostService, datasourceService, httpService, queryService, targetService, new ObjectMapper());
    }

    private SshHostConfig host(String id, String name, String toolName, String env, String tags) {
        SshHostConfig host = new SshHostConfig();
        host.setId(id);
        host.setName(name);
        host.setToolName(toolName);
        host.setTitle(name);
        host.setHostname(name + ".internal");
        host.setEnvironment(env);
        host.setTags(tags);
        host.setEnabled(true);
        return host;
    }

    private SqlDatasourceConfig datasource(String id, String name, String toolName, String env, String governanceJson) {
        SqlDatasourceConfig datasource = new SqlDatasourceConfig();
        datasource.setId(id);
        datasource.setName(name);
        datasource.setToolName(toolName);
        datasource.setTitle(name);
        datasource.setEnvironment(env);
        datasource.setGovernanceJson(governanceJson);
        datasource.setEnabled(true);
        return datasource;
    }

    private ExecutionTargetConfig target(String key,
                                         String assetType,
                                         String env,
                                         String selectorType,
                                         String selectorValue,
                                         String labelsJson) {
        ExecutionTargetConfig target = new ExecutionTargetConfig();
        target.setId("target-" + key);
        target.setTargetKey(key);
        target.setName(key);
        target.setAssetType(assetType);
        target.setEnvironment(env);
        target.setSelectorType(selectorType);
        target.setSelectorValue(selectorValue);
        target.setLabelsJson(labelsJson);
        target.setEnabled(true);
        target.setPriority(10);
        return target;
    }

    private HttpEndpointConfig httpEndpoint(String id, String name, String toolName, String env) {
        HttpEndpointConfig endpoint = new HttpEndpointConfig();
        endpoint.setId(id);
        endpoint.setName(name);
        endpoint.setToolName(toolName);
        endpoint.setTitle(name);
        endpoint.setUrlTemplate("https://example.com/" + name);
        endpoint.setEnvironment(env);
        endpoint.setEnabled(true);
        return endpoint;
    }

    private DatabaseQueryConfig databaseQuery(String id, String toolName, String title) {
        DatabaseQueryConfig query = new DatabaseQueryConfig();
        query.setId(id);
        query.setToolName(toolName);
        query.setTitle(title);
        query.setSqlTemplate("select 1");
        query.setEnabled(true);
        return query;
    }
}
