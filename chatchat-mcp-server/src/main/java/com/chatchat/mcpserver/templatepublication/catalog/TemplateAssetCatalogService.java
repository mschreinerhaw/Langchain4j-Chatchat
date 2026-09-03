package com.chatchat.mcpserver.templatepublication.catalog;

import com.chatchat.mcpserver.api.registry.ApiServiceConfigService;
import com.chatchat.mcpserver.category.BusinessCategory;
import com.chatchat.mcpserver.category.BusinessCategoryService;
import com.chatchat.mcpserver.authorization.McpAuthorizationService;
import com.chatchat.mcpserver.authorization.McpScopeExpression;
import com.chatchat.mcpserver.database.definition.DatabaseQueryConfigService;
import com.chatchat.mcpserver.ops.command.CommandTemplateService;
import com.chatchat.mcpserver.ops.http.HttpEndpointConfigService;
import com.chatchat.mcpserver.ops.ssh.SshHostConfigService;
import com.chatchat.mcpserver.python.PythonMcpToolPublisher;
import com.chatchat.mcpserver.python.PythonTemplateCatalog;
import com.chatchat.mcpserver.sql.datasource.SqlDatasourceConfigService;
import com.chatchat.mcpserver.sql.template.SqlTemplateService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TemplateAssetCatalogService {

    public static final String SSH = "ssh_host";
    public static final String SQL = "sql_datasource";
    public static final String HTTP = "http_endpoint";
    public static final String DATABASE_QUERY = "database_query";
    public static final String API = "api_service";
    public static final String PYTHON = "python_runtime";

    private final CommandTemplateService commandTemplateService;
    private final SqlTemplateService sqlTemplateService;
    private final HttpEndpointConfigService httpEndpointConfigService;
    private final DatabaseQueryConfigService databaseQueryConfigService;
    private final ApiServiceConfigService apiServiceConfigService;
    private final PythonTemplateCatalog pythonTemplateCatalog;
    private final BusinessCategoryService businessCategoryService;
    private final SshHostConfigService sshHostConfigService;
    private final SqlDatasourceConfigService sqlDatasourceConfigService;
    private final McpAuthorizationService authorizationService;
    private final ObjectMapper objectMapper;

    public List<TemplateAsset> listEnabled() {
        return entries().stream().map(CatalogEntry::asset).toList();
    }

    public List<TemplateAsset> listAuthorizedForRole(String roleId) {
        McpAuthorizationService.RoleView role = authorizationService.roles(null).stream()
            .filter(item -> item.id().equals(roleId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Role not found: " + roleId));
        return entries().stream()
            .filter(entry -> entry.tenantId() == null || entry.tenantId().equals(role.tenantId()))
            .filter(entry -> entry.authorizationRefs().stream().anyMatch(ref ->
                authorizationService.roleAllows(role.id(), role.tenantId(), ref.toolName(), ref.scope(role.tenantId()))))
            .map(CatalogEntry::asset)
            .toList();
    }

    public List<TemplateAsset> listAuthorizedForRoleAndType(String roleId, String assetType) {
        if (assetType == null || assetType.isBlank()) {
            throw new IllegalArgumentException("assetType is required");
        }
        return listAuthorizedForRole(roleId).stream()
            .filter(asset -> assetType.equals(asset.assetType()))
            .toList();
    }

    private List<CatalogEntry> entries() {
        List<BusinessCategory> businessCategories = businessCategoryService.listEnabled();
        Map<String, BusinessCategory> categoriesById = businessCategories.stream()
            .collect(Collectors.toMap(BusinessCategory::getId, Function.identity()));
        Map<String, BusinessCategory> categoriesByCode = businessCategories.stream()
            .collect(Collectors.toMap(item -> normalize(item.getCode()), Function.identity(), (left, right) -> left));
        var hosts = sshHostConfigService.listEnabled();
        var datasources = sqlDatasourceConfigService.listEnabled();
        List<CatalogEntry> result = new ArrayList<>();
        commandTemplateService.listEnabled().forEach(item -> result.add(entry(asset(
            SSH, item.getCode(), item.getTitle(), item.getDescription(), item.getCategory(),
            category(categoriesById, categoriesByCode, null, item.getCategory(), item.getCategory())),
            refsForCommand(item.getCode(), hosts))));
        sqlTemplateService.listEnabled().forEach(item -> result.add(entry(asset(
            SQL, item.getCode(), item.getTitle(), item.getDescription(), item.getCategory(),
            category(categoriesById, categoriesByCode, null, item.getCategory(), item.getCategory())),
            refsForSqlTemplate(item.getCode(), datasources))));
        httpEndpointConfigService.listEnabled().forEach(item -> {
            String templateId = firstText(item.getToolName(), firstText(item.getName(), item.getId()));
            result.add(entry(asset(HTTP, templateId, firstText(item.getTitle(), item.getName()),
                item.getDescription(), item.getCategory(), category(categoriesById, categoriesByCode,
                    item.getCategoryId(), item.getCategory(), item.getCategory())),
                List.of(new AuthorizationRef(templateId, HTTP, "execute", "request", item.getId()))));
        });
        databaseQueryConfigService.listEnabled().forEach(item -> result.add(entry(asset(
            DATABASE_QUERY, item.getToolName(), item.getTitle(), item.getDescription(), item.getCapabilityCategory(),
            category(categoriesById, categoriesByCode, item.getCategoryId(),
                firstText(item.getCapabilityCategory(), item.getBusinessGroup()), item.getBusinessGroupName())),
            List.of(new AuthorizationRef(item.getToolName(), null, null, null, null)))));
        apiServiceConfigService.listEnabled().forEach(item -> result.add(entry(asset(
            API, item.getToolName(), item.getTitle(), item.getDescription(), item.getBusinessGroup(),
            category(categoriesById, categoriesByCode, item.getCategoryId(),
                item.getBusinessGroup(), item.getBusinessGroupName())),
            List.of(new AuthorizationRef(item.getToolName(), null, null, null, null)))));
        pythonTemplateCatalog.listPublished().forEach(item -> result.add(entry(asset(
            PYTHON, item.getId(), item.getTemplateName(), item.getDescription(), item.getDomain(),
            category(categoriesById, categoriesByCode, item.getCategoryId(), item.getDomain(), item.getDomain())),
            List.of(new AuthorizationRef(PythonMcpToolPublisher.ANALYSIS_RUN_TOOL, null, null, null, null)),
            item.getTenantId())));
        return result.stream()
            .sorted(Comparator.comparing((CatalogEntry item) -> item.asset().assetType())
                .thenComparing(item -> item.asset().title()))
            .toList();
    }

    public boolean contains(String key) {
        return listEnabled().stream().anyMatch(item -> item.key().equals(key));
    }

    private List<AuthorizationRef> refsForCommand(String templateId,
                                                   List<com.chatchat.mcpserver.ops.ssh.SshHostConfig> hosts) {
        List<AuthorizationRef> refs = new ArrayList<>();
        refs.add(new AuthorizationRef(templateId, null, null, null, null));
        hosts.stream().filter(host -> readIds(host.getAllowedCommandsJson()).contains(templateId)).forEach(host ->
            refs.add(new AuthorizationRef(firstText(host.getToolName(), firstText(host.getName(), host.getId())),
                SSH, "execute", "command", host.getId())));
        return List.copyOf(refs);
    }

    private List<AuthorizationRef> refsForSqlTemplate(String templateId,
                                                       List<com.chatchat.mcpserver.sql.datasource.SqlDatasourceConfig> datasources) {
        List<AuthorizationRef> refs = new ArrayList<>();
        refs.add(new AuthorizationRef(templateId, null, null, null, null));
        datasources.stream().filter(item -> readIds(item.getAllowedTemplatesJson()).contains(templateId)).forEach(item ->
            refs.add(new AuthorizationRef(firstText(item.getToolName(), item.getId()),
                SQL, "execute", "query", item.getId())));
        return List.copyOf(refs);
    }

    private Set<String> readIds(String json) {
        if (json == null || json.isBlank()) {
            return Set.of();
        }
        try {
            return Set.copyOf(objectMapper.readValue(json, new TypeReference<List<String>>() { }));
        } catch (Exception ex) {
            return Set.of();
        }
    }

    private CatalogEntry entry(TemplateAsset asset, List<AuthorizationRef> refs) {
        return entry(asset, refs, null);
    }

    private CatalogEntry entry(TemplateAsset asset, List<AuthorizationRef> refs, String tenantId) {
        return new CatalogEntry(asset, refs, tenantId);
    }

    private TemplateAsset asset(String assetType, String templateId, String title,
                                String description, String category, CategoryRef businessCategory) {
        return new TemplateAsset(assetType + ":" + templateId, assetType, templateId,
            firstText(title, templateId), firstText(description, ""), firstText(category, ""),
            businessCategory.code(), businessCategory.name());
    }

    private CategoryRef category(Map<String, BusinessCategory> categoriesById,
                                 Map<String, BusinessCategory> categoriesByCode,
                                 String categoryId, String fallbackCode, String fallbackName) {
        BusinessCategory category = categoryId == null ? null : categoriesById.get(categoryId);
        if (category == null && fallbackCode != null) {
            category = categoriesByCode.get(normalize(fallbackCode));
        }
        if (category != null) {
            return new CategoryRef(firstText(category.getCode(), ""), firstText(category.getName(), category.getCode()));
        }
        String code = firstText(fallbackCode, "");
        return new CategoryRef(code, firstText(fallbackName, code));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String firstText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    public record TemplateAsset(String key, String assetType, String templateId, String title,
                                String description, String category, String businessCategoryCode,
                                String businessCategoryName) { }

    private record CategoryRef(String code, String name) { }

    private record CatalogEntry(TemplateAsset asset, List<AuthorizationRef> authorizationRefs,
                                String tenantId) { }

    private record AuthorizationRef(String toolName, String assetType, String capability,
                                    String action, String domain) {
        McpScopeExpression scope(String tenantId) {
            return assetType == null ? null : McpScopeExpression.of(
                assetType, capability, action, tenantId, domain, "read");
        }
    }
}
