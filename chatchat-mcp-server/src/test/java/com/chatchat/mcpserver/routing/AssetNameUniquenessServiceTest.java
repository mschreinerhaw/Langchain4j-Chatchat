package com.chatchat.mcpserver.routing;

import com.chatchat.mcpserver.category.BusinessCategoryService;
import com.chatchat.mcpserver.category.BusinessCategory;
import com.chatchat.mcpserver.api.registry.ApiServiceConfigRepository;
import com.chatchat.mcpserver.ops.http.HttpEndpointConfig;
import com.chatchat.mcpserver.ops.http.HttpEndpointConfigRepository;
import com.chatchat.mcpserver.ops.http.HttpEndpointConfigService;
import com.chatchat.mcpserver.ops.ssh.SshHostConfig;
import com.chatchat.mcpserver.ops.ssh.SshHostConfigRepository;
import com.chatchat.mcpserver.ops.ssh.SshHostConfigService;
import com.chatchat.mcpserver.sql.datasource.SqlDatasourceConfig;
import com.chatchat.mcpserver.sql.datasource.SqlDatasourceConfigRepository;
import com.chatchat.mcpserver.sql.datasource.SqlDatasourceConfigService;
import com.chatchat.mcpserver.sql.metadata.SqlMetadataAssetRegistryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AssetNameUniquenessServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutionTargetService executionTargetService = mock(ExecutionTargetService.class);
    private final BusinessCategoryService categoryService = mock(BusinessCategoryService.class);

    @Test
    void rejectsDuplicateSshHostNameOnCreate() {
        stubDefaultCategory();
        SshHostConfigRepository repository = mock(SshHostConfigRepository.class);
        SshHostConfigService service = new SshHostConfigService(repository, objectMapper, executionTargetService, categoryService);
        when(repository.findByNameIgnoreCase("AppServer")).thenReturn(Optional.of(sshHost("host-1", "AppServer")));

        assertThatThrownBy(() -> service.create(sshHost("host-2", "AppServer")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("SSH host name already exists: AppServer");
    }

    @Test
    void rejectsDuplicateSqlDatasourceNameOnCreate() {
        stubDefaultCategory();
        SqlDatasourceConfigRepository repository = mock(SqlDatasourceConfigRepository.class);
        SqlDatasourceConfigService service = new SqlDatasourceConfigService(
            repository,
            objectMapper,
            executionTargetService,
            mock(SqlMetadataAssetRegistryService.class),
            categoryService
        );
        when(repository.findByNameIgnoreCase("MySQL248")).thenReturn(Optional.of(datasource("ds-1", "MySQL248")));

        assertThatThrownBy(() -> service.create(datasource("ds-2", "MySQL248")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("SQL datasource name already exists: MySQL248");
    }

    @Test
    void rejectsDuplicateHttpEndpointNameOnCreate() {
        stubDefaultCategory();
        HttpEndpointConfigRepository repository = mock(HttpEndpointConfigRepository.class);
        HttpEndpointConfigService service = new HttpEndpointConfigService(
            repository, objectMapper, categoryService, mock(ApiServiceConfigRepository.class));
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

    private void stubDefaultCategory() {
        BusinessCategory category = new BusinessCategory();
        category.setId("default-id");
        category.setCode("default");
        category.setName("默认分类");
        when(categoryService.resolveOrDefault(null)).thenReturn(category);
    }
}
