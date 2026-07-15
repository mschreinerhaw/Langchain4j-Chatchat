package com.chatchat.mcpserver.database;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolLogSummarizer;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.mcpserver.audit.InvocationAuditService;
import com.chatchat.mcpserver.mcp.McpInvocationContext;
import com.chatchat.mcpserver.cache.DatabaseQueryCacheService;
import com.chatchat.mcpserver.sql.DynamicDateParamService;
import com.chatchat.mcpserver.sql.SqlDatasourceConfig;
import com.chatchat.mcpserver.sql.SqlDatasourceConfigService;
import com.chatchat.mcpserver.sql.SqlScriptExecuteService;
import com.chatchat.mcpserver.template.AgentRuntimeTemplateDsl;
import com.chatchat.mcpserver.template.AgentRuntimeTemplateDsl.TemplatePlan;
import com.chatchat.mcpserver.template.AgentRuntimeTemplateDsl.TemplateStep;
import com.chatchat.tools.workflow.SqlWorkflowEngine;
import com.chatchat.tools.workflow.SqlWorkflowExecution;
import com.chatchat.tools.workflow.SqlWorkflowNode;
import com.chatchat.tools.workflow.SqlWorkflowNodeResult;
import com.chatchat.tools.workflow.SqlWorkflowParameterMapping;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Comparator;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class DatabaseQueryInvokeService {

    private static final String TOOL_NAME = "database_query";

    private final ToolRegistry toolRegistry;
    private final SqlDatasourceConfigService datasourceConfigService;
    private final SqlScriptExecuteService scriptExecuteService;
    private final DynamicDateParamService dynamicDateParamService;
    private final ObjectMapper objectMapper;
    private final InvocationAuditService auditService;
    private final DatabaseQueryCacheService cacheService;
    private final SqlWorkflowEngine workflowEngine = new SqlWorkflowEngine();

    @Value("${chatchat.tools.database-query.workflow.max-parallelism:4}")
    private int workflowMaxParallelism = 4;

    /**
     * Performs the invoke operation.
     *
     * @param config the config value
     * @param arguments the arguments value
     * @return the operation result
     */
    public ToolOutput invoke(DatabaseQueryConfig config, Map<String, Object> arguments) {
        long startedAt = System.currentTimeMillis();
        Map<String, Object> auditArgs = arguments == null ? Map.of() : arguments;
        ToolOutput output;
        try {
            assertExecutionCapability(config);
            log.info("Database query invoke started databaseQueryId={} tool={} maxRows={} sql={} args={}",
                config.getId(),
                config.getToolName(),
                config.getMaxRows(),
                config.getSqlTemplate(),
                ToolLogSummarizer.summarize(arguments));
            Map<String, Object> parameters = toParameters(config, auditArgs);
            var cached = cacheService.get(config, parameters);
            if (cached.isPresent()) {
                output = cached.get();
                output.setExecutionTimeMs(Math.max(0L, System.currentTimeMillis() - startedAt));
                log.info("Database query invoke cache hit databaseQueryId={} tool={} durationMs={}",
                    config.getId(),
                    config.getToolName(),
                    Math.max(0L, System.currentTimeMillis() - startedAt));
            } else {
                output = hasSqlSteps(config)
                    ? invokeConfiguredSqlSteps(config, parameters, startedAt)
                    : invoke(parameters);
                if (output.getMetadata() != null) {
                    output.getMetadata().putIfAbsent("cacheHit", false);
                }
                cacheService.put(config, parameters, output);
            }
        } catch (Exception ex) {
            log.warn("Database query invoke failed databaseQueryId={} tool={} error={}",
                config == null ? null : config.getId(), config == null ? null : config.getToolName(), ex.getMessage(), ex);
            output = ToolOutput.failure(ex.getMessage());
            output.setExecutionTimeMs(Math.max(0L, System.currentTimeMillis() - startedAt));
        }
        long durationMs = output == null || output.getExecutionTimeMs() == null
            ? Math.max(0L, System.currentTimeMillis() - startedAt)
            : output.getExecutionTimeMs();
        auditService.recordDatabaseQueryCall(config, auditArgs, output, durationMs);
        return output;
    }

    private void assertExecutionCapability(DatabaseQueryConfig config) {
        if (config.getCapabilitiesJson() == null || config.getCapabilitiesJson().isBlank()) {
            log.warn("MCP database query has no protocol capabilities configured; allowing legacy execution: databaseQueryId={}, tool={}",
                config.getId(), config.getToolName());
            return;
        }
        Set<String> capabilities;
        try {
            capabilities = objectMapper.readValue(config.getCapabilitiesJson(), new TypeReference<List<String>>() {}).stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        } catch (Exception ex) {
            throw new IllegalArgumentException("Database query capabilities config is invalid");
        }
        if (capabilities.stream().noneMatch(value -> value.equals("database_query")
            || value.equals("sql_query_execute")
            || value.equals("sql_script_execute")
            || value.equals("sql_exec")
            || value.equals("sql")
            || value.equals("jdbc"))) {
            throw new IllegalArgumentException("Database query does not declare SQL execution capability: "
                + config.getToolName());
        }
    }

    /**
     * Performs the invoke operation.
     *
     * @param parameters the parameters value
     * @return the operation result
     */
    public ToolOutput invoke(Map<String, Object> parameters) {
        long startedAt = System.currentTimeMillis();
        if (!toolRegistry.hasTool(TOOL_NAME)) {
            long durationMs = Math.max(0L, System.currentTimeMillis() - startedAt);
            log.warn("Database query invoke failed tool={} durationMs={} error={}",
                TOOL_NAME,
                durationMs,
                "database_query tool is not registered");
            ToolOutput output = ToolOutput.failure("database_query tool is not registered");
            output.setExecutionTimeMs(durationMs);
            return output;
        }
        String sql = text(parameters, "sql");
        TemplatePlan plan = sqlPlan(sql);
        List<String> statements = plan.steps().stream().map(TemplateStep::command).toList();
        if (statements.size() > 1) {
            return invokeScript(parameters, plan, startedAt);
        }
        if (statements.size() == 1) {
            parameters = new LinkedHashMap<>(parameters);
            parameters.put("sql", statements.get(0));
        }
        ToolInput input = ToolInput.builder()
            .requestId(UUID.randomUUID().toString())
            .userId(invocationUserId(parameters))
            .parameters(parameters)
            .build();
        ToolOutput output = toolRegistry.executeEnhancedTool(TOOL_NAME, input);
        long durationMs = Math.max(0L, System.currentTimeMillis() - startedAt);
        if (output == null) {
            output = ToolOutput.failure("database_query tool returned no output");
        }
        output.setExecutionTimeMs(durationMs);
        if (output.isSuccess()) {
            log.info("Database query invoke succeeded tool={} durationMs={} result={}",
                TOOL_NAME,
                durationMs,
                ToolLogSummarizer.summarize(output.getData()));
        } else {
            log.warn("Database query invoke failed tool={} durationMs={} error={} result={}",
                TOOL_NAME,
                durationMs,
                output.getErrorMessage(),
                ToolLogSummarizer.summarize(output.getData()));
        }
        return output;
    }

    private ToolOutput invokeScript(Map<String, Object> parameters, TemplatePlan plan, long startedAt) {
        List<Map<String, Object>> results = new java.util.ArrayList<>();
        boolean success = true;
        String errorMessage = null;
        List<TemplateStep> statements = plan.steps();
        for (int index = 0; index < statements.size(); index++) {
            TemplateStep current = statements.get(index);
            Map<String, Object> stepParameters = new LinkedHashMap<>(parameters);
            stepParameters.put("sql", current.command());
            log.info("MCP execution detail: executionType=SQL_SCRIPT_STEP, source=database_query, statementIndex={}, stepCode={}, stepName={}, maxRows={}, sql={}",
                index + 1, current.stepCode(), current.stepName(),
                stepParameters.getOrDefault("max_rows", stepParameters.get("maxRows")), current.command());
            ToolOutput step = invokeSingleStatement(stepParameters);
            Map<String, Object> data = mapValue(step.getData());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("statementIndex", index + 1);
            result.put("stepCode", current.stepCode());
            result.put("stepName", current.stepName());
            result.put("stepType", current.stepType());
            result.put("required", current.required());
            result.put("analysisHint", current.analysisHint());
            result.put("success", step.isSuccess());
            result.put("sql", data.getOrDefault("sql", current.command()));
            result.put("columns", data.getOrDefault("columns", List.of()));
            result.put("rows", data.getOrDefault("rows", List.of()));
            result.put("rowCount", data.getOrDefault("rowCount", 0));
            result.put("maxRows", data.getOrDefault("maxRows", parameters.getOrDefault("max_rows", parameters.get("maxRows"))));
            result.put("possiblyTruncated", data.getOrDefault("possiblyTruncated", false));
            result.put("errorMessage", step.getErrorMessage());
            results.add(result);
            if (!step.isSuccess()) {
                if (current.required()) {
                    success = false;
                    errorMessage = step.getErrorMessage();
                }
                if (!plan.continueOnError()) {
                    break;
                }
            }
        }
        long durationMs = Math.max(0L, System.currentTimeMillis() - startedAt);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mode", plan.dsl() ? "agent_runtime_template_dsl" : "sql_script");
        data.put("templateDsl", AgentRuntimeTemplateDsl.metadata(plan));
        data.put("analysisPolicy", plan.analysisPolicy());
        data.put("executionMode", plan.executionMode());
        data.put("continueOnError", plan.continueOnError());
        data.put("statementCount", statements.size());
        data.put("resultSetCount", results.size());
        data.put("results", results);
        data.put("possiblyPartial", results.size() < statements.size());
        ToolOutput output = success
            ? ToolOutput.success(data, "Database SQL script completed successfully")
            : ToolOutput.failure(errorMessage == null ? "Database SQL script failed" : errorMessage);
        output.setData(data);
        output.setExecutionTimeMs(durationMs);
        output.getMetadata().put("executionMode", data.get("mode"));
        output.getMetadata().put("statementCount", statements.size());
        return output;
    }

    private ToolOutput invokeSingleStatement(Map<String, Object> parameters) {
        ToolInput input = ToolInput.builder()
            .requestId(UUID.randomUUID().toString())
            .userId(invocationUserId(parameters))
            .parameters(parameters)
            .build();
        ToolOutput output = toolRegistry.executeEnhancedTool(TOOL_NAME, input);
        return output == null ? ToolOutput.failure("database_query tool returned no output") : output;
    }

    private ToolOutput invokeConfiguredSqlSteps(DatabaseQueryConfig config,
                                                Map<String, Object> baseParameters,
                                                long startedAt) {
        List<DatabaseQuerySqlStep> steps = readSqlSteps(config.getSqlStepsJson()).stream()
            .filter(DatabaseQuerySqlStep::enabled)
            .sorted(Comparator.comparing(DatabaseQuerySqlStep::getExecutionOrder))
            .toList();
        if (steps.isEmpty()) {
            return ToolOutput.failure("database query template has no enabled SQL steps");
        }
        Map<String, DatabaseQuerySqlStep> stepConfigs = steps.stream().collect(Collectors.toMap(
            DatabaseQuerySqlStep::getSqlCode, item -> item, (first, ignored) -> first, LinkedHashMap::new));
        boolean dependencyWorkflow = steps.stream().anyMatch(step -> Boolean.TRUE.equals(step.getWorkflowEnabled()));
        List<SqlWorkflowNode> workflowNodes = dependencyWorkflow
            ? steps.stream().map(this::toWorkflowNode).toList()
            : legacySequentialWorkflowNodes(steps);
        @SuppressWarnings("unchecked")
        Map<String, Object> userInput = baseParameters.get("params") instanceof Map<?, ?> map
            ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>();
        Map<String, Object> systemContext = new LinkedHashMap<>();
        systemContext.put("currentUser", invocationUserId(userInput));
        systemContext.put("datasourceId", baseParameters.get("datasource_id"));
        systemContext.put("datasourceName", baseParameters.get("datasource_name"));
        String workflowExecutionNo = UUID.randomUUID().toString();
        log.info("Database query workflow prepared executionNo={} databaseQueryId={} tool={} datasourceId={} datasourceName={} executionMode={} nodeCount={} maxParallelism={} inputParameterKeys={} nodes={}",
            workflowExecutionNo, config.getId(), config.getToolName(), baseParameters.get("datasource_id"),
            baseParameters.get("datasource_name"), dependencyWorkflow ? "DEPENDENCY_GRAPH" : "SEQUENTIAL",
            workflowNodes.size(), workflowMaxParallelism, userInput.keySet(),
            workflowNodes.stream().map(node -> Map.of(
                "code", node.code(), "order", node.displayOrder(), "dependencies", node.dependencies())).toList());
        SqlWorkflowExecution execution = workflowEngine.execute(
            workflowExecutionNo,
            workflowNodes,
            userInput,
            systemContext,
            workflowMaxParallelism,
            (node, resolvedParameters) -> {
            DatabaseQuerySqlStep stepConfig = stepConfigs.get(node.code());
            long stepStartedAt = System.currentTimeMillis();
            Map<String, Object> stepParameters = parametersForSqlStep(config, baseParameters, stepConfig, resolvedParameters);
            log.info("MCP execution detail: executionType=SQL_QUERY_STEP, source=sql_script_execute, executionNo={}, databaseQueryId={}, tool={}, executionOrder={}, sqlCode={}, sqlName={}, resolvedParameterKeys={}, maxRows={}, timeoutSeconds={}, sql={}",
                workflowExecutionNo, config.getId(), config.getToolName(), stepConfig.getExecutionOrder(), stepConfig.getSqlCode(),
                stepConfig.getSqlName(), resolvedParameters.keySet(),
                stepParameters.getOrDefault("max_rows", stepParameters.get("maxRows")),
                stepParameters.getOrDefault("timeoutSeconds", stepParameters.get("timeout_seconds")), stepParameters.get("sql"));
            ToolOutput step = invokeSingleStatement(stepParameters);
            long stepDurationMs = Math.max(0L, System.currentTimeMillis() - stepStartedAt);
            if (step.isSuccess()) {
                log.info("Database query workflow SQL completed executionNo={} databaseQueryId={} tool={} sqlCode={} sqlName={} status=SUCCESS rowCount={} durationMs={}",
                    workflowExecutionNo, config.getId(), config.getToolName(), stepConfig.getSqlCode(), stepConfig.getSqlName(),
                    rowCount(mapValue(step.getData()).get("rows")), stepDurationMs);
            } else {
                log.warn("Database query workflow SQL failed executionNo={} databaseQueryId={} tool={} sqlCode={} sqlName={} status=FAILED durationMs={} error={}",
                    workflowExecutionNo, config.getId(), config.getToolName(), stepConfig.getSqlCode(), stepConfig.getSqlName(),
                    stepDurationMs, step.getErrorMessage());
            }
            auditService.recordDatabaseQueryStepCall(
                config, stepConfig.getSqlCode(), stepConfig.getSqlName(),
                text(baseParameters, "datasource_name"), String.valueOf(stepParameters.get("sql")),
                resolvedParameters, step, stepDurationMs);
            return new SqlWorkflowNodeResult(step.isSuccess(), mapValue(step.getData()), step.getErrorMessage(), stepDurationMs);
        });
        List<Map<String, Object>> resultSets = execution.steps().stream()
            .map(item -> resultSet(config, stepConfigs.get(item.node().code()), item))
            .toList();
        List<Map<String, Object>> modelResultSets = resultSets.stream()
            .filter(item -> Boolean.TRUE.equals(item.get("returnToModel")))
            .toList();
        int failedCount = (int) execution.steps().stream().filter(item -> "FAILED".equals(item.status())).count();
        int skippedCount = (int) execution.steps().stream().filter(item -> "SKIPPED".equals(item.status())).count();
        long durationMs = execution.durationMs();
        log.info("Database query workflow result assembled executionNo={} databaseQueryId={} tool={} status={} resultSetCount={} modelResultSetCount={} failedCount={} skippedCount={} durationMs={}",
            execution.executionNo(), config.getId(), config.getToolName(), execution.status(), resultSets.size(),
            modelResultSets.size(), failedCount, skippedCount, durationMs);
        Map<String, Object> firstSuccess = firstSuccessResult(modelResultSets);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mode", dependencyWorkflow ? "database_query_workflow" : "database_query_multi_sql");
        data.put("schemaVersion", dependencyWorkflow
            ? "database_query_workflow_result.v1" : "database_query_multi_sql_result.v1");
        data.put("tool", Map.of(
            "name", config.getToolName(),
            "title", firstText(config.getTitle(), config.getToolName()),
            "description", firstText(config.getDescription(), ""),
            "implementationSteps", firstText(config.getImplementationSteps(), "")
        ));
        data.put("execution", Map.of(
            "executionNo", execution.executionNo(),
            "status", execution.status(),
            "executionMode", dependencyWorkflow ? "DEPENDENCY_GRAPH" : "SEQUENTIAL",
            "executionLevels", execution.executionLevels(),
            "executedNodeCount", resultSets.size() - skippedCount,
            "successNodeCount", resultSets.size() - failedCount - skippedCount,
            "failedNodeCount", failedCount,
            "skippedNodeCount", skippedCount,
            "durationMs", durationMs
        ));
        data.put("templateId", config.getToolName());
        data.put("templateName", config.getTitle());
        data.put("templateDescription", config.getDescription());
        data.put("implementationSteps", config.getImplementationSteps());
        data.put("executionMode", dependencyWorkflow ? "DEPENDENCY_GRAPH" : "SEQUENTIAL");
        data.put("executionLevels", execution.executionLevels());
        data.put("resultSetCount", resultSets.size());
        data.put("configuredSqlCount", steps.size());
        data.put("failedResultSetCount", failedCount);
        data.put("skippedResultSetCount", skippedCount);
        data.put("stopped", "FAILED".equals(execution.status()));
        data.put("stoppedByFailure", "FAILED".equals(execution.status()));
        data.put("possiblyPartial", failedCount > 0 || skippedCount > 0);
        data.put("steps", modelResultSets);
        data.put("resultSets", modelResultSets);
        data.put("results", modelResultSets);
        data.put("nodeExecutions", resultSets.stream().map(item -> Map.of(
            "nodeCode", item.get("nodeCode"),
            "nodeName", item.get("nodeName"),
            "dependencies", item.get("dependencies"),
            "executionOrder", item.get("executionOrder"),
            "executionLevel", item.get("executionLevel"),
            "success", item.get("success"),
            "status", item.get("status"),
            "rowCount", item.get("rowCount"),
            "durationMs", item.get("durationMs")
        )).toList());
        data.put("modelGuidance", Map.of(
            "analysisObjective", firstText(config.getDescription(), config.getTitle()),
            "resultRelationships", steps.stream().map(step -> step.getDependencies().isEmpty()
                ? step.getSqlCode() + " is a workflow entry result"
                : step.getSqlCode() + " depends on " + String.join(", ", step.getDependencies())).toList(),
            "cautions", steps.stream()
                .map(DatabaseQuerySqlStep::getResultSemantic)
                .map(DatabaseQueryResultSemantic::getEmptyMeaning)
                .filter(value -> value != null && !value.isBlank())
                .distinct().toList()
        ));
        data.put("columns", firstSuccess.getOrDefault("columns", List.of()));
        data.put("rows", firstSuccess.getOrDefault("rows", List.of()));
        data.put("rowCount", firstSuccess.getOrDefault("rowCount", 0));
        data.put("maxRows", firstSuccess.getOrDefault("maxRows", config.getMaxRows()));
        data.put("possiblyTruncated", firstSuccess.getOrDefault("possiblyTruncated", false));
        String firstError = execution.steps().stream().filter(item -> item.result() != null && !item.result().success())
            .map(item -> item.result().errorMessage()).filter(value -> value != null && !value.isBlank()).findFirst().orElse(null);
        ToolOutput output = "FAILED".equals(execution.status())
            ? ToolOutput.failure(firstError == null ? "Database query workflow failed" : firstError)
            : ToolOutput.success(data, failedCount > 0
                ? "Database query workflow completed with partial node failures"
                : "Database query workflow completed successfully");
        output.setData(data);
        output.setExecutionTimeMs(durationMs);
        output.getMetadata().put("executionMode", dependencyWorkflow ? "database_query_workflow" : "database_query_multi_sql");
        output.getMetadata().put("executionNo", execution.executionNo());
        output.getMetadata().put("resultSetCount", resultSets.size());
        output.getMetadata().put("failedResultSetCount", failedCount);
        return output;
    }

    private Map<String, Object> parametersForSqlStep(DatabaseQueryConfig config,
                                                     Map<String, Object> baseParameters,
                                                     DatabaseQuerySqlStep stepConfig,
                                                     Map<String, Object> resolvedParameters) {
        Map<String, Object> parameters = new LinkedHashMap<>(baseParameters);
        SqlDatasourceConfig datasource = datasourceConfigService.getEnabled(config.getDatasourceId());
        Map<String, Object> resolvedParams = dynamicDateParamService.enrichParameters(
            resolvedParameters,
            datasource,
            stepConfig.getSqlContent()
        );
        String sql = dynamicDateParamService.resolveSqlPlaceholders(stepConfig.getSqlContent(), datasource);
        parameters.put("sql", sql);
        parameters.put("params", resolvedParams);
        int maxRows = stepConfig.getMaxResultRows() == null || stepConfig.getMaxResultRows() <= 0
            ? config.getMaxRows()
            : stepConfig.getMaxResultRows();
        int timeoutSeconds = stepConfig.getTimeoutSeconds() == null || stepConfig.getTimeoutSeconds() <= 0
            ? config.getTimeoutSeconds()
            : stepConfig.getTimeoutSeconds();
        parameters.put("max_rows", maxRows);
        parameters.put("maxRows", maxRows);
        parameters.put("timeoutSeconds", timeoutSeconds);
        parameters.put("timeout_seconds", timeoutSeconds);
        return parameters;
    }

    private Map<String, Object> resultSet(DatabaseQueryConfig config,
                                          DatabaseQuerySqlStep stepConfig,
                                          SqlWorkflowExecution.StepExecution execution) {
        Map<String, Object> data = execution.result() == null ? Map.of() : execution.result().data();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("step", execution.executionOrder());
        result.put("executionLevel", execution.executionLevel());
        result.put("nodeCode", stepConfig.getSqlCode());
        result.put("nodeName", stepConfig.getSqlName());
        result.put("sqlCode", stepConfig.getSqlCode());
        result.put("sqlName", stepConfig.getSqlName());
        result.put("description", stepConfig.getSqlDescription());
        result.put("resultSetDescription", stepConfig.getSqlDescription());
        result.put("resultSemantic", objectMapper.convertValue(stepConfig.getResultSemantic(), new TypeReference<Map<String, Object>>() {}));
        result.put("dependencies", stepConfig.getDependencies());
        List<Map<String, Object>> parameterMappings = objectMapper.convertValue(stepConfig.getParameterMappings(),
            new TypeReference<List<Map<String, Object>>>() {});
        result.put("parameterMapping", parameterMappings);
        result.put("parameterMappings", parameterMappings);
        result.put("resolvedParameters", execution.resolvedParameters());
        result.put("returnToModel", stepConfig.getReturnToModel() == null || stepConfig.getReturnToModel());
        result.put("executionOrder", execution.executionOrder());
        result.put("configuredOrder", stepConfig.getExecutionOrder());
        result.put("success", "SUCCESS".equals(execution.status()));
        result.put("status", execution.status());
        result.put("durationMs", execution.result() == null ? 0L : execution.result().durationMs());
        result.put("failureStrategy", firstText(stepConfig.getFailureStrategy(), "STOP"));
        result.put("emptyResultStrategy", firstText(stepConfig.getEmptyResultStrategy(), "CONTINUE"));
        result.put("sql", data.getOrDefault("sql", stepConfig.getSqlContent()));
        result.put("columns", data.getOrDefault("columns", List.of()));
        result.put("columnMetadata", data.getOrDefault("columnMetadata", List.of()));
        result.put("rows", data.getOrDefault("rows", List.of()));
        result.put("rowCount", data.getOrDefault("rowCount", 0));
        result.put("returnedRowCount", rowCount(data.get("rows")));
        result.put("maxRows", data.getOrDefault("maxRows", stepConfig.getMaxResultRows()));
        result.put("possiblyTruncated", data.getOrDefault("possiblyTruncated", false));
        result.put("errorMessage", execution.result() == null ? null : execution.result().errorMessage());
        result.put("skipReason", execution.skipReason());
        return result;
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
            step.getTimeoutSeconds() == null || step.getTimeoutSeconds() <= 0 ? 30 : step.getTimeoutSeconds(),
            step.getMaxResultRows() == null || step.getMaxResultRows() <= 0 ? 50 : step.getMaxResultRows()
        );
    }

    private List<SqlWorkflowNode> legacySequentialWorkflowNodes(List<DatabaseQuerySqlStep> steps) {
        List<SqlWorkflowNode> nodes = new java.util.ArrayList<>();
        String previous = null;
        for (DatabaseQuerySqlStep step : steps) {
            SqlWorkflowNode node = toWorkflowNode(step);
            nodes.add(new SqlWorkflowNode(
                node.code(), node.name(), node.description(), node.sql(), node.displayOrder(),
                previous == null ? List.of() : List.of(previous), node.parameterMappings(), node.staticParameters(),
                node.failureStrategy(), node.emptyResultStrategy(), node.timeoutSeconds(), node.maxRows()
            ));
            previous = node.code();
        }
        return nodes;
    }

    private Map<String, Object> firstSuccessResult(List<Map<String, Object>> resultSets) {
        return resultSets.stream()
            .filter(item -> Boolean.TRUE.equals(item.get("success")))
            .findFirst()
            .orElseGet(() -> resultSets.isEmpty() ? Map.of() : resultSets.get(0));
    }

    /**
     * Converts the value to parameters.
     *
     * @param config the config value
     * @param arguments the arguments value
     * @return the converted parameters
     */
    private Map<String, Object> toParameters(DatabaseQueryConfig config, Map<String, Object> arguments) {
        if (config.getDatasourceId() == null || config.getDatasourceId().isBlank()) {
            throw new IllegalArgumentException("database query requires an enabled datasource asset");
        }
        SqlDatasourceConfig datasource = datasourceConfigService.getEnabled(config.getDatasourceId());
        String sqlContext = hasSqlSteps(config)
            ? readSqlSteps(config.getSqlStepsJson()).stream()
                .map(DatabaseQuerySqlStep::getSqlContent)
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.joining("\n"))
            : config.getSqlTemplate();
        Map<String, Object> resolvedArguments = dynamicDateParamService.enrichParameters(
            arguments,
            datasource,
            sqlContext
        );
        Map<String, Object> parameters = new LinkedHashMap<>();
        if (!hasSqlSteps(config)) {
            parameters.put("sql", dynamicDateParamService.resolveSqlPlaceholders(config.getSqlTemplate(), datasource));
        }
        parameters.put("params", resolvedArguments);
        parameters.put("max_rows", config.getMaxRows());
        parameters.put("timeoutSeconds", config.getTimeoutSeconds());
        parameters.put("timeout_seconds", config.getTimeoutSeconds());
        putIfPresent(parameters, "jdbc_url", datasource.getJdbcUrl());
        putIfPresent(parameters, "driver_class", datasource.getDriverClass());
        putIfPresent(parameters, "database_type", datasource.getDatabaseType());
        putIfPresent(parameters, "username", datasource.getUsername());
        putIfPresent(parameters, "password", datasource.getPassword());
        parameters.put("datasource_id", datasource.getId());
        parameters.put("datasource_name", datasource.getName());
        parameters.put("reload_drivers", false);
        return parameters;
    }

    private boolean hasSqlSteps(DatabaseQueryConfig config) {
        return config != null && config.getSqlStepsJson() != null && !config.getSqlStepsJson().isBlank();
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

    private TemplatePlan sqlPlan(String sql) {
        TemplatePlan dsl = AgentRuntimeTemplateDsl.parse(sql, "database_query", "DB_SQL", "SQL");
        if (dsl != null) {
            return dsl;
        }
        List<String> statements = scriptExecuteService.extractStatements(sql);
        List<TemplateStep> steps = new java.util.ArrayList<>();
        for (int index = 0; index < statements.size(); index++) {
            steps.add(AgentRuntimeTemplateDsl.singleStep(index + 1, "SQL", statements.get(index)));
        }
        return new TemplatePlan(
            "database_query",
            "database_query",
            "DB_SQL",
            null,
            "SEQUENTIAL",
            false,
            "LOW",
            null,
            Map.of(),
            steps,
            false
        );
    }

    /**
     * Stores the if present.
     *
     * @param parameters the parameters value
     * @param key the key value
     * @param value the value value
     */
    private void putIfPresent(Map<String, Object> parameters, String key, String value) {
        if (value != null && !value.isBlank()) {
            parameters.put(key, value.trim());
        }
    }

    private String text(Map<String, Object> values, String key) {
        Object value = values == null ? null : values.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private int rowCount(Object rows) {
        return rows instanceof List<?> list ? list.size() : 0;
    }

    private String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String invocationUserId(Map<String, Object> parameters) {
        McpInvocationContext.Context context = McpInvocationContext.current();
        Map<String, Object> nested = mapValue(parameters == null ? null : parameters.get("params"));
        return firstText(
            context == null ? null : context.userId(),
            context == null ? null : context.username(),
            text(parameters, "userId"),
            text(parameters, "username"),
            text(nested, "userId"),
            text(nested, "username"),
            "mcp-tool"
        );
    }

    /**
     * Performs the argument keys operation.
     *
     * @param arguments the arguments value
     * @return the operation result
     */
    private java.util.List<String> argumentKeys(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return java.util.List.of();
        }
        return arguments.keySet().stream()
            .filter(key -> key != null && !key.isBlank())
            .sorted()
            .toList();
    }
}
