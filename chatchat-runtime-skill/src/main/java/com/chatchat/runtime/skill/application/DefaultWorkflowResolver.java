package com.chatchat.runtime.skill.application;

import com.chatchat.runtime.skill.api.resolution.AuthorizedSkillScope;
import com.chatchat.runtime.skill.api.skill.ResolvedSkill;
import com.chatchat.runtime.skill.api.workflow.ResolvedWorkflow;
import com.chatchat.runtime.skill.api.identity.SkillRoleContext;
import com.chatchat.runtime.skill.api.workflow.WorkflowResolution;
import com.chatchat.runtime.skill.api.workflow.WorkflowType;
import com.chatchat.runtime.skill.port.inbound.WorkflowResolver;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Deterministic workflow selection. A model is never allowed to invent or authorize a workflow. */
public final class DefaultWorkflowResolver implements WorkflowResolver {
    @Override
    public WorkflowResolution resolve(ResolvedSkill skill, AuthorizedSkillScope scope,
                                      SkillRoleContext roleContext, Map<String, Object> intent) {
        if (skill == null || scope == null || !scope.skillAllowed())
            return unresolved("SKILL_NOT_AUTHORIZED", 0);
        List<String> authorized = scope.workflowIds().stream().sorted().toList();
        String requested = text(first(intent, "workflowId", "workflow_id"));
        var requirements = skill.requirements();
        if (intent != null && Boolean.TRUE.equals(intent.get("allowInstructionOnly")) && workflowType(intent) == WorkflowType.DATA_ANALYSIS
            && (requested.isBlank() || "builtin:skill-instructions".equals(requested))
            && requirements.data().isEmpty() && requirements.documentIds().isEmpty() && requirements.knowledgeBaseIds().isEmpty()
            && requirements.mcpToolIds().isEmpty() && requirements.agentIds().isEmpty() && requirements.workflowIds().isEmpty()) {
            return new WorkflowResolution(new ResolvedWorkflow("builtin:skill-instructions", WorkflowType.DATA_ANALYSIS,
                List.of(), Map.of("instructionOnly", true)), "RESOLVED", Map.of("selection", "INSTRUCTION_ONLY"));
        }
        if (!requested.isBlank() && !authorized.contains(requested))
            return unresolved("WORKFLOW_NOT_AUTHORIZED", authorized.size());
        String workflowId;
        if (!requested.isBlank()) workflowId = requested;
        else if (authorized.size() == 1) workflowId = authorized.get(0);
        else return unresolved(authorized.isEmpty() ? "NO_AUTHORIZED_WORKFLOW" : "WORKFLOW_SELECTION_REQUIRED",
            authorized.size());

        WorkflowType type = workflowType(intent);
        if (type == null) return unresolved("WORKFLOW_TYPE_REQUIRED", authorized.size());
        List<String> capabilities = new ArrayList<>();
        scope.mcpToolIds().forEach(id -> capabilities.add("mcp:" + id));
        scope.agentIds().forEach(id -> capabilities.add("agent:" + id));
        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("documentIds", scope.documentIds());
        configuration.put("knowledgeBaseIds", scope.knowledgeBaseIds());
        configuration.put("mcpToolIds", scope.mcpToolIds());
        configuration.put("agentIds", scope.agentIds());
        configuration.put("skillId", skill.descriptor().id());
        return new WorkflowResolution(new ResolvedWorkflow(workflowId, type, capabilities, configuration),
            "RESOLVED", Map.of("selection", requested.isBlank() ? "SINGLE_AUTHORIZED" : "EXPLICIT_AUTHORIZED"));
    }

    private WorkflowType workflowType(Map<String, Object> intent) {
        String declared = text(first(intent, "workflowType", "workflow_type"));
        if (declared.isBlank()) return null;
        try { return WorkflowType.valueOf(declared.toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    private WorkflowResolution unresolved(String status, int count) {
        return new WorkflowResolution(null, status, Map.of("authorizedWorkflowCount", count));
    }

    private Object first(Map<String, Object> values, String... keys) {
        if (values == null) return null;
        for (String key : keys) if (values.containsKey(key)) return values.get(key);
        return null;
    }

    private String text(Object value) { return value == null ? "" : value.toString().trim(); }
}
