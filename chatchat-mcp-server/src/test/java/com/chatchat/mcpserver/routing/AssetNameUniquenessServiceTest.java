package com.chatchat.mcpserver.routing;

import com.chatchat.mcpserver.ops.HttpEndpointConfig;
import com.chatchat.mcpserver.ops.HttpEndpointConfigRepository;
import com.chatchat.mcpserver.ops.HttpEndpointConfigService;
import com.chatchat.mcpserver.ops.SshHostConfig;
import com.chatchat.mcpserver.ops.SshHostConfigRepository;
import com.chatchat.mcpserver.ops.SshHostConfigService;
import com.chatchat.mcpserver.sql.SqlDatasourceConfig;
import com.chatchat.mcpserver.sql.SqlDatasourceConfigRepository;
import com.chatchat.mcpserver.sql.SqlDatasourceConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AssetNameUniquenessServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutionTargetService executionTargetService = mock(ExecutionTargetService.class);

    @Test
    void rejectsDuplicateSshHostNameOnCreate() {
        SshHostConfigRepository repository = mock(SshHostConfigRepository.class);
        SshHostConfigService service = new SshHostConfigService(repository, objectMapper, executionTargetService);
        when(repository.findByNameIgnoreCase("AppServer")).thenReturn(Optional.of(sshHost("host-1", "AppServer")));

        assertThatThrownBy(() -> service.create(sshHost("host-2", "AppServer")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("SSH host name already exists: AppServer");
    }

    @Test
    void rejectsDuplicateSqlDatasourceNameOnCreate() {
        SqlDatasourceConfigRepository repository = mock(SqlDatasourceConfigRepository.class);
        SqlDatasourceConfigService service = new SqlDatasourceConfigService(repository, objectMapper, executionTargetService);
        when(repository.findByNameIgnoreCase("MySQL248")).thenReturn(Optional.of(datasource("ds-1", "MySQL248")));

        assertThatThrownBy(() -> service.create(datasource("ds-2", "MySQL248")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("SQL datasource name already exists: MySQL248");
    }

    @Test
    void rejectsDuplicateHttpEndpointNameOnCreate() {
        HttpEndpointConfigRepository repository = mock(HttpEndpointConfigRepository.class);
        HttpEndpointConfigService service = new HttpEndpointConfigService(repository, objectMapper);
        when(repository.findByNameIgnoreCase("OrderApi")).thenReturn(Optional.of(endpoint("http-1", "OrderApi")));

        assertThatThrownBy(() -> service.create(endpoint("http-2", "OrderApi")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("HTTP endpoint name already exists: OrderApi");
    }

    private SshHostConfig sshHost(String id, String name) {
        SshHostConfig host = new SshHostConfig();
        host.setId(id);
        host.setName(name);
        host.setToolName("ssh_" + name.toLowerCase());
        host.setHostname("127.0.0.1");
        host.setUsername("root");
        return host;
    }

    private SqlDatasourceConfig datasource(String id, String name) {
        SqlDatasourceConfig datasource = new SqlDatasourceConfig();
        datasource.setId(id);
        datasource.setName(name);
        datasource.setToolName("db_query_" + name.toLowerCase() + "_dev");
        datasource.setJdbcUrl("jdbc:mysql://127.0.0.1:3306/test");
        datasource.setDatabaseType("mysql");
        return datasource;
    }

    private HttpEndpointConfig endpoint(String id, String name) {
        HttpEndpointConfig endpoint = new HttpEndpointConfig();
        endpoint.setId(id);
        endpoint.setName(name);
        endpoint.setToolName("http_" + name.toLowerCase());
        endpoint.setUrlTemplate("https://example.com/" + name);
        endpoint.setMethod("GET");
        return endpoint;
    }
}
