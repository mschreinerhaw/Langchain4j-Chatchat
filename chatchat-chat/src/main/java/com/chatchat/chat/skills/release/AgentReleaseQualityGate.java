package com.chatchat.chat.skills.release;

import com.chatchat.chat.skills.SkillDefinition;
import com.chatchat.chat.skills.SkillToolConfig;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Deterministic, fail-closed checks that every immutable release must pass. */
@Component
public class AgentReleaseQualityGate {

    public AgentReleaseQualityReport evaluate(SkillDefinition skill) {
        List<AgentReleaseQualityReport.Check> checks = new ArrayList<>();
        checks.add(check("identity", hasText(skill == null ? null : skill.id())
            && hasText(skill == null ? null : skill.label()), "Agent identity and label are required"));
        checks.add(check("execution_contract", skill != null && hasText(skill.defaultMode())
            && (hasText(skill.systemPrompt()) || hasText(skill.description())),
            "Execution mode and behavioral instructions are required"));
        checks.add(check("resource_bindings", validBindings(skill),
            "Tool and knowledge bindings must be non-blank and unambiguous"));
        checks.add(check("routing_contract", skill != null && skill.workflowConfig() != null,
            "Workflow configuration must be materialized"));
        int passed = (int) checks.stream().filter(AgentReleaseQualityReport.Check::passed).count();
        return new AgentReleaseQualityReport(AgentReleaseQualityReport.CONTRACT_VERSION,
            passed == checks.size(), checks.size(), passed, checks);
    }

    private boolean validBindings(SkillDefinition skill) {
        if (skill == null) return false;
        List<String> values = new ArrayList<>();
        add(values, skill.boundMcpServiceIds());
        add(values, skill.boundMcpToolNames());
        add(values, skill.boundDocumentIds());
        add(values, skill.boundDocumentTags());
        if (values.stream().anyMatch(value -> !hasText(value))) return false;
        Set<String> distinct = new HashSet<>(values);
        if (distinct.size() != values.size()) return false;
        if (skill.toolConfigs() != null) {
            for (SkillToolConfig tool : skill.toolConfigs()) {
                if (tool == null || !hasText(tool.toolName())) return false;
            }
        }
        return true;
    }

    private void add(List<String> target, List<String> source) {
        if (source != null) target.addAll(source);
    }

    private AgentReleaseQualityReport.Check check(String id, boolean passed, String message) {
        return new AgentReleaseQualityReport.Check(id, passed, passed ? "PASS" : message);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
