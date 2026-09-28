package com.chatchat.api.runtime;

import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.retrieval.SkillExecutionScopePort;
import com.chatchat.common.runtime.analysis.model.AnalysisContext;
import com.chatchat.common.runtime.analysis.model.AnalysisScope;
import com.chatchat.runtime.skill.api.execution.SkillDataResult;
import com.chatchat.runtime.skill.api.identity.SkillRoleContext;
import com.chatchat.runtime.skill.api.resolution.SkillResolution;
import com.chatchat.runtime.skill.api.skill.SkillDataRequirement;
import com.chatchat.runtime.skill.port.outbound.SkillDataWorkflow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Fixed template acquisition via the existing authorized MCP operator. */
@Component
@EnableConfigurationProperties(SkillDataWorkflowProperties.class)
public class TemplateSkillDataWorkflow implements SkillDataWorkflow {
    private final SkillDataWorkflowProperties properties;
    private final SkillExecutionScopePort scopes;
    private final PreauthorizedStructuredDataOperator data;
    private final ObjectMapper mapper;

    public TemplateSkillDataWorkflow(SkillDataWorkflowProperties properties, SkillExecutionScopePort scopes,
                                     PreauthorizedStructuredDataOperator data, ObjectMapper mapper) {
        this.properties = properties;
        this.scopes = scopes;
        this.data = data;
        this.mapper = mapper;
    }

    private List<SkillDataWorkflowProperties.Binding> bindings(SkillDataRequirement requirement,
                                                              SkillResolution skill, SkillRoleContext identity) {
        return properties.bindings().stream().filter(binding -> binding.enabled()
            && binding.tenantId().equals(identity.tenantId())
            && binding.domainSkillId().equals(skill.skill().descriptor().id())
            && binding.contractId().equals(requirement.contractId())).toList();
    }

    @Override public boolean supports(SkillDataRequirement requirement, SkillResolution skill, SkillRoleContext identity) {
        return !bindings(requirement, skill, identity).isEmpty();
    }

    @Override public SkillDataResult acquire(SkillDataRequirement requirement, SkillResolution skill,
                                            SkillRoleContext identity, Map<String, Object> parameters) {
        var matches = bindings(requirement, skill, identity);
        if (matches.size() != 1) return unavailable(requirement, matches.isEmpty()
            ? SkillDataResult.Status.NO_BINDING : SkillDataResult.Status.AMBIGUOUS_BINDING, "Data binding is not unique");
        var binding = matches.get(0);
        if (!skill.resolved() || identity.tenantId().isBlank() || identity.userId().isBlank()
            || !skill.authorizedScope().workflowIds().contains(binding.workflowId()))
            return unavailable(requirement, SkillDataResult.Status.DENIED, "Data workflow is not authorized");
        var executionScope = scopes.resolve(identity.tenantId(), identity.userId(), binding.executionSkillId(),
            List.of(), List.of());
        if (!executionScope.skillAllowed())
            return unavailable(requirement, SkillDataResult.Status.DENIED, "Execution Skill is not authorized");
        Map<String, Object> templateParameters = new LinkedHashMap<>();
        for (var entry : binding.parameterBindings().entrySet()) {
            Object value = parameters.get(entry.getValue());
            if (!(value instanceof String || value instanceof Number || value instanceof Boolean))
                return unavailable(requirement, SkillDataResult.Status.MISSING_INPUT, "Template requires a scalar business parameter");
            templateParameters.put(entry.getKey(), value);
        }
        String requestId = UUID.randomUUID().toString();
        var kernel = new KernelDataScope(identity.tenantId(), identity.userId(), requestId, null, requestId, null, Map.of());
        var context = new AnalysisContext("Acquire " + requirement.contractId(), kernel, binding.executionSkillId(),
            List.of(), List.of(), executionScope.roles(), null, Map.of(
                PreauthorizedStructuredDataOperator.TEMPLATE_ID, binding.templateId(),
                PreauthorizedStructuredDataOperator.ASSET_NAME, binding.assetName(),
                PreauthorizedStructuredDataOperator.ENVIRONMENT, binding.environment(),
                PreauthorizedStructuredDataOperator.PARAMETERS, templateParameters));
        var result = data.execute(context, new AnalysisScope(identity.tenantId(), identity.userId(),
            executionScope.roles(), List.of(), Map.of()), null);
        if (result.evidence().size() != 1)
            return unavailable(requirement, SkillDataResult.Status.FAILED, "Fixed acquisition workflow returned no structured dataset");
        var evidence = result.evidence().get(0);
        try {
            var payload = mapper.readTree(evidence.content());
            var sourceRows = payload.path("data").path("rows");
            if (!sourceRows.isArray() || sourceRows.size() > 100)
                return unavailable(requirement, SkillDataResult.Status.INVALID_DATA, "Expected bounded structured rows");
            List<Map<String, Object>> rows = new ArrayList<>();
            for (var source : sourceRows) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (var field : binding.fields().entrySet()) {
                    if (!source.hasNonNull(field.getValue()) || !source.get(field.getValue()).isValueNode())
                        return unavailable(requirement, SkillDataResult.Status.INVALID_DATA, "A required canonical field is missing or non-scalar");
                    row.put(field.getKey(), mapper.convertValue(source.get(field.getValue()), Object.class));
                }
                rows.add(row);
            }
            Map<String, Object> provenance = new LinkedHashMap<>();
            provenance.put("workflowId", binding.workflowId());
            provenance.put("workflowVersion", binding.version());
            provenance.put("templateId", binding.templateId());
            provenance.put("assetName", binding.assetName());
            provenance.put("environment", binding.environment());
            provenance.put("executionSkillId", binding.executionSkillId());
            provenance.put("evidenceId", evidence.evidenceId());
            provenance.put("requestId", requestId);
            provenance.put("parameters", parameters);
            provenance.put("semantics", binding.semantics());
            return new SkillDataResult(requirement, rows.isEmpty() ? SkillDataResult.Status.EMPTY
                : SkillDataResult.Status.AVAILABLE, rows, provenance, List.of());
        } catch (java.io.IOException invalid) {
            return unavailable(requirement, SkillDataResult.Status.INVALID_DATA, "Structured data is not valid JSON");
        }
    }

    private SkillDataResult unavailable(SkillDataRequirement requirement, SkillDataResult.Status status, String message) {
        return SkillDataResult.unavailable(requirement, status, message);
    }
}
