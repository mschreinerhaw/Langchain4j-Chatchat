package com.chatchat.agents.runtime.plan;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolWorkflowRole;
import java.util.List;
import java.util.Map;

/** Discovery-only workflows explain a question; retrieval enriches their answer.
 * A workflow containing data acquisition or an unknown capability stays strict. */
public final class DiscoveryEvidencePolicy {
    public static final String ATTRIBUTE = "discoveryEvidenceSupplemental";
    private DiscoveryEvidencePolicy() {}

    public static boolean supplemental(InterpretationPlan plan, List<String> tools,
                                      Map<String, Object> attributes, ToolRegistry registry) {
        if (plan == null || registry == null || tools == null || tools.isEmpty()) return false;
        Object family = attributes == null ? null : attributes.get("workflowFamily");
        if (family != null && !"ASSET_GUIDANCE".equals(family.toString())) return false;
        if (!tools.stream().allMatch(name -> discovery(registry.getWorkflowRole(name)))) return false;
        boolean lookup = false;
        for (var step : plan.steps()) {
            if ("final_answer".equalsIgnoreCase(step.actionType())) continue;
            if (!step.mcpToolAction() || !discovery(registry.getWorkflowRole(step.toolName()))) return false;
            lookup = true;
        }
        return lookup;
    }

    public static boolean discovery(ToolWorkflowRole role) {
        return role == ToolWorkflowRole.ASSET_DISCOVERY || role == ToolWorkflowRole.TEMPLATE_DISCOVERY;
    }

    public static boolean enabled(Map<String, Object> attributes) {
        return attributes != null && Boolean.TRUE.equals(attributes.get(ATTRIBUTE));
    }

    public static com.chatchat.agents.assessment.TaskContract supplementalContract(
            com.chatchat.agents.assessment.TaskContract contract) {
        return new com.chatchat.agents.assessment.TaskContract(contract.contractVersion(),
            contract.taskType(), contract.userGoal(),
            com.chatchat.agents.assessment.TaskContract.EvidenceRequirement.OPTIONAL,
            contract.allowAssumptions(), contract.answerMode(), contract.mandatoryTools(),
            contract.evidenceItems().stream().map(item -> new com.chatchat.agents.assessment.TaskContract.EvidenceItem(
                item.id(), item.sourceStepId(), item.sourceTool(),
                com.chatchat.agents.assessment.TaskContract.EvidenceImportance.OPTIONAL)).toList());
    }

    public static final String SYNTHESIS_INSTRUCTION = """
        This workflow explains the user's question using its existing interpretation plan and Skill context.
        Asset and template retrieval are supplemental business context, not a prerequisite for answering.
        Summarize the analysis scope, methodology and applicable scenarios directly even when no matching
        template was found. Do not request another search, pagination or a rewritten plan to fill optional gaps.
        Use only admitted candidates for claims about specific published assets. Rejected candidates are not
        matching assets. Distinguish general domain explanation from retrieved facts; never invent business
        records, identifiers, measurements, executions or verified capabilities. A directory entry alone does not
        establish production availability, data frequency, reliability, quality or coverage. Mention missing supplementary
        context briefly only where it limits an asset-specific claim. Do not replace the answer with tool diagnostics.
        """;
}
