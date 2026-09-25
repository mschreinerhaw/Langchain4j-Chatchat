package com.chatchat.api.runtime;

import com.chatchat.common.runtime.analysis.evidence.StructuredDataEvidence;
import com.chatchat.common.runtime.analysis.execution.WorkflowExecutionResult;
import com.chatchat.common.runtime.analysis.model.AnalysisCapability;
import com.chatchat.common.runtime.analysis.model.AnalysisContext;
import com.chatchat.common.runtime.analysis.model.AnalysisScope;
import com.chatchat.common.runtime.analysis.plan.WorkflowPlan;
import com.chatchat.common.runtime.analysis.spi.AnalysisCapabilityOperator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Turns an approved template result into structured evidence; raw SQL is never accepted. */
@Component
public class PreauthorizedStructuredDataOperator implements AnalysisCapabilityOperator {
    public static final String TEMPLATE_ID = "runtime.analysis.dataTemplateId";
    public static final String ASSET_NAME = "runtime.analysis.dataAssetName";
    public static final String ENVIRONMENT = "runtime.analysis.dataEnvironment";
    public static final String PARAMETERS = "runtime.analysis.dataParameters";
    public static final String SKILL_ID = "runtime.analysis.dataSkillId";
    public static final String TOOL_NAME = "sql_template_analysis_execute";

    private final RegisteredToolAnalysisOperator tools;
    private final ObjectMapper mapper;

    public PreauthorizedStructuredDataOperator(RegisteredToolAnalysisOperator tools, ObjectMapper mapper) {
        this.tools = tools;
        this.mapper = mapper;
    }

    @Override public AnalysisCapability capability() { return AnalysisCapability.STRUCTURED_DATA; }
    @Override public boolean available(AnalysisContext context) {
        return context != null && context.attributes().get(TEMPLATE_ID) instanceof String value && !value.isBlank();
    }

    @Override
    public WorkflowExecutionResult execute(AnalysisContext context, AnalysisScope scope, WorkflowPlan plan) {
        String template = string(context.attributes().get(TEMPLATE_ID));
        String asset = string(context.attributes().get(ASSET_NAME));
        String env = string(context.attributes().get(ENVIRONMENT));
        Object parameters = context.attributes().getOrDefault(PARAMETERS, Map.of());
        if (template == null || asset == null || env == null || !(parameters instanceof Map<?, ?>))
            return failed("A published template, logical asset and environment are required");
        AnalysisContext toolContext = context.withAttribute(RegisteredToolAnalysisOperator.TOOL_CALLS, null)
            .withAttribute(RegisteredToolAnalysisOperator.TOOL_NAME, TOOL_NAME)
            .withAttribute(RegisteredToolAnalysisOperator.TOOL_ARGUMENTS, Map.of(
                "templateId", template, "parameters", parameters,
                "executionContext", Map.of("assetName", asset, "env", env),
                "assetDomain", asset, "domain", asset,
                "maxRows", 100, "timeoutSeconds", 30,
                "purpose", "Agent Runtime OS structured evidence"));
        if (context.attributes().get(SKILL_ID) instanceof String skillId && !skillId.isBlank())
            toolContext = new AnalysisContext(toolContext.query(), toolContext.kernelScope(), skillId,
                List.of(), List.of(), toolContext.roles(), toolContext.intent(), toolContext.attributes());
        WorkflowExecutionResult result = tools.execute(toolContext, scope, plan);
        if (result.evidence().size() != 1) return result;
        String content = result.evidence().get(0).content();
        try {
            JsonNode payload = mapper.readTree(content);
            JsonNode data = payload.path("data");
            if (!payload.path("success").asBoolean(false) || !data.path("complete").asBoolean(false)
                || !data.path("rowCount").canConvertToLong() || !data.path("rows").isArray())
                return failed("SQL template returned no verifiable structured result");
            long rows = data.path("rowCount").asLong();
            if (rows < 0 || rows > 100 || data.path("rows").size() != rows)
                return failed("SQL template result is truncated or exceeds the analysis row limit");
            var evidence = new StructuredDataEvidence(result.evidence().get(0).evidenceId(), asset, template,
                rows, Instant.now().toString(), content,
                Map.of("sourceTool", TOOL_NAME, "tenantId", scope.tenantId(),
                    "remoteProjection", Map.of("assetName", asset, "templateId", template, "rowCount", rows)));
            return new WorkflowExecutionResult(List.of(evidence), Map.of(), List.of());
        } catch (Exception invalid) {
            return failed("SQL template result cannot be verified");
        }
    }

    private String string(Object value) {
        return value instanceof String text && !text.isBlank() ? text.trim() : null;
    }

    private WorkflowExecutionResult failed(String reason) {
        return new WorkflowExecutionResult(List.of(), Map.of(), List.of(reason));
    }
}
