package com.chatchat.mcpserver.sql.tool;

import com.chatchat.mcpserver.sql.datasource.SqlDatasourceConfig;
import com.chatchat.mcpserver.sql.datasource.SqlDatasourceConfigService;
import com.chatchat.mcpserver.sql.execution.workflow.SqlQueryExecutionWorkflow;
import com.chatchat.mcpserver.sql.execution.SqlSafetyService;
import com.chatchat.mcpserver.sql.parsing.SqlStatementExtractor;
import com.chatchat.mcpserver.sql.template.SqlTemplateConfig;
import com.chatchat.mcpserver.sql.template.SqlTemplateService;
import com.chatchat.mcpserver.database.definition.DatabaseQueryConfigService;
import com.chatchat.mcpserver.template.AgentRuntimeTemplateDsl;
import com.chatchat.mcpserver.tool.AgentRuntimeGovernanceFactory;
import com.chatchat.mcpserver.tool.McpToolConcurrencyManager;
import com.chatchat.mcpserver.tool.ToolPublication;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Separately governed, template-only SQL capability for unattended analysis. */
@Component
public class PreauthorizedSqlTemplateAnalysisPublisher implements com.chatchat.mcpserver.tool.McpToolContributor {
    public static final String TOOL_NAME = "sql_template_analysis_execute";
    private static final Set<String> ALLOWED_KEYS = Set.of("templateId", "parameters", "executionContext",
        "maxRows", "timeoutSeconds", "purpose", "sourceTaskId", "assetDomain", "domain");

    private final McpSyncServer server;
    private final SqlDatasourceConfigService datasources;
    private final SqlTemplateService templates;
    private final DatabaseQueryConfigService businessQueries;
    private final SqlQueryExecutionWorkflow workflow;
    private final SqlSafetyService safety;
    private final AgentRuntimeGovernanceFactory governance;
    private final McpToolConcurrencyManager concurrency;
    private final ObjectMapper mapper;

    public PreauthorizedSqlTemplateAnalysisPublisher(McpSyncServer server,
                                                     SqlDatasourceConfigService datasources,
                                                     SqlTemplateService templates,
                                                     DatabaseQueryConfigService businessQueries,
                                                     SqlQueryExecutionWorkflow workflow,
                                                     SqlSafetyService safety,
                                                     AgentRuntimeGovernanceFactory governance,
                                                     McpToolConcurrencyManager concurrency,
                                                     ObjectMapper mapper) {
        this.server = server;
        this.datasources = datasources;
        this.templates = templates;
        this.businessQueries = businessQueries;
        this.workflow = workflow;
        this.safety = safety;
        this.governance = governance;
        this.concurrency = concurrency;
        this.mapper = mapper;
    }

    @Override public String contributorId() { return "sql_template_analysis"; }
    @Override public McpSyncServer publicationServer() { return server; }
    @Override public List<ToolPublication> contribute() { return List.of(ToolPublication.from(specification())); }

    McpServerFeatures.SyncToolSpecification specification() {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("category", "sql_template_analysis");
        policy.put("operation_type", "read");
        policy.put("risk_level", "medium");
        policy.put("runtime_level", "readonly");
        policy.put("data_scope", "sql_datasource:preauthorized_template");
        policy.put("user_visible", true);
        policy.put("confirmation", Map.of("default", "auto_execute", "allow_user_override", false));
        policy.put("audit", Map.of("enabled", true, "log_params", true, "log_result_summary", true));
        McpSchema.JsonSchema schema = new McpSchema.JsonSchema("object", Map.of(
            "templateId", Map.of("type", "string"),
            "assetDomain", Map.of("type", "string"),
            "domain", Map.of("type", "string"),
            "parameters", Map.of("type", "object"),
            "executionContext", Map.of("type", "object"),
            "maxRows", Map.of("type", "integer", "minimum", 1, "maximum", 100),
            "timeoutSeconds", Map.of("type", "integer", "minimum", 1, "maximum", 30),
            "purpose", Map.of("type", "string")
        ), List.of("templateId", "assetDomain", "domain", "executionContext"), false, null, null);
        McpSchema.Tool tool = McpSchema.Tool.builder().name(TOOL_NAME)
            .title("Preauthorized read-only SQL template analysis")
            .description("Execute only an enabled, explicitly datasource-allowlisted, single read-only SQL template. "
                + "Raw SQL, scripts, concrete datasource IDs and unapproved templates are forbidden.")
            .inputSchema(schema).meta(governance.toMeta("sql_template_analysis", TOOL_NAME, policy)).build();
        return McpServerFeatures.SyncToolSpecification.builder().tool(tool)
            .callHandler((exchange, request) -> concurrency.execute(TOOL_NAME, "read_only",
                request.arguments(), () -> execute(request.arguments()))).build();
    }

    McpSchema.CallToolResult execute(Map<String, Object> input) {
        Map<String, Object> approved = validate(input);
        return workflow.execute(approved);
    }

