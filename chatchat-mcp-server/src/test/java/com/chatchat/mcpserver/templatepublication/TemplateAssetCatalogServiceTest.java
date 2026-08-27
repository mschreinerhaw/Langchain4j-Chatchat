package com.chatchat.mcpserver.templatepublication;

import com.chatchat.mcpserver.api.registry.ApiServiceConfig;
import com.chatchat.mcpserver.api.registry.ApiServiceConfigService;
import com.chatchat.mcpserver.category.BusinessCategory;
import com.chatchat.mcpserver.category.BusinessCategoryService;
import com.chatchat.mcpserver.database.definition.DatabaseQueryConfigService;
import com.chatchat.mcpserver.ops.command.CommandTemplateService;
import com.chatchat.mcpserver.ops.http.HttpEndpointConfigService;
import com.chatchat.mcpserver.ops.ssh.SshHostConfigService;
import com.chatchat.mcpserver.sql.datasource.SqlDatasourceConfigService;
import com.chatchat.mcpserver.sql.template.SqlTemplateService;
import com.chatchat.mcpserver.authorization.McpAuthorizationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TemplateAssetCatalogServiceTest {

    @Test
    void exposesCanonicalBusinessCategoryForTemplateFiltering() {
        CommandTemplateService commands = mock(CommandTemplateService.class);
        SqlTemplateService sql = mock(SqlTemplateService.class);
        HttpEndpointConfigService http = mock(HttpEndpointConfigService.class);
        DatabaseQueryConfigService databaseQueries = mock(DatabaseQueryConfigService.class);
        ApiServiceConfigService apis = mock(ApiServiceConfigService.class);
        BusinessCategoryService categories = mock(BusinessCategoryService.class);
        SshHostConfigService sshHosts = mock(SshHostConfigService.class);
        SqlDatasourceConfigService datasources = mock(SqlDatasourceConfigService.class);
        McpAuthorizationService authorization = mock(McpAuthorizationService.class);
        when(commands.listEnabled()).thenReturn(List.of());
        when(sql.listEnabled()).thenReturn(List.of());
        when(http.listEnabled()).thenReturn(List.of());
        when(databaseQueries.listEnabled()).thenReturn(List.of());
        when(sshHosts.listEnabled()).thenReturn(List.of());
        when(datasources.listEnabled()).thenReturn(List.of());

        BusinessCategory category = new BusinessCategory();
        category.setId("category-customer");
        category.setCode("customer_service");
        category.setName("客户服务");
        when(categories.listEnabled()).thenReturn(List.of(category));
        ApiServiceConfig api = new ApiServiceConfig();
        api.setId("api-1");
        api.setToolName("customer_profile_query");
        api.setTitle("客户画像查询");
        api.setCategoryId(category.getId());
        api.setBusinessGroup("legacy_customer_group");
        api.setEnabled(true);
        when(apis.listEnabled()).thenReturn(List.of(api));

        TemplateAssetCatalogService service = new TemplateAssetCatalogService(
            commands, sql, http, databaseQueries, apis, categories, sshHosts, datasources,
            authorization, new ObjectMapper());

        assertThat(service.listEnabled()).singleElement().satisfies(asset -> {
            assertThat(asset.businessCategoryCode()).isEqualTo("customer_service");
            assertThat(asset.businessCategoryName()).isEqualTo("客户服务");
        });

        when(authorization.roles(null)).thenReturn(List.of(new McpAuthorizationService.RoleView(
            "role-1", "tenant-1", "CUSTOMER", "客户人员", "BUSINESS", "ACTIVE")));
        when(authorization.roleAllows("role-1", "tenant-1", "customer_profile_query", null))
            .thenReturn(true);
        assertThat(service.listAuthorizedForRole("role-1"))
            .extracting(TemplateAssetCatalogService.TemplateAsset::templateId)
            .containsExactly("customer_profile_query");
    }
}
