package com.chatchat.mcpserver.templatepublication.catalog;

import com.chatchat.mcpserver.api.registry.ApiServiceConfigService;
import com.chatchat.mcpserver.category.BusinessCategory;
import com.chatchat.mcpserver.category.BusinessCategoryService;
import com.chatchat.mcpserver.authorization.McpAuthorizationService;
import com.chatchat.common.security.McpScopeExpression;
import com.chatchat.mcpserver.database.definition.DatabaseQueryConfigService;
import com.chatchat.mcpserver.external.ExternalMcpRegistryService;
import com.chatchat.mcpserver.external.ExternalMcpService;
import com.chatchat.mcpserver.external.ExternalMcpToolPublisher;
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
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
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
    @Autowired(required = false)
    private ExternalMcpRegistryService externalMcpRegistry;
    @Autowired(required = false)
    private com.chatchat.common.retrieval.ResourceAuthorizationPort templateAuthorization;

    /** Business catalog intersects template grants with the actual asset ACL; never returns secrets. */
    public List<BoundTemplate> businessTemplates(boolean management) {
        var caller = authorizationService.currentCallerContext();
        if (caller == null || caller.userId() == null || caller.tenantId() == null) return List.of();
        var entries = entries().stream().filter(entry -> entry.tenantId() == null
            || entry.tenantId().equals(caller.tenantId())).toList();
        Set<String> candidates = entries.stream().map(entry -> entry.asset().key()).collect(Collectors.toSet());
        Set<String> allowed = management ? candidates : templateAuthorization == null ? Set.of()
            : templateAuthorization.allowedIds(com.chatchat.common.retrieval.ResourceAuthorizationPort.TEMPLATE,
                caller.tenantId(), caller.userId(), Set.of(), candidates);
        List<BoundTemplate> result = new ArrayList<>();
        for (var entry : entries) {
            var asset = entry.asset();
            if (!allowed.contains(asset.key())) continue;
            if (externalMcpRegistry != null && asset.templateId().startsWith("external_")) {
                for (var service : externalMcpRegistry.list()) {
                    if (!service.isEnabled()) continue;
                    for (var template : externalMcpRegistry.templates(service)) {
                        String name = ExternalMcpToolPublisher.publishedName(service.getId(), template.name());
                        if (template.readOnly() && name.equals(asset.templateId())
                            && (management || authorizationService.callerAllows(name, null))) {
                            result.add(bound(asset, service.getId(), service.getName(), name, Map.of(), service, template));
                        }
                    }
                }
                continue;
            }
            switch (asset.assetType()) {
                case SSH -> {
                    var template = commandTemplateService.listEnabled().stream()
                        .filter(t -> t.getCode().equals(asset.templateId())).findFirst().orElse(null);
                    if (template == null) continue;
                    for (var host : sshHostConfigService.listEnabled()) {
                        if (!readIds(host.getAllowedCommandsJson()).contains(template.getCode())) continue;
                        String identity = firstText(host.getToolName(), firstText(host.getName(), host.getId()));
                        String level = "HIGH".equalsIgnoreCase(template.getRiskLevel()) ? "manage" : "MEDIUM".equalsIgnoreCase(template.getRiskLevel()) ? "write" : "read";
                        if (!management && !authorizationService.callerAllows(identity,
                            McpScopeExpression.of(SSH, "execute", "command", caller.tenantId(), host.getId(), level))) continue;
                        result.add(bound(asset, host.getId(), host.getName(), "linux_command_execute",
                            Map.of("hostId", host.getId(), "template", template.getCode(), "permissionLevel", level), template, host));
                    }
                }
                case SQL -> {
                    var template = sqlTemplateService.listEnabled().stream()
                        .filter(t -> t.getCode().equals(asset.templateId())).findFirst().orElse(null);
                    if (template == null) continue;
                    for (var datasource : sqlDatasourceConfigService.listEnabled()) {
                        var templateAllowlist = readIds(datasource.getAllowedTemplatesJson());
                        if (!templateAllowlist.isEmpty() && !templateAllowlist.contains(template.getCode())
                            || !sqlTemplateService.isCompatible(template, datasource)) continue;
                        if (!management && !authorizationService.callerAllows(firstText(datasource.getToolName(), datasource.getId()),
                            McpScopeExpression.of(SQL, "execute", "query", caller.tenantId(), datasource.getId(), "read"))) continue;
                        result.add(bound(asset, datasource.getId(), datasource.getName(), "sql_query_execute",
                            Map.of("datasourceId", datasource.getId(), "template", template.getCode(), "permissionLevel", "read"), template, datasource));
                    }
                }
                case HTTP -> httpEndpointConfigService.listEnabled().stream()
                    .filter(t -> asset.templateId().equals(t.getToolName()))
                    .filter(t -> management || authorizationService.callerAllowsOperation(t.getToolName(), HTTP, t.getId(), httpLevel(t.getMethod())))
                    .forEach(t -> result.add(bound(asset, t.getId(), t.getName(), "http_request_execute",
                        Map.of("endpointId", t.getId(), "template", t.getToolName(), "permissionLevel", httpLevel(t.getMethod())), t)));
                case DATABASE_QUERY -> {
                    for (var template : databaseQueryConfigService.listEnabled()) {
                        if (!asset.templateId().equals(template.getToolName())) continue;
                        var datasource = sqlDatasourceConfigService.listEnabled().stream()
                            .filter(item -> item.getId().equals(template.getDatasourceId())).findFirst().orElse(null);
                        if (datasource == null) continue;
                        if (!management && (!authorizationService.callerAllows(template.getToolName(), null)
                            || !authorizationService.callerAllows(firstText(datasource.getToolName(), datasource.getId()),
                                McpScopeExpression.of(SQL, "execute", "query", caller.tenantId(), datasource.getId(), "read")))) continue;
                        result.add(bound(asset, datasource.getId(), datasource.getName(), "sql_query_execute",
                            Map.of("datasourceId", datasource.getId(), "template", template.getToolName(), "permissionLevel", "read"), template, datasource));
                    }
                }
                case API -> {
                    for (var template : apiServiceConfigService.listEnabled()) {
                        if (!asset.templateId().equals(template.getToolName()) || !management && !authorizationService.callerAllowsOperation(
                            template.getToolName(), API, template.getId(), httpLevel(template.getMethod()))) continue;
                        var gateway = httpEndpointConfigService.listEnabled().stream().filter(item -> item.getId().equals(template.getGatewayId())).findFirst();
                        if (template.getGatewayId() != null && !template.getGatewayId().isBlank() && gateway.isEmpty()) continue;
                        result.add(bound(asset, template.getId(), template.getTitle(), "api_template_execute",
                            Map.of("templateId", template.getToolName(), "permissionLevel", httpLevel(template.getMethod())), template, gateway.orElse(null)));
                    }
                }
                case PYTHON -> {
                    for (var template : pythonTemplateCatalog.listPublished()) {
                        if (!caller.tenantId().equals(template.getTenantId()) || !asset.templateId().equals(template.getId())) continue;
                        var environment = pythonTemplateCatalog.publishedEnvironment(template);
                        if (environment == null || !management && !authorizationService.callerAllowsOperation(
                            PythonMcpToolPublisher.TEMPLATE_EXECUTE_TOOL, PYTHON, template.getAssetId(), "write")) continue;
                        result.add(bound(asset, template.getAssetId(), template.getAssetName(), PythonMcpToolPublisher.TEMPLATE_EXECUTE_TOOL,
                            Map.of("templateId", template.getId(), "permissionLevel", "write"), template, environment));
                    }
                }
                default -> { }
            }
        }
        return List.copyOf(result);
    }

    private BoundTemplate bound(TemplateAsset template, String assetId, String assetName, String executor,
                                Map<String, Object> binding, Object... definitions) {
        try {
            var contract = new LinkedHashMap<>(template.parameterSchema());
            contract.put("type", "object"); contract.putIfAbsent("properties", Map.of());
            var dependencies = objectMapper.createArrayNode();
            for (var definition : definitions) {
                var dependency = objectMapper.valueToTree(definition);
                if (dependency.isObject()) ((com.fasterxml.jackson.databind.node.ObjectNode) dependency).remove(
                    List.of("usageCount", "rating", "lastUsedAt", "lastExecutionAt", "discoveredAt", "createdAt", "updatedAt"));
                dependencies.add(dependency);
            }
            var snapshot = objectMapper.valueToTree(List.of(template, binding, dependencies));
            String revision = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(objectMapper.writeValueAsBytes(snapshot)));
            return new BoundTemplate(template.key() + "@" + assetId, template.key(), template.title(),
                template.description(), firstText(assetName, "关联资源"), template.businessCategoryName(),
                contract, revision, executor, binding);
        } catch (Exception ex) { throw new IllegalStateException("Cannot snapshot template contract", ex); }
    }

    private String httpLevel(String method) { return method != null && Set.of("GET", "HEAD", "OPTIONS").contains(method.toUpperCase(Locale.ROOT)) ? "read" : "write"; }

    public record BoundTemplate(String key, String templateKey, String title, String description,
                                String assetName, String category, Map<String, Object> inputSchema,
                                String revision, String executor, Map<String, Object> binding) {
        public Map<String, Object> publicView() {
            return Map.of("key", key, "templateKey", templateKey, "title", title, "description", description,
                "assetName", assetName, "category", category, "inputSchema", inputSchema, "revision", revision,
                "operationLevel", binding.getOrDefault("permissionLevel", "read"));
        }
    }

    public List<TemplateAsset> listEnabled() {
        return entries().stream().map(CatalogEntry::asset).toList();
    }

    /** Loads only the requested asset family when a bound child route already fixed it. */
    public List<TemplateAsset> listEnabledForType(String assetType) {
        if (API.equals(assetType)) {
            return java.util.stream.Stream.concat(apiEntries().stream(), externalMcpEntries().stream()
                .filter(entry -> API.equals(entry.asset().assetType())))
                .map(CatalogEntry::asset).toList();
        }
        return listEnabled().stream().filter(asset -> assetType.equals(asset.assetType())).toList();
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

    /** Generic discovery consumes RBAC grants, independently of child-tool publication bindings. */
    public List<TemplateAsset> listAuthorizedForCallerAndType(String assetType) {
        var caller = authorizationService.currentCallerContext();
        if (caller == null || caller.tenantId() == null || caller.userId() == null) return List.of();
        return entries().stream()
            .filter(entry -> assetType.equals(entry.asset().assetType()))
            .filter(entry -> entry.tenantId() == null || caller.tenantId().equals(entry.tenantId()))
            .filter(entry -> entry.authorizationRefs().stream().anyMatch(ref ->
                authorizationService.callerAllows(ref.toolName(), ref.scope(caller.tenantId()))))
            .map(CatalogEntry::asset).toList();
    }

    public List<TemplateAsset> listAuthorizedForRoleAndType(String roleId, String assetType) {
        if (assetType == null || assetType.isBlank()) {
            throw new IllegalArgumentException("assetType is required");
        }
        if (!API.equals(assetType)) {
            return listAuthorizedForRole(roleId).stream()
                .filter(asset -> assetType.equals(asset.assetType()))
                .toList();
        }
        McpAuthorizationService.RoleView role = authorizationService.roles(null).stream()
            .filter(item -> item.id().equals(roleId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Role not found: " + roleId));
        return java.util.stream.Stream.concat(apiEntries().stream(), externalMcpEntries().stream()
                .filter(entry -> API.equals(entry.asset().assetType())))
            .filter(entry -> entry.tenantId() == null || entry.tenantId().equals(role.tenantId()))
            .filter(entry -> entry.authorizationRefs().stream().anyMatch(ref ->
                authorizationService.roleAllows(role.id(), role.tenantId(), ref.toolName(), ref.scope(role.tenantId()))))
            .map(CatalogEntry::asset)
            .toList();
    }

    private List<CatalogEntry> apiEntries() {
        List<BusinessCategory> businessCategories = businessCategoryService.listEnabled();
        Map<String, BusinessCategory> categoriesById = businessCategories.stream()
            .collect(Collectors.toMap(BusinessCategory::getId, Function.identity()));
        Map<String, BusinessCategory> categoriesByCode = businessCategories.stream()
            .collect(Collectors.toMap(item -> normalize(item.getCode()), Function.identity(), (left, right) -> left));
        return apiServiceConfigService.listEnabled().stream()
            .map(item -> entry(asset(
                API, item.getToolName(), item.getTitle(), item.getDescription(), item.getBusinessGroup(),
                category(categoriesById, categoriesByCode, item.getCategoryId(),
                    item.getBusinessGroup(), item.getBusinessGroupName()), item.getInputSchemaJson()),
                List.of(new AuthorizationRef(item.getToolName(), null, null, null, null))))
            .sorted(Comparator.comparing(item -> item.asset().title()))
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
            category(categoriesById, categoriesByCode, null, item.getCategory(), item.getCategory()),
            item.getParameterSchemaJson()),
            refsForCommand(item.getCode(), hosts))));
        sqlTemplateService.listEnabled().forEach(item -> result.add(entry(asset(
            SQL, item.getCode(), item.getTitle(), item.getDescription(), item.getCategory(),
            category(categoriesById, categoriesByCode, null, item.getCategory(), item.getCategory()),
            item.getParameterSchemaJson()),
            refsForSqlTemplate(item.getCode(), datasources))));
        httpEndpointConfigService.listEnabled().forEach(item -> {
            String templateId = firstText(item.getToolName(), firstText(item.getName(), item.getId()));
            result.add(entry(asset(HTTP, templateId, firstText(item.getTitle(), item.getName()),
                item.getDescription(), item.getCategory(), category(categoriesById, categoriesByCode,
                    item.getCategoryId(), item.getCategory(), item.getCategory()), item.getInputSchemaJson()),
                List.of(new AuthorizationRef(templateId, HTTP, "execute", "request", item.getId()))));
        });
        databaseQueryConfigService.listEnabled().forEach(item -> result.add(entry(asset(
            DATABASE_QUERY, item.getToolName(), item.getTitle(), item.getDescription(), item.getCapabilityCategory(),
            category(categoriesById, categoriesByCode, item.getCategoryId(),
                firstText(item.getCapabilityCategory(), item.getBusinessGroup()), item.getBusinessGroupName()),
            item.getInputSchemaJson()).withQueryFamily(databaseQueryFamily(item, datasources)),
            List.of(new AuthorizationRef(item.getToolName(), null, null, null, null)))));
        apiServiceConfigService.listEnabled().forEach(item -> result.add(entry(asset(
            API, item.getToolName(), item.getTitle(), item.getDescription(), item.getBusinessGroup(),
            category(categoriesById, categoriesByCode, item.getCategoryId(),
                item.getBusinessGroup(), item.getBusinessGroupName()), item.getInputSchemaJson()),
            List.of(new AuthorizationRef(item.getToolName(), null, null, null, null)))));
        pythonTemplateCatalog.listPublished().forEach(item -> result.add(entry(asset(
            PYTHON, item.getId(), item.getTemplateName(), item.getDescription(), item.getDomain(),
            category(categoriesById, categoriesByCode, item.getCategoryId(), item.getDomain(), item.getDomain()),
            item.getInputSchemaJson()),
            List.of(new AuthorizationRef(PythonMcpToolPublisher.ANALYSIS_RUN_TOOL, null, null, null, null)),
            item.getTenantId())));
        result.addAll(externalMcpEntries());
        return result.stream()
            .sorted(Comparator.comparing((CatalogEntry item) -> item.asset().assetType())
                .thenComparing(item -> item.asset().title()))
            .toList();
    }

    public boolean contains(String key) {
        return listEnabled().stream().anyMatch(item -> item.key().equals(key));
    }

    private List<CatalogEntry> externalMcpEntries() {
        if (externalMcpRegistry == null) return List.of();
        List<CatalogEntry> entries = new ArrayList<>();
        for (ExternalMcpService service : externalMcpRegistry.list()) {
            if (!service.isEnabled()) continue;
            String type = externalMcpRegistry.parentAssetType(service);
            for (ExternalMcpRegistryService.ToolTemplate template : externalMcpRegistry.templates(service)) {
                if (!template.readOnly()) continue;
                String publishedName = ExternalMcpToolPublisher.publishedName(service.getId(), template.name());
                TemplateAsset asset = new TemplateAsset(type + ":" + publishedName,
                    type, publishedName, template.title(), template.description(), service.getName(),
                    "external_mcp", service.getName(), template.inputSchema());
                String queryFamily = com.chatchat.mcpserver.database.definition.DatabaseQueryFamily.forParent(service.getParentToolName());
                if (queryFamily != null) asset = asset.withQueryFamily(queryFamily);
                entries.add(entry(asset, List.of(new AuthorizationRef(publishedName, null, null, null, null))));
            }
        }
        return entries;
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
                                String description, String category, CategoryRef businessCategory,
                                String parameterSchemaJson) {
        return new TemplateAsset(assetType + ":" + templateId, assetType, templateId,
            firstText(title, templateId), firstText(description, ""), firstText(category, ""),
            businessCategory.code(), businessCategory.name(), schema(parameterSchemaJson));
    }

    private Map<String, Object> schema(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> value = objectMapper.readValue(
                json, new TypeReference<Map<String, Object>>() { });
            return value == null ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(value));
        } catch (Exception ex) {
            return Map.of();
        }
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
                                String businessCategoryName, Map<String, Object> parameterSchema, String queryFamily) {
        public TemplateAsset(String key, String assetType, String templateId, String title, String description,
                             String category, String businessCategoryCode, String businessCategoryName, Map<String, Object> parameterSchema) {
            this(key, assetType, templateId, title, description, category, businessCategoryCode, businessCategoryName, parameterSchema,
                DATABASE_QUERY.equals(assetType) ? "relational" : null);
        }
        public TemplateAsset withQueryFamily(String family) {
            return new TemplateAsset(key, assetType, templateId, title, description, category, businessCategoryCode, businessCategoryName, parameterSchema, family);
        }
    }

    private String databaseQueryFamily(com.chatchat.mcpserver.database.definition.DatabaseQueryConfig query,
                                     List<com.chatchat.mcpserver.sql.datasource.SqlDatasourceConfig> datasources) {
        String type = datasources.stream().filter(asset -> asset.getId().equals(query.getDatasourceId()))
            .map(asset -> com.chatchat.mcpserver.sql.datasource.NativeQueryDatasource.isNative(asset)
                ? com.chatchat.mcpserver.sql.datasource.NativeQueryDatasource.type(asset) : asset.getDatabaseType())
            .filter(java.util.Objects::nonNull).findFirst().orElse(query.getDatabaseType());
        return com.chatchat.mcpserver.database.definition.DatabaseQueryFamily.fromDatabaseType(type);
    }

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
