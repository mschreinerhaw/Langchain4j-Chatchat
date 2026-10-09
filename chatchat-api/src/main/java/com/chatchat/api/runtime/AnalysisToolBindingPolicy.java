package com.chatchat.api.runtime;

import com.chatchat.chat.skills.model.SkillDefinition;
import com.chatchat.common.mcp.catalog.McpToolCatalogQueryPort;

/** Explicit publication bindings, shared by execution and historical observation access. */
final class AnalysisToolBindingPolicy {
    private AnalysisToolBindingPolicy() {}
    static boolean bound(SkillDefinition skill, String name, McpToolCatalogQueryPort catalog) {
        if (skill.toolConfigs() != null && skill.toolConfigs().stream().anyMatch(config -> config != null
            && name.equals(config.toolName()) && Boolean.FALSE.equals(config.enabled()))) return false;
        if (skill.boundMcpToolNames() != null && skill.boundMcpToolNames().contains(name)) return true;
        if (skill.toolConfigs() != null && skill.toolConfigs().stream().anyMatch(config -> config != null
            && name.equals(config.toolName()) && Boolean.TRUE.equals(config.enabled()))) return true;
        return skill.boundMcpServiceIds() != null && !skill.boundMcpServiceIds().isEmpty()
            && catalog.registeredTools().stream().anyMatch(tool -> name.equals(tool.localToolName())
                && skill.boundMcpServiceIds().contains(tool.serviceId()));
    }
}
