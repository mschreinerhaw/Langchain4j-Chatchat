package com.chatchat.agents.orchestration.workflow;

import com.chatchat.agents.orchestration.tool.AgentToolNameResolver;

import com.chatchat.common.interaction.InteractionToolTrace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves mandatory workflow tools and document-web verification progress.
 */
public class AgentWorkflowToolResolver {

    private final AgentToolNameResolver toolNames;

    public AgentWorkflowToolResolver(AgentToolNameResolver toolNames) {
        this.toolNames = toolNames;
    }

    public boolean shouldRequireDocumentWebVerification(List<String> tools,
                                                 String documentSearchTool,
                                                 String verificationWebSearchTool,
                                                 List<String> documentIds,
                                                 List<String> documentTags) {
        return tools != null
            && documentSearchTool != null
            && tools.contains(documentSearchTool)
            && verificationWebSearchTool != null
            && (!documentIds.isEmpty() || !documentTags.isEmpty());
    }

    public boolean missingDocumentWebVerification(List<InteractionToolTrace> traces,
                                           String documentSearchTool,
                                           String verificationWebSearchTool) {
        return !hasToolTrace(traces, documentSearchTool) || !hasToolTrace(traces, verificationWebSearchTool);
    }

    public boolean missingDocumentWebVerification(Set<String> completedTools,
                                           String documentSearchTool,
                                           String verificationWebSearchTool) {
        return !hasCompletedTool(completedTools, documentSearchTool) || !hasCompletedTool(completedTools, verificationWebSearchTool);
    }

    public boolean hasToolTrace(List<InteractionToolTrace> traces, String toolName) {
        if (traces == null || traces.isEmpty() || toolName == null || toolName.isBlank()) {
            return false;
        }
        return traces.stream()
            .anyMatch(trace -> trace != null
                && !confirmationRequired(trace)
                && toolNames.sameToolName(toolName, trace.getToolName()));
    }

    public boolean hasAnyToolTrace(List<InteractionToolTrace> traces, String toolName) {
        if (traces == null || traces.isEmpty() || toolName == null || toolName.isBlank()) {
            return false;
        }
        return traces.stream()
            .anyMatch(trace -> trace != null && toolNames.sameToolName(toolName, trace.getToolName()));
    }

    public List<String> missingMandatoryTools(List<String> mandatoryTools, List<InteractionToolTrace> traces) {
        return normalizeList(mandatoryTools).stream()
            .filter(toolName -> !hasToolTrace(traces, toolName))
            .toList();
    }

    public List<String> missingMandatoryTools(List<String> mandatoryTools, Set<String> completedTools) {
        return normalizeList(mandatoryTools).stream()
            .filter(toolName -> !hasCompletedTool(completedTools, toolName))
            .toList();
    }

    public String nextMandatoryTool(List<String> mandatoryTools, List<InteractionToolTrace> traces) {
        List<String> missing = missingMandatoryTools(mandatoryTools, traces);
        return missing.isEmpty() ? null : missing.get(0);
    }

    public String nextMandatoryTool(List<String> mandatoryTools, Set<String> completedTools) {
        List<String> missing = missingMandatoryTools(mandatoryTools, completedTools);
        return missing.isEmpty() ? null : missing.get(0);
    }

    @SuppressWarnings("unchecked")
    public void recordWorkflowOverride(Map<String, Object> metadata,
                                String plannedTool,
                                String enforcedTool,
                                String reason) {
        if (metadata == null || enforcedTool == null || enforcedTool.isBlank()) {
            return;
        }
        List<Map<String, Object>> overrides = metadata.get("workflowToolOverrides") instanceof List<?> existing
            ? (List<Map<String, Object>>) existing
            : new ArrayList<>();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("plannedTool", plannedTool);
        item.put("enforcedTool", enforcedTool);
        item.put("reason", reason);
        overrides.add(item);
        metadata.put("workflowToolOverrides", overrides);
    }

    public List<String> resolveMandatoryToolCandidates(List<String> tools,
                                                List<String> requiredToolNames) {
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }
        List<String> requiredTools = normalizeList(requiredToolNames).stream()
            .map(toolName -> toolNames.normalizeToolName(toolName, tools))
            .filter(tools::contains)
            .distinct()
            .toList();
        if (!requiredTools.isEmpty()) {
            return requiredTools;
        }
        return List.of();
    }

    public List<String> withDocumentWebVerificationMandatoryTools(List<String> mandatoryTools,
                                                           String documentSearchTool,
                                                           String verificationWebSearchTool) {
        LinkedHashMap<String, Boolean> ordered = new LinkedHashMap<>();
        if (documentSearchTool != null && !documentSearchTool.isBlank()) {
            ordered.put(documentSearchTool, Boolean.TRUE);
        }
        if (verificationWebSearchTool != null && !verificationWebSearchTool.isBlank()) {
            ordered.put(verificationWebSearchTool, Boolean.TRUE);
        }
        normalizeList(mandatoryTools).forEach(toolName -> ordered.put(toolName, Boolean.TRUE));
        return new ArrayList<>(ordered.keySet());
    }

    private List<String> normalizeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
            .filter(value -> value != null && !value.isBlank())
            .map(String::trim)
            .distinct()
            .toList();
    }

    private boolean hasCompletedTool(Set<String> completedTools, String toolName) {
        if (completedTools == null || completedTools.isEmpty() || toolName == null || toolName.isBlank()) {
            return false;
        }
        Set<String> normalized = new LinkedHashSet<>(completedTools);
        return normalized.stream().anyMatch(completed -> toolNames.sameToolName(toolName, completed));
    }

    private boolean confirmationRequired(InteractionToolTrace trace) {
        if (trace == null || trace.getRuntimeMetadata() == null) {
            return false;
        }
        Object outcome = trace.getRuntimeMetadata().get("outcome");
        return "confirmation_required".equalsIgnoreCase(String.valueOf(outcome));
    }
}