    Map<String, Object> validate(Map<String, Object> input) {
        if (input == null || !ALLOWED_KEYS.containsAll(input.keySet()))
            throw new IllegalArgumentException("Only template execution arguments are accepted");
        String code = requiredText(input.get("templateId"), "templateId");
        if (!(input.get("executionContext") instanceof Map<?, ?> rawContext)
            || !Set.of("assetName", "env").containsAll(rawContext.keySet()))
            throw new IllegalArgumentException("Only logical assetName and env are allowed in executionContext");
        String assetName = requiredText(rawContext.get("assetName"), "assetName");
        String env = requiredText(rawContext.get("env"), "env");
        if (!assetName.equals(input.get("assetDomain")) || !assetName.equals(input.get("domain")))
            throw new IllegalArgumentException("Authorization domain must match the logical SQL asset");
        List<SqlDatasourceConfig> matching = datasources.listEnabled().stream()
            .filter(asset -> assetName.equals(asset.getName()) && env.equalsIgnoreCase(asset.getEnvironment()))
            .toList();
        if (matching.size() != 1) throw new IllegalArgumentException("Logical SQL asset is not uniquely enabled");
        SqlDatasourceConfig asset = matching.get(0);
        if (!explicitlyAllowed(asset, code))
            throw new IllegalArgumentException("SQL template is not explicitly authorized on this asset");
        if (businessQueries.listEnabled().stream().anyMatch(value -> value.getToolName() != null
            && code.equalsIgnoreCase(value.getToolName())))
            throw new IllegalArgumentException("SQL template identifier conflicts with another execution contract");
        SqlTemplateConfig template = templates.listEnabled().stream()
            .filter(value -> code.equalsIgnoreCase(value.getCode()))
            .filter(value -> value.getDatasourceId() == null || value.getDatasourceId().isBlank()
                || value.getDatasourceId().equals(asset.getId()))
            .findFirst().orElseThrow(() -> new IllegalArgumentException("SQL template is not published for this asset"));
        String risk = String.valueOf(template.getRiskLevel()).toLowerCase(Locale.ROOT);
        if (!Set.of("low", "medium").contains(risk)
            || AgentRuntimeTemplateDsl.looksLikeDsl(template.getSqlTemplate())
            || SqlStatementExtractor.splitStatements(template.getSqlTemplate()).size() != 1)
            throw new IllegalArgumentException("Only one low/medium-risk SQL template statement may run automatically");
        safety.validateAndNormalizeScriptStatement(template.getSqlTemplate(), 100);
        String templateType = SqlDatasourceConfigService.normalizeDatabaseTypeToken(template.getDatabaseType());
        String assetType = SqlDatasourceConfigService.normalizeDatabaseTypeToken(asset.getDatabaseType());
        if (!"generic".equals(templateType) && !templateType.equals(assetType))
            throw new IllegalArgumentException("SQL template dialect is incompatible with the asset");
        Object rawParameters = input.getOrDefault("parameters", Map.of());
        if (!(rawParameters instanceof Map<?, ?> parameters)
            || parameters.keySet().stream().anyMatch(key -> !(key instanceof String)))
            throw new IllegalArgumentException("parameters must be a JSON object");
        try {
            if (mapper.writeValueAsString(parameters).length() > 8192)
                throw new IllegalArgumentException("SQL template parameters exceed the limit");
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new IllegalArgumentException("SQL template parameters are not valid JSON", invalid);
        }
        int maxRows = boundedNumber(input.get("maxRows"), 100, 1, 100);
        int timeout = boundedNumber(input.get("timeoutSeconds"), 30, 1, 30);
        Map<String, Object> approved = new LinkedHashMap<>();
        approved.put("template", template.getCode());
        approved.put("parameters", parameters);
        approved.put("executionContext", Map.of("assetName", asset.getName(), "env", asset.getEnvironment()));
        approved.put("maxRows", maxRows);
        approved.put("timeoutSeconds", timeout);
        approved.put("purpose", input.get("purpose") instanceof String purpose && !purpose.isBlank()
            ? purpose.substring(0, Math.min(200, purpose.length())) : "Agent Runtime OS analysis");
        return Map.copyOf(approved);
    }

    private boolean explicitlyAllowed(SqlDatasourceConfig asset, String code) {
        String json = asset.getAllowedTemplatesJson();
        if (json == null || json.isBlank()) return false;
        try {
            List<String> allowed = mapper.readValue(json, new TypeReference<List<String>>() {});
            return allowed != null && allowed.stream().anyMatch(value -> code.equalsIgnoreCase(value));
        } catch (Exception invalid) { return false; }
    }

    private String requiredText(Object value, String field) {
        if (!(value instanceof String text) || text.isBlank() || text.length() > 200)
            throw new IllegalArgumentException(field + " must be a bounded non-empty string");
        return text.trim();
    }

    private int boundedNumber(Object value, int fallback, int minimum, int maximum) {
        if (value == null) return fallback;
        if (!(value instanceof Number number) || number.intValue() < minimum || number.intValue() > maximum)
            throw new IllegalArgumentException("SQL template execution limit is invalid");
        return number.intValue();
    }
}
