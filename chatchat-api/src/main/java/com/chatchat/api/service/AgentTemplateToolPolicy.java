package com.chatchat.api.service;

import com.chatchat.chat.skills.catalog.SkillCatalogService;
import com.chatchat.common.retrieval.ResourceAuthorizationPort;
import com.chatchat.common.tool.TemplateToolNames;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;

/** Actual Agent bindings intersect the current user's central grants on every request. */
@Service @RequiredArgsConstructor
public class AgentTemplateToolPolicy {
    private final SkillCatalogService agents;
    private final EnterpriseAdminService identities;
    private final ResourceAuthorizationPort permissions;

    public Set<String> allowed(String tenant, String user, String agentId, Set<String> candidates) {
        if (agentId == null || agentId.isBlank()) return Set.of();
        var principal = identities.getUserView(user);
        if (!tenant.equals(principal.tenantId()) || !"enabled".equalsIgnoreCase(principal.status())
            || !(identities.hasAllAgentAccess(user) || identities.canAccessAgent(user, agentId))) return Set.of();
        var agent = agents.resolve(agentId);
        if (!agentId.equals(agent.id()) || !"published".equalsIgnoreCase(agent.marketStatus())) return Set.of();
        Set<String> bound = new HashSet<>();
        if (agent.boundMcpToolNames() != null) agent.boundMcpToolNames().stream().map(TemplateToolNames::remote).filter(Objects::nonNull).forEach(bound::add);
        if (agent.toolConfigs() != null) {
            agent.toolConfigs().stream().filter(config -> Boolean.TRUE.equals(config.enabled())).map(config -> TemplateToolNames.remote(config.toolName())).filter(Objects::nonNull).forEach(bound::add);
            agent.toolConfigs().stream().filter(config -> Boolean.FALSE.equals(config.enabled())).map(config -> TemplateToolNames.remote(config.toolName())).filter(Objects::nonNull).forEach(bound::remove);
        }
        Set<String> names = candidates.stream().filter(name -> TemplateToolNames.remote(name) != null).map(TemplateToolNames::local).collect(java.util.stream.Collectors.toSet());
        var allowed = permissions.allowedIdsForAgent(ResourceAuthorizationPort.MCP_TOOL, tenant, user, Set.of(), names, agentId);
        return candidates.stream().filter(name -> bound.contains(TemplateToolNames.remote(name)) && allowed.contains(TemplateToolNames.local(name)))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
