package com.chatchat.runtime.skill.api.skill;

import java.util.List;

/** Resource requirements declared by a Skill; declarations never grant access. */
public record SkillRequirements(List<String> documentIds, List<String> knowledgeBaseIds,
                                List<String> mcpToolIds, List<String> agentIds,
                                List<String> workflowIds, List<SkillDataRequirement> data, List<SkillAnalysisStep> steps) {
    public SkillRequirements {
        documentIds = immutable(documentIds);
        knowledgeBaseIds = immutable(knowledgeBaseIds);
        mcpToolIds = immutable(mcpToolIds);
        agentIds = immutable(agentIds);
        workflowIds = immutable(workflowIds);
        data = data == null ? List.of() : List.copyOf(data);
        if (data.size() > 8 || data.stream().map(SkillDataRequirement::id).distinct().count() != data.size())
            throw new IllegalArgumentException("Declare at most eight data requirements with unique ids");
        steps = steps == null ? List.of() : List.copyOf(steps);
        validateSteps(data, steps);
    }

    public SkillRequirements(List<String> documents, List<String> knowledgeBases, List<String> tools,
                             List<String> agents, List<String> workflows, List<SkillDataRequirement> data) {
        this(documents, knowledgeBases, tools, agents, workflows, data, List.of());
    }

    private static void validateSteps(List<SkillDataRequirement> data, List<SkillAnalysisStep> steps) {
        if (steps.size() > 24 || steps.stream().map(SkillAnalysisStep::id).distinct().count() != steps.size())
            throw new IllegalArgumentException("Declare at most 24 uniquely named analysis steps");
        var ids = steps.stream().map(SkillAnalysisStep::id).collect(java.util.stream.Collectors.toSet());
        var datasets = data.stream().map(SkillDataRequirement::id).collect(java.util.stream.Collectors.toSet());
        for (var step : steps) {
            if (!datasets.contains(step.datasetId()) || !ids.containsAll(step.dependsOn()))
                throw new IllegalArgumentException("Analysis step references an unknown dataset or dependency");
        }
        if (!steps.isEmpty() && data.stream().anyMatch(item -> !ids.containsAll(item.requiredFor())))
            throw new IllegalArgumentException("requiredFor references an unknown analysis step");
        var remaining = new java.util.ArrayList<>(steps);
        var completed = new java.util.HashSet<String>();
        while (!remaining.isEmpty()) {
            var ready = remaining.stream().filter(step -> completed.containsAll(step.dependsOn())).toList();
            if (ready.isEmpty()) throw new IllegalArgumentException("Analysis dependencies contain a cycle");
            ready.forEach(step -> completed.add(step.id()));
            remaining.removeAll(ready);
        }
    }

    public SkillRequirements(List<String> documentIds, List<String> knowledgeBaseIds,
                             List<String> mcpToolIds, List<String> agentIds, List<String> workflowIds) {
        this(documentIds, knowledgeBaseIds, mcpToolIds, agentIds, workflowIds, List.of());
    }

    public static SkillRequirements empty() {
        return new SkillRequirements(List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static List<String> immutable(List<String> values) {
        return values == null ? List.of() : values.stream().filter(value -> value != null && !value.isBlank())
            .map(String::trim).distinct().toList();
    }
}
