package com.chatchat.mcpserver.templatepublication.catalog;

import com.chatchat.mcpserver.api.registry.ApiServiceConfig;
import com.chatchat.mcpserver.api.registry.ApiServiceConfigService;
import com.chatchat.mcpserver.category.BusinessCategory;
import com.chatchat.mcpserver.category.BusinessCategoryService;
import com.chatchat.mcpserver.database.definition.DatabaseQueryConfigService;
import com.chatchat.mcpserver.external.ExternalMcpRegistryService;
import com.chatchat.mcpserver.external.ExternalMcpService;
import com.chatchat.mcpserver.external.ExternalMcpToolPublisher;
import com.chatchat.mcpserver.ops.command.CommandTemplateService;
import com.chatchat.mcpserver.ops.http.HttpEndpointConfigService;
import com.chatchat.mcpserver.ops.ssh.SshHostConfigService;
import com.chatchat.mcpserver.python.PythonTemplateCatalog;
import com.chatchat.mcpserver.python.PythonTemplate;
import com.chatchat.mcpserver.sql.datasource.SqlDatasourceConfigService;
import com.chatchat.mcpserver.sql.template.SqlTemplateService;
import com.chatchat.mcpserver.authorization.McpAuthorizationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TemplateAssetCatalogServiceTest {

    @Test
    void databaseFamiliesFollowCurrentCentralDatasourceType() {
        var queries = mock(DatabaseQueryConfigService.class);
        var datasources = mock(SqlDatasourceConfigService.class);
        var query = new com.chatchat.mcpserver.database.definition.DatabaseQueryConfig();
        query.setToolName("graph_query"); query.setTitle("Graph"); query.setDatasourceId("graph"); query.setDatabaseType("mysql");
        var asset = new com.chatchat.mcpserver.sql.datasource.SqlDatasourceConfig();
        asset.setId("graph"); asset.setDatabaseType("neo4j"); asset.setDriverClass("neo4j-http"); asset.setJdbcUrl("http://localhost:7474");
        when(queries.listEnabled()).thenReturn(List.of(query)); when(datasources.listEnabled()).thenReturn(List.of(asset));
        var service = new TemplateAssetCatalogService(mock(CommandTemplateService.class), mock(SqlTemplateService.class),
            mock(HttpEndpointConfigService.class), queries, mock(ApiServiceConfigService.class), mock(PythonTemplateCatalog.class),
            mock(BusinessCategoryService.class), mock(SshHostConfigService.class), datasources,
            mock(McpAuthorizationService.class), new ObjectMapper());
        assertThat(service.listEnabled()).singleElement().satisfies(template -> assertThat(template.queryFamily()).isEqualTo("neo4j"));
        asset.setDatabaseType("elasticsearch"); asset.setDriverClass("elasticsearch-http");
        assertThat(service.listEnabled()).singleElement().satisfies(template -> assertThat(template.queryFamily()).isEqualTo("elasticsearch"));
    }

    @Test
    void callerDiscoveryUsesEffectiveCallerPolicyWithoutPublicationBindings() {
        var authorization = mock(McpAuthorizationService.class);
        var sql = mock(SqlTemplateService.class);
        var allowed = new com.chatchat.mcpserver.sql.template.SqlTemplateConfig();
        allowed.setCode("METRICS_ALLOWED"); allowed.setTitle("Metrics");
        var denied = new com.chatchat.mcpserver.sql.template.SqlTemplateConfig();
        denied.setCode("METRICS_DENIED"); denied.setTitle("Denied metrics");
        when(sql.listEnabled()).thenReturn(List.of(allowed, denied));
        var service = new TemplateAssetCatalogService(
            mock(CommandTemplateService.class), sql, mock(HttpEndpointConfigService.class),
            mock(DatabaseQueryConfigService.class), mock(ApiServiceConfigService.class), mock(PythonTemplateCatalog.class),
            mock(BusinessCategoryService.class), mock(SshHostConfigService.class), mock(SqlDatasourceConfigService.class),
            authorization, new ObjectMapper());
        when(authorization.currentCallerContext()).thenReturn(new McpAuthorizationService.CallerAuthorizationContext(
            "tenant-1", "user-1", "member", List.of("role-1")));
        when(authorization.callerAllows("METRICS_ALLOWED", null)).thenReturn(true);
        when(authorization.currentCallerContext()).thenReturn(new McpAuthorizationService.CallerAuthorizationContext(
            "tenant-1", "user-1", "member", List.of()));
        assertThat(service.listAuthorizedForCallerAndType("sql_datasource"))
            .extracting(TemplateAssetCatalogService.TemplateAsset::templateId).containsExactly("METRICS_ALLOWED");
        when(authorization.callerAllows("METRICS_ALLOWED", null)).thenReturn(false);
        assertThat(service.listAuthorizedForCallerAndType("sql_datasource")).isEmpty();
        when(authorization.currentCallerContext()).thenReturn(null);
        assertThat(service.listAuthorizedForCallerAndType("sql_datasource")).isEmpty();
    }
    @Test
    void exposesCanonicalBusinessCategoryForTemplateFiltering() {
        CommandTemplateService commands = mock(CommandTemplateService.class);
        SqlTemplateService sql = mock(SqlTemplateService.class);
        HttpEndpointConfigService http = mock(HttpEndpointConfigService.class);
        DatabaseQueryConfigService databaseQueries = mock(DatabaseQueryConfigService.class);
        ApiServiceConfigService apis = mock(ApiServiceConfigService.class);
        PythonTemplateCatalog pythonTemplates = mock(PythonTemplateCatalog.class);
        BusinessCategoryService categories = mock(BusinessCategoryService.class);
        SshHostConfigService sshHosts = mock(SshHostConfigService.class);
        SqlDatasourceConfigService datasources = mock(SqlDatasourceConfigService.class);
        McpAuthorizationService authorization = mock(McpAuthorizationService.class);
        when(commands.listEnabled()).thenReturn(List.of());
        when(sql.listEnabled()).thenReturn(List.of());
        when(http.listEnabled()).thenReturn(List.of());
        when(databaseQueries.listEnabled()).thenReturn(List.of());
        when(pythonTemplates.listPublished()).thenReturn(List.of());
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
        api.setInputSchemaJson("{\"type\":\"object\",\"required\":[\"customer_id\"]}");
        api.setEnabled(true);
        when(apis.listEnabled()).thenReturn(List.of(api));

        TemplateAssetCatalogService service = new TemplateAssetCatalogService(
            commands, sql, http, databaseQueries, apis, pythonTemplates, categories, sshHosts, datasources,
            authorization, new ObjectMapper());

        assertThat(service.listEnabled()).singleElement().satisfies(asset -> {
            assertThat(asset.businessCategoryCode()).isEqualTo("customer_service");
            assertThat(asset.parameterSchema()).containsEntry("type", "object");
            assertThat(asset.parameterSchema().get("required")).isEqualTo(List.of("customer_id"));
            assertThat(asset.businessCategoryName()).isEqualTo("客户服务");
        });

        when(authorization.roles(null)).thenReturn(List.of(new McpAuthorizationService.RoleView(
            "role-1", "tenant-1", "CUSTOMER", "客户人员", "BUSINESS", "ACTIVE")));
        when(authorization.roleAllows("role-1", "tenant-1", "customer_profile_query", null))
            .thenReturn(true);
        assertThat(service.listAuthorizedForRole("role-1"))
            .extracting(TemplateAssetCatalogService.TemplateAsset::templateId)
            .containsExactly("customer_profile_query");

        PythonTemplate ownTemplate = pythonTemplate("python-1", "tenant-1", "Sales analysis");
        PythonTemplate otherTenantTemplate = pythonTemplate("python-2", "tenant-2", "Hidden analysis");
        when(pythonTemplates.listPublished()).thenReturn(List.of(ownTemplate, otherTenantTemplate));
        when(authorization.roleAllows("role-1", "tenant-1", "python_analysis_query", null))
            .thenReturn(true);

        assertThat(service.listAuthorizedForRoleAndType("role-1", TemplateAssetCatalogService.PYTHON))
            .extracting(TemplateAssetCatalogService.TemplateAsset::templateId)
            .containsExactly("python-1");

        ExternalMcpRegistryService external = mock(ExternalMcpRegistryService.class);
        ExternalMcpService partner = new ExternalMcpService();
        partner.setId("partner-1");
        partner.setName("集团服务");
        partner.setEnabled(true);
        when(external.list()).thenReturn(List.of(partner));
        when(external.parentAssetType(partner)).thenReturn(TemplateAssetCatalogService.API);
        when(external.templates(partner)).thenReturn(List.of(
            new ExternalMcpRegistryService.ToolTemplate("read_data", "远端查询", "说明",
                Map.of("type", "object", "properties", Map.of()), true)));
        ReflectionTestUtils.setField(service, "externalMcpRegistry", external);
        String published = ExternalMcpToolPublisher.publishedName("partner-1", "read_data");
        when(authorization.roleAllows("role-1", "tenant-1", published, null)).thenReturn(true);
        assertThat(service.listEnabledForType(TemplateAssetCatalogService.API))
            .extracting(TemplateAssetCatalogService.TemplateAsset::templateId)
            .contains(published);
        assertThat(service.listAuthorizedForRoleAndType("role-1", TemplateAssetCatalogService.API))
            .extracting(TemplateAssetCatalogService.TemplateAsset::templateId)
            .contains(published);
    }

    private PythonTemplate pythonTemplate(String id, String tenantId, String name) {
        PythonTemplate template = new PythonTemplate();
        template.setId(id);
        template.setTenantId(tenantId);
        template.setTemplateName(name);
        template.setDescription(name);
        template.setDomain("analytics");
        return template;
    }
}
