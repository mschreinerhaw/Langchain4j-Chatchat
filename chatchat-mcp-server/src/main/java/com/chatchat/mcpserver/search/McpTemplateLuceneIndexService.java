package com.chatchat.mcpserver.search;

import com.chatchat.mcpserver.database.DatabaseQueryConfig;
import com.chatchat.mcpserver.database.DatabaseQueryConfigService;
import com.chatchat.mcpserver.ops.CommandTemplateConfig;
import com.chatchat.mcpserver.ops.CommandTemplateService;
import com.chatchat.mcpserver.ops.HttpEndpointConfig;
import com.chatchat.mcpserver.ops.HttpEndpointConfigService;
import com.chatchat.mcpserver.sql.SqlDatasourceConfigService;
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
    private final DatabaseQueryConfigService databaseQueryConfigService;
    private final ObjectMapper objectMapper;

    @Order(Ordered.LOWEST_PRECEDENCE)
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        refreshAll();
    }

    public synchronized void refreshAll() {
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
        safe(databaseQueryConfigService.listAll()).stream()
            .map(this::databaseQueryTemplateDoc)
            .forEach(docs::add);
        luceneSearchService.indexTemplates(docs);
        log.info("MCP Lucene template index refreshed, indexed {} templates", docs.size());
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
        luceneSearchService.upsertTemplates(safe(templates).stream().map(this::databaseQueryTemplateDoc).toList());
        log.info("MCP Lucene database query template index upserted {} templates", safe(templates).size());
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

    private LuceneMcpSearchService.TemplateDoc databaseQueryTemplateDoc(DatabaseQueryConfig config) {
        List<String> signals = new ArrayList<>(readStringArray(config.getRoutingLabelsJson()));
        signals.addAll(readStringArray(config.getCapabilitiesJson()));
        signals.addAll(readStringArray(config.getTagsJson()));
        signals.addAll(governanceTerms(config.getGovernanceJson()));
        addTerms(signals, config.getToolName(), config.getTitle(), config.getDescription(), config.getSqlTemplate(),
            config.getTemplateIntent(), config.getDatabaseType(), config.getRiskLevel(), config.getOwner());
        return new LuceneMcpSearchService.TemplateDoc(
            config.getId(),
            "database_query",
            firstText(config.getTitle(), config.getToolName()),
            firstText(config.getDescription(), "") + " " + firstText(config.getSqlTemplate(), ""),
            "sql_template_registry",
            SqlDatasourceConfigService.normalizeDatabaseTypeToken(config.getDatabaseType()),
            String.join(" ", signals),
            firstText(config.getRiskLevel(), "read_only"),
            distinct(signals),
            "database_query_registry"
        );
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
