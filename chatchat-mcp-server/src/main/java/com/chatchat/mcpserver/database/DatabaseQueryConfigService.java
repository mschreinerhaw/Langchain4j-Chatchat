package com.chatchat.mcpserver.database;

import com.chatchat.agents.protocol.ModelProtocolJson;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.tools.workflow.SqlWorkflowEngine;
import com.chatchat.tools.workflow.SqlWorkflowNode;
import com.chatchat.tools.workflow.SqlWorkflowParameterMapping;
import com.chatchat.mcpserver.api.ApiServiceConfigRepository;
import com.chatchat.mcpserver.search.LuceneMcpSearchService;
import com.chatchat.mcpserver.sql.SqlDatasourceConfig;
import com.chatchat.mcpserver.sql.SqlDatasourceConfigService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class DatabaseQueryConfigService {

    private static final Pattern TOOL_NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,128}$");
    private static final Set<String> RESERVED_TOOL_NAMES = Set.of("database_query", "database_query_execute");
    private static final Set<String> PARAMETER_SOURCE_TYPES = Set.of("USER_INPUT", "SYSTEM_CONTEXT", "UPSTREAM_RESULT", "STATIC");
    private static final SqlWorkflowEngine SQL_WORKFLOW_ENGINE = new SqlWorkflowEngine();

    private final DatabaseQueryConfigRepository repository;
    private final ApiServiceConfigRepository apiServiceConfigRepository;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final SqlDatasourceConfigService datasourceConfigService;
    private final LuceneMcpSearchService luceneSearchService;

    public DatabaseQueryConfigService(DatabaseQueryConfigRepository repository,
                                      ApiServiceConfigRepository apiServiceConfigRepository,
                                      ToolRegistry toolRegistry,
                                      ObjectMapper objectMapper,
                                      SqlDatasourceConfigService datasourceConfigService) {
        this(repository, apiServiceConfigRepository, toolRegistry, objectMapper, datasourceConfigService, null);
    }

    @Autowired
    public DatabaseQueryConfigService(DatabaseQueryConfigRepository repository,
                                      ApiServiceConfigRepository apiServiceConfigRepository,
                                      ToolRegistry toolRegistry,
                                      ObjectMapper objectMapper,
                                      SqlDatasourceConfigService datasourceConfigService,
                                      LuceneMcpSearchService luceneSearchService) {
        this.repository = repository;
        this.apiServiceConfigRepository = apiServiceConfigRepository;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
        this.datasourceConfigService = datasourceConfigService;
        this.luceneSearchService = luceneSearchService;
    }

    /**
     * Lists the all.
     *
     * @return the all list
     */
    @Transactional(readOnly = true)
    public List<DatabaseQueryConfig> listAll() {
        return repository.findAllByOrderByToolNameAsc();
    }

    @Transactional(readOnly = true)
    public List<DatabaseQueryConfig> search(String query) {
        String keyword = blankToNull(query);
        if (keyword == null) {
            return listAll();
        }
        List<DatabaseQueryConfig> all = listAll();
        if (luceneSearchService == null || !luceneSearchService.enabled()) {
            return List.of();
        }
        List<DatabaseQueryConfig> indexable = all.stream()
            .filter(item -> item.getId() != null && !item.getId().isBlank())
            .toList();
        Map<String, DatabaseQueryConfig> byId = indexable.stream()
            .collect(java.util.stream.Collectors.toMap(
                DatabaseQueryConfig::getId,
                item -> item,
                (first, ignored) -> first,
                java.util.LinkedHashMap::new
            ));
        return luceneSearchService.searchDatabaseQueryTemplates(
                indexable.stream().map(this::templateDoc).toList(),
                new LuceneMcpSearchService.TemplateSearchRequest("database_query", null, keyword, 50)
            ).stream()
            .map(hit -> byId.get(hit.id()))
            .filter(item -> item != null)
            .toList();
    }

    /**
     * Lists the enabled.
     *
     * @return the enabled list
     */
    @Transactional(readOnly = true)
    public List<DatabaseQueryConfig> listEnabled() {
        return repository.findByEnabledTrueOrderByToolNameAsc().stream()
            .filter(this::hasUsableDatasource)
            .toList();
    }

    /**
     * Returns the by id.
     *
     * @param id the id value
     * @return the by id
     */
    @Transactional(readOnly = true)
    public DatabaseQueryConfig getById(String id) {
        return repository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Database query config not found: " + id));
    }

    /**
     * Creates the create.
     *
     * @param draft the draft value
     * @return the created create
     */
    @Transactional
    public DatabaseQueryConfig create(DatabaseQueryConfig draft) {
        validate(draft, null);
        return repository.save(draft);
    }

    /**
     * Updates the update.
     *
     * @param id the id value
     * @param draft the draft value
     * @return the updated update
     */
    @Transactional
    public DatabaseQueryConfig update(String id, DatabaseQueryConfig draft) {
        DatabaseQueryConfig current = getById(id);
        current.setToolName(draft.getToolName());
        current.setTitle(draft.getTitle());
        current.setDatasourceId(draft.getDatasourceId());
        current.setDescription(draft.getDescription());
        current.setImplementationSteps(draft.getImplementationSteps());
        current.setBusinessGroup(draft.getBusinessGroup());
        current.setBusinessGroupName(draft.getBusinessGroupName());
        current.setBusinessGroupDescription(draft.getBusinessGroupDescription());
        current.setSqlTemplate(draft.getSqlTemplate());
        current.setSqlStepsJson(draft.getSqlStepsJson());
        current.setInputSchemaJson(draft.getInputSchemaJson());
        current.setGovernanceJson(draft.getGovernanceJson());
        current.setRoutingLabelsJson(draft.getRoutingLabelsJson());
        current.setRoutingLabels(draft.getRoutingLabels());
        current.setCapabilitiesJson(draft.getCapabilitiesJson());
        current.setCapabilities(draft.getCapabilities());
        current.setTemplateIntent(draft.getTemplateIntent());
        current.setDatabaseType(draft.getDatabaseType());
        current.setTagsJson(draft.getTagsJson());
        current.setRiskLevel(draft.getRiskLevel());
        current.setOwner(draft.getOwner());
        current.setRating(draft.getRating());
        current.setUsageCount(draft.getUsageCount());
        current.setMaxRows(draft.getMaxRows());
        current.setTimeoutSeconds(draft.getTimeoutSeconds());
        current.setCacheEnabled(draft.isCacheEnabled());
        current.setCacheTtlSeconds(draft.getCacheTtlSeconds());
        current.setCacheStorage(normalizeCacheStorage(draft.getCacheStorage()));
        current.setJdbcUrl(draft.getJdbcUrl());
        current.setDriverClass(draft.getDriverClass());
        current.setUsername(draft.getUsername());
        current.setPassword(draft.getPassword());
        current.setReloadDrivers(draft.isReloadDrivers());
        current.setEnabled(draft.isEnabled());
        validate(current, id);
        return repository.save(current);
    }

    @Transactional
    public DatabaseQueryConfig updateCachePolicy(String id, boolean cacheEnabled, int cacheTtlSeconds, String cacheStorage) {
        DatabaseQueryConfig current = getById(id);
        current.setCacheEnabled(cacheEnabled);
        current.setCacheTtlSeconds(Math.max(1, Math.min(cacheTtlSeconds, 86400)));
        current.setCacheStorage(normalizeCacheStorage(cacheStorage));
        return repository.save(current);
    }

    private String normalizeCacheStorage(String value) {
        String normalized = value == null ? "ROCKSDB" : value.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("ROCKSDB", "REDIS").contains(normalized)) {
            throw new IllegalArgumentException("cacheStorage must be ROCKSDB or REDIS");
        }
        return normalized;
    }

    /**
     * Sets the enabled.
     *
     * @param id the id value
     * @param enabled the enabled value
     * @return the operation result
     */
    @Transactional
    public DatabaseQueryConfig setEnabled(String id, boolean enabled) {
        DatabaseQueryConfig current = getById(id);
        if (enabled && !hasUsableDatasource(current)) {
            throw new IllegalArgumentException("database query requires an enabled datasource asset");
        }
        current.setEnabled(enabled);
        return repository.save(current);
    }

    /**
     * Deletes the delete.
     *
     * @param id the id value
     */
    @Transactional
    public void delete(String id) {
        repository.deleteById(id);
    }

    /**
     * Deletes the all.
     *
     * @param ids the ids value
     * @return the operation result
     */
    @Transactional
    public int deleteAll(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        List<String> normalizedIds = ids.stream()
            .filter(id -> id != null && !id.isBlank())
            .map(String::trim)
            .distinct()
            .toList();
        if (normalizedIds.isEmpty()) {
            return 0;
        }
        List<DatabaseQueryConfig> existing = repository.findAllById(normalizedIds);
        repository.deleteAll(existing);
        return existing.size();
    }

    /**
     * Validates the validate.
     *
     * @param config the config value
     * @param currentId the current id value
     */
    private void validate(DatabaseQueryConfig config, String currentId) {
        String toolName = normalizeRequired(config.getToolName(), "toolName");
        if (!TOOL_NAME_PATTERN.matcher(toolName).matches()) {
            throw new IllegalArgumentException("toolName can only contain letters, numbers, '_' and '-'");
        }
        if (RESERVED_TOOL_NAMES.contains(toolName.toLowerCase(Locale.ROOT)) || toolRegistry.hasTool(toolName)) {
            throw new IllegalArgumentException("toolName conflicts with existing tool: " + toolName);
        }
        if (apiServiceConfigRepository.findByToolNameIgnoreCase(toolName).isPresent()) {
            throw new IllegalArgumentException("toolName conflicts with API service: " + toolName);
        }
        repository.findByToolNameIgnoreCase(toolName)
            .filter(existing -> currentId == null || !existing.getId().equals(currentId))
            .ifPresent(existing -> {
                throw new IllegalArgumentException("toolName already exists: " + toolName);
            });
        config.setToolName(toolName);
        config.setTitle(blankToNull(config.getTitle()) == null ? toolName : config.getTitle().trim());
        config.setDatasourceId(blankToNull(config.getDatasourceId()));
        config.setDescription(normalizeRequired(config.getDescription(), "description"));
        config.setImplementationSteps(normalizeRequired(config.getImplementationSteps(), "implementationSteps"));
        config.setBusinessGroup(normalizeBusinessGroup(config.getBusinessGroup()));
        config.setBusinessGroupName(firstText(blankToNull(config.getBusinessGroupName()), config.getBusinessGroup()));
        config.setBusinessGroupDescription(blankToNull(config.getBusinessGroupDescription()));
        List<DatabaseQuerySqlStep> sqlSteps = normalizeSqlSteps(config);
        config.setSqlStepsJson(sqlSteps.isEmpty() ? null : writeSqlSteps(sqlSteps));
        config.setSqlTemplate(normalizeSqlTemplate(config, sqlSteps));
        config.setInputSchemaJson(normalizeJsonObject(config.getInputSchemaJson()));
        config.setGovernanceJson(normalizeJsonObject(config.getGovernanceJson(), "governance"));
        config.setRoutingLabelsJson(normalizeJsonArray(
            mergedProtocolValues(config.getRoutingLabelsJson(), config.getRoutingLabels()),
            "routingLabels"
        ));
        config.setCapabilitiesJson(firstText(
            normalizeJsonArray(mergedProtocolValues(config.getCapabilitiesJson(), config.getCapabilities()), "capabilities"),
            ModelProtocolJson.compact(List.of("database_query", "sql_query_execute", "jdbc"))
        ));
        config.setMaxRows(config.getMaxRows() <= 0 ? 50 : Math.min(500, config.getMaxRows()));
        config.setTimeoutSeconds(config.getTimeoutSeconds() <= 0 ? 30 : Math.min(300, config.getTimeoutSeconds()));
        if (config.getCacheTtlSeconds() <= 0) {
            config.setCacheTtlSeconds(300);
        } else {
            config.setCacheTtlSeconds(Math.min(86400, config.getCacheTtlSeconds()));
        }
        config.setCacheStorage(normalizeCacheStorage(config.getCacheStorage()));
        if (config.getDatasourceId() == null) {
            throw new IllegalArgumentException("datasourceId is required");
        }
        SqlDatasourceConfig datasource = datasourceConfigService.getEnabled(config.getDatasourceId());
        config.setDatabaseType(normalizeDatabaseType(firstText(explicitDatabaseType(config.getDatabaseType()), datasource.getDatabaseType())));
        config.setTemplateIntent(normalizeIntent(firstText(config.getTemplateIntent(), governanceString(config.getGovernanceJson(), "intent"))));
        config.setTagsJson(normalizeJsonArray(mergedProtocolValues(config.getTagsJson(), governanceTags(config.getGovernanceJson())), "tags"));
        config.setRiskLevel(normalizeMarketplaceRisk(firstText(explicitRiskLevel(config.getRiskLevel()), governanceString(config.getGovernanceJson(), "riskLevel"))));
        config.setOwner(firstText(explicitOwner(config.getOwner()), governanceString(config.getGovernanceJson(), "owner"), "admin"));
        config.setRating(Math.max(0.0, Math.min(5.0, config.getRating())));
        config.setUsageCount(Math.max(0L, config.getUsageCount()));
        config.setJdbcUrl(null);
        config.setDriverClass(null);
        config.setUsername(null);
        config.setPassword(null);
        config.setReloadDrivers(false);
    }

    /**
     * Normalizes the json object.
     *
     * @param json the json value
     * @return the operation result
     */
    private String normalizeJsonObject(String json) {
        return normalizeJsonObject(json, "inputSchema");
    }

    private List<DatabaseQuerySqlStep> normalizeSqlSteps(DatabaseQueryConfig config) {
        List<DatabaseQuerySqlStep> steps = readSqlSteps(config.getSqlStepsJson());
        if (steps.isEmpty()) {
            return List.of();
        }
        List<DatabaseQuerySqlStep> normalized = new ArrayList<>();
        Set<String> codes = new LinkedHashSet<>();
        Set<Integer> orders = new LinkedHashSet<>();
        for (int index = 0; index < steps.size(); index++) {
            DatabaseQuerySqlStep source = steps.get(index);
            if (source == null) {
                continue;
            }
            DatabaseQuerySqlStep step = new DatabaseQuerySqlStep();
            int executionOrder = source.getExecutionOrder() == null || source.getExecutionOrder() <= 0
                ? index + 1
                : source.getExecutionOrder();
            String code = blankToNull(source.getSqlCode());
            if (code == null) {
                code = "SQL_" + executionOrder;
            }
            code = code.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_\\-]", "_");
            if (code.isBlank()) {
                code = "SQL_" + executionOrder;
            }
            if (!codes.add(code)) {
                throw new IllegalArgumentException("sqlSteps sqlCode duplicated: " + code);
            }
            if (!orders.add(executionOrder)) {
                throw new IllegalArgumentException("sqlSteps executionOrder duplicated: " + executionOrder);
            }
            step.setSqlCode(code);
            step.setSqlName(normalizeRequired(source.getSqlName(), "sqlSteps[" + index + "].sqlName"));
            step.setSqlDescription(normalizeRequired(source.getSqlDescription(), "sqlSteps[" + index + "].sqlDescription"));
            step.setSqlContent(normalizeRequired(source.getSqlContent(), "sqlSteps[" + index + "].sqlContent"));
            step.setExecutionOrder(executionOrder);
            step.setDependencies(source.getDependencies() == null ? List.of() : source.getDependencies().stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_\\-]", "_"))
                .distinct()
                .toList());
            step.setWorkflowEnabled(Boolean.TRUE.equals(source.getWorkflowEnabled()));
            step.setEnabled(source.getEnabled() == null || source.getEnabled());
            int timeout = source.getTimeoutSeconds() == null || source.getTimeoutSeconds() <= 0
                ? config.getTimeoutSeconds()
                : source.getTimeoutSeconds();
            step.setTimeoutSeconds(Math.max(1, Math.min(300, timeout)));
            String failureStrategy = firstText(source.getFailureStrategy(), "STOP").trim().toUpperCase(Locale.ROOT);
            if (!Set.of("STOP", "CONTINUE").contains(failureStrategy)) {
                throw new IllegalArgumentException("sqlSteps[" + index + "].failureStrategy must be STOP or CONTINUE");
            }
            step.setFailureStrategy(failureStrategy);
            String emptyResultStrategy = firstText(source.getEmptyResultStrategy(), "CONTINUE").trim().toUpperCase(Locale.ROOT);
            if (!Set.of("CONTINUE", "SKIP_DEPENDENTS", "STOP").contains(emptyResultStrategy)) {
                throw new IllegalArgumentException("sqlSteps[" + index + "].emptyResultStrategy must be CONTINUE, SKIP_DEPENDENTS or STOP");
            }
            step.setEmptyResultStrategy(emptyResultStrategy);
            int maxRows = source.getMaxResultRows() == null || source.getMaxResultRows() <= 0
                ? config.getMaxRows()
                : source.getMaxResultRows();
            step.setMaxResultRows(Math.max(1, Math.min(1000, maxRows)));
            step.setParameters(normalizeStepParameters(source.getParameters(), index));
            step.setParameterMappings(normalizeParameterMappings(source.getParameterMappings(), index));
            step.setResultSemantic(source.getResultSemantic());
            if (step.getResultSemantic().getResultSetName() == null || step.getResultSemantic().getResultSetName().isBlank()) {
                step.getResultSemantic().setResultSetName(code.toLowerCase(Locale.ROOT));
            }
            step.setReturnToModel(source.getReturnToModel() == null || source.getReturnToModel());
            normalized.add(step);
        }
        normalized.sort(Comparator.comparing(DatabaseQuerySqlStep::getExecutionOrder));
        for (DatabaseQuerySqlStep step : normalized) {
            for (DatabaseQueryParameterMapping mapping : step.getParameterMappings()) {
                if ("UPSTREAM_RESULT".equals(mapping.getSourceType())
                    && !step.getDependencies().contains(mapping.getSourceNode())) {
                    throw new IllegalArgumentException("sqlSteps node " + step.getSqlCode()
                        + " upstream parameter source must also be a dependency: " + mapping.getSourceNode());
                }
            }
        }
        SQL_WORKFLOW_ENGINE.executionLevels(normalized.stream()
            .filter(DatabaseQuerySqlStep::enabled)
            .map(this::toWorkflowNode)
            .toList());
        return normalized;
    }

    private List<DatabaseQueryParameterMapping> normalizeParameterMappings(List<DatabaseQueryParameterMapping> mappings,
                                                                            int stepIndex) {
        if (mappings == null || mappings.isEmpty()) return List.of();
        List<DatabaseQueryParameterMapping> normalized = new ArrayList<>();
        Set<String> parameters = new LinkedHashSet<>();
        for (int index = 0; index < mappings.size(); index++) {
            DatabaseQueryParameterMapping source = mappings.get(index);
            if (source == null) continue;
            String parameter = normalizeRequired(source.getParameter(),
                "sqlSteps[" + stepIndex + "].parameterMappings[" + index + "].parameter");
            if (!parameters.add(parameter)) {
                throw new IllegalArgumentException("sqlSteps[" + stepIndex + "] parameter mapping duplicated: " + parameter);
            }
            String sourceType = firstText(source.getSourceType(), "USER_INPUT").toUpperCase(Locale.ROOT);
            if (!PARAMETER_SOURCE_TYPES.contains(sourceType)) {
                throw new IllegalArgumentException("sqlSteps[" + stepIndex + "] parameter sourceType is invalid: " + sourceType);
            }
            if ("UPSTREAM_RESULT".equals(sourceType) && blankToNull(source.getSourceNode()) == null) {
                throw new IllegalArgumentException("sqlSteps[" + stepIndex + "] upstream parameter requires sourceNode");
            }
            DatabaseQueryParameterMapping mapping = new DatabaseQueryParameterMapping();
            mapping.setParameter(parameter);
            mapping.setSourceType(sourceType);
            mapping.setSourceKey(blankToNull(source.getSourceKey()));
            mapping.setSourceNode(blankToNull(source.getSourceNode()) == null ? null : source.getSourceNode().trim().toUpperCase(Locale.ROOT));
            mapping.setSourceExpression(blankToNull(source.getSourceExpression()));
            mapping.setDefaultValue(source.getDefaultValue());
            mapping.setRequired(Boolean.TRUE.equals(source.getRequired()));
            normalized.add(mapping);
        }
        return normalized;
    }

    private Map<String, Object> normalizeStepParameters(Map<String, Object> parameters, int stepIndex) {
        if (parameters == null || parameters.isEmpty()) return Map.of();
        Map<String, Object> normalized = new LinkedHashMap<>();
        parameters.forEach((key, value) -> {
            String name = blankToNull(key);
            if (name == null) {
                throw new IllegalArgumentException("sqlSteps[" + stepIndex + "].parameters contains an empty parameter name");
            }
            if (value instanceof Map<?, ?> || value instanceof Iterable<?> || (value != null && value.getClass().isArray())) {
                throw new IllegalArgumentException("sqlSteps[" + stepIndex + "].parameters." + name
                    + " must be a text, number or boolean value");
            }
            normalized.put(name, value);
        });
        return normalized;
    }

    private SqlWorkflowNode toWorkflowNode(DatabaseQuerySqlStep step) {
        return new SqlWorkflowNode(
            step.getSqlCode(), step.getSqlName(), step.getSqlDescription(), step.getSqlContent(),
            step.getExecutionOrder(), step.getDependencies(),
            step.getParameterMappings().stream().map(mapping -> new SqlWorkflowParameterMapping(
                mapping.getParameter(), mapping.getSourceType(), mapping.getSourceKey(), mapping.getSourceNode(),
                mapping.getSourceExpression(), mapping.getDefaultValue(), Boolean.TRUE.equals(mapping.getRequired())
            )).toList(),
            step.getParameters(), step.getFailureStrategy(), step.getEmptyResultStrategy(),
            step.getTimeoutSeconds(), step.getMaxResultRows()
        );
    }

    private String normalizeSqlTemplate(DatabaseQueryConfig config, List<DatabaseQuerySqlStep> sqlSteps) {
        if (sqlSteps != null && !sqlSteps.isEmpty()) {
            return sqlSteps.stream()
                .filter(DatabaseQuerySqlStep::enabled)
                .findFirst()
                .map(DatabaseQuerySqlStep::getSqlContent)
                .orElseGet(() -> firstText(config.getSqlTemplate(), "-- multi SQL template has no enabled SQL step"));
        }
        return normalizeRequired(config.getSqlTemplate(), "sql");
    }

    private String writeSqlSteps(List<DatabaseQuerySqlStep> steps) {
        try {
            return ModelProtocolJson.compact(steps == null ? List.of() : steps);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("sqlSteps must be valid JSON");
        }
    }

    private List<DatabaseQuerySqlStep> readSqlSteps(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<DatabaseQuerySqlStep>>() {});
        } catch (Exception ex) {
            throw new IllegalArgumentException("sqlSteps must be a valid JSON array");
        }
    }

    /**
     * Normalizes the json object.
     *
     * @param json the json value
     * @param fieldName the field name value
     * @return the operation result
     */
    private String normalizeJsonObject(String json, String fieldName) {
        String value = blankToNull(json);
        if (value == null) {
            return null;
        }
        try {
            Map<String, Object> map = objectMapper.readValue(value, new TypeReference<>() {});
            return ModelProtocolJson.compact(map);
        } catch (Exception ex) {
            throw new IllegalArgumentException(fieldName + " must be a valid JSON object");
        }
    }

    private String normalizeJsonArray(String json, String fieldName) {
        String value = blankToNull(json);
        if (value == null) {
            return null;
        }
        try {
            List<String> items = objectMapper.readValue(value, new TypeReference<>() {});
            List<String> normalized = items.stream()
                .filter(item -> item != null && !item.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
            return normalized.isEmpty() ? null : ModelProtocolJson.compact(normalized);
        } catch (Exception ex) {
            throw new IllegalArgumentException(fieldName + " must be a JSON string array");
        }
    }

    private String mergedProtocolValues(String json, List<String> values) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        readJsonArray(json).forEach(merged::add);
        if (values != null) {
            values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .forEach(merged::add);
        }
        return merged.isEmpty() ? null : ModelProtocolJson.compact(new ArrayList<>(merged));
    }

    private List<String> readJsonArray(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {}).stream()
                .filter(item -> item != null && !item.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private LuceneMcpSearchService.TemplateDoc templateDoc(DatabaseQueryConfig config) {
        List<String> labels = new ArrayList<>();
        labels.addAll(readJsonArray(config.getRoutingLabelsJson()));
        labels.addAll(readJsonArray(config.getCapabilitiesJson()));
        labels.addAll(readJsonArray(config.getTagsJson()));
        labels.addAll(governanceSearchTerms(config.getGovernanceJson()));
        labels.add(firstText(config.getTemplateIntent(), ""));
        labels.add(firstText(config.getDatabaseType(), ""));
        labels.add(firstText(config.getRiskLevel(), ""));
        labels.add(firstText(config.getOwner(), ""));
        labels.add(firstText(config.getBusinessGroup(), ""));
        labels.add(firstText(config.getBusinessGroupName(), ""));
        labels.add(firstText(config.getBusinessGroupDescription(), ""));
        labels.add(firstText(config.getImplementationSteps(), ""));
        labels.add(firstText(config.getToolName(), ""));
        labels.add(firstText(config.getTitle(), ""));
        labels.add(firstText(config.getSqlTemplate(), ""));
        readSqlSteps(config.getSqlStepsJson()).forEach(step -> {
            labels.add(firstText(step.getSqlCode(), ""));
            labels.add(firstText(step.getSqlName(), ""));
            labels.add(firstText(step.getSqlDescription(), ""));
            labels.add(firstText(step.getSqlContent(), ""));
        });
        datasourceFor(config).ifPresent(datasource -> {
            labels.add(firstText(datasource.getName(), ""));
            labels.add(firstText(datasource.getTitle(), ""));
            labels.add(firstText(datasource.getToolName(), ""));
            labels.add(firstText(datasource.getDescription(), ""));
            labels.add(firstText(datasource.getEnvironment(), ""));
            labels.add(firstText(datasource.getDatabaseType(), ""));
            labels.add(firstText(datasource.getMetadataScopeValue(), ""));
        });
        return new LuceneMcpSearchService.TemplateDoc(
            config.getId(),
            "database_query",
            firstText(config.getTitle(), config.getToolName()),
            firstText(config.getDescription(), "") + " "
                + firstText(config.getImplementationSteps(), "") + " "
                + firstText(config.getBusinessGroupName(), "") + " "
                + firstText(config.getBusinessGroupDescription(), "") + " "
                + firstText(config.getSqlTemplate(), "") + " "
                + datasourceFor(config)
                    .map(datasource -> firstText(datasource.getName(), "") + " "
                        + firstText(datasource.getTitle(), "") + " "
                        + firstText(datasource.getDescription(), ""))
                    .orElse(""),
            "sql_template_registry",
            normalizeDatabaseType(config.getDatabaseType()),
            String.join(" ", labels),
            firstText(config.getRiskLevel(), "read_only"),
            labels,
            "database_query_registry"
        );
    }

    private java.util.Optional<SqlDatasourceConfig> datasourceFor(DatabaseQueryConfig config) {
        if (config == null || config.getDatasourceId() == null || config.getDatasourceId().isBlank()) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.ofNullable(datasourceConfigService.getEnabled(config.getDatasourceId()));
        } catch (Exception ignored) {
            return java.util.Optional.empty();
        }
    }

    private List<String> governanceSearchTerms(String json) {
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

    private String governanceString(String json, String key) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> map = objectMapper.readValue(json, new TypeReference<>() {});
            Object value = map.get(key);
            if (value == null && "riskLevel".equals(key)) {
                value = map.get("risk_level");
            }
            return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<String> governanceTags(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            Map<String, Object> map = objectMapper.readValue(json, new TypeReference<>() {});
            Object value = map.get("tags");
            if (value instanceof List<?> list) {
                return list.stream()
                    .filter(item -> item != null && !String.valueOf(item).isBlank())
                    .map(item -> String.valueOf(item).trim())
                    .toList();
            }
            if (value instanceof String text && !text.isBlank()) {
                return List.of(text.trim());
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return List.of();
    }

    private String normalizeIntent(String value) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            return "general_query";
        }
        return normalized.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-]", "_");
    }

    private String normalizeBusinessGroup(String value) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            return "default";
        }
        normalized = normalized.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-]", "_");
        normalized = normalized.replaceAll("_+", "_").replaceAll("^_+|_+$", "");
        return normalized.isBlank() ? "default" : normalized;
    }

    private String normalizeDatabaseType(String value) {
        return SqlDatasourceConfigService.normalizeDatabaseTypeToken(value);
    }

    private String explicitDatabaseType(String value) {
        String normalized = blankToNull(value);
        if (normalized == null || "generic".equalsIgnoreCase(normalized)) {
            return null;
        }
        return normalized;
    }

    private String explicitRiskLevel(String value) {
        String normalized = blankToNull(value);
        if (normalized == null || "read_only".equalsIgnoreCase(normalized)) {
            return null;
        }
        return normalized;
    }

    private String explicitOwner(String value) {
        String normalized = blankToNull(value);
        if (normalized == null || "admin".equalsIgnoreCase(normalized)) {
            return null;
        }
        return normalized;
    }

    private String normalizeMarketplaceRisk(String riskLevel) {
        String normalized = firstText(riskLevel, "read_only").trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "read_only", "readonly", "read" -> "read_only";
            case "safe", "low" -> "safe";
            case "dangerous", "high", "critical" -> "dangerous";
            default -> "read_only";
        };
    }

    @SuppressWarnings("unchecked")
    private void flattenValues(List<String> terms, Object value) {
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, item) -> {
                if (key != null) {
                    terms.add(String.valueOf(key));
                }
                flattenValues(terms, item);
            });
            return;
        }
        if (value instanceof List<?> list) {
            list.forEach(item -> flattenValues(terms, item));
            return;
        }
        if (value != null && !String.valueOf(value).isBlank()) {
            terms.add(String.valueOf(value));
        }
    }

    /**
     * Normalizes the required.
     *
     * @param value the value value
     * @param fieldName the field name value
     * @return the operation result
     */
    private String normalizeRequired(String value, String fieldName) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return normalized;
    }

    private String firstText(String... values) {
        if (values == null || values.length == 0) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    /**
     * Performs the blank to null operation.
     *
     * @param value the value value
     * @return the operation result
     */
    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private boolean hasUsableDatasource(DatabaseQueryConfig config) {
        if (blankToNull(config.getDatasourceId()) != null) {
            try {
                datasourceConfigService.getEnabled(config.getDatasourceId());
                return true;
            } catch (Exception ignored) {
                return false;
            }
        }
        return false;
    }
}
