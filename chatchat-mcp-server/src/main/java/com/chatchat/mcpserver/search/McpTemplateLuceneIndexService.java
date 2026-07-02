package com.chatchat.mcpserver.search;

import com.chatchat.mcpserver.api.ApiServiceConfig;
import com.chatchat.mcpserver.api.ApiServiceConfigService;
import com.chatchat.mcpserver.database.DatabaseQueryConfig;
import com.chatchat.mcpserver.database.DatabaseQueryConfigService;
import com.chatchat.mcpserver.ops.CommandTemplateConfig;
import com.chatchat.mcpserver.ops.CommandTemplateService;
import com.chatchat.mcpserver.ops.HttpEndpointConfig;
import com.chatchat.mcpserver.ops.HttpEndpointConfigService;
import com.chatchat.mcpserver.sql.SqlDatasourceConfigService;
import com.chatchat.mcpserver.sql.SqlDatasourceConfig;
import com.chatchat.mcpserver.sql.SqlTemplateConfig;
import com.chatchat.mcpserver.sql.SqlTemplateService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class McpTemplateLuceneIndexService {

    private static final Set<String> RETIRED_SQL_METADATA_TEMPLATE_CODES = Set.of(
        "MYSQL_SCHEMA_TABLE_OVERVIEW",
        "MYSQL_TABLE_LOCATION",
        "MYSQL_TABLE_METADATA",
        "ORACLE_TABLE_METADATA",
        "POSTGRES_TABLE_METADATA",
        "SQLSERVER_TABLE_METADATA"
    );

    private final LuceneMcpSearchService luceneSearchService;
    private final CommandTemplateService commandTemplateService;
    private final SqlTemplateService sqlTemplateService;
    private final HttpEndpointConfigService httpEndpointConfigService;
    private final ApiServiceConfigService apiServiceConfigService;
    private final DatabaseQueryConfigService databaseQueryConfigService;
    private final SqlDatasourceConfigService datasourceConfigService;
    private final ObjectMapper objectMapper;

    @Order(Ordered.LOWEST_PRECEDENCE)
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        refreshAll();
    }

    public synchronized void refreshAll() {
        refreshTemplateIndex();
        refreshDatabaseQueryTemplateIndex();
        refreshApiServiceTemplateIndex();
    }

    public synchronized void refreshTemplateIndex() {
        if (luceneSearchService == null || !luceneSearchService.enabled()) {
            return;
        }
        List<LuceneMcpSearchService.TemplateDoc> docs = new ArrayList<>();
        safe(commandTemplateService.listEnabled()).stream()
            .map(this::commandTemplateDoc)
            .forEach(docs::add);
        safe(sqlTemplateService.listEnabled()).stream()
            .filter(template -> !isRetiredSqlMetadataTemplate(template))
            .map(this::sqlTemplateDoc)
            .forEach(docs::add);
        safe(httpEndpointConfigService.listEnabled()).stream()
            .map(this::httpTemplateDoc)
            .forEach(docs::add);
        luceneSearchService.indexTemplates(docs);
        log.info("MCP Lucene template index refreshed, indexed {} templates", docs.size());
    }

    public synchronized void refreshDatabaseQueryTemplateIndex() {
        if (luceneSearchService == null || !luceneSearchService.enabled()) {
            return;
        }
        List<LuceneMcpSearchService.TemplateDoc> docs = databaseQueryAssetDocs();
        luceneSearchService.indexDatabaseQueryTemplates(docs);
        log.info("MCP Lucene database query template index refreshed, indexed {} datasource assets", docs.size());
    }

    public synchronized void refreshApiServiceTemplateIndex() {
        if (luceneSearchService == null || !luceneSearchService.enabled()) {
            return;
        }
        List<LuceneMcpSearchService.TemplateDoc> docs = safe(apiServiceConfigService.listAll()).stream()
            .filter(ApiServiceConfig::isEnabled)
            .map(this::apiServiceTemplateDoc)
            .toList();
        luceneSearchService.indexApiServiceTemplates(docs);
        log.info("MCP Lucene API service template index refreshed, indexed {} templates", docs.size());
    }

    public void upsertCommandTemplates(List<CommandTemplateConfig> templates) {
        luceneSearchService.upsertTemplates(safe(templates).stream().map(this::commandTemplateDoc).toList());
    }

    public void upsertSqlTemplates(List<SqlTemplateConfig> templates) {
        luceneSearchService.upsertTemplates(safe(templates).stream()
            .filter(template -> !isRetiredSqlMetadataTemplate(template))
            .map(this::sqlTemplateDoc)
            .toList());
    }

    public void upsertDatabaseQueryTemplates(List<DatabaseQueryConfig> templates) {
        refreshDatabaseQueryTemplateIndex();
        log.info("MCP Lucene database query template index rebuilt after {} changed templates", safe(templates).size());
    }

    public void upsertApiServiceTemplates(List<ApiServiceConfig> templates) {
        luceneSearchService.upsertApiServiceTemplates(safe(templates).stream()
            .filter(ApiServiceConfig::isEnabled)
            .map(this::apiServiceTemplateDoc)
            .toList());
        log.info("MCP Lucene API service template index upserted {} templates", safe(templates).size());
    }

    private LuceneMcpSearchService.TemplateDoc commandTemplateDoc(CommandTemplateConfig template) {
        List<String> signals = new ArrayList<>(readStringArray(template.getIntentSignalsJson()));
        addTerms(signals, template.getCode(), template.getTitle(), template.getDescription(), template.getCategory());
        return new LuceneMcpSearchService.TemplateDoc(
            template.getCode(),
            "ssh_host",
            firstText(template.getTitle(), template.getCode()),
            firstText(template.getDescription(), ""),
            firstText(template.getCategory(), "system_diagnostic"),
            "generic",
            String.join(" ", signals),
            firstText(template.getRiskLevel(), "LOW"),
            distinct(signals),
            "command_template"
        );
    }

    private LuceneMcpSearchService.TemplateDoc sqlTemplateDoc(SqlTemplateConfig template) {
        List<String> signals = new ArrayList<>(readStringArray(template.getIntentSignalsJson()));
        signals.addAll(readStringArray(template.getRoutingLabelsJson()));
        addTerms(signals, template.getCode(), template.getTitle(), template.getDescription(), template.getCategory(),
            template.getDatabaseType(), template.getSqlTemplate());
        return new LuceneMcpSearchService.TemplateDoc(
            template.getCode(),
            "sql_datasource",
            firstText(template.getTitle(), template.getCode()),
            firstText(template.getDescription(), ""),
            firstText(template.getCategory(), "sql_diagnostic"),
            SqlDatasourceConfigService.normalizeDatabaseTypeToken(template.getDatabaseType()),
            String.join(" ", signals),
            firstText(template.getRiskLevel(), "MEDIUM"),
            distinct(signals),
            "sql_template"
        );
    }

    private LuceneMcpSearchService.TemplateDoc httpTemplateDoc(HttpEndpointConfig endpoint) {
        String templateId = firstText(endpoint.getToolName(), endpoint.getName());
        List<String> signals = new ArrayList<>(readStringArray(endpoint.getRoutingLabelsJson()));
        signals.addAll(readStringArray(endpoint.getCapabilitiesJson()));
        addDelimited(signals, endpoint.getTags());
        addTerms(signals, templateId, endpoint.getName(), endpoint.getTitle(), endpoint.getDescription(), endpoint.getCategory());
        return new LuceneMcpSearchService.TemplateDoc(
            templateId,
            "http_endpoint",
            firstText(endpoint.getTitle(), templateId),
            firstText(endpoint.getDescription(), ""),
            firstText(endpoint.getCategory(), "http_request"),
            "generic",
            String.join(" ", signals),
            "GET".equalsIgnoreCase(firstText(endpoint.getMethod(), "")) ? "LOW" : "MEDIUM",
            distinct(signals),
            "http_endpoint"
        );
    }

    private LuceneMcpSearchService.TemplateDoc apiServiceTemplateDoc(ApiServiceConfig config) {
        List<String> signals = new ArrayList<>(governanceTerms(config.getGovernanceJson()));
        addTerms(signals, config.getToolName(), config.getTitle(), config.getDescription(), config.getBusinessGroup(),
            config.getBusinessGroupName(), config.getBusinessGroupDescription(), config.getMethod());
        return new LuceneMcpSearchService.TemplateDoc(
            config.getToolName(),
            "api_service",
            firstText(config.getTitle(), config.getToolName()),
            firstText(config.getDescription(), "") + " "
                + firstText(config.getBusinessGroupName(), "") + " "
                + firstText(config.getBusinessGroupDescription(), ""),
            firstText(config.getBusinessGroup(), "api_service"),
            "generic",
            String.join(" ", signals),
            apiRiskLevel(config.getGovernanceJson()),
            distinct(signals),
            "api_service_registry"
        );
    }

    private List<LuceneMcpSearchService.TemplateDoc> databaseQueryAssetDocs() {
        Map<String, DatabaseQueryAssetTemplates> grouped = new LinkedHashMap<>();
        List<DatabaseQueryConfig> configs = databaseQueryConfigService == null
            ? List.of()
            : databaseQueryConfigService.listAll();
        for (DatabaseQueryConfig config : safe(configs)) {
            if (config == null || !config.isEnabled()) {
                continue;
            }
            databaseQueryDatasource(config).ifPresent(datasource -> {
                String key = firstText(datasource.getId(), firstText(datasource.getName(), datasource.getToolName()));
                if (key == null) {
                    return;
                }
                grouped.computeIfAbsent(key, ignored -> new DatabaseQueryAssetTemplates(datasource, new ArrayList<>()))
                    .templates()
                    .add(config);
            });
        }
        return grouped.values().stream()
            .map(this::databaseQueryAssetDoc)
            .toList();
    }

    private LuceneMcpSearchService.TemplateDoc databaseQueryAssetDoc(DatabaseQueryAssetTemplates asset) {
        SqlDatasourceConfig datasource = asset.datasource();
        List<DatabaseQueryConfig> templates = asset.templates();
        List<String> signals = new ArrayList<>();
        addTerms(signals,
            datasource.getName(),
            datasource.getTitle(),
            datasource.getToolName(),
            datasource.getDescription(),
            datasource.getEnvironment(),
            datasource.getDatabaseType(),
            datasource.getMetadataScopeValue(),
            "assetName",
            "env",
            "environment",
            "databaseType",
            "dbType",
            "executionContext"
        );
        signals.addAll(readStringArray(datasource.getRoutingLabelsJson()));
        signals.addAll(readStringArray(datasource.getCapabilitiesJson()));
        signals.addAll(governanceTerms(datasource.getGovernanceJson()));
        for (DatabaseQueryConfig config : templates) {
            signals.addAll(readStringArray(config.getRoutingLabelsJson()));
            signals.addAll(readStringArray(config.getCapabilitiesJson()));
            signals.addAll(readStringArray(config.getTagsJson()));
            signals.addAll(governanceTerms(config.getGovernanceJson()));
            addTerms(signals, config.getToolName(), config.getTitle(), config.getDescription(), config.getSqlTemplate(),
                config.getTemplateIntent(), config.getBusinessGroup(), config.getBusinessGroupName(),
                config.getBusinessGroupDescription(), config.getDatabaseType(), config.getRiskLevel(), config.getOwner());
        }
        return new LuceneMcpSearchService.TemplateDoc(
            firstText(datasource.getId(), firstText(datasource.getName(), datasource.getToolName())),
            "database_query",
            firstText(datasource.getTitle(), firstText(datasource.getName(), datasource.getToolName())),
            databaseQueryAssetDescription(datasource, templates),
            "sql_template_registry",
            SqlDatasourceConfigService.normalizeDatabaseTypeToken(datasource.getDatabaseType()),
            String.join(" ", signals),
            "read_only",
            distinct(signals),
            "database_query_asset_registry"
        );
    }

    private String databaseQueryAssetDescription(SqlDatasourceConfig datasource, List<DatabaseQueryConfig> templates) {
        List<String> values = new ArrayList<>();
        addTerms(values, datasource.getDescription(), datasource.getName(), datasource.getTitle(), datasource.getToolName(),
            datasource.getEnvironment(), datasource.getDatabaseType());
        for (DatabaseQueryConfig config : templates) {
            addTerms(values, config.getTitle(), config.getDescription(), config.getTemplateIntent(),
                config.getBusinessGroup(), config.getBusinessGroupName(), config.getBusinessGroupDescription());
        }
        return String.join(" ", distinct(values));
    }

    private record DatabaseQueryAssetTemplates(SqlDatasourceConfig datasource, List<DatabaseQueryConfig> templates) {
    }

    private java.util.Optional<SqlDatasourceConfig> databaseQueryDatasource(DatabaseQueryConfig config) {
        if (config == null || config.getDatasourceId() == null || config.getDatasourceId().isBlank()) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.ofNullable(datasourceConfigService.getEnabled(config.getDatasourceId()));
        } catch (Exception ignored) {
            return java.util.Optional.empty();
        }
    }

    private List<String> governanceTerms(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            Map<String, Object> map = objectMapper.readValue(json, new TypeReference<>() {});
            List<String> terms = new ArrayList<>();
            flattenValues(terms, map);
            return terms;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private void flattenValues(List<String> terms, Object value) {
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, item) -> {
                addTerms(terms, key == null ? null : String.valueOf(key));
                flattenValues(terms, item);
            });
            return;
        }
        if (value instanceof List<?> list) {
            list.forEach(item -> flattenValues(terms, item));
            return;
        }
        addTerms(terms, value == null ? null : String.valueOf(value));
    }

    private String apiRiskLevel(String json) {
        if (json == null || json.isBlank()) {
            return "read_only";
        }
        try {
            Map<String, Object> map = objectMapper.readValue(json, new TypeReference<>() {});
            Object risk = map.get("riskLevel");
            if (risk == null) {
                risk = map.get("risk_level");
            }
            return firstText(risk == null ? null : String.valueOf(risk), "read_only");
        } catch (Exception ignored) {
            return "read_only";
        }
    }

    private List<String> readStringArray(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private void addDelimited(List<String> terms, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        for (String item : value.split("[,;\\s]+")) {
            addTerms(terms, item);
        }
    }

    private void addTerms(List<String> terms, String... values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            String normalized = normalize(value);
            if (normalized != null) {
                terms.add(normalized);
            }
        }
    }

    private List<String> distinct(List<String> values) {
        Set<String> distinct = new LinkedHashSet<>();
        safe(values).stream()
            .map(this::normalize)
            .filter(value -> value != null)
            .forEach(distinct::add);
        return new ArrayList<>(distinct);
    }

    private boolean isRetiredSqlMetadataTemplate(SqlTemplateConfig template) {
        String code = template == null ? null : normalize(template.getCode());
        return code != null && RETIRED_SQL_METADATA_TEMPLATE_CODES.contains(code.toUpperCase(Locale.ROOT));
    }

    private <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private String firstText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
