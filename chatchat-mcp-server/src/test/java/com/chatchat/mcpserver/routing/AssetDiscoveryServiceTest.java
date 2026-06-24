package com.chatchat.mcpserver.routing;

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

class AssetDiscoveryServiceTest {

    @Test
    void queriesRedactedAssetsByLogicalContext() {
        SshHostConfigService hostService = mock(SshHostConfigService.class);
        SqlDatasourceConfigService datasourceService = mock(SqlDatasourceConfigService.class);
        HttpEndpointConfigService httpService = mock(HttpEndpointConfigService.class);
        AssetDiscoveryService service = service(hostService, datasourceService, httpService);
        SshHostConfig hadoopHost = sshHost("host-1", "dn-a", "PROD", "[\"cluster:hadoop-prod\",\"datanode\"]");
        SshHostConfig kafkaHost = sshHost("host-2", "broker-a", "PROD", "[\"cluster:kafka-prod\",\"broker\"]");
        when(hostService.listEnabled()).thenReturn(List.of(hadoopHost, kafkaHost));
        when(datasourceService.listEnabled()).thenReturn(List.of());
        when(httpService.listEnabled()).thenReturn(List.of());

        Map<String, Object> result = service.query(Map.of(
            "assetType", "ssh_host",
            "filters", Map.of(
                "env", "prod",
                "cluster", "hadoop-prod",
                "targetType", "datanode"
            ),
            "limit", 10
        ));
        List<?> assets = (List<?>) result.get("assets");
        Map<?, ?> metadata = (Map<?, ?>) assets.get(0);
        Map<?, ?> asset = (Map<?, ?>) metadata.get("asset");

        assertThat(result)
            .containsEntry("schemaVersion", AssetDiscoveryService.RESULT_SCHEMA_VERSION)
            .containsEntry("success", true)
            .containsEntry("returnedCount", 1);
        assertThat(asset.get("id")).isEqualTo("host-1");
        assertThat(result.toString()).doesNotContain("10.0.0.1", ".internal", "jdbc:", "https://");
    }

    @Test
    void rejectsQueryWithoutLogicalContext() {
        AssetDiscoveryService service = service(
            mock(SshHostConfigService.class),
            mock(SqlDatasourceConfigService.class),
            mock(HttpEndpointConfigService.class)
        );

        assertThatThrownBy(() -> service.query(Map.of("assetType", "ssh_host")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("requires at least one logical context filter");
    }

    @Test
    void treatsLooseContextStringAsServiceFilter() {
        SshHostConfigService hostService = mock(SshHostConfigService.class);
        SqlDatasourceConfigService datasourceService = mock(SqlDatasourceConfigService.class);
        HttpEndpointConfigService httpService = mock(HttpEndpointConfigService.class);
        AssetDiscoveryService service = service(hostService, datasourceService, httpService);
        when(hostService.listEnabled()).thenReturn(List.of(sshHost("host-1", "docker-host", "PROD", "[\"docker\"]")));
        when(datasourceService.listEnabled()).thenReturn(List.of());
        when(httpService.listEnabled()).thenReturn(List.of());

        Map<String, Object> result = service.query(Map.of(
            "assetType", "ssh_host",
            "context", "docker"
        ));

        assertThat((List<?>) result.get("assets")).hasSize(1);
        assertThat(((Map<?, ?>) result.get("filters")).get("service")).isEqualTo("docker");
    }

    @Test
    void doesNotMatchPartialTokenFromCompoundAssetName() {
        SshHostConfigService hostService = mock(SshHostConfigService.class);
        SqlDatasourceConfigService datasourceService = mock(SqlDatasourceConfigService.class);
        HttpEndpointConfigService httpService = mock(HttpEndpointConfigService.class);
        AssetDiscoveryService service = service(hostService, datasourceService, httpService);
        SshHostConfig dockerHost = sshHost("host-1", "docker_service", "DEV", null);
        dockerHost.setTitle("docker_service_host");
        dockerHost.setDescription("Ubuntu 22.04 LTS host running docker containers");
        when(hostService.listEnabled()).thenReturn(List.of(dockerHost));
        when(datasourceService.listEnabled()).thenReturn(List.of());
        when(httpService.listEnabled()).thenReturn(List.of());

        Map<String, Object> result = service.query(Map.of(
            "assetType", "ssh_host",
            "filters", Map.of("service", "docker")
        ));

        assertThat((List<?>) result.get("assets")).isEmpty();
        assertThat(result).containsEntry("returnedCount", 0);
        assertThat(result.toString()).doesNotContain("10.0.0.1");
    }

    @Test
    void matchesExactAssetNameFromCompoundAssetName() {
        SshHostConfigService hostService = mock(SshHostConfigService.class);
        SqlDatasourceConfigService datasourceService = mock(SqlDatasourceConfigService.class);
        HttpEndpointConfigService httpService = mock(HttpEndpointConfigService.class);
        AssetDiscoveryService service = service(hostService, datasourceService, httpService);
        SshHostConfig dockerHost = sshHost("host-1", "docker_service", "DEV", null);
        dockerHost.setTitle("docker_service_host");
        dockerHost.setDescription("Ubuntu 22.04 LTS host running docker containers");
        dockerHost.setAllowedCommandsJson("[\"CHECK_SYSTEM_OVERVIEW\"]");
        when(hostService.listEnabled()).thenReturn(List.of(dockerHost));
        when(datasourceService.listEnabled()).thenReturn(List.of());
        when(httpService.listEnabled()).thenReturn(List.of());

        Map<String, Object> result = service.query(Map.of(
            "assetType", "ssh_host",
            "filters", Map.of("assetName", "docker_service")
        ));

        assertThat((List<?>) result.get("assets")).hasSize(1);
        assertThat(result).containsEntry("returnedCount", 1);
        List<?> assets = (List<?>) result.get("assets");
        Map<?, ?> metadata = (Map<?, ?>) assets.get(0);
        Map<?, ?> asset = (Map<?, ?>) metadata.get("asset");
        Map<?, ?> capabilities = (Map<?, ?>) metadata.get("capabilities");
        assertThat(asset.get("name")).isEqualTo("docker_service");
        assertThat(asset.get("environment")).isEqualTo("DEV");
        assertThat(asset.get("toolName")).isEqualTo("ssh_docker_service");
        assertThat(capabilities.get("allowedCommandTemplates")).isEqualTo(List.of("CHECK_SYSTEM_OVERVIEW"));
        assertThat(result).doesNotContainKey("data");
        assertThat(result.toString()).doesNotContain("10.0.0.1");
    }

    @Test
    void rejectsConcreteTargetFields() {
        AssetDiscoveryService service = service(
            mock(SshHostConfigService.class),
            mock(SqlDatasourceConfigService.class),
            mock(HttpEndpointConfigService.class)
        );

        assertThatThrownBy(() -> service.query(Map.of(
            "filters", Map.of(
                "env", "prod",
                "ip", "10.0.0.1"
            )
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Concrete target field is not allowed");
    }

    @Test
    void capsLimitAtTwenty() {
        SshHostConfigService hostService = mock(SshHostConfigService.class);
        SqlDatasourceConfigService datasourceService = mock(SqlDatasourceConfigService.class);
        HttpEndpointConfigService httpService = mock(HttpEndpointConfigService.class);
        AssetDiscoveryService service = service(hostService, datasourceService, httpService);
        List<SshHostConfig> hosts = java.util.stream.IntStream.range(0, 25)
            .mapToObj(index -> sshHost("host-" + index, "host-" + index, "PROD", "[\"hadoop\"]"))
            .toList();
        when(hostService.listEnabled()).thenReturn(hosts);
        when(datasourceService.listEnabled()).thenReturn(List.of());
        when(httpService.listEnabled()).thenReturn(List.of());

        Map<String, Object> result = service.query(Map.of(
            "assetType", "ssh_host",
            "filters", Map.of("env", "prod", "service", "hadoop"),
            "limit", 100
        ));

        assertThat((List<?>) result.get("assets")).hasSize(20);
        assertThat(result)
            .containsEntry("limit", 20)
            .containsEntry("possiblyTruncated", true);
    }

    private AssetDiscoveryService service(SshHostConfigService hostService,
                                          SqlDatasourceConfigService datasourceService,
                                          HttpEndpointConfigService httpService) {
        return new AssetDiscoveryService(
            hostService,
            datasourceService,
            httpService,
            new AssetMetadataFactory(new ObjectMapper())
        );
    }

    private SshHostConfig sshHost(String id, String name, String env, String routingLabelsJson) {
        SshHostConfig host = new SshHostConfig();
        host.setId(id);
        host.setName(name);
        host.setTitle(name);
        host.setToolName("ssh_" + name);
        host.setHostname("10.0.0.1");
        host.setEnvironment(env);
        host.setRoutingLabelsJson(routingLabelsJson);
        host.setCapabilitiesJson("[\"ssh\",\"linux_command_execute\"]");
        host.setEnabled(true);
        return host;
    }

    @SuppressWarnings("unused")
    private SqlDatasourceConfig datasource(String id, String name, String env, String labels) {
        SqlDatasourceConfig datasource = new SqlDatasourceConfig();
        datasource.setId(id);
        datasource.setName(name);
        datasource.setTitle(name);
        datasource.setToolName("sql_" + name);
        datasource.setJdbcUrl("jdbc:mysql://10.0.0.10/orders");
        datasource.setEnvironment(env);
        datasource.setRoutingLabelsJson(labels);
        datasource.setEnabled(true);
        return datasource;
    }

    @SuppressWarnings("unused")
    private HttpEndpointConfig endpoint(String id, String name, String env, String labels) {
        HttpEndpointConfig endpoint = new HttpEndpointConfig();
        endpoint.setId(id);
        endpoint.setName(name);
        endpoint.setTitle(name);
        endpoint.setToolName("http_" + name);
        endpoint.setUrlTemplate("https://example.com/" + name);
        endpoint.setEnvironment(env);
        endpoint.setRoutingLabelsJson(labels);
        endpoint.setEnabled(true);
        return endpoint;
    }
}
